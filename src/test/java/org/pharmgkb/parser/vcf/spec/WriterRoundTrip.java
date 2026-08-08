package org.pharmgkb.parser.vcf.spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pharmgkb.parser.vcf.VcfLineParser;
import org.pharmgkb.parser.vcf.VcfMetadata;
import org.pharmgkb.parser.vcf.VcfParser;
import org.pharmgkb.parser.vcf.VcfPosition;
import org.pharmgkb.parser.vcf.VcfSample;
import org.pharmgkb.parser.vcf.VcfWriter;


/**
 * Parses a VCF fixture or text, writes it back out with {@link VcfWriter}, and compares the result against the
 * original -- both semantically (re-parsed field-by-field equality) and as raw text (line-by-line, ignoring
 * line-ending style).
 */
final class WriterRoundTrip {

  private WriterRoundTrip() {}

  record ParsedFile(VcfMetadata metadata, List<VcfPosition> positions, List<List<VcfSample>> samples) {}

  private static final class CollectingLineParser implements VcfLineParser {
    private VcfMetadata m_metadata;
    private final List<VcfPosition> m_positions = new ArrayList<>();
    private final List<List<VcfSample>> m_samples = new ArrayList<>();

    @Override
    public void parseMetadata(VcfMetadata metadata) {
      m_metadata = metadata;
    }

    @Override
    public void parseLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {
      m_positions.add(position);
      m_samples.add(sampleData);
    }

    ParsedFile toParsedFile() {
      return new ParsedFile(m_metadata, m_positions, m_samples);
    }
  }

  static ParsedFile parseFile(Path file) throws IOException {
    CollectingLineParser collector = new CollectingLineParser();
    try (VcfParser parser = new VcfParser.Builder().fromFile(file).parseWith(collector).build()) {
      parser.parse();
    }
    return collector.toParsedFile();
  }

  static ParsedFile parseText(String text) throws IOException {
    CollectingLineParser collector = new CollectingLineParser();
    try (BufferedReader reader = new BufferedReader(new StringReader(text));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith(collector).build()) {
      parser.parse();
    }
    return collector.toParsedFile();
  }

  static String write(ParsedFile parsed) throws IOException {
    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
      writer.writeHeader(parsed.metadata());
      for (int i = 0; i < parsed.positions().size(); i++) {
        writer.writeLine(parsed.metadata(), parsed.positions().get(i), parsed.samples().get(i));
      }
    }
    return sw.toString();
  }

  /**
   * @return empty if "before" and "after" match on every compared field for every record, or a short description
   * of the first mismatch found otherwise.
   */
  static Optional<String> semanticDiff(ParsedFile before, ParsedFile after) {
    if (before.positions().size() != after.positions().size()) {
      return Optional.of("record count differs: " + before.positions().size() + " before, " +
          after.positions().size() + " after");
    }
    for (int i = 0; i < before.positions().size(); i++) {
      VcfPosition b = before.positions().get(i);
      VcfPosition a = after.positions().get(i);
      Optional<String> diff = comparePosition(i, b, a);
      if (diff.isPresent()) {
        return diff;
      }
      List<VcfSample> bSamples = before.samples().get(i);
      List<VcfSample> aSamples = after.samples().get(i);
      if (bSamples.size() != aSamples.size()) {
        return Optional.of("record " + i + ": sample count differs: " + bSamples.size() + " before, " +
            aSamples.size() + " after");
      }
      for (int s = 0; s < bSamples.size(); s++) {
        for (String key : b.getFormat()) {
          String bValue = bSamples.get(s).getProperty(key);
          String aValue = aSamples.get(s).getProperty(key);
          if (!Objects.equals(bValue, aValue)) {
            return Optional.of("record " + i + ", sample " + s + ", key " + key + ": \"" + bValue +
                "\" before, \"" + aValue + "\" after");
          }
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<String> comparePosition(int index, VcfPosition b, VcfPosition a) {
    if (!b.getChromosome().equals(a.getChromosome())) {
      return Optional.of("record " + index + ": CHROM \"" + b.getChromosome() + "\" before, \"" +
          a.getChromosome() + "\" after");
    }
    if (b.getPosition() != a.getPosition()) {
      return Optional.of("record " + index + ": POS " + b.getPosition() + " before, " + a.getPosition() + " after");
    }
    if (!b.getIds().equals(a.getIds())) {
      return Optional.of("record " + index + ": ID " + b.getIds() + " before, " + a.getIds() + " after");
    }
    if (!b.getRef().equals(a.getRef())) {
      return Optional.of("record " + index + ": REF \"" + b.getRef() + "\" before, \"" + a.getRef() + "\" after");
    }
    if (!b.getAltBases().equals(a.getAltBases())) {
      return Optional.of("record " + index + ": ALT " + b.getAltBases() + " before, " + a.getAltBases() + " after");
    }
    if (!Objects.equals(b.getQuality(), a.getQuality())) {
      return Optional.of("record " + index + ": QUAL " + b.getQuality() + " before, " + a.getQuality() + " after");
    }
    if (b.getFilterStatus() != a.getFilterStatus() || !b.getFilters().equals(a.getFilters())) {
      return Optional.of("record " + index + ": FILTER status " + b.getFilterStatus() + "/" + b.getFilters() +
          " before, " + a.getFilterStatus() + "/" + a.getFilters() + " after");
    }
    if (!b.getInfo().equals(a.getInfo())) {
      return Optional.of("record " + index + ": INFO " + b.getInfo() + " before, " + a.getInfo() + " after");
    }
    if (!b.getFormat().equals(a.getFormat())) {
      return Optional.of("record " + index + ": FORMAT " + b.getFormat() + " before, " + a.getFormat() + " after");
    }
    return Optional.empty();
  }

  /**
   * @return empty if the two texts are identical after normalizing line endings ({@code \r\n} and {@code \r} both
   * treated as {@code \n}), or a short description of how many lines differ and the first one otherwise.
   */
  static Optional<String> byteDiff(String original, String written) {
    List<String> originalLines = normalizeLines(original);
    List<String> writtenLines = normalizeLines(written);
    int max = Math.max(originalLines.size(), writtenLines.size());
    int differing = 0;
    String firstDiff = null;
    for (int i = 0; i < max; i++) {
      String o = i < originalLines.size() ? originalLines.get(i) : null;
      String w = i < writtenLines.size() ? writtenLines.get(i) : null;
      if (!Objects.equals(o, w)) {
        differing++;
        if (firstDiff == null) {
          firstDiff = "line " + (i + 1) + ": \"" + o + "\" original, \"" + w + "\" written";
        }
      }
    }
    if (differing == 0) {
      return Optional.empty();
    }
    return Optional.of(differing + " of " + max + " lines differ, first difference at " + firstDiff);
  }

  private static List<String> normalizeLines(String text) {
    return List.of(text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));
  }
}

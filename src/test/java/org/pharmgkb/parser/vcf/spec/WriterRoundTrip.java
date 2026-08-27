package org.pharmgkb.parser.vcf.spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.BaseMetadata;
import org.pharmgkb.parser.vcf.PedigreeMetadata;
import org.pharmgkb.parser.vcf.VcfLineParser;
import org.pharmgkb.parser.vcf.VcfMetadata;
import org.pharmgkb.parser.vcf.VcfParser;
import org.pharmgkb.parser.vcf.VcfPosition;
import org.pharmgkb.parser.vcf.VcfRecord;
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
    public void parseLine(VcfMetadata metadata, VcfRecord record) {
      m_positions.add(record.getPosition());
      m_samples.add(record.getSamples());
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
    return write(parsed, false);
  }

  /**
   * @param validateBeforeWrite if true, builds the {@link VcfWriter} with
   * {@link VcfWriter.Builder#validateBeforeWrite()} instead of the default writer.
   */
  static String write(ParsedFile parsed, boolean validateBeforeWrite) throws IOException {
    StringWriter sw = new StringWriter();
    VcfWriter.Builder builder = new VcfWriter.Builder().toWriter(new PrintWriter(sw));
    if (validateBeforeWrite) {
      builder.validateBeforeWrite();
    }
    try (VcfWriter writer = builder.build()) {
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
    Optional<String> metadataDiff = compareMetadata(before.metadata(), after.metadata());
    if (metadataDiff.isPresent()) {
      return metadataDiff;
    }
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
   * Compares every field {@link VcfMetadata} exposes -- fileformat, sample columns, all eight declaration
   * kinds, and raw generic properties. Found missing entirely by the Phase 3 writer-compliance review's
   * Path B kickoff (finding #7): {@link #semanticDiff} previously never referenced {@code before.metadata()}/
   * {@code after.metadata()} at all, so a metadata-level round-trip regression (e.g. a dropped declaration, a
   * corrupted sample name -- see finding #2) was invisible to every check built on this method.
   */
  private static Optional<String> compareMetadata(VcfMetadata b, VcfMetadata a) {
    if (!b.getFileFormat().equals(a.getFileFormat())) {
      return Optional.of("fileformat \"" + b.getFileFormat() + "\" before, \"" + a.getFileFormat() + "\" after");
    }
    if (b.getNumSamples() != a.getNumSamples()) {
      return Optional.of("sample column count " + b.getNumSamples() + " before, " + a.getNumSamples() + " after");
    }
    for (int i = 0; i < b.getNumSamples(); i++) {
      if (!b.getSampleName(i).equals(a.getSampleName(i))) {
        return Optional.of("sample column " + i + ": \"" + b.getSampleName(i) + "\" before, \"" +
            a.getSampleName(i) + "\" after");
      }
    }
    Optional<String> diff = compareDeclarations("ALT", b.getAlts(), a.getAlts());
    if (diff.isPresent()) {
      return diff;
    }
    diff = compareDeclarations("INFO metadata", b.getInfo(), a.getInfo());
    if (diff.isPresent()) {
      return diff;
    }
    diff = compareDeclarations("FILTER", b.getFilters(), a.getFilters());
    if (diff.isPresent()) {
      return diff;
    }
    diff = compareDeclarations("FORMAT metadata", b.getFormats(), a.getFormats());
    if (diff.isPresent()) {
      return diff;
    }
    diff = compareDeclarations("contig", b.getContigs(), a.getContigs());
    if (diff.isPresent()) {
      return diff;
    }
    diff = compareDeclarations("SAMPLE metadata", b.getSamples(), a.getSamples());
    if (diff.isPresent()) {
      return diff;
    }
    diff = compareDeclarations("META", b.getMetas(), a.getMetas());
    if (diff.isPresent()) {
      return diff;
    }
    diff = comparePedigrees(b.getPedigrees(), a.getPedigrees());
    if (diff.isPresent()) {
      return diff;
    }
    if (!b.getRawProperties().equals(a.getRawProperties())) {
      return Optional.of("raw generic properties " + b.getRawProperties() + " before, " + a.getRawProperties() +
          " after");
    }
    return Optional.empty();
  }

  /**
   * Compares one of {@link VcfMetadata}'s seven keyed declaration maps (ALT/INFO/FILTER/FORMAT/contig/
   * SAMPLE/META) by VALUE, not by {@link Map#equals}: none of {@link BaseMetadata}'s subclasses overrides
   * {@code equals}/{@code hashCode}, so {@code Map.equals} on the declaration objects themselves would compare
   * by reference identity and could never match two independently-parsed {@code VcfMetadata} instances, even
   * for byte-identical content -- comparing each declaration's own {@link BaseMetadata#getPropertiesRaw()}
   * instead compares the actual property content.
   */
  private static Optional<String> compareDeclarations(String label, Map<String, ? extends BaseMetadata> before,
      Map<String, ? extends BaseMetadata> after) {
    if (!before.keySet().equals(after.keySet())) {
      return Optional.of(label + " keys " + before.keySet() + " before, " + after.keySet() + " after");
    }
    for (String key : before.keySet()) {
      Map<String, String> bProps = before.get(key).getPropertiesRaw();
      Map<String, String> aProps = after.get(key).getPropertiesRaw();
      if (!bProps.equals(aProps)) {
        return Optional.of(label + " \"" + key + "\" " + bProps + " before, " + aProps + " after");
      }
    }
    return Optional.empty();
  }

  /** {@link #compareDeclarations}'s counterpart for {@code ##PEDIGREE}, which is stored as a {@link List}
   * (order-preserving, no unique key) rather than a {@link Map} -- compared by index instead of by key, for
   * the identical by-value reason {@code compareDeclarations} does. */
  private static Optional<String> comparePedigrees(List<PedigreeMetadata> before, List<PedigreeMetadata> after) {
    if (before.size() != after.size()) {
      return Optional.of("PEDIGREE count " + before.size() + " before, " + after.size() + " after");
    }
    for (int i = 0; i < before.size(); i++) {
      Map<String, String> bProps = before.get(i).getPropertiesRaw();
      Map<String, String> aProps = after.get(i).getPropertiesRaw();
      if (!bProps.equals(aProps)) {
        return Optional.of("PEDIGREE " + i + " " + bProps + " before, " + aProps + " after");
      }
    }
    return Optional.empty();
  }

  /**
   * Escapes a raw CR/LF/tab as its literal {@code \r}/{@code \n}/{@code \t} so a diagnostic message can't
   * embed a control character that would visually corrupt the console output the message is printed to --
   * mirrors {@code InternalUtils.truncateAndEscapeForMessage}'s own escaping (not its truncation, which
   * isn't needed here: a fixture line's length is already bounded by the fixture it came from). Duplicated
   * rather than reused because that method is package-private in {@code org.pharmgkb.parser.vcf}, a
   * different package from this one.
   */
  @Nullable
  private static String escapeForMessage(@Nullable String value) {
    return value == null ? null : value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }

  /**
   * @return empty if the two texts are byte-for-byte identical, or a short description of how many lines
   * differ and the first one otherwise. No line-ending normalization: {@link VcfWriter} always emits {@code \n},
   * never the platform separator, so a genuine divergence here (e.g. a stray {@code \r}) is a real defect,
   * not a cosmetic difference to tolerate.
   */
  static Optional<String> byteDiff(String original, String written) {
    List<String> originalLines = List.of(original.split("\n", -1));
    List<String> writtenLines = List.of(written.split("\n", -1));
    int max = Math.max(originalLines.size(), writtenLines.size());
    int differing = 0;
    String firstDiff = null;
    for (int i = 0; i < max; i++) {
      String o = i < originalLines.size() ? originalLines.get(i) : null;
      String w = i < writtenLines.size() ? writtenLines.get(i) : null;
      if (!Objects.equals(o, w)) {
        differing++;
        if (firstDiff == null) {
          firstDiff = "line " + (i + 1) + ": \"" + escapeForMessage(o) + "\" original, \"" +
              escapeForMessage(w) + "\" written";
        }
      }
    }
    if (differing == 0) {
      return Optional.empty();
    }
    return Optional.of(differing + " of " + max + " lines differ, first difference at " + firstDiff);
  }
}

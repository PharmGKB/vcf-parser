package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class parses a VCF file.
 *
 * @author Mark Woon
 */
public class VcfParser implements Closeable {
  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final char TAB = '\t';
  private static final char COMMA = ',';
  private static final char COLON = ':';
  private static final char SEMICOLON = ';';
  private static final String GLE = "GLE";
  // GLE is a VCF 4.1/4.2 FORMAT String whose own comma-separated genotype:likelihood pairs contain colons. The
  // first group consumes those pairs; the optional second group is the ordinary colon-separated remainder of the
  // sample field for FORMAT keys following GLE. Restrict each pair's genotype to allele indexes so a malformed,
  // arbitrary GLE string (which the parser deliberately preserves) is not mistaken for this special encoding.
  // The "*+" (possessive) quantifiers on the allele-separator and pair-repetition groups are required: Java's
  // regex engine recurses one Java call frame per repetition for a quantified group containing alternation, so a
  // GLE value with a long sequence of short repetitions (e.g. thousands of "|"-separated allele-index tokens)
  // would otherwise throw an uncaught StackOverflowError instead of failing to match cleanly. Safe to make
  // possessive: each repetition is unambiguously anchored by a required literal separator ("|"/"/" before an
  // allele, "," before a pair), so no legitimate match ever needs to backtrack across repetitions.
  private static final Pattern GLE_VALUE_PATTERN = Pattern.compile(
      "((?:(?:\\d+|\\.)(?:[|/](?:\\d+|\\.))*+:[^,:]+)(?:,(?:\\d+|\\.)(?:[|/](?:\\d+|\\.))*+:[^,:]+)*+)(?::(.*))?");
  // the mandatory fixed fields, in order; in the column-header line CHROM is written as "#CHROM"
  private static final List<String> FIXED_FIELD_NAMES =
      List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO");

  private final boolean m_rsidsOnly;
  private final BufferedReader m_reader;
  private @Nullable VcfMetadata m_vcfMetadata;
  private final VcfLineParser m_vcfLineParser;
  private final @Nullable Path m_warningsFile;

  private int m_lineNumber;
  private boolean m_alreadyFinished;
  private VcfWarnings m_warnings = VcfWarnings.DEFAULT;
  private @Nullable String m_lastChromosome;
  private long m_lastPosition;
  private final Set<String> m_closedChromosomes = new HashSet<>();
  // FORMAT-validation cache: real VCFs often repeat the same FORMAT column text across large blocks of
  // consecutive records. Holds only the single most
  // recently validated (raw text, validated list) pair -- not a growing map -- since the access pattern this
  // targets is contiguous repetition, not an arbitrary working set; this keeps the cache's memory bounded
  // regardless of how many distinct FORMAT strings a file contains, at the cost of one specific pathological
  // pattern: a file that strictly ALTERNATES between two (or more) FORMAT strings every record (e.g. GATK
  // HaplotypeCaller mixing phased and unphased FORMAT columns throughout one file) makes every single record a
  // cache miss, each paying a wasted String.equals check and one extra ArrayList allocation/copy on top of the
  // full validation that would have run anyway -- a small net regression versus no cache at all for that shape
  // of file, not just "not maximally fast." Still the right tradeoff for the common contiguous-block case this
  // was built for; a persistent multi-entry cache would recover the alternating case too but adds real
  // complexity (unbounded memory needs a bound, eviction policy, etc.) for a pattern assumed to be rare.
  // m_lastValidatedFormat is a private, never-externally-exposed copy -- see its use in parseNextLine() for why
  // a defensive copy is made on write, not just on read.
  private @Nullable String m_lastFormatRawText;
  private @Nullable List<String> m_lastValidatedFormat;



  private VcfParser(BufferedReader reader, boolean rsidsOnly, VcfLineParser lineParser,
      @Nullable Path warningsFile) {
    m_reader = reader;
    m_rsidsOnly = rsidsOnly;
    m_vcfLineParser = lineParser;
    m_warningsFile = warningsFile;
  }


  /**
   * Parses metadata only.
   * This method should be if only the metadata is needed; otherwise, {@link #parse()} is preferred.
   */
  public VcfMetadata parseMetadata() throws IOException {

    if (m_vcfMetadata != null) {
      throw new IllegalStateException("Metadata has already been parsed.");
    }
    VcfMetadata.Builder mdBuilder = new VcfMetadata.Builder().lenient(m_warnings);

    // the first physical line must be the single ##fileformat declaration
    String first = m_reader.readLine();
    if (first == null) {
      throw new VcfFormatException("Not a VCF file: the first line must be ##fileformat", -1);
    }
    m_lineNumber++;
    if (!first.startsWith("##fileformat=")) {
      throw new VcfFormatException("Not a VCF file: the first line must be ##fileformat", m_lineNumber);
    }
    parseMetadataLine(mdBuilder, first);

    String line;
    boolean foundHeader = false;
    while ((line = m_reader.readLine()) != null) {
      m_lineNumber++;
      if (line.startsWith("##")) {
        if (line.startsWith("##fileformat=")) {
          throw new VcfFormatException("Duplicate ##fileformat line", m_lineNumber);
        }
        parseMetadataLine(mdBuilder, line);
      } else if (line.startsWith("#")) {
        try {
          parseColumnInfo(mdBuilder, line);
        } catch (VcfFormatException ex) {
          ex.addMetadata(m_lineNumber, "column (# header)");
          throw ex;
        } catch (RuntimeException e) {
          throw new VcfFormatException(m_lineNumber, "column (# header)", e);
        }
        foundHeader = true;
        break;
      } else {
        throw new VcfFormatException("Unexpected line before the #CHROM header (only ## metadata lines are allowed here)",
            m_lineNumber);
      }
    }
    VcfMetadata metadata = mdBuilder.build();
    if (!foundHeader) {
      throw new VcfFormatException("No column header line (starting with #CHROM) was found before the end of the file",
          m_lineNumber);
    }
    m_vcfMetadata = metadata;

    // check sample lists
    if (m_vcfMetadata.getNumSamples() == m_vcfMetadata.getSamples().size()) {
      for (int i = 0; i < m_vcfMetadata.getNumSamples(); i++) {
        String sampleName = m_vcfMetadata.getSampleName(i);
        if (!m_vcfMetadata.getSamples().containsKey(sampleName)) {
          m_warnings.warn("Sample {} is missing in the metadata", sampleName);
        }
      }
    } else {
      m_warnings.warn("There are {} samples in the header but {} in the metadata", m_vcfMetadata.getNumSamples(),
          m_vcfMetadata.getSamples().size());
    }

    // deliver the metadata to the line parser once, before any data lines (this method runs at most once).
    // Mirrors parseLine()'s identical try/catch below: a VcfFormatException the callback throws on purpose (to
    // reject an already-parsed header) gets this line's number attached, the same as a parseLine() callback
    // rejection does -- previously it propagated completely raw, with no line number and no "Error parsing
    // metadata: " prefix, even though m_lineNumber here correctly holds the #CHROM header line's number. A
    // RuntimeException from the callback itself (a bug in the
    // caller's own code, not a VCF-content problem) still propagates with its original type, unwrapped.
    try {
      m_vcfLineParser.parseMetadata(m_vcfMetadata);
    } catch (VcfFormatException ex) {
      ex.addMetadata(m_lineNumber, "metadata");
      throw ex;
    }

    return m_vcfMetadata;
  }


  /**
   * Gets VCF metadata (if it has already been parsed).
   */
  public @Nullable VcfMetadata getMetadata() {
    return m_vcfMetadata;
  }


  /**
   * Parses the entire VCF file (including the metadata).
   * <p>
   * This is the preferred way to read a VCF file.
   */
  public void parse() throws IOException {
    if (m_warningsFile == null) {
      doParse();
      return;
    }
    m_warnings = VcfWarnings.openFile(m_warningsFile);
    try {
      doParse();
    } finally {
      m_warnings.close();
      m_warnings = VcfWarnings.DEFAULT;
    }
  }

  private void doParse() throws IOException {
    boolean hasNext = true;
    while (hasNext) {
      hasNext = parseNextLine();
    }
  }

  /**
   * Parses just the next data line available, also reading all the metadata if it has not been read.
   * This is a specialized method; in general calling {@link #parse()} to parse the entire stream is preferred.
   *
   * @return true if a line was read (it may have been skipped without being delivered to the {@link VcfLineParser}
   * callback, e.g. by {@link Builder#rsidsOnly()}); false once the end of the stream is reached -- this happens
   * only on the read attempt made *after* the last data line has already been returned, not on the call that
   * returns that last line itself
   * @throws IllegalStateException If the stream was already fully parsed
   * @throws VcfFormatException If a VCF formatting error is found
   */
  public boolean parseNextLine() throws IOException, VcfFormatException {

    if (m_alreadyFinished) {
      // prevents user errors from causing infinite loops
      throw new IllegalStateException("Already finished reading the stream");
    }

    if (m_vcfMetadata == null) {
      parseMetadata();
    }

    String line = m_reader.readLine();
    if (line == null) {
      m_alreadyFinished = true;
      return false;
    }
    m_lineNumber++;
    if (line.startsWith("#")) {
      // the VCF spec has no comment syntax: "#" is significant only for "##" metadata lines and the single column
      // header line, both of which are only valid before the first data line (and are already consumed by then)
      throw new VcfFormatException("Unexpected line starting with '#' after the column header; VCF has no comment "
          + "syntax", m_lineNumber);
    }

    String chromosome;
    long position;
    VcfPosition pos;
    List<VcfSample> samples;
    try {
      List<String> data = toList(TAB, line);
      // a line with no tab at all (data.size() == 1) and nothing but whitespace is genuinely empty -- report
      // that directly rather than a less-specific message below. A line that DOES split into the expected
      // number of tab-delimited columns (e.g. an all-tab, no-sample data row) is not "empty" in that sense --
      // let it fall through to the column-count and per-field checks below instead, which usually pinpoint the
      // specific empty field, though a line whose fields are non-empty whitespace (e.g. a lone space per field)
      // instead fails at whichever column-specific parse first rejects non-numeric/non-enum content (e.g. POS)
      if (data.size() == 1 && StringUtils.stripToNull(data.get(0)) == null) {
        throw new VcfFormatException("Empty line", m_lineNumber);
      }
      if (data.size() != m_vcfMetadata.getNumColumns()) {
        throw new VcfFormatException("Data line does not have expected number of columns (got " + data.size() +
            " vs. " + m_vcfMetadata.getNumColumns() + ")", m_lineNumber);
      }

      // every fixed field is mandatory; an empty field is invalid (the missing value must be ".") -- this check
      // (unlike everything below it) applies regardless of rsidsOnly(), since it's a precondition for reading
      // any field, including ID, at all: you can't know whether a line has an rsID without first being able to
      // read it as a well-formed record.
      for (int i = 0; i < FIXED_FIELD_NAMES.size(); i++) {
        if (data.get(i).isEmpty()) {
          throw new VcfFormatException(FIXED_FIELD_NAMES.get(i) + " field is empty; the missing value must be '.'",
              m_lineNumber);
        }
      }

      // ID -- checked here, before CHROM/POS/REF/ALT/FILTER/FORMAT are examined at all, so rsidsOnly() acts as
      // a true pre-filter: a discarded line is treated as though it were never in the file, the same as if it
      // had been removed by an external tool before this file was ever handed to VcfParser. Nothing below this
      // point -- CHROM/POS ordering, negative-POS, CHROM's character-set rules, REF/ALT syntax, FILTER/FORMAT
      // rules, even confirming POS parses as a number at all -- runs for a line this discards. See
      // rsidsOnly()'s own Javadoc for the full rationale, including why a VcfLineParser-based filter can't
      // achieve the same thing (it only sees a record after VcfPosition has already been fully constructed and
      // validated).
      List<String> ids = null;
      if (!data.get(2).equals(".")) {
        List<String> parsedIds = toList(SEMICOLON, data.get(2));
        if (m_rsidsOnly && parsedIds.stream().noneMatch(id -> InternalUtils.RSID_PATTERN.matcher(id).matches())) {
          return true;
        }
        ids = parsedIds;
      } else if (m_rsidsOnly) {
        return true;
      }

      // CHROM
      chromosome = data.get(0);

      // POS
      try {
        position = Long.parseLong(data.get(1));
      } catch (NumberFormatException e) {
        throw new VcfFormatException("POS '" + data.get(1) + "' is not a number");
      }

      // REF
      String ref = data.get(3);

      // ALT
      List<String> alt = null;
      if (!data.get(4).equals(".")) {
        alt = toList(COMMA, data.get(4));
      }

      // FILTER
      List<String> filters = toList(SEMICOLON, data.get(6));

      // FORMAT
      List<String> format = null;
      String rawFormat = null;
      boolean formatCacheHit = false;
      if (data.size() >= 9) {
        rawFormat = data.get(8);
        if (rawFormat.equals(m_lastFormatRawText)) {
          // a fresh copy is required, never the cached prototype itself: VcfPosition.getFormat() is documented
          // and tested as safe to mutate in place, and a caller doing so must never be able to corrupt a later
          // record's cache hit
          format = new ArrayList<>(m_lastValidatedFormat);
          formatCacheHit = true;
        } else {
          format = toList(COLON, rawFormat);
          if (format.size() == 1 && format.get(0).equals(".")) {
            // no per-sample format fields at all; see VcfPosition.checkFormat's javadoc for why this is valid
            // (matches bcftools 1.23.1 behavior, though VCFv4.2 through 4.5 are all silent on this exact case)
            format = new ArrayList<>();
          }
        }
      }
      boolean noFormatFields = format != null && format.isEmpty();

      // QUAL and INFO are parsed lazily by VcfPosition (see setRawQuality/setRawInfo); many consumers never read them.
      pos = formatCacheHit
          ? VcfPosition.newWithTrustedFormat(chromosome, position, ids, ref, alt, null, filters, null, format,
              m_lineNumber, m_warnings)
          : new VcfPosition(chromosome, position, ids, ref, alt, null, filters, null, format, m_lineNumber,
              m_warnings);
      if (!formatCacheHit && rawFormat != null) {
        // cache a defensive COPY of pos.getFormat() -- what checkFormat actually produced -- not the
        // pre-checkFormat `format` local: checkFormat is a normalizing check (like checkIds's own
        // dropEmptyEntries reshaping), so a future change to it could make `format` and pos.getFormat() diverge
        // in content, not just object identity. Caching pos.getFormat() directly keeps the invariant "the cache
        // always holds exactly what a real VcfPosition ended up with" true by construction, not by the current
        // coincidence that checkFormat happens to return its argument unchanged on every path VcfParser reaches.
        // Also never the list itself: pos.getFormat() may be mutated in place later (by this callback), and that
        // must never corrupt a later record's cache hit.
        m_lastFormatRawText = rawFormat;
        m_lastValidatedFormat = new ArrayList<>(pos.getFormat());
      }
      pos.setRawQuality(data.get(5));
      pos.setRawInfo(data.get(7));
      samples = new ArrayList<>();
      // VcfSample must never share the same List object as VcfPosition's own m_format: VcfPosition.getFormat() is
      // documented and tested as safe to mutate in place, but VcfSample's own parallel keys/values lists require
      // keys.size() == values.size() to hold, and are corrupted by an in-place mutation applied through a
      // shared reference. One copy per line (not per sample) is sufficient:
      // no VcfSample method ever mutates its own keys list in place.
      // Only skipped when format itself is null (no FORMAT column at all); deliberately not guarded for the
      // zero-sample-columns case (format non-null but the loop below never runs), since that copy's cost is
      // negligible (proportional to a typically-single-digit FORMAT key count) and guarding it would add more
      // complexity than the allocation it avoids is worth.
      List<String> sampleFormat = format == null ? null : new ArrayList<>(format);
      for (int x = 9; x < data.size(); x++) {
        List<String> values;
        if (noFormatFields) {
          if (!data.get(x).equals(".")) {
            m_warnings.warn("Sample column \"{}\" has content, but FORMAT is '.' (no format fields declared); " +
                "ignoring it, matching bcftools 1.23.1 behavior", data.get(x));
          }
          values = new ArrayList<>();
        } else {
          values = toSampleValues(format, data.get(x));
          InternalUtils.fillEmptyEntriesWithDot("sample value", values, m_warnings);
          // per the VCF spec, trailing FORMAT sub-fields may be dropped from a sample; pad any missing ones with the
          // missing value so the sample's value count matches the FORMAT key count
          if (format != null) {
            while (values.size() < format.size()) {
              values.add(".");
            }
          }
        }
        // sampleFormat/values are always produced above by splitting on ':'/tab and padding with ".", so they can
        // never contain a structural delimiter -- see VcfSample.newTrusted()'s own Javadoc for the measured cost
        // this skips and why it's safe only for this exact call site.
        VcfSample sample = VcfSample.newTrusted(sampleFormat, values);
        sample.setWarningsSink(m_warnings);
        samples.add(sample);
      }

    } catch (VcfFormatException ex) {
      ex.addMetadata(m_lineNumber, "data");
      throw ex;
    } catch (RuntimeException e) {
      throw new VcfFormatException(m_lineNumber, "data", e);
    }

    // only run once every check above that can reject this line -- notably VcfPosition's own structural
    // checks and VcfSample's FORMAT/sample-arity check -- has passed, so the ordering/contiguity state
    // reflects exactly the records delivered to the VcfLineParser below, never a line rejected before
    // reaching it. Deliberately runs before parseLine() itself, not after: a VcfLineParser callback that
    // throws is the caller's own problem with an already-valid record, not this parser rejecting it.
    //
    // Both calls below are deliberately OUTSIDE the try/catch above: a RuntimeException from the
    // VcfLineParser callback itself (a bug in the caller's own code, not a VCF-content problem) must
    // propagate with its original type, not get relabeled as VcfFormatException the way a genuine
    // content-parsing failure above does. A VcfFormatException the callback throws on purpose (to reject an
    // already-structurally-valid record) still gets this line's number attached, same as before.
    checkPositionOrdering(chromosome, position);
    try {
      m_vcfLineParser.parseLine(m_vcfMetadata, VcfRecord.newTrusted(pos, samples));
    } catch (VcfFormatException ex) {
      ex.addMetadata(m_lineNumber, "data");
      throw ex;
    }
    return true;
  }

  /**
   * Warns (never throws) if {@code position} is out of order within {@code chromosome}, or if
   * {@code chromosome} is not contiguous (its records were interrupted by a different CHROM and have now
   * reappeared). VCFv4.2.tex line 179/180: CHROM entries "should" form a contiguous block, and POS values
   * "are sorted numerically, in increasing order" within a CHROM (multiple records at the same POS are
   * explicitly permitted). Neither violates this library's structural floor -- record/column boundaries stay
   * unambiguous either way -- and neither bcftools 1.23.1 nor vcf_validator 0.10.2 treats either as
   * rejection-worthy (vcf_validator's own severity classification puts both at warning-tier, not
   * error-tier), so this warns and recovers rather than throwing.
   */
  private void checkPositionOrdering(String chromosome, long position) {
    if (m_lastChromosome != null) {
      if (chromosome.equals(m_lastChromosome)) {
        if (position < m_lastPosition) {
          m_warnings.warn("POS {} is out of order for CHROM {} (previous record's POS was {}, on line {})",
              position, chromosome, m_lastPosition, m_lineNumber);
        }
      } else {
        if (m_closedChromosomes.contains(chromosome)) {
          m_warnings.warn("CHROM {} is not contiguous: it reappears after being interrupted by a different " +
              "CHROM (on line {})", chromosome, m_lineNumber);
        }
        m_closedChromosomes.add(m_lastChromosome);
      }
    }
    m_lastChromosome = chromosome;
    m_lastPosition = position;
  }

  @Override
  public void close() {
    try {
      m_reader.close();
    } catch (Exception ex) {
      sf_logger.info("Error closing reader", ex);
    }
  }


  /**
   * Parses a ## metadata line, annotating any failure with the line number.
   */
  private void parseMetadataLine(VcfMetadata.Builder mdBuilder, String line) {
    try {
      parseMetadata(mdBuilder, line);
    } catch (VcfFormatException ex) {
      ex.addMetadata(m_lineNumber, "metadata");
      throw ex;
    } catch (RuntimeException e) {
      throw new VcfFormatException(m_lineNumber, "metadata", e);
    }
  }

  /**
   * Parses a metadata line (starts with ##).
   */
  private void parseMetadata(VcfMetadata.Builder mdBuilder, String line) {

    int idx = line.indexOf("=");
    if (idx < 0) {
      throw new VcfFormatException("Metadata line is missing '=': " + line);
    }
    String propName = line.substring(2, idx).trim();
    String propValue = line.substring(idx + 1).trim();

    sf_logger.debug("{} : {}", propName, propValue);

    switch (propName) {
      case "fileformat":
        mdBuilder.setFileFormat(propValue);
        break;

      // keep in sync with InternalUtils.RESERVED_METADATA_KEYS (VcfWriter/VcfMetadata reject raw properties with these names)
      case "ALT":
      case "FILTER":
      case "INFO":
      case "FORMAT":
      case "contig":
      case "SAMPLE":
      case "PEDIGREE":
        parseMetadataProperty(mdBuilder, propName, removeAngleBrackets(propValue));
        break;

      default:
        mdBuilder.addRawProperty(propName, propValue);
    }
  }

  /**
   * Removes angle brackets around a string.
   * @throws VcfFormatException If angle brackets are not present
   */
  private static String removeAngleBrackets(String string) throws VcfFormatException {
    if (string.startsWith("<") && string.endsWith(">")) {
      return string.substring(1, string.length() - 1);
    }
    throw new VcfFormatException("Angle brackets not present for: '" + string + "'");
  }

  /**
   * Converts metadata name-value pair into object.
   */
  private void parseMetadataProperty(VcfMetadata.Builder mdBuilder,
      String propName, String value) {
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(value, m_warnings);
    // propName is already constrained to these exact-case literals by parseMetadata()'s own switch above,
    // so no case-folding is needed here at all -- a prior version lower-cased propName before this switch,
    // which meant a Turkish/Azeri default locale's dotless-i case-folding (e.g. "INFO".toLowerCase() ->
    // "ınfo") silently matched none of the case labels below and dropped the declaration with no warning.
    switch (propName) {
      case "ALT":
        mdBuilder.addAlt(new IdDescriptionMetadata("ALT", props, m_warnings));
        break;
      case "FILTER":
        mdBuilder.addFilter(new IdDescriptionMetadata("FILTER", props, m_warnings));
        break;
      case "INFO":
        mdBuilder.addInfo(new InfoMetadata(props, m_warnings));
        break;
      case "FORMAT":
        mdBuilder.addFormat(new FormatMetadata(props, m_warnings));
        break;
      case "contig":
        mdBuilder.addContig(new ContigMetadata(props, m_warnings));
        break;
      case "SAMPLE":
        mdBuilder.addSample(new IdDescriptionMetadata("SAMPLE", props, m_warnings));
        break;
      case "PEDIGREE": {
        // PedigreeMetadata has no init() and calls no warn site of its own today (unlike the IdMetadata
        // subclasses above, which invoke checkAttributeOrder() from their own constructors) -- so a mis-ordered
        // ##PEDIGREE line currently produces no attribute-order warning at all, not merely one routed
        // elsewhere. Still claiming m_warnings here keeps this consistent with every sibling declaration type
        // and ensures any future lazy warn site added to PedigreeMetadata/BaseMetadata is correctly routed to
        // warningsToFile() instead of silently falling back to the shared logger.
        PedigreeMetadata pedigree = new PedigreeMetadata(props);
        pedigree.setWarningsSink(m_warnings);
        mdBuilder.addPedigree(pedigree);
        break;
      }
    }
  }

  /**
   * Splits {@code string} on the single character {@code delim}, matching
   * {@code Pattern.compile(String.valueOf(delim)).split(string, -1)}: leading, interior, and trailing empty fields
   * are all kept, and a string containing no delimiter yields a single-element list. Avoids the regex engine, which
   * is a meaningful per-line cost when parsing large VCFs.
   * <p>
   * Callers are responsible for handling any empty entries in the result (VCF does not allow zero-length fields); see
   * {@code EMPTY_FIELD_HANDLING.md} for how each field type handles them.
   */
  // package-private for differential testing against Pattern.split
  static List<String> toList(char delim, String string) {
    int idx = string.indexOf(delim);
    if (idx < 0) {
      List<String> single = new ArrayList<>(1);
      single.add(string);
      return single;
    }
    // sized exactly via a first counting pass (cheap: indexOf calls only, no allocation), rather than growing
    // (and copying) an unsized ArrayList as elements are added -- measured up to ~15% faster for a split that
    // would otherwise cross ArrayList's default capacity (10) boundary; a smaller but real improvement (~2-5%)
    // even for a wide split, where String.substring/indexOf scanning dominates total cost far more.
    int count = 1;
    for (int i = idx; i >= 0; i = string.indexOf(delim, i + 1)) {
      count++;
    }
    List<String> list = new ArrayList<>(count);
    int start = 0;
    while (idx >= 0) {
      list.add(string.substring(start, idx));
      start = idx + 1;
      idx = string.indexOf(delim, start);
    }
    list.add(string.substring(start));
    // zero production cost (JVM assertions are off by default at runtime) but lets the broad differential test
    // below (testToListMatchesPatternSplit) catch a mis-sized count as a direct assertion failure rather than
    // relying on ArrayList's silent auto-grow to paper over it
    assert list.size() == count : "count " + count + " did not match actual size " + list.size();
    return list;
  }

  /**
   * Splits a sample field according to its FORMAT keys. GLE is the sole VCF 4.1/4.2 exception to ordinary
   * colon-delimited sample values: its value is a String containing comma-separated {@code genotype:likelihood}
   * pairs. See {@code VCFv4.2.tex} line 225's GLE example (
   * {@code GLE=0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53}).
   */
  private static List<String> toSampleValues(@Nullable List<String> format, String sample) {
    if (format == null) {
      return toList(COLON, sample);
    }
    int gleIndex = format.indexOf(GLE);
    if (gleIndex < 0) {
      return toList(COLON, sample);
    }

    List<String> values = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < gleIndex; i++) {
      int separator = sample.indexOf(COLON, start);
      if (separator < 0) {
        // GLE and every following field were dropped; ordinary splitting lets the caller pad the trailing fields.
        return toList(COLON, sample);
      }
      values.add(sample.substring(start, separator));
      start = separator + 1;
    }

    String gleAndRemainder = sample.substring(start);
    Matcher matcher = GLE_VALUE_PATTERN.matcher(gleAndRemainder);
    if (!matcher.matches()) {
      // A missing GLE value (".") has no internal colon; ordinary splitting handles it and malformed values retain
      // the existing sample-arity validation behavior.
      return toList(COLON, sample);
    }
    values.add(matcher.group(1));
    if (matcher.group(2) != null) {
      values.addAll(toList(COLON, matcher.group(2)));
    }
    return values;
  }

  /**
   * @return A running count of lines read from the input so far (both metadata and data lines), starting at
   * {@code 0} before any line has been read and incrementing by one for each line read afterward.
   */
  public int getLineNumber() {
    return m_lineNumber;
  }

  private void parseColumnInfo(VcfMetadata.Builder mdBuilder, String line) {
    List<String> cols = toList(TAB, line);
    if (cols.size() < 8) {
      throw new VcfFormatException("Header line does not have mandatory (tab-delimited) columns", m_lineNumber);
    }
    for (int i = 0; i < FIXED_FIELD_NAMES.size(); i++) {
      String expected = i == 0 ? "#" + FIXED_FIELD_NAMES.get(0) : FIXED_FIELD_NAMES.get(i);
      if (!cols.get(i).equals(expected)) {
        throw new VcfFormatException("Header column " + (i + 1) + " must be '" + expected +
            "' but was '" + cols.get(i) + "'", m_lineNumber);
      }
    }
    if (cols.size() > 8) {
      if (!cols.get(8).equals("FORMAT")) {
        throw new VcfFormatException("Header column 9 must be 'FORMAT' when sample columns are present but was '" +
            cols.get(8) + "'", m_lineNumber);
      }
      if (cols.size() == 9) {
        // FORMAT only has meaning relative to sample columns; confirmed bcftools 1.23.1 hard-errors on this
        // exact shape and vcf_validator 0.10.2 reports it invalid (see VcfParserTest.testFormatWithNoSamplesThrows)
        throw new VcfFormatException("Header declares FORMAT but no sample columns follow it", m_lineNumber);
      }
    }
    Set<String> sampleNames = new HashSet<>();
    for (int i = 9; i < cols.size(); i++) {
      if (cols.get(i).isEmpty()) {
        // confirmed bcftools 1.23.1 hard-errors ("[E::bcf_hdr_add_sample_len] Empty sample name: trailing
        // spaces/tabs in the header line?") and vcf_validator 0.10.2 reports the file invalid, for both a
        // trailing and an interior empty sample name
        throw new VcfFormatException("Empty sample name in header (check for a trailing or duplicated tab)",
            m_lineNumber);
      }
      if (!sampleNames.add(cols.get(i))) {
        throw new VcfFormatException("Duplicate sample name '" + cols.get(i) + "' in header", m_lineNumber);
      }
    }
    mdBuilder.setColumns(cols);
  }


  public static class Builder {
    private BufferedReader m_reader;
    private Path m_vcfFile;
    private boolean m_rsidsOnly;
    private VcfLineParser m_vcfLineParser;
    private boolean m_warningsToFile;
    private @Nullable Path m_warningsFile;


    /**
     * Provides the {@link Path} to the VCF file to parse.
     * <p>
     * This reads the file as plain, uncompressed text; it does not accept a compressed VCF (e.g. {@code .vcf.gz}) and
     * does not attempt to decompress one. This is deliberate: {@link java.util.zip.GZIPInputStream} cannot reliably read
     * BGZF (block gzip), the format most bioinformatics tools use for compressed VCF, since a BGZF stream is a sequence
     * of independent gzip blocks with trailing extra data that plain {@code GZIPInputStream} does not expect and can
     * mishandle. To read a compressed VCF, decompress it with a library appropriate to how it was compressed (BGZF vs.
     * plain gzip) and pass the result to {@link #fromReader}.
     */
    public Builder fromFile(Path dataFile) {
      Preconditions.checkNotNull(dataFile);
      if (m_reader != null) {
        throw new IllegalStateException("Already loading from reader");
      }
      if (!dataFile.toString().endsWith(".vcf")) {
        throw new IllegalArgumentException("Not a VCF file (doesn't end with .vcf extension)");
      }
      m_vcfFile = dataFile;
      return this;
    }

    /**
     * Provides a {@link BufferedReader} to the beginning of the VCF file to parse.
     */
    public Builder fromReader(BufferedReader reader) {
      Preconditions.checkNotNull(reader);
      if (m_vcfFile != null) {
        throw new IllegalStateException("Already loading from file");
      }
      m_reader = reader;
      return this;
    }

    /**
     * Tells parser to ignore data lines that are not associated with an RSID: a line is kept only if at least one
     * complete semicolon-delimited ID component matches {@code rs\d+}. An ID such as "notrs123suffix" does not
     * qualify, even though it contains "rs123" as a substring.
     * <p>This is a true pre-filter, not a post-parse one: a discarded line is treated as though it were never in
     * the file at all -- the same as if it had been removed by an external tool (e.g. {@code bcftools view})
     * before this file was ever handed to {@code VcfParser}. Only the column-count check and the check that no
     * fixed field is empty apply to a line before this filter runs, since both are preconditions for reading the
     * ID field at all; everything else -- confirming POS even parses as a number, CHROM/POS ordering, and the
     * rest of {@link VcfPosition}'s structural-floor validation (negative POS, CHROM's character-set rules,
     * invalid REF/ALT syntax, FILTER/FORMAT rules) -- never runs for a line this option discards, and never
     * throws {@link VcfFormatException} on its account. {@code VcfPosition} is never constructed for a
     * discarded line at all.
     * <p>This is why a {@link VcfLineParser}-based filter cannot achieve the same thing: a line parser only sees a
     * record after {@code VcfPosition} has already been fully constructed and validated, so a structurally
     * invalid record this option would silently discard instead aborts parsing entirely before any
     * line parser gets a chance to reject it.
     */
    public Builder rsidsOnly() {
      m_rsidsOnly = true;
      return this;
    }

    public Builder parseWith(VcfLineParser lineParser) {
      Preconditions.checkNotNull(lineParser);
      m_vcfLineParser = lineParser;
      return this;
    }

    /**
     * Redirects VCF-content warnings from parsing to a file named after the source VCF file's own full name (kept,
     * including its extension) plus {@code .warn.log}, in the same directory -- e.g. parsing {@code sample.vcf}
     * writes warnings to {@code sample.vcf.warn.log} alongside it. Requires the parser to have been built via
     * {@link #fromFile(Path)}, not {@link #fromReader(BufferedReader)} -- there is no path to derive a default
     * name from otherwise; use {@link #warningsToFile(Path)} instead in that case. See
     * {@link #warningsToFile(Path)}'s Javadoc for a same-path truncation risk that also applies here if this
     * default-derived name happens to collide with another {@code warningsToFile()}-configured
     * {@link VcfParser}/{@link VcfWriter}'s own default-derived name (e.g. an in-place parse-then-write).
     * <p>
     * This redirection is scoped to the {@link VcfParser#parse()} call itself. A warning from a value whose
     * validation this library defers until a getter is called (e.g. {@code VcfPosition.getQuality()}) is
     * captured correctly even if that getter is called from a different thread than the one running
     * {@link VcfParser#parse()}, as long as it's called before {@code parse()} returns -- {@link VcfPosition}
     * holds the same {@link VcfWarnings} instance {@code parse()} configured, not a thread-local reference. If
     * that getter is instead called after {@code parse()} has already returned (and this file has been closed),
     * it falls back to the default logger instead of failing -- see {@link VcfWarnings#warn}'s own Javadoc.
     * Calling {@link VcfParser#parseMetadata()} or {@link VcfParser#parseNextLine()} directly, instead of
     * {@link VcfParser#parse()}, is not redirected at all.
     */
    public Builder warningsToFile() {
      m_warningsToFile = true;
      m_warningsFile = null;
      return this;
    }

    /**
     * Redirects VCF-content warnings from parsing to {@code customFile} instead of the default derived name.
     * <p>
     * This redirection is scoped to the {@link VcfParser#parse()} call itself. A warning from a value whose
     * validation this library defers until a getter is called (e.g. {@code VcfPosition.getQuality()}) is
     * captured correctly even if that getter is called from a different thread than the one running
     * {@link VcfParser#parse()}, as long as it's called before {@code parse()} returns -- {@link VcfPosition}
     * holds the same {@link VcfWarnings} instance {@code parse()} configured, not a thread-local reference. If
     * that getter is instead called after {@code parse()} has already returned (and this file has been closed),
     * it falls back to the default logger instead of failing -- see {@link VcfWarnings#warn}'s own Javadoc.
     * Calling {@link VcfParser#parseMetadata()} or {@link VcfParser#parseNextLine()} directly, instead of
     * {@link VcfParser#parse()}, is not redirected at all.
     * <p>
     * If {@code customFile} is the same path another {@code warningsToFile()}-configured {@link VcfParser} or
     * {@link VcfWriter} also writes to -- including via a matching default-derived name, e.g. an in-place
     * parse-then-write to the same file -- whichever side opens its warnings file second silently truncates the
     * first side's content. Both open via {@code Files.newBufferedWriter}, which defaults to overwrite, not
     * append. This "truncates" description is exact only when the two sides' warnings-file lifetimes are
     * sequential (one fully written and closed before the other opens the same path). If the two lifetimes
     * instead overlap -- e.g. a {@link VcfWriter} built before {@link VcfParser#parse()} runs, its own warnings
     * file still open -- each side holds an independent file position from its own {@code open()} call, so the
     * result is a byte-level overwrite/interleave (potentially leaving a zero-filled gap) rather than a clean,
     * complete truncation of one side's content.
     */
    public Builder warningsToFile(Path customFile) {
      Preconditions.checkNotNull(customFile);
      m_warningsToFile = true;
      m_warningsFile = customFile;
      return this;
    }


    public VcfParser build() throws IOException {
      if (m_vcfLineParser == null) {
        throw new IllegalStateException("Missing VcfLineParser");
      }
      if (m_vcfFile != null) {
        m_reader = Files.newBufferedReader(m_vcfFile);
      }
      if (m_reader == null) {
        throw new IllegalStateException("Must specify either file or reader to parse");
      }
      Path resolvedWarningsFile = null;
      if (m_warningsToFile) {
        if (m_warningsFile != null) {
          resolvedWarningsFile = m_warningsFile;
        } else if (m_vcfFile != null) {
          resolvedWarningsFile = m_vcfFile.resolveSibling(m_vcfFile.getFileName().toString() + ".warn.log");
        } else {
          throw new IllegalStateException("warningsToFile() requires either an explicit file or a source file " +
              "obtained via fromFile(Path) -- fromReader(BufferedReader) provides no path to derive a default " +
              "name from");
        }
      }
      return new VcfParser(m_reader, m_rsidsOnly, m_vcfLineParser, resolvedWarningsFile);
    }
  }
}

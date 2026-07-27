package org.pharmgkb.parser.vcf;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.BaseMetadata;
import org.pharmgkb.parser.vcf.model.FormatMetadata;
import org.pharmgkb.parser.vcf.model.FormatType;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Writes to VCF format from a {@link VcfSample}, {@link VcfPosition VcfPositions}, and {@link VcfMetadata}.
 * The default write path stays fast by performing little validation of its own; build with
 * {@link Builder#validateBeforeWrite()} for full structural validation and semantic-compliance diagnostics.
 * <p>
 * {@link #close} must always be called to guarantee output is completely flushed and any write failure (disk
 * full, broken pipe, or an encoding failure) is reported -- such a failure surfaces as an unchecked
 * {@link java.io.UncheckedIOException} from {@link #writeHeader}, {@link #writeLine}, or {@link #close} itself,
 * and permanently invalidates this instance: the underlying writer may be left in a partially-written or
 * otherwise inconsistent state, so retrying is never safe and no further work is attempted. Call
 * {@link #isValid()} to check without triggering an exception; once invalid, {@link #writeHeader} and
 * {@link #writeLine} both throw {@link IllegalStateException} (with the original write failure as the cause)
 * instead of doing anything further. A {@link VcfFormatException} thrown for ordinary content rejection (e.g. a
 * sample-count mismatch, or invalid metadata in {@link Builder#validateBeforeWrite()} mode), by contrast, does
 * not invalidate this instance -- nothing was corrupted, so a corrected call can succeed normally afterward.
 *
 * @author Douglas Myers-Turnbull
 * @see TransformingVcfLineParser TransformingVcfLineParser - a read-transform-write streamer that is publicly accessible
 */
public class VcfWriter implements Closeable {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final @Nullable Path m_file;
  private final PrintWriter m_writer;
  private final boolean m_validateBeforeWrite;
  private int m_lineNumber;
  private boolean m_headerWritten;
  private @Nullable VcfMetadata m_headerMetadata;
  // snapshot of the exact header text emitted by writeHeader(), in validateBeforeWrite mode only; lets writeLine()
  // detect the same metadata object being mutated in place after the header was already flushed (e.g. via
  // addInfo()/removeInfo()), which "metadata != m_headerMetadata" alone can't catch
  private @Nullable String m_headerText;

  private boolean m_valid = true;
  private @Nullable Exception m_invalidatingCause;

  private VcfWriter(@Nullable Path file, PrintWriter writer, boolean validateBeforeWrite) {
    m_file = file;
    m_writer = writer;
    m_validateBeforeWrite = validateBeforeWrite;
  }

  /**
   * @return {@code false} once a write failure has permanently invalidated this instance (see the class Javadoc);
   * {@code true} otherwise.
   */
  public boolean isValid() {
    return m_valid;
  }

  private void checkValid() {
    if (!m_valid) {
      throw new IllegalStateException("This VcfWriter is no longer usable after a write failure", m_invalidatingCause);
    }
  }

  /**
   * Writes the VCF header.
   *
   * @throws IllegalStateException if {@link #isValid()} is already {@code false} -- this instance must be
   * abandoned after any prior write failure, not reused.
   * @throws UncheckedIOException if a write failure is detected here. This permanently invalidates the instance
   * (see the class Javadoc) -- retrying is never safe, since some header lines may have already reached the
   * underlying writer.
   */
  public void writeHeader(VcfMetadata metadata) {
    checkValid();

    if (m_validateBeforeWrite) {
      if (m_headerWritten) {
        throw new VcfFormatException("writeHeader() was already called; a VCF file must have exactly one header");
      }
      validateMetadata(metadata);
    }

    List<String> lines = buildHeaderLines(metadata);
    for (String line : lines) {
      printLine(line);
    }

    // header-state fields are set only after checkForWriteError() confirms the write actually succeeded, not
    // before it -- setting them earlier and relying on "it's moot, checkValid() blocks everything after a
    // failure anyway" is exactly the reasoning that let a premature-flag bug slip into a sibling class; this
    // way each field's own timing is correct on its own terms
    checkForWriteError();
    m_headerWritten = true;
    m_headerMetadata = metadata;
    if (m_validateBeforeWrite) {
      m_headerText = String.join("\n", lines);
    }
    sf_logger.debug("Wrote {} lines of header{}", m_lineNumber, (m_file == null ? "" : " to " + m_file));
  }

  /**
   * Builds every header line (metadata declarations, then the {@code #CHROM} column line) that {@link #writeHeader}
   * would emit for {@code metadata}, without writing anything. Used both by {@link #writeHeader} itself and, in
   * validateBeforeWrite mode, by {@link #writeLine} to detect the header metadata object having been mutated in
   * place since the header was written (see {@link #m_headerText}).
   */
  private List<String> buildHeaderLines(VcfMetadata metadata) {
    List<String> lines = new ArrayList<>();

    // file format
    lines.add("##fileformat=" + metadata.getFileFormat());

    // metadata, in order from spec
    addMetadataLines(lines, "INFO", metadata.getInfo().values());
    addMetadataLines(lines, "FILTER", metadata.getFilters().values());
    addMetadataLines(lines, "FORMAT", metadata.getFormats().values());
    addMetadataLines(lines, "ALT", metadata.getAlts().values());
    addMetadataLines(lines, "contig", metadata.getContigs().values());
    addMetadataLines(lines, "SAMPLE", metadata.getSamples().values());
    addMetadataLines(lines, "PEDIGREE", metadata.getPedigrees());

    for (String key : metadata.getRawPropertyKeys()) {
      addPropertyLines(lines, key, metadata.getRawValuesOfProperty(key));
    }

    // header line
    StringBuilder sb = new StringBuilder("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO");
    if (metadata.getNumSamples() > 0) {
      sb.append("\tFORMAT");
    }
    for (int i = 0; i < metadata.getNumSamples(); i++) {
      sb.append("\t").append(metadata.getSampleName(i));
    }
    lines.add(sb.toString());

    return lines;
  }

  /**
   * Writes a single data line.
   * <p>
   * By default, this does not fully revalidate metadata, the position, or samples, to keep the direct
   * parse-and-write path fast. If this writer was built with {@link Builder#validateBeforeWrite()}, it rejects
   * structurally invalid output and warns about detected semantic non-compliance before writing.
   * <p>
   * Output is flushed, and checked for a write failure, every 1000 lines and at {@link #writeHeader}/
   * {@link #close}, not after every call to this method -- a caller that needs output visible to another
   * reader sooner must flush the underlying stream directly. Calling {@link #close} is required to guarantee a
   * pending write failure is eventually reported and that all buffered output reaches the underlying stream;
   * writing fewer than 1000 lines and never calling {@link #close} will neither surface a failure nor guarantee
   * the data was actually written.
   *
   * @throws UncheckedIOException if a write failure is detected (only possible on a call that lands on the
   * periodic 1000-line checkpoint; most calls cannot throw this)
   */
  public void writeLine(VcfMetadata metadata, VcfPosition position,
      List<VcfSample> samples) {
    checkValid();

    if (m_validateBeforeWrite) {
      if (!m_headerWritten) {
        throw new VcfFormatException("writeLine() was called before writeHeader(); every VCF file must have a " +
            "header before any data line");
      }
      if (metadata != m_headerMetadata) {
        throw new VcfFormatException("writeLine() was given different metadata than writeHeader() used; the " +
            "emitted header and this record would be inconsistent");
      }
      // metadata == m_headerMetadata only proves it's the same object, not that it's unchanged: a caller can
      // mutate it in place (e.g. addInfo()/removeInfo()) after writeHeader() already flushed the header text, so
      // the header actually on disk/in the stream can still disagree with what this same object would now
      // produce. Compare the header text metadata would produce right now against the snapshot taken when it was
      // actually written.
      if (!String.join("\n", buildHeaderLines(metadata)).equals(m_headerText)) {
        throw new VcfFormatException("The metadata object given to writeLine() has changed since writeHeader() " +
            "was called (e.g. via addInfo()/removeInfo()); the already-emitted header no longer matches what this " +
            "metadata would produce");
      }
      position.validate();
      validateMetadata(metadata);
      validateInfo(metadata, position);
      validateSamples(metadata, position, samples);
    }

    int numSamples = metadata.getNumSamples();
    if (numSamples == 0) {
      if (!samples.isEmpty() || !position.getFormat().isEmpty()) {
        throw new VcfFormatException("Position " + position.getChromosome() + ":" + position.getPosition() +
            " has FORMAT or sample data, but the header declares no samples");
      }
    } else if (samples.size() != numSamples) {
      throw new VcfFormatException("Position " + position.getChromosome() + ":" + position.getPosition() +
          " has " + samples.size() + " sample(s), but the header declares " + numSamples);
    }
    if (position.getRef().isEmpty()) {
      // REF has no missing-value sentinel in the spec ("." means something else entirely for other columns); writing
      // "." here would not actually be valid VCF, just a placeholder that avoids crashing on a mutated position
      throw new VcfFormatException("Position " + position.getChromosome() + ":" + position.getPosition() +
          " has an empty REF, which the VCF spec does not allow (REF has no missing-value sentinel)");
    }

    StringBuilder sb = new StringBuilder();

    sb.append(position.getChromosome()).append("\t");
    sb.append(position.getPosition()).append("\t");
    addListOrElse(position.getIds(), ";", ".", sb);
    sb.append(position.getRef()).append("\t");
    addListOrElse(position.getAltBases(), ",", ".", sb);
    addStringOrElse(position.getQuality(), ".", sb);
    if (position.getFilterStatus() == VcfPosition.FilterStatus.NONE) {
      sb.append(".\t"); // filters not applied: write the missing value rather than PASS
    } else {
      addListOrElse(position.getFilters(), ";", "PASS", sb);
    }
    addInfoOrDot(metadata, position, sb);

    // "PASS" is VCF's reserved "all filters passed" keyword (VCFv4.2.tex line 185: "PASS if this position has
    // passed all filters"), never expected to have its own ##FILTER=<ID=PASS,...> declaration. This filter must
    // exclude it explicitly: since VcfPosition.checkFilters() now warns instead of throwing when FILTER combines
    // PASS with another code, position.getFilters() can legitimately contain a literal "PASS" entry alongside
    // other codes -- a case this check could never previously see, because that combination used to be rejected
    // during parsing before reaching the writer.
    position.getFilters().stream()
        .filter(key -> !key.equals("PASS") && !metadata.getFilters().containsKey(key)).forEach(key ->
        sf_logger.warn("Position {}:{} has FILTER {}, but there is no FILTER metadata with that name (on line {})",
            position.getChromosome(), position.getPosition(), key, m_lineNumber));

    // these columns can be skipped completely
    addFormatConditionally(position, numSamples, sb);
    int sampleIndex = 0;
    for (VcfSample sample : samples) {
      addSampleConditionally(metadata, sampleIndex, position, sample, sb);
      sampleIndex++;
    }

    String line = sb.toString();
    if (line.endsWith("\t")) line = line.substring(0, line.length() - 1);
    printLine(line);
  }

  /**
   * Closes the underlying writer. This must be called to guarantee complete, correctly-flushed output and that
   * any pending write failure is reported -- see the class Javadoc.
   *
   * @throws UncheckedIOException if a write failure is detected (this call's own, e.g. from the underlying
   * stream's own {@code close()}, or one from an earlier periodic check that hadn't yet surfaced). The underlying
   * writer is always released first, even when this is thrown. This also permanently invalidates the instance,
   * the same as a failure detected by {@link #writeHeader} or {@link #writeLine}.
   */
  @Override
  public void close() {
    // checkError() flushes before checking (see its own Javadoc), so this first call catches anything still
    // buffered from writeLine()/writeHeader(). IOUtils.closeQuietly() always runs next regardless, so the
    // resource is never leaked even when we're about to throw. The second checkError() (after closeQuietly())
    // catches a failure from the underlying stream's own close() (e.g. an fsync-on-close error) -- safe to call
    // again here because a closed PrintWriter's checkError() no longer attempts to flush.
    boolean hadError = m_writer.checkError();
    IOUtils.closeQuietly(m_writer);
    if (hadError || m_writer.checkError()) {
      UncheckedIOException e = writeError();
      m_valid = false;
      m_invalidatingCause = e;
      throw e;
    }
  }

  private void addFormatConditionally(VcfPosition position, int numSamples, StringBuilder sb) {
    Iterator<String> formats = position.getFormat().iterator();
    if (!formats.hasNext()) {
      if (numSamples > 0) {
        // FORMAT="." (no per-sample format fields) is valid when sample columns are declared -- see
        // VcfPosition.checkFormat's javadoc for the bcftools 1.23.1-verified rationale
        sb.append(".\t");
      }
      return;
    }
    while (formats.hasNext()) {
      sb.append(formats.next());
      if (formats.hasNext()) {
        sb.append(":");
      }
    }
    sb.append("\t");
  }

  private void validateMetadata(VcfMetadata metadata) {
    if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(metadata.getFileFormat()).matches()) {
      throw new VcfFormatException("VCF file format must be VCF 4.x: " + metadata.getFileFormat());
    }
    validateKeyedMetadataEntries("INFO", metadata.getInfo());
    validateKeyedMetadataEntries("FORMAT", metadata.getFormats());
    validateKeyedMetadataEntries("FILTER", metadata.getFilters());
    validateKeyedMetadataEntries("ALT", metadata.getAlts());
    validateKeyedMetadataEntries("contig", metadata.getContigs());
    validateKeyedMetadataEntries("SAMPLE", metadata.getSamples());
    // unlike the six kinds above, PEDIGREE entries aren't stored in a map keyed by ID, so there's no map key to
    // compare against; duplicate IDs across entries must still be checked directly here
    Set<String> pedigreeIds = new HashSet<>();
    for (BaseMetadata pedigree : metadata.getPedigrees()) {
      pedigree.validate();
      Map<String, String> properties = pedigree.getPropertiesRaw();
      validateMetadataProperties("PEDIGREE", properties);
      String id = properties.get("ID");
      if (id != null && !pedigreeIds.add(id)) {
        // unlike VcfMetadata.Builder.addPedigree() (which warns and drops a duplicate ID at construction time),
        // this check runs post-construction and must not silently remove content the caller already holds a
        // reference to (same "warn, don't drop" writer philosophy as the empty-metadata-value check elsewhere in
        // this method) -- so this only warns; both entries are still written. validateMetadata() runs once per
        // writeLine() call in validateBeforeWrite() mode, so this warning repeats for every data record written
        // while the duplicate remains -- the same repeat-per-record behavior already accepted for the
        // empty-metadata-value warning above, not a new tradeoff introduced here.
        sf_logger.warn("Duplicate ID \"{}\" for PEDIGREE metadata", id);
      }
    }
    Set<String> sampleNames = new HashSet<>();
    for (int i = 0; i < metadata.getNumSamples(); i++) {
      String sampleName = metadata.getSampleName(i);
      VcfUtils.checkNoLineTerminator("sample name", sampleName);
      if (sampleName.isEmpty() || sampleName.contains("\t") || !sampleNames.add(sampleName)) {
        throw new VcfFormatException("Invalid or duplicate sample name in header: " + sampleName);
      }
    }
    for (String key : metadata.getRawPropertyKeys()) {
      // a raw property is written as "##" + key + "=" + value, and reread by splitting on the FIRST "=" only
      // (VcfParser.parseMetadata); a key containing '=' shifts that split point, so the key/value recovered on
      // reparse would not match what was written. Verified live: writing key "foo=bar" reparses as key "foo",
      // value "bar=value" instead of key "foo=bar", value "value".
      if (key.contains("=")) {
        throw new VcfFormatException("Metadata property name \"" + key + "\" contains '=', which would shift " +
            "where the key/value split falls when re-read");
      }
      if (VcfUtils.isReservedMetadataKey(key)) {
        // getRawProperties() returns the backing multimap directly, so a caller can insert this after
        // addRawProperty()'s own check has already run once, at insertion time; unlike that check, this one
        // rejects outright, since a raw "INFO"/"FORMAT"/etc. property would produce output VcfParser cannot
        // read back as what was written (see VcfParser.parseMetadata's switch)
        throw new VcfFormatException("Metadata property name \"" + key + "\" collides with a structured " +
            "declaration keyword");
      }
      for (String value : metadata.getRawValuesOfProperty(key)) {
        VcfUtils.checkNoLineTerminator(key, value);
        if (VcfMetadata.isEmptyMetadataValue(value)) {
          // unlike VcfMetadata.Builder.addRawProperty()/addAssembly()/addPedigreeDatabase() (which drop an empty
          // value at insertion time), this only warns -- silently removing content the caller is already holding
          // a reference to, right before serializing it, is a bigger behavioral surprise than dropping it before
          // the caller ever sees the parsed object.
          sf_logger.warn("Metadata property \"{}\" has an empty value: {}", key, value);
        }
      }
    }
  }

  /**
   * Validates every entry of a metadata map (INFO/FORMAT/FILTER/ALT/contig/SAMPLE), including that each entry's own
   * {@code ID} property still matches the map key it's stored under. That invariant normally holds automatically
   * (these maps are keyed by {@code getId()} when built), but {@link VcfMetadata#getInfo()} and its siblings return
   * the backing map directly, so a caller can mutate an entry's raw {@code ID} property (via
   * {@link BaseMetadata#getPropertiesRaw()}) without it being reflected in the map key. Left undetected, the header
   * would then declare a different ID than what data lines actually reference by that entry's map key, and a
   * duplicate-ID check across entries would also be unable to catch a real collision this way (see below).
   */
  private void validateKeyedMetadataEntries(String kind, Map<String, ? extends BaseMetadata> entriesByKey) {
    for (Map.Entry<String, ? extends BaseMetadata> mapEntry : entriesByKey.entrySet()) {
      String mapKey = mapEntry.getKey();
      BaseMetadata entry = mapEntry.getValue();
      entry.validate();
      entry.checkAttributeOrder(LoggerFactory.getLogger(entry.getClass()));
      if ("ALT".equals(kind) && entry instanceof IdDescriptionMetadata idDescriptionMetadata) {
        VcfMetadata.validateAlt(idDescriptionMetadata);
      }
      Map<String, String> properties = entry.getPropertiesRaw();
      validateMetadataProperties(kind, properties);
      String id = properties.get("ID");
      // duplicate IDs across different entries can't occur once every entry's ID is confirmed to match its own
      // (necessarily unique) map key, so no separate "duplicate ID" check is needed here
      if (id != null && !mapKey.equals(id)) {
        throw new VcfFormatException(kind + " metadata is stored under key \"" + mapKey + "\" but its own ID " +
            "property is \"" + id + "\"; the header would declare a different ID than records referencing this " +
            "entry by its map key");
      }
    }
  }

  private void validateMetadataProperties(String kind, Map<String, String> properties) {
    for (Map.Entry<String, String> property : properties.entrySet()) {
      String propertyName = property.getKey();
      if (propertyName == null || propertyName.isEmpty() || propertyName.contains(",") ||
          propertyName.contains("=")) {
        throw new VcfFormatException(kind + " metadata contains an invalid property name: " + property.getKey());
      }
      String value = property.getValue();
      if (value == null) {
        throw new VcfFormatException(kind + " metadata property " + propertyName + " has a null value");
      }
      VcfUtils.checkNoLineTerminator(propertyName, value);
      if (!isQuoted(value) && (value.contains(",") || value.contains("=") || containsUnescapedQuote(value))) {
        throw new VcfFormatException(kind + " metadata property " + propertyName +
            " contains an unquoted structural delimiter");
      }
    }
  }

  private static boolean isQuoted(String value) {
    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      return false;
    }
    boolean escaped = false;
    for (int i = 1; i < value.length() - 1; i++) {
      char c = value.charAt(i);
      if (c == '"' && !escaped) {
        return false;
      }
      if (c == '\\') {
        escaped = !escaped;
      } else {
        escaped = false;
      }
    }
    return !escaped;
  }

  /**
   * @return whether {@code value} contains a {@code "} not preceded by an escaping {@code \} (the same escape
   * convention {@link VcfUtils}'s top-level splitter uses). {@link #isQuoted} already rejects a value that starts
   * and ends with {@code "} but isn't validly escaped in between, but a value can also contain a stray, unescaped
   * {@code "} without looking quoted at all (no leading/trailing {@code "}) and without containing {@code ,} or
   * {@code =} either, so this must be checked independently of both. Left unrejected, such a value would flip the
   * writer's top-level "inside a quoted value" tracking state when re-read, potentially misaligning every property
   * that follows it in the same declaration. Verified live: a value of {@code "abc"def"} followed by another
   * property in the same declaration fails to re-parse at all ({@code VcfFormatException}), not just producing a
   * different value than intended.
   */
  private static boolean containsUnescapedQuote(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length() && (value.charAt(i + 1) == '\\' || value.charAt(i + 1) == '"')) {
        i++; // skip the escaped character
      } else if (c == '"') {
        return true;
      }
    }
    return false;
  }

  private void validateInfo(VcfMetadata metadata, VcfPosition position) {
    for (String key : position.getInfoKeys()) {
      List<String> values = position.getInfo(key);
      InfoMetadata info = metadata.getInfo().get(key);
      if (info == null) {
        sf_logger.warn("Position {}:{} contains INFO {}, but there is no INFO metadata with that name",
            position.getChromosome(), position.getPosition(), key);
      } else {
        validateInfoValue(info, values, key, position.getAltBases().size());
      }
    }
  }

  private void validateSamples(VcfMetadata metadata, VcfPosition position, List<VcfSample> samples) {
    List<String> formatKeys = position.getFormat();
    for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
      VcfSample sample = samples.get(sampleIndex);
      sample.validate();
      for (String key : sample.getPropertyKeys()) {
        if (!formatKeys.contains(key)) {
          throw new VcfFormatException("Sample #" + sampleIndex + " contains property " + key +
              ", but it is not declared in FORMAT");
        }
      }
      Integer ploidy = getPloidy(position, sample, sampleIndex);
      for (String key : formatKeys) {
        String value = sample.getProperty(key);
        FormatMetadata format = metadata.getFormats().get(key);
        if (value == null) {
          sf_logger.warn("Sample #{} is missing property {}", sampleIndex, key);
        } else if (format == null) {
          sf_logger.warn("Sample #{} contains FORMAT {}, but there is no FORMAT metadata with that name", sampleIndex, key);
        } else {
          validateFormatValue(format, value, key, sampleIndex, position.getAltBases().size(), ploidy);
        }
      }
    }
  }

  private void validateInfoValue(InfoMetadata info, List<String> values, String key, int numAltAlleles) {
    InfoType type = info.getType();
    if (type == InfoType.Flag) {
      if (values.size() != 1 || !values.get(0).isEmpty()) {
        sf_logger.warn("INFO {} has Type=Flag but has a value", key);
      }
      return;
    }
    if (values.size() == 1 && values.get(0).equals(".")) {
      // the whole field is the missing-value sentinel, not a real value with an inspectable count -- comparing 1
      // against Number's expected cardinality here would be a false positive (e.g. Number=A with 2 ALT alleles but
      // INFO "AF=." would otherwise warn "has 1 value(s), but Number=A requires 2", even though the field is
      // simply unset). validateFormatValue already has the equivalent early return for FORMAT values.
      return;
    }
    if (values.stream().anyMatch(String::isEmpty)) {
      sf_logger.warn("INFO {} contains an empty value", key);
    }
    String number = info.getNumber();
    // unlike a FORMAT value, an INFO value isn't tied to any one sample, so there's no single ploidy to compute
    // Number=G's expected cardinality from; pass null so getExpectedCardinality skips that check instead of
    // guessing a ploidy (previously hardcoded to 2, which could produce the same false-positive warning that
    // getPloidy's ploidy==null handling was introduced to prevent for FORMAT values)
    warnIfWrongCardinality("INFO " + key, number, values.size(), numAltAlleles, null);
    if (type == null) {
      return;
    }
    for (String value : values) {
      try {
        VcfUtils.convertProperty(type, value);
      } catch (VcfFormatException e) {
        sf_logger.warn("INFO {} value {} is not of type {}", key, value, type);
      }
    }
  }

  private void validateFormatValue(FormatMetadata format, String value, String key, int sampleIndex,
      int numAltAlleles, @Nullable Integer ploidy) {
    if (value.equals(".")) {
      return;
    }
    if (!value.isEmpty()) {
      // an empty value is already reported generically below (for any FORMAT key, not just FT/PS); avoid warning
      // about it twice via checkReservedFormatConstraints's own "empty filter code" check. The returned normalized
      // value is discarded: this writer preserves raw content rather than rewriting it, consistent with how a
      // reserved list-typed FORMAT value's empty entry is also only normalized on read, not on write.
      VcfUtils.checkReservedFormatConstraints(key, value);
    }
    String[] values = key.equals("GLE") ? new String[] { value } : value.split(",", -1);
    for (String element : values) {
      if (element.isEmpty()) {
        sf_logger.warn("FORMAT {} for sample #{} contains an empty value", key, sampleIndex);
      }
    }
    String number = format.getNumber();
    warnIfWrongCardinality("FORMAT " + key + " for sample #" + sampleIndex, number, values.length, numAltAlleles,
        ploidy);
    FormatType type = format.getType();
    if (type == null) {
      return;
    }
    for (String element : values) {
      try {
        VcfUtils.convertProperty(type, element);
      } catch (VcfFormatException e) {
        sf_logger.warn("FORMAT {} for sample #{} value {} is not of type {}", key, sampleIndex, element, type);
      }
    }
  }

  private void warnIfWrongCardinality(String field, @Nullable String number, int actual, int numAltAlleles,
      @Nullable Integer ploidy) {
    Long expected = getExpectedCardinality(number, numAltAlleles, ploidy);
    if (expected != null && expected != actual) {
      sf_logger.warn("{} has {} value(s), but Number={} requires {}", field, actual, number, expected);
    }
  }

  static @Nullable Long getExpectedCardinality(@Nullable String number, int numAltAlleles, @Nullable Integer ploidy) {
    if (number == null || number.equals(".")) {
      return null;
    }
    if (number.equals("A")) {
      return (long) numAltAlleles;
    }
    if (number.equals("R")) {
      return (long) numAltAlleles + 1;
    }
    if (number.equals("G")) {
      if (ploidy == null) {
        // ploidy could not be determined (e.g. GT is absent for this sample), so there's nothing valid to compare
        // Number=G's expected cardinality against; skip rather than guessing
        return null;
      }
      Long combinations = combinationsWithRepetition(numAltAlleles + 1, ploidy);
      if (combinations == null) {
        sf_logger.warn("Number=G cardinality for {} allele(s) and ploidy {} is too large to validate", numAltAlleles + 1,
            ploidy);
      }
      return combinations;
    }
    try {
      return Long.parseLong(number);
    } catch (NumberFormatException e) {
      sf_logger.warn("Number={} is too large to validate cardinality", number);
      return null;
    }
  }

  /**
   * @return {@code null} if the result overflows a {@code long}, consistent with {@link #getExpectedCardinality}'s
   * other branches skipping validation (rather than reporting a sentinel value) when a cardinality can't be computed
   */
  private static @Nullable Long combinationsWithRepetition(int numAlleles, int ploidy) {
    int k = Math.min(ploidy, numAlleles - 1);
    long result = 1;
    for (int i = 1; i <= k; i++) {
      try {
        result = Math.multiplyExact(result, numAlleles + ploidy - 1L - k + i) / i;
      } catch (ArithmeticException e) {
        return null;
      }
    }
    return result;
  }

  /**
   * @return the ploidy inferred from this sample's GT value, or {@code null} if GT is absent and ploidy can
   * therefore not be determined (previously defaulted to 2, which could produce a false Number=G cardinality
   * warning for a haploid, or higher-ploidy, sample whose GT simply isn't present in this record)
   */
  private @Nullable Integer getPloidy(VcfPosition position, VcfSample sample, int sampleIndex) {
    String gt = sample.getProperty("GT");
    if (gt == null) {
      return null;
    }
    String[] alleles = gt.split("[/|]", -1);
    boolean valid = alleles.length > 0;
    for (String allele : alleles) {
      if (allele.equals(".")) {
        continue;
      }
      try {
        int index = Integer.parseInt(allele);
        if (index < 0 || index > position.getAltBases().size()) {
          valid = false;
        }
      } catch (NumberFormatException e) {
        valid = false;
      }
    }
    if (!valid) {
      sf_logger.warn("Sample #{} has invalid GT {}", sampleIndex, gt);
    }
    return Math.max(1, alleles.length);
  }

  private void addSampleConditionally(VcfMetadata metadata, int sampleIndex,
      VcfPosition position, VcfSample sample, StringBuilder sb) {

    List<String> formatKeys = position.getFormat();
    if (formatKeys.isEmpty()) {
      // FORMAT="." (no per-sample format fields) is valid when sample columns are declared -- see
      // VcfPosition.checkFormat's javadoc for the bcftools 1.23.1-verified rationale. Every sample column is
      // written as "." regardless of any properties a caller may have set on this VcfSample, since there is no
      // FORMAT key left for them to correspond to.
      if (!sample.getPropertyKeys().isEmpty()) {
        sf_logger.warn("Sample #{} has propert(y/ies) set, but FORMAT is empty ('.'); they will not be written " +
            "(on line {})", sampleIndex, m_lineNumber);
      }
      sb.append(".\t");
      return;
    }

    for (int i = 0; i < formatKeys.size(); i++) {
      String key = formatKeys.get(i);

      if (!m_validateBeforeWrite && !metadata.getFormats().containsKey(key)) {
        sf_logger.warn("Sample #{} for {}:{} contains FORMAT {}, but there is no FORMAT metadata with that name " +
                "(on line {})",
            sampleIndex, position.getChromosome(), position.getPosition(), key, m_lineNumber);
      }

      String value = sample.getProperty(key);
      if (value == null) {
        if (!m_validateBeforeWrite) {
          sf_logger.warn("Sample #{} is missing property {}" +
              " (on line {})", sampleIndex, key, m_lineNumber);
        }
        value = ".";
      }

      FormatMetadata format = metadata.getFormats().get(key);
      if (!m_validateBeforeWrite && format != null) {
        Integer number = null;
        try {
          number = Integer.parseInt(format.getNumber());
        } catch (NumberFormatException ignored) {}
        if (number != null && number == 1 && format.getType() != null) {
          try {
            VcfUtils.convertProperty(format.getType(), value);
          } catch (VcfFormatException e) {
            sf_logger.warn("Property {} for sample #{} is not of type {}" +
                " (on line {})", key, sampleIndex, format.getType(), m_lineNumber);
          }
        }
      }

      sb.append(value);
      if (i < formatKeys.size() - 1) {
        sb.append(":");
      }
    }

    // now make sure the sample doesn't contain extra keys
    if (!m_validateBeforeWrite) {
      sample.getPropertyKeys().stream().filter(key -> !position.getFormat().contains(key)).forEach(key -> {
        sf_logger.warn("Sample #{} contains extra property {} " +
            "(on line {})", sampleIndex, key, m_lineNumber);
      });
    }
    sb.append("\t");
  }

  private void addInfoOrDot(VcfMetadata metadata, VcfPosition position, StringBuilder sb) {

    Iterator<String> keys = position.getInfoKeys().iterator();
    if (!keys.hasNext()) {
      sb.append(".");
    }

    while (keys.hasNext()) {
      String key = keys.next();

      List<String> values = position.getInfo(key);
      assert values != null;

      if (!m_validateBeforeWrite && !metadata.getInfo().containsKey(key)) {
        sf_logger.warn("Position {}:{} contains INFO {}, but there is no INFO metadata with that name (on line {})",
            position.getChromosome(), position.getPosition(), key, m_lineNumber);
      } else if (!m_validateBeforeWrite) {
        InfoMetadata info = metadata.getInfo().get(key);
        for (String value : values) {
          Integer number = null;
          try {
            number = Integer.parseInt(info.getNumber());
          } catch (NumberFormatException ignored) {}
          // if the number is anything but 1, it might be a list of something else, represented as a string
          // in that case, we can't compare
          if (number != null && number == 1 && info.getType() != null) {
            try {
              VcfUtils.convertProperty(info.getType(), value); // just test
            } catch (VcfFormatException e) {
              sf_logger.warn("Property {} is not of type {} (on line {})", key, info.getType(), m_lineNumber);
            }
          }
        }
      }

      sb.append(key);
      if (!values.isEmpty() && !(values.size() == 1 && values.get(0).isEmpty())) {
        sb.append("=").append(values.get(0));
        for (int i = 1; i < values.size(); i++) {
          sb.append(",").append(values.get(i));
        }
      }
      if (keys.hasNext()) {
        sb.append(";");
      }
    }
    sb.append("\t");
  }

  private void addStringOrElse(@Nullable Object object, String missingValue, StringBuilder sb) {
    if (object == null || object.toString().isEmpty()) {
      sb.append(missingValue);
    } else {
      sb.append(object.toString());
    }
    sb.append("\t");
  }

  private void addListOrElse(List<String> list, String delimiter, String missingValue,
      StringBuilder sb) {
    if (list.isEmpty()) {
      sb.append(missingValue);
    } else {
      sb.append(list.get(0));
      for (int i = 1; i < list.size(); i++) {
        sb.append(delimiter).append(list.get(i));
      }
    }
    sb.append("\t");
  }

  private void addPropertyLines(List<String> lines, String name, Collection<String> list) {
    for (String string : list) {
      lines.add("##" + name + "=" + string);
    }
  }

  private void addMetadataLines(List<String> lines, String name, Collection<? extends BaseMetadata> list) {
    for (BaseMetadata metadata : list) {
      lines.add(getAllProperties(name, metadata));
    }
  }

  private String getAllProperties(String name, BaseMetadata metadata) {
    StringBuilder sb = new StringBuilder("##");
    sb.append(name).append("=<");
    Map<String, String> props = metadata.getPropertiesRaw();
    int i = 0;
    for (Map.Entry<String, String> entry : props.entrySet()) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(entry.getKey()).append("=").append(entry.getValue());
      i++;
    }
    sb.append(">");
    return sb.toString();
  }

  public static class Builder {

    private @Nullable Path m_file;
    private @Nullable PrintWriter m_writer;
    private boolean m_validateBeforeWrite;

    /**
     * Files are written as UTF-8, matching {@link org.pharmgkb.parser.vcf.VcfParser.Builder#fromFile}.
     */
    public Builder toFile(Path file) {
      m_file = file;
      return this;
    }

    public Builder toWriter(PrintWriter writer) {
      m_writer = writer;
      return this;
    }

    /**
     * Enables full-output diagnostics. Before writing, the writer validates metadata, positions, INFO, FORMAT, and
     * sample values. It rejects content that would produce structurally invalid VCF and warns about detected semantic
     * non-compliance. This is off by default to keep the direct parse-and-write path fast.
     */
    public Builder validateBeforeWrite() {
      m_validateBeforeWrite = true;
      return this;
    }

    public VcfWriter build() throws IOException {
      if (m_file != null) {
        // explicit UTF-8 encoding is stricter than the old FileWriter's platform-default REPLACE behavior: an
        // unpaired surrogate in metadata now triggers an encoding failure in the underlying encoder instead of
        // being silently replaced with "?", matching VcfParser's Files.newBufferedReader decoder on the read
        // side. That failure does NOT throw here or propagate up through print()/write() -- PrintWriter catches
        // any IOException from its underlying writer internally and only sets its own error flag. It's
        // checkForWriteError() (called from printLine()'s periodic check, writeHeader(), and close()) that
        // actually turns this into a visible UncheckedIOException.
        m_writer = new PrintWriter(new BufferedWriter(
            new OutputStreamWriter(Files.newOutputStream(m_file), StandardCharsets.UTF_8), 65536));
      }
      if (m_writer == null) {
        throw new IllegalStateException("Must specify either file or writer");
      }
      return new VcfWriter(m_file, m_writer, m_validateBeforeWrite);
    }

  }

  private UncheckedIOException writeError() {
    return new UncheckedIOException(new IOException(
        "Error writing VCF output after line " + m_lineNumber + " (disk full, broken pipe, or encoding failure)"));
  }

  private void checkForWriteError() {
    if (m_writer.checkError()) {
      UncheckedIOException e = writeError();
      m_valid = false;
      m_invalidatingCause = e;
      throw e;
    }
  }

  private void printLine(Object line) {
    String string = line.toString();
    if (string.contains("\n") || string.contains("\r")) {
      throw new RuntimeException("Something went wrong writing line #" + m_lineNumber + ": [[[" + string +
          "]]] contains a line terminator");
    }
    // always terminate with LF (not the platform separator from println) so output is deterministic across platforms
    m_writer.print(string);
    m_writer.print('\n');
    m_lineNumber++;
    if (m_lineNumber % 1000 == 0) {
      sf_logger.debug("Wrote {} lines{}", m_lineNumber, (m_file == null ? "" : " to " + m_file));
      checkForWriteError();
    }
  }

}

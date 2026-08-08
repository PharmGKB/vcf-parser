package org.pharmgkb.parser.vcf;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
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
import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Writes to VCF format from a {@link VcfSample}, {@link VcfPosition VcfPositions}, and {@link VcfMetadata}.
 * The default write path stays fast by performing little validation of its own; build with
 * {@link Builder#validateBeforeWrite()} for full structural validation and semantic-compliance diagnostics.
 * <p>
 * {@link #close} must always be called to guarantee output is completely flushed and any write failure
 * (disk full, broken pipe, or an encoding failure) is reported -- such a failure surfaces as an unchecked
 * {@link java.io.UncheckedIOException} from {@link #writeHeader}, {@link #writeLine}, or {@link #close} itself,
 * and permanently invalidates this instance: the underlying writer may be left in a partially-written or otherwise
 * inconsistent state, so retrying is never safe and no further work is attempted. A write failure detected
 * mid-stream (not just at {@link #close}) also releases the underlying resource immediately, as part of the same
 * failure, rather than leaving it open until some later {@link #close} call. Call {@link #isValid()} to
 * check without triggering an exception; once invalid, {@link #writeHeader} and {@link #writeLine} both throw
 * {@link IllegalStateException} (with the exception that first signaled the failure as the cause) instead
 * of doing anything further. A {@link VcfFormatException} thrown for ordinary content rejection (e.g. a
 * sample-count mismatch, or invalid metadata in {@link Builder#validateBeforeWrite()} mode), by contrast,
 * does not invalidate this instance -- nothing was corrupted, so a corrected call can succeed normally afterward.
 * <p>
 * Closing this instance, even cleanly with no error, also permanently invalidates it the same way -- a
 * {@link #writeHeader}/{@link #writeLine} call made afterward throws {@link IllegalStateException} instead of
 * silently being ignored by the now-closed underlying writer. {@link #close} itself is idempotent: a second
 * call has no effect, matching {@link java.io.Closeable#close()}'s own documented contract, regardless of
 * whether the first call threw.
 *
 * @author Douglas Myers-Turnbull
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

  private boolean m_closed;
  private @Nullable Exception m_invalidatingCause;
  private final VcfWarnings m_warnings;
  private final @Nullable String m_targetFileFormat;
  // the context for this write. Built in writeHeader() -- the Builder has no metadata to build it from -- and
  // rebuilt on each writeHeader() attempt, since a content rejection permits a corrected retry with different
  // metadata. writeLine() cannot be reached before writeHeader() has succeeded (it throws) with anything but
  // that same metadata instance (it throws for that too), so by then it is fixed.
  private @Nullable VcfContext m_context;

  private VcfWriter(@Nullable Path file, PrintWriter writer, boolean validateBeforeWrite, VcfWarnings warnings,
      @Nullable String targetFileFormat) {
    m_file = file;
    m_writer = writer;
    m_validateBeforeWrite = validateBeforeWrite;
    m_warnings = warnings;
    m_targetFileFormat = targetFileFormat;
  }

  /**
   * @return {@code false} once a write failure has permanently invalidated this instance, or once this instance
   * has been closed (see the class Javadoc); {@code true} otherwise.
   */
  public boolean isValid() {
    return !m_closed;
  }

  private void checkValid() {
    if (m_closed) {
      if (m_invalidatingCause != null) {
        throw new IllegalStateException("This VcfWriter is no longer usable after a write failure", m_invalidatingCause);
      }
      throw new IllegalStateException("This VcfWriter is no longer usable after being closed");
    }
  }

  /**
   * Writes the VCF header. Must not be called more than once per instance.
   *
   * @throws IllegalStateException if {@link #isValid()} is already {@code false} -- this instance must be
   * abandoned after any prior write failure, not reused.
   * @throws UncheckedIOException if a write failure is detected here. This permanently invalidates the instance
   * and releases the underlying writer immediately (see the class Javadoc) -- retrying is never safe, since some
   * header lines may have already reached the underlying writer.
   */
  public void writeHeader(VcfMetadata metadata) {
    checkValid();
    if (metadata == null) {
      throw new VcfFormatException("writeHeader() was called with a null metadata");
    }
    if (m_headerWritten) {
      throw new VcfFormatException("writeHeader() was already called; a VCF file must have exactly one header");
    }
    // Reject a target/metadata version mismatch before anything else happens, and in particular before a single
    // byte is emitted: AGENTS.md's Writer Validation and Round-Trip Policy states a writer "must not emit a
    // fileformat declaration for another VCF version unless the emitted output is also valid under that
    // declared version", and this library implements no conversion, so any mismatch would advertise a version
    // the data does not conform to.
    //
    // Compared canonicalized, not raw and not resolved. Raw-string comparison would reject
    // targetFileFormat("VCFv4.02") against VCFv4.2 metadata, though VcfVersion deliberately treats those as the
    // same version. Resolved-version comparison would be wrong the other way: VCFv4.7 and VCFv4.9 both fall
    // back to 4.3 and would compare equal despite being genuinely different declarations.
    String targetFileFormat = m_targetFileFormat;
    if (targetFileFormat != null
        && !VcfVersion.canonicalize(targetFileFormat).equals(VcfVersion.canonicalize(metadata.getFileFormat()))) {
      throw new VcfFormatException("Writer target " + targetFileFormat + " does not match the metadata's "
          + metadata.getFileFormat() + "; cross-version conversion is not supported");
    }
    // Rebuilt on every writeHeader() attempt, deliberately NOT cached across attempts. A content rejection
    // below (validateMetadata, a line-terminator check) does not invalidate this writer -- only an IO failure
    // does -- so a corrected retry with different metadata is supported and tested
    // (testHeaderContentRejectionAllowsCorrectedRetry). Caching the first attempt's context would leave that
    // retry writing the REJECTED metadata's ##fileformat over the accepted metadata's content, which is the
    // very thing the cross-version check above exists to prevent, reached around it (that check only compares
    // m_targetFileFormat, which is null in the inherit-from-metadata case).
    m_context = VcfContext.lenient(declaredFileFormatFor(metadata), m_warnings);
    // claim after this method's own structural checks, matching writeLine()'s convention: a rejected call must not
    // leave every metadata declaration's warning sink re-pointed at this writer as a side effect
    claimMetadataWarningsSinks(metadata);
    if (m_validateBeforeWrite) {
      validateMetadata(metadata);
      checkSampleMetadataMatchesChromColumns(metadata);
    }

    List<String> lines = buildHeaderLines(metadata);
    // check every line for a line terminator BEFORE printing any of them, so this rejection is atomic relative to
    // the underlying writer -- otherwise a rejection on a later line would leave earlier lines (including
    // ##fileformat, always line 1) already written, and a caller's documented-safe retry of writeHeader() would
    // then duplicate them.
    for (String line : lines) {
      if (line.contains("\n") || line.contains("\r")) {
        throw new VcfFormatException("Header line contains a line terminator: [[[" + line + "]]]");
      }
    }
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

    // Derived from the argument, not read back off m_context, for two reasons. It keeps a retried writeHeader()
    // honest (see that method's own comment), and it keeps writeLine()'s header-text snapshot able to detect a
    // post-header setFileFormat() change: sourcing this from the fixed context would make the recomputed header
    // text identical no matter what the caller did to the metadata's own declaration afterward. (VcfMetadata's
    // setFileFormat only permits a re-spelling of the same version, e.g. VCFv4.2 -> VCFv4.02, since it must
    // agree with its own context -- but that still changes the emitted bytes, so it must still be caught.)
    lines.add("##fileformat=" + declaredFileFormatFor(metadata));

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
   * @return the {@code ##fileformat} value this writer emits for {@code metadata}: an explicit
   * {@link Builder#targetFileFormat} if one was set, otherwise the metadata's own declaration. Returned
   * verbatim -- {@code writeHeader}'s canonicalized cross-version check decides only whether the two agree,
   * never what gets written -- so {@code targetFileFormat("VCFv4.02")} emits {@code ##fileformat=VCFv4.02}.
   */
  private String declaredFileFormatFor(VcfMetadata metadata) {
    return m_targetFileFormat != null ? m_targetFileFormat : metadata.getFileFormat();
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
   * <p>
   * {@link #writeHeader} must be called first, and {@code metadata} must be the same object instance given to it.
   *
   * @throws IllegalStateException if {@link #isValid()} is already {@code false} -- this instance must be
   * abandoned after any prior write failure, not reused.
   * @throws UncheckedIOException if a write failure is detected (only possible on a call that lands on the
   * periodic 1000-line checkpoint; most calls cannot throw this). This permanently invalidates the instance and
   * releases the underlying writer immediately (see the class Javadoc) -- retrying is never safe, since some data
   * may have already reached the underlying writer.
   */
  public void writeLine(VcfMetadata metadata, VcfPosition position,
      List<VcfSample> samples) {
    checkValid();
    if (!m_headerWritten) {
      throw new VcfFormatException("writeLine() was called before writeHeader(); every VCF file must have a " +
          "header before any data line");
    }
    // a null metadata is already rejected cleanly here: m_headerMetadata is guaranteed non-null once
    // m_headerWritten is true (writeHeader() itself now rejects a null metadata), so null != m_headerMetadata
    // is true and this throws, rather than needing its own explicit null check
    if (metadata != m_headerMetadata) {
      throw new VcfFormatException("writeLine() was given different metadata than writeHeader() used; the " +
          "emitted header and this record would be inconsistent");
    }
    if (position == null) {
      throw new VcfFormatException("writeLine() was called with a null position");
    }
    if (samples == null) {
      throw new VcfFormatException("writeLine() was called with a null samples list");
    }
    for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
      if (samples.get(sampleIndex) == null) {
        throw new VcfFormatException("Sample #" + sampleIndex + " is null");
      }
    }
    position.setWarningsSink(m_warnings);
    for (VcfSample sample : samples) {
      sample.setWarningsSink(m_warnings);
    }
    if (m_validateBeforeWrite) {
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
    // getChromosome()/getRef() are set via setChromosome()/setRef(), which -- like the live-view accessors above
    // -- do not validate their input by design (see VcfPosition.validate()'s Javadoc); position.validate() above
    // (validateBeforeWrite() mode only) would catch a null here via checkChromosome()/checkRef(), but the
    // default path needs its own guard, since neither has a missing-value sentinel in the spec ("." means
    // something else entirely for other columns) and StringBuilder.append(Object) would otherwise silently
    // stringify a null into the literal text "null" instead of a clean rejection.
    String chromosome = position.getChromosome();
    if (chromosome == null) {
      throw new VcfFormatException("Position has a null CHROM, which the VCF spec does not allow (CHROM has no " +
          "missing-value sentinel)");
    }
    String ref = position.getRef();
    if (ref == null || ref.isEmpty()) {
      // writing "." here would not actually be valid VCF, just a placeholder that avoids crashing on a mutated
      // position
      throw new VcfFormatException("Position " + chromosome + ":" + position.getPosition() +
          " has a null or empty REF, which the VCF spec does not allow (REF has no missing-value sentinel)");
    }

    StringBuilder sb = new StringBuilder();

    sb.append(chromosome).append("\t");
    sb.append(position.getPosition()).append("\t");
    addListOrElse("ID", position.getIds(), ";", ".", false, sb);
    sb.append(ref).append("\t");
    // ALT is position-dependent (GT genotype values index into it), so an empty entry is substituted with "."
    // rather than dropped -- dropping would shift every later allele's index; see EMPTY_FIELD_HANDLING.md.
    addListOrElse("ALT", position.getAltBases(), ",", ".", true, sb);
    addStringOrElse(position.getQuality(), ".", sb);
    addListOrElse("FILTER", position.getFilters(), ";", ".", false, sb);
    addInfoOrDot(metadata, position, sb);

    // "PASS" is VCF's reserved "all filters passed" keyword (VCFv4.2.tex line 185: "PASS if this position has
    // passed all filters"), never expected to have its own ##FILTER=<ID=PASS,...> declaration. This filter must
    // exclude it explicitly for two reasons: since VcfPosition.checkFilters() now warns instead of throwing when
    // FILTER combines PASS with another code, position.getFilters() can legitimately contain a literal "PASS"
    // entry alongside other codes -- a case this check could never previously see, because that combination used
    // to be rejected during parsing before reaching the writer. More significantly, VcfPosition.getFilterStatus()
    // now derives FilterStatus.PASSED from a literal ["PASS"] list, so this stream sees a literal "PASS" entry
    // for every ordinary passing record now, not just the combined-with-another-code edge case.
    // "." (FILTER "not applied") is excluded for the same reason: it's VCF's other reserved FILTER sentinel, also
    // never expected to have its own ##FILTER=<ID=.,...> declaration. Not normally reachable as a list element --
    // VcfPosition.normalizeFilters() collapses a lone "." into an empty list, and checkFilters() rejects "."
    // combined with another code -- but a caller can still produce it via direct mutation of the live list
    // returned by getFilters() without calling validate() afterward.
    position.getFilters().stream()
        .filter(key -> !key.equals("PASS") && !key.equals(".") && !metadata.getFilters().containsKey(key))
        .forEach(key ->
            m_warnings.warn("Position {}:{} has FILTER {}, but there is no FILTER metadata with that name (on line {})",
                position.getChromosome(), position.getPosition(), key, lineBeingWritten()));

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
   * any pending write failure is reported -- see the class Javadoc. Idempotent: a second call after the first has
   * no effect, matching {@link Closeable#close()}'s own documented contract, regardless of whether the first call
   * threw.
   *
   * @throws UncheckedIOException if a write failure is detected here (this call's own, e.g. from the underlying
   * stream's own {@code close()}, or one that hadn't yet been caught by a periodic check because fewer than 1000
   * lines were written since the last one). The underlying writer is always released first, even when this is
   * thrown. This also permanently invalidates the instance, the same as a failure detected by {@link #writeHeader}
   * or {@link #writeLine}.
   */
  @Override
  public void close() {
    if (m_closed) {
      return; // idempotent: a second close() has no effect, regardless of whether the first one threw
    }
    // checkError() flushes before checking (see its own Javadoc), so this first call catches anything still
    // buffered from writeLine()/writeHeader(). closeUnderlyingWriter() always runs next regardless, so the
    // resource is never leaked even when we're about to throw. The second checkError() (after closing)
    // catches a failure from the underlying stream's own close() (e.g. an fsync-on-close error) -- safe to call
    // again here because a closed PrintWriter's checkError() no longer attempts to flush.
    boolean hadError = m_writer.checkError();
    closeUnderlyingWriter();
    if (hadError || m_writer.checkError()) {
      throw recordWriteError();
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
      // position.getFormat() is a live view a caller can insert a null entry into directly, bypassing
      // VcfPosition.validate()'s own checkFormat() null guard, which only runs under validateBeforeWrite(). On the
      // default path, StringBuilder.append(Object) would otherwise silently stringify a null key into the literal
      // text "null" instead of a clean rejection.
      String key = formats.next();
      if (key == null) {
        throw new VcfFormatException("FORMAT contains a null sub-field name");
      }
      sb.append(key);
      if (formats.hasNext()) {
        sb.append(":");
      }
    }
    sb.append("\t");
  }

  private void validateMetadata(VcfMetadata metadata) {
    if (!InternalUtils.FILE_FORMAT_PATTERN.matcher(metadata.getFileFormat()).matches()) {
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
      // getPedigrees() is a live view, so a caller can insert a null element after writeHeader() already ran
      // claimMetadataWarningsSinks()'s own null check -- same reasoning as validateKeyedMetadataEntries()'s
      // identical guard above. Currently masked on both of validateMetadata()'s call sites: in writeHeader(),
      // validateMetadata() runs BEFORE buildHeaderLines()/addMetadataLines(), so claimMetadataWarningsSinks()'s
      // own checkNoNullMetadataEntries() (called even earlier, at line 127) is what actually catches it there;
      // in writeLine()'s post-header-mutation check, buildHeaderLines()/addMetadataLines() runs first instead.
      // Kept anyway so this method stays a correct, self-contained validation pass on its own terms -- not
      // dependent on call-order elsewhere in this class remaining exactly as it is today.
      if (pedigree == null) {
        throw new VcfFormatException("PEDIGREE metadata contains a null entry");
      }
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
        m_warnings.warn("Duplicate ID \"{}\" for PEDIGREE metadata", id);
      }
    }
    Set<String> sampleNames = new HashSet<>();
    for (int i = 0; i < metadata.getNumSamples(); i++) {
      String sampleName = metadata.getSampleName(i);
      InternalUtils.checkNoLineTerminator("sample name", sampleName);
      if (sampleName.isEmpty() || sampleName.contains("\t") || !sampleNames.add(sampleName)) {
        throw new VcfFormatException("Invalid or duplicate sample name in header: " + sampleName);
      }
    }
    for (String key : metadata.getRawPropertyKeys()) {
      // getRawProperties()/getRawValuesOfProperty() are live views (see their Javadoc), so a caller can insert a
      // null key/value directly, bypassing every check addRawProperty() would otherwise run at insertion time
      if (key == null) {
        throw new VcfFormatException("Metadata contains a null raw property name");
      }
      // a raw property is written as "##" + key + "=" + value, and reread by splitting on the FIRST "=" only
      // (VcfParser.parseMetadata); a key containing '=' shifts that split point, so the key/value recovered on
      // reparse would not match what was written. Verified live: writing key "foo=bar" reparses as key "foo",
      // value "bar=value" instead of key "foo=bar", value "value".
      if (key.contains("=")) {
        throw new VcfFormatException("Metadata property name \"" + key + "\" contains '=', which would shift " +
            "where the key/value split falls when re-read");
      }
      if (InternalUtils.isReservedMetadataKey(key)) {
        // getRawProperties() returns the backing multimap directly, so a caller can insert this after
        // addRawProperty()'s own check has already run once, at insertion time; unlike that check, this one
        // rejects outright, since a raw "INFO"/"FORMAT"/etc. property would produce output VcfParser cannot
        // read back as what was written (see VcfParser.parseMetadata's switch)
        throw new VcfFormatException("Metadata property name \"" + key + "\" collides with a structured " +
            "declaration keyword");
      }
      for (String value : metadata.getRawValuesOfProperty(key)) {
        if (value == null) {
          throw new VcfFormatException("Metadata property \"" + key + "\" has a null value");
        }
        InternalUtils.checkNoLineTerminator(key, value);
        if (VcfMetadata.isEmptyMetadataValue(value)) {
          // unlike VcfMetadata.Builder.addRawProperty()/addAssembly()/addPedigreeDatabase() (which drop an empty
          // value at insertion time), this only warns -- silently removing content the caller is already holding
          // a reference to, right before serializing it, is a bigger behavioral surprise than dropping it before
          // the caller ever sees the parsed object. bcftools 1.23.1 silently preserves a plain empty generic
          // property value unchanged, so there is no round-trip hazard here to reject over.
          m_warnings.warn("Metadata property \"{}\" has an empty value: {}", key, value);
        }
        if (VcfMetadata.collidesWithAttributeListGrammar(value)) {
          // unlike the empty-value case above, this rejects outright rather than warning: getRawProperties()/
          // getRawValuesOfProperty() are live views, so a caller can insert a bracket-wrapped value here after
          // addRawProperty()/addAssembly()/addPedigreeDatabase()'s own insertion-time rejection has already run
          // once. Confirmed directly that bcftools 1.23.1 hard-errors ("Could not parse the header line") on a
          // generic property value shaped like this -- see VcfMetadata.collidesWithAttributeListGrammar's own comment --
          // so writing it out would produce output a reference tool cannot read back at all, not merely
          // semantically non-compliant content this policy would otherwise preserve. This is the
          // structurally-unparseable-output case AGENTS.md's Writer Validation and Round-Trip Policy calls out
          // as the one thing validateBeforeWrite() must reject rather than warn about.
          throw new VcfFormatException("Metadata property \"" + key + "\" (" + value + ") is wrapped in angle " +
              "brackets with no key=value content, which bcftools cannot parse as a generic property value");
        }
      }
    }
  }

  // mirrors VcfParser's identical post-parse check (same warning text). Called once, from writeHeader() only --
  // not from validateMetadata() (which also runs per writeLine() call) -- since #CHROM's sample-column list is
  // fixed forever once VcfMetadata is built (VcfMetadata.Builder.build() copies it into an immutable list with no
  // supported mutation path), so this can only ever need re-checking if ##SAMPLE itself changes. A caller who
  // mutates ##SAMPLE (via addSample()/removeSample(), or by mutating the map getSamples() returns directly) after
  // writeHeader() has already run is already rejected outright by writeLine()'s own header-text-snapshot check
  // (m_headerText) before this method's caller would ever be reached again -- so checking only here, once, misses
  // nothing a later writeLine() call could otherwise have caught.
  private void checkSampleMetadataMatchesChromColumns(VcfMetadata metadata) {
    if (metadata.getNumSamples() == metadata.getSamples().size()) {
      for (int i = 0; i < metadata.getNumSamples(); i++) {
        String sampleName = metadata.getSampleName(i);
        if (!metadata.getSamples().containsKey(sampleName)) {
          m_warnings.warn("Sample {} is missing in the metadata", sampleName);
        }
      }
    } else {
      m_warnings.warn("There are {} samples in the header but {} in the metadata", metadata.getNumSamples(),
          metadata.getSamples().size());
    }
  }

  /**
   * Claims every metadata declaration's warning sink for this writer, unconditionally -- called from
   * {@link #writeHeader} after its own structural checks (non-null metadata, header-not-already-written) but
   * before {@link #buildHeaderLines} or {@link #validateMetadata} run, so a lazy warning from any of these
   * objects (today: none exist, but this matches {@link #writeLine}'s identical claiming of {@code position}/
   * each {@code sample} and the design's own stated contract for forward compatibility) is always captured by
   * this writer's own destination.
   *
   * @throws VcfFormatException if any declaration map/list contains a null entry -- checked before claiming
   * anything, so a null found partway through never leaves some entries claimed and others not
   */
  private void claimMetadataWarningsSinks(VcfMetadata metadata) {
    checkNoNullMetadataEntries("INFO", metadata.getInfo().values());
    checkNoNullMetadataEntries("FILTER", metadata.getFilters().values());
    checkNoNullMetadataEntries("FORMAT", metadata.getFormats().values());
    checkNoNullMetadataEntries("ALT", metadata.getAlts().values());
    checkNoNullMetadataEntries("contig", metadata.getContigs().values());
    checkNoNullMetadataEntries("SAMPLE", metadata.getSamples().values());
    checkNoNullMetadataEntries("PEDIGREE", metadata.getPedigrees());
    metadata.getInfo().values().forEach(entry -> entry.setWarningsSink(m_warnings));
    metadata.getFilters().values().forEach(entry -> entry.setWarningsSink(m_warnings));
    metadata.getFormats().values().forEach(entry -> entry.setWarningsSink(m_warnings));
    metadata.getAlts().values().forEach(entry -> entry.setWarningsSink(m_warnings));
    metadata.getContigs().values().forEach(entry -> entry.setWarningsSink(m_warnings));
    metadata.getSamples().values().forEach(entry -> entry.setWarningsSink(m_warnings));
    metadata.getPedigrees().forEach(entry -> entry.setWarningsSink(m_warnings));
  }

  private static void checkNoNullMetadataEntries(String kind, Collection<? extends BaseMetadata> entries) {
    for (BaseMetadata entry : entries) {
      if (entry == null) {
        throw new VcfFormatException(kind + " metadata contains a null entry");
      }
    }
  }

  /**
   * Validates every entry of a metadata map (INFO/FORMAT/FILTER/ALT/contig/SAMPLE), including that each entry's own
   * {@code ID} property still matches the map key it's stored under. That invariant normally holds automatically
   * (these maps are keyed by {@code getId()} when built), but {@link VcfMetadata#getInfo()} and its siblings return
   * the backing map directly, so a caller can mutate an entry's raw {@code ID} property (via
   * {@link BaseMetadata#getPropertiesRaw()}) -- changing it to a different value, or removing it entirely --
   * without it being reflected in the map key. Left undetected, the header would then declare a different (or
   * missing) ID than what data lines actually reference by that entry's map key, and a duplicate-ID check across
   * entries would also be unable to catch a real collision this way (see below).
   */
  private void validateKeyedMetadataEntries(String kind, Map<String, ? extends BaseMetadata> entriesByKey) {
    for (Map.Entry<String, ? extends BaseMetadata> mapEntry : entriesByKey.entrySet()) {
      String mapKey = mapEntry.getKey();
      BaseMetadata entry = mapEntry.getValue();
      // entriesByKey is a live view (see this method's own Javadoc), so a caller can insert a null value after
      // writeHeader() already ran claimMetadataWarningsSinks()'s own null check. Currently masked on both of
      // this method's call sites: in writeHeader(), this method runs BEFORE buildHeaderLines()/
      // addMetadataLines(), so claimMetadataWarningsSinks()'s own checkNoNullMetadataEntries() (called even
      // earlier, at line 127) is what actually catches it there; in writeLine()'s post-header-mutation check,
      // buildHeaderLines()/addMetadataLines() runs first instead. Kept anyway so validateMetadata() stays a
      // correct, self-contained validation pass on its own terms -- not dependent on call-order elsewhere in
      // this class remaining exactly as it is today.
      if (entry == null) {
        throw new VcfFormatException(kind + " metadata contains a null entry");
      }
      entry.validate();
      if ("ALT".equals(kind) && entry instanceof IdDescriptionMetadata idDescriptionMetadata) {
        VcfContext context = m_context;
        assert context != null; // validateMetadata only runs from writeHeader/writeLine, after the context exists
        VcfMetadata.validateAlt(idDescriptionMetadata, context);
      }
      Map<String, String> properties = entry.getPropertiesRaw();
      validateMetadataProperties(kind, properties);
      String id = properties.get("ID");
      // duplicate IDs across different entries can't occur once every entry's ID is confirmed to match its own
      // (necessarily unique) map key, so no separate "duplicate ID" check is needed here
      // mapKey is never null on any path that goes through VcfMetadata's/Builder's addXxx methods (all reject/
      // drop a null-getId() entry before put), but entriesByKey is the same live, caller-mutable view this
      // method already guards for a null VALUE (see the null-check above) -- LinkedHashMap tolerates a null
      // KEY too, so metadata.getInfo().put(null, someInfoMetadata) reaches here with mapKey itself null.
      // Guarded explicitly rather than left to a raw NullPointerException at mapKey.equals(id).
      if (mapKey == null) {
        throw new VcfFormatException(kind + " metadata contains a null key");
      }
      // VCFv4.3.tex line 121 ("All structured lines require an ID...") makes ID mandatory for all six keyed
      // types; VCFv4.2 doesn't use the word "required" but its own templates for all six types show ID
      // unconditionally first -- same guidance IdMetadata's own constructor already cites (IdMetadata.java:20-21;
      // see DUPLICATE_METADATA_ID_HANDLING.md) -- so an entirely-missing ID (id == null) is just as much a drift
      // from the map key as a changed one, and is also caught by !mapKey.equals(id) below.
      if (!mapKey.equals(id)) {
        String actual = id == null ? "no ID property at all" : "an ID property of \"" + id + "\"";
        throw new VcfFormatException(kind + " metadata is stored under key \"" + mapKey + "\" but has " + actual +
            "; the header would declare a different (or missing) ID than records referencing this entry by its " +
            "map key");
      }
    }
  }

  // both call sites pass the same live map an entry.validate()/pedigree.validate() call already ran against,
  // immediately before this method runs -- BaseMetadata.validate() already calls checkNoLineTerminator(key,
  // value) for every property in that same map, so redoing it here would just repeat that work per property.
  // It also now rejects a null value itself, so a null here is already unreachable -- no separate check needed.
  private void validateMetadataProperties(String kind, Map<String, String> properties) {
    for (Map.Entry<String, String> property : properties.entrySet()) {
      String propertyName = property.getKey();
      if (propertyName == null || propertyName.isEmpty() || propertyName.contains(",") ||
          propertyName.contains("=")) {
        throw new VcfFormatException(kind + " metadata contains an invalid property name: " + property.getKey());
      }
      String value = property.getValue();
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
   * convention {@link InternalUtils}'s top-level splitter uses). {@link #isQuoted} already rejects a value that starts
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
        m_warnings.warn("Position {}:{} contains INFO {}, but there is no INFO metadata with that name",
            position.getChromosome(), position.getPosition(), key);
        // still validate a reserved key's value against its own canonical Number/Type, matching
        // getInfo(ReservedInfoProperty)'s identical read-path fallback for exactly this situation -- an
        // undeclared reserved key otherwise silently skipped every check below, even though its expected
        // shape is well known independent of any header declaration
        ReservedInfoProperty reserved = ReservedInfoProperty.fromId(key);
        if (reserved != null) {
          validateInfoValue(reserved.getExpectedNumber(), reserved.getType(), values, key,
              position.getAltBases().size());
        }
      } else {
        validateInfoValue(info.getNumber(), info.getType() == null ? null : InternalUtils.toJavaType(info.getType()),
            values, key, position.getAltBases().size());
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
          m_warnings.warn("Sample #{} is missing property {}", sampleIndex, key);
        } else if (format == null) {
          m_warnings.warn("Sample #{} contains FORMAT {}, but there is no FORMAT metadata with that name", sampleIndex, key);
          // still validate a reserved key's value against its own canonical Number/Type, matching
          // getProperty(VcfPosition, VcfMetadata, String)'s identical read-path fallback for exactly this
          // situation -- see validateInfo's identical INFO-side fallback for the full rationale
          ReservedFormatProperty reserved = ReservedFormatProperty.fromId(key);
          if (reserved != null) {
            validateFormatValue(reserved.getExpectedNumber(), reserved.getType(), value, key, sampleIndex,
                position.getAltBases().size(), ploidy);
          }
        } else {
          validateFormatValue(format.getNumber(),
              format.getType() == null ? null : InternalUtils.toJavaType(format.getType()), value, key, sampleIndex,
              position.getAltBases().size(), ploidy);
        }
      }
    }
  }

  // number/type are the resolved Number/Type to validate against -- either a declared ##INFO=<ID=...>'s own
  // values, or (when there is no declaration at all) a reserved key's own canonical Number/Type, matching
  // getInfo(ReservedInfoProperty)'s identical read-path fallback. type is the already-converted Java type
  // (see InternalUtils.toJavaType(InfoType)/ReservedInfoProperty.getType()), not the InfoType enum itself, so
  // this one method serves both callers without needing a synthetic InfoMetadata for the reserved-only case.
  private void validateInfoValue(@Nullable String number, @Nullable Class<?> type, List<String> values, String key,
      int numAltAlleles) {
    if (type == Boolean.class) {
      if (values.size() != 1 || !values.get(0).isEmpty()) {
        m_warnings.warn("INFO {} has Type=Flag but has a value", key);
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
    // mirrors validateFormatValue's own checkReservedFormatConstraints call: AC/AN/DP/END/MQ0/NS/AF go beyond
    // what their declared Type can express (see InternalUtils.checkReservedInfoConstraints's own Javadoc), so
    // this is checked separately, both here (the write/diagnostics path) and via getInfo(ReservedInfoProperty)
    // (the read path, through convertProperty(ReservedProperty, ...)'s dispatch)
    InternalUtils.checkReservedInfoConstraints(key, String.join(",", values), m_warnings);
    if (values.stream().anyMatch(String::isEmpty)) {
      m_warnings.warn("INFO {} contains an empty value", key);
    }
    // unlike a FORMAT value, an INFO value isn't tied to any one sample, so there's no single ploidy to compute
    // Number=G's expected cardinality from; pass null so getExpectedCardinality skips that check instead of
    // guessing a ploidy (previously hardcoded to 2, which could produce the same false-positive warning that
    // getPloidy's ploidy==null handling was introduced to prevent for FORMAT values)
    InternalUtils.warnIfWrongCardinality("INFO " + key, number, values.size(), numAltAlleles, null, m_warnings);
    if (type == null) {
      return;
    }
    for (String value : values) {
      if (value.isEmpty()) {
        // already reported generically above ("INFO {} contains an empty value"); convertProperty()'s own
        // empty-value handling would otherwise warn about the same defect a second time, with no key context
        continue;
      }
      try {
        InternalUtils.convertProperty(type, value, false, m_warnings);
      } catch (VcfFormatException e) {
        m_warnings.warn("INFO {} value {} is not of type {}", key, value, type.getSimpleName());
      }
    }
  }

  // number/type are the resolved Number/Type to validate against -- either a declared ##FORMAT=<ID=...>'s own
  // values, or (when there is no declaration at all) a reserved key's own canonical Number/Type, matching
  // getProperty(VcfPosition, VcfMetadata, String)'s identical read-path fallback. type is the already-converted
  // Java type (see InternalUtils.toJavaType(FormatType)/ReservedFormatProperty.getType()), not the FormatType
  // enum itself, so this one method serves both callers without needing a synthetic FormatMetadata for the
  // reserved-only case.
  private void validateFormatValue(@Nullable String number, @Nullable Class<?> type, String value, String key,
      int sampleIndex, int numAltAlleles, @Nullable Integer ploidy) {
    if (value.equals(".")) {
      return;
    }
    if (!value.isEmpty()) {
      // an empty value is already reported generically below (for any FORMAT key, not just FT/PS); avoid warning
      // about it twice via checkReservedFormatConstraints's own "empty filter code" check. The returned normalized
      // value is discarded: this writer preserves raw content rather than rewriting it, consistent with how a
      // reserved list-typed FORMAT value's empty entry is also only normalized on read, not on write.
      InternalUtils.checkReservedFormatConstraints(key, value, m_warnings);
    }
    String[] values = InternalUtils.splitFormatValue(key, value);
    for (String element : values) {
      if (element.isEmpty()) {
        m_warnings.warn("FORMAT {} for sample #{} contains an empty value", key, sampleIndex);
      }
    }
    if (!InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck(key)) {
      // GLE's conventional Number=G does not describe a comma-delimited value list the way it does for every other
      // FORMAT key -- GLE's commas are part of its own internal genotype:likelihood encoding (see
      // ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy), so values.length is always 1 here and
      // comparing it against Number=G's combinatorial expected count would always be a false mismatch. CNP's
      // value IS a genuine comma-delimited list, but its own Number=G means "per potential copy number," not
      // the ploidy-derived genotype ordering this combinatorial count assumes -- see
      // InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck and
      // ReservedFormatProperty.CopyNumberPosteriorProbabilities' own comment. GL/PL/GP/PP's own spec-stated
      // "diploidy is assumed" default when GT is absent -- see
      // InternalUtils.resolvePloidyForCardinalityCheck's own comment for the full citation -- is applied per
      // key here rather than once for the whole sample, since ploidy is computed once per sample (in
      // validateSamples, shared across every FORMAT key) and this default applies only to these four keys.
      InternalUtils.warnIfWrongCardinality("FORMAT " + key + " for sample #" + sampleIndex, number, values.length,
          numAltAlleles, InternalUtils.resolvePloidyForCardinalityCheck(key, ploidy), m_warnings);
    }
    if (type == null) {
      return;
    }
    for (String element : values) {
      if (element.isEmpty()) {
        // already reported generically above ("FORMAT {} for sample #{} contains an empty value");
        // convertProperty()'s own empty-value handling would otherwise warn about the same defect a second
        // time, with no key/sample context
        continue;
      }
      try {
        InternalUtils.convertProperty(type, element, false, m_warnings);
      } catch (VcfFormatException e) {
        m_warnings.warn("FORMAT {} for sample #{} value {} is not of type {}", key, sampleIndex, element,
            type.getSimpleName());
      }
    }
  }

  /**
   * @return the ploidy inferred from this sample's GT value, or {@code null} if GT is absent and ploidy can
   * therefore not be determined (previously defaulted to 2, which could produce a false Number=G cardinality
   * warning for a haploid, or higher-ploidy, sample whose GT simply isn't present in this record). GL/PL/GP/PP
   * still get an assumed ploidy of 2 when this returns {@code null} -- their own spec-stated default, unlike
   * the blanket default this method itself no longer applies -- but that substitution happens per key, in
   * {@code validateFormatValue} via {@code InternalUtils.resolvePloidyForCardinalityCheck}, not here.
   */
  private @Nullable Integer getPloidy(VcfPosition position, VcfSample sample, int sampleIndex) {
    return InternalUtils.getPloidy(position, sample.getProperty("GT"), "Sample #" + sampleIndex, m_warnings);
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
        m_warnings.warn("Sample #{} has propert(y/ies) set, but FORMAT is empty ('.'); they will not be written " +
            "(on line {})", sampleIndex, lineBeingWritten());
      }
      sb.append(".\t");
      return;
    }

    for (int i = 0; i < formatKeys.size(); i++) {
      String key = formatKeys.get(i);

      if (!m_validateBeforeWrite && !metadata.getFormats().containsKey(key)) {
        m_warnings.warn("Sample #{} for {}:{} contains FORMAT {}, but there is no FORMAT metadata with that name " +
                "(on line {})",
            sampleIndex, position.getChromosome(), position.getPosition(), key, lineBeingWritten());
      }

      String value = sample.getProperty(key);
      if (value == null) {
        if (!m_validateBeforeWrite) {
          m_warnings.warn("Sample #{} is missing property {}" +
              " (on line {})", sampleIndex, key, lineBeingWritten());
        }
        value = ".";
      } else if (value.isEmpty()) {
        // FORMAT sub-field values are position-dependent (matched to FORMAT keys by index), so an empty value is
        // substituted with "." rather than dropped, the same reasoning EMPTY_FIELD_HANDLING.md gives for ALT.
        // VcfSample.validate() already does this same substitution under validateBeforeWrite() (called before
        // this method runs in that mode), so this closes the identical gap for the default write path only --
        // the !m_validateBeforeWrite guard on the warning avoids double-warning for the same event.
        if (!m_validateBeforeWrite) {
          m_warnings.warn("Sample #{} property {} has an empty value; treating it as the missing value '.'" +
              " (on line {})", sampleIndex, key, lineBeingWritten());
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
            InternalUtils.convertProperty(format.getType(), value, m_warnings);
          } catch (VcfFormatException e) {
            m_warnings.warn("Property {} for sample #{} is not of type {}" +
                " (on line {})", key, sampleIndex, format.getType(), lineBeingWritten());
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
        m_warnings.warn("Sample #{} contains extra property {} " +
            "(on line {})", sampleIndex, key, lineBeingWritten());
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
      // position.getInfo() is a live LinkedListMultimap view that permits null keys/values (see
      // VcfPosition.checkInfoEntries's identical comment), bypassing VcfPosition.validate()'s own null guard, which
      // only runs under validateBeforeWrite(). Left unguarded, a null value would reach values.get(0).isEmpty()
      // below as a raw NullPointerException, and a non-crashing null would otherwise silently stringify via
      // StringBuilder.append(Object) into the literal text "null" -- neither is the clean VcfFormatException
      // rejection this project's null-safety policy requires on the default path.
      if (key == null) {
        throw new VcfFormatException("INFO column contains a null key");
      }

      List<String> values = position.getInfo(key);
      assert values != null;
      for (String value : values) {
        if (value == null) {
          throw new VcfFormatException("INFO value for key \"" + key + "\" is null");
        }
      }

      InfoMetadata info = metadata.getInfo().get(key);
      if (!m_validateBeforeWrite && info == null) {
        m_warnings.warn("Position {}:{} contains INFO {}, but there is no INFO metadata with that name (on line {})",
            position.getChromosome(), position.getPosition(), key, lineBeingWritten());
      } else if (!m_validateBeforeWrite) {
        for (String value : values) {
          if (value.isEmpty()) {
            // the isEmptyValueList/multi-value branches below already warn appropriately for an empty value
            // (in whichever form applies to this key's Type/declaration state) and substitute "." on write;
            // this "just test" type-conversion probe has nothing meaningful to check for an empty value, and
            // (since InternalUtils.convertProperty now itself warns on an empty scalar value) would otherwise
            // emit a redundant, context-free second warning for the same defect this loop's own catch block
            // isn't even equipped to describe usefully here (an empty value isn't "not of type X").
            continue;
          }
          Integer number = null;
          try {
            number = Integer.parseInt(info.getNumber());
          } catch (NumberFormatException ignored) {}
          // if the number is anything but 1, it might be a list of something else, represented as a string
          // in that case, we can't compare
          if (number != null && number == 1 && info.getType() != null) {
            try {
              InternalUtils.convertProperty(info.getType(), value, m_warnings); // just test
            } catch (VcfFormatException e) {
              m_warnings.warn("Property {} is not of type {} (on line {})", key, info.getType(), lineBeingWritten());
            }
          }
        }
      }

      sb.append(key);
      boolean isEmptyValueList = values.isEmpty() || (values.size() == 1 && values.get(0).isEmpty());
      if (isEmptyValueList) {
        // A single empty-string value is ambiguous here: VcfPosition.info() only ever produces this exact
        // representation for a genuine Flag (no "=" in the original text) -- every other empty-value case
        // (e.g. "AD=") is already normalized to the "." missing-value sentinel at parse time, before it can
        // reach this method looking identical to a Flag (see EMPTY_FIELD_HANDLING.md). A caller can bypass
        // that parse-time distinction by inserting an empty string directly into the live getInfo() view
        // (e.g. a transformation pipeline), which this method previously could not tell apart from a real
        // Flag, silently writing the bare key and reinterpreting a non-Flag field's type on reparse. Only
        // emit the bare-key form when the key is entirely undeclared (info == null): that specific case is
        // the library's existing, documented round-trip for a Flag with no ##INFO declaration at all
        // (EMPTY_FIELD_HANDLING.md's "INFO flag (no =)" row), which this must not regress. A key that IS
        // declared but whose Type is missing or unparseable gets no such benefit of the doubt: a genuine
        // Type=Flag is an exact, easy-to-spell literal, so a garbled/absent Type is far more likely a
        // malformed non-Flag declaration than an accidental near-miss on "Flag" -- treating it as "maybe a
        // flag" would silently re-admit a narrower instance of the exact corruption this fix closes.
        if (info != null && info.getType() != InfoType.Flag) {
          // Under validateBeforeWrite(), validateInfoValue() already warned "INFO {} contains an empty
          // value" for this exact key -- don't also warn here, matching validateFormatValue()'s established
          // convention of skipping a check "already reported generically" to avoid warning about it twice.
          if (!m_validateBeforeWrite) {
            m_warnings.warn("INFO {} has an empty value but is declared Type={} (not Flag); writing the " +
                "missing-value sentinel \".\" instead (on line {})", key, info.getType(), lineBeingWritten());
          }
          sb.append("=.");
        }
      } else {
        // A multi-value list (or a size-1 list whose one value is non-empty) can still contain an empty
        // element at any position -- e.g. "1,,2" via a live getInfo() mutation -- which the isEmptyValueList
        // branch above doesn't catch (it only recognizes the whole-list-is-one-empty-string case). Per
        // EMPTY_FIELD_HANDLING.md, INFO's per-key values are a position-dependent list (their count can
        // matter for a Number=-declared arity), so an empty entry is substituted with "." rather than
        // dropped, keeping every other value's index intact.
        // Under validateBeforeWrite(), validateInfoValue() already runs for this key whenever info != null or
        // the key is a reserved property (see validateInfo()'s own fallback comment) -- and, when it runs, it
        // warns something about this exact empty-value shape: ordinarily "INFO {} contains an empty value",
        // or (for a declared/reserved Type=Flag key specifically) "INFO {} has Type=Flag but has a value"
        // instead, since validateInfoValue()'s Type==Boolean branch returns before reaching the generic
        // empty-value check -- either way, this method's own warning below would be redundant. For an
        // UNDECLARED, non-reserved key, though, validateInfoValue() never runs at all, so nothing warns
        // generically; suppressing this method's own warning unconditionally on m_validateBeforeWrite alone
        // would silently canonicalize that case with zero warnings, worse than the default path.
        boolean alreadyWarnedGenerically = m_validateBeforeWrite &&
            (info != null || ReservedInfoProperty.fromId(key) != null);
        sb.append("=");
        for (int i = 0; i < values.size(); i++) {
          if (i > 0) {
            sb.append(",");
          }
          String value = values.get(i);
          if (value.isEmpty()) {
            if (!alreadyWarnedGenerically) {
              m_warnings.warn("INFO {} contains an empty value (VCF does not allow zero-length fields); " +
                  "treating it as the missing value '.' (on line {})", key, lineBeingWritten());
            }
            value = ".";
          }
          sb.append(value);
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

  private void addListOrElse(String fieldName, List<String> list, String delimiter, String missingValue,
      boolean substituteEmptyWithDot, StringBuilder sb) {
    // list (position.getIds()/getAltBases()/getFilters()) is a live view a caller can insert a null or empty
    // entry into directly, bypassing VcfPosition.validate()'s own guards (checkIds/checkAltBases/checkFilters),
    // which only run under validateBeforeWrite(). On the default path, StringBuilder.append(Object) would
    // otherwise silently stringify a null entry into the literal text "null" instead of a clean rejection, and
    // an empty entry would silently produce a zero-length VCF field instead of being canonicalized.
    //
    // The empty-entry canonicalization below (drop for a position-independent list like ID/FILTER, substitute
    // "." for a position-dependent one like ALT -- see EMPTY_FIELD_HANDLING.md) is folded into this method's own
    // single pass over the list, rather than calling InternalUtils.dropEmptyEntries()/fillEmptyEntriesWithDot()
    // first, to avoid a second full scan of the list on this performance-sensitive default write path. This
    // means this method and those two InternalUtils methods independently implement the SAME per-field decision
    // table -- if EMPTY_FIELD_HANDLING.md's table ever changes, update both places to keep them in sync.
    boolean appendedAny = false;
    for (String entry : list) {
      if (entry == null) {
        throw new VcfFormatException(fieldName + " contains a null entry");
      }
      if (entry.isEmpty()) {
        if (substituteEmptyWithDot) {
          m_warnings.warn("{} contains an empty entry (VCF does not allow zero-length fields); treating it as " +
              "the missing value '.'", fieldName);
          entry = ".";
        } else {
          m_warnings.warn("{} contains an empty entry (VCF does not allow zero-length fields); dropping it",
              fieldName);
          continue;
        }
      }
      if (appendedAny) {
        sb.append(delimiter);
      }
      sb.append(entry);
      appendedAny = true;
    }
    if (!appendedAny) {
      sb.append(missingValue);
    }
    sb.append("\t");
  }

  private void addPropertyLines(List<String> lines, String name, Collection<String> list) {
    // this is the only call site, from buildHeaderLines(), which runs on every write regardless of
    // validateBeforeWrite() -- unlike validateMetadata()'s equivalent guards above, this is not dead code on the
    // default (non-validated) write path: "##" + name + "=" + string silently stringifies a null into the literal
    // text "null" via Java's string concatenation, corrupting the header with fabricated content that was never
    // actually in the property, rather than an empty value or a clean rejection
    if (name == null) {
      throw new VcfFormatException("Metadata contains a null raw property name");
    }
    for (String string : list) {
      if (string == null) {
        throw new VcfFormatException("Metadata property \"" + name + "\" has a null value");
      }
      lines.add("##" + name + "=" + string);
    }
  }

  private void addMetadataLines(List<String> lines, String name, Collection<? extends BaseMetadata> list) {
    for (BaseMetadata metadata : list) {
      // list is a live view of a VcfMetadata collection (see getAllProperties()'s own comment on
      // metadata.getPropertiesRaw() for the identical live-view concern one level down); a caller can insert a
      // null element after writeHeader() has already run. This guard is reachable only in validateBeforeWrite()
      // mode: the default write path never calls buildHeaderLines() from writeLine() at all, and on the first
      // writeHeader() call, claimMetadataWarningsSinks() already rejects a null entry earlier (line 127) before
      // buildHeaderLines() (and this method, via it) ever runs. The one place this guard actually fires is
      // writeLine()'s post-header-mutation check in validateBeforeWrite() mode, where buildHeaderLines() (and so
      // this method) runs BEFORE validateMetadata()'s equivalent null checks -- so this guard is not redundant
      // with those, it's the one that actually runs first there.
      if (metadata == null) {
        throw new VcfFormatException(name + " metadata contains a null entry");
      }
      lines.add(getAllProperties(name, metadata));
    }
  }

  private String getAllProperties(String name, BaseMetadata metadata) {
    StringBuilder sb = new StringBuilder("##");
    sb.append(name).append("=<");
    // metadata.getPropertiesRaw() is a live view (see its Javadoc), so a caller can insert a null key/value after
    // this entry's own validate() already ran. Unlike addPropertyLines() below, there's no validateBeforeWrite()
    // gap to close here -- validateMetadata() already rejects a null key/value for every kind this method
    // processes, via validateMetadataProperties(), and that check runs before buildHeaderLines() in writeHeader().
    // This guard covers only the default (non-validateBeforeWrite()) path, where entry.getKey()/entry.getValue()
    // being null would otherwise silently stringify via StringBuilder.append(Object) into the literal text "null"
    // (e.g. "##INFO=<ID=X,Description=null>"), fabricating content that was never actually in the property.
    Map<String, String> props = metadata.getPropertiesRaw();
    int i = 0;
    for (Map.Entry<String, String> entry : props.entrySet()) {
      if (i > 0) {
        sb.append(",");
      }
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null) {
        throw new VcfFormatException(name + " metadata has a null property name");
      }
      if (value == null) {
        throw new VcfFormatException(name + " metadata property \"" + key + "\" has a null value");
      }
      sb.append(key).append("=").append(value);
      i++;
    }
    sb.append(">");
    return sb.toString();
  }

  public static class Builder {

    private @Nullable Path m_file;
    private @Nullable PrintWriter m_writer;
    private boolean m_validateBeforeWrite;
    private boolean m_warningsToFile;
    private @Nullable Path m_warningsFile;
    private @Nullable String m_targetFileFormat;

    /**
     * Files are written as UTF-8, matching {@link org.pharmgkb.parser.vcf.VcfParser.Builder#fromFile}.
     */
    public Builder toFile(Path file) {
      // file is a caller-configuration object (like VcfParser.Builder's Path/Reader/VcfLineParser arguments),
      // never VCF content, so Preconditions.checkNotNull's raw NullPointerException is this codebase's
      // established convention here (see AGENTS.md's Public API Null-Safety Policy and WONT_FIX.md's
      // VcfParser.Builder entries) -- without this, build() would only detect the omission indirectly, via
      // "Must specify either file or writer", which doesn't indicate the caller actually passed null here
      Preconditions.checkNotNull(file);
      if (m_writer != null) {
        throw new IllegalStateException("Already writing to a writer");
      }
      m_file = file;
      return this;
    }

    public Builder toWriter(PrintWriter writer) {
      // same caller-configuration-object reasoning as toFile(Path) above
      Preconditions.checkNotNull(writer);
      if (m_file != null) {
        throw new IllegalStateException("Already writing to a file");
      }
      m_writer = writer;
      return this;
    }

    /**
     * Declares the VCF version this writer emits, overriding the version the metadata itself declares.
     * <p>
     * Optional, and inherited from the metadata when not set. It is a {@code Builder} option rather than a
     * {@code writeHeader} parameter because a decorator flow constructs the sink before parsing (and therefore
     * before any metadata) exists; it is optional rather than required for the same reason.
     * <p>
     * This does <em>not</em> convert anything. {@link #writeHeader} rejects a target naming a different version
     * than the metadata declares -- see its own comment for why, and why the comparison is canonicalized. The
     * value given here is emitted verbatim, so {@code targetFileFormat("VCFv4.02")} writes
     * {@code ##fileformat=VCFv4.02} over {@code VCFv4.2} metadata.
     *
     * @param fileFormat Ex: "VCFv4.2"
     */
    public Builder targetFileFormat(String fileFormat) {
      // VCF content, not a caller-configuration object like toFile(Path)/toWriter(PrintWriter): this string is
      // written into the output verbatim, so AGENTS.md's Public API Null-Safety Policy calls for
      // VcfFormatException rather than Preconditions' raw NullPointerException. The value's own well-formedness
      // is checked in writeHeader(), where VcfVersion.canonicalize runs.
      if (fileFormat == null) {
        throw new VcfFormatException("targetFileFormat() was called with a null file format");
      }
      m_targetFileFormat = fileFormat;
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

    /**
     * Redirects VCF-content warnings from writing to a file named after the target VCF file's own full name (kept,
     * including its extension) plus {@code .warn.log}, in the same directory. Requires the writer to have been
     * built via {@link #toFile(Path)}, not {@link #toWriter(PrintWriter)} -- there is no path to derive a default
     * name from otherwise; use {@link #warningsToFile(Path)} instead in that case. See
     * {@link #warningsToFile(Path)}'s Javadoc for a same-path truncation risk that also applies here if this
     * default-derived name happens to collide with another {@code warningsToFile()}-configured
     * {@link VcfWriter}/{@link VcfParser}'s own default-derived name (e.g. an in-place parse-then-write).
     * <p>
     * This redirection is scoped to this {@link VcfWriter}'s own write calls. {@link VcfWriter#writeLine} claims
     * {@code position} and every sample in {@code samples} for this writer's own destination after its own
     * structural checks (header-written, metadata identity, and the null position/samples/sample-element checks)
     * but before any lazy getter or validation logic runs -- so a {@code VcfPosition}/{@code VcfSample} field
     * that's still unparsed when handed to {@link #writeLine} has its lazy warning correctly captured here,
     * even if the object was originally parsed or built under a different destination. A getter accessed and cached *before* being handed to
     * {@link #writeLine} at all is not retroactively fixed -- the value is already resolved by then, so there is
     * nothing left to warn about at write time.
     */
    public Builder warningsToFile() {
      m_warningsToFile = true;
      m_warningsFile = null;
      return this;
    }

    /**
     * Redirects VCF-content warnings from writing to {@code customFile} instead of the default derived name.
     * <p>
     * If {@code customFile} is the same path another {@code warningsToFile()}-configured {@link VcfWriter} or
     * {@link VcfParser} also writes to -- including via a matching default-derived name, e.g. an in-place
     * parse-then-write to the same file -- whichever side opens its warnings file second silently truncates the
     * first side's content. Both open via {@code Files.newBufferedWriter}, which defaults to overwrite, not
     * append. This "truncates" description is exact only when the two sides' warnings-file lifetimes are
     * sequential (one fully written and closed before the other opens the same path). If the two lifetimes
     * instead overlap -- e.g. a {@link VcfWriter} built before {@link VcfParser#parse()} runs, its own warnings
     * file still open -- each side holds an independent file position from its own {@code open()} call, so the
     * result is a byte-level overwrite/interleave (potentially leaving a zero-filled gap) rather than a clean,
     * complete truncation of one side's content.
     * <p>
     * This redirection is scoped to this {@link VcfWriter}'s own write calls. {@link VcfWriter#writeLine} claims
     * {@code position} and every sample in {@code samples} for this writer's own destination after its own
     * structural checks (header-written, metadata identity, and the null position/samples/sample-element checks)
     * but before any lazy getter or validation logic runs -- so a {@code VcfPosition}/{@code VcfSample} field
     * that's still unparsed when handed to {@link #writeLine} has its lazy warning correctly captured here,
     * even if the object was originally parsed or built under a different destination. A getter accessed and cached *before* being handed to
     * {@link #writeLine} at all is not retroactively fixed -- the value is already resolved by then, so there is
     * nothing left to warn about at write time.
     */
    public Builder warningsToFile(Path customFile) {
      // customFile is a caller-configuration object, matching the established convention (see toFile(Path)
      // above and VcfParser.Builder.warningsToFile(Path), which already has this same check). Without this, a
      // null customFile would silently set m_warningsFile back to null -- identical to calling the zero-arg
      // warningsToFile() -- so build() would silently fall back to the default derived filename instead of
      // failing, rather than raising a clear error at the actual mistake.
      Preconditions.checkNotNull(customFile);
      m_warningsToFile = true;
      m_warningsFile = customFile;
      return this;
    }

    public VcfWriter build() throws IOException {
      PrintWriter writer = m_writer;
      boolean weOpenedWriter = false;
      if (m_file != null) {
        // a plain Charset-based OutputStreamWriter silently replaces malformed input (e.g. an unpaired surrogate)
        // with "?" instead of failing -- verified empirically; it does NOT use CharsetEncoder's own REPORT
        // defaults. Building the encoder explicitly with REPORT actions makes a malformed-input encoding failure
        // set PrintWriter's error flag instead, matching VcfParser's Files.newBufferedReader decoder on the read
        // side (which already throws MalformedInputException on malformed input). That failure does NOT throw
        // here or propagate up through print()/write() -- PrintWriter catches any IOException from its
        // underlying writer internally and only sets its own error flag. It's checkForWriteError() (called from
        // printLine()'s periodic check, writeHeader(), and close()) that actually turns this into a visible
        // UncheckedIOException.
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        writer = new PrintWriter(new BufferedWriter(
            new OutputStreamWriter(Files.newOutputStream(m_file), encoder), 65536));
        weOpenedWriter = true;
      }
      if (writer == null) {
        throw new IllegalStateException("Must specify either file or writer");
      }
      VcfWarnings warnings = VcfWarnings.DEFAULT;
      if (m_warningsToFile) {
        try {
          Path resolvedWarningsFile;
          if (m_warningsFile != null) {
            resolvedWarningsFile = m_warningsFile;
          } else if (m_file != null) {
            resolvedWarningsFile = m_file.resolveSibling(m_file.getFileName().toString() + ".warn.log");
          } else {
            throw new IllegalStateException("warningsToFile() requires either an explicit file or a target file " +
                "obtained via toFile(Path) -- toWriter(PrintWriter) provides no path to derive a default name from");
          }
          warnings = VcfWarnings.openFile(resolvedWarningsFile);
        } catch (IOException | RuntimeException e) {
          if (weOpenedWriter) {
            writer.close();
          }
          throw e;
        }
      }
      return new VcfWriter(m_file, writer, m_validateBeforeWrite, warnings, m_targetFileFormat);
    }

  }

  private UncheckedIOException writeError() {
    return new UncheckedIOException(new IOException(
        "Error writing VCF output after line " + m_lineNumber + " (disk full, broken pipe, or encoding failure)"));
  }

  // shared by close() and checkForWriteError(): builds the write-failure exception and stashes it as the cause.
  // Each call site still owns its own m_closed handling via closeUnderlyingWriter(), since the two sites differ
  // in when they call it -- checkForWriteError() calls it here, while close() already had, just above, before
  // ever reaching this point.
  private UncheckedIOException recordWriteError() {
    UncheckedIOException e = writeError();
    m_invalidatingCause = e;
    return e;
  }

  private void checkForWriteError() {
    if (m_writer.checkError()) {
      UncheckedIOException e = recordWriteError();
      closeUnderlyingWriter();
      throw e;
    }
  }

  private void closeUnderlyingWriter() {
    try {
      m_writer.close();
    } finally {
      m_closed = true;
      closeWarningsWriterQuietly();
    }
  }

  private void closeWarningsWriterQuietly() {
    m_warnings.close();
  }

  /**
   * {@link #m_lineNumber} is a post-increment "lines fully written so far" counter -- only accurate once
   * {@link #printLine} has actually run for the line in question. A warning or exception message describing the
   * line currently under construction (fired from {@link #writeLine}'s own body, or from {@link #printLine}
   * itself before its own increment below has run) must report this value instead of {@link #m_lineNumber}
   * directly, or it silently reports the <em>previous</em> line's number instead.
   */
  private int lineBeingWritten() {
    return m_lineNumber + 1;
  }

  private void printLine(Object line) {
    String string = line.toString();
    if (string.contains("\n") || string.contains("\r")) {
      throw new VcfFormatException("Something went wrong writing line #" + lineBeingWritten() + ": [[[" + string +
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

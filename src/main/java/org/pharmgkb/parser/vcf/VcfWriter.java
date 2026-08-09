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
    //
    // Resolved through VcfVersion.resolveWithoutWarning rather than by VcfContext.lenient's own
    // VcfVersion.resolve call: on the write side a version fallback is a validateBeforeWrite() diagnostic, and
    // resolve() emits unconditionally, so building the context the ordinary way would already have warned by the
    // time the m_validateBeforeWrite branch below is reached. The fallback is carried as a fact instead and
    // reported further down, at the only point where the message is true of anything (see there).
    String declaredFileFormat = declaredFileFormatFor(metadata);
    VcfVersion.Resolution resolution = VcfVersion.resolveWithoutWarning(declaredFileFormat);
    m_context = VcfContext.lenientWithoutFallbackWarning(declaredFileFormat, resolution.version(), m_warnings);
    // What m_context IS: the ##fileformat spelling this writer emits and the warnings sink this writer's own
    // generated diagnostics (e.g. an undeclared INFO key) go to.
    // What m_context is NOT: the record's semantic context. It is a fresh VcfContext built here on every
    // writeHeader() attempt (see the comment above), so it is never reference-identical to the context that
    // built metadata/position/samples -- not even for a straight parse-then-write. writeLine()'s own
    // metadata/position/sample context-agreement check (Task 2) therefore pivots on metadata.getContext(), never
    // on m_context; a later check that reused m_context here would silently always fail (or, if it fell back to
    // value comparison instead, would silently accept components from two different contexts that merely
    // declare the same version -- reintroducing the bug this task removes).
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
    // Reported here, and only here: after every rejection above has been passed and immediately before the first
    // header byte is written. The message is a statement about output that exists, so an attempt that writes
    // nothing must not make it -- a rejected attempt followed by a corrected retry therefore reports this once in
    // total, not once per attempt. Diagnostics-mode only, per AGENTS.md's Writer Validation and Round-Trip
    // Policy: the default write path is performance-sensitive and asked for no diagnostics.
    if (m_validateBeforeWrite && resolution.fellBack()) {
      m_warnings.warn("The output declares ##fileformat={}, whose semantics this library does not implement; it " +
          "was written under VCF {} rules instead, so its validity under the declared version is not guaranteed",
          declaredFileFormat, resolution.version().getId());
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
    sf_logger.debug("Wrote {} lines of header{}", m_lineNumber, (m_file == null ? "" : " to " + m_file));
  }

  /**
   * Builds every header line (metadata declarations, then the {@code #CHROM} column line) that {@link #writeHeader}
   * would emit for {@code metadata}, without writing anything. Called only from {@link #writeHeader}: it used to
   * also run per {@link #writeLine} call, to recompute the header text and compare it against a snapshot, but
   * {@link VcfMetadata} is immutable now, so the same object can no longer produce different header text than the
   * one already emitted.
   */
  private List<String> buildHeaderLines(VcfMetadata metadata) {
    List<String> lines = new ArrayList<>();

    // Derived from the argument, not read back off m_context. That matters because the two can legitimately
    // differ in spelling: VcfMetadata only requires its ##fileformat to agree with its context's CANONICALIZED
    // version, so metadata declaring "VCFv4.02" under a "VCFv4.2" context is valid and must emit its own
    // spelling, byte for byte. Reading it off the context would silently rewrite the caller's declaration.
    // Pinned by VcfWriterTest.testWriteHeaderDerivesFileFormatFromTheMetadataNotItsContext. It also keeps a
    // retried writeHeader() honest (see that method's own comment).
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
    // Structural, not the default path's exempted VCF-semantic re-validation (AGENTS.md's Writer Validation and
    // Round-Trip Policy): a record assembled from components built under different VcfContext instances has no
    // unambiguous resolved version or warnings destination, so this runs in both modes, before the
    // m_validateBeforeWrite branch below. Every argument is already known non-null at this point. metadata's own
    // context is the pivot -- it, not m_context (see that field's own comment), is this record's actual semantic
    // context -- so position and every sample are compared against it, closing the position-vs-samples gap too
    // by transitivity. Reuses VcfContext.newRecord's and VcfMetadata.checkSameContext's message wording (same
    // reference-identity rationale: two equal-valued contexts still represent two different operations with
    // different warnings sinks) so diagnostics stay consistent across the library.
    VcfContext context = metadata.getContext();
    if (position.getContext() != context) {
      throw new VcfFormatException("writeLine() was given a position created under a different VcfContext than " +
          "the metadata; the record would have no unambiguous version or warnings destination");
    }
    for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
      if (samples.get(sampleIndex).getContext() != context) {
        throw new VcfFormatException("Sample #" + sampleIndex + " was created under a different VcfContext than " +
            "the metadata; the record would have no unambiguous version or warnings destination");
      }
    }
    // The record's own resolved version -- context.getVersion() and position.getContext().getVersion() are
    // equivalent here, since the identity checks just above already proved position.getContext() == context --
    // derived once and threaded explicitly into validateInfo/validateSamples and, from there, into the reserved-
    // key constraint checks. Deliberately NOT m_context.getVersion(): m_context is this writer's own diagnostics
    // context (rebuilt fresh on every writeHeader(), see that field's own comment above), never guaranteed
    // reference-identical to the context that actually built metadata/position/samples, so it is not this
    // record's version even when the two happen to resolve to the same VcfVersion value.
    VcfVersion recordVersion = context.getVersion();
    if (m_validateBeforeWrite) {
      // No header-drift re-check here. "metadata == m_headerMetadata" used to prove only object identity, not
      // that the object would still produce the same header text, so this rebuilt the whole header on every
      // record and compared it against a snapshot taken in writeHeader(). VcfMetadata is immutable now (no
      // instance mutators, every collection getter an unmodifiable view, every declaration's properties map
      // unmodifiable), so the same object always produces the same header text and identity is sufficient.
      position.validate();
      validateMetadata(metadata);
      validateInfo(metadata, position, recordVersion);
      validateSamples(metadata, position, samples, recordVersion);
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
    // No null/empty-CHROM or null/empty-REF guard here: VcfPosition.checkChromosome()/checkRef() run
    // unconditionally in every constructor and are never skipped by any VcfContext trust flag (see
    // VcfPosition's own Javadoc for its trustedFormat/trustedFields parameters), so a VcfPosition can never
    // carry either value into this method in the first place.
    String chromosome = position.getChromosome();
    String ref = position.getRef();

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
    // combined with another code -- but VcfContext.newPositionCopyingTrustedFields skips checkFilters entirely,
    // so a position built via VcfPosition.Builder#build() can still carry a "." combined with another code (see
    // that factory's own Javadoc: it trusts Builder#build() to have already re-run whichever checks a setter
    // actually changed, and is a real production fast path, not a test-only bypass). getFilters() itself is an
    // unmodifiable view, so there is no live-mutation route any more.
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
      // Kept deliberately. getFormat() is an unmodifiable view over a frozen VcfPosition, so no public API
      // caller can insert a null entry -- but VcfContext's package-private position factories
      // (newPositionWithTrustedFormat, newPositionCopyingTrustedFields) skip checkFormat outright, and both are
      // real production fast paths (VcfParser's per-record FORMAT cache, VcfPosition.Builder.build()), not
      // test-only bypasses. On the default write path StringBuilder.append(Object) would silently stringify a
      // null key into the literal text "null" rather than rejecting cleanly, so this stays.
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
      // Kept, unlike the null-element guard addMetadataLines() used to carry for this same list. getPedigrees()
      // is an unmodifiable view and Builder.addPedigree() rejects a null, so no public route reaches this;
      // VcfMetadata's package-private trusted constructor still can. This method is the full-output diagnostics
      // pass, whose job is to report everything it can detect about the object it was handed, so it keeps its own
      // self-contained null check rather than relying on a construction-time invariant holding -- addMetadataLines()
      // (the default fast path) is where paying for a re-check that can no longer fire is not worth it.
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
      // Kept for the same reason as the PEDIGREE null-entry guard above: both raw-property getters are
      // unmodifiable views and Builder.addRawProperty() rejects a null key/value, so only VcfMetadata's
      // package-private trusted constructor can reach this, but this is the diagnostics pass and it reports
      // everything it can detect about the object it was handed.
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
        // Only VcfMetadata's package-private trusted constructor can reach this now (getRawProperties() is an
        // unmodifiable view, and addRawProperty() drops a reserved key at insertion time). Kept, and unlike
        // addRawProperty()'s warn-and-drop this rejects outright, since a raw "INFO"/"FORMAT"/etc. property
        // would produce output VcfParser cannot read back as what was written (see VcfParser.parseMetadata's
        // switch)
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
          // unlike the empty-value case above, this rejects outright rather than warning. Builder.addRawProperty
          // is the single insertion point and already rejects/drops this, so only the package-private VcfMetadata
          // trusted constructor can bypass it -- kept for the same diagnostics-pass reason as the guards above.
          // Confirmed directly that bcftools 1.23.1 hard-errors ("Could not parse the header line") on a
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
  // not from validateMetadata() (which also runs per writeLine() call) -- since both sides of the comparison are
  // fixed forever once VcfMetadata is built: #CHROM's sample-column list is copied into an immutable list, and
  // ##SAMPLE can no longer change either (VcfMetadata is immutable -- no instance mutators, and getSamples()
  // returns an unmodifiable view). Re-running this per record could therefore only ever repeat the same result.
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
   * Validates every entry of a metadata map (INFO/FORMAT/FILTER/ALT/contig/SAMPLE), including that each entry's own
   * {@code ID} property still matches the map key it's stored under. That invariant normally holds automatically
   * (these maps are keyed by {@code getId()} when built), but a declaration's own raw {@code ID} property could
   * historically be changed to a different value, or removed entirely, without it being reflected in the map
   * key. Left undetected, the header would then declare a different (or missing) ID than what data lines
   * actually reference by that entry's map key, and a duplicate-ID check across entries would also be unable to
   * catch a real collision this way (see below).
   * <p>
   * The route that made that drift possible was {@link VcfMetadata#getInfo()} and its siblings returning the
   * backing map directly, plus {@link BaseMetadata#getPropertiesRaw()} being a live view. Both are unmodifiable
   * now, so no public API caller can reach this state; only the package-private
   * {@code BaseMetadata.trustedMutableProperties()} bypass can, which is how the tests still cover it. These
   * checks stay because this is {@code validateBeforeWrite()}'s full-output diagnostics pass, which must report
   * everything it can detect about the object it was handed rather than assume a construction-time invariant.
   */
  private void validateKeyedMetadataEntries(String kind, Map<String, ? extends BaseMetadata> entriesByKey) {
    for (Map.Entry<String, ? extends BaseMetadata> mapEntry : entriesByKey.entrySet()) {
      String mapKey = mapEntry.getKey();
      BaseMetadata entry = mapEntry.getValue();
      // The keyed metadata getters are unmodifiable views and Builder's addXxx methods all reject a null, so
      // only VcfMetadata's package-private trusted constructor can insert one. Kept for the diagnostics-pass
      // reason in this method's own Javadoc; note that addMetadataLines(), the default fast path, no longer
      // makes the equivalent check, so this is the only null-entry rejection left.
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
      // mapKey is never null on any path that goes through Builder's addXxx methods (all reject/drop a
      // null-getId() entry before put). LinkedHashMap tolerates a null KEY, so an INFO map carrying one reaches
      // here with mapKey itself null; guarded explicitly rather than left to a raw NullPointerException at
      // mapKey.equals(id). Only VcfMetadata's package-private trusted constructor can produce a null key now;
      // kept for the same reason as the null-VALUE guard above.
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

  // both call sites pass the same map an entry.validate()/pedigree.validate() call already ran against,
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

  private void validateInfo(VcfMetadata metadata, VcfPosition position, VcfVersion recordVersion) {
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
              position.getAltBases().size(), recordVersion);
        }
      } else {
        validateInfoValue(info.getNumber(), info.getType() == null ? null : InternalUtils.toJavaType(info.getType()),
            values, key, position.getAltBases().size(), recordVersion);
      }
    }
  }

  private void validateSamples(VcfMetadata metadata, VcfPosition position, List<VcfSample> samples,
      VcfVersion recordVersion) {
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
          // version-aware: GLE stops being a reserved key at VCFv4.3, so an undeclared 4.3+ key named GLE gets
          // no reserved-canonical fallback at all, matching getProperty(VcfPosition, VcfMetadata, String)
          ReservedFormatProperty reserved = ReservedFormatProperty.fromId(key, recordVersion);
          if (reserved != null) {
            validateFormatValue(reserved.getExpectedNumber(), reserved.getType(), value, key, sampleIndex,
                position.getAltBases().size(), ploidy, recordVersion);
          }
        } else {
          validateFormatValue(format.getNumber(),
              format.getType() == null ? null : InternalUtils.toJavaType(format.getType()), value, key, sampleIndex,
              position.getAltBases().size(), ploidy, recordVersion);
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
      int numAltAlleles, VcfVersion recordVersion) {
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
    InternalUtils.checkReservedInfoConstraints(key, String.join(",", values), recordVersion, m_warnings);
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
      int sampleIndex, int numAltAlleles, @Nullable Integer ploidy, VcfVersion recordVersion) {
    if (value.equals(".")) {
      return;
    }
    if (!value.isEmpty()) {
      // an empty value is already reported generically below (for any FORMAT key, not just FT/PS); avoid warning
      // about it twice via checkReservedFormatConstraints's own "empty filter code" check. The returned normalized
      // value is discarded: this writer preserves raw content rather than rewriting it, consistent with how a
      // reserved list-typed FORMAT value's empty entry is also only normalized on read, not on write.
      InternalUtils.checkReservedFormatConstraints(key, value, recordVersion, m_warnings);
    }
    String[] values = InternalUtils.splitFormatValue(key, value, recordVersion);
    for (String element : values) {
      if (element.isEmpty()) {
        m_warnings.warn("FORMAT {} for sample #{} contains an empty value", key, sampleIndex);
      }
    }
    if (!InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck(key, recordVersion)) {
      // Under VCFv4.2 and earlier, GLE's conventional Number=G does not describe a comma-delimited value list the
      // way it does for every other FORMAT key -- GLE's commas are part of its own internal genotype:likelihood
      // encoding (see ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy), so values.length is always
      // 1 there and comparing it against Number=G's combinatorial expected count would always be a false
      // mismatch. VCFv4.3 removed GLE, so a 4.3+ key named GLE is no longer exempt and gets the ordinary check
      // against whatever Number its own declaration states. CNP's
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
      // Kept deliberately, for the same reason as addFormatConditionally()'s null-key guard: VcfPosition is
      // frozen and getInfo() is an unmodifiable view, so no public API caller can reach this, but
      // VcfContext.newPositionCopyingTrustedFields skips checkInfoEntries outright and is a real production
      // path (VcfPosition.Builder.build()), not a test-only bypass. Left unguarded, a null value would reach
      // values.get(0).isEmpty() below as a raw NullPointerException, and a non-crashing null would silently
      // stringify via StringBuilder.append(Object) into the literal text "null" -- neither is the clean
      // VcfFormatException rejection this project's null-safety policy requires on the default path.
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
    // Both branches below are kept deliberately. position.getIds()/getAltBases()/getFilters() are unmodifiable
    // views over a frozen VcfPosition, so no public API caller can insert a null or empty entry -- but
    // VcfContext.newPositionCopyingTrustedFields skips checkIds/checkAltBases/checkFilters outright and is a
    // real production path (VcfPosition.Builder.build()), not a test-only bypass. On the default path,
    // StringBuilder.append(Object) would otherwise silently stringify a null entry into the literal text "null"
    // instead of a clean rejection. The empty-entry branch is load-bearing for a further reason: it is the ONLY
    // place this per-field drop-vs-substitute decision is implemented for the writer (see the "independently
    // implement the SAME per-field decision table" note below) -- VcfPositionTest/InternalUtilsTest only cover
    // InternalUtils's own copy, not this one, so VcfWriterTest's testDefaultWriteDropsEmptyIdEntry/
    // testDefaultWriteDropsAllEmptyIdEntriesRendersMissingValue/testDefaultWriteDropsEmptyFilterEntry/
    // testDefaultWriteSubstitutesEmptyAltEntryWithDot construct the position via the package-private
    // newPositionCopyingTrustedFields() factory (deliberately bypassing those checks, reachable only because
    // that test class is in this same package) to keep this branch under test.
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
    for (String string : list) {
      lines.add("##" + name + "=" + string);
    }
  }

  // Neither this method, addPropertyLines() above, nor getAllProperties() below re-checks metadata for nulls
  // any more. Each used to guard against a caller inserting a null through what were once live views onto
  // VcfMetadata's declaration collections, its raw-property multimap, and BaseMetadata's own properties map.
  // All three are unmodifiable views now, and VcfMetadata.Builder rejects a null declaration (requireNonNull in
  // every addXxx) and a null raw-property name/value (addRawProperty) before either can be stored, so no
  // construction path this library actually uses can put a null here. Only VcfMetadata's package-private
  // trusted constructor and BaseMetadata.trustedMutableProperties() can, and neither has a src/main caller that
  // supplies null content. This is the header-building path, which runs on every write regardless of mode and
  // is covered by AGENTS.md's "the default writer path is performance-sensitive ... without full
  // revalidation"; validateMetadata() keeps its equivalent checks, since validateBeforeWrite() is the
  // full-output diagnostics mode and must report what it can detect rather than assume an invariant.
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
     * <p>
     * Where those warnings land is not uniform: see {@link #warningsToFile()} for the split between diagnostics
     * this writer generates itself and lazy content warnings from models built under a different context.
     *
     * @see #warningsToFile()
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
     * <b>This only captures diagnostics this {@link VcfWriter} generates itself</b> -- e.g. an undeclared INFO/
     * FORMAT key, or a header/sample mismatch. The version-fallback diagnostic for a {@code ##fileformat} this
     * writer emits but does not implement is captured only under {@link #validateBeforeWrite()}, and only for a
     * header that actually proceeds to being written: the default write path stays silent about it, and a
     * rejected {@link #writeHeader} attempt reports nothing at all. It does <em>not</em> capture a lazy content
     * warning from the {@code metadata}/{@code position}/{@code samples} handed to {@link #writeHeader}/
     * {@link #writeLine} (e.g. an out-of-range QUAL, a duplicate FORMAT key, or a malformed metadata attribute
     * discovered on first access): every model object's warnings destination is fixed at construction, from its
     * own {@link VcfContext}, and this writer never re-points it -- see {@link VcfWarnings}'s class Javadoc for
     * the full attribution rule. A caller who needs such a warning captured here must build {@code metadata}/
     * {@code position}/{@code samples} under a context that already points at this same destination.
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
     * <b>This only captures diagnostics this {@link VcfWriter} generates itself</b> -- e.g. an undeclared INFO/
     * FORMAT key, or a header/sample mismatch. The version-fallback diagnostic for a {@code ##fileformat} this
     * writer emits but does not implement is captured only under {@link #validateBeforeWrite()}, and only for a
     * header that actually proceeds to being written: the default write path stays silent about it, and a
     * rejected {@link #writeHeader} attempt reports nothing at all. It does <em>not</em> capture a lazy content
     * warning from the {@code metadata}/{@code position}/{@code samples} handed to {@link #writeHeader}/
     * {@link #writeLine} (e.g. an out-of-range QUAL, a duplicate FORMAT key, or a malformed metadata attribute
     * discovered on first access): every model object's warnings destination is fixed at construction, from its
     * own {@link VcfContext}, and this writer never re-points it -- see {@link VcfWarnings}'s class Javadoc for
     * the full attribution rule. A caller who needs such a warning captured here must build {@code metadata}/
     * {@code position}/{@code samples} under a context that already points at this same destination.
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

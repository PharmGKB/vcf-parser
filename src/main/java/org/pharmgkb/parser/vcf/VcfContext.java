package org.pharmgkb.parser.vcf;

import com.google.common.base.Preconditions;


/**
 * Immutable, file-scoped context for one parse or one write.
 * <p>
 * Exactly one instance exists per parse and per write, shared by reference across every model object that
 * operation creates -- an interned enum plus two references, so it costs nothing on the per-record hot path.
 * <p>
 * It carries <em>both</em> the declared {@code ##fileformat} string and the resolved {@link VcfVersion}. The
 * writer emits the declared value verbatim, so a file declaring a version this library does not implement still
 * round-trips under its own declaration; version-dependent interpretation uses the resolved value.
 * <p>
 * Deliberately excluded: {@link VcfMetadata} (it would re-couple {@link VcfPosition} to header state and pin
 * the header into every streamed record) and the per-record line number (this context is file-scoped, so a
 * shared mutable "current line" would be a stale read for any record a caller holds past the next
 * {@code parseNextLine()}).
 *
 * @author Mark Woon
 */
public final class VcfContext {

  private final String m_declaredFileFormat;
  private final VcfVersion m_version;
  private final VcfWarnings m_warnings;
  private final boolean m_lenient;

  private VcfContext(String declaredFileFormat, VcfWarnings warnings, boolean lenient) {
    Preconditions.checkNotNull(warnings, "warnings must not be null");
    m_declaredFileFormat = declaredFileFormat;
    m_version = VcfVersion.resolve(declaredFileFormat, warnings);
    m_warnings = warnings;
    m_lenient = lenient;
  }

  /**
   * Context for parsing or writing a file: a non-structural content violation warns and recovers.
   *
   * @throws VcfFormatException if {@code declaredFileFormat} is null or not a valid VCF 4.x version
   */
  public static VcfContext lenient(String declaredFileFormat, VcfWarnings warnings) {
    return new VcfContext(declaredFileFormat, warnings, true);
  }

  /**
   * Context for direct programmatic construction: a content violation that {@link #lenient} would warn about
   * throws instead. Warnings go to {@link VcfWarnings#DEFAULT}.
   * <p>
   * This governs <em>content</em> validation only. Version resolution is identical in both modes -- a
   * well-formed {@code ##fileformat} for an unimplemented version falls back and warns here too, since it is
   * not invalid content. Only a structurally unusable value throws.
   *
   * @throws VcfFormatException if {@code declaredFileFormat} is null or not a valid VCF 4.x version
   */
  public static VcfContext strict(String declaredFileFormat) {
    return new VcfContext(declaredFileFormat, VcfWarnings.DEFAULT, false);
  }

  /** @return the raw {@code ##fileformat} value, for verbatim re-emission on write. */
  public String getDeclaredFileFormat() {
    return m_declaredFileFormat;
  }

  /**
   * @return the version whose semantics apply, after fallback resolution.
   * <p>
   * Package-private in Plan A <em>on purpose</em>. {@link VcfVersion#HIGHEST_IMPLEMENTED} is already
   * {@link VcfVersion#V4_3}, but no version-gated behavior exists until Plan B -- interpretation is still
   * uniform across every version. Publishing this accessor now would let a caller read "V4_3" while the
   * library is not yet applying 4.3 semantics. Promote it to {@code public} in Plan B, in the same change
   * that makes the value meaningful.
   */
  VcfVersion getVersion() {
    return m_version;
  }

  public VcfWarnings getWarnings() {
    return m_warnings;
  }

  /** @return {@code true} to warn and recover on a non-structural content violation, {@code false} to throw. */
  public boolean isLenient() {
    return m_lenient;
  }
}

package org.pharmgkb.parser.vcf;

import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;


/**
 * A VCF 4.x specification version, in ascending order.
 * <p>
 * {@link #V4_4} and {@link #V4_5} exist so {@link #resolve} has a complete table of declarable versions, but
 * their semantics are deliberately not implemented -- hts-specs publishes no conformance fixtures for 4.4 and
 * only one for 4.5, and neither bcftools nor vcf_validator covers them, so there is no way to verify an
 * interpretation.
 * <p>
 * {@link #V4_1} and {@link #V4_2} are currently behaviorally identical: every version-gated rule in this
 * library sits at the 4.2/4.3 boundary. The distinction exists for accurate diagnostics and future use.
 *
 * @author Mark Woon
 */
public enum VcfVersion {

  V4_1("4.1"),
  V4_2("4.2"),
  V4_3("4.3"),
  V4_4("4.4"),
  V4_5("4.5");

  /** The newest version whose semantics this library implements. */
  public static final VcfVersion HIGHEST_IMPLEMENTED = V4_3;

  /** The oldest version this library interprets. */
  public static final VcfVersion LOWEST_SUPPORTED = V4_1;

  private final String m_id;

  VcfVersion(String id) {
    m_id = id;
  }

  /** @return the bare version number, e.g. {@code "4.3"}. */
  public String getId() {
    return m_id;
  }

  /** @return the {@code ##fileformat} declaration value, e.g. {@code "VCFv4.3"}. */
  public String getFileFormat() {
    return "VCFv" + m_id;
  }

  /**
   * @return whether this version is {@code other} or newer.
   * @throws VcfFormatException if {@code other} is {@code null}
   */
  public boolean isAtLeast(@Nullable VcfVersion other) {
    if (other == null) {
      throw new VcfFormatException("isAtLeast was called with a null version");
    }
    return ordinal() >= other.ordinal();
  }

  /**
   * Resolves a declared {@code ##fileformat} value to the version whose semantics apply.
   * <p>
   * The rule is <em>use the declared version; where it isn't implemented, fall back to the nearest implemented
   * one and warn</em>. Falling back down from 4.4+ and up from 4.0 are the same rule. For 4.1, 4.2 and 4.3 the
   * declared version is used unchanged and nothing is warned.
   * <p>
   * Interpreting a file under a version it did not declare corrupts data silently in both directions -- reading
   * 4.2 as 4.3 would percent-decode a literal {@code %3A}, disable the GLE colon-splitter (misaligning the whole
   * sample column) and read {@code GP} as 0-1 rather than phred-scaled; reading 4.4 as 4.5 would apply 4.5's
   * {@code M[0-9]+} FORMAT reservation to legal 4.4 custom keys. Fallback is therefore a last resort, always
   * warned.
   * <p>
   * Resolution is uniform across strict and lenient contexts: a well-formed declaration for an unimplemented
   * version is not invalid content, so it warns rather than throwing even under
   * {@code VcfContext.strict(...)}. Only a structurally unusable value throws.
   *
   * @throws VcfFormatException if {@code declaredFileFormat} is {@code null} or does not match
   * {@link InternalUtils#FILE_FORMAT_PATTERN} -- a structural violation, matching VcfParser's existing
   * rejection of a missing {@code ##fileformat}.
   */
  public static VcfVersion resolve(@Nullable String declaredFileFormat, VcfWarnings warnings) {
    // validated up front, not only on the fallback path -- otherwise resolve("VCFv4.2", null) would succeed
    // while resolve("VCFv4.9", null) threw, which is worse than failing consistently. VcfWarnings is
    // caller configuration, not VCF content, so it takes a raw NPE per AGENTS.md's Null-Safety Policy.
    Preconditions.checkNotNull(warnings, "warnings must not be null");
    if (declaredFileFormat == null) {
      throw new VcfFormatException("##fileformat is null");
    }
    if (!InternalUtils.FILE_FORMAT_PATTERN.matcher(declaredFileFormat).matches()) {
      throw new VcfFormatException("##fileformat \"" + declaredFileFormat + "\" is not a valid VCF 4.x version");
    }
    // The digits after "4." are an integer minor version, not a decimal fraction: FILE_FORMAT_PATTERN hardcodes
    // the major as 4, and hts-specs treats these as discrete tokens throughout (test/vcf/4.1, 4.2, 4.3, and the
    // "Changes between VCFv4.4 and VCFv4.3" headings). Leading zeros are normalized away so VCFv4.01 resolves to
    // exactly V4_1 rather than falling through; a decimal reading would instead map a hypothetical VCFv4.10 to
    // 4.1 silently, interpreting a future version under ancient rules with no fallback warning at all.
    String normalizedMinor = minorOf(declaredFileFormat.substring("VCFv".length()));
    String normalizedId = "4." + normalizedMinor;
    for (VcfVersion v : values()) {
      if (v.m_id.equals(normalizedId)) {
        if (v.isAtLeast(HIGHEST_IMPLEMENTED) && v != HIGHEST_IMPLEMENTED) {
          return fallback(declaredFileFormat, HIGHEST_IMPLEMENTED, warnings);
        }
        return v;
      }
    }
    // Outside the table: either older than LOWEST_SUPPORTED (4.0) or newer than V4_5 (4.6+, 4.10+, ...).
    return fallback(declaredFileFormat,
        isBelowFloor(normalizedMinor) ? LOWEST_SUPPORTED : HIGHEST_IMPLEMENTED, warnings);
  }

  /** @return {@code id}'s minor-version digits with leading zeros stripped, keeping at least one digit. */
  private static String minorOf(String id) {
    return id.substring(id.indexOf('.') + 1).replaceFirst("^0+(?=.)", "");
  }

  /**
   * @return whether {@code normalizedMinor} is numerically below {@link #LOWEST_SUPPORTED}'s minor version.
   * Compared as zero-stripped digit strings -- length first, then lexically -- rather than parsed to an int:
   * FILE_FORMAT_PATTERN's {@code \d+} accepts an arbitrarily long minor version, so {@code Integer.parseInt}
   * would throw a raw {@code NumberFormatException} on e.g. {@code VCFv4.99999999999999999999}, a public-API
   * crash {@link #resolve}'s own contract promises not to produce.
   */
  private static boolean isBelowFloor(String normalizedMinor) {
    String floorMinor = minorOf(LOWEST_SUPPORTED.m_id);
    if (normalizedMinor.length() != floorMinor.length()) {
      return normalizedMinor.length() < floorMinor.length();
    }
    return normalizedMinor.compareTo(floorMinor) < 0;
  }

  private static VcfVersion fallback(String declared, VcfVersion target, VcfWarnings warnings) {
    warnings.warn("##fileformat={} declares a version whose semantics this library does not implement; " +
        "interpreting it as VCF {}", declared, target.getId());
    return target;
  }
}

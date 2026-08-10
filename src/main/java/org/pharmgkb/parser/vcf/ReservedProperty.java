package org.pharmgkb.parser.vcf;

import org.jspecify.annotations.Nullable;


/**
 * A field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public interface ReservedProperty {

  String getId();

  String getDescription();

  Class getType();

  boolean isList();

  /**
   * @return The exact VCF {@code Number} value this reserved key's declaration should have: an integer literal
   * (e.g. {@code "1"}, {@code "4"}) or one of {@code "A"}, {@code "G"}, {@code "R"}, {@code "."}.
   */
  String getExpectedNumber();

  /**
   * The version-aware form of {@link #getExpectedNumber()}: the {@code Number} this reserved key's declaration
   * should have specifically under {@code version}. Defaults to the version-independent value; overridden only
   * where a reserved key's own expected {@code Number} genuinely differs across versions this library targets --
   * today that is {@link ReservedFormatProperty#CopyNumberLikelihood CNL} only (see its own comment for the
   * citation). Any such override must dispatch via {@link VcfVersion#isAtLeast}, never a string or ordinal
   * comparison on {@link VcfVersion}.
   * <p>
   * The null check lives here, in the default method every constant either inherits or (for {@code CNL}) calls
   * through an override that performs the identical check first -- so the same public call shape behaves
   * uniformly across every implementing constant, not just the one that happens to dereference {@code version}.
   * @throws VcfFormatException if {@code version} is {@code null}, matching {@link VcfVersion#isAtLeast}'s own
   * null contract
   */
  default String getExpectedNumber(@Nullable VcfVersion version) {
    if (version == null) {
      throw new VcfFormatException("getExpectedNumber was called with a null version");
    }
    return getExpectedNumber();
  }
}

package org.pharmgkb.parser.vcf;

import java.util.Map;


/**
 * This class represents a single VCF PEDIGREE metadata line.
 * <p>
 * VCFv4.2.tex line 152: {@code ##PEDIGREE=<Name_0=ID,...,Name_N=ID>} -- unlike {@code ##INFO}/{@code ##FORMAT}/
 * {@code ##contig}, this declaration has no fixed schema (no mandatory {@code ID}, no {@code Description}), so
 * this class has no typed constructor mirroring a fixed field set -- only the raw properties map is exposed,
 * via the inherited {@link BaseMetadata} accessors.
 * <p>
 * This class's properties are immutable after construction, and more strictly so than the rest of the
 * hierarchy. Every {@link BaseMetadata} subclass is immutable through this library's <em>public</em> API (see
 * that class's own Javadoc), but this one also blocks the two package-private inserters: {@link #putPropertyRaw}
 * and {@link #putAndQuoteProperty} both throw {@link UnsupportedOperationException} here. That costs nothing --
 * unlike {@code InfoMetadata} and friends, this class has no typed constructor that needs to insert anything,
 * since {@code ##PEDIGREE} has no fixed schema. The only way to change a PEDIGREE entry's content is to build a
 * new instance and rebuild the {@link VcfMetadata} around it via {@link VcfMetadata#toBuilder()}.
 * <p>
 * That extra strictness is deliberate, not incidental: {@code VcfMetadata.Builder}'s {@code addPedigree}
 * dedupes on a {@code Set} of the {@code ID}s it has already seen, and nothing recomputes that set. If a
 * {@code PedigreeMetadata} already in the builder's list could have its own {@code ID} mutated in place, the
 * set would silently go stale -- a genuinely new {@code ID=A} would be rejected as a duplicate of an {@code A}
 * that no longer exists, and the real duplicate would be accepted. The six keyed types cannot make the same
 * guarantee (their typed constructors need the inserters), which is why the equivalent
 * {@code getId()}-vs-map-key gap for them is a documented, writer-caught residual rather than a closed one --
 * see {@code WONT_FIX.md}.
 * <p>
 * One in-package route remains, for every declaration type including this one:
 * {@code BaseMetadata.trustedMutableProperties()}, the deliberately unchecked test-only bypass. It is
 * package-private and has no production call site, but a caller reaching for it on a stored PEDIGREE entry can
 * still desync the builder's set. Nothing may treat "the PEDIGREE dedup set can never go stale" as an absolute
 * invariant of this package's own code -- only of its public API.
 *
 * @author Mark Woon
 */
public final class PedigreeMetadata extends BaseMetadata {

  PedigreeMetadata(VcfContext context, Map<String, String> properties) {
    super(context, "PEDIGREE", properties);
  }

  /**
   * Always throws: this class's properties are immutable after construction, including to this package's own
   * code. See this class's own Javadoc for why it is stricter here than in {@link BaseMetadata}.
   */
  @Override
  void putPropertyRaw(String key, String value) {
    throw new UnsupportedOperationException(
        "PedigreeMetadata's properties are immutable after construction; build a new instance instead");
  }

  /**
   * Always throws: this class's properties are immutable after construction. See this class's own Javadoc.
   */
  @Override
  void putAndQuoteProperty(String key, String value) {
    throw new UnsupportedOperationException(
        "PedigreeMetadata's properties are immutable after construction; build a new instance instead");
  }
}

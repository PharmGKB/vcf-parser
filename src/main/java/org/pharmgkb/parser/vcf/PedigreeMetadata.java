package org.pharmgkb.parser.vcf;

import java.util.Map;


/**
 * This class represents a single VCF PEDIGREE metadata line.
 * <p>
 * VCFv4.2.tex line 152: {@code ##PEDIGREE=<Name_0=G0-ID,Name_1=G1-ID,...,Name_N=GN-ID>} -- unlike {@code ##INFO}/{@code ##FORMAT}/
 * {@code ##contig}, VCFv4.2's own grammar for this declaration has no fixed schema (no mandatory {@code ID}, no
 * {@code Description}), so this class has no typed constructor mirroring a fixed field set -- only the raw
 * properties map is exposed, via the inherited {@link BaseMetadata} accessors. VCFv4.3+ (VCFv4.3.tex line 121,
 * "All structured lines require an ID") does make {@code ID} mandatory, enforced by
 * {@code VcfMetadata.Builder.addPedigree} rather than here -- see that method's Javadoc and
 * DUPLICATE_METADATA_ID_HANDLING.md.
 * <p>
 * This class's properties are immutable after construction, and more strictly so than the rest of the
 * hierarchy. Every {@link BaseMetadata} subclass is immutable through this library's <em>public</em> API (see
 * that class's own Javadoc), but this one also blocks the two package-private inserters: {@link #putPropertyRaw}
 * and {@link #putAndQuoteProperty} both throw {@link UnsupportedOperationException} here. That costs nothing --
 * unlike {@code InfoMetadata} and friends, this class has no typed constructor that needs to insert anything,
 * since {@code ##PEDIGREE} has no fixed schema. The only way to change a PEDIGREE entry's content is to build a
 * new instance and rebuild the {@link VcfMetadata} around it via {@link VcfMetadata#toBuilder()}.
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

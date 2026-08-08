package org.pharmgkb.parser.vcf;

import java.util.Collections;
import java.util.Map;


/**
 * This class represents a single VCF PEDIGREE metadata line.
 * <p>
 * VCFv4.2.tex line 152: {@code ##PEDIGREE=<Name_0=ID,...,Name_N=ID>} -- unlike {@code ##INFO}/{@code ##FORMAT}/
 * {@code ##contig}, this declaration has no fixed schema (no mandatory {@code ID}, no {@code Description}), so
 * this class has no typed constructor mirroring a fixed field set -- only the raw properties map is exposed,
 * via the inherited {@link BaseMetadata} accessors.
 * <p>
 * Unlike every other {@link BaseMetadata} subclass, this class's properties are immutable after construction
 * (its inherited {@link #setWarningsSink} is not affected -- that's a caller-configuration detail, not VCF
 * content): {@link #getPropertiesRaw()} returns an unmodifiable view, and {@link #putPropertyRaw}/{@link
 * #putAndQuoteProperty} both throw. This class is also {@code final} so no subclass can reintroduce mutability
 * by overriding those methods. The only way to change a PEDIGREE entry's content is to build a new instance and
 * use {@link VcfMetadata#removePedigree}/{@link VcfMetadata#addPedigree} (or the equivalent direct add/remove
 * mutation of the live list returned by {@link VcfMetadata#getPedigrees()}) to swap it in. This is deliberate,
 * not merely incidental: {@link VcfMetadata#addPedigree}'s duplicate-ID dedup caches seen IDs for performance
 * (see its own javadoc), and that cache is only invalidated by a structural change to {@code m_pedigree} (an
 * add, a remove, or the like) -- if a {@code PedigreeMetadata} already in the list could have its own {@code
 * ID} property mutated in place, the cache would have no way to detect that and could give a stale answer in
 * either direction. Making this class immutable removes that possibility entirely, rather than accepting it as
 * a residual risk. (A {@code List.set(i, ...)}/{@code ListIterator.set(...)} replacement of a list slot's
 * occupant is a separate, narrower, still-accepted residual gap unrelated to this class's own mutability -- see
 * {@code WONT_FIX.md}.)
 *
 * @author Mark Woon
 */
public final class PedigreeMetadata extends BaseMetadata {

  PedigreeMetadata(VcfContext context, Map<String, String> properties) {
    super(context, "PEDIGREE", properties);
  }

  /**
   * @return An unmodifiable view of the backing properties map -- unlike {@link BaseMetadata#getPropertiesRaw()},
   * a mutation attempt throws {@link UnsupportedOperationException} rather than silently succeeding. See this
   * class's own javadoc for why.
   */
  @Override
  public Map<String, String> getPropertiesRaw() {
    return Collections.unmodifiableMap(super.getPropertiesRaw());
  }

  /**
   * Always throws: this class's properties are immutable after construction. See this class's own javadoc.
   */
  @Override
  public void putPropertyRaw(String key, String value) {
    throw new UnsupportedOperationException(
        "PedigreeMetadata's properties are immutable after construction; build a new instance instead");
  }

  /**
   * Always throws: this class's properties are immutable after construction. See this class's own javadoc.
   */
  @Override
  public void putAndQuoteProperty(String key, String value) {
    throw new UnsupportedOperationException(
        "PedigreeMetadata's properties are immutable after construction; build a new instance instead");
  }
}

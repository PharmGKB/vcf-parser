package org.pharmgkb.parser.vcf;

import java.util.Map;


/**
 * VCF metadata for {@code ##META=<...>} declarations.
 * <p>
 * VCFv4.3.tex:273-276 gives four worked examples (e.g. {@code ##META=<ID=Assay,Type=String,Number=.,
 * Values=[WholeGenome, Exome]>}) but no prose grammar for {@code Values} or the other extra attributes, so this
 * class validates only what {@link IdMetadata} already validates for every ID-bearing declaration -- a required,
 * unique {@code ID} -- and leaves {@code Type}/{@code Number}/{@code Values} and anything else otherwise
 * unvalidated, as opaque properties reachable only through the inherited {@link BaseMetadata} accessors.
 * No attribute-order enforcement applies to {@code ##META}, or to any other declaration type in this
 * hierarchy: no VCF spec version requires an attribute order (VCFv4.3, our target, says nothing at all;
 * VCFv4.4/4.5 say implementations "must not rely on the order"), and an order check would have warned --
 * and thrown under a strict context -- on the specification's own worked example, {@code VCFv4.3.tex:273}'s
 * {@code ID,Type,Number}. See {@code VCF_SPEC_AMBIGUITIES.md}'s "`##META` attribute order" entry for the
 * full measured evidence and the decision to remove attribute-order checking from every keyed type, not
 * just this one.
 * <p>
 * {@code final}: unlike {@link ContigMetadata}, this type has no subclass of its own.
 */
public final class MetaMetadata extends IdMetadata {

  MetaMetadata(VcfContext context, Map<String, String> properties) {
    super(context, "META", properties);
  }

}

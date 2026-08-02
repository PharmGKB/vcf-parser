package org.pharmgkb.parser.vcf.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * A VCF metadata element with an Id (called "ID").
 */
public abstract class IdMetadata extends BaseMetadata {

  public static final String ID = "ID";

  protected IdMetadata(String label, String id) {
    super(label, new LinkedHashMap<>());
    // VCFv4.3.tex line 121: "All structured lines require an ID..." -- VCFv4.2 doesn't use the word "required" but
    // its own templates for all six keyed types show ID unconditionally first; see DUPLICATE_METADATA_ID_HANDLING.md
    if (id == null || id.trim().isEmpty()) {
      throw new VcfFormatException("ID is required and must not be blank");
    }
    // strict construction: throws immediately instead of deferring to init()'s warn-based whitespace check (see
    // that method's own comment, cited from VCFv4.2.tex/VCFv4.3.tex line 105/200 and the hts-specs fixtures named
    // there) -- init() remains warn-based for the Map-based (lenient) constructor below
    if (InternalUtils.containsWhitespace(id)) {
      throw new VcfFormatException(label + " metadata ID \"" + id + "\" must not contain whitespace");
    }
    // strict construction, same reasoning as the whitespace check above -- see init()'s own comment (cited from
    // every ##INFO/##FILTER/##FORMAT/##ALT/##contig/##SAMPLE worked example's shared comma-delimited
    // attribute-list grammar) for why this applies uniformly, including to ALT, with no exception.
    if (id.contains(",")) {
      throw new VcfFormatException(label + " metadata ID \"" + id + "\" must not contain a comma");
    }
    putPropertyRaw(ID, id);
  }

  protected IdMetadata(String label, Map<String, String> properties, VcfWarnings warnings) {
    super(label, properties);
    setWarningsSink(warnings);
    init();
  }

  @Override
  public void validate() {
    super.validate();
    init();
    checkAttributeOrder();
  }

  private void init() {
    String id = getPropertyRaw(ID);
    warnIfMissing(ID, id);
    // warnIfMissing only tests for null; a present-but-blank ID (id.equals("")) previously got no diagnostic at
    // all here -- containsWhitespace(id) below doesn't fire either, since an empty string has no characters to
    // contain whitespace. This class's own typed (label, id) constructor already rejects both null and blank
    // (VCFv4.3.tex line 121: "All structured lines require an ID..."); this closes the identical gap for the
    // Map-based constructor and for validate() re-running on a live-mutated blank ID, via the same
    // already-active validate()/VcfWriter.validateBeforeWrite() mechanism the checks below rely on.
    if (id != null && id.isEmpty()) {
      m_warnings.warn("{} metadata ID is blank", getLabel());
    }
    // VCFv4.2.tex/VCFv4.3.tex line 105/200 only states this rule for ##ALT's own ID explicitly, but hts-specs'
    // own conformance fixtures (test/vcf/{4.1,4.2,4.3}/failed/failed_meta_contig_001.vcf,
    // test/vcf/4.3/failed/failed_meta_sample_001.vcf) extend it to ##contig and ##SAMPLE, bcftools 1.23.1
    // empirically warns ("Invalid tag name"/"Invalid contig name") for ##INFO/##FORMAT/##contig ID whitespace,
    // and vcf_validator 0.10.2 rejects whitespace uniformly across every structured metadata ID type. For
    // ##FILTER/##SAMPLE specifically, bcftools 1.23.1 accepts whitespace silently -- it has no dedicated header
    // check for either -- directly contradicting hts-specs' own failed_meta_sample_001.vcf fixture; this is
    // treated as a bcftools coverage gap rather than a considered position, so the hts-specs fixture and
    // vcf_validator's uniform treatment govern instead, per this project's decision hierarchy.
    if (id != null && InternalUtils.containsWhitespace(id)) {
      m_warnings.warn("{} metadata ID \"{}\" must not contain whitespace", getLabel(), id);
    }
    // Every ##INFO/##FILTER/##FORMAT/##ALT/##contig/##SAMPLE worked example in VCFv4.2.tex (e.g. line 35:
    // "##INFO=<ID=NS,Number=1,...>") delimits attributes with an unescaped comma; a literal comma inside an ID
    // value would be indistinguishable from the end of the ID attribute itself, for every one of these six
    // types uniformly -- unlike the whitespace check above, this needs no bcftools/vcf_validator corroboration,
    // since it follows directly from the attribute-list grammar every example shares, not a per-type policy
    // choice. Deliberately not scoped away from ALT the way angle brackets are (see VcfMetadata.validateAlt()'s
    // own comment): a comma is never part of either of ALT's spec-sanctioned colonless conventions (an IUPAC
    // ambiguity code or a CN0-style copy-number allele) or its colon-delimited structural-variant-code grammar,
    // so this check applies to every type this class covers, with no exception.
    if (id != null && id.contains(",")) {
      m_warnings.warn("{} metadata ID \"{}\" must not contain a comma", getLabel(), id);
    }
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public String getId() {
    return getPropertyRaw(ID);
  }

}

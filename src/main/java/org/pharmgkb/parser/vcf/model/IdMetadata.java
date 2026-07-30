package org.pharmgkb.parser.vcf.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;
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
    putPropertyRaw(ID, id);
    init();
  }

  protected IdMetadata(String label, Map<String, String> properties) {
    super(label, properties);
    init();
  }

  @Override
  public void validate() {
    super.validate();
    init();
  }

  private void init() {
    String id = getPropertyRaw(ID);
    warnIfMissing(ID, id);
    // VCFv4.2.tex/VCFv4.3.tex line 105/200 only states this rule for ##ALT's own ID explicitly, but hts-specs'
    // own conformance fixtures (test/vcf/{4.1,4.2,4.3}/failed/failed_meta_contig_001.vcf,
    // test/vcf/4.3/failed/failed_meta_sample_001.vcf) extend it to ##contig and ##SAMPLE, bcftools 1.23.1
    // empirically warns ("Invalid tag name"/"Invalid contig name") for ##INFO/##FORMAT/##contig ID whitespace,
    // and vcf_validator 0.10.2 rejects whitespace uniformly across every structured metadata ID type. For
    // ##FILTER/##SAMPLE specifically, bcftools 1.23.1 accepts whitespace silently -- it has no dedicated header
    // check for either -- directly contradicting hts-specs' own failed_meta_sample_001.vcf fixture; this is
    // treated as a bcftools coverage gap rather than a considered position, so the hts-specs fixture and
    // vcf_validator's uniform treatment govern instead, per this project's decision hierarchy.
    if (id != null && VcfUtils.containsWhitespace(id)) {
      VcfWarnings.warn("{} metadata ID \"{}\" must not contain whitespace", getLabel(), id);
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

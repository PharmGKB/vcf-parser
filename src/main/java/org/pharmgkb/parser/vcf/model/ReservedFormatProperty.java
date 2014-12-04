package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * A FORMAT field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedFormatProperty implements ReservedProperty {

  Genotype("GT", "Genotype, encoded as allele values separated by either / or |.", String.class, false),
  Depth("DP", "Read depth at this position for this sample.", Long.class, false),
  Filter("FT", "Sample genotype filter indicating if this genotype was called.", String.class, false),
  GenotypeLikelihoods("GL", "Genotype likelihoods comprised of comma separated floating point log10-scaled likelihoods"
      + " for all possible genotypes given the set of alleles defined in the REF and ALT fields.",
      BigDecimal.class, true),
  GenotypeLikelihoodsOfHeterogenousPloidy("GLE",
      "genotype likelihoods of heterogeneous ploidy, used in presence of uncertain copy number.",
      String.class, true),
  PhredScaledGenotypeLikelihoods("PL", "the phred-scaled genotype likelihoods rounded to the closest integer" +
      "(and otherwise defined precisely as the GL field)", Long.class, true),
  GenotypePosteriorProbabilitiesPhredScaled("GP", "The phred-scaled genotype posterior probabilities (and otherwise defined " +
      "precisely as the GL field); intended to store imputed genotype probabilities", BigDecimal.class, true),
  GenotypeQualityConditional("GQ", "conditional genotype quality, encoded as a phred quality" +
      "−10log10p(genotype call is wrong, conditioned on the site’s being variant)", Long.class, false),
  HaplotypeQualities("HQ", "Haplotype qualities, two comma separated phred qualities", Long.class, true),
  PhaseSet("PS", "Phase set; see specification.", Long.class, false),
  PhasingQuality("PQ", "Phasing quality, the phred-scaled probability that alleles are ordered incorrectly in a " +
      "heterozygote (against all other members in the phase set).", Long.class, false),
  ExpectedAlleleCounts("EC", "List of expected alternate allele counts for each alternate allele in the same order " +
      "as listed in the ALT field (typically used in association analyses)", Long.class, true),
  MappingQuality("MQ", "RMS mapping quality, similar to the version in the INFO field.", Long.class, true);

  private final @Nonnull String m_id;

  private final @Nonnull String m_description;

  private final @Nonnull Class m_type;

  private final boolean m_isList;

  ReservedFormatProperty(@Nonnull String id, @Nonnull String description, @Nonnull Class type, boolean isList) {
    m_id = id;
    m_description = description;
    m_type = type;
    m_isList = isList;
  }

  @Nonnull
  public String getId() {
    return m_id;
  }

  @Nonnull
  public String getDescription() {
    return m_description;
  }

  @Nonnull
  public Class getType() {
    return m_type;
  }

  public boolean isList() {
    return m_isList;
  }

}

package org.pharmgkb.parser.vcf.model;


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
      VcfFloat.class, true),
  // isList=false: the spec's own example (GLE=0:-75.22,1:-223.42,0/0:-323.03,...) is one opaque String that uses
  // commas internally as part of its own genotype:likelihood encoding, not a delimited list of independent values
  GenotypeLikelihoodsOfHeterogenousPloidy("GLE",
      "genotype likelihoods of heterogeneous ploidy, used in presence of uncertain copy number.",
      String.class, false),
  PhredScaledGenotypeLikelihoods("PL", "the phred-scaled genotype likelihoods rounded to the closest integer " +
      "(and otherwise defined precisely as the GL field)", Long.class, true),
  GenotypePosteriorProbabilitiesPhredScaled("GP", "The phred-scaled genotype posterior probabilities (and otherwise defined " +
      "precisely as the GL field); intended to store imputed genotype probabilities", VcfFloat.class, true),
  GenotypeQualityConditional("GQ", "conditional genotype quality, encoded as a phred quality " +
      "−10log10p(genotype call is wrong, conditioned on the site’s being variant)", Long.class, false),
  HaplotypeQualities("HQ", "Haplotype qualities, two comma separated phred qualities", Long.class, true),
  PhaseSet("PS", "Phase set; see specification.", Long.class, false),
  PhasingQuality("PQ", "Phasing quality, the phred-scaled probability that alleles are ordered incorrectly in a " +
      "heterozygote (against all other members in the phase set).", Long.class, false),
  ExpectedAlleleCounts("EC", "List of expected alternate allele counts for each alternate allele in the same order " +
      "as listed in the ALT field (typically used in association analyses)", Long.class, true),
  // isList=false: unlike GL/PL/GP/HQ/EC (explicitly described as comma-separated in the spec), MQ has no such
  // qualifier -- a single value
  MappingQuality("MQ", "RMS mapping quality, similar to the version in the INFO field.", Long.class, false),

  // structural variants

  CopyNumber("CN", "Copy number genotype for imprecise events", Long.class, false),
  CopyNumberGenotypeQuality("CNQ", "Copy number genotype quality for imprecise events", VcfFloat.class, false),
  CopyNumberLikelihood("CNL", "Copy number genotype likelihood for imprecise events", VcfFloat.class, true),
  PhredScoreForNovelty("NQ", "Phred style probability score that the variant is novel", Long.class, false),
  HaplotypeId("HAP", "Unique haplotype identifier", Long.class, false),
  AncestralHaplotypeId("AHAP", "Unique identifier of ancestral haplotype", Long.class, false);

  private final String m_id;

  private final String m_description;

  private final Class m_type;

  private final boolean m_isList;

  ReservedFormatProperty(String id, String description, Class type, boolean isList) {
    m_id = id;
    m_description = description;
    m_type = type;
    m_isList = isList;
  }

  public String getId() {
    return m_id;
  }

  public String getDescription() {
    return m_description;
  }

  public Class getType() {
    return m_type;
  }

  public boolean isList() {
    return m_isList;
  }

}

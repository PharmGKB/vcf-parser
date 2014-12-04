package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * An INFO field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedInfoProperty implements ReservedProperty {

  AncestralAllele("AA", "Ancestral allele.", String.class, false),
  AlleleCount("AC", "Allele count in genotypes, for each ALT allele, in the same order as listed",
      Long.class, true),
  AlleleFrequency("AF", "Allele frequency for each ALT allele in the same order as listed: use this when estimated" +
      "from primary data, not called genotypes", BigDecimal.class, true),
  AlleleNumber("AN", "Total number of alleles in called genotypes", Long.class, false),
  BaseQuality("BQ", "RMS base quality at this position", BigDecimal.class, false),
  Cigar("CIGAR", "Cigar string describing how to align an alternate allele to the reference allele", String.class,
      false),
  Dbsnp("DB", "dbSNP membership", Boolean.class, false),
  Depth("DP", "Combined depth across samples", BigDecimal.class, false),
  End("END", "End position of the variant described in this record (for use with symbolic alleles)", String.class,
      false),
  Hapmap2("H2", "Membership in HapMap2", Boolean.class, false),
  Hapmap3("H3", "Membership in HapMap2", Boolean.class, false),
  MappingQuality("MQ", "RMS mapping quality, e.g. MQ=52", Float.class, false),
  MappingQualityZeroCount("MQ0", "Number of MAPQ == 0 reads covering this record", Long.class, false),
  NumberOfSamples("NS", "Number of samples with data", Long.class, false),
  StrandBias("SB", "Strand bias at this position", Long.class, true),
  SomaticMutation("SOMATIC", "Indicates that the record is a somatic mutation", Boolean.class, false),
  Validated("VALIDATED", "Validated by follow-up experiment", Boolean.class, false),
  ThousandGenomes("1000G", "Is a member of 1000 genomes", Boolean.class, false);

  private final @Nonnull String m_id;

  private final @Nonnull String m_description;

  private final @Nonnull Class m_type;

  private final boolean m_isList;

  ReservedInfoProperty(@Nonnull String id, @Nonnull String description, @Nonnull Class type, boolean isList) {
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

package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * An INFO field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedInfoProperty implements ReservedProperty {

  // standard

  AncestralAllele("AA", "Ancestral allele", String.class, false, "1"),
  AlleleCount("AC", "Allele count in genotypes, for each ALT allele, in the same order as listed",
      Long.class, true, "A"),
  AlleleFrequency("AF", "Allele frequency for each ALT allele in the same order as listed: use this when estimated" +
      "from primary data, not called genotypes", BigDecimal.class, true, "A"),
  AlleleNumber("AN", "Total number of alleles in called genotypes", Long.class, false, "1"),
  BaseQuality("BQ", "RMS base quality at this position", BigDecimal.class, false, "1"),
  Cigar("CIGAR", "Cigar string describing how to align an alternate allele to the reference allele", String.class,
      false, "1"),
  Dbsnp("DB", "dbSNP membership", Boolean.class, false, "0"),
  Depth("DP", "Combined depth across samples", BigDecimal.class, false, "1"),
  Hapmap2("H2", "Membership in HapMap2", Boolean.class, false, "0"),
  Hapmap3("H3", "Membership in HapMap2", Boolean.class, false, "0"),
  MappingQuality("MQ", "RMS mapping quality, e.g. MQ=52", Float.class, false, "1"),
  MappingQualityZeroCount("MQ0", "Number of MAPQ == 0 reads covering this record", Long.class, false, "1"),
  NumberOfSamples("NS", "Number of samples with data", Long.class, false, "1"),
  StrandBias("SB", "Strand bias at this position", Long.class, true, "."),
  SomaticMutation("SOMATIC", "Indicates that the record is a somatic mutation", Boolean.class, false, "0"),
  Validated("VALIDATED", "Validated by follow-up experiment", Boolean.class, false, "0"),
  ThousandGenomes("1000G", "Is a member of 1000 genomes", Boolean.class, false, "0"),

  // imprecise structural variants

  Imprecise("IMPRECISE", "Imprecise structural variation", Boolean.class, false, "0"),
  Novel("NOVEL", "Indicates a novel structural variation", Boolean.class, false, "0"),
  End("END", "End position of the variant described in this record", Long.class, false, "1"), // also standard

  /**
   * Note that the VCF 4.2 spec calls this "SYTYPE", which is clearly a typo.
   */
  StructuralVariantType("SVTYPE", "Type of structural variant", String.class, false, "1"),
  StructuralVariantLength("SVLEN", "Difference in length between REF and ALT alleles", Long.class, false, "."),

  ConfidenceIntervalForPosition("CIPOS", "Confidence interval around POS for imprecise variants", Long.class, true, "2"),
  ConfidenceIntervalForEnd("CIEND", "Confidence interval around END for imprecise variants", Long.class, true, "2"),
  HomologyLength("HOMLEN", "Length of base pair identical micro-homology at event breakpoints", Long.class, false, "."),
  HomologySequence("HOMLEN", "Sequence of base pair identical micro-homology at event breakpoints", String.class, false, "."),

  BreakpointId("BKPTID", "ID of the assembled alternate allele in the assembly file", String.class, false, "."),

  // precise structural variants

  MobileElementInfo("MEINFO", "Mobile element info of the form NAME,START,END,POLARITY", String.class, true, "4"),
  MobileElementTransduction("METRANS", "Mobile element transduction info of the form CHR,START,END,POLARITY", String.class, true, "4"),
  DgvId("DGVID", "ID of this element in Database of Genomic Variation", String.class, false, "1"),
  DbvarId("DBVARID", "ID of this element in DBVAR", String.class, false, "1"),
  DbripId("DBRIPID", "ID of this element in DBRIP", String.class, false, "1"),
  MateId("MATEID", "ID of mate breakends", String.class, false, "."),
  PartnerId("PARID", "ID of partner breakend", String.class, false, "1"),
  EventId("EVENT", "ID of event associated to breakend", String.class, false, "1"),
  ConfidenceIntervalForInsertedMaterial("CILEN", "Confidence interval around the inserted material between breakends", Long.class, true, "2"),
  ReadDepthOfAdjacency("DPADJ", "Read Depth of adjacency", Long.class, true, "."),
  CopyNumberOfSegment("CN", "Copy number of segment containing breakend", Long.class, false, "1"),
  CopyNumberOfAdjacency("CNADJ", "Copy number of adjacency", Long.class, true, "."),
  ConfidenceIntervalForSegmentCopyNumber("CICN", "Confidence interval around copy number for the segment", Long.class, true, "2"),
  ConfidenceIntervalForAdjacencyCopyNumber("CICNADJ", "Confidence interval around copy number for the adjacency", Long.class, true, "2");


  private final @Nonnull String m_id;

  private final @Nonnull String m_description;

  private final @Nonnull Class m_type;

  private final @Nonnull String m_number;

  private final boolean m_isList;

  ReservedInfoProperty(@Nonnull String id, @Nonnull String description, @Nonnull Class type, boolean isList,
      @Nonnull String number) {
    m_id = id;
    m_description = description;
    m_type = type;
    m_isList = isList;
    m_number = number;
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

  @Nonnull
  public String getNumber() {
    return m_number;
  }

  public boolean isList() {
    return m_isList;
  }

}

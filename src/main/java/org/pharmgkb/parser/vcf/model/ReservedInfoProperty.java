package org.pharmgkb.parser.vcf.model;

import java.math.BigDecimal;


/**
 * An INFO field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedInfoProperty implements ReservedProperty {

  // standard

  AncestralAllele("AA", "Ancestral allele", String.class, false),
  AlleleCount("AC", "Allele count in genotypes, for each ALT allele, in the same order as listed",
      Long.class, true),
  AlleleFrequency("AF", "Allele frequency for each ALT allele in the same order as listed: use this when estimated " +
      "from primary data, not called genotypes", BigDecimal.class, true),
  AlleleNumber("AN", "Total number of alleles in called genotypes", Long.class, false),
  BaseQuality("BQ", "RMS base quality at this position", BigDecimal.class, false),
  Cigar("CIGAR", "Cigar string describing how to align an alternate allele to the reference allele", String.class,
      false),
  Dbsnp("DB", "dbSNP membership", Boolean.class, false),
  Depth("DP", "Combined depth across samples", Long.class, false),
  Hapmap2("H2", "Membership in HapMap2", Boolean.class, false),
  Hapmap3("H3", "Membership in HapMap3", Boolean.class, false),
  MappingQuality("MQ", "RMS mapping quality, e.g. MQ=52", BigDecimal.class, false),
  MappingQualityZeroCount("MQ0", "Number of MAPQ == 0 reads covering this record", Long.class, false),
  NumberOfSamples("NS", "Number of samples with data", Long.class, false),
  StrandBias("SB", "Strand bias at this position", Long.class, true),
  SomaticMutation("SOMATIC", "Indicates that the record is a somatic mutation", Boolean.class, false),
  Validated("VALIDATED", "Validated by follow-up experiment", Boolean.class, false),
  ThousandGenomes("1000G", "Is a member of 1000 genomes", Boolean.class, false),

  // imprecise structural variants

  Imprecise("IMPRECISE", "Imprecise structural variation", Boolean.class, false),
  Novel("NOVEL", "Indicates a novel structural variation", Boolean.class, false),
  End("END", "End position of the variant described in this record", Long.class, false), // also standard

  /**
   * Note that the VCF 4.2 spec calls this "SYTYPE", which is clearly a typo.
   */
  StructuralVariantType("SVTYPE", "Type of structural variant", String.class, false),
  // Number=. is genuinely multi-valued (one per ALT allele in a multi-allelic record, e.g. "SVLEN=-100,-110"),
  // per VCFv4.3's clarifying example; confirmed against VCFv4.2's own ##INFO declaration for this key.
  StructuralVariantLength("SVLEN", "Difference in length between REF and ALT alleles", Long.class, true),

  ConfidenceIntervalForPosition("CIPOS", "Confidence interval around POS for imprecise variants", Long.class, true),
  ConfidenceIntervalForEnd("CIEND", "Confidence interval around END for imprecise variants", Long.class, true),
  HomologyLength("HOMLEN", "Length of base pair identical micro-homology at event breakpoints", Long.class, true),
  HomologySequence("HOMSEQ", "Sequence of base pair identical micro-homology at event breakpoints", String.class,
      true),

  BreakpointId("BKPTID", "ID of the assembled alternate allele in the assembly file", String.class, true),

  // precise structural variants

  MobileElementInfo("MEINFO", "Mobile element info of the form NAME,START,END,POLARITY", String.class, true),
  MobileElementTransduction("METRANS", "Mobile element transduction info of the form CHR,START,END,POLARITY", String.class, true),
  DgvId("DGVID", "ID of this element in Database of Genomic Variation", String.class, false),
  DbvarId("DBVARID", "ID of this element in DBVAR", String.class, false),
  DbripId("DBRIPID", "ID of this element in DBRIP", String.class, false),
  MateId("MATEID", "ID of mate breakends", String.class, true),
  PartnerId("PARID", "ID of partner breakend", String.class, false),
  EventId("EVENT", "ID of event associated to breakend", String.class, false),
  ConfidenceIntervalForInsertedMaterial("CILEN", "Confidence interval around the inserted material between breakends", Long.class, true),
  ReadDepthOfAdjacency("DPADJ", "Read Depth of adjacency", Long.class, true),
  CopyNumberOfSegment("CN", "Copy number of segment containing breakend", Long.class, false),
  CopyNumberOfAdjacency("CNADJ", "Copy number of adjacency", Long.class, true),
  ConfidenceIntervalForSegmentCopyNumber("CICN", "Confidence interval around copy number for the segment", Long.class, true),
  ConfidenceIntervalForAdjacencyCopyNumber("CICNADJ", "Confidence interval around copy number for the adjacency", Long.class, true);


  private final String m_id;

  private final String m_description;

  private final Class m_type;

  private final boolean m_isList;

  ReservedInfoProperty(String id, String description, Class type, boolean isList) {
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

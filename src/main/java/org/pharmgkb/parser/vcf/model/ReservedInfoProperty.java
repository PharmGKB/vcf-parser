package org.pharmgkb.parser.vcf.model;

import org.jspecify.annotations.Nullable;


/**
 * An INFO field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedInfoProperty implements ReservedProperty {

  // standard -- VCFv4.2 only describes these in prose with no formal Number/Type table; VCFv4.3's Reserved INFO
  // Keys table (table:reserved-info) is used as clarifying guidance for expectedNumber, per AGENTS.md (VCFv4.2 is
  // silent here, not in conflict)

  AncestralAllele("AA", "Ancestral allele", String.class, false, "1"),
  AlleleCount("AC", "Allele count in genotypes, for each ALT allele, in the same order as listed",
      Long.class, true, "A"),
  AlleleFrequency("AF", "Allele frequency for each ALT allele in the same order as listed: use this when estimated " +
      "from primary data, not called genotypes", VcfFloat.class, true, "A"),
  AlleleNumber("AN", "Total number of alleles in called genotypes", Long.class, false, "1"),
  BaseQuality("BQ", "RMS base quality at this position", VcfFloat.class, false, "1"),
  // isList=true (Number=A, one value per ALT allele): VCFv4.2 only describes CIGAR in prose, with no formal
  // Number declared, but VCFv4.3, 4.4, and 4.5 all unanimously and explicitly declare it Number=A in their
  // reserved INFO tables -- a clarification of something 4.2 left undefined, not a conflict with it.
  Cigar("CIGAR", "Cigar string describing how to align an alternate allele to the reference allele", String.class,
      true, "A"),
  Dbsnp("DB", "dbSNP membership", Boolean.class, false, "0"),
  Depth("DP", "Combined depth across samples", Long.class, false, "1"),
  Hapmap2("H2", "Membership in HapMap2", Boolean.class, false, "0"),
  Hapmap3("H3", "Membership in HapMap3", Boolean.class, false, "0"),
  MappingQuality("MQ", "RMS mapping quality, e.g. MQ=52", VcfFloat.class, false, "1"),
  MappingQualityZeroCount("MQ0", "Number of MAPQ == 0 reads covering this record", Long.class, false, "1"),
  NumberOfSamples("NS", "Number of samples with data", Long.class, false, "1"),
  // expectedNumber is the literal count "4" (VCFv4.3's table gives SB Number=4), not a variable-list code -- unlike
  // most isList=true entries here, SB's list length is fixed, not one-per-ALT/-genotype/-.
  StrandBias("SB", "Strand bias at this position", Long.class, true, "4"),
  SomaticMutation("SOMATIC", "Indicates that the record is a somatic mutation", Boolean.class, false, "0"),
  Validated("VALIDATED", "Validated by follow-up experiment", Boolean.class, false, "0"),
  ThousandGenomes("1000G", "Is a member of 1000 genomes", Boolean.class, false, "0"),

  // imprecise structural variants -- these ARE declared natively with an explicit ##INFO=<...> example in
  // VCFv4.2.tex itself, so no VCFv4.3 clarification is needed; expectedNumber is taken directly from that example

  Imprecise("IMPRECISE", "Imprecise structural variation", Boolean.class, false, "0"),
  Novel("NOVEL", "Indicates a novel structural variation", Boolean.class, false, "0"),
  End("END", "End position of the variant described in this record", Long.class, false, "1"), // also standard

  /**
   * Note that the VCF 4.2 spec calls this "SYTYPE", which is clearly a typo.
   */
  StructuralVariantType("SVTYPE", "Type of structural variant", String.class, false, "1"),
  // Number=. is genuinely multi-valued (one per ALT allele in a multi-allelic record, e.g. "SVLEN=-100,-110"),
  // per VCFv4.3's clarifying example; confirmed against VCFv4.2's own ##INFO declaration for this key.
  StructuralVariantLength("SVLEN", "Difference in length between REF and ALT alleles", Long.class, true, "."),

  ConfidenceIntervalForPosition("CIPOS", "Confidence interval around POS for imprecise variants", Long.class, true,
      "2"), // fixed count of 2 (VCFv4.2.tex's own ##INFO=<ID=CIPOS,...> example), not a variable-list code
  ConfidenceIntervalForEnd("CIEND", "Confidence interval around END for imprecise variants", Long.class, true,
      "2"), // same as CIPOS: fixed count of 2
  HomologyLength("HOMLEN", "Length of base pair identical micro-homology at event breakpoints", Long.class,
      true, "."),
  HomologySequence("HOMSEQ", "Sequence of base pair identical micro-homology at event breakpoints", String.class,
      true, "."),

  BreakpointId("BKPTID", "ID of the assembled alternate allele in the assembly file", String.class, true, "."),

  // precise structural variants -- same sourcing note as the imprecise-SV group above: declared natively in
  // VCFv4.2.tex, no VCFv4.3 clarification needed

  MobileElementInfo("MEINFO", "Mobile element info of the form NAME,START,END,POLARITY", String.class, true,
      "4"), // fixed count of 4 (NAME,START,END,POLARITY), not a variable-list code
  MobileElementTransduction("METRANS", "Mobile element transduction info of the form CHR,START,END,POLARITY",
      String.class, true, "4"), // fixed count of 4, same reasoning as MEINFO
  DgvId("DGVID", "ID of this element in Database of Genomic Variation", String.class, false, "1"),
  DbvarId("DBVARID", "ID of this element in DBVAR", String.class, false, "1"),
  DbripId("DBRIPID", "ID of this element in DBRIP", String.class, false, "1"),
  MateId("MATEID", "ID of mate breakends", String.class, true, "."),
  PartnerId("PARID", "ID of partner breakend", String.class, false, "1"),
  EventId("EVENT", "ID of event associated to breakend", String.class, false, "1"),
  ConfidenceIntervalForInsertedMaterial("CILEN", "Confidence interval around the inserted material between breakends",
      Long.class, true, "2"), // fixed count of 2, same reasoning as CIPOS/CIEND
  ReadDepthOfAdjacency("DPADJ", "Read Depth of adjacency", Long.class, true, "."),
  CopyNumberOfSegment("CN", "Copy number of segment containing breakend", Long.class, false, "1"),
  CopyNumberOfAdjacency("CNADJ", "Copy number of adjacency", Long.class, true, "."),
  ConfidenceIntervalForSegmentCopyNumber("CICN", "Confidence interval around copy number for the segment",
      Long.class, true, "2"), // fixed count of 2, same reasoning as CIPOS/CIEND
  ConfidenceIntervalForAdjacencyCopyNumber("CICNADJ", "Confidence interval around copy number for the adjacency",
      Long.class, true, ".");


  private final String m_id;

  private final String m_description;

  private final Class m_type;

  private final boolean m_isList;

  private final String m_expectedNumber;

  ReservedInfoProperty(String id, String description, Class type, boolean isList, String expectedNumber) {
    m_id = id;
    m_description = description;
    m_type = type;
    m_isList = isList;
    m_expectedNumber = expectedNumber;
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

  public String getExpectedNumber() {
    return m_expectedNumber;
  }

  @Nullable
  public static ReservedInfoProperty fromId(String id) {
    switch (id) {
      case "AA":
        return AncestralAllele;
      case "AC":
        return AlleleCount;
      case "AF":
        return AlleleFrequency;
      case "AN":
        return AlleleNumber;
      case "BQ":
        return BaseQuality;
      case "CIGAR":
        return Cigar;
      case "DB":
        return Dbsnp;
      case "DP":
        return Depth;
      case "H2":
        return Hapmap2;
      case "H3":
        return Hapmap3;
      case "MQ":
        return MappingQuality;
      case "MQ0":
        return MappingQualityZeroCount;
      case "NS":
        return NumberOfSamples;
      case "SB":
        return StrandBias;
      case "SOMATIC":
        return SomaticMutation;
      case "VALIDATED":
        return Validated;
      case "1000G":
        return ThousandGenomes;
      case "IMPRECISE":
        return Imprecise;
      case "NOVEL":
        return Novel;
      case "END":
        return End;
      case "SVTYPE":
        return StructuralVariantType;
      case "SVLEN":
        return StructuralVariantLength;
      case "CIPOS":
        return ConfidenceIntervalForPosition;
      case "CIEND":
        return ConfidenceIntervalForEnd;
      case "HOMLEN":
        return HomologyLength;
      case "HOMSEQ":
        return HomologySequence;
      case "BKPTID":
        return BreakpointId;
      case "MEINFO":
        return MobileElementInfo;
      case "METRANS":
        return MobileElementTransduction;
      case "DGVID":
        return DgvId;
      case "DBVARID":
        return DbvarId;
      case "DBRIPID":
        return DbripId;
      case "MATEID":
        return MateId;
      case "PARID":
        return PartnerId;
      case "EVENT":
        return EventId;
      case "CILEN":
        return ConfidenceIntervalForInsertedMaterial;
      case "DPADJ":
        return ReadDepthOfAdjacency;
      case "CN":
        return CopyNumberOfSegment;
      case "CNADJ":
        return CopyNumberOfAdjacency;
      case "CICN":
        return ConfidenceIntervalForSegmentCopyNumber;
      case "CICNADJ":
        return ConfidenceIntervalForAdjacencyCopyNumber;
      default:
        return null;
    }
  }

}

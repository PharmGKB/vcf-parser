package org.pharmgkb.parser.vcf;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;


/**
 * An INFO field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public enum ReservedInfoProperty implements ReservedProperty {

  // standard -- expectedNumber is sourced directly from VCFv4.3's Reserved INFO Keys table (table:reserved-info),
  // the target's own table; VCFv4.2 only describes these in prose with no formal Number/Type table of its own,
  // which predates the table rather than conflicting with it

  AncestralAllele("AA", "Ancestral allele", String.class, false, "1"),
  AlleleCount("AC", "Allele count in genotypes, for each ALT allele, in the same order as listed",
      Long.class, true, "A"),
  // Number=R (one value per allele, including REF, unlike Number=A's ALT-only count) -- VCFv4.3.tex's Reserved
  // INFO Keys table (table:reserved-info); VCFv4.2 does not table INFO AD/ADF/ADR at all
  AlleleDepth("AD", "Total read depth for each allele", Long.class, true, "R"),
  AlleleDepthForward("ADF", "Read depth for each allele on the forward strand", Long.class, true, "R"),
  AlleleDepthReverse("ADR", "Read depth for each allele on the reverse strand", Long.class, true, "R"),
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
  // VCFv4.3.tex (the target) itself, unchanged from VCFv4.2.tex; expectedNumber is taken directly from that
  // example

  Imprecise("IMPRECISE", "Imprecise structural variation", Boolean.class, false, "0"),
  Novel("NOVEL", "Indicates a novel structural variation", Boolean.class, false, "0"),
  End("END", "End position of the variant described in this record", Long.class, false, "1"), // also standard

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
  // VCFv4.3.tex (the target), unchanged from VCFv4.2.tex

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

  private static final Map<String, ReservedInfoProperty> BY_ID = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(ReservedInfoProperty::getId, Function.identity()));
  // The INFO-side mirror of ReservedFormatProperty.ADDED_AT_V4_3: these three have no VCFv4.2 declaration at
  // all -- VCFv4.3.tex's Reserved INFO keys table (table:reserved-info) added them, and VCFv4.2.tex's INFO
  // prose never mentions ADF/ADR and mentions AD only once, in an unrelated BCF-encoding worked example, not
  // as a reserved key -- so this library must not treat them as reserved under a version that predates their
  // introduction. Unlike ReservedFormatProperty, INFO has no removed-key case (GLE's mirror image), so this
  // is the only special case fromId(String, VcfVersion) below needs. Used only by that method.
  private static final Set<ReservedInfoProperty> ADDED_AT_V4_3 = Set.of(AlleleDepth, AlleleDepthForward,
      AlleleDepthReverse);

  /**
   * @return {@code true} if {@code property} is one of the three reserved INFO keys VCFv4.3 added (see
   * {@link #ADDED_AT_V4_3}'s own comment) -- used by {@link #fromId(String, VcfVersion)} for the
   * string-keyed classification route, and directly by {@code InternalUtils.convertProperty} for the
   * enum-keyed route, where a caller already holds the constant and {@code fromId} is never consulted --
   * the same reason {@code ReservedFormatProperty.isAddedAtV4_3} exists for its own enum-keyed callers.
   */
  static boolean isAddedAtV4_3(@Nullable ReservedInfoProperty property) {
    return property != null && ADDED_AT_V4_3.contains(property);
  }

  /**
   * The version-unaware form: classifies {@code id} as a reserved INFO key if it is reserved under
   * <em>any</em> version this library targets. Use this when no {@link VcfVersion} is in scope, or when the
   * caller intentionally wants the version-independent classification. Prefer
   * {@link #fromId(String, VcfVersion)} whenever a version is available -- it additionally returns
   * {@code null} for {@link #AlleleDepth AD}/{@link #AlleleDepthForward ADF}/{@link #AlleleDepthReverse ADR}
   * under a version older than VCFv4.3, since those three have no earlier declaration (see
   * {@link #ADDED_AT_V4_3}'s own comment).
   */
  @Nullable
  public static ReservedInfoProperty fromId(@Nullable String id) {
    return id == null ? null : BY_ID.get(id);
  }

  /**
   * The version-aware form of {@link #fromId(String)}: {@code id} is classified as a reserved INFO key only
   * if it is reserved <em>under {@code version}</em>.
   * <p>
   * Deliberately <b>not</b> a general version-aware reserved-key table -- see
   * {@code ReservedFormatProperty.fromId(String, VcfVersion)}'s identical caveat, which applies here for the
   * same reason. This method only answers the narrower existence question ("is {@code id} reserved at all
   * under {@code version}"), via the single hardcoded {@link #ADDED_AT_V4_3} set -- INFO has no removed-key
   * case (GLE's mirror image on the FORMAT side).
   *
   * @return the reserved INFO property {@code id} names under {@code version}, or {@code null} if {@code id}
   * names none (including a key not yet reserved under an older version but reserved under {@code version})
   */
  @Nullable
  static ReservedInfoProperty fromId(@Nullable String id, VcfVersion version) {
    ReservedInfoProperty property = fromId(id);
    if (isAddedAtV4_3(property) && !version.isAtLeast(VcfVersion.V4_3)) {
      return null;
    }
    return property;
  }

}

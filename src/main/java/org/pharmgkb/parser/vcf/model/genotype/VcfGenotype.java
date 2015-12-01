package org.pharmgkb.parser.vcf.model.genotype;

import org.pharmgkb.parser.vcf.VcfUtils;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A diploid (or haploid; see below) genotype matching the VCF 4.2 specification.
 * For example:
 * <ul>
 *     A/A (an unphased homozygous genotype)
 *     A|ATG (an phased heterozygous 2bp insertion of TG)
 * </ul>
 * This class can also handle haploid genotypes (e.g. A), but note that:
 * <code>
 *     Genotype.fromString("A").toString(); // prints A|A
 * </code>
 * Always use {@link #equals(Object)} to compare genotypes, not {@code toString().equals}.
 * @author Douglas Myers-Turnbull
 */
@Immutable
public class VcfGenotype {

  private static final String sf_noData = ".";
  private static final String sf_phasedDelimiter = "|";
  private static final String sf_unphasedDelimiter = "/";
  private static final Pattern sf_genotypePattern = Pattern.compile('(' + VcfUtils.ALT_BASE_PATTERN.pattern() + ")" +
      "[\\|/](" + VcfUtils.ALT_BASE_PATTERN.pattern() + ')');
  private static final Pattern sf_digitPattern = Pattern.compile("(\\d+|\\.)");
  private static final Pattern sf_numberPattern = Pattern.compile(sf_digitPattern + "[\\|/]" + sf_digitPattern);

  private final VcfAllele m_allele1;

  private final VcfAllele m_allele2;

  private final boolean m_isPhased;

  /**
   * @param genotype A string like A/TT
   */
  @Nonnull
  public static VcfGenotype fromString(@Nonnull String genotype) {
    Matcher matcher = sf_genotypePattern.matcher(genotype);
    if (matcher.matches() && !matcher.group(1).isEmpty() && !matcher.group(2).isEmpty()) {
      @Nonnull String allele1 = matcher.group(1);
      @Nonnull String allele2 = matcher.group(2);
      boolean isPhased = allele1.equals(allele2) || genotype.contains(sf_phasedDelimiter); // A/A -> A|A
      return new VcfGenotype(new VcfAllele(allele1), new VcfAllele(allele2), isPhased);
    } else if (VcfUtils.ALT_BASE_PATTERN.matcher(genotype).matches()) { // for haploid calls in chrM, chrX, and chrY
      // the choice of isPhased=true is weird here but really means "phasing is resolved"
      return new VcfGenotype(new VcfAllele(genotype), new VcfAllele(genotype), true);
    }
    throw new IllegalArgumentException("Genotype " + genotype + " is invalid");
  }

  /**
   * @return The genotype, or null if GT is null
   */
  @Nullable
  public static VcfGenotype fromVcf(@Nonnull VcfPosition position, @Nonnull VcfSample sample) {

    String genotype = sample.getProperty(ReservedFormatProperty.Genotype.getId());
    if (genotype == null) {
      return null;
    }
    return fromNumberString(position, genotype);

  }

  /**
   * @param genotype A string like 0/1
   * @return The genotype, or null if GT is null
   */
  @Nullable
  public static VcfGenotype fromNumberString(@Nonnull VcfPosition position, @Nonnull String genotype) {

    Matcher matcher = sf_numberPattern.matcher(genotype);

    if (matcher.matches() && !matcher.group(1).isEmpty() && !matcher.group(2).isEmpty()) {

      @Nullable String allele1 = getAlleleFromIndex(position, matcher.group(1)); // null if dot
      @Nullable String allele2 = getAlleleFromIndex(position, matcher.group(2)); // null if dot

      @Nullable VcfAllele vcfAllele1 = allele1==null? null : new VcfAllele(allele1);
      @Nullable VcfAllele vcfAllele2 = allele2==null? null : new VcfAllele(allele2);
      // A/A -> A|A:
      boolean isPhased = allele1 != null && allele1.equals(allele2) || genotype.contains(sf_phasedDelimiter);

      return new VcfGenotype(vcfAllele1, vcfAllele2, isPhased);

    } else if (sf_digitPattern.matcher(genotype).matches()) {
      @Nullable String allele = getAlleleFromIndex(position, genotype); // null if dot
      @Nullable VcfAllele vcfAllele = allele==null? null : new VcfAllele(allele);
      return new VcfGenotype(vcfAllele, vcfAllele, true);
    }

    throw new IllegalArgumentException("Genotype " + genotype + " is invalid");
  }

  /**
   * Note:
   * <code>
   *   VcfGenotype homozygous = new VcfGenotype("A", "A", false);
   *   homogyzgous.isPhased(); // false
   * </code>
   * The same logic applies with haploid genotypes.
   */
  public VcfGenotype(@Nullable VcfAllele allele1, @Nullable VcfAllele allele2, boolean isPhased) {
    m_allele1 = allele1;
    m_allele2 = allele2;
    m_isPhased = isPhased;
  }

  @Nonnull
  public String makeGt(@Nonnull VcfPosition position) {
    return (m_allele1==null? sf_noData : getAlleleIndex(position, m_allele1.toString()))
        + (m_isPhased? sf_phasedDelimiter : sf_unphasedDelimiter)
        + (m_allele2==null? sf_noData : getAlleleIndex(position, m_allele2.toString()));
  }

  private String getAlleleIndex(@Nonnull VcfPosition position, @Nonnull String allele) {
    if (position.getRef().equals(allele)) {
      return "0";
    }
    for (int i = 0; i < position.getAltBases().size(); i++) {
      String alt = position.getAltBases().get(i);
      if (alt.equals(allele)) {
        return String.valueOf(i + 1);
      }
    }
    throw new IllegalArgumentException("Allele " + allele + " does not exist");
  }

  /**
   * @return True iff the the genotype is <em>effectively phased.</em>:
   *    returns true for haploid genotypes and homozygous genotypes
   */
  public boolean isPhased() {
    return m_isPhased;
  }

  public boolean isHomozygous() {
    if (m_allele1 != null && m_allele2 != null) {
      return m_allele1.equals(m_allele2);
    }
    return m_allele1 == m_allele2; // both null
  }

  public boolean isNoCall() {
    return m_allele1 == null && m_allele2 == null;
  }

  @Nullable
  public VcfAllele getAllele1() {
    return m_allele1;
  }

  @Nullable
  public VcfAllele getAllele2() {
    return m_allele2;
  }

  @Nonnull
  public Set<VcfAllele> getAlleleSet() {
    Set<VcfAllele> set = new HashSet<>();
    if (m_allele1 != null) {
      set.add(m_allele1);
    }
    if (m_allele2 != null) {
      set.add(m_allele2);
    }
    return set;
  }

  /**
   * @return For example, A|ATGC or A/C
   */
  @Override
  public String toString() {
    return (m_allele1==null? sf_noData : m_allele1)
        + (m_isPhased? sf_phasedDelimiter: sf_unphasedDelimiter)
        + (m_allele2==null? sf_noData : m_allele2);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    VcfGenotype genotype = (VcfGenotype) o;
    return m_isPhased == genotype.m_isPhased
        && (m_allele1 != null && m_allele1.equals(genotype.m_allele1) || m_allele1 == genotype.m_allele1)
        && (m_allele2 != null && m_allele2.equals(genotype.m_allele2) || m_allele2 == genotype.m_allele2);
  }

  @Override
  public int hashCode() {
    return Objects.hash(m_allele1, m_allele2, m_isPhased);
  }

  @Nullable
  private static String getAlleleFromIndex(@Nonnull VcfPosition position, @Nonnull String indexString) {
    if (indexString.equals(sf_noData)) {
      return null;
    }
    int index;
    try {
      index = Integer.parseInt(indexString);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Allele index " + indexString + " is not a number");
    }
    if (index < 0 || index > position.getAltBases().size()) {
      throw new IllegalArgumentException("Allele index " + indexString + " is out of range: It should be between 0 " +
          "and " + position.getAltBases().size() + ", inclusive");
    }
    if (index == 0) {
      return position.getRef();
    } else {
      return position.getAltBases().get(index - 1);
    }
  }
}

package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.errorprone.annotations.Immutable;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;


/**
 * See {@link MemoryMappedVcfLineParser}.
 * <p>
 * Not thread-safe: internal state is held in plain, unsynchronized collections. Confine an instance to a
 * single thread, or provide external synchronization for concurrent use.
 *
 * @author Douglas Myers-Turnbull
 */
public class MemoryMappedVcfDataStore {

  private @Nullable VcfMetadata m_metadata;
  private final Map<String, VcfPosition> m_idToPosition = new HashMap<>();
  private final Map<Locus, List<VcfPosition>> m_locusToPositions = new HashMap<>();
  private final Map<String, List<VcfSample>> m_idToSamples = new HashMap<>();
  private final Map<Locus, List<List<VcfSample>>> m_locusToSamplesList = new HashMap<>();

  /**
   * @return Every position read, or null if parsing has not happened yet. Returns an empty (non-null) collection
   * if parsing completed but the file had no data lines.
   */
  public @Nullable
  Collection<VcfPosition> getAllPositions() {
    if (m_metadata == null) {
      return null;
    }
    List<VcfPosition> all = new ArrayList<>();
    for (List<VcfPosition> positions : m_locusToPositions.values()) {
      all.addAll(positions);
    }
    return all;
  }

  /**
   * @return The samples for every VCF record read, or null if parsing has not happened yet. Returns an empty
   * (non-null) collection if parsing completed but the file had no data lines.
   */
  public @Nullable Collection<List<VcfSample>> getAllSamples() {
    if (m_metadata == null) {
      return null;
    }
    List<List<VcfSample>> all = new ArrayList<>();
    for (List<List<VcfSample>> samplesList : m_locusToSamplesList.values()) {
      all.addAll(samplesList);
    }
    return all;
  }

  /**
   * @return The metadata, or null if parsing has not happened yet.
   */
  public @Nullable VcfMetadata getMetadata() {
    return m_metadata;
  }

  protected void setMetadata(VcfMetadata metadata) {
    m_metadata = metadata;
  }

  public @Nullable VcfPosition getPositionForId(String id) {
    return m_idToPosition.get(id);
  }

  public @Nullable List<VcfSample> getSamplesForId(String id) {
    return m_idToSamples.get(id);
  }

  /**
   * @return Every record at this locus, in the order they were read, or null if this locus was never read.
   */
  public @Nullable List<VcfPosition> getPositionsAtLocus(String chromosome, long position) {
    return m_locusToPositions.get(new Locus(chromosome, position));
  }

  /**
   * @return The samples for every record at this locus, index-aligned with {@link #getPositionsAtLocus}, or null if
   * this locus was never read.
   */
  public @Nullable List<List<VcfSample>> getSamplesAtLocus(String chromosome, long position) {
    return m_locusToSamplesList.get(new Locus(chromosome, position));
  }

  public @Nullable VcfSample getSampleForId(String positionId, String sampleId) {
    List<VcfSample> samples = m_idToSamples.get(positionId);
    if (samples == null) {
      return null;
    }
    int idx = m_metadata.getSampleIndex(sampleId);
    return idx < 0 ? null : samples.get(idx);
  }

  public @Nullable VcfSample getSampleForId(String positionId, int sampleIndex) {
    List<VcfSample> samples = m_idToSamples.get(positionId);
    if (samples == null || sampleIndex < 0 || sampleIndex >= samples.size()) {
      return null;
    }
    return samples.get(sampleIndex);
  }

  public @Nullable VcfSample getSampleAtLocus(String chromosome, long position, int recordIndex, String sampleId) {
    List<List<VcfSample>> samplesList = m_locusToSamplesList.get(new Locus(chromosome, position));
    if (samplesList == null || recordIndex < 0 || recordIndex >= samplesList.size()) {
      return null;
    }
    int idx = m_metadata.getSampleIndex(sampleId);
    return idx < 0 ? null : samplesList.get(recordIndex).get(idx);
  }

  public @Nullable VcfSample getSampleAtLocus(String chromosome, long position, int recordIndex, int sampleIndex) {
    List<List<VcfSample>> samplesList = m_locusToSamplesList.get(new Locus(chromosome, position));
    if (samplesList == null || recordIndex < 0 || recordIndex >= samplesList.size()) {
      return null;
    }
    List<VcfSample> samples = samplesList.get(recordIndex);
    return sampleIndex < 0 || sampleIndex >= samples.size() ? null : samples.get(sampleIndex);
  }

  public @Nullable Genotype getGenotypeForId(String positionId, String sampleId) {
    VcfPosition position = m_idToPosition.get(positionId);
    List<VcfSample> samples = m_idToSamples.get(positionId);
    if (position == null || samples == null) {
      return null;
    }
    int idx = m_metadata.getSampleIndex(sampleId);
    if (idx < 0) {
      return null;
    }
    return doGetGenotype(position, samples.get(idx));
  }

  public @Nullable Genotype getGenotypeAtLocus(String chromosome, long position, int recordIndex, String sampleId) {
    Locus locus = new Locus(chromosome, position);
    List<VcfPosition> positions = m_locusToPositions.get(locus);
    List<List<VcfSample>> samplesList = m_locusToSamplesList.get(locus);
    // bounds-checking recordIndex against samplesList.size() alone also guards positions.get(recordIndex) below:
    // MemoryMappedVcfLineParser.parseLine() populates both maps in lockstep (one add() call each, same locus,
    // every record), so positions.size() == samplesList.size() always holds for a given locus.
    if (positions == null || samplesList == null || recordIndex < 0 || recordIndex >= samplesList.size()) {
      return null;
    }
    int idx = m_metadata.getSampleIndex(sampleId);
    if (idx < 0) {
      return null;
    }
    return doGetGenotype(positions.get(recordIndex), samplesList.get(recordIndex).get(idx));
  }

  public @Nullable Genotype getGenotypeForId(String positionId, int sampleIndex) {
    VcfPosition position = m_idToPosition.get(positionId);
    List<VcfSample> samples = m_idToSamples.get(positionId);
    if (position == null || samples == null || sampleIndex < 0 || sampleIndex >= samples.size()) {
      return null;
    }
    return doGetGenotype(position, samples.get(sampleIndex));
  }

  public @Nullable Genotype getGenotypeAtLocus(String chromosome, long position, int recordIndex, int sampleIndex) {
    Locus locus = new Locus(chromosome, position);
    List<VcfPosition> positions = m_locusToPositions.get(locus);
    List<List<VcfSample>> samplesList = m_locusToSamplesList.get(locus);
    // bounds-checking recordIndex against samplesList.size() alone also guards positions.get(recordIndex) below --
    // see the comment in the sibling getGenotypeAtLocus(String, long, int, String) overload for why.
    if (positions == null || samplesList == null || recordIndex < 0 || recordIndex >= samplesList.size()) {
      return null;
    }
    List<VcfSample> samples = samplesList.get(recordIndex);
    if (sampleIndex < 0 || sampleIndex >= samples.size()) {
      return null;
    }
    return doGetGenotype(positions.get(recordIndex), samples.get(sampleIndex));
  }

  private @Nullable Genotype doGetGenotype(VcfPosition position, VcfSample sample) {
    String genotype = sample.getProperty(ReservedFormatProperty.Genotype);
    if (genotype == null || genotype.isEmpty()) {
      return null;
    }
    // Scanned manually (not String.split("[\\|/]", -1)) so each separator's identity ('|' vs '/') is captured per
    // allele boundary -- split() discards which delimiter matched, which previously collapsed mixed phasing (e.g.
    // "0|1/2") into one whole-genotype boolean and silently corrupted it on toString(). The manual scan still
    // preserves a trailing empty allele (e.g. a trailing '/', which should have been '.') the same way split's -1
    // limit did, rather than silently dropping it -- dropping it would silently change the call's ploidy. VCF does
    // not allow zero-length fields. See EMPTY_FIELD_HANDLING.md.
    int separatorCount = 0;
    for (int i = 0; i < genotype.length(); i++) {
      char c = genotype.charAt(i);
      if (c == '|' || c == '/') {
        separatorCount++;
      }
    }
    List<String> bases = new ArrayList<>(separatorCount + 1);
    List<Boolean> phasedBoundaries = new ArrayList<>(separatorCount);
    int start = 0;
    for (int i = 0; i < genotype.length(); i++) {
      char c = genotype.charAt(i);
      if (c == '|' || c == '/') {
        bases.add(genotype.substring(start, i));
        phasedBoundaries.add(c == '|');
        start = i + 1;
      }
    }
    bases.add(genotype.substring(start));

    List<String> alleles = new ArrayList<>(bases.size());
    boolean allMissing = true;
    for (String base : bases) {
      if (base.isEmpty()) {
        VcfWarnings.warn("GT \"{}\" contains an empty allele (VCF does not allow zero-length fields); treating it " +
            "as the missing value '.'", genotype);
      }
      if (base.isEmpty() || base.equals(".")) {
        // a missing allele (".") is kept as-is rather than parsed as an index
        alleles.add(".");
      } else {
        allMissing = false;
        alleles.add(position.getAllele(parseAlleleIndex(base, position)));
      }
    }
    // a fully missing call (e.g. "." or "./.") has no genotype
    return allMissing ? null : new Genotype(alleles, phasedBoundaries);
  }

  /**
   * Parses a GT allele index, converting a malformed or out-of-range value into {@link VcfFormatException} rather than
   * letting {@link NumberFormatException} or {@link IndexOutOfBoundsException} leak out of {@link #doGetGenotype}.
   */
  private static int parseAlleleIndex(String base, VcfPosition position) {
    int index;
    try {
      index = Integer.parseInt(base);
    } catch (NumberFormatException e) {
      throw new VcfFormatException("Allele index \"" + base + "\" is not a number");
    }
    if (index < 0 || index > position.getAltBases().size()) {
      throw new VcfFormatException("Allele index " + index + " is out of range: should be between 0 and " +
          position.getAltBases().size() + ", inclusive");
    }
    return index;
  }

  protected Map<String, VcfPosition> getIdToPosition() {
    return m_idToPosition;
  }

  protected Map<Locus, List<VcfPosition>> getLocusToPositions() {
    return m_locusToPositions;
  }

  protected Map<String, List<VcfSample>> getIdToSamples() {
    return m_idToSamples;
  }

  protected Map<Locus, List<List<VcfSample>>> getLocusToSamplesList() {
    return m_locusToSamplesList;
  }

  @Immutable
  public static class Genotype {
    private final List<String> m_alleles;
    private final List<Boolean> m_phasedBoundaries;
    private final boolean m_isPhased;

    public Genotype(List<String> alleles, boolean isPhased) {
      // requireNonNullAlleles() must run as part of this this(...) call's own argument evaluation (a constructor
      // delegating via this(...) cannot have a preceding statement) -- Java evaluates arguments left to right, so
      // this throws before alleles.size() below is ever reached if alleles is null
      this(requireNonNullAlleles(alleles), Collections.nCopies(Math.max(0, alleles.size() - 1), isPhased));
    }

    private static List<String> requireNonNullAlleles(List<String> alleles) {
      if (alleles == null) {
        throw new VcfFormatException("alleles is null");
      }
      return alleles;
    }

    /**
     * @param phasedBoundaries one entry per allele boundary (size {@code alleles.size() - 1}); {@code true} if
     * that boundary is phased ({@code |}), {@code false} if unphased ({@code /})
     * @throws IllegalArgumentException if {@code phasedBoundaries}'s size does not match {@code alleles}'s
     */
    public Genotype(List<String> alleles, List<Boolean> phasedBoundaries) {
      requireNonNullAlleles(alleles);
      if (phasedBoundaries == null) {
        throw new VcfFormatException("phasedBoundaries is null");
      }
      int expected = Math.max(0, alleles.size() - 1);
      if (phasedBoundaries.size() != expected) {
        throw new IllegalArgumentException("phasedBoundaries must have " + expected + " element(s) for " +
            alleles.size() + " allele(s), got " + phasedBoundaries.size());
      }
      m_alleles = List.copyOf(alleles);
      m_phasedBoundaries = List.copyOf(phasedBoundaries);
      m_isPhased = !m_phasedBoundaries.isEmpty() && m_phasedBoundaries.stream().allMatch(Boolean::booleanValue);
    }

    public List<String> getAlleles() {
      return m_alleles;
    }

    /**
     * @return one entry per allele boundary (size {@code getAlleles().size() - 1}); {@code true} if that boundary
     * is phased ({@code |}), {@code false} if unphased ({@code /})
     */
    public List<Boolean> getPhasedBoundaries() {
      return m_phasedBoundaries;
    }

    /**
     * @return {@code true} only if every allele boundary is phased; {@code false} if any boundary is unphased, or
     * there are no boundaries at all (a single-allele genotype)
     */
    public boolean isPhased() {
      return m_isPhased;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < m_alleles.size(); i++) {
        if (i > 0) {
          sb.append(m_phasedBoundaries.get(i - 1) ? '|' : '/');
        }
        sb.append(m_alleles.get(i));
      }
      return sb.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Genotype genotype = (Genotype) o;
      return Objects.equals(m_alleles, genotype.getAlleles()) &&
          Objects.equals(m_phasedBoundaries, genotype.getPhasedBoundaries());
    }

    @Override
    public int hashCode() {
      return Objects.hash(m_alleles, m_phasedBoundaries);
    }
  }

  @Immutable
  protected static class Locus {
    private final String m_chromosome;
    private final long m_position;

    public Locus(String chromosome, long position) {
      m_chromosome = chromosome;
      m_position = position;
    }

    public String getChromosome() {
      return m_chromosome;
    }

    public long getPosition() {
      return m_position;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final Locus locus = (Locus)o;
      return m_position == locus.getPosition() &&
          Objects.equals(m_chromosome, locus.getChromosome());
    }

    @Override
    public int hashCode() {
      return Objects.hash(m_chromosome, m_position);
    }

    @Override
    public String toString() {
      return m_chromosome + ":" + m_position;
    }
  }
}

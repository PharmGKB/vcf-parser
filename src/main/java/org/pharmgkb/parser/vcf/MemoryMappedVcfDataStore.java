package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.common.base.Joiner;
import com.google.errorprone.annotations.Immutable;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;


/**
 * See {@link MemoryMappedVcfLineParser}.
 * @author Douglas Myers-Turnbull
 */
public class MemoryMappedVcfDataStore {

  private VcfMetadata m_metadata;
  private Map<String, VcfPosition> m_idToPosition = new HashMap<>();
  private Map<Locus, VcfPosition> m_locusToPosition = new HashMap<>();
  private Map<String, List<VcfSample>> m_idToSamples = new HashMap<>();
  private Map<Locus, List<VcfSample>> m_locusToSamples = new HashMap<>();

  /**
   * @return Every position read, or null if none no lines read.
   */
  public @Nullable
  Collection<VcfPosition> getAllPositions() {
    if (m_metadata == null) {
      return null;
    }
    return m_locusToPosition.values();
  }

  /**
   * @return The samples for every VCF record read, or null if no lines were read.
   */
  public @Nullable Collection<List<VcfSample>> getAllSamples() {
    if (m_metadata == null) {
      return null;
    }
    return m_locusToSamples.values();
  }

  /**
   * @return The metadata, or null if no lines were read.
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

  public @Nullable VcfPosition getPositionAtLocus(String chromosome, long position) {
    return m_locusToPosition.get(new Locus(chromosome, position));
  }

  public @Nullable List<VcfSample> getSamplesAtLocus(String chromosome, long position) {
    return m_locusToSamples.get(new Locus(chromosome, position));
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
    return samples == null ? null : samples.get(sampleIndex);
  }

  public @Nullable VcfSample getSampleAtLocus(String chromosome, long position, String sampleId) {
    List<VcfSample> samples = m_locusToSamples.get(new Locus(chromosome, position));
    if (samples == null) {
      return null;
    }
    int idx = m_metadata.getSampleIndex(sampleId);
    return idx < 0 ? null : samples.get(idx);
  }

  public @Nullable VcfSample getSampleAtLocus(String chromosome, long position, int sampleIndex) {
    List<VcfSample> samples = m_locusToSamples.get(new Locus(chromosome, position));
    return samples == null ? null : samples.get(sampleIndex);
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

  public @Nullable Genotype getGenotypeAtLocus(String chromosome, long position, String sampleId) {
    Locus locus = new Locus(chromosome, position);
    VcfPosition pos = m_locusToPosition.get(locus);
    List<VcfSample> samples = m_locusToSamples.get(locus);
    if (pos == null || samples == null) {
      return null;
    }
    int idx = m_metadata.getSampleIndex(sampleId);
    if (idx < 0) {
      return null;
    }
    return doGetGenotype(pos, samples.get(idx));
  }

  public @Nullable Genotype getGenotypeForId(String positionId, int sampleIndex) {
    VcfPosition position = m_idToPosition.get(positionId);
    List<VcfSample> samples = m_idToSamples.get(positionId);
    if (position == null || samples == null) {
      return null;
    }
    return doGetGenotype(position, samples.get(sampleIndex));
  }

  public @Nullable Genotype getGenotypeAtLocus(String chromosome, long position, int sampleIndex) {
    Locus locus = new Locus(chromosome, position);
    VcfPosition pos = m_locusToPosition.get(locus);
    List<VcfSample> samples = m_locusToSamples.get(locus);
    if (pos == null || samples == null) {
      return null;
    }
    return doGetGenotype(pos, samples.get(sampleIndex));
  }

  private @Nullable Genotype doGetGenotype(VcfPosition position, VcfSample sample) {
    String genotype = sample.getProperty(ReservedFormatProperty.Genotype);
    if (genotype == null || genotype.isEmpty()) {
      return null;
    }
    boolean isPhased = genotype.contains("|");
    String[] bases = genotype.split("[\\|/]");
    List<String> alleles = new ArrayList<>(bases.length);
    boolean allMissing = true;
    for (String base : bases) {
      if (base.equals(".")) {
        // a missing allele (".") is kept as-is rather than parsed as an index
        alleles.add(".");
      } else {
        allMissing = false;
        alleles.add(position.getAllele(parseAlleleIndex(base, position)));
      }
    }
    // a fully missing call (e.g. "." or "./.") has no genotype
    return allMissing ? null : new Genotype(alleles, isPhased);
  }

  /**
   * Parses a GT allele index, converting a malformed or out-of-range value into {@link VcfFormatException} rather than
   * letting {@link NumberFormatException} or {@link IndexOutOfBoundsException} leak out of {@link #doGetGenotype},
   * consistent with {@code VcfGenotype.getAlleleFromIndex}.
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

  protected void setIdToPosition(Map<String, VcfPosition> idToPosition) {
    m_idToPosition = idToPosition;
  }

  protected Map<Locus, VcfPosition> getLocusToPosition() {
    return m_locusToPosition;
  }

  protected void setLocusToPosition(Map<Locus, VcfPosition> locusToPosition) {
    m_locusToPosition = locusToPosition;
  }

  protected Map<String, List<VcfSample>> getIdToSamples() {
    return m_idToSamples;
  }

  protected void setIdToSamples(Map<String, List<VcfSample>> idToSamples) {
    m_idToSamples = idToSamples;
  }

  protected Map<Locus, List<VcfSample>> getLocusToSamples() {
    return m_locusToSamples;
  }

  protected void setLocusToSamples(Map<Locus, List<VcfSample>> locusToSamples) {
    m_locusToSamples = locusToSamples;
  }

  @Immutable
  public static class Genotype {
    private final List<String> m_alleles;
    private final boolean m_isPhased;

    public Genotype(List<String> alleles, boolean isPhased) {
      m_alleles = List.copyOf(alleles);
      m_isPhased = isPhased;
    }

    public List<String> getAlleles() {
      return m_alleles;
    }

    public boolean isPhased() {
      return m_isPhased;
    }

    @Override
    public String toString() {
      return Joiner.on(m_isPhased ? "|" : "/").join(m_alleles);
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
      return Objects.equals(m_isPhased, genotype.isPhased()) &&
          Objects.equals(m_alleles, genotype.getAlleles());
    }

    @Override
    public int hashCode() {
      return Objects.hash(m_alleles, m_isPhased);
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
      return Objects.equals(m_position, locus.getPosition()) &&
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

package org.pharmgkb.parser.vcf;

import com.google.common.base.Joiner;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.util.*;

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

  public @Nullable VcfPosition getPositionForId(@Nonnull String id) {
    return m_idToPosition.get(id);
  }

  public @Nullable List<VcfSample> getSamplesForId(@Nonnull String id) {
    return m_idToSamples.get(id);
  }

  public @Nullable VcfPosition getPositionAtLocus(@Nonnull String chromosome, long position) {
    return m_locusToPosition.get(new Locus(chromosome, position));
  }

  public @Nullable List<VcfSample> getSamplesAtLocus(@Nonnull String chromosome, long position) {
    return m_locusToSamples.get(new Locus(chromosome, position));
  }

  public @Nullable VcfSample getSampleForId(@Nonnull String positionId, @Nonnull String sampleId) {
    return m_idToSamples.get(positionId).get(m_metadata.getSampleIndex(sampleId));
  }

  public @Nullable VcfSample getSampleForId(@Nonnull String positionId, int sampleIndex) {
    return m_idToSamples.get(positionId).get(sampleIndex);
  }

  public @Nullable VcfSample getSampleAtLocus(@Nonnull String chromosome, long position, @Nonnull String sampleId) {
    return m_locusToSamples.get(new Locus(chromosome, position)).get(m_metadata.getSampleIndex(sampleId));
  }

  public @Nullable VcfSample getSampleAtLocus(@Nonnull String chromosome, long position, int sampleIndex) {
    return m_locusToSamples.get(new Locus(chromosome, position)).get(sampleIndex);
  }

  public @Nullable Genotype getGenotypeForId(@Nonnull String positionId, String sampleId) {
    VcfPosition position = m_idToPosition.get(positionId);
    VcfSample sample = m_idToSamples.get(positionId).get(m_metadata.getSampleIndex(sampleId));
    return doGetGenotype(position, sample);
  }

  public @Nullable Genotype getGenotypeAtLocus(@Nonnull String chromosome, long position, String sampleId) {
    VcfPosition position1 = m_locusToPosition.get(new Locus(chromosome, position));
    VcfSample sample = m_locusToSamples.get(new Locus(chromosome, position)).get(m_metadata.getSampleIndex(sampleId));
    return doGetGenotype(position1, sample);
  }

  public @Nullable Genotype getGenotypeForId(@Nonnull String positionId, int sampleIndex) {
    VcfPosition position = m_idToPosition.get(positionId);
    VcfSample sample = m_idToSamples.get(positionId).get(sampleIndex);
    return doGetGenotype(position, sample);
  }

  public @Nullable Genotype getGenotypeAtLocus(@Nonnull String chromosome, long position, int sampleIndex) {
    VcfPosition position1 = m_locusToPosition.get(new Locus(chromosome, position));
    VcfSample sample = m_locusToSamples.get(new Locus(chromosome, position)).get(sampleIndex);
    return doGetGenotype(position1, sample);
  }

  private @Nullable Genotype doGetGenotype(VcfPosition position, VcfSample sample) {
    String genotype = sample.getProperty(ReservedFormatProperty.Genotype);
    if (genotype == null || genotype.isEmpty() || genotype.equals(".")) {
      return null;
    }
    boolean isPhased = genotype.contains("|");
    String[] bases = genotype.split("[\\|/]");
    List<String> alleles = new ArrayList<>(bases.length);
    for (String base : bases) {
      alleles.add(position.getAllele(Integer.parseInt(base)));
    }
    return new Genotype(alleles, isPhased);
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
      m_alleles = alleles;
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
    public boolean equals(Object o) {
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
    public boolean equals(Object o) {
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

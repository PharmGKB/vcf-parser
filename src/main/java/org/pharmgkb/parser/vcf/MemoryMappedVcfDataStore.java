package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.errorprone.annotations.Immutable;
import org.jspecify.annotations.Nullable;
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
    if (metadata == null) {
      // rejected here, not left to surface later, because a null m_metadata silently corrupts every
      // sample-index lookup (getSampleForId, and the String-sampleId overloads of getSampleAtLocus/
      // getGenotypeForId/getGenotypeAtLocus) with an NPE at that unrelated call site instead of at the actual
      // mistake. This method is called from both parseLine() and parseMetadata(), so the message can't name
      // either specific caller -- see VcfSample.Genotype's own "alleles is null"/"phasedBoundaries is null" for
      // the same caller-agnostic phrasing convention.
      throw new VcfFormatException("metadata is null");
    }
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
    return idx < 0 || idx >= samples.size() ? null : samples.get(idx);
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
    List<VcfSample> samples = samplesList.get(recordIndex);
    int idx = m_metadata.getSampleIndex(sampleId);
    return idx < 0 || idx >= samples.size() ? null : samples.get(idx);
  }

  public @Nullable VcfSample getSampleAtLocus(String chromosome, long position, int recordIndex, int sampleIndex) {
    List<List<VcfSample>> samplesList = m_locusToSamplesList.get(new Locus(chromosome, position));
    if (samplesList == null || recordIndex < 0 || recordIndex >= samplesList.size()) {
      return null;
    }
    List<VcfSample> samples = samplesList.get(recordIndex);
    return sampleIndex < 0 || sampleIndex >= samples.size() ? null : samples.get(sampleIndex);
  }

  public VcfSample.@Nullable Genotype getGenotypeForId(String positionId, String sampleId) {
    VcfPosition position = m_idToPosition.get(positionId);
    List<VcfSample> samples = m_idToSamples.get(positionId);
    if (position == null || samples == null) {
      return null;
    }
    int idx = m_metadata.getSampleIndex(sampleId);
    if (idx < 0 || idx >= samples.size()) {
      return null;
    }
    return samples.get(idx).getGenotype(position);
  }

  public VcfSample.@Nullable Genotype getGenotypeAtLocus(String chromosome, long position, int recordIndex, String sampleId) {
    Locus locus = new Locus(chromosome, position);
    List<VcfPosition> positions = m_locusToPositions.get(locus);
    List<List<VcfSample>> samplesList = m_locusToSamplesList.get(locus);
    // bounds-checking recordIndex against samplesList.size() alone also guards positions.get(recordIndex) below:
    // MemoryMappedVcfLineParser.parseLine() populates both maps in lockstep (one add() call each, same locus,
    // every record), so positions.size() == samplesList.size() always holds for a given locus.
    if (positions == null || samplesList == null || recordIndex < 0 || recordIndex >= samplesList.size()) {
      return null;
    }
    List<VcfSample> samples = samplesList.get(recordIndex);
    int idx = m_metadata.getSampleIndex(sampleId);
    if (idx < 0 || idx >= samples.size()) {
      return null;
    }
    return samples.get(idx).getGenotype(positions.get(recordIndex));
  }

  public VcfSample.@Nullable Genotype getGenotypeForId(String positionId, int sampleIndex) {
    VcfPosition position = m_idToPosition.get(positionId);
    List<VcfSample> samples = m_idToSamples.get(positionId);
    if (position == null || samples == null || sampleIndex < 0 || sampleIndex >= samples.size()) {
      return null;
    }
    return samples.get(sampleIndex).getGenotype(position);
  }

  public VcfSample.@Nullable Genotype getGenotypeAtLocus(String chromosome, long position, int recordIndex, int sampleIndex) {
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
    return samples.get(sampleIndex).getGenotype(positions.get(recordIndex));
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

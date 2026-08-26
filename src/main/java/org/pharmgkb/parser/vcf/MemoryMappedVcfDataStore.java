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
  // The metadata that was active when each record was stored, index-aligned (id-keyed) or list-index-aligned
  // (locus-keyed) with m_idToSamples/m_locusToSamplesList above. MemoryMappedVcfLineParser.parseLine() can be
  // called repeatedly with different metadata (e.g. reusing one store across files with a different sample
  // order), which replaces m_metadata below; without a per-record copy, every by-name sample/genotype lookup
  // would resolve a sample name against whichever metadata is current rather than the metadata that was in
  // effect when that particular record was stored, silently returning the wrong sample.
  private final Map<String, VcfMetadata> m_idToMetadata = new HashMap<>();
  private final Map<Locus, List<VcfMetadata>> m_locusToMetadataList = new HashMap<>();
  // Dedicated file-order lists for getAllPositions()/getAllSamples() below, appended to directly by
  // MemoryMappedVcfLineParser.parseLine() alongside the locus maps above. A LinkedHashMap keyed on locus was
  // tried for this previously, but that only orders *loci*, not individual records: flattening
  // m_locusToPositions.values() groups every record at a locus together, so a locus revisited later in the file
  // (e.g. chr1:100, chr1:200, chr1:100) gets hoisted back next to its earlier occurrence instead of staying in
  // its actual read position. These lists have no such grouping -- one append per record, in read order -- so
  // they are the only correct backing for the "file order" guarantee those two methods document.
  private final List<VcfPosition> m_positionsInFileOrder = new ArrayList<>();
  private final List<List<VcfSample>> m_samplesListInFileOrder = new ArrayList<>();

  /**
   * @return Every position read, in the order it appeared in the file, or null if parsing has not happened yet.
   * Returns an empty (non-null) collection if parsing completed but the file had no data lines.
   */
  public @Nullable
  Collection<VcfPosition> getAllPositions() {
    if (m_metadata == null) {
      return null;
    }
    return new ArrayList<>(m_positionsInFileOrder);
  }

  /**
   * @return The samples for every VCF record read, in the order it appeared in the file, or null if parsing has
   * not happened yet. Returns an empty (non-null) collection if parsing completed but the file had no data lines.
   * Each element is a record's own per-sample list, the same unmodifiable object {@link #getSamplesForId} returns
   * for that record -- see {@link #getSamplesAtLocus}'s Javadoc for why. The contained {@link VcfSample} objects
   * themselves remain mutable.
   */
  public @Nullable Collection<List<VcfSample>> getAllSamples() {
    if (m_metadata == null) {
      return null;
    }
    return new ArrayList<>(m_samplesListInFileOrder);
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

  /**
   * @return An unmodifiable view of this record's per-sample data in column order, or null if no record with
   * this ID was read. This is the same list {@link #getSamplesAtLocus} exposes for the same record -- see that
   * method's Javadoc for why it is unmodifiable.
   */
  public @Nullable List<VcfSample> getSamplesForId(String id) {
    return m_idToSamples.get(id);
  }

  /**
   * @return An unmodifiable view of every record at this locus, in the order they were read, or null if this
   * locus was never read. {@link #getGenotypeAtLocus}'s {@code recordIndex} indexes into this list and into
   * {@link #getSamplesAtLocus}'s in lockstep -- returning a live-mutable list here would let a caller desync
   * that alignment with an ordinary {@code List.remove()}/{@code clear()}, silently misattributing a sample's
   * genotype to the wrong position (or throwing an unrelated {@code IndexOutOfBoundsException}) with no
   * indication anything went wrong. This view still reflects this store's own later mutations (e.g. during
   * parsing), the same as before -- only caller-side mutation is now blocked, at the point of the caller's own
   * mistake, not somewhere far away.
   */
  public @Nullable List<VcfPosition> getPositionsAtLocus(String chromosome, long position) {
    List<VcfPosition> positions = m_locusToPositions.get(new Locus(chromosome, position));
    return positions == null ? null : Collections.unmodifiableList(positions);
  }

  /**
   * @return An unmodifiable view of the samples for every record at this locus, index-aligned with
   * {@link #getPositionsAtLocus}, or null if this locus was never read. See that method's own Javadoc for why
   * this outer list is unmodifiable rather than the live-mutable list returned before. Each element (one
   * record's per-sample list, the same object {@link #getSamplesForId} returns for that record) is
   * unmodifiable too: {@link MemoryMappedVcfLineParser#parseLine} stores {@link VcfRecord#getSamples}, which
   * is an unmodifiable view on every {@code VcfRecord} construction path. That closes the inner-list variant
   * of the same desync -- a caller can no longer shift a record's own sample indices out of alignment with
   * the {@code #CHROM} line's declared sample columns and silently misdirect
   * {@link #getSampleAtLocus}/{@link #getGenotypeAtLocus} to the wrong sample. The contained
   * {@link VcfSample} objects themselves remain mutable.
   */
  public @Nullable List<List<VcfSample>> getSamplesAtLocus(String chromosome, long position) {
    List<List<VcfSample>> samplesList = m_locusToSamplesList.get(new Locus(chromosome, position));
    return samplesList == null ? null : Collections.unmodifiableList(samplesList);
  }

  public @Nullable VcfSample getSampleForId(String positionId, String sampleId) {
    List<VcfSample> samples = m_idToSamples.get(positionId);
    VcfMetadata metadata = m_idToMetadata.get(positionId);
    if (samples == null || metadata == null) {
      return null;
    }
    return getOrNull(samples, metadata.getSampleIndex(sampleId));
  }

  public @Nullable VcfSample getSampleForId(String positionId, int sampleIndex) {
    return getOrNull(m_idToSamples.get(positionId), sampleIndex);
  }

  public @Nullable VcfSample getSampleAtLocus(String chromosome, long position, int recordIndex, String sampleId) {
    Locus locus = new Locus(chromosome, position);
    List<VcfSample> samples = getOrNull(m_locusToSamplesList.get(locus), recordIndex);
    VcfMetadata metadata = getOrNull(m_locusToMetadataList.get(locus), recordIndex);
    if (samples == null || metadata == null) {
      return null;
    }
    return getOrNull(samples, metadata.getSampleIndex(sampleId));
  }

  public @Nullable VcfSample getSampleAtLocus(String chromosome, long position, int recordIndex, int sampleIndex) {
    List<VcfSample> samples = getOrNull(m_locusToSamplesList.get(new Locus(chromosome, position)), recordIndex);
    return getOrNull(samples, sampleIndex);
  }

  public VcfSample.@Nullable Genotype getGenotypeForId(String positionId, String sampleId) {
    VcfPosition position = m_idToPosition.get(positionId);
    List<VcfSample> samples = m_idToSamples.get(positionId);
    VcfMetadata metadata = m_idToMetadata.get(positionId);
    if (position == null || samples == null || metadata == null) {
      return null;
    }
    VcfSample sample = getOrNull(samples, metadata.getSampleIndex(sampleId));
    return sample == null ? null : sample.getGenotype(position);
  }

  public VcfSample.@Nullable Genotype getGenotypeAtLocus(String chromosome, long position, int recordIndex, String sampleId) {
    Locus locus = new Locus(chromosome, position);
    List<VcfPosition> positions = m_locusToPositions.get(locus);
    // bounds-checking recordIndex against the samples list's presence/size alone (via getOrNull below) also
    // guards positions.get(recordIndex) below: MemoryMappedVcfLineParser.parseLine() populates both maps in
    // lockstep (one add() call each, same locus, every record), so positions.size() == samplesList.size()
    // == metadataList.size() always holds for a given locus.
    List<VcfSample> samples = getOrNull(m_locusToSamplesList.get(locus), recordIndex);
    VcfMetadata metadata = getOrNull(m_locusToMetadataList.get(locus), recordIndex);
    if (samples == null || metadata == null) {
      return null;
    }
    VcfSample sample = getOrNull(samples, metadata.getSampleIndex(sampleId));
    return sample == null ? null : sample.getGenotype(positions.get(recordIndex));
  }

  public VcfSample.@Nullable Genotype getGenotypeForId(String positionId, int sampleIndex) {
    VcfPosition position = m_idToPosition.get(positionId);
    VcfSample sample = getOrNull(m_idToSamples.get(positionId), sampleIndex);
    return position == null || sample == null ? null : sample.getGenotype(position);
  }

  public VcfSample.@Nullable Genotype getGenotypeAtLocus(String chromosome, long position, int recordIndex, int sampleIndex) {
    Locus locus = new Locus(chromosome, position);
    List<VcfPosition> positions = m_locusToPositions.get(locus);
    // see the comment in the sibling getGenotypeAtLocus(String, long, int, String) overload for why
    // bounds-checking recordIndex against the samples list's presence/size alone also guards
    // positions.get(recordIndex) below.
    List<VcfSample> samples = getOrNull(m_locusToSamplesList.get(locus), recordIndex);
    if (samples == null) {
      return null;
    }
    VcfSample sample = getOrNull(samples, sampleIndex);
    return sample == null ? null : sample.getGenotype(positions.get(recordIndex));
  }

  /**
   * @return {@code list.get(idx)}, or {@code null} if {@code list} is {@code null} or {@code idx} is out of
   * bounds. Shared by every by-ID/by-locus sample and genotype lookup above, all of which repeated this same
   * bounds-check-or-null pattern; this class is a non-performance-critical convenience store, so the extra
   * call carries no meaningful cost.
   */
  private static <T> @Nullable T getOrNull(@Nullable List<T> list, int idx) {
    return list == null || idx < 0 || idx >= list.size() ? null : list.get(idx);
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

  protected Map<String, VcfMetadata> getIdToMetadata() {
    return m_idToMetadata;
  }

  protected Map<Locus, List<VcfMetadata>> getLocusToMetadataList() {
    return m_locusToMetadataList;
  }

  protected List<VcfPosition> getPositionsInFileOrder() {
    return m_positionsInFileOrder;
  }

  protected List<List<VcfSample>> getSamplesListInFileOrder() {
    return m_samplesListInFileOrder;
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

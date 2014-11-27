package org.pharmgkb.parser.vcf;

import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A simple {@link org.pharmgkb.parser.vcf.VcfLineParser} that loads an entire VCF file into memory to permit constant-time lookup for VCF records, either:
 * <ul>
 *   <li>By any ID listed in the {@code ID} field, or</li>
 *   <li>By chromosome and position</li>
 * </ul>
 *
 * <em>This implementation is memory-intensive and should only be used for short VCF files where repeated arbitrary (random) access to VCF records is required.</em>
 *
 * By default, an {@link java.lang.IllegalArgumentException} is thrown each time a duplicate ID or locus is found.
 * To change this behavior, see {@link #setDuplicateLocusHandler} and {@link #setDuplicateLocusHandler}.
 *
 * @author Douglas Myers-Turnbull
 */
public class MemoryMappedVcfLineParser implements VcfLineParser {

  private VcfMetadata m_metadata;
  private Map<String,VcfPosition> m_idToPosition;
  private Map<Locus,VcfPosition> m_locusToPosition;
  private Map<String,List<VcfSample>> m_idToSamples;
  private Map<Locus,List<VcfSample>> m_locusToSamples;
  private DuplicateHandler m_duplicateIdHandler;
  private DuplicateHandler m_duplicateLocusHandler;

  public MemoryMappedVcfLineParser() {
    m_idToPosition = new HashMap<>();
    m_locusToPosition = new HashMap<>();
    m_idToSamples = new HashMap<>();
    m_locusToSamples = new HashMap<>();
    m_duplicateIdHandler = DuplicateHandler.FAIL;
    m_duplicateLocusHandler = DuplicateHandler.FAIL;
  }

  /**
   * @return Every position read, or null if none no lines read.
   */
  public @Nullable Collection<VcfPosition> getAllPositions() {
    if (m_metadata == null) return null;
    return m_locusToPosition.values();
  }

  /**
   * @return The samples for every VCF record read, or null if no lines were read.
   */
  public @Nullable Collection<List<VcfSample>> getAllSamples() {
    if (m_metadata == null) return null;
    return m_locusToSamples.values();
  }

  /**
   * @return The metadata, or null if no lines were read.
   */
  public @Nullable VcfMetadata getMetadata() {
    return m_metadata;
  }

  /**
   * Determines what to do when an ID that was previously set is encountered, regardless of whether the two IDs correspond to the same locus.
   * This is independent of {@link #setDuplicateLocusHandler}, except when either is set to {@link org.pharmgkb.parser.vcf.MemoryMappedVcfLineParser.DuplicateHandler#FAIL FAIL}.
   */
  public @Nonnull MemoryMappedVcfLineParser setDuplicateIdHandler(@Nonnull DuplicateHandler handler) {
    m_duplicateIdHandler = handler;
    return this;
  }

  /**
   * Determines what to do when a VCF record is encountered with a locus (chromosome and position) that was already found.
   * This is independent of {@link #setDuplicateIdHandler}, except when either is set to {@link org.pharmgkb.parser.vcf.MemoryMappedVcfLineParser.DuplicateHandler#FAIL FAIL}.
   */
  public @Nonnull MemoryMappedVcfLineParser setDuplicateLocusHandler(@Nonnull DuplicateHandler handler) {
    m_duplicateLocusHandler = handler;
    return this;
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

  @Override
  public void parseLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {

    m_metadata = metadata;

    // link by locus
    Locus locus = new Locus(position.getChromosome(), position.getPosition());
    boolean containsPosition = m_locusToPosition.containsKey(locus);
    if (containsPosition && m_duplicateLocusHandler == DuplicateHandler.FAIL) {
      throw new IllegalArgumentException("Duplicate VCF record for position " + locus);
    }
    if (!containsPosition || m_duplicateLocusHandler == DuplicateHandler.KEEP_LAST) {
      m_locusToPosition.put(locus, position);
      m_locusToSamples.put(locus, sampleData);
    }

    // link by ID
    for (String id : position.getIds()) {
      boolean containsId = m_idToPosition.containsKey(id);
      if (containsId && m_duplicateIdHandler == DuplicateHandler.FAIL) {
        throw new IllegalArgumentException("Duplicate VCF record for ID " + id);
      }
      if (!containsId || m_duplicateIdHandler == DuplicateHandler.KEEP_LAST) {
          m_idToPosition.put(id, position);
          m_idToSamples.put(id, sampleData);
      }
    }
  }

  /**
   * What to do when a duplicate VCF record (line) is encountered.
   * This includes cases where the contents of the record, such as the ALT, REF, INFO, and sample fields, differ.
   */
  public static enum DuplicateHandler {

    /**
     * Return an {@link java.lang.IllegalArgumentException} when a duplicate is encountered.
     */
    FAIL,

    /**
     * Always keep the record that was previously read. That is, don't process duplicate records.
     */
    KEEP_FIRST,

    /**
     * Replace the {@link org.pharmgkb.parser.vcf.model.VcfSample VcfSamples} and {@link org.pharmgkb.parser.vcf.model.VcfPosition}.
     * In other words, ignore the fact that the record is a duplicate and record it anyway.
     */
    KEEP_LAST;
  }

  private static class Locus {
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
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Locus locus = (Locus) o;
      return m_position == locus.m_position && m_chromosome.equals(locus.m_chromosome);
    }

    @Override
    public int hashCode() {
      int result = m_chromosome.hashCode();
      result = 31 * result + (int) (m_position ^ (m_position >>> 32));
      return result;
    }

    @Override
    public String toString() {
      return m_chromosome + ":" + m_position;
    }
  }

}

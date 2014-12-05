package org.pharmgkb.parser.vcf;

import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A simple {@link org.pharmgkb.parser.vcf.VcfLineParser} that loads an entire VCF file into memory to permit
 * constant-time lookup for VCF records, either:
 * <ul>
 * <li>By any ID listed in the {@code ID} field, or</li>
 * <li>By chromosome and position</li>
 * </ul>
 * <p>
 * <em>This implementation is memory-intensive and should only be used for short VCF files where repeated arbitrary
 * (random) access to VCF records is required.</em>
 * <p>
 * By default, an {@link java.lang.IllegalArgumentException} is thrown each time a duplicate ID or locus is found.
 * To change this behavior, see {@link Builder#setDuplicateLocusHandler} and {@link Builder#setDuplicateLocusHandler}.
 *
 * @author Douglas Myers-Turnbull
 */
public class MemoryMappedVcfLineParser implements VcfLineParser {
  private MemoryMappedVcfDataStore m_dataStore = new MemoryMappedVcfDataStore();
  private DuplicateHandler m_duplicateIdHandler;
  private DuplicateHandler m_duplicateLocusHandler;


  private MemoryMappedVcfLineParser(@Nonnull DuplicateHandler idHandler, @Nonnull DuplicateHandler locusHandler) {
    m_duplicateIdHandler = idHandler;
    m_duplicateLocusHandler = locusHandler;
  }


  public @Nonnull MemoryMappedVcfDataStore getDataStore() {
    return  m_dataStore;
  }


  @Override
  public void parseLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {

    m_dataStore.setMetadata(metadata);

    // link by locus
    MemoryMappedVcfDataStore.Locus locus = new MemoryMappedVcfDataStore.Locus(position.getChromosome(), position.getPosition());
    boolean containsPosition = m_dataStore.getLocusToPosition().containsKey(locus);
    if (containsPosition && m_duplicateLocusHandler == DuplicateHandler.FAIL) {
      throw new IllegalArgumentException("Duplicate VCF record for position " + locus);
    }
    if (!containsPosition || m_duplicateLocusHandler == DuplicateHandler.KEEP_LAST) {
      m_dataStore.getLocusToPosition().put(locus, position);
      m_dataStore.getLocusToSamples().put(locus, sampleData);
    }

    // link by ID
    for (String id : position.getIds()) {
      boolean containsId = m_dataStore.getIdToPosition().containsKey(id);
      if (containsId && m_duplicateIdHandler == DuplicateHandler.FAIL) {
        throw new IllegalArgumentException("Duplicate VCF record for ID " + id);
      }
      if (!containsId || m_duplicateIdHandler == DuplicateHandler.KEEP_LAST) {
        m_dataStore.getIdToPosition().put(id, position);
        m_dataStore.getIdToSamples().put(id, sampleData);
      }
    }
  }


  public static class Builder {
    private DuplicateHandler m_duplicateIdHandler = DuplicateHandler.FAIL;
    private DuplicateHandler m_duplicateLocusHandler = DuplicateHandler.FAIL;

    /**
     * Determines what to do when an ID that was previously set is encountered, regardless of whether the two IDs
     * correspond to the same locus.
     *
     * This is independent of {@link #setDuplicateLocusHandler(DuplicateHandler)}, except when either is set to
     * {@link DuplicateHandler#FAIL}.
     */
    public @Nonnull Builder setDuplicateIdHandler(@Nonnull DuplicateHandler handler) {
      m_duplicateIdHandler = handler;
      return this;
    }

    /**
     * Determines what to do when a VCF record is encountered with a locus (chromosome and position) that was already
     * found.
     *
     * This is independent of {@link #setDuplicateIdHandler(DuplicateHandler)}, except when either is set to
     * {@link DuplicateHandler#FAIL}.
     */
    public @Nonnull Builder setDuplicateLocusHandler(@Nonnull DuplicateHandler handler) {
      m_duplicateLocusHandler = handler;
      return this;
    }

    public MemoryMappedVcfLineParser build() {
      return new MemoryMappedVcfLineParser(m_duplicateIdHandler, m_duplicateLocusHandler);
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
    KEEP_LAST
  }

}

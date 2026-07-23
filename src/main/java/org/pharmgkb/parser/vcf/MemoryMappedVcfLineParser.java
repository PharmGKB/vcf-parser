package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.List;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;


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
 * VCF permits more than one record at the same locus (e.g. multi-allelic sites split across lines), so every locus
 * is stored as a list of records rather than a single one; by default, a {@link VcfFormatException} is thrown
 * instead when a locus is seen more than once — see {@link Builder#setDuplicateLocusHandler}. A repeated {@code ID},
 * by contrast, is not a normal VCF occurrence and is still treated as a single-valued duplicate: by default, a
 * {@link VcfFormatException} is thrown each time a duplicate ID is found. To change this behavior, see
 * {@link Builder#setDuplicateIdHandler}.
 *
 * @author Douglas Myers-Turnbull
 */
public class MemoryMappedVcfLineParser implements VcfLineParser {
  private MemoryMappedVcfDataStore m_dataStore = new MemoryMappedVcfDataStore();
  private DuplicateHandler m_duplicateIdHandler;
  private LocusDuplicateHandler m_duplicateLocusHandler;


  private MemoryMappedVcfLineParser(DuplicateHandler idHandler, LocusDuplicateHandler locusHandler) {
    m_duplicateIdHandler = idHandler;
    m_duplicateLocusHandler = locusHandler;
  }


  public MemoryMappedVcfDataStore getDataStore() {
    return  m_dataStore;
  }


  @Override
  public void parseLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {

    m_dataStore.setMetadata(metadata);

    // link by locus
    MemoryMappedVcfDataStore.Locus locus = new MemoryMappedVcfDataStore.Locus(position.getChromosome(), position.getPosition());
    List<VcfPosition> positionsAtLocus = m_dataStore.getLocusToPositions().computeIfAbsent(locus, l -> new ArrayList<>());
    if (!positionsAtLocus.isEmpty() && m_duplicateLocusHandler == LocusDuplicateHandler.FAIL) {
      throw new VcfFormatException("Duplicate VCF record for position " + locus);
    }
    positionsAtLocus.add(position);
    m_dataStore.getLocusToSamplesList().computeIfAbsent(locus, l -> new ArrayList<>()).add(sampleData);

    // link by ID
    for (String id : position.getIds()) {
      boolean containsId = m_dataStore.getIdToPosition().containsKey(id);
      if (containsId && m_duplicateIdHandler == DuplicateHandler.FAIL) {
        throw new VcfFormatException("Duplicate VCF record for ID " + id);
      }
      if (!containsId || m_duplicateIdHandler == DuplicateHandler.KEEP_LAST) {
        m_dataStore.getIdToPosition().put(id, position);
        m_dataStore.getIdToSamples().put(id, sampleData);
      }
    }
  }


  public static class Builder {
    private DuplicateHandler m_duplicateIdHandler = DuplicateHandler.FAIL;
    private LocusDuplicateHandler m_duplicateLocusHandler = LocusDuplicateHandler.FAIL;

    /**
     * Determines what to do when an ID that was previously set is encountered, regardless of whether the two IDs
     * correspond to the same locus.
     *
     * This is independent of {@link #setDuplicateLocusHandler(LocusDuplicateHandler)}.
     */
    public Builder setDuplicateIdHandler(DuplicateHandler handler) {
      m_duplicateIdHandler = handler;
      return this;
    }

    /**
     * Determines what to do when a VCF record is encountered with a locus (chromosome and position) that was already
     * found.
     *
     * This is independent of {@link #setDuplicateIdHandler(DuplicateHandler)}.
     */
    public Builder setDuplicateLocusHandler(LocusDuplicateHandler handler) {
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
  public enum DuplicateHandler {

    /**
     * Throw a {@link VcfFormatException} when a duplicate is encountered.
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

  /**
   * What to do when a VCF record is encountered at a locus (chromosome and position) that was already seen.
   */
  public enum LocusDuplicateHandler {

    /**
     * Throw a {@link VcfFormatException} when a locus is seen more than once.
     */
    FAIL,

    /**
     * Keep every record seen at the locus.
     */
    KEEP_ALL
  }

}

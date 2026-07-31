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
 * Not thread-safe: internal state is held in plain, unsynchronized collections. Confine an instance to a
 * single thread, or provide external synchronization for concurrent use.
 * <p>
 * VCF permits more than one record at the same locus (e.g. multi-allelic sites split across lines), so every locus
 * is stored as a list of records rather than a single one; every record seen at a locus is kept, and it's up to the
 * consumer to decide what, if anything, to do about duplicates. A repeated {@code ID}, by contrast, is not a normal
 * VCF occurrence and is still treated as a single-valued duplicate: by default, a {@link VcfFormatException} is
 * thrown each time a duplicate ID is found. To change this behavior, see {@link Builder#setDuplicateIdHandler}.
 *
 * @author Douglas Myers-Turnbull
 */
public class MemoryMappedVcfLineParser implements VcfLineParser {
  private final MemoryMappedVcfDataStore m_dataStore = new MemoryMappedVcfDataStore();
  private DuplicateHandler m_duplicateIdHandler;


  private MemoryMappedVcfLineParser(DuplicateHandler idHandler) {
    m_duplicateIdHandler = idHandler;
  }


  public MemoryMappedVcfDataStore getDataStore() {
    return  m_dataStore;
  }


  @Override
  public void parseMetadata(VcfMetadata metadata) {
    m_dataStore.setMetadata(metadata);
  }


  @Override
  public void parseLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {

    // fallback in case parseMetadata was not invoked (e.g. parseLine called directly, not through VcfParser)
    m_dataStore.setMetadata(metadata);

    // check every ID for a FAIL-mode collision before mutating anything, so a rejected record is never partially
    // committed -- neither its locus entry nor any of its (possibly several) IDs
    if (m_duplicateIdHandler == DuplicateHandler.FAIL) {
      for (String id : position.getIds()) {
        if (m_dataStore.getIdToPosition().containsKey(id)) {
          throw new VcfFormatException("Duplicate VCF record for ID " + id);
        }
      }
    }

    // link by locus: VCF permits more than one record at the same locus, so every record is kept
    MemoryMappedVcfDataStore.Locus locus = new MemoryMappedVcfDataStore.Locus(position.getChromosome(), position.getPosition());
    m_dataStore.getLocusToPositions().computeIfAbsent(locus, l -> new ArrayList<>()).add(position);
    m_dataStore.getLocusToSamplesList().computeIfAbsent(locus, l -> new ArrayList<>()).add(sampleData);

    // link by ID
    for (String id : position.getIds()) {
      boolean containsId = m_dataStore.getIdToPosition().containsKey(id);
      if (!containsId || m_duplicateIdHandler == DuplicateHandler.KEEP_LAST) {
        m_dataStore.getIdToPosition().put(id, position);
        m_dataStore.getIdToSamples().put(id, sampleData);
      }
    }
  }


  public static class Builder {
    private DuplicateHandler m_duplicateIdHandler = DuplicateHandler.FAIL;

    /**
     * Determines what to do when an ID that was previously set is encountered, regardless of whether the two IDs
     * correspond to the same locus.
     */
    public Builder setDuplicateIdHandler(DuplicateHandler handler) {
      m_duplicateIdHandler = handler;
      return this;
    }

    public MemoryMappedVcfLineParser build() {
      return new MemoryMappedVcfLineParser(m_duplicateIdHandler);
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
     * Keeps the ID-based lookup ({@link MemoryMappedVcfDataStore#getSampleForId} and
     * {@link MemoryMappedVcfDataStore#getGenotypeForId}) pointing at the record that was previously read for a
     * given ID. The record itself is still added to the locus-based lookup
     * ({@link MemoryMappedVcfDataStore#getSampleAtLocus} and {@link MemoryMappedVcfDataStore#getGenotypeAtLocus})
     * like any other record; only the specific colliding ID's entry in the ID-based maps is left unchanged.
     */
    KEEP_FIRST,

    /**
     * Replace the {@link org.pharmgkb.parser.vcf.model.VcfSample VcfSamples} and {@link org.pharmgkb.parser.vcf.model.VcfPosition}.
     * In other words, ignore the fact that the record is a duplicate and record it anyway.
     */
    KEEP_LAST
  }

}

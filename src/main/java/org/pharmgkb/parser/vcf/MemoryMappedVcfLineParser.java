package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.google.common.base.Preconditions;


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
  public void parseLine(VcfMetadata metadata, VcfRecord record) {

    // fallback in case parseMetadata was not invoked (e.g. parseLine called directly, not through VcfParser);
    // also rejects a null metadata immediately, before the record is ever touched below
    m_dataStore.setMetadata(metadata);
    // parseLine() is public and callable directly, so per AGENTS.md's Public API Null-Safety Policy a null
    // record is rejected with VcfFormatException rather than raw-crashing on the unpack below. This subsumes
    // the former explicit null-position/null-sample-list checks: every VcfRecord funnels through one
    // constructor that already rejects both, including on the VcfContext.newTrustedRecord() fast path, so
    // neither is reachable here any more.
    if (record == null) {
      throw new VcfFormatException("parseLine() was called with a null record");
    }
    VcfPosition position = record.getPosition();
    List<VcfSample> sampleData = record.getSamples();
    // a null sample element does survive VcfContext.newTrustedRecord(), which skips the per-element check
    // VcfContext.newRecord makes, so this loop -- unlike the two checks it replaced -- is still reachable
    for (int sampleIndex = 0; sampleIndex < sampleData.size(); sampleIndex++) {
      if (sampleData.get(sampleIndex) == null) {
        throw new VcfFormatException("parseLine() was called with a null sample at index " + sampleIndex);
      }
    }

    // check every ID for a FAIL-mode collision before mutating anything, so a rejected record is never partially
    // committed -- neither its locus entry nor any of its (possibly several) IDs. This must also catch a
    // collision within this record's own ID list (e.g. "ID=rs1;rs1"): checking only against previously-committed
    // records would miss it, since neither occurrence is in the data store yet -- the second loop below would then
    // silently keep only the first occurrence (equivalent to KEEP_FIRST), contradicting FAIL's own contract.
    if (m_duplicateIdHandler == DuplicateHandler.FAIL) {
      Set<String> idsSeenInThisRecord = new HashSet<>();
      for (String id : position.getIds()) {
        if (m_dataStore.getIdToPosition().containsKey(id) || !idsSeenInThisRecord.add(id)) {
          throw new VcfFormatException("Duplicate VCF record for ID " + id);
        }
      }
    }

    // link by locus: VCF permits more than one record at the same locus, so every record is kept
    MemoryMappedVcfDataStore.Locus locus = new MemoryMappedVcfDataStore.Locus(position.getChromosome(), position.getPosition());
    m_dataStore.getLocusToPositions().computeIfAbsent(locus, l -> new ArrayList<>()).add(position);
    m_dataStore.getLocusToSamplesList().computeIfAbsent(locus, l -> new ArrayList<>()).add(sampleData);
    // also record file order directly (not derivable from the locus maps above -- see
    // MemoryMappedVcfDataStore's m_positionsInFileOrder/m_samplesListInFileOrder field comment) for
    // getAllPositions()/getAllSamples()
    m_dataStore.getPositionsInFileOrder().add(position);
    m_dataStore.getSamplesListInFileOrder().add(sampleData);

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
      // handler is a caller-configuration object, never VCF content, so Preconditions.checkNotNull's raw
      // NullPointerException is this codebase's established convention here (see AGENTS.md's Public API
      // Null-Safety Policy and WONT_FIX.md's VcfParser.Builder entries). Without this, a null handler would
      // silently fall through every == comparison against DuplicateHandler.FAIL/KEEP_LAST in parseLine()
      // above (lines 82, 99), substituting the remaining behavior with no warning that the caller's intended
      // handler was never applied, rather than failing fast at the actual mistake.
      Preconditions.checkNotNull(handler);
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
     * Replace the {@link org.pharmgkb.parser.vcf.VcfSample VcfSamples} and {@link org.pharmgkb.parser.vcf.VcfPosition}.
     * In other words, ignore the fact that the record is a duplicate and record it anyway.
     */
    KEEP_LAST
  }

}

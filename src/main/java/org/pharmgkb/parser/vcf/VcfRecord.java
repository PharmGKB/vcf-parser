package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;


/**
 * A single VCF data record: one {@link VcfPosition} plus its per-sample data, in column order.
 * <p>
 * Structurally immutable -- the sample list is exposed unmodifiable, and every field of this class is
 * {@code final}, so this object itself is safe to publish across threads. The contained
 * {@link VcfPosition}/{@link VcfSample} objects carry their own, separate mutability contract, unlike this
 * class's own: {@code VcfPosition} performs unsynchronized lazy QUAL/INFO parsing on first access and is
 * documented as not thread-safe on its own terms, so sharing a {@code VcfRecord} across threads is only as
 * safe as sharing its contained {@code VcfPosition} would be -- this class's own structural immutability does
 * not, by itself, make the whole record shareable.
 *
 * <p>
 * {@code final} deliberately: with overridable getters, a subclass could return mutable state and the
 * structural-immutability promise above would not be enforced by the type.
 *
 * @author Mark Woon
 */
public final class VcfRecord {

  private final VcfContext m_context;
  private final VcfPosition m_position;
  private final List<VcfSample> m_samples;


  /**
   * The single construction path for a record. Package-private: every caller goes through
   * {@link VcfContext#newRecord}, or -- for {@code VcfParser}'s own hot path --
   * {@code VcfContext.newTrustedRecord}.
   * <p>
   * The null checks below are deliberately in this constructor rather than only in {@link VcfContext#newRecord}:
   * they must hold on <em>both</em> paths, so {@code MemoryMappedVcfLineParser.parseLine} need not re-check
   * them. {@code VcfRecordTest} pins that for the trusted path specifically.
   *
   * @param trusted when {@code true}, {@code samples} is wrapped without copying or per-element null-checking
   */
  VcfRecord(VcfContext context, @Nullable VcfPosition position, @Nullable List<VcfSample> samples,
      boolean trusted) {
    m_context = Preconditions.checkNotNull(context, "context must not be null");
    if (position == null) {
      throw new VcfFormatException("VcfRecord was constructed with a null position");
    }
    if (samples == null) {
      throw new VcfFormatException("VcfRecord was constructed with a null sample list");
    }
    m_position = position;
    if (trusted) {
      m_samples = Collections.unmodifiableList(samples);
      return;
    }
    List<VcfSample> copy = new ArrayList<>(samples.size());
    for (int i = 0; i < samples.size(); i++) {
      VcfSample sample = samples.get(i);
      if (sample == null) {
        throw new VcfFormatException("VcfRecord was constructed with a null sample at index " + i);
      }
      copy.add(sample);
    }
    m_samples = Collections.unmodifiableList(copy);
  }


  /**
   * @return the context this record, its position, and every one of its samples were created under; never
   * {@code null}.
   */
  public VcfContext getContext() {
    return m_context;
  }


  public VcfPosition getPosition() {
    return m_position;
  }

  /** @return per-sample data in column order; never {@code null}, never contains {@code null}, unmodifiable. */
  public List<VcfSample> getSamples() {
    return m_samples;
  }

  /**
   * @return a new {@link Builder}, seeded with this record's position and a copy of its current sample list,
   * bound to this record's own {@link VcfContext} by reference (not a re-derived or newly constructed one --
   * see {@link VcfContext#newRecord}, which rejects a component built under a different context instance).
   */
  public Builder toBuilder() {
    return new Builder(m_context)
        .position(m_position)
        .samples(m_samples);
  }


  /**
   * A copy-and-modify builder for {@link VcfRecord}, obtained via {@link #toBuilder()}. Every setter
   * defensively copies its argument on the way in -- matching {@link VcfSample.Builder}'s own convention: a
   * caller mutating a list handed to a setter, or the list obtained from the source record via
   * {@link #toBuilder()}, must never be able to reach back into the record {@link #build()} produces.
   */
  public static class Builder {
    private @Nullable VcfPosition m_position;
    private @Nullable List<VcfSample> m_samples;
    private final VcfContext m_context;

    Builder(VcfContext context) {
      m_context = Preconditions.checkNotNull(context, "context must not be null");
    }

    /**
     * @throws VcfFormatException if {@code position} is null
     */
    public Builder position(VcfPosition position) {
      // position is typed non-@Nullable, but nothing enforces that at runtime; reject immediately per AGENTS.md's
      // Public API Null-Safety Policy, matching VcfContext.newRecord's identical check.
      if (position == null) {
        throw new VcfFormatException("Position is null");
      }
      m_position = position;
      return this;
    }

    /**
     * Defensively copies {@code samples}; see this class's own Javadoc for why.
     *
     * @throws VcfFormatException if {@code samples} is null
     */
    public Builder samples(List<VcfSample> samples) {
      // samples is typed non-@Nullable, but nothing enforces that at runtime; reject immediately per AGENTS.md's
      // Public API Null-Safety Policy, matching VcfContext.newRecord's identical check.
      if (samples == null) {
        throw new VcfFormatException("Sample list is null");
      }
      m_samples = new ArrayList<>(samples);
      return this;
    }

    /**
     * @return a new {@link VcfRecord}, delegating to {@link VcfContext#newRecord}, which validates
     * position/sample-list nullness, per-sample nullness, and that every component was created under this
     * builder's own context.
     */
    public VcfRecord build() {
      return m_context.newRecord(m_position, m_samples);
    }
  }
}

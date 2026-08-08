package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;


/**
 * A single VCF data record: one {@link VcfPosition} plus its per-sample data, in column order.
 * <p>
 * Structurally immutable -- the sample list is exposed unmodifiable. The contained
 * {@link VcfPosition}/{@link VcfSample} objects carry their own mutability contract.
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
   * <p>
   * Package-private for the same reason as {@link VcfPosition#getContext()}.
   */
  VcfContext getContext() {
    return m_context;
  }


  public VcfPosition getPosition() {
    return m_position;
  }

  /** @return per-sample data in column order; never {@code null}, never contains {@code null}, unmodifiable. */
  public List<VcfSample> getSamples() {
    return m_samples;
  }
}

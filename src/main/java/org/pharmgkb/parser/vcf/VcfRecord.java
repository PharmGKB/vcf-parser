package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

  private final VcfPosition m_position;
  private final List<VcfSample> m_samples;


  /**
   * @param position the record's fixed fields
   * @param samples per-sample data in column order; defensively copied, so a later mutation of the argument
   * does not affect this record
   * @throws VcfFormatException if {@code position} or {@code samples} is {@code null}, or {@code samples}
   * contains a {@code null} element. Per AGENTS.md's Public API Null-Safety Policy, a constructor storing
   * caller-supplied VCF content rejects {@code null} immediately rather than raw-crashing later.
   */
  public VcfRecord(@Nullable VcfPosition position, @Nullable List<VcfSample> samples) {
    this(position, samples, false);
  }

  /**
   * @param trusted when {@code true}, {@code samples} is wrapped without copying or per-element null-checking.
   * The flag is what distinguishes this from the public constructor: without it the two would erase to the
   * identical signature {@code VcfRecord(VcfPosition, List)} and fail to compile, since {@code @Nullable} does
   * not participate in overload resolution.
   */
  private VcfRecord(@Nullable VcfPosition position, @Nullable List<VcfSample> samples, boolean trusted) {
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
   * Internal fast path for {@code VcfParser}'s own per-record construction. Wraps {@code ownedSamples} without
   * copying or per-element null-checking it, which on the parse hot path would cost one allocation and an
   * O(sample-count) copy per data line. Safe only because {@code VcfParser} builds that list locally and never
   * shares it -- the same single-call-site ownership contract as {@link VcfSample#newTrusted}. Never call with
   * a list an external caller might still hold a reference to or mutate, or one that could contain
   * {@code null}.
   * <p>
   * Package-private, not public-with-{@code @RestrictedApi}: {@code @RestrictedApi} is documentation-only in
   * this project (see {@code AGENTS.md}), so a public trusted path would let an external caller skip
   * validation and retain a mutable handle into a model that advertises itself as structurally immutable.
   * Visibility enforces what the annotation only advertised. Not part of this library's supported public API
   * and may change or be removed without notice.
   */
  static VcfRecord newTrusted(VcfPosition position, List<VcfSample> ownedSamples) {
    return new VcfRecord(position, ownedSamples, true);
  }


  public VcfPosition getPosition() {
    return m_position;
  }

  /** @return per-sample data in column order; never {@code null}, never contains {@code null}, unmodifiable. */
  public List<VcfSample> getSamples() {
    return m_samples;
  }
}

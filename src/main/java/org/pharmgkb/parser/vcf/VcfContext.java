package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.google.common.base.Preconditions;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import org.jspecify.annotations.Nullable;


/**
 * Immutable, file-scoped context for one parse or one write.
 * <p>
 * Exactly one instance exists per parse and per write, shared by reference across every model object that
 * operation creates -- an interned enum plus two references, so it costs nothing on the per-record hot path.
 * <p>
 * It carries <em>both</em> the declared {@code ##fileformat} string and the resolved {@link VcfVersion}. The
 * writer emits the declared value verbatim, so a file declaring a version this library does not implement still
 * round-trips under its own declaration; version-dependent interpretation uses the resolved value.
 * <p>
 * Deliberately excluded: {@link VcfMetadata} (it would re-couple {@link VcfPosition} to header state and pin
 * the header into every streamed record) and the per-record line number (this context is file-scoped, so a
 * shared mutable "current line" would be a stale read for any record a caller holds past the next
 * {@code parseNextLine()}).
 *
 * @author Mark Woon
 */
public final class VcfContext {

  private final String m_declaredFileFormat;
  private final VcfVersion m_version;
  private final VcfWarnings m_warnings;
  private final boolean m_lenient;

  private VcfContext(String declaredFileFormat, VcfWarnings warnings, boolean lenient) {
    Preconditions.checkNotNull(warnings, "warnings must not be null");
    m_declaredFileFormat = declaredFileFormat;
    m_version = VcfVersion.resolve(declaredFileFormat, warnings);
    m_warnings = warnings;
    m_lenient = lenient;
  }

  /**
   * Context for parsing or writing a file: a non-structural content violation warns and recovers.
   *
   * @throws VcfFormatException if {@code declaredFileFormat} is null or not a valid VCF 4.x version
   */
  public static VcfContext lenient(String declaredFileFormat, VcfWarnings warnings) {
    return new VcfContext(declaredFileFormat, warnings, true);
  }

  /**
   * Context for direct programmatic construction: a content violation that {@link #lenient} would warn about
   * throws instead. Warnings go to {@link VcfWarnings#DEFAULT}.
   * <p>
   * This governs <em>content</em> validation only. Version resolution is identical in both modes -- a
   * well-formed {@code ##fileformat} for an unimplemented version falls back and warns here too, since it is
   * not invalid content. Only a structurally unusable value throws.
   *
   * @throws VcfFormatException if {@code declaredFileFormat} is null or not a valid VCF 4.x version
   */
  public static VcfContext strict(String declaredFileFormat) {
    return new VcfContext(declaredFileFormat, VcfWarnings.DEFAULT, false);
  }

  /** @return the raw {@code ##fileformat} value, for verbatim re-emission on write. */
  public String getDeclaredFileFormat() {
    return m_declaredFileFormat;
  }

  /** @return the version whose semantics apply, after fallback resolution. */
  public VcfVersion getVersion() {
    return m_version;
  }

  public VcfWarnings getWarnings() {
    return m_warnings;
  }

  /** @return {@code true} to warn and recover on a non-structural content violation, {@code false} to throw. */
  public boolean isLenient() {
    return m_lenient;
  }


  /**
   * Creates a fully-specified {@link VcfPosition} bound to this context.
   * <p>
   * <b>Every mutable argument is defensively copied.</b> Unmodifiable getters alone would not be enough: if this
   * stored the caller's {@code List}/{@code ListMultimap} by reference, {@link VcfPosition#getFilters()} and
   * friends would return an unmodifiable view wrapping that same caller-owned object, so the caller could still
   * mutate the position's backing state from outside by mutating their own reference directly, bypassing the
   * view entirely. The copies are real, mutable {@link ArrayList}/{@link LinkedListMultimap} instances, not
   * {@code List.copyOf(...)} -- normalizing helpers such as {@code InternalUtils.fillEmptyEntriesWithDot} mutate
   * their argument in place and require a mutable list.
   *
   * @param lineNumber the originating line number in the source VCF file, or {@code -1} if this position was not
   * produced by parsing a real file
   */
  public VcfPosition newPosition(String chr, long pos, @Nullable List<String> ids, String ref,
      @Nullable List<String> altBases, @Nullable BigDecimal qual, @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info, @Nullable List<String> format, int lineNumber) {
    return new VcfPosition(this, chr, pos, copyOf(ids), ref, copyOf(altBases), qual, copyOf(filter), copyOf(info),
        copyOf(format), lineNumber, false, false, null, null);
  }

  /**
   * Creates a {@link VcfPosition} with no ID/ALT/FILTER/INFO/FORMAT content. Nothing mutable is supplied, so
   * there is nothing to copy.
   *
   * @param lineNumber the originating line number in the source VCF file, or {@code -1} if this position was not
   * produced by parsing a real file
   */
  public VcfPosition newPosition(String chromosome, long position, String refBases, @Nullable BigDecimal quality,
      int lineNumber) {
    return new VcfPosition(this, chromosome, position, refBases, quality, lineNumber);
  }

  /**
   * Internal fast path for {@code VcfParser}, for collections the caller built itself and owns exclusively.
   * <p>
   * <b>Skips only the defensive copies</b> {@link #newPosition} makes -- every validation check still runs,
   * FORMAT's included. That is the whole difference from {@link #newPositionWithTrustedFormat} below, which
   * additionally skips {@code checkFormat}; the two are not interchangeable, and this one is what a
   * FORMAT-cache <em>miss</em> needs, since a miss is precisely the case that has to validate FORMAT.
   * <p>
   * Safe only because {@code VcfParser} produces each of these lists by splitting the data line it is
   * currently parsing and never shares them. Never call with a collection an external caller might still hold
   * a reference to: {@link VcfPosition#getIds()} and friends return an unmodifiable view, but it would still be
   * backed by that caller's own still-mutable object, so a later mutation through the caller's own reference
   * would be visible through the position instead of the position holding an independent, truly immutable copy.
   * <p>
   * Package-private, not public-with-{@code @RestrictedApi}: that annotation is documentation-only here, so
   * visibility is what actually enforces the restriction.
   */
  VcfPosition newPositionTakingOwnership(String chr, long pos, @Nullable List<String> ids, String ref,
      @Nullable List<String> altBases, @Nullable BigDecimal qual, @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info, @Nullable List<String> ownedFormat, int lineNumber) {
    return newPositionTakingOwnership(chr, pos, ids, ref, altBases, qual, filter, info, ownedFormat, lineNumber,
        null, null);
  }

  /**
   * Same as the overload above, but also accepts raw, not-yet-parsed QUAL/INFO column text to attach in the same
   * call -- lets {@code VcfParser} construct a lazily-parsed position in one call, without a second
   * post-construction call or an intermediate {@link VcfPosition.Builder} allocation. {@code rawQual} is only
   * meaningful (and only stored) when {@code qual} is {@code null} -- mirrors
   * {@link VcfPosition.Builder}'s own quality-vs-rawQuality mutual exclusivity; likewise {@code rawInfo} vs
   * {@code info}. Package-private for the same reason as the overload above.
   */
  VcfPosition newPositionTakingOwnership(String chr, long pos, @Nullable List<String> ids, String ref,
      @Nullable List<String> altBases, @Nullable BigDecimal qual, @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info, @Nullable List<String> ownedFormat, int lineNumber,
      @Nullable String rawQual, @Nullable String rawInfo) {
    return new VcfPosition(this, chr, pos, ids, ref, altBases, qual, filter, info, ownedFormat, lineNumber,
        false, false, rawQual, rawInfo);
  }

  /**
   * Internal fast path for {@code VcfParser}'s own per-record FORMAT-validation cache. Skips FORMAT
   * validation entirely -- {@code checkFormat} and every warning it would emit -- <em>and</em> the defensive
   * copies, for a caller-guaranteed already-validated, independently-owned list.
   * <p>
   * Strictly more skipping than {@link #newPositionTakingOwnership} above: use this one only on a cache
   * <em>hit</em>, where an identical FORMAT column has already been validated on an earlier record in the same
   * parse.
   * <p>
   * Package-private, not public-with-{@code @RestrictedApi}: that annotation is documentation-only here, so
   * visibility is what actually enforces the restriction.
   */
  VcfPosition newPositionWithTrustedFormat(String chr, long pos, @Nullable List<String> ids, String ref,
      @Nullable List<String> altBases, @Nullable BigDecimal qual, @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info, List<String> trustedFormat, int lineNumber) {
    return newPositionWithTrustedFormat(chr, pos, ids, ref, altBases, qual, filter, info, trustedFormat, lineNumber,
        null, null);
  }

  /**
   * Same as the overload above, but also accepts raw, not-yet-parsed QUAL/INFO column text -- see
   * {@link #newPositionTakingOwnership(String, long, List, String, List, BigDecimal, List, ListMultimap, List,
   * int, String, String)}'s identical rawQual/rawInfo Javadoc; the same reasoning applies here.
   */
  VcfPosition newPositionWithTrustedFormat(String chr, long pos, @Nullable List<String> ids, String ref,
      @Nullable List<String> altBases, @Nullable BigDecimal qual, @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info, List<String> trustedFormat, int lineNumber,
      @Nullable String rawQual, @Nullable String rawInfo) {
    return new VcfPosition(this, chr, pos, ids, ref, altBases, qual, filter, info, trustedFormat, lineNumber,
        true, false, rawQual, rawInfo);
  }

  /**
   * Internal fast path used exclusively by {@code VcfPosition.Builder#build()}.
   * <p>
   * <b>Always copies</b> every list/multimap argument, like the public {@link #newPosition} above and unlike
   * {@link #newPositionTakingOwnership} -- {@code Builder} is reusable (a caller may call {@code build()} more
   * than once on the same instance), so handing off the builder's own retained references without copying would
   * alias every position built from it to each other and to the builder itself. Skips
   * {@code checkIds}/{@code checkAltBases}/{@code checkFilters}/{@code checkInfoEntries}/{@code checkFormat}
   * entirely (every check each performs, including any warning), trusting the caller -- {@code Builder#build()},
   * which re-runs exactly the same checks itself for whichever field a setter actually changed -- to guarantee
   * every argument here already passed its own check once, unchanged. {@code checkChromosome}/
   * {@code checkPosition}/{@code checkRef} are not skippable and always still run; see the constructor's own
   * {@code trustedFields} Javadoc for why.
   * <p>
   * Package-private, not public-with-{@code @RestrictedApi}: that annotation is documentation-only here, so
   * visibility is what actually enforces the restriction.
   */
  VcfPosition newPositionCopyingTrustedFields(String chr, long pos, @Nullable List<String> ids, String ref,
      @Nullable List<String> altBases, @Nullable BigDecimal qual, @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info, @Nullable List<String> format, int lineNumber,
      @Nullable String rawQual, @Nullable String rawInfo) {
    return new VcfPosition(this, chr, pos, copyOf(ids), ref, copyOf(altBases), qual, copyOf(filter), copyOf(info),
        copyOf(format), lineNumber, true, true, rawQual, rawInfo);
  }

  /**
   * Creates a {@link VcfSample} bound to this context, defensively copying both lists -- see
   * {@link #newPosition(String, long, List, String, List, BigDecimal, List, ListMultimap, List, int)} for why
   * unmodifiable getters alone would not be sufficient.
   */
  public VcfSample newSample(@Nullable List<String> keys, @Nullable List<String> values) {
    return new VcfSample(this, copyOf(keys), copyOf(values), false);
  }

  /**
   * Creates a {@link VcfSample} from a property map, defensively copying the map for the same reason the
   * list-based factory copies its lists. The copy is a {@link LinkedHashMap}, preserving the caller's iteration
   * order, which is the FORMAT sub-field order this sample will be written in.
   */
  public VcfSample newSample(@Nullable LinkedHashMap<String, String> properties) {
    // the copy has to happen after the null check, not as part of it: new LinkedHashMap<>(null) raw-crashes with
    // NullPointerException, and AGENTS.md's Public API Null-Safety Policy requires VcfFormatException here.
    // VcfSample's own constructor makes the identical check, so this one exists purely to order the copy safely.
    if (properties == null) {
      throw new VcfFormatException("Sample properties map is null");
    }
    return new VcfSample(this, new LinkedHashMap<>(properties));
  }

  /**
   * Internal fast path for {@code VcfParser}'s own per-record sample splitting. Skips the
   * structural-delimiter scan and the defensive copies the plain factory performs, safe only because the
   * keys/values are produced by splitting on {@code ':'}/tab and can never contain a delimiter.
   * <p>
   * Package-private, not public-with-{@code @RestrictedApi}: that annotation is documentation-only here, so
   * visibility is what actually enforces the restriction.
   */
  VcfSample newTrustedSample(List<String> keys, List<String> values) {
    return new VcfSample(this, keys, values, true);
  }

  /**
   * The only public construction path for a {@link VcfRecord}.
   * <p>
   * Rejects a {@code null} position, a {@code null} sample list, and a {@code null} sample element with
   * {@link VcfFormatException}, and defensively copies {@code samples} so a later mutation of the caller's
   * list is not visible through the record. On top of that it verifies that {@code position} and every
   * sample were created under <em>this</em> context, by reference.
   * <p>
   * Reference identity is the right check, not value equality: one {@code VcfContext} instance per parse and
   * per write is the whole model, so two equal-valued contexts still represent two different operations with
   * different warnings sinks. A record assembled from a context-A position and context-B samples would have no
   * unambiguous resolved version or sink to answer with.
   * <p>
   * <b>Null checks run before any dereference.</b> A context comparison such as
   * {@code position.getContext() != this} would raw-crash with {@code NullPointerException} on a null
   * position, which AGENTS.md's Public API Null-Safety Policy forbids -- the caller must get a
   * {@code VcfFormatException} naming the actual mistake.
   *
   * @throws VcfFormatException on any null, or if any component carries a different context instance
   */
  public VcfRecord newRecord(@Nullable VcfPosition position, @Nullable List<VcfSample> samples) {
    if (position == null) {
      throw new VcfFormatException("VcfRecord was constructed with a null position");
    }
    if (samples == null) {
      throw new VcfFormatException("VcfRecord was constructed with a null sample list");
    }
    if (position.getContext() != this) {
      throw new VcfFormatException("The position was created under a different VcfContext than this one; the " +
          "record would have no unambiguous version or warnings destination");
    }
    for (int i = 0; i < samples.size(); i++) {
      VcfSample sample = samples.get(i);
      if (sample == null) {
        throw new VcfFormatException("VcfRecord was constructed with a null sample at index " + i);
      }
      if (sample.getContext() != this) {
        throw new VcfFormatException("Sample #" + i + " was created under a different VcfContext than this one; " +
            "the record would have no unambiguous version or warnings destination");
      }
    }
    return new VcfRecord(this, position, samples, false);
  }

  /**
   * Internal fast path for {@code VcfParser}'s own per-record construction. Skips both the defensive copy
   * and the context-identity verification {@link #newRecord} performs -- safe only because the parser built
   * {@code position} and every sample from <em>this</em> context moments earlier and owns the list
   * exclusively.
   * <p>
   * It does <em>not</em> skip the null-position/null-sample-list rejection: those live in
   * {@link VcfRecord}'s own constructor precisely so both paths make them, which is why
   * {@code MemoryMappedVcfLineParser.parseLine} does not re-check them.
   */
  VcfRecord newTrustedRecord(VcfPosition position, List<VcfSample> ownedSamples) {
    return new VcfRecord(this, position, ownedSamples, true);
  }

  private static @Nullable List<String> copyOf(@Nullable List<String> list) {
    return list == null ? null : new ArrayList<>(list);
  }

  private static @Nullable ListMultimap<String, String> copyOf(@Nullable ListMultimap<String, String> multimap) {
    return multimap == null ? null : LinkedListMultimap.create(multimap);
  }
}

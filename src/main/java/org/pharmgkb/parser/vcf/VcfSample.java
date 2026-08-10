package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.Immutable;
import org.jspecify.annotations.Nullable;


/**
 * This class contains sample data for a VCF position line.
 *
 * <p>
 * Effectively immutable: there are no public mutation routes, and {@link #getPropertyKeys()}/
 * {@link #propertyEntrySet()} each return an unmodifiable view -- an entry's {@link Map.Entry#setValue} also throws
 * {@link UnsupportedOperationException} -- so no value a caller can observe changes once construction (or
 * {@link Builder#build()}) has returned. {@link #validate()} is the one internal exception: it mutates
 * {@link #m_properties} in place to canonicalize an empty value to {@code "."} (see its own Javadoc), the same
 * design {@link VcfPosition#validate()} uses for its own equivalent in-place normalization.
 * </p>
 *
 * <p>To avoid allocating a map for every sample on every line (the common parse-then-read path), the FORMAT keys and
 * their values are kept as parallel lists and read by a short linear scan. A {@link LinkedHashMap} is materialized
 * only the first time a keys/entries view is requested (or supplied directly via the map constructor), writing
 * {@link #m_properties} (and clearing {@link #m_keys}/{@link #m_values}) at that point. That caching has no
 * observable effect on a single-threaded caller, but it is unsynchronized, so <b>this class is not thread-safe</b>:
 * sharing one instance across threads before its property views have been read at least once is not safe without
 * external synchronization. A caller that needs a changed sample derives a new one via {@link #toBuilder()}.</p>
 *
 * @author Mark Woon
 */
public final class VcfSample {

  // Lean representation used on the parse path; both null once m_properties has been materialized. VcfParser may
  // pass the same List instance as m_keys to multiple VcfSample objects for the same data line (one independent
  // copy per line, not per sample) -- safe only because no method in this class ever mutates m_keys in place; a
  // future change that did so would silently corrupt every sample sharing that line's copy.
  private @Nullable List<String> m_keys;
  private @Nullable List<String> m_values;
  // Materialized on demand (a keys/entries view is requested, or validate() runs), or supplied directly via the
  // map constructor.
  private @Nullable LinkedHashMap<String, String> m_properties;
  // Memoized unmodifiable views over m_properties's keySet()/entrySet(), returned by getPropertyKeys()/
  // propertyEntrySet() below instead of allocating a fresh wrapper on every call -- VcfWriter calls these once per
  // sample per record. Once built, each remains valid for this instance's entire lifetime: m_properties itself is
  // never reassigned to a different object after materialization (validate() mutates entry values in place, but
  // never replaces the map), so a memoized view never goes stale.
  private @Nullable Set<String> m_propertyKeysView;
  private @Nullable Set<Map.Entry<String, String>> m_propertyEntrySetView;
  // Fixed at construction, from m_context's own sink, for this instance's entire lifetime -- nothing (including
  // a VcfWriter this instance is later handed to) can re-point it afterward. See VcfWarnings's class Javadoc for
  // the full attribution rule. Same pattern as BaseMetadata#m_warnings; this class just isn't a BaseMetadata
  // subclass so it needs its own copy of the field.
  private final VcfWarnings m_warnings;
  private final VcfContext m_context;

  /**
   * The single construction path for a parallel-list sample. Package-private: every caller goes through
   * {@link VcfContext#newSample(List, List)} or, for {@code VcfParser}'s own per-record splitting,
   * {@code VcfContext.newTrustedSample}.
   * <p>
   * <b>This constructor takes ownership of {@code keys} and {@code values}</b> -- it does not defensively copy
   * either list, to avoid an allocation on this library's hottest path (once per sample per data line -- see
   * this class's own Javadoc for the parse-then-read performance rationale). {@link VcfContext#newSample} is
   * where the copy for an externally-supplied list happens.
   *
   * @param trusted when {@code true}, skips the per-key/value {@link #checkNoStructuralDelimiter} scan, which
   * micro-benchmarking measured as ~92% of the plain path's cost (43.4% of all execution samples on a
   * 163-sample-per-record JFR profile). The non-null/matching-length checks still run; only the O(n)
   * structural-delimiter scan is skipped, since a parser-produced key/value can never contain a colon, tab, or
   * line terminator (already split on / stripped before reaching here).
   */
  VcfSample(VcfContext context, @Nullable List<String> keys, @Nullable List<String> values, boolean trusted) {
    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_warnings = context.getWarnings();
    if (keys == null) {
      if (values == null || values.isEmpty()) {
        m_keys = Collections.emptyList();
        m_values = Collections.emptyList();
        return;
      }
      throw new VcfFormatException("Sample keys is null but values is not");
    } else if (values == null) {
      throw new VcfFormatException("Sample values is null but keys is not");
    }
    if (keys.size() != values.size()) {
      throw new VcfFormatException("Number of FORMAT entries does not match number of sample entries");
    }
    if (!trusted) {
      VcfVersion version = context.getVersion();
      for (int x = 0; x < keys.size(); x++) {
        checkNoStructuralDelimiter(keys.get(x), values.get(x), version);
      }
    }
    m_keys = keys;
    m_values = values;
  }

  /**
   * Takes ownership of {@code properties}: like the parallel-list constructor above, this does not defensively
   * copy it. {@link VcfContext#newSample(LinkedHashMap)} is where the copy for an externally-supplied map
   * happens.
   */
  VcfSample(VcfContext context, LinkedHashMap<String, String> properties) {
    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_warnings = context.getWarnings();
    // properties is typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting
    // m_properties.entrySet() below throw a raw NullPointerException. The sibling parallel-list constructor
    // already rejects null keys/values cleanly; this constructor's own properties parameter was simply missed.
    if (properties == null) {
      throw new VcfFormatException("Sample properties map is null");
    }
    m_properties = properties;
    VcfVersion version = context.getVersion();
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      checkNoStructuralDelimiter(entry.getKey(), entry.getValue(), version);
    }
  }

  /** @return the context this sample was created under; never {@code null}. */
  public VcfContext getContext() {
    return m_context;
  }

  /**
   * @return a new {@link Builder}, seeded with a copy of this sample's current representation -- whichever of
   * the parallel-list ({@link #m_keys}/{@link #m_values}) or property-map ({@link #m_properties}) form is
   * currently populated, read directly rather than through {@link #properties()}, which would force
   * materialization (and, for a sample with a duplicate FORMAT key, warn about it) at copy time. Bound to this
   * sample's own {@link VcfContext} by reference (not a re-derived or newly constructed one).
   */
  public Builder toBuilder() {
    Builder builder = new Builder(m_context);
    if (m_properties != null) {
      builder.properties(m_properties);
    } else {
      List<String> keys = m_keys;
      List<String> values = m_values;
      assert keys != null && values != null;
      builder.keys(keys).values(values);
    }
    return builder;
  }

  /**
   * Rejects a key or value containing a line terminator, a colon, or a tab: a parsed key/value can never contain
   * these (they were already split on to arrive at this key/value), but one supplied directly to a constructor or
   * {@link Builder#property} could; reject them so the writer's output remains re-parseable (a colon would add a
   * spurious FORMAT subfield, and a tab would add a spurious sample column).
   * <p>
   * <b>Under VCFv4.2 and earlier only</b>,
   * {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE} (VCFv4.1.tex line 208/VCFv4.2.tex
   * line 225's genotype-likelihoods-of-heterogeneous-ploidy key) is exempted from the colon check on its
   * <em>value</em> only: the spec's own example for this key
   * ({@code GLE=0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53}) is one opaque String that uses colons
   * internally as part of its own genotype:likelihood encoding, not a delimiter between independent FORMAT
   * sub-fields, so rejecting those colons would make the spec's own example unrepresentable.
   * <p>
   * From VCFv4.3 the exemption does not apply: VCFv4.3 removed the key (VCFv4.3.tex line 2168, "Removed unused
   * and ill-defined GLE FORMAT tag"), so a 4.3+ key named {@code GLE} is an ordinary custom key with no reserved
   * meaning, and its value is colon-rejected exactly like every other non-reserved key's -- a colon there would
   * add a spurious FORMAT sub-field on write, which is precisely what this method exists to prevent. Keeping the
   * exemption past 4.2 would privilege one arbitrary custom key name over every other.
   * <p>
   * The exemption never applies to the key itself (a colon in the key name is always rejected), to a tab in any
   * value, or to any other key's value.
   */
  private static void checkNoStructuralDelimiter(String key, @Nullable String value, VcfVersion version) {
    if (key == null) {
      throw new VcfFormatException("Sample property key is null");
    }
    InternalUtils.checkNoLineTerminator(key, value);
    if (key.contains(":") || key.contains("\t")) {
      throw new VcfFormatException("Sample property key \"" + key + "\" contains ':' or a tab");
    }
    boolean isGle = !version.isAtLeast(VcfVersion.V4_3) &&
        key.equals(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy.getId());
    if (value != null && (value.contains("\t") || (!isGle && value.contains(":")))) {
      throw new VcfFormatException("Sample property value \"" + value + "\" for key \"" + key +
          "\" contains ':' or a tab");
    }
  }

  /**
   * Re-validates this sample's current properties, throwing {@link VcfFormatException} for a structural delimiter.
   * Every public construction path (both constructors, and {@link Builder#build()}) already runs
   * {@link #checkNoStructuralDelimiter} once, so this is normally a no-op re-check -- it remains genuinely load-
   * bearing only for a sample built via the package-private, validation-skipping {@code VcfContext.newTrustedSample}
   * ({@code VcfParser}'s own fast path; see its own Javadoc), which has not had that guarantee independently
   * verified. {@code VcfWriter}'s {@code validateBeforeWrite()} calls this method for exactly that reason. An empty
   * value is not a structural problem -- it does not corrupt column/sub-field alignment, the same as an empty INFO
   * value or an empty comma-separated FORMAT element elsewhere in this library -- so it is warned about and
   * normalized to {@code "."} (the VCF missing-value sentinel) in place, rather than rejected, mutating
   * {@link #m_properties} directly (this class's one exception to its own effective-immutability claim; see this
   * class's own Javadoc). See {@code EMPTY_FIELD_HANDLING.md}.
   */
  public void validate() {
    VcfVersion version = m_context.getVersion();
    for (Map.Entry<String, String> entry : properties().entrySet()) {
      checkNoStructuralDelimiter(entry.getKey(), entry.getValue(), version);
      if (entry.getValue() != null && entry.getValue().isEmpty()) {
        m_warnings.warn("Sample property {} has an empty value (VCF does not allow zero-length fields); treating " +
            "it as the missing value '.'", entry.getKey());
        entry.setValue(".");
      }
    }
  }

  /**
   * Materializes (and caches) the property map, dropping the lean parallel-list representation.
   */
  private LinkedHashMap<String, String> properties() {
    if (m_properties == null) {
      LinkedHashMap<String, String> map = new LinkedHashMap<>();
      List<String> keys = m_keys;
      List<String> values = m_values;
      assert keys != null && values != null;
      for (int x = 0; x < keys.size(); x++) {
        String key = keys.get(x);
        if (map.containsKey(key)) {
          // matches getProperty()'s already-correct first-wins behavior, and this project's established
          // "warn and keep first" convention for every other recoverable duplicate-key case (see
          // DUPLICATE_METADATA_ID_HANDLING.md and VcfPosition's duplicate top-level INFO key)
          m_warnings.warn("Sample contains duplicate FORMAT key \"{}\"; keeping only the first occurrence", key);
          continue;
        }
        map.put(key, values.get(x));
      }
      m_properties = map;
      m_keys = null;
      m_values = null;
    }
    return m_properties;
  }

  /**
   * @return the raw wire text of the value for {@code key}, or {@code null} if not present. This library never
   * percent-decodes a sample (FORMAT) value; see {@link VcfPercentEncoding} if you need semantic text.
   */
  public @Nullable String getProperty(String key) {
    if (m_properties != null) {
      return m_properties.get(key);
    }
    List<String> keys = m_keys;
    assert keys != null && m_values != null;
    for (int x = 0; x < keys.size(); x++) {
      if (keys.get(x).equals(key)) {
        return m_values.get(x);
      }
    }
    return null;
  }

  /**
   * Returns the value for the reserved property as the type specified by both {@link ReservedFormatProperty#getType()}
   * and {@link ReservedFormatProperty#isList()}.
   *
   * <p>
   * Returns {@code null}, after warning, when {@code key} is
   * {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE} and this sample's context declares
   * VCFv4.3 or later: VCFv4.3 removed that key, so there is no reserved property to read. The check lives in
   * {@code InternalUtils.convertProperty(ReservedProperty, ...)}, which this method delegates to; see its Javadoc.
   * Use {@link #getProperty(String)} or {@link #getProperty(VcfPosition, VcfMetadata, String)} for a 4.3+ key
   * named {@code GLE}.
   *
   * @param <T> The type specified by {@code ReservedInfoProperty.getType()} if {@code ReservedFormatProperty.isList()}
   *           is false;
   *           otherwise {@code List<V>} where V is the type specified by {@code ReservedFormatProperty.getType()}.
   */
  public @Nullable <T> T getProperty(ReservedFormatProperty key) {
    // matches getProperty(String)'s own null-tolerant "not present" contract (see containsProperty's Javadoc
    // comment below for the precedent this follows) -- returning null here also avoids ever reaching
    // InternalUtils.convertProperty(null, ...); this method guards against a null key itself rather than
    // relying on convertProperty to reject it
    if (key == null) {
      return null;
    }
    return InternalUtils.convertProperty(key, getProperty(key.getId()), m_context.getVersion(), m_warnings);
  }

  /**
   * Returns the value for {@code key} converted to the type its {@link ReservedFormatProperty} declares, or --
   * for a non-reserved key -- the type its {@code ##FORMAT} declaration in {@code metadata} specifies. Unlike
   * {@link #getProperty(ReservedFormatProperty)}, this also validates the value's cardinality against the
   * declared {@code Number}, using {@code position}'s ALT allele count and this sample's own ploidy (derived
   * from its own {@code GT} value) -- including {@code Number=G}'s ploidy-dependent genotype-ordering formula,
   * which {@link #getProperty(ReservedFormatProperty)} alone cannot compute, since it has no access to
   * {@code position} or {@code metadata}. {@code GL}/{@code PL}/{@code GP}/{@code PP} assume ploidy 2 when
   * {@code GT} is absent (their own spec-stated default -- see
   * {@code InternalUtils.resolvePloidyForCardinalityCheck}); every other {@code Number=G} key instead skips the
   * cardinality check entirely when ploidy can't be determined. A cardinality mismatch warns rather than
   * throwing; a genuine {@code Type} mismatch still throws {@link VcfFormatException}, unchanged from
   * {@link #getProperty(ReservedFormatProperty)}'s existing behavior. Returns {@code null} -- without any
   * cardinality warning -- when the value is absent or is the missing-value sentinel {@code "."}. A
   * non-reserved key's {@code ##FORMAT} declaration missing its own {@code Number} attribute is treated as
   * {@code Number=.} (matching bcftools's own "Assuming '.'" behavior for the same case, uniformly regardless
   * of {@code Type}) -- so the value is returned list-shaped even for {@code Type=String}.
   *
   * @param <T> the type {@code key}'s declared {@code Type} specifies if it (or, for a non-reserved key, its
   * {@code ##FORMAT} declaration's {@code Number}) is not list-shaped; otherwise {@code List<V>} where {@code V}
   * is that type
   * @throws VcfFormatException if {@code position} or {@code metadata} was built under a different
   * {@link VcfContext} than this sample, or if the raw value's {@code Type} does not match {@code key}'s declared
   * type
   */
  public @Nullable <T> T getProperty(VcfPosition position, VcfMetadata metadata, String key) {
    if (key == null || position == null || metadata == null) {
      return null;
    }
    // Structural, not this method's null tolerance above -- a different axis (AGENTS.md's Public API
    // Null-Safety Policy): a read assembled from a position/metadata built under a different VcfContext than
    // this sample has no unambiguous resolved version or warnings destination, so this throws rather than
    // returning null. Placed here, before rawValue is even looked up, so the two ordinary absence exits just
    // below (rawValue == null, and rawValue.equals(".")) never run first -- a structural mismatch must not
    // depend on whether the requested key happens to have a value. This sample's own context (m_context) is
    // the pivot, matching m_context.getVersion() below and in getProperty(ReservedFormatProperty) -- both
    // typed-read overloads resolve version from the sample, never from position or metadata. Message wording
    // follows VcfContext.newRecord's and VcfWriter.writeLine's (same reference-identity rationale: two
    // equal-valued contexts still represent two different operations with different warnings sinks).
    if (position.getContext() != m_context) {
      throw new VcfFormatException("getProperty() was given a position created under a different VcfContext " +
          "than this sample; the read would have no unambiguous version or warnings destination");
    }
    if (metadata.getContext() != m_context) {
      throw new VcfFormatException("getProperty() was given metadata created under a different VcfContext " +
          "than this sample; the read would have no unambiguous version or warnings destination");
    }
    String rawValue = getProperty(key);
    if (rawValue == null) {
      return null;
    }
    // the whole field is the missing-value sentinel, not a real value with an inspectable count -- comparing 1
    // against Number's expected cardinality here would be a false positive (e.g. a record that legitimately drops
    // a trailing FORMAT sub-field gets '.'-filled by the parser, and a Number=G key like GL would otherwise warn
    // "has 1 value(s), but Number=G requires 3"). VcfWriter.validateFormatValue has the identical early return,
    // and InternalUtils.convertProperty(Class<?>, ...) likewise treats "." as absent.
    if (rawValue.equals(".")) {
      return null;
    }
    // read once and reused by every version-gated call below
    VcfVersion version = m_context.getVersion();
    // version-aware: GLE stops being a reserved key at VCFv4.3, so from there it takes the non-reserved branch
    // below (validated against its own ##FORMAT declaration, or the standard "no metadata declaration" warning
    // when undeclared) instead of this library's reserved canonical Number/Type
    ReservedFormatProperty reserved = ReservedFormatProperty.fromId(key, version);
    FormatMetadata format = null;
    String number;
    boolean isList;
    if (reserved != null) {
      // for a reserved key, always use this library's own hardcoded canonical Number/Type, regardless of whether
      // the file's header actually declares ##FORMAT=<ID=...> for it. This is intentional continuity with
      // getProperty(ReservedFormatProperty), which has never consulted VcfMetadata either. VcfWriter's write path
      // now falls back to the same reserved canonical values when there's no declaration at all, matching this
      // method -- the one remaining, deliberate divergence is a reserved key declared with a non-canonical
      // Number/Type: this method still ignores the declaration and uses the canonical value, while the writer's
      // validateBeforeWrite() validates against what was actually declared, so the two can warn in opposite
      // directions for that specific (declared-but-non-canonical) case.
      // version-aware: CNL is the one reserved key whose expected Number differs by version -- see its own
      // comment in ReservedFormatProperty for the citations
      number = reserved.getExpectedNumber(version);
      isList = reserved.isList();
    } else {
      format = metadata.getFormats().get(key);
      if (format == null) {
        m_warnings.warn("FORMAT {} has no metadata declaration; cannot determine its Number/Type", key);
        return null;
      }
      number = format.getNumber();
      if (number == null) {
        // a ##FORMAT declaration missing its own Number attribute: Number is spec-required for INFO
        // (VCFv4.2.tex line 66, "first four keys are required"), and FORMAT inherits this verbatim
        // (VCFv4.2.tex line 97: "this field is otherwise defined precisely as the INFO field"); VCFv4.4.tex
        // line 187/VCFv4.5.tex line 185 later restate the requirement directly for FORMAT, without the
        // inheritance hop. BaseMetadata.checkNumberProperty already warned about this when the declaration
        // was parsed, so it isn't re-warned here. bcftools 1.23.1 treats a missing Number exactly like an
        // explicit Number=. declaration ("A FORMAT field has no Number defined. Assuming '.'"), uniformly
        // regardless of Type -- confirmed empirically: for a Type=String field with no Number and value
        // "a,b,c", `bcftools query -f '[%KEY{0} | %KEY{1} | %KEY{2}]'` returns three separate list elements
        // ("a | b | c"), not one opaque scalar string. Substituting "." here matches that. Every
        // undeclared-Number value becomes list-shaped this way, not just a genuinely multi-valued one (a
        // single value like "5" now returns a one-element list, not a bare scalar).
        // InternalUtils.getExpectedCardinality already treats null and "." identically (both skip the
        // cardinality check, since a wide-open Number has nothing to compare against), so this changes only
        // the list-vs-scalar shape decision below, never the cardinality-warning behavior.
        number = ".";
      }
      // Number=1 means a single value and any other integer means that many values (VCFv4.3.tex line 158: "if the
      // INFO field contains a single number, then this value must be 1; if the INFO field describes a pair of
      // numbers, then this value must be 2"), which FORMAT inherits verbatim (line 190: FORMAT's fields are
      // "otherwise defined precisely as the INFO field"). Number=0 is spec-tied exclusively to Type=Flag (line
      // 171), a type FormatType doesn't have, so it can never legitimately appear on a real ##FORMAT declaration
      // -- treating it as scalar here is a defensive default for non-compliant input, not a real case.
      isList = !number.equals("0") && !number.equals("1");
    }
    String[] pieces = InternalUtils.splitFormatValue(key, rawValue, version);
    if (!InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck(key, version)) {
      Integer ploidy = InternalUtils.getPloidy(position, getProperty("GT"), "FORMAT " + key, m_warnings);
      // GL/PL/GP/PP's own spec-stated "diploidy is assumed" default when GT is absent -- see
      // InternalUtils.resolvePloidyForCardinalityCheck's own comment for the full citation
      ploidy = InternalUtils.resolvePloidyForCardinalityCheck(key, ploidy);
      InternalUtils.warnIfWrongCardinality("FORMAT " + key, number, pieces.length, position.getAltBases().size(),
          ploidy, m_warnings);
    }
    // more than one comma-piece for a scalar (isList=false) key is a cardinality violation already warned about
    // above -- convert only the first piece (the same "keep the first, warn about the rest" convention this
    // library already uses for a duplicate metadata ID and a duplicate INFO key), rather than passing the whole
    // multi-piece value through as one opaque element, which would silently swallow a String-typed key's extra
    // pieces or crash a numeric/Boolean-typed one. number is never null by this point: the reserved branch above
    // always sets a real value, and the non-reserved branch just substituted "." for an absent declaration.
    String valueToConvert = (!isList && pieces.length > 1) ? pieces[0] : rawValue;
    if (reserved != null) {
      return InternalUtils.convertProperty(reserved, valueToConvert, version, m_warnings);
    }
    FormatType formatType = format.getType();
    Class<?> type = formatType == null ? String.class : InternalUtils.toJavaType(formatType);
    return InternalUtils.convertProperty(type, valueToConvert, isList, m_warnings);
  }

  /**
   * Interprets this sample's {@code GT} value against {@code position}'s alleles, resolving each allele index to
   * its actual REF/ALT base string and preserving per-boundary phasing identity ({@code |} vs {@code /}).
   *
   * @return {@code null} if {@code GT} is absent or empty, or is entirely missing (e.g. {@code "."} or
   * {@code "./."})
   * @throws VcfFormatException if an allele index is not a number, or is out of range (0..{@code
   * position.getAltBases().size()}, inclusive)
   */
  public @Nullable Genotype getGenotype(VcfPosition position) {
    // matches getProperty(VcfPosition, VcfMetadata, String)'s and VcfPosition.getInfo(ReservedInfoProperty)'s
    // null-tolerant contract: a null lookup key/position means "not present", not a crash
    //
    // Deliberately NOT subject to getProperty(VcfPosition, VcfMetadata, String)'s reference-identity check
    // (VALIDATION_PHILOSOPHY.md's "How the declared version is dispatched" / "One context per operation"):
    // that check exists because a mismatched context leaves no unambiguous resolved version or warnings
    // destination to use. Neither applies here -- this method has no version-gated behavior (it only walks
    // GT's separators and resolves allele indexes against position's REF/ALT list, which is the same at every
    // version), and it warns exclusively through this sample's own m_warnings, never through position's. So a
    // foreign-context position is accepted on purpose; do not add the check here to "match" getProperty.
    if (position == null) {
      return null;
    }
    String genotype = getProperty(ReservedFormatProperty.Genotype);
    if (genotype == null || genotype.isEmpty()) {
      return null;
    }
    // Scanned manually (not String.split("[\\|/]", -1)) so each separator's identity ('|' vs '/') is captured per
    // allele boundary -- split() discards which delimiter matched, which previously collapsed mixed phasing (e.g.
    // "0|1/2") into one whole-genotype boolean and silently corrupted it on toString(). The manual scan still
    // preserves a trailing empty allele (e.g. a trailing '/', which should have been '.') the same way split's -1
    // limit did, rather than silently dropping it -- dropping it would silently change the call's ploidy. VCF does
    // not allow zero-length fields. See EMPTY_FIELD_HANDLING.md.
    int separatorCount = 0;
    for (int i = 0; i < genotype.length(); i++) {
      char c = genotype.charAt(i);
      if (c == '|' || c == '/') {
        separatorCount++;
      }
    }
    List<String> bases = new ArrayList<>(separatorCount + 1);
    List<Boolean> phasedBoundaries = new ArrayList<>(separatorCount);
    int start = 0;
    for (int i = 0; i < genotype.length(); i++) {
      char c = genotype.charAt(i);
      if (c == '|' || c == '/') {
        bases.add(genotype.substring(start, i));
        phasedBoundaries.add(c == '|');
        start = i + 1;
      }
    }
    bases.add(genotype.substring(start));

    List<String> alleles = new ArrayList<>(bases.size());
    boolean allMissing = true;
    for (String base : bases) {
      if (base.isEmpty()) {
        m_warnings.warn("GT \"{}\" contains an empty allele (VCF does not allow zero-length fields); treating it " +
            "as the missing value '.'", genotype);
      }
      if (base.isEmpty() || base.equals(".")) {
        // a missing allele (".") is kept as-is rather than parsed as an index
        alleles.add(".");
      } else {
        allMissing = false;
        alleles.add(position.getAllele(parseAlleleIndex(base, position)));
      }
    }
    // a fully missing call (e.g. "." or "./.") has no genotype
    return allMissing ? null : new Genotype(alleles, phasedBoundaries);
  }

  /**
   * Parses a GT allele index, converting a malformed or out-of-range value into {@link VcfFormatException} rather than
   * letting {@link NumberFormatException} or {@link IndexOutOfBoundsException} leak out of {@link #getGenotype}.
   */
  private static int parseAlleleIndex(String base, VcfPosition position) {
    int index;
    try {
      index = Integer.parseInt(base);
    } catch (NumberFormatException e) {
      throw new VcfFormatException("Allele index \"" + base + "\" is not a number");
    }
    if (index < 0 || index > position.getAltBases().size()) {
      throw new VcfFormatException("Allele index " + index + " is out of range: should be between 0 and " +
          position.getAltBases().size() + ", inclusive");
    }
    return index;
  }

  @Immutable
  public static class Genotype {
    private final List<String> m_alleles;
    private final List<Boolean> m_phasedBoundaries;
    private final boolean m_isPhased;

    public Genotype(List<String> alleles, boolean isPhased) {
      // requireNonNullAlleles() must run as part of this this(...) call's own argument evaluation (a constructor
      // delegating via this(...) cannot have a preceding statement) -- Java evaluates arguments left to right, so
      // this throws before alleles.size() below is ever reached if alleles is null
      this(requireNonNullAlleles(alleles), Collections.nCopies(Math.max(0, alleles.size() - 1), isPhased));
    }

    private static List<String> requireNonNullAlleles(List<String> alleles) {
      if (alleles == null) {
        throw new VcfFormatException("alleles is null");
      }
      return alleles;
    }

    /**
     * @param phasedBoundaries one entry per allele boundary (size {@code alleles.size() - 1}); {@code true} if
     * that boundary is phased ({@code |}), {@code false} if unphased ({@code /})
     * @throws IllegalArgumentException if {@code phasedBoundaries}'s size does not match {@code alleles}'s
     * @throws VcfFormatException if {@code alleles} or {@code phasedBoundaries} is {@code null} or contains a
     * null entry
     */
    public Genotype(List<String> alleles, List<Boolean> phasedBoundaries) {
      requireNonNullAlleles(alleles);
      if (phasedBoundaries == null) {
        throw new VcfFormatException("phasedBoundaries is null");
      }
      int expected = Math.max(0, alleles.size() - 1);
      if (phasedBoundaries.size() != expected) {
        throw new IllegalArgumentException("phasedBoundaries must have " + expected + " element(s) for " +
            alleles.size() + " allele(s), got " + phasedBoundaries.size());
      }
      // List.copyOf throws a raw, message-less NullPointerException on a null element; check explicitly first so
      // this VCF-content parameter fails the same way VcfPosition.checkAltBases/checkFormat's own null-element
      // checks do, per AGENTS.md's Public API Null-Safety Policy
      for (String allele : alleles) {
        if (allele == null) {
          throw new VcfFormatException("alleles contains a null entry");
        }
      }
      for (Boolean boundary : phasedBoundaries) {
        if (boundary == null) {
          throw new VcfFormatException("phasedBoundaries contains a null entry");
        }
      }
      m_alleles = List.copyOf(alleles);
      m_phasedBoundaries = List.copyOf(phasedBoundaries);
      m_isPhased = !m_phasedBoundaries.isEmpty() && m_phasedBoundaries.stream().allMatch(Boolean::booleanValue);
    }

    public List<String> getAlleles() {
      return m_alleles;
    }

    /**
     * @return one entry per allele boundary (size {@code getAlleles().size() - 1}); {@code true} if that boundary
     * is phased ({@code |}), {@code false} if unphased ({@code /})
     */
    public List<Boolean> getPhasedBoundaries() {
      return m_phasedBoundaries;
    }

    /**
     * @return {@code true} only if every allele boundary is phased; {@code false} if any boundary is unphased, or
     * there are no boundaries at all (a single-allele genotype)
     */
    public boolean isPhased() {
      return m_isPhased;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < m_alleles.size(); i++) {
        if (i > 0) {
          sb.append(m_phasedBoundaries.get(i - 1) ? '|' : '/');
        }
        sb.append(m_alleles.get(i));
      }
      return sb.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Genotype genotype = (Genotype) o;
      return Objects.equals(m_alleles, genotype.getAlleles()) &&
          Objects.equals(m_phasedBoundaries, genotype.getPhasedBoundaries());
    }

    @Override
    public int hashCode() {
      return Objects.hash(m_alleles, m_phasedBoundaries);
    }
  }

  public boolean containsProperty(@Nullable String key) {
    // Property keys can never actually be null in this class (checkNoStructuralDelimiter rejects a null
    // key in every constructor; VcfSample has no mutation route at all since Task 9 froze it), so a null
    // argument is trivially "not present" -- the same null-tolerant-predicate shape as
    // InternalUtils.isReservedMetadataKey and VcfMetadata.getColumnIndex()/getSampleIndex(), not a caller
    // contract violation worth throwing over.
    if (key == null) {
      return false;
    }
    if (m_properties != null) {
      return m_properties.containsKey(key);
    }
    List<String> keys = m_keys;
    assert keys != null;
    return keys.contains(key);
  }

  /**
   * @return whether this sample carries a value for the <em>reserved</em> property {@code key}. Under VCFv4.3 and
   * later this always returns {@code false} for
   * {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE}, with a warning: VCFv4.3 removed
   * that key (VCFv4.3.tex line 2168), so the reserved property being asked about does not exist, even if this
   * sample does carry a custom key literally named {@code GLE}. Use {@link #containsProperty(String)} to ask that
   * other question. Warned rather than silent because a bare {@code false} is indistinguishable from "key absent"
   * and would send a caller hunting for a key that is actually there; the noise is bounded, since it fires only
   * when this specific constant is passed under 4.3+, which is always a mistake. Unchanged under VCFv4.2 and
   * earlier.
   */
  public boolean containsProperty(ReservedFormatProperty key) {
    // null-tolerant, matching containsProperty(String)'s own documented contract above
    if (key == null) {
      return false;
    }
    // A predicate never converts, so this cannot ride on InternalUtils.convertProperty's identical GLE guard the
    // way getProperty(ReservedFormatProperty) does; it needs its own.
    if (key == ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy) {
      VcfVersion version = m_context.getVersion();
      if (version.isAtLeast(VcfVersion.V4_3)) {
        m_warnings.warn("FORMAT GLE is not a reserved key under VCF {} (VCFv4.3 removed it); answering false -- " +
            "use containsProperty(String) to ask whether a custom key named GLE is present", version.getId());
        return false;
      }
    }
    return containsProperty(key.getId());
  }

  /**
   * @return An order-preserving, unmodifiable view of the property keys. A mutation attempt directly on the
   * returned set (e.g. {@link Set#remove}) throws {@link UnsupportedOperationException} rather than silently
   * affecting this instance. Memoized: the same instance is returned on every call, allocated once on first
   * access -- {@code VcfWriter} calls this once per sample per record.
   */
  public Set<String> getPropertyKeys() {
    Set<String> view = m_propertyKeysView;
    if (view == null) {
      // LinkedHashMap.keySet() returns a LinkedKeySet, which has guaranteed order; Collections.unmodifiableSet
      // preserves that ordering since it's a thin wrapper, not a copy
      view = Collections.unmodifiableSet(properties().keySet());
      m_propertyKeysView = view;
    }
    return view;
  }

  /**
   * @return An unmodifiable view of the property entries: unlike {@link Collections#unmodifiableSet}'s naive
   * behavior on a plain map's own {@code entrySet()}, an entry obtained from this set also rejects
   * {@link Map.Entry#setValue} with {@link UnsupportedOperationException} (this is standard, and has been since
   * Java's own {@code Collections.unmodifiableMap} entrySet() fix), rather than exposing a back door that mutates
   * this instance despite the set itself refusing structural changes. Memoized: the same instance is returned on
   * every call, allocated once on first access.
   */
  public Set<Map.Entry<String, String>> propertyEntrySet() {
    Set<Map.Entry<String, String>> view = m_propertyEntrySetView;
    if (view == null) {
      view = Collections.unmodifiableMap(properties()).entrySet();
      m_propertyEntrySetView = view;
    }
    return view;
  }


  /**
   * A copy-and-modify builder for {@link VcfSample}, obtained via {@link #toBuilder()}. Every setter defensively
   * copies its argument on the way in -- a caller mutating a list/map handed to a setter, or one obtained from
   * the source sample via {@link #toBuilder()}, must never be able to reach back into the sample {@link #build()}
   * produces.
   */
  public static class Builder {
    private @Nullable List<String> m_keys;
    private @Nullable List<String> m_values;
    private @Nullable LinkedHashMap<String, String> m_properties;
    private final VcfContext m_context;

    Builder(VcfContext context) {
      m_context = Preconditions.checkNotNull(context, "context must not be null");
    }

    /**
     * Defensively copies {@code keys}; see this class's own Javadoc for why. Clears any property map set via
     * {@link #properties}: the parallel-list and property-map representations are mutually exclusive, matching
     * {@link VcfSample}'s own internal invariant.
     */
    public Builder keys(@Nullable List<String> keys) {
      m_keys = keys == null ? null : new ArrayList<>(keys);
      m_properties = null;
      return this;
    }

    /**
     * Defensively copies {@code values}; see this class's own Javadoc for why. Clears any property map set via
     * {@link #properties}, for the same reason as {@link #keys}.
     */
    public Builder values(@Nullable List<String> values) {
      m_values = values == null ? null : new ArrayList<>(values);
      m_properties = null;
      return this;
    }

    /**
     * Defensively copies {@code properties}; see this class's own Javadoc for why. Clears any keys/values set
     * via {@link #keys}/{@link #values}, for the same reason as {@link #keys}.
     *
     * @throws VcfFormatException if {@code properties} is null
     */
    public Builder properties(LinkedHashMap<String, String> properties) {
      // properties is typed non-@Nullable, but nothing enforces that at runtime; reject immediately per
      // AGENTS.md's Public API Null-Safety Policy, matching VcfContext.newSample(LinkedHashMap)'s identical check
      if (properties == null) {
        throw new VcfFormatException("Sample properties map is null");
      }
      m_properties = new LinkedHashMap<>(properties);
      m_keys = null;
      m_values = null;
      return this;
    }

    /**
     * Sets a single property -- the natural replacement for {@code VcfSample}'s former {@code putProperty(String,
     * String)}, now that {@code VcfSample} itself has no mutation routes. Switches this builder to the property-map
     * representation (like {@link #properties}) if it isn't already: on first call, materializes any parallel-list
     * representation set via {@link #keys}/{@link #values} into a fresh map (keeping the first value for a
     * duplicate key, matching {@code VcfSample}'s own materialization convention, minus the warning -- this builder
     * has no warnings sink of its own to emit one through), then reuses that same map for every subsequent
     * {@code property}/{@link #properties} call. Unlike {@link #keys}/{@link #values}/{@link #properties}, this does
     * not itself run {@link VcfSample#checkNoStructuralDelimiter} on {@code key}/{@code value} -- {@link #build()}
     * always re-validates the whole property map anyway (see its own Javadoc), so validating here too would be
     * redundant.
     *
     * @throws VcfFormatException if {@code key} is null
     */
    public Builder property(String key, @Nullable String value) {
      // key is typed non-@Nullable, but nothing enforces that at runtime; reject immediately per AGENTS.md's
      // Public API Null-Safety Policy, matching the former putProperty(String, ...)'s identical check
      if (key == null) {
        throw new VcfFormatException("Sample property key is null");
      }
      if (m_properties == null) {
        LinkedHashMap<String, String> materialized = new LinkedHashMap<>();
        List<String> keys = m_keys;
        List<String> values = m_values;
        if (keys != null && values != null) {
          for (int i = 0; i < keys.size(); i++) {
            materialized.putIfAbsent(keys.get(i), values.get(i));
          }
        }
        m_properties = materialized;
        m_keys = null;
        m_values = null;
      }
      m_properties.put(key, value);
      return this;
    }

    /**
     * @return a new {@link VcfSample}, delegating to the validating, copying {@link VcfContext#newSample}
     * overloads. Unlike {@link VcfPosition.Builder#build()}, this cannot delegate to an ownership-taking-and-
     * validating factory: {@code VcfContext} has none for {@link VcfSample} -- only the copying-and-validating
     * {@code newSample} overloads used here, and the skip-both-copy-and-validation {@code newTrustedSample},
     * which would silently accept content this builder's own setters never validated. The copy this performs is
     * therefore redundant with the defensive copy this builder's own setters already made on the way in, trading
     * one extra allocation for keeping caller-supplied content validated.
     *
     * @throws VcfFormatException if exactly one of {@link #keys}/{@link #values} was called (with no
     * {@link #properties} call to switch representation back) -- named explicitly here, rather than letting it
     * surface as {@code VcfContext.newSample}'s more generic "keys is null but values is not" (or vice versa),
     * since the actual mistake is specific to this builder: {@code keys}/{@code values} must be set together
     */
    public VcfSample build() {
      if (m_properties != null) {
        return m_context.newSample(m_properties);
      }
      if (m_keys != null && m_values == null) {
        throw new VcfFormatException("Builder.keys() was called without a matching Builder.values() call (and no " +
            "Builder.properties() to switch representation back) -- both must be set together");
      }
      if (m_values != null && m_keys == null) {
        throw new VcfFormatException("Builder.values() was called without a matching Builder.keys() call (and no " +
            "Builder.properties() to switch representation back) -- both must be set together");
      }
      return m_context.newSample(m_keys, m_values);
    }
  }
}

package org.pharmgkb.parser.vcf.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * This class contains sample data for a VCF position line.
 *
 * <p>To avoid allocating a map for every sample on every line (the common parse-then-read path), the FORMAT keys and
 * their values are kept as parallel lists and read by a short linear scan. A {@link LinkedHashMap} is materialized only
 * when the sample is mutated or its keys/entries are iterated.</p>
 *
 * @author Mark Woon
 */
public class VcfSample {

  // Lean representation used on the parse path; both null once m_properties has been materialized. VcfParser may
  // pass the same List instance as m_keys to multiple VcfSample objects for the same data line (one independent
  // copy per line, not per sample) -- safe only because no method in this class ever mutates m_keys in place; a
  // future change that did so would silently corrupt every sample sharing that line's copy.
  private @Nullable List<String> m_keys;
  private @Nullable List<String> m_values;
  // Materialized on demand (mutation / key or entry iteration), or supplied directly via the map constructor.
  private @Nullable LinkedHashMap<String, String> m_properties;
  /** See {@link org.pharmgkb.parser.vcf.model.BaseMetadata#m_warnings}'s identical Javadoc -- same pattern, this
   * class just isn't a {@code BaseMetadata} subclass so it needs its own copy of the field and setter. */
  private VcfWarnings m_warnings = VcfWarnings.DEFAULT;

  public void setWarningsSink(VcfWarnings warnings) {
    m_warnings = Preconditions.checkNotNull(warnings, "warnings must not be null");
  }

  /**
   * Takes ownership of {@code keys} and {@code values}: this constructor does not defensively copy either list,
   * to avoid an allocation on this library's hottest path (once per sample per data line -- see this class's own
   * Javadoc for the parse-then-read performance rationale). Callers must not mutate either list after
   * construction. See also {@link #VcfSample(LinkedHashMap)}, which has the same no-copy contract.
   */
  public VcfSample(@Nullable List<String> keys, @Nullable List<String> values) {
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
    for (int x = 0; x < keys.size(); x++) {
      checkNoStructuralDelimiter(keys.get(x), values.get(x));
    }
    m_keys = keys;
    m_values = values;
  }

  /**
   * Takes ownership of {@code properties}: like {@link #VcfSample(List, List)}, this does not defensively copy
   * it. Callers must not mutate the map after construction.
   */
  public VcfSample(LinkedHashMap<String, String> properties) {
    // properties is typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting
    // m_properties.entrySet() below throw a raw NullPointerException. The sibling VcfSample(List, List)
    // constructor already rejects null keys/values cleanly; this constructor's own properties parameter was
    // simply missed.
    if (properties == null) {
      throw new VcfFormatException("Sample properties map is null");
    }
    m_properties = properties;
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      checkNoStructuralDelimiter(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Rejects a key or value containing a line terminator, a colon, or a tab: a parsed key/value can never contain
   * these (they were already split on to arrive at this key/value), but one set directly via {@link #putProperty} or
   * {@link #propertyEntrySet()} could; reject them so the writer's output remains re-parseable (a colon would add a
   * spurious FORMAT subfield, and a tab would add a spurious sample column).
   * <p>
   * {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE} (VCFv4.1/4.2's
   * genotype-likelihoods-of-heterogeneous-ploidy key; removed from the spec in VCFv4.3+ and confirmed absent from
   * both the VCFv4.3 and VCFv4.4 specs) is exempted from the colon check on its <em>value</em> only: the spec's own
   * example for this key (e.g. {@code 0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53}) is one opaque String
   * that uses colons internally as part of its own genotype:likelihood encoding, not a delimiter between independent
   * FORMAT sub-fields. There is no VCF version where a key literally named {@code GLE} should still be
   * colon-rejected: either it means this VCFv4.1/4.2 encoding (which requires colons), or -- since {@code GLE} isn't
   * reserved past VCFv4.2 -- it's just a made-up custom key, free to contain whatever its author wants, same as any
   * other non-reserved key today. This exemption does not apply to the key itself (a colon in the key name is always
   * rejected) or to any other key's value.
   */
  private static void checkNoStructuralDelimiter(String key, @Nullable String value) {
    if (key == null) {
      throw new VcfFormatException("Sample property key is null");
    }
    InternalUtils.checkNoLineTerminator(key, value);
    if (key.contains(":") || key.contains("\t")) {
      throw new VcfFormatException("Sample property key \"" + key + "\" contains ':' or a tab");
    }
    boolean isGle = key.equals(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy.getId());
    if (value != null && (value.contains("\t") || (!isGle && value.contains(":")))) {
      throw new VcfFormatException("Sample property value \"" + value + "\" for key \"" + key +
          "\" contains ':' or a tab");
    }
  }

  /**
   * Re-validates the current properties after possible mutation through {@link #propertyEntrySet()}, throwing
   * {@link VcfFormatException} for a structural delimiter. An empty value is not a structural problem -- it does not
   * corrupt column/sub-field alignment, the same as an empty INFO value or an empty comma-separated FORMAT element
   * elsewhere in this library -- so it is warned about and normalized to {@code "."} (the VCF missing-value
   * sentinel) in place, rather than rejected. See {@code EMPTY_FIELD_HANDLING.md}.
   */
  public void validate() {
    for (Map.Entry<String, String> entry : properties().entrySet()) {
      checkNoStructuralDelimiter(entry.getKey(), entry.getValue());
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
    return InternalUtils.convertProperty(key, getProperty(key.getId()), m_warnings);
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
   */
  public @Nullable <T> T getProperty(VcfPosition position, VcfMetadata metadata, String key) {
    if (key == null || position == null || metadata == null) {
      return null;
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
    ReservedFormatProperty reserved = ReservedFormatProperty.fromId(key);
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
      number = reserved.getExpectedNumber();
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
    String[] pieces = InternalUtils.splitFormatValue(key, rawValue);
    if (!InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck(key)) {
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
      return InternalUtils.convertProperty(reserved, valueToConvert, m_warnings);
    }
    FormatType formatType = format.getType();
    Class<?> type = formatType == null ? String.class : InternalUtils.toJavaType(formatType);
    return InternalUtils.convertProperty(type, valueToConvert, isList, m_warnings);
  }

  public boolean containsProperty(@Nullable String key) {
    // Property keys can never actually be null in this class (checkNoStructuralDelimiter rejects a null
    // key in every constructor/mutation path), so a null argument is trivially "not present" -- the same
    // null-tolerant-predicate shape as InternalUtils.isReservedMetadataKey and
    // VcfMetadata.getColumnIndex()/getSampleIndex(), not a caller contract violation worth throwing over.
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

  public boolean containsProperty(ReservedFormatProperty key) {
    // null-tolerant, matching containsProperty(String)'s own documented contract above
    return key != null && containsProperty(key.getId());
  }

  public void putProperty(String key, @Nullable String value) {
    checkNoStructuralDelimiter(key, value);
    properties().put(key, value);
  }

  public void putProperty(ReservedFormatProperty key, @Nullable String value) {
    // unlike the read/query methods, putProperty(String, ...) already throws for a null key (via
    // checkNoStructuralDelimiter) -- pass through so a null ReservedFormatProperty key gets the identical
    // VcfFormatException instead of NPEing here at key.getId()
    putProperty(key == null ? null : key.getId(), value);
  }

  public void removeProperty(String key) {
    properties().remove(key);
  }

  public void removeProperty(ReservedFormatProperty key) {
    // null-tolerant no-op, matching removeProperty(String)'s own already-null-tolerant Map.remove(null) behavior
    if (key != null) {
      properties().remove(key.getId());
    }
  }

  public void clearProperties() {
    properties().clear();
  }

  /**
   * @return A set of the property keys, which has guaranteed order
   */
  public Set<String> getPropertyKeys() {
    // LinkedHashMap.keySet() returns a LinkedKeySet, which has guaranteed order
    return properties().keySet();
  }

  /**
   * @return The backing, mutable property entries. Unlike {@link #putProperty}, mutating an entry's value directly
   * (e.g. via {@link Map.Entry#setValue}) does not check for a line terminator, colon, or tab.
   */
  public Set<Map.Entry<String, String>> propertyEntrySet() {
    return properties().entrySet();
  }
}

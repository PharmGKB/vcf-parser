package org.pharmgkb.parser.vcf.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * The properties <em>are case-sensitive</em>.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadata {

  private final Map<String, String> m_properties;
  private final String m_label;

  /**
   * @param properties this declaration's raw key-value properties. Copied defensively into a new
   * {@link LinkedHashMap} -- the caller's own map is never retained by reference, so mutating it after
   * construction has no effect on this instance. The copy also fixes this instance's iteration order at
   * construction time, but only to whatever order {@code properties} itself iterated in: {@link #checkAttributeOrder()}
   * and {@link #validate()} rely on that order matching declaration order, which is only guaranteed if
   * {@code properties} was itself order-preserving (e.g. a {@link LinkedHashMap}, as {@code VcfParser}'s own
   * real parsing path always constructs) -- passing a {@code HashMap} or {@code Map.of(...)} still risks a
   * meaningless (nondeterministic or simply wrong) attribute-order warning, since this copy cannot recover an
   * order that was never preserved in the first place.
   */
  public BaseMetadata(String label, Map<String, String> properties) {
    m_label = label;
    m_properties = new LinkedHashMap<>(properties);
    validateProperties();
  }

  /**
   * @return The label identifying this declaration's VCF keyword (e.g. "INFO", "FILTER") in warning messages.
   * Set once at construction; see subclass constructors for how each declaration kind supplies it.
   */
  protected final String getLabel() {
    return m_label;
  }

  /**
   * Re-validates the current properties after possible mutation through {@link #getPropertiesRaw()}.
   */
  public void validate() {
    validateProperties();
  }

  private void validateProperties() {
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      if (entry.getKey() == null) {
        throw new VcfFormatException("Metadata contains a null property name");
      }
      VcfUtils.checkNoLineTerminator(entry.getKey(), entry.getValue());
    }
  }

  @Nullable
  public String getPropertyUnquoted(String key) {
    String got = m_properties.get(key);
    if (got == null) {
      return null;
    }
    return VcfUtils.unquote(got);
  }

  @Nullable
  public String getPropertyRaw(String key) {
    return m_properties.get(key);
  }

  /**
   * @return A new map of every property, unquoted -- the map-returning complement to
   * {@link #getPropertyUnquoted(String)}.
   */
  public Map<String, String> getPropertiesUnquoted() {
    Map<String, String> map = new HashMap<>();
    for (Map.Entry<String, String>  entry : m_properties.entrySet()) {
      map.put(entry.getKey(), VcfUtils.unquote(entry.getValue()));
    }
    return map;
  }

  /**
   * @return The backing, mutable properties map. Unlike {@link #putPropertyRaw} and {@link #putAndQuoteProperty},
   * mutations made directly through this map do not run {@link VcfUtils#checkNoLineTerminator}.
   */
  public Map<String, String> getPropertiesRaw() {
    return m_properties;
  }

  public Set<String> getPropertyKeys() {
    return m_properties.keySet();
  }

  public void putAndQuoteProperty(String key, String value) {
    if (value == null) {
      throw new VcfFormatException("Metadata property \"" + key + "\" has a null value");
    }
    VcfUtils.checkNoLineTerminator(key, value);
    m_properties.put(key, VcfUtils.quote(value));
  }

  public void putPropertyRaw(String key, String value) {
    if (value == null) {
      throw new VcfFormatException("Metadata property \"" + key + "\" has a null value");
    }
    VcfUtils.checkNoLineTerminator(key, value);
    m_properties.put(key, value);
  }

  /**
   * Should be used only for base classes.
   * Warns (via {@link VcfWarnings#warn}) if this metadata contains a property key not in the array passed. The
   * warning message embeds this declaration's {@link #getLabel() label} so it is attributed to the correct VCF
   * declaration kind -- necessary because some concrete classes (e.g. {@code IdDescriptionMetadata}) are shared
   * by multiple declaration kinds with no other distinguishing field.
   * @param names An array of permitted property keys
   */
  protected void ensureNoExtras(String... names) {
    Set<String> set = new HashSet<>();
    Collections.addAll(set, names);
    m_properties.keySet().stream().filter(property -> !set.contains(property)).forEach(property -> {
      VcfWarnings.warn("{} metadata line contains unexpected property {}", getLabel(), property);
    });
  }

  /**
   * Warns (via {@link VcfWarnings#warn}) if {@code value} is null, i.e. the required property
   * {@code propertyName} is absent. The warning message embeds this declaration's {@link #getLabel() label} so
   * it is attributed to the correct VCF declaration kind -- necessary because some concrete classes (e.g.
   * {@code IdDescriptionMetadata}) are shared by multiple declaration kinds with no other distinguishing field.
   */
  protected void warnIfMissing(String propertyName, @Nullable String value) {
    if (value == null) {
      VcfWarnings.warn("{} required metadata property \"{}\" is missing", getLabel(), propertyName);
    }
  }

  /**
   * Validates a VCF {@code Number} property value, warning (via {@link VcfWarnings#warn}) if it is missing or does
   * not match {@link VcfUtils#NUMBER_PATTERN}. Shared by the metadata types ({@code INFO}, {@code FORMAT}) that
   * declare a {@code Number} field with identical semantics. The warning message embeds this declaration's
   * {@link #getLabel() label} so it is attributed to the correct VCF declaration kind.
   */
  protected void checkNumberProperty(@Nullable String number) {
    warnIfMissing("Number", number);
    if (number != null && !VcfUtils.NUMBER_PATTERN.matcher(number).matches()) {
      VcfWarnings.warn("{} Number is not a valid VCF number: '{}'", getLabel(), number);
    }
  }

  private static final List<String> CANONICAL_ATTRIBUTE_ORDER =
      List.of("ID", "Number", "Type", "Description", "Source", "Version");

  /**
   * Warns (via {@link VcfWarnings#warn}) if this declaration's attributes, restricted to the well-known VCF metadata
   * keys ID/Number/Type/Description/Source/Version, are not declared in that canonical relative order. A key this
   * declaration kind doesn't use, or any other extra/unrecognized key, is ignored -- order is checked only among
   * whichever of those six keys are actually present, so e.g. an ALT line's Description immediately after ID, with
   * no Number/Type/Source/Version present at all, triggers no warning. The warning message embeds this
   * declaration's {@link #getLabel() label} so it is attributed to the correct VCF declaration kind.
   * VCFv4.2.tex line 69 (##INFO), line 94 (##FORMAT), line 103 (##ALT), line 87 (##FILTER): every canonical template
   * and worked example in the spec uses this relative order; test/vcf/{4.1,4.2}/failed/failed_meta_{info_003,
   * format_003}.vcf and failed_meta_alt_004.vcf (##CauseOfFailure=Incorrect order of ALT/INFO/FORMAT fields)
   * establish that a declaration violating it is invalid, resolving the spec text's own silence on whether the
   * order shown is required.
   *
   * @throws VcfFormatException If a property name is {@code null}
   */
  public void checkAttributeOrder() {
    List<String> actual = new ArrayList<>();
    for (String key : getPropertyKeys()) {
      if (key == null) {
        throw new VcfFormatException("Metadata contains a null property name");
      }
      if (CANONICAL_ATTRIBUTE_ORDER.contains(key)) {
        actual.add(key);
      }
    }
    List<String> expected = new ArrayList<>(CANONICAL_ATTRIBUTE_ORDER);
    expected.retainAll(actual);
    if (!actual.equals(expected)) {
      VcfWarnings.warn("{} metadata attributes declared out of canonical order: {} (expected order: {})",
          getLabel(), actual, expected);
    }
  }

}

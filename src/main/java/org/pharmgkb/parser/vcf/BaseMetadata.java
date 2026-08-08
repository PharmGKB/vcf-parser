package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.base.Preconditions;
import org.jspecify.annotations.Nullable;


/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * The properties <em>are case-sensitive</em>.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadata {

  private final Map<String, String> m_properties;
  private final String m_label;
  /**
   * Where this instance's own lazy/re-validation warnings go. Defaults to {@link VcfWarnings#DEFAULT} (the
   * shared SLF4J logger); {@link #setWarningsSink} lets {@code VcfWriter}/{@code VcfParser} explicitly claim
   * this instance for the duration of a specific parse or write, and a lenient (Map-based) subclass constructor
   * sets it once at construction time via the same method. This is a live, mutable field -- the same category
   * of caller-mutable state already exposed by e.g. {@link #getPropertiesRaw()} -- not thread-local or ambient.
   */
  protected VcfWarnings m_warnings = VcfWarnings.DEFAULT;

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
    // properties is typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting
    // new LinkedHashMap<>(properties) below throw a raw NullPointerException. Reachable through every
    // Map-taking constructor of every subclass (PedigreeMetadata, ContigMetadata, IdDescriptionMetadata,
    // InfoMetadata, FormatMetadata).
    if (properties == null) {
      throw new VcfFormatException("Metadata properties map is null");
    }
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
   * Redirects this instance's future lazy/re-validation warnings to {@code warnings}, replacing whatever was
   * previously configured (the constructor's own choice, or an earlier call to this method). Used by
   * {@code VcfWriter}/{@code VcfParser} to claim an object for the duration of a specific write or parse, even
   * if it was originally built (or last used) under a different destination.
   */
  public void setWarningsSink(VcfWarnings warnings) {
    m_warnings = Preconditions.checkNotNull(warnings, "warnings must not be null");
  }

  /**
   * Re-validates the current properties after possible mutation through {@link #getPropertiesRaw()}. ({@link
   * PedigreeMetadata} overrides {@link #getPropertiesRaw()} to be unmodifiable, so this is a no-op re-check for
   * that subclass specifically -- there is nothing for it to have mutated.)
   */
  public void validate() {
    validateProperties();
  }

  private void validateProperties() {
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      if (entry.getKey() == null) {
        throw new VcfFormatException("Metadata contains a null property name");
      }
      // putPropertyRaw/putAndQuoteProperty already reject a null value at the point of insertion; this closes
      // the same gap for a value inserted directly through getPropertiesRaw()'s live view, bypassing those put
      // methods entirely. Unreachable from real VCF parsing (InternalUtils.splitProperty always returns two
      // non-null halves or throws), but this loop already runs unconditionally at construction (via the
      // Map-based constructor every parsed declaration uses) and on every validate() re-check, so this is one
      // more check on an already-active path, not a new one.
      if (entry.getValue() == null) {
        throw new VcfFormatException("Metadata property \"" + entry.getKey() + "\" has a null value");
      }
      InternalUtils.checkNoLineTerminator(entry.getKey(), entry.getValue());
    }
  }

  @Nullable
  public String getPropertyUnquoted(String key) {
    String got = m_properties.get(key);
    if (got == null) {
      return null;
    }
    return InternalUtils.unquote(got);
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
      map.put(entry.getKey(), InternalUtils.unquote(entry.getValue()));
    }
    return map;
  }

  /**
   * @return The backing, mutable properties map. Unlike {@link #putPropertyRaw} and {@link #putAndQuoteProperty},
   * mutations made directly through this map do not run {@link InternalUtils#checkNoLineTerminator}.
   * <strong>{@link PedigreeMetadata} overrides this to return an unmodifiable view instead</strong> -- see its
   * own class javadoc for why; that subclass's properties are immutable after construction.
   */
  public Map<String, String> getPropertiesRaw() {
    return m_properties;
  }

  /**
   * @return An order-preserving, unmodifiable view of every property key. Unlike {@link #getPropertiesRaw()},
   * a mutation attempt directly on the returned set (e.g. {@link Set#remove}) throws
   * {@link UnsupportedOperationException} rather than silently affecting this instance -- a later mutation made
   * through {@link #getPropertiesRaw()}/{@link #putPropertyRaw}/{@link #putAndQuoteProperty} is, however, still
   * reflected here, since this remains a view over the live backing map, not a snapshot copy. ({@link
   * PedigreeMetadata} is the one exception: since its {@link #getPropertiesRaw()} override is itself
   * unmodifiable, there is no later mutation for this view to reflect.)
   */
  public Set<String> getPropertyKeys() {
    return Collections.unmodifiableSet(m_properties.keySet());
  }

  public void putAndQuoteProperty(String key, String value) {
    // key is typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting a null key
    // reach InternalUtils.checkNoLineTerminator(key, value) below (key.contains(...) would NPE)
    if (key == null) {
      throw new VcfFormatException("Metadata contains a null property name");
    }
    if (value == null) {
      throw new VcfFormatException("Metadata property \"" + key + "\" has a null value");
    }
    InternalUtils.checkNoLineTerminator(key, value);
    m_properties.put(key, InternalUtils.quote(value));
  }

  public void putPropertyRaw(String key, String value) {
    if (key == null) {
      throw new VcfFormatException("Metadata contains a null property name");
    }
    if (value == null) {
      throw new VcfFormatException("Metadata property \"" + key + "\" has a null value");
    }
    InternalUtils.checkNoLineTerminator(key, value);
    m_properties.put(key, value);
  }

  /**
   * Warns (via {@link VcfWarnings#warn}) if {@code value} is null, i.e. the required property
   * {@code propertyName} is absent. The warning message embeds this declaration's {@link #getLabel() label} so
   * it is attributed to the correct VCF declaration kind -- necessary because some concrete classes (e.g.
   * {@code IdDescriptionMetadata}) are shared by multiple declaration kinds with no other distinguishing field.
   */
  protected void warnIfMissing(String propertyName, @Nullable String value) {
    if (value == null) {
      m_warnings.warn("{} required metadata property \"{}\" is missing", getLabel(), propertyName);
    }
  }

  /**
   * Validates a VCF {@code Number} property value, warning (via {@link VcfWarnings#warn}) if it is missing or does
   * not match {@link InternalUtils#NUMBER_PATTERN}. Shared by the metadata types ({@code INFO}, {@code FORMAT}) that
   * declare a {@code Number} field with identical semantics. The warning message embeds this declaration's
   * {@link #getLabel() label} so it is attributed to the correct VCF declaration kind.
   */
  protected void checkNumberProperty(@Nullable String number) {
    warnIfMissing("Number", number);
    if (number != null && !InternalUtils.NUMBER_PATTERN.matcher(number).matches()) {
      m_warnings.warn("{} Number is not a valid VCF number: '{}'", getLabel(), number);
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
      m_warnings.warn("{} metadata attributes declared out of canonical order: {} (expected order: {})",
          getLabel(), actual, expected);
    }
  }

}

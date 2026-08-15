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
import org.slf4j.helpers.MessageFormatter;


/**
 * VCF metadata in the format XXX=&lt;key=value,key=value,...&gt;.
 * The properties <em>are case-sensitive</em>.
 * <p>
 * <strong>Effectively immutable through this library's public API, and not thread-safe.</strong> No public
 * method mutates a declaration's properties: {@link #getPropertiesRaw()} and {@link #getPropertyKeys()} both
 * return unmodifiable views, and the property-inserting methods ({@code putPropertyRaw}/
 * {@code putAndQuoteProperty}) are package-private, reachable only by this package's own subclass constructors,
 * which run before the instance escapes. "Effectively" rather than outright immutable for two reasons, neither
 * externally observable for a single-threaded caller: those two unmodifiable views are memoized into fields on
 * first access (an unsynchronized lazy write), and {@link InfoMetadata}/{@link FormatMetadata} recompute their
 * cached {@code Type} field inside {@link #validate()}. Because of those lazy writes, this class makes a
 * <em>value</em> guarantee, not a concurrency guarantee -- do not share an instance across threads without
 * external synchronization.
 * <p>
 * This hierarchy is {@code sealed}: every subclass is in this package and is itself {@code sealed} or
 * {@code final}, so no external subclass can override a getter to hand back mutable content and reintroduce
 * the mutability this class no longer has.
 *
 * @author Douglas Myers-Turnbull
 */
public sealed class BaseMetadata permits IdMetadata, PedigreeMetadata {

  private final Map<String, String> m_properties;
  /**
   * Memoized unmodifiable views over {@link #m_properties}. Built on first access and reused thereafter, so a
   * repeated getter call in a hot path does not allocate a fresh wrapper each time. Safe to cache even though
   * subclass constructors keep inserting into {@code m_properties} after this class's own constructor returns:
   * both are <em>views</em> over the live backing map, not snapshot copies, so they always reflect its current
   * contents. {@code m_properties} itself is never reassigned.
   */
  private @Nullable Map<String, String> m_propertiesRawView;
  private @Nullable Set<String> m_propertyKeysView;
  private final String m_label;
  private final VcfContext m_context;
  /**
   * Where this instance's own lazy/re-validation warnings go. Fixed at construction, from this declaration's
   * {@link VcfContext}'s own sink, for this instance's entire lifetime -- nothing (including a {@code
   * VcfWriter} this instance is later handed to) can re-point it afterward. See {@link VcfWarnings}'s class
   * Javadoc for the full attribution rule.
   * <p>
   * This is the warnings <em>destination</em> only. Whether a recoverable condition warns here at all or instead
   * throws is decided by {@link VcfContext#isLenient()}, never by this field -- see {@link #warnOrThrow}.
   */
  protected final VcfWarnings m_warnings;

  /**
   * Package-private, not public: nothing should instantiate the base declaration type directly. Every subclass
   * is in this package, so this is real encapsulation rather than a documentation-only restriction.
   *
   * @param context the context this declaration belongs to -- supplies both its warnings sink and its
   * strict-vs-lenient behavior. {@code VcfMetadata.Builder} owns the one instance per parse/write and threads
   * it in here.
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
  BaseMetadata(VcfContext context, String label, Map<String, String> properties) {
    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_warnings = context.getWarnings();
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
   * @return the context this declaration was created under; never {@code null}.
   * <p>
   * {@code final} so a permitted subclass cannot override it to report a different context and defeat
   * {@code VcfMetadata.Builder}'s identity check.
   */
  public final VcfContext getContext() {
    return m_context;
  }

  /**
   * Reports a recoverable, non-structural content problem: warns to {@link #m_warnings} under a lenient
   * {@link VcfContext}, throws {@link VcfFormatException} under a strict one.
   * <p>
   * This is what makes leniency come from the context rather than from which constructor shape the caller
   * reached. Before this existed, a declaration's typed {@code (id, ...)} constructor threw unconditionally
   * while its {@code Map}-based sibling warned unconditionally, so the same invalid content behaved differently
   * purely by input format. Both now route here.
   */
  protected final void warnOrThrow(String message, Object... args) {
    if (!m_context.isLenient()) {
      throw new VcfFormatException(MessageFormatter.arrayFormat(message, args).getMessage());
    }
    m_warnings.warn(message, args);
  }

  /**
   * @return The label identifying this declaration's VCF keyword (e.g. "INFO", "FILTER") in warning messages.
   * Set once at construction; see subclass constructors for how each declaration kind supplies it.
   */
  protected final String getLabel() {
    return m_label;
  }

  /**
   * Re-validates the current properties.
   * <p>
   * Every public construction path already validates once, and no public method can mutate a declaration
   * afterwards (see this class's own Javadoc), so for a declaration obtained through this library's public API
   * the property checks below are a no-op re-check. They stay because {@code VcfWriter}'s
   * {@code validateBeforeWrite()} mode calls this on every declaration it is handed, and because the
   * package-private {@link #trustedMutableProperties()} bypass can put a same-package caller's declaration into
   * a state no public route can reach. Subclasses override this to re-run their own typed checks as well.
   */
  public void validate() {
    validateProperties();
  }

  private void validateProperties() {
    for (Map.Entry<String, String> entry : m_properties.entrySet()) {
      if (entry.getKey() == null) {
        throw new VcfFormatException("Metadata contains a null property name");
      }
      // putPropertyRaw/putAndQuoteProperty already reject a null value at the point of insertion, and
      // getPropertiesRaw() is unmodifiable, so no public route can introduce one; this closes the same gap for
      // a value inserted through the package-private trustedMutableProperties() bypass. Unreachable from real
      // VCF parsing (InternalUtils.parseAttribute/tokenizeAttributeList always produce a non-null name and
      // value together, or drop the whole attribute), but this loop already runs unconditionally at
      // construction (via the Map-based constructor every parsed declaration uses) and on every validate()
      // re-check, so this is one more check on an already-active path, not a new one.
      if (entry.getValue() == null) {
        throw new VcfFormatException("Metadata property \"" + entry.getKey() + "\" has a null value");
      }
      InternalUtils.checkNoLineTerminator(entry.getKey(), entry.getValue());
    }
  }

  @Nullable
  public final String getPropertyUnquoted(String key) {
    String got = m_properties.get(key);
    if (got == null) {
      return null;
    }
    return InternalUtils.unquote(got);
  }

  @Nullable
  public final String getPropertyRaw(String key) {
    return m_properties.get(key);
  }

  /**
   * @return A new map of every property, unquoted -- the map-returning complement to
   * {@link #getPropertyUnquoted(String)}. Unlike {@link #getPropertiesRaw()}/{@link #getPropertyKeys()}, this
   * is a freshly-allocated snapshot copy rather than an unmodifiable view, so it is safe for a caller to mutate
   * and it is deliberately <em>not</em> memoized: mutating it affects only the returned copy, never this
   * declaration.
   */
  public final Map<String, String> getPropertiesUnquoted() {
    Map<String, String> map = new HashMap<>();
    for (Map.Entry<String, String>  entry : m_properties.entrySet()) {
      map.put(entry.getKey(), InternalUtils.unquote(entry.getValue()));
    }
    return map;
  }

  /**
   * @return An order-preserving, unmodifiable view of this declaration's raw properties. A mutation attempt on
   * the returned map -- including {@link Map.Entry#setValue} on one of its entries -- throws
   * {@link UnsupportedOperationException} rather than silently changing this declaration.
   */
  public final Map<String, String> getPropertiesRaw() {
    if (m_propertiesRawView == null) {
      m_propertiesRawView = Collections.unmodifiableMap(m_properties);
    }
    return m_propertiesRawView;
  }

  /**
   * @return An order-preserving, unmodifiable view of every property key. Like {@link #getPropertiesRaw()}, a
   * mutation attempt directly on the returned set (e.g. {@link Set#remove}) throws
   * {@link UnsupportedOperationException}.
   */
  public final Set<String> getPropertyKeys() {
    if (m_propertyKeysView == null) {
      m_propertyKeysView = Collections.unmodifiableSet(m_properties.keySet());
    }
    return m_propertyKeysView;
  }

  /**
   * The live, mutable backing properties map -- the one route that can still change a declaration after
   * construction, deliberately kept package-private so no caller outside this library can reach it.
   * <p>
   * This is the metadata-layer counterpart of {@code VcfContext.newPositionCopyingTrustedFields} and
   * {@code VcfContext.newTrustedSample}: a trusted, validation-skipping bypass that exists so this package's
   * own tests can still synthesize the malformed declarations {@code VcfWriter}'s defensive checks are written
   * against, now that no public API route can produce one. Unlike {@link #putPropertyRaw}/
   * {@link #putAndQuoteProperty} it runs no checks at all, so it can also introduce a null key/value or a line
   * terminator. Not for production code: nothing in {@code src/main} calls it.
   */
  final Map<String, String> trustedMutableProperties() {
    return m_properties;
  }

  /**
   * Inserts a property, quoting {@code value}. Package-private, not public: a declaration is immutable through
   * this library's public API (see this class's own Javadoc), and this exists only so a subclass constructor
   * can populate its own typed properties -- with {@link InternalUtils#checkNoLineTerminator} applied, which a
   * direct field assignment would skip -- before the instance escapes.
   * <p>
   * Not {@code final}, unlike the getters: {@link PedigreeMetadata} overrides this (and
   * {@link #putPropertyRaw}) to throw, because it has no typed constructor that needs to insert anything and
   * because {@code VcfMetadata.Builder}'s PEDIGREE duplicate-ID set would go stale if a stored entry's
   * {@code ID} could change. The hierarchy is {@code sealed} and every subclass is in this package, so leaving
   * this overridable opens nothing to an external caller.
   */
  void putAndQuoteProperty(String key, String value) {
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

  /**
   * Inserts a property verbatim. Package-private, and non-{@code final}, for the same reasons as
   * {@link #putAndQuoteProperty(String, String)} -- see its Javadoc.
   */
  void putPropertyRaw(String key, String value) {
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
      warnOrThrow("{} required metadata property \"{}\" is missing", getLabel(), propertyName);
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
      warnOrThrow("{} Number is not a valid VCF number: '{}'", getLabel(), number);
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
      warnOrThrow("{} metadata attributes declared out of canonical order: {} (expected order: {})",
          getLabel(), actual, expected);
    }
  }

}

package org.pharmgkb.parser.vcf;

import ch.qos.logback.classic.Logger;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Enforces the value/encoding rule (see VALIDATION_PHILOSOPHY.md): of the accessors returning a property
 * value, one named {@code *Raw} returns the encoding and every other returns the value.
 */
class MetadataValueEncodingTest {

  /** Raw stored form: the 9 characters {@code "a\"b\\c"}. */
  private static final String RAW_SENTINEL = "\"a\\\"b\\\\c\"";
  /** Decoded value: the 5 characters {@code a"b\c}. */
  private static final String DECODED_SENTINEL = "a\"b\\c";

  /**
   * Getters that are public, zero-argument and String-returning but are NOT property-backed, and so are
   * exempt from the invariant. Empty at introduction. Adding an entry requires a comment justifying it --
   * the point is that the decision lands on the record rather than being absorbed by a looser selection rule.
   */
  private static final Set<String> OPT_OUT = Set.of();

  /**
   * Public, zero-argument, String-returning {@code get*} methods declared anywhere in {@code type}'s
   * hierarchy below {@link Object}.
   * <p>
   * The {@code Object} exclusion is deliberate defence-in-depth, not tidiness: none of the six metadata
   * classes have any property accessor with a raw/unquoted contract that resolves to a method declared on
   * {@code Object}, so excluding {@code Object} itself from the walk costs nothing today, but it keeps this
   * selection from ever silently picking up an {@code Object} method should one someday match the
   * {@code startsWith("get")}/{@code String}-returning/zero-argument shape this method filters on.
   */
  private static List<Method> propertyGetters(Class<?> type) {
    List<Method> found = new ArrayList<>();
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Method m : c.getDeclaredMethods()) {
        if (Modifier.isPublic(m.getModifiers())
            && !m.isSynthetic()
            && m.getParameterCount() == 0
            && m.getReturnType() == String.class
            && m.getName().startsWith("get")
            && !OPT_OUT.contains(c.getSimpleName() + "." + m.getName())) {
          found.add(m);
        }
      }
    }
    return found;
  }

  private static LinkedHashMap<String, String> allPropertiesQuoted(String... keys) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    for (String key : keys) {
      map.put(key, RAW_SENTINEL);
    }
    return map;
  }

  /**
   * @return the number of getters checked, so callers can guard against {@link #propertyGetters} silently
   * selecting nothing (see the non-empty assertion in {@code testEveryTypedGetterReturnsTheDecodedValue})
   */
  private static int assertEveryGetterReturnsTheValue(BaseMetadata md) throws Exception {
    List<Method> getters = propertyGetters(md.getClass());
    for (Method getter : getters) {
      // Names BOTH classes: the declaring class is where a broken getter is fixed, the instance class is which
      // fixture surfaced it. They differ often here -- getId() is declared on the abstract IdMetadata but only
      // ever invoked on a concrete subclass -- so a message with just one of them sends the reader to the
      // wrong file.
      String where = getter.getDeclaringClass().getSimpleName() + "." + getter.getName()
          + "() [on a " + md.getClass().getSimpleName() + "]";
      Object actual = getter.invoke(md);
      // null is a FAILURE, not an accepted alternative: the fixture populates every declared property name,
      // so a null can only mean the fixture is incomplete or the getter is wired to a property it does not
      // claim. Accepting null here would let a getter reading a mistyped key pass.
      assertNotNull(actual, where + " returned null");
      assertEquals(DECODED_SENTINEL, actual, where + " must return the decoded value");
    }
    return getters.size();
  }

  @Test
  void testEveryTypedGetterReturnsTheDecodedValue() throws Exception {
    // Lenient, necessarily: the sentinel is not a valid Number, Type, URL or length, so a strict context
    // would throw during construction and this fixture could not be built. testStrictContextAcceptsQuoted
    // ValidValues below is the companion that covers the strict validation path.
    VcfContext ctx = TestUtils.LENIENT_CONTEXT;

    // IdMetadata is ABSTRACT (IdMetadata.java:13, "public abstract sealed class"), so it is not instantiated
    // here. Its getId() is still covered: propertyGetters walks the hierarchy, so every concrete subclass
    // below picks it up as a declared method of IdMetadata. Five concrete classes, not six.
    //
    // Each call's return value (the number of getters it actually checked) is summed and asserted non-zero
    // below, rather than asserted individually per fixture: a per-fixture minimum would need updating whenever
    // a class gains or loses a getter, which is exactly the kind of churn this guard must not create. A single
    // total across these four is enough to catch the failure mode this guards against -- propertyGetters (or a
    // future edit to it) silently selecting nothing for every fixture, which would leave this test, the
    // branch's primary enforcement artifact, passing vacuously while checking nothing. PedigreeMetadata is
    // deliberately excluded from the sum: it has no zero-argument String getters today (see its own call
    // below), so folding it in would only weaken the threshold this guard checks against.
    int totalGettersChecked = 0;
    totalGettersChecked += assertEveryGetterReturnsTheValue(
        new IdDescriptionMetadata(ctx, "ALT",
            allPropertiesQuoted(IdMetadata.ID, IdDescriptionMetadata.DESCRIPTION)));
    totalGettersChecked += assertEveryGetterReturnsTheValue(
        new ContigMetadata(ctx, allPropertiesQuoted(IdMetadata.ID, ContigMetadata.LENGTH,
            ContigMetadata.ASSEMBLY, ContigMetadata.MD5, ContigMetadata.SPECIES,
            ContigMetadata.TAXONOMY, ContigMetadata.URL)));
    totalGettersChecked += assertEveryGetterReturnsTheValue(
        new InfoMetadata(ctx, allPropertiesQuoted(IdMetadata.ID, InfoMetadata.NUMBER, InfoMetadata.TYPE,
            InfoMetadata.DESCRIPTION, InfoMetadata.SOURCE, InfoMetadata.VERSION)));
    totalGettersChecked += assertEveryGetterReturnsTheValue(
        new FormatMetadata(ctx, allPropertiesQuoted(IdMetadata.ID, FormatMetadata.NUMBER,
            FormatMetadata.TYPE, FormatMetadata.DESCRIPTION)));
    assertTrue(totalGettersChecked > 0,
        "propertyGetters selected no getters across all four non-Pedigree fixtures -- this invariant test "
            + "would be silently vacuous; check the propertyGetters filter");
    // PedigreeMetadata has no zero-argument String getters today, so this is vacuous for it. Included so
    // that adding one later is covered automatically.
    assertEveryGetterReturnsTheValue(
        new PedigreeMetadata(ctx, allPropertiesQuoted(IdMetadata.ID)));
  }

  @Test
  void testSentinelIsEscapedNotPlain() {
    // Guards the guard. If someone simplifies RAW_SENTINEL to a plain "Zq", the invariant test above silently
    // stops covering escape decoding and a getter implemented as substring(1, length - 1) would pass it.
    assertEquals(DECODED_SENTINEL, InternalUtils.unquote(RAW_SENTINEL));
    assertFalse(DECODED_SENTINEL.equals(RAW_SENTINEL.substring(1, RAW_SENTINEL.length() - 1)),
        "the sentinel must distinguish real unquoting from a naive outer-quote strip");
  }

  @Test
  void testStrictContextAcceptsQuotedValidValues() {
    // The load-bearing assertion here is "does not throw", and it is not about getters at all: it pins the
    // premise of the whole rule -- quoting is an encoding, not a defect, so a quoted-but-valid declaration
    // must PASS strict validation. If any validation site regressed to reading raw (NUMBER_PATTERN against
    // "1", InfoType.valueOf("\"Integer\""), URI.create on a quoted URL), construction would throw here. The
    // sentinel test above cannot catch that: under a lenient context the same regression only warns.
    VcfContext ctx = VcfContext.strict("VCFv4.2");

    // Explicit puts, NOT new LinkedHashMap<>(Map.of(...)): Map.of does not promise iteration order, and
    // IdDescriptionMetadata's map constructor calls checkAttributeOrder() (IdDescriptionMetadata.java:44),
    // which routes through warnOrThrow -- so under this strict context an unlucky iteration order throws
    // VcfFormatException before any getter assertion runs, nondeterministically.
    LinkedHashMap<String, String> filterProps = new LinkedHashMap<>();
    filterProps.put(IdMetadata.ID, "\"q10\"");
    filterProps.put(IdDescriptionMetadata.DESCRIPTION, "\"d\"");
    IdDescriptionMetadata filter = new IdDescriptionMetadata(ctx, "FILTER", filterProps);
    assertEquals("q10", filter.getId());
    assertEquals("\"q10\"", filter.getPropertyRaw(IdMetadata.ID));

    LinkedHashMap<String, String> infoProps = new LinkedHashMap<>();
    infoProps.put(IdMetadata.ID, "XX");
    infoProps.put(InfoMetadata.NUMBER, "\"1\"");
    infoProps.put(InfoMetadata.TYPE, "Integer");
    infoProps.put(InfoMetadata.DESCRIPTION, "\"d\"");
    InfoMetadata info = new InfoMetadata(ctx, infoProps);
    assertEquals("1", info.getNumber());
    assertEquals("\"1\"", info.getPropertyRaw(InfoMetadata.NUMBER));

    LinkedHashMap<String, String> formatProps = new LinkedHashMap<>();
    formatProps.put(IdMetadata.ID, "YY");
    formatProps.put(FormatMetadata.NUMBER, "\"1\"");
    formatProps.put(FormatMetadata.TYPE, "Integer");
    formatProps.put(FormatMetadata.DESCRIPTION, "\"d\"");
    FormatMetadata format = new FormatMetadata(ctx, formatProps);
    assertEquals("1", format.getNumber());
    assertEquals("\"1\"", format.getPropertyRaw(FormatMetadata.NUMBER));

    LinkedHashMap<String, String> contigProps = new LinkedHashMap<>();
    contigProps.put(IdMetadata.ID, "1");
    contigProps.put(ContigMetadata.LENGTH, "\"123\"");
    contigProps.put(ContigMetadata.ASSEMBLY, "\"B36\"");
    contigProps.put(ContigMetadata.MD5, "\"f126cdf8a6e0c7f379d618ff66beb2da\"");
    contigProps.put(ContigMetadata.TAXONOMY, "\"x\"");
    contigProps.put(ContigMetadata.URL, "\"ftp://example.com/a.fa\"");
    ContigMetadata contig = new ContigMetadata(ctx, contigProps);
    assertEquals(123L, contig.getLength());
    assertEquals("B36", contig.getAssembly());
    assertEquals("x", contig.getTaxonomy());
    assertEquals("f126cdf8a6e0c7f379d618ff66beb2da", contig.getMd5());
    assertEquals("ftp://example.com/a.fa", contig.getUrl());
    assertEquals("\"B36\"", contig.getPropertyRaw(ContigMetadata.ASSEMBLY));
    assertEquals("\"ftp://example.com/a.fa\"", contig.getPropertyRaw(ContigMetadata.URL));
  }

  @Test
  void testTypedContigConstructorUnquotesIdAndUrl() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    // no throw is the load-bearing half: a quoted argument is an encoding, not a defect
    ContigMetadata contig = new ContigMetadata(ctx, "\"1\"", 123L, null, null, null, null,
        "\"ftp://example.com/a.fa\"");
    assertEquals("1", contig.getId());
    assertEquals("ftp://example.com/a.fa", contig.getUrl());
    assertEquals("\"1\"", contig.getPropertyRaw(IdMetadata.ID));
    assertEquals("\"ftp://example.com/a.fa\"", contig.getPropertyRaw(ContigMetadata.URL));
  }

  @Test
  void testTypedContigConstructorWarnsOnAMalformedUrlInsideQuotes() {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      // warns rather than throws, per the constructor's existing warnOrThrow policy
      new ContigMetadata(TestUtils.LENIENT_CONTEXT, "1", 123L, null, null, null, null, "\"not a url\"");
      // the warning must name the UNQUOTED value -- that is what proves validation saw through the quoting
      // rather than failing because of it
      assertTrue(appender.anyMessageContains("not a url"));
      assertFalse(appender.anyMessageContains("\"not a url\""));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}

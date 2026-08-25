package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests {@link BaseMetadata}.
 * @author Douglas Myers-Turnbull
 */
public class BaseMetadataTest {

  @Test
  public void testConstructorRejectsNullPropertiesMapInsteadOfThrowingNpe() {
    // properties is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at new LinkedHashMap<>(properties)
    assertThrows(VcfFormatException.class, () -> new BaseMetadata(LENIENT_CONTEXT, "TEST", null));
  }

  @Test
  public void testGetProperty() throws Exception {
    Map<String, String> map = new HashMap<>();
    map.put("test", "one");
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    assertEquals("one", metadata.getPropertyRaw("test"));
  }

  @Test
  public void testConstructorDoesNotAliasCallersMap() {
    // the constructor used to store the caller's map by reference, so mutating it after construction
    // silently corrupted this instance's state; it now defensively copies into its own LinkedHashMap
    Map<String, String> map = new LinkedHashMap<>();
    map.put("test", "one");
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    map.put("test", "mutated-after-construction");
    map.put("extra", "should not appear");
    assertEquals("one", metadata.getPropertyRaw("test"));
    assertFalse(metadata.getPropertyKeys().contains("extra"));
  }

  @Test
  public void testGetPropertyKeysRejectsDirectMutationInsteadOfSilentlyDefeatingIt() {
    // regression test: getPropertyKeys() used to return m_properties.keySet() directly -- a standard
    // Map.keySet() view where remove() deletes the entry from the backing map, bypassing every check
    // putPropertyRaw/putAndQuoteProperty perform, with no signal to the caller that anything unusual
    // happened. It now returns an unmodifiable view: a mutation attempt fails fast with
    // UnsupportedOperationException instead of either corrupting the backing map (the original bug) or
    // silently no-opping on a detached copy (this project's first-drafted fix for this same bug, reconsidered
    // in favor of fail-fast).
    Map<String, String> map = new LinkedHashMap<>();
    map.put("Number", "1");
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    assertThrows(UnsupportedOperationException.class, () -> metadata.getPropertyKeys().remove("Number"));
    assertEquals("1", metadata.getPropertyRaw("Number"));
  }

  @Test
  public void testGetPropertyKeysReflectsALaterMutationMadeThroughOtherMethods() {
    // companion test: unlike a snapshot copy, getPropertyKeys() remains a view over the live backing map -- a
    // property inserted through putPropertyRaw() after the fact is visible through a set obtained beforehand.
    // putPropertyRaw is package-private since the metadata layer was frozen (it exists so a subclass
    // constructor can populate its own typed properties), so this is reachable from here but not by a caller
    // outside this library -- what makes memoizing the view safe: it is a view, never a snapshot.
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ID", "DP");
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    Set<String> keys = metadata.getPropertyKeys();
    assertFalse(keys.contains("Number"));
    metadata.putPropertyRaw("Number", "1");
    assertTrue(keys.contains("Number"));
  }

  @Test
  public void testGetPropertiesUnquoted() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("a", "\"quoted\"");
    map.put("b", "unquoted");
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    Map<String, String> unquoted = metadata.getPropertiesUnquoted();
    assertEquals(2, unquoted.size());
    assertEquals("quoted", unquoted.get("a"));
    assertEquals("unquoted", unquoted.get("b"));
  }

  @Test
  public void testGetPropertiesUnquotedPreservesDeclarationOrder() {
    // regression test found by the Phase 2 review's fourth re-verification round: getPropertiesUnquoted()
    // previously returned a plain HashMap, unlike every sibling accessor on this class (getPropertyKeys(),
    // getPropertiesRaw()), which are explicitly order-preserving. A caller reading this map's properties back
    // out via replaceInfo(...)/addInfo(...) (the obvious read-modify-rebuild idiom this accessor exists for)
    // would silently reorder the rebuilt declaration's attributes to HashMap hash order instead of the
    // original declaration order. ID/Number/Type/Description is canonical order, deliberately not
    // alphabetical, so hash order would very likely scramble it.
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ID", "AF");
    map.put("Number", "A");
    map.put("Type", "Float");
    map.put("Description", "d");
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    Map<String, String> unquoted = metadata.getPropertiesUnquoted();
    assertEquals(List.of("ID", "Number", "Type", "Description"), new ArrayList<>(unquoted.keySet()));
  }

  @Test
  public void testKeyNewline() throws Exception {
    assertThrows(VcfFormatException.class, () -> {
      Map<String, String> map = new HashMap<>();
      map.put("test", "two\nlines");
      new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    });
  }

  @Test
  public void testValueNewline() throws Exception {
    assertThrows(VcfFormatException.class, () -> {
      Map<String, String> map = new HashMap<>();
      map.put("second\ntest", "one");
      new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    });
  }

  @Test
  public void testPutPropertyRawRejectsLineTerminator() {
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", "bad\rvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("bad\nkey", "value"));
  }

  @Test
  public void testPutPropertyRawRejectsNull() {
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw("key", null));
  }

  @Test
  public void testPutPropertyRawRejectsNullKeyInsteadOfThrowingNpe() {
    // key is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at InternalUtils.checkNoLineTerminator(key, value)
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putPropertyRaw(null, "value"));
  }

  @Test
  public void testPutAndQuotePropertyRejectsLineTerminator() {
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", "bad\rvalue"));
  }

  @Test
  public void testPutAndQuotePropertyRejectsNull() {
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty("key", null));
  }

  @Test
  public void testPutAndQuotePropertyRejectsNullKeyInsteadOfThrowingNpe() {
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", new HashMap<>());
    assertThrows(VcfFormatException.class, () -> metadata.putAndQuoteProperty(null, "value"));
  }

  @Test
  public void testValidateThrowsForNullPropertyValueInsertedViaGetPropertiesRaw() {
    // regression test: putPropertyRaw/putAndQuoteProperty already reject a null value at insertion, but
    // validateProperties() (called by validate(), and unconditionally at construction) only checked for a null
    // key, tolerating a null value inserted afterward. Since the metadata layer was frozen, getPropertiesRaw()
    // is unmodifiable and no public route can reach this state at all -- trustedMutableProperties() is the
    // package-private bypass that still can, and is what keeps this check covered.
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ID", "DP");
    BaseMetadata metadata = new BaseMetadata(LENIENT_CONTEXT, "TEST", map);
    metadata.trustedMutableProperties().put("Extra", null);
    VcfFormatException e = assertThrows(VcfFormatException.class, metadata::validate);
    assertTrue(e.getMessage().contains("null value"));
  }

  @Test
  public void testConstructorRejectsNullPropertyValueMessageIsTruncated() {
    // regression test found by the eighth re-verification round's fresh bug hunt: validateProperties()'s null-
    // value message embedded the raw property key verbatim and unbounded. Unlike the bypass-only test above,
    // this needs no trustedMutableProperties() bypass at all -- validateProperties() runs unconditionally at
    // the end of the Map-based constructor (line 87), so this is directly reachable from the public
    // addXxx(Map)/replaceXxx(Map) overloads via a caller-supplied HashMap/LinkedHashMap containing a null
    // value (unlike Map.of, these tolerate one).
    Map<String, String> map = new LinkedHashMap<>();
    map.put("ID", "DP");
    map.put("X".repeat(200), null);
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new BaseMetadata(LENIENT_CONTEXT, "TEST", map));
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    assertTrue(ex.getMessage().length() < 300, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testDefaultWarningsSinkIsSharedLogger() {
    // LENIENT_CONTEXT's own sink is VcfWarnings.DEFAULT (Task 7 removed setWarningsSink, so this is the only
    // sink a BaseMetadata not built with an explicit VcfWarnings can ever have) -- must warn to the shared
    // logger, not throw or silently no-op.
    // warnIfMissing is protected, not part of any concrete declaration kind's own public behavior, but
    // accessible here (same package) purely as a vehicle to trigger a warning through m_warnings -- any
    // warn() call site would do equally well for this test's actual point.
    BaseMetadata md = new BaseMetadata(LENIENT_CONTEXT, "TESTLABEL", Map.of());
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender = new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      md.warnIfMissing("SomeProp", null);
      assertTrue(appender.anyMessageContains("TESTLABEL required metadata property \"SomeProp\" is missing"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}

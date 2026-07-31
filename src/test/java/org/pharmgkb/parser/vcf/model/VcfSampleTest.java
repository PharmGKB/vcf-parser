package org.pharmgkb.parser.vcf.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests {@link VcfSample}.
 *
 * @author Douglas Myers-Turnbull
 */
class VcfSampleTest {

  @Test
  void testGetProperty() {
    List<String> keys = Arrays.asList("key1", "key2");
    List<String> values = Arrays.asList("value1", "value2");
    VcfSample sample = new VcfSample(keys, values);
    assertEquals(2, sample.getPropertyKeys().size());
    assertEquals("value1", sample.getProperty("key1"));
  }

  @Test
  void testOrder() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("1", "A");
    map.put("2", "B");
    VcfSample sample = new VcfSample(map);
    assertEquals(Arrays.asList("1", "2"), new ArrayList<>(sample.getPropertyKeys()));

    LinkedHashMap<String, String> reversedMap = new LinkedHashMap<>();
    reversedMap.put("2", "B");
    reversedMap.put("1", "A");
    VcfSample sampleReverse = new VcfSample(reversedMap);
    assertEquals(Arrays.asList("2", "1"), new ArrayList<>(sampleReverse.getPropertyKeys()));
  }

  @Test
  void testBadConstructor() {
    assertThrows(VcfFormatException.class, () -> {
      List<String> keys = Arrays.asList("key1", "key2");
      List<String> values = Collections.singletonList("value1");
      new VcfSample(keys, values);
    });
  }

  @Test
  void testHasNewline() {
    assertThrows(VcfFormatException.class, () -> {
      List<String> keys = Collections.singletonList("key1");
      List<String> values = Collections.singletonList("value\n1");
      new VcfSample(keys, values);
    });
  }

  @Test
  void testPutPropertyRejectsLineTerminator() {
    // putProperty previously had no line-terminator check at all, unlike the constructors
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    assertThrows(VcfFormatException.class, () -> sample.putProperty("GT", "0/1\nextra"));
    assertThrows(VcfFormatException.class, () -> sample.putProperty("GT", "0/1\rextra"));
    assertThrows(VcfFormatException.class, () -> sample.putProperty("bad\nkey", "value"));
    // the ReservedFormatProperty overload must not bypass the check either
    assertThrows(VcfFormatException.class, () -> sample.putProperty(ReservedFormatProperty.Genotype, "0/1\nextra"));
  }

  @Test
  void testMapConstructorRejectsNullPropertiesInsteadOfThrowingNpe() {
    // properties is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at m_properties.entrySet()
    assertThrows(VcfFormatException.class, () -> new VcfSample((LinkedHashMap<String, String>) null));
  }

  @Test
  void testReservedFormatPropertyOverloadsHandleNullKeyLikeTheirStringSiblings() {
    // getProperty/containsProperty/removeProperty(ReservedFormatProperty) must not raw-NPE at key.getId() for a
    // null key -- each matches its own String-keyed sibling's already-null-tolerant "not present"/no-op contract.
    // putProperty is the exception: putProperty(String, ...) already throws for a null key, so the
    // ReservedFormatProperty overload must throw too, not silently tolerate it.
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    assertNull(sample.getProperty((ReservedFormatProperty) null));
    assertFalse(sample.containsProperty((ReservedFormatProperty) null));
    assertThrows(VcfFormatException.class, () -> sample.putProperty((ReservedFormatProperty) null, "value"));
    sample.removeProperty((ReservedFormatProperty) null); // must not throw
  }

  @Test
  void testPutPropertyRejectsStructuralDelimiters() {
    // a colon would add a spurious FORMAT subfield, and a tab would add a spurious sample column, when written back out
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    assertThrows(VcfFormatException.class, () -> sample.putProperty("DP", "1:2"));
    assertThrows(VcfFormatException.class, () -> sample.putProperty("DP", "1\t2"));
    assertThrows(VcfFormatException.class, () -> sample.putProperty("bad:key", "value"));
    assertThrows(VcfFormatException.class, () -> sample.putProperty("bad\tkey", "value"));
  }

  @Test
  void testConstructorRejectsStructuralDelimiters() {
    assertThrows(VcfFormatException.class, () -> {
      List<String> keys = Collections.singletonList("DP");
      List<String> values = Collections.singletonList("1:2");
      new VcfSample(keys, values);
    });
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("DP", "1:2");
    assertThrows(VcfFormatException.class, () -> new VcfSample(map));
  }

  @Test
  void testValidateRejectsMutationThroughEntryView() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("DP", "1");
    VcfSample sample = new VcfSample(map);
    sample.propertyEntrySet().iterator().next().setValue("1:2");
    assertThrows(VcfFormatException.class, sample::validate);
  }

  @Test
  void testGetPropertyReservedListTypeWithEmptyEntries() {
    // HQ is a list-typed reserved FORMAT property (Long); getProperty(ReservedFormatProperty) previously routed
    // through VcfUtils.convertProperty's plain value.split(","), which silently dropped a trailing empty entry and
    // threw an unhelpful generic exception for an interior one, unlike the rest of the empty-field handling
    LinkedHashMap<String, String> trailing = new LinkedHashMap<>();
    trailing.put("HQ", "1,2,");
    VcfSample trailingSample = new VcfSample(trailing);
    assertEquals(Arrays.asList(1L, 2L, null),
        trailingSample.getProperty(ReservedFormatProperty.HaplotypeQualities));

    LinkedHashMap<String, String> interior = new LinkedHashMap<>();
    interior.put("HQ", "1,,2");
    VcfSample interiorSample = new VcfSample(interior);
    assertEquals(Arrays.asList(1L, null, 2L),
        interiorSample.getProperty(ReservedFormatProperty.HaplotypeQualities));
  }

  @Test
  void testStructuralVariantFormatPropertiesAreIntegerTyped() {
    // NQ, HAP, and AHAP were previously declared with the wrong Type (Float/String); VCFv4.2's own ##FORMAT
    // declarations for these keys all state Type=Integer
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("NQ", "30");
    map.put("HAP", "1");
    map.put("AHAP", "2");
    VcfSample sample = new VcfSample(map);
    assertEquals(Long.valueOf(30L), sample.getProperty(ReservedFormatProperty.PhredScoreForNovelty));
    assertEquals(Long.valueOf(1L), sample.getProperty(ReservedFormatProperty.HaplotypeId));
    assertEquals(Long.valueOf(2L), sample.getProperty(ReservedFormatProperty.AncestralHaplotypeId));
  }

  @Test
  void testMappingQualityIsSingleValued() {
    // MQ was previously declared isList=true; the spec describes it as a single Integer, unlike GL/PL/GP/HQ/EC
    // which are explicitly comma-separated
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("MQ", "45");
    VcfSample sample = new VcfSample(map);
    assertEquals(Long.valueOf(45L), sample.getProperty(ReservedFormatProperty.MappingQuality));
  }

  @Test
  void testGenotypeLikelihoodsOfHeterogenousPloidyIsSingleValued() {
    // GLE was previously declared isList=true, which would split the spec's own example value on its internal
    // commas; it's one opaque String, not a list of independent values
    String gle = "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53";
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GLE", gle);
    VcfSample sample = new VcfSample(map);
    assertEquals(gle, sample.getProperty(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy));
  }

  @Test
  void testGleValueIsExemptFromColonCheck() {
    // GLE's own spec example (e.g. "0:-75.22,1:-223.42,...") uses colons internally as part of its own encoding; it
    // is the one key exempted from the colon check on its value. The key itself is still checked, and every other
    // key's value is unaffected.
    String gle = "0:-75.22,1:-223.42,0/0:-323.03";
    VcfSample viaMap = new VcfSample(new LinkedHashMap<>());
    viaMap.putProperty("GLE", gle);
    assertEquals(gle, viaMap.getProperty("GLE"));
    VcfSample viaConstructor = new VcfSample(Collections.singletonList("GLE"), Collections.singletonList(gle));
    assertEquals(gle, viaConstructor.getProperty("GLE"));

    // the exemption is specific to the key "GLE"; a colon in any other key's value is still rejected
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    assertThrows(VcfFormatException.class, () -> sample.putProperty("DP", gle));
    // a colon in the key "GLE" itself (not its value) is still rejected
    assertThrows(VcfFormatException.class, () -> sample.putProperty("GLE:bad", "value"));
    // a tab in GLE's value is still rejected (tab always means a spurious sample column, regardless of key)
    assertThrows(VcfFormatException.class, () -> sample.putProperty("GLE", "a\tb"));
  }

  @Test
  void testPropertiesMaterializationKeepsFirstOccurrenceOnDuplicateKey() {
    // properties() (the materialization path triggered by getPropertyKeys()/propertyEntrySet()/putProperty()/etc.)
    // previously let the LAST occurrence of a duplicate key silently overwrite the first, while getProperty()
    // (the un-materialized linear-scan path) already returned the FIRST occurrence -- the same VcfSample answered
    // a lookup differently depending purely on which path ran first. This constructs a VcfSample directly (the
    // list-based constructor never checks for duplicate keys itself). For a normal, non-empty duplicate FORMAT key
    // (e.g. GT:DP:DP), VcfPosition.checkFormat does reject it during parsing, before a VcfSample would ever be
    // built from that data line -- but a duplicate of the EMPTY key specifically is excluded from that check (see
    // EMPTY_FIELD_HANDLING.md's "duplicate FORMAT keys" section: the exclusion is there because two empty keys
    // aren't "duplicates" in the spec's malformed-declaration sense, not because empty-key collisions in
    // VcfSample's own property map are safe), so FORMAT=GT:: with two "" keys IS parser-reachable -- see
    // WriterRoundTripTest.testDuplicateEmptyFormatKeyRoundTripsWithoutColumnCorruption for that end-to-end case.
    List<String> keys = Arrays.asList("GT", "GT");
    List<String> values = Arrays.asList("0/1", "1/1");
    VcfSample sample = new VcfSample(keys, values);

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      // getPropertyKeys() forces materialization
      assertEquals(Collections.singletonList("GT"), new ArrayList<>(sample.getPropertyKeys()));
      assertTrue(appender.hasWarnOrHigher());
      assertTrue(appender.anyMessageContains("GT"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // materialization must have kept the FIRST value ("0/1"), not the last ("1/1")
    assertEquals("0/1", sample.getProperty("GT"));
  }

  @Test
  void testGetPropertyReturnsFirstOccurrenceBeforeMaterialization() {
    // regression guard: getProperty() called BEFORE any materialization-triggering call must already return the
    // first occurrence -- this existing correct behavior must not change
    List<String> keys = Arrays.asList("GT", "GT");
    List<String> values = Arrays.asList("0/1", "1/1");
    VcfSample sample = new VcfSample(keys, values);
    assertEquals("0/1", sample.getProperty("GT"));
  }

  @Test
  void testContainsPropertyReturnsFalseForNullKeyOnLeanListPath() {
    // containsProperty(null) is null-tolerant, matching the other null-tolerant predicates
    // VcfUtils.isReservedMetadataKey and VcfMetadata.getColumnIndex()/getSampleIndex() -- a property key can
    // never actually be null in this class, so null trivially "isn't one of this sample's keys". Uses List.of
    // (not Arrays.asList) so m_keys.contains(null) would throw NullPointerException without the guard.
    List<String> keys = List.of("key1", "key2");
    List<String> values = List.of("value1", "value2");
    VcfSample sample = new VcfSample(keys, values);
    assertFalse(sample.containsProperty((String) null));
  }

  @Test
  void testContainsPropertyReturnsFalseForNullKeyAfterMaterialization() {
    // pins that both internal-state paths (lean-list above, materialized-map here) converge on the same
    // false answer for a null key
    List<String> keys = Arrays.asList("key1", "key2");
    List<String> values = Arrays.asList("value1", "value2");
    VcfSample sample = new VcfSample(keys, values);
    sample.putProperty("key3", "value3"); // forces materialization of m_properties
    assertFalse(sample.containsProperty((String) null));
  }
}

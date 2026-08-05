package org.pharmgkb.parser.vcf.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
  void testGetPropertyKeysRejectsDirectMutationInsteadOfSilentlyDefeatingIt() {
    // regression test: getPropertyKeys() used to return properties().keySet() directly -- a standard
    // Map.keySet() view where remove() deletes the entry from the backing map with no validation and no
    // signal to the caller. It now returns an unmodifiable view: a mutation attempt fails fast with
    // UnsupportedOperationException instead of silently corrupting the sample.
    VcfSample sample = new VcfSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    assertThrows(UnsupportedOperationException.class, () -> sample.getPropertyKeys().remove("GT"));
    assertEquals("0/1", sample.getProperty("GT"));
  }

  @Test
  void testGetPropertyKeysReflectsALaterMutationMadeThroughOtherMethods() {
    // companion test: unlike a snapshot copy, getPropertyKeys() remains a view over the live backing map --
    // a mutation made through putProperty() after the fact is visible through a set obtained beforehand
    VcfSample sample = new VcfSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    Set<String> keys = sample.getPropertyKeys();
    assertFalse(keys.contains("DP"));
    sample.putProperty("DP", "10");
    assertTrue(keys.contains("DP"));
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
    // through InternalUtils.convertProperty's plain value.split(","), which silently dropped a trailing empty entry and
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
  void testGetPropertyFilterAllEmptyCodesReturnsNullNotEmptyString() {
    // regression test: when every semicolon-separated FT code is empty (e.g. ";;"), InternalUtils.
    // checkFilterLikeValue used to return "" (from String.join(";", emptyList)) instead of ".", this library's
    // established missing-value sentinel -- convertProperty's only "treat as absent" check is
    // value == null || ".".equals(value), so "" reached type conversion unchanged, making this getter return a
    // non-null empty string for a sample whose FT content was entirely empty artifacts, violating the
    // null-means-absent contract every other reserved property getter follows
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("FT", ";;");
    VcfSample sample = new VcfSample(map);
    assertNull(sample.getProperty(ReservedFormatProperty.Filter));
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
  void testAlleleDepthAndPosteriorProbabilityKeysAreListTyped() {
    // AD/ADF/ADR/PP were previously missing from ReservedFormatProperty entirely, so getProperty(
    // ReservedFormatProperty) could not be called with them at all
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("AD", "10,5");
    map.put("ADF", "6,3");
    map.put("ADR", "4,2");
    map.put("PP", "0,3,10");
    VcfSample sample = new VcfSample(map);
    assertEquals(Arrays.asList(10L, 5L), sample.getProperty(ReservedFormatProperty.AlleleDepth));
    assertEquals(Arrays.asList(6L, 3L), sample.getProperty(ReservedFormatProperty.AlleleDepthForward));
    assertEquals(Arrays.asList(4L, 2L), sample.getProperty(ReservedFormatProperty.AlleleDepthReverse));
    assertEquals(Arrays.asList(0L, 3L, 10L),
        sample.getProperty(ReservedFormatProperty.PhredScaledPosteriorProbabilities));
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
    // InternalUtils.isReservedMetadataKey and VcfMetadata.getColumnIndex()/getSampleIndex() -- a property key can
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

  @Test
  void testValidateWarnsToConfiguredSinkForEmptyValue() {
    VcfSample sample = new VcfSample(List.of("DP"), new ArrayList<>(List.of("")));
    java.io.StringWriter sink = new java.io.StringWriter();
    sample.setWarningsSink(new org.pharmgkb.parser.vcf.VcfWarnings(sink));
    sample.validate();
    assertTrue(sink.toString().contains("has an empty value"));
    assertEquals(".", sample.getProperty("DP"));
  }

  @Test
  void testDuplicateFormatKeyWarnsToConfiguredSinkOnLazyMaterialization() {
    VcfSample sample = new VcfSample(List.of("DP", "DP"), List.of("1", "2"));
    java.io.StringWriter sink = new java.io.StringWriter();
    sample.setWarningsSink(new org.pharmgkb.parser.vcf.VcfWarnings(sink));
    assertEquals("1", sample.getPropertyKeys().contains("DP") ? sample.getProperty("DP") : null);
    assertTrue(sink.toString().contains("duplicate FORMAT key"));
  }

  @Test
  void testDefaultWarningsSinkIsSharedLoggerWhenNeverConfigured() {
    VcfSample sample = new VcfSample(List.of("DP"), new ArrayList<>(List.of("")));
    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      sample.validate();
      assertTrue(appender.anyMessageContains("has an empty value"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataChecksReservedListCardinality() {
    // PL is Number=G, isList=true: GT=0|1 (ploidy 2), 1 ALT allele -> 2 total alleles -> expected
    // combinationsWithRepetition(2, 2) = 3, but only 2 values given -- must warn, but still return the full list
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("PL", "1,2");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Object result = sample.getProperty(position, metadata, "PL");
      assertEquals(java.util.Arrays.asList(1L, 2L), result);
      assertTrue(appender.anyMessageContains("FORMAT PL has 2 value(s), but Number=G requires 3"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataDoesNotWarnOnCorrectCardinality() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("PL", "1,2,3");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(java.util.Arrays.asList(1L, 2L, 3L), sample.getProperty(position, metadata, "PL"));
      assertFalse(appender.anyMessageContains("value(s), but Number="));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataExemptsGleFromCardinalityCheck() {
    // GLE's Number=G doesn't describe a comma-delimited list -- its commas are internal to its own encoding
    String gle = "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53";
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("GLE", gle);
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(gle, sample.getProperty(position, metadata, "GLE"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataExemptsCnpFromCardinalityCheck() {
    // CNP's Number=G means "one value per potential copy number," not the ploidy-derived genotype ordering
    // this codebase's Number=G formula computes -- GT=0|1 (ploidy 2), 1 ALT allele would make the formula
    // expect 3 values, but a real CNP for 5 possible copy-number states legitimately has 5; must not warn
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("CNP", "0.1,0.2,0.3,0.2,0.2");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(java.util.Arrays.asList(
          VcfFloat.of(new java.math.BigDecimal("0.1")), VcfFloat.of(new java.math.BigDecimal("0.2")),
          VcfFloat.of(new java.math.BigDecimal("0.3")), VcfFloat.of(new java.math.BigDecimal("0.2")),
          VcfFloat.of(new java.math.BigDecimal("0.2"))), sample.getProperty(position, metadata, "CNP"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataSkipsCardinalityCheckWhenPloidyUnknown() {
    // a non-reserved Number=G field has no spec-stated ploidy default the way GL/PL/GP/PP do (see the dedicated
    // PL test below) -- GT absent -> ploidy can't be determined -> skip rather than guess
    FormatMetadata xxFormat = new FormatMetadata("XX", "Custom Number=G field", "G", FormatType.Integer);
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("XX", "1,2");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(xxFormat).build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(java.util.Arrays.asList(1L, 2L), sample.getProperty(position, metadata, "XX"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataAssumesDiploidyForPlWhenGtAbsent() {
    // PL's own spec definition (VCFv4.2.tex line 224 and unchanged through 4.5, via GL's definition PL defers
    // to) states "without GT field, diploidy is assumed" -- unlike the non-reserved Number=G field above, this
    // is a real, spec-stated default, not a guess. 1 ALT allele + assumed ploidy 2 -> expected 3 values, but
    // only 2 are given, so this must warn (regression test for a prior bug: this used to silently skip the
    // check entirely whenever GT was absent, for GL/PL/GP/PP same as any other Number=G field)
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("PL", "1,2");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(java.util.Arrays.asList(1L, 2L), sample.getProperty(position, metadata, "PL"));
      assertTrue(appender.anyMessageContains("FORMAT PL has 2 value(s), but Number=G requires 3"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataAssumedDiploidyForPlMatchesCorrectValueCount() {
    // same assumed-diploidy default as above, but with the correct 3-value count for a diploid, biallelic
    // sample -- must not warn
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("PL", "1,2,3");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(java.util.Arrays.asList(1L, 2L, 3L), sample.getProperty(position, metadata, "PL"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataTruncatesScalarCardinalityViolation() {
    // FT is Number=1/String-typed, isList=false; a raw comma is a cardinality violation this library had no
    // read-path way to detect before this method -- must warn and keep only the first piece, not silently pass
    // the whole comma-containing value through as one opaque String
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("FT", "STD_FILTER,extra");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals("STD_FILTER", sample.getProperty(position, metadata, "FT"));
      assertTrue(appender.anyMessageContains("FORMAT FT has 2 value(s), but Number=1 requires 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataReturnsNullForAbsentValueOrNullArgs() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    assertNull(sample.getProperty(position, metadata, "PL")); // not present on this sample
    assertNull(sample.getProperty(null, metadata, "GT"));
    assertNull(sample.getProperty(position, null, "GT"));
    assertNull(sample.getProperty(position, metadata, (String) null));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataHandlesNonReservedScalarKey() {
    // DS is declared Number=1/Type=Float in metadata but is not a ReservedFormatProperty
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "1.5");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    FormatMetadata dsFormat = new FormatMetadata("DS", "Genotype dosage", "1", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(dsFormat).build();

    assertEquals(VcfFloat.of(new java.math.BigDecimal("1.5")), sample.getProperty(position, metadata, "DS"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataWarnsOnNonReservedScalarCardinalityViolation() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "1.5,2.5");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    FormatMetadata dsFormat = new FormatMetadata("DS", "Genotype dosage", "1", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(dsFormat).build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(VcfFloat.of(new java.math.BigDecimal("1.5")), sample.getProperty(position, metadata, "DS"));
      assertTrue(appender.anyMessageContains("FORMAT DS has 2 value(s), but Number=1 requires 1"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataHandlesNonReservedListKey() {
    // AL is declared Number=A/Type=Float; ALT has 2 alleles, so Number=A expects 2 values
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("AL", "0.1,0.2");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    position.getAltBases().add("C");
    FormatMetadata alFormat = new FormatMetadata("AL", "Alt allele field", "A", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(alFormat).build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(java.util.Arrays.asList(VcfFloat.of(new java.math.BigDecimal("0.1")),
          VcfFloat.of(new java.math.BigDecimal("0.2"))), sample.getProperty(position, metadata, "AL"));
      assertFalse(appender.anyMessageContains("value(s), but Number="));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataThrowsOnNonReservedTypeMismatch() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "my_string");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    FormatMetadata dsFormat = new FormatMetadata("DS", "Genotype dosage", "1", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(dsFormat).build();

    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "DS"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataTreatsMissingValueSentinelAsAbsent() {
    // a record that legitimately drops a trailing FORMAT sub-field gets '.'-filled by the parser; "." is the
    // missing-value sentinel, not a real 1-element value, so it must not be measured against Number=G
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("GL", ".");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertNull(sample.getProperty(position, metadata, "GL"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataTreatsUndeclaredNumberAsListRegardlessOfType() {
    // a leniently-parsed ##FORMAT declaration missing Number leaves getNumber() null. bcftools 1.23.1 treats
    // this exactly like an explicit Number=. declaration ("A FORMAT field has no Number defined. Assuming
    // '.'"), uniformly regardless of Type -- confirmed empirically that bcftools genuinely splits a
    // Type=String value like this one into separate list elements rather than keeping it as one opaque
    // scalar string, so this test matches that rather than the scalar shape this method previously returned.
    // There's nothing to judge cardinality against either way, so no cardinality warning fires.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("XX", "a,b,c");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    FormatMetadata xxFormat = new FormatMetadata(InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=String", "Description=\"no Number\""}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(xxFormat).build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(List.of("a", "b", "c"), sample.getProperty(position, metadata, "XX"));
      assertFalse(appender.anyMessageContains("value(s), but Number="));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataDoesNotCrashOnMultiValuedNonStringTypeWithUndeclaredNumber() {
    // regression test: before this fix, a non-String Type (Integer/Float/Character) with undeclared Number
    // was still forced to scalar shape, so a genuinely multi-valued sample value crashed with
    // VcfFormatException ("Expected integer; got 1,2,3") even though every individual piece was a perfectly
    // valid Integer -- treating undeclared Number as Number=. (matching bcftools) fixes this by returning
    // the value list-shaped instead of attempting (and failing) a scalar conversion
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("XX", "1,2,3");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    FormatMetadata xxFormat = new FormatMetadata(InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=Integer", "Description=\"no Number\""}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(xxFormat).build();

    assertEquals(List.of(1L, 2L, 3L), sample.getProperty(position, metadata, "XX"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataUndeclaredNumberSingleValueStillWorksForNonStringType() {
    // sanity check: a single-value (no comma) sample value for an undeclared-Number non-String key must
    // still convert correctly once treated as list-shaped -- a one-element list, not a crash
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("XX", "5");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    FormatMetadata xxFormat = new FormatMetadata(InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=Integer", "Description=\"no Number\""}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(xxFormat).build();

    assertEquals(List.of(5L), sample.getProperty(position, metadata, "XX"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataUndeclaredNumberStillThrowsOnGenuineTypeMismatch() {
    // regression test for the new list-shaped conversion path itself (not just the old, now-unreachable
    // scalar path): a genuinely malformed element must still throw VcfFormatException, per this method's own
    // documented "a genuine Type mismatch still throws" contract -- confirms the fix didn't silently trade
    // the crash for swallowing real type errors
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("XX", "1.0,abc");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    FormatMetadata xxFormat = new FormatMetadata(InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=Float", "Description=\"no Number\""}, VcfWarnings.DEFAULT),
        VcfWarnings.DEFAULT);
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addFormat(xxFormat).build();

    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "XX"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataReturnsNullWhenNoMetadataDeclared() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "1.5");
    VcfSample sample = new VcfSample(map);
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build(); // no ##FORMAT=<ID=DS,...>

    assertNull(sample.getProperty(position, metadata, "DS"));
  }

  @Test
  void testGenotypeAllelesAreImmutable() {
    List<String> alleles = new ArrayList<>(Arrays.asList("A", "T"));
    VcfSample.Genotype genotype = new VcfSample.Genotype(alleles, true);

    // mutating the list passed to the constructor must not affect the Genotype (defensive copy)
    alleles.add("G");
    assertEquals(Arrays.asList("A", "T"), genotype.getAlleles());

    // the returned list must itself be immutable
    assertThrows(UnsupportedOperationException.class, () -> genotype.getAlleles().add("G"));
  }

  @Test
  void testGenotypePhasedBoundariesAreImmutable() {
    List<Boolean> phasedBoundaries = new ArrayList<>(Arrays.asList(true, false));
    VcfSample.Genotype genotype =
        new VcfSample.Genotype(Arrays.asList("A", "T", "G"), phasedBoundaries);

    // mutating the list passed to the constructor must not affect the Genotype (defensive copy)
    phasedBoundaries.add(true);
    assertEquals(Arrays.asList(true, false), genotype.getPhasedBoundaries());

    // the returned list must itself be immutable
    assertThrows(UnsupportedOperationException.class, () -> genotype.getPhasedBoundaries().add(true));
  }

  @Test
  void testGenotypeToStringPreservesMixedPhasing() {
    // "0|1/2" -- boundary 0-1 is phased, boundary 1-2 is unphased; a single whole-genotype boolean cannot
    // represent this, so toString() must reconstruct using the actual per-boundary separator, not one uniform one
    VcfSample.Genotype genotype =
        new VcfSample.Genotype(List.of("0", "1", "2"), List.of(true, false));
    assertEquals("0|1/2", genotype.toString());
    assertEquals(List.of(true, false), genotype.getPhasedBoundaries());
    // isPhased() reports "fully phased" -- false here, since the second boundary is unphased
    assertFalse(genotype.isPhased());
  }

  @Test
  void testGenotypeToStringHandlesEmptyAllelesList() {
    // toString() must not throw for an empty alleles list -- the old Joiner-based implementation returned "" here;
    // a naive rewrite that special-cases the first element (m_alleles.get(0)) would throw IndexOutOfBoundsException
    VcfSample.Genotype genotype = new VcfSample.Genotype(List.of(), List.of());
    assertEquals("", genotype.toString());
  }

  @Test
  void testGenotypePhasedBoundariesArityValidation() {
    assertThrows(IllegalArgumentException.class, () ->
        new VcfSample.Genotype(List.of("0", "1", "2"), List.of(true)));
  }

  @Test
  void testGenotypeConstructorsRejectNullInsteadOfThrowingNpe() {
    // alleles/phasedBoundaries are typed non-@Nullable, but nothing enforces that at compile time; both
    // constructors must reject a null with VcfFormatException, not raw-NPE at alleles.size()/phasedBoundaries.size()
    assertThrows(VcfFormatException.class, () -> new VcfSample.Genotype(null, true));
    assertThrows(VcfFormatException.class, () -> new VcfSample.Genotype(null, List.of(true)));
    assertThrows(VcfFormatException.class,
        () -> new VcfSample.Genotype(List.of("A", "T"), (List<Boolean>) null));
  }

  @Test
  void testGenotypeConstructorsRejectNullElementInsteadOfThrowingNpe() {
    // the whole-list-null case above is already covered; List.of() itself rejects a null element at
    // construction time, so a non-null list containing a null entry needs Arrays.asList() to build. Both
    // constructors must reject that with VcfFormatException, not List.copyOf's raw, message-less NPE
    assertThrows(VcfFormatException.class,
        () -> new VcfSample.Genotype(Arrays.asList("A", null), false));
    assertThrows(VcfFormatException.class,
        () -> new VcfSample.Genotype(Arrays.asList("A", "T"), Arrays.asList((Boolean) null)));
  }

  @Test
  void testGenotypeLegacyConstructorSingleAlleleIsNeverPhased() {
    // a single-allele genotype has zero boundaries, so isPhased() is now always false regardless of the flag
    // passed to the legacy constructor -- matches what getGenotype's real parsing already produced for a
    // haploid GT (no separator to be "|"), so this only affects direct construction, not parsing
    VcfSample.Genotype genotype = new VcfSample.Genotype(List.of("1"), true);
    assertFalse(genotype.isPhased());
    assertTrue(genotype.getPhasedBoundaries().isEmpty());
  }

  @Test
  void testGetGenotypeReturnsNullForAbsentOrEmptyGt() {
    VcfSample noGt = new VcfSample(List.of("DP"), List.of("10"));
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    assertNull(noGt.getGenotype(position));

    VcfSample emptyGt = new VcfSample(List.of("GT"), new ArrayList<>(List.of("")));
    assertNull(emptyGt.getGenotype(position));
  }

  @Test
  void testGetGenotypeReturnsNullForFullyMissingCall() {
    VcfSample sample = new VcfSample(List.of("GT"), List.of("./."));
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    assertNull(sample.getGenotype(position));
  }

  @Test
  void testGetGenotypeResolvesAllelesAndPreservesMixedPhasing() {
    // "0|1/2" -- boundary 0-1 is phased, boundary 1-2 is unphased
    VcfSample sample = new VcfSample(List.of("GT"), List.of("0|1/2"));
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    position.getAltBases().add("C");
    VcfSample.Genotype genotype = sample.getGenotype(position);
    assertNotNull(genotype);
    assertEquals(List.of("A", "T", "C"), genotype.getAlleles());
    assertEquals(List.of(true, false), genotype.getPhasedBoundaries());
    assertEquals("A|T/C", genotype.toString());
  }

  @Test
  void testGetGenotypeThrowsForOutOfRangeAlleleIndex() {
    // ALT has one allele (T), so valid indices are 0 (REF) and 1; index 5 is out of range
    VcfSample sample = new VcfSample(List.of("GT"), List.of("5/0"));
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    assertThrows(VcfFormatException.class, () -> sample.getGenotype(position));
  }

  @Test
  void testGetGenotypeThrowsForNonNumericAlleleIndex() {
    VcfSample sample = new VcfSample(List.of("GT"), List.of("X/0"));
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    assertThrows(VcfFormatException.class, () -> sample.getGenotype(position));
  }

  @Test
  void testGetGenotypeEmptyAlleleWarnsToConfiguredSink() {
    // a trailing '/' (e.g. "0/") is a zero-length allele where '.' should have been used
    VcfSample sample = new VcfSample(List.of("GT"), new ArrayList<>(List.of("0/")));
    java.io.StringWriter sink = new java.io.StringWriter();
    sample.setWarningsSink(new VcfWarnings(sink));
    VcfPosition position = new VcfPosition("chr1", 1, "A", null, -1);
    position.getAltBases().add("T");
    VcfSample.Genotype genotype = sample.getGenotype(position);
    assertNotNull(genotype);
    assertEquals(2, genotype.getAlleles().size());
    assertEquals("A", genotype.getAlleles().get(0));
    assertEquals(".", genotype.getAlleles().get(1));
    assertTrue(sink.toString().contains("empty allele"));
  }

  @Test
  void testGetGenotypeReturnsNullForNullPosition() {
    // matches getProperty(VcfPosition, VcfMetadata, String)'s and VcfPosition.getInfo(ReservedInfoProperty)'s
    // null-tolerant contract -- must use a real (non-empty, non-missing) GT so this actually exercises the
    // position-dependent allele-resolution code path, not the earlier absent/missing-GT short-circuit
    VcfSample sample = new VcfSample(List.of("GT"), List.of("0/1"));
    assertNull(sample.getGenotype(null));
  }
}

package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.lenientContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    VcfSample sample = STRICT_CONTEXT.newSample(keys, values);
    assertEquals(2, sample.getPropertyKeys().size());
    assertEquals("value1", sample.getProperty("key1"));
  }

  @Test
  void testGetPropertyKeysRejectsDirectMutationInsteadOfSilentlyDefeatingIt() {
    // regression test: getPropertyKeys() used to return properties().keySet() directly -- a standard
    // Map.keySet() view where remove() deletes the entry from the backing map with no validation and no
    // signal to the caller. It now returns an unmodifiable view: a mutation attempt fails fast with
    // UnsupportedOperationException instead of silently corrupting the sample.
    VcfSample sample = STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    assertThrows(UnsupportedOperationException.class, () -> sample.getPropertyKeys().remove("GT"));
    assertEquals("0/1", sample.getProperty("GT"));
  }

  @Test
  void testOrder() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("1", "A");
    map.put("2", "B");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    assertEquals(Arrays.asList("1", "2"), new ArrayList<>(sample.getPropertyKeys()));

    LinkedHashMap<String, String> reversedMap = new LinkedHashMap<>();
    reversedMap.put("2", "B");
    reversedMap.put("1", "A");
    VcfSample sampleReverse = STRICT_CONTEXT.newSample(reversedMap);
    assertEquals(Arrays.asList("2", "1"), new ArrayList<>(sampleReverse.getPropertyKeys()));
  }

  @Test
  void testBadConstructor() {
    assertThrows(VcfFormatException.class, () -> {
      List<String> keys = Arrays.asList("key1", "key2");
      List<String> values = Collections.singletonList("value1");
      STRICT_CONTEXT.newSample(keys, values);
    });
  }

  @Test
  void testHasNewline() {
    assertThrows(VcfFormatException.class, () -> {
      List<String> keys = Collections.singletonList("key1");
      List<String> values = Collections.singletonList("value\n1");
      STRICT_CONTEXT.newSample(keys, values);
    });
  }

  @Test
  void testNewTrustedSkipsStructuralDelimiterCheckInProductionButSelfVerifiesUnderAssertions() {
    // regression/contract test for newTrustedSample()'s full, now two-part contract (see VcfSample's
    // trusted-branch Javadoc): in a production JVM (assertions disabled, the default for a real caller), it
    // skips checkNoStructuralDelimiter's scan entirely (the ~92% of construction cost this factory exists to
    // avoid) for input the public constructor still rejects. This project's own test JVM runs with assertions
    // enabled (Gradle's default for the `test` task), so under test the self-verifying assert added to
    // VcfSample's trusted branch catches exactly this violation instead of silently admitting it. Pins both
    // halves: the raw skip really exists (via the doubly-unchecked test-only bypass, which is
    // assertion-independent and so demonstrates what the trusted path itself does before self-verification is
    // layered on top), and the self-verification actually fires (via newTrustedSample itself, the method a
    // real caller goes through, in THIS test JVM).
    List<String> keys = Collections.singletonList("key1");
    List<String> values = Collections.singletonList("value:with:colons");
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(keys, values));
    VcfSample sample = VcfSample.newDoublyUncheckedForTest(STRICT_CONTEXT, keys, values);
    assertEquals("value:with:colons", sample.getProperty("key1"));
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newTrustedSample(keys, values));
  }

  @Test
  void testNewTrustedStillRejectsMismatchedKeyValueLengths() {
    // the cheap, O(1) length-mismatch check is NOT part of what newTrusted() skips -- only the O(n) per-element
    // structural-delimiter scan is
    List<String> keys = Arrays.asList("key1", "key2");
    List<String> values = Collections.singletonList("value1");
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newTrustedSample(keys, values));
  }

  @Test
  void testNewTrustedProducesEquivalentSampleToPublicConstructorForWellFormedInput() {
    // for input the public constructor would accept anyway (VcfParser's actual, always-well-formed output),
    // newTrusted() must produce an observably identical VcfSample
    List<String> keys = Arrays.asList("GT", "DP");
    List<String> values = Arrays.asList("0/1", "10");
    VcfSample trusted = STRICT_CONTEXT.newTrustedSample(keys, values);
    VcfSample untrusted = STRICT_CONTEXT.newSample(keys, values);
    assertEquals(untrusted.getPropertyKeys(), trusted.getPropertyKeys());
    assertEquals(untrusted.getProperty("GT"), trusted.getProperty("GT"));
    assertEquals(untrusted.getProperty("DP"), trusted.getProperty("DP"));
  }

  @Test
  void testBuilderPropertyDefersLineTerminatorRejectionToBuild() {
    // Task 9: VcfSample lost its putProperty mutator (there are no mutation routes left at all); Builder.property()
    // is the replacement, but -- unlike putProperty -- does not itself validate. build() re-validates the whole
    // property map via the same validating VcfContext.newSample the map-based properties() setter already goes
    // through, so a bad value is only caught there, not at property()'s own call site.
    VcfSample.Builder builder1 = STRICT_CONTEXT.newSample(new LinkedHashMap<>()).toBuilder()
        .property("GT", "0/1\nextra");
    assertThrows(VcfFormatException.class, builder1::build);
    VcfSample.Builder builder2 = STRICT_CONTEXT.newSample(new LinkedHashMap<>()).toBuilder()
        .property("GT", "0/1\rextra");
    assertThrows(VcfFormatException.class, builder2::build);
    VcfSample.Builder builder3 = STRICT_CONTEXT.newSample(new LinkedHashMap<>()).toBuilder()
        .property("bad\nkey", "value");
    assertThrows(VcfFormatException.class, builder3::build);
  }

  @Test
  void testMapConstructorRejectsNullPropertiesInsteadOfThrowingNpe() {
    // properties is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at m_properties.entrySet()
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample((LinkedHashMap<String, String>) null));
  }

  @Test
  void testReservedFormatPropertyReadOverloadsHandleNullKeyLikeTheirStringSiblings() {
    // getProperty/containsProperty(ReservedFormatProperty) must not raw-NPE at key.getId() for a null key -- each
    // matches its own String-keyed sibling's already-null-tolerant "not present" contract. (Task 9 removed
    // putProperty/removeProperty entirely -- there is no longer a mutator overload to check here.)
    VcfSample sample = STRICT_CONTEXT.newSample(new LinkedHashMap<>());
    assertNull(sample.getProperty((ReservedFormatProperty) null));
    assertFalse(sample.containsProperty((ReservedFormatProperty) null));
  }

  @Test
  void testBuilderPropertyDefersStructuralDelimiterRejectionToBuild() {
    // a colon would add a spurious FORMAT subfield, and a tab would add a spurious sample column, when written back
    // out; see testBuilderPropertyDefersLineTerminatorRejectionToBuild's comment for why this is a build()-time
    // check now rather than immediate, unlike the former putProperty
    VcfSample.Builder builder1 = STRICT_CONTEXT.newSample(new LinkedHashMap<>()).toBuilder().property("DP", "1:2");
    assertThrows(VcfFormatException.class, builder1::build);
    VcfSample.Builder builder2 = STRICT_CONTEXT.newSample(new LinkedHashMap<>()).toBuilder().property("DP", "1\t2");
    assertThrows(VcfFormatException.class, builder2::build);
    VcfSample.Builder builder3 = STRICT_CONTEXT.newSample(new LinkedHashMap<>()).toBuilder()
        .property("bad:key", "value");
    assertThrows(VcfFormatException.class, builder3::build);
    VcfSample.Builder builder4 = STRICT_CONTEXT.newSample(new LinkedHashMap<>()).toBuilder()
        .property("bad\tkey", "value");
    assertThrows(VcfFormatException.class, builder4::build);
  }

  @Test
  void testConstructorRejectsStructuralDelimiters() {
    assertThrows(VcfFormatException.class, () -> {
      List<String> keys = Collections.singletonList("DP");
      List<String> values = Collections.singletonList("1:2");
      STRICT_CONTEXT.newSample(keys, values);
    });
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("DP", "1:2");
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(map));
  }

  @Test
  void testGetPropertyReservedListTypeWithEmptyEntries() {
    // HQ is a list-typed reserved FORMAT property (Long); getProperty(ReservedFormatProperty) previously routed
    // through InternalUtils.convertProperty's plain value.split(","), which silently dropped a trailing empty entry and
    // threw an unhelpful generic exception for an interior one, unlike the rest of the empty-field handling.
    // Lenient context, not strict: an empty list entry now throws under strict (InternalUtils.convertProperty's
    // own isLenient()-aware dispatch) -- this test is about the substitution itself.
    LinkedHashMap<String, String> trailing = new LinkedHashMap<>();
    trailing.put("HQ", "1,2,");
    VcfSample trailingSample = LENIENT_CONTEXT.newSample(trailing);
    assertEquals(Arrays.asList(1L, 2L, null),
        trailingSample.getProperty(ReservedFormatProperty.HaplotypeQualities));

    LinkedHashMap<String, String> interior = new LinkedHashMap<>();
    interior.put("HQ", "1,,2");
    VcfSample interiorSample = LENIENT_CONTEXT.newSample(interior);
    assertEquals(Arrays.asList(1L, null, 2L),
        interiorSample.getProperty(ReservedFormatProperty.HaplotypeQualities));
  }

  @Test
  void testGetPropertyReservedListTypeWithEmptyEntryThrowsUnderStrictContext() {
    // a sample built under a strict VcfContext must throw VcfFormatException, not warn-and-substitute, when a
    // reserved list-typed key's value contains an empty entry. This now fires at construction, not at a later
    // getProperty() call: checkReservedFormatTypes eagerly runs this exact conversion for every reserved key
    // present -- manual construction only, never reachable via VcfParser, which always uses the trusted
    // parallel-list fast path this check never runs on.
    LinkedHashMap<String, String> trailing = new LinkedHashMap<>();
    trailing.put("HQ", "1,2,");
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(trailing));
    assertTrue(e.getMessage().contains("contains an empty entry"));
  }

  @Test
  void testGetPropertyFilterAllEmptyCodesReturnsNullNotEmptyString() {
    // regression test: when every semicolon-separated FT code is empty (e.g. ";;"), InternalUtils.
    // checkFilterLikeValue used to return "" (from String.join(";", emptyList)) instead of ".", this library's
    // established missing-value sentinel, making this getter return a non-null empty string for a sample whose
    // FT content was entirely empty artifacts, violating the null-means-absent contract every other reserved
    // property getter follows. The plain assertNull(...) below no longer alone distinguishes a correct "."
    // return from an incorrect "" one, now that InternalUtils.convertProperty's scalar branch also treats a
    // plain "" as absent for a non-Boolean type (so getProperty(...) would still return null either way) -- the
    // warnings-sink assertion re-establishes that distinction: an incorrect "" return would additionally trigger
    // convertProperty's own (context-free) "is an empty entry" warning, which checkFilterLikeValue's correct
    // "." return must never reach at all.
    // Task 7: setWarningsSink was removed -- route the sink through the sample's own construction-time context
    // (lenientContext(...)) instead of redirecting it afterward.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("FT", ";;");
    java.io.StringWriter sink = new java.io.StringWriter();
    VcfSample sample = lenientContext(new VcfWarnings(sink)).newSample(map);
    assertNull(sample.getProperty(ReservedFormatProperty.Filter));
    assertFalse(sink.toString().contains("is an empty entry"));
  }

  @Test
  void testGetPropertyReservedFormatPropertyFilterThrowsUnderStrictContext() {
    // a strict VcfContext must throw VcfFormatException when a grammar-invalid FT value is present --
    // InternalUtilsTest's own testCheckReservedFormatConstraintsFtThrowsUnderStrictForEachViolationShape only
    // exercises the package-private checkReservedFormatConstraints helper directly, not this actual call path
    // (VcfSample construction -> checkReservedFormatTypes -> InternalUtils.convertProperty(ReservedProperty, ...)
    // -> checkReservedFormatConstraints -> checkFilterLikeValue). This now fires at construction, not at a
    // later getProperty() call -- see testGetPropertyReservedListTypeWithEmptyEntryThrowsUnderStrictContext's
    // comment for why.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("FT", "PASS;q10");
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(map));
    assertTrue(e.getMessage().contains("combines PASS or '.' with other filter codes"));
  }

  @Test
  void testGetPropertyReservedFormatPropertyFilterThrowsUnderStrictContextForEmptyCode() {
    // sibling of the PASS-combined test above, covering FT's other two violation shapes end-to-end (not just
    // through the package-private checkReservedFormatConstraints/checkFilterLikeValue helpers)
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("FT", "q10;;q20");
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(map));
    assertTrue(e.getMessage().contains("contains an empty filter code"));
  }

  @Test
  void testGetPropertyReservedFormatPropertyFilterThrowsUnderStrictContextForWhitespace() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("FT", "q10 q20");
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(map));
    assertTrue(e.getMessage().contains("contains whitespace in filter code"));
  }

  @Test
  void testGetPropertyReservedFormatPropertyPhaseSetThrowsFirstViolationOnlyUnderStrictContext() {
    // PS's own checkNonNegative32BitInteger overlaps with the general checkInteger32BitRange for a value that
    // is both negative and a reserved sentinel -- under lenient this warns twice (see InternalUtilsTest's
    // testConvertPropertyReservedFormatPsWarnsTwiceForAReservedSentinelValue); under strict the first check to
    // run (checkNonNegative32BitInteger, inside checkReservedFormatConstraints, called before the general
    // range check in convertProperty's own dispatch order) throws before the second ever runs, so the caller
    // must see only its message, never the more specific "BCF-reserved sentinel" one from checkInteger32BitRange.
    // Fires at construction now, not at a later getProperty() call -- same reason as the FT tests above.
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("PS", "-2147483648"))));
    assertTrue(e.getMessage().contains("is not a non-negative 32-bit integer"));
    assertFalse(e.getMessage().contains("BCF-reserved sentinel"));
  }

  @Test
  void testGetPropertyReservedFormatPropertyGenotypePosteriorThrowsUnderStrictContextAt43() {
    // a strict VCFv4.3 VcfContext must throw VcfFormatException for an out-of-[0,1]-range GP value. This is the
    // strict-context coverage that testGetPropertyReservedFormatPropertyGenotypePosteriorRangeCheckIsVersionGated
    // no longer provides for its 4.3 case (that test switched to lenient specifically to observe the warning
    // rather than the throw, once checkUnitInterval became isLenient()-aware) -- a dedicated test restores
    // end-to-end strict coverage without reintroducing that test's own conflict with the version-gate check.
    // Fires at construction now, not at a later getProperty() call -- same reason as the FT tests above.
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> strict43.newSample(new LinkedHashMap<>(Map.of("GP", "5.0"))));
    assertTrue(e.getMessage().contains("does not lie in the interval [0, 1]"));
  }

  @Test
  void testStructuralVariantFormatPropertiesAreIntegerTyped() {
    // NQ, HAP, and AHAP were previously declared with the wrong Type (Float/String); VCFv4.2's own ##FORMAT
    // declarations for these keys all state Type=Integer
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("NQ", "30");
    map.put("HAP", "1");
    map.put("AHAP", "2");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    assertEquals(Long.valueOf(30L), sample.getProperty(ReservedFormatProperty.PhredScoreForNovelty));
    assertEquals(Long.valueOf(1L), sample.getProperty(ReservedFormatProperty.HaplotypeId));
    assertEquals(Long.valueOf(2L), sample.getProperty(ReservedFormatProperty.AncestralHaplotypeId));
  }

  @Test
  void testAlleleDepthAndPosteriorProbabilityKeysAreListTyped() {
    // AD/ADF/ADR/PP were previously missing from ReservedFormatProperty entirely, so getProperty(
    // ReservedFormatProperty) could not be called with them at all. All four are VCFv4.3 additions
    // (ReservedFormatProperty.isAddedAtV4_3), so this must use a VCFv4.3+ context -- STRICT_CONTEXT is
    // VCFv4.2, under which these keys are not yet reserved.
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("AD", "10,5");
    map.put("ADF", "6,3");
    map.put("ADR", "4,2");
    map.put("PP", "0,3,10");
    VcfSample sample = strict43.newSample(map);
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
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    assertEquals(Long.valueOf(45L), sample.getProperty(ReservedFormatProperty.MappingQuality));
  }

  @Test
  void testGenotypeLikelihoodsOfHeterogenousPloidyIsSingleValued() {
    // GLE was previously declared isList=true, which would split the spec's own example value on its internal
    // commas; it's one opaque String, not a list of independent values
    String gle = "0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53";
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GLE", gle);
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    assertEquals(gle, sample.getProperty(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy));
  }

  @Test
  void testGleValueIsExemptFromColonCheck() {
    // GLE's own spec example (e.g. "0:-75.22,1:-223.42,...") uses colons internally as part of its own encoding; it
    // is the one key exempted from the colon check on its value. The key itself is still checked, and every other
    // key's value is unaffected.
    String gle = "0:-75.22,1:-223.42,0/0:-323.03";
    VcfSample viaMap = STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GLE", gle)));
    assertEquals(gle, viaMap.getProperty("GLE"));
    VcfSample viaConstructor = STRICT_CONTEXT.newSample(Collections.singletonList("GLE"),
        Collections.singletonList(gle));
    assertEquals(gle, viaConstructor.getProperty("GLE"));

    // the exemption is specific to the key "GLE"; a colon in any other key's value is still rejected
    LinkedHashMap<String, String> badValue = new LinkedHashMap<>();
    badValue.put("DP", gle);
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(badValue));
    // a colon in the key "GLE" itself (not its value) is still rejected
    LinkedHashMap<String, String> badKey = new LinkedHashMap<>();
    badKey.put("GLE:bad", "value");
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(badKey));
    // a tab in GLE's value is still rejected (tab always means a spurious sample column, regardless of key)
    LinkedHashMap<String, String> badTab = new LinkedHashMap<>();
    badTab.put("GLE", "a\tb");
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newSample(badTab));
  }

  @Test
  void testGleColonExemptionIsVersionGated() {
    // behavior 3: VCFv4.3 removed GLE (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT tag"),
    // so from 4.3 a key named GLE is an ordinary custom key and a colon in its value is rejected like any other
    // key's -- it would add a spurious FORMAT sub-field on write. Only direct construction can show this: the
    // parser never produces a colon-bearing value at all (it split on colons to get here).
    String gle = "0:-75.22,1:-223.42,0/0:-323.03";
    VcfContext context43 = VcfContext.strict("VCFv4.3");

    // accepted under 4.2 through both direct-construction routes
    assertEquals(gle, STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GLE", gle))).getProperty("GLE"));
    assertEquals(gle, new VcfSample.Builder(STRICT_CONTEXT).property("GLE", gle).build().getProperty("GLE"));

    // rejected under 4.3 through the same two routes
    LinkedHashMap<String, String> map43 = new LinkedHashMap<>(Map.of("GLE", gle));
    assertThrows(VcfFormatException.class, () -> context43.newSample(map43));
    assertThrows(VcfFormatException.class,
        () -> new VcfSample.Builder(context43).property("GLE", gle).build());
    // ... and through the parallel-list constructor, the third route into checkNoStructuralDelimiter
    assertThrows(VcfFormatException.class,
        () -> context43.newSample(Collections.singletonList("GLE"), Collections.singletonList(gle)));

    // a colon-free GLE value is still fine under 4.3 -- this gate is about the colon exemption, not the key
    assertEquals("abc", context43.newSample(new LinkedHashMap<>(Map.of("GLE", "abc"))).getProperty("GLE"));
  }

  @Test
  void testConstructionDoesNotWarnForGleUnder43EvenThoughItsNotReservedThere() {
    // regression test: checkReservedFormatTypes (both overloads) used to iterate every ReservedFormatProperty
    // constant and probe the sample's keys for a match, ignoring the VcfContext's version entirely -- so a
    // legitimate custom key spelled "GLE" under a 4.3+ context (where VCFv4.3 removed GLE as reserved -- see
    // testGleColonExemptionIsVersionGated above) still matched GenotypeLikelihoodsOfHeterogenousPloidy and was
    // handed to InternalUtils.convertProperty, which emits "not a reserved key under VCF 4.3... ignoring this
    // reserved-property read" -- misleading, since nothing was being read; this is a construction-time probe,
    // not a getProperty(ReservedFormatProperty) call. Fixed by resolving each key via the version-aware
    // ReservedFormatProperty.fromId(key, version), matching getProperty(String)'s own classification.
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      context43.newSample(new LinkedHashMap<>(Map.of("GLE", "abc")));
      assertFalse(appender.anyMessageContains("not a reserved key"));

      context43.newSample(Collections.singletonList("GLE"), Collections.singletonList("abc"));
      assertFalse(appender.anyMessageContains("not a reserved key"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyReservedFormatPropertyGleIsVersionGated() {
    // behavior 6: the enum-key accessor consults neither metadata nor (before this gate) the version, so under
    // 4.3 a caller passing the removed key's constant would still get its historical interpretation. It
    // delegates to InternalUtils.convertProperty(ReservedProperty, ...), where the single guard lives (behavior
    // 8); this test covers the delegating route, InternalUtilsTest covers the direct one.
    String gle = "0:-75.22,1:-223.42";
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    VcfSample sample42 = STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GLE", gle)));
    // colon-free under 4.3: a colon-bearing value can no longer be constructed there at all (see
    // testGleColonExemptionIsVersionGated), which is exactly why this behavior needs its own fixture
    VcfSample sample43 = context43.newSample(new LinkedHashMap<>(Map.of("GLE", "abc")));

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(gle,
          sample42.getProperty(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy));
      assertFalse(appender.anyMessageContains("not a reserved key"));

      assertNull(sample43.getProperty(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy));
      assertTrue(appender.anyMessageContains("FORMAT GLE is not a reserved key under VCF 4.3"));
      // warn-and-null, never a silent null: silence would be indistinguishable from an absent value. This route
      // never consults metadata, so a 4.3 file that DOES declare ##FORMAT=<ID=GLE,...> gets the same answer --
      // getProperty(VcfPosition, VcfMetadata, String) is the overload that honors the declaration.
      assertEquals(1, appender.countMessagesContaining("FORMAT GLE is not a reserved key"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testContainsPropertyReservedFormatPropertyGleIsVersionGated() {
    // behavior 7: a predicate never converts, so it cannot ride on the converter's guard. Under 4.3 the truthful
    // answer is false -- the sample carries a key NAMED GLE, but not the reserved property being asked about.
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    VcfSample sample42 = STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GLE", "abc")));
    VcfSample sample43 = context43.newSample(new LinkedHashMap<>(Map.of("GLE", "abc")));

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertTrue(sample42.containsProperty(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy));
      assertFalse(appender.anyMessageContains("not a reserved key"));

      assertFalse(sample43.containsProperty(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy));
      assertTrue(appender.anyMessageContains("FORMAT GLE is not a reserved key under VCF 4.3"));
      // the other question -- "is a custom key named GLE present?" -- still answers true under 4.3
      assertTrue(sample43.containsProperty("GLE"));
      // and the gate is specific to GLE: every other reserved constant is unaffected under 4.3
      VcfSample gt43 = context43.newSample(new LinkedHashMap<>(Map.of("GT", "0|1")));
      assertTrue(gt43.containsProperty(ReservedFormatProperty.Genotype));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataGleClassificationIsVersionGated() {
    // behavior 4, observed through the metadata-aware read: under 4.2 an UNDECLARED GLE still gets this
    // library's reserved canonical Number/Type (so the opaque value comes back); under 4.3 GLE is no longer a
    // reserved key, so it takes the ordinary "no metadata declaration" path instead. Neither the enum-key
    // accessor's tests nor the parser-splitting tests can observe classification at all.
    String gle = "0:-75.22,1:-223.42";
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    LinkedHashMap<String, String> map42 = new LinkedHashMap<>();
    map42.put("GT", "0|1");
    map42.put("GLE", gle);
    VcfSample sample42 = STRICT_CONTEXT.newSample(map42);
    VcfPosition position42 = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata42 = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

    LinkedHashMap<String, String> map43 = new LinkedHashMap<>();
    map43.put("GT", "0|1");
    map43.put("GLE", "abc");
    VcfSample sample43 = context43.newSample(map43);
    VcfPosition position43 = context43.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata43 = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(gle, sample42.getProperty(position42, metadata42, "GLE"));
      assertFalse(appender.anyMessageContains("has no metadata declaration"));

      assertNull(sample43.getProperty(position43, metadata43, "GLE"));
      assertTrue(appender.anyMessageContains("FORMAT GLE has no metadata declaration"));
      // and it is NOT the removed-reserved-key warning: this route reaches the ordinary custom-key path, not
      // the converter's GLE guard
      assertFalse(appender.anyMessageContains("not a reserved key"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataDeclaredGleIsAnOrdinaryKeyUnder43() {
    // behavior 4's other half: a 4.3 file that DOES declare ##FORMAT=<ID=GLE,...> gets its value validated
    // against its own declaration like any custom key -- here Number=2/Type=Integer, so a two-element value
    // converts to a List<Long> with no cardinality warning. Under 4.2 the same declaration would be overridden
    // by GLE's reserved canonical Number=G/Type=String (see testGetPropertyWithPositionAndMetadataExemptsGle
    // FromCardinalityCheck), which is exactly the difference this gate introduces.
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("GLE", "7,8");
    VcfSample sample = context43.newSample(map);
    VcfPosition position = context43.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3")
        .addFormat(new FormatMetadata(context43, "GLE", "custom", "2", FormatType.Integer))
        .build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(List.of(7L, 8L), sample.getProperty(position, metadata, "GLE"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testPropertiesMaterializationKeepsFirstOccurrenceOnDuplicateKey() {
    // properties() (the materialization path triggered by getPropertyKeys()/propertyEntrySet()/validate())
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
    VcfSample sample = STRICT_CONTEXT.newSample(keys, values);

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
    VcfSample sample = STRICT_CONTEXT.newSample(keys, values);
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
    VcfSample sample = STRICT_CONTEXT.newSample(keys, values);
    assertFalse(sample.containsProperty((String) null));
  }

  @Test
  void testContainsPropertyReturnsFalseForNullKeyAfterMaterialization() {
    // pins that both internal-state paths (lean-list above, materialized-map here) converge on the same
    // false answer for a null key
    List<String> keys = Arrays.asList("key1", "key2");
    List<String> values = Arrays.asList("value1", "value2");
    VcfSample sample = STRICT_CONTEXT.newSample(keys, values);
    sample.getPropertyKeys(); // forces materialization of m_properties
    assertFalse(sample.containsProperty((String) null));
  }

  @Test
  void testValidateWarnsToConfiguredSinkForEmptyValue() {
    // Task 7: setWarningsSink was removed -- the sink is now fixed at construction via the sample's own context.
    java.io.StringWriter sink = new java.io.StringWriter();
    VcfSample sample = lenientContext(new org.pharmgkb.parser.vcf.VcfWarnings(sink))
        .newSample(List.of("DP"), new ArrayList<>(List.of("")));
    sample.validate();
    assertTrue(sink.toString().contains("has an empty value"));
    assertEquals(".", sample.getProperty("DP"));
  }

  @Test
  void testDuplicateFormatKeyWarnsToConfiguredSinkOnLazyMaterialization() {
    // Task 7: setWarningsSink was removed -- the sink is now fixed at construction via the sample's own context.
    java.io.StringWriter sink = new java.io.StringWriter();
    VcfSample sample = lenientContext(new org.pharmgkb.parser.vcf.VcfWarnings(sink))
        .newSample(List.of("DP", "DP"), List.of("1", "2"));
    assertEquals("1", sample.getPropertyKeys().contains("DP") ? sample.getProperty("DP") : null);
    assertTrue(sink.toString().contains("duplicate FORMAT key"));
  }

  @Test
  void testDefaultWarningsSinkIsSharedLoggerWhenNeverConfigured() {
    // "XX" (not a reserved key) rather than "DP": this test is about validate()'s own always-warn empty-value
    // handling, unrelated to checkReservedFormatTypes' new construction-time reserved-key check, which would
    // otherwise throw immediately for DP's own empty value under this strict context
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("XX"), new ArrayList<>(List.of("")));
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
    // combinationsWithRepetition(2, 2) = 3, but only 2 values given -- must warn, but still return the full list.
    // Uses LENIENT_CONTEXT, not STRICT_CONTEXT: a cardinality mismatch now throws under strict (see
    // testGetPropertyWithPositionAndMetadataCardinalityViolationThrowsUnderStrict below) -- this test is
    // specifically about the warn path.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("PL", "1,2");
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();

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
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

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
  void testGetPropertyReservedFormatPropertyGenotypePosteriorRangeCheckIsVersionGated() {
    // GP's [0, 1] range check applies only under VCFv4.3+ (VCFv4.3.tex line 491; see
    // ReservedFormatProperty.GenotypePosterior's own comment) -- under <=4.2 any Float is a legitimate phred
    // value, so the same out-of-range value must not warn there. sample42 deliberately stays STRICT_CONTEXT: the
    // check must not merely warn-instead-of-throw under 4.2, it must not run at all, and a strict context would
    // surface "runs but doesn't throw" as a bug via an unexpected VcfFormatException. sample43 must be lenient,
    // not strict, now that the check itself is isLenient()-aware (InternalUtils.checkUnitInterval) -- under
    // strict it throws instead of warning, which is covered separately by
    // InternalUtilsTest.testCheckReservedFormatConstraintsGenotypePosteriorThrowsUnderStrictWhenVersionGateApplies.
    VcfContext context43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    VcfSample sample42 = STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GP", "5.0")));
    VcfSample sample43 = context43.newSample(new LinkedHashMap<>(Map.of("GP", "5.0")));

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      List<VcfFloat> result42 = sample42.getProperty(ReservedFormatProperty.GenotypePosterior);
      assertEquals(1, result42.size());
      assertFalse(appender.anyMessageContains("does not lie in the interval"));

      List<VcfFloat> result43 = sample43.getProperty(ReservedFormatProperty.GenotypePosterior);
      assertEquals(1, result43.size());
      assertTrue(appender.anyMessageContains("does not lie in the interval"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetPropertyWithPositionAndMetadataGenotypePosteriorMultiValuedWarnsOnceForMiddleElement() {
    // GP is Number=G; on a diploid (GT=0|1), one-ALT-allele record, Number=G expects exactly
    // combinationsWithRepetition(2, 2) = 3 values (matching testGetPropertyWithPositionAndMetadataChecksReserved
    // ListCardinality's own PL math above), so this correctly-sized 3-value GP list isolates the range-check
    // assertion from any Number=G cardinality warning. The middle element (5.0) is out of [0, 1]; an
    // implementation that scalar-parsed the whole "0.2,5.0,0.8" value would throw NumberFormatException, fall
    // through to ordinary list conversion, and silently accept it.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("GP", "0.2,5.0,0.8");

    // sample43/context43 must be lenient, not strict, now that checkUnitInterval is isLenient()-aware -- under
    // strict it throws instead of warning (covered separately in InternalUtilsTest); sample42/STRICT_CONTEXT
    // stays strict, since under <=4.2 the check doesn't run at all and a strict context proves that more
    // strongly than a lenient one would (a throw here would mean the check ran when it shouldn't have).
    VcfContext context43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    VcfSample sample42 = STRICT_CONTEXT.newSample(new LinkedHashMap<>(map));
    VcfPosition position42 = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata42 = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

    VcfSample sample43 = context43.newSample(new LinkedHashMap<>(map));
    VcfPosition position43 = context43.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata43 = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3").build();

    List<VcfFloat> expected = Arrays.asList(VcfFloat.of(new java.math.BigDecimal("0.2")),
        VcfFloat.of(new java.math.BigDecimal("5.0")), VcfFloat.of(new java.math.BigDecimal("0.8")));

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Object result42 = sample42.getProperty(position42, metadata42, "GP");
      assertEquals(expected, result42);
      assertFalse(appender.anyMessageContains("does not lie in the interval"));
      // the cardinality is correct (3 values for ploidy 2, 1 ALT allele); a wrong-cardinality warning here would
      // mask whether the range check itself actually ran
      assertFalse(appender.anyMessageContains("value(s), but Number=G requires"));

      Object result43 = sample43.getProperty(position43, metadata43, "GP");
      assertEquals(expected, result43);
      assertEquals(1, appender.countMessagesContaining("does not lie in the interval"));
      assertTrue(appender.anyMessageContains("5.0"));
      assertFalse(appender.anyMessageContains("value(s), but Number=G requires"));
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
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

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
    // expect 3 values, but a real CNP for 5 possible copy-number states legitimately has 5; must not warn.
    // CNP has no VCFv4.2 declaration at all (ReservedFormatProperty.isAddedAtV4_3), so this must use a
    // VCFv4.3+ context -- STRICT_CONTEXT is VCFv4.2, under which CNP is not yet reserved at all.
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("CNP", "0.1,0.2,0.3,0.2,0.2");
    VcfSample sample = context43.newSample(map);
    VcfPosition position = context43.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3").build();

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
  void testGetPropertyWithPositionAndMetadataExemptsCnlFromCardinalityCheckAtV43() {
    // same shape as testGetPropertyWithPositionAndMetadataExemptsCnpFromCardinalityCheck above, for CNL: its
    // Number=G means "one value per potential copy number" only from VCFv4.3 on (VCFv4.2.tex:308 declares it
    // Number=.; VCFv4.3.tex:678 redeclares it Number=G -- see ReservedFormatProperty.CopyNumberLikelihood's
    // own comment), so the exemption from the ploidy-derived Number=G cardinality formula must fire under a
    // 4.3 context. GT=0|1 (ploidy 2), 1 ALT allele would make the formula expect 3 values, but a real CNL for
    // 5 possible copy-number states legitimately has 5; must not warn.
    VcfContext context43 = VcfContext.strict("VCFv4.3");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("CNL", "-0.1,-0.2,-0.3,-0.2,-0.2");
    VcfSample sample = context43.newSample(map);
    VcfPosition position = context43.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(context43).setFileFormat("VCFv4.3").build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals(java.util.Arrays.asList(
          VcfFloat.of(new java.math.BigDecimal("-0.1")), VcfFloat.of(new java.math.BigDecimal("-0.2")),
          VcfFloat.of(new java.math.BigDecimal("-0.3")), VcfFloat.of(new java.math.BigDecimal("-0.2")),
          VcfFloat.of(new java.math.BigDecimal("-0.2"))), sample.getProperty(position, metadata, "CNL"));
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
    FormatMetadata xxFormat = new FormatMetadata(STRICT_CONTEXT, "XX", "Custom Number=G field", "G",
        FormatType.Integer);
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("XX", "1,2");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").addFormat(xxFormat).build();

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
    // only 2 are given, so this must warn under lenient (regression test for a prior bug: this used to silently
    // skip the check entirely whenever GT was absent, for GL/PL/GP/PP same as any other Number=G field). Uses
    // LENIENT_CONTEXT, not STRICT_CONTEXT: this test is about the warn path specifically -- see
    // testGetPropertyWithPositionAndMetadataCardinalityViolationThrowsUnderStrict for the strict throw.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("PL", "1,2");
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();

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
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

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
    // read-path way to detect before this method -- must warn under lenient and keep only the first piece, not
    // silently pass the whole comma-containing value through as one opaque String. Uses LENIENT_CONTEXT, not
    // STRICT_CONTEXT: this test is about the warn path specifically -- see
    // testGetPropertyWithPositionAndMetadataCardinalityViolationThrowsUnderStrict below for the strict throw.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("FT", "STD_FILTER,extra");
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").build();

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
  void testGetPropertyWithPositionAndMetadataCardinalityViolationThrowsUnderStrict() {
    // the actual fix: a strict VcfContext now throws VcfFormatException for a cardinality mismatch instead of
    // warning and best-effort truncating to the first comma-piece -- same shape as
    // testGetPropertyWithPositionAndMetadataTruncatesScalarCardinalityViolation above (a reserved scalar key,
    // FT, given a comma-corrupted value), but under STRICT_CONTEXT. This is the original motivating case: a
    // scalar-declared FORMAT value containing a literal comma silently truncates on typed access under lenient,
    // which is real (if bcftools-harmless) data loss for a caller of this library's own typed getter.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("FT", "STD_FILTER,extra");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> sample.getProperty(position, metadata, "FT"));
    assertTrue(ex.getMessage().contains("FORMAT FT has 2 value(s), but Number=1 requires 1"));

    // a genuine list-cardinality mismatch (not just the comma-in-scalar shape) also throws under strict, since
    // the fix generalizes InternalUtils.warnIfWrongCardinality itself, not just the scalar case
    LinkedHashMap<String, String> plMap = new LinkedHashMap<>();
    plMap.put("GT", "0|1");
    plMap.put("PL", "1,2");
    VcfSample plSample = STRICT_CONTEXT.newSample(plMap);
    VcfFormatException plEx = assertThrows(VcfFormatException.class,
        () -> plSample.getProperty(position, metadata, "PL"));
    assertTrue(plEx.getMessage().contains("FORMAT PL has 2 value(s), but Number=G requires 3"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataReturnsNullForAbsentValueOrNullKey() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

    assertNull(sample.getProperty(position, metadata, "PL")); // not present on this sample
    assertNull(sample.getProperty(position, metadata, (String) null));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataThrowsNpeForNullPosition() {
    // position/metadata == null is a Java-null-argument problem, not VCF content (AGENTS.md's Public API
    // Null-Safety Policy) -- key == null keeps its existing null-tolerant "not present" contract above, but
    // position/metadata now throw rather than silently returning null (indistinguishable from "not found").
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();
    assertThrows(NullPointerException.class, () -> sample.getProperty(null, metadata, "GT"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataThrowsNpeForNullMetadata() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    assertThrows(NullPointerException.class, () -> sample.getProperty(position, null, "GT"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataRejectsPositionFromDifferentContext() {
    // The two contexts are deliberately equal in value (both declared "VCFv4.2") and differ only by identity --
    // matching VcfContextTest.testRecordRejectsComponentsFromDifferentContexts's precedent -- so this fails if
    // the implementation ever compares VcfVersion values instead of VcfContext references.
    VcfContext sampleContext = VcfContext.strict("VCFv4.2");
    VcfContext otherContext = VcfContext.strict("VCFv4.2");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("PL", "1,2,3");
    VcfSample sample = sampleContext.newSample(map);
    VcfPosition position = otherContext.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(sampleContext).setFileFormat("VCFv4.2").build();
    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "PL"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataRejectsMetadataFromDifferentContext() {
    // Same identity-vs-value rationale as the position case above, for the metadata argument.
    VcfContext sampleContext = VcfContext.strict("VCFv4.2");
    VcfContext otherContext = VcfContext.strict("VCFv4.2");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("PL", "1,2,3");
    VcfSample sample = sampleContext.newSample(map);
    VcfPosition position = sampleContext.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(otherContext).setFileFormat("VCFv4.2").build();
    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "PL"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataRejectsMismatchAcrossDifferentVersions() {
    // The scenario this task closes: a 4.2 sample read against 4.3 position/metadata was constructible before
    // this check existed (see AGENTS.md-adjacent task brief's intro) -- must throw now, not silently resolve
    // ALT/ploidy/FORMAT semantics against the wrong version.
    VcfContext sampleContext = VcfContext.strict("VCFv4.2");
    VcfContext otherVersionContext = VcfContext.strict("VCFv4.3");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("PL", "1,2,3");
    VcfSample sample = sampleContext.newSample(map);
    VcfPosition position = otherVersionContext.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null,
        null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(otherVersionContext).setFileFormat("VCFv4.3").build();
    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "PL"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataRejectsMismatchRegardlessOfAbsentValue() {
    // Pins the exact placement of the context check: it must run before the two ordinary absence exits
    // (rawValue == null, and rawValue.equals(".")) so a structural mismatch never depends on whether the
    // requested key happens to have a value. "PL" is absent from this sample -- an implementation that placed
    // the check after the rawValue == null exit would wrongly return null here instead of throwing.
    VcfContext sampleContext = VcfContext.strict("VCFv4.2");
    VcfContext otherContext = VcfContext.strict("VCFv4.2");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    VcfSample sample = sampleContext.newSample(map);
    VcfPosition position = otherContext.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(sampleContext).setFileFormat("VCFv4.2").build();
    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "PL"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataRejectsMismatchRegardlessOfDotValue() {
    // Same placement pin as above, for the second ordinary absence exit: "PL" is present but "." (the
    // missing-value sentinel), which getProperty(VcfPosition, VcfMetadata, String) itself treats as absent --
    // an implementation that placed the context check after that exit would wrongly return null instead of
    // throwing.
    VcfContext sampleContext = VcfContext.strict("VCFv4.2");
    VcfContext otherContext = VcfContext.strict("VCFv4.2");
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("PL", ".");
    VcfSample sample = sampleContext.newSample(map);
    VcfPosition position = otherContext.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(sampleContext).setFileFormat("VCFv4.2").build();
    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "PL"));
  }

  @Test
  void testGetPropertyReservedFormatPropertyOverloadHasNoSecondContextToMismatch() {
    // getProperty(ReservedFormatProperty) takes only this sample -- there is no position/metadata argument, so
    // there is no second VcfContext it could ever mismatch against; it already resolves its version from this
    // sample's own m_context, unaffected by the new check on the three-object overload above. Named explicitly
    // because this method is a version-sensitive read entry point that could otherwise become a second,
    // easily-missed enforcement site later.
    VcfSample sample = STRICT_CONTEXT.newSample(new LinkedHashMap<>(Map.of("GT", "0/1")));
    assertEquals("0/1", sample.getProperty(ReservedFormatProperty.Genotype));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataHandlesNonReservedScalarKey() {
    // DS is declared Number=1/Type=Float in metadata but is not a ReservedFormatProperty
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "1.5");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata dsFormat = new FormatMetadata(STRICT_CONTEXT, "DS", "Genotype dosage", "1", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").addFormat(dsFormat).build();

    assertEquals(VcfFloat.of(new java.math.BigDecimal("1.5")), sample.getProperty(position, metadata, "DS"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataWarnsOnNonReservedScalarCardinalityViolation() {
    // Uses LENIENT_CONTEXT, not STRICT_CONTEXT: this test is about the warn path specifically -- a cardinality
    // mismatch now throws under strict.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "1.5,2.5");
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata dsFormat = new FormatMetadata(LENIENT_CONTEXT, "DS", "Genotype dosage", "1", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(dsFormat).build();

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
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T", "C"), null, null, null,
    null, -1);
    FormatMetadata alFormat = new FormatMetadata(STRICT_CONTEXT, "AL", "Alt allele field", "A", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").addFormat(alFormat).build();

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
  void testGetPropertyWithPositionAndMetadataWarnsOnNonReservedIntegerOverflowUnderLenientContext() {
    // Task 2 wired InternalUtils.checkInteger32BitRange into the reserved-key path; this pins the same check
    // for a non-reserved key's own ##FORMAT declaration (Type=Integer -> Long.class) -- 2^31 is one past
    // Integer.MAX_VALUE, so it warns rather than throwing under a lenient context.
    java.io.StringWriter sink = new java.io.StringWriter();
    VcfContext ctx = lenientContext(new VcfWarnings(sink));
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("CUSTOM", "2147483648");
    VcfSample sample = ctx.newSample(map);
    VcfPosition position = ctx.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata customFormat = new FormatMetadata(ctx, "CUSTOM", "custom integer", "1", FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2").addFormat(customFormat).build();

    Long value = sample.getProperty(position, metadata, "CUSTOM");
    assertEquals(Long.valueOf(2147483648L), value);
    assertTrue(sink.toString().contains("outside the 32-bit signed range"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataThrowsOnNonReservedIntegerOverflowUnderStrictContext() {
    // Same as testGetPropertyWithPositionAndMetadataWarnsOnNonReservedIntegerOverflowUnderLenientContext above,
    // but under a strict context, where checkInteger32BitRange throws instead of warning.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("CUSTOM", "2147483648");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata customFormat = new FormatMetadata(STRICT_CONTEXT, "CUSTOM", "custom integer", "1",
        FormatType.Integer);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addFormat(customFormat).build();

    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> sample.getProperty(position, metadata, "CUSTOM"));
    assertTrue(ex.getMessage().contains("outside the 32-bit signed range"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataThrowsOnNonReservedTypeMismatch() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "my_string");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata dsFormat = new FormatMetadata(STRICT_CONTEXT, "DS", "Genotype dosage", "1", FormatType.Float);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").addFormat(dsFormat).build();

    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "DS"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataTreatsMissingValueSentinelAsAbsent() {
    // a record that legitimately drops a trailing FORMAT sub-field gets '.'-filled by the parser; "." is the
    // missing-value sentinel, not a real 1-element value, so it must not be measured against Number=G
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0|1");
    map.put("GL", ".");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();

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
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata xxFormat = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=String", "Description=\"no Number\""}, VcfWarnings.DEFAULT));
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(xxFormat).build();

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
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata xxFormat = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=Integer", "Description=\"no Number\""}, VcfWarnings.DEFAULT));
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(xxFormat).build();

    assertEquals(List.of(1L, 2L, 3L), sample.getProperty(position, metadata, "XX"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataUndeclaredNumberSingleValueStillWorksForNonStringType() {
    // sanity check: a single-value (no comma) sample value for an undeclared-Number non-String key must
    // still convert correctly once treated as list-shaped -- a one-element list, not a crash
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("XX", "5");
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata xxFormat = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=Integer", "Description=\"no Number\""}, VcfWarnings.DEFAULT));
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(xxFormat).build();

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
    VcfSample sample = LENIENT_CONTEXT.newSample(map);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    FormatMetadata xxFormat = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[] {"ID=XX", "Type=Float", "Description=\"no Number\""}, VcfWarnings.DEFAULT));
    assertNull(xxFormat.getNumber());
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(xxFormat).build();

    assertThrows(VcfFormatException.class, () -> sample.getProperty(position, metadata, "XX"));
  }

  @Test
  void testGetPropertyWithPositionAndMetadataReturnsNullWhenNoMetadataDeclared() {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GT", "0/1");
    map.put("DS", "1.5");
    VcfSample sample = STRICT_CONTEXT.newSample(map);
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build(); // no ##FORMAT=<ID=DS,...>

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
    // noGt (the key is absent entirely) stays STRICT_CONTEXT: that case never reaches convertProperty's
    // empty-value substitution at all, so strict proves the absent-key path doesn't throw where it shouldn't.
    // emptyGt (GT="") must be LENIENT_CONTEXT instead, now that an empty value throws under strict
    // (InternalUtils.convertProperty's own isLenient()-aware dispatch) -- this test is about the substitution
    // itself, covered separately for strict by testGetPropertyEmptyGtThrowsUnderStrictContext below.
    VcfSample noGt = STRICT_CONTEXT.newSample(List.of("DP"), List.of("10"));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    assertNull(noGt.getGenotype(position));

    VcfSample emptyGt = LENIENT_CONTEXT.newSample(List.of("GT"), new ArrayList<>(List.of("")));
    assertNull(emptyGt.getGenotype(position));
  }

  @Test
  void testGetPropertyEmptyGtThrowsUnderStrictContext() {
    // a sample built under a strict VcfContext must throw VcfFormatException, not warn-and-substitute, when a
    // reserved scalar String-typed key's (GT's) value is empty. This now fires at construction time --
    // checkReservedFormatTypes eagerly runs this exact conversion for every reserved key present, so there is
    // no longer a way to construct such a sample under strict at all, let alone reach getProperty/getGenotype
    // with it afterward. This single test now covers what used to be two separate end-to-end pins
    // (testGetPropertyEmptyGtThrowsUnderStrictContext at the getProperty level, and a since-removed
    // testGetGenotypeThrowsUnderStrictContextForEmptyGt at the getGenotype level) -- both are the same
    // construction-time throw now, so keeping both would only duplicate the same assertion.
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> STRICT_CONTEXT.newSample(List.of("GT"), new ArrayList<>(List.of(""))));
    assertTrue(e.getMessage().contains("is an empty entry"));
  }

  @Test
  void testGetGenotypeReturnsNullForFullyMissingCall() {
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("./."));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    assertNull(sample.getGenotype(position));
  }

  @Test
  void testGetGenotypeResolvesAllelesAndPreservesMixedPhasing() {
    // "0|1/2" -- boundary 0-1 is phased, boundary 1-2 is unphased
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0|1/2"));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T", "C"), null, null, null,
    null, -1);
    VcfSample.Genotype genotype = sample.getGenotype(position);
    assertNotNull(genotype);
    assertEquals(List.of("A", "T", "C"), genotype.getAlleles());
    assertEquals(List.of(true, false), genotype.getPhasedBoundaries());
    assertEquals("A|T/C", genotype.toString());
  }

  @Test
  void testGetGenotypeThrowsForOutOfRangeAlleleIndex() {
    // ALT has one allele (T), so valid indices are 0 (REF) and 1; index 5 is out of range
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("5/0"));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    assertThrows(VcfFormatException.class, () -> sample.getGenotype(position));
  }

  @Test
  void testGetGenotypeThrowsForNonNumericAlleleIndex() {
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("X/0"));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    assertThrows(VcfFormatException.class, () -> sample.getGenotype(position));
  }

  @Test
  void testGetGenotypeEmptyAlleleWarnsToConfiguredSink() {
    // a trailing '/' (e.g. "0/") is a zero-length allele where '.' should have been used
    // Task 7: setWarningsSink was removed -- the sink is now fixed at construction via the sample's own context.
    java.io.StringWriter sink = new java.io.StringWriter();
    VcfSample sample = lenientContext(new VcfWarnings(sink)).newSample(List.of("GT"), new ArrayList<>(List.of("0/")));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    VcfSample.Genotype genotype = sample.getGenotype(position);
    assertNotNull(genotype);
    assertEquals(2, genotype.getAlleles().size());
    assertEquals("A", genotype.getAlleles().get(0));
    assertEquals(".", genotype.getAlleles().get(1));
    assertTrue(sink.toString().contains("empty allele"));
  }

  @Test
  void testGetGenotypeEmptyAlleleWarnsRatherThanThrowsUnderAStrictContext() {
    // pins the recoverable-vs-structural split for getGenotype() specifically: its two neighbours
    // (testGetGenotypeThrowsForOutOfRangeAlleleIndex / ...NonNumericAlleleIndex) THROW under a strict context,
    // so a zero-length allele being merely warn-and-recover is the exception, not the rule. The sink-routing
    // sibling above had to move to a lenient context when Task 7 removed setWarningsSink, which left this
    // property unpinned for strict construction -- a refactor routing the empty-allele path through
    // warnOrThrow would otherwise pass every remaining test while breaking strictly-constructed callers.
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), new ArrayList<>(List.of("0/")));
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfSample.Genotype genotype = sample.getGenotype(position);
      assertNotNull(genotype);
      assertEquals(".", genotype.getAlleles().get(1));
      assertTrue(appender.anyMessageContains("empty allele"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void testGetGenotypeReturnsNullForNullPosition() {
    // matches getProperty(VcfPosition, VcfMetadata, String)'s and VcfPosition.getInfo(ReservedInfoProperty)'s
    // null-tolerant contract -- must use a real (non-empty, non-missing) GT so this actually exercises the
    // position-dependent allele-resolution code path, not the earlier absent/missing-GT short-circuit
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1"));
    assertNull(sample.getGenotype(null));
  }

  @Test
  void testToBuilderFromParallelListsCopiesFieldsAndPreservesContext() {
    VcfSample original = STRICT_CONTEXT.newSample(List.of("GT", "DP"), List.of("0/1", "10"));
    VcfSample copy = original.toBuilder().build();
    assertEquals(original.getPropertyKeys(), copy.getPropertyKeys());
    assertEquals("0/1", copy.getProperty("GT"));
    assertEquals("10", copy.getProperty("DP"));
    assertSame(original.getContext(), copy.getContext());
  }

  @Test
  void testToBuilderFromPropertyMapCopiesFieldsAndPreservesContext() {
    LinkedHashMap<String, String> props = new LinkedHashMap<>();
    props.put("GT", "0/1");
    props.put("DP", "10");
    VcfSample original = STRICT_CONTEXT.newSample(props);
    VcfSample copy = original.toBuilder().build();
    assertEquals(original.getPropertyKeys(), copy.getPropertyKeys());
    assertEquals("0/1", copy.getProperty("GT"));
    assertSame(original.getContext(), copy.getContext());
  }

  @Test
  void testBuilderSettersDefensivelyCopyEveryMutableInput() {
    List<String> keys = new ArrayList<>(List.of("GT"));
    List<String> values = new ArrayList<>(List.of("0/1"));
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1")).toBuilder()
        .keys(keys).values(values).build();
    keys.add("DP");
    values.add("10");
    assertEquals(Set.of("GT"), sample.getPropertyKeys());
  }

  @Test
  void testBuilderPropertiesSetterDefensivelyCopiesInput() {
    LinkedHashMap<String, String> props = new LinkedHashMap<>();
    props.put("GT", "0/1");
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1")).toBuilder()
        .properties(props).build();
    props.put("DP", "10");
    assertEquals(Set.of("GT"), sample.getPropertyKeys());
  }

  @Test
  void testToBuilderDoesNotMaterializeOrWarnOnDuplicateKeyUntilRead() {
    // materializing the parallel-list representation via properties() warns about a duplicate FORMAT key --
    // toBuilder() must not force that; see VcfSample.toBuilder()'s own Javadoc
    java.io.StringWriter sink = new java.io.StringWriter();
    VcfSample sample = lenientContext(new VcfWarnings(sink)).newSample(new ArrayList<>(List.of("DP", "DP")),
        new ArrayList<>(List.of("1", "2")));
    VcfSample copy = sample.toBuilder().build();
    assertEquals("", sink.toString(), "copying alone must not materialize or warn");
    assertEquals("1", copy.getProperty("DP"));
  }

  @Test
  void testBuilderPropertiesRejectsNullInsteadOfThrowingNpe() {
    VcfSample.Builder builder = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1")).toBuilder();
    assertThrows(VcfFormatException.class, () -> builder.properties(null));
  }

  @Test
  void testBuilderKeysWithoutValuesOnAPropertyMapBackedSampleNamesTheActualMistake() {
    // switching a property-map-backed sample's builder to the parallel-list representation via keys() alone
    // (with no matching values() call) used to surface VcfContext.newSample's generic "values is null but keys
    // is not" -- accurate about the field state, but not about what the caller actually did wrong
    LinkedHashMap<String, String> props = new LinkedHashMap<>();
    props.put("GT", "0/1");
    VcfSample.Builder builder = STRICT_CONTEXT.newSample(props).toBuilder().keys(List.of("DP"));
    VcfFormatException ex = assertThrows(VcfFormatException.class, builder::build);
    assertTrue(ex.getMessage().contains("values()"), ex::getMessage);
  }

  @Test
  void testBuilderValuesWithoutKeysOnAPropertyMapBackedSampleNamesTheActualMistake() {
    LinkedHashMap<String, String> props = new LinkedHashMap<>();
    props.put("GT", "0/1");
    VcfSample.Builder builder = STRICT_CONTEXT.newSample(props).toBuilder().values(List.of("1/1"));
    VcfFormatException ex = assertThrows(VcfFormatException.class, builder::build);
    assertTrue(ex.getMessage().contains("keys()"), ex::getMessage);
  }

  // --- Task 4 / D4 regression test: see VcfPercentEncodingTest for the codec's own unit tests, and
  // VcfPositionTest's identically-shaped INFO-side test.

  @Test
  void testGetPropertyIdenticalUnder42And43ResolvedContexts() {
    // no getter may return a different value for identical raw input just because the resolved VcfVersion
    // differs -- there is no version-dependent percent-decoding (or any other transformation) to diverge on
    VcfSample sample42 = VcfContext.strict("VCFv4.2").newSample(List.of("XZ"), List.of("a%3Bb%03Ac%41d"));
    VcfSample sample43 = VcfContext.strict("VCFv4.3").newSample(List.of("XZ"), List.of("a%3Bb%03Ac%41d"));
    assertEquals(sample42.getProperty("XZ"), sample43.getProperty("XZ"));
  }

  // --- Quoted-Number regression tests. These pin that a declaration written Number="1" behaves identically to
  // one written Number=1 on the metadata-aware read path. FormatMetadata.getNumber() returns the unquoted
  // value (quoting is an encoding, not part of the value -- see VALIDATION_PHILOSOPHY.md's value/encoding
  // rule), so the two spellings compare equal wherever the read path tests the Number. They did not before:
  // getNumber() returned the raw "\"1\"", which matched no expected Number anywhere, which is why these tests
  // are shaped around proving each individual comparison site now sees "1".

  @Test
  void testQuotedNumberYieldsTheSameScalarShapeAsUnquoted() {
    // the defect is a shape decision at VcfSample.java:398, reachable only through the metadata-aware getter --
    // comparing two metadata getters directly is not the test
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("XX", "7");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);
    VcfPosition position =
        LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, List.of("GT", "XX"), -1);

    FormatMetadata plain = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[] {"ID=XX", "Number=1", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
    FormatMetadata quoted = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[] {"ID=XX", "Number=\"1\"", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));

    VcfMetadata withPlain = new VcfMetadata.Builder(LENIENT_CONTEXT)
        .setFileFormat("VCFv4.2").addFormat(plain).build();
    VcfMetadata withQuoted = new VcfMetadata.Builder(LENIENT_CONTEXT)
        .setFileFormat("VCFv4.2").addFormat(quoted).build();

    // scalar 7, explicitly NOT the one-element list [7] -- which is the actual pre-fix behavior for the
    // quoted declaration, because "\"1\"".equals("1") is false at VcfSample.java:398
    // assigned through an Object local first (matching this file's other metadata-aware-getter tests, e.g.
    // testGetPropertyWithPositionAndMetadataChecksReservedListCardinality) -- getProperty's <T> T signature
    // otherwise lets target-typed inference pick T=Long here, and the resulting unchecked (Long) cast at the
    // call site throws ClassCastException instead of failing the assertion when the quoted-Number defect
    // actually returns a List.
    Object resultPlain = sample.getProperty(position, withPlain, "XX");
    Object resultQuoted = sample.getProperty(position, withQuoted, "XX");
    assertEquals(7L, resultPlain);
    assertEquals(7L, resultQuoted);
  }

  @Test
  void testQuotedNumberStillRunsTheCardinalityCheckOnTheReadPath() {
    // InternalUtils.warnIfWrongCardinality is shared but reached from two call sites -- VcfSample.java:406 (read
    // path, covered here) and VcfWriter.java:885 (validateBeforeWrite(), covered in VcfWriterTest) -- each
    // passing its own raw/unquoted number, and the raw value broke both.
    LinkedHashMap<String, String> sampleProps = new LinkedHashMap<>();
    sampleProps.put("GT", "0/1");
    sampleProps.put("XX", "1,2,3");
    VcfSample sample = LENIENT_CONTEXT.newSample(sampleProps);
    VcfPosition position = LENIENT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null,
        List.of("GT", "XX"), -1);
    FormatMetadata quoted = new FormatMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[] {"ID=XX", "Number=\"2\"", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addFormat(quoted).build();

    Logger root = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);   // after construction
    try {
      sample.getProperty(position, metadata, "XX");
      // positive: the ordinary cardinality check runs, naming the key so an unrelated warning cannot satisfy it
      assertTrue(appender.anyMessageContains("FORMAT XX has 3 value(s), but Number=2 requires 2"));
      // negative: the misleading pre-fix diagnostic is gone
      assertFalse(appender.anyMessageContains("too large to validate"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}

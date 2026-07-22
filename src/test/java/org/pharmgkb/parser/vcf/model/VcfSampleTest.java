package org.pharmgkb.parser.vcf.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


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
    // GLE was previously declared isList=true, which would split its value on internal commas; it's one opaque
    // String, not a list of independent values. (The spec's own example value, e.g. "0:-75.22,1:-223.42,...", uses
    // colons as part of its own encoding, which VcfSample's structural-delimiter check now rejects at construction
    // time -- a pre-existing tension between that check and this one, rarely-used reserved key; not addressed here.)
    String gle = "some,comma,containing,value";
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put("GLE", gle);
    VcfSample sample = new VcfSample(map);
    assertEquals(gle, sample.getProperty(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy));
  }
}

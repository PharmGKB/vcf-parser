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
}

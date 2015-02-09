package org.pharmgkb.parser.vcf.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link VcfSample}.
 * @author Douglas Myers-Turnbull
 */
public class VcfSampleTest {

  @Test
  public void testGetProperty() {
    List<String> keys = Arrays.asList("key1", "key2");
    List<String> values = Arrays.asList("value1", "value2");
    VcfSample sample = new VcfSample(keys, values);
    assertEquals(2, sample.getPropertyKeys().size());
    assertEquals("value1", sample.getProperty("key1"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadConstructor() {
    List<String> keys = Arrays.asList("key1", "key2");
    List<String> values = Arrays.asList("value1");
    new VcfSample(keys, values);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testHasNewline() {
    List<String> keys = Arrays.asList("key1");
    List<String> values = Arrays.asList("value\n1");
    new VcfSample(keys, values);
  }

}
package org.pharmgkb.parser.vcf;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.Test;
import org.pharmgkb.parser.vcf.model.VcfPosition;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Tests {@link VcfPosition}.
 * @author Douglas Myers-Turnbull
 */
public class VcfPositionTest {

  @Test
  public void testNoFilters() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, null, null, null);
    assertTrue(position.getFilters().isEmpty());
    assertTrue(position.isPassingAllFilters());
  }

  @Test
  public void testHasFilters() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Arrays.asList("bad"),
        null, null);
    assertEquals(1, position.getFilters().size());
    assertEquals("bad", position.getFilters().get(0));
    assertFalse(position.isPassingAllFilters());
  }

  @Test
  public void testBadFilter1() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Arrays.asList("PASS"),
        null, null);
    assertTrue(position.getFilters().isEmpty());
    assertTrue(position.isPassingAllFilters());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadFilter2() {
    new VcfPosition("chr", 1, null, "C", null, null, Arrays.asList("bad", "PASS"),
        null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadChromosome() {
    new VcfPosition("chr:", 1, null, "C", null, null, null, null, null);
  }

  @Test
  public void testTelomericPositions() {
    new VcfPosition("chr1", 0, null, "C", null, null, null, null, null);
    new VcfPosition("chr1", -1, null,"C", null, null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadId() {
    new VcfPosition("chr1", 1, Arrays.asList(";"), "C", null, null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadRefBase() {
    new VcfPosition("chr1", 1, null, "X", null, null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadVarBase() {
    new VcfPosition("chr1", 1, null, "C", Arrays.asList("X"), null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testZeroFilter() {
    new VcfPosition("chr1", 1, null, "C", null, null, Arrays.asList("0"), null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testFilterWithWhitespace() {
    new VcfPosition("chr1", 1, null, "C", null, null, Arrays.asList("adsf\nsdf"), null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInfoWithWhitespace() {
    ListMultimap<String, String> map = ArrayListMultimap.create();
    map.put("anid", "a\nvalue");
    new VcfPosition("chr1", 1, null, "C", null, null, null, map, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadFormat() {
    new VcfPosition("chr1", 1, null, "C", null, null, null, null, Arrays.asList("+"));
  }

}

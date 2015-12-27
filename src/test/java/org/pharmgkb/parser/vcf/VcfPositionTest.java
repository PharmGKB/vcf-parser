package org.pharmgkb.parser.vcf;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.Test;
import org.pharmgkb.parser.vcf.model.VcfPosition;

import java.util.Arrays;
import java.util.Collections;

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
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("BAD"),
        null, null);
    assertEquals(1, position.getFilters().size());
    assertEquals("BAD", position.getFilters().get(0));
    assertFalse(position.isPassingAllFilters());
  }

  @Test
  public void testBadFilter1() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("PASS"),
        null, null);
    assertTrue(position.getFilters().isEmpty());
    assertTrue(position.isPassingAllFilters());
  }

  @Test
  public void testDeletesDot() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("."),
        null, null);
    assertEquals(Collections.singletonList("."), position.getFilters());
    // true if PASS or .; if no filters were applied, it's passing all of them:
    assertTrue(position.isPassingAllFilters());
    position.getFilters().add("bad");
    assertEquals(Collections.singletonList("bad"), position.getFilters());
    assertFalse(position.isPassingAllFilters());
  }

  /**
   * Completes {@link #testDeletesDot()}.
   */
  @Test
  public void testKeepsMultipleFilters() {
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("no"),
        null, null);
    assertEquals(Collections.singletonList("no"), position.getFilters());
    assertFalse(position.isPassingAllFilters());
    position.getFilters().add("bad");
    assertEquals(Arrays.asList("no", "bad"), position.getFilters());
    assertFalse(position.isPassingAllFilters());
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
    new VcfPosition("chr1", 1, Collections.singletonList(";"), "C", null, null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadRefBase() {
    new VcfPosition("chr1", 1, null, "X", null, null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadVarBase() {
    new VcfPosition("chr1", 1, null, "C", Collections.singletonList("X"), null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testZeroFilter() {
    new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("0"), null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testFilterWithWhitespace() {
    new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("adsf\nsdf"), null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInfoWithWhitespace() {
    ListMultimap<String, String> map = ArrayListMultimap.create();
    map.put("anid", "a\nvalue");
    new VcfPosition("chr1", 1, null, "C", null, null, null, map, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadFormat() {
    new VcfPosition("chr1", 1, null, "C", null, null, null, null, Collections.singletonList("+"));
  }

}

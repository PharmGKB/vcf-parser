package org.pharmgkb.parser.vcf;

import java.util.Arrays;
import java.util.Collections;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.VcfPosition;

import static org.junit.jupiter.api.Assertions.*;

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
    VcfPosition position = new VcfPosition("chr", 1, null, "C", null, null, Collections.singletonList("bad"),
        null, null);
    assertEquals(1, position.getFilters().size());
    assertEquals("bad", position.getFilters().get(0));
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
  public void testBadFilter2() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr", 1, null, "C", null, null, Arrays.asList("bad", "PASS"),
          null, null);
    });
  }

  @Test
  public void testBadChromosome() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr:", 1, null, "C", null, null, null, null, null);
    });
  }

  @Test
  public void testTelomericPositions() {
    new VcfPosition("chr1", 0, null, "C", null, null, null, null, null);
    new VcfPosition("chr1", -1, null,"C", null, null, null, null, null);
  }

  @Test
  public void testBadId() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, Collections.singletonList(";"), "C", null, null, null, null, null);
    });
  }

  @Test
  public void testBadRefBase() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "X", null, null, null, null, null);
    });
  }

  @Test
  public void testBadVarBase() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", Collections.singletonList("X"), null, null, null, null);
    });
  }

  @Test
  public void testZeroFilter() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("0"), null, null);
    });
  }

  @Test
  public void testFilterWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, Collections.singletonList("adsf\nsdf"), null, null);
    });
  }

  @Test
  public void testInfoWithWhitespace() {
    assertThrows(VcfFormatException.class, () -> {
      ListMultimap<String, String> map = ArrayListMultimap.create();
      map.put("anid", "a\nvalue");
      new VcfPosition("chr1", 1, null, "C", null, null, null, map, null);
    });
  }

  @Test
  public void testBadFormat() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfPosition("chr1", 1, null, "C", null, null, null, null, Collections.singletonList("+"));
    });
  }

}

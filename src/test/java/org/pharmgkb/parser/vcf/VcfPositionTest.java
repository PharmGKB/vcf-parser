package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
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
  public void testLazyQuality() {
    VcfPosition value = newPosition();
    value.setRawQuality("29.5");
    assertEquals(new BigDecimal("29.5"), value.getQuality());
    assertEquals(new BigDecimal("29.5"), value.getQuality()); // stable across repeated reads

    VcfPosition missing = newPosition();
    missing.setRawQuality(".");
    assertNull(missing.getQuality());

    VcfPosition empty = newPosition();
    empty.setRawQuality("");
    assertNull(empty.getQuality());

    // an invalid QUAL is validated lazily: setting it does not throw, reading it does
    VcfPosition bad = newPosition();
    bad.setRawQuality("not-a-number");
    assertThrows(VcfFormatException.class, bad::getQuality);

    // setQuality overrides any pending raw value
    VcfPosition override = newPosition();
    override.setRawQuality("1");
    override.setQuality(new BigDecimal("2"));
    assertEquals(new BigDecimal("2"), override.getQuality());
  }

  @Test
  public void testLazyInfo() {
    VcfPosition p = newPosition();
    p.setRawInfo("DP=35;AF=0.5,0.25;FLAG");
    assertEquals(Collections.singletonList("35"), p.getInfo("DP"));
    assertEquals(Arrays.asList("0.5", "0.25"), p.getInfo("AF"));
    assertTrue(p.hasInfo("FLAG"));
    assertEquals(Collections.singletonList(""), p.getInfo("FLAG"));
    assertNull(p.getInfo("MISSING"));
    assertFalse(p.hasInfo("MISSING"));

    VcfPosition dot = newPosition();
    dot.setRawInfo(".");
    assertTrue(dot.getInfo().isEmpty());
    assertFalse(dot.hasInfo("X"));
  }

  private static VcfPosition newPosition() {
    return new VcfPosition("chr", 1, null, "C", null, null, null, null, null);
  }

  @Test
  public void testGetAllele() {
    VcfPosition position = new VcfPosition("chr1", 1, null, "C", new ArrayList<>(Arrays.asList("A", "T")), null, null,
        null, null);
    assertEquals("C", position.getAllele(0));
    assertEquals("A", position.getAllele(1));
    assertEquals("T", position.getAllele(2));
    assertThrows(IndexOutOfBoundsException.class, () -> position.getAllele(3));

    // lookup reflects a mutation to REF (no stale cache)
    position.setRef("G");
    assertEquals("G", position.getAllele(0));

    // lookup reflects a mutation to the ALT list (no stale cache)
    position.getAltBases().add("N");
    assertEquals("N", position.getAllele(3));
  }

  @Test
  public void testGetAlleleShortConstructor() {
    VcfPosition position = new VcfPosition("chr1", 1, "C", new BigDecimal("0.0"));
    assertEquals("C", position.getAllele(0));
    assertThrows(IndexOutOfBoundsException.class, () -> position.getAllele(1));
  }

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

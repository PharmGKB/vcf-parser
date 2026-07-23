package org.pharmgkb.parser.vcf.model.genotype;

import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.*;

public class VcfAlleleTest {

  @Test
  public void testLength() throws Exception {
    assertEquals(3, new VcfAllele("AAT").length());
  }

  @Test
  public void testBadLength1() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfAllele("<ID>").length();
    });
  }

  @Test
  public void testBadLength2() {
    assertThrows(VcfFormatException.class, () -> {
      new VcfAllele("ag]20]").length();
    });
  }

  @Test
  public void testIsBreakpoint() throws Exception {
    // mate locations need a ":pos" (a breakend chromosome/contig is never valid without one)
    assertFalse(new VcfAllele("AT").isBreakpoint());
    assertTrue(new VcfAllele("aG]<TT>:1]").isBreakpoint());
    assertTrue(new VcfAllele("C[<ctg1>:1[").isBreakpoint());
  }

  @Test
  public void testIsSymbolic() throws Exception {
    assertFalse(new VcfAllele("AT").isSymbolic());
    assertFalse(new VcfAllele("aG]55:1]").isSymbolic());
    assertTrue(new VcfAllele("aG]<TT>:1]").isSymbolic());
    assertTrue(new VcfAllele("<anid>").isSymbolic());
    assertTrue(new VcfAllele("C[<ctg1>:1[").isSymbolic());
  }

  @Test
  public void testIsAmbigious() throws Exception {
    assertFalse(new VcfAllele("AT").isAmbigious());
    assertFalse(new VcfAllele("aG]<Nn>:1]").isAmbigious());
    assertTrue(new VcfAllele("N]<TT>:1]").isAmbigious());
    assertTrue(new VcfAllele("n]<TT>:1]").isAmbigious());
    assertFalse(new VcfAllele("C[<Nnn>:1[").isAmbigious());
  }

  @Test
  public void testWithLowercaseBases() throws Exception {
    assertEquals("ag]20:1]", new VcfAllele("aG]20:1]").withLowercaseBases());
    assertEquals("ag]<TT>:1]", new VcfAllele("aG]<TT>:1]").withLowercaseBases());
    assertEquals("<TT>", new VcfAllele("<TT>").withLowercaseBases());
  }

  @Test
  public void testContainsBase() throws Exception {
    assertTrue(new VcfAllele("AT").containsBase('A'));
    assertFalse(new VcfAllele("AT").containsBase('a'));
    assertFalse(new VcfAllele("<TT>").containsBase('T'));
    assertTrue(new VcfAllele("A").containsBase('T', 'A'));
  }

  @Test
  public void testGetPrimaryType() {
    // "." (the no-variant missing value) has length 1 like a real base, but must not be classified as SINGLE_BASE
    assertEquals(VcfAllele.PrimaryType.NO_VARIATION, new VcfAllele(".").getPrimaryType());
    assertEquals(VcfAllele.PrimaryType.SINGLE_BASE, new VcfAllele("A").getPrimaryType());
    assertEquals(VcfAllele.PrimaryType.MULTI_BASE, new VcfAllele("AT").getPrimaryType());
    assertEquals(VcfAllele.PrimaryType.SYMBOLIC, new VcfAllele("<TT>").getPrimaryType());
    assertEquals(VcfAllele.PrimaryType.DELETED, new VcfAllele("*").getPrimaryType());
    assertEquals(VcfAllele.PrimaryType.BREAKPOINT, new VcfAllele("C[<ctg1>:1[").getPrimaryType());
  }
}

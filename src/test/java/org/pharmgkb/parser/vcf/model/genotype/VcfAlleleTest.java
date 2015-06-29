package org.pharmgkb.parser.vcf.model.genotype;

import org.junit.Test;

import static org.junit.Assert.*;

public class VcfAlleleTest {

  @Test
  public void testLength() throws Exception {
    assertEquals(3, new VcfAllele("AAT").length());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadLength1() throws Exception {
    new VcfAllele("<ID>").length();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBadLength2() throws Exception {
    new VcfAllele("ag]20]").length();
  }

  @Test
  public void testIsBreakpoint() throws Exception {
    assertFalse(new VcfAllele("AT").isBreakpoint());
    assertTrue(new VcfAllele("aG]<TT>]").isBreakpoint());
    assertTrue(new VcfAllele("C[<ctg1>:1[").isBreakpoint());
  }

  @Test
  public void testIsSymbolic() throws Exception {
    assertFalse(new VcfAllele("AT").isSymbolic());
    assertFalse(new VcfAllele("aG]55]").isSymbolic());
    assertTrue(new VcfAllele("aG]<TT>]").isSymbolic());
    assertTrue(new VcfAllele("<anid>").isSymbolic());
    assertTrue(new VcfAllele("C[<ctg1>:1[").isSymbolic());
  }

  @Test
  public void testIsAmbigious() throws Exception {
    assertFalse(new VcfAllele("AT").isAmbigious());
    assertFalse(new VcfAllele("aG]<Nn>]").isAmbigious());
    assertTrue(new VcfAllele("N]<TT>]").isAmbigious());
    assertTrue(new VcfAllele("n]<TT>]").isAmbigious());
    assertFalse(new VcfAllele("C[<Nnn>:1[").isAmbigious());
  }

  @Test
  public void testWithLowercaseBases() throws Exception {
    assertEquals("ag]20]", new VcfAllele("aG]20]").withLowercaseBases());
    assertEquals("ag]<TT>]", new VcfAllele("aG]<TT>]").withLowercaseBases());
    assertEquals("<TT>", new VcfAllele("<TT>").withLowercaseBases());
  }

  @Test
  public void testContainsBase() throws Exception {
    assertTrue(new VcfAllele("AT").containsBase('A'));
    assertFalse(new VcfAllele("AT").containsBase('a'));
    assertFalse(new VcfAllele("<TT>").containsBase('T'));
    assertTrue(new VcfAllele("A").containsBase('T', 'A'));
  }
}
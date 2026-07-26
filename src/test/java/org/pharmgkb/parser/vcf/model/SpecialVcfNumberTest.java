package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SpecialVcfNumberTest {

  @Test
  public void testFromIdResolvesAllReservedValues() {
    assertEquals(SpecialVcfNumber.ONE_PER_ALT, SpecialVcfNumber.fromId("A"));
    assertEquals(SpecialVcfNumber.ONE_PER_ALT_OR_REF, SpecialVcfNumber.fromId("R"));
    assertEquals(SpecialVcfNumber.ONE_PER_GENOTYPE, SpecialVcfNumber.fromId("G"));
    assertEquals(SpecialVcfNumber.UNKNOWN_OR_UNBOUNDED, SpecialVcfNumber.fromId("."));
  }

  @Test
  public void testFromIdReturnsNullForNonReservedValue() {
    assertNull(SpecialVcfNumber.fromId("NOT_A_RESERVED_VALUE"));
  }

  @Test
  public void testGetId() {
    assertEquals("A", SpecialVcfNumber.ONE_PER_ALT.getId());
    assertEquals("R", SpecialVcfNumber.ONE_PER_ALT_OR_REF.getId());
    assertEquals("G", SpecialVcfNumber.ONE_PER_GENOTYPE.getId());
    assertEquals(".", SpecialVcfNumber.UNKNOWN_OR_UNBOUNDED.getId());
  }
}

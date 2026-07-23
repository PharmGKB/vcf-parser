package org.pharmgkb.parser.vcf.model;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link VcfFloat}.
 */
public class VcfFloatTest {

  @Test
  public void testParseNormalDecimal() {
    VcfFloat value = VcfFloat.parse("52.4");
    assertFalse(value.isSpecial());
    assertEquals(new BigDecimal("52.4"), value.getValue());
    assertNull(value.getSpecial());
  }

  @Test
  public void testParseSpecialValuesCaseInsensitiveAndSigned() {
    // VCFv4.2 doesn't define Float's grammar; VCFv4.5 clarifies it accepts NAN/INF/INFINITY (case-insensitive,
    // optionally signed) in addition to a normal decimal number
    for (String nan : new String[] { "NaN", "nan", "NAN" }) {
      VcfFloat value = VcfFloat.parse(nan);
      assertTrue(value.isSpecial());
      assertEquals(VcfFloat.Special.NAN, value.getSpecial());
      assertNull(value.getValue());
    }
    for (String inf : new String[] { "INF", "inf", "Infinity", "+INF", "+Infinity" }) {
      assertEquals(VcfFloat.Special.POSITIVE_INFINITY, VcfFloat.parse(inf).getSpecial());
    }
    for (String negInf : new String[] { "-INF", "-inf", "-Infinity" }) {
      assertEquals(VcfFloat.Special.NEGATIVE_INFINITY, VcfFloat.parse(negInf).getSpecial());
    }
  }

  @Test
  public void testParseInvalidValueThrows() {
    assertThrows(VcfFormatException.class, () -> VcfFloat.parse("not-a-number"));
  }

  @Test
  public void testToString() {
    assertEquals("52.4", VcfFloat.parse("52.4").toString());
    assertEquals("NaN", VcfFloat.ofSpecial(VcfFloat.Special.NAN).toString());
    assertEquals("INFINITY", VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY).toString());
    assertEquals("-INFINITY", VcfFloat.ofSpecial(VcfFloat.Special.NEGATIVE_INFINITY).toString());
  }

  @Test
  public void testEqualsAndHashCode() {
    assertEquals(VcfFloat.of(new BigDecimal("1.5")), VcfFloat.of(new BigDecimal("1.5")));
    assertEquals(VcfFloat.of(new BigDecimal("1.5")).hashCode(), VcfFloat.of(new BigDecimal("1.5")).hashCode());
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NAN), VcfFloat.ofSpecial(VcfFloat.Special.NAN));
    assertFalse(VcfFloat.of(new BigDecimal("1.5")).equals(VcfFloat.ofSpecial(VcfFloat.Special.NAN)));
    assertFalse(VcfFloat.ofSpecial(VcfFloat.Special.NAN).equals(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY)));
  }

}

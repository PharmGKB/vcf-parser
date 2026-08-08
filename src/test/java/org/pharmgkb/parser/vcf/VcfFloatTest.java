package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
  public void testParseNormalDecimalDoesNotWarn() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfFloat.parse("52.4");
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testParseTrailingDecimalPointWarnsButStillParses() {
    // regression test: BigDecimal accepts "5." (a trailing decimal point with no digits after it), but
    // VCFv4.3.tex line 100's Float grammar requires at least one digit after the decimal point. This doesn't
    // corrupt or misdirect anything (the numeric value is unambiguous), so per this project's permissive
    // parser policy (AGENTS.md's Parser Validation Policy) it must warn and still return the parsed value,
    // not silently accept it with no diagnostic
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfFloat value = VcfFloat.parse("5.");
      assertFalse(value.isSpecial());
      assertEquals(new BigDecimal("5"), value.getValue());
      assertTrue(appender.hasWarnOrHigher());
      assertTrue(appender.anyMessageContains("does not match the VCF Float grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
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
  public void testParseRejectsValueExceedingMaxDecimalLength() {
    // regression test: parseDecimal used to call `new BigDecimal(value)` with no length bound at all --
    // BigDecimal/BigInteger's decimal-string parsing is quadratic in digit count, so an attacker-controlled
    // value with no length bound was a CPU-exhaustion vector reachable through any Float-typed reserved key.
    // An oversized value is now rejected before the expensive parse is ever attempted, using the same
    // NumberFormatException-to-VcfFormatException policy dispatch as any other unparseable value.
    String tooLong = "1".repeat(129);
    assertThrows(VcfFormatException.class, () -> VcfFloat.parse(tooLong));
  }

  @Test
  public void testParseAcceptsValueAtMaxDecimalLengthBoundary() {
    // pins the boundary itself: exactly 128 characters must still parse normally, not be swept up by an
    // off-by-one in the new length guard
    String atBoundary = "1".repeat(128);
    VcfFloat value = VcfFloat.parse(atBoundary);
    assertEquals(new BigDecimal(atBoundary), value.getValue());
  }

  @Test
  public void testParseDoesNotHangOnPathologicallyLongAdversarialValue() {
    // 3 seconds is a wide margin above the fixed behavior's near-instant rejection at this size; the unfixed
    // code (new BigDecimal(value) with no length bound) took ~3 seconds at this exact size, empirically, per
    // the finding that motivated this fix
    String pathologicallyLong = "1".repeat(500_000);
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
        assertThrows(VcfFormatException.class, () -> VcfFloat.parse(pathologicallyLong)));
  }

  @Test
  public void testToString() {
    assertEquals("52.4", VcfFloat.parse("52.4").toString());
    assertEquals("NaN", VcfFloat.ofSpecial(VcfFloat.Special.NAN).toString());
    assertEquals("INFINITY", VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY).toString());
    assertEquals("-INFINITY", VcfFloat.ofSpecial(VcfFloat.Special.NEGATIVE_INFINITY).toString());
  }

  @Test
  public void testParseRejectsNullInsteadOfThrowingNpe() {
    // value is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at SPECIAL_PATTERN.matcher(value)
    assertThrows(VcfFormatException.class, () -> VcfFloat.parse(null));
  }

  @Test
  public void testOfRejectsNullInsteadOfSilentlyBuildingInvalidInstance() {
    // unlike parse(), of(null) previously succeeded silently, building an instance with both m_value and
    // m_special null -- a state isSpecial()/getValue() can't represent -- and only raw-NPE'd later, in
    // toString()
    assertThrows(VcfFormatException.class, () -> VcfFloat.of(null));
  }

  @Test
  public void testOfSpecialRejectsNullInsteadOfSilentlyBuildingInvalidInstance() {
    assertThrows(VcfFormatException.class, () -> VcfFloat.ofSpecial(null));
  }

  @Test
  public void testEqualsAndHashCode() {
    assertEquals(VcfFloat.of(new BigDecimal("1.5")), VcfFloat.of(new BigDecimal("1.5")));
    assertEquals(VcfFloat.of(new BigDecimal("1.5")).hashCode(), VcfFloat.of(new BigDecimal("1.5")).hashCode());
    assertEquals(VcfFloat.ofSpecial(VcfFloat.Special.NAN), VcfFloat.ofSpecial(VcfFloat.Special.NAN));
    assertFalse(VcfFloat.of(new BigDecimal("1.5")).equals(VcfFloat.ofSpecial(VcfFloat.Special.NAN)));
    assertFalse(VcfFloat.ofSpecial(VcfFloat.Special.NAN).equals(VcfFloat.ofSpecial(VcfFloat.Special.POSITIVE_INFINITY)));
  }

  @Test
  public void testParseWithWarningsRoutesGrammarMismatchToSuppliedSink() {
    StringWriter sink = new StringWriter();
    VcfFloat result = VcfFloat.parse("5.", new VcfWarnings(sink));
    assertEquals(new BigDecimal("5."), result.getValue());
    assertTrue(sink.toString().contains("does not match the VCF Float grammar"));
  }

  @Test
  public void testPublicParseStillWarnsToSharedLoggerForGrammarMismatch() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender = new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfFloat.parse("5.");
      assertTrue(appender.anyMessageContains("does not match the VCF Float grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

}

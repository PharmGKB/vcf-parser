package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class VcfContextTest {

  @Test
  void testLenientCarriesDeclaredAndResolvedVersions() {
    VcfWarnings warnings = new VcfWarnings(new StringWriter());
    VcfContext ctx = VcfContext.lenient("VCFv4.2", warnings);
    assertEquals("VCFv4.2", ctx.getDeclaredFileFormat());
    assertEquals(VcfVersion.V4_2, ctx.getVersion());
    assertSame(warnings, ctx.getWarnings());
    assertTrue(ctx.isLenient());
  }

  @Test
  void testDeclaredIsPreservedVerbatimWhenResolutionFallsBack() {
    VcfWarnings warnings = new VcfWarnings(new StringWriter());
    VcfContext ctx = VcfContext.lenient("VCFv4.5", warnings);
    assertEquals("VCFv4.5", ctx.getDeclaredFileFormat());
    assertEquals(VcfVersion.HIGHEST_IMPLEMENTED, ctx.getVersion());
  }

  @Test
  void testStrictIsNotLenientButStillFallsBackRatherThanThrowing() {
    // strict governs CONTENT validation; version resolution is uniform across both modes.
    VcfContext ctx = VcfContext.strict("VCFv4.9");
    assertFalse(ctx.isLenient());
    assertEquals("VCFv4.9", ctx.getDeclaredFileFormat());
    assertEquals(VcfVersion.HIGHEST_IMPLEMENTED, ctx.getVersion());
    assertSame(VcfWarnings.DEFAULT, ctx.getWarnings());
  }

  @Test
  void testMalformedFileFormatThrowsInBothModes() {
    VcfWarnings warnings = new VcfWarnings(new StringWriter());
    assertThrows(VcfFormatException.class, () -> VcfContext.lenient("banana", warnings));
    assertThrows(VcfFormatException.class, () -> VcfContext.strict("banana"));
  }

  @Test
  void testNullWarningsThrowsNullPointerException() {
    // VcfWarnings is caller configuration, not VCF content -- raw NPE per AGENTS.md's Null-Safety Policy.
    assertThrows(NullPointerException.class, () -> VcfContext.lenient("VCFv4.2", null));
  }
}

package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class VcfVersionTest {

  private static String resolveCapturingWarnings(String declared, StringWriter sink) {
    VcfWarnings warnings = new VcfWarnings(sink);
    VcfVersion resolved = VcfVersion.resolve(declared, warnings);
    warnings.close();
    return resolved.getId();
  }

  @Test
  void testOrdering() {
    assertTrue(VcfVersion.V4_3.isAtLeast(VcfVersion.V4_2));
    assertTrue(VcfVersion.V4_3.isAtLeast(VcfVersion.V4_3));
    assertFalse(VcfVersion.V4_2.isAtLeast(VcfVersion.V4_3));
  }

  @Test
  void testIds() {
    assertEquals("4.3", VcfVersion.V4_3.getId());
    assertEquals("VCFv4.3", VcfVersion.V4_3.getFileFormat());
  }

  @Test
  void testImplementedVersionsResolveToThemselvesSilently() {
    for (String v : new String[] {"4.1", "4.2", "4.3"}) {
      StringWriter sink = new StringWriter();
      assertEquals(v, resolveCapturingWarnings("VCFv" + v, sink));
      assertThat("VCFv" + v + " must not warn", sink.toString(), emptyString());
    }
  }

  @Test
  void testUnimplementedNewerVersionsFallBackDownWithWarning() {
    for (String v : new String[] {"4.4", "4.5", "4.9"}) {
      StringWriter sink = new StringWriter();
      assertEquals(VcfVersion.HIGHEST_IMPLEMENTED.getId(), resolveCapturingWarnings("VCFv" + v, sink));
      assertThat(sink.toString(), containsString("VCFv" + v));
      assertThat(sink.toString(), containsString(VcfVersion.HIGHEST_IMPLEMENTED.getId()));
    }
  }

  @Test
  void testBelowFloorFallsBackUpWithWarning() {
    StringWriter sink = new StringWriter();
    assertEquals(VcfVersion.LOWEST_SUPPORTED.getId(), resolveCapturingWarnings("VCFv4.0", sink));
    assertThat(sink.toString(), containsString("VCFv4.0"));
  }

  @Test
  void testMalformedFileFormatThrows() {
    VcfWarnings warnings = new VcfWarnings(new StringWriter());
    assertThrows(VcfFormatException.class, () -> VcfVersion.resolve("banana", warnings));
    assertThrows(VcfFormatException.class, () -> VcfVersion.resolve("VCFv3.3", warnings));
    assertThrows(VcfFormatException.class, () -> VcfVersion.resolve("VCFv4..2", warnings));
    assertThrows(VcfFormatException.class, () -> VcfVersion.resolve(null, warnings));
  }

  @Test
  void testAbsurdlyLongMinorVersionDoesNotOverflow() {
    // FILE_FORMAT_PATTERN's "\d+" accepts this, so resolve must not reach Integer.parseInt and throw a raw
    // NumberFormatException -- its contract promises VcfFormatException or a resolved version, nothing else.
    StringWriter sink = new StringWriter();
    assertEquals(VcfVersion.HIGHEST_IMPLEMENTED.getId(),
        resolveCapturingWarnings("VCFv4.99999999999999999999", sink));
    assertThat(sink.toString(), containsString("VCFv4.99999999999999999999"));
  }

  @Test
  void testLeadingZerosNormalizeToTheExactImplementedVersion() {
    // 4.01 IS 4.1 -- it must resolve exactly, with no fallback and no warning, not fall through to 4.3
    for (String declared : new String[] {"VCFv4.01", "VCFv4.001"}) {
      StringWriter sink = new StringWriter();
      assertEquals("4.1", resolveCapturingWarnings(declared, sink));
      assertThat(declared + " must not warn", sink.toString(), emptyString());
    }
    StringWriter sink = new StringWriter();
    assertEquals("4.2", resolveCapturingWarnings("VCFv4.02", sink));
    assertThat(sink.toString(), emptyString());
  }

  @Test
  void testAllZeroMinorVersionIsBelowTheFloor() {
    for (String declared : new String[] {"VCFv4.0", "VCFv4.00"}) {
      StringWriter sink = new StringWriter();
      assertEquals(VcfVersion.LOWEST_SUPPORTED.getId(), resolveCapturingWarnings(declared, sink));
      assertThat(sink.toString(), containsString(declared));
    }
  }

  @Test
  void testDoubleDigitMinorIsTreatedAsAFutureVersionNotADecimal() {
    // 4.10 is minor version 10, i.e. newer than 4.5 -- NOT a decimal equal to 4.1. Reading it as 4.1 would
    // silently interpret a future version under ancient rules with no fallback warning at all.
    for (String declared : new String[] {"VCFv4.10", "VCFv4.11200"}) {
      StringWriter sink = new StringWriter();
      assertEquals(VcfVersion.HIGHEST_IMPLEMENTED.getId(), resolveCapturingWarnings(declared, sink));
      assertThat(sink.toString(), containsString(declared));
    }
  }

  @Test
  void testNullWarningsFailsConsistentlyRegardlessOfPath() {
    // must fail for an implemented version too, not only when the fallback path would emit a warning
    assertThrows(NullPointerException.class, () -> VcfVersion.resolve("VCFv4.2", null));
    assertThrows(NullPointerException.class, () -> VcfVersion.resolve("VCFv4.9", null));
  }

  @Test
  void testIsAtLeastRejectsNull() {
    assertThrows(VcfFormatException.class, () -> VcfVersion.V4_3.isAtLeast(null));
  }
  @Test
  void testCanonicalizeStripsLeadingZerosButKeepsAnUnimplementedVersionsOwnNumber() {
    // identifies the DECLARATION, unlike resolve, which collapses an unimplemented version onto the nearest
    // implemented one -- that difference is the whole reason VcfWriter's cross-version check uses this
    assertEquals("VCFv4.2", VcfVersion.canonicalize("VCFv4.2"));
    assertEquals("VCFv4.2", VcfVersion.canonicalize("VCFv4.02"));
    assertEquals("VCFv4.2", VcfVersion.canonicalize("VCFv4.002"));
    assertEquals("VCFv4.7", VcfVersion.canonicalize("VCFv4.7"));
    assertEquals("VCFv4.9", VcfVersion.canonicalize("VCFv4.9"));
    assertNotEquals(VcfVersion.canonicalize("VCFv4.7"), VcfVersion.canonicalize("VCFv4.9"));
    assertEquals("VCFv4.0", VcfVersion.canonicalize("VCFv4.0"));
  }

  @Test
  void testCanonicalizeRejectsNullAndMalformedValues() {
    assertThrows(VcfFormatException.class, () -> VcfVersion.canonicalize(null));
    assertThrows(VcfFormatException.class, () -> VcfVersion.canonicalize("banana"));
    assertThrows(VcfFormatException.class, () -> VcfVersion.canonicalize("VCFv5.1"));
  }

}

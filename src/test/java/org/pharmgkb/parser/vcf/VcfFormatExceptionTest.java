package org.pharmgkb.parser.vcf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests {@link VcfFormatException}.
 */
public class VcfFormatExceptionTest {

  @Test
  public void testSingleArgConstructorRejectsNullOrBlank() {
    assertThrows(NullPointerException.class, () -> new VcfFormatException(null));
    assertThrows(NullPointerException.class, () -> new VcfFormatException(""));
    assertThrows(NullPointerException.class, () -> new VcfFormatException("   "));
  }

  @Test
  public void testLineNumberConstructorRejectsNullOrBlank() {
    assertThrows(NullPointerException.class, () -> new VcfFormatException(null, 5));
    assertThrows(NullPointerException.class, () -> new VcfFormatException("", 5));
    assertThrows(NullPointerException.class, () -> new VcfFormatException("   ", 5));
  }

  @Test
  public void testThrowableConstructorRejectsNullOrBlankMessage() {
    RuntimeException cause = new RuntimeException("cause");
    assertThrows(NullPointerException.class, () -> new VcfFormatException(null, cause));
    assertThrows(NullPointerException.class, () -> new VcfFormatException("", cause));
    assertThrows(NullPointerException.class, () -> new VcfFormatException("   ", cause));
  }

  @Test
  public void testMessageIsTrimmed() {
    VcfFormatException ex = new VcfFormatException("  has whitespace  ");
    assertEquals("has whitespace", ex.getMessage());
  }

  @Test
  public void testLineNumberSectionConstructorFallsBackToExceptionClassNameWhenMessageIsNull() {
    RuntimeException noMessage = new RuntimeException();
    VcfFormatException ex = new VcfFormatException(5, "data", noMessage);
    assertEquals("[Line #5] Error parsing data: java.lang.RuntimeException", ex.getMessage());
  }

  @Test
  public void testLineNumberSectionConstructorFallsBackToExceptionClassNameWhenMessageIsBlank() {
    RuntimeException blankMessage = new RuntimeException("   ");
    VcfFormatException ex = new VcfFormatException(5, "data", blankMessage);
    assertEquals("[Line #5] Error parsing data: java.lang.RuntimeException", ex.getMessage());
  }

  @Test
  public void testLineNumberSectionConstructorTrimsRealMessage() {
    RuntimeException cause = new RuntimeException("  real message  ");
    VcfFormatException ex = new VcfFormatException(5, "data", cause);
    assertEquals("[Line #5] Error parsing data: real message", ex.getMessage());
  }

  @Test
  public void testLineNumberSectionConstructorRejectsNullEx() {
    // regression test: this constructor's null/blank-message fallback (see the two tests above) anticipated
    // "ex has no message" but not "ex itself is null" -- both before and after this fix, a null ex throws
    // NullPointerException, so asserting the exception type alone would be vacuous. Before the fix, that NPE
    // came from an uncontrolled ex.getMessage() deep in the constructor body, with a generic JVM-generated
    // message; the fix instead rejects ex immediately via Preconditions.checkNotNull, with a specific message
    // -- asserting on that message is what actually distinguishes the two.
    NullPointerException ex = assertThrows(NullPointerException.class, () -> new VcfFormatException(5, "data", null));
    assertTrue(ex.getMessage().contains("ex must not be null"));
  }

  @Test
  public void testGetLineNumberDefaultsToNegativeOneBeforeAnyMetadataAttached() {
    VcfFormatException ex = new VcfFormatException("plain message");
    assertEquals(-1, ex.getLineNumber());
  }

  @Test
  public void testGetMessageWithNoSectionReturnsBareMessage() {
    VcfFormatException ex = new VcfFormatException("plain message");
    assertEquals("plain message", ex.getMessage());
  }

  @Test
  public void testGetMessageWithSectionButNoLineNumberOmitsLinePrefix() {
    VcfFormatException ex = new VcfFormatException("plain message");
    ex.addMetadata(-1, "data");
    assertEquals("Error parsing data: plain message", ex.getMessage());
  }

  @Test
  public void testGetMessageWithLineNumberButNoSectionIncludesLinePrefix() {
    // regression test: the (String, int) constructor sets a real lineNumber but never a section -- previously
    // getMessage()'s branch on m_section == null silently dropped the line number in this case, even though
    // getLineNumber() correctly returned it. This is the constructor VcfParser.java uses for many real parse
    // errors (e.g. "Not a VCF file: the first line must be ##fileformat").
    VcfFormatException ex = new VcfFormatException("plain message", 9);
    assertEquals("[Line #9] plain message", ex.getMessage());
    assertEquals(9, ex.getLineNumber());
  }

  @Test
  public void testGetMessageWithSectionAndLineNumberIncludesLinePrefix() {
    VcfFormatException ex = new VcfFormatException("plain message");
    ex.addMetadata(7, "data");
    assertEquals("[Line #7] Error parsing data: plain message", ex.getMessage());
    assertEquals(7, ex.getLineNumber());
  }

  @Test
  public void testToStringNeverReturnsNull() {
    VcfFormatException ex = new VcfFormatException(5, "data", new RuntimeException());
    assertNotNull(ex.toString());
  }
}

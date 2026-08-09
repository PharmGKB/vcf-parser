package org.pharmgkb.parser.vcf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


/**
 * Tests {@link VcfPercentEncoding}.
 */
class VcfPercentEncodingTest {

  @Test
  void testRoundTripOverAllEightTabledSequences() {
    String[][] table = {
        {"%3A", ":"},
        {"%3B", ";"},
        {"%3D", "="},
        {"%25", "%"},
        {"%2C", ","},
        {"%0D", "\r"},
        {"%0A", "\n"},
        {"%09", "\t"},
    };
    for (String[] row : table) {
      String encoded = row[0];
      String literal = row[1];
      assertEquals(literal, VcfPercentEncoding.decode(encoded), "decode(" + encoded + ")");
      assertEquals(encoded, VcfPercentEncoding.encode(literal), "encode(" + literal + ")");
    }
  }

  @Test
  void testDecodeIsCaseInsensitiveOnEachAlphabeticToken() {
    // review 36: the eight-sequence round trip above starts from decoded characters, so it only exercises
    // encode()'s uppercase output -- it cannot tell a general case-insensitive lookup from a %3b special case.
    // %25 and %09 have no hex letters, so only the six lettered codes are asserted here, directly, lowercase.
    assertEquals(":", VcfPercentEncoding.decode("%3a"));
    assertEquals(";", VcfPercentEncoding.decode("%3b"));
    assertEquals("=", VcfPercentEncoding.decode("%3d"));
    assertEquals(",", VcfPercentEncoding.decode("%2c"));
    assertEquals("\n", VcfPercentEncoding.decode("%0a"));
    assertEquals("\r", VcfPercentEncoding.decode("%0d"));
  }

  @Test
  void testEncodeAlwaysEmitsCanonicalUppercaseSpelling() {
    // VCFv4.3.tex:83: "they must be represented with the capitalized percent encoding" -- encode() never emits
    // a lowercase hex digit, regardless of what a caller's own decode step (if any) produced
    assertEquals("%3A", VcfPercentEncoding.encode(":"));
    assertEquals("%3B", VcfPercentEncoding.encode(";"));
    assertEquals("%3D", VcfPercentEncoding.encode("="));
    assertEquals("%2C", VcfPercentEncoding.encode(","));
    assertEquals("%0A", VcfPercentEncoding.encode("\n"));
    assertEquals("%0D", VcfPercentEncoding.encode("\r"));
  }

  @Test
  void testUnrecognizedOrMalformedSequencesLeftAsLiteralText() {
    // %03A is NOT %3A: the two characters immediately after '%' are "03", a two-hex-digit code that isn't one
    // of the eight tabled sequences, so nothing is consumed and the whole thing is copied through unchanged,
    // character by character. Matches hts-specs' own test/vcf/4.3/passed/passed_body_info.vcf:63, whose MY3
    // value contains a literal, never-decoded "%03A".
    assertEquals("%03A", VcfPercentEncoding.decode("%03A"));
    // %41 is 'A' in general percent-encoding, but this is not a general percent-decoder -- only the eight tabled
    // byte values are recognized, so an otherwise well-formed two-hex-digit sequence outside that table is left
    // untouched too.
    assertEquals("%41", VcfPercentEncoding.decode("%41"));
    // not hex at all
    assertEquals("%zz", VcfPercentEncoding.decode("%zz"));
  }

  @Test
  void testEmptyStringIsUnchanged() {
    assertEquals("", VcfPercentEncoding.decode(""));
    assertEquals("", VcfPercentEncoding.encode(""));
  }

  @Test
  void testTrailingBarePercentLeftLiteral() {
    assertEquals("abc%", VcfPercentEncoding.decode("abc%"));
  }

  @Test
  void testPercentWithOnlyOneFollowingCharacterLeftLiteral() {
    assertEquals("abc%3", VcfPercentEncoding.decode("abc%3"));
  }

  @Test
  void testDecodeIsASingleForwardScanThatNeverRescansItsOwnOutput() {
    // specified after review 28: a chained-replacement implementation decodes %253B to %3B and then, depending
    // on replacement order, again to ';' -- recursive decoding, which would lose a caller's literal escape text.
    // One pass over %253B consumes %25 as the first three characters (decoding to a literal '%'), leaving "3B"
    // as ordinary trailing characters, so the answer is "%3B", not ";".
    assertEquals("%3B", VcfPercentEncoding.decode("%253B"));
    assertEquals("%25", VcfPercentEncoding.decode("%2525"));
    // the round-trip property: encoding a literal "%3B" (i.e. the three characters '%','3','B') produces
    // "%253B" (the '%' alone is encoded to "%25"; '3' and 'B' are ordinary characters), and decoding that back
    // must reproduce the original literal text, not the semicolon it would decode to under recursive decoding
    assertEquals("%3B", VcfPercentEncoding.decode(VcfPercentEncoding.encode("%3B")));
  }

  @Test
  void testDecodeRejectsNull() {
    assertThrows(VcfFormatException.class, () -> VcfPercentEncoding.decode(null));
  }

  @Test
  void testEncodeRejectsNull() {
    assertThrows(VcfFormatException.class, () -> VcfPercentEncoding.encode(null));
  }
}

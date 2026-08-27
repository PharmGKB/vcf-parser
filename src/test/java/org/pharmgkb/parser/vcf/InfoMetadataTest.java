package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.lenientContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class InfoMetadataTest {


  @Test
  public void testConstructor() {

    String[] props = new String[4];
    props[0] = "ID=AA";
    props[1] = "Number=1";
    props[2] = "Type=String";
    String desc = "Ancestral Allele, test";
    props[3] = "Description=\"" + desc + "\"";
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(props, VcfWarnings.DEFAULT));
    assertEquals(InfoType.String, md.getType());
    assertEquals(desc, md.getDescription());

    String[] nums = {
        "0", "23", "A", "G", "R", "."
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(props, VcfWarnings.DEFAULT));
      assertEquals(n, md.getNumber());
    }

    nums = new String[] { // these should all cause warnings
        "AA", "x", ".g", "23a"
    };
    for (String n : nums) {
      props[1] = "Number=" + n;
      new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(props, VcfWarnings.DEFAULT));
    }
  }

  @Test
  public void testTypedConstructorRejectsNullTypeInsteadOfThrowingNpe() {
    // type is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at type.name()
    assertThrows(VcfFormatException.class, () -> new InfoMetadata(STRICT_CONTEXT, "AA", "d", null, "1", null, null));
  }

  @Test
  public void testFlagWithNonZeroNumberWarns() {
    // Type=Flag with Number != 0 is malformed; it should warn, not throw
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=Flag", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertEquals("1", md.getNumber());
    assertEquals(InfoType.Flag, md.getType());
  }

  @Test
  public void testFlagWithMissingNumberWarnsOnceNotTwice() {
    // regression test: Type=Flag with Number simply absent previously fired both the legitimate
    // "required metadata property \"Number\" is missing" warning from checkNumberProperty AND a second,
    // redundant, confusingly worded one containing the literal string "Number is 'null'" -- only the first
    // should fire
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    InfoMetadata md = new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=X", "Type=Flag", "Description=\"d\""}, warnings));
    assertEquals(InfoType.Flag, md.getType());
    assertTrue(sink.toString().contains("required metadata property \"Number\" is missing"));
    assertFalse(sink.toString().contains("'null'"));
  }

  @Test
  public void testUnquotedDescriptionWarns() {
    // an unquoted Description is malformed; it should warn, not throw
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=String", "Description=unquoted"}, VcfWarnings.DEFAULT));
    assertEquals("X", md.getId());
  }

  @Test
  public void testInvalidTypeWarns() {
    // an invalid Type should warn (not throw); the type is left null
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=NotAType", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertNull(md.getType());
  }

  @Test
  public void testMissingTypeWarns() {
    // a missing Type should warn (not throw); the type is left null
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertNull(md.getType());
  }

  @Test
  public void testDescriptionWithLineTerminatorRejectedAtConstruction() {
    // a Description containing a newline must be rejected here, not deferred to a generic RuntimeException at write
    // time (see VcfWriter.printLine's single-line check)
    assertThrows(VcfFormatException.class, () ->
        new InfoMetadata(STRICT_CONTEXT, "ID", "bad\ndescription", InfoType.String, "1", null, null));
    assertThrows(VcfFormatException.class, () ->
        new InfoMetadata(STRICT_CONTEXT, "ID", "bad\rdescription", InfoType.String, "1", null, null));
  }

  @Test
  public void testValidateRefreshesTypeAfterRawMutation() {
    // getPropertiesRaw() is unmodifiable since the metadata layer was frozen; putPropertyRaw is the
    // package-private construction-time inserter, used here to reach the same state from inside the package
    InfoMetadata md = new InfoMetadata(STRICT_CONTEXT, "X", "d", InfoType.Integer, "1", null, null);
    md.putPropertyRaw("Type", "String");
    md.validate();
    assertEquals(InfoType.String, md.getType());
  }

  @Test
  public void testExtraPropertyRetained() {
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- an unrecognized property is compliant, not just tolerated, and must be preserved
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=String", "Description=\"d\"", "Custom=extra"}, VcfWarnings.DEFAULT));
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }

  @Test
  public void testReservedNumberMismatchWarnsAndPreservesValue() {
    // AC's reserved Number is "A" (VCFv4.3's Reserved INFO Keys table); declaring it "1" is a mismatch
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    InfoMetadata md = new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=AC", "Number=1", "Type=Integer", "Description=\"d\""}, warnings));
    // warn-and-preserve, not reject-and-rewrite: the declared (non-compliant) value is unchanged
    assertEquals("1", md.getNumber());
    assertTrue(sink.toString().contains("reserved definition expects"));
  }

  @Test
  public void testReservedTypeMismatchWarns() {
    // AC's reserved Type is Integer; declaring it String is a mismatch
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=AC", "Number=A", "Type=String", "Description=\"d\""}, warnings));
    assertTrue(sink.toString().contains("reserved definition expects"));
  }

  @Test
  public void testCorrectlyDeclaredReservedKeyDoesNotWarn() {
    // AC declared exactly as the reserved definition expects (Number=A, Type=Integer) must not warn
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=AC", "Number=A", "Type=Integer", "Description=\"d\""}, warnings));
    // VcfWarnings.warn writes only the formatted message text (plus a newline) to its sink -- no level name --
    // so an empty sink is the only sound "nothing warned" assertion
    assertEquals("", sink.toString());
  }

  @Test
  public void testCorrectlyDeclaredStructuralVariantKeyDoesNotWarn() {
    // END is declared natively in VCFv4.3.tex (the target) itself (Number=1, Type=Integer), unchanged from
    // VCFv4.2.tex -- exercises the structural-variant sourcing path, not just the "standard" 4.3-table path
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=END", "Number=1", "Type=Integer", "Description=\"d\""}, warnings));
    assertEquals("", sink.toString());
  }

  @Test
  public void testNonReservedKeyIsNotChecked() {
    // a custom/non-reserved ID has no canonical Number/Type to check against -- VCFv4.2 explicitly allows extra
    // fields, so this must not warn regardless of what Number/Type it declares
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=MYCUSTOM", "Number=3", "Type=String", "Description=\"d\""}, warnings));
    assertEquals("", sink.toString());
  }

  @Test
  public void testValidateWithExtraPropertyDoesNotAffectCoreFields() {
    // combined with an extra raw property, validate() must still correctly re-derive Type/Number and not treat
    // the typed constructor's own Number/Type/Source/Version properties as unexpected extras (see
    // testValidateRefreshesTypeAfterRawMutation for the general validate() re-derivation this exercises)
    InfoMetadata md = new InfoMetadata(STRICT_CONTEXT, "X", "d", InfoType.Integer, "1", "src", "1.0");
    md.putPropertyRaw("Custom", "extra");
    md.validate();
    assertEquals(InfoType.Integer, md.getType());
    assertEquals("1", md.getNumber());
    assertEquals("extra", md.getPropertyRaw("Custom"));
  }

  @Test
  public void testMapConstructorIsNotAttributeOrderChecked() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_info_003.vcf's ##CauseOfFailure=Incorrect order of INFO
    // fields -- attribute-order checking was removed from every keyed declaration type (see
    // VCF_SPEC_AMBIGUITIES.md's "##META attribute order" entry), so this now warns about nothing at all.
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"Number=1", "Type=Integer", "Description=\"Raw read depth\"", "ID=DP"}, warnings));
    assertEquals("", sink.toString());
  }

  @Test
  public void testTypedConstructorInsertsPropertiesInCanonicalOrder() {
    // Not enforced by any validation any more (attribute order isn't checked at all), but the typed
    // constructor still inserts properties in the ID, Number, Type, Description, Source, Version order for
    // output readability, matching VCFv4.4.tex:134-135's "to aid human readability" recommendation. Source/
    // Version are both non-null here because an earlier version of this constructor inserted Description after
    // Source/Version instead of before them, which only this specific case would have caught.
    InfoMetadata info = new InfoMetadata(STRICT_CONTEXT, "DP", "Raw read depth", InfoType.Integer, "1", "src", "1.0");
    assertEquals(List.of("ID", "Number", "Type", "Description", "Source", "Version"),
        new ArrayList<>(info.getPropertyKeys()));
  }

  @Test
  public void testTypedConstructorRejectsInvalidNumber() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "AN", "desc", InfoType.Integer, "not-a-number", null, null));
    assertTrue(ex.getMessage().contains("Number is not a valid VCF number"));
  }

  @Test
  public void testTypedConstructorRejectsInvalidNumberMessageIsTruncatedAndEscaped() {
    // regression test for the fifth re-verification round's finding #1: BaseMetadata.checkNumberProperty's
    // "Number is not a valid VCF number" throw goes through BaseMetadata.warnOrThrow, one of the three shared
    // warn-or-throw implementations that previously embedded a String arg verbatim and unbounded. A huge,
    // control-character-laden Number value reaches this message directly from the caller's own input.
    String hugeNumber = "\r" + "9".repeat(200);
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "AN", "desc", InfoType.Integer, hugeNumber, null, null));
    assertTrue(ex.getMessage().contains("\\r"), "raw CR must be escaped, not embedded verbatim: " + ex.getMessage());
    assertFalse(ex.getMessage().contains("\r"), "message must not contain a raw CR: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    // the two assertFalse/assertTrue checks above only prove escaping happened, not truncation -- see
    // InternalUtilsTest.testCheckReservedFormatConstraintsFtWhitespaceMessageIsTruncatedAndEscaped's identical
    // comment; checking for the escaped-but-untruncated form closes that gap
    String escapedHugeNumber = "\\r" + "9".repeat(200);
    assertFalse(ex.getMessage().contains(escapedHugeNumber),
        "escaped-but-untruncated value must not appear: " + ex.getMessage());
    assertTrue(ex.getMessage().length() < 250, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testTypedConstructorRejectsNullNumberWithSpecificMessage() {
    // regression test: putPropertyRaw(NUMBER, number) used to run before this class's own Number-format
    // check below it, so a null Number threw BaseMetadata's generic "Metadata property \"Number\" has a
    // null value" message instead of this class's own, more specific one -- the specific check was dead
    // code for the null case.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "AN", "desc", InfoType.Integer, null, null, null));
    assertTrue(ex.getMessage().contains("INFO Number is not a valid VCF number: 'null'"));
  }

  @Test
  public void testFlagWithMissingNumberViaTypedConstructorWarnsOnceNotTwice() {
    // regression test for the fifth re-verification round's finding #7: the typed constructor's own
    // Type=Flag-implies-Number=0 check had no unquotedNumber != null guard, unlike init()'s own identical
    // check (the Map-based constructor, already covered by testFlagWithMissingNumberWarnsOnceNotTwice), so
    // a Flag with Number simply absent fired both the legitimate "Number is not a valid VCF number: 'null'"
    // warning AND a second, redundant "has Type=Flag but Number is 'null' (should be 0)" one.
    StringWriter sink = new StringWriter();
    new InfoMetadata(lenientContext(new VcfWarnings(sink)), "XX", "desc", InfoType.Flag, null, null, null);
    assertTrue(sink.toString().contains("Number is not a valid VCF number: 'null'"), sink.toString());
    assertFalse(sink.toString().contains("has Type=Flag but Number is"), sink.toString());
  }

  @Test
  public void testReservedKeyWithMissingNumberViaTypedConstructorWarnsOnceNotTwice() {
    // regression test for the fifth re-verification round's finding #7: the typed constructor's
    // reserved-Number-mismatch check had no unquotedNumber != null guard, unlike init()'s own identical
    // check (the Map-based constructor), so a reserved key (here AC, expected Number "A") with Number
    // simply absent fired both the legitimate "Number is not a valid VCF number: 'null'" warning AND a
    // second, redundant "has Number 'null' but the reserved definition expects 'A'" one.
    StringWriter sink = new StringWriter();
    new InfoMetadata(lenientContext(new VcfWarnings(sink)), "AC", "desc", InfoType.Integer, null, null, null);
    assertTrue(sink.toString().contains("Number is not a valid VCF number: 'null'"), sink.toString());
    assertFalse(sink.toString().contains("but the reserved definition expects"), sink.toString());
  }

  @Test
  public void testTypedConstructorPrioritizesInvalidNumberOverNullType() {
    // documents a real, narrow behavior change from moving the Number-format check ahead of putPropertyRaw:
    // when Number is malformed (non-null) AND Type is null simultaneously, the Number-format error now wins.
    // Previously the Type-null check fired first (it ran before the malformed-Number check, which used to
    // sit after Type/Description handling), so this exact input used to throw "INFO type is null" instead.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "AN", "desc", null, "not-a-number", null, null));
    assertTrue(ex.getMessage().contains("Number is not a valid VCF number"));
  }

  @Test
  public void testTypedConstructorRejectsFlagWithNonZeroNumber() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "DB", "desc", InfoType.Flag, "1", null, null));
    assertTrue(ex.getMessage().contains("has Type=Flag but Number is '1'"));
  }

  @Test
  public void testTypedConstructorRejectsZeroNumberWithNonFlagType() {
    // regression test: the converse of testTypedConstructorRejectsFlagWithNonZeroNumber above -- Number=0 is
    // spec-tied exclusively to Type=Flag (VCFv4.3.tex line 171), so a non-Flag type declaring Number=0 is
    // equally non-compliant, but only the Flag-implies-0 direction was checked, not this one.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "XYZ", "desc", InfoType.Integer, "0", null, null));
    assertTrue(ex.getMessage().contains("has Number '0' but Type is 'Integer'"));
  }

  @Test
  public void testMapConstructorRejectsZeroNumberWithNonFlagType() {
    StringWriter sink = new StringWriter();
    new InfoMetadata(lenientContext(new VcfWarnings(sink)), InternalUtils.extractProperties(
        new String[] {"ID=XYZ", "Number=0", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertTrue(sink.toString().contains("has Number '0' but Type is 'Integer'"));
  }

  @Test
  public void testTypedConstructorRejectsReservedNumberMismatch() {
    // AC is reserved with expected Number "A" (VCFv4.2.tex reserved INFO table)
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "AC", "desc", InfoType.Integer, "1", null, null));
    assertTrue(ex.getMessage().contains("reserved definition expects"));
  }

  @Test
  public void testAdReservedNumberCheckIsVersionGated() {
    // AD is reserved with expected Number "R" only as of VCFv4.3.tex's Reserved INFO Keys table
    // (table:reserved-info) -- VCFv4.2.tex does not table INFO AD at all (ReservedInfoProperty.isAddedAtV4_3,
    // the INFO-side mirror of FORMAT's own AD/ADF/ADR/PP/CNP gap). "Number=." is the form many real-world 4.2
    // pipelines emit for a custom AD-named key; regression test for the same fix as FormatMetadataTest's
    // testAdReservedNumberCheckIsVersionGated -- this must not warn/throw under 4.2, but must under 4.3+.
    StringWriter sink41 = new StringWriter();
    InfoMetadata md41 = new InfoMetadata(VcfContext.lenient("VCFv4.1", new VcfWarnings(sink41)),
        InternalUtils.extractProperties(
            new String[]{"ID=AD", "Number=.", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertEquals(".", md41.getNumber());
    assertFalse(sink41.toString().contains("reserved definition expects"));

    StringWriter sink42 = new StringWriter();
    InfoMetadata md42 = new InfoMetadata(lenientContext(new VcfWarnings(sink42)), InternalUtils.extractProperties(
        new String[]{"ID=AD", "Number=.", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertEquals(".", md42.getNumber());
    assertFalse(sink42.toString().contains("reserved definition expects"));

    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(strict43, "AD", "desc", InfoType.Integer, ".", null, null));
    assertTrue(ex.getMessage().contains("reserved definition expects"));

    StringWriter sink43 = new StringWriter();
    VcfWarnings warnings43 = new VcfWarnings(sink43);
    InfoMetadata md43 = new InfoMetadata(VcfContext.lenient("VCFv4.3", warnings43), InternalUtils.extractProperties(
        new String[]{"ID=AD", "Number=.", "Type=Integer", "Description=\"d\""}, warnings43));
    assertEquals(".", md43.getNumber());
    assertTrue(sink43.toString().contains("reserved definition expects"));
  }

  @Test
  public void testTypedConstructorAcceptsValidInfo() {
    InfoMetadata md = new InfoMetadata(STRICT_CONTEXT, "AF", "allele freq", InfoType.Float, "A", null, null);
    assertEquals(InfoType.Float, md.getType());
    assertEquals("A", md.getNumber());
  }

  @Test
  public void testMapConstructorRequiresContextInstance() {
    java.util.Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=X", "Number=bad", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT);
    assertThrows(NullPointerException.class, () -> new InfoMetadata(null, props));
  }

  @Test
  public void testMapConstructorWarnsInsteadOfThrowingForInvalidNumber() {
    java.util.Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=X", "Number=bad", "Type=Integer", "Description=\"d\""}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    InfoMetadata md = new InfoMetadata(lenientContext(new VcfWarnings(sink)), props);
    assertEquals("bad", md.getNumber());
    assertTrue(sink.toString().contains("Number is not a valid VCF number"));
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, null, "d", InfoType.String, "1", null, null));
    assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "", "d", InfoType.String, "1", null, null));
    assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "   ", "d", InfoType.String, "1", null, null));
  }

  @Test
  public void testUnquotedDescriptionWarningAttributedToInfoMetadataNotIdDescriptionMetadata() {
    // regression test: IdDescriptionMetadata.init()'s warn calls must embed InfoMetadata's own label ("INFO"),
    // set via the required label constructor parameter, in the message text -- otherwise an InfoMetadata's
    // Description warning is indistinguishable from an IdDescriptionMetadata one, since both now route through
    // the same shared VcfWarnings logger
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=AA", "Number=1", "Type=String", "Description=unquoted"}, warnings));
    assertTrue(sink.toString().contains("INFO"));
  }

  @Test
  public void testIdWithWhitespaceWarns() {
    // regression test: an INFO ID containing whitespace was previously silently accepted -- bcftools 1.23.1
    // warns "Invalid tag name" for this exact case
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    new InfoMetadata(lenientContext(warnings), InternalUtils.extractProperties(
        new String[]{"ID=A B", "Number=1", "Type=String", "Description=\"d\""}, warnings));
    assertTrue(sink.toString().contains("must not contain whitespace"));
  }

  @Test
  public void testGetReservedNumberReturnsReservedCode() {
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=A", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertEquals(SpecialVcfNumber.ONE_PER_ALT, md.getReservedNumber());
  }

  @Test
  public void testGetReservedNumberReturnsNullWhenNumberIsMissing() {
    // regression test: getReservedNumber() used to throw NullPointerException here instead of returning null,
    // because SpecialVcfNumber.fromId previously had no null guard; it now returns null for a null id.
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertNull(md.getNumber());
    assertNull(md.getReservedNumber());
  }

  @Test
  public void testGetSourceAndVersionUnquote() {
    // regression-shaped test: getSource()/getVersion() had zero test coverage despite using
    // getPropertyUnquoted (the same operation whose sibling, ContigMetadata.getLength(), previously had a
    // real quoted-value bug) -- verify the quote-stripping actually round-trips, not just that construction
    // doesn't throw
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=String", "Description=\"d\"", "Source=\"dbSNP\"",
            "Version=\"138\""}, VcfWarnings.DEFAULT));
    assertEquals("dbSNP", md.getSource());
    assertEquals("138", md.getVersion());
  }

  @Test
  public void testTypedConstructorSourceAndVersionRoundTrip() {
    // the typed constructor auto-quotes Source/Version via putAndQuoteProperty(); getSource()/getVersion()
    // must unquote them back to the original values, not just avoid throwing
    InfoMetadata md = new InfoMetadata(STRICT_CONTEXT, "X", "d", InfoType.String, "1", "dbSNP", "138");
    assertEquals("dbSNP", md.getSource());
    assertEquals("138", md.getVersion());
  }

  @Test
  public void testGetSourceAndVersionReturnNullWhenAbsent() {
    InfoMetadata md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
        new String[]{"ID=X", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
    assertNull(md.getSource());
    assertNull(md.getVersion());
  }

  @Test
  public void testSourceAsSingleUnterminatedQuoteCharacterStillWarnsAsUnquoted() {
    // regression test for the fifth re-verification round's finding #3: a raw Source value of exactly "\""
    // (one, unterminated double-quote character) satisfies both startsWith("\"") and endsWith("\"") on that
    // same one character, so without a length guard this silently passed as "properly quoted" -- the exact
    // shape InternalUtils.unquote() already guards against. Built directly via the Map-based constructor
    // with a literal Map (not InternalUtils.extractProperties) so the raw stored value is exactly one '"'
    // character, bypassing the attribute-list tokenizer's own unrelated quote-parsing entirely.
    Map<String, String> props = new HashMap<>();
    props.put("ID", "X");
    props.put("Number", "1");
    props.put("Type", "String");
    props.put("Description", "\"d\"");
    props.put("Source", "\"");
    StringWriter sink = new StringWriter();
    new InfoMetadata(lenientContext(new VcfWarnings(sink)), props);
    assertTrue(sink.toString().contains("should be quoted"), sink.toString());
  }

  @Test
  public void testTypedConstructorRejectsInvalidTagNameCharacter() {
    assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "AB*C", "d", InfoType.String, "1", null, null));
  }

  @Test
  public void testTypedConstructorRejectsLeadingDigit() {
    assertThrows(VcfFormatException.class,
        () -> new InfoMetadata(STRICT_CONTEXT, "1ABC", "d", InfoType.String, "1", null, null));
  }

  @Test
  public void testTypedConstructorAcceptsValidTagName() {
    InfoMetadata md = new InfoMetadata(STRICT_CONTEXT, "DP", "d", InfoType.Integer, "1", null, null);
    assertEquals("DP", md.getId());
  }

  @Test
  public void testTypedConstructorAccepts1000GLegacyException() {
    InfoMetadata md = new InfoMetadata(STRICT_CONTEXT, "1000G", "d", InfoType.Flag, "0", null, null);
    assertEquals("1000G", md.getId());
  }

  @Test
  public void testLenientConstructorWarnsOnInvalidTagNameCharacter() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    InfoMetadata md;
    try {
      md = new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=AB*C", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("does not match the required tag-name grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("AB*C", md.getId());
  }

  @Test
  public void testLenientConstructorAccepts1000GLegacyExceptionWithoutWarning() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=1000G", "Number=0", "Type=Flag", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertFalse(appender.anyMessageContains("does not match the required tag-name grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testLenientConstructorDoesNotDoubleWarnForCommaContainingId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=AB,C", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(0, appender.countMessagesContaining("does not match the required tag-name grammar"));
      assertEquals(1, appender.countMessagesContaining("must not contain a comma"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorDoesNotDoubleWarnForCommaContainingId() {
    // regression guard: the typed constructor previously checked the tag-name grammar against the raw id
    // without stripping commas/whitespace first, re-warning about a comma IdMetadata's own super(...) call had
    // already reported -- mirrors testLenientConstructorDoesNotDoubleWarnForCommaContainingId above, which
    // already covered this correctly for the Map-based constructor
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(LENIENT_CONTEXT, "AB,C", "d", InfoType.String, "1", null, null);
      assertEquals(0, appender.countMessagesContaining("does not match the required tag-name grammar"));
      assertEquals(1, appender.countMessagesContaining("must not contain a comma"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testLenientConstructorWarnsForBothCommaAndInvalidCharacterDefects() {
    // regression test: the old guard skipped the entire tag-name-grammar check whenever a comma (or whitespace)
    // was present anywhere in the id, which silently suppressed a genuinely different defect (e.g. an invalid
    // character) coexisting in the same id -- confirmed against bcftools 1.23.1, which flags the '*' in
    // "AB,C*D" as "Invalid tag name" independently of the comma. Both defects must now be reported.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(LENIENT_CONTEXT, InternalUtils.extractProperties(
          new String[]{"ID=AB,C*D", "Number=1", "Type=String", "Description=\"d\""}, VcfWarnings.DEFAULT));
      assertEquals(1, appender.countMessagesContaining("must not contain a comma"));
      assertEquals(1, appender.countMessagesContaining("does not match the required tag-name grammar"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorAppliesReservedKeyValidationToQuotedId() {
    // regression test: the typed constructor previously validated the raw (possibly quoted) id/number instead
    // of unquoting first, matching neither its own tag-name-grammar check nor the ReservedInfoProperty lookup
    // against a quoted value -- so ID="AF" silently skipped the Number/Type mismatch check this class's own
    // init() (the Map-based constructor's path) already performs for the equivalent unquoted declaration.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new InfoMetadata(LENIENT_CONTEXT, "\"AF\"", "d", InfoType.String, "\"1\"", null, null);
      assertEquals(0, appender.countMessagesContaining("does not match the required tag-name grammar"));
      assertEquals(0, appender.countMessagesContaining("is not a valid VCF number"));
      assertEquals(1, appender.countMessagesContaining("but the reserved definition expects 'A'"));
      assertEquals(1, appender.countMessagesContaining("but the reserved definition expects 'Float'"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}

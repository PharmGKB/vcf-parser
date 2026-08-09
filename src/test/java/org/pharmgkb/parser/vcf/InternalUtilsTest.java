package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link InternalUtils}.
 * @author Douglas Myers-Turnbull
 */
public class InternalUtilsTest {

  @Test
  public void testAltBasePatternSimple() throws Exception {
    System.out.println(InternalUtils.ALT_BASE_PATTERN.pattern());
    String[] shouldPass = {".", "A", "AaT", "*", ".An", "An.", "<3&*FH#>", ".TGCA"};
    for (String test : shouldPass) {
      assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was not recognized");
    }
  }

  @Test
  public void testAltBasePatternBreakpoint() throws Exception {
    System.out.println(InternalUtils.ALT_BASE_PATTERN.pattern());
    // all copied from the VCF 4.2 specification, in order
    String[] shouldPass = {" G]17:198982]", " ]13:123456]T", "C[2:321682[", "[17:198983[A", "A]2:321681]",
        " [13:123457[C", "]13 : 123456]AGTNNNNNCAT", " CAGTNNNNNCA[2 : 321682[", "C[<ctg1>: 1[", "] <ctg1 >: 329]A ",
        "C<ctg1 >", " C[<ctg1>: 7[", "] <ctg1 >: 214]A", " ]13 : 123456]T",
        "]13 : 123456]A", " G[13 : 123460[", " ]13 : 123456]T", " C[2 : 321682[", "]2 : 321681]A", ".[13 : 123457[",
        " ]13 : 123456]T", " C[1 : 1[", " ]1 : 0]A", "G[13 : 123457[", " ]13 : 123456]T", " C[2 : 321682[",
        " ]2 : 321681]A", "G]2 : 421681]", "[2 : 421682[T", "A]2 : 321681]", "[2 : 321682[C", " T]13 : 123462]",
        "A]2 : 321687]", " [2 : 321682[C" };
    for (String test : shouldPass) {
      test = test.replaceAll(" ", "");
      assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was not recognized");
    }
  }

  @Test
  public void testAltBasePatternBad() throws Exception {
    String[] shouldPass = {"<at", "at>", "", "<", "]34[AT", "[34]AT", "[<xxx[AT"};
    for (String test : shouldPass) {
      assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was recognized");
    }
  }

  @Test
  public void testAltBasePatternBreakpointAcceptsNonNumericChromosome() {
    // the mate chromosome name is not restricted to digits by the spec; the previous \d+-only pattern only ever
    // passed because every example in the spec itself happens to use a purely numeric chromosome name
    String[] shouldPass = {"A]chr1:100]", "A]X:100]", "G]scaffold_12:55]", "]chrX:1]T", "C[chr1:1["};
    for (String test : shouldPass) {
      assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was not recognized");
    }
  }

  @Test
  public void testAltBasePatternBreakpointRequiresPosition() {
    // "chr:pos" is always both parts together per the spec; there is no valid breakend form giving a chromosome
    // without a position (a truly mate-less breakend uses the unrelated "G."/".A" single-breakend shorthand)
    String[] shouldFail = {"A]2]", "A]chr1]", "C[2[", "]13]T", "[<ctg1>[A"};
    for (String test : shouldFail) {
      assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was recognized");
    }
  }

  @Test
  public void testAltBasePatternBreakendRequiresReplacementString() {
    // hts-specs' own conformance suite (test/vcf/4.2/failed/failed_body_alt_005.vcf) rejects a breakend whose
    // replacement string "t" is missing on both sides: CauseOfFailure="Breakend without bases at either side",
    // ALT="[1:100[". Every officially-passing breakend example (test/vcf/4.1/passed/passed_body_alt.vcf) has
    // content on exactly one side (e.g. "]1:100]AGT", "[1:100[AGT", "AGT]1:100]", "AGT[1:100[").
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("[1:100[").matches());
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("]1:100]").matches());
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher("[1:100[AGT").matches());
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher("AGT]1:100]").matches());
  }

  @Test
  public void testAltBasePatternBreakendReplacementStringCanBeLiteralDot() {
    // VCFv4.2's own "Telomeres" section gives ".[13:123457[" (CHROM=1, POS=0, REF=N) as a real example: a virtual
    // telomeric breakend where "t" (the replacement string) is literally "." -- representing no real reference
    // sequence at the virtual position -- not "t" being absent (which testAltBasePatternBreakendRequiresReplacementString
    // confirms is invalid).
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher(".[13:123457[").matches());
  }

  @Test
  public void testAltBasePatternAcceptsLargeInsertionShorthand() {
    // VCFv4.2's own "Large Insertions" section gives this as a real example (13 321682 INS0 T C<ctg1> ...),
    // calling it "the shorthand notation" for "the special case of the complete insertion of a sequence between
    // two base pairs": a base run directly followed by a symbolic ID, with NO breakend bracket between them.
    // Confirmed present in the raw VCFv4.2 spec text itself (not just later versions). A prior review round
    // mistakenly "fixed" this as over-permissive and rejected it, based on incomplete spec research -- that
    // change was reverted. Note that the EBI vcf_validator 0.10.2 (github.com/ebivariation/vcf-validator) does
    // NOT implement this shorthand -- it rejects "C<ctg1>" with the same generic error it gives for clearly
    // invalid ALT syntax -- but the VCFv4.2 spec text is unambiguous that this form is valid, so this parser
    // accepts it. This test exists specifically to prevent this exact regression from recurring.
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher("C<ctg1>").matches());
  }

  @Test
  public void testAltBasePatternRejectsStrayDotBesideBreakendReplacement() {
    // t (sf_breakendReplacement) itself already accepts a literal "." for the telomere case; a breakpoint pattern
    // used to also wrap the whole expression in its own optional leading/trailing dot, a leftover from before t
    // supported "." directly. Combined with t's new "." alternative, that let a stray extra dot slip in beside t
    // with no basis in any spec example. Fixed by removing the now-redundant outer wrapper.
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("..[1:1[").matches());
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("A]1:1].").matches());
  }

  @Test
  public void testAltBasePatternAcceptsUnconfirmedBaseSymbolicConcatenations() {
    // Unlike "C<ctg1>" above, these two forms (bases both before AND after a symbolic ID; multiple concatenated
    // symbolic IDs) have no confirming example anywhere: VCFv4.2 through 4.5 are silent, hts-specs' own
    // conformance suite (test/vcf/{4.1,4.2,4.3}/{passed,failed}/*.vcf) has zero files referencing "ctg" at all,
    // and EBI vcf_validator 0.10.2 rejects them (with the same generic error it also gives the confirmed-valid
    // "C<ctg1>" case, so it can't distinguish the two). bcftools 1.23.1 accepts both, but that carries no
    // evidentiary weight: it also accepts outright invalid ALT text ("XYZ123!!!") with no complaint, so it isn't
    // validating ALT syntax at all. With no source able to confirm or deny these forms, and a previous attempt to
    // narrow this regex having incorrectly broken the one confirmed-valid case, this permissiveness is left as-is
    // rather than risked again -- this test locks in that deliberate decision, not a confirmed compliance claim.
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher("AC<ctg1>GT").matches());
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher("<ctg1><ctg2>").matches());
  }

  @Test
  public void testAltBasePatternDoesNotCatastrophicallyBacktrackOnLongMalformedInput() {
    // regression test: sf_simpleAltPattern's inner nucleotide quantifier must be possessive. Without it, a long
    // nucleotide run followed by one invalid character triggers the classic (x+)+ backtracking blowup -- measured
    // directly against the composite ALT_BASE_PATTERN below (not the isolated sub-pattern, whose blowup alone is
    // much smaller): ~13.9s at N=20,000 before the fix. 3 seconds is a wide margin above the fixed pattern's ~1ms
    // at this size, so this won't be flaky on a slower CI machine, while still catching a regression back to
    // greedy matching.
    String malformed = "A".repeat(20_000) + "!";
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
        assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher(malformed).matches()));
  }

  @Test
  public void testAltBasePatternDoesNotStackOverflowOnLongSymbolicIdConcatenation() {
    // regression test: sf_simpleAltPattern's outer alternation-quantified group must be possessive too --
    // distinct from the inner nucleotide-run quantifier's possessiveness tested above, which only prevents
    // exponential-time backtracking on one long run of a single alternative. This test exercises a different
    // failure shape: a long *sequence* of short alternatives (many short symbolic IDs concatenated, a form
    // testAltBasePatternAcceptsUnconfirmedBaseSymbolicConcatenations already treats as valid). Java's regex
    // engine recurses one Java call frame per repetition for a quantified group containing alternation, so
    // without the outer possessive quantifier this blows the JVM call stack directly (StackOverflowError)
    // regardless of backtracking -- confirmed before the fix: 700 repetitions of "<a>" succeeded, 1,000
    // crashed. 5,000 repetitions (15,000 chars) is a wide margin above that threshold.
    String manySymbolicIds = "<a>".repeat(5_000);
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher(manySymbolicIds).matches());
  }

  @Test
  public void testExtractPropertiesWithEscapes() {
    // an escaped backslash (\\) and an escaped double-quote (\") inside a quoted Description must not crash and must be
    // preserved (the split must not treat the escaped quote as a real quote)
    Map<String, String> props = InternalUtils.extractPropertiesFromLine("ID=X,Description=\"a \\\\ b \\\" c\"",
        VcfWarnings.DEFAULT);
    assertEquals("X", props.get("ID"));
    assertEquals("\"a \\\\ b \\\" c\"", props.get("Description"));
  }

  @Test
  public void testStrayBackslashDoesNotSuppressDelimiter() {
    // a backslash not followed by "\\" or "\"" has no defined escape meaning and must not consume/protect the following
    // delimiter; before the fix, the comma here was incorrectly treated as escaped, merging both properties into one
    // blob with two "=" signs, which then threw instead of parsing to two properties
    Map<String, String> props = InternalUtils.extractPropertiesFromLine("Source=A\\,Number=1", VcfWarnings.DEFAULT);
    assertEquals("A\\", props.get("Source"));
    assertEquals("1", props.get("Number"));
  }

  @Test
  public void testExtractPropertiesDuplicateAttributeWarnsAndKeepsLast() {
    // a declaration with a repeated attribute (e.g. ##INFO=<ID=X,Number=1,Number=2,...>) silently kept only the
    // last value with no diagnostic at all; must at least warn (VCF metadata-declaration policy is lenient, not a
    // structural violation, so this doesn't throw)
    Map<String, String> props = InternalUtils.extractPropertiesFromLine("ID=X,Number=1,Number=2", VcfWarnings.DEFAULT);
    assertEquals("2", props.get("Number"));
  }

  @Test
  public void testExtractPropertiesNoPlaceholderCollision() {
    // a value literally containing the old "~~~~" placeholder must be preserved, and an escaped quote must not be
    // treated as a real quote (so the comma inside the quoted value is not a top-level delimiter)
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=X,Description=\"literal ~~~~ and \\\"q, v\\\"\"", VcfWarnings.DEFAULT);
    assertEquals("X", props.get("ID"));
    assertEquals("\"literal ~~~~ and \\\"q, v\\\"\"", props.get("Description"));
  }

  @Test
  public void testExtractPropertiesPreservesInsertionOrder() {
    // real example from an hts-specs SAMPLE fixture: this attribute order was previously scrambled to
    // Description,Genomes,Mixture on write because extractProperties built a plain HashMap (hash-bucket order),
    // not insertion order
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=Sample_ok,Genomes=G1_ID;G2_ID;GK_ID,Mixture=N1;N2;NK,Description=\"S1;S2;SK\"", VcfWarnings.DEFAULT);
    assertEquals(Arrays.asList("ID", "Genomes", "Mixture", "Description"), new ArrayList<>(props.keySet()));
  }

  @Test
  public void testExtractPropertiesFromLineWarnsOnUnbalancedQuotes() {
    // an unescaped quote makes it ambiguous where a quoted value ends -- reproduces
    // test/vcf/{4.1,4.2}/failed/failed_meta_001.vcf's Description="Description with "non-escaped quote" (an odd
    // count of unescaped '"' characters). Confirmed bcftools 1.23.1 hard-errors here and vcf_validator 0.10.2
    // reports the file invalid, but this stays warn-not-throw per AGENTS.md's metadata-declaration leniency policy
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    Map<String, String> props;
    try {
      props = InternalUtils.extractPropertiesFromLine(
          "ID=DEL,Number=1,Type=String,Description=\"Description with \"non-escaped quote\"", VcfWarnings.DEFAULT);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // best-effort split is unchanged by the warning -- still recovers the earlier attributes correctly
    assertEquals("DEL", props.get("ID"));
    assertEquals("1", props.get("Number"));
    assertEquals("String", props.get("Type"));
  }

  @Test
  public void testExtractPropertiesFromLineDoesNotWarnOnEscapedQuote() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.extractPropertiesFromLine("ID=X,Description=\"a \\\" b\"", VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testExtractPropertiesFromLineDoesNotWarnOnEmptyQuotedValue() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.extractPropertiesFromLine("ID=X,Description=\"\"", VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testExtractPropertiesFromLineDoesNotWarnOnUnquotedValue() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.extractPropertiesFromLine("ID=X,Number=1,Type=String", VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testSymbolicAltRejectsWhitespace() {
    // an angle-bracketed ALT ID may not contain whitespace, commas, or angle brackets
    assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher("<DEL>").matches());
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("<DEL bad>").matches());
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("<a,b>").matches());
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("<a<b>").matches());
  }

  @Test
  public void testQuoteEscapesBackslashAndQuote() {
    assertEquals("\"plain\"", InternalUtils.quote("plain"));
    assertEquals("\"a \\\\ b\"", InternalUtils.quote("a \\ b"));
    assertEquals("\"a \\\"quoted\\\" b\"", InternalUtils.quote("a \"quoted\" b"));
  }

  @Test
  public void testUnquoteDecodesEscapes() {
    assertEquals("plain", InternalUtils.unquote("\"plain\""));
    assertEquals("a \\ b", InternalUtils.unquote("\"a \\\\ b\""));
    assertEquals("a \"quoted\" b", InternalUtils.unquote("\"a \\\"quoted\\\" b\""));
    // not wrapped in quotes: returned as-is
    assertEquals("unquoted", InternalUtils.unquote("unquoted"));
    // single-quote-character input must not crash (previously threw StringIndexOutOfBoundsException)
    assertEquals("\"", InternalUtils.unquote("\""));
  }

  @Test
  public void testQuoteUnquoteRoundTrip() {
    String[] values = { "plain", "a \\ b", "a \"quoted\" b", "\\\"both\\\"", "" };
    for (String value : values) {
      assertEquals(value, InternalUtils.unquote(InternalUtils.quote(value)), "round-trip failed for: " + value);
    }
  }

  @Test
  public void testSplitProp() throws Exception {
    assertEquals(Pair.of("abc", "\"d=ef\""), InternalUtils.splitProperty("abc=\"d=ef\""));
    assertEquals(Pair.of("\"a=bc\"", "\"d=ef\""), InternalUtils.splitProperty("\"a=bc\"=\"d=ef\""));
    assertEquals(Pair.of("\"a=b=c\"", "\"d=ef\""), InternalUtils.splitProperty("\"a=b=c\"=\"d=ef\""));
  }

  @Test
  public void testCheckNoLineTerminator() {
    InternalUtils.checkNoLineTerminator("key", "value"); // no line terminator: does not throw
    InternalUtils.checkNoLineTerminator("key", null); // a null value is not checked (nothing to check)
    assertThrows(VcfFormatException.class, () -> InternalUtils.checkNoLineTerminator("bad\nkey", "value"));
    assertThrows(VcfFormatException.class, () -> InternalUtils.checkNoLineTerminator("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> InternalUtils.checkNoLineTerminator("key", "bad\rvalue"));
  }

  @Test
  public void testConvertPropertyCharacterReturnsCharacterNotString() {
    // convertElement's Character.class branch previously returned the raw String; type erasure let that past
    // convertProperty() itself, but it then threw ClassCastException at the caller's assignment site below
    Character value = InternalUtils.convertProperty(InfoType.Character, "A", VcfWarnings.DEFAULT);
    assertEquals(Character.valueOf('A'), value);
  }

  @Test
  public void testCheckReservedFormatConstraintsFtWarnsButDoesNotThrow() {
    // FT's grammar (PASS/./semicolon-separated codes, no whitespace, PASS/'.' not combined with other codes) isn't
    // expressible through its declared Type=String, so it's checked separately; this is non-structural sample
    // content, so an invalid value warns rather than throws
    assertEquals("PASS",
        InternalUtils.checkReservedFormatConstraints("FT", "PASS", VcfVersion.V4_2, VcfWarnings.DEFAULT)); // valid: does not throw
    assertEquals("q10;q20",
        InternalUtils.checkReservedFormatConstraints("FT", "q10;q20", VcfVersion.V4_2, VcfWarnings.DEFAULT)); // valid: does not throw
    assertEquals("q10 q20",
        InternalUtils.checkReservedFormatConstraints("FT", "q10 q20", VcfVersion.V4_2, VcfWarnings.DEFAULT)); // whitespace in a code: warns
    assertEquals("PASS;q10",
        InternalUtils.checkReservedFormatConstraints("FT", "PASS;q10", VcfVersion.V4_2, VcfWarnings.DEFAULT)); // PASS + code: warns
    assertNull(
        InternalUtils.checkReservedFormatConstraints("FT", null, VcfVersion.V4_2, VcfWarnings.DEFAULT)); // no value set: nothing to check
    assertEquals(".",
        InternalUtils.checkReservedFormatConstraints("FT", ".", VcfVersion.V4_2, VcfWarnings.DEFAULT)); // missing value: nothing to check
  }

  @Test
  public void testCheckReservedFormatConstraintsFtDropsEmptyCodes() {
    // an empty semicolon-separated code is warned about and dropped, the same "warn and drop" convention already
    // used for an empty entry in FILTER (FT's grammar is explicitly modeled on FILTER's) and every other
    // position-independent list in this library -- unlike PASS/'.' combined with a genuine other code (see above),
    // which is a real grammar violation and must not be silently rewritten away
    assertEquals("q10;q20", InternalUtils.checkReservedFormatConstraints("FT", "q10;;q20", VcfVersion.V4_2, VcfWarnings.DEFAULT));
    assertEquals("q10", InternalUtils.checkReservedFormatConstraints("FT", "q10;", VcfVersion.V4_2, VcfWarnings.DEFAULT));
    assertEquals("q10", InternalUtils.checkReservedFormatConstraints("FT", ";q10", VcfVersion.V4_2, VcfWarnings.DEFAULT));
    // dropping the empty artifacts must not leave a false "combined with PASS" warning when PASS is the only
    // remaining real content (e.g. a trailing ';' after PASS)
    assertEquals("PASS", InternalUtils.checkReservedFormatConstraints("FT", "PASS;", VcfVersion.V4_2, VcfWarnings.DEFAULT));
  }

  @Test
  public void testCheckReservedFormatConstraintsFtDoesNotWarnWhenPassOrMissingIsNotActuallyCombinedWithAnotherCode() {
    // regression test: kept.size() > 1 alone previously triggered the "combines PASS or '.' with other filter
    // codes" warning even when every surviving code was itself the exact SAME code repeated (e.g. "PASS;PASS")
    // -- the warning's own wording implies a genuinely different code is present. "PASS" and "." are still
    // distinct codes from each other, though (PASS means "passed all filters", "." means "no filter information
    // recorded" -- genuinely contradictory, matching VcfPosition.checkFilters's identical treatment of the same
    // shape for the FILTER column), so "PASS;." must still warn, unlike same-code repetition.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      assertEquals("PASS;PASS", InternalUtils.checkReservedFormatConstraints("FT", "PASS;PASS", VcfVersion.V4_2, VcfWarnings.DEFAULT));
      assertFalse(appender.anyMessageContains("combines PASS or '.' with other filter codes"));

      assertEquals(".;.", InternalUtils.checkReservedFormatConstraints("FT", ".;.", VcfVersion.V4_2, VcfWarnings.DEFAULT));
      assertFalse(appender.anyMessageContains("combines PASS or '.' with other filter codes"));

      // PASS and "." are distinct codes, so this must still warn, unlike the two same-code cases above
      assertEquals("PASS;.", InternalUtils.checkReservedFormatConstraints("FT", "PASS;.", VcfVersion.V4_2, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("combines PASS or '.' with other filter codes"));

      // sanity check: a genuinely different code alongside PASS/'.' must still warn
      assertEquals("PASS;q10", InternalUtils.checkReservedFormatConstraints("FT", "PASS;q10", VcfVersion.V4_2, VcfWarnings.DEFAULT));
      assertTrue(appender.anyMessageContains("combines PASS or '.' with other filter codes"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsFtAllEmptyCodesNormalizesToMissingValueSentinel() {
    // regression test: when every semicolon-separated code is empty, the previous behavior returned "" (from
    // String.join(";", emptyList)), not "." -- convertProperty's only "treat as absent" check is
    // value == null || ".".equals(value), so "" silently reached type conversion unchanged, making
    // VcfSample.getProperty(ReservedFormatProperty.Filter) return a non-null empty string instead of null for a
    // sample whose FT content was entirely empty artifacts
    assertEquals(".", InternalUtils.checkReservedFormatConstraints("FT", ";;", VcfVersion.V4_2, VcfWarnings.DEFAULT));
    assertEquals(".", InternalUtils.checkReservedFormatConstraints("FT", ";", VcfVersion.V4_2, VcfWarnings.DEFAULT));
  }

  @Test
  public void testCheckReservedFormatConstraintsPsWarnsButDoesNotThrow() {
    // PS must be a non-negative 32-bit integer, but that constraint isn't expressible through its declared
    // Type=Integer (which maps to a 64-bit Long), so it's checked separately; this is non-structural sample
    // content, so an out-of-range value warns rather than throws
    InternalUtils.checkReservedFormatConstraints("PS", "0", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
    InternalUtils.checkReservedFormatConstraints("PS", String.valueOf(Integer.MAX_VALUE),
        VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
    InternalUtils.checkReservedFormatConstraints("PS", "-5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // negative: warns, does not throw
    InternalUtils.checkReservedFormatConstraints("PS", String.valueOf(Integer.MAX_VALUE + 1L),
        VcfVersion.V4_2, VcfWarnings.DEFAULT); // out of 32-bit range: warns
  }

  @Test
  public void testConvertPropertyPhaseSetPreservesOutOfRangeValue() {
    // the typed accessor still returns the parsed Long even when it violates PS's non-negative-32-bit constraint,
    // consistent with the "warn and preserve" policy for non-structural sample content
    Long value = InternalUtils.convertProperty(ReservedFormatProperty.PhaseSet, "-5", VcfVersion.V4_2, VcfWarnings.DEFAULT);
    assertEquals(-5L, value);
  }

  @Test
  public void testConvertPropertyFilterDropsEmptyCodes() {
    // unlike PS, the typed FT accessor returns a normalized value: an empty semicolon-separated code is dropped,
    // matching the "warn and drop" convention used for FILTER's own empty entries
    String value = InternalUtils.convertProperty(ReservedFormatProperty.Filter, "q10;;q20", VcfVersion.V4_2, VcfWarnings.DEFAULT);
    assertEquals("q10;q20", value);
  }

  @Test
  public void testConvertPropertyFilterAllEmptyCodesReturnsNullNotEmptyString() {
    // end-to-end regression test: convertProperty(ReservedProperty, ...) must return null, matching this
    // library's established null-means-absent contract, not "" -- see
    // testCheckReservedFormatConstraintsFtAllEmptyCodesNormalizesToMissingValueSentinel for the underlying fix
    String value = InternalUtils.convertProperty(ReservedFormatProperty.Filter, ";;", VcfVersion.V4_2, VcfWarnings.DEFAULT);
    assertNull(value);
  }

  @Test
  public void testCheckReservedInfoConstraintsNonNegativeKeysWarnButDoNotThrow() {
    // AC/AN/DP/END/MQ0/NS's declared Long Type accepts a negative value, but each is semantically a count/depth/
    // position that can't be negative -- checked separately, warning rather than throwing (non-structural INFO
    // content)
    for (String key : new String[] { "AC", "AN", "DP", "END", "MQ0", "NS" }) {
      InternalUtils.checkReservedInfoConstraints(key, "0", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
      InternalUtils.checkReservedInfoConstraints(key, "5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
      InternalUtils.checkReservedInfoConstraints(key, "-1", VcfVersion.V4_2, VcfWarnings.DEFAULT); // negative: warns, does not throw
    }
    InternalUtils.checkReservedInfoConstraints("AC", null, VcfVersion.V4_2, VcfWarnings.DEFAULT); // no value set: nothing to check
    InternalUtils.checkReservedInfoConstraints("AC", ".", VcfVersion.V4_2, VcfWarnings.DEFAULT); // missing value: nothing to check
  }

  @Test
  public void testCheckReservedInfoConstraintsNonNegativeKeyChecksEachListElement() {
    // AC/AN (Number=A/1 respectively) may carry a comma-joined list; a negative value anywhere in the list must
    // be caught, not just the first element
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedInfoConstraints("AC", "1,-2,3", VcfVersion.V4_2, VcfWarnings.DEFAULT);
      assertEquals(1, appender.countMessagesContaining("negative number"));
      assertTrue(appender.anyMessageContains("-2"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedInfoConstraintsAlleleFrequencyWarnsOutsideUnitInterval() {
    InternalUtils.checkReservedInfoConstraints("AF", "0", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
    InternalUtils.checkReservedInfoConstraints("AF", "1", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
    InternalUtils.checkReservedInfoConstraints("AF", "0.5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
    InternalUtils.checkReservedInfoConstraints("AF", "-0.01", VcfVersion.V4_2, VcfWarnings.DEFAULT); // below 0: warns, does not throw
    InternalUtils.checkReservedInfoConstraints("AF", "1.5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // above 1: warns, does not throw
    // NaN/Inf have no meaningful position within [0, 1] -- must not warn (or throw)
    InternalUtils.checkReservedInfoConstraints("AF", "NaN", VcfVersion.V4_2, VcfWarnings.DEFAULT);
    InternalUtils.checkReservedInfoConstraints("AF", "INF", VcfVersion.V4_2, VcfWarnings.DEFAULT);
  }

  @Test
  public void testCheckReservedInfoConstraintsAlleleFrequencyDoesNotHangOnPathologicallyLongValue() {
    // regression test: checkUnitInterval used to call `new BigDecimal(value)` with no length bound --
    // BigDecimal/BigInteger's decimal-string parsing is quadratic in digit count, so an attacker-controlled
    // AF value with no length bound was a CPU-exhaustion vector. A oversized value is now treated the same
    // as "not a number at all" (skipped, no warning here -- the real Type conversion reports it) without
    // ever attempting the expensive parse. 3 seconds is a wide margin above the fixed behavior's near-instant
    // rejection at this size; the unfixed code took seconds even at a fraction of this length.
    String pathologicallyLong = "1".repeat(500_000);
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
        InternalUtils.checkReservedInfoConstraints("AF", pathologicallyLong, VcfVersion.V4_2, VcfWarnings.DEFAULT));
  }

  @Test
  public void testConvertPropertyAlleleFrequencyGrammarMismatchWarnsOnce() {
    // regression test: checkReservedInfoConstraints's AF range check previously used VcfFloat.parse(...) to get
    // a comparable value, which has its own "does not match the VCF Float grammar" warning as a side effect for
    // a value that's BigDecimal-parseable but grammar-mismatched (e.g. "1.", a trailing decimal point with no
    // digits after it -- see VcfFloat.FLOAT_PATTERN's own comment). The generic Type conversion immediately
    // afterward (in convertProperty's own dispatch) already emits that exact warning once on its own, so calling
    // VcfFloat.parse a second time inside the range check doubled it. The fix parses with plain BigDecimal
    // (bypassing the grammar check entirely) purely to get a value to compare against [0, 1]. Deliberately uses
    // "1." (equals 1, still within [0, 1]) rather than an out-of-range value like "5." so this test isolates the
    // grammar-warning count from the unrelated, independently-tested out-of-range warning.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      List<VcfFloat> value = InternalUtils.convertProperty(ReservedInfoProperty.AlleleFrequency, "1.",
          VcfVersion.V4_2, VcfWarnings.DEFAULT);
      assertEquals(1, appender.countMessagesContaining("does not match the VCF Float grammar"));
      assertEquals(0, appender.countMessagesContaining("does not lie in the interval"));
      assertEquals(1, value.size());
      assertEquals(0, value.get(0).getValue().compareTo(new java.math.BigDecimal("1")));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testConvertPropertyAlleleCountPreservesNegativeValue() {
    // the typed accessor still returns the parsed value even when it violates AC's non-negative constraint,
    // consistent with the "warn and preserve" policy for non-structural INFO content
    List<Long> value = InternalUtils.convertProperty(ReservedInfoProperty.AlleleCount, "-5", VcfVersion.V4_2, VcfWarnings.DEFAULT);
    assertEquals(List.of(-5L), value);
  }

  @Test
  public void testCheckReservedInfoConstraintsCigarWarnsOnInvalidGrammarButDoesNotThrow() {
    // CIGAR's grammar (a lone "*", or one or more <length><operation> pairs) isn't expressible through its
    // declared Type=String (which accepts any string unchanged), so it's checked separately; this is
    // non-structural INFO content, so an invalid value warns rather than throws
    InternalUtils.checkReservedInfoConstraints("CIGAR", "8M2I4M1D3M", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
    InternalUtils.checkReservedInfoConstraints("CIGAR", "*",
        VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid ("unavailable"): does not throw
    InternalUtils.checkReservedInfoConstraints("CIGAR", "123MYCIGAR",
        VcfVersion.V4_2, VcfWarnings.DEFAULT); // illegal trailing chars: warns
    InternalUtils.checkReservedInfoConstraints("CIGAR", "0.05",
        VcfVersion.V4_2, VcfWarnings.DEFAULT); // a decimal number, not pairs: warns
    InternalUtils.checkReservedInfoConstraints("CIGAR", null, VcfVersion.V4_2, VcfWarnings.DEFAULT); // no value set: nothing to check
    InternalUtils.checkReservedInfoConstraints("CIGAR", ".", VcfVersion.V4_2, VcfWarnings.DEFAULT); // missing value: nothing to check
  }

  @Test
  public void testCheckReservedInfoConstraintsCigarChecksEachListElement() {
    // CIGAR is Number=A (one value per ALT allele); a malformed value anywhere in the comma-joined list must be
    // caught, not just the first element
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedInfoConstraints("CIGAR", "8M,0.05,4M1D", VcfVersion.V4_2, VcfWarnings.DEFAULT);
      assertEquals(1, appender.countMessagesContaining("does not match the expected CIGAR grammar"));
      assertTrue(appender.anyMessageContains("0.05"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testConvertPropertyCigarPreservesInvalidValue() {
    // the typed accessor still returns the raw value even when it violates CIGAR's grammar, consistent with the
    // "warn and preserve" policy for non-structural INFO content -- there's nothing to normalize since CIGAR's
    // declared Type=String passes any value through unchanged
    List<String> value = InternalUtils.convertProperty(ReservedInfoProperty.Cigar, "123MYCIGAR", VcfVersion.V4_2, VcfWarnings.DEFAULT);
    assertEquals(List.of("123MYCIGAR"), value);
  }

  @Test
  public void testCigarPatternDoesNotStackOverflowOnLongRepetition() {
    // regression test: CIGAR_PATTERN's outer quantified group must be possessive. Java's regex engine
    // recurses one Java call frame per repetition of a quantified group, so a long sequence of short
    // "<digits><op>" pairs previously blew the JVM call stack directly (StackOverflowError), confirmed
    // reachable end-to-end via VcfPosition.getInfo(ReservedInfoProperty.Cigar) on a fixture-shaped CIGAR
    // value before this fix. 5,000 repetitions (10,000 chars) is a wide margin above the threshold that
    // crashed the unfixed pattern. (The inner "[0-9]++" possessive quantifier is also present, for parity
    // with sf_simpleAltPattern's inner quantifier, but isn't fixing a distinct verified bug here -- see
    // CIGAR_PATTERN's own comment for why this grammar's disjoint digit/operation-letter character classes
    // don't actually blow up combinatorially the way ALT's ambiguous alternation can.)
    String manyPairs = "1M".repeat(5_000);
    assertTrue(InternalUtils.CIGAR_PATTERN.matcher(manyPairs).matches());
  }

  @Test
  public void testConvertPropertyDoesNotApplyInfoConstraintsToNonInfoReservedProperty() {
    // regression test: a ReservedProperty implementation whose ID happens to equal "AC" (the same ID as
    // ReservedInfoProperty.AlleleCount) must NOT have AC's INFO-only non-negative constraint applied, since
    // checkReservedInfoConstraints is scoped to ReservedInfoProperty only
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.convertProperty(new FakeInfoIdReservedProperty(), "-5", VcfVersion.V4_2, VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testToJavaTypeMapsInfoType() {
    assertEquals(Long.class, InternalUtils.toJavaType(InfoType.Integer));
    assertEquals(VcfFloat.class, InternalUtils.toJavaType(InfoType.Float));
    assertEquals(Character.class, InternalUtils.toJavaType(InfoType.Character));
    assertEquals(String.class, InternalUtils.toJavaType(InfoType.String));
    assertEquals(Boolean.class, InternalUtils.toJavaType(InfoType.Flag));
  }

  @Test
  public void testToJavaTypeMapsFormatType() {
    assertEquals(Long.class, InternalUtils.toJavaType(FormatType.Integer));
    assertEquals(VcfFloat.class, InternalUtils.toJavaType(FormatType.Float));
    assertEquals(Character.class, InternalUtils.toJavaType(FormatType.Character));
    assertEquals(String.class, InternalUtils.toJavaType(FormatType.String));
  }

  @Test
  public void testIsReservedMetadataKey() {
    assertTrue(InternalUtils.isReservedMetadataKey("INFO"));
    assertFalse(InternalUtils.isReservedMetadataKey("NotReserved"));
    // regression test: this used to throw NullPointerException from Set.of(...).contains(null) instead of
    // returning false -- null is trivially not one of the fixed reserved keyword strings.
    assertFalse(InternalUtils.isReservedMetadataKey(null));
  }

  @Test
  public void testConvertPropertyDoesNotApplyFormatConstraintsToNonFormatReservedProperty() {
    // regression test: a ReservedProperty implementation whose ID happens to equal "FT" (the same ID as
    // ReservedFormatProperty.Filter) must NOT have FT's FORMAT-only "combines PASS with other codes"
    // constraint applied, since checkReservedFormatConstraints is scoped to ReservedFormatProperty only
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    String result;
    try {
      result = InternalUtils.convertProperty(new FakeFormatIdReservedProperty(), "PASS;other", VcfVersion.V4_2, VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("PASS;other", result);
  }

  @Test
  public void testConvertPropertyStillAppliesFormatConstraintsToRealReservedFormatProperty() {
    // confirms the fix above did not disable the check for the type it's actually meant to protect
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.convertProperty(ReservedFormatProperty.Filter, "PASS;other", VcfVersion.V4_2, VcfWarnings.DEFAULT);
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDropEmptyEntriesRejectsNullEntry() {
    // a null entry can only reach here through a caller-supplied list passed directly to a public factory (e.g.
    // VcfContext.newPosition(chr, pos, Arrays.asList("rs1", null), ...)), never through parsing; it must be
    // rejected with VcfFormatException, not a raw NullPointerException from isEmpty()
    assertThrows(VcfFormatException.class,
        () -> InternalUtils.dropEmptyEntries("ID", new ArrayList<>(Arrays.asList("rs1", null)), null));
  }

  @Test
  public void testFillEmptyEntriesWithDotRejectsNullEntry() {
    assertThrows(VcfFormatException.class,
        () -> InternalUtils.fillEmptyEntriesWithDot("ALT", new ArrayList<>(Arrays.asList("A", null)), null));
  }

  @Test
  public void testUnquoteRejectsNullInsteadOfThrowingNpe() {
    // unlike its sibling methods in this class, unquote()'s null-check is genuinely load-bearing even though
    // the class is documented as internal-only: BaseMetadata.getPropertiesUnquoted() iterates its live
    // properties map and calls unquote() on every value with no guard of its own, and a caller can insert a
    // null value directly through BaseMetadata.getPropertiesRaw()'s own documented bypass -- so this can
    // genuinely receive null in production, not a hypothetical external-caller-only scenario.
    assertThrows(VcfFormatException.class, () -> InternalUtils.unquote(null));
  }

  private static final class FakeFormatIdReservedProperty implements org.pharmgkb.parser.vcf.ReservedProperty {
    @Override
    public String getId() {
      return "FT";
    }

    @Override
    public String getDescription() {
      return "fake";
    }

    @Override
    public Class getType() {
      return String.class;
    }

    @Override
    public boolean isList() {
      return false;
    }

    @Override
    public String getExpectedNumber() {
      return "1";
    }
  }

  private static final class FakeInfoIdReservedProperty implements org.pharmgkb.parser.vcf.ReservedProperty {
    @Override
    public String getId() {
      return "AC";
    }

    @Override
    public String getDescription() {
      return "fake";
    }

    @Override
    public Class getType() {
      return String.class;
    }

    @Override
    public boolean isList() {
      return false;
    }

    @Override
    public String getExpectedNumber() {
      return "1";
    }
  }

  @Test
  public void testDropEmptyEntriesWithNullWarningsThrowsInsteadOfWarning() {
    List<String> list = new java.util.ArrayList<>(List.of("rs1", "", "rs2"));
    assertThrows(VcfFormatException.class, () -> InternalUtils.dropEmptyEntries("ID", list, null));
  }

  @Test
  public void testDropEmptyEntriesWithWarningsWarnsAndDrops() {
    List<String> list = new java.util.ArrayList<>(List.of("rs1", "", "rs2"));
    StringWriter sink = new StringWriter();
    List<String> result = InternalUtils.dropEmptyEntries("ID", list, new VcfWarnings(sink));
    assertEquals(List.of("rs1", "rs2"), result);
    assertTrue(sink.toString().contains("ID contains an empty entry"));
  }

  @Test
  public void testFillEmptyEntriesWithDotWithNullWarningsThrows() {
    List<String> list = new java.util.ArrayList<>(List.of("A", "", "C"));
    assertThrows(VcfFormatException.class, () -> InternalUtils.fillEmptyEntriesWithDot("ALT", list, null));
  }

  @Test
  public void testFillEmptyEntriesWithDotWithWarningsWarnsAndFills() {
    List<String> list = new java.util.ArrayList<>(List.of("A", "", "C"));
    StringWriter sink = new StringWriter();
    InternalUtils.fillEmptyEntriesWithDot("ALT", list, new VcfWarnings(sink));
    assertEquals(List.of("A", ".", "C"), list);
    assertTrue(sink.toString().contains("ALT contains an empty entry"));
  }

  @Test
  public void testExtractPropertiesRoutesDuplicateAttributeWarningToSuppliedInstance() {
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=X", "Type=Integer", "Type=String"}, new VcfWarnings(sink));
    assertEquals("String", props.get("Type"));
    assertTrue(sink.toString().contains("duplicate attribute"));
  }

  @Test
  public void testExtractPropertiesWarnsAndDropsMalformedAttributeInsteadOfThrowing() {
    // regression test: a comma-delimited attribute that doesn't split into exactly one "key=value" pair (here, an
    // empty attribute produced by an interior double comma) previously made extractProperties rethrow
    // unconditionally, aborting the entire metadata declaration -- and, via VcfParser, the entire file parse. It
    // must instead warn and drop just that one attribute, keeping every other attribute that did parse.
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=AF", "", "Number=1", "Type=Float"}, new VcfWarnings(sink));
    assertEquals(Map.of("ID", "AF", "Number", "1", "Type", "Float"), props);
    assertTrue(sink.toString().contains("Could not parse metadata attribute"));
  }

  @Test
  public void testExtractPropertiesWarnsAndDropsAttributeWithUnquotedEmbeddedEqualsSign() {
    // pins the chosen recovery for an attribute whose unquoted value itself contains "=" -- splitProperty finds
    // more than one top-level "=" and can't unambiguously determine the key/value split, so the whole attribute
    // is dropped rather than guessed at, same recovery as the interior-empty-attribute case above. A quoted value
    // containing "=" (e.g. Description="a=b") never reaches this path at all: splitTopLevel's quote-awareness
    // means only the first, unquoted "=" is ever top-level.
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=X", "Number=1", "Type=String", "Description=a=b"}, new VcfWarnings(sink));
    assertEquals(Map.of("ID", "X", "Number", "1", "Type", "String"), props);
    assertFalse(props.containsKey("Description"));
    assertTrue(sink.toString().contains("Could not parse metadata attribute \"Description=a=b\""));
  }

  @Test
  public void testSpecialNumberCardinality() {
    assertEquals(2L, InternalUtils.getExpectedCardinality("A", 2, 2, VcfWarnings.DEFAULT));
    assertEquals(3L, InternalUtils.getExpectedCardinality("R", 2, 2, VcfWarnings.DEFAULT));
    assertEquals(6L, InternalUtils.getExpectedCardinality("G", 2, 2, VcfWarnings.DEFAULT));
    assertEquals(10L, InternalUtils.getExpectedCardinality("G", 2, 3, VcfWarnings.DEFAULT));
  }

  @Test
  public void testGCardinalityOverflowSkipsValidationInsteadOfSentinelValue() {
    assertNull(InternalUtils.getExpectedCardinality("G", 1000, 1000, VcfWarnings.DEFAULT));
  }

  @Test
  public void testGCardinalityWithUnknownPloidySkipsValidation() {
    assertNull(InternalUtils.getExpectedCardinality("G", 2, null, VcfWarnings.DEFAULT));
    assertEquals(2L, InternalUtils.getExpectedCardinality("A", 2, null, VcfWarnings.DEFAULT));
    assertEquals(3L, InternalUtils.getExpectedCardinality("R", 2, null, VcfWarnings.DEFAULT));
    assertEquals(5L, InternalUtils.getExpectedCardinality("5", 2, null, VcfWarnings.DEFAULT));
  }

  @Test
  public void testWarnIfWrongCardinalityWarnsOnMismatchOnly() {
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    InternalUtils.warnIfWrongCardinality("FORMAT PL", "G", 2, 1, 2, warnings);
    assertTrue(sink.toString().contains("FORMAT PL has 2 value(s), but Number=G requires 3"));

    StringWriter matchSink = new StringWriter();
    VcfWarnings matchWarnings = new VcfWarnings(matchSink);
    InternalUtils.warnIfWrongCardinality("FORMAT PL", "A", 2, 2, null, matchWarnings);
    assertEquals("", matchSink.toString());
  }

  @Test
  public void testSplitFormatValueExemptsGle() {
    assertArrayEquals(new String[] { "0:-75.22,1:-223.42" },
        InternalUtils.splitFormatValue("GLE", "0:-75.22,1:-223.42"));
    assertArrayEquals(new String[] { "1", "2", "3" }, InternalUtils.splitFormatValue("PL", "1,2,3"));
    assertArrayEquals(new String[] { "5" }, InternalUtils.splitFormatValue("DP", "5"));
  }

  @Test
  public void testGetPloidyDerivesFromGtAlleleCount() {
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    assertEquals(Integer.valueOf(2), InternalUtils.getPloidy(position, "0|1", "Sample", VcfWarnings.DEFAULT));
    assertEquals(Integer.valueOf(1), InternalUtils.getPloidy(position, "0", "Sample", VcfWarnings.DEFAULT));
    assertNull(InternalUtils.getPloidy(position, null, "Sample", VcfWarnings.DEFAULT));
  }

  @Test
  public void testGetPloidyWarnsForOutOfRangeAlleleIndex() {
    VcfPosition position = STRICT_CONTEXT.newPosition("chr1", 1, null, "A", List.of("T"), null, null, null, null, -1);
    StringWriter sink = new StringWriter();
    VcfWarnings warnings = new VcfWarnings(sink);
    // only 1 ALT allele (index 0 or 1 valid); "2" is out of range
    InternalUtils.getPloidy(position, "0|2", "Sample #0", warnings);
    assertTrue(sink.toString().contains("Sample #0 has invalid GT 0|2"));
  }

  @Test
  public void testContainsWhitespaceDetectsEachAsciiWhitespaceCharacter() {
    assertTrue(InternalUtils.containsWhitespace(" "));
    assertTrue(InternalUtils.containsWhitespace("\t"));
    assertTrue(InternalUtils.containsWhitespace("\n"));
    assertTrue(InternalUtils.containsWhitespace("\u000B"));
    assertTrue(InternalUtils.containsWhitespace("\f"));
    assertTrue(InternalUtils.containsWhitespace("\r"));
    assertTrue(InternalUtils.containsWhitespace("ab cd"));
    assertFalse(InternalUtils.containsWhitespace("abcd"));
    assertFalse(InternalUtils.containsWhitespace(""));
  }

  @Test
  public void testContainsWhitespaceMatchesRegexSemanticsExactly() {
    // containsWhitespace was changed from a Pattern.compile("\\s").matcher(s).find() call to a hand-written
    // scan for performance (measured ~2.2x faster on this library's hot path). This proves the manual scan is
    // byte-for-byte equivalent to \s's own default (non-UNICODE_CHARACTER_CLASS) semantics over every code
    // point Pattern's own Javadoc documents \s as covering, not just the six known whitespace characters above.
    Pattern referenceWhitespacePattern = Pattern.compile("\\s");
    for (int c = 0; c <= 0x2FFF; c++) {
      String s = String.valueOf((char) c);
      boolean expected = referenceWhitespacePattern.matcher(s).find();
      boolean actual = InternalUtils.containsWhitespace(s);
      assertEquals(expected, actual, "mismatch at code point 0x" + Integer.toHexString(c));
    }
  }

  @Test
  public void testIsValidRefBasesMatchesRefBasePatternExactly() {
    // isValidRefBases was added as a hand-written scan replacing REF_BASE_PATTERN.matcher(s).matches() for
    // performance (measured ~5x faster on this library's hot path). This proves exact equivalence: every
    // single-character string over the pattern's full case-sensitivity-relevant range, plus representative
    // multi-character strings (all-valid, empty, and an invalid character at each position).
    assertFalse(InternalUtils.isValidRefBases(""));
    for (int c = 0; c <= 0xFF; c++) {
      String s = String.valueOf((char) c);
      boolean expected = InternalUtils.REF_BASE_PATTERN.matcher(s).matches();
      boolean actual = InternalUtils.isValidRefBases(s);
      assertEquals(expected, actual, "mismatch at code point 0x" + Integer.toHexString(c));
    }
    assertTrue(InternalUtils.isValidRefBases("ACGTNacgtn"));
    assertFalse(InternalUtils.isValidRefBases("ACGTB"));
    assertFalse(InternalUtils.isValidRefBases("BACGT"));
    assertFalse(InternalUtils.isValidRefBases("ACBGT"));
  }

}

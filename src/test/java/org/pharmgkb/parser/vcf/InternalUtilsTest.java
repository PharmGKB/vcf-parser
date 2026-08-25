package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;


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
  public void testAltBasePatternBreakpointAcceptsColonInMateChromosome() {
    // regression test: the mate chromosome class previously excluded ':', so a colon-bearing mate chromosome
    // (legal per VCFv4.3.tex:2140-2141's errata, which this pattern's own field comment now cites) made the
    // breakend unrepresentable, even though the identical colon is already accepted in a CHROM value/##contig
    // ID per InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN's citation of the same errata. All four breakend
    // orientations, plus the disambiguation resolving to the LAST ":digits" suffix (not the first).
    String[] shouldPass = {
        "C[HLA-A*01:01:200[", "G]HLA-A*01:01:100]", "]HLA-A*01:01:100]T", "[HLA-A*01:01:100[T",
    };
    for (String test : shouldPass) {
      assertTrue(InternalUtils.ALT_BASE_PATTERN.matcher(test).matches(), "String " + test + " was not recognized");
    }
    // still rejected: a colon-bearing chromosome with no trailing ":digits" position anywhere is exactly as
    // invalid as any other chromosome-without-position (testAltBasePatternBreakpointRequiresPosition above)
    assertFalse(InternalUtils.ALT_BASE_PATTERN.matcher("C[HLA-A*01:XX[").matches());
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
        VcfWarnings.DEFAULT, "INFO");
    assertEquals("X", props.get("ID"));
    assertEquals("\"a \\\\ b \\\" c\"", props.get("Description"));
  }

  @Test
  public void testStrayBackslashDoesNotSuppressDelimiter() {
    // a backslash not followed by "\\" or "\"" has no defined escape meaning and must not consume/protect the following
    // delimiter; before the fix, the comma here was incorrectly treated as escaped, merging both properties into one
    // blob with two "=" signs, which then threw instead of parsing to two properties
    Map<String, String> props = InternalUtils.extractPropertiesFromLine("Source=A\\,Number=1", VcfWarnings.DEFAULT,
        "INFO");
    assertEquals("A\\", props.get("Source"));
    assertEquals("1", props.get("Number"));
  }

  @Test
  public void testExtractPropertiesDuplicateAttributeWarnsAndKeepsFirst() {
    // a declaration with a repeated attribute (e.g. ##INFO=<ID=X,Number=1,Number=2,...>) warns and keeps the
    // FIRST value -- one policy applied evenly with every other duplicate-key case in this codebase (AGENTS.md's
    // duplicate-declaration-ID rule). No spec version contemplates a repeated attribute name, so this is a
    // library choice, deliberately NOT derived from bcftools (which keeps last for a non-ID attribute).
    Map<String, String> props = InternalUtils.extractPropertiesFromLine("ID=X,Number=1,Number=2", VcfWarnings.DEFAULT,
        "INFO");
    assertEquals("1", props.get("Number"));
  }

  @Test
  public void testExtractPropertiesNoPlaceholderCollision() {
    // a value literally containing the old "~~~~" placeholder must be preserved, and an escaped quote must not be
    // treated as a real quote (so the comma inside the quoted value is not a top-level delimiter)
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=X,Description=\"literal ~~~~ and \\\"q, v\\\"\"", VcfWarnings.DEFAULT, "INFO");
    assertEquals("X", props.get("ID"));
    assertEquals("\"literal ~~~~ and \\\"q, v\\\"\"", props.get("Description"));
  }

  @Test
  public void testExtractPropertiesPreservesInsertionOrder() {
    // real example from an hts-specs SAMPLE fixture: this attribute order was previously scrambled to
    // Description,Genomes,Mixture on write because extractProperties built a plain HashMap (hash-bucket order),
    // not insertion order
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=Sample_ok,Genomes=G1_ID;G2_ID;GK_ID,Mixture=N1;N2;NK,Description=\"S1;S2;SK\"", VcfWarnings.DEFAULT,
        "SAMPLE");
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
          "ID=DEL,Number=1,Type=String,Description=\"Description with \"non-escaped quote\"", VcfWarnings.DEFAULT,
          "INFO");
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
      InternalUtils.extractPropertiesFromLine("ID=X,Description=\"a \\\" b\"", VcfWarnings.DEFAULT, "INFO");
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
      InternalUtils.extractPropertiesFromLine("ID=X,Description=\"\"", VcfWarnings.DEFAULT, "INFO");
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
      InternalUtils.extractPropertiesFromLine("ID=X,Number=1,Type=String", VcfWarnings.DEFAULT, "INFO");
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
  public void testCheckNoLineTerminator() {
    InternalUtils.checkNoLineTerminator("key", "value"); // no line terminator: does not throw
    InternalUtils.checkNoLineTerminator("key", null); // a null value is not checked (nothing to check)
    assertThrows(VcfFormatException.class, () -> InternalUtils.checkNoLineTerminator("bad\nkey", "value"));
    assertThrows(VcfFormatException.class, () -> InternalUtils.checkNoLineTerminator("key", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> InternalUtils.checkNoLineTerminator("key", "bad\rvalue"));
  }

  @Test
  public void testCheckNoLineTerminatorMessageIsTruncatedAndEscaped() {
    // regression test found by the Phase 2 review's third re-verification round: checkNoLineTerminator's own
    // "Property [[[...]]]" message previously embedded key/value verbatim and unbounded -- reachable with an
    // arbitrarily large value once VcfParser.readPhysicalLine() stopped treating a lone CR as a line
    // separator (a CR-only file's "line" can be the entire remaining file content).
    String hugeValue = "x".repeat(50_000) + "\r" + "y".repeat(50_000);
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkNoLineTerminator("key", hugeValue));
    assertTrue(ex.getMessage().contains("contains a line terminator"));
    assertTrue(ex.getMessage().contains("truncated"));
    assertTrue(ex.getMessage().length() < 500, "Exception message was not bounded: " + ex.getMessage().length() +
        " chars");
  }

  @Test
  public void testTruncateAndEscapeForMessage() {
    assertNull(InternalUtils.truncateAndEscapeForMessage(null));
    assertEquals("", InternalUtils.truncateAndEscapeForMessage(""));
    assertEquals("plain", InternalUtils.truncateAndEscapeForMessage("plain"));
    assertEquals("a\\rb\\nc\\td", InternalUtils.truncateAndEscapeForMessage("a\rb\nc\td"));
    // exactly at the boundary: not truncated
    String atLimit = "x".repeat(InternalUtils.MAX_MESSAGE_VALUE_LENGTH);
    assertEquals(atLimit, InternalUtils.truncateAndEscapeForMessage(atLimit));
    // one over the boundary: truncated, with the escaped (not raw) length reported
    String overLimit = "x".repeat(InternalUtils.MAX_MESSAGE_VALUE_LENGTH + 1);
    String result = InternalUtils.truncateAndEscapeForMessage(overLimit);
    assertTrue(result.startsWith("x".repeat(InternalUtils.MAX_MESSAGE_VALUE_LENGTH) + "... (truncated, "));
    assertTrue(result.endsWith(" chars total)"));
    // escaping happens before truncation, so a run of control characters near the boundary is still fully
    // escaped, and the reported length reflects the escaped (longer) string, not the raw one
    String controlHeavy = "\r".repeat(InternalUtils.MAX_MESSAGE_VALUE_LENGTH);
    String controlResult = InternalUtils.truncateAndEscapeForMessage(controlHeavy);
    assertTrue(controlResult.contains("(truncated, " + (InternalUtils.MAX_MESSAGE_VALUE_LENGTH * 2) + " chars total)"),
        controlResult);
  }

  @Test
  public void testTruncateAndEscapeForMessageNeverSplitsAnEscapeSequenceAtTheCutBoundary() {
    // regression test found by the Phase 2 review's fourth re-verification round: the cut point is computed
    // against the ALREADY-ESCAPED string, so a raw CR/LF/tab landing right at the MAX_MESSAGE_VALUE_LENGTH
    // boundary previously got its two-character \r/\n/\t escape sequence torn in half, leaving a meaningless
    // stray trailing backslash. 99 plain characters (indices 0-98) put the inserted "\\r" pair's backslash
    // exactly at index 99 (one before the cut) and its 'r' at index 100 (exactly at the cut) -- the exact
    // boundary condition that splits the pair without this fix.
    String splitBoundary = "x".repeat(99) + "\r" + "y".repeat(50);
    String result = InternalUtils.truncateAndEscapeForMessage(splitBoundary);
    assertFalse(result.contains("x\\"), "must not cut between the backslash and its escape letter: " + result);
    assertTrue(result.startsWith("x".repeat(99) + "... (truncated, "), result);
    // total length reported is still the full escaped string's length (151: 99 x's + 2-char "\r" + 50 y's),
    // unaffected by backing the cut off by one
    assertTrue(result.contains("(truncated, 151 chars total)"), result);
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
  public void testCheckReservedFormatConstraintsFtThrowsUnderStrictForEachViolationShape() {
    // a null checkWarnings means strict: each of FT's three warn-only violations (empty code, whitespace in a
    // code, PASS/'.' combined with another code) must throw VcfFormatException instead. Whitespace and
    // PASS-combined throw with the exact same text as the lenient warning; the empty-code case's strict
    // message omits the lenient path's appended "; dropping it" clause, since strict mode never drops anything
    VcfFormatException empty = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("FT", "q10;;q20", VcfVersion.V4_2, null));
    assertTrue(empty.getMessage().contains("contains an empty filter code"));

    VcfFormatException whitespace = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("FT", "q10 q20", VcfVersion.V4_2, null));
    assertTrue(whitespace.getMessage().contains("contains whitespace in filter code"));

    VcfFormatException combined = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("FT", "PASS;q10", VcfVersion.V4_2, null));
    assertTrue(combined.getMessage().contains("combines PASS or '.' with other filter codes"));

    // valid values still don't throw under strict
    assertEquals("PASS", InternalUtils.checkReservedFormatConstraints("FT", "PASS", VcfVersion.V4_2, null));
  }

  @Test
  public void testCheckReservedFormatConstraintsFtWhitespaceMessageIsTruncatedAndEscaped() {
    // regression test for the fifth re-verification round's finding #1: checkFilterLikeValue's whitespace-in-
    // filter-code throw goes through InternalUtils's own private warnOrThrow, the third of the three shared
    // warn-or-throw implementations that previously embedded a String arg verbatim and unbounded. A huge,
    // control-character-laden FT value reaches this message directly from the sample's own raw content.
    String hugeCode = "q10 \r" + "9".repeat(200);
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("FT", hugeCode, VcfVersion.V4_2, null));
    assertTrue(ex.getMessage().contains("\\r"), "raw CR must be escaped, not embedded verbatim: " + ex.getMessage());
    assertFalse(ex.getMessage().contains("\r"), "message must not contain a raw CR: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    // the two assertFalse/assertTrue checks above only prove escaping happened, not truncation -- escaping
    // runs unconditionally before truncation, so they'd still pass even if truncation were a no-op. Checking
    // for the ESCAPED-but-untruncated form closes that gap -- found by the ninth re-verification round's
    // test-coverage pass.
    String escapedHugeCode = "q10 \\r" + "9".repeat(200);
    assertFalse(ex.getMessage().contains(escapedHugeCode),
        "escaped-but-untruncated value must not appear: " + ex.getMessage());
    assertTrue(ex.getMessage().length() < 350, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testCheckReservedFormatConstraintsFtEmptyCodeMessageIsTruncated() {
    // regression test for the fifth re-verification round's finding #1b: checkFilterLikeValue's empty-code
    // branch is deliberately NOT routed through warnOrThrow (its own comment explains why: the lenient and
    // strict messages genuinely differ), so it was missed by finding #1's shared-helper fix -- an ad-hoc
    // warn-or-throw site sharing one pre-built message string, fixed the same way as VcfPosition's own three
    // ad-hoc sites (wrapping only the string passed to the throw, not the one passed to warn).
    String hugeValue = ";" + "9".repeat(200);
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("FT", hugeValue, VcfVersion.V4_2, null));
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    // the length bound alone is not discriminative (a leaky "truncated" notice appended without actually
    // cutting the string would still pass it, at 321 chars) -- found by the ninth re-verification round's
    // test-coverage pass; asserting the raw untruncated value is absent proves truncation actually happened
    assertFalse(ex.getMessage().contains(hugeValue), "raw untruncated value must not appear: " + ex.getMessage());
    assertTrue(ex.getMessage().length() < 350, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testCheckReservedFormatConstraintsFtEmptyCodeWarnMessageStaysUnsanitized() {
    // regression test: no existing test proved the warn branch of
    // the empty-code site above is deliberately left unsanitized (per its own comment) -- only that the throw
    // branch is truncated/escaped. A huge, raw FT value must appear unescaped and untruncated in the warning.
    String hugeValue = ";" + "9".repeat(200);
    StringWriter sink = new StringWriter();
    assertEquals("9".repeat(200),
        InternalUtils.checkReservedFormatConstraints("FT", hugeValue, VcfVersion.V4_2, new VcfWarnings(sink)));
    assertTrue(sink.toString().contains(hugeValue), "warn path must stay unsanitized per documented scope");
  }

  @Test
  public void testConvertElementCharacterRejectionMessageIsTruncated() {
    // regression test for the fifth re-verification round's finding #1b: convertElement's Character branch
    // throws unconditionally, regardless of context (see VALIDATION_REFERENCE.md's own note on this), so this
    // is reachable via ordinary lenient parsing plus a typed getter call, not just direct strict construction
    String hugeValue = "x".repeat(200);
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> InternalUtils.convertProperty(Character.class, hugeValue, false, VcfWarnings.DEFAULT, null));
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    assertTrue(ex.getMessage().length() < 200, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testConvertElementLongRejectionMessageIsTruncated() {
    // same finding, convertElement's Long branch
    String hugeValue = "x".repeat(200);
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> InternalUtils.convertProperty(Long.class, hugeValue, false, VcfWarnings.DEFAULT, null));
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    assertTrue(ex.getMessage().length() < 200, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testSanitizeArgsForExceptionOnlyTransformsStringArgs() {
    // Unit test of the shared sanitizer itself: a String arg is truncated/escaped exactly as
    // truncateAndEscapeForMessage would, while a non-String arg (an Integer, an enum) passes through
    // unchanged -- e.g. a line number or a VcfVersion value is already bounded and never contains a raw
    // control character, so wrapping it would be pointless (and toString()-ing an enum/Integer here would
    // be wrong: MessageFormatter.arrayFormat already knows how to render the original object).
    String withCr = "a\rb";
    Object[] sanitized = InternalUtils.sanitizeArgsForException("label", 42, VcfVersion.V4_3, withCr);
    assertEquals("label", sanitized[0]);
    assertEquals(42, sanitized[1]);
    assertEquals(VcfVersion.V4_3, sanitized[2]);
    assertEquals(InternalUtils.truncateAndEscapeForMessage(withCr), sanitized[3]);
    assertEquals("a\\rb", sanitized[3]);
    // empty varargs: no exception, empty result
    assertEquals(0, InternalUtils.sanitizeArgsForException().length);
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
  public void testCheckReservedFormatConstraintsPsThrowsUnderStrict() {
    // a null checkWarnings means strict: the same out-of-range PS value that only warns above must throw
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("PS", "-5", VcfVersion.V4_2, null));
    assertTrue(e.getMessage().contains("is not a non-negative 32-bit integer"));
    // a valid value still doesn't throw under strict
    assertEquals("0", InternalUtils.checkReservedFormatConstraints("PS", "0", VcfVersion.V4_2, null));
  }

  @Test
  public void testConvertPropertyPhaseSetThrowsUnderStrictContext() {
    // end-to-end: convertProperty(ReservedProperty, ...) must actually thread its computed checkWarnings into
    // checkReservedFormatConstraints, not just the internal helper tested directly above
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> InternalUtils.convertProperty(
        ReservedFormatProperty.PhaseSet, "-5", VcfContext.strict("VCFv4.2")));
    assertTrue(e.getMessage().contains("is not a non-negative 32-bit integer"));
  }

  @Test
  public void testConvertPropertyPhaseSetPreservesOutOfRangeValue() {
    // the typed accessor still returns the parsed Long even when it violates PS's non-negative-32-bit constraint,
    // consistent with the "warn and preserve" policy for non-structural sample content
    Long value = InternalUtils.convertProperty(ReservedFormatProperty.PhaseSet, "-5",
        VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
    assertEquals(-5L, value);
  }

  @Test
  void testCheckInteger32BitRangeWarnsOnOverflowUnderLenientContext() {
    StringWriter sink = new StringWriter();
    InternalUtils.checkInteger32BitRange("INT", 2147483648L, new VcfWarnings(sink));
    assertTrue(sink.toString().contains("outside the 32-bit signed range"));
  }

  @Test
  void testCheckInteger32BitRangeThrowsOnOverflowUnderStrictContext() {
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkInteger32BitRange("INT", 2147483648L, null));
    assertTrue(e.getMessage().contains("outside the 32-bit signed range"));
  }

  @Test
  void testCheckInteger32BitRangeWarnsOnUnderflowUnderLenientContext() {
    StringWriter sink = new StringWriter();
    InternalUtils.checkInteger32BitRange("INT", -2147483649L, new VcfWarnings(sink));
    assertTrue(sink.toString().contains("outside the 32-bit signed range"));
  }

  @Test
  void testCheckInteger32BitRangeWarnsOnReservedSentinelUnderLenientContext() {
    // VCFv4.3.tex:103/:1710 -- the eight values -2^31 through -2^31+7 are BCF-reserved sentinels, not a
    // magnitude overflow (they ARE representable as signed 32-bit integers)
    for (long sentinel = -2147483648L; sentinel <= -2147483641L; sentinel++) {
      StringWriter sink = new StringWriter();
      InternalUtils.checkInteger32BitRange("INT", sentinel, new VcfWarnings(sink));
      assertTrue(sink.toString().contains("BCF-reserved sentinel"),
          "Expected a reserved-sentinel warning for " + sentinel + ", got: " + sink);
    }
  }

  @Test
  void testCheckInteger32BitRangeThrowsOnReservedSentinelUnderStrictContext() {
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkInteger32BitRange("INT", -2147483648L, null));
    assertTrue(e.getMessage().contains("BCF-reserved sentinel"));
  }

  @Test
  void testCheckInteger32BitRangeDoesNothingForAnOrdinaryInRangeValue() {
    StringWriter sink = new StringWriter();
    InternalUtils.checkInteger32BitRange("INT", 5L, new VcfWarnings(sink));
    assertEquals("", sink.toString());
    assertDoesNotThrow(() -> InternalUtils.checkInteger32BitRange("INT", -5L, null));
    assertDoesNotThrow(() -> InternalUtils.checkInteger32BitRange("INT", Integer.MAX_VALUE, null));
    assertDoesNotThrow(() -> InternalUtils.checkInteger32BitRange("INT", Integer.MIN_VALUE + 8L, null));
  }

  @Test
  public void testConvertPropertyFilterDropsEmptyCodes() {
    // unlike PS, the typed FT accessor returns a normalized value: an empty semicolon-separated code is dropped,
    // matching the "warn and drop" convention used for FILTER's own empty entries
    String value = InternalUtils.convertProperty(ReservedFormatProperty.Filter, "q10;;q20",
        VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
    assertEquals("q10;q20", value);
  }

  @Test
  public void testConvertPropertyFilterAllEmptyCodesReturnsNullNotEmptyString() {
    // end-to-end regression test: convertProperty(ReservedProperty, ...) must return null, matching this
    // library's established null-means-absent contract, not "" -- see
    // testCheckReservedFormatConstraintsFtAllEmptyCodesNormalizesToMissingValueSentinel for the underlying fix
    String value = InternalUtils.convertProperty(ReservedFormatProperty.Filter, ";;",
        VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
    assertNull(value);
  }

  @Test
  public void testCheckReservedInfoConstraintsNonNegativeKeysWarnButDoNotThrow() {
    // AC/AN/DP/END/MQ0/NS's declared Long Type accepts a negative value, but each is semantically a count/depth/
    // position that can't be negative -- checked separately, warning rather than throwing (non-structural INFO
    // content). CN/CNADJ/HOMLEN/DPADJ added by the Phase 2 review's third re-verification round --
    // same reasoning, same vcf_validator 0.10.2-sourced authority (confirmed live, since no hts-specs fixture
    // covers these four); CICN deliberately excluded, see NON_NEGATIVE_INTEGER_INFO_KEYS's own comment.
    // AD/ADF/ADR are tested separately below (testCheckReservedInfoConstraintsAdKeysAreVersionGated): they have
    // no VCFv4.2 declaration at all, so this loop's fixed V4_2 version would incorrectly find them exempt.
    for (String key : new String[] { "AC", "AN", "DP", "END", "MQ0", "NS", "CN", "CNADJ", "HOMLEN", "DPADJ" }) {
      InternalUtils.checkReservedInfoConstraints(key, "0", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
      InternalUtils.checkReservedInfoConstraints(key, "5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
      InternalUtils.checkReservedInfoConstraints(key, "-1", VcfVersion.V4_2, VcfWarnings.DEFAULT); // negative: warns, does not throw
    }
    InternalUtils.checkReservedInfoConstraints("AC", null, VcfVersion.V4_2, VcfWarnings.DEFAULT); // no value set: nothing to check
    InternalUtils.checkReservedInfoConstraints("AC", ".", VcfVersion.V4_2, VcfWarnings.DEFAULT); // missing value: nothing to check
  }

  @Test
  public void testCheckReservedInfoConstraintsNonNegativeKeysThrowUnderStrict() {
    // regression test found by the Phase 2 review's third re-verification round: the four newly-added keys
    // must also throw (not just warn) under a strict (null) checkWarnings, matching AC/AN/DP/END/MQ0/NS's
    // existing treatment (see testCheckReservedInfoConstraintsNonNegativeKeyThrowsUnderStrictContext below for
    // the original six's own dedicated test)
    for (String key : new String[] { "CN", "CNADJ", "HOMLEN", "DPADJ" }) {
      VcfFormatException ex = assertThrows(VcfFormatException.class,
          () -> InternalUtils.checkReservedInfoConstraints(key, "-5", VcfVersion.V4_2, null));
      assertTrue(ex.getMessage().contains("is a negative number"), "key " + key + ": " + ex.getMessage());
      InternalUtils.checkReservedInfoConstraints(key, "5", VcfVersion.V4_2, null); // valid: does not throw
    }
  }

  @Test
  public void testCheckReservedInfoConstraintsAdKeysAreVersionGated() {
    // regression test found by the Phase 2 review's fourth re-verification round: AD/ADF/ADR have no VCFv4.2
    // declaration at all (ReservedInfoProperty.isAddedAtV4_3), so checkReservedInfoConstraints -- reached even
    // for a DECLARED key via VcfWriter.validateInfoValue's "declared" branch, which carries no version-gated
    // fallback of its own -- must not apply the non-negative-integer rule to a pre-4.3 file's own custom key
    // literally named AD/ADF/ADR, the same shape as the FORMAT-side CNP write-path gap this round also fixed.
    for (String key : new String[] { "AD", "ADF", "ADR" }) {
      InternalUtils.checkReservedInfoConstraints(key, "-5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // not yet reserved: no warning
      InternalUtils.checkReservedInfoConstraints(key, "-5", VcfVersion.V4_2, null); // not yet reserved: does not throw either
      VcfFormatException ex = assertThrows(VcfFormatException.class,
          () -> InternalUtils.checkReservedInfoConstraints(key, "-5", VcfVersion.V4_3, null));
      assertTrue(ex.getMessage().contains("is a negative number"), "key " + key + ": " + ex.getMessage());
      InternalUtils.checkReservedInfoConstraints(key, "5", VcfVersion.V4_3, null); // valid: does not throw
    }
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
  public void testCheckReservedInfoConstraintsAlleleFrequencyThrowsUnderStrict() {
    // a null checkWarnings means strict: the same out-of-range AF value that only warns above must throw
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedInfoConstraints("AF", "-0.01", VcfVersion.V4_2, null));
    assertTrue(e.getMessage().contains("does not lie in the interval [0, 1]"));
    // a valid value, and the NaN/Inf exemptions, still don't throw under strict
    InternalUtils.checkReservedInfoConstraints("AF", "0.5", VcfVersion.V4_2, null);
    InternalUtils.checkReservedInfoConstraints("AF", "NaN", VcfVersion.V4_2, null);
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
          VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
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
    List<Long> value = InternalUtils.convertProperty(ReservedInfoProperty.AlleleCount, "-5",
        VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
    assertEquals(List.of(-5L), value);
  }

  @Test
  public void testCheckReservedInfoConstraintsAlleleCountThrowsUnderStrict() {
    // a null checkWarnings means strict: the same negative AC value that only warns under lenient must throw
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedInfoConstraints("AC", "-5", VcfVersion.V4_2, null));
    assertTrue(e.getMessage().contains("is a negative number"));
    // a valid value still doesn't throw under strict
    InternalUtils.checkReservedInfoConstraints("AC", "5", VcfVersion.V4_2, null);
  }

  @Test
  public void testConvertPropertyAlleleCountThrowsUnderStrictContext() {
    // end-to-end: convertProperty(ReservedProperty, ...) must actually thread its computed checkWarnings into
    // checkReservedInfoConstraints, not just the internal helper tested directly above
    VcfFormatException e = assertThrows(VcfFormatException.class, () -> InternalUtils.convertProperty(
        ReservedInfoProperty.AlleleCount, "-5", VcfContext.strict("VCFv4.2")));
    assertTrue(e.getMessage().contains("is a negative number"));
  }

  @Test
  public void testCheckReservedFormatConstraintsGenotypePosteriorRangeCheckIsVersionGated() {
    // GP was redefined from VCFv4.2's phred-scaled encoding to a 0-1 probability by VCFv4.3 (VCFv4.3.tex line
    // 491, "Genotype posterior probabilities in the range 0 to 1"; see ReservedFormatProperty.GenotypePosterior's
    // own comment) -- the [0, 1] range check applies only from 4.3 onward. Under <=4.2 any Float is a legitimate
    // phred value, so the same out-of-range value must not warn there.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("GP", "0.5", VcfVersion.V4_3, VcfWarnings.DEFAULT); // valid: no warn
      InternalUtils.checkReservedFormatConstraints("GP", "5.0", VcfVersion.V4_2, VcfWarnings.DEFAULT); // <=4.2: no check at all
      assertEquals(0, appender.countMessagesContaining("does not lie in the interval"));
      InternalUtils.checkReservedFormatConstraints("GP", "5.0", VcfVersion.V4_3, VcfWarnings.DEFAULT); // 4.3+: warns
      assertEquals(1, appender.countMessagesContaining("does not lie in the interval"));
      assertTrue(appender.anyMessageContains("FORMAT GP"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsGenotypePosteriorChecksEachListElementUnder43() {
    // per-element, not a scalar parse of the whole value: GP is Number=G and list-shaped, and this method
    // receives the entire comma-delimited value before any splitting (splitFormatValue runs one line after
    // this check in both callers, see checkReservedFormatConstraints's own comment). A scalar BigDecimal parse
    // of "0.2,5.0,0.8" would throw NumberFormatException and be treated as "not a number at all" (skipped, no
    // warning) -- silently accepting the out-of-range middle element.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("GP", "0.2,5.0,0.8", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      assertEquals(1, appender.countMessagesContaining("does not lie in the interval"));
      assertTrue(appender.anyMessageContains("5.0"));
      // the same list is silent under <=4.2, where the range check does not apply at all
      InternalUtils.checkReservedFormatConstraints("GP", "0.2,5.0,0.8", VcfVersion.V4_2, VcfWarnings.DEFAULT);
      assertEquals(1, appender.countMessagesContaining("does not lie in the interval"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsGenotypePosteriorSkipsSpecialFloatValuesUnder43() {
    // matches AlleleFrequency's own exemption, inherited via the shared checkUnitInterval helper: NaN/Inf/-Inf
    // have no meaningful position within [0, 1], so neither warns
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("GP", "NaN", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      InternalUtils.checkReservedFormatConstraints("GP", "INF", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      InternalUtils.checkReservedFormatConstraints("GP", "-INF", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      assertEquals(0, appender.countMessagesContaining("does not lie in the interval"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsGenotypePosteriorThrowsUnderStrictWhenVersionGateApplies() {
    // a null checkWarnings means strict: the same out-of-range GP value that only warns above must throw, but
    // only once the 4.3+ version gate is in force -- under <=4.2 the range check doesn't apply at all, so it
    // must not throw there either
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("GP", "5.0", VcfVersion.V4_3, null));
    assertTrue(e.getMessage().contains("does not lie in the interval [0, 1]"));
    InternalUtils.checkReservedFormatConstraints("GP", "5.0", VcfVersion.V4_2, null); // <=4.2: no check, no throw
  }

  @Test
  public void testCheckReservedFormatConstraintsCopyNumberPosteriorProbabilitiesRangeCheckIsUnconditional() {
    // regression test found by the Phase 2 review's re-verification round: VCFv4.3.tex line 689 ("CNP is 0 to
    // 1-scaled copy number posterior probabilities") and the 4.3 changelog at line 2156 ("Both CNP and GP use
    // 0 to 1 encoding") pair CNP with GP explicitly, but only GP was range-checked. Unlike GP, CNP has no
    // VCFv4.2 declaration at all, so this check is unconditional -- no version gate.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("CNP", "0.5", VcfVersion.V4_3, VcfWarnings.DEFAULT); // valid: no warn
      assertEquals(0, appender.countMessagesContaining("does not lie in the interval"));
      InternalUtils.checkReservedFormatConstraints("CNP", "5.0", VcfVersion.V4_3, VcfWarnings.DEFAULT); // warns
      assertEquals(1, appender.countMessagesContaining("does not lie in the interval"));
      assertTrue(appender.anyMessageContains("FORMAT CNP"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsCopyNumberPosteriorProbabilitiesChecksEachListElement() {
    // per-element, not a scalar parse of the whole value -- mirrors GP's identical shape, since CNP is also
    // Number=G and list-shaped
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("CNP", "0.2,5.0,0.8", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      assertEquals(1, appender.countMessagesContaining("does not lie in the interval"));
      assertTrue(appender.anyMessageContains("5.0"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsCopyNumberLikelihoodIsNotRangeChecked() {
    // regression guard: CNL is a list of log10 likelihoods, not a 0-1 probability (VCFv4.3.tex line 688,
    // "CNL specifies a list of log10 likelihoods... starting from zero") -- the new CNP check must not leak
    // to its sibling CNL, which shares no such constraint
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("CNL", "-5.0", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      assertEquals(0, appender.countMessagesContaining("does not lie in the interval"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsCopyNumberPosteriorProbabilitiesThrowsUnderStrict() {
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedFormatConstraints("CNP", "5.0", VcfVersion.V4_3, null));
    assertTrue(e.getMessage().contains("does not lie in the interval [0, 1]"));
  }

  @Test
  public void testCheckReservedFormatConstraintsNonNegativeKeysWarnButDoNotThrow() {
    // the FORMAT-side mirror of checkReservedInfoConstraints's own identical set (see that method's test of
    // the same name) -- CN/DP/EC/GQ/HAP/HQ/MQ/NQ/PL/PQ/AHAP are reserved at every version this library
    // targets, so a fixed VCFv4.2 version is a valid probe for all eleven. AD/ADF/ADR are tested separately
    // below (testCheckReservedFormatConstraintsAdKeysAreVersionGated): they have no VCFv4.2 declaration at
    // all, so this loop's fixed V4_2 version would incorrectly find them exempt. PS is deliberately excluded
    // -- it already has its own, stricter checkNonNegative32BitInteger call (see
    // NON_NEGATIVE_INTEGER_FORMAT_KEYS's own comment).
    for (String key : new String[] { "CN", "DP", "EC", "GQ", "HAP", "HQ", "MQ", "NQ", "PL", "PQ", "AHAP" }) {
      InternalUtils.checkReservedFormatConstraints(key, "0", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
      InternalUtils.checkReservedFormatConstraints(key, "5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // valid: does not throw
      InternalUtils.checkReservedFormatConstraints(key, "-1", VcfVersion.V4_2, VcfWarnings.DEFAULT); // negative: warns, does not throw
    }
    InternalUtils.checkReservedFormatConstraints("DP", null, VcfVersion.V4_2, VcfWarnings.DEFAULT); // no value set: nothing to check
    InternalUtils.checkReservedFormatConstraints("DP", ".", VcfVersion.V4_2, VcfWarnings.DEFAULT); // missing value: nothing to check
  }

  @Test
  public void testCheckReservedFormatConstraintsNonNegativeKeysThrowUnderStrict() {
    for (String key : new String[] { "CN", "DP", "EC", "GQ", "HAP", "HQ", "MQ", "NQ", "PL", "PQ", "AHAP" }) {
      VcfFormatException ex = assertThrows(VcfFormatException.class,
          () -> InternalUtils.checkReservedFormatConstraints(key, "-5", VcfVersion.V4_2, null));
      assertTrue(ex.getMessage().contains("is a negative number"), "key " + key + ": " + ex.getMessage());
      InternalUtils.checkReservedFormatConstraints(key, "5", VcfVersion.V4_2, null); // valid: does not throw
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsAdKeysAreVersionGated() {
    // regression test found by the Phase 2 review's fourth re-verification round: AD/ADF/ADR have no VCFv4.2
    // declaration at all (ReservedFormatProperty.isAddedAtV4_3), so checkReservedFormatConstraints -- reached
    // even for a DECLARED key via VcfWriter.validateFormatValue's "declared" branch, which carries no
    // version-gated fallback of its own -- must not apply the non-negative-integer rule to a pre-4.3 file's
    // own custom key literally named AD/ADF/ADR, the same shape as this same method's own CNP branch.
    for (String key : new String[] { "AD", "ADF", "ADR" }) {
      InternalUtils.checkReservedFormatConstraints(key, "-5", VcfVersion.V4_2, VcfWarnings.DEFAULT); // not yet reserved: no warning
      InternalUtils.checkReservedFormatConstraints(key, "-5", VcfVersion.V4_2, null); // not yet reserved: does not throw either
      VcfFormatException ex = assertThrows(VcfFormatException.class,
          () -> InternalUtils.checkReservedFormatConstraints(key, "-5", VcfVersion.V4_3, null));
      assertTrue(ex.getMessage().contains("is a negative number"), "key " + key + ": " + ex.getMessage());
      InternalUtils.checkReservedFormatConstraints(key, "5", VcfVersion.V4_3, null); // valid: does not throw
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsNonNegativeKeyChecksEachListElement() {
    // AD (Number=R) may carry a comma-joined list; a negative value anywhere in the list must be caught, not
    // just the first element -- mirrors checkReservedInfoConstraints's identical per-element test
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("AD", "10,-5", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      assertTrue(appender.anyMessageContains("FORMAT AD value \"-5\" is a negative number"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testCheckReservedFormatConstraintsPhaseSetIsNotDoubleCoveredByNonNegativeIntegerFormatKeys() {
    // PS is deliberately excluded from NON_NEGATIVE_INTEGER_FORMAT_KEYS -- its own dedicated
    // checkNonNegative32BitInteger branch already covers every value this generic check would too. Regression
    // guard: PS must not additionally match the generic "is a negative number" message.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      InternalUtils.checkReservedFormatConstraints("PS", "-5", VcfVersion.V4_3, VcfWarnings.DEFAULT);
      assertEquals(0, appender.countMessagesContaining("is a negative number"));
      assertTrue(appender.anyMessageContains("is not a non-negative 32-bit integer"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testConvertPropertyGenotypePosteriorPreservesOutOfRangeValueUnder43() {
    // the typed accessor still returns the parsed VcfFloat even when it violates GP's 4.3+ [0, 1] constraint,
    // consistent with the "warn and preserve" policy for non-structural sample content; also confirms the
    // renamed ReservedFormatProperty.GenotypePosterior constant plumbs through convertProperty end to end
    List<VcfFloat> value = InternalUtils.convertProperty(ReservedFormatProperty.GenotypePosterior, "5.0",
        VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT));
    assertEquals(1, value.size());
    assertEquals(0, value.get(0).getValue().compareTo(new java.math.BigDecimal("5.0")));
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
  public void testCheckReservedInfoConstraintsCigarThrowsUnderStrict() {
    // a null checkWarnings means strict: the same grammar-invalid CIGAR value that only warns above must throw
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.checkReservedInfoConstraints("CIGAR", "123MYCIGAR", VcfVersion.V4_2, null));
    assertTrue(e.getMessage().contains("does not match the expected CIGAR grammar"));
    // a valid value, and the "no value"/missing-value cases, still don't throw under strict
    InternalUtils.checkReservedInfoConstraints("CIGAR", "8M2I4M1D3M", VcfVersion.V4_2, null);
    InternalUtils.checkReservedInfoConstraints("CIGAR", null, VcfVersion.V4_2, null);
    InternalUtils.checkReservedInfoConstraints("CIGAR", ".", VcfVersion.V4_2, null);
  }

  @Test
  public void testConvertPropertyCigarPreservesInvalidValue() {
    // the typed accessor still returns the raw value even when it violates CIGAR's grammar, consistent with the
    // "warn and preserve" policy for non-structural INFO content -- there's nothing to normalize since CIGAR's
    // declared Type=String passes any value through unchanged
    List<String> value = InternalUtils.convertProperty(ReservedInfoProperty.Cigar, "123MYCIGAR",
        VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
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
      InternalUtils.convertProperty(new FakeInfoIdReservedProperty(), "-5",
          VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
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
    assertTrue(InternalUtils.isReservedMetadataKey("INFO", VcfVersion.V4_3));
    assertFalse(InternalUtils.isReservedMetadataKey("NotReserved", VcfVersion.V4_3));
    // regression test: this used to throw NullPointerException from Set.of(...).contains(null) instead of
    // returning false -- null is trivially not one of the fixed reserved keyword strings.
    assertFalse(InternalUtils.isReservedMetadataKey(null, VcfVersion.V4_3));
  }

  @Test
  public void testIsReservedMetadataKeyExcludesMetaBeforeV4_3() {
    // ##META does not exist before VCFv4.3 (VCFv4.1.tex/VCFv4.2.tex never mention it) -- see
    // VcfParser.parseMetadata's own "META" case for the parse-time counterpart of this rule.
    assertFalse(InternalUtils.isReservedMetadataKey("META", VcfVersion.V4_1));
    assertFalse(InternalUtils.isReservedMetadataKey("META", VcfVersion.V4_2));
    assertTrue(InternalUtils.isReservedMetadataKey("META", VcfVersion.V4_3));
    assertTrue(InternalUtils.isReservedMetadataKey("META", VcfVersion.V4_4));
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
      result = InternalUtils.convertProperty(new FakeFormatIdReservedProperty(), "PASS;other",
          VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
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
      InternalUtils.convertProperty(ReservedFormatProperty.Filter, "PASS;other",
          VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT));
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
    assertEquals("Integer", props.get("Type"));
    assertTrue(sink.toString().contains("duplicate attribute"));
  }

  @Test
  void anArrayElementsOwnCommaIsContentNotASeparator() {
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"Description=a,b", "ID=X"}, VcfWarnings.DEFAULT);
    assertEquals(2, props.size());
    assertEquals("a,b", props.get("Description"));
    assertEquals("X", props.get("ID"));
  }

  @Test
  void theArrayFormSharesTheTokenizersValueRules() {
    // same classification, same junk-after-close recovery -- only the boundaries differ
    assertEquals("[a, b]", InternalUtils.extractProperties(
        new String[] {"x=[a, b]"}, VcfWarnings.DEFAULT).get("x"));
    assertEquals("\"a\"", InternalUtils.extractProperties(
        new String[] {"x=\"a\" junk"}, VcfWarnings.DEFAULT).get("x"));
  }

  @Test
  void nullsStillCrashRatherThanBeingSilentlyDropped() {
    // InternalUtils is exempt from the VcfFormatException null policy (AGENTS.md); a raw NPE is the
    // documented outcome and matches today's behaviour. Pinned so a future null guard cannot quietly
    // downgrade a caller bug into a warned, dropped attribute.
    assertThrows(NullPointerException.class,
        () -> InternalUtils.extractProperties((String[]) null, VcfWarnings.DEFAULT));
    assertThrows(NullPointerException.class,
        () -> InternalUtils.extractProperties(new String[] {null}, VcfWarnings.DEFAULT));
  }

  @Test
  void theArrayFormWarnsAndKeepsFirstOnADuplicateName() {
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=A", "ID=B"}, new VcfWarnings(sink));
    assertEquals("A", props.get("ID"));
    assertThat(sink.toString(), containsString("duplicate attribute"));
  }

  @Test
  void keepFirstAppliesToThreeOrMoreDuplicates() {
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=X,Number=1,Number=2,Number=3", new VcfWarnings(sink), "INFO");
    assertEquals("1", props.get("Number"));
    assertThat(sink.toString(), containsString("keeping the first value"));
  }

  @Test
  void aConformingQuotedDuplicateCannotDisplaceOrBeDisplacedByPosition() {
    // Pins that keep-first is decided on ORDER ONLY, never on the value's form. An earlier design tested for a
    // structural character in the value; because Attribute.rawValue() retains the quote delimiters, that test
    // returned true for every quoted value and would have discarded this conforming one.
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=X,Description=\"new, better\",Description=old", new VcfWarnings(sink), "INFO");
    assertEquals("\"new, better\"", props.get("Description"));
    assertThat(sink.toString(), containsString("duplicate attribute \"Description\""));
  }

  @Test
  void aMalformedDuplicateIdCannotDisplaceAValidOne() {
    // The case that motivated this change: a truncation tail containing '=' becomes a fabricated named
    // attribute, and keep-last let it take over the declaration's identity.
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=c1,x=A<1,ID=fake>B,length=999", VcfWarnings.DEFAULT, "contig");
    assertEquals("c1", props.get("ID"));
  }

  @Test
  public void testExtractPropertiesWarnsAndDropsMalformedAttributeInsteadOfThrowing() {
    // regression test: an attribute with no '=' at all (here, an empty attribute produced by an interior
    // double comma in the original declaration, which the array-splitting caller turns into a bare "" element)
    // previously made extractProperties rethrow unconditionally, aborting the entire metadata declaration --
    // and, via VcfParser, the entire file parse. It must instead warn and drop just that one attribute,
    // keeping every other attribute that did parse. Wording updated for the attribute-list tokenizer
    // (InternalUtils.parseAttribute, shared with tokenizeAttributeList via parseOneAttribute): a missing '='
    // is now reported directly rather than via a caught VcfFormatException wrapped as "Could not parse
    // metadata attribute".
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=AF", "", "Number=1", "Type=Float"}, new VcfWarnings(sink));
    assertEquals(Map.of("ID", "AF", "Number", "1", "Type", "Float"), props);
    assertTrue(sink.toString().contains("has no '='; dropping it"));
  }

  @Test
  public void testExtractPropertiesTreatsEmbeddedEqualsSignInValueAsOrdinaryContent() {
    // regression-turned-behavior-change test: the retired splitProperty/splitTopLevel pair found every
    // top-level "=" and dropped the whole attribute when there was more than one. The attribute-list tokenizer
    // (InternalUtils.parseAttribute, sharing its per-attribute grammar with tokenizeAttributeList via
    // parseOneAttribute) instead stops the name scan at the FIRST '=' only, so a second, unquoted "=" is
    // ordinary value content -- the same rule AttributeListTokenizerTest#anEqualsSignInAValueIsOrdinary already
    // pins for the attribute-list form, now shared by the array form.
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=X", "Number=1", "Type=String", "Description=a=b"}, new VcfWarnings(sink));
    assertEquals(Map.of("ID", "X", "Number", "1", "Type", "String", "Description", "a=b"), props);
    assertFalse(sink.toString().contains("dropping it"));
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
    InternalUtils.warnIfWrongCardinality("FORMAT PL", "G", 2, 1, 2, warnings, warnings);
    assertTrue(sink.toString().contains("FORMAT PL has 2 value(s), but Number=G requires 3"));

    StringWriter matchSink = new StringWriter();
    VcfWarnings matchWarnings = new VcfWarnings(matchSink);
    InternalUtils.warnIfWrongCardinality("FORMAT PL", "A", 2, 2, null, matchWarnings, matchWarnings);
    assertEquals("", matchSink.toString());
  }

  @Test
  public void testWarnIfWrongCardinalityThrowsUnderStrict() {
    // checkWarnings == null (strict) throws instead of warning; matches checkInteger32BitRange's and
    // convertProperty's own null-means-throw convention
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> InternalUtils.warnIfWrongCardinality("FORMAT PL", "G", 2, 1, 2, VcfWarnings.DEFAULT, null));
    assertTrue(ex.getMessage().contains("FORMAT PL has 2 value(s), but Number=G requires 3"));

    // a match still doesn't throw under strict -- only a genuine mismatch does
    InternalUtils.warnIfWrongCardinality("FORMAT PL", "A", 2, 2, null, VcfWarnings.DEFAULT, null);
  }

  @Test
  public void testSplitFormatValueExemptsGle() {
    assertArrayEquals(new String[] { "0:-75.22,1:-223.42" },
        InternalUtils.splitFormatValue("GLE", "0:-75.22,1:-223.42", VcfVersion.V4_2));
    assertArrayEquals(new String[] { "1", "2", "3" }, InternalUtils.splitFormatValue("PL", "1,2,3", VcfVersion.V4_2));
    assertArrayEquals(new String[] { "5" }, InternalUtils.splitFormatValue("DP", "5", VcfVersion.V4_2));
  }

  @Test
  public void testSplitFormatValueGleExemptionIsVersionGated() {
    // VCFv4.3 removed GLE (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT tag"), so from 4.3
    // a key named GLE is an ordinary custom key whose value splits on commas like any other
    assertArrayEquals(new String[] { "0:-75.22,1:-223.42" },
        InternalUtils.splitFormatValue("GLE", "0:-75.22,1:-223.42", VcfVersion.V4_1));
    assertArrayEquals(new String[] { "0:-75.22", "1:-223.42" },
        InternalUtils.splitFormatValue("GLE", "0:-75.22,1:-223.42", VcfVersion.V4_3));
  }

  @Test
  public void testIsExemptFromGenotypeOrderingCardinalityCheckGatesGleAndCnpInOppositeDirections() {
    // GLE's exemption exists only because its value isn't comma-delimited at all, which is only true while GLE
    // is the reserved 4.1/4.2 key -- so it moves true->false as the version crosses 4.3. CNP's exemption has an
    // unrelated rationale ("one value per potential copy number"), but CNP has no VCFv4.2 declaration at all
    // (ReservedFormatProperty.isAddedAtV4_3), so a pre-4.3 key literally named CNP is an ordinary custom key and
    // gets no exemption -- CNP's exemption moves false->true as the version crosses 4.3, the mirror image of
    // GLE's. Once CNP genuinely is the reserved key (>=4.3), the exemption is unconditional regardless of the
    // declared Number, unlike CNL below. Regression test for the fix that closed VcfWriter's write-path half of
    // this gap (a pre-4.3 file's own custom CNP key previously still got the reserved [0, 1] range check and
    // this cardinality exemption).
    assertTrue(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("GLE", VcfVersion.V4_2, "G"));
    assertFalse(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("GLE", VcfVersion.V4_3, "G"));
    assertFalse(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNP", VcfVersion.V4_2, "G"));
    assertTrue(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNP", VcfVersion.V4_3, "G"));
    assertTrue(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNP", VcfVersion.V4_3, "4"));
    assertFalse(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("PL", VcfVersion.V4_2, "G"));
  }

  @Test
  public void testIsExemptFromGenotypeOrderingCardinalityCheckGatesCnlOnResolvedNumber() {
    // CNL's exemption is doubly gated: the >=4.3 version gate (below that its canonical Number is "." and the
    // generic check already skips it without this exemption) AND, from this regression fix on, whether the
    // Number actually in force -- a ##FORMAT declaration's own stated Number, or the reserved canonical fallback
    // -- genuinely is "G". A declaration overriding CNL to a concrete Number (e.g. Number=4) states a real,
    // checkable cardinality, so the exemption must not suppress that check.
    assertTrue(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNL", VcfVersion.V4_3, "G"));
    assertFalse(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNL", VcfVersion.V4_3, "4"));
    assertFalse(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNL", VcfVersion.V4_3, null));
    // below 4.3, CNL's canonical Number is "." regardless of what's passed in here -- not exempt via this
    // predicate either way, since the version gate alone already excludes it
    assertFalse(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNL", VcfVersion.V4_2, "G"));
  }

  @Test
  public void testConvertPropertyGleIsVersionGated() {
    // behavior 8: the generic, supertype-typed converter is itself public, so a caller can hand it the GLE
    // constant directly (this is the route VcfSample.getProperty(ReservedFormatProperty) delegates to). Under
    // <=4.2 it still returns GLE's historical opaque-String conversion; under 4.3+ it warns and returns null,
    // since the reserved key no longer exists. Tested here directly, not through getProperty, so a guard placed
    // in VcfSample instead of here would fail this test.
    String gle = "0:-75.22,1:-223.42,0/0:-323.03";
    StringWriter sink42 = new StringWriter();
    Object under42 = InternalUtils.convertProperty(
        ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy, gle,
        VcfContext.lenient("VCFv4.2", new VcfWarnings(sink42)));
    assertEquals(gle, under42);
    assertEquals("", sink42.toString());

    StringWriter sink43 = new StringWriter();
    Object under43 = InternalUtils.convertProperty(
        ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy, gle,
        VcfContext.lenient("VCFv4.3", new VcfWarnings(sink43)));
    assertNull(under43);
    assertTrue(sink43.toString().contains("FORMAT GLE is not a reserved key under VCF 4.3"));
  }

  @Test
  void testConvertPropertyReservedInfoWarnsOnIntegerOverflowUnderLenientContext() {
    StringWriter sink = new StringWriter();
    VcfContext lenient = VcfContext.lenient("VCFv4.2", new VcfWarnings(sink));
    Long value = InternalUtils.convertProperty(ReservedInfoProperty.Depth, "2147483648", lenient);
    assertEquals(2147483648L, value);
    assertTrue(sink.toString().contains("outside the 32-bit signed range"));
  }

  @Test
  void testConvertPropertyReservedInfoThrowsOnIntegerOverflowUnderStrictContext() {
    VcfContext strict = VcfContext.strict("VCFv4.2");
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> InternalUtils.convertProperty(ReservedInfoProperty.Depth, "2147483648", strict));
    assertTrue(e.getMessage().contains("outside the 32-bit signed range"));
  }

  @Test
  void testConvertPropertyReservedFormatPsWarnsTwiceForAReservedSentinelValue() {
    // accepted overlap -- see checkInteger32BitRange's own Javadoc
    StringWriter sink = new StringWriter();
    VcfContext lenient = VcfContext.lenient("VCFv4.2", new VcfWarnings(sink));
    Long value = InternalUtils.convertProperty(ReservedFormatProperty.PhaseSet, "-2147483648", lenient);
    assertEquals(-2147483648L, value);
    assertTrue(sink.toString().contains("not a non-negative 32-bit integer"));
    assertTrue(sink.toString().contains("BCF-reserved sentinel"));
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

  @Test
  void anInvalidAttributeNameWarnsAtParseTimeAndIsStillStored() {
    // A truncation tail containing '=' is fabricated into a named attribute whose name is invalid. Before this,
    // nothing named it at all -- the only warning was about the preceding value. Warn, do not drop: the parser
    // preserves recoverable input.
    StringWriter sink = new StringWriter();
    Map<String, String> props = InternalUtils.extractPropertiesFromLine(
        "ID=c1,x=A<1,2>B=q,length=999", new VcfWarnings(sink), "contig");
    assertEquals("q", props.get("2>B"));
    assertThat(sink.toString(), containsString("Metadata attribute name \"2>B\" is not valid"));
  }

  @Test
  void aValidButUnusualAttributeNameDoesNotWarn() {
    // Pins the pattern's actual boundary rather than assuming it: '_' may lead, '.' and digits may follow.
    StringWriter sink = new StringWriter();
    InternalUtils.extractPropertiesFromLine("ID=X,_a.b2=v", new VcfWarnings(sink), "INFO");
    assertThat(sink.toString(), not(containsString("is not valid")));
  }

  @Test
  void aRepeatedInvalidNameWarnsOnceNotOncePerOccurrence() {
    // Pins the POST-FOLD placement. Per-attribute placement would warn twice here; the declaration has one bad
    // name, and Task 2's duplicate warning already reports the repetition.
    StringWriter sink = new StringWriter();
    InternalUtils.extractPropertiesFromLine("ID=c1,2>B=x,2>B=y,length=999", new VcfWarnings(sink), "contig");
    assertEquals(1, countOccurrences(sink.toString(), "Metadata attribute name \"2>B\" is not valid"));
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = haystack.indexOf(needle);
    while (idx >= 0) {
      count++;
      idx = haystack.indexOf(needle, idx + needle.length());
    }
    return count;
  }

}

package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.google.errorprone.annotations.RestrictedApi;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;


/**
 * Internal helper methods shared across this library's own {@code org.pharmgkb.parser.vcf} package.
 * Despite several members being {@code public} -- nothing in this class is part of this
 * library's supported public API. It may change or be removed without notice in any release. See the
 * {@link RestrictedApi} annotation on each such method for the same signal in a form linters/IDEs can act
 * on; also excluded from generated Javadoc entirely via the {@code @hidden} tag below, since published
 * Javadoc is the one surface a real external consumer would actually browse.
 *
 * @author Douglas Myers-Turnbull
 * @hidden
 */
public class InternalUtils {

  // The "+" below deliberately allows repeating/concatenating the nucleotides-or-symbolic-ID alternative (e.g.
  // "C<ctg1>"), not just one instance of either. This is confirmed necessary for VCFv4.2's own "Large Insertions"
  // shorthand ("13 321682 INS0 T C<ctg1> ... SVTYPE=INS", a base run directly followed by a symbolic ID with no
  // breakend bracket between them) -- see InternalUtilsTest#testAltBasePatternAcceptsLargeInsertionShorthand, which
  // exists specifically because a prior review round mistakenly "fixed" this as over-permissive and rejected it.
  //
  // The "+" is broader than that one confirmed case requires, though: it also accepts forms with no known
  // precedent either way, such as "AC<ctg1>GT" (bases before AND after a symbolic ID) or "<ctg1><ctg2>" (multiple
  // concatenated symbolic IDs). Checked and found no evidence to justify tightening this further:
  //   - VCFv4.2/4.3/4.4/4.5 spec text: silent: no example or grammar statement covers these forms either way.
  //   - hts-specs' own conformance test suite (test/vcf/{4.1,4.2,4.3}/{passed,failed}/*.vcf): zero test files
  //     reference "ctg" at all, so this exact question has no official pass/fail fixture in either direction.
  //   - EBI vcf_validator 0.10.2 (github.com/ebivariation/vcf-validator): rejects ALL of "C<ctg1>", "AC<ctg1>GT",
  //     and "<ctg1><ctg2>" with the identical generic error, so it can't distinguish the confirmed-valid case from
  //     the unconfirmed ones.
  //   - bcftools 1.23.1: accepts all of the above -- but also accepts outright invalid ALT text like "XYZ123!!!"
  //     with no complaint, so its acceptance carries no evidentiary weight here; it does not validate ALT syntax.
  // Given no source can confirm or deny the broader forms, and a previous attempt to narrow this regex incorrectly
  // broke the one form that IS confirmed valid, this is intentionally left as-is rather than risked again.
  private static final String sf_simpleAltPattern =
      "(?:"                    + // wrap the whole expression
        "(?:"                  + // allow nucleotides, symbolic IDs, or both
          "(?:[AaCcGgTtNn]++)" + // nucleotides -- possessive quantifier: without it, this is nested inside the
                                 // outer "+" below, the classic (x+)+ catastrophic-backtracking shape (verified:
                                 // O(N^2) blowup on a long nucleotide run followed by one invalid trailing
                                 // character). Safe to make possessive because a run of [AaCcGgTtNn] never needs
                                 // to backtrack into the sibling alternative below (symbolic IDs always start
                                 // with '<', which is never a nucleotide character)
          "|(?:<[^\\s,<>]+>)"  + // symbolic IDs (no whitespace, commas, or angle brackets inside)
          ")++"                + // see the note above for why this repeats. Also possessive, for a distinct
                                 // reason than the inner nucleotide-run quantifier above: Java's regex engine
                                 // recurses one Java call frame per repetition for a quantified group containing
                                 // alternation, so a long sequence of short alternatives (e.g. many concatenated
                                 // symbolic IDs) can throw an uncaught StackOverflowError instead of failing to
                                 // match cleanly -- a stack-depth problem, not the time-complexity backtracking
                                 // problem the inner quantifier's possessiveness addresses. Safe to make
                                 // possessive: each repetition here is unambiguously anchored (nucleotide chars
                                 // vs. a mandatory leading '<'), so no legitimate match ever needs to backtrack
                                 // across repetitions of this group.
        "|\\*"                 + // indicates that the position doesn't exist due to an upstream deletion
      ")";

  // the mate breakend location "chr:pos" (VCF 4.2: "a string of the form `chr:pos'"): chr and pos are both always
  // required together -- there is no valid breakend form that gives a chromosome without a position (a truly
  // mate-less breakend uses the unrelated "G." / ".A" single-breakend shorthand, matched elsewhere in
  // ALT_BASE_PATTERN, not this pattern). chr may be any non-symbolic CHROM-like value, not just digits: the spec's
  // own examples happen to use only numeric chromosome names (plus one symbolic contig ID), which is why this was
  // wrongly restricted to \d+ before and never caught by tests built from those same examples.
  private static final String sf_mateLocation =
      "(?:"                                     + // wrap the whole expression
        "(?:[^\\s,\\[\\]:]+|(?:<[^\\s,<>]+>))"   + // chromosome: CHROM-like (no whitespace, comma, brackets, or colon), or a symbolic ID
        ":\\d+"                                  + // position (required)
      ")";

  // The breakend replacement string "t" is mandatory -- never zero-length -- but VCFv4.2's own "Telomeres" section
  // (e.g. "1 0 bnd_X N .[13:123457[") uses a literal "." as t itself, for a virtual telomeric breakend with no
  // real reference sequence. So t is bases/symbolic-ID/"*" (sf_simpleAltPattern) OR literally ".", never absent.
  // hts-specs' own conformance suite confirms the "absent" case is invalid: test/vcf/4.2/failed/failed_body_alt_005.vcf,
  // CauseOfFailure="Breakend without bases at either side", ALT="[1:100[" (t truly missing on both sides, not
  // even a "."). Every passing breakend example (test/vcf/4.1/passed/passed_body_alt.vcf) has content on exactly
  // one side (e.g. "]1:100]AGT", "[1:100[AGT", "AGT]1:100]", "AGT[1:100["), never zero.
  private static final String sf_breakendReplacement = "(?:" + sf_simpleAltPattern + "|\\.)";

  // No outer optional dot wraps these breakpoint types: t (sf_breakendReplacement) already accepts a literal "."
  // itself for the telomere case, so an outer wrapper would let a stray extra dot sit beside t with no basis in
  // any spec example (e.g. "..[1:1[" or "A]1:1]." would wrongly match). Confirmed live that removing it does not
  // affect any of this class's existing breakend test cases, all of which are covered by t accepting "." directly.
  private static final Pattern sf_breakpointAltPattern = Pattern.compile(
      "(?:"                                                         + // start breakpoint types
        "(?:" + sf_breakendReplacement + "\\[" + sf_mateLocation + "\\[)"  + // breakpoint type 1: t[p[
        "|(?:" + sf_breakendReplacement + "]" + sf_mateLocation + "])" + // breakpoint type 2: t]p]
        "|(?:\\]" + sf_mateLocation + "]" + sf_breakendReplacement + ")" + // breakpoint type 3: ]p]t
        "|(?:\\[" + sf_mateLocation + "\\[" + sf_breakendReplacement + ")" + // breakpoint type 4: [p[t
      ")"                                                             // end breakpoint types
  );

  public static final Pattern ALT_BASE_PATTERN = Pattern.compile(
      "\\.|" +                                 // means no variant
      "(?:\\.?" + sf_simpleAltPattern + ")"  + // ex: .A
      "|(?:" + sf_simpleAltPattern + "\\.?)" + // ex: A.
      "|" + sf_breakpointAltPattern            // ex: C[2[
  );

  // VCFv4.2.tex line 182: "REF - reference base(s): Each base must be one of A,C,G,T,N (case insensitive)."
  // Kept as a Pattern (rather than folded entirely into isValidRefBases below) because VcfPosition.checkRef's
  // exception message interpolates this field directly to describe the expected grammar to the caller.
  public static final Pattern REF_BASE_PATTERN = Pattern.compile("[AaCcGgTtNn]+");

  /**
   * A hand-written scan equivalent to {@code REF_BASE_PATTERN.matcher(s).matches()} -- measured ~5x faster
   * (12.5 ns/iter regex vs. 2.5 ns/iter manual, equivalence-verified against 2M random strings with 0
   * mismatches) for {@link org.pharmgkb.parser.vcf.VcfPosition#checkRef}, called once per record on this
   * library's hot path. {@code REF_BASE_PATTERN}'s {@code +} quantifier under {@code matches()} means an empty
   * string never matches -- {@code isEmpty()} is checked explicitly here for the same reason.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean isValidRefBases(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != 'A' && c != 'a' && c != 'C' && c != 'c' && c != 'G' && c != 'g' && c != 'T' && c != 't'
          && c != 'N' && c != 'n') {
        return false;
      }
    }
    return true;
  }
  // VCFv4.3.tex (this project's target spec) gives this exact regex verbatim, unchanged in VCFv4.4/4.5:
  // "colon-separated FORMAT keys matching the regular expression ^[A-Za-z_][0-9A-Za-z_.]*$, duplicate keys are
  // not allowed" (VCFv4.3.tex:429; repeated in the VCFv4.2->4.3 changelog at VCFv4.3.tex:2150, which states the
  // identical rule for "INFO and FORMAT tag names" together). VCFv4.2 does not table a formal FORMAT-key
  // grammar -- it only describes the FORMAT field informally as a "colon-separated alphanumeric String"
  // (VCFv4.2.tex, Genotype fields subsection), predating the target's regex rather than conflicting with it.
  // Used both for a data-line FORMAT key (VcfPosition.java) and for a
  // ##FORMAT declaration's own ID (FormatMetadata.java) -- confirmed via direct bcftools 1.23.1 testing that
  // bcftools enforces this same stricter grammar for ##FORMAT declaration IDs specifically (an asterisk anywhere
  // but the first character triggers "Invalid tag name"), unlike ##FILTER/##SAMPLE/##ALT declaration IDs, which
  // bcftools accepts the identical character in silently (see VCF_SPEC_AMBIGUITIES.md's "##FORMAT/##INFO
  // declaration ID grammar is stricter than ##FILTER/##SAMPLE/##ALT" entry). Not reused for INFO
  // declaration IDs, which VCFv4.3.tex:368 gives a different regex with a "1000G" legacy exception -- see
  // INFO_PATTERN below.
  public static final Pattern FORMAT_PATTERN = Pattern.compile("^[A-Za-z_][0-9A-Za-z_.]*$");
  // VCFv4.3.tex:368 (unchanged in 4.4/4.5): "INFO keys must match the regular expression
  // ^([A-Za-z_][0-9A-Za-z_.]*|1000G)$, please note that ``1000G'' is allowed as a special legacy value." The
  // VCFv4.2->4.3 changelog (VCFv4.3.tex:2150) states the rule for "INFO and FORMAT tag names" together with no
  // 1000G exception mentioned, but the more specific body text -- confirmed live via bcftools 1.23.1, which
  // silently accepts ##INFO=<ID=1000G,...> -- governs over the changelog's more general summary. This is simply
  // the VCFv4.3.tex target's own stated rule, applied uniformly including to VCFv4.1/4.2 input: those versions
  // are silent on INFO tag-name grammar, predating the rule rather than conflicting with it. Confirmed
  // via direct bcftools 1.23.1 testing that ##INFO declaration IDs are held to this stricter grammar (an
  // asterisk anywhere but the first character triggers "Invalid tag name"), unlike ##FILTER/##SAMPLE/##ALT
  // declaration IDs -- see VCF_SPEC_AMBIGUITIES.md's "##FORMAT/##INFO declaration ID grammar is stricter than
  // ##FILTER/##SAMPLE/##ALT" entry.
  public static final Pattern INFO_PATTERN = Pattern.compile("^([A-Za-z_][0-9A-Za-z_.]*|1000G)$");
  public static final Pattern RSID_PATTERN = Pattern.compile("rs\\d+");
  public static final Pattern NUMBER_PATTERN = Pattern.compile("(?:\\d+|[ARG.])");

  // VCF 4.x only; also rejects malformed versions like VCFv4..2
  public static final Pattern FILE_FORMAT_PATTERN = Pattern.compile("VCFv4\\.\\d+");

  // VCFv4.3.tex (the target) states this fuller character-set grammar directly, in its own "Changes to VCFv4.3"
  // errata (the subsection at VCFv4.3.tex lines 2129-2144; the load-bearing sentences are 2136 -- "The set of
  // characters allowed in VCF contig names is now the same as that allowed in SAM reference sequence names" --
  // and 2137-2138, which list exactly the characters below; carried verbatim into VCFv4.4.tex line 2599 and
  // VCFv4.5.tex line 2781, and restated in the body text at VCFv4.3.tex lines 258-259, VCFv4.4.tex line 272 and
  // VCFv4.5.tex line 321). VCFv4.2.tex line 179 only states "no whitespace permitted" for CHROM and doesn't
  // mention ##contig IDs' character set at all -- it predates this grammar rather than conflicting with it.
  // Applied at every version this library reads, not gated on the declared ##fileformat, since this is the
  // target's own rule for "VCF contig names", not a version-dependent one. Used by
  // both VcfPosition.checkChromosome (CHROM values) and
  // ContigMetadata (##contig IDs) -- both are "VCF contig names" per that same errata text, which is why this
  // constant isn't named after either specific call site. Colon is deliberately NOT in this set -- the same
  // errata explicitly confirms it's valid at VCFv4.3.tex lines 2140-2141 ("this was unnecessary"); see
  // HTS_SPECS_BASELINE_STATUS.md for the fixtures this knowingly diverges from.
  public static final Pattern INVALID_CONTIG_NAME_CHAR_PATTERN = Pattern.compile("[\\\\,\"`'(){}\\[\\]<>]");

  // No VCF spec version (checked VCFv4.2.tex through VCFv4.5.tex directly -- unlike CHROM/contig names, which
  // 4.3+ explicitly ties to "the same rules as the SAM format's reference sequence names") ever states that
  // INFO CIGAR's grammar follows the SAM specification's own CIGAR string format; every version just describes
  // it in prose/table as "Cigar string describing how to align an alternate allele to the reference allele",
  // with no BNF and no cross-reference. The only source connecting this key to SAM's grammar is vcf_validator
  // 0.10.2's own message text ("value is not an alphanumeric string compliant with the SAM specification"),
  // confirmed directly against test/vcf/4.2/failed/failed_body_info_010.vcf (CIGAR=123MYCIGAR, illegal trailing
  // characters) and _011.vcf (CIGAR=0.05, a decimal number, not (length, operation) pairs at all); bcftools
  // 1.23.1 is silent on both. This regex is SAM's own well-established CIGAR grammar (a lone "*" for
  // unavailable, or one-or-more (positive-integer length, single-letter operation) pairs, operation one of
  // M/I/D/N/S/H/P/=/X) -- not itself vendored in this repo, so cited here as the common bioinformatics
  // convention vcf_validator's message implies, not as a VCF-spec-mandated rule.
  //
  // The outer group is possessive ("++"), for the stack-depth reason already documented on
  // sf_simpleAltPattern's own outer quantifier above: Java's regex engine recurses one call frame per
  // repetition of a quantified group, so a long sequence of short "<digits><op>" pairs previously threw an
  // uncaught StackOverflowError instead of failing the match cleanly -- verified directly: "1M" repeated
  // 2000+ times plus one trailing bare digit (~4KB) crashed with the non-possessive version, reachable
  // end-to-end via VcfPosition.getInfo(ReservedInfoProperty.Cigar). Safe to make possessive: each repetition
  // here is unambiguously anchored (a digit run is never ambiguous with the mandatory single operation
  // letter that follows it), so no legitimate match ever needs to backtrack across repetitions of this group.
  //
  // The inner "[0-9]++" is also possessive, but unlike sf_simpleAltPattern's inner nucleotide quantifier,
  // this one isn't fixing a verified catastrophic-backtracking blowup -- confirmed empirically that a long
  // all-digit run followed by an invalid trailing character (e.g. "1"-repeated-20000 + "!") already fails in
  // ~O(N) time even without it, since digits and the fixed operation-letter set (MIDNSHPX=) are disjoint
  // character classes: backtracking [0-9]+ one position at a time always immediately re-fails the
  // just-freed digit against the operation-letter check, so there's no combinatorial split to blow up the
  // way ALT's ambiguous nucleotide-vs-symbolic-ID alternation can. Kept possessive anyway for the same
  // "no legitimate match ever needs it" safety argument and parity with the sibling pattern -- free
  // hardening, not a fix for a distinct observed bug.
  public static final Pattern CIGAR_PATTERN = Pattern.compile("\\*|(?:[0-9]++[MIDNSHPX=])++");

  private static final String RESTRICTED_API_EXPLANATION =
      "Internal helper; public only because Java requires it for cross-package use within this library. Not "
          + "part of this library's supported public API and may change or be removed without notice.";

  private InternalUtils() {}

  /**
   * A hand-written scan replacing a {@code Pattern.compile("\\s").matcher(s).find()} call this method used
   * previously -- measured ~2.2x faster (16.5 ns/iter regex vs. 7.4 ns/iter manual, equivalence-verified
   * against 0x0-0x2FFF with 0 mismatches) on a method called from this library's hot path (e.g.
   * {@code VcfPosition.checkChromosome}/{@code checkFilters}, once per value per record). Exactly matches
   * {@code \\s}'s own default (non-{@code UNICODE_CHARACTER_CLASS}) definition per {@link Pattern}'s own
   * Javadoc: {@code [ \t\n\x0B\f\r]} -- ASCII-only, does not detect non-ASCII Unicode whitespace.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean containsWhitespace(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r') {
        return true;
      }
    }
    return false;
  }

  /**
   * {@link #containsWhitespace} minus U+0020 SPACE: matches {@code [\t\n\x0B\f\r]} only.
   * <p>
   * Exists solely for INFO <em>values</em>, the one field where a literal space is legal.
   * {@code VCFv4.3.tex} line 366 states "Space characters are allowed in values" -- repeated verbatim at
   * {@code VCFv4.4.tex} line 382 and {@code VCFv4.5.tex} line 431, so no later version widens it beyond
   * U+0020. {@code VCFv4.2.tex} line 186 forbids all whitespace there ("String, no whitespace, semicolons,
   * or equals-signs permitted"), and this library applies the later, more permissive reading at every
   * version it reads; see {@code VcfPosition.checkInfoEntries}, this method's only caller, for why that
   * relaxation is unconditional rather than version-gated.
   * <p>
   * <b>Do not substitute {@link #containsWhitespace} here, and do not widen this method to the rest of
   * {@code \\s}.</b> The spec says <em>space characters</em>, not whitespace: a raw tab in an INFO value adds
   * a column when {@code VcfWriter.addInfoOrDot} writes that value straight into the record, and a raw
   * LF/CR ends the record early. Nothing downstream catches either -- the adjacent check in
   * {@code checkInfoEntries} covers only {@code ';'}/{@code ','}, and {@link #checkNoLineTerminator} is
   * only ever called on the metadata path -- so this predicate is the sole guard against emitting a
   * structurally unparseable record.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean containsWhitespaceOtherThanSpace(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r') {
        return true;
      }
    }
    return false;
  }

  static Map<String, String> extractPropertiesFromLine(String value, VcfWarnings warnings) {
    if (hasUnbalancedQuotes(value)) {
      // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_001.vcf (##CauseOfFailure=Description contains
      // non-escaped quotes). Checked on the raw, unsplit string rather than per-attribute after splitTopLevel:
      // an unescaped quote can make splitTopLevel itself mis-split at the wrong comma (its open/close toggle
      // flips on every unescaped '"', so a stray one can make a comma meant to stay inside a value look
      // top-level), so checking post-split could miss or misattribute the very problem being detected here.
      warnings.warn("Metadata declaration attribute list \"{}\" contains an unescaped quote, making attribute " +
          "boundaries ambiguous; parsing it best-effort", value);
    }
    // split on top-level commas (those not inside a double-quoted value); splitTopLevel preserves escaped characters
    return extractProperties(splitTopLevel(value, ',').toArray(new String[0]), warnings);
  }

  /**
   * @return Whether {@code value} has an odd number of unescaped {@code "} characters. An odd count means
   * {@link #splitTopLevel} would finish with its internal {@code inQuotes} toggle left {@code true}, guaranteeing
   * some quote is unmatched and making attribute boundaries ambiguous. An even count does not guarantee all quotes
   * are correctly paired (e.g., two separate stray quotes in different attributes can cancel out to an even total);
   * this is a best-effort heuristic, not a per-attribute validation. Respects the same {@code \\}/{@code \"} escape
   * convention as {@link #splitTopLevel} and {@link #unquote}, so a properly-escaped inner quote is not miscounted.
   */
  private static boolean hasUnbalancedQuotes(String value) {
    int unescapedQuoteCount = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length() && (value.charAt(i + 1) == '\\' || value.charAt(i + 1) == '"')) {
        i++; // skip the escaped character, matching splitTopLevel's own escape handling
      } else if (c == '"') {
        unescapedQuoteCount++;
      }
    }
    return unescapedQuoteCount % 2 != 0;
  }

  // public only because ~57 test call sites (InfoMetadataTest,
  // FormatMetadataTest, IdDescriptionMetadataTest, VcfMetadataTest, ContigMetadataTest) call this directly
  // to build a Map<String,String> from "key=value" arrays -- its only PRODUCTION caller is
  // extractPropertiesFromLine, in this same class
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static Map<String, String> extractProperties(String[] props, VcfWarnings warnings) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String prop : props) {
      Pair<String, String> pair;
      try {
        pair = splitProperty(prop);
      } catch (VcfFormatException e) {
        // Warn and drop just this one malformed attribute rather than aborting the whole metadata declaration
        // (previously: rethrew, which propagated uncaught out of VcfParser.parseMetadata() and aborted the
        // entire file over one cosmetic header defect). AGENTS.md's Parser Validation Policy lists metadata
        // declarations among the fields this parser is lenient about; confirmed live against bcftools 1.23.1
        // (htslib 1.23.1) that it never aborts the whole file for this -- it drops the one malformed
        // declaration and continues (exit 0). This drops only the offending attribute, not the whole
        // declaration: every downstream metadata constructor's lenient (Map, VcfWarnings) path already warns
        // rather than throws for a missing required attribute (e.g. InfoMetadata.init()'s warnIfMissing(TYPE,
        // ...)), so a declaration left short one attribute here is already a supported, tested state.
        //
        // hts-specs' own conformance suite disagrees with this leniency for one narrow sub-case -- an
        // unescaped comma inside an unquoted ID splitting it into two bogus top-level attributes --
        // test/vcf/{4.1,4.2}/failed/failed_meta_contig_002.vcf (##CauseOfFailure=contig ID contains a comma,
        // ##contig=<ID=1,A,length=123456>) and test/vcf/{4.1,4.2}/failed/failed_meta_alt_007.vcf
        // (##CauseOfFailure=The ALT ID contains a comma, ##ALT=<ID=DEL:A,B,Description="...">) are both marked
        // failed/ for this exact shape. This decision still sides with bcftools: this project's own leniency
        // philosophy (AGENTS.md's "'Strict' in this project means structural correctness... not eager rejection
        // of every semantic VCF violation") treats an unquoted comma inside an ID as a semantic grammar defect,
        // not the structural kind this library hard-rejects for -- see VCF_SPEC_AMBIGUITIES.md's "Malformed
        // attribute inside a metadata declaration's <...> list" entry for the full reasoning. Only splitProperty's
        // own VcfFormatException is caught here (never a broader RuntimeException): a bug elsewhere in
        // splitTopLevel must still surface as a real crash, not get silently reported as "could not parse this
        // attribute".
        warnings.warn("Could not parse metadata attribute \"{}\" ({}); dropping it", prop, e.getMessage());
        continue;
      }
      if (map.containsKey(pair.getKey())) {
        // Keeping the LAST value here is deliberate and differs from every other duplicate-key case in this
        // codebase (which keep the first) -- this handles a duplicate ATTRIBUTE within one metadata declaration's
        // own <ID=...,Number=...,...> structure, a different scenario from a duplicate declaration ID across
        // separate lines or a duplicate INFO/FORMAT key within a data record. hts-specs' test/vcf/{4.1,4.2,4.3}
        // conformance suite has no fixture for a repeated attribute within one declaration. Confirmed live against
        // bcftools 1.23.1: declaring Type twice (e.g. Type=Integer,...,Type=String) with a value only valid under
        // one of the two, then forcing conversion to binary BCF (which enforces the declared type, unlike
        // plain-text VCF), shows bcftools always resolves to the LAST-declared value regardless of which type came
        // first -- e.g. Type=String declared last preserves a non-numeric value as-is; Type=Integer declared last
        // silently coerces the same non-numeric value to the missing sentinel. vcf_validator 0.10.2 rejects this
        // case outright, but per this project's tie-breaker hierarchy its stricter structural-field behavior is
        // non-authoritative when it disagrees with bcftools.
        warnings.warn("Metadata declaration contains duplicate attribute \"{}\"; keeping the last value", pair.getKey());
      }
      map.put(pair.getKey(), pair.getValue());
    }
    return map;
  }

  /**
   * Splits a property into a key-value pair.
   * @param prop In the form "key=value"
   */
  static Pair<String, String> splitProperty(String prop) {
    List<String> parts = splitTopLevel(prop, '=');
    if (parts.size() != 2) {
      throw new VcfFormatException("There were " + (parts.size() - 1) + " equals signs for: " + prop);
    }
    return Pair.of(parts.get(0), parts.get(1));
  }

  /**
   * Splits {@code s} on top-level occurrences of {@code delim} — those not inside a double-quoted substring. Only
   * {@code \\} and {@code \"} are recognized escape sequences (per the VCF spec; matches {@link #unquote}): {@code \"}
   * does not toggle quoting and {@code \\} is a literal backslash, and both are preserved verbatim in the output. A
   * backslash before any other character (or at the end of the string) has no special meaning and does not consume or
   * protect the following character, so a delimiter or quote immediately after it is still recognized.
   */
  private static List<String> splitTopLevel(String s, char delim) {
    List<String> parts = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == '\\' || s.charAt(i + 1) == '"')) {
        cur.append(c).append(s.charAt(i + 1));
        i++;
      } else if (c == '"') {
        inQuotes = !inQuotes;
        cur.append(c);
      } else if (c == delim && !inQuotes) {
        parts.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    parts.add(cur.toString());
    return parts;
  }

  /**
   * Adds double quotation marks around a string, escaping any embedded backslash or double-quote character (as the VCF
   * spec requires: {@code \} becomes {@code \\}, {@code "} becomes {@code \"}). Reverse with {@link #unquote}.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static String quote(String string) {
    StringBuilder sb = new StringBuilder(string.length() + 2).append('"');
    for (int i = 0; i < string.length(); i++) {
      char c = string.charAt(i);
      if (c == '\\' || c == '"') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.append('"').toString();
  }

  /**
   * Removes double quotation marks around a string if they are present, decoding any escaped backslash ({@code \\}) or
   * double-quote ({@code \"}) inside. Reverse of {@link #quote}.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static String unquote(String string) {
    // unlike this class's other null-guards (removed as dead code once the class became internal-only), this
    // one stays: BaseMetadata.getPropertiesUnquoted() iterates its live properties map and calls unquote() on
    // every value with no guard of its own. Since the metadata layer was frozen, no public API route can put a
    // null value into that map (getPropertiesRaw() is unmodifiable and validateProperties() rejects a null at
    // construction), but the package-private BaseMetadata.trustedMutableProperties() bypass still can -- and
    // this library's own tests use exactly that bypass to synthesize the malformed declarations VcfWriter's
    // defensive checks are written against, so this guard is still exercised, not hypothetical.
    if (string == null) {
      throw new VcfFormatException("Cannot unquote a null string");
    }
    if (string.length() < 2 || !string.startsWith("\"") || !string.endsWith("\"")) {
      return string;
    }
    String inner = string.substring(1, string.length() - 1);
    StringBuilder sb = new StringBuilder(inner.length());
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (c == '\\' && i + 1 < inner.length() && (inner.charAt(i + 1) == '\\' || inner.charAt(i + 1) == '"')) {
        sb.append(inner.charAt(++i));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Rejects a key or value containing a line terminator: such a property would corrupt the single-line structure of a
   * written {@code ##} metadata line or data line (see {@link VcfWriter}), so this should be checked at the point of
   * construction or mutation rather than deferred to write time.
   *
   * @param value if there is no value to check (e.g. a single-string property with no separate key), pass the same
   *              string as both {@code key} and {@code value}
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static void checkNoLineTerminator(String key, @Nullable String value) {
    if (key.contains("\n") || key.contains("\r") || (value != null && (value.contains("\n") || value.contains("\r")))) {
      throw new VcfFormatException("Property [[[" + key + "=" + value + "]]] contains a line terminator");
    }
  }

  private static final Set<String> RESERVED_METADATA_KEYS =
      Set.of("fileformat", "ALT", "FILTER", "INFO", "FORMAT", "contig", "SAMPLE", "PEDIGREE");

  /**
   * @return true if {@code name} is one of the structured metadata declaration keywords (e.g. "INFO",
   * "FORMAT") that {@link VcfParser#parseMetadata} always routes to its own structured-parsing path -- a raw
   * property using one of these names could never be re-read as what it was written, since reparsing would
   * route it through the wrong path entirely
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean isReservedMetadataKey(@Nullable String name) {
    return name != null && RESERVED_METADATA_KEYS.contains(name);
  }

  /**
   * Warns about and drops any empty string in {@code list}, for a position-independent delimited field (e.g. ID,
   * FILTER) where an empty entry has nothing salvageable. See {@code EMPTY_FIELD_HANDLING.md} for the rationale.
   *
   * @param warnings where to route the warning, or {@code null} for strict mode -- an empty entry throws
   * {@link VcfFormatException} instead of being warned about and dropped
   * @return {@code list} unchanged if it contained no empty entries, or a new list with them removed
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static List<String> dropEmptyEntries(String fieldName, List<String> list, @Nullable VcfWarnings warnings) {
    List<String> result = list;
    for (int i = 0; i < result.size(); i++) {
      if (result.get(i) == null) {
        throw new VcfFormatException(fieldName + " contains a null entry");
      }
      if (result.get(i).isEmpty()) {
        if (result == list) {
          result = new ArrayList<>(list);
        }
        if (warnings == null) {
          throw new VcfFormatException(fieldName + " contains an empty entry (VCF does not allow zero-length fields)");
        }
        warnings.warn("{} contains an empty entry (VCF does not allow zero-length fields); dropping it", fieldName);
        result.remove(i);
        i--;
      }
    }
    return result;
  }

  /**
   * Warns about and replaces, in place, any empty string in {@code list} with {@code "."} (the VCF missing-value
   * sentinel), for a position-dependent delimited field (e.g. a sample's FORMAT-keyed values) where dropping an
   * entry would misalign it with its corresponding key. See {@code EMPTY_FIELD_HANDLING.md} for the rationale.
   *
   * @param warnings where to route the warning, or {@code null} for strict mode -- an empty entry throws
   * {@link VcfFormatException} instead of being warned about and replaced
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static void fillEmptyEntriesWithDot(String fieldName, List<String> list, @Nullable VcfWarnings warnings) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i) == null) {
        throw new VcfFormatException(fieldName + " contains a null entry");
      }
      if (list.get(i).isEmpty()) {
        if (warnings == null) {
          throw new VcfFormatException(fieldName + " contains an empty entry (VCF does not allow zero-length fields)");
        }
        warnings.warn("{} contains an empty entry (VCF does not allow zero-length fields); treating it as the " +
            "missing value '.'", fieldName);
        list.set(i, ".");
      }
    }
  }

  /**
   * Converts a String representation of a property into a more useful type.
   * Specifically, can return:
   * <ul>
   *   <li>String</li>
   *   <li>Long</li>
   *   <li>VcfFloat</li>
   *   <li>The Boolean true (for flags)</li>
   *   <li>A List of any of the above types</li>
   * </ul>
   *
   * @param version the record's resolved VCF version (see {@link VcfContext#getVersion()}), passed as its own
   * argument -- never a {@link VcfContext} -- so a caller can never read it off the wrong context (e.g. a
   * writer's own diagnostics context instead of the record's). Read here to reject
   * {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE} under VCFv4.3+ (see below), and
   * passed on to {@link #checkReservedFormatConstraints}/{@link #checkReservedInfoConstraints} for their own
   * version-gated rules. Any such check must dispatch via {@code version.isAtLeast(VcfVersion.V4_3)} (see
   * {@link VcfVersion#isAtLeast}), never a string or ordinal comparison on {@link VcfVersion}.
   * @return {@code null}, after warning, when {@code key} is
   * {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE} and {@code version} is VCFv4.3 or
   * later: VCFv4.3 removed that key (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT tag"), so
   * there is no reserved property to convert against. Warn-and-{@code null} rather than throwing, because the
   * only public route in is {@link VcfSample#getProperty(ReservedFormatProperty)}, a lookup-style accessor whose
   * {@code String}-keyed sibling is null-tolerant "not present" (AGENTS.md's Public API Null-Safety Policy);
   * silence would be indistinguishable from an absent value.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static @Nullable <T> T convertProperty(ReservedProperty key, @Nullable String value, VcfVersion version,
      VcfWarnings warnings) {
    // FT/PS are FORMAT-only reserved IDs, and AC/AN/DP/END/MQ0/NS/AF are INFO-only reserved IDs (see each
    // check's own Javadoc) -- a future reserved key on the other side whose ID happens to collide must not get
    // the wrong side's semantics applied
    String normalized = value;
    if (key instanceof ReservedFormatProperty) {
      // Placed here, with the FORMAT dispatch, rather than in VcfSample.getProperty(ReservedFormatProperty):
      // that method delegates to this one, so one check here covers both routes, and this method is itself
      // public, so a guard there alone would leave this route open.
      if (key == ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy &&
          version.isAtLeast(VcfVersion.V4_3)) {
        warnings.warn("FORMAT GLE is not a reserved key under VCF {} (VCFv4.3 removed it); ignoring this " +
            "reserved-property read -- use VcfSample.getProperty(String) for the raw value, or " +
            "getProperty(VcfPosition, VcfMetadata, String) to interpret it against its own ##FORMAT declaration",
            version.getId());
        return null;
      }
      normalized = checkReservedFormatConstraints(key.getId(), value, version, warnings);
    } else if (key instanceof ReservedInfoProperty) {
      checkReservedInfoConstraints(key.getId(), value, version, warnings);
    }
    return convertProperty(key.getType(), normalized, key.isList(), warnings);
  }

  /**
   * Warns if {@code value} violates a constraint a target VCF specification version places on a reserved FORMAT
   * key beyond its declared {@code Type} -- constraints that generic {@code Type}-based conversion can't express.
   * Currently:
   * <ul>
   *   <li>{@link ReservedFormatProperty#Filter FT} must be {@code PASS}, {@code .}, or a semicolon-separated list of
   *   codes, each containing no whitespace, and {@code PASS}/{@code .} may not be combined with other codes -- the
   *   same grammar as the FILTER column (both VCFv4.2 constraints, unaffected by {@code version}).</li>
   *   <li>{@link ReservedFormatProperty#PhaseSet PS} must be a non-negative 32-bit integer (also VCFv4.2).</li>
   *   <li>Under VCFv4.3+ only, {@link ReservedFormatProperty#GenotypePosterior GP} must lie in the interval
   *   [0, 1] -- VCFv4.3.tex line 491 redefines GP from VCFv4.2's phred-scaled encoding to a 0-1 probability (see
   *   {@link ReservedFormatProperty#GenotypePosterior GP}'s own comment for the full citation); under <=4.2, any
   *   Float is a legitimate phred value, so no range check applies there. {@code GP} is {@code Number=G} and
   *   list-shaped, so each comma-separated element is checked independently, reusing {@link #checkUnitInterval}
   *   -- the same helper and the same two exemptions (a special {@link VcfFloat} value, and a value too long to
   *   be worth {@code BigDecimal}-parsing) already established for {@link ReservedInfoProperty#AlleleFrequency
   *   AF}; see that method's own Javadoc.</li>
   * </ul>
   *
   * @return the value to use for subsequent type conversion: unchanged, except for FT, whose empty
   * semicolon-separated codes are dropped -- the same "warn and drop" convention already used for an empty entry in
   * FILTER (FT's grammar is explicitly modeled on FILTER's) and every other position-independent list in this
   * library. If every code was dropped this way, returns {@code "."} (this library's established missing-value
   * sentinel) rather than an empty string, matching every other "reduces to nothing" case in this library. A
   * caller that only wants the validation side effect (e.g. a writer in diagnostics mode, which preserves raw
   * content rather than rewriting it) may discard the return value.
   *
   * @param version the record's resolved VCF version, passed as its own argument -- never a {@link VcfContext} --
   * so there is nothing here to read a version off but the value the caller explicitly supplied. See
   * {@link #convertProperty(ReservedProperty, String, VcfVersion, VcfWarnings)}'s own {@code version} doc for the
   * {@code isAtLeast} dispatch idiom this method's GP branch uses.
   */
  static @Nullable String checkReservedFormatConstraints(String key, @Nullable String value, VcfVersion version,
      VcfWarnings warnings) {
    if (value == null || value.equals(".")) {
      return value;
    }
    if (key.equals(ReservedFormatProperty.Filter.getId())) {
      return checkFilterLikeValue(key, value, warnings);
    } else if (key.equals(ReservedFormatProperty.PhaseSet.getId())) {
      checkNonNegative32BitInteger(key, value, warnings);
    } else if (key.equals(ReservedFormatProperty.GenotypePosterior.getId()) && version.isAtLeast(VcfVersion.V4_3)) {
      // per-element, not a scalar parse of the whole comma-delimited value: GP is Number=G and list-shaped, but
      // both callers hand this method the entire value before any splitting of their own -- VcfWriter's
      // validateFormatValue calls splitFormatValue one line after its own checkReservedFormatConstraints call,
      // and convertProperty(ReservedProperty, ...) doesn't split at all here, only later, inside the generic
      // convertProperty(Class<?>, ...) it calls next. A scalar BigDecimal parse of e.g. "0.2,5.0,0.8" would throw
      // and be treated as "not a number at all" (see checkUnitInterval), silently skipping the whole check.
      for (String element : value.split(",", -1)) {
        checkUnitInterval("FORMAT", key, element, warnings);
      }
    }
    return value;
  }

  private static String checkFilterLikeValue(String key, String value, VcfWarnings warnings) {
    String[] codes = value.split(";", -1);
    List<String> kept = new ArrayList<>(codes.length);
    boolean hasPassOrMissing = false;
    for (String code : codes) {
      if (code.isEmpty()) {
        warnings.warn("FORMAT {} value \"{}\" contains an empty filter code (VCF does not allow zero-length " +
            "fields); dropping it", key, value);
        continue;
      }
      if (containsWhitespace(code)) {
        warnings.warn("FORMAT {} value \"{}\" contains whitespace in filter code \"{}\"", key, value, code);
      }
      if (code.equals("PASS") || code.equals(".")) {
        hasPassOrMissing = true;
      }
      kept.add(code);
    }
    // kept.size() > 1 alone isn't enough: "PASS;PASS" also has size 2 and hasPassOrMissing == true, but repeating
    // the same code isn't "combined with another" one -- distinct-code count is what matters. "PASS;." IS two
    // distinct codes and must still warn: PASS means "passed all filters", "." means "no filter information
    // recorded", genuinely contradictory -- matching VcfPosition.checkFilters's identical unconditional
    // rejection of the same shape for the FILTER column (VcfPosition.java's own checkFilters, hts-specs
    // test/vcf/4.2/failed/failed_body_filter_000.vcf, ##CauseOfFailure=FILTER contains missing in addition to a
    // value); FT is documented as sharing FILTER's own grammar, so the same distinction applies here.
    if (hasPassOrMissing && new HashSet<>(kept).size() > 1) {
      warnings.warn("FORMAT {} value \"{}\" combines PASS or '.' with other filter codes", key, value);
    }
    if (kept.isEmpty()) {
      // every code was empty and dropped above; "." is this codebase's established missing-value sentinel for
      // every other "reduces to nothing" case (INFO values, ALT entries, sample values, FILTER lists) --
      // returning it here, rather than "", keeps this method consistent with that convention. (convertProperty
      // -- reached via checkReservedFormatConstraints, this method's only caller -- now also treats a plain ""
      // the same as "." for a scalar, non-Boolean value, so returning "" here would no longer actually break
      // VcfSample.getProperty(ReservedFormatProperty.Filter)'s null-means-absent contract the way it once did --
      // but "." remains the more accurate, self-documenting choice for "every code was empty", not just a
      // workaround for a since-fixed gap.)
      return ".";
    }
    return String.join(";", kept);
  }

  private static void checkNonNegative32BitInteger(String key, String value, VcfWarnings warnings) {
    long parsed;
    try {
      parsed = Long.parseLong(value);
    } catch (NumberFormatException e) {
      // not a number at all; the generic Long conversion reports this on its own
      return;
    }
    if (parsed < 0 || parsed > Integer.MAX_VALUE) {
      warnings.warn("FORMAT {} value \"{}\" is not a non-negative 32-bit integer", key, value);
    }
  }

  // AC/AN/DP/END/MQ0/NS's declared Long Type accepts any value Long.parseLong parses, including a negative one --
  // vcf_validator 0.10.2 rejects a negative value for each of these ("value must be a non-negative integer
  // number"; confirmed directly against test/vcf/4.2/failed/failed_body_info_001.vcf (AC), _006.vcf (AN),
  // _013.vcf (DP), _016.vcf (END), _021.vcf (MQ0), _023.vcf (NS)); bcftools 1.23.1 is silent on all six (it never
  // validates reserved-key semantics, only structural presence). Neither VCFv4.2.tex nor the VCFv4.3.tex target
  // tables this constraint directly, but each key's own reserved-key description implies a count/depth/position
  // can't be negative.
  private static final Set<String> NON_NEGATIVE_INTEGER_INFO_KEYS = Set.of(
      ReservedInfoProperty.AlleleCount.getId(), ReservedInfoProperty.AlleleNumber.getId(),
      ReservedInfoProperty.Depth.getId(), ReservedInfoProperty.End.getId(),
      ReservedInfoProperty.MappingQualityZeroCount.getId(), ReservedInfoProperty.NumberOfSamples.getId());

  /**
   * Warns if {@code value} violates a constraint that a reserved INFO key's own description implies beyond its
   * declared {@code Type} -- constraints that generic {@code Type}-based conversion can't express. Currently:
   * <ul>
   *   <li>{@link ReservedInfoProperty#AlleleCount AC}, {@link ReservedInfoProperty#AlleleNumber AN},
   *   {@link ReservedInfoProperty#Depth DP}, {@link ReservedInfoProperty#End END},
   *   {@link ReservedInfoProperty#MappingQualityZeroCount MQ0}, and {@link ReservedInfoProperty#NumberOfSamples NS}
   *   must each be a non-negative integer.</li>
   *   <li>{@link ReservedInfoProperty#AlleleFrequency AF} must lie in the interval [0, 1] -- confirmed directly
   *   against vcf_validator 0.10.2 with both a negative value (test/vcf/4.2/failed/failed_body_info_004.vcf,
   *   AF=-0.01) and a value above 1 (a hand-built AF=1.5 file, since hts-specs has no fixture for that side of
   *   the interval); bcftools 1.23.1 is silent on both.</li>
   *   <li>{@link ReservedInfoProperty#Cigar CIGAR} must match {@link #CIGAR_PATTERN} -- see that constant's own
   *   comment for the citation (vcf_validator only, no VCF spec version ties this key to SAM's grammar).</li>
   * </ul>
   * {@code value} may be a comma-joined list (AC/AF/CIGAR are all {@code Number=A}, one value per ALT allele):
   * each comma-separated element is checked independently. Unlike {@link #checkReservedFormatConstraints},
   * nothing here needs normalizing -- these are pure range/grammar checks with no equivalent to FT's
   * empty-entry-dropping -- so this returns {@code void} rather than a value for the caller to use.
   *
   * @param version the record's resolved VCF version, passed as its own argument -- never a {@link VcfContext} --
   * so there is nothing here to read a version off but the value the caller explicitly supplied. See
   * {@link #convertProperty(ReservedProperty, String, VcfVersion, VcfWarnings)}'s own {@code version} doc for the
   * {@code isAtLeast} dispatch idiom a future version-gated rule here must use. Not yet read by this method.
   */
  static void checkReservedInfoConstraints(String key, @Nullable String value, VcfVersion version,
      VcfWarnings warnings) {
    if (value == null || value.equals(".")) {
      return;
    }
    if (NON_NEGATIVE_INTEGER_INFO_KEYS.contains(key)) {
      for (String element : value.split(",", -1)) {
        checkNonNegativeInteger(key, element, warnings);
      }
    } else if (key.equals(ReservedInfoProperty.AlleleFrequency.getId())) {
      for (String element : value.split(",", -1)) {
        checkUnitInterval("INFO", key, element, warnings);
      }
    } else if (key.equals(ReservedInfoProperty.Cigar.getId())) {
      for (String element : value.split(",", -1)) {
        checkCigarGrammar(element, warnings);
      }
    }
    // TODO: SVLEN and CN (INFO's CopyNumberOfSegment) each gain a 4.4+-only reserved-key Number/Type change
    // not modeled here (VCFv4.4.tex changelog line 2575, "Redefined Number= for SVLEN, ... CN, ... to support
    // multiple symbolic alleles"); END keeps Number=1 but gains a 4.4+-only meaning change (line 2576,
    // "Redefined END as the end position of the longest ALT allele"). None of this is reachable while
    // VcfVersion.HIGHEST_IMPLEMENTED stays V4_3 -- deferred to the version-aware reserved-key tables TODO.md's
    // "Investigate bumping the target VCF spec to 4.4 or 4.5" entry already tracks as the next structural
    // change for this method.
  }

  private static void checkNonNegativeInteger(String key, String value, VcfWarnings warnings) {
    if (value.isEmpty() || value.equals(".")) {
      // an empty entry or the missing-value sentinel is reported elsewhere; not this check's concern
      return;
    }
    long parsed;
    try {
      parsed = Long.parseLong(value);
    } catch (NumberFormatException e) {
      // not a number at all; the generic Long conversion reports this on its own
      return;
    }
    if (parsed < 0) {
      warnings.warn("INFO {} value \"{}\" is a negative number", key, value);
    }
  }

  // fieldType is the literal "INFO" or "FORMAT" prefix for the warning message -- not VCF content, just which
  // of checkReservedInfoConstraints's (AF) or checkReservedFormatConstraints's (GP, under 4.3+) two callers
  // this is, matching every other field-specific message in this class (e.g. checkNonNegativeInteger's own
  // hardcoded "INFO", checkNonNegative32BitInteger's own hardcoded "FORMAT")
  private static void checkUnitInterval(String fieldType, String key, String value, VcfWarnings warnings) {
    if (value.isEmpty() || value.equals(".")) {
      return;
    }
    if (VcfFloat.isSpecialValue(value)) {
      // NaN/Inf/-Inf have no meaningful position within [0, 1]; nothing to compare
      return;
    }
    // deliberately not VcfFloat.parse/parseDecimal here: both emit a "does not match the VCF Float grammar"
    // warning as a side effect for a value that's BigDecimal-parseable but grammar-mismatched (e.g. "5."), and
    // the real Type conversion that runs immediately after this check in every caller (convertProperty(key.getType(),
    // ...) on the read path, convertProperty(type, value, ...) on the write path) already emits that exact
    // warning once on its own -- calling VcfFloat.parse here as well would double it
    if (VcfFloat.exceedsMaxDecimalLength(value)) {
      // too long to be a legitimate value; treated the same as "not a number at all" below -- see
      // VcfFloat.exceedsMaxDecimalLength's own comment for why this guard exists (new BigDecimal(value) is
      // quadratic-time in digit count, a CPU-exhaustion vector on an unbounded attacker-controlled string)
      return;
    }
    BigDecimal decimal;
    try {
      decimal = new BigDecimal(value);
    } catch (NumberFormatException e) {
      // not a number at all; the generic VcfFloat conversion reports this on its own
      return;
    }
    if (decimal.compareTo(BigDecimal.ZERO) < 0 || decimal.compareTo(BigDecimal.ONE) > 0) {
      warnings.warn(fieldType + " {} value \"{}\" does not lie in the interval [0, 1]", key, value);
    }
  }

  private static void checkCigarGrammar(String value, VcfWarnings warnings) {
    if (value.isEmpty() || value.equals(".")) {
      // an empty entry or the missing-value sentinel is reported elsewhere; not this check's concern
      return;
    }
    if (!CIGAR_PATTERN.matcher(value).matches()) {
      warnings.warn("INFO CIGAR value \"{}\" does not match the expected CIGAR grammar (a lone '*', or one or " +
          "more <length><operation> pairs, operation one of MIDNSHPX=)", value);
    }
  }

  // relocated from VcfWriter (was inlined in validateFormatValue there) so both the write path and VcfSample's
  // own read-path cardinality check can derive a FORMAT value's comma-separated pieces identically. GLE (see
  // ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy) is exempted under VCFv4.2 and earlier: its
  // commas are part of its own internal genotype:likelihood encoding, not a delimiter between independent
  // values. VCFv4.3 removed the key (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT tag"),
  // so from 4.3 a key named GLE is an ordinary custom key whose value splits on commas like any other. The
  // version test is written first so the 4.3+ path short-circuits on an enum ordinal compare instead of a
  // String.equals -- this is a per-value call on the write path.
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static String[] splitFormatValue(String key, String value, VcfVersion version) {
    if (!version.isAtLeast(VcfVersion.V4_3) &&
        key.equals(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy.getId())) {
      return new String[] { value };
    }
    return value.split(",", -1);
  }

  /**
   * @param number the {@code Number} actually in force for {@code key} at the call site -- either a
   * {@code ##FORMAT} declaration's own stated {@code Number}, or (when there is no declaration) the reserved
   * key's own version-resolved canonical {@code Number} ({@link ReservedFormatProperty#getExpectedNumber
   * (VcfVersion)}). Only {@code CNL}'s branch below consults it; {@code null} is tolerated (an undeclared
   * {@code Number} attribute) and simply never matches {@code "G"}, correctly falling through to the ordinary
   * check, which itself no-ops on a {@code null}/{@code "."} {@code number} (see {@code getExpectedCardinality}).
   * @return whether {@code key}'s formal {@code Number=G} does not describe the standard ploidy-derived
   * genotype-ordering list that {@code getExpectedCardinality}'s {@code "G"} branch computes an expected count
   * for, so that generic {@code Number=G} value-cardinality check specifically (not {@code Number}/{@code Type}
   * checks generally) must be skipped.
   * <p>
   * Three keys qualify, for two unrelated reasons, and two of the three are version-dependent (in opposite
   * directions):
   * <ul>
   *   <li>{@code GLE}, whose value isn't comma-delimited the normal way at all (see
   *   {@link #splitFormatValue}) -- but only under VCFv4.2 and earlier, since VCFv4.3 removed the key
   *   (VCFv4.3.tex line 2168) and a 4.3+ key named {@code GLE} is an ordinary custom key that gets the ordinary
   *   check against whatever {@code Number} its own {@code ##FORMAT} declaration states.</li>
   *   <li>{@code CNP} and {@code CNL}, whose values ARE genuine comma-delimited lists, but per their own spec
   *   prose ({@link ReservedFormatProperty#CopyNumberPosteriorProbabilities}/
   *   {@link ReservedFormatProperty#CopyNumberLikelihood}'s own comments) mean "one value per potential copy
   *   number" -- a dimension nothing in a VCF record states, unlike ploidy + ALT allele count, which
   *   genotype-ordering {@code "G"} is defined from. {@code CNP}'s exemption is <b>not version-gated and not
   *   {@code number}-gated</b>: it is reserved with {@code Number=G} at every version this library interprets,
   *   and (unlike {@code CNL}) a caller-declared override of that {@code Number} doesn't change what a CNP
   *   value structurally means, so the exemption applies to the key outright. {@code CNL}'s exemption
   *   <b>is</b> gated on {@code number} actually being {@code "G"}, in addition to the {@code >=4.3} version
   *   gate: {@code CNL} is only reserved with {@code Number=G} from VCFv4.3 on (VCFv4.2.tex:308 declares it
   *   {@code Number=.}; VCFv4.3.tex:678, the target, redeclares it {@code Number=G} -- see
   *   {@link ReservedFormatProperty#CopyNumberLikelihood}'s own comment), but a {@code ##FORMAT} declaration is
   *   free to override that with a different, concrete {@code Number} (e.g. {@code Number=4}) -- in which case
   *   the value has a real, checkable expected cardinality again and the ordinary check must run against it.
   *   Below {@code >=4.3}, {@code CNL}'s canonical {@code Number} is {@code .}, which the generic check already
   *   skips for that reason alone, without needing this exemption at all.</li>
   * </ul>
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean isExemptFromGenotypeOrderingCardinalityCheck(String key, VcfVersion version,
      @Nullable String number) {
    if (!version.isAtLeast(VcfVersion.V4_3) &&
        key.equals(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy.getId())) {
      return true;
    }
    if (key.equals(ReservedFormatProperty.CopyNumberLikelihood.getId())) {
      return version.isAtLeast(VcfVersion.V4_3) && "G".equals(number);
    }
    return key.equals(ReservedFormatProperty.CopyNumberPosteriorProbabilities.getId());
  }

  // GL's own spec definition (VCFv4.3.tex line 505 -- the target -- "In presence of the GT field the same ploidy
  // is expected; without GT field, diploidy is assumed", a shorter restatement (dropping the "canonical order"
  // clause) of VCFv4.2.tex line 224's identical-in-substance "In presence of the GT field the same ploidy is
  // expected and the canonical order is used; without GT field, diploidy is assumed"; carried unchanged again at
  // VCFv4.4.tex line 531/VCFv4.5.tex line 630) states an explicit ploidy default for the one case getPloidy()
  // otherwise can't resolve at all (GT absent). PL is "otherwise defined in the same way as the GL field"
  // (VCFv4.3.tex line 562, target) and GP is "using the same ordering as the GL field" (VCFv4.3.tex line 491,
  // target -- narrower wording than VCFv4.2.tex line 227's "otherwise defined precisely as the GL field", but
  // still enough to carry GL's ploidy-dependent ordering, which is exactly what the diploidy default governs) --
  // the target spec itself states the inheritance directly for both, no clarification from a later version
  // needed. PP is likewise declared directly in the VCFv4.3.tex target (line 563: "and otherwise defined in the
  // same way as the GP field"); VCFv4.2.tex has no PP entry at all (PP was introduced by VCFv4.3), but that gap
  // is in a version the target has already superseded, not a gap the target itself needs a later version's
  // guidance to fill. All four inherit GL's own diploidy-assumed rule transitively, not just the one key the
  // spec states it for directly. No other Number=G field, reserved or not, has any such spec-stated default, so
  // this default is scoped to exactly these four keys rather than generalized to getExpectedCardinality's "G"
  // branch itself.
  private static final Set<String> KEYS_ASSUMING_DIPLOIDY_WITHOUT_GT = Set.of(
      ReservedFormatProperty.GenotypeLikelihoods.getId(),
      ReservedFormatProperty.PhredScaledGenotypeLikelihoods.getId(),
      ReservedFormatProperty.GenotypePosterior.getId(),
      ReservedFormatProperty.PhredScaledPosteriorProbabilities.getId());

  /**
   * @return {@code ploidy} unchanged, unless it's {@code null} (GT absent, per {@link #getPloidy}) and
   * {@code key} is one of GL/PL/GP/PP, in which case this returns {@code 2} (diploidy), per those keys' own
   * spec-stated default.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static @Nullable Integer resolvePloidyForCardinalityCheck(String key, @Nullable Integer ploidy) {
    if (ploidy == null && KEYS_ASSUMING_DIPLOIDY_WITHOUT_GT.contains(key)) {
      return 2;
    }
    return ploidy;
  }

  /**
   * Warns if {@code actual} doesn't match the cardinality {@code number} implies for {@code numAltAlleles} ALT
   * alleles (and, for {@code Number=G}, {@code ploidy}). Relocated from {@code VcfWriter} (was a private instance
   * method reading {@code m_warnings} implicitly) so {@code VcfSample}'s own read-path check can share the
   * identical implementation, rather than a second, independently-drifting copy.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static void warnIfWrongCardinality(String field, @Nullable String number, int actual, int numAltAlleles,
      @Nullable Integer ploidy, VcfWarnings warnings) {
    Long expected = getExpectedCardinality(number, numAltAlleles, ploidy, warnings);
    if (expected != null && expected != actual) {
      warnings.warn("{} has {} value(s), but Number={} requires {}", field, actual, number, expected);
    }
  }

  // relocated from VcfWriter; package-private (not private) so InternalUtilsTest can exercise this
  // pure cardinality logic directly; takes an explicit VcfWarnings (rather than being an instance method) for the
  // same reason VcfMetadata.validateAlt does -- it has no instance state of its own to route a warning through
  static @Nullable Long getExpectedCardinality(@Nullable String number, int numAltAlleles, @Nullable Integer ploidy,
      VcfWarnings warnings) {
    if (number == null || number.equals(".")) {
      return null;
    }
    if (number.equals("A")) {
      return (long) numAltAlleles;
    }
    if (number.equals("R")) {
      return (long) numAltAlleles + 1;
    }
    if (number.equals("G")) {
      if (ploidy == null) {
        // ploidy could not be determined (e.g. GT is absent for this sample), so there's nothing valid to compare
        // Number=G's expected cardinality against; skip rather than guessing
        return null;
      }
      Long combinations = combinationsWithRepetition(numAltAlleles + 1, ploidy);
      if (combinations == null) {
        warnings.warn("Number=G cardinality for {} allele(s) and ploidy {} is too large to validate", numAltAlleles + 1,
            ploidy);
      }
      return combinations;
    }
    try {
      return Long.parseLong(number);
    } catch (NumberFormatException e) {
      warnings.warn("Number={} is too large to validate cardinality", number);
      return null;
    }
  }

  /**
   * @return {@code null} if the result overflows a {@code long}, consistent with {@link #getExpectedCardinality}'s
   * other branches skipping validation (rather than reporting a sentinel value) when a cardinality can't be computed
   */
  private static @Nullable Long combinationsWithRepetition(int numAlleles, int ploidy) {
    int k = Math.min(ploidy, numAlleles - 1);
    long result = 1;
    for (int i = 1; i <= k; i++) {
      try {
        result = Math.multiplyExact(result, numAlleles + ploidy - 1L - k + i) / i;
      } catch (ArithmeticException e) {
        return null;
      }
    }
    return result;
  }

  /**
   * @return the ploidy inferred from {@code gt}, or {@code null} if {@code gt} is absent and ploidy can therefore
   * not be determined. Relocated from {@code VcfWriter} (was a private instance method taking a {@code VcfSample}
   * and {@code sampleIndex} directly); {@code warnContext} lets each caller supply its own message prefix, since
   * {@code VcfSample}'s own read-path caller has no {@code sampleIndex} of its own to include, unlike
   * {@code VcfWriter}'s per-sample write loop.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static @Nullable Integer getPloidy(VcfPosition position, @Nullable String gt, String warnContext,
      VcfWarnings warnings) {
    if (gt == null) {
      return null;
    }
    String[] alleles = gt.split("[/|]", -1);
    // gt.split(regex, -1) on a non-null input always yields an array of length >= 1, so this starts true
    boolean valid = true;
    for (String allele : alleles) {
      if (allele.equals(".")) {
        continue;
      }
      try {
        int index = Integer.parseInt(allele);
        if (index < 0 || index > position.getAltBases().size()) {
          valid = false;
        }
      } catch (NumberFormatException e) {
        valid = false;
      }
    }
    if (!valid) {
      warnings.warn("{} has invalid GT {}", warnContext, gt);
    }
    return Math.max(1, alleles.length);
  }

  /**
   * Shared type-dispatch plumbing behind the {@link ReservedProperty}/{@link FormatType}/{@link InfoType}
   * overloads above -- not public API in its own right. Unlike those three (each driven by a real,
   * caller-supplied domain concept -- a reserved key, or a metadata declaration's own {@code Type}), this
   * takes a raw {@link Class}, which {@link #convertElement} only supports for a fixed, closed set of six
   * types and throws {@link VcfFormatException} for anything else -- so it can't actually serve a caller
   * with a genuinely custom type the way its signature suggests. Callers wanting to convert a custom,
   * non-reserved property's raw value should do their own conversion instead.
   */
  // public only because VcfSample needs to convert
  // a raw Class<?> for non-reserved FORMAT keys the same way ReservedProperty and FormatType overloads above do.
  // @RestrictedApi marks this as public solely for that reason, not as real external API.
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  @SuppressWarnings("unchecked")
  public static @Nullable <T> T convertProperty(Class<?> clas, @Nullable String value, boolean isList, VcfWarnings warnings) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (!isList) {
      // clas != Boolean.class: convertElement()'s own Boolean branch already gives an empty string a distinct,
      // deliberate meaning (a Flag key's "present, no value" encoding -- see VcfPosition.info()'s "if (idx ==
      // -1) info.put(key, \"\")" for where that shape comes from) -- true, not "missing". Only for every other
      // type does an empty (but non-".") scalar value have no such meaning, matching the list branch's own
      // per-element handling immediately below (an empty entry there already warns and substitutes the
      // missing-value sentinel); a scalar value previously had no equivalent check at all, so an empty value
      // reached convertElement() directly -- silently returning the empty string as-is for a String-typed key
      // (e.g. a live-mutated INFO AA="") or throwing a confusing "Expected integer; got " for a numeric one
      // (e.g. INFO DP="") instead of the graceful null this library's missing-value convention specifies
      // elsewhere. See EMPTY_FIELD_HANDLING.md.
      if (value.isEmpty() && clas != Boolean.class) {
        // this shared method has no key/field-name context to report (see its own Javadoc: it serves callers
        // as different as a reserved-property getter and a generic declared-Type conversion) -- the expected
        // Java type is the only identifying detail available here. Every direct caller of this scalar branch
        // that already has its own generic "contains an empty value" warning (VcfWriter's validateInfoValue/
        // validateFormatValue) skips calling this method at all for an empty value, specifically to avoid
        // this context-free line firing as a redundant second warning for the same defect.
        warnings.warn("Value of type {} is an empty entry (VCF does not allow zero-length fields); treating " +
            "it as the missing value '.'", clas.getSimpleName());
        return null;
      }
      // an unchecked cast to this method's own unbounded generic type parameter T is erased to a no-op by javac
      // (no checkcast bytecode is emitted), so it can never throw ClassCastException here -- a real type mismatch
      // only surfaces later, in the caller's own stack frame when the value is used as the wrong type
      return (T) convertElement(clas, value, warnings);
    }
    List<Object> list = new ArrayList<>();
    // limit -1 preserves an empty entry (e.g. a trailing ','), handled below rather than silently dropped; VCF does
    // not allow zero-length fields. See EMPTY_FIELD_HANDLING.md.
    //
    // Deliberately not extended to a wholly-empty value (e.g. "" splitting to a single-element [""]) the way the
    // scalar branch above now is: a list value's element count is part of its own arity (a Number=A/R/G/2/3/...
    // declaration), so collapsing a single empty element to an overall "missing" null (rather than a one-element
    // list containing a null) would silently change how many values this property reports having. The per-element
    // substitution below already reports the defect (with a warning) and preserves positional arity; a caller
    // that unboxes a returned list element without a null check has the same obligation it already has for any
    // other list-typed property's genuine "." entry.
    for (String part : value.split(",", -1)) {
      if (part.isEmpty()) {
        warnings.warn("List value \"{}\" contains an empty entry (VCF does not allow zero-length fields); " +
            "treating it as the missing value '.'", value);
        list.add(null);
      } else {
        list.add(convertElement(clas, part, warnings));
      }
    }
    return (T) list;
  }

  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static Class<?> toJavaType(FormatType type) {
    switch (type) {
      case Integer:
        return Long.class;
      case Float:
        return VcfFloat.class;
      case Character:
        return Character.class;
      case String:
        return String.class;
      default:
        throw new VcfFormatException(FormatType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
  }

  static @Nullable <T> T convertProperty(FormatType type, @Nullable String value, VcfWarnings warnings) {
    return convertProperty(toJavaType(type), value, false, warnings);
  }

  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static Class<?> toJavaType(InfoType type) {
    switch (type) {
      case Integer:
        return Long.class;
      case Float:
        return VcfFloat.class;
      case Character:
        return Character.class;
      case String:
        return String.class;
      case Flag:
        return Boolean.class;
      default:
        throw new VcfFormatException(InfoType.class.getSimpleName() + " " + type + " isn't covered?!");
    }
  }

  static @Nullable <T> T convertProperty(InfoType type, @Nullable String value, VcfWarnings warnings) {
    return convertProperty(toJavaType(type), value, false, warnings);
  }

  private static @Nullable Object convertElement(Class<?> clas, @Nullable String value, VcfWarnings warnings) {
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (clas == String.class) {
      return value;
    } else if (clas == Character.class) {
      if (value.length() == 1) {
        return value.charAt(0);
      } else {
        throw new VcfFormatException("Invalid character value '" + value + "'");
      }
    } else if (clas == Boolean.class) {
      value = StringUtils.stripToNull(value);
      if (value == null) {
        return true;
      }
      if (value.equals("0") || value.equalsIgnoreCase("false")) {
        return false;
      }
      if (value.equals("1") || value.equalsIgnoreCase("true")) {
        return true;
      }
      throw new VcfFormatException("Invalid boolean value: '" + value + "'");

    } else if (clas == VcfFloat.class) {
      // unlike plain BigDecimal, this also accepts NAN/INF/INFINITY (case-insensitive, optionally signed), which
      // VCFv4.3.tex (the target) states are valid Float literals; VCFv4.2 leaves Float's grammar undefined
      // entirely -- see VcfFloat's own Javadoc for the citation and why VCFv4.3, not a later version, is cited
      return VcfFloat.parse(value, warnings);
    } else if (clas == Long.class) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw new VcfFormatException("Expected integer; got " + value);
      }
    }
    throw new VcfFormatException("Type " + clas + " unrecognized");
  }

  /**
   * Rejects a metadata property whose key or value would corrupt the {@code <key=value,...>} attribute list it is
   * emitted into. This is the single implementation behind both the construction-time check in
   * {@code VcfMetadata.Builder} and the write-time check in {@code VcfWriter.validateMetadataProperties}; two
   * independent copies of a predicate this subtle drift.
   * <p>
   * See STRUCTURAL_DELIMITER_HANDLING.md for the verified corruption matrix, including why an unescaped quote is
   * the most destructive of the three and why its damage is position-dependent.
   *
   * @throws VcfFormatException if a key is null/empty, or contains {@code ,}, {@code =}, or any {@code "}
   * (escaped or not), or if an unquoted value contains {@code ,}, {@code =}, or an unescaped {@code "}
   */
  static void checkMetadataPropertyEncoding(String kind, Map<String, String> properties) {
    for (Map.Entry<String, String> property : properties.entrySet()) {
      String propertyName = property.getKey();
      // Any '"' in a NAME is rejected, escaped or not -- unlike a VALUE, where a properly escaped quote inside
      // a quoted string is legal and common (VCFv4.3.tex line 119 gives the grammar as
      // "##key=<key=value,key=value,...>" and states no character set for the inner key, so the spec is
      // silent; per AGENTS.md's Decision Hierarchy bcftools governs). bcftools 1.23.1 (htslib 1.23.1) refuses
      // the whole header line for a\"b, a\"b\" and "ab" alike -- all three produce
      // "[E::bcf_hdr_parse_line] Could not parse the header line" and the declaration is dropped -- while
      // accepting an escaped quote inside a quoted value. This library round-trips the escaped-name forms
      // cleanly on its own, so an internal round-trip test cannot detect this; see
      // BCFTOOLS_VERSION_DEPENDENCIES.md.
      if (propertyName == null || propertyName.isEmpty() || propertyName.contains(",") ||
          propertyName.contains("=") || propertyName.contains("\"")) {
        throw new VcfFormatException(kind + " metadata contains an invalid property name: " + property.getKey());
      }
      String value = property.getValue();
      if (!isQuoted(value) && (value.contains(",") || value.contains("=") || containsUnescapedQuote(value))) {
        throw new VcfFormatException(kind + " metadata property " + propertyName +
            " contains an unquoted structural delimiter");
      }
    }
  }

  static boolean isQuoted(String value) {
    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      return false;
    }
    boolean escaped = false;
    for (int i = 1; i < value.length() - 1; i++) {
      char c = value.charAt(i);
      if (c == '"' && !escaped) {
        return false;
      }
      if (c == '\\') {
        escaped = !escaped;
      } else {
        escaped = false;
      }
    }
    return !escaped;
  }

  /**
   * @return whether {@code value} contains a {@code "} not preceded by an escaping {@code \} (the same escape
   * convention {@link InternalUtils}'s top-level splitter uses). {@link #isQuoted} already rejects a value that starts
   * and ends with {@code "} but isn't validly escaped in between, but a value can also contain a stray, unescaped
   * {@code "} without looking quoted at all (no leading/trailing {@code "}) and without containing {@code ,} or
   * {@code =} either, so this must be checked independently of both. Left unrejected, such a value would flip the
   * writer's top-level "inside a quoted value" tracking state when re-read, potentially misaligning every property
   * that follows it in the same declaration. Verified live: a value of {@code "abc"def"} followed by another
   * property in the same declaration fails to re-parse at all ({@code VcfFormatException}), not just producing a
   * different value than intended.
   */
  static boolean containsUnescapedQuote(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length() && (value.charAt(i + 1) == '\\' || value.charAt(i + 1) == '"')) {
        i++; // skip the escaped character
      } else if (c == '"') {
        return true;
      }
    }
    return false;
  }

}

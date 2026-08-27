package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import com.google.errorprone.annotations.RestrictedApi;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.helpers.MessageFormatter;


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
  //
  // Colon is deliberately NOT excluded from the unbracketed chromosome class, even though this pattern's own job
  // is to find the ":pos" suffix: VCFv4.3.tex:2140-2141's errata explicitly addresses exactly this ambiguity --
  // "The VCF specification previously disallowed colons (':') in contig names to avoid confusion when parsing
  // breakends, but this was unnecessary. Even with contig names containing colons, the breakend mate position
  // notation can be unambiguously parsed because the ':pos' part is always present" -- i.e. resolve any colon
  // ambiguity by taking the LAST ":digits" suffix as pos, never the first. A colon-excluding class here would
  // make a colon-bearing contig name (legal per InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN's own identical
  // citation, and thus a legal CHROM or ##contig ID) unrepresentable in a breakend ALT that refers to it -- this
  // was a real bug (an unconditional VcfFormatException, aborting the whole parse even under a lenient context)
  // until fixed. Relying on backtracking, not a possessive quantifier, is required for this: the greedy "+"
  // below must be able to give back trailing characters to whatever ":\d+" needs, which is exactly how the
  // spec's own disambiguation works (measured: "HLA-A*01:01:200" still resolves to chr="HLA-A*01:01", pos=200).
  private static final String sf_mateLocation =
      "(?:"                                     + // wrap the whole expression
        "(?:[^\\s,\\[\\]]+|(?:<[^\\s,<>]+>))"    + // chromosome: CHROM-like (no whitespace, comma, or brackets), or a symbolic ID
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
  // The same errata's own outer bound, never enforced by the blocklist just above: a VCF contig name "may
  // contain any printable ASCII characters in the range [!-~]" (VCFv4.3.tex:258-259, carried verbatim into
  // VCFv4.4.tex:273/VCFv4.5.tex:322). INVALID_CONTIG_NAME_CHAR_PATTERN only blocklists the specific punctuation
  // characters EXCLUDED from that range; it says nothing about the range's own edges, so a C0 control
  // character, DEL (0x7F), or any non-ASCII character silently passed both checks. [!-~] is exactly 0x21-0x7E
  // in ASCII. Confirmed live that bcftools 1.23.1 warns ("[W::bcf_hrec_check] Invalid contig name") on both a
  // DEL character and a non-ASCII character in a contig name, where this library previously warned about
  // neither.
  public static final Pattern NON_PRINTABLE_ASCII_CONTIG_NAME_CHAR_PATTERN = Pattern.compile("[^\\x21-\\x7E]");

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

  /**
   * Grammar for a metadata declaration's attribute NAME. The VCF spec states none -- VCFv4.3.tex:117 gives
   * only "the value is itself a comma-separated list of key=value pairs, enclosed within `<' and `>'
   * characters", leaving `key' an undefined nonterminal (identically in 4.2/4.4/4.5) -- so per AGENTS.md's
   * Decision Hierarchy bcftools governs. htslib 1.23.1 `vcf.c:695' carries this exact regex as a comment
   * above its scanner: "^[A-Za-z_][0-9A-Za-z_.]*$". A name outside it makes bcftools discard the whole
   * declaration, so we must not originate one.
   */
  public static final Pattern METADATA_ATTRIBUTE_NAME_PATTERN =
      Pattern.compile("^[A-Za-z_][0-9A-Za-z_.]*$");

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

  /** One {@code name=value} pair from a metadata declaration's attribute list. */
  record Attribute(String name, String rawValue) {}

  /** @return index of the first {@code ,} at or after {@code from}, or {@code s.length()} if none. */
  private static int indexOfComma(String s, int from) {
    int i = s.indexOf(',', Math.min(from, s.length()));
    return i < 0 ? s.length() : i;
  }

  /**
   * @return index of the first {@code target} at or after {@code from} that is not preceded by an odd run of
   * {@code \}, or {@code -1} if none. The backslash run is counted no further back than {@code floor}, which
   * is the value's first byte -- matching htslib's {@code is_escaped(p, q)}, which counts from the value
   * start rather than the whole line.
   */
  private static int indexOfUnescaped(String s, char target, int from, int floor) {
    for (int i = from; i < s.length(); i++) {
      if (s.charAt(i) != target) {
        continue;
      }
      int backslashes = 0;
      for (int k = i - 1; k >= floor && s.charAt(k) == '\\'; k--) {
        backslashes++;
      }
      if (backslashes % 2 == 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Strips leading and trailing ASCII {@code 0x20} only. Deliberately NOT {@link String#trim()}, which also
   * strips tab, CR and LF -- htslib skips exactly {@code ' '} on both sides of a name
   * ({@code while ( *q && *q==' ' ) { p++; q++; }} at vcf.c:694, and
   * {@code while ( *q && *q==' ' ) { q++; m++; }} at vcf.c:703), so trimming a tab here would silently rename an
   * attribute the reference implementation would have rejected outright.
   */
  private static String stripAsciiSpaces(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && s.charAt(start) == ' ') {
      start++;
    }
    while (end > start && s.charAt(end - 1) == ' ') {
      end--;
    }
    return s.substring(start, end);
  }

  /**
   * The outcome of {@link #parseOneAttribute}: either a successfully parsed {@link Attribute}, or {@code null}
   * if none could be extracted (already warned) -- plus the index immediately after this attribute (past its
   * separating comma, if {@code commaIsSeparator}), for a multi-attribute caller to resume scanning from.
   */
  private record AttributeParseResult(@Nullable Attribute attribute, int nextIndex) {}

  /**
   * @return whether {@code name} is {@code ##META}'s {@code Values} attribute -- the one place any VCF
   * version (4.3-4.5) evidences {@code [...]} as a comma-protecting value form (VCFv4.3.tex:273-276's worked
   * examples). Shared by {@link #parseOneAttribute} and {@link #findEncodingProblems} so construction and
   * parsing cannot independently drift on which attribute gets bracket-list quoting -- see
   * VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting beyond `##META`'s `Values`" entry for why this is
   * scoped this narrowly rather than generalized to every attribute the way htslib generalizes it.
   */
  private static boolean isMetaValues(String kind, String name) {
    return kind.equals("META") && name.equals("Values");
  }

  /**
   * @return the index at which the current attribute must end: the next {@code ,} (or {@code s.length()} if
   * none), when {@code commaIsSeparator}; otherwise {@code s.length()}, since {@code s} is then already exactly
   * one attribute and a {@code ,} inside it is ordinary content.
   */
  private static int attributeLimit(String s, int from, boolean commaIsSeparator) {
    return commaIsSeparator ? indexOfComma(s, from) : s.length();
  }

  /**
   * Parses one {@code name=value} pair starting at {@code s.charAt(i)}, implementing bcftools' splitting
   * grammar (htslib 1.23.1 {@code bcf_hdr_parse_line}) with TWO deliberate exceptions.
   *
   * <p>First, htslib's unquoted-value scan keeps an angle-bracket nesting counter (`vcf.c:730-733`, "if (
   * *q=='<' ) nopen++;" ... "if ( *q==',' && nopen==1 ) break;") so a {@code ,} inside a {@code <...>} span
   * does not end the attribute. This library does NOT implement that counter, because no VCF version defines
   * {@code <...>} as a quoting or nesting form for a value -- VCFv4.3.tex:117, "the value is itself a
   * comma-separated list of key=value pairs, enclosed within `<' and `>' characters", makes {@code ,} the
   * separator with no stated exception. A value that must contain {@code ,}, {@code <}, or {@code >} has to
   * be double-quoted. This is a recorded decision, not an oversight -- see VCF_SPEC_AMBIGUITIES.md's "Angle
   * brackets are not a quoting form in a metadata attribute value" entry before "fixing" it.
   *
   * <p>Second, and unlike htslib (whose {@code [}/{@code ]} handling is attribute-name- and declaration-type-
   * agnostic), a leading {@code [} is recognized as a comma-protecting delimiter ONLY for {@code ##META}'s
   * {@code Values} attribute -- the one place any VCF version (4.3-4.5) evidences the form at all
   * (VCFv4.3.tex:273-276's worked examples). Everywhere else, {@code [} is an ordinary byte, exactly like
   * {@code <}/{@code >}: it protects nothing, and its presence only warns. See VCF_SPEC_AMBIGUITIES.md's
   * "General bracket-list quoting beyond `##META`'s `Values`" entry for the full reasoning and the reversal
   * this represents from an earlier, broader decision. Within {@code Values}'s bracket span, there is no
   * escape mechanism for {@code [}/{@code ]} (the spec defines none), so the closing {@code ]} is found by a
   * plain, unescaped scan -- commas, quotes, and backslashes inside are all ordinary content.
   *
   * <p>This deliberately does NOT enforce bcftools' name-validity rule -- see the design spec's "Precisely
   * which grammar, on which surface" table. The parse path accepts a superset and preserves it.
   *
   * @param commaIsSeparator {@code true} when {@code s} is an attribute LIST (a {@code ,} ends this attribute
   * and starts the next, so the name scan and every value-boundary search below stop at it); {@code false}
   * when {@code s} is already exactly one attribute (a {@code ,} is ordinary content, and the name scan runs
   * to the first {@code =} only). {@link #tokenizeAttributeList} passes {@code true}; {@link #parseAttribute}
   * passes {@code false}. Every other rule here is shared between the two callers.
   * @param kind the declaration type this attribute list belongs to (e.g. {@code "META"}), used only to
   * decide whether this is {@code ##META}'s {@code Values} attribute; {@link #parseAttribute} has no such
   * context and passes {@code ""}, which can never match.
   */
  private static AttributeParseResult parseOneAttribute(String s, int i, boolean commaIsSeparator,
      VcfWarnings warnings, String kind) {
    int n = s.length();
    int limit = attributeLimit(s, i, commaIsSeparator);
    int nameEnd = i;
    while (nameEnd < limit && s.charAt(nameEnd) != '=') {
      nameEnd++;
    }
    if (nameEnd >= limit) {
      warnings.warn("Metadata attribute \"{}\" has no '='; dropping it", s.substring(i, Math.min(nameEnd, n)));
      return new AttributeParseResult(null, limit + 1);
    }
    String name = stripAsciiSpaces(s.substring(i, nameEnd));
    int idx = nameEnd + 1;
    if (name.isEmpty()) {
      warnings.warn("Metadata attribute has an empty name; dropping it");
      int end = attributeLimit(s, idx, commaIsSeparator);
      return new AttributeParseResult(null, end + 1);
    }
    while (idx < n && s.charAt(idx) == ' ') {
      idx++;
    }
    int valueStart = idx;
    char first = idx < n ? s.charAt(idx) : '\0';
    String rawValue;
    int nextIndex;
    if (first == '"' || (first == '[' && isMetaValues(kind, name))) {
      // META's Values has no escape mechanism for '[' / ']' (the spec defines none), unlike the quoted-value
      // form's backslash escaping -- a plain, unescaped scan for the next ']' is correct here, not
      // indexOfUnescaped.
      int close = first == '"'
          ? indexOfUnescaped(s, '"', valueStart + 1, valueStart)
          : s.indexOf(']', valueStart + 1);
      if (close >= 0) {
        rawValue = s.substring(valueStart, close + 1);
        int end = attributeLimit(s, close + 1, commaIsSeparator);
        if (end > close + 1) {
          warnings.warn("Metadata attribute \"{}\" has trailing content \"{}\" after its closing "
              + "delimiter; discarding it", name, s.substring(close + 1, end));
        }
        nextIndex = end + 1;
      } else if (first == '"') {
        int end = attributeLimit(s, valueStart, commaIsSeparator);
        String content = s.substring(valueStart, end);
        warnings.warn("Metadata attribute \"{}\" has an unterminated {}; recovering best-effort",
            name, "quoted value");
        rawValue = content;
        nextIndex = end + 1;
      } else {
        // META's Values, unterminated '[': parse-only leniency -- emit the value as-is, unmodified, and let
        // a downstream tool decide what to do with it, rather than re-encoding it (the unterminated-quote
        // recovery above does not re-encode either; the ORIGINAL unterminated-bracket recovery this replaces
        // used to re-quote defensively, back when '[' was a general-purpose delimiter reachable on any
        // attribute -- now that it is scoped to this one spec-evidenced attribute, construction rejects this
        // shape outright (see findEncodingProblems), so an emitted "as-is" value here can only ever be
        // carried-forward content from someone else's file, never something this library originated.
        //
        // Deliberately NOT given the same defensive re-quote the non-Values literal branch below gets for the
        // identical "dangling '[', no ']' anywhere" shape, and this is not an oversight: that re-quote exists
        // because outside Values, this library's OWN comma-splitting can turn a WELL-FORMED bracket value into
        // a dangling one (splitting "[a, b]" at its interior comma yields "[a") -- manufacturing new
        // bcftools-fatal content from input that was never malformed. Values cannot suffer that failure mode:
        // its closing ']' scan above is a plain s.indexOf(']', ...) over the ENTIRE remainder of the line, not
        // bounded by attributeLimit/the next comma (see aMetaValuesClosingBracketIsSearchedForAcrossTheWholeRemainder
        // -- deliberately, so the spec's own ##META worked example, whose Values list contains interior commas,
        // is never misread as "unterminated"). So the only way this branch is ever reached at all is if the
        // ORIGINAL file's Values value already had no closing ']' anywhere in the rest of the line -- this
        // library's own parsing cannot create that state, only observe it. bcftools would already fail fatally
        // reading that same original file, before this library was ever involved, so re-emitting the value
        // as-is on the default write path introduces no NEW failure mode; it is a parse-only, purely
        // carried-forward artifact of someone else's malformed file (AGENTS.md's originated-vs-carried-forward
        // distinction), not a case of this library manufacturing fresh corruption. bcftools' own fatal exit on
        // an unterminated '[' at all is itself considered a defect and is filed upstream at
        // https://github.com/samtools/htslib/issues/2065 -- the same issue the non-Values re-quote fix's own
        // comment and VCF_SPEC_AMBIGUITIES.md/BCFTOOLS_VERSION_DEPENDENCIES.md cite; that filing is why this
        // library does not additionally paper over bcftools' behavior here the way it does for the genuinely
        // self-inflicted non-Values case.
        int end = attributeLimit(s, valueStart, commaIsSeparator);
        rawValue = s.substring(valueStart, end);
        warnings.warn("Metadata attribute \"{}\" has no closing ']'; preserving its value as-is", name);
        nextIndex = end + 1;
      }
    } else {
      int end = attributeLimit(s, valueStart, commaIsSeparator);
      // leading 0x20 was already consumed before valueStart, so this only strips the trailing run
      String literal = stripAsciiSpaces(s.substring(valueStart, end));
      if (first == '[' && literal.indexOf(']') < 0) {
        // Splitting at the comma (since '[' is not comma-protecting outside ##META's Values) can turn a
        // WELL-FORMED bracket value into something that looks unterminated once serialized on its own --
        // e.g. "x=[a, b]" truncates to just "[a" here, the ']' having been discarded along with the "
        // b]" fragment that follows the comma. Writing "[a" back out verbatim would manufacture a value
        // bcftools cannot read (exit 255) from an input that was perfectly safe -- not a case of carrying
        // forward already-malformed content, but of THIS library creating new fatal content via its own
        // truncation. Defensively re-quote whenever the stored value dangles a '[' with no ']' anywhere in
        // it, regardless of whether that happened via truncation or because the original input already had
        // no closing ']' before this attribute's own boundary -- either way, quoting keeps the default write
        // path from ever originating this shape.
        rawValue = quote(literal);
        warnings.warn("Metadata attribute \"{}\" has an unquoted value \"{}\" starting with '[' but never "
            + "closing it; quoting it so it stays safe to write back out", name, literal);
      } else {
        rawValue = literal;
        if (first == '[' || literal.indexOf('<') >= 0 || literal.indexOf('>') >= 0
            || containsUnescapedQuote(literal)) {
          warnings.warn("Metadata attribute \"{}\" has an unquoted value \"{}\" containing a character "
              + "bcftools treats as structural; preserving it", name, literal);
        }
      }
      nextIndex = end + 1;
    }
    return new AttributeParseResult(new Attribute(name, rawValue), nextIndex);
  }

  /**
   * Splits a metadata declaration's attribute list into {@code name=value} pairs -- see {@link
   * #parseOneAttribute} for the shared per-attribute grammar this loops over with {@code commaIsSeparator =
   * true}.
   *
   * @param innerText the attribute list WITHOUT the surrounding {@code <} and {@code >}
   * @param kind the declaration type this attribute list belongs to (e.g. {@code "META"}) -- see {@link
   * #parseOneAttribute}'s {@code kind} parameter
   */
  static List<Attribute> tokenizeAttributeList(String innerText, VcfWarnings warnings, String kind) {
    List<Attribute> attributes = new ArrayList<>();
    int n = innerText.length();
    if (n == 0) {
      return attributes;
    }
    boolean trailingSeparator = innerText.charAt(n - 1) == ',';
    int i = 0;
    while (i < n) {
      AttributeParseResult result = parseOneAttribute(innerText, i, true, warnings, kind);
      if (result.attribute() != null) {
        attributes.add(result.attribute());
      }
      i = result.nextIndex();
    }
    if (trailingSeparator) {
      warnings.warn("Metadata attribute list has an empty trailing attribute; dropping it");
    }
    return attributes;
  }

  static Map<String, String> extractPropertiesFromLine(String value, VcfWarnings warnings, String kind) {
    return foldAttributes(tokenizeAttributeList(value, warnings, kind), warnings);
  }

  /**
   * Folds tokenized attributes into the {@code Map} the model consumes. Owns the duplicate-name rule -- warn
   * and keep first -- which was previously in {@code extractProperties}; see VCF_SPEC_AMBIGUITIES.md.
   * A plain map fold would keep last but silently lose the warning. Also owns the parse-time
   * {@link #METADATA_ATTRIBUTE_NAME_PATTERN} check, run once per surviving key after the fold.
   */
  private static Map<String, String> foldAttributes(List<Attribute> attributes, VcfWarnings warnings) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (Attribute attribute : attributes) {
      if (properties.containsKey(attribute.name())) {
        // Keeping the FIRST value is one policy applied evenly: AGENTS.md requires keep-first for a duplicate
        // declaration ID across all eight declaration types ("never keep-last/overwrite"), and this is the same shape
        // one level down. No VCF version (4.2-4.5) contemplates a repeated attribute name within one
        // declaration, so there is no spec to defer to and this is a library choice -- deliberately NOT derived
        // from bcftools, which keeps LAST for a non-ID attribute and FIRST for ID (both measured, bcftools
        // 1.23.1). Diverging from a tool on input no spec contemplates is a decision, not a defect; see
        // VCF_SPEC_AMBIGUITIES.md's "Duplicate attribute within one metadata declaration" entry.
        // The duplicate is unrepresentable either way -- this Map holds one value per key -- so keep-first loses
        // no more data than keep-last; it selects the other survivor.
        warnings.warn("Metadata declaration contains duplicate attribute \"{}\"; keeping the first value",
            attribute.name());
        continue;
      }
      properties.put(attribute.name(), attribute.rawValue());
    }
    // Parse-time counterpart to findEncodingProblems' construction/write-time check of the same pattern. Runs
    // POST-fold, once per surviving key: a repeated invalid name is one bad name, and the duplicate-attribute
    // warning above already reports the repetition. Warn, never drop -- the value is preserved and re-emitted,
    // per the Parser Validation Policy. Without this, an attribute name containing e.g. '>' was silently
    // accepted at parse time and first surfaced only at validateBeforeWrite().
    for (String name : properties.keySet()) {
      if (!METADATA_ATTRIBUTE_NAME_PATTERN.matcher(name).matches()) {
        warnings.warn("Metadata attribute name \"{}\" is not valid", name);
      }
    }
    return properties;
  }

  /**
   * Parses ONE already-delimited attribute. The caller's boundary is authoritative, so a {@code ,} in
   * {@code attribute} is ordinary content rather than a separator, and the name runs to the first {@code =}
   * only. Every other rule -- ASCII-space trimming, leading-byte classification, escape handling, junk-after-close
   * recovery -- is shared with {@link #tokenizeAttributeList} via {@link #parseOneAttribute}.
   *
   * <p><b>Null contract:</b> NEVER returns {@code null} for a {@code null} {@code attribute} -- that is a caller
   * bug and must keep crashing with a raw {@code NullPointerException} ({@link InternalUtils} is exempt from this
   * library's public-API {@code VcfFormatException} null-rejection policy; see AGENTS.md's Public API
   * Null-Safety Policy). {@code null} is returned only to mean "this attribute was unparseable and has already
   * been warned about; drop it."
   *
   * @return the parsed attribute, or {@code null} if it has no {@code =} or an empty name (warned)
   */
  static @Nullable Attribute parseAttribute(String attribute, VcfWarnings warnings) {
    // "" can never equal "META", so this test-facing surface never takes the Values-specific bracket branch --
    // it has no declaration-type context to offer, and no caller needs it (see parseOneAttribute's kind param).
    return parseOneAttribute(attribute, 0, false, warnings, "").attribute();
  }

  // public only because a large number of test call sites across this package's metadata tests call this
  // directly to build a Map<String,String> from "key=value" arrays -- it has ZERO production callers now that
  // extractPropertiesFromLine calls foldAttributes(tokenizeAttributeList(...)) directly instead of routing
  // through this method; this is now a purely test-facing surface, kept @RestrictedApi/public for those call
  // sites' sake, not for any internal caller. (Deliberately not naming an exact count or class list here: per
  // AGENTS.md's "an identifier grep is not an inventory," a number pinned into a comment only invites drift as
  // tests are added -- the ZERO-production-callers claim above is the one that matters and is re-verified by
  // this method having no non-test caller in src/main.)
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static Map<String, String> extractProperties(String[] props, VcfWarnings warnings) {
    List<Attribute> attributes = new ArrayList<>(props.length);
    for (String prop : props) {
      Attribute attribute = parseAttribute(prop, warnings);
      if (attribute != null) {
        attributes.add(attribute);
      }
    }
    return foldAttributes(attributes, warnings);
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
      // truncated and escaped, not embedded verbatim: since readPhysicalLine() stopped treating a lone CR as
      // a line separator, a CR-only file (or any file with no LF at all before EOF) makes one "line" the
      // entire remaining file content, and that content can reach here as `key` or `value` -- found by the
      // Phase 2 review's third re-verification round, the same shape f5f713e fixed for the "Not a VCF file"
      // message specifically. See truncateAndEscapeForMessage's own comment.
      throw new VcfFormatException("Property [[[" + truncateAndEscapeForMessage(key) + "=" +
          truncateAndEscapeForMessage(value) + "]]] contains a line terminator");
    }
  }

  // Caps how much of an untrusted, potentially-unbounded string (see this method's own callers' comments for
  // why a "line" isn't actually bounded by anything resembling a real VCF line's length) reaches an exception
  // message, and escapes any embedded CR/LF/tab as literal \r/\n/\t so the message can't itself contain a raw
  // control character that would visually render as if it overwrote preceding text. No legitimate field
  // value is remotely this long, so this bound is never reached by real, even malformed-but-ordinary,
  // content. Escapes BEFORE truncating (not after), so the final message length stays bounded regardless of
  // how much a run of control characters would otherwise expand under escaping.
  static final int MAX_MESSAGE_VALUE_LENGTH = 100;

  @Nullable
  static String truncateAndEscapeForMessage(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String escaped = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    if (escaped.length() <= MAX_MESSAGE_VALUE_LENGTH) {
      return escaped;
    }
    // Back off the cut by one character if it would otherwise land right after a backslash: every \r/\n/\t
    // this method itself just inserted is exactly two characters, so cutting between the backslash and its
    // following r/n/t leaves a stray, meaningless trailing backslash in the truncated message. A backslash
    // already present in the original text (not one of the three this method inserts) is treated the same
    // way out of caution, even though splitting it wouldn't actually mislead a reader -- it's already just a
    // single literal character, not a two-character unit that could be torn in half. Found by the Phase 2
    // review's fourth re-verification round: the original substring(0, MAX_MESSAGE_VALUE_LENGTH) cut on the
    // already-escaped string, with no such adjustment, so a run of raw CRs crossing the length boundary at an
    // odd offset produced a message ending in a lone "\" with its r/n/t cut off.
    int cut = MAX_MESSAGE_VALUE_LENGTH;
    if (escaped.charAt(cut - 1) == '\\') {
      cut--;
    }
    return escaped.substring(0, cut) + "... (truncated, " + escaped.length() + " chars total)";
  }

  /**
   * Sanitizes the {@code String}-typed elements of {@code args} for embedding in a thrown {@link
   * VcfFormatException}'s message, by running each through {@link #truncateAndEscapeForMessage}; every
   * other element (a line number, an already-bounded enum/version value, etc.) passes through unchanged.
   * Shared by all three warn-or-throw implementations' throw branch -- {@link BaseMetadata#warnOrThrow},
   * {@code VcfMetadata}'s own private static {@code warnOrThrow(VcfContext, ...)}, and this class's own
   * {@link #warnOrThrow} below -- so this one fix reaches every one of their many callers at once, instead
   * of requiring each call site to sanitize its own args before formatting.
   * <p>
   * Deliberately does not touch the warn (lenient) branch of any of the three: the exception-message
   * hardening this method supports (unbounded length, raw control characters that could corrupt a
   * caller's rendered output) was scoped to thrown messages specifically; the logged-warning path is a
   * separate concern this method isn't trying to address.
   */
  static Object[] sanitizeArgsForException(Object... args) {
    Object[] sanitized = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      sanitized[i] = args[i] instanceof String ? truncateAndEscapeForMessage((String) args[i]) : args[i];
    }
    return sanitized;
  }

  private static final Set<String> RESERVED_METADATA_KEYS =
      Set.of("fileformat", "ALT", "FILTER", "INFO", "FORMAT", "contig", "SAMPLE", "PEDIGREE", "META");

  /**
   * @return true if {@code name} is one of the structured metadata declaration keywords (e.g. "INFO",
   * "FORMAT") that {@link VcfParser#parseMetadata} routes to its own structured-parsing path under {@code
   * version} -- a raw property using one of these names could never be re-read as what it was written, since
   * reparsing would route it through the wrong path entirely. "META" is excluded below {@link VcfVersion#V4_3},
   * since {@code ##META} does not exist before VCFv4.3 (see {@code VcfParser.parseMetadata}'s own "META" case)
   * and a raw "META" property there reparses as itself, not as a structured declaration.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean isReservedMetadataKey(@Nullable String name, VcfVersion version) {
    if (name == null) {
      return false;
    }
    if (name.equals("META") && !version.isAtLeast(VcfVersion.V4_3)) {
      return false;
    }
    return RESERVED_METADATA_KEYS.contains(name);
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
   * @param context the record's resolved {@link VcfContext}. Its {@link VcfContext#getVersion() version} is read
   * here to reject {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE} under VCFv4.3+ (see
   * below), and passed on to {@link #checkReservedFormatConstraints}/{@link #checkReservedInfoConstraints} for
   * their own version-gated rules. Any such check must dispatch via {@code version.isAtLeast(VcfVersion.V4_3)}
   * (see {@link VcfVersion#isAtLeast}), never a string or ordinal comparison on {@link VcfVersion}. Its
   * {@link VcfContext#isLenient() leniency} gates the general 32-bit range check below.
   * @return {@code null}, after warning, when {@code key} is
   * {@link ReservedFormatProperty#GenotypeLikelihoodsOfHeterogenousPloidy GLE} and {@code version} is VCFv4.3 or
   * later: VCFv4.3 removed that key (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT tag"), so
   * there is no reserved property to convert against. Also {@code null}, after an analogous warning, when
   * {@code key} is one of the five FORMAT keys VCFv4.3 added ({@link ReservedFormatProperty#isAddedAtV4_3}), or
   * one of the three INFO keys VCFv4.3 added ({@link ReservedInfoProperty#isAddedAtV4_3}), and {@code version}
   * predates VCFv4.3 -- the mirror-image case, where the reserved property does not exist <em>yet</em> rather
   * than no longer existing. Warn-and-{@code null} rather than throwing in all three cases, because the only
   * public routes in are {@link VcfSample#getProperty(ReservedFormatProperty)} and
   * {@link VcfPosition#getInfo(ReservedInfoProperty)}, lookup-style accessors whose {@code String}-keyed
   * siblings are null-tolerant "not present" (AGENTS.md's Public API Null-Safety Policy); silence would be
   * indistinguishable from an absent value.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static @Nullable <T> T convertProperty(ReservedProperty key, @Nullable String value, VcfContext context) {
    VcfVersion version = context.getVersion();
    VcfWarnings warnings = context.getWarnings();
    // isLenient()-aware, shared by every reserved-key content check below (checkReservedFormatConstraints/
    // checkReservedInfoConstraints and, further down, the general 32-bit range check): null means strict
    // (throws VcfFormatException), non-null means lenient (warns to it) -- the same null-means-strict internal
    // convention VcfPosition's own content checks already use. Never fires differently for a parsed file
    // (VcfParser only ever builds a lenient VcfContext), only for a caller who manually builds a strict-context
    // position/sample and then reads a bad value.
    VcfWarnings checkWarnings = context.isLenient() ? warnings : null;
    // FT/PS are FORMAT-only reserved IDs, and AC/AN/DP/END/MQ0/NS/AF are INFO-only reserved IDs (see each
    // check's own Javadoc) -- a future reserved key on the other side whose ID happens to collide must not get
    // the wrong side's semantics applied
    String normalized = value;
    // Also drives the range-check label below, so a key reserved on both sides (e.g. DP) doesn't produce an
    // ambiguous warning -- matches the "INFO "/"FORMAT " prefix every sibling call site already uses
    // (VcfPosition, VcfSample, VcfWriter).
    String rangeCheckLabelPrefix = "";
    if (key instanceof ReservedFormatProperty) {
      // Placed here, with the FORMAT dispatch, rather than in VcfSample.getProperty(ReservedFormatProperty):
      // that method delegates to this one, so one check here covers both routes, and this method is itself
      // public, so a guard there alone would leave this route open.
      rangeCheckLabelPrefix = "FORMAT ";
      if (key == ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy &&
          version.isAtLeast(VcfVersion.V4_3)) {
        warnings.warn("FORMAT GLE is not a reserved key under VCF {} (VCFv4.3 removed it); ignoring this " +
            "reserved-property read -- use VcfSample.getProperty(String) for the raw value, or " +
            "getProperty(VcfPosition, VcfMetadata, String) to interpret it against its own ##FORMAT declaration",
            version.getId());
        return null;
      }
      // The mirror image of the GLE check just above: a caller already holds one of the five FORMAT
      // constants VCFv4.3 added (see ReservedFormatProperty.isAddedAtV4_3's own comment), so
      // ReservedFormatProperty.fromId(String, VcfVersion) is never consulted here -- this needs its own
      // check, the same reason the GLE check above cannot rely on fromId alone either.
      if (ReservedFormatProperty.isAddedAtV4_3((ReservedFormatProperty) key) && !version.isAtLeast(VcfVersion.V4_3)) {
        warnings.warn("FORMAT {} is not a reserved key under VCF {} (VCFv4.3 introduced it); ignoring this " +
            "reserved-property read -- use VcfSample.getProperty(String) for the raw value, or " +
            "getProperty(VcfPosition, VcfMetadata, String) to interpret it against its own ##FORMAT declaration",
            key.getId(), version.getId());
        return null;
      }
      normalized = checkReservedFormatConstraints(key.getId(), value, version, checkWarnings);
    } else if (key instanceof ReservedInfoProperty) {
      rangeCheckLabelPrefix = "INFO ";
      // The INFO-side mirror of the FORMAT isAddedAtV4_3 check above: a caller already holds one of the three
      // reserved INFO constants VCFv4.3 added (see ReservedInfoProperty.ADDED_AT_V4_3's own comment), so
      // ReservedInfoProperty.fromId(String, VcfVersion) is never consulted here -- this needs its own check,
      // the same reason the FORMAT branch's own check above can't rely on fromId alone either.
      if (ReservedInfoProperty.isAddedAtV4_3((ReservedInfoProperty) key) && !version.isAtLeast(VcfVersion.V4_3)) {
        warnings.warn("INFO {} is not a reserved key under VCF {} (VCFv4.3 introduced it); ignoring this " +
            "reserved-property read -- use VcfPosition.getInfo(String) for the raw value, or " +
            "getInfo(VcfMetadata, String) to interpret it against its own ##INFO declaration",
            key.getId(), version.getId());
        return null;
      }
      checkReservedInfoConstraints(key.getId(), value, version, checkWarnings);
    }
    // The general 32-bit range check, gated on the key's own declared Type -- see checkInteger32BitRange's
    // own Javadoc for why FORMAT/PS is deliberately not excluded. Reuses the same checkWarnings computed above.
    if (normalized != null && key.getType() == Long.class) {
      checkInteger32BitRange(rangeCheckLabelPrefix + key.getId(), normalized, key.isList(), checkWarnings);
    }
    return convertProperty(key.getType(), normalized, key.isList(), warnings, checkWarnings);
  }

  /**
   * Warns or throws if {@code value} violates a constraint a target VCF specification version places on a
   * reserved FORMAT key beyond its declared {@code Type} -- constraints that generic {@code Type}-based
   * conversion can't express. Currently:
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
   *   <li>{@link ReservedFormatProperty#CopyNumberPosteriorProbabilities CNP} must also lie in [0, 1] --
   *   VCFv4.3.tex line 689 ("CNP is 0 to 1-scaled copy number posterior probabilities") and the 4.3 changelog
   *   at line 2156 ("Both CNP and GP use 0 to 1 encoding") pair it with GP explicitly. Gated to {@code >=4.3},
   *   the same version CNP is reserved from at all (see
   *   {@link ReservedFormatProperty#CopyNumberPosteriorProbabilities CNP}'s own comment) -- a pre-4.3 custom
   *   key literally named CNP has no such reserved meaning and is not checked here. Deliberately NOT extended
   *   to {@link ReservedFormatProperty#CopyNumberLikelihood CNL}: that key is a list of log10 likelihoods, not
   *   a 0-1 probability, per its own body-text sentence two lines above CNP's in the same table
   *   ("CNL specifies a list of log10 likelihoods... starting from zero").</li>
   *   <li>{@link ReservedFormatProperty#AlleleDepth AD}/{@link ReservedFormatProperty#AlleleDepthForward ADF}/
   *   {@link ReservedFormatProperty#AlleleDepthReverse ADR}/{@link ReservedFormatProperty#AncestralHaplotypeId
   *   AHAP}/{@link ReservedFormatProperty#CopyNumber CN}/{@link ReservedFormatProperty#Depth DP}/
   *   {@link ReservedFormatProperty#ExpectedAlleleCounts EC}/
   *   {@link ReservedFormatProperty#GenotypeQualityConditional GQ}/{@link ReservedFormatProperty#HaplotypeId
   *   HAP}/{@link ReservedFormatProperty#HaplotypeQualities HQ}/{@link ReservedFormatProperty#MappingQuality
   *   MQ}/{@link ReservedFormatProperty#PhredScoreForNovelty NQ}/
   *   {@link ReservedFormatProperty#PhredScaledGenotypeLikelihoods PL}/{@link ReservedFormatProperty#PhasingQuality
   *   PQ} must each be a non-negative integer -- the FORMAT-side mirror of
   *   {@link ReservedInfoProperty}'s own identical set (see {@link #checkReservedInfoConstraints}'s Javadoc),
   *   sourced from the same authority (vcf_validator 0.10.2's shared {@code check_field_integer_range}, applied
   *   identically to its {@code format_v43} table). {@link ReservedFormatProperty#PhaseSet PS} is deliberately
   *   excluded despite being Integer-typed in that same table: it already has its own, stricter
   *   {@link #checkNonNegative32BitInteger} call. {@code AD}/{@code ADF}/{@code ADR} are further gated to
   *   {@code >=4.3}, the same version they are reserved from at all -- the other eleven are reserved at every
   *   version this library targets.</li>
   * </ul>
   *
   * @return the value to use for subsequent type conversion: unchanged, except for FT under a lenient
   * {@code checkWarnings}, whose empty semicolon-separated codes are dropped -- the same "warn and drop"
   * convention already used for an empty entry in FILTER (FT's grammar is explicitly modeled on FILTER's) and
   * every other position-independent list in this library. If every code was dropped this way, returns
   * {@code "."} (this library's established missing-value sentinel) rather than an empty string, matching
   * every other "reduces to nothing" case in this library. Under a strict (null) {@code checkWarnings}, an
   * empty code throws instead of being dropped, so this return value is never reached in that case. A caller
   * that only wants the validation side effect (e.g. a writer in diagnostics mode, which preserves raw content
   * rather than rewriting it) may discard the return value.
   *
   * @param version the record's resolved VCF version, passed as its own argument -- never a {@link VcfContext} --
   * so there is nothing here to read a version off but the value the caller explicitly supplied. See
   * {@link #convertProperty(ReservedProperty, String, VcfContext)}'s own {@code context} doc for the
   * {@code isAtLeast} dispatch idiom this method's GP branch uses.
   * @param checkWarnings {@code null} means strict (throws {@link VcfFormatException} on the first violation
   * found); non-null means lenient (warns to it and keeps checking) -- the same null-means-strict convention
   * {@link #checkInteger32BitRange} and {@link VcfPosition}'s own content checks already use. A caller that
   * always wants a warning regardless of the record's own strict/lenient origin (e.g. {@link VcfWriter}'s
   * always-warn diagnostics mode) passes a non-null instance unconditionally.
   */
  static @Nullable String checkReservedFormatConstraints(String key, @Nullable String value, VcfVersion version,
      @Nullable VcfWarnings checkWarnings) {
    if (value == null || value.equals(".")) {
      return value;
    }
    if (key.equals(ReservedFormatProperty.Filter.getId())) {
      return checkFilterLikeValue(key, value, checkWarnings);
    } else if (key.equals(ReservedFormatProperty.PhaseSet.getId())) {
      checkNonNegative32BitInteger(key, value, checkWarnings);
    } else if (key.equals(ReservedFormatProperty.GenotypePosterior.getId()) && version.isAtLeast(VcfVersion.V4_3)) {
      // per-element, not a scalar parse of the whole comma-delimited value: GP is Number=G and list-shaped, but
      // both callers hand this method the entire value before any splitting of their own -- VcfWriter's
      // validateFormatValue calls splitFormatValue one line after its own checkReservedFormatConstraints call,
      // and convertProperty(ReservedProperty, ...) doesn't split at all here, only later, inside the generic
      // convertProperty(Class<?>, ...) it calls next. A scalar BigDecimal parse of e.g. "0.2,5.0,0.8" would throw
      // and be treated as "not a number at all" (see checkUnitInterval), silently skipping the whole check.
      checkEachCommaElement(value, element -> checkUnitInterval("FORMAT", key, element, checkWarnings));
    } else if (key.equals(ReservedFormatProperty.CopyNumberPosteriorProbabilities.getId()) &&
        version.isAtLeast(VcfVersion.V4_3)) {
      // version-gated, unlike this branch's own comment previously claimed: CNP has no VCFv4.2 declaration at
      // all (see ReservedFormatProperty.ADDED_AT_V4_3's own comment), so a pre-4.3 file's own custom key named
      // CNP carries its own declared meaning, not this reserved [0, 1] range -- the write-path mirror of
      // convertProperty(ReservedProperty, ...)'s own isAddedAtV4_3 guard, which already skips this same check
      // on the read side for exactly this reason. Reachable here even for a DECLARED CNP (unlike the
      // undeclared-key fallback in VcfWriter.validateSamples, which is already version-gated one level up via
      // ReservedFormatProperty.fromId(key, recordVersion)): this method is also called for a key with its own
      // ##FORMAT=<ID=CNP,...> declaration, which carries no version information of its own to consult.
      checkEachCommaElement(value, element -> checkUnitInterval("FORMAT", key, element, checkWarnings));
    } else if (NON_NEGATIVE_INTEGER_FORMAT_KEYS.contains(key) && ReservedFormatProperty.fromId(key, version) != null) {
      // ReservedFormatProperty.fromId(key, version) != null: the FORMAT-side mirror of
      // checkReservedInfoConstraints's identical AD/ADF/ADR gate just above -- three of these fourteen keys
      // (AD/ADF/ADR) have no VCFv4.2 declaration at all, so without this gate a pre-4.3 file's own custom
      // ##FORMAT=<ID=AD,...> with a negative value would be spuriously flagged the same way CNP's [0, 1]
      // range check was before this round's fix. For the other eleven keys (reserved at every version this
      // library targets), fromId is unaffected by version and this check is unconditionally true.
      checkEachCommaElement(value, element -> checkNonNegativeInteger("FORMAT", key, element, checkWarnings));
    }
    return value;
  }

  /**
   * Splits {@code value} on {@code ","} (allowing empty elements, matching every caller's own
   * {@code split(",", -1)}) and runs {@code check} against each element in order -- shared by
   * {@link #checkReservedFormatConstraints}'s {@code GP} and {@code CNP} branches and
   * {@link #checkReservedInfoConstraints}'s three branches, which otherwise each independently repeated this
   * same split-and-check loop, differing only in which single-element validator they invoke.
   */
  private static void checkEachCommaElement(String value, Consumer<String> check) {
    for (String element : value.split(",", -1)) {
      check.accept(element);
    }
  }

  private static String checkFilterLikeValue(String key, String value, @Nullable VcfWarnings checkWarnings) {
    String[] codes = value.split(";", -1);
    List<String> kept = new ArrayList<>(codes.length);
    boolean hasPassOrMissing = false;
    for (String code : codes) {
      if (code.isEmpty()) {
        // deliberately not warnOrThrow: unlike this method's other two checks, the two branches' messages
        // genuinely differ (lenient appends "; dropping it", since only that branch actually drops the code),
        // so a single shared pattern can't express both
        String message = "FORMAT " + key + " value \"" + value + "\" contains an empty filter code (VCF does " +
            "not allow zero-length fields)";
        if (checkWarnings != null) {
          checkWarnings.warn(message + "; dropping it");
        } else {
          // see VcfPosition.checkChromosome's identical non-printable-ASCII check for why this wraps the
          // whole built message (bounding/escaping the thrown form only, not the warn above)
          throw new VcfFormatException(truncateAndEscapeForMessage(message));
        }
        continue;
      }
      if (containsWhitespace(code)) {
        warnOrThrow(checkWarnings, "FORMAT {} value \"{}\" contains whitespace in filter code \"{}\"", key, value,
            code);
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
      warnOrThrow(checkWarnings, "FORMAT {} value \"{}\" combines PASS or '.' with other filter codes", key, value);
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

  private static void checkNonNegative32BitInteger(String key, String value, @Nullable VcfWarnings checkWarnings) {
    long parsed;
    try {
      parsed = Long.parseLong(value);
    } catch (NumberFormatException e) {
      // not a number at all; the generic Long conversion reports this on its own
      return;
    }
    if (parsed < 0 || parsed > Integer.MAX_VALUE) {
      warnOrThrow(checkWarnings, "FORMAT {} value \"{}\" is not a non-negative 32-bit integer", key, value);
    }
  }

  private static final long RESERVED_SENTINEL_LOW = Integer.MIN_VALUE;
  private static final long RESERVED_SENTINEL_HIGH = Integer.MIN_VALUE + 7L;

  /**
   * Warns or throws if {@code parsed} violates VCFv4.3.tex's general 32-bit Integer constraint --
   * {@code VCFv4.3.tex:100} ("Integer (32-bit, signed)") and {@code :103} ("the values from -2^31 to
   * -2^31+7 cannot be stored in the binary version and therefore are disallowed in both VCF and BCF");
   * identical text at {@code VCFv4.4.tex:100}/{@code :103}, shifted to {@code VCFv4.5.tex:98}/{@code :101}.
   * {@code VCFv4.2.tex} states no general bound (only {@link #checkNonNegative32BitInteger}'s narrower,
   * {@code PS}-specific rule at {@code VCFv4.2.tex:230}, whose PS entry ends "(Non-negative 32-bit
   * Integer)") -- this check is applied unconditionally regardless
   * of declared version anyway, since it is not a case where the *meaning* of Integer genuinely differs
   * across versions, only whether the spec bothered to state it.
   * <p>
   * Two spec-distinct problems, both from the same {@code :103} sentence, get distinct messages: a plain
   * magnitude violation (not representable as any 32-bit signed integer at all), and a value that IS
   * representable but collides with one of the eight BCF-reserved sentinel bit patterns
   * ({@code VCFv4.3.tex:1708}/{@code :1710}: {@code 0x80000000}-{@code 0x80000007}, interpreted as a BCF
   * missing-value sentinel, an END_OF_VECTOR marker, or reserved for future use).
   * <p>
   * Deliberately not excluded for {@code FORMAT/PS}: its own {@link #checkNonNegative32BitInteger} already
   * flags every value this check would too (any negative value, which covers every reserved sentinel and
   * any underflow) -- the only overlap is a {@code PS} value that is itself a reserved sentinel or overflows
   * past {@code Integer.MAX_VALUE}. Under a lenient {@code VcfContext} this produces two warnings for one
   * input, accepted, matching this codebase's existing "multiple independent checks warn separately about the
   * same defect" pattern (see {@code WONT_FIX.md}). Under a strict one, {@link #checkNonNegative32BitInteger}
   * runs first (see {@link #convertProperty(ReservedProperty, String, VcfContext)}'s dispatch order) and
   * throws before this check ever runs, so the caller sees only its "is not a non-negative 32-bit integer"
   * message, never this one's more specific "collides with a BCF-reserved sentinel value" -- both are true of
   * the same value, and first-violation-wins matches every other check in this class.
   *
   * @param checkWarnings {@code null} means strict (throws {@link VcfFormatException}); non-null means
   * lenient (warns to it) -- the same null-means-strict internal convention {@link VcfPosition}'s own content
   * checks already use.
   */
  static void checkInteger32BitRange(String label, long parsed, @Nullable VcfWarnings checkWarnings) {
    if (parsed > Integer.MAX_VALUE || parsed < Integer.MIN_VALUE) {
      warnOrThrow(checkWarnings, "{} value \"{}\" is outside the 32-bit signed range", label, parsed);
    } else if (parsed >= RESERVED_SENTINEL_LOW && parsed <= RESERVED_SENTINEL_HIGH) {
      warnOrThrow(checkWarnings, "{} value \"{}\" collides with a BCF-reserved sentinel value and must not be " +
          "used", label, parsed);
    }
  }

  /**
   * {@link #checkInteger32BitRange(String, long, VcfWarnings)}, absorbing the split-on-comma loop every one of
   * its five call sites previously hand-copied: gate on a {@code Long}-typed field, split {@code rawValue} on
   * {@code ,} when it is list-shaped, parse each element, check it, and silently swallow a
   * {@link NumberFormatException} (the generic {@code Type} conversion each caller runs right after already
   * reports "not a number at all" on its own). {@code label} is the only input that ever varied between the
   * five copies -- it drifted at one of them for several fix-loop rounds during the plan that first added
   * this check, since no single call site's review saw two copies at once to notice.
   *
   * @param isList whether {@code rawValue} is a comma-joined list (the caller's own {@code key.isList()}/
   * declared-{@code Number} decision) or a single scalar value already isolated by the caller (e.g.
   * {@link VcfWriter}'s two write-side callers, which iterate their own already-split value list one element
   * at a time and always pass {@code false} here).
   * @param checkWarnings see {@link #checkInteger32BitRange(String, long, VcfWarnings)}'s own doc.
   */
  static void checkInteger32BitRange(String label, String rawValue, boolean isList, @Nullable VcfWarnings checkWarnings) {
    String[] elements = isList ? rawValue.split(",", -1) : new String[] {rawValue};
    for (String element : elements) {
      try {
        checkInteger32BitRange(label, Long.parseLong(element), checkWarnings);
      } catch (NumberFormatException e) {
        // not a number at all; the generic Long conversion the caller runs right after reports this on its own
      }
    }
  }

  /**
   * Warns to {@code checkWarnings}, using SLF4J placeholder-style {@code pattern}/{@code args}, if it is
   * non-null (lenient); throws {@link VcfFormatException} with the same formatted message if it is {@code null}
   * (strict), after running {@code args} through {@link #sanitizeArgsForException} so a {@code String}-typed
   * arg can't blow up the thrown message's length or embed a raw control character. A third implementation
   * of the same shape, alongside {@link BaseMetadata#warnOrThrow} and {@code VcfMetadata}'s own private
   * static {@code warnOrThrow(VcfContext, ...)} -- all three use the identical warn-or-throw-with-{@code
   * MessageFormatter.arrayFormat} pattern (and now the identical sanitize-before-throw step), but this one
   * takes an already-resolved {@code checkWarnings} value directly rather than a {@code VcfContext}, because
   * every caller here already made that strict-vs-lenient decision before reaching this method (see
   * {@link #convertProperty(ReservedProperty, String, VcfContext)}'s own {@code checkWarnings} derivation) --
   * unlike the other two, which each hold their own {@code VcfContext}/{@code m_context} field to derive
   * strictness from directly. That difference is what lets {@link VcfWriter} force always-warn independently
   * of the written object's own context by simply never passing {@code null} here.
   */
  private static void warnOrThrow(@Nullable VcfWarnings checkWarnings, String pattern, Object... args) {
    if (checkWarnings != null) {
      checkWarnings.warn(pattern, args);
    } else {
      throw new VcfFormatException(MessageFormatter.arrayFormat(pattern, sanitizeArgsForException(args)).getMessage());
    }
  }

  // AC/AN/DP/END/MQ0/NS's declared Long Type accepts any value Long.parseLong parses, including a negative one --
  // vcf_validator 0.10.2 rejects a negative value for each of these ("value must be a non-negative integer
  // number"; confirmed directly against test/vcf/4.2/failed/failed_body_info_001.vcf (AC), _006.vcf (AN),
  // _013.vcf (DP), _016.vcf (END), _021.vcf (MQ0), _023.vcf (NS)); bcftools 1.23.1 is silent on all six (it never
  // validates reserved-key semantics, only structural presence). Neither VCFv4.2.tex nor the VCFv4.3.tex target
  // tables this constraint directly, but each key's own reserved-key description implies a count/depth/position
  // can't be negative.
  //
  // AD/ADF/ADR/CN/CNADJ/HOMLEN/DPADJ (found by the Phase 2 review's third re-verification round): same
  // reasoning, same authority -- vcf_validator 0.10.2 gives the identical "value must be a non-negative
  // integer number" message for a negative value of each, confirmed live (no hts-specs fixture covers these
  // seven; the probe used the same undeclared-key shape the original six were sourced from, since
  // vcf_validator only applies its reserved-key semantic checks when the key has no ##INFO declaration of its
  // own). bcftools 1.23.1 is silent on all seven, same as the original six. Spec citations for "a depth/copy
  // number/length can't be negative": AD/ADF/ADR -- VCFv4.3.tex:397-399, "Total read depth for each allele" /
  // "Read depth for each allele on the forward/reverse strand"; CN/CNADJ -- VCFv4.3.tex:665-666, "Copy number
  // of segment containing breakend" / "Copy number of adjacency"; HOMLEN -- VCFv4.3.tex:644, "Length of base
  // pair identical micro-homology at event breakpoints"; DPADJ -- VCFv4.3.tex:664, "Read Depth of adjacency".
  // CICN (a sibling reserved key, confidence interval around copy number) is deliberately NOT included:
  // vcf_validator gives two mutually-contradictory rules for it (this same non-negative-integer rule, plus a
  // separate confidence-interval-shape rule satisfied only by a first value <= 0) -- looks like a
  // vcf_validator defect, not a clean signal, so it needs its own separate, more careful decision.
  private static final Set<String> NON_NEGATIVE_INTEGER_INFO_KEYS = Set.of(
      ReservedInfoProperty.AlleleCount.getId(), ReservedInfoProperty.AlleleNumber.getId(),
      ReservedInfoProperty.Depth.getId(), ReservedInfoProperty.End.getId(),
      ReservedInfoProperty.MappingQualityZeroCount.getId(), ReservedInfoProperty.NumberOfSamples.getId(),
      ReservedInfoProperty.AlleleDepth.getId(), ReservedInfoProperty.AlleleDepthForward.getId(),
      ReservedInfoProperty.AlleleDepthReverse.getId(), ReservedInfoProperty.CopyNumberOfSegment.getId(),
      ReservedInfoProperty.CopyNumberOfAdjacency.getId(), ReservedInfoProperty.HomologyLength.getId(),
      ReservedInfoProperty.ReadDepthOfAdjacency.getId());

  /**
   * Warns or throws if {@code value} violates a constraint that a reserved INFO key's own description implies
   * beyond its declared {@code Type} -- constraints that generic {@code Type}-based conversion can't express.
   * Currently:
   * <ul>
   *   <li>{@link ReservedInfoProperty#AlleleCount AC}, {@link ReservedInfoProperty#AlleleNumber AN},
   *   {@link ReservedInfoProperty#Depth DP}, {@link ReservedInfoProperty#End END},
   *   {@link ReservedInfoProperty#MappingQualityZeroCount MQ0}, {@link ReservedInfoProperty#NumberOfSamples NS},
   *   {@link ReservedInfoProperty#AlleleDepth AD}, {@link ReservedInfoProperty#AlleleDepthForward ADF},
   *   {@link ReservedInfoProperty#AlleleDepthReverse ADR}, {@link ReservedInfoProperty#CopyNumberOfSegment CN},
   *   {@link ReservedInfoProperty#CopyNumberOfAdjacency CNADJ}, {@link ReservedInfoProperty#HomologyLength HOMLEN},
   *   and {@link ReservedInfoProperty#ReadDepthOfAdjacency DPADJ} must each be a non-negative integer.</li>
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
   * {@link #convertProperty(ReservedProperty, String, VcfContext)}'s own {@code context} doc for the
   * {@code isAtLeast} dispatch idiom a future version-gated rule here must use. Not yet read by this method.
   * @param checkWarnings {@code null} means strict (throws {@link VcfFormatException} on the first violation
   * found); non-null means lenient (warns to it and keeps checking every remaining element) -- the same
   * null-means-strict convention {@link #checkInteger32BitRange} and {@link VcfPosition}'s own content checks
   * already use. A caller that always wants a warning regardless of the record's own strict/lenient origin
   * (e.g. {@link VcfWriter}'s always-warn diagnostics mode) passes a non-null instance unconditionally.
   */
  static void checkReservedInfoConstraints(String key, @Nullable String value, VcfVersion version,
      @Nullable VcfWarnings checkWarnings) {
    if (value == null || value.equals(".")) {
      return;
    }
    // ReservedInfoProperty.fromId(key, version) != null: AD/ADF/ADR are among NON_NEGATIVE_INTEGER_INFO_KEYS
    // but have no VCFv4.2 declaration at all (ReservedInfoProperty.isAddedAtV4_3) -- this method is also
    // reached for a DECLARED key (VcfWriter.validateInfoValue's "declared" branch carries no version-gated
    // fallback of its own the way the undeclared-key branch does), so without this gate a pre-4.3 file's own
    // custom ##INFO=<ID=AD,...> with a negative value would be spuriously flagged even though AD isn't
    // reserved at all under that version. For the other ten keys here (reserved at every version this library
    // targets), fromId(key, version) is unaffected by version and this check is unconditionally true, so this
    // adds no gate for them. Found by the Phase 2 review's fourth re-verification round, the same shape as
    // checkReservedFormatConstraints's own CNP write-path gap this round also fixed.
    if (NON_NEGATIVE_INTEGER_INFO_KEYS.contains(key) && ReservedInfoProperty.fromId(key, version) != null) {
      checkEachCommaElement(value, element -> checkNonNegativeInteger("INFO", key, element, checkWarnings));
    } else if (key.equals(ReservedInfoProperty.AlleleFrequency.getId())) {
      checkEachCommaElement(value, element -> checkUnitInterval("INFO", key, element, checkWarnings));
    } else if (key.equals(ReservedInfoProperty.Cigar.getId())) {
      checkEachCommaElement(value, element -> checkCigarGrammar(element, checkWarnings));
    }
    // TODO: SVLEN and CN (INFO's CopyNumberOfSegment) each gain a 4.4+-only reserved-key Number/Type change
    // not modeled here (VCFv4.4.tex changelog line 2575, "Redefined Number= for SVLEN, ... CN, ... to support
    // multiple symbolic alleles"); END keeps Number=1 but gains a 4.4+-only meaning change (line 2576,
    // "Redefined END as the end position of the longest ALT allele"). None of this is reachable while
    // VcfVersion.HIGHEST_IMPLEMENTED stays V4_3 -- deferred to the version-aware reserved-key tables TODO.md's
    // "Investigate bumping the target VCF spec to 4.4 or 4.5" entry already tracks as the next structural
    // change for this method.
  }

  // fieldType is the literal "INFO" or "FORMAT" prefix for the warning message -- see checkUnitInterval's
  // identical parameter for why (shared by both INFO's and FORMAT's own non-negative-integer key sets below).
  private static void checkNonNegativeInteger(String fieldType, String key, String value,
      @Nullable VcfWarnings checkWarnings) {
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
      warnOrThrow(checkWarnings, "{} {} value \"{}\" is a negative number", fieldType, key, value);
    }
  }

  // AD/ADF/ADR/AHAP/CN/DP/EC/GQ/HAP/HQ/MQ/NQ/PL/PQ: the FORMAT-side mirror of NON_NEGATIVE_INTEGER_INFO_KEYS
  // below -- vcf_validator 0.10.2's check_field_integer_range (record.cpp) is the same shared function called
  // from both check_predefined_tag_info and check_predefined_tag_format, applied to every Integer-typed key in
  // its format_v43 table (file_structure.hpp) with no per-key exemption (the INFO-side exemption list --
  // SVLEN/CIPOS/CIEND/CILEN/CIRB/CICNADJ<4.4 -- names no FORMAT key). Confirmed live: an undeclared FORMAT key
  // named AD/DP/GQ/CN with a negative sample value is flagged "value must be a non-negative integer number"
  // for each; bcftools 1.23.1 is silent, same as the INFO-side seven. PS is deliberately excluded from this
  // set despite being Integer-typed in the same table: it already has its own, stricter
  // checkNonNegative32BitInteger call (also bounds-checks against Integer.MAX_VALUE, a historical special case
  // that predates this generic mechanism) -- adding it here too would double-warn under a lenient context for
  // the identical negative-value case. Found by the Phase 2 review's fourth re-verification round.
  private static final Set<String> NON_NEGATIVE_INTEGER_FORMAT_KEYS = Set.of(
      ReservedFormatProperty.AlleleDepth.getId(), ReservedFormatProperty.AlleleDepthForward.getId(),
      ReservedFormatProperty.AlleleDepthReverse.getId(), ReservedFormatProperty.AncestralHaplotypeId.getId(),
      ReservedFormatProperty.CopyNumber.getId(), ReservedFormatProperty.Depth.getId(),
      ReservedFormatProperty.ExpectedAlleleCounts.getId(), ReservedFormatProperty.GenotypeQualityConditional.getId(),
      ReservedFormatProperty.HaplotypeId.getId(), ReservedFormatProperty.HaplotypeQualities.getId(),
      ReservedFormatProperty.MappingQuality.getId(), ReservedFormatProperty.PhredScoreForNovelty.getId(),
      ReservedFormatProperty.PhredScaledGenotypeLikelihoods.getId(), ReservedFormatProperty.PhasingQuality.getId());

  // fieldType is the literal "INFO" or "FORMAT" prefix for the warning message -- not VCF content, just which
  // of checkReservedInfoConstraints's (AF) or checkReservedFormatConstraints's (GP under 4.3+, or CNP
  // unconditionally) three callers this is, matching every other field-specific message in this class (e.g.
  // checkNonNegativeInteger's own hardcoded "INFO", checkNonNegative32BitInteger's own hardcoded "FORMAT")
  private static void checkUnitInterval(String fieldType, String key, String value,
      @Nullable VcfWarnings checkWarnings) {
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
      warnOrThrow(checkWarnings, "{} {} value \"{}\" does not lie in the interval [0, 1]", fieldType, key, value);
    }
  }

  private static void checkCigarGrammar(String value, @Nullable VcfWarnings checkWarnings) {
    if (value.isEmpty() || value.equals(".")) {
      // an empty entry or the missing-value sentinel is reported elsewhere; not this check's concern
      return;
    }
    if (!CIGAR_PATTERN.matcher(value).matches()) {
      warnOrThrow(checkWarnings, "INFO CIGAR value \"{}\" does not match the expected CIGAR grammar (a lone " +
          "'*', or one or more <length><operation> pairs, operation one of MIDNSHPX=)", value);
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
   *   genotype-ordering {@code "G"} is defined from. {@code CNP}'s exemption is gated on {@code >=4.3}
   *   (the same version CNP is reserved from at all -- {@link ReservedFormatProperty} has no VCFv4.2
   *   declaration for it), but not {@code number}-gated within that: from 4.3 on, a caller-declared override
   *   of that {@code Number} doesn't change what a CNP value structurally means, so the exemption applies to
   *   the key outright once it is actually the reserved key. Below 4.3, a custom key named CNP is an ordinary
   *   custom key with no special meaning and gets the ordinary check instead. {@code CNL}'s exemption is
   *   <b>also</b> gated on {@code number} actually being {@code "G"}, in addition to its own {@code >=4.3}
   *   version gate: {@code CNL} is only reserved with {@code Number=G} from VCFv4.3 on (VCFv4.2.tex:308
   *   declares it {@code Number=.}; VCFv4.3.tex:678, the target, redeclares it {@code Number=G} -- see
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
    // version-gated, unlike this method's own Javadoc previously claimed: CNP has no VCFv4.2 declaration at
    // all (ReservedFormatProperty.ADDED_AT_V4_3), so a pre-4.3 file's own custom key named CNP is an ordinary
    // custom key with no special structural meaning -- its cardinality (if it declares Number=G at all) must
    // get the same ordinary genotype-ordering check any other custom key would, not this exemption.
    return version.isAtLeast(VcfVersion.V4_3) &&
        key.equals(ReservedFormatProperty.CopyNumberPosteriorProbabilities.getId());
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
  // same way as the GP field"); VCFv4.2.tex has no PP entry at all (PP was introduced by VCFv4.3, see
  // ReservedFormatProperty.ADDED_AT_V4_3's own comment) -- unlike GL/PL/GP, which are reserved at every version
  // this library targets, PP's default applies only from 4.3 on; a pre-4.3 custom key literally named PP is an
  // ordinary custom key with no such spec-stated default and gets the ordinary (no-ploidy-assumed) treatment.
  // All four inherit GL's own diploidy-assumed rule transitively, not just the one key the spec states it for
  // directly. No other Number=G field, reserved or not, has any such spec-stated default, so this default is
  // scoped to exactly these four keys rather than generalized to getExpectedCardinality's "G" branch itself.
  private static final Set<String> KEYS_ASSUMING_DIPLOIDY_WITHOUT_GT_AT_EVERY_VERSION = Set.of(
      ReservedFormatProperty.GenotypeLikelihoods.getId(),
      ReservedFormatProperty.PhredScaledGenotypeLikelihoods.getId(),
      ReservedFormatProperty.GenotypePosterior.getId());

  /**
   * @return {@code ploidy} unchanged, unless it's {@code null} (GT absent, per {@link #getPloidy}) and
   * {@code key} is one of GL/PL/GP (at any version this library targets) or PP (at VCFv4.3 or later, since PP
   * has no earlier declaration -- see {@link ReservedFormatProperty#ADDED_AT_V4_3}'s own comment), in which
   * case this returns {@code 2} (diploidy), per those keys' own spec-stated default.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static @Nullable Integer resolvePloidyForCardinalityCheck(String key, VcfVersion version,
      @Nullable Integer ploidy) {
    if (ploidy == null && (KEYS_ASSUMING_DIPLOIDY_WITHOUT_GT_AT_EVERY_VERSION.contains(key) ||
        (key.equals(ReservedFormatProperty.PhredScaledPosteriorProbabilities.getId()) &&
            version.isAtLeast(VcfVersion.V4_3)))) {
      return 2;
    }
    return ploidy;
  }

  /**
   * Warns (or, if {@code checkWarnings} is {@code null} -- strict mode -- throws {@link VcfFormatException}) if
   * {@code actual} doesn't match the cardinality {@code number} implies for {@code numAltAlleles} ALT alleles
   * (and, for {@code Number=G}, {@code ploidy}). Relocated from {@code VcfWriter} (was a private instance method
   * reading {@code m_warnings} implicitly) so {@code VcfSample}'s own read-path check can share the identical
   * implementation, rather than a second, independently-drifting copy.
   * <p>
   * {@code warnings} and {@code checkWarnings} are deliberately separate, matching
   * {@link #convertProperty(Class, String, boolean, VcfWarnings, VcfWarnings)}'s own two-parameter shape:
   * {@code warnings} is always used for {@link #getExpectedCardinality}'s own "cardinality too large to
   * validate" message, which is an internal computation limit, not a claim about {@code actual} being wrong, and
   * must never become a throw regardless of context; {@code checkWarnings} is the {@code isLenient()}-gated
   * dispatch for the actual mismatch this method itself reports. A write-side caller (always-warn diagnostics,
   * per {@code AGENTS.md}'s Writer Validation Policy) passes the same non-null {@link VcfWarnings} for both; a
   * read-side caller passes {@code isLenient() ? warnings : null} for {@code checkWarnings}.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static void warnIfWrongCardinality(String field, @Nullable String number, int actual, int numAltAlleles,
      @Nullable Integer ploidy, VcfWarnings warnings, @Nullable VcfWarnings checkWarnings) {
    Long expected = getExpectedCardinality(number, numAltAlleles, ploidy, warnings);
    if (expected != null && expected != actual) {
      warnOrThrow(checkWarnings, "{} has {} value(s), but Number={} requires {}", field, actual, number, expected);
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
   * Shared type-dispatch plumbing behind the {@link ReservedProperty} overload above and the two read-side
   * typed accessors that resolve a non-reserved key's declared {@code Type} themselves ({@code
   * VcfPosition.getInfo(VcfMetadata, String)}, {@code VcfSample.getProperty(VcfPosition, VcfMetadata,
   * String)}) -- not public API in its own right. Unlike a caller-supplied domain concept (a reserved key, or
   * a metadata declaration's own {@code Type}), this takes a raw {@link Class}, which {@link #convertElement}
   * only supports for a fixed, closed set of six types and throws {@link VcfFormatException} for anything
   * else -- so it can't actually serve a caller with a genuinely custom type the way its signature suggests.
   * Callers wanting to convert a custom, non-reserved property's raw value should do their own conversion
   * instead.
   * <p>
   * This is the read-path variant specifically: {@code checkWarnings} is {@code @Nullable} and
   * {@code isLenient()}-aware for the empty-value substitution below (warns under a lenient
   * {@code VcfContext}, throws {@link VcfFormatException} under a strict one), matching {@code VcfPosition}'s
   * own construction-time checks' treatment of the analogous empty-entry condition for ID/ALT/FILTER/FORMAT
   * sub-field lists. {@link #convertNonEmptyProperty} is the write-path counterpart, used by every
   * {@link VcfWriter} call site -- see its own Javadoc for why an empty value never reaches this class from
   * the writer at all, and is therefore never a case this method's own read-path callers need to reconcile
   * with the Writer Validation Policy's always-warn rule.
   *
   * @param warnings the caller's real, always-non-null warnings destination -- forwarded to
   * {@link #convertElement}, which needs a real sink of its own for a warning unrelated to this method's
   * empty-value dispatch (e.g. {@link VcfFloat#parse}'s "does not match the VCF Float grammar" warning, which
   * stays unconditional regardless of strict/lenient, exactly as before this method took a {@code
   * checkWarnings} parameter at all).
   * @param checkWarnings {@code null} means strict (throws on an empty value); non-null means lenient (warns
   * to it) -- always either {@code null} or reference-equal to {@code warnings}, never a third, independent
   * instance; kept as its own parameter, rather than derived from {@code warnings} here, because only the
   * caller's own {@code VcfContext} knows whether it's strict or lenient.
   */
  // public only because VcfSample needs to convert
  // a raw Class<?> for non-reserved FORMAT keys the same way the ReservedProperty overload above does.
  // @RestrictedApi marks this as public solely for that reason, not as real external API.
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  @SuppressWarnings("unchecked")
  public static @Nullable <T> T convertProperty(Class<?> clas, @Nullable String value, boolean isList,
      VcfWarnings warnings, @Nullable VcfWarnings checkWarnings) {
    // clas != Boolean.class: the exact same reason the isEmpty() check a few lines below already excludes
    // Boolean, one layer earlier -- a Flag key's "." is just another attached-value string, not this method's
    // ordinary "value genuinely absent" sentinel, since reaching this method at all already proves the key is
    // present (see convertElement's own Boolean branch, which this delegates straight through to and which
    // handles null/"."/empty/any-value uniformly by itself). Found missing here too by the Phase 2 review's
    // fourth re-verification round -- the same DB=. gap convertElement's own reordering fixed one layer
    // down, left open one layer up in this method's independent null/"." early return.
    if (clas != Boolean.class && (value == null || ".".equals(value))) {
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
      // clas != Boolean.class checked first (short-circuiting before value.isEmpty()): a null value only ever
      // reaches this point when clas == Boolean.class, per the top-level guard above, so evaluating
      // value.isEmpty() first here would raw-crash with NullPointerException instead of falling through to
      // convertElement (which handles a null value for Boolean safely, via StringUtils.stripToNull).
      if (clas != Boolean.class && value.isEmpty()) {
        // this shared method has no key/field-name context to report (see its own Javadoc: it serves callers
        // as different as a reserved-property getter and a generic declared-Type conversion) -- the expected
        // Java type is the only identifying detail available here.
        warnOrThrow(checkWarnings, "Value of type {} is an empty entry (VCF does not allow zero-length " +
            "fields); treating it as the missing value '.'", clas.getSimpleName());
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
        warnOrThrow(checkWarnings, "List value \"{}\" contains an empty entry (VCF does not allow zero-length " +
            "fields); treating it as the missing value '.'", value);
        list.add(null);
      } else {
        list.add(convertElement(clas, part, warnings));
      }
    }
    return (T) list;
  }

  /**
   * {@link #convertProperty(Class, String, boolean, VcfWarnings, VcfWarnings)}'s write-path counterpart: used only by
   * {@link VcfWriter}'s own "just test whether this value converts" probes (the two direct {@code
   * validateInfoValue}/{@code validateFormatValue} call sites, and the two reached via the {@link FormatType}/
   * {@link InfoType} overloads below, both of which delegate here rather than the read-path overload). Every
   * one of those four call sites already substitutes {@code "."} or skips an empty value before ever reaching
   * a conversion call, so unlike the read-path overload, {@code value} being empty here is never a real VCF
   * content case to warn or throw about -- it is this library's own bug if it happens, not a caller's, so it
   * is an assertion failure ({@link IllegalArgumentException}), not a recoverable content check. This is what
   * makes "an empty value never reaches this class from the writer" true by construction rather than merely
   * documented: a future accidental removal of one of those four guards fails loudly here instead of silently
   * reintroducing the isLenient()-dispatch question this method's split from the read-path overload exists to
   * keep separate.
   * <p>
   * The one exception to "an empty value never reaches this class from the writer" is a Flag's own missing-
   * value sentinel, {@code "."}: it is deliberately NOT treated as absent here, for the same reason as
   * {@link #convertElement}'s own Boolean branch and this method's read-path sibling's identical exclusion
   * (both {@code clas != Boolean.class} guards) -- reaching this method at all already proves the key is
   * present, so a Flag's {@code "."} is just another attached-value string. Missed here by the fourth
   * re-verification round's Flag fix (which corrected {@link #convertElement} and the read-path sibling but
   * not this write-path one), until the fifth re-verification round's finding #2.
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  @SuppressWarnings("unchecked")
  public static @Nullable <T> T convertNonEmptyProperty(Class<?> clas, @Nullable String value, VcfWarnings warnings) {
    if (clas != Boolean.class && (value == null || ".".equals(value))) {
      return null;
    }
    // clas != Boolean.class checked first (short-circuiting before value.isEmpty()): by this point, a null
    // value only survives when clas == Boolean.class (every other class already returned above), so
    // evaluating value.isEmpty() first here would raw-crash with NullPointerException instead of falling
    // through to convertElement (which handles a null value for Boolean safely).
    if (clas != Boolean.class && value.isEmpty()) {
      throw new IllegalArgumentException("convertNonEmptyProperty called with an empty value for " + clas +
          " -- every caller must substitute or skip an empty value before reaching this method");
    }
    return (T) convertElement(clas, value, warnings);
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

  // delegates to convertNonEmptyProperty, not the read-path convertProperty(Class, ...) overload: this
  // overload's only caller is VcfWriter's own "just test" probe, which has already substituted "." for an
  // empty value before reaching here -- see convertNonEmptyProperty's own Javadoc.
  static @Nullable <T> T convertProperty(FormatType type, @Nullable String value, VcfWarnings warnings) {
    return convertNonEmptyProperty(toJavaType(type), value, warnings);
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

  // delegates to convertNonEmptyProperty, not the read-path convertProperty(Class, ...) overload: this
  // overload's only production caller is VcfWriter's own "just test" probe, which has already substituted
  // "." for an empty value before reaching here -- see convertNonEmptyProperty's own Javadoc.
  static @Nullable <T> T convertProperty(InfoType type, @Nullable String value, VcfWarnings warnings) {
    return convertNonEmptyProperty(toJavaType(type), value, warnings);
  }

  // TODO.md: reverse of the two toJavaType overloads above -- a type-mismatch diagnostic that only has the
  // already-resolved Java class in hand (VcfWriter's validateFormatValue/validateInfoValue, reached from
  // both a declared FormatType/InfoType, converted via toJavaType, and a reserved property's own
  // getType(), which never went through a FormatType/InfoType at all) previously named that Java
  // implementation detail directly (e.g. "is not of type Long"), inconsistent with the sibling default
  // write path, which names the VCF-facing type the caller actually declared (e.g. "is not of type
  // Integer") for the identical declared type. Both FormatType and InfoType map to the same four classes
  // (Character/String stay disjoint from Long/VcfFloat's own two callers); INFO's Flag is the only type
  // with no FORMAT-side equivalent. A caller never legitimately reaches the default branch: every value
  // reaching this method resolved from a FormatType/InfoType or a reserved property's canonical type,
  // both drawn only from this same five-class set.
  static String describeJavaTypeAsVcfType(Class<?> type) {
    if (type == Long.class) {
      return "Integer";
    }
    if (type == VcfFloat.class) {
      return "Float";
    }
    if (type == Character.class) {
      return "Character";
    }
    if (type == String.class) {
      return "String";
    }
    if (type == Boolean.class) {
      return "Flag";
    }
    throw new VcfFormatException("Class " + type.getSimpleName() + " isn't a mapped VCF Type?!");
  }

  private static @Nullable Object convertElement(Class<?> clas, @Nullable String value, VcfWarnings warnings) {
    if (clas == Boolean.class) {
      // Checked before the generic null/"." early return just below, unlike every other clas branch: for
      // every other type, "." is the missing-value sentinel (this key's value is genuinely absent), but a
      // Flag's own missing-value sentinel would be indistinguishable from an ordinary explicit value, since a
      // Flag's truth is its PRESENCE in the map -- reaching this method at all already proves the key is
      // present (see this method's own callers, which never call it for an absent key), so "." here is just
      // another attached-value string, no different from "0"/"2"/anything else. Before this reordering,
      // DB=. read back as null (indistinguishable from DB being entirely absent), contradicting bcftools
      // 1.23.1 (measured: `-i 'DB=1'` matches DB=. exactly like DB=0/DB=1/bare DB), and contradicting this
      // method's own write-side sibling (VcfWriter.validateInfoValue warns "has Type=Flag but has a value"
      // for DB=. exactly like any other non-empty value) -- found by the Phase 2 review's fourth
      // re-verification round.
      //
      // VCFv4.3.tex line 171: "the 'Flag' type indicates that the INFO field does not contain a Value entry,
      // and hence the Number must be 0 in this case" -- a Flag's truth is its PRESENCE, not any value string
      // attached to it. Confirmed live (htslib 1.23.1, vcf.c's vcf_parse_info, "if Flag has a value, treat it
      // as a string", and bcf_get_info_values's presence-only BCF_HT_FLAG read): when a Flag key does carry
      // an explicit value, htslib treats the value as opaque and the tag simply stays present -- it never
      // inspects the string's content, so "0" carries no more (or less) special meaning than "1", "false", or
      // any other text. This method previously treated "0"/"false" as false and "1"/"true" as true, inverting
      // a Flag's meaning for exactly the "0" shape hts-specs' own
      // failed_body_info_012/019/020/025/026/027.vcf fixtures use (##CauseOfFailure=INFO <KEY> value is not a
      // flag) -- found by the Phase 2 review's third re-verification round. The write side
      // (VcfWriter.validateInfoValue) already warns "has Type=Flag but has a value" for this same shape
      // without trying to interpret the value's content either; this mirrors that on the read side.
      //
      // bcftools and vcf_validator 0.10.2 disagree here, and this deliberately follows bcftools (AGENTS.md's
      // decision hierarchy: bcftools is the tie-breaker when the spec itself is silent, which it is on what a
      // spec-nonconforming attached Flag value means). vcf_validator's own Ragel grammar (record.cpp's Flag
      // handling) accepts only "0"/"1"/no-value as well-formed and rejects any other literal -- so "2" is NOT
      // "never specifically the problem" the way this comment previously (and wrongly) claimed; measured live,
      // vcf_validator 0.10.2 flags DB=2 invalid ("a flag value must be 0, 1 or none") while accepting DB=0 and
      // DB=1. vcf_validator also then reads "0" as semantically false downstream: measured, a
      // POS=100/REF=A/IMPRECISE=0/END=105 record is REJECTED ("END value must be equal to POS + length of REF
      // - 1 for a precise variant (where IMPRECISE is set to 0)", since 105 != 100), while the identical
      // END=105 under IMPRECISE=1 or bare IMPRECISE (not subject to that precise-variant formula at all) is
      // accepted -- i.e. vcf_validator's own semantic check treats IMPRECISE=0 as equivalent to IMPRECISE
      // being false/absent, the literal inversion this fix removed. Both are genuine, measured facts about
      // vcf_validator, not a defect in it
      // (neither of AGENTS.md's two confirmed vcf_validator token-capture defects applies to a Flag literal),
      // so this is a real bcftools/vcf_validator split, documented here per AGENTS.md's requirement to record
      // a disagreement rather than silently pick a side.
      value = StringUtils.stripToNull(value);
      if (value == null) {
        return true;
      }
      warnings.warn("Flag value \"{}\" is not empty, but a Flag's truth is its presence, not any attached " +
          "value; treating it as present regardless of the value's content", value);
      return true;
    }
    if (value == null || ".".equals(value)) {
      return null;
    }
    if (clas == String.class) {
      return value;
    } else if (clas == Character.class) {
      if (value.length() == 1) {
        return value.charAt(0);
      } else {
        throw new VcfFormatException("Invalid character value '" + truncateAndEscapeForMessage(value) + "'");
      }
    } else if (clas == VcfFloat.class) {
      // unlike plain BigDecimal, this also accepts NAN/INF/INFINITY (case-insensitive, optionally signed), which
      // VCFv4.3.tex (the target) states are valid Float literals; VCFv4.2 leaves Float's grammar undefined
      // entirely -- see VcfFloat's own Javadoc for the citation and why VCFv4.3, not a later version, is cited
      return VcfFloat.parse(value, warnings);
    } else if (clas == Long.class) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw new VcfFormatException("Expected integer; got " + truncateAndEscapeForMessage(value));
      }
    }
    throw new VcfFormatException("Type " + clas + " unrecognized");
  }

  /**
   * Rejects a metadata property whose key or value would corrupt the {@code <key=value,...>} attribute list it is
   * emitted into, or that bcftools would discard/refuse entirely. This is the CONSTRUCTION-time entry point --
   * every public add/replace route in {@code VcfMetadata.Builder} calls it, and every problem the shared
   * {@link #findEncodingProblems} classifier finds throws immediately, since originated content must never reach
   * the header in a form bcftools cannot parse or would silently drop (AGENTS.md's "bcftools is asymmetric
   * evidence" -- that absolute floor applies to originated content). See
   * {@link #checkMetadataPropertyEncodingForWrite} for the parallel {@code validateBeforeWrite()} entry point,
   * which instead warns for a problem that leaves the file merely semantically non-compliant but still
   * bcftools-readable, since that route's content may be carried-forward (parsed, not originated) rather than
   * something this call is creating.
   * <p>
   * See STRUCTURAL_DELIMITER_HANDLING.md's Reference table for the per-shape throw/warn outcomes.
   *
   * @throws VcfFormatException for any problem {@link #findEncodingProblems} finds: an invalid property name, a
   * malformed bracket value, or an unquoted value containing {@code ,}, {@code <}, {@code >}, an unescaped
   * {@code "}, or leading/trailing space
   */
  static void checkMetadataPropertyEncoding(String kind, Map<String, String> properties) {
    for (EncodingProblem problem : findEncodingProblems(kind, properties)) {
      throw new VcfFormatException(problem.message());
    }
  }

  /**
   * The {@code validateBeforeWrite()} entry point: the write-time counterpart to
   * {@link #checkMetadataPropertyEncoding}, sharing its {@link #findEncodingProblems} classifier so the two
   * routes cannot drift about what counts as a problem. Throws only for a problem
   * {@link EncodingProblem#unreadableOutput()} marks -- for one of two distinct reasons, not one: see that
   * flag's own Javadoc. Every other problem is warned to {@code warnings} and the value is preserved
   * unchanged: this is the carried-forward case documented in VCF_SPEC_AMBIGUITIES.md
   * (##contig=<ID=1,a-b=v> read from a file, warned about, and re-emitted, rather than something this write
   * call originated).
   */
  static void checkMetadataPropertyEncodingForWrite(String kind, Map<String, String> properties,
      VcfWarnings warnings) {
    for (EncodingProblem problem : findEncodingProblems(kind, properties)) {
      if (problem.unreadableOutput()) {
        throw new VcfFormatException(problem.message());
      }
      warnings.warn("{}", problem.message());
    }
  }

  /**
   * A problem found by {@link #findEncodingProblems}. {@code unreadableOutput} marks the ones that throw at
   * write time too ({@link #checkMetadataPropertyEncodingForWrite}), and it groups three genuinely different
   * reasons under one flag, not one reason with three examples:
   * <ul>
   *   <li>The unterminated-bracket shape (no closing {@code ]} at all) throws because bcftools genuinely
   *   cannot read the file at all (measured: exit 255) -- the absolute floor {@code AGENTS.md}'s Writer
   *   Validation and Round-Trip Policy states, which a warn branch would violate.</li>
   *   <li>The unquoted comma and the bracket-with-trailing-junk shapes throw for a weaker, different reason.
   *   Measured, bcftools merely discards the one declaration for these (exit 0) -- the same outcome an
   *   invalid attribute name only warns for -- but the attribute-list tokenizer can never produce either
   *   shape as a STORED value on the parse path (a comma always ends an unquoted value there; trailing
   *   content after a bracket's close is always split off and warned about separately, never merged into
   *   the stored value), so throwing here harms no carried-forward content and costs nothing.</li>
   *   <li>The malformed-quoted-value-with-a-comma shape (a value starting with {@code "} that fails
   *   {@link #isQuoted} and also contains a {@code ,}) throws for a third reason that is about
   *   THIS library, not bcftools: a value with no valid closing {@code "} is recovered by rescanning for the
   *   next comma rather than the value's own end ({@link #parseOneAttribute}'s {@code close<0} branch), so
   *   re-emitting this shape verbatim and re-parsing it silently drops everything after that comma. Like the
   *   previous bullet, the parse path itself can never produce this exact shape as a stored value (its own
   *   recovery already truncates at the first comma), so throwing here costs nothing either.</li>
   * </ul>
   * {@code message} embeds the offending property name through {@link #truncateAndEscapeForMessage}, unlike
   * this project's usual scope for that helper (thrown messages only, not logged warnings): {@code message}
   * is a single string shared by both {@link #checkMetadataPropertyEncoding}'s unconditional throw and
   * {@link #checkMetadataPropertyEncodingForWrite}'s warn-or-throw dispatch on the identical value, with no
   * point after construction where the two paths could sanitize independently -- so sanitizing here is the
   * only way to close the throw-path gap without either duplicating this classifier's construction logic or
   * leaving the shared string unsanitized for both. A property name is not attacker-length-controlled in
   * ordinary use, but this method also runs on carried-forward (parsed-from-a-file) content, where it can be
   * -- found by the fifth re-verification round's finding #1b.
   */
  private record EncodingProblem(String message, boolean unreadableOutput) {}

  /**
   * The single classifier {@link #checkMetadataPropertyEncoding} and {@link #checkMetadataPropertyEncodingForWrite}
   * both call, so the rule for what counts as a problem exists once rather than as two copies free to drift.
   * <p>
   * <b>Why this dereferences {@code value} without a null check -- an ordering invariant, not an oversight.</b>
   * {@code BaseMetadata.validateProperties} ({@code BaseMetadata.java:139-156}) rejects both a null key and a
   * null value with {@code VcfFormatException}, and it always runs FIRST on both routes into this method: at
   * construction, the declaration object is built -- running {@code validateProperties} via the Map constructor
   * every parsed declaration uses -- before {@code checkMetadataPropertyEncoding} runs; at write time,
   * {@code VcfWriter.validateKeyedMetadataEntries} calls {@code entry.validate()} before the write-mode call
   * reaches here. So the package-private {@code BaseMetadata.trustedMutableProperties()} bypass cannot deliver a
   * null value here -- it is caught one layer up, with a better message that names the property. The
   * {@code name == null} check below is kept anyway -- it costs nothing and matches this method's own defensive
   * style -- but do NOT add a null-VALUE branch; it would only duplicate {@code validateProperties}' error with a
   * worse one. If a new entry point into this method is ever added, re-check that it still runs after an
   * equivalent null check, or a clean {@code VcfFormatException} becomes a raw {@code NullPointerException}.
   */
  private static List<EncodingProblem> findEncodingProblems(String kind, Map<String, String> properties) {
    List<EncodingProblem> problems = new ArrayList<>();
    for (Map.Entry<String, String> property : properties.entrySet()) {
      String name = property.getKey();
      if (name == null || !METADATA_ATTRIBUTE_NAME_PATTERN.matcher(name).matches()) {
        // bcftools discards the declaration but still reads the file, so this is warn-at-write:
        // ##contig=<ID=1,a-b=v> is the documented carried-forward-content example.
        problems.add(new EncodingProblem(
            kind + " metadata has an invalid property name: " + truncateAndEscapeForMessage(name), false));
        continue;
      }
      String value = property.getValue();
      // Select the branch on the value's first byte, exactly as the tokenizer itself does (design spec's
      // §5, "Construction-time value predicate") -- selecting on well-formedness instead is a defect: a
      // malformed value in one quoting form would fail that form's test, fall through to the literal
      // branch, and (for a form other than a leading '"') might pass that branch's checks too and be
      // emitted verbatim.
      if (value.startsWith("\"")) {
        if (!isQuoted(value)) {
          if (value.indexOf(',') >= 0) {
            // Fatal for a different reason than bcftools: OUR OWN tokenizer's recovery for a malformed
            // quoted value (parseOneAttribute's close<0 branch) re-scans for the next COMMA, not the value's
            // own end, so an embedded comma is genuinely ambiguous on re-parse. Emitted verbatim,
            // `x="abc,def` re-parses to just `x="abc`, with "def" silently dropped (it has no '=' of its
            // own, so it warns and is discarded rather than merged back in) -- unreadable BY US regardless
            // of what bcftools does with it, so this must throw rather than warn, the same as the plain
            // unquoted-comma case below.
            problems.add(new EncodingProblem(
                kind + " metadata property " + truncateAndEscapeForMessage(name) +
                    " has a malformed quoted value containing a comma", true));
          } else {
            // Non-fatal, same as the unescaped '<'/'>' cases below: measured, bcftools does not hard-fail on
            // an unterminated leading quote ("ab, no closing quote) -- it warns ("Incomplete header line")
            // and mangles the declaration, but the file stays readable (design spec's Evidence table,
            // "quote-position value" row). This used to be caught incidentally by containsUnescapedQuote() in
            // the literal branch below, once isQuoted's well-formedness check (rather than the leading '"'
            // byte) selected the branch -- one check covering another's gap, the exact pattern §5 was written
            // to prevent.
            problems.add(new EncodingProblem(
                kind + " metadata property " + truncateAndEscapeForMessage(name) + " has a malformed quoted value",
                false));
          }
        }
        continue;
      }
      // Bracket-quoting ACCEPTANCE (a well-formed value protecting an interior comma) is scoped to ##META's
      // Values ONLY -- see VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting beyond ##META's Values"
      // entry. But bcftools' OWN generic bracket handling is unaffected by that scoping: it still treats ANY
      // leading '[' as opening a quoted span, regardless of attribute or declaration type, and keeps scanning
      // for a closing ']' past the end of the line (even past this declaration's own closing '>') if none
      // exists. A value with NO ']' anywhere is therefore universally unreadable to bcftools (measured exit
      // 255) no matter which attribute carries it, so that floor is checked unconditionally, before the
      // Values-only gate below.
      if (value.startsWith("[") && value.indexOf(']', 1) < 0) {
        problems.add(new EncodingProblem(
            kind + " metadata property " + truncateAndEscapeForMessage(name) +
                " has an unterminated bracket value bcftools cannot read", true));
        continue;
      }
      if (value.startsWith("[") && isMetaValues(kind, name)) {
        if (!isWellFormedMetaValuesBracket(value)) {
          // The unterminated case was already thrown above (universally); what's left here is a bracket that
          // DOES close but has junk after it ('[a]b]') -- that only makes bcftools exit 0 and drop the one
          // declaration -- the same outcome an invalid name only warns for -- but the tokenizer can never
          // produce this exact shape as a stored value on the parse path either (trailing content after a
          // bracket's close is always split off and warned about separately, never merged into the stored
          // value), so throwing costs nothing here too.
          problems.add(new EncodingProblem(
              kind + " metadata property " + truncateAndEscapeForMessage(name) + " has a malformed bracket value",
              true));
        }
        continue;
      }
      if (value.indexOf(',') >= 0) {
        // bcftools merely discards the declaration at exit 0 for this (the same outcome the invalid-name
        // case above only warns for), but the tokenizer can never produce a STORED literal value containing
        // a comma -- an unquoted value always terminates at the first one -- so no carried-forward content
        // is harmed by throwing, and the check costs nothing. See EncodingProblem's own Javadoc. This also
        // catches a leading '[' outside Values that contains a comma (e.g. "[a,b]" on some other attribute):
        // it is exactly as unreadable-on-reparse as any other unquoted comma, so it gets the same throw.
        problems.add(new EncodingProblem(
            kind + " metadata property " + truncateAndEscapeForMessage(name) + " contains an unquoted comma",
            true));
        continue;
      }
      if (value.startsWith("[") || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
          || containsUnescapedQuote(value) || value.startsWith(" ") || value.endsWith(" ")) {
        problems.add(new EncodingProblem(kind + " metadata property " + truncateAndEscapeForMessage(name)
            + " contains an unquoted structural delimiter or layout whitespace", false));
      }
    }
    return problems;
  }

  /**
   * @return whether {@code value} (already known to start with {@code [}, and only ever consulted for
   * {@code ##META}'s {@code Values} attribute, via {@link #isMetaValues}) is well-formed: its FIRST literal
   * {@code ]} is its last character. Deliberately a plain, unescaped scan -- unlike {@link #isQuoted}'s
   * double-quote form, the spec defines no escape mechanism for {@code [}/{@code ]} -- applying the identical
   * rule {@link #parseOneAttribute}'s {@code isMetaValues} branch uses to find a value's closing {@code ]}
   * (a different scan in code, since one runs over the whole remaining line at parse time and this one runs
   * over an already-extracted, already-stored value at construction time, but the same first-literal-{@code
   * ]} rule), so construction and parsing cannot disagree about where a bracket value ends.
   */
  private static boolean isWellFormedMetaValuesBracket(String value) {
    return value.indexOf(']', 1) == value.length() - 1;
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
   * convention {@link #tokenizeAttributeList} and {@link #parseAttribute} use for a value's own closing quote).
   * {@link #isQuoted} already rejects a value that starts and ends with {@code "} but isn't validly escaped in
   * between, but a value can also contain a stray, unescaped {@code "} without looking quoted at all (no
   * leading/trailing {@code "}) and without containing {@code ,} or {@code =} either, so this must be checked
   * independently of both. Called only from a value's LITERAL branch (its first byte is neither {@code "} nor
   * {@code [}), where a non-leading {@code "} does not truncate anything on re-read -- unlike a leading one, the
   * attribute-list tokenizer classifies purely on the value's first byte, so this value takes the same literal
   * branch again and is preserved whole (see {@code STRUCTURAL_DELIMITER_HANDLING.md}'s "Severity ranking"
   * section: "a non-leading quote is an ordinary byte and protects nothing"). This is flagged anyway, exactly
   * like an unescaped {@code <}/{@code >} in the same literal branch: {@code "} is a character bcftools' own
   * header tokenizer treats as structural, so a value containing one unquoted is worth a warning on principle
   * even though this library's own round trip is provably safe from it.
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

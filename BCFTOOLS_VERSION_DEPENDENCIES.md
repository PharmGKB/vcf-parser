# bcftools version dependencies

This document indexes every decision this library has made based on bcftools's specific current behavior, so
they can be mechanically re-checked whenever bcftools is upgraded. It does not contain the checks themselves --
those live in `BcftoolsDependencyTest`, a runnable, opt-in test suite. Run `./gradlew bcftoolsDependencyTest`
after upgrading bcftools; any failure means a decision below needs revisiting.

Bound to bcftools 1.23.1 -- `BcftoolsDependencyTest#testBcftoolsIsExpectedVersion` fails on its own, independent
of every other test in the class, if a different version is installed; every other test still runs and reports
its own result regardless, so a version-mismatch failure there is a flag to read the rest of the results
carefully, not a reason they didn't run. Fuller reasoning for each entry lives in `VCF_SPEC_AMBIGUITIES.md`
unless noted otherwise.

## Source reference: the htslib functions this library's header grammar depends on

**On an upgrade, diff the source too, not only the test results.** `BcftoolsDependencyTest` can only fail on a
behavior some entry below already anticipated. A source diff also surfaces changes to cases nobody thought to
test -- which is how the header tokenizer's real grammar was established in the first place (see `AGENTS.md`'s
"A probe is not a grammar").

htslib source is checked out at **`~/Dev/htslib`**, pinned to the installed version:

| | |
|---|---|
| tag | `1.23.1` |
| commit | `d1a2a873552a4fb8c30b3a77620c5f8bef7a7143` |

Re-clone at the *new* tag before diffing; reading a different version than the installed binary documents a
grammar nobody is running.

These five functions in `vcf.c` are the ones this library's metadata attribute-list grammar was derived from.
Each is fingerprinted by **function boundary rather than line number**, so the check survives unrelated edits
elsewhere in the file and does not rot the way a line citation does:

| function | governs | sha256 (first 16) |
|---|---|---|
| `bcf_hdr_parse_line` | the entire `##KEY=<...>` tokenizer: name grammar `[A-Za-z_][A-Za-z0-9_.]*`, the space-skip before a value, `"` vs `[` delimiting, the `nopen` bracket-depth counter, warn-vs-error | `e8c0ff70219d93c5` |
| `is_escaped` | whether `\"`/`\]` closes a delimited value (backslash run, mod 2) | `1e1d410b6fedc29f` |
| `bcf_hrec_set_val` | that a quoted value is stored **with** its delimiters — nothing is ever unquoted | `e1de8df770c872fc` |
| `_bcf_hrec_format` | that re-emission is **reconstructed from parsed pairs**, not echoed — which is what makes re-emitted bytes admissible evidence about the parse | `e43c331df68ca9ca` |
| `bcf_hrec_check` | which declaration types get ID validation at all (CTG/INFO/FMT only; never FILTER or generic `BCF_HL_STR`) | `8ed68eab6b78d0fe` |

Recompute all five:

```bash
cd ~/Dev/htslib
for f in '^bcf_hrec_t \*bcf_hdr_parse_line' '^static inline int is_escaped' \
         '^int bcf_hrec_set_val' '^static int _bcf_hrec_format' '^static int bcf_hrec_check'; do
  printf '%-34s %s\n' "$f" "$(sed -n "/$f/,/^}/p" vcf.c | sha256sum | cut -c1-16)"
done
```

**A changed hash is not a failure — it is a prompt to re-read.** Diff that function against the pinned commit
and decide whether the change touches anything this library relies on. If it does, re-derive the affected rows
of the behavior tables per `BEHAVIOR_TABLE_STANDARD.md` (predictions from source first, then measure), update
the entry below, and record the new hash here. A hash that changes with no behavioral consequence still gets
updated, with a one-line note saying why it did not matter.

Coverage here is best-effort, not exhaustive -- new entries should be added as new bcftools-informed decisions
are made. The one exception is the "Round-trip-critical" section below: every decision in that category MUST be
listed and covered, since a regression there means this library could emit VCF that bcftools itself cannot read
back.

## Round-trip-critical

These MUST be re-checked on every bcftools upgrade: if bcftools's behavior changed here without this library
changing to match, this library could emit VCF that bcftools itself cannot read back.

### Generic metadata property values shaped like an attribute list
- Test: `BcftoolsDependencyTest#testGenericPropertyEmptyBracketsBcftoolsRejects`,
  `#testGenericPropertyBracketedNoEqualsBcftoolsRejects`, `#testGenericPropertyBracketedWithEqualsBcftoolsAccepts`,
  `#testGenericPropertySquareBracketSubListBcftoolsAccepts`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`##pedigreeDB`/`##assembly` bracket-wrapped values colliding with
  attribute-list grammar" entry

### Percent-encoding is never decoded
- Test: `BcftoolsDependencyTest#testPercentEncodingBcftoolsDecodesNothingAnywhere`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Percent-encoding field scope, and the eight-sequence limit" entry.
  "bcftools 1.23.1 percent-decodes nothing, anywhere" is load-bearing for `VcfPercentEncoding`'s whole design
  (Task 4) -- the model layer never decodes automatically specifically because there is no tool precedent for
  it and no round-trip need for it. Probed live with:
  `bcftools view -h pct.vcf` (an `##INFO`/`##FILTER` header containing `%3B` in a `Description` attribute) and
  `bcftools query -f '%INFO/XY\n' pct.vcf` (a data-line INFO value containing `%3B%2C%3A`) -- both round-trip
  the percent-encoded text completely unchanged, with no decoding in either the header or the data line. Must
  be re-checked on every bcftools upgrade: if a future bcftools started decoding these sequences, this
  library's own choice not to would become a round-trip mismatch against real-world bcftools-produced files.

### Contig ID with a non-leading asterisk
- Test: `BcftoolsDependencyTest#testContigIdWithNonLeadingAsteriskBcftoolsAccepts`,
  `#testContigIdWithLeadingAsteriskBcftoolsWarnsButPreserves`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Contig ID with a non-leading asterisk (hts-specs' `failed/` fixtures
  vs. VCFv4.3 errata)" entry. This library already accepts a non-leading `*` in a contig name and the default
  writer emits it verbatim, so if a future bcftools started rejecting the form, this library's output would
  stop being readable by a real consumer -- round-trip-critical by this document's own definition. bcftools
  1.23.1 accepts `##contig=<ID=1.*,length=123456>` and a `CHROM` of `chr*1` silently (no warning); it warns
  (`[W::bcf_hrec_check] Invalid contig name: "*chr"`) but still exits 0 and preserves the declaration for a
  **leading** asterisk, which is the boundary the control test pins.

### Unterminated bracket value in a metadata attribute
- Test: `BcftoolsDependencyTest#testUnterminatedBracketValueBcftoolsHardErrors`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "General bracket-list quoting beyond `##META`'s `Values`" entry (which
  supersedes this decision's original citation, "Unquoted metadata attribute value: malformed bracket vs.
  `<`/`>`/`;`", after bracket-quoting *acceptance* was narrowed to `##META`'s `Values` -- this specific check
  did not narrow with it). `InternalUtils.findEncodingProblems`'s unconditional "no `]` anywhere" check throws
  for this shape at BOTH construction and `validateBeforeWrite()`, regardless of attribute name or
  declaration type, since it is the one malformed-value shape that makes the ENTIRE file unreadable
  (`[E::bcf_hdr_parse_line] Missing ']' in header line`, exit 255) rather than merely dropping one
  declaration -- if this library ever originated one, bcftools could not read the file back at all. This
  check is deliberately NOT gated to `##META`'s `Values`: bcftools' own generic bracket handling treats any
  leading `[` as opening a quoted span regardless of this library's narrower acceptance scope, so the fatal
  floor stays universal even though which values get read back as *protected* does not. Pinned via `##contig`'s
  own `x` attribute specifically (not `##META`'s `Values`) to prove the universality.
- **Parse side closes the same gap, after a real bug caught by external review.** The first cut of this
  decision left parsing a `[`-leading value with no closing `]` on any attribute *other than* `##META`'s
  `Values` un-rewritten -- ordinary content, warned about, exactly like an unquoted `<`. That was wrong:
  splitting at the comma (since `[` doesn't protect it outside `Values`) can turn a WELL-FORMED bracket value
  into a dangling one purely via truncation -- `##contig=<ID=c1,x=[a, b],length=999>` is valid input, but
  truncating `x`'s value at its interior comma yields `[a`, no `]` anywhere, and writing that back out raw
  manufactured `##contig=<ID=c1,x=[a,length=999>` (confirmed live: bcftools exit 255). That is this library's
  own splitting creating new fatal content from safe input, not carrying forward already-malformed content --
  the "accepted cost" precedent for `<`/`>` mishandling does not cover it, since every measured `<`/`>` shape
  stays at exit 0 (corrupted, never fatal); an unterminated `[` uniquely is fatal, and that asymmetry is
  exactly why it needs its own defense rather than inheriting `<`'s treatment wholesale.
  `InternalUtils.parseOneAttribute`'s literal branch now re-quotes (`InternalUtils.quote`) whenever the stored
  value dangles a `[` with no `]` anywhere, regardless of whether that happened via truncation or because the
  input was already unterminated (the two are indistinguishable once stored). bcftools' failure on this shape
  at all is arguably its own defect -- filed upstream at
  [samtools/htslib#2065](https://github.com/samtools/htslib/issues/2065) -- but that motivated defending
  against the shape rather than accepting it, since the fix costs nothing (parsing still does not protect the
  interior comma, matching the design decision) and closes a hole the original implementation had not
  actually scoped correctly. Verified end-to-end (not just unit-level) by
  `BcftoolsDependencyTest#testNonMetaValuesBracketTruncationDoesNotManufactureAnUnreadableHeader`, which
  parses and writes the example above through this library's own pipeline and pipes the real output through
  bcftools to confirm it stays readable.

## Other bcftools-dependent decisions

(best-effort coverage, not exhaustive)

### Malformed attribute inside a metadata declaration's `<...>` list
- Test: `BcftoolsDependencyTest#testMalformedMetadataAttributeBcftoolsDropsDeclarationAndContinues`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Malformed attribute inside a metadata declaration's `<...>` list" entry

### Metadata attribute name outside the allowlist
- Test: `BcftoolsDependencyTest#testMetadataAttributeNameOutsideAllowlistBcftoolsDropsDeclarationSilently`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Metadata attribute NAME grammar (parse and write warn, construction throws)" entry

### Bracket value with trailing junk (closed but malformed)
- Test: `BcftoolsDependencyTest#testBracketValueWithTrailingJunkBcftoolsDropsDeclarationSilently`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Unquoted metadata attribute value: malformed bracket vs. `<`/`>`/`;`"
  entry. Unlike the unterminated-bracket shape indexed under Round-trip-critical above, this shape leaves the
  rest of the file readable (exit 0, just the one declaration dropped), so `checkMetadataPropertyEncodingForWrite`
  still throws for it -- it is grouped with the unterminated shape under the same
  `EncodingProblem.unreadableOutput=true`, not with the merely-lossy `<`/`>` shapes below.

### Unescaped `<`/`>` in an unquoted metadata attribute value
- Test: `BcftoolsDependencyTest#testUnescapedLessThanInMetadataValueBcftoolsMisparsesButSurvives`,
  `#testUnescapedGreaterThanInMetadataValueBcftoolsTruncatesDeclaration`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Unquoted metadata attribute value: malformed bracket vs. `<`/`>`/`;`"
  entry. Both shapes leave the file readable (exit 0) -- a garbled but present declaration for `<`, a silently
  truncated one for `>` -- which is why `checkMetadataPropertyEncodingForWrite` warns rather than throws here.

### Unescaped `;` in an unquoted metadata attribute value is not structural
- Test: `BcftoolsDependencyTest#testUnescapedSemicolonInMetadataValueBcftoolsTreatsAsOrdinaryContent`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Unquoted metadata attribute value: malformed bracket vs. `<`/`>`/`;`"
  entry. Confirms `InternalUtils.findEncodingProblems` is right to have no `;` branch at all: bcftools' own
  scanner never special-cases `;` in this grammar either.

### `##FORMAT`/`##INFO` declaration ID grammar vs. `##FILTER`/`##SAMPLE`/`##ALT`
- Test: `BcftoolsDependencyTest#testFormatDeclarationIdRejectsAsteriskButFilterAccepts`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`##FORMAT`/`##INFO` declaration ID grammar is stricter than
  `##FILTER`/`##SAMPLE`/`##ALT`" entry

### `"1000G"` legacy INFO tag-name exception
- Test: `BcftoolsDependencyTest#testInfo1000GLegacyTagNameBcftoolsAccepts`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`\"1000G\"` legacy INFO tag-name exception" entry

### `##contig` ID character set
- Test: `BcftoolsDependencyTest#testContigIdCharacterSetBcftoolsRejectsCommaSameAsChromDoes`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`##contig` ID character set" entry

### `##contig` extra/unmodeled attributes
- Test: `BcftoolsDependencyTest#testContigExtraAttributeBcftoolsAcceptsSilently`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`##contig` extra/unmodeled attributes" entry

### `##META` attribute order
- Test: `BcftoolsDependencyTest#testMetaAttributeOrderBcftoolsIgnoresCanonicalOrdering` (canonical-order control),
  `#testMetaAttributeOrderBcftoolsAcceptsTheSpecsOwnWorkedExample` (the spec-order shape the decision covers),
  `#testInfoAttributeOrderBcftoolsIgnoresIdPosition` (generalizes to a non-`##META` type -- see below)
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`##META` attribute order" entry. No keyed declaration type
  attribute-order checks any more -- the decision, originally scoped to `##META` alone, was extended to
  `##INFO`/`##FORMAT`/`##ALT`/`##FILTER`/`##contig`/`##SAMPLE` on 2026-08-14 -- so ten fixtures
  (`failed_meta_{meta_003,info_003,format_003,alt_004}`, up to three spec versions each) are all
  permanently-accepted `hts-specs-baseline.tsv` divergences whose sole justification is bcftools' own silence
  on ordering -- nothing else re-checks that premise (`htsSpecsTest` never runs bcftools; `bcf_hdr_parse_line`
  never consults `##fileformat`), so a future bcftools upgrade that started warning on ordering would rot
  this decision silently without these three tests. The third test exists specifically because the
  broader decision leans on a non-`##META` control row (an ID-last `##INFO` declaration) that had no
  dedicated `bcftoolsDependencyTest` case of its own before this generalization -- see
  `BEHAVIOR_TABLE_STANDARD.md`'s evidence-preservation tier 2.

### Generic metadata property with an empty value
- Test: `BcftoolsDependencyTest#testGenericPropertyEmptyValueBcftoolsPreservesButWeAreStricter`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Generic metadata property with an empty value" entry

### Duplicate `##PEDIGREE` lines with no `ID` property
- Test: `BcftoolsDependencyTest#testPedigreeNoIdDuplicatesBcftoolsKeepsBoth`
- Reasoning: `DUPLICATE_METADATA_ID_HANDLING.md`, "`##PEDIGREE`: a conditional case" section

### Duplicate `##PEDIGREE` lines with an empty (not absent) `ID=`
- Test: `BcftoolsDependencyTest#testPedigreeEmptyIdDuplicatesBcftoolsKeepsFirst`
- Reasoning: `WONT_FIX.md`, "`PedigreeMetadata` duplicate-ID dedup treats a present-but-blank `ID` the same as
  a real ID" entry

### Missing `ID` on `##META`
- Test: `BcftoolsDependencyTest#testMetaMissingIdBcftoolsKeepsSilently`
- Reasoning: `DUPLICATE_METADATA_ID_HANDLING.md`'s "Missing or empty ID" section. A deliberate divergence:
  bcftools 1.23.1 keeps an ID-less `##META` unchanged with no warning; `vcf-parser` warns and drops it,
  since (unlike `##PEDIGREE`, whose `ID` requirement is version-gated at VCFv4.3+) `##META`'s required-`ID`
  rule is version-independent.

### Duplicate metadata declaration ID (seven keyed types)
- Test: `BcftoolsDependencyTest#testDuplicateContigIdBcftoolsKeepsFirst` (representative; not every declaration
  type is independently re-verified here)
- Reasoning: `DUPLICATE_METADATA_ID_HANDLING.md` (its own reference table is the fuller source of truth)

### FORMAT declared but zero sample columns
- Test: `BcftoolsDependencyTest#testFormatDeclaredNoSampleColumnsBcftoolsHardErrors`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "FORMAT declared but zero sample columns" entry

### Empty sample name in `#CHROM` header
- Test: `BcftoolsDependencyTest#testEmptySampleNameInHeaderBcftoolsHardErrors`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Empty sample name in `#CHROM` header" entry

### CHROM contiguity / POS ordering within a CHROM block
- Test: `BcftoolsDependencyTest#testChromContiguityViolationBcftoolsDoesNotReject`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "CHROM contiguity / POS ordering within a CHROM block" entry

### Trailing blank line at EOF
- Test: `BcftoolsDependencyTest#testTrailingBlankLineAtEofBcftoolsRejects`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Trailing blank line at EOF" entry

### Duplicate `#CHROM` sample-column name
- Test: `BcftoolsDependencyTest#testDuplicateChromSampleColumnNameBcftoolsHardErrors`
- Reasoning: `DUPLICATE_METADATA_ID_HANDLING.md` (the contrast case against a duplicate declaration ID)

### `PASS` combined with other FILTER codes
- Test: `BcftoolsDependencyTest#testFilterPassCombinedWithOtherCodesBcftoolsAcceptsSilently`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`PASS` combined with other FILTER codes" entry

### FILTER status (`PASSED`/`FAILED`/`NONE`) derivation
- Test: `BcftoolsDependencyTest#testFilterClearNeverPromotesToPassBcftoolsConvention`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "FILTER status (`PASSED`/`FAILED`/`NONE`) derivation" entry

### INFO value containing a bare `=`
- Test: `BcftoolsDependencyTest#testInfoValueContainingBareEqualsBcftoolsAcceptsSilently`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "INFO value containing a bare `=`" entry

### Duplicate INFO key within one record
- Test: `BcftoolsDependencyTest#testDuplicateInfoKeyBcftoolsKeepsFirstOccurrence`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Duplicate INFO key within one record" entry

### Duplicate ID/FILTER entries within one record
- Test: `BcftoolsDependencyTest#testDuplicateIdBcftoolsAcceptsSilently`,
  `#testDuplicateFilterCodeBcftoolsAcceptsSilently`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Duplicate ID/FILTER entries within one record" entry

### `FORMAT="."` meaning no per-sample fields
- Test: `BcftoolsDependencyTest#testFormatDotMeansNoPerSampleFieldsBcftoolsAcceptsAndInterprets`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`FORMAT=\".\"` meaning no per-sample fields" entry

### Data-line FORMAT key fails tag-name grammar
- Test: `BcftoolsDependencyTest#testDataLineFormatKeyInvalidTagNameBcftoolsWarnsAndContinues`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Data-line FORMAT key fails tag-name grammar" entry

### Undeclared `##FORMAT` `Number` attribute
- Test: `BcftoolsDependencyTest#testUndeclaredFormatNumberBcftoolsAssumesDot`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Undeclared `##FORMAT` `Number` attribute" entry

### `CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G`
- Test: `BcftoolsDependencyTest#testNumberGCardinalityNotValidatedByBcftoolsForAnyKey`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G`" entry

### Empty/zero-length delimited field handling
- Test: `BcftoolsDependencyTest#testEmptyAltEntryBcftoolsSubstitutesDotMatchingThisLibrarysOwnChoice`
  (representative for ALT specifically; not every field's own empty-entry rule is independently re-verified here)
- Reasoning: `EMPTY_FIELD_HANDLING.md` (its own field-by-field table is the fuller source of truth)

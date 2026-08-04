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
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`##pedigreeDB`/`##assembly` bracket collision" entry

## Other bcftools-dependent decisions

(best-effort coverage, not exhaustive)

### Duplicate attribute within one metadata declaration
- Test: `BcftoolsDependencyTest#testDuplicateAttributeWithinDeclarationBcftoolsKeepsLast`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Duplicate attribute within one metadata declaration" entry

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

### Generic metadata property with an empty value
- Test: `BcftoolsDependencyTest#testGenericPropertyEmptyValueBcftoolsPreservesButWeAreStricter`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Generic metadata property with an empty value" entry

### Duplicate `##PEDIGREE` lines with no `ID` property
- Test: `BcftoolsDependencyTest#testPedigreeNoIdDuplicatesBcftoolsKeepsBoth`
- Reasoning: `DUPLICATE_METADATA_ID_HANDLING.md`, "`##PEDIGREE`: a conditional case" section

### Duplicate metadata declaration ID (six keyed types)
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

### `FORMAT="."` meaning no per-sample fields
- Test: `BcftoolsDependencyTest#testFormatDotMeansNoPerSampleFieldsBcftoolsAcceptsAndInterprets`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`FORMAT=\".\"` meaning no per-sample fields" entry

### Undeclared `##FORMAT` `Number` attribute
- Test: `BcftoolsDependencyTest#testUndeclaredFormatNumberBcftoolsAssumesDot`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "Undeclared `##FORMAT` `Number` attribute" entry

### `CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G`
- Test: `BcftoolsDependencyTest#testNumberGCardinalityNotValidatedByBcftoolsForAnyKey`
- Reasoning: `VCF_SPEC_AMBIGUITIES.md`, "`CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G`" entry

### Empty/zero-length delimited field handling
- Test: `BcftoolsDependencyTest#testEmptyAltEntryBcftoolsSubstitutesDotUnlikeThisLibrarysOwnDropChoice`
  (representative for ALT specifically; not every field's own empty-entry rule is independently re-verified here)
- Reasoning: `EMPTY_FIELD_HANDLING.md` (its own field-by-field table is the fuller source of truth)

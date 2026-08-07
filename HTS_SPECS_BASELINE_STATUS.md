# hts-specs conformance baseline status

**This document is bound to `htsSpecsPinnedSha = da617203a9527537746e200abda2885bec3a822c` (see `build.gradle`).**
If that value has changed since, treat every row below as unverified until re-checked: hts-specs can add, remove,
rename, or reclassify fixtures across commits, silently invalidating the fixture-path/cause mapping here. To
re-verify: run `./gradlew htsSpecsUpdateBaseline`, then `git diff
src/test/resources/org/pharmgkb/parser/vcf/spec/hts-specs-baseline.tsv` against the fixture list in each row
below — update both this document and the rows before trusting it again.

The same re-verification pass must also check `HtsSpecsConformanceTest`'s `EXPECTED_WARNING_SUBSTRINGS` map
(`src/test/java/org/pharmgkb/parser/vcf/spec/HtsSpecsConformanceTest.java`):

1. **A new multi-line `failed/` fixture with no map entry** doesn't need a separate detection step —
   `htsSpecsTest` already hard-fails directly on it, naming the fixture and printing its actual captured
   warnings. Resolve it the way the map's own comment describes: cross-check the printed warnings against the
   fixture's `##CauseOfFailure=`, pick a specific substring, add an entry. Because this hard fail bypasses the
   baseline mechanism entirely, it also fires during the `./gradlew htsSpecsUpdateBaseline` run this
   re-verification pass starts with, so that run reporting `BUILD FAILED` doesn't mean the baseline update
   itself failed -- `HtsSpecsConformanceTest`'s `@AfterAll writeReports()` (which rewrites the baseline TSV in
   update mode) runs regardless of dynamic-test failures, so the baseline file is still correctly updated.
2. **A pin bump that changes an existing mapped fixture's content** so its substring stops matching surfaces as
   a normal `parser-warn-content` baseline finding through the usual `htsSpecsUpdateBaseline` workflow — nothing
   extra to do here beyond the existing baseline-diff review.
3. **A pin bump that removes or renames a mapped fixture** leaves its old map entry dead (the key never matches
   any current fixture, and nothing fails because of it). Check for and prune orphaned
   `EXPECTED_WARNING_SUBSTRINGS` entries during this same pass.

If cross-referencing fixture content directly against a local hts-specs clone (e.g. via `git show`/`git diff`
rather than through `./gradlew htsSpecsUpdateBaseline`), make sure that clone is on its `master` branch —
`master` is the branch that matches `htsSpecsPinnedSha` (verified: `master`'s HEAD *is* the pinned SHA). A
clone can be checked out to an arbitrary other branch (a personal fork branch with unmerged, in-progress
changes, for example) that doesn't reflect the pinned state at all, and comparing against that instead of
`master` will silently produce wrong conclusions about what this project's baseline is actually pinned to.

This is primarily an index, not a rationale document — where another doc already covers a row's reasoning
(`EMPTY_FIELD_HANDLING.md`, `DUPLICATE_METADATA_ID_HANDLING.md`, `VCF_SPEC_AMBIGUITIES.md`), point there instead
of restating it; if a row's linked doc doesn't exist or doesn't cover the claim, that's a sign this document
itself is stale. Some rows carry their own standalone rationale section below instead of a pointer — this is
correct, not a violation of the rule above, when the reasoning is specific to one hts-specs fixture's own
provenance or wording (e.g. "why this exact fixture's `##CauseOfFailure=` claim doesn't hold up") rather than a
general decision this library made that's already documented elsewhere.

## Provenance: 129 entries added by the 2026-08-02 detection-signal fix

A test-infrastructure bug in `HtsSpecsConformanceTest.assertThrowsOrWarns()` previously caused it to treat two
generic, content-independent warnings as proof that a `failed/*.vcf` fixture's invalidity was detected,
regardless of whether the fixture's actual documented violation was ever caught. Fixing that bug (commit
`66147d8`) surfaced 129 previously-hidden `parser-throw-or-warn` divergences in one pass. `./gradlew
htsSpecsUpdateBaseline` was run immediately to mechanically absorb all 129 into `hts-specs-baseline.tsv` and
keep the build green, then all 129 were individually triaged (bcftools 1.23.1 / vcf_validator 0.10.2 checked
directly, cross-referenced against this project's own established policy docs) and are catalogued in the
table below.

## `parser-throw-or-warn` entries (129: 10 pre-existing + 129 added 2026-08-02, 10 of the resulting 139 since fixed and removed) — `failed/` fixtures whose invalidity isn't detected

This check only runs `failed/` fixtures from VCF versions 4.1 and 4.2 (`HtsSpecsConformanceTest.java`'s
`FAILED_VERSIONS = List.of("4.1", "4.2")`) — 4.3's own `failed/` fixtures are never examined at all. This is
deliberate, not an oversight: a fixture's VCF-4.3-specific "failure" can be a rule 4.3 introduced that VCFv4.2
(this library's target) leaves silent, so a 4.3 `failed/` fixture parsing without error wouldn't be a real
divergence under this library's target semantics — there's nothing to gain by testing it. `passed/` fixtures
(the `writer-*` checks) are unaffected by this and still run across all four available versions (4.1, 4.2,
4.3, 4.5).

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Genome ID with whitespace/punctuation in `##PEDIGREE` | `failed_meta_pedigree_000.vcf`, `failed_meta_pedigree_001.vcf` (4.1, 4.2) | 4 | Candidate for upstream filing, not yet written | bcftools 1.23.1 accepts both silently (no rule enforced); `VCFv4.2.tex` line 152 gives no character grammar for genome IDs at all. Investigated but no issue doc written yet. |
| Non-valid URL / mislabeled cause | `failed_meta_assembly_001.vcf` (4.1), `failed_meta_pedigreedb_002.vcf` (4.1, 4.2) | 3 | Permanently accepted | See "Non-valid URL fixtures" below. |
| Incorrect ALT ID value (`ID=DEL1`) | `failed_meta_alt_000.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | A documented false negative: the reserved structural-variant-code hierarchy is only enforced when an ALT ID contains `:`, since a bare token has other spec-sanctioned uses (IUPAC codes, `CN0`-style copy-number codes). |
| Meta-data entry is not a key-value pair | `failed_meta_assembly_001.vcf` (4.2 only) | 1 | Permanently accepted | Same fixture/value as the URL row above, but this version copy has a different, inconsistent `##CauseOfFailure=` label than its 4.1/4.3 siblings — doesn't matter to us, both labels get the same treatment. See "Non-valid URL fixtures" below. |
| CHROM contains a colon | `failed_body_chrom_001.vcf` (4.1, 4.2) | 2 | Candidate for upstream filing, draft written | See "CHROM colon fixture" below. |
| Duplicate/triplicate identical variant records at the same POS | `failed_body_duplicated_000.vcf` (duplicated), `_001.vcf` (triplicated), `_002.vcf` (multiallelic-derived), `_003.vcf` (different-context) (4.1, 4.2) | 8 | Permanently accepted, deliberate | See "Duplicate/triplicate record fixtures" below. |
| PL/GL/GP cardinality-vs-ploidy mismatch not detected at parse time | `failed_body_samples_ploidy_000-003.vcf` (4.1, 4.2) | 8 | Permanently accepted | Fixed: `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` now performs this check lazily on typed read access. `HtsSpecsConformanceTest`'s automated harness still can't observe it — its no-op `parseWith` callback never calls a typed getter, the same structural limitation already documented for the CIGAR/negative-INFO-value rows above — confirmed via an empty `htsSpecsUpdateBaseline` diff, and covered instead by `VcfParserTest.testHtsSpecsPlCardinalityFixturesWarnOnTypedAccess`, which calls the new accessor directly. |
| Non-numeric QUAL | `failed_body_qual_000.vcf` (4.1, 4.2) | 2 | Permanently accepted | `VcfPosition.getQuality()` warns ("QUAL '{}' is not a number; treating it as the missing value") and falls back to `null` on first access — deferred validation catching the problem exactly per Parser Validation Policy. Confirmed with a scratch parse+`getQuality()` test. |
| Negative QUAL | `failed_body_qual_001.vcf` (4.1, 4.2) | 2 | Permanently accepted | See "Negative QUAL fixtures" below. |
| `##SAMPLE` missing `Genomes` field | `failed_meta_sample_000.vcf` (4.1, 4.2) | 2 | Permanently accepted | `VCFv4.2.tex` line 146's `##SAMPLE` template shows `Genomes` but no spec text (4.2-4.5) marks any `##SAMPLE` field but `ID` as mandatory; `IdDescriptionMetadata` (used for `##SAMPLE`) only models/validates `ID`/`Description`, treating `Genomes`/`Mixture` as unmodeled extra fields by design. bcftools 1.23.1 accepts silently; only vcf_validator 0.10.2 flags it (`Error: SAMPLE metadata Genomes is not a valid string...`). |
| `##SAMPLE` non-`Description` field quoted (`Mixture="..."`) | `failed_meta_sample_001.vcf` (4.1, 4.2) | 2 | Permanently accepted | Same unmodeled-extra-field reasoning as the row above. Verified directly: parsing preserves the raw quoted value and `getPropertyUnquoted("Mixture")` correctly strips it to the intended value, with lossless round-trip via `VcfWriter`'s raw-property passthrough — no data loss or misalignment. bcftools 1.23.1 accepts silently; only vcf_validator 0.10.2 flags it (`Error: SAMPLE metadata Mixture is not a valid string...`). |
| FORMAT key contains an underscore, flagged as "non-alphanumeric" | `failed_body_format_003.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | `InternalUtils.FORMAT_PATTERN` (`^[A-Za-z_][0-9A-Za-z_.]*$`, cited to `VCFv4.3.tex` line 429 as VCFv4.2-silent clarifying guidance) already permits `_` in a FORMAT key — the fixture's `G_S` key matches it. bcftools 1.23.1 accepts `GT:G_S:GL` silently (no charset complaint). vcf_validator 0.10.2 reports it invalid ("Format is not a colon-separated list of alphanumeric strings"), reading "alphanumeric" more literally than the spec's own cited regex allows; bcftools is the tie-breaker per `AGENTS.md`. |
| GT contains an interior empty allele between two adjacent separators (`0/\|1`) | `failed_body_format_004.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate — same divergence as `EMPTY_FIELD_HANDLING.md`'s existing GT row | `VcfSample.getGenotype`'s manual separator scan applies the identical warn-and-substitute-`.` treatment to an empty allele regardless of position (interior or trailing) — confirmed directly: `0/\|1` parses to alleles `[C, ., T]` with the same "GT ... contains an empty allele" warning already documented for the trailing case (`0/`). bcftools 1.23.1 hard-errors (exit code 8), the same error class already cited there; vcf_validator 0.10.2 reports "Allele index must not be empty." See `EMPTY_FIELD_HANDLING.md`'s GT row rather than a new explanation. |
| GT semantic-content violations (illegal allele value, out-of-range allele index, non-genotype-shaped first field) | `failed_body_sample_000.vcf` (`1/C`), `_001.vcf` (`0/3`, only 2 valid indices), `_002.vcf` (`0.000`) (4.1, 4.2) | 6 | Permanently accepted | `VcfSample.getProperty(ReservedFormatProperty.Genotype)` returns GT's raw String unvalidated, but `VcfSample.getGenotype(VcfPosition)` — the library's typed Genotype-interpretation method — throws `VcfFormatException` for all three (a non-numeric allele token, or an index exceeding `position.getAltBases().size()` per `VCFv4.2.tex` line 217's allele-indexing rule), and is reached via `MemoryMappedVcfDataStore.getGenotypeForId`/`getGenotypeAtLocus`. Confirmed with a scratch parse+`getGenotypeForId()` test. Matches Parser Validation Policy's deferred-validation-via-typed-conversion design. bcftools 1.23.1 hard-errors on `_000.vcf`/`_002.vcf` but is silent on `_001.vcf`'s out-of-range allele; vcf_validator 0.10.2 flags all three invalid. |
| Sample FORMAT value's element count doesn't match its declared `Number` (a non-reserved key) | `failed_body_sample_004.vcf` (`DS`, Number=1, 2 values), `_005.vcf` (`AL`, Number=A), `_006.vcf` (`AL`, Number=G), `_010.vcf` (`AL`, Number=R; 4.2 only) | 7 | Permanently accepted | Fixed: `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` now resolves a non-reserved key's `Number` from its own `##FORMAT` declaration and checks cardinality the same way as a reserved key. Same lazy-check/no-op-callback limitation as the PL/GL/GP row above applies to `HtsSpecsConformanceTest`'s automated harness — confirmed via an empty `htsSpecsUpdateBaseline` diff, covered instead by `VcfParserTest.testHtsSpecsNonReservedFormatNumberFixturesWarnOnTypedAccess`. bcftools 1.23.1 is silent on all four fixtures; vcf_validator 0.10.2 flags all four invalid with correct expected-vs-actual counts. |
| Sample FORMAT value doesn't match its declared `Type` (a non-reserved key) | `failed_body_sample_007.vcf` (`DS`, Type=Float, `my_string`), `_008.vcf` (Type=Integer), `_009.vcf` (Type=Character) (4.1, 4.2) | 6 | Permanently accepted | Fixed: `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` now converts a non-reserved key's value using its declared `##FORMAT` `Type`, throwing `VcfFormatException` on a genuine mismatch (unchanged from `getProperty(ReservedFormatProperty)`'s existing throw-on-mismatch behavior for a reserved key). Covered by `VcfParserTest.testHtsSpecsNonReservedFormatTypeFixturesThrowOnTypedAccess`. bcftools 1.23.1 hard-errors for `_007.vcf`/`_008.vcf` but is silent on `_009.vcf`'s Character-type mismatch; vcf_validator 0.10.2 flags all three invalid. |
| INFO value is not a flag (`1000G`, `DB`, `H2`, `H3`, `SOMATIC`, `VALIDATED`) | `failed_body_info_012.vcf`, `_019.vcf`, `_020.vcf`, `_025.vcf`, `_026.vcf`, `_027.vcf` (4.1, 4.2) | 12 | Permanently accepted | `InternalUtils.convertElement`'s `Boolean` branch throws `VcfFormatException` for any value other than `0/1/true/false`, reached on `VcfPosition.getInfo(ReservedInfoProperty)` for each of these reserved Flag-typed keys — deferred validation working as designed. vcf_validator 0.10.2 independently confirms each invalid ("does not match the specification Type=Flag"); bcftools 1.23.1 is silent (no reserved-key semantic checks). |
| INFO reserved Integer/Float value is a floating point number, or is not a number (`AC`, `AN`, `DP`, `END`, `MQ0`, `NS`, `BQ`, `AF`) | `failed_body_info_002.vcf`, `_003.vcf`, `_005.vcf`, `_007.vcf`, `_008.vcf`, `_009.vcf`, `_014.vcf`, `_015.vcf`, `_017.vcf`, `_018.vcf`, `_022.vcf`, `_024.vcf` (4.1, 4.2) | 24 | Permanently accepted | `Long.parseLong`/`VcfFloat.parse` throw `VcfFormatException` for a non-numeric or wrongly-shaped value, reached on `VcfPosition.getInfo(ReservedInfoProperty)` for each of these reserved keys. vcf_validator 0.10.2 independently confirms each invalid; bcftools 1.23.1 is silent. |
| INFO contains a whitespace | `failed_body_info_028.vcf` (4.1, 4.2) | 2 | Permanently accepted | `VcfPosition.checkInfoEntries` (run from the lazy `info()` parse on first `getInfo()`/`getInfo(key)` access) throws `VcfFormatException` for whitespace in an INFO key or value — deferred, not eager at `parseLine()`, matching the Parser Validation Policy. vcf_validator 0.10.2 confirms invalid ("Info key is not a sequence of alphanumeric and/or punctuation characters"). |
| INFO field does not match Number/Type definition in meta section (custom key `MY`), including one fixture mislabeled "Sample field does not match Number definition in meta section" | `failed_body_info_029.vcf`, `_030.vcf`, `_031.vcf`, `_033.vcf` (4.1, 4.2), `_034.vcf`, `_035.vcf` (4.1 only) | 10 | Permanently accepted, via `validateBeforeWrite()` not the plain getter | `MY` is a non-reserved key with no typed accessor; `getInfo(String)` never validates it. `VcfWriter.Builder().validateBeforeWrite()`'s `validateInfoValue()` — the library's documented full-output-diagnostics mode — catches every one of these against the field's own `##INFO` declaration (Flag-with-value, Number cardinality including `R`, and Type mismatches for Float/Integer/Character), confirmed live. vcf_validator 0.10.2 independently confirms each invalid. |
| INFO `AA` (AncestralAllele) contains a comma | `failed_body_info_000.vcf` (4.1, 4.2) | 2 | Permanently accepted | Fixed: `VcfPosition.getInfo(ReservedInfoProperty)` now detects a scalar (`isList=false`) reserved key with more than one comma-separated raw value, warns ("INFO {} has {} value(s), but Number={} requires {}"), and keeps only the first — `AA=1,23` now warns and returns `"1"`, not the previously-silent rejoined `"1,23"`. Same structural limitation as the PL/GL/GP row above: `HtsSpecsConformanceTest`'s no-op `parseWith` callback never calls a typed getter, so it can't observe this — confirmed via an empty `htsSpecsUpdateBaseline` diff, and covered instead by a direct unit test, `VcfPositionTest.testGetInfoStringTypedScalarKeyCardinalityViolationWarnsAndKeepsFirstValue`. `VcfWriter.validateBeforeWrite()` only catches this if the caller explicitly declares `##INFO=<ID=AA,...>` in the header (most real files, and this fixture, don't). vcf_validator 0.10.2 confirms invalid ("does not match the specification Number=1"); bcftools 1.23.1 is silent. |
| INFO reserved Integer/Float value is a negative number (`AC`, `AN`, `DP`, `END`, `MQ0`, `NS`, `AF`), including "AC is a negative number in several lines" | `failed_body_info_001.vcf`, `_004.vcf`, `_006.vcf`, `_013.vcf`, `_016.vcf`, `_021.vcf`, `_023.vcf`, `_036.vcf` (4.1, 4.2) | 16 | Permanently accepted | `InternalUtils.checkReservedInfoConstraints` now warns for a negative value ("is a negative number", or for `AF`, "does not lie in the interval [0, 1]"), reached on `VcfPosition.getInfo(ReservedInfoProperty)` (read) and `VcfWriter.validateInfoValue()` (write/diagnostics) -- the same dual-reachability pattern already established for `FORMAT/FT`/`FORMAT/PS` (see `VALIDATION_REFERENCE.md`). This row stays in the baseline (not removed by `htsSpecsUpdateBaseline`) for the same structural reason the "floating point number, or is not a number" row above it does: the check is intentionally lazy/deferred per this library's Parser Validation Policy, but `HtsSpecsConformanceTest`'s own `assertThrowsOrWarns` only exercises a no-op `parseWith` callback that never calls any typed getter, so it can't observe a check that only fires on explicit typed access. Confirmed directly: reparsing each of these 7 fixtures (excluding `_036.vcf`, a multi-line variant of `_001.vcf`) and calling `getInfo()` for the fixture's own key does produce the expected warning (see `VcfParserTest.testHtsSpecsNegativeReservedInfoValueFixturesWarnOnTypedAccess`). vcf_validator 0.10.2 confirms invalid ("value must be a non-negative integer number" / AF: "does not lie in the interval [0,1]"); bcftools 1.23.1 is silent. See `VCF_SPEC_AMBIGUITIES.md`'s "Negative/out-of-range values for reserved INFO keys `AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF`" entry for the decision rationale. |
| INFO `CIGAR` contains illegal characters, or is a number | `failed_body_info_010.vcf`, `_011.vcf` (4.1, 4.2) | 4 | Permanently accepted | `InternalUtils.checkReservedInfoConstraints` now warns for a value that doesn't match `InternalUtils.CIGAR_PATTERN` (a lone `*`, or one or more `<length><operation>` pairs), reached on `VcfPosition.getInfo(ReservedInfoProperty.Cigar)` (read) and `VcfWriter.validateInfoValue()` (write/diagnostics) — the same dual-reachability pattern as the negative-number row above. This row stays in the baseline (not removed by `htsSpecsUpdateBaseline`) for the identical structural reason that row does: the check is intentionally lazy/deferred, but `HtsSpecsConformanceTest`'s own `assertThrowsOrWarns` only exercises a no-op `parseWith` callback that never calls a typed getter, so it can't observe this class of check. Confirmed directly: reparsing both fixtures and calling `getInfo(ReservedInfoProperty.Cigar)` does produce the expected warning (see `VcfParserTest.testHtsSpecsInvalidCigarValueFixturesWarnOnTypedAccess`). No VCF spec version (4.2 through 4.5, all checked directly) ties `CIGAR`'s grammar to the SAM specification — vcf_validator 0.10.2's own message text ("value is not an alphanumeric string compliant with the SAM specification") is the only source connecting the two; bcftools 1.23.1 is silent. See `VCF_SPEC_AMBIGUITIES.md`'s "CIGAR grammar (SAM compliance)" entry for the decision rationale. |

## Negative QUAL fixtures: why permanently accepted

`failed_body_qual_001.vcf` sets `##CauseOfFailure=QUAL is a negative number` (`QUAL=-100`).
`VcfPosition.getQuality()` does *not* catch this: `VcfFloat`'s `FLOAT_PATTERN` explicitly permits a leading
`-`, so a negative QUAL parses as an ordinary valid `BigDecimal` with no warning and no sentinel fallback —
confirmed with a scratch parse+`getQuality()` test returning `-100` with zero captured warnings. Despite that
internal asymmetry with the sibling non-numeric-QUAL row above, this is not treated as a reportable finding,
because neither reference tool this project defers to actually rejects a negative QUAL either: bcftools
1.23.1 (htslib 1.23.1) passes `-100` through unchanged with no warning, and vcf_validator 0.10.2 reports the
fixture (and a hand-built minimal 4.2 file with `QUAL=-5`) as valid — even though vcf_validator's own error
text for the sibling non-numeric-QUAL fixture claims to require "a single dot or a positive number," its
check does not actually fire for a negative value, an inconsistency in the tool itself. `VCFv4.2.tex` line
184 defines QUAL only as `(Numeric)` with no sign constraint or grammar. Since none of the three authoritative
references this project defers to (target spec text, bcftools, vcf_validator) enforces non-negativity, the
hts-specs fixture's own `##CauseOfFailure=` claim is unsupported — analogous to the "Non-valid URL fixtures"
precedent above.

## Duplicate/triplicate record fixtures: why permanently accepted

Four fixtures document repeated/near-repeated records as invalid: `failed_body_duplicated_000.vcf` (the
exact same `1:123 C>G` record appears twice, byte-for-byte identical including sample data,
`##CauseOfFailure=duplicated variant`), `_001.vcf` (six records sharing one `ID` across six different
positions, `triplicated variant`), `_002.vcf` (two adjacent records, `AT→AAT,AA` at POS 123 and `T→A` at POS
124 — exactly the shape a multiallelic-decomposition tool produces, `duplicated variant due to
multiallelic`), and `_003.vcf` (two adjacent records representing the same variant under two different
left-alignment contexts, `duplicated variant due to different context`).

`VCFv4.2.tex` line 180 explicitly permits multiple records at the same POS ("It is permitted to have multiple
records with the same POS"); VCFv4.3.tex/4.4.tex/4.5.tex (checked through 4.5, the newest version in this
project's local hts-specs clone) repeat this sentence verbatim, with no version ever adding language
addressing duplicate or near-duplicate records. `_002.vcf`'s own cause text names exactly the legitimate
multiallelic-decomposition case this would risk rejecting if enforced generally. Both reference tools this
project defers to independently treat all four fixtures as valid: bcftools 1.23.1 (htslib 1.23.1) exits 0 on
all four with no duplicate-related warning (only the generic, unrelated "contig/INFO/FORMAT not defined in
header" warnings every fixture in this suite triggers), and vcf_validator 0.10.2 reports all four as valid
("According to the VCF specification, the input file is valid"). Since neither the target spec, its later
clarifying versions, nor either reference tool treats any of these four as invalid, this reads as a
vcf_validator-conformance-suite-specific convention with no independent support, the same shape as the
"Non-valid URL fixtures" precedent above.

A cheap, hot-path-compatible check (comparing each record's CHROM/POS/REF/ALT against only the immediately
preceding record, reusing the same O(1) per-record state a future CHROM-contiguity/POS-ordering check would
also need) would catch `_000.vcf`'s exact-duplicate case, but not the other three: `_001.vcf`'s repeats are
non-adjacent, and `_002.vcf`/`_003.vcf` require variant-normalization logic (left-alignment/trimming,
generally needing reference-genome context) to recognize as equivalent — a materially larger feature this
library isn't designed to provide, not a hot-path-cheap addition. Implementing the partial, `_000`-only
version was considered and rejected as not worth the inconsistency (catching one of four documented patterns
while leaving the rest permanently accepted anyway).

## `writer-bytediff` entries (9) — `passed/` fixtures whose round trip isn't byte-identical

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Metadata declaration reordering | `complexfile_passed_000.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | `VcfWriter.buildHeaderLines()` has a pre-existing, fixed per-type metadata grouping order (INFO, FILTER, FORMAT, ALT, contig, SAMPLE, PEDIGREE, then generic properties); this fixture interleaves types in a different order. |
| `QUAL` scientific-notation case | `passed_body_qual.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | `2e+1` round-trips as `2E+1` — `BigDecimal`'s own string formatting. |
| Duplicate `##contig` line dropped | `passed_meta_contig.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | Direct, intended consequence of the duplicate-metadata-ID keep-first policy. See `DUPLICATE_METADATA_ID_HANDLING.md`. |
| Empty sample value substituted | `zero_length_LAA.vcf` (4.5) | 1 | Permanently accepted | Matches this library's established empty-field normalization policy. See `EMPTY_FIELD_HANDLING.md`. |

## Non-valid URL fixtures: why permanently accepted

Several `failed/` fixtures use `##CauseOfFailure=Non-valid URL` for an `##assembly=`/`##pedigreeDB=`
value whose only distinguishing feature is an all-digit hostname (`ftp://8080:8080/...`). That string
is syntactically valid per RFC 3986 (a `reg-name` may be all-digits) and is accepted without complaint
by both `java.net.URI`/`URL` (OpenJDK 25) and `bcftools view` 1.23.1 (htslib 1.23.1) — confirmed
directly: `bcftools view test/vcf/4.2/failed/failed_meta_pedigreedb_002.vcf` prints no error or
warning for its `##pedigreeDB=` line. Neither VCFv4.1, VCFv4.2, nor VCFv4.3 defines a formal grammar
for the `url` value in `##assembly=url` / `##pedigreeDB=URL` (VCFv4.2.tex describes both only as prose
examples, with no BNF or validity rule).

Affected fixtures:

| Fixture | Version(s) | `##CauseOfFailure=` |
|---|---|---|
| `failed_meta_assembly_001.vcf` | 4.1 | `Non-valid URL` |
| `failed_meta_assembly_001.vcf` | 4.2 (file internally declares `VCFv4.3` despite living under the `4.2/` directory), 4.3 | `Meta-data entry is not a key-value pair` |
| `failed_meta_pedigreedb_002.vcf` | 4.1, 4.2, 4.3 | `Non-valid URL` (consistent across versions) |

## CHROM colon fixture: candidate for upstream filing

`failed_body_chrom_001.vcf`'s `##CauseOfFailure=contig ID contains a colon` claim is directly contradicted by
`VCFv4.3.tex`'s own "changes since previous version" appendix (~line 2135-2141, unchanged in VCFv4.4/4.5):

> "The VCF specification previously disallowed colons (`:`) in contig names to avoid confusion when parsing
> breakends, but this was unnecessary. Even with contig names containing colons, the breakend mate position
> notation can be unambiguously parsed because the `:pos` part is always present."

This is used as VCFv4.2-silent clarifying guidance per `AGENTS.md`'s decision hierarchy (VCFv4.2 has no
character-set grammar for CHROM beyond forbidding whitespace). Confirmed directly with bcftools 1.23.1 and
vcf_validator 0.10.2 against this exact fixture: both tools accept it without complaint (bcftools exit 0,
silent; vcf_validator reports the file valid even at its `warning` verbosity level). `VcfPosition.checkChromosome`
deliberately excludes colon from its invalid-character set for this reason — see the citation comment on
`InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN`.

This fixture was added in 2021 (commit `7aeed5b`), over a year after the 2020 spec correction it contradicts
(commit `0ef79c0`) — a full upstream-filing write-up (with reproduction steps and a suggested resolution) has
already been drafted locally but not yet filed.

Affected fixture:

| Fixture | Version(s) | `##CauseOfFailure=` |
|---|---|---|
| `failed_body_chrom_001.vcf` | 4.1, 4.2 | `contig ID contains a colon` |

## Contig ID asterisk fixture (4.3-only, out of automated scope): candidate for upstream filing, and why it's also stale

`test/vcf/4.3/failed/failed_meta_contig_003.vcf` declares `##contig=<ID=1.*,length=123456>` with
`##CauseOfFailure=contig ID contains illegal character`. This fixture only exists under `4.3/failed/` (no
4.1/4.2 counterpart), so `HtsSpecsConformanceTest`'s automated check -- which only examines `failed/`
fixtures for `FAILED_VERSIONS = List.of("4.1", "4.2")` -- never examines it, and it has no row in
`hts-specs-baseline.tsv`. Confirmed: regenerating the baseline after `ContigMetadata`'s character-set fix
produces an empty diff.

Independent of the automated-scope question, this fixture's claimed defect is also stale, per the same
`VCFv4.3.tex` errata cited in the "CHROM colon fixture" section above: `*` is invalid only as the first
character of a contig name, and in `1.*` it's the third. This isn't just spec-text-vs-one-fixture --
hts-specs' own suite disagrees with itself on the identical ID string: the 4.1 and 4.2 copies of
`passed/passed_meta_contig.vcf` (fixtures expected to parse cleanly) both contain the literal line
`##contig=<ID=1.*,length=123456>`, while the 4.3 `failed/` fixture added in the same original commit claims
that exact string is invalid. Confirmed with direct `bcftools 1.23.1` testing that bcftools accepts a
contig ID with `*` in a non-leading position silently, and only warns when `*` (or `=`) is the leading
character -- matching the amended rule, not the fixture's claim.

This is not tracked as a baseline gap (per the automated-scope point above), but is documented here so a
future reader who greps for "contig"/"asterisk" finds the reasoning. Both this fixture and the CHROM-colon
fixture above were introduced in the same 2021 hts-specs commit (`7aeed5b`); a combined upstream-filing draft
covering both already exists locally but has not yet been filed.

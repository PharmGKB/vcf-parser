# hts-specs conformance baseline status

**This document is bound to `htsSpecsPinnedSha = da617203a9527537746e200abda2885bec3a822c` (see `build.gradle`).**
If that value has changed since, treat every row below as unverified until re-checked: hts-specs can add, remove,
rename, or reclassify fixtures across commits, silently invalidating the fixture-path/cause mapping here. To
re-verify: run `./gradlew htsSpecsUpdateBaseline`, then `git diff
src/test/resources/org/pharmgkb/parser/vcf/spec/hts-specs-baseline.tsv` against the fixture list in each row
below — update both this document and the rows before trusting it again.

## What a green gate does and does not prove

`Baseline.Entry.key()` is `fixturePath + "\t" + checkType` — **and nothing else**. A row matches on identity
alone, so the gate answers *"does this fixture still diverge on this check?"* and, on its own, never answered
*"does it diverge in the same way?"*. Neither `category` nor `reason` took part in the comparison.

That let a real behavior change sit unrecorded for six days and roughly twenty commits: `d8024e0`
(2026-08-04, "narrow generic metadata property bracket-value rejection to what actually breaks bcftools")
made the writer drop `##AnalysisTitleBrackets=<"FINRISK: …">` from
`test/vcf/{4.1,4.2}/passed/complexfile_passed_000.vcf`, because a bracket-wrapped value containing no `=` is
one bcftools cannot parse (see `VCF_SPEC_AMBIGUITIES.md`'s "`##pedigreeDB`/`##assembly` bracket-wrapped values
colliding with attribute-list grammar"). Dropping it is correct and deliberate. But losing one header line
shifts every line after it, taking that fixture's round trip from `26 of 75 lines differ` to `57 of 75` — and
the gate reported `0 new/stale, 0 rows moved` throughout, because the row's identity never changed. The 4.3
variant was unaffected: its `##AnalysisTitleStructured=<ID=FINRISK,Description="…">` contains an `=`, so it
survives.

The baseline was resynchronised at that point, so every row's `category` and `reason` reflect current
behavior again.

**This is a distinct blind spot from the deferred-validation one** documented per-row below (the harness's
no-op `parseWith` callback never calls a typed getter, so lazily-checked violations read as
`silently-accepted`). That one is about *what the harness exercises*; this one was about *what the comparison
compared*.

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

## Fixture numbering is not stable across hts-specs version directories

**hts-specs renumbers `failed/` fixtures between version directories, and can even reuse a filename for an
entirely unrelated fixture.** A filename-keyed comparison against this document's existing rows over-counts
new findings and under-counts replicated ones *simultaneously* -- treat a same-named 4.3 fixture as automatically
"the same as its 4.1/4.2 namesake" and you'll miss the cases where it's actually a different defect; treat it
as automatically new and you'll duplicate a row that already exists. Key every row on its `##CauseOfFailure=`
text plus the fixture's actual content, never on its filename, and search this document for that cause before
adding a new row:

```
rg -n '<cause text>' HTS_SPECS_BASELINE_STATUS.md
```

Two worked examples from the 2026-08-10 VCF 4.3 target-flip expansion (below), both confirmed by diffing the
fixture bodies directly against their 4.1/4.2 counterparts:

- **Renumbered, same defect:** `test/vcf/4.3/failed/failed_body_format_006.vcf` is byte-for-byte the same `GT`
  interior-empty-allele fixture (`0/|1`) as `test/vcf/{4.1,4.2}/failed/failed_body_format_004.vcf`, down to the
  identical `##CauseOfFailure=FORMAT predefined tag GT has missing alleles` line -- just filed under a different
  number in the 4.3 directory. It's folded into that row above, not given a new one.
- **Same filename, unrelated defect:** `test/vcf/4.3/failed/failed_body_info_033.vcf` reuses the exact filename
  of `test/vcf/{4.1,4.2}/failed/failed_body_info_033.vcf` (an `INFO field does not match Number/Type definition`
  fixture, already in the table above), but its actual content and `##CauseOfFailure=` are entirely different
  (`INFO contains duplicate keys`, `AA=1;AA=23`) -- a case a filename-keyed diff would have silently merged into
  the wrong row. It gets its own row in the "New 4.3-only clusters" table below instead.

## Provenance: 75 entries added by the 2026-08-10 VCF 4.3 target-flip expansion

The target VCF specification moved from 4.2 to 4.3 (this phase's Task 1). `HtsSpecsConformanceTest.java`'s
`FAILED_VERSIONS` was extended from `List.of("4.1", "4.2")` to `List.of("4.1", "4.2", "4.3")` (this phase's
Task 2) to match: 4.3's own `failed/` corpus (224 fixtures) is now in scope for the `parser-throw-or-warn`
check, for the same reason 4.1/4.2 always were -- a fixture failing under the library's *current* target
version is a real divergence worth tracking, not a false failure. (This also means the "Contig ID asterisk
fixture" section below, which documented `failed_meta_contig_003.vcf` as out of the automated check's scope,
is now stale on that specific point -- the fixture has a `hts-specs-baseline.tsv` row as of this expansion.
Reconciled by this phase's Task 3 -- see that section's updated heading and the "New 4.3-only
`parser-throw-or-warn` clusters" table above.)

Running `./gradlew htsSpecsUpdateBaseline` against the expanded `FAILED_VERSIONS` mechanically absorbed 75
new `parser-throw-or-warn` divergences on 4.3 `failed/` fixtures (verified additions-only: `git diff` on
`hts-specs-baseline.tsv` showed 75 insertions and zero removed/modified lines). None of the 129-row
2026-08-02 catalog below changed, and neither did the `writer-bytediff`/`writer-semantic` rows discussed in
"What a green gate does and does not prove" above. As with that fix, this pass's 75 rows are recorded here
mechanically to keep the gate green; per-cluster triage (bcftools/vcf_validator consultation, permanently
accepted vs. candidate-for-filing, etc.) is a follow-up task in this same phase, not done here.

At the time of this expansion, the frozen total moved from 617 to **845** (`build/reports/hts-specs-
conformance/summary.md`'s `**Total**` row, confirmed via a plain `./gradlew htsSpecsTest` run reporting
`845/845 checks match baseline, 0 new/stale, 0 per-version row(s) moved`). That snapshot is retained above
as narrative context for this provenance section; it is not the table's current state.

The table below instead reflects the gate's *current* total, confirmed by running `./gradlew htsSpecsTest`
and reading `build/reports/hts-specs-conformance/summary.md`'s `**Total**` row: **1435**. Three later changes
drove the jump from 845 to 1435 and were never folded back into this table until now: (a) the
`writer-semantic` column's own count grew from 25/25/25/1 to its current, much larger values when a later
phase added the `failed/`-fixture semantic-check factory (`failedFixturesRoundTripSemantically`, see the
`writer-semantic` section below) on top of the original `passed/`-only scope this table was last synced
against (that original scope's 25/25/25/1 matches `writer-bytediff`'s own counts, since both checked the
same `passed/` fixture set); (b) the `2026-08-26-0141-writer-round-trip-oracle-gaps-r7-plan.md` plan added the
`writer-warning-invariant` column itself (22/22/22/-- = 66 checks, one per `passed/` fixture that parsed with
zero warnings); and (c) the `2026-08-26-1117-declared-version-format-cache-metamorphic-coverage-r8-plan`
plan's Task 10 reworked that check to run on *every* `passed/` fixture, not just the ones that parsed
cleanly, raising `writer-warning-invariant`'s per-version counts to 25/25/25/1 (10 more checks -- see below
for what the strengthened check does, and the "`writer-warning-invariant` entries" section for the 5 new
divergences it surfaced).

| Version | parser-no-throw | parser-throw-or-warn | parser-warn-content | writer-bytediff | writer-semantic | writer-warning-invariant |
|---|---|---|---|---|---|---|
| 4.1 | 25 | 193 | 3 | 25 | 188 | 25 |
| 4.2 | 25 | 190 | 3 | 25 | 185 | 25 |
| 4.3 | 25 | 224 | 4 | 25 | 216 | 25 |
| 4.5 | 1 | -- | -- | 1 | 1 | 1 |

`writer-warning-invariant` reparses every `passed/` fixture's written output and asserts that the reparse's
full warning set is identical to the original parse's warning set -- not merely that reparsing introduces no
*new* warning, which was this check's original, weaker form before Task 10 (see above) strengthened it to a
full before/after set comparison.

4.3's `parser-throw-or-warn` total (224) is every 4.3 `failed/` fixture; of those, 75 are the newly-baselined
divergences and the rest already throw or warn correctly. 4.3's `parser-warn-content` count of 4 reflects all
four `EXPECTED_WARNING_SUBSTRINGS` entries now covering 4.3 fixtures (three twins of existing 4.1/4.2 entries
plus one new entry for `failed_body_format_007.vcf`, which has no twin).

This total is frozen again under the same rule as every prior baseline change in this project (see AGENTS.md
and "What a green gate does and does not prove" above): `hts-specs-baseline.tsv` is edited going forward only
to *remove* a row that legitimately stops reproducing, never to re-baseline over a genuine behavior change.

`htsSpecsPinnedSha` is unchanged at `da617203a9527537746e200abda2885bec3a822c` -- this expansion is driven
entirely by the target-version change, not a pin bump.

## Provenance: 129 entries added by the 2026-08-02 detection-signal fix

A test-infrastructure bug in `HtsSpecsConformanceTest.assertThrowsOrWarns()` previously caused it to treat two
generic, content-independent warnings as proof that a `failed/*.vcf` fixture's invalidity was detected,
regardless of whether the fixture's actual documented violation was ever caught. Fixing that bug (commit
`66147d8`) surfaced 129 previously-hidden `parser-throw-or-warn` divergences in one pass. `./gradlew
htsSpecsUpdateBaseline` was run immediately to mechanically absorb all 129 into `hts-specs-baseline.tsv` and
keep the build green, then all 129 were individually triaged (bcftools 1.23.1 / vcf_validator 0.10.2 checked
directly, cross-referenced against this project's own established policy docs) and are catalogued in the
table below.

## `parser-throw-or-warn` entries (10 pre-existing + 129 added 2026-08-02 − 10 of the resulting 139 since fixed and removed = 129 for 4.1/4.2 as of that pass; the 2026-08-10 expansion folded 59 more 4.3 fixture instances into these same rows, bringing the table's `Count` column to 188 today) — `failed/` fixtures whose invalidity isn't detected

As of the 2026-08-10 expansion documented above, this check runs `failed/` fixtures from VCF versions 4.1,
4.2, and 4.3 (`HtsSpecsConformanceTest.java`'s `FAILED_VERSIONS = List.of("4.1", "4.2", "4.3")`) — capped at
the library's current spec target, whatever that is. This is deliberate, not an oversight: a fixture's
"failure" under a version *later* than the target can be a rule that later version introduced which the
target leaves silent, so such a fixture parsing without error wouldn't be a real divergence under this
library's target semantics — there's nothing to gain by testing it. `passed/` fixtures (the `writer-*` checks)
are unaffected by this distinction and still run across all four available versions (4.1, 4.2, 4.3, 4.5).

**Read "Fixture numbering is not stable across hts-specs version directories" below before treating any 4.3
fixture as new.** The 75 rows the 2026-08-10 expansion added (see the provenance section above) were triaged
in a follow-up pass keyed on `##CauseOfFailure=` text plus fixture content, not filename: 59 of them are the
same defect as an existing 4.1/4.2 row already in the table below, just replicated onto a (frequently
renumbered) 4.3 fixture, and are folded into that row's `Fixtures`/`Count` columns rather than getting a row
of their own. The other 16 are recorded as their own new rows immediately after the table: all 16 are triaged
here (`INFO` 32-bit integer range, no trailing newline, `##META`, `INFO` duplicate keys, and -- since landed --
`##PEDIGREE` missing `ID`, fixed by this phase's Task 4, and the contig-asterisk pair, ruled on by this phase's
Task 5).

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Genome ID with whitespace/punctuation in `##PEDIGREE` | `failed_meta_pedigree_000.vcf`, `failed_meta_pedigree_001.vcf` (4.1, 4.2, 4.3) | 6 | Candidate for upstream filing, not yet written | bcftools 1.23.1 accepts both silently (no rule enforced); `VCFv4.2.tex` line 152 gives no character grammar for genome IDs at all. Investigated but no issue doc written yet. The 4.3 copies use a different (`ID=`/`Father=`/`Mother=`) `##PEDIGREE` shape and a slightly reworded `##CauseOfFailure=` ("Value with whitespace"/"Value with non-accepted punctuation character" vs. 4.1/4.2's "Genome ID with ..."), but the underlying defect — an unenforced genome-ID character grammar — is identical. |
| Non-valid URL / mislabeled cause | `failed_meta_assembly_001.vcf` (4.1), `failed_meta_pedigreedb_002.vcf` (4.1, 4.2, 4.3) | 4 | Permanently accepted | See "Non-valid URL fixtures" below. |
| Incorrect ALT ID value (`ID=DEL1`) | `failed_meta_alt_000.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | A documented false negative: the reserved structural-variant-code hierarchy is only enforced when an ALT ID contains `:`, since a bare token has other spec-sanctioned uses (IUPAC codes, `CN0`-style copy-number codes). |
| Meta-data entry is not a key-value pair | `failed_meta_assembly_001.vcf` (4.2, 4.3) | 2 | Permanently accepted | Same fixture/value as the URL row above, but this version copy has a different, inconsistent `##CauseOfFailure=` label than its 4.1 sibling — doesn't matter to us, both labels get the same treatment. See "Non-valid URL fixtures" below. |
| CHROM contains a colon | `failed_body_chrom_001.vcf` (4.1, 4.2, 4.3) | 3 | Candidate for upstream filing, draft written | See "CHROM colon fixture" below. |
| Duplicate/triplicate identical variant records at the same POS | `failed_body_duplicated_000.vcf` (duplicated), `_001.vcf` (triplicated), `_002.vcf` (multiallelic-derived), `_003.vcf` (different-context) (4.1, 4.2, 4.3) | 12 | Permanently accepted, deliberate | See "Duplicate/triplicate record fixtures" below. |
| PL/GL/GP cardinality-vs-ploidy mismatch not detected at parse time | `failed_body_samples_ploidy_000-003.vcf` (4.1, 4.2, 4.3) | 12 | Permanently accepted | Fixed: `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` now performs this check lazily on typed read access. `HtsSpecsConformanceTest`'s automated harness still can't observe it — its no-op `parseWith` callback never calls a typed getter, the same structural limitation already documented for the CIGAR/negative-INFO-value rows above — confirmed via an empty `htsSpecsUpdateBaseline` diff, and covered instead by `VcfParserTest.testHtsSpecsPlCardinalityFixturesWarnOnTypedAccess`, which calls the new accessor directly. |
| Non-numeric QUAL | `failed_body_qual_000.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | `VcfPosition.getQuality()` now throws `VcfFormatException` unconditionally for a non-numeric QUAL (matching `InternalUtils.convertElement`'s identical decision for INFO/FORMAT reserved keys), with the original text still recoverable via `getRawQuality()` — a genuinely deferred check, since `VcfParser` itself stays lenient regardless. Covered by `VcfParserTest.testHtsSpecsNonNumericQualFixtureThrowsOnTypedAccess`, which reparses the real fixture and calls `getQuality()` directly. |
| Negative QUAL | `failed_body_qual_001.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | See "Negative QUAL fixtures" below. |
| `##SAMPLE` missing `Genomes` field | `failed_meta_sample_000.vcf` (4.1, 4.2) | 2 | Permanently accepted | `VCFv4.2.tex` line 146's `##SAMPLE` template shows `Genomes` but no spec text (4.2-4.5) marks any `##SAMPLE` field but `ID` as mandatory; `IdDescriptionMetadata` (used for `##SAMPLE`) only models/validates `ID`/`Description`, treating `Genomes`/`Mixture` as unmodeled extra fields by design. bcftools 1.23.1 accepts silently; only vcf_validator 0.10.2 flags it (`Error: SAMPLE metadata Genomes is not a valid string...`). |
| `##SAMPLE` non-`Description` field quoted (`Mixture="..."`) | `failed_meta_sample_001.vcf` (4.1, 4.2) | 2 | Permanently accepted | Same unmodeled-extra-field reasoning as the row above. Verified directly: parsing preserves the raw quoted value and `getPropertyUnquoted("Mixture")` correctly strips it to the intended value, with lossless round-trip via `VcfWriter`'s raw-property passthrough — no data loss or misalignment. bcftools 1.23.1 accepts silently; only vcf_validator 0.10.2 flags it (`Error: SAMPLE metadata Mixture is not a valid string...`). |
| FORMAT key contains an underscore, flagged as "non-alphanumeric" | `failed_body_format_003.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | `InternalUtils.FORMAT_PATTERN` (`^[A-Za-z_][0-9A-Za-z_.]*$`, stated directly by `VCFv4.3.tex` line 429, the target; `VCFv4.2.tex` predates it) already permits `_` in a FORMAT key — the fixture's `G_S` key matches it. bcftools 1.23.1 accepts `GT:G_S:GL` silently (no charset complaint). vcf_validator 0.10.2 reports it invalid ("Format is not a colon-separated list of alphanumeric strings"), reading "alphanumeric" more literally than the spec's own cited regex allows; bcftools is the tie-breaker per `AGENTS.md`. |
| GT contains an interior empty allele between two adjacent separators (`0/\|1`) | `failed_body_format_004.vcf` (4.1, 4.2), `failed_body_format_006.vcf` (4.3 — renumbered copy of the same fixture, see "Fixture numbering is not stable across hts-specs version directories" below) | 3 | Permanently accepted, deliberate — same divergence as `EMPTY_FIELD_HANDLING.md`'s existing GT row | `VcfSample.getGenotype`'s manual separator scan applies the identical warn-and-substitute-`.` treatment to an empty allele regardless of position (interior or trailing) — confirmed directly: `0/\|1` parses to alleles `[C, ., T]` with the same "GT ... contains an empty allele" warning already documented for the trailing case (`0/`). bcftools 1.23.1 hard-errors (exit code 8), the same error class already cited there; vcf_validator 0.10.2 reports "Allele index must not be empty." See `EMPTY_FIELD_HANDLING.md`'s GT row rather than a new explanation. |
| GT semantic-content violations (illegal allele value, out-of-range allele index, non-genotype-shaped first field) | `failed_body_sample_000.vcf` (`1/C`), `_001.vcf` (`0/3`, only 2 valid indices), `_002.vcf` (`0.000`) (4.1, 4.2, 4.3) | 9 | Permanently accepted | `VcfSample.getProperty(ReservedFormatProperty.Genotype)` returns GT's raw String unvalidated, but `VcfSample.getGenotype(VcfPosition)` — the library's typed Genotype-interpretation method — throws `VcfFormatException` for all three (a non-numeric allele token, or an index exceeding `position.getAltBases().size()` per `VCFv4.2.tex` line 217's allele-indexing rule), and is reached via `MemoryMappedVcfDataStore.getGenotypeForId`/`getGenotypeAtLocus`. Covered by `VcfParserTest.testHtsSpecsGtSemanticViolationFixturesThrowOnTypedAccess`, which reparses all three real fixtures and calls `getGenotype(VcfPosition)` directly. Matches Parser Validation Policy's deferred-validation-via-typed-conversion design. bcftools 1.23.1 hard-errors on `_000.vcf`/`_002.vcf` but is silent on `_001.vcf`'s out-of-range allele; vcf_validator 0.10.2 flags all three invalid. |
| Sample FORMAT value's element count doesn't match its declared `Number` (a non-reserved key) | `failed_body_sample_004.vcf` (`DS`, Number=1, 2 values), `_005.vcf` (`AL`, Number=A), `_006.vcf` (`AL`, Number=G) (4.1, 4.2, 4.3), `_010.vcf` (`AL`, Number=R; 4.2, 4.3) | 11 | Permanently accepted | Fixed: `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` now resolves a non-reserved key's `Number` from its own `##FORMAT` declaration and checks cardinality the same way as a reserved key. Same lazy-check/no-op-callback limitation as the PL/GL/GP row above applies to `HtsSpecsConformanceTest`'s automated harness — confirmed via an empty `htsSpecsUpdateBaseline` diff, covered instead by `VcfParserTest.testHtsSpecsNonReservedFormatNumberFixturesWarnOnTypedAccess`. bcftools 1.23.1 is silent on all four fixtures; vcf_validator 0.10.2 flags all four invalid with correct expected-vs-actual counts. |
| Sample FORMAT value doesn't match its declared `Type` (a non-reserved key) | `failed_body_sample_007.vcf` (`DS`, Type=Float, `my_string`), `_008.vcf` (Type=Integer), `_009.vcf` (Type=Character) (4.1, 4.2, 4.3) | 9 | Permanently accepted | Fixed: `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` now converts a non-reserved key's value using its declared `##FORMAT` `Type`, throwing `VcfFormatException` on a genuine mismatch (unchanged from `getProperty(ReservedFormatProperty)`'s existing throw-on-mismatch behavior for a reserved key). Covered by `VcfParserTest.testHtsSpecsNonReservedFormatTypeFixturesThrowOnTypedAccess`. bcftools 1.23.1 hard-errors for `_007.vcf`/`_008.vcf` but is silent on `_009.vcf`'s Character-type mismatch; vcf_validator 0.10.2 flags all three invalid. |
| INFO value is not a flag (`1000G`, `DB`, `H2`, `H3`, `SOMATIC`, `VALIDATED`) | `failed_body_info_012.vcf`, `_019.vcf`, `_020.vcf`, `_025.vcf`, `_026.vcf`, `_027.vcf` (4.1, 4.2, 4.3) | 18 | Permanently accepted | `InternalUtils.convertElement`'s `Boolean` branch warns (not throws, since the Phase 2 review's third re-verification round: a Flag's truth is its presence, not its value's content -- see `VcfWriter.validateInfoValue`'s identical write-side treatment) for any non-empty value, reached on `VcfPosition.getInfo(ReservedInfoProperty)` for each of these reserved Flag-typed keys -- deferred validation working as designed. vcf_validator 0.10.2 independently confirms each invalid ("does not match the specification Type=Flag"); bcftools 1.23.1 is silent (no reserved-key semantic checks). |
| INFO reserved Integer/Float value is a floating point number, or is not a number (`AC`, `AN`, `DP`, `END`, `MQ0`, `NS`, `BQ`, `AF`) | `failed_body_info_002.vcf`, `_003.vcf`, `_005.vcf`, `_007.vcf`, `_008.vcf`, `_009.vcf`, `_014.vcf`, `_015.vcf`, `_017.vcf`, `_018.vcf`, `_022.vcf`, `_024.vcf` (4.1, 4.2, 4.3) | 36 | Permanently accepted | `Long.parseLong`/`VcfFloat.parse` throw `VcfFormatException` for a non-numeric or wrongly-shaped value, reached on `VcfPosition.getInfo(ReservedInfoProperty)` for each of these reserved keys. vcf_validator 0.10.2 independently confirms each invalid; bcftools 1.23.1 is silent. |
| INFO contains a whitespace | `failed_body_info_028.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | `VcfPosition.checkInfoEntries` (run from the lazy `info()` parse on first `getInfo()`/`getInfo(key)` access) throws `VcfFormatException` for whitespace in an INFO key or value — deferred, not eager at `parseLine()`, matching the Parser Validation Policy. vcf_validator 0.10.2 confirms invalid ("Info key is not a sequence of alphanumeric and/or punctuation characters"). |
| INFO field does not match Number/Type definition in meta section (custom key `MY`), including one fixture mislabeled "Sample field does not match Number definition in meta section" | `failed_body_info_029.vcf`, `_030.vcf`, `_031.vcf` (4.1, 4.2, 4.3), `_033.vcf` (4.1, 4.2 only — the 4.3 fixture of the same name is a different, unrelated defect; see the "INFO contains duplicate keys" row below), `_034.vcf`, `_035.vcf` (4.1 only) | 13 | Permanently accepted, via `validateBeforeWrite()` not the plain getter | `MY` is a non-reserved key with no typed accessor; `getInfo(String)` never validates it. `VcfWriter.Builder().validateBeforeWrite()`'s `validateInfoValue()` — the library's documented full-output-diagnostics mode — catches every one of these against the field's own `##INFO` declaration (Flag-with-value, Number cardinality including `R`, and Type mismatches for Float/Integer/Character), confirmed live. vcf_validator 0.10.2 independently confirms each invalid. |
| INFO `AA` (AncestralAllele) contains a comma | `failed_body_info_000.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | Fixed: `VcfPosition.getInfo(ReservedInfoProperty)` now detects a scalar (`isList=false`) reserved key with more than one comma-separated raw value, warns ("INFO {} has {} value(s), but Number={} requires {}"), and keeps only the first — `AA=1,23` now warns and returns `"1"`, not the previously-silent rejoined `"1,23"`. Same structural limitation as the PL/GL/GP row above: `HtsSpecsConformanceTest`'s no-op `parseWith` callback never calls a typed getter, so it can't observe this — confirmed via an empty `htsSpecsUpdateBaseline` diff, and covered instead by a direct unit test, `VcfPositionTest.testGetInfoStringTypedScalarKeyCardinalityViolationWarnsAndKeepsFirstValue`. `VcfWriter.validateBeforeWrite()` only catches this if the caller explicitly declares `##INFO=<ID=AA,...>` in the header (most real files, and this fixture, don't). vcf_validator 0.10.2 confirms invalid ("does not match the specification Number=1"); bcftools 1.23.1 is silent. |
| INFO reserved Integer/Float value is a negative number (`AC`, `AN`, `DP`, `END`, `MQ0`, `NS`, `AF`), including "AC is a negative number in several lines" | `failed_body_info_001.vcf`, `_004.vcf`, `_006.vcf`, `_013.vcf`, `_016.vcf`, `_021.vcf`, `_023.vcf`, `_036.vcf` (4.1, 4.2, 4.3) | 24 | Permanently accepted | `InternalUtils.checkReservedInfoConstraints` now warns for a negative value ("is a negative number", or for `AF`, "does not lie in the interval [0, 1]"), reached on `VcfPosition.getInfo(ReservedInfoProperty)` (read) and `VcfWriter.validateInfoValue()` (write/diagnostics) -- the same dual-reachability pattern already established for `FORMAT/FT`/`FORMAT/PS` (see `VALIDATION_REFERENCE.md`). This row stays in the baseline (not removed by `htsSpecsUpdateBaseline`) for the same structural reason the "floating point number, or is not a number" row above it does: the check is intentionally lazy/deferred per this library's Parser Validation Policy, but `HtsSpecsConformanceTest`'s own `assertThrowsOrWarns` only exercises a no-op `parseWith` callback that never calls any typed getter, so it can't observe a check that only fires on explicit typed access. Confirmed directly: reparsing each of these 7 fixtures (excluding `_036.vcf`, a multi-line variant of `_001.vcf`) and calling `getInfo()` for the fixture's own key does produce the expected warning (see `VcfParserTest.testHtsSpecsNegativeReservedInfoValueFixturesWarnOnTypedAccess`). vcf_validator 0.10.2 confirms invalid ("value must be a non-negative integer number" / AF: "does not lie in the interval [0,1]"); bcftools 1.23.1 is silent. See `VCF_SPEC_AMBIGUITIES.md`'s "Negative/out-of-range values for reserved INFO keys `AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF`" entry for the decision rationale. |
| INFO `CIGAR` contains illegal characters, or is a number | `failed_body_info_010.vcf`, `_011.vcf` (4.1, 4.2, 4.3) | 6 | Permanently accepted | `InternalUtils.checkReservedInfoConstraints` now warns for a value that doesn't match `InternalUtils.CIGAR_PATTERN` (a lone `*`, or one or more `<length><operation>` pairs), reached on `VcfPosition.getInfo(ReservedInfoProperty.Cigar)` (read) and `VcfWriter.validateInfoValue()` (write/diagnostics) — the same dual-reachability pattern as the negative-number row above. This row stays in the baseline (not removed by `htsSpecsUpdateBaseline`) for the identical structural reason that row does: the check is intentionally lazy/deferred, but `HtsSpecsConformanceTest`'s own `assertThrowsOrWarns` only exercises a no-op `parseWith` callback that never calls a typed getter, so it can't observe this class of check. Confirmed directly: reparsing both fixtures and calling `getInfo(ReservedInfoProperty.Cigar)` does produce the expected warning (see `VcfParserTest.testHtsSpecsInvalidCigarValueFixturesWarnOnTypedAccess`). No VCF spec version (4.2 through 4.5, all checked directly) ties `CIGAR`'s grammar to the SAM specification — vcf_validator 0.10.2's own message text ("value is not an alphanumeric string compliant with the SAM specification") is the only source connecting the two; bcftools 1.23.1 is silent. See `VCF_SPEC_AMBIGUITIES.md`'s "CIGAR grammar (SAM compliance)" entry for the decision rationale. |

## New 4.3-only `parser-throw-or-warn` clusters added by the 2026-08-10 expansion

The 16 fixtures below have no 4.1/4.2 sibling with the same `##CauseOfFailure=` and fixture content, so they
are not folded into the table above. Four are triaged here; the `##PEDIGREE` missing-`ID` cluster was
deferred at the time this section was written but is fixed as of this phase's Task 4 (see that row below); the
`##META` field-order fixture (one of the original four `##META` fixtures) was also deferred at the time this
section was written, was briefly fixed by the later, separate attribute-list-tokenizer phase's Task 5, and
was then reverted by that same phase's Task 6 as an unauthorized-by-design check -- it is now a permanently
accepted divergence (see that row below, now split from the other three `##META` fixtures which remain
deferred); the remaining cluster (the contig-asterisk pair, two fixtures) was ruled on by this phase's Task 5
(permanently accepted, per bcftools).

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| INFO contains duplicate keys within one record (`AA=1;AA=23`) | `failed_body_info_033.vcf` (4.3 only — a different, unrelated fixture from the 4.1/4.2 files of the same name; see the Number/Type-mismatch row above) | 1 | Permanently accepted (already handled; harness blind spot) | `VcfPosition`'s lazy INFO parse (the same code path cited in the "INFO `AA` contains a comma" row above, `VcfPosition.java` around the `seenKeys` duplicate check) already warns and keeps only the first occurrence of a repeated top-level INFO key, matching bcftools 1.23.1 behavior (`DP=1;DP=2` round-trips as `1`, not `1,2`). `HtsSpecsConformanceTest`'s no-op `parseWith` callback never calls a typed getter, so it can't observe this — the same structural limitation as every other lazy-check row in this document. Covered directly by `VcfPositionTest.testLazyInfoDuplicateKeyKeepsOnlyFirstOccurrence`. |
| INFO reserved/non-reserved Integer value outside the 32-bit signed range | `failed_body_info_integer_overflow.vcf` (`INT=2147483648`), `_underflow.vcf` (`INT=-2147483649`, `##CauseOfFailure=INFO integer overflow` — mislabeled, the value is actually below the minimum), `_reserved.vcf` (eight records using each of the eight reserved sentinel values `-2147483648`..`-2147483641`) | 3 | Fixed | `InternalUtils.checkInteger32BitRange` now checks every Integer-typed INFO/FORMAT value (reserved or not) against the 32-bit signed range and against the eight BCF-reserved sentinel values, wired into `InternalUtils.convertProperty` (reserved INFO/FORMAT), `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` (non-reserved FORMAT), and the new `VcfPosition.getInfo(VcfMetadata, String)` (non-reserved INFO). `HtsSpecsConformanceTest`'s automated harness still can't observe it — its no-op `parseWith` callback never calls a typed getter, the same structural limitation already documented for the CIGAR/negative-INFO-value/PL-GL-GP rows above — confirmed via an empty `htsSpecsUpdateBaseline` diff (this row remains), and covered instead by `VcfParserTest.testHtsSpecsIntegerOverflowFixtureWarnsOnTypedAccess`/`testHtsSpecsIntegerUnderflowFixtureWarnsOnTypedAccess`/`testHtsSpecsIntegerReservedSentinelFixtureWarnsOnTypedAccess`, which call the new accessor directly. |
| No newline at end of file | `failed_body_no_newline_000.vcf` through `_004.vcf` | 5 | Won't fix (see `WONT_FIX.md`) | `VcfParser` reads records via its own `readPhysicalLine()` (`VcfParser.java:317`), which deliberately keeps `BufferedReader.readLine()`'s own return contract: it returns non-`null` for a final unterminated line exactly the same as a terminated one, so the parser has no way to distinguish "last line ended without a newline" from "last line ended with one." See `WONT_FIX.md`'s "A missing trailing newline at end of file is not detected" entry for the full reasoning (this got cheaper to detect once `readPhysicalLine()` replaced `readLine()` for an unrelated fix, but remains undone by choice, not by cost). |
| `##META` bad `Number`/`Type` value, or `Values` not bracket-wrapped | `failed_meta_meta_000.vcf` (bad `Number`), `_001.vcf` (bad `Type`), `_002.vcf` (unbracketed `Values`) | 3 | Deferred | The attribute-list-tokenizer phase's Task 5 added a structured `MetaMetadata` model routed through `VcfMetadata.getMetas()`, but per `MetaMetadata`'s own Javadoc it validates only what `IdMetadata` already validates for every ID-bearing declaration -- a required, unique `ID`; `Type`/`Number`/`Values` remain opaque, unvalidated properties, since VCFv4.3.tex gives four worked `##META` examples but no prose grammar for them. A `Number`/`Type`/`Values` grammar check would be a new feature, not a bug fix. |
| Metadata declaration fields declared out of canonical order | `failed_meta_meta_003.vcf`; `failed_meta_info_003.vcf`, `failed_meta_format_003.vcf`, `failed_meta_alt_004.vcf` (each 4.1/4.2/4.3) | 10 | **Permanently accepted** | Originally scoped to `##META` alone: Task 5 routed `##META` through `VcfParser`/`VcfWriter` for the first time and gave `MetaMetadata`'s constructor a `checkAttributeOrder()` call by analogy with `ContigMetadata`; that call briefly warned on this fixture's `Number,Type,Values,ID` ordering and removed its row from `hts-specs-baseline.tsv`. Task 6 reversed it: the call was never an authorized decision, and once live it warned -- and, under a strict context, threw -- on text copied verbatim from the spec's own worked example (`VCFv4.3.tex:273`'s `ID,Type,Number`). No spec version requires an attribute order for any declaration type (4.3, the target, is silent; 4.4/4.5 say implementations "must not rely on the order"); bcftools 1.23.1 is silent on every ordering shape measured, `##META` and non-`##META` alike; vcf_validator 0.10.2 rejects every non-canonical ordering, including the spec's own example, which disqualifies its contrary verdict. On 2026-08-14 this reasoning was extended from `##META` alone to every keyed declaration type -- `BaseMetadata.checkAttributeOrder()` and the `IdMetadata.validateAttributeOrder()` dispatch hook it required were removed entirely (the hook, never public, became permanently unreachable once every subclass overrode it to a no-op; `checkAttributeOrder()` itself was public but was introduced 2026-07-24, entirely after the last real release, so removing it broke no shipped consumer) -- adding the `info_003`/`format_003`/`alt_004` rows above to what was previously only `meta_003`. See `VCF_SPEC_AMBIGUITIES.md`'s "`##META` attribute order" entry for the full measured table and reasoning, and `MetaMetadataTest#aMetaDeclarationIsNotAttributeOrderChecked`/`InfoMetadataTest#testMapConstructorIsNotAttributeOrderChecked`/`FormatMetadataTest#testMapConstructorIsNotAttributeOrderChecked`/`IdDescriptionMetadataTest#testMapConstructorIsNotAttributeOrderChecked`/`ContigMetadataTest#testMapConstructorIsNotAttributeOrderChecked` for the pinning tests. |
| `##PEDIGREE` missing `ID` | `failed_meta_pedigree_003.vcf` | 1 | **Fixed by this phase's Task 4** | Task 4 implemented the `##PEDIGREE`-requires-an-`ID` rule at VCF 4.3+ (`VcfMetadata.Builder.addPedigree`, gated on `VcfVersion.isAtLeast(VcfVersion.V4_3)`); landing it removed this fixture's row from `hts-specs-baseline.tsv` rather than leaving it recorded as a known divergence. Confirmed via `./gradlew htsSpecsUpdateBaseline` producing a diff of exactly this one row removed, and a subsequent clean `./gradlew htsSpecsTest` reporting `845/845 checks match baseline, 0 new/stale, 0 per-version row(s) moved` -- the check total is unchanged because it counts every fixture's check regardless of whether it matches a recorded divergence or the spec-conformant default; only the divergence entry disappeared. |
| `contig`/CHROM ID contains an asterisk in a non-leading position | `failed_body_chrom_004.vcf` (CHROM `chr*1`), `failed_meta_contig_003.vcf` (`##contig=<ID=1.*,...>`) | 2 | Permanently accepted | Both share the same claimed defect discussed in this document's "Contig ID asterisk fixture" section below (a non-leading `*`, which the VCFv4.3.tex errata says is legal). Task 5 adjudicated the ruling directly against bcftools: bcftools 1.23.1 accepts both non-leading-asterisk forms silently, only warning (not rejecting) on a *leading* `*`. See `VCF_SPEC_AMBIGUITIES.md`'s "Contig ID with a non-leading asterisk" entry for the full reasoning and the pinning `BcftoolsDependencyTest` methods. |
| `##SAMPLE` ID contains illegal character (a non-leading `*`) | `failed_meta_sample_003.vcf` (`##SAMPLE=<ID=1.*,...>`) | 1 | Permanently accepted; newly surfaced by the parser Phase 2 review's Task fixing `##SAMPLE`'s Description requirement | Newly appears in the baseline because this fixture's `##SAMPLE` declaration also omits `Description` -- until that fix, the (wrong) "Description is required for `##SAMPLE`" warning already satisfied `parser-throw-or-warn`'s "did the parser warn at all" check, masking that no ID character-set check exists for `##SAMPLE` at all. This differs from the contig/CHROM row directly above: that row is this library deliberately accepting a *specific, already-adjudicated* character (a non-leading `*`) in a check that otherwise runs; `IdMetadata`'s shared `init()` (used by `##ALT`/`##FILTER`/`##SAMPLE`) checks only whitespace and comma, with no character-set check at all to accept or reject an asterisk from -- unlike `ContigMetadata`'s dedicated, fuller check. Measured directly: bcftools 1.23.1 accepts `##SAMPLE=<ID=1.*,Assay=WholeGenome>` silently (exit 0, preserved verbatim), consistent with the contig-asterisk precedent. Implementing a `##SAMPLE`/`##ALT`/`##FILTER` ID character-set check is a new feature (no spec text gives one for these three types beyond ALT's own whitespace/comma/angle-bracket rule, `VCFv4.3.tex:200`), not a bug fix. |

## Negative QUAL fixtures: why permanently accepted

`failed_body_qual_001.vcf` sets `##CauseOfFailure=QUAL is a negative number` (`QUAL=-100`).
`VcfPosition.getQuality()` does *not* catch this: `VcfFloat`'s `FLOAT_PATTERN` explicitly permits a leading
`-`, so a negative QUAL parses as an ordinary valid `VcfFloat` with no warning and no sentinel fallback —
covered by `VcfParserTest.testHtsSpecsNegativeQualFixtureDoesNotThrowOrWarnOnTypedAccess`, which reparses the
real fixture and confirms `getQuality()` returns `-100` with zero captured warnings. Despite that
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

**Unrelated coincidental defect in `_002.vcf`:** this fixture's first record has `ALT=AAT,AA` (2 alleles) but
`AC=249`/`AF=0.11` (1 value each) -- a genuine `Number=A` cardinality mismatch, and its `FORMAT GL` values are
also short by half. This is a real, already-covered-elsewhere check (the same one documented in the "Sample
FORMAT value's element count doesn't match its declared `Number`" and reserved-INFO-cardinality rows above),
just coincidentally present in a fixture whose own documented cause is the unrelated duplicate-record claim.
Discovered while re-measuring the deferred-validation blind spot below with `DeferredValidationLineParser`:
this coincidental defect is what makes `_002.vcf` alone (not `_000`/`_001`/`_003`) still show as detected once
the `FORMAT {} has no metadata declaration` noise (see below) is excluded -- it does not change this section's
"permanently accepted" ruling on the duplicate-record claim itself, which remains genuinely undetected.

## `HtsSpecsConformanceTest`'s automated harness cannot observe a deferred/lazy validation check

Every "Permanently accepted" row above whose Details column says the underlying check is "lazy"/"deferred" is
undetectable by `HtsSpecsConformanceTest.assertParsesCleanly`/`assertThrowsOrWarns` as currently written: both
use a no-op `parseWith` callback (`(metadata, record) -> { }`), so a check that only fires when a typed getter
is actually called (`getQuality()`, `getInfo(ReservedInfoProperty)`, `getGenotype(VcfPosition)`,
`getProperty(VcfPosition, VcfMetadata, String)`) never runs during the automated suite at all. This is
deliberate, not an oversight -- see `DeferredValidationLineParser`'s own Javadoc and the sibling
`LocalFixtureConformanceTest` harness, which wires it in directly; `HtsSpecsConformanceTest` does not, because
its outcomes are baseline-diffed across up to 1435 rows rather than hand-written per fixture, and wiring in
deferred validation there risks two distinct failure modes measured directly while investigating the TODO.md
item this section closes out (`HtsSpecsConformanceTest` may have silently unverified deferred-validation
warnings, filed 2026-08-16):

- **False credit from a generic, content-independent warning.** `DeferredValidationLineParser` calls
  `sample.getProperty(position, metadata, key)` for every sample property key present, which fires `"FORMAT {}
  has no metadata declaration; cannot determine its Number/Type"` for any fixture using a key like `DS`/`GL`
  without a `##FORMAT` declaration -- true of most of this corpus, and unrelated to whatever each fixture
  actually documents as wrong. Measured directly: temporarily wiring `DeferredValidationLineParser` into both
  `assertParsesCleanly`/`assertThrowsOrWarns` and running `./gradlew htsSpecsTest` produced 179 divergences;
  excluding this one additional generic warning (the same masking effect `GENERIC_NOISE_WARNINGS` already
  guards against for two other warnings) dropped it to 147.
- **Correct detection, wrong reason.** The `_002.vcf` coincidental defect immediately above is a second,
  distinct failure mode: a real check firing legitimately, but not the one the fixture's own
  `##CauseOfFailure=` documents. `HtsSpecsConformanceTest`'s `EXPECTED_WARNING_SUBSTRINGS` mechanism exists to
  guard exactly this for a *fresh* detection, but does not run on a *baseline-STALE* transition (the
  fixture-level `assertTrue` on `Baseline.check()`'s result throws before that content check is ever reached)
  -- so wiring deferred validation into the main harness at this scale would need that gap closed too, not
  just the generic-noise one above.

Given both risks, this project's chosen resolution (matching every "Permanently accepted, covered by
`VcfParserTest.testHtsSpecs*`" row already in this document) is: leave `HtsSpecsConformanceTest`'s harness and
`hts-specs-baseline.tsv` untouched, and instead add one small, targeted, permanent regression test per
rule-category, each reparsing the real fixture and asserting the *specific* expected exception/warning message
-- never a bare "some warning fired" check. Of the ~40 rule-categories behind the 141 genuine STALE rows
measured above, all but two (non-numeric QUAL, GT semantic-content violations) already had such a test; both
gaps are now closed (see their table rows above). The remaining 6 of the 147 measured divergences were `NEW`,
not `STALE` -- two `passed/` fixture-scope-mismatch cases, covered in the next section.

## `passed/` fixture-scope-mismatch cases: candidates for upstream filing

Discovered by the same `DeferredValidationLineParser` re-measurement described above, applied to
`passedFixturesParseWithoutError`'s no-op callback too: two `passed/` fixtures (should parse cleanly) contain a
record that this library correctly, and by deliberate existing design, rejects once a typed getter actually
reads it. Neither is a library bug -- both look like upstream template artifacts, not deliberate test content,
and neither is wired into `hts-specs-baseline.tsv` (the automated `parser-no-throw` check still uses a no-op
callback and never observes either), so this is documentation + a pinning test only, matching the "CHROM colon
fixture" precedent above.

| Fixture | Version(s) | Record | Why this looks like an upstream defect |
|---|---|---|---|
| `passed_body_alt.vcf` | 4.1, 4.2, 4.3 | `POS=1900, ALT=.` (zero ALT alleles), sample 2's `GT=0\|1` | Every other row in this file's ALT-syntax sweep reuses the identical `GT:DS:GL` sample block; only the `ALT=.` row is where that reused `GT=0\|1` becomes actually invalid (index 1 with zero ALT alleles) -- `VcfSample.getGenotype(VcfPosition)` unconditionally rejects an out-of-range allele index, per its own `@throws` Javadoc, predating this investigation. |
| `passed_body_info.vcf` | 4.1, 4.2, 4.3 | `CHROM=14, SB=0.150`/`SB=1.1e-123` | `SB` is reserved with `Number=4, Type=Integer` (`VCFv4.3.tex`'s Reserved INFO Keys table: `SB & 4 & Integer & Strand bias`), but both records supply a single float value -- the same `0.150`/`1.1e-123` template used elsewhere in this file for `AF`/`BQ`/`MQ` (all genuinely `Float`-typed) looks like it was mistakenly reused for `SB`. `InternalUtils.convertElement`'s unconditional throw for a reserved-key value unparseable as its declared type was reconfirmed correct in `a46cad6`, one day before this investigation. |

Both pinned by `VcfParserTest.testHtsSpecsPassedBodyAltFixtureAltMissingRecordThrowsOnTypedGtAccess` and
`testHtsSpecsPassedBodyInfoFixtureStrandBiasFloatValueThrowsOnTypedAccess`. Neither has a written upstream
issue draft yet.

## `writer-bytediff` entries (6) — `passed/` fixtures whose round trip isn't byte-identical

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Metadata declaration reordering | `complexfile_passed_000.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | `VcfWriter.buildHeaderLines()` has a pre-existing, fixed per-type metadata grouping order (INFO, FILTER, FORMAT, ALT, contig, META, SAMPLE, PEDIGREE, then generic properties -- META inserted between contig and SAMPLE by the attribute-list-tokenizer phase's Task 5, matching VCFv4.3.tex's own Meta-information lines ordering); this fixture interleaves types in a different order. |
| Duplicate `##contig` line dropped | `passed_meta_contig.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | Direct, intended consequence of the duplicate-metadata-ID keep-first policy. See `DUPLICATE_METADATA_ID_HANDLING.md`. |
| Empty sample value substituted | `zero_length_LAA.vcf` (4.5) | 1 | Permanently accepted | Matches this library's established empty-field normalization policy. See `EMPTY_FIELD_HANDLING.md`. |

## `writer-semantic` entries (6) — `failed/` fixtures whose round trip throws, added by Phase 3's finding #6

Before Phase 3's writer-compliance review (finding #6), `failedFixturesRoundTripSemantically` did not exist --
`failed/` fixtures were never round-tripped at all, only checked for `parser-no-throw`/`parser-throw-or-warn`.
Adding that factory (reusing the existing `checkRoundTripSemantic` unchanged, which already returns early for
a fixture that doesn't even parse) raised the frozen total from **845** to **1359**, confirmed via
`./gradlew htsSpecsUpdateBaseline` producing a diff of exactly 6 insertions (verified: `git diff` on
`hts-specs-baseline.tsv` showed 6 new lines, zero removed/modified) and a subsequent `./gradlew htsSpecsTest`
reporting `1359/1359 checks match baseline, 0 new/stale, 0 per-version row(s) moved`.

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Non-numeric QUAL throws on write | `failed_body_qual_000.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | The identical, already-documented "Non-numeric QUAL" row above (`VcfPosition.getQuality()` throws unconditionally for a non-numeric QUAL) now also surfaces on the writer side: `checkRoundTripSemantic`'s `semanticDiff` phase calls `getQuality()` on both `before`/`after` to compare them, so it throws for exactly the reason that row already explains -- caught by the `semantic-diff-exception` guard `checkRoundTripSemantic` already has for this class of exception. |
| INFO key whitespace throws on write | `failed_body_info_028.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | The identical, already-documented "INFO contains a whitespace" row above (`VcfPosition.checkInfoEntries` throws unconditionally for whitespace in an INFO key) now also surfaces on the writer side: `VcfWriter.addInfoOrDot` calls `position.getInfoKeys()`/`getInfo(key)` to enumerate INFO for writing, on both the default and `validateBeforeWrite()` paths, which forces the same lazy check to run and throw during `checkRoundTripSemantic`'s `write` phase -- caught by its existing `write-exception` guard. |

## `writer-warning-invariant` entries (5) — `passed/` fixtures whose round-trip warning set changed, added by R8's Task 10

Before this change, `checkRoundTripWarningInvariant` used `Assumptions.abort` to skip entirely any `passed/`
fixture that already warned on its original parse, and only compared "did reparsing the written output
introduce any *new* warning" for the rest -- never the full before/after warning set. The
`2026-08-26-1117-declared-version-format-cache-metamorphic-coverage-r8-plan` plan's Task 10 reworked it to run
on every `passed/` fixture and compare the complete before/after warning sets, which surfaced 5 new
divergences from the same 3 fixtures already known to diverge on `writer-bytediff` above -- two of them
(`complexfile_passed_000.vcf`, `passed_meta_contig.vcf`) across their 4.1/4.2 versions, the third
(`zero_length_LAA.vcf`) at its own 4.5-only version -- since the same normalization that changes their
written bytes also changes their warning set. This is not a 1:1 row count with `writer-bytediff`'s 6 rows
for these fixtures: the VCFv4.3 copy of
`complexfile_passed_000.vcf` (one of those 6 rows) does not get a matching `writer-warning-invariant` row,
because that fixture's 4.3-version content uses a key=value metadata form, not the bracket-wrapped form
dropped at 4.1/4.2, so nothing changes in its warning set -- see the table below. Confirmed via `./gradlew
htsSpecsUpdateBaseline` producing a diff of exactly 5 insertions (verified: `git diff` on
`hts-specs-baseline.tsv` showed 5 new lines, zero removed/modified) and a subsequent `./gradlew htsSpecsTest`
reporting `1435/1435 checks match baseline, 0 new/stale, 0 per-version row(s) moved`.

Each row's warning-set change is a direct consequence of a normalization the original parse already performs
(and already warns about): the normalized content is what actually gets written, so reparsing the
round-tripped output no longer has the original defect to warn about. Each row's `after` set is the `before`
set minus the normalized-away warning, with one exception: for `complexfile_passed_000.vcf`, one remaining
message's cited line number also shifts down by one (line 50 -> line 49), a direct consequence of the
dropped metadata line removing one line from the output -- so that message's exact text does technically
differ from anything in the `before` set, even though it reports the same underlying warning.

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Dropped bracket-wrapped metadata property removes its own warning and shifts a later line number | `complexfile_passed_000.vcf` (4.1, 4.2) | 2 | Permanently accepted | Same underlying divergence as the `writer-bytediff` "Metadata declaration reordering" row above: the original parse drops `##AnalysisTitleBrackets=<"...">` (a bracket-wrapped value with no `=`, which bcftools can't parse) with a warning. Since the written output no longer contains that property, reparsing has nothing left to warn about, and losing that one header line also shifts the unrelated CHROM-discontiguity warning's cited line number down by one (line 50 -> line 49) -- not a new defect, just a byproduct of one fewer header line. |
| Duplicate `##contig` line dropped removes its own warning | `passed_meta_contig.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | Same underlying divergence as the `writer-bytediff` "Duplicate `##contig` line dropped" row above: the duplicate-metadata-ID keep-first policy drops the second `##contig` declaration on the original parse, with a warning. Since the written output has only one `##contig` line, reparsing has no duplicate left to warn about. See `DUPLICATE_METADATA_ID_HANDLING.md`. |
| Empty sample value substituted removes its own warning | `zero_length_LAA.vcf` (4.5) | 1 | Permanently accepted | Same underlying divergence as the `writer-bytediff` "Empty sample value substituted" row above: the empty `LAA` sample value is normalized to `.` with a warning on the original parse. Since the written output already contains `.`, reparsing has no empty field left to warn about. See `EMPTY_FIELD_HANDLING.md`. |

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

## Inconsistent `##CauseOfFailure=` wording for the same empty-value condition: candidate for upstream filing

`failed_meta_009.vcf` (an empty `##reference=` value) labels its `##CauseOfFailure=` as "Meta-data entry is
not a key-value pair (empty value)", while `failed_meta_assembly_000.vcf` and `failed_meta_pedigreedb_000.vcf`
— each an empty `##assembly=`/`##pedigreeDB=` value, the exact same underlying condition (zero characters
after `=`) — instead use "Meta-data entry is not a key-value pair", without the `(empty value)` qualifier.
This holds across all three versions these fixtures exist for (4.1, 4.2, 4.3) — nine files total, three
fixture families x three versions each. Confirmed still current against this file's pinned
`htsSpecsPinnedSha`. There's no apparent reason the qualifier is present for one field's empty-value case and
absent for the other two, given the content is structurally identical. (`failed_meta_pedigreedb_001.vcf`'s
`##pedigreeDB=<>` is a different condition — a non-empty two-character value, not zero characters after `=`
— so its lack of the qualifier is arguably correct as-is and isn't part of this note.)

This is purely a wording inconsistency in hts-specs' own prose, with no functional effect on this library or
its conformance harness: both wordings describe the identical "meta-data entry rejected" outcome, and
`HtsSpecsConformanceTest`'s `EXPECTED_WARNING_SUBSTRINGS` is keyed per-fixture-path, not per-cause-text, so it
doesn't group or count fixtures by this string — nothing here to fix in this library. A full upstream-filing
write-up (affected-files table, suggested resolution) has already been drafted locally but not yet filed, the
same disclosure this file already makes for the "CHROM colon fixture" entry below.

## CHROM colon fixture: candidate for upstream filing

`failed_body_chrom_001.vcf`'s `##CauseOfFailure=contig ID contains a colon` claim is directly contradicted by
`VCFv4.3.tex`'s own "changes since previous version" appendix (~line 2135-2141, unchanged in VCFv4.4/4.5):

> "The VCF specification previously disallowed colons (`:`) in contig names to avoid confusion when parsing
> breakends, but this was unnecessary. Even with contig names containing colons, the breakend mate position
> notation can be unambiguously parsed because the `:pos` part is always present."

This is the VCFv4.3.tex target's own stated rule, applied directly (VCFv4.2.tex has no character-set grammar
for CHROM beyond forbidding whitespace, predating this rule rather than conflicting with it). Confirmed
directly with bcftools 1.23.1 and vcf_validator 0.10.2 against this exact fixture: both tools accept it
without complaint (bcftools exit 0, silent; vcf_validator reports the file valid even at its `warning`
verbosity level). `VcfPosition.checkChromosome` deliberately excludes colon from its invalid-character set for
this reason — see the citation comment on `InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN`.

This fixture was added in 2021 (commit `7aeed5b`), over a year after the 2020 spec correction it contradicts
(commit `0ef79c0`) — a full upstream-filing write-up (with reproduction steps and a suggested resolution) has
already been drafted locally but not yet filed.

Affected fixture:

| Fixture | Version(s) | `##CauseOfFailure=` |
|---|---|---|
| `failed_body_chrom_001.vcf` | 4.1, 4.2, 4.3 | `contig ID contains a colon` |

## Contig ID asterisk fixture: why its claimed defect is stale, and a scope correction

**Scope correction:** this section previously described `failed_meta_contig_003.vcf` as outside the automated
check's scope, on the grounds that `FAILED_VERSIONS` didn't reach 4.3 yet and the fixture has no 4.1/4.2
counterpart. That's no longer true: the 2026-08-10 target-flip expansion extended `FAILED_VERSIONS` to include
`4.3`, and the fixture now has an ordinary `hts-specs-baseline.tsv` row (`parser-throw-or-warn`,
`silently-accepted`), alongside a sibling with the identical defect on CHROM instead of `##contig`,
`failed_body_chrom_004.vcf` (`chr*1`). Both are listed in the "New 4.3-only `parser-throw-or-warn` clusters"
table above, ruled on by this phase's Task 5 (permanently accepted).

`test/vcf/4.3/failed/failed_meta_contig_003.vcf` declares `##contig=<ID=1.*,length=123456>` with
`##CauseOfFailure=contig ID contains illegal character`. Its claimed defect is stale, per the same
`VCFv4.3.tex` errata cited in the "CHROM colon fixture" section above: `*` is invalid only as the first
character of a contig name, and in `1.*` it's the third. This isn't just spec-text-vs-one-fixture --
hts-specs' own suite disagrees with itself on the identical ID string: the 4.1 and 4.2 copies of
`passed/passed_meta_contig.vcf` (fixtures expected to parse cleanly) both contain the literal line
`##contig=<ID=1.*,length=123456>`, while the 4.3 `failed/` fixture added in the same original commit claims
that exact string is invalid. Confirmed with direct `bcftools 1.23.1` testing that bcftools accepts a
contig ID with `*` in a non-leading position silently, and only warns when `*` (or `=`) is the leading
character -- matching the amended rule, not the fixture's claim.

**Ruling (Task 5):** bcftools is the tie-breaker per `AGENTS.md`, and it sides with the spec's own errata, not
the fixture's `##CauseOfFailure=`. Both `failed_meta_contig_003.vcf` and `failed_body_chrom_004.vcf` are
permanently accepted as stale-defect fixtures. Pinned by two required `BcftoolsDependencyTest` methods:
`testContigIdWithNonLeadingAsteriskBcftoolsAccepts` (both the `##contig=<ID=1.*>` and `CHROM=chr*1` shapes,
accepted silently) and `testContigIdWithLeadingAsteriskBcftoolsWarnsButPreserves` (the control: a *leading*
asterisk still warns and is not accepted, it is merely preserved). Full reasoning and the bcftools output
quoted verbatim live in `VCF_SPEC_AMBIGUITIES.md`'s "Contig ID with a non-leading asterisk" entry. Both this
fixture and the CHROM-colon fixture above were introduced in the same 2021 hts-specs commit (`7aeed5b`); a
combined upstream-filing draft covering both already exists locally but has not yet been filed.

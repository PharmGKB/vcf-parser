# Empty ("zero-length") field handling

This document describes the parser's (lenient) behavior -- reached via `VcfParser`, `VcfPosition.validate()`,
or `VcfContext.newPosition(...)` on a context built with `VcfContext.lenient(...)`. On a
`VcfContext.strict(...)` context, the same factory throws `VcfFormatException` immediately for an empty
ID/ALT/FILTER entry, an empty INFO key, or an empty FORMAT sub-field name instead of warning-and-normalizing
per the table below; see `VALIDATION_PHILOSOPHY.md`.
`VcfWriter`'s default write path applies this same warn-and-normalize behavior for an empty ID/ALT/FILTER
entry or sample FORMAT value that reaches `writeLine()` without validation having re-run. There is no
live-mutation route into that state: the model layer is immutable and `VcfPosition`'s list getters return
unmodifiable views. The two routes that do reach it are `VcfContext.newPositionCopyingTrustedFields` (which
skips `checkIds`/`checkAltBases`/`checkFilters` outright) for ID/ALT/FILTER, and ordinary construction of a
legitimately-empty sample value for a sample's FORMAT sub-field value -- see "Where this is implemented"
below. It is not part of `VALIDATION_PHILOSOPHY.md`'s strict/lenient split, since the writer has no "direct
construction" analogue to be strict about.

The VCF spec (explicitly stated starting with VCFv4.3; VCFv4.2 is silent on it) says:

> Zero length fields are not allowed, a dot (`.`) must be used instead.

That means an empty entry produced by a delimiter — a leading, interior, or trailing `;`, `,`, or `:` with
nothing between it and the next delimiter (or the end of the field) — is not valid VCF.

## Why this warns instead of throwing

A hard rejection would seem like the natural way to enforce this. In practice, the reference implementation most
of the ecosystem treats as canonical, [bcftools](https://github.com/samtools/bcftools)/htslib, does **not** reject
any of these cases. Tested directly against **bcftools 1.23.1 (htslib 1.23.1)**: every example below parses with
exit code 0 (except where noted), and bcftools either normalizes the empty entry away, substitutes `.` for it, or
(for two field types) warns but still keeps the record.

Rejecting these outright would make `vcf-parser` stricter than bcftools on inputs that demonstrably occur and are
tolerated in practice, so `vcf-parser` matches that practical tolerance: it logs a warning and normalizes, rather
than throwing `VcfFormatException`. This is consistent with the "strict for structure, lenient for practical
realities" split described in [`README.md`](README.md#validation) — the difference here is that
the "reality" is demonstrated by the reference tool itself, not just by metadata-quality concerns.

**vcf_validator 0.10.2** (the EBI reference validator; the tool [hts-specs](https://github.com/samtools/hts-specs)
uses to classify its own `passed/`/`failed/` conformance fixtures) was also checked against every row below.
Structural, top-level fields (`ID`, `FILTER`, `ALT`, `INFO`) are consistently reported **invalid** for every empty
entry, consistent with the spec's own "zero length fields are not allowed" text — with one asymmetry noted in the
table below (`AD=1,2,`). Sample-column values (`GT`, reserved `FORMAT` sub-fields) are reported **valid** in every
case tested — but for two different reasons, which the rows below keep apart. For a reserved `FORMAT` sub-field
value (rows 20-22), vcf_validator genuinely does not validate the sub-structure. For `GT` (row 19), it does have
an allele-index check, and the **valid** verdict is specific to the *trailing* placement this table probes: a
leading or interior empty allele is reported invalid ("Allele index must not be empty."). See row 19. No hts-specs
conformance fixture exercises any of these exact scenarios, so this is a direct tool run, not a documented suite
fixture; per `AGENTS.md`'s decision hierarchy, bcftools remains the tie-breaker for these spec-silent/ambiguous
cases.

## How each field is normalized

Two different normalization strategies are used, depending on whether the field's list position carries meaning:

- **Position-independent lists** (ID, FILTER, and INFO's top-level `;`-separated entries): an empty entry is
  **dropped**. Nothing else depends on these lists' element order or exact count, so removing the bad entry is a
  clean fix.
- **Position-dependent lists** (a sample's `:`-separated values, matched by index to the FORMAT keys; INFO's
  per-key `,`-separated values, whose count can matter for a `Number=`-declared arity; and ALT's alternate-allele
  list, since `GT` genotype values index into it positionally): an empty entry is **replaced with `.`**, the VCF
  missing-value sentinel, so every other element keeps its original index.
- **FORMAT's `:`-separated key list** is a special case: it's a position-dependent list (every sample's values are
  matched to it by index), but "empty" isn't a value that can be replaced with `.` — an empty key is kept exactly
  as parsed, and only a warning is logged. Every other FORMAT key keeps its index this way, matching what bcftools
  itself does here (it warns "not defined in header" but does not drop the entry either).

ALT is a position-dependent list because `GT` genotype values index into it positionally (`VCFv4.2.tex` line 217:
"1 for the first allele listed in ALT, 2 for the second allele list in ALT and so on" -- "list", not "listed",
is the spec's own wording for the second clause, in every version from 4.2 through 4.5); `VcfPosition.getAllele
(int)` implements this as `m_altBases.get(index - 1)`. Dropping an empty entry would shift every subsequent allele's
index down by one, silently misdirecting any `GT` value referencing a position after the dropped entry to the wrong
allele — a real correctness bug, not just a cosmetic list-length difference. Substituting `.` instead, matching
bcftools exactly, preserves every index. The VCF spec recognizes `.` as a valid per-entry ALT token: `VCFv4.3.tex`
(the target) line 348 introduces the ALT column's comma-separated list ("Comma-separated list of alternate
non-reference alleles."), line 349 adds that "These alleles do not have to be called in any of the samples.",
and line 350 gives that list's per-entry options, including "a MISSING value `.' (no variant)"; `VCFv4.4.tex`
line 367 and `VCFv4.5.tex` line 416 restate this rule more explicitly:
"Each allele in this list must be one of: ... the MISSING value `.` (no variant); ...". Confirmed bcftools 1.23.1
accepts `T,.,C` with zero warning whether the `.` is substituted or literal, and with genotype indices before, at,
and after the dot all resolving correctly.

This reading is contested by tooling, worth stating plainly rather than omitting: vcf_validator 0.10.2 itself
rejects the library's own substituted output (`T,.,C`) as invalid under every version's `##fileformat` declaration
tested, including 4.3+ — it applies `VCFv4.3.tex`'s own whole-field regex (`^([ACGTNacgtn]+|\*|\.)$`) literally, as
"a single dot, OR a comma-separated list of pure bases," not "a dot allowed inside such a list." bcftools 1.23.1 is
the tie-breaker here per `AGENTS.md` ("If still in doubt, consult bcftools behavior"), and it accepts this
construction with zero warning, consistent with the newer versions' prose. This library follows bcftools and the
newer-version prose, not vcf_validator's stricter regex reading, for the reasons given above.

**ALT's `.`-substitution is unconditional across every VCF version this library targets or accepts — it is not
4.3+ behavior awaiting version dispatch.** The paragraph above states two separate claims that must not be
conflated into one version-gated rule: *why* an empty entry is substituted rather than dropped (index
preservation) is grounded entirely in the target spec, `VCFv4.3.tex` line 493's `GT`-indexes-into-`ALT` rule
("1 for the first allele listed in ALT, 2 for the second allele list in ALT and so on"), and
holds regardless of the declared `##fileformat` — a 4.2-declared file's `GT` values index into `ALT` exactly the
same way a 4.3-declared file's do. *Whether* `.` is a formally sanctioned per-entry ALT token is the only piece
that differs by version — 4.2 is silent, 4.3+ states it explicitly — but that difference only ratifies a token
4.2 never forbade; it does not change what this library does with an empty entry under either version. bcftools
1.23.1 confirms there is nothing to gate: it substitutes `T,,C` → `T,.,C` and `T,` → `T,.` identically regardless
of the file's declared version. A version dispatch here would therefore have only two possible shapes, and both
are wrong: dropping under ≤4.2 would reintroduce the exact index-shift bug the paragraph above describes;
substituting in both branches would be a no-op branch a future reader would simplify away — back into the first,
wrong shape. Pinned end to end (construction, `Builder#altBases`, and the writer's independent copy of this
decision, run under both a 4.2-declared and a 4.3-declared header and asserted identical) by
`VcfPositionTest.testAltEmptyEntrySubstitutionPreservesGtIndexAcrossDeclaredVersions`,
`VcfPositionTest.testBuilderAltBasesSubstitutesEmptyEntryBeforeDelegatingToTrustedFactory`, and
`VcfWriterTest.testDefaultWriteSubstitutesEmptyAltEntryWithDotUnconditionallyAcrossDeclaredVersions`.

## Reference table

Verified via `BcftoolsDependencyTest` and the `local-fixtures/` conformance corpus. Each row below names the
specific test and fixture it rests on.

**Table format:** every row below (1-23) follows `BEHAVIOR_TABLE_STANDARD.md`'s per-row `### Row N`
sub-list format, chosen over a wide table for the identical cell-fit problem `STRUCTURAL_DELIMITER_HANDLING.md`'s
own Reference table hit first -- a one-row draft in each format showed the same result: each row here needs up
to four spec citations with verbatim quotes, a `bcftools`/`vcf_validator` cell each with enough prose to state
the exact diagnostic (not just "invalid"), one to three path outcomes, a probe shape, and a fixture/test
citation, which does not fit a table cell any more legibly here than it did there.

**Tier 2 evidence disclosure** (`BEHAVIOR_TABLE_STANDARD.md`'s "Preserving the evidence"): no separate probe
script is committed for rows 1-23 -- each row's bcftools evidence lives in its own dedicated
`BcftoolsDependencyTest` case, and its vcf_validator run is recorded verbatim, invocation included, in its own
`local-fixtures/` fixture's `##Authority=` lines (rows 1-17 and 19-23 are each a `failed/`-corpus fixture; row
18, the control row, is the corpus's own `passed/` counterpart, per the descriptive-only exemption below --
`BEHAVIOR_TABLE_STANDARD.md`'s "No separate probe script is required" for a descriptive-only row).

**Probe shape, common to rows 1-12:** each is a data-line field (`ID`/`FILTER`/`ALT`/FORMAT's key list/a
sample's colon-separated values/`INFO`'s semicolon-separated entries), not a `##KEY=<...>` metadata
declaration, so "position in the attribute list" from `BEHAVIOR_TABLE_STANDARD.md`'s attribute-list-specific
guidance does not apply the same way. The analogous dimension here is interior-vs-trailing placement of the
empty entry, which rows 3 vs. 4 show is **not** interchangeable -- every row states which it probes.

**Naming our paths for rows 1-12:** for `ID`/`FILTER`/`ALT`/FORMAT's key list, and `INFO`'s top-level entries
(rows 1-8, 12), `VcfPosition`'s own constructor calls `checkIds`/`checkFilters`/`checkAltBases`/`checkFormat`/
`checkInfoEntries` unconditionally on every non-trusted construction route -- verified by reading how
`VcfParser` itself builds each record (`VcfParser.java`'s `context.newPositionTakingOwnership`/
`newPositionWithTrustedFormat` call): `newPositionTakingOwnership` runs every one of these checks, and
`newPositionWithTrustedFormat`'s cache-hit branch skips only `checkFormat` (reusing a result the same check
already produced for the immediately preceding record's identical FORMAT text, not skipping the check's
outcome). `VcfWriter.Builder.validateBeforeWrite()`'s `position.validate()` call re-runs the identical checks.
So **Parse, Public construction, and Validated write collapse into one column** for these nine rows, per
`BEHAVIOR_TABLE_STANDARD.md`'s "verified to produce the identical outcome for every row, not merely because
they call the same method" test. Default write differs only for rows 1-6 (`ID`/`FILTER`/`ALT`), where
`VcfWriter.addListOrElse()` independently re-implements the identical drop/substitute decision as a hot-path
duplicate, reachable when an empty entry survives to `writeLine()` without validation having re-run (e.g. via
`VcfContext.newPositionCopyingTrustedFields`) -- see "Where this is implemented" below. Rows 7-8 (FORMAT's key
list) and row 12 (`INFO`) have no such duplicate: this library's own strategy for both is to keep the value
exactly as constructed (FORMAT: keep-as-is; `INFO`: already dropped by the one, always-run `checkInfoEntries`),
so default write has nothing further to do for either -- it is a constant "emits verbatim", stated once here
per `BEHAVIOR_TABLE_STANDARD.md`'s "Default write is usually a constant" rule rather than as a repeated
per-row column.

For sample values (rows 9-11), this collapse does **not** hold: `VcfParser` eagerly corrects an empty sample
value (`InternalUtils.fillEmptyEntriesWithDot`, called before `context.newTrustedSample(...)`) before a
`VcfSample` even exists, but ordinary Public construction of a `VcfSample` (e.g. `VcfContext.newSample`) does
not -- correction there is deferred to an explicit `.validate()` call (also what `validateBeforeWrite()`'s own
`sample.validate()` invokes). A directly-constructed, not-yet-validated `VcfSample` can therefore return the
empty value from a raw property read, something a parsed one never can. Each of rows 9-11 tables this as two
states rather than one: "Parse / Public construction + `.validate()` / Validated write" (all three converge on
warn + substitute `.`) and "Public construction, before `.validate()`" (accepts silently, no warning). Default
write for these three rows has its own hot-path duplicate too, `VcfWriter.addSampleConditionally()`, reachable
via ordinary construction of a legitimately-empty (not structurally invalid) sample value.

**Control row (`BEHAVIOR_TABLE_STANDARD.md`'s "Include a control row"):** none of rows 1-12 functions as one --
every one of the twelve inputs is itself a malformed, zero-length-field case, so none is the clean "must
round-trip untouched" input the standard requires. Row 18 ("INFO flag `DB;DP=10`, no `=`") is that control row
-- see its own section below for why it is labeled, and reclassified descriptive-only, there rather than here.

### Index

| Row | Field | Payload | Classification | Outcome |
|---|---|---|---|---|
| [1](#row-1----id-rs1rs2-interior-empty-entry) | ID | `rs1;;rs2` (interior) | Decision-bearing | warn + drop |
| [2](#row-2----id-rs3-trailing-empty-entry) | ID | `rs3;` (trailing) | Decision-bearing | warn + drop |
| [3](#row-3----filter-q10q20-interior-empty-entry) | FILTER | `q10;;q20` (interior) | Decision-bearing | warn + drop (bcftools itself warns) |
| [4](#row-4----filter-q10-trailing-empty-entry) | FILTER | `q10;` (trailing) | Decision-bearing | warn + drop (bcftools itself silent -- see the row 3/4 asymmetry) |
| [5](#row-5----alt-tc-interior-empty-entry) | ALT | `T,,C` (interior) | Decision-bearing | warn + substitute `.` |
| [6](#row-6----alt-t-trailing-empty-entry) | ALT | `T,` (trailing) | Decision-bearing | warn + substitute `.` |
| [7](#row-7----format-key-gtgq-interior-empty-entry) | FORMAT key | `GT::GQ` (interior) | Decision-bearing | warn, kept as-is |
| [8](#row-8----format-key-gtdp-trailing-empty-entry) | FORMAT key | `GT:DP:` (trailing) | Decision-bearing | warn, kept as-is |
| [9](#row-9----sample-value-01-under-format-gtdp-trailing-empty-sub-field) | Sample value | `0/1:` under `GT:DP` (trailing) | Decision-bearing | Parse: warn + substitute `.`; Public construction (pre-`validate()`): silent |
| [10](#row-10----sample-value-0130-under-format-gtdpgq-dp-declared-typeinteger-interior-empty-sub-field) | Sample value | `0/1::30` under `GT:DP:GQ`, `DP` Integer (interior) | Decision-bearing | Parse: warn + substitute `.`; Public construction (pre-`validate()`): silent |
| [11](#row-11----sample-value-0130-under-format-gtxxgq-xx-declared-typestring-interior-empty-sub-field) | Sample value | `0/1::30` under `GT:XX:GQ`, `XX` String (interior) | Decision-bearing | Parse: warn + substitute `.`; Public construction (pre-`validate()`): silent -- and deliberately diverging from bcftools' own NUL-byte artifact |
| [12](#row-12----info-top-level-entry-dp10af05-interior-empty-entry) | INFO top-level entry | `DP=10;;AF=0.5` (interior) | Decision-bearing | warn + drop |
| [13](#row-13----info-top-level-entry-dp10-trailing-empty-entry) | INFO top-level entry | `DP=10;` (trailing) | Decision-bearing | warn + drop |
| [14](#row-14----info-entry-with-an-empty-key-5dp10) | INFO entry | `=5;DP=10` (empty key) | Decision-bearing | warn + drop |
| [15](#row-15----info-value-ad12-interior-empty-element) | INFO value | `AD=1,,2` (interior) | Decision-bearing | Parse / typed read / either write mode: warn + substitute `.`; Public construction read untyped: silent **even after `.validate()`** |
| [16](#row-16----info-value-ad12-trailing-empty-element) | INFO value | `AD=1,2,` (trailing) | Decision-bearing | Parse / typed read / either write mode: warn + substitute `.`; Public construction read untyped: silent **even after `.validate()`** |
| [17](#row-17----info-value-ad-wholly-empty) | INFO value | `AD=` (wholly empty) | Decision-bearing | Parse / typed read / either write mode: warn + substitute `.`; Public construction read untyped: silent **even after `.validate()`** |
| [18](#row-18----info-flag-dbdp10-no-----the-control-row) | INFO flag | `DB;DP=10` (no `=`) | Descriptive-only -- the control row | fine, unaffected |
| [19](#row-19----gt-allele-0-trailing-empty-allele) | GT allele | `0/` (trailing) | Decision-bearing | warn + substitute `.`, read-side only (bcftools hard-errors) |
| [20](#row-20----reserved-formathq-value-12-trailing-empty-element) | Reserved FORMAT/HQ | `1,2,` (trailing) | Decision-bearing | warn + substitute `.`, read-side only |
| [21](#row-21----reserved-formatft-value-q10q20-interior-empty-code) | Reserved FORMAT/FT | `q10;;q20` (interior) | Decision-bearing | warn + drop code, read-side only |
| [22](#row-22----reserved-formatft-value--all-codes-empty) | Reserved FORMAT/FT | `;;` (all empty) | Decision-bearing | warn + drop all, normalize to `.`, read-side only |
| [23](#row-23----generic-metadata-property-reference-empty-value) | Generic metadata property | `##reference=` | Decision-bearing | warn + drop entire property |

**A note on rows 15-17's `AD` label:** those three rows are headed and indexed with `AD` because that is the
key `BcftoolsDependencyTest` measures them on, but each row's own `local-fixtures/` fixture and its
vcf_validator citation use a test key `XD` instead -- a deliberate substitution, so that `AD`'s reserved
`Number=R` declaration doesn't add an unrelated arity warning on top of the empty-element one being probed.
Expect to find `XD`, not `AD`, on opening any of those three fixtures; see each row's own Probe shape bullet.

### Row 1 -- ID `rs1;;rs2` (interior empty entry)

- **Classification:** Decision-bearing, split (per `BEHAVIOR_TABLE_STANDARD.md`'s own worked example of
  splitting a row rather than conflating a spec-plain fact with a tool-derived one): that a zero-length `ID`
  entry is spec-forbidden is spec-plain; that this library recovers it by **dropping** the entry rather than
  substituting `.` is self-evident from position-independence (nothing indexes into the `ID` list -- see "How
  each field is normalized" above); that recovery happens by **warning**, not throwing, rests on bcftools' own
  measured tolerance of this exact spec-forbidden shape.
- **Probe shape:** `ID` column, two `;`-separated entries with an empty one **interior** between them
  (`rs1;;rs2`), the column's sole content. No `##ID`-shaped declaration exists to declare IDs, so there is no
  "undeclared" warning this test could be confused with -- unlike FILTER/FORMAT-key below, an undeclared `ID`
  is never itself a warnable condition.
- **4.2:** `silent` -- VCFv4.2.tex has no "Zero length fields..." statement at all (confirmed: zero hits for
  "Zero length" anywhere in `VCFv4.2.tex`).
- **4.3:** `VCFv4.3.tex:39` -- "Zero length fields are not allowed, a dot (``.'') must be used instead."
- **4.4:** `VCFv4.4.tex:39` -- identical wording to the 4.3 citation above.
- **4.5:** `VCFv4.5.tex:37` -- identical wording to the 4.3 citation above (line number shifts by two; text
  unchanged).
- **bcftools 1.23.1:** passes `rs1;;rs2` through byte-for-byte, no warning, exit 0 -- bcftools does not treat
  the `ID` column's `;`-separated entries as structural at all.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 10: ID is not a single dot or a list of
  strings without semicolons or whitespaces." (measured live via `vcf_validator -i <file> -r summary,text`).
- **Parse / Public construction / Validated write:** warn + drop (`VcfPosition.checkIds`, reached identically
  by all three -- see "Naming our paths for rows 1-12" above).
- **Default write:** warn + drop, via `VcfWriter.addListOrElse()`'s own duplicated decision table, reached only
  if an empty `ID` entry survives to `writeLine()` without `position.validate()` having re-run (e.g. through
  `VcfContext.newPositionCopyingTrustedFields`).
- **Test/fixture evidence:** `BcftoolsDependencyTest#testEmptyIdEntryInteriorBcftoolsPassesThroughSilently`;
  `local-fixtures/failed/id-doubled-semicolon-empty-entry.vcf`.

### Row 2 -- ID `rs3;` (trailing empty entry)

- **Classification:** Decision-bearing, split -- identical reasoning to row 1, applied to a trailing rather
  than interior empty entry.
- **Probe shape:** `ID` column, a single trailing `;` with nothing after it (`rs3;`); no `##ID`-shaped
  declaration, for the same reason as row 1.
- **4.2 - 4.5:** identical citations to row 1 (`silent`; `VCFv4.3.tex:39`; `VCFv4.4.tex:39`; `VCFv4.5.tex:37`)
  -- the general zero-length-fields rule does not distinguish interior from trailing placement.
- **bcftools 1.23.1:** passes `rs3;` through byte-for-byte, no warning, exit 0 -- the identical silent
  -passthrough behavior as row 1's interior case; unlike FILTER's row 3/4 asymmetry below, `ID`'s passthrough
  does not depend on position.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 10: ID is not a single dot or a list of
  strings without semicolons or whitespaces." (same message text as row 1; measured live).
- **Parse / Public construction / Validated write:** warn + drop (`VcfPosition.checkIds`).
- **Default write:** warn + drop, via `VcfWriter.addListOrElse()`, same as row 1.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testEmptyIdEntryTrailingBcftoolsPassesThroughSilently`;
  `local-fixtures/failed/id-trailing-semicolon.vcf`.

### Row 3 -- FILTER `q10;;q20` (interior empty entry)

- **Classification:** Decision-bearing, split -- same three-way split as row 1 (spec-plain zero-length
  prohibition; self-evident drop strategy from FILTER's own position-independence; bcftools-measured
  severity), with FILTER's own severity now confirmed to be **warn**, not the silent passthrough `ID` gets.
- **Probe shape:** `FILTER` column, two `;`-separated codes with an empty entry interior between them
  (`q10;;q20`); both `q10` and `q20` are declared via their own `##FILTER` lines first, so bcftools' warning
  can be attributed to the empty entry specifically, not to an undeclared code.
- **4.2:** `silent`, for the identical reason as row 1.
- **4.3:** `VCFv4.3.tex:39` -- same citation as row 1 (the general rule governs every data-line field alike,
  FILTER included).
- **4.4:** `VCFv4.4.tex:39` -- identical wording.
- **4.5:** `VCFv4.5.tex:37` -- identical wording.
- **bcftools 1.23.1:** warns `FILTER '' is not defined in the header` but keeps the FILTER value unchanged
  (`q10;;q20`), exit 0 -- unlike `ID`, bcftools treats an empty FILTER entry as an (undefined) filter code and
  reports it as such, though it still declines to reject the record.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 12: Filter is not a single dot or a
  semicolon-separated list of strings." (measured live against this row's own fixture).
- **Parse / Public construction / Validated write:** warn + drop (`VcfPosition.checkFilters`).
- **Default write:** warn + drop, via `VcfWriter.addListOrElse()`.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testEmptyFilterEntryInteriorBcftoolsWarnsButKeepsValue`;
  `local-fixtures/failed/filter-doubled-semicolon.vcf`.

### Row 4 -- FILTER `q10;` (trailing empty entry)

- **Classification:** Decision-bearing, split -- identical reasoning to row 3, but bcftools' own measured
  severity flips for a trailing entry: **this is a real, confirmed asymmetry, not a transcription error.** An
  interior empty FILTER entry (row 3) makes bcftools warn; the identical malformed shape, only trailing
  (`q10;`), makes bcftools drop it with **no warning at all**. This library's own `checkFilters` does not
  replicate this position-dependent asymmetry -- it warns uniformly for both shapes, matching `AGENTS.md`'s
  Parser Validation Policy rather than bcftools' own inconsistency between the two.
- **Probe shape:** `FILTER` column, a single declared code followed by a trailing `;` (`q10;`); `q10` is
  declared via `##FILTER` first, for the same reason as row 3.
- **4.2 - 4.5:** identical citations to row 3.
- **bcftools 1.23.1:** silently drops the trailing empty entry, **no warning**, exit 0, leaving `q10` --
  contrast directly with row 3's warning for the interior case.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 11: Filter is not a single dot or a
  semicolon-separated list of strings." (measured live).
- **Parse / Public construction / Validated write:** warn + drop (`VcfPosition.checkFilters`) -- unchanged
  from row 3 despite bcftools' own asymmetry.
- **Default write:** warn + drop, via `VcfWriter.addListOrElse()`.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testEmptyFilterEntryTrailingBcftoolsSilentlyDrops`;
  `local-fixtures/failed/filter-trailing-semicolon.vcf`.

### Row 5 -- ALT `T,,C` (interior empty entry)

- **Classification:** Decision-bearing, split -- unlike the position-independent lists above, the
  **substitute-`.`-not-drop** strategy here is itself spec-grounded, not merely self-evident convention:
  `VCFv4.3.tex:350` states an ALT allele's "Options are ... or a MISSING value `.` (no variant)", and
  `VcfPosition.getAllele(int)` indexes into `ALT` positionally (`GT` values reference alleles by position --
  see "How each field is normalized" above), so dropping would silently misdirect a later `GT` index. Only the
  **warn-not-throw** severity remains bcftools-measured.
- **Probe shape:** `ALT` column, two comma-separated alleles with an empty entry interior between them
  (`T,,C`).
- **4.2:** `silent` on ALT's own `.`-as-MISSING-value grammar -- `VCFv4.2.tex`'s ALT description does not use
  the word "MISSING" or list `.` as an explicit allele token the way 4.3+ do (though `VCFv4.2.tex` line 217's
  `GT`-indexes-into-`ALT` rule, already cited above the table, holds regardless of this silence).
- **4.3:** `VCFv4.3.tex:350` -- "Options are base Strings made up of the bases A,C,G,T,N (case insensitive) or
  the `*' symbol ... or a MISSING value `.' (no variant) or an angle-bracketed ID String ... or a breakend
  replacement string".
- **4.4:** `VCFv4.4.tex:367` -- restates this more explicitly (already cited above the table): "Each allele in
  this list must be one of: ... the MISSING value `.` (no variant); ...".
- **4.5:** `VCFv4.5.tex:416` -- identical restatement (already cited above the table).
- **bcftools 1.23.1:** silently substitutes `.` for the interior empty entry (`T,,C` -> `T,.,C`), no warning,
  exit 0.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 11: Alternate is not a single dot or a
  comma-separated list of bases." (measured live) -- the same stricter whole-field-regex reading already
  discussed at length above the table (vcf_validator rejects this library's own substituted output too).
- **Parse / Public construction / Validated write:** warn + substitute `.` (`VcfPosition.checkAltBases`).
- **Default write:** warn + substitute `.`, via `VcfWriter.addListOrElse()`.
- **Test/fixture evidence:**
  `BcftoolsDependencyTest#testEmptyAltEntryBcftoolsSubstitutesDotMatchingThisLibrarysOwnChoice` (pre-existing);
  `local-fixtures/failed/alt-doubled-comma.vcf`.

### Row 6 -- ALT `T,` (trailing empty entry)

- **Classification:** Decision-bearing, split -- identical reasoning to row 5, applied to a trailing rather
  than interior empty entry; unlike FILTER's rows 3/4, ALT shows no interior/trailing asymmetry in bcftools'
  own behavior.
- **Probe shape:** `ALT` column, a single allele followed by a trailing `,` (`T,`).
- **4.2 - 4.5:** identical citations to row 5.
- **bcftools 1.23.1:** silently substitutes `.` for the trailing empty entry (`T,` -> `T,.`), no warning, exit
  0 -- confirms row 5's substitution rule is not interior-only.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 11: Alternate is not a single dot or a
  comma-separated list of bases." (measured live; same message as row 5).
- **Parse / Public construction / Validated write:** warn + substitute `.` (`VcfPosition.checkAltBases`).
- **Default write:** warn + substitute `.`, via `VcfWriter.addListOrElse()`.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testTrailingEmptyAltEntryBcftoolsSubstitutesDot`;
  `local-fixtures/failed/alt-trailing-comma.vcf`.

### Row 7 -- FORMAT key `GT::GQ` (interior empty entry)

- **Classification:** Decision-bearing -- and here even the **strategy** itself (keep the empty key exactly
  as-is, neither drop nor substitute), not just the severity, is bcftools-derived: "How each field is
  normalized" above states this explicitly matches bcftools' own behavior ("it warns \"not defined in header\"
  but does not drop the entry either"). An empty FORMAT key cannot be substituted with `.` the way a *value*
  can (`.` is not a legal key), and dropping would misalign every later sample value's index against the
  FORMAT key list -- so bcftools' own choice (keep it, warn about it) is the only option that preserves
  alignment, not merely the one this library happened to copy.
- **Probe shape:** `FORMAT` column, two declared keys with an empty key interior between them (`GT::GQ`), both
  `GT` and `GQ` declared via `##FORMAT` first.
- **4.2:** `silent` on a character-set constraint for FORMAT keys -- `VCFv4.2.tex:211` describes the FORMAT
  field only as "colon-separated alphanumeric String", with no regex or explicit minimum-length rule.
- **4.3:** `VCFv4.3.tex:429` -- "First a FORMAT field is given specifying the data types and order
  (colon-separated FORMAT keys matching the regular expression `^[A-Za-z_][0-9A-Za-z_.]*$`, duplicate keys are
  not allowed)." This regex requires at least one leading `[A-Za-z_]` character, so an empty key fails it -- a
  more specific citation than the general zero-length-fields rule (`VCFv4.3.tex:39`, which also applies but
  does not by itself explain why a *key*, not just a value, is affected).
- **4.4:** `VCFv4.4.tex:447` -- identical wording to the 4.3 citation above.
- **4.5:** `VCFv4.5.tex:499` -- identical wording to the 4.3 citation above.
- **bcftools 1.23.1:** warns twice -- "is not defined in the header" and "Invalid tag name" -- but keeps the
  FORMAT column text unchanged (`GT::GQ`), exit 0.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 12: Format does not start with a
  letter/underscore followed by alphanumeric/underscore/dot characters." (measured live) -- notably, this is
  vcf_validator applying essentially the same character-class rule the 4.3+ regex above states.
- **Parse / Public construction / Validated write:** warn, kept as-is (`VcfPosition.checkFormat`).
- **Default write:** emits verbatim -- no separate duplicate-logic column applies here (see "Naming our paths
  for rows 1-12" above): this library's own strategy is already "keep as constructed," so there is nothing
  left for the writer to correct.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testFormatKeyEmptyEntryInteriorBcftoolsWarnsAndKeepsAsIs`;
  `local-fixtures/failed/format-key-doubled-colon.vcf`.

### Row 8 -- FORMAT key `GT:DP:` (trailing empty entry)

- **Classification:** Decision-bearing, for the identical reason as row 7 -- applied to a trailing rather than
  interior empty key.
- **Probe shape:** `FORMAT` column, two declared keys followed by a trailing empty key (`GT:DP:`), both `GT`
  and `DP` declared via `##FORMAT` first.
- **4.2 - 4.5:** identical citations to row 7 -- the regex (4.3+) and its absence (4.2) do not distinguish
  interior from trailing position.
- **bcftools 1.23.1:** the same pair of warnings as row 7 ("is not defined in the header", "Invalid tag
  name"), FORMAT column text unchanged (`GT:DP:`), exit 0 -- unlike FILTER's row 3/4 asymmetry, FORMAT-key
  severity does not depend on position.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 12: Format does not start with a
  letter/underscore followed by alphanumeric/underscore/dot characters." (measured live; same message as row
  7).
- **Parse / Public construction / Validated write:** warn, kept as-is (`VcfPosition.checkFormat`).
- **Default write:** emits verbatim, same reasoning as row 7.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testFormatKeyEmptyEntryTrailingBcftoolsWarnsAndKeepsAsIs`;
  `local-fixtures/failed/format-key-trailing-colon.vcf`.

### Row 9 -- Sample value `0/1:` under FORMAT `GT:DP` (trailing empty sub-field)

- **Classification:** Decision-bearing, split: the **substitute-`.`** strategy is spec-grounded, not merely
  self-evident -- `VCFv4.2.tex:236`/`VCFv4.3.tex:435`/`VCFv4.4.tex:453`/`VCFv4.5.tex:506` each state "If any of
  the fields is missing, it is replaced with the MISSING value" (identically in substance, across every
  version this library targets or accepts, unlike the general zero-length-fields rule which is silent in 4.2).
  The **warn-not-throw** severity remains bcftools-measured, and bcftools does not diverge here the way it
  does for `GT` itself (row 19, outside this range) -- this is not a case where the library's leniency stands
  independent of tool behavior.
- **Probe shape:** sample column, FORMAT `GT:DP`, value `0/1:` -- a trailing `:` with nothing after it, so the
  declared `DP` sub-field is present as an empty string rather than dropped outright (dropping the sub-field
  entirely, e.g. `0/1`, is the spec's own explicitly sanctioned shorthand for "missing trailing fields" -- see
  `VCFv4.3.tex:438`, "Trailing fields can be dropped" -- a different, unproblematic case from this row's
  explicit empty string).
- **4.2:** `VCFv4.2.tex:236` -- "If any of the fields is missing, it is replaced with the missing value. For
  example if the FORMAT is GT:GQ:DP:HQ then $0\mid0:.:23:23,34$ indicates that GQ is missing."
- **4.3:** `VCFv4.3.tex:435` -- "If any of the fields is missing, it is replaced with the MISSING value."
- **4.4:** `VCFv4.4.tex:453` -- identical wording to the 4.3 citation above.
- **4.5:** `VCFv4.5.tex:506` -- identical wording to the 4.3 citation above.
- **bcftools 1.23.1:** silently substitutes `.` for the missing trailing `DP` sub-field (`0/1:` -> `0/1:.`), no
  warning, exit 0.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 12: Sample #1 is not a valid string."
  (measured live) -- per `AGENTS.md`'s own caution, vcf_validator's rigor on sample-column sub-structure is not
  load-bearing here regardless.
- **Parse:** warn + substitute `.` (`InternalUtils.fillEmptyEntriesWithDot`, run eagerly before the `VcfSample`
  is constructed).
- **Public construction, before an explicit `.validate()` call:** accepts silently -- **not** the same outcome
  as Parse (see "Naming our paths for rows 1-12" above). A `VcfSample` built directly with an empty string in
  this position (e.g. via `VcfContext.newSample`) holds it unchanged until `.validate()` runs.
- **Public construction + `.validate()` / Validated write:** warn + substitute `.` (`VcfSample.validate()`,
  also what `VcfWriter.Builder.validateBeforeWrite()`'s `sample.validate()` call invokes) -- converges with
  Parse's outcome.
- **Default write:** warn + substitute `.`, via `VcfWriter.addSampleConditionally()`'s own duplicated logic.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testSampleValueTrailingEmptySubfieldBcftoolsSubstitutesDot`;
  `local-fixtures/failed/sample-value-trailing-colon.vcf`.

### Row 10 -- Sample value `0/1::30` under FORMAT `GT:DP:GQ`, `DP` declared `Type=Integer` (interior empty sub-field)

- **Classification:** Decision-bearing, split -- identical reasoning to row 9, applied to an interior rather
  than trailing empty sub-field, on a numeric (`Integer`) field specifically.
- **Probe shape:** sample column, FORMAT `GT:DP:GQ`, value `0/1::30` -- an interior empty `DP` sub-field
  between two present ones (`GT`=`0/1`, `GQ`=`30`); `DP` is declared `Type=Integer`.
- **4.2 - 4.5:** identical citations to row 9.
- **bcftools 1.23.1:** its numeric-field parser treats the empty `DP` sub-field as an extreme/unparseable value
  and warns "Extreme FORMAT/DP value encountered and set to missing", substituting `.` (`0/1::30` ->
  `0/1:.:30`) -- a distinct warning mechanism from rows 7/8's FORMAT-key-level warnings above, but the
  identical final substitution.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 13: Sample #1 is not a valid string."
  (measured live).
- **Parse:** warn + substitute `.`, identical mechanism to row 9.
- **Public construction, before `.validate()`:** accepts silently, identical divergence to row 9.
- **Public construction + `.validate()` / Validated write:** warn + substitute `.`.
- **Default write:** warn + substitute `.`, via `VcfWriter.addSampleConditionally()`.
- **Test/fixture evidence:**
  `BcftoolsDependencyTest#testSampleValueInteriorEmptySubfieldIntegerFieldBcftoolsWarnsAndSubstitutesDot`;
  `local-fixtures/failed/sample-value-extreme-integer-warning.vcf`.

### Row 11 -- Sample value `0/1::30` under FORMAT `GT:XX:GQ`, `XX` declared `Type=String` (interior empty sub-field)

- **Classification:** Decision-bearing, split -- same spec-grounded substitute-`.` strategy and
  bcftools-measured warn-not-throw severity as rows 9-10, but bcftools' own **substituted value** genuinely
  diverges here, worth stating plainly rather than treating as another "matches bcftools" row: for a
  `String`-typed sub-field specifically, bcftools represents the empty value internally as a raw NUL byte
  (`0x00`), not `.` -- confirmed via a literal NUL byte appearing in its own text output (not merely inferred
  from behavior). This library substitutes `.` uniformly regardless of the sub-field's declared `Type`,
  deliberately not replicating bcftools' `String`-specific artifact -- consistent with "How each field is
  normalized" above (`.` is the one missing-value sentinel every other row in this table already converges on)
  and with the Parser Validation Policy's "preserve or normalize the value in the least surprising usable form"
  instruction (`AGENTS.md`) -- a raw NUL byte is not a usable VCF value.
- **Probe shape:** sample column, FORMAT `GT:XX:GQ`, value `0/1::30` -- an interior empty `XX` sub-field
  between two present ones; `XX` is declared `Type=String` (a non-reserved, test-only key, chosen specifically
  to force bcftools' String-typed code path rather than its Integer one).
- **4.2 - 4.5:** identical citations to rows 9-10 -- the spec's own "missing field -> MISSING value" rule does
  not distinguish by declared `Type`.
- **bcftools 1.23.1:** silent (no warning at all, unlike row 10's Integer case) and represents the empty `XX`
  sub-field internally as a raw NUL byte (`0x00`), confirmed via the literal byte appearing in bcftools' own
  combined stdout+stderr text.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 13: Sample #1 is not a valid string."
  (measured live; same message as row 10).
- **Parse:** warn + substitute `.`, identical mechanism to rows 9-10 -- diverging deliberately from bcftools'
  own NUL-byte artifact, per the classification note above.
- **Public construction, before `.validate()`:** accepts silently, identical divergence to rows 9-10.
- **Public construction + `.validate()` / Validated write:** warn + substitute `.`.
- **Default write:** warn + substitute `.`, via `VcfWriter.addSampleConditionally()`.
- **Test/fixture evidence:**
  `BcftoolsDependencyTest#testSampleValueInteriorEmptySubfieldStringFieldBcftoolsStoresRawNulByte`;
  `local-fixtures/failed/sample-value-empty-string-field.vcf`.

### Row 12 -- INFO top-level entry `DP=10;;AF=0.5` (interior empty entry)

- **Classification:** Decision-bearing, split -- same three-way split as row 1: spec-plain zero-length
  prohibition; self-evident drop strategy (INFO's top-level `;`-separated entries are position-independent,
  per "How each field is normalized" above); bcftools-measured warn-not-throw severity, here manifesting as
  bcftools' own **silent** drop (no warning at all), matching FILTER's trailing case (row 4) rather than its
  interior one (row 3).
- **Probe shape:** `INFO` column, two declared keys' entries with an empty top-level entry interior between
  them (`DP=10;;AF=0.5`); both `DP` and `AF` are declared via `##INFO` first, so the drop can be attributed to
  the empty entry, not an undeclared key.
- **4.2:** `silent`, for the identical reason as row 1.
- **4.3:** `VCFv4.3.tex:39` -- same citation as row 1 (the general rule governs INFO's top-level entries the
  same as any other data-line field).
- **4.4:** `VCFv4.4.tex:39` -- identical wording.
- **4.5:** `VCFv4.5.tex:37` -- identical wording.
- **bcftools 1.23.1:** silently drops the empty entry (`DP=10;;AF=0.5` -> `DP=10;AF=0.5`), no warning, exit 0.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 12: Info key is not a sequence of
  alphanumeric and/or punctuation characters." (measured live).
- **Parse / Public construction / Validated write:** warn + drop (`VcfPosition.info()`/`checkInfoEntries`, the
  one always-run check for this field -- see "Naming our paths for rows 1-12" above).
- **Default write:** emits verbatim -- no separate duplicate-logic column applies (same reasoning as rows 7-8):
  `checkInfoEntries` has no known construction-time bypass, so an empty top-level entry can never reach the
  writer unvalidated in the first place.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testInfoEmptyTopLevelEntryInteriorBcftoolsSilentlyDrops`;
  `local-fixtures/failed/info-doubled-semicolon.vcf`.

**Naming our paths for rows 13-23:** unlike rows 1-12, this range is not one homogeneous group -- each
sub-range has its own path structure, derived by reading the actual source rather than assumed to transfer
from rows 1-12's pattern:

- **Rows 13-14 (INFO top-level entry / empty key):** identical collapse to rows 1-4/12 -- `VcfPosition.info()`'s
  own inline checks and `checkInfoEntries` are the always-run pair reached by Parse, Public construction, and
  Validated write alike; Default write emits verbatim (no known construction-time bypass for either check).
- **Rows 15-17 (INFO *value* emptiness):** a genuine, three-way path divergence, not a collapse. `info()`'s
  own per-value split (`prop.substring(idx + 1).split(",", -1)`) warns and substitutes `.` immediately as part
  of the same lazy first-parse that also drops empty entries/keys (rows 12-14) -- so **Parse** already carries
  the substitution. But `checkInfoEntries` (the check shared by the constructor and `.validate()`) has **no**
  equivalent empty-*value* check, so **Public construction with a pre-built `ListMultimap`, read via untyped
  `getInfo()`/`getInfo(String)`, stays silent even after an explicit `.validate()` call** -- a real divergence
  from rows 9-11's sample-value pattern, where `.validate()` does converge with Parse. A **typed** read
  (`getInfo(ReservedInfoProperty)`/`getInfo(VcfMetadata, String)`) reaches `InternalUtils.convertProperty`'s
  list branch instead, which does warn and substitute. **Default write and Validated write both** run
  `VcfWriter.addInfoOrDot()` unconditionally (per "Where this is implemented" below), so either write mode
  is guaranteed to fix a value that survived construction unread.
- **Row 18:** no path table applies -- reclassified descriptive-only below; there is no violation to normalize
  on any path.
- **Row 19 (GT) and rows 20-22 (reserved list-FORMAT/FT):** **read-side conversion only**, on every one of
  these four rows -- a typed accessor (`getGenotype`, `getProperty(ReservedFormatProperty)`,
  `checkReservedFormatConstraints`) returns a corrected value, but **none of the four ever rewrites the
  sample's own stored property text**, confirmed by reading `VcfSample.validate()` and
  `VcfWriter.addSampleConditionally()` directly: both only substitute `.` when a property's
  *entire* value is empty, and none of these four rows' payloads (`"0/"`, `"1,2,"`, `"q10;;q20"`, `";;"`) is
  itself an empty string. Default write and Validated write therefore emit the malformed text **unchanged** on
  both paths for all four rows. In particular, row 19's round-trip output for `0/` is `0/`, not `0/.`: the `.`
  substitution exists only in the `Genotype` object `getGenotype()` returns (see Row 19's own classification
  bullet).
- **Row 23 (generic metadata property):** construction-time drop (`VcfMetadata.Builder.addRawProperty()`)
  under a lenient `VcfContext`, throw under strict; Validated write warns without dropping (the writer must not
  silently remove content the caller already holds); Default write emits verbatim whatever construction left
  behind. See "Where this is implemented" below for the code these three outcomes correspond to.

### Row 13 -- INFO top-level entry `DP=10;` (trailing empty entry)

- **Classification:** Decision-bearing, split -- the trailing counterpart of row 12's interior case, with the
  identical three-way split (spec-plain zero-length prohibition; self-evident drop strategy from
  position-independence; bcftools-measured severity). The severity is **identical to row 12's, not a new
  asymmetry**: both the interior (row 12) and trailing (this row) empty top-level INFO entry are silently
  dropped by bcftools, no warning either way -- INFO's top-level entries show no interior/trailing asymmetry,
  unlike FILTER's row 3/4 split.
- **Probe shape:** `INFO` column, one declared key (`DP`) followed by a trailing `;` with nothing after it
  (`DP=10;`); `DP` is declared via `##INFO` first, so the drop is attributable to the empty trailing entry, not
  an undeclared key.
- **4.2:** `silent`, identical reasoning to row 1.
- **4.3:** `VCFv4.3.tex:39` -- same citation as row 12.
- **4.4:** `VCFv4.4.tex:39` -- identical wording.
- **4.5:** `VCFv4.5.tex:37` -- identical wording.
- **bcftools 1.23.1:** silently drops the trailing empty entry (`DP=10;` -> `DP=10`), no warning, exit 0.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 11: Info key is not a sequence of
  alphanumeric and/or punctuation characters." (measured live, per this row's own fixture).
- **Parse / Public construction / Validated write:** warn + drop (`VcfPosition.info()`'s own inline check plus
  `checkInfoEntries`, the same always-run pair as row 12).
- **Default write:** emits verbatim, same reasoning as row 12.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testInfoEmptyTopLevelEntryTrailingBcftoolsSilentlyDrops`;
  `local-fixtures/failed/info-trailing-semicolon.vcf`.

### Row 14 -- INFO entry with an empty key `=5;DP=10`

- **Classification:** Decision-bearing, split -- a more specific spec citation is available here than the
  general zero-length rule, from VCFv4.3's INFO-field grammar:
  `VCFv4.3.tex:368`'s INFO-key regex additionally rules out an empty key specifically, the same relationship
  rows 7-8's FORMAT-key regex has to the general rule. The drop strategy is self-evident (an entry with no key
  has nothing to preserve positionally); severity is bcftools-measured.
- **Probe shape:** `INFO` column, a key-less entry (`=5`) followed by a declared key's entry (`DP=10`); `DP` is
  declared via `##INFO` first.
- **4.2:** `silent` on both the general zero-length rule and any INFO-key character-class constraint --
  `VCFv4.2.tex:186` describes INFO fields only as "short keys with optional values," with no regex.
- **4.3:** `VCFv4.3.tex:39` (general rule) plus, more specifically, `VCFv4.3.tex:368`: "INFO keys must match the
  regular expression `^([A-Za-z_][0-9A-Za-z_.]*|1000G)$`, please note that ``1000G'' is allowed as a
  special legacy value." This regex requires >=1 leading `[A-Za-z_]` character, ruling out an empty key the same
  way rows 7-8's FORMAT-key regex does.
- **4.4:** `VCFv4.4.tex:39` / `VCFv4.4.tex:384` -- identical wording to the 4.3 citations above.
- **4.5:** `VCFv4.5.tex:37` / `VCFv4.5.tex:433` -- identical wording.
- **bcftools 1.23.1:** silently drops the key-less `=5` entry entirely, keeps `DP=10`, no warning, exit 0.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 11: Info key is not a sequence of
  alphanumeric and/or punctuation characters." (measured live; same message text as row 13, since
  vcf_validator's own INFO-key grammar check doesn't distinguish "empty" from "trailing-semicolon-produced
  empty" the way this library's two separate inline checks do).
- **Parse / Public construction / Validated write:** warn + drop (`VcfPosition.info()`'s own separate,
  pre-existing inline empty-key check, before a key ever reaches the built multimap; `checkInfoEntries` runs a
  second, equivalent empty-key check for the constructor/`.validate()` route -- see "Where this is implemented"
  below).
- **Default write:** emits verbatim, same reasoning as rows 12-13.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testInfoEntryWithEmptyKeyBcftoolsSilentlyDrops`;
  `local-fixtures/failed/info-entry-empty-key.vcf`.

### Row 15 -- INFO value `AD=1,,2` (interior empty element)

- **Classification:** Decision-bearing, split, along a materially different axis than rows 12-14 above: this
  row is about a *value*, not a key/entry, so `checkInfoEntries` (which drops empty keys/entries but has no
  equivalent empty-*value* check -- see "Where this is implemented" below) is not where this is caught; reading
  `VcfPosition.info()`'s source directly locates the actual check, its own per-value split
  (`for (String value : prop.substring(idx + 1).split(",", -1))`). The **substitute-`.`-not-drop** strategy is
  spec-grounded the same way ALT's is: INFO's per-key values are position-dependent for a `Number=`-declared
  arity, so dropping would silently change the reported element count -- though no version's INFO-`Number`
  prose (`VCFv4.3.tex:157`: "The Number entry is an Integer that describes the number of values that can be
  included with the INFO field") states an arity-counting rule against a value list that itself contains an
  empty element; confirmed silent on that specific interaction across all four versions (VCFv4.2 - VCFv4.5).
  Only the warn-not-throw severity is bcftools-measured.
- **Probe shape:** `INFO` column, a declared key (test key `XD`, not the reserved `AD`, to avoid `AD`'s own
  reserved-`Number=R` mismatch warning confounding this fixture -- see the fixture's own `##Authority=`) with
  two comma-separated values and an empty element interior between them (`XD=1,,2`); `XD` is declared
  `Number=3,Type=Integer` to match this input's 3-element split count.
- **4.2:** `silent`, identical reasoning to row 1.
- **4.3:** `VCFv4.3.tex:39`.
- **4.4:** `VCFv4.4.tex:39`.
- **4.5:** `VCFv4.5.tex:37`.
- **bcftools 1.23.1:** silently substitutes `.` for the interior empty element (`AD=1,,2` -> `AD=1,.,2`), no
  warning, exit 0 (`BcftoolsDependencyTest` measures this on the reserved key `AD`, with an unrelated "AD
  should be declared as Number=R" advisory warning present alongside; the empty-element substitution itself is
  unaffected by the key choice, confirmed live).
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 11: INFO XD does not match the meta
  specification Type=Integer. XD=1,,2." (measured live against this row's own fixture).
- **Parse (first access to INFO, via `info()`'s lazy first-parse):** warn + substitute `.`, immediately, as
  part of the same per-value loop that builds the multimap.
- **Public construction (a caller-supplied `ListMultimap` passed directly, e.g. via `Builder.info(...)`/
  `VcfContext.newPosition`) -- untyped access (`getInfo()`/`getInfo(String)`):** silent -- `checkInfoEntries`
  has no empty-*value* check, so the empty string persists even after an explicit `.validate()` call, unlike
  rows 9-11's sample-value pattern.
- **Public construction -- typed access (`getInfo(ReservedInfoProperty)`/`getInfo(VcfMetadata, String)` for a
  declared `Type`):** warn + substitute `.`, via `InternalUtils.convertProperty`'s list branch.
- **Default write / Validated write (both):** warn + substitute `.`, via `VcfWriter.addInfoOrDot()`, which runs
  unconditionally in both write modes (see "Where this is implemented" below) -- the one path guaranteed to fix
  a value that survived construction unread.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testInfoValueInteriorEmptyElementBcftoolsSubstitutesDot`;
  `local-fixtures/failed/info-value-interior-empty-element.vcf`.

### Row 16 -- INFO value `AD=1,2,` (trailing empty element)

- **Classification:** Decision-bearing, split -- identical reasoning to row 15, applied to a trailing rather
  than interior empty element.
- **Probe shape:** `INFO` column, same `XD,Number=3,Type=Integer` declaration as row 15, two present values
  followed by a trailing empty element (`XD=1,2,`).
- **4.2 - 4.5:** identical citations to row 15.
- **bcftools 1.23.1:** silently substitutes `.` for the trailing empty element (`AD=1,2,` -> `AD=1,2,.`), no
  warning, exit 0 -- confirms row 15's substitution rule is not interior-only (measured on `AD`, same
  key-substitution caveat as row 15).
- **vcf_validator 0.10.2 (4.3):** reports this row's own fixture invalid -- "Line 11: INFO XD does not match
  the meta specification Number=3 (expected 3 value(s)). XD=1,2,." **This is a different shape from the
  vcf_validator asymmetry this document already documents in detail below**, not the same case: this fixture's
  declared `Number=3` does not match the 2-real-element count left after dropping the trailing comma, so
  vcf_validator reports it invalid on arity grounds here. The paragraph below describes the
  general asymmetry using `AD`'s own native `Number=R` arity, which IS tolerated by vcf_validator's
  trailing-comma exception -- the two are not in tension, they simply use different declared arities to
  illustrate different points:

  > The `AD=1,2,` row is a confirmed asymmetry within vcf_validator's own handling of INFO fields specifically:
  > every other INFO/ID/FILTER/ALT empty-field case, interior or trailing, is reported invalid — except a
  > single *trailing* comma, which vcf_validator tolerates without inserting a phantom empty element (confirmed
  > directly from its own text report, which shows no error for this case, only unrelated
  > header-completeness warnings). This tolerance is not specific to an unbounded (`Number=.`) field the way
  > `AD`'s own declared `Number` might suggest: directly verified that a *fixed*-arity field's trailing comma
  > is tolerated the identical way, as long as the resulting (non-phantom) element count still matches the
  > declared `Number` -- vcf_validator still separately, and always, enforces arity regardless of whether an
  > element came from a real value or survived comma-splitting (e.g. `Number=2, XY=1,` and `Number=3, XY=1,2,`
  > are both reported invalid, but only because dropping the trailing comma leaves too few elements for the
  > declared count, not because the trailing comma itself is disallowed there). An *interior* empty entry
  > (`AD=1,,2`) is always invalid, regardless of declared `Number`, since it inserts a real phantom element
  > mid-list rather than merely trailing. This doesn't change `vcf-parser`'s own behavior — every case in this
  > row already warns and substitutes `.` uniformly, with no arity-dependent distinction of its own — it's
  > simply worth recording as a real, reproducible quirk of vcf_validator itself, not a testing mistake.
- **Parse / Public construction / typed access / Default write / Validated write:** identical to row 15 in
  every respect.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testInfoValueTrailingEmptyElementBcftoolsSubstitutesDot`;
  `local-fixtures/failed/info-value-trailing-empty-element.vcf`.

### Row 17 -- INFO value `AD=` (wholly empty)

- **Classification:** Decision-bearing, split -- same reasoning as rows 15-16, but for a wholly-empty value
  rather than one element of a multi-value list; `XD` is declared `Number=1` here since `XD=` splits to exactly
  one empty element -- a genuinely distinct arity shape from rows 15-16's `Number=3`, not just a smaller
  instance of the same list.
- **Probe shape:** `INFO` column, `XD` declared `Number=1,Type=Integer`, value `XD=` (nothing after `=`).
- **4.2 - 4.5:** identical citations to row 15.
- **bcftools 1.23.1:** silently substitutes `.` for the wholly-empty value (`AD=` -> `AD=.`), no warning, exit
  0 (measured on `AD`, same key-substitution caveat as rows 15-16).
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 11: Info field value is not a
  comma-separated list of valid strings (maybe it contains whitespaces?)." (measured live against this row's
  own fixture) -- a distinct message shape from rows 15-16's, since a wholly-empty value doesn't trip the same
  `Type=Integer`/`Number=` mismatch check.
- **Parse / Public construction / typed access / Default write / Validated write:** identical mechanism to
  rows 15-16 -- `info()`'s per-value loop still runs `value.split(",", -1)` even for a single-element value, so
  the wholly-empty case reaches the same code path as an interior/trailing element within a longer list.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testInfoValueWhollyEmptyBcftoolsSubstitutesDot`;
  `local-fixtures/failed/info-value-wholly-empty.vcf`.

### Row 18 -- INFO flag `DB;DP=10` (no `=`) -- the control row

- **Classification:** **Descriptive-only**, per `BEHAVIOR_TABLE_STANDARD.md`'s descriptive-only test: the
  "fine, as expected" outcome rests entirely on a pure code-reading fact
  -- `VcfPosition.info()` distinguishes a flag (no `=` at all, `idx == -1`) from an actual empty value (`=`
  present, nothing after it) *before* either sentinel is created, at two different lines of the same method.
  This distinction would hold even if bcftools' own behavior here were different -- it is not an
  interoperability tolerance choice the way every other row in this table is. Per the standard, no
  `bcftoolsDependencyTest` case is *required* for this classification to stand: this Decision depends on the
  parser's own flag-vs-empty-value branch, not on bcftools.
  **This is the table's control row** (`BEHAVIOR_TABLE_STANDARD.md`'s "Include a control row"): a legitimate,
  well-formed input that must round-trip clean, distinguishing "the harness works" from "everything gets
  flagged."
  **Disclosure on the test:** `BcftoolsDependencyTest#testInfoFlagWithNoEqualsBcftoolsRoundTripsUnchanged`
  remains in the suite despite this descriptive-only classification. It is
  redundant-but-harmless corroboration rather than load-bearing evidence.
- **Probe shape:** `INFO` column, a `Flag`-typed declared key with no `=` (`DB`) followed by a declared
  `Integer` key with a real value (`DP=10`); both declared via `##INFO` first.
- **4.2 - 4.5:** `silent`, collapsed into one statement per the standard's descriptive-only exemption: none of
  the four versions' zero-length-fields text is about this row at all -- a Flag key with no `=` has no value to
  be empty, so the rule this table otherwise tracks doesn't apply here in the first place. `VCFv4.3.tex:374`'s
  own worked example ("Keys without corresponding values may be used to indicate group membership (e.g. H2
  indicates the SNP is found in HapMap 2)") is the closest the spec comes to describing this shape, and
  confirms it as sanctioned syntax, not silence-by-omission.
- **bcftools 1.23.1:** round-trips `DB;DP=10` unchanged, no warning, exit 0.
- **vcf_validator 0.10.2 (4.3):** reports the file valid.
- **Parse / Public construction / Validated write / Default write:** fine, unaffected -- `VcfPosition.info()`'s
  flag branch (`info.put(key, "")` when `idx == -1`) never reaches the empty-value warning branch, which is
  only reached when `idx != -1` (an `=` was present).
- **Test/fixture evidence:** `BcftoolsDependencyTest#testInfoFlagWithNoEqualsBcftoolsRoundTripsUnchanged`;
  `local-fixtures/passed/info-flag-no-equals.vcf`.

### Row 19 -- GT allele `0/` (trailing empty allele)

- **Classification:** Decision-bearing, split, on two facts about `VcfSample.getGenotype(VcfPosition)`'s
  source:
  1. The **substitute-`.`-not-drop** strategy is spec-grounded via `GT`'s positional indexing into `ALT`
     (already cited above the table: `VCFv4.2.tex:217`/`VCFv4.3.tex:493`/`VCFv4.4.tex:516`/`VCFv4.5.tex:615`,
     present in **all four** versions, unlike the general zero-length-fields rule which
     is silent in 4.2) -- dropping an empty allele (e.g. turning `0/` into `0`) would silently change the
     call's ploidy from diploid to haploid, a real correctness bug.
  2. **This row's severity is deliberately NOT `isLenient()`-aware, unlike every other row in this table**:
     `getGenotype`'s empty-allele branch calls `m_warnings.warn(...)` directly, never `warnOrThrow`, so it
     warns-and-recovers even under a **strict** `VcfContext` -- confirmed by reading the source and by
     `VcfSampleTest.testGetGenotypeEmptyAlleleWarnsRatherThanThrowsUnderAStrictContext`, whose own comment
     states this pins "the recoverable-vs-structural split for
     `getGenotype()` specifically... a zero-length allele being merely warn-and-recover is the exception, not
     the rule" (its sibling out-of-range/non-numeric-allele-index checks DO throw under strict). This is a
     narrower, separate fact from `VALIDATION_REFERENCE.md`'s strict-section GT bullet, which covers a
     *malformed or out-of-range* allele index and a *wholly-empty* `GT` value (the latter via
     `getProperty(ReservedFormatProperty)`'s own `isLenient()`-aware substitution) -- both different code
     paths and different payloads from this row's "non-empty `GT` string containing one empty allele token."
     `VALIDATION_REFERENCE.md`'s Lenient "INFO/FORMAT/sample value content" section carries this row's own
     bullet.

  **bcftools' own divergence must not be folded into agreement**: bcftools **hard-errors** on this exact input
  (exit code 8, "Couldn't read GT data: value not a number or '.'"), stricter than this library's own
  warn-and-substitute on every dimension above -- a deliberate, documented divergence per `AGENTS.md`'s Parser
  Validation Policy (sample values are a deliberately permissive category independent of any single tool's
  behavior; splitting `0/` by `/` creates no structural ambiguity), not a "matches bcftools" row the way rows
  1-17 above are framed.
- **Probe shape:** sample column, FORMAT `GT` only, value `0/` -- a trailing `/` with nothing after it, so
  `getGenotype` splits it into two allele tokens, `"0"` and `""`.
- **4.2:** `silent` on the general zero-length-fields rule (identical reasoning to row 1); `VCFv4.2.tex:217`
  (GT-indexes-into-ALT) nonetheless applies regardless of that silence, per the reasoning already given above
  the table for ALT's own unconditional substitution rule.
- **4.3:** `VCFv4.3.tex:39` (general rule) plus `VCFv4.3.tex:493` (GT-indexes-into-ALT, verbatim: "The allele
  values are 0 for the reference allele (what is in the REF field), 1 for the first allele listed in ALT, 2 for
  the second allele list in ALT and so on.").
- **4.4:** `VCFv4.4.tex:39` / `VCFv4.4.tex:516` -- identical wording to the 4.3 citations above.
- **4.5:** `VCFv4.5.tex:37` / `VCFv4.5.tex:615` -- identical wording.
- **bcftools 1.23.1:** **hard error**, exit code 8: `[E::vcf_parse_format_fill5] Couldn't read GT data: value
  not a number or '.'`, followed by `Error: VCF parse error` -- see the classification note above for why this
  is not folded into a "matches bcftools" framing.
- **vcf_validator 0.10.2 (4.3):** reports the file valid -- **but not because it ignores `GT`.** vcf_validator
  0.10.2 does have a `GT`-specific allele-index check, and it has its own interior-vs-trailing asymmetry,
  directly parallel to the FILTER row 3/4 asymmetry above. Measured live against this row's own fixture and
  against several one-token edits of it: a **leading** or **interior** empty allele (`/1`, `0//1`, `0/|1`)
  is reported invalid -- "Line 11: Allele index must not be empty." -- while a
  **trailing** empty allele is reported valid, both for this row's `0/` and for `0|` and `0/1/`; the control
  `0/1` is valid. So this row's "valid" verdict is specific to the *trailing* placement it probes, not evidence
  that `GT` sub-structure goes unchecked. `HTS_SPECS_BASELINE_STATUS.md`'s "GT contains an interior empty allele
  between two adjacent separators (`0/|1`)" row records the same "Allele index must not be empty." message for
  the interior case, from hts-specs' own `failed_body_format_004.vcf`/`_006.vcf` fixtures. `AGENTS.md`'s general
  caution about vcf_validator's sample-column rigor still applies to *reserved FORMAT sub-field values* (rows
  20-22 below), which is a different question from `GT` allele tokens.
- **Parse / Public construction / Validated write / Default write:** **no path divergence in the sense rows
  1-12 track, because the stored value is never rewritten at all** -- `getGenotype(VcfPosition)` is a
  read-time-only typed accessor: it returns a `Genotype` object with the missing allele already normalized to
  `"."`, but never mutates the sample's own stored `GT` text. Confirmed directly against both write paths:
  `VcfSample.validate()` only substitutes `.` when a property's *entire* value is empty
  (`entry.getValue().isEmpty()`), and `VcfWriter.addSampleConditionally()` likewise only substitutes when the
  whole `value` string is empty -- a non-empty string like `"0/"` that merely *contains* an empty allele token
  trips neither check. **The round-trip output for `0/` is therefore `0/`, unchanged, on both the default and
  validated write paths, not `0/.`** -- the
  substitution exists only in the `Genotype` object `getGenotype()` returns, the same "read-side conversion
  only" shape rows 20-22 below already use, not a rewritten wire value.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testEmptyGtAlleleBcftoolsHardErrors`;
  `local-fixtures/failed/gt-empty-allele.vcf`; the strict-vs-lenient pin is
  `VcfSampleTest.testGetGenotypeEmptyAlleleWarnsRatherThanThrowsUnderAStrictContext`.

### Row 20 -- Reserved FORMAT/HQ value `1,2,` (trailing empty element)

- **Classification:** Decision-bearing, split -- the **substitute-`.`** strategy for a reserved list-typed
  FORMAT value uses the same list-arity code path as INFO's rows 15-17 (`InternalUtils.convertProperty`'s
  shared list branch, "List value \"{}\" contains an empty entry..." -- the identical warning text and code
  path, not merely an analogous one); `HQ`'s own declared arity (`Number=2,Type=Integer`) is stated identically
  across all four spec versions, VCFv4.2 - VCFv4.5 ("two comma separated phred qualities"), so the row's own
  list-of-2 shape is spec-grounded, though (like row 15's `Number=` citation) no version states an
  arity-counting rule against a list containing an empty element specifically. Severity is bcftools-measured,
  and this row's mechanism is `isLenient()`-aware (unlike row 19's GT case immediately above), via the same
  `convertProperty` code path rows 15-17 use.
- **Probe shape:** sample column, FORMAT `GT:HQ`, `HQ` declared `Number=2,Type=Integer` (the VCF spec's
  Reserved Genotype Keys table entry), value `1,2,` -- a trailing empty element after two real values, so the
  raw split produces 3 elements against a declared arity of 2 (bcftools does not separately flag this arity
  mismatch; see the bcftools cell below).
- **4.2:** `VCFv4.2.tex:229` -- "HQ : haplotype qualities, two comma separated phred qualities (Integers)" (no
  explicit zero-length-fields rule, per row 1's reasoning, but `HQ`'s own comma-separated-list shape is
  stated).
- **4.3:** `VCFv4.3.tex:39` (general rule) plus `VCFv4.3.tex:560`: "HQ (Integer): Haplotype qualities, two
  comma separated phred qualities."
- **4.4:** `VCFv4.4.tex:39` / `VCFv4.4.tex:586` -- identical wording to the 4.3 citations above.
- **4.5:** `VCFv4.5.tex:37` / `VCFv4.5.tex:685` -- identical wording.
- **bcftools 1.23.1:** warns "Extreme FORMAT/HQ value encountered and set to missing" and substitutes `.` for
  the trailing empty element (`0/1:1,2,` -> `0/1:1,2,.`), exit 0 -- the same warning mechanism as row 10's
  ordinary `Integer`-typed `DP` case above (numeric-field parser treating the empty element as
  extreme/unparseable), not an `HQ`-specific check.
- **vcf_validator 0.10.2 (4.3):** reports the file valid -- and here, unlike row 19's `GT` case, this really
  is vcf_validator not validating the sub-structure: it has no check on a reserved `FORMAT` sub-field value's
  own comma-separated element list, so `AGENTS.md`'s caution about its sample-column rigor applies directly.
- **Parse / Public construction / Validated write / Default write:** **read-side conversion only**, the same
  shape as row 19 above -- `VcfSample.getProperty(ReservedFormatProperty.HaplotypeQualities)` reaches
  `InternalUtils.convertProperty`'s list branch, which warns and substitutes `null` (`.`) for the empty element
  in the *returned* list, but never rewrites the sample's own stored `HQ` text. Round-trip output is therefore
  `0/1:1,2,` unchanged on both write paths, not `0/1:1,2,.` -- `n/a (read-side conversion only)`, matching this
  document's own pre-existing framing for this row (no correction needed here, unlike row 19).
- **Test/fixture evidence:**
  `BcftoolsDependencyTest#testReservedListFormatValueTrailingEmptyElementBcftoolsWarnsAndSubstitutesDot`;
  `local-fixtures/failed/reserved-format-hq-trailing-empty-element.vcf`.

### Row 21 -- Reserved FORMAT/FT value `q10;;q20` (interior empty code)

- **Classification:** Decision-bearing -- and unlike list-typed reserved values (row 20) or `GT` (row 19),
  `FT`'s own semicolon-separated-code grammar is checked by a dedicated method,
  `InternalUtils.checkFilterLikeValue` (via `checkReservedFormatConstraints`), not `convertProperty`'s generic
  list branch -- because `FT`'s own `Type` is `String`, not a comma-separated list type, so its internal
  semicolon-separated structure is invisible to `convertProperty` entirely (bcftools' own passthrough below
  confirms this: `FT` is opaque to it too, for the identical reason). This check is `isLenient()`-aware (warn
  under lenient, throw under strict) per `VALIDATION_REFERENCE.md`'s own FT/PS bullet, which this row's
  citation defers to rather than re-deriving. No version's Reserved Genotype Keys table entry for `FT`
  states anything about emptiness beyond the general zero-length rule and the FILTER-grammar analogy
  `VALIDATION_REFERENCE.md`'s bullet already covers. The wording is near-identical across all four versions,
  but 4.2 packs it into one line where 4.3+ break it across two, so the two halves need separate line numbers:
  `VCFv4.2.tex:223` carries both ("sample genotype filter indicating if this genotype was ``called'' (similar
  in concept to the FILTER field). Again, use PASS to indicate that all filters have been passed, a
  semicolon-separated list of codes for filters that fail, or `.' to indicate that filters have not been
  applied."), while in 4.3+ the FILTER-concept half is on `VCFv4.3.tex:486`/`VCFv4.4.tex:508`/`VCFv4.5.tex:607`
  ("FT (String): Sample genotype filter indicating if this genotype was ``called'' (similar in concept to the
  FILTER field).") and the codes-list half is on the *next* line,
  `VCFv4.3.tex:487`/`VCFv4.4.tex:509`/`VCFv4.5.tex:608` ("Again, use PASS to indicate that all filters have
  been passed, a semicolon-separated list of codes for filters that fail, or `.' to indicate that filters have
  not been applied.").
- **Probe shape:** sample column, FORMAT `GT:FT`, value `q10;;q20` -- an interior empty code between two
  present ones, under a lenient `VcfContext` (the default for parsed input).
- **4.2:** `VCFv4.2.tex:223` (the whole FT description on one line; no explicit zero-length rule, same
  reasoning as row 20's HQ citation).
- **4.3:** `VCFv4.3.tex:39` (general rule) plus `VCFv4.3.tex:486-487` (FT description, split across the two
  lines quoted above).
- **4.4:** `VCFv4.4.tex:39` / `VCFv4.4.tex:508-509` -- identical wording, identically split.
- **4.5:** `VCFv4.5.tex:37` / `VCFv4.5.tex:607-608` -- identical wording, identically split.
- **bcftools 1.23.1:** passes this through silently, no warning, exit 0, unchanged in output (`0/1:q10;;q20`)
  -- `FT` is an opaque `String`-typed key to bcftools, which has no knowledge of `FT`'s own reserved
  semicolon-separated-code grammar.
- **vcf_validator 0.10.2 (4.3):** reports the file valid -- same reasoning as row 20: no check on a reserved
  `FORMAT` sub-field value's own internal structure (here `FT`'s semicolon-separated codes).
- **Parse / Public construction (typed access, lenient `VcfContext`):** warn and drop the empty code (not
  substitute `.` -- codes, unlike a comma-separated list's elements, don't carry the same positional-index
  hazard `Number=`-declared arity does, so `checkFilterLikeValue` drops rather than substitutes; see
  `VALIDATION_REFERENCE.md`'s FT/PS bullet). Under a **strict** `VcfContext`: throws `VcfFormatException` on the
  first empty code -- this check runs lazily on typed access, not at sample construction, so (per this
  document's own introduction) it is not one of the always-run construction-time checks rows 1-12 track.
- **Default write / Validated write:** **read-side conversion only**, same shape as rows 19-20 -- the sample's
  own stored `FT` text is never rewritten; only a typed read's return value reflects the drop.
- **Test/fixture evidence:**
  `BcftoolsDependencyTest#testReservedFormatFtValueInteriorEmptyCodeBcftoolsPassesThroughSilently`;
  `local-fixtures/failed/reserved-format-ft-interior-empty-code.vcf`.

### Row 22 -- Reserved FORMAT/FT value `;;` (all codes empty)

- **Classification:** Decision-bearing -- a completion of row 21, not a new rule: when every
  semicolon-separated code is empty, `checkFilterLikeValue` normalizes the fully-reduced result to `.` (the
  missing-value sentinel) rather than an empty string, matching the missing-value convention every other
  "reduces to nothing" row in this table already follows, and avoiding `convertProperty`'s own separate,
  context-free "is an empty entry" warning firing as a second, less-specific report of the same defect this
  method's own "empty filter code" warning already covers.
- **Probe shape:** sample column, FORMAT `GT:FT`, value `;;` -- three semicolon-separated codes, all empty.
- **4.2 - 4.5:** identical citations to row 21 -- the FT description text does not distinguish "some codes
  empty" from "all codes empty."
- **bcftools 1.23.1:** passes this through silently, no warning, exit 0, unchanged in output (`0/1:;;`) -- same
  reasoning as row 21.
- **vcf_validator 0.10.2 (4.3):** reports the file valid -- same caveat as row 21.
- **Parse / Public construction, lenient:** warn and drop every code (three separate warnings, one per empty
  code, per `checkFilterLikeValue`'s per-element loop), then normalize the fully-reduced result to `.`. Strict:
  throws on the first empty code, same as row 21.
- **Default write / Validated write:** read-side conversion only, same as row 21.
- **Test/fixture evidence:** `BcftoolsDependencyTest#testReservedFormatFtValueAllCodesEmptyBcftoolsPassesThroughSilently`;
  `local-fixtures/failed/reserved-format-ft-all-codes-empty.vcf`.

### Row 23 -- Generic metadata property `##reference=` (empty value)

- **Classification:** Decision-bearing, but structurally different from every row above it: this is not a
  delimited list at all, so there is no element to drop or substitute -- the *entire* declaration is dropped or
  kept. Two spec rules justify the stricter-than-bcftools choice (bcftools itself does not diverge from silent
  passthrough here): the general zero-length-fields rule, and -- more specifically, and uniquely on-point for
  this row -- 4.3+'s own statement that an *unstructured* meta-information line's value "may not be empty and
  must not start with a `<` character." That second rule covers both halves of what
  `VcfMetadata.Builder.addRawProperty()` checks: `isEmptyMetadataValue` (the empty half) and
  `collidesWithAttributeListGrammar` (the leading-`<` half). It is the same citation the attribute-list-value
  section below uses to distinguish an unstructured line's value from an attribute *within* a structured
  line's `<...>` list.
- **Probe shape:** a generic (non-structured) metadata line, `##reference=`, with nothing after the `=` -- this
  row's reasoning generalizes to any generic `##key=value` property (`##assembly`, `##pedigreeDB`, or an
  unrecognized key).
- **4.2:** `silent` on both rules -- no zero-length-fields statement (identical reasoning to row 1), and no
  "may not be empty" statement for an unstructured meta-information line either.
- **4.3:** `VCFv4.3.tex:39` (general rule) plus, more specifically, `VCFv4.3.tex:109`: "An \emph{unstructured}
  meta-information line consists of a~\emph{key} (denoting the type of meta-information recorded) and
  a~\emph{value} (which may not be empty and must not start with a `\verb|<|' character), separated by an
  `\verb|=|' character".
- **4.4:** `VCFv4.4.tex:39` / `VCFv4.4.tex:109` -- identical wording to the 4.3 citations above.
- **4.5:** `VCFv4.5.tex:37` / `VCFv4.5.tex:107` -- identical wording.
- **bcftools 1.23.1:** silently **preserves** the generic property unchanged in the header (`##reference=`
  round-trips through `bcftools view -h` unmodified), no warning, exit 0 -- unlike every other row in this
  table, bcftools does **not** diverge from silent passthrough here.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 8: Error in meta-data section." (measured
  live against this row's own fixture).
- **Parse / Public construction:** warn and **drop** the entire property (`VcfMetadata.Builder.addRawProperty()`,
  `isEmptyMetadataValue`) under a lenient `VcfContext` -- a deliberate, stricter-than-bcftools choice, the same
  way this library is already stricter than bcftools for an empty ID/FILTER list entry; throws
  `VcfFormatException` under a strict one.
- **Validated write (`VcfWriter.Builder.validateBeforeWrite()`):** warns on this same condition but does
  **not** drop the entry -- unlike the parse-time path, the writer must not silently remove content the caller
  is already holding a reference to.
- **Default write:** emits verbatim whatever content survived construction (nothing left to normalize if
  construction already dropped it).
- **Test/fixture evidence:** `BcftoolsDependencyTest#testGenericPropertyEmptyValueBcftoolsPreservesButWeAreStricter`
  (pre-existing); `local-fixtures/failed/generic-property-empty-value.vcf`.

## A related, but distinct, fix: duplicate FORMAT keys

`VcfPosition.checkFormat` also rejects a FORMAT declaration with a duplicate key (e.g. `GT:DP:DP`), throwing rather
than warning. This is not an empty-field case — VCFv4.3 and VCFv4.4 both state outright that "duplicate keys are not
allowed" for FORMAT, and without this check a duplicate key caused silent data loss (`VcfSample.getProperty` returns
only the first occurrence's value, discarding the rest) rather than a merely-malformed-looking value. An empty
FORMAT key (see above) is excluded from this duplicate check, since it's already handled, and warned about,
separately — two empty keys are not "duplicates" in the sense the spec means.

This exclusion is only about `checkFormat`'s rejection check, not an end-to-end guarantee: `VcfSample`'s own
property map still collapses same-named keys by key string, including two empty-string keys, so a FORMAT
declaration like `GT::` that reaches a real `VcfSample` (excluded from rejection for the reason above) still loses
every occurrence after the first when that sample is read or written, with a warning (see
`VcfSample.properties()`). Two empty keys are not "duplicates" for rejection purposes, but they are still a
data-loss case in practice.

## A different kind of "empty": a metadata attribute-list value

Every row above is about a VCF **data line** field (`ID`/`FILTER`/`ALT`/`INFO`/a sample value). A `##KEY=<...>`
metadata declaration's own attribute list has a distinct set of empty shapes, handled by
`InternalUtils.tokenizeAttributeList`/`InternalUtils.parseAttribute` (sharing one per-attribute grammar via
`parseOneAttribute`; see `STRUCTURAL_DELIMITER_HANDLING.md` for the surrounding delimiter rules) rather than by
any of the record-line checks above. Most of these rows are not bcftools/vcf_validator comparison rows the way
the table above is -- they document this library's own tokenizer, verified directly against
`AttributeListTokenizerTest` -- but two (AL-6, AL-7 below) are, exactly like the main table; see the
classification note below the spec-silence paragraph for which is which.

All four VCF spec versions this library targets or accepts -- VCFv4.2 through VCFv4.5 -- are silent on every row
below: none states whether an individual attribute's value inside a structured line's `<...>` list may be empty,
and none mentions a doubled or trailing comma between attributes. This was confirmed by reading each version's
Meta-information-lines subsection directly (`VCFv4.2.tex:56`; `VCFv4.3.tex:105`/`VCFv4.4.tex:105`/`VCFv4.5.tex:103`,
which share near-identical wording from there on), not inferred from the target spec's own "Zero length fields are
not allowed, a dot (`.`) must be used instead" sentence (`VCFv4.3.tex:39`, identically worded at `VCFv4.4.tex:39`
and `VCFv4.5.tex:37`): that sentence sits in the general format overview immediately after describing data lines'
*tab*-separated fields ("text fields separated by tabs"), before the later, separate description of a structured
line's own *comma*-separated attribute-list grammar ("the value is itself a comma-separated list of key=value
pairs, enclosed within `<` and `>` characters" -- `VCFv4.3.tex:117`/`VCFv4.4.tex:117`/`VCFv4.5.tex:115`), so it
governs data-line fields, not this table's attribute values. VCFv4.3/4.4/4.5 do each separately state that an
*unstructured* meta-information line's own value "may not be empty" (`VCFv4.3.tex:109`/`VCFv4.4.tex:109`/
`VCFv4.5.tex:107`) -- but that rule is about a bare `##key=value` line's value (e.g. `##fileDate=`), not an
attribute *within* a structured line's `<...>` list, and VCFv4.2 has no such statement even for that case. `##META`'s
`Values=[...]` bracket-list syntax (the first row below) is silent a fortiori: every version introduces it only
through worked examples (`VCFv4.3.tex:273-276`, `VCFv4.4.tex:287-290`, `VCFv4.5.tex:336-339`), with no accompanying
grammar prose at all.

**Classification.** `BEHAVIOR_TABLE_STANDARD.md`'s descriptive-only exemption applies to
five of the seven rows below (AL-1 through AL-5): each records a pure code-reading fact about
`InternalUtils.parseOneAttribute`/`tokenizeAttributeList`, and the Decision each rests on -- that a
zero-length-but-well-formed attribute value is kept, not substituted or dropped -- would be unchanged if every
tool row in this section flipped. Per the standard's own exemption text, the four per-version spec columns
collapse into the one silence statement already given above (VCFv4.2 through VCFv4.5 all `silent`, per the
citations already given), and no new `bcftoolsDependencyTest` case or committed probe script is added for
these five rows: none is needed, since none of their claims rests on measured tool behavior in the first
place. AL-2 is the one exception among the five that *does* carry a live bcftools measurement (recorded in its
own fixture below) -- included for completeness, but the classification is still descriptive-only, since the
scope decision it measures (treat `[` as ordinary content outside `##META`'s `Values`) was already made as a
documented maintainer judgment call in `VCF_SPEC_AMBIGUITIES.md`'s "General bracket-list quoting beyond
`##META`'s `Values`" entry, independent of this specific empty-value shape.

The remaining two rows (AL-6, AL-7) are **decision-bearing**, not descriptive-only: a tool measurement does
exist for both shapes. `VCF_SPEC_AMBIGUITIES.md`'s
"Malformed attribute inside a metadata declaration's `<...>` list" entry already weighs a measured bcftools
behavior for this exact shape -- bcftools discards the *entire* declaration rather than just the empty
attribute -- and deliberately chooses not to replicate it ("side with bcftools over the fixtures for this
sub-case: warn and drop just the one malformed attribute... finer-grained than bcftools"). That is a real
Decision resting on measured tool behavior, the same shape as the main Reference table above, so each gets its
own tool-version-pinned evidence below rather than collapsing into the prose exemption.

**Control set:** AL-1, AL-3, AL-4, and AL-5 jointly serve the role `BEHAVIOR_TABLE_STANDARD.md`'s "Include a
control row" describes -- each is a legitimate, well-formed value (not a malformed shape) that must and does
parse/round-trip clean with no warning, distinguishing "the tokenizer works" from "every empty-looking value
gets flagged." No single dedicated control fixture is added for this small a table; the four rows already
demonstrate it collectively.

### Index

| Row | Form | Example | Classification | Result | Warning |
|---|---|---|---|---|---|
| [AL-1](#al-1----empty-bracket-value-metas-values-attribute-only) | Empty bracket value (`##META`'s `Values` attribute only) | `##META=<ID=A,Values=[]>` | Descriptive-only | kept as `[]` | none |
| [AL-2](#al-2----empty-bracket-looking-value-on-any-other-attribute) | Empty bracket-*looking* value on any other attribute | `x=[]` | Descriptive-only | kept as the literal string `[]` | `Metadata attribute "x" has an unquoted value "[]" containing a character bcftools treats as structural; preserving it` |
| [AL-3](#al-3----empty-quoted-value) | Empty quoted value | `Description=""` | Descriptive-only | kept as `""` | none |
| [AL-4](#al-4----empty-unquoted-value) | Empty unquoted value | `x=` | Descriptive-only | kept as `""` | none |
| [AL-5](#al-5----empty-attribute-list) | Empty attribute list | `##INFO=<>` | Descriptive-only | zero attributes | none |
| [AL-6](#al-6----doubled-separator-interior-empty-attribute) | Zero-length attribute from a **doubled** separator | `ID=c1,,length=999` | Decision-bearing | empty interior attribute dropped; `ID`/`length` unaffected | `Metadata attribute "" has no '='; dropping it` |
| [AL-7](#al-7----trailing-separator) | Zero-length attribute from a **trailing** separator | `ID=c1,length=999,` | Decision-bearing | empty trailing attribute dropped; `ID`/`length` unaffected | `Metadata attribute list has an empty trailing attribute; dropping it` |

AL-1 through AL-4 are not really "empty-field" cases at all in the sense the rest of this document uses the
term: each is a *value* that happens to be zero characters long but is still a **complete, well-formed**
attribute (a name followed by `=` followed by nothing, or by an empty quoted/bracketed pair) -- there is no
missing-value sentinel to substitute, because the value legitimately *is* empty, the same way `Description=""`
already means "the description is the empty string," not "no description was given." Only AL-6 and AL-7 are
genuinely analogous to the rest of this document: a delimiter produced a gap with no value on either side
of it, and that gap is dropped (matching the position-independent-list strategy above, since an attribute
list has no positional index for anything else to reference into) rather than substituted.

### AL-1 -- Empty bracket value (`##META`'s `Values` attribute only)

- **Classification:** Descriptive-only -- `[...]` is the only attribute `[...]` is a quoting form for at all,
  per `VCF_SPEC_AMBIGUITIES.md`'s "General bracket-list quoting beyond `##META`'s `Values`" entry; that an
  *empty* `[]` is simply the shortest instance of an already-decided form is self-evident from the code, not
  something a tool measurement could change.
- **Probe shape:** a `##META` declaration, `Values` attribute, value exactly `[]` -- an immediately-closing
  bracket pair with zero interior bytes, distinct from a `Values` list containing a real empty *element*
  (e.g. `[a,,b]`), which this table does not cover.
- **4.2 - 4.5:** `silent` (see the silence analysis above the Index).
- **This library:** kept as `[]`, no warning -- `InternalUtils.parseOneAttribute`'s `isMetaValues` branch finds
  the closing `]` immediately (`s.indexOf(']', valueStart + 1)` returns `valueStart + 1` for this input), so
  the "trailing content after closing delimiter" warning (only reached when `end > close + 1`) never fires.
- **Test evidence:** `AttributeListTokenizerTest#metaValuesBracketsHaveNoEscapeMechanism` (asserts
  `tokenize("ID=c1,Values=[],length=999", "META")` parses `Values` to `"[]"`).
- **Evidence gap:** that call is not followed by its own `warnings()`
  assertion, unlike several sibling assertions earlier in the same test -- the "no warning" half of this row's
  claim is confirmed only by the source trace above, not by a dedicated assertion.

### AL-2 -- Empty bracket-looking value on any other attribute

- **Classification:** Descriptive-only -- the underlying scope decision (protect commas inside `[...]` only
  for `##META`'s `Values`; treat `[` as an ordinary byte everywhere else) is a documented maintainer judgment
  call already made in `VCF_SPEC_AMBIGUITIES.md`'s "General bracket-list quoting beyond `##META`'s `Values`"
  entry on a "genuine tie," explicitly declining to follow bcftools' own broader generalization. This row's
  outcome does not turn on the bcftools measurement below; the measurement is recorded because it happens to
  exist (Tier 3 fixture evidence), not because the Decision rests on it.
- **Probe shape:** a `##contig` declaration, a non-`##META`, non-`Values` attribute (`x`) whose value is
  exactly `[]`, followed by a real attribute (`length`) so the parse isn't confused with a last-position edge
  case.
- **4.2 - 4.5:** `silent`.
- **bcftools 1.23.1:** accepts `x=[]` unchanged, no warning, exit 0 (measured live 2026-08-15, per
  `local-fixtures/passed/attribute-bracket-looking-empty-value.vcf`'s own `##Authority=` lines). No
  vcf_validator run is recorded for this row.
- **This library:** kept as the literal string `[]`; warns `Metadata attribute "x" has an unquoted value "[]"
  containing a character bcftools treats as structural; preserving it` (`InternalUtils.parseOneAttribute`'s
  unquoted-literal branch, `first == '['` check).
- **Test/fixture evidence:**
  `AttributeListTokenizerTest#leadingBracketOutsideMetaValuesIsOrdinaryButWarnsLikeAngleBrackets` (asserts the
  identical code path for the non-empty case `x=[a]`; no dedicated assertion uses an *empty* `[]` value, though
  the branch it enters, `first == '['`, does not distinguish empty from non-empty content);
  `local-fixtures/passed/attribute-bracket-looking-empty-value.vcf`.

### AL-3 -- Empty quoted value

- **Classification:** Descriptive-only -- a pure code fact, no tool comparison involved.
- **Probe shape:** a quoted attribute value (`Description=""`) whose content between the two `"` delimiters is
  zero bytes.
- **4.2 - 4.5:** `silent`.
- **This library:** kept as `""`, no warning -- `InternalUtils.parseOneAttribute`'s quoted branch finds the
  closing `"` immediately, the same "no trailing content" reasoning as AL-1.
- **Test evidence:** `InternalUtilsTest#testExtractPropertiesFromLineDoesNotWarnOnEmptyQuotedValue` (asserts
  `ID=X,Description=""` produces no warning, verified via a real logging-appender capture rather than by
  omission -- no evidence gap for this row).

### AL-4 -- Empty unquoted value

- **Classification:** Descriptive-only.
- **Probe shape:** an unquoted attribute (`x=`) with nothing at all between `=` and the next `,`/end-of-list.
- **4.2 - 4.5:** `silent`.
- **This library:** kept as `""` (not normalized to `.` -- unlike a record-line field, an attribute-list value
  has no positional-index hazard to protect); no warning -- `InternalUtils.parseOneAttribute`'s unquoted-literal
  branch is entered with `first != '['`, so neither warning branch in that method is reached.
- **Test evidence:** `AttributeListTokenizerTest#degenerateAttributesAreDroppedIndividually` (asserts
  `tokenize("ID=c1,x=,length=999")` parses `x` to `""`).
- **Evidence gap:** same shape as AL-1 -- no dedicated `warnings()`
  assertion follows this specific call in the test; "no warning" is confirmed only by the source trace above.

### AL-5 -- Empty attribute list

- **Classification:** Descriptive-only.
- **Probe shape:** a structured declaration (`##INFO=<>`) whose entire `<...>` inner text is the empty string.
- **4.2 - 4.5:** `silent`.
- **This library:** zero attributes, no warning -- `InternalUtils.tokenizeAttributeList` returns immediately
  when `innerText.length() == 0`, before the trailing-separator check even runs, so an empty list can never
  itself be flagged as a "trailing empty attribute."
- **Test evidence:** `AttributeListTokenizerTest#degenerateAttributesAreDroppedIndividually` (asserts
  `tokenize("")` returns `emptyIterable()`).
- **Evidence gap:** same shape as AL-1/AL-4 -- no dedicated `warnings()`
  assertion follows this call either.

### AL-6 -- Doubled separator (interior empty attribute)

- **Classification:** Decision-bearing -- unlike AL-1 through AL-5, this row records a considered divergence
  from measured bcftools/vcf_validator behavior rather than a self-contained code fact.
  `VCF_SPEC_AMBIGUITIES.md`'s "Malformed attribute inside a metadata declaration's `<...>` list" entry weighs
  bcftools' own "drop the whole declaration" behavior and deliberately declines to replicate it, choosing
  instead to drop only the one empty attribute and keep the rest of the declaration.
  This row's tool evidence is recorded below rather than deferred to the descriptive-only prose
  exemption.
- **Probe shape:** a `##contig` declaration with two well-formed attributes and one empty interior attribute
  produced by a doubled `,` (`ID=chr1,,length=1000000`).
- **4.2 - 4.5:** `silent` -- this reuses the descriptive-only exemption's collapsed one-line notation purely
  for typesetting economy, since all four versions were independently confirmed silent (see the Classification
  bullet above); it does not make this row descriptive-only, and every other rule (probe shape, control row,
  tool-defect handling) still applies as a decision-bearing row.
- **bcftools 1.23.1:** cannot parse the declaration at all and discards the entire `##contig` line --
  `[E::bcf_hdr_parse_line] Could not parse the header line: "##contig=<ID=chr1,,length=1000000>"`, exit 0
  (the file itself stays readable) -- measured live 2026-08-15, matching the pre-existing, `##INFO`-shaped
  `BcftoolsDependencyTest#testMalformedMetadataAttributeBcftoolsDropsDeclarationAndContinues`.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 8: Error in contig metadata." (measured
  live 2026-08-15, per the fixture's own `##Authority=` lines).
- **This library:** drops only the empty attribute, keeps `ID`/`length` -- warns `Metadata attribute "" has no
  '='; dropping it` (`InternalUtils.parseOneAttribute`).
- **Test/fixture evidence:**
  `BcftoolsDependencyTest#testMalformedMetadataAttributeBcftoolsDropsDeclarationAndContinues`
  (`##INFO`-shaped);
  `AttributeListTokenizerTest#degenerateAttributesAreDroppedIndividually` (`tokenize("ID=c1,,length=999")`);
  `local-fixtures/failed/attribute-doubled-comma-empty-attribute.vcf` (`##contig`-shaped, recording both
  tool verdicts above). This row has no `bcftoolsDependencyTest` case of its own: the case named above already
  exercises this exact bcftools behavior on a different declaration type.

### AL-7 -- Trailing separator

- **Classification:** Decision-bearing -- identical reasoning to AL-6, applied to a trailing rather than
  interior empty attribute.
- **Probe shape:** a `##contig` declaration with two well-formed attributes followed by a trailing `,` with
  nothing after it (`ID=chr1,length=1000000,`).
- **4.2 - 4.5:** `silent` -- as with AL-6 above, this reuses the descriptive-only exemption's collapsed
  one-line notation purely for typesetting economy, not because this row is descriptive-only (it remains
  decision-bearing per the Classification bullet above).
- **bcftools 1.23.1:** cannot parse the declaration at all and discards the entire `##contig` line --
  `[E::bcf_hdr_parse_line] Could not parse the header line: "##contig=<ID=chr1,length=1000000,>"`, exit 0
  -- measured live 2026-08-15; shows no interior/trailing asymmetry the way the main Reference table's FILTER
  rows 3/4 do.
- **vcf_validator 0.10.2 (4.3):** reports the file invalid -- "Line 8: Error in contig metadata." (measured
  live 2026-08-15, same message text as AL-6, per the fixture's own `##Authority=` lines).
- **This library:** drops only the trailing empty attribute, keeps `ID`/`length` -- warns `Metadata attribute
  list has an empty trailing attribute; dropping it` (`InternalUtils.tokenizeAttributeList`).
- **Test/fixture evidence:** `AttributeListTokenizerTest#degenerateAttributesAreDroppedIndividually`
  (`tokenize("ID=c1,length=999,")`); `local-fixtures/failed/attribute-trailing-comma-empty-attribute.vcf`.
  No `bcftoolsDependencyTest` case covers this row specifically --
  unlike AL-6, no bcftools test exercises this trailing shape; the bcftools/vcf_validator evidence
  above rests solely on the fixture's own live-measured `##Authority=` lines (Tier 3), not on an executable
  Tier 2 case. This is a real, narrower evidence tier than AL-6's, worth stating rather than implying parity.

## Where this is implemented

- `InternalUtils.dropEmptyEntries` / `InternalUtils.fillEmptyEntriesWithDot` — the two shared normalization
  strategies, used by `VcfPosition`'s own constructor-time checks and `validate()`.
- `VcfWriter.addListOrElse()` (ID/ALT/FILTER) and `addSampleConditionally()` (a sample's FORMAT sub-field
  value) — the default write path's own equivalent of the same two strategies. `addListOrElse()`'s guard is
  reached by `VcfContext.newPositionCopyingTrustedFields`, which skips `checkIds`/`checkAltBases`/`checkFilters`
  outright, and `addSampleConditionally()`'s is reached by ordinary construction of a legitimately-empty (not
  structurally invalid) sample value -- both are real production fast paths (`VcfParser`'s per-record FORMAT
  cache, `VcfPosition.Builder.build()`), not test-only bypasses (see
  `VcfWriter.java`'s own comments on `addListOrElse()` and `addFormatConditionally()`).
  `VcfWriterTest`'s `testDefaultWriteDropsEmptyIdEntry`/`testDefaultWriteDropsAllEmptyIdEntriesRendersMissingValue`/
  `testDefaultWriteDropsEmptyFilterEntry`/`testDefaultWriteSubstitutesEmptyAltEntryWithDot` reach
  `addListOrElse()`'s branch via the package-private `newPositionCopyingTrustedFields` factory -- reachable in
  that test only because it is in the same package as `VcfPosition`/`VcfContext`, not because the branch itself
  is otherwise unreachable in production. An empty entry reaches `writeLine()` without
  `position.validate()`/`sample.validate()` ever having run, since the default path skips those for
  performance (see `VALIDATION_PHILOSOPHY.md`). `addListOrElse()` folds the drop/substitute
  decision into its own single pass over the list rather than calling `dropEmptyEntries`/`fillEmptyEntriesWithDot`
  first, to avoid scanning the list twice on this hot path -- it duplicates, rather than reuses, the same
  per-field decision table those two methods implement. **If that table ever changes, update both places.**
  `addSampleConditionally()`'s empty-value substitution is a simpler, unconditional "always substitute `.`"
  rule (not a per-field table), independently duplicated a third time by `VcfSample.validate()` below --
  update all three if a sample value's canonicalization rule ever needs to become field-specific too.
- `VcfPosition.checkIds` / `checkFilters` — drop empty entries (ID, FILTER).
- `VcfPosition.checkAltBases` — substitutes `.` for an empty entry (position-dependent, like the sample/INFO
  values below); a `.` combined with a real allele is not rejected.
- `VcfPosition.checkFormat` — warns and keeps an empty FORMAT key as-is; separately, throws on a duplicate
  (non-empty) FORMAT key.
- `VcfPosition.info()` — warns and drops an empty top-level entry, and (via its own separate, pre-existing
  inline check, before a key ever reaches a built multimap) an empty key; warns and substitutes `.` for an
  empty value. The eager (`ListMultimap`) constructor and `validate()` share a second, equivalent empty-key
  check in `checkInfoEntries` (the same method both call). `checkInfoEntries` does **not** have an equivalent
  empty-*value* check: an attempt to add one was reverted after it broke the `Flag`-value round-trip
  (`checkInfoEntries` has no access to a key's declared `##INFO` `Type`, so it can't tell a genuine Flag's
  empty-string "present" encoding from a real empty value the way `VcfWriter.addInfoOrDot()` below can). The
  eager constructor's/`validate()`'s empty-*value* gap is instead addressed one layer down, at the
  reserved-property typed-conversion step -- see `InternalUtils.convertProperty` below.
- `VcfParser`'s per-sample value parsing — warns and substitutes `.` for an empty sample value.
- `InternalUtils.convertProperty(Class<?>, String, boolean, VcfWarnings, VcfWarnings)` (the read-path
  overload, used by reserved and non-reserved typed reads alike) — for a list-typed value, substitutes `.`
  (`null`) for an empty entry in a comma-separated value; for a scalar (non-list) value, likewise substitutes
  `.` (`null`) for an empty (but non-`"."`) value -- **except** `Boolean.class` (a `Flag`-typed reserved
  property), whose empty string is a distinct, pre-existing "present" encoding (see `VcfPosition.info()`'s
  bare-flag case above), not "missing"; that case is deliberately left alone. Both branches are
  `isLenient()`-aware: warns under a lenient `VcfContext`, throws `VcfFormatException` under a strict one,
  matching `VcfPosition`'s own construction-time checks' treatment of the analogous empty-entry condition.
  `InternalUtils.convertNonEmptyProperty` is the write-path counterpart `VcfWriter` calls instead -- it has no
  empty-value branch at all (every `VcfWriter` call site already substitutes or skips an empty value before
  reaching it), so an empty value can never reach this class from the writer, and there is no Writer
  Validation Policy always-warn case to reconcile here the way there is for the five reserved-key content
  checks. Separately, warns and drops an empty semicolon-separated code in a reserved `FORMAT/FT` value under a
  lenient `VcfContext` (`InternalUtils.checkReservedFormatConstraints`, delegating to `checkFilterLikeValue`),
  throwing `VcfFormatException` under a strict one. `VcfPosition.getInfo(ReservedInfoProperty)` reaches this
  branch for a scalar reserved key (e.g. `DP`, `AA`) via the eager constructor.
- `VcfSample.getGenotype` — warns and substitutes `.` for an empty GT allele.
- `VcfSample.validate()` — warns and substitutes `.` for a sample property holding an empty value. An empty
  (not structurally invalid) property value is not rejected at construction time, so `VcfSampleTest`/
  `VcfWriterTest` reach this branch via ordinary direct construction (e.g. `VcfContext.newSample` given a map
  or parallel lists containing an empty value). Consistent with `VcfWriter`'s own warn-only treatment (in
  `validateBeforeWrite()` mode) of an empty comma-separated FORMAT element.
- `VcfWriter.addInfoOrDot()` — warns and substitutes `.` for an INFO key's value list holding a single empty
  string, but only when the key is declared with a `Type` other than `Flag`. An empty INFO *value* (as
  opposed to an empty key) is not rejected or altered by any construction-time check, so `VcfWriterTest`
  reaches this branch via ordinary direct construction (a `ListMultimap` built with the empty value, passed
  straight to `VcfContext.newPosition`). A key declared `Type=Flag`, or one with no `##INFO` declaration at all
  (the "INFO flag (no `=`)" row above), still writes bare: an empty value there is either a genuine flag or
  indistinguishable from one, since `VcfPosition.info()` produces the identical single-empty-string
  representation for both. Unlike every other row
  in this table, this runs in **both** write modes, not just `validateBeforeWrite()` — the ambiguity this resolves
  (an empty value that looks identical to a Flag) would otherwise silently corrupt the field's
  semantic type on the default write path too, not just under full diagnostics. Separately, and unconditionally
  (regardless of declared `Type`, since a multi-value list can never be mistaken for a `Flag`), it also
  substitutes `.` for an empty entry at any position in a multi-value list (e.g. `["1", "", "2"]`), matching the
  position-dependent-list rule above.
- `VcfMetadata.Builder.addRawProperty()` -- the single entry point for a generic `##key=value` property,
  including `##assembly` and `##pedigreeDB` (there are no separate `VcfMetadata.addAssembly()`/
  `addPedigreeDatabase()` instance methods). Warns and drops a generic metadata property with an empty
  value (`isEmptyMetadataValue`), or one wrapped in angle
  brackets whose inner content is empty or has no `=` (`collidesWithAttributeListGrammar`, e.g. `<>` or
  `<http://foo.com>`) under a lenient `VcfContext`; under a strict one, both conditions throw
  `VcfFormatException` instead -- see `VALIDATION_PHILOSOPHY.md`. The two conditions
  differ in `VcfWriter.Builder.validateBeforeWrite()`, though: the plain-empty-value case only warns, without
  dropping, since bcftools tolerates a plain empty value and there is no round-trip hazard; the
  bracket-collision case instead **throws** `VcfFormatException`, since bcftools cannot parse a value shaped
  like this back in at all -- writing it out would produce output this library's own bcftools-informed
  decisions treat as invalid, so `validateBeforeWrite()` rejects it as structurally unparseable rather than
  merely warning. See `VCF_SPEC_AMBIGUITIES.md`'s "`##pedigreeDB`/`##assembly` bracket-wrapped values colliding
  with attribute-list grammar" entry for the bcftools evidence behind the bracket-collision half of this rule.
- `InternalUtils.tokenizeAttributeList`/`InternalUtils.parseAttribute` (via the shared `parseOneAttribute`) --
  the "A different kind of 'empty'" section above's own implementation: an empty bracket/quoted/unquoted value
  is kept as-is (it is a complete, well-formed value, not a gap), while a zero-length attribute produced by a
  doubled or trailing separator is dropped with a warning, distinguishing the two wordings ("has no '='" for
  an interior gap, "empty trailing attribute" for a trailing one).

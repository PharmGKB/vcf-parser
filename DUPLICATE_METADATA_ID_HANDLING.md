# Duplicate metadata declaration ID handling

This document describes `VcfMetadata.Builder`'s/`VcfMetadata`'s behavior under a lenient `VcfContext`
(`VcfContext.lenient(fileFormat, warnings)`) -- the mode `VcfParser` always uses internally, so a parsed
file's behavior is exactly as described below. Under a strict context (`VcfContext.strict(fileFormat)`) each
of these conditions throws `VcfFormatException` immediately instead; see `VALIDATION_PHILOSOPHY.md`.

VCF metadata declarations (`##contig`, `##INFO`, `##FORMAT`, `##FILTER`, `##ALT`, `##SAMPLE`, `##META`) are each
keyed by an `ID` attribute. VCFv4.3.tex line 121 states that a structured line's `ID` "must be unique within
their type," and VCFv4.4.tex line 121 and VCFv4.5.tex line 119 carry the identical sentence; VCFv4.2 has no such
requirement at all. But no version — 4.2 through 4.5 — states what should happen when a file violates that
uniqueness requirement: there is no throw/warn/drop/accept instruction attached to it. That is the specific
silence this document resolves via bcftools and vcf_validator behavior.

## Why this warns instead of throwing

Rejecting a file outright for a repeated declaration ID — as `vcf-parser` once did, via `VcfFormatException:
Duplicate ID ... for ...` — would be stricter than either reference tool this project consults when spec text
is silent (see `AGENTS.md`'s decision hierarchy):

- **vcf_validator** (the EBI reference validator; the tool [hts-specs](https://github.com/samtools/hts-specs) uses
  to classify its own `passed/`/`failed/` conformance fixtures) reports a file with a repeated declaration ID as
  **valid**, for every one of the seven declaration types (verified directly for `##META`: vcf_validator 0.10.2
  against a `VCFv4.3` fixture declaring `##META=<ID=Assay,...>` twice with different `Values`).
- **bcftools**, the reference implementation most of the ecosystem treats as canonical, keeps the **first**
  declaration and silently drops each duplicate — no warning, no error — again for every one of the seven types
  (verified directly for `##META`: bcftools 1.23.1).

> **Measurement hazard: never probe this with `##FILTER=<ID=PASS,...>`.** htslib injects
> `##FILTER=<ID=PASS,Description="All filters passed">` into the header *before* parsing the file
> (`~/Dev/htslib` `vcf.c`, in `bcf_hdr_parse`), so a file's own `PASS` declaration is always already a
> duplicate and is always the one dropped — even when the file declares it exactly once. Measured: a file
> with no `##FILTER` at all still shows that line in `bcftools view -h`, and a file declaring
> `##FILTER=<ID=PASS,Description="MY OWN TEXT">` shows htslib's wording, not its own. A first-wins result
> obtained that way would be an artifact of the injection rather than evidence about duplicate handling.
> The first-wins finding above is **not** affected: it was re-confirmed on a non-`PASS` ID
> (`##FILTER=<ID=q10,...>` declared twice keeps the first), which isolates the behaviour from the
> injection. See `AGENTS.md`'s "A probe is not a grammar" for why the carrier you choose is part of the
> measurement.

hts-specs' own `test/vcf/{4.1,4.2}/passed/passed_meta_contig.vcf` fixture declares `##contig=<ID=1>` and then
`##contig=<ID=1,length=123456>`, and is expected to parse successfully — direct confirmation that this is not a
structural violation. Rejecting the file outright would make `vcf-parser` reject input that hts-specs' own
conformance suite, its own reference validator, and the ecosystem's reference implementation all treat as
acceptable, so `vcf-parser` matches that practical tolerance: it logs a warning and keeps the first declaration,
rather than throwing `VcfFormatException`.

For the seven `Map`-backed types above, this is construction-time-only: once stored, a `Map<String, ...>` can't
hold two entries under one key, so a "duplicate" can only be encountered while *adding* a declaration, which
now happens in exactly one place -- `VcfMetadata.Builder` (used directly, or by `VcfParser` at parse time, or
via `VcfMetadata.toBuilder()`). A built `VcfMetadata` is immutable, with no instance `addXxx` methods and no
mutable collection getters, so there is no second entry point for this rule to also cover.

`##PEDIGREE` is the one shape that still needs a second, write-time check: it is `List`-backed, so unlike the
keyed types nothing about the storage itself prevents two same-`ID` entries from coexisting. Its
`VcfWriter.validateMetadata()` check is retained (warn-only) rather than dropped along with the mutation
routes -- it also covers a list assembled outside `Builder.addPedigree`, and the writer keeps its own
defensive validation pass self-contained rather than depending on how its input was built.

## Reference table

Per `BEHAVIOR_TABLE_STANDARD.md`. **Probe shape:** one hand-built fixture per declaration type, each
declaring the exact same `ID` twice with a single differing non-`ID` attribute value (e.g.
`##INFO=<ID=DP,...,Description="depth v1">` immediately followed by an otherwise-identical declaration whose
`Description` reads `"depth v2"`), the file's only two declarations of that type, first-then-second in file
order. `##FILTER`'s probe deliberately uses `ID=q10`, never `ID=PASS` -- see the "Measurement hazard" callout
above. Every one of the seven `local-fixtures` probe fixtures declares `##fileformat=VCFv4.3`. The
`bcftoolsDependencyTest` cases that re-measure the bcftools column are a *second*, separate evidence source --
inline probes built in that test rather than the committed fixtures -- and six of those seven declare
`##fileformat=VCFv4.2` instead (only `testDuplicateMetaIdBcftoolsKeepsFirst` declares `VCFv4.3`, since `##META`
has no pre-4.3 form). That difference is immaterial to the bcftools column (`bcf_hdr_parse_line` never consults
`##fileformat`), but it does mean the `vcf_validator 0.10.2 (4.3)` pin below describes the fixture corpus, which
is what vcf_validator was actually run against. **Discriminating observable:** which attribute value
(`"...v1"` vs. `"...v2"`) each tool's own header echo or warning names, not merely that the file as a
whole re-parses -- this is what distinguishes "kept the first, dropped the second" from "merged/renamed the
second into the first," which byte count alone cannot.

**Probe script:** `scripts/probe-duplicate-metadata-id-handling.sh` re-runs both tools against these seven
fixtures (plus every other probe in this document) and checks each vcf_validator verdict against the value
recorded here, per `BEHAVIOR_TABLE_STANDARD.md`'s Tier 2 evidence-preservation requirement -- re-run it after
a vcf_validator or bcftools upgrade rather than re-deriving this table from memory.

Column notes, per the standard: **bcftools** gets one unqualified column, since its header tokenizer never
consults `##fileformat` (`bcf_hdr_parse_line`, htslib 1.23.1 `vcf.c`). **vcf_validator** is pinned to the
grammar actually exercised by every probe fixture (`VCFv4.3`) -- see row 7's own note for why `##META` alone
needs a further, non-tabular caveat there. **Parse** and **Public construction** are collapsed into one
column: both reach the identical `VcfMetadata.Builder.addXxx()` -> `warnIfDuplicate()` call for all seven
types (verified by reading `VcfMetadata.java`'s current `addXxx`/`addXxxTrusted` implementations -- the
`validateContent` boolean the two paths differ on gates only pre-dedup *content* validation, not the dedup
call itself), except `##META` below VCFv4.3, footnoted at row 7. **Validated write** and **Default write** are
both omitted as table columns because they are a constant `N/A` for every row, for the identical reason: once
a `VcfMetadata` is built, each of the seven types is stored in a `Map<String, ...>`, which cannot hold two
entries under one key -- so the condition this table describes (two declarations sharing an `ID`) cannot exist
post-construction, for any of the seven types, and there is nothing for either write path to detect.

**Case convention, used consistently in every table in this document:** uppercase `N/A` (always footnoted)
marks a cell where the declaration type itself doesn't exist yet at that spec version (row 7's 4.2 cell below:
`##META` isn't introduced until VCFv4.3); lowercase `n/a` marks a control row's cells, where the column's own
subject -- a duplicate, or a missing/empty `ID` -- simply doesn't apply to that row at all. The two forms
answer different questions (does the *type* exist vs. does the *condition* apply) and are not interchangeable,
despite looking similar at a glance.

| # | Declaration type | 4.2 | 4.3 | 4.4 | 4.5 | bcftools 1.23.1 | vcf_validator 0.10.2 (4.3) | Parse / Public construction | Fixture / `bcftoolsDependencyTest` |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `##contig` | silent | "unique within type" [a] | "unique within type" [a] | "unique within type" [a] | keep first, DROP dup, no warning | Valid | warn + keep first | `duplicate-contig-id.vcf`; `testDuplicateContigIdBcftoolsKeepsFirst` |
| 2 | `##INFO` | silent | "unique within type" [a] | "unique within type" [a] | "unique within type" [a] | keep first, DROP dup, no warning | Valid | warn + keep first | `duplicate-info-id.vcf`; `testDuplicateInfoIdBcftoolsKeepsFirst` |
| 3 | `##FORMAT` | silent | "unique within type" [a] | "unique within type" [a] | "unique within type" [a] | keep first, DROP dup, no warning | Valid | warn + keep first | `duplicate-format-id.vcf`; `testDuplicateFormatIdBcftoolsKeepsFirst` |
| 4 | `##FILTER` | silent | "unique within type" [a] | "unique within type" [a] | "unique within type" [a] | keep first, DROP dup, no warning | Valid | warn + keep first | `duplicate-filter-id.vcf`; `testDuplicateFilterIdBcftoolsKeepsFirst` |
| 5 | `##ALT` | silent | "unique within type" [a] | "unique within type" [a] | "unique within type" [a] | keep first, DROP dup, no warning | Valid | warn + keep first | `duplicate-alt-id.vcf`; `testDuplicateAltIdBcftoolsKeepsFirst` |
| 6 | `##SAMPLE` | silent | "unique within type" [a] | "unique within type" [a] | "unique within type" [a] | keep first, DROP dup, no warning | Valid | warn + keep first | `duplicate-sample-id.vcf`; `testDuplicateSampleIdBcftoolsKeepsFirst` |
| 7 | `##META` | N/A [b] | "unique within type" [a][c] | "unique within type" [a][c] | "unique within type" [a][c] | keep first, DROP dup, no warning | Valid [d] | warn + keep first [e] | `duplicate-meta-id.vcf`; `testDuplicateMetaIdBcftoolsKeepsFirst` |
| 8 | **Control** -- single, non-duplicated `##contig` | n/a (not a duplicate case) | n/a | n/a | n/a | kept, no warning | Valid | kept, no warning | see note [f] |

**Notes:**

- **[a]** VCFv4.3.tex line 121, VCFv4.4.tex line 121, VCFv4.5.tex line 119 -- identical text in all three:
  "All structured lines require an ID which must be unique within their type, i.e., within all the
  meta-information lines with the same ``##``*key*`=` prefix." VCFv4.2 has no such sentence at all (0 hits for
  "unique within" in `VCFv4.2.tex`) -- reusing this document's own introduction's reconciled citation rather
  than re-deriving it.
- **[b]** `##META` does not exist as a structured declaration type before VCFv4.3 -- see
  `VALIDATION_REFERENCE.md`'s own entry for the citation and for why this is enforced only at parse time, not
  at construction or write time.
- **[c]** VCFv4.3.tex line 121's "All structured lines" text is unqualified by declaration type, so it grounds
  `##META`'s own uniqueness requirement directly and unconditionally from the version `##META` exists in
  onward -- the identical reasoning this document's "Missing ID is spec-grounded" section already applies to
  the *required*-ID half of this same sentence, applied here to its *unique*-ID half.
- **[d]** Measured under `##fileformat=VCFv4.3` (the only version every fixture in this table declares).
  Reading `~/Dev/vcf-validator`'s source confirms `##META` needs no further per-version split for *this*
  table's purposes, but the mechanism differs by version in a way worth recording: `##META` is excluded from
  the generic-typeid fallback (`meta_typeid`) and given its own dedicated `ID`-bearing grammar (`meta_meta`)
  only from `vcf_v43.ragel` onward (`vcf_v43.ragel:77,138-141`; confirmed generated `inc/vcf/validator_detail_v4*.hpp`
  matches). `vcf_v41.ragel`/`vcf_v42.ragel` line 70's `meta_typeid` exclusion list omits `META` entirely, so a
  `##META=<...>` line under a pre-4.3 grammar instead falls through to the generic, unstructured
  `meta_typeid_generic` production -- which this library never probes, since its own row 7 spec cells are
  already `N/A` below VCFv4.3 (note [b]) and no pre-4.3 `##META` fixture exists to exercise it with.
- **[e]** Holds identically to rows 1-6 from VCFv4.3 onward (verified in `VcfMetadata.java`). Below VCFv4.3 the
  two paths genuinely diverge for `##META` only: **Parse** never reaches `warnIfDuplicate()` at all --
  `VcfParser.parseMetadata`'s `case "META":` routes a pre-4.3 line to `addRawProperty` instead (confirmed in
  `VcfParser.java`), so two duplicate pre-4.3 `##META` lines are both silently kept as two separate entries in
  the generic-property `ListMultimap` (`VcfMetadata.m_properties`), with no dedup and no warning. **Public
  construction** has no such version gate -- `VcfMetadata.Builder.addMeta()` itself never checks the context's
  version -- so calling it directly under a pre-4.3 `VcfContext` still reaches `warnIfDuplicate()` and dedupes.
  This divergence is outside this table's own decision (which only concerns the versions `##META` exists in,
  matching row 7's `N/A` spec cells below VCFv4.3), but `BEHAVIOR_TABLE_STANDARD.md`'s own collapse rule
  requires stating a verified divergence rather than assuming the collapse holds everywhere it wasn't checked.
- **[f]** No dedicated fixture: each of rows 2-7's own fixtures (`duplicate-info-id.vcf`,
  `duplicate-format-id.vcf`, `duplicate-filter-id.vcf`, `duplicate-alt-id.vcf`, `duplicate-sample-id.vcf`,
  `duplicate-meta-id.vcf`) already contains exactly one, non-duplicated `##contig=<ID=chr1,length=1000000>`
  declaration alongside that row's own duplicated pair, confirmed present exactly once in each file. Each of
  those six fixtures' own passing bcftools/vcf_validator/`vcf-parser` runs is therefore already a live control
  instance in the same run as its row's decision-bearing case, distinguishing "only the duplicated declaration
  is affected" from "the whole fixture is rejected."

Contrast with a duplicate `#CHROM` sample-column name, which remains a structural error in `vcf-parser`. This is a
different *kind* of duplication, not merely a stricter policy choice: `#CHROM`'s sample columns are *positional* —
every data line's per-sample values are aligned to them by index, and `VcfMetadata.getSampleIndex(id)` resolves a
name via `m_columns.indexOf(id)`. A duplicate name makes the *second* column's data permanently unreachable by
name (only reachable positionally), silently misdirecting any name-based lookup to the first column's data instead
— a real data-integrity hazard none of the seven declaration types above have, since a declaration merely describes
something; nothing else in the file indexes into it by position.

Both reference tools independently draw this same line. **This table is descriptive-only**, per
`BEHAVIOR_TABLE_STANDARD.md`'s "Descriptive-only tables" section: the Decision above (why `#CHROM`
duplication is structural and a duplicate declaration ID isn't) rests entirely on the positional-indexing
argument in the preceding paragraph, not on any cell below — the table only records that both reference tools
independently corroborate that argument, and would not change the Decision even if a tool's verdict here
differed. Per the exemption this earns: all four spec versions (4.2 through 4.5) are silent on which *kind*
of duplication is structural — see the positional-indexing argument above, which is the actual basis for this
table's rows — so the spec columns are collapsed into that one sentence rather than given a column each. No
new `bcftoolsDependencyTest` case is added for either decision-bearing row below: the duplicate-declaration-ID
row is already covered by the "Reference table" section's own `testDuplicateContigIdBcftoolsKeepsFirst` (and,
identically, by each of that section's other six per-type cases), and the `#CHROM` row already has
`testDuplicateChromSampleColumnNameBcftoolsHardErrors`.

**Probe shape:** the duplicate-declaration-ID row reuses the "Reference table" section's own
`duplicate-contig-id.vcf` fixture (`##contig=<ID=chr1,length=1>` immediately followed by
`##contig=<ID=chr1,length=999>`; no sample columns at all) — cited here rather than duplicated as a second
fixture, per this task's disclosed citation-not-duplication choice. The `#CHROM` row is
`duplicate-chrom-sample-column.vcf`'s own header line, declaring the sample-column list `S1\tS1` (two
identical names, the file's only sample columns) with one data line supplying matching `GT` values for both.
The control row is a hand-built one-off (`##fileformat=VCFv4.3`, one `##FORMAT=<ID=GT,...>`, one `##contig`,
and a `#CHROM` line with a single `S1` sample column plus one matching `GT` data value) — not committed as a
fixture, since it is the ordinary case rather than a duplicate-ID decision, but run directly against both
tools below rather than assumed, and reproducible via `scripts/probe-duplicate-metadata-id-handling.sh`
(which builds this exact payload inline, since it isn't a committed fixture). **Discriminating observable:** whether the file is accepted or rejected
outright, not which value survives — unlike the "Reference table" section's rows, which distinguish "kept the
first" from "merged/renamed," these cases differ on whether parsing succeeds at all.

Column notes, per the standard: **bcftools** gets one unqualified column, same reasoning as the "Reference
table" section above (`bcf_hdr_parse_line` never consults `##fileformat`); **vcf_validator** is pinned to
`(4.3)`, the grammar the two cited fixtures and the hand-built control both declare. No **Parse / Public
construction** column is included here, unlike this document's other three tables: this table is
descriptive-only (see above), and both rows' own Parse/Public-construction behavior is already on record
elsewhere in this document rather than novel to this table -- the duplicate-declaration-ID row is exactly the
"Reference table" section's own row 1 (cited, not duplicated, per the probe shape above), and the `#CHROM` row
is this document's own opening "Contrast with a duplicate `#CHROM` sample-column name" paragraph. Adding a
third copy of either fact as a column here would restate content on record, not establish anything new.

| # | Case | bcftools 1.23.1 | vcf_validator 0.10.2 (4.3) | Fixture / `bcftoolsDependencyTest` |
|---|---|---|---|---|
| 1 | Duplicate `##contig`/`##INFO`/etc. declaration ID | keep first, DROP dup, no warning | Valid | `duplicate-contig-id.vcf` (the "Reference table" section's own fixture, cited here rather than duplicated); `testDuplicateContigIdBcftoolsKeepsFirst` (already exists — no new case needed) |
| 2 | Duplicate `#CHROM` sample-column name | **Hard error — refuses to parse the file at all** | **Invalid** | `duplicate-chrom-sample-column.vcf`; `testDuplicateChromSampleColumnNameBcftoolsHardErrors` (already exists — no new case needed) |
| 3 | **Control** — single, non-duplicated `#CHROM` sample-column list | Accepted (exit 0), no warning | Valid | no dedicated fixture — measured live against a hand-built one-off on 2026-08-16 (see probe shape above); not itself a duplicate-ID decision, so no committed fixture or `bcftoolsDependencyTest` case was added |

(Verified directly: `bcftools view` on a file with two `S1` sample columns emits `[E::bcf_hdr_add_sample_len]
Duplicated sample name 'S1'` and fails to parse; `vcf_validator` reports the file invalid. The control's
single-`S1`-column equivalent: `bcftools view` exits 0 with no warning, and `vcf_validator` reports "the input
file is valid.")

## Missing or empty ID

Unlike a duplicate ID (above), a metadata declaration whose `ID` is **absent, empty, or whitespace-only** is a
different failure mode entirely: it isn't a collision between two declarations, it's a single declaration that
has no ID to dedupe on. At *add* time, `vcf-parser` warns and drops such a declaration in full, for all seven
keyed types (including `##META`, whose required-`ID` rule is unconditional/version-independent -- unlike
`##PEDIGREE` below, whose own `ID` requirement only starts at VCFv4.3) -- never stored, unlike a
duplicate-ID-when-present (which keeps the first and drops the rest). This uniformity is independently
confirmed, not merely asserted from this paragraph's own prose: each of the 14 missing/empty x seven-type
combinations below has its own `local-fixtures/failed/*.vcf` corpus fixture whose `##ExpectParser=warn`
declaration was verified directly against this library -- see the "bcftools/vcf_validator behavior
(live-tested)" section's reference table below, none of which diverges into a throw or a silent accept. This
add-time drop is independently preceded by a first warning of its own, from the declaration object's own
`IdMetadata.init()` at construction (before `addXxx` ever sees it) -- parsing one malformed header line produces
both warnings; see `WONT_FIX.md`'s "Multiple independent checks warn separately about the same underlying
defect" entry's "Missing or blank metadata ID" sub-case. "Never stored" is also specifically an add-time
guarantee, not a lifetime one: an object *already* in storage whose `ID` somehow became blank afterward is not
retroactively removed -- a `Map` can't un-key itself reactively -- `validate()`/`IdMetadata.init()` only warns
on it from then on. Since the metadata layer was frozen there is no public route to that state at all
(`getPropertiesRaw()` is an unmodifiable view and `putPropertyRaw` is package-private); it survives only
through the package-private `BaseMetadata.trustedMutableProperties()` bypass, which is what keeps
`VcfWriter`'s equivalent write-time check tested. See `VALIDATION_REFERENCE.md`'s equivalent bullet.

`##PEDIGREE` is an eighth, differently-shaped case, not covered by the "never stored, for all seven keyed
types" rule above: under VCFv4.3+ (VCFv4.3.tex line 121, "All structured lines require an ID which must be
unique within their type"), both `VcfMetadata.Builder.addPedigree` (construction) and
`VcfWriter.validateMetadata()`'s PEDIGREE loop (`validateBeforeWrite()`) warn (or, construction-side only,
throw under a strict context) on an absent/empty/whitespace-only `ID`, but **keep** the declaration rather
than dropping it. The seven keyed types drop because they're `Map`-backed and a blank `ID` leaves nothing to
key the entry on ("cannot be stored"); `##PEDIGREE` is `List`-backed, so that reason doesn't apply -- there's
no map slot to fail to claim. In both places the missing-ID check is checked *before* the duplicate-ID branch,
and a blank `ID` is deliberately never added to the respective dedupe set (`m_pedigreeIds` at construction,
the write-time `pedigreeIds` local at write time), so two blank-ID `##PEDIGREE` declarations each
independently warn "has no ID" rather than the second colliding with the first as a spurious `Duplicate ID ""`
-- on either route. At construction this also produces only one warning, not the two-warning pattern described
above for the seven keyed types: `PedigreeMetadata` extends `BaseMetadata` directly
(`PedigreeMetadata.java:44`), not `IdMetadata`, so there is no `IdMetadata.init()` precursor check to fire
first. Under VCFv4.2 the missing-`ID` gate never activates on either route, because `##PEDIGREE`'s own grammar
(VCFv4.2.tex line 152) has no `ID` attribute at all -- but "the gate never activates" is not the same as
"nothing happens", and the two blank shapes part company there. A declaration with **no `ID` attribute at
all** is added and kept unconditionally: it reaches neither the missing-`ID` branch (version-gated off) nor
the duplicate-`ID` branch (which is itself guarded on `id != null`). A declaration whose **`ID=` is present
but blank** instead falls straight through to the duplicate-`ID` branch, where the blank value is deduped like
any other shared `ID` -- so two blank-`ID=` `##PEDIGREE` lines under VCFv4.2 warn `Duplicate ID ""` and keep
only the first, rather than each independently warning "has no ID" the way they do at VCFv4.3+. See "The
empty-`ID=` row is version-split" at the end of this document.

### Quoting is not part of the identity

Every missing/blank-`ID` check described above compares the unquoted form before comparing or storing it, per
AGENTS.md's "Unquoting before internal use" policy: `IdMetadata.init()`, the seven keyed types'
`addXxx`/`replaceXxx` blank check and dedupe key, `VcfMetadata.Builder.addPedigree`, and
`VcfWriter.validateMetadata()`'s PEDIGREE loop all read `getPropertyUnquoted(ID)`, never `getPropertyRaw(ID)`.
The typed `IdMetadata(VcfContext, String, String)` constructor reaches the identical outcome by a different
route: it validates a caller-supplied `String id` parameter, not yet a stored property (nothing is put into
the properties map until validation passes), so it calls `InternalUtils.unquote(id)` directly rather than
`getPropertyUnquoted` -- there is no property to read yet. Two consequences follow directly:

- A **quoted** blank `ID` -- either whitespace-only (`##PEDIGREE=<ID="   ",...>` / `##FILTER=<ID="   ",...>`)
  or empty (`ID=""`) -- is detected identically to its unquoted equivalent (`ID=`, `ID=   `), for all eight
  structured declaration types. Before this was fixed, every one of these checks read `getPropertyRaw(ID)`
  instead: a quoted blank `ID` arrived as a non-blank raw string (the quote characters survive trimming) and
  escaped detection entirely for `##PEDIGREE` (whose own blank check has no whitespace fallback), or was
  misdiagnosed as "must not contain whitespace" instead of "is blank" for the seven keyed types (see the retired
  `WONT_FIX.md` entry this section replaces).
- `ID="A"` and `ID=A` are **the same ID** for dedup purposes: quoting is an encoding, not part of the
  identifier. A declaration whose `ID` is quoted now collides with an earlier (or later) declaration of the
  same unquoted `ID`, for all eight types -- the seven keyed types' map storage key is itself the unquoted `ID`
  (so `getInfo()`/`getAlts()`/etc. are reachable by the plain, unquoted key even when the sole declaration used
  a quoted one), and `##PEDIGREE`'s `m_pedigreeIds`/write-time `pedigreeIds` dedupe sets store the unquoted form
  too.

The seven keyed types' own public getters (`getId()`, `FormatMetadata.getNumber()`, `InfoMetadata.getNumber()`,
`ContigMetadata.getAssembly()`/`getTaxonomy()`/`getMd5()`/`getUrl()`) follow the same rule: they return the
*unquoted* value too, since quoting is an encoding and the value is what a consumer asked for -- see
`VALIDATION_PHILOSOPHY.md`'s "a property has a value and an encoding" rule. The dedup consequence is that a
declaration's `getId()` and the map key it is stored under are now *always* the same string, for every one of
the seven keyed types; a caller who wants the original spelling opts in via `getPropertyRaw(ID)`.

### Missing ID is spec-grounded

VCFv4.3.tex line 121 (unchanged in VCFv4.4/VCFv4.5): "All structured lines require an ID which must be unique
within their type, i.e., within all the meta-information lines with the same `##key=` prefix." VCFv4.2 has no
general structured-line rule of this kind, and carries an explicit `ID` requirement for exactly one of the six
pre-4.3 types: `VCFv4.2.tex` line 66, "INFO fields should be described as follows (first four keys are required,
source and version are recommended):", immediately preceding the `##INFO` template at line 69,
`##INFO=<ID=ID,Number=number,Type=type,Description="description",Source="source",Version="version">`, whose
first four keys are `ID`, `Number`, `Type` and `Description`. For the other five pre-4.3 types the argument is
the templates instead: VCFv4.2's own templates show `ID=` unconditionally first, with no optional-field notation
-- consistent with, not contradicted by, VCFv4.3's explicit statement. VCFv4.4/VCFv4.5
additionally give `##ALT` its own explicit restatement: "ALT meta-information lines are structured lines with
require[d] fields of ID and Description..." This applies uniformly to all six pre-4.3 types, regardless of what
bcftools actually does per type (see below) -- per `AGENTS.md`'s decision hierarchy, spec text governs when it
isn't silent. `##META` has no VCFv4.2 form to draw a templates argument from (it is introduced at VCFv4.3), but
line 121's "All structured lines" text is unqualified by declaration type, so it grounds `##META`'s own
required-`ID` rule directly and unconditionally from the version `##META` exists in onward -- unlike `##PEDIGREE`
just below, whose own VCFv4.2 grammar has no `ID` attribute at all, which is why *its* rule is version-gated
instead. The reference table in the "bcftools/vcf_validator behavior (live-tested)" section below reuses this
exact reasoning as its own four per-version spec columns (footnotes [a]/[b]/[e]/[f] there), rather than
re-deriving it a second time.

### bcftools/vcf_validator behavior (live-tested)

Per `BEHAVIOR_TABLE_STANDARD.md`. **Probe shape:** one hand-built fixture per declaration type per condition (14
total: 7 types x {missing, empty}), each declaring a single instance of that type with the `ID` attribute either
wholly absent or present-but-empty (`ID=`), immediately followed by a well-formed `##contig=<ID=chr1,length=
1000000>` line -- except the two `##contig` rows themselves, where the declaration under test is deliberately the
file's *only* `##contig` line (adding a second, well-formed one would defeat the point; confirmed live that
bcftools does not require a resolvable contig for `##contig`'s own missing-ID case). Every one of those 14
`local-fixtures` probe fixtures declares `##fileformat=VCFv4.3`. The 14 `bcftoolsDependencyTest` cases that
re-measure the bcftools columns are a *second*, separate evidence source -- inline probes built in that test
rather than the committed fixtures -- and 13 of them declare `##fileformat=VCFv4.2` instead (only
`testMetaEmptyIdBcftoolsKeepsVerbatim` declares `VCFv4.3`). That difference is immaterial to the bcftools
columns (`bcf_hdr_parse_line` never consults `##fileformat`), but it does mean the `(4.3)` vcf_validator pin
describes the fixture corpus, which is what vcf_validator was actually run against. **Discriminating
observable:** for bcftools, whether the declaration survives to `bcftools view -h`'s output at all, and
verbatim vs. diagnostic-then-verbatim; for vcf_validator, its own Valid/Invalid verdict plus, where
distinguishing, its diagnostic message text.

The two `##SAMPLE` fixtures (`sample-missing-id.vcf`/`sample-empty-id.vcf`) and their corresponding
`bcftoolsDependencyTest` inline probes also carry a `Genomes=Germline` attribute beyond the `ID`-focused probe
shape stated above -- harmless, since `IdDescriptionMetadata` (used for `##SAMPLE`) treats `Genomes` as an
unmodeled extra attribute it neither validates nor requires (see `VCF_SPEC_AMBIGUITIES.md`'s "`##SAMPLE`
missing `Genomes` field" entry), so it has no bearing on the missing/empty-`ID` condition under test. Each fixture's own `##Authority=` line already discloses that its committed fixture
additionally carries `Description="test"` where the inline test probe doesn't (to avoid an unrelated
missing-`Description` warning); `Genomes=Germline` itself is common to both and was not previously called out
as a deviation from the probe shape, since it isn't one -- it just isn't part of what this table's rows measure.

Two separate spec facts apply below and must not be conflated into one citation:

- **(a) Whether an `ID` is required at all** -- version-gated, stated per cell in the four spec columns below,
  reusing this file's own "Missing ID is spec-grounded" section's reasoning rather than re-deriving it: `silent`
  under VCFv4.2 for five of the six types that exist there (footnote [a] -- not a true "anything goes" silence,
  see below), `required` under VCFv4.2 for `##INFO` alone, which VCFv4.2 states outright (footnote [h]), and
  `required` from VCFv4.3 onward for every type that already exists at that version (footnote [b]; `##ALT`
  additionally carries its own 4.4/4.5 restatement, footnote [e]; `##META` is `N/A` at 4.2 since it doesn't
  exist yet, footnote [f]).
- **(b) Whether any version distinguishes a wholly missing `ID` attribute from a present-but-empty `ID=` value**
  -- uniformly `no`, for all four versions and all seven types alike: no VCF specification version says anything
  different about the two shapes. This fact does not vary by row or column, so it is stated once, here, rather
  than repeated in each of the 28 spec cells below.

**Probe script:** `scripts/probe-duplicate-metadata-id-handling.sh` also re-runs both tools against all 14 of
these fixtures (see that script for the exact invocations and verdict parsing), the same evidence-preservation
mechanism as the "Reference table" section above. **Independently re-derived 2026-08-21** (per
`BEHAVIOR_TABLE_STANDARD.md`'s requirement that a load-bearing table get a second-party check): a fresh
re-derivation from the `.ragel` grammar, predictions written before measuring and without reading this table
first, confirmed every verdict in the `bcftools 1.23.1`/`vcf_validator 0.10.2` columns below and resolved
notes [c]/[d]'s previously-open "presumed, not confirmed" status -- see those notes for the one new nuance it
surfaced. (This document's other three tables have not had the same independent check; only this one's
missing/empty-`ID` table has, since its own notes [c]/[d] were the specific gap that motivated commissioning
it.)

Column notes, per the standard: **bcftools** and **vcf_validator** each get one unqualified/version-pinned
column per condition (missing vs. empty), the same convention as this document's "Reference table" above --
bcftools unqualified (`bcf_hdr_parse_line` never consults `##fileformat`), vcf_validator pinned to `(4.3)`, the
grammar every `local-fixtures` probe fixture here actually declares (see the probe shape above for how the
inline `bcftoolsDependencyTest` probes differ). **Parse / Public construction** is omitted as a table column
rather than given its own: it is a constant `warn + DROP decl` for all 14 combinations below, verified two ways
rather than assumed from the "warns and drops ... for all seven keyed types" prose above -- (1) directly against
the parser itself, since every one of the 14 fixtures cited below carries its own `##ExpectParser=warn`
declaration *plus* an `##ExpectWarnContains=` line pinning that type's own
`... metadata declaration has no ID and cannot be stored; dropped: ...` text -- the actual `DROP decl` evidence,
not merely the `IdMetadata.init()` precursor warning -- both confirmed by `LocalFixtureConformanceTest`; and
(2) by reading `VcfMetadata.java` directly
(`addAlt`/`addInfo`/`addFilter`/`addFormat`/`addContig`/`addSample`/`addMeta`, lines ~1048-1218): each one's
missing/blank-`ID` `warnOrThrow` check runs in the identical shared, package-private `addXxx(md)` method that
this document's "Reference table" section above already confirmed is reached identically by both `VcfParser`'s `addXxxTrusted`
route and direct public construction's typed/raw `addXxx` overloads for the *duplicate*-ID check just below it
in the same method body -- the missing-ID check sits earlier in that same shared method, so the identical
collapse argument applies to it too. Stated once as policy per `BEHAVIOR_TABLE_STANDARD.md`'s "Default write is
usually a constant... state the policy once" rule, applied here to Parse/Public construction instead. **Validated
write** and **Default write** are omitted for the identical structural reason as the "Reference table" above: a
declaration that is never stored (the seven keyed types' `Map`-backed storage; see this section's opening
paragraph) leaves nothing for either write path to detect.

**No column below distinguishes a missing `ID` from an empty `ID=`, because no spec version does either** --
fact (b) above, restated here for a reader who jumps straight to the table without reading the prose first.

| # | Declaration type | 4.2 | 4.3 | 4.4 | 4.5 | bcftools 1.23.1 (missing) | vcf_validator 0.10.2 (4.3) (missing) | bcftools 1.23.1 (empty) | vcf_validator 0.10.2 (4.3) (empty) | Fixture / `bcftoolsDependencyTest` (missing) | Fixture / `bcftoolsDependencyTest` (empty) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `##contig` | silent [a] | required [b] | required [b] | required [b] | warn + DROP decl | Invalid | warn + keep verbatim | Invalid (presumed same pattern) [c] | `contig-missing-id.vcf`; `testContigMissingIdBcftoolsWarnsAndDrops` | `contig-empty-id.vcf`; `testContigEmptyIdBcftoolsWarnsAndKeeps` |
| 2 | `##INFO` | required [h] | required [b] | required [b] | required [b] | warn + DROP decl | Invalid | warn + keep verbatim | Invalid (same charset message) [d] | `info-missing-id.vcf`; `testInfoMissingIdBcftoolsWarnsAndDrops` | `info-empty-id.vcf`; `testInfoEmptyIdBcftoolsWarnsAndKeeps` |
| 3 | `##FORMAT` | silent [a] | required [b] | required [b] | required [b] | warn + DROP decl | Invalid | warn + keep verbatim | Invalid (presumed same pattern) [c] | `format-missing-id.vcf`; `testFormatMissingIdBcftoolsWarnsAndDrops` | `format-empty-id.vcf`; `testFormatEmptyIdBcftoolsWarnsAndKeeps` |
| 4 | `##FILTER` | silent [a] | required [b] | required [b] | required [b] | DROP decl (no warning) | Invalid | keep verbatim | Invalid (same charset message) [d] | `filter-missing-id.vcf`; `testFilterMissingIdBcftoolsSilentlyDrops` | `filter-empty-id.vcf`; `testFilterEmptyIdBcftoolsKeepsVerbatim` |
| 5 | `##ALT` | silent [a] | required [b] | required [b][e] | required [b][e] | keep | Invalid ("Error in ALT metadata") | keep verbatim | Invalid ("ID not prefixed by DEL/INS/...") | `alt-missing-id.vcf`; `testAltMissingIdBcftoolsKeepsSilently` | `alt-empty-id.vcf`; `testAltEmptyIdBcftoolsKeepsVerbatim` |
| 6 | `##SAMPLE` | silent [a] | required [b] | required [b] | required [b] | keep | Invalid | keep verbatim | Invalid ("ID contains a character different from...") | `sample-missing-id.vcf`; `testSampleMissingIdBcftoolsKeepsSilently` | `sample-empty-id.vcf`; `testSampleEmptyIdBcftoolsKeepsVerbatim` |
| 7 | `##META` | N/A [f] | required [b] | required [b] | required [b] | keep | Invalid ("Error in META metadata") | keep verbatim | Invalid ("ID contains a character different from...") | `meta-missing-id.vcf`; `testMetaMissingIdBcftoolsKeepsSilently` | `meta-empty-id.vcf`; `testMetaEmptyIdBcftoolsKeepsVerbatim` |
| 8 | **Control** -- single, well-formed, non-empty `##contig` `ID` | n/a (not a missing/empty-ID case) | n/a | n/a | n/a | kept, no warning | Valid | kept, no warning | Valid | see note [g] | see note [g] |

**Notes:**

- **[a]** VCFv4.2 has no statement requiring an `ID` for *this* declaration type: it has no general
  structured-line rule at all (0 hits for "unique within" in `VCFv4.2.tex`), and its one explicit "required"
  sentence about a structured line's attributes is `##INFO`-specific (footnote [h] below, which is why row 2's
  4.2 cell reads `required` rather than `silent`). What VCFv4.2 does show for every one of these six pre-4.3
  types is a worked example/template with `ID=` unconditionally first and no optional-field notation
  (confirmed: `VCFv4.2.tex` lines 33 (`##contig`), 35-40 (`##INFO`), 41-42 (`##FILTER`), 43-46 (`##FORMAT`),
  69/87/94/103/126/140/146 (each type's own generic `ID=ID`/`ID=type` attribute-list template)) -- see this
  file's "Missing ID is spec-grounded" section above, reused here rather than re-derived.
- **[b]** VCFv4.3.tex line 121, VCFv4.4.tex line 121, VCFv4.5.tex line 119 -- identical text in all three:
  "All structured lines require an ID which must be unique within their type, i.e., within all the
  meta-information lines with the same ``##``*key*`=` prefix." Same citation as this document's "Reference
  table" footnote [a] above, reused rather than re-derived.
- **[c]** **Confirmed 2026-08-21** by an independent second-party re-derivation (predictions written from
  `vcf_v43.ragel`/`vcf.ragel` before measuring, per `BEHAVIOR_TABLE_STANDARD.md`'s discipline, without first
  reading this table): `##FORMAT`/`##contig`'s empty-`ID` case does produce the identical
  "Metadata ID contains a character different from alphanumeric, dot, underscore, dash and plus." message as
  `##SAMPLE`/`##META`. Root cause: `meta_format`/`meta_contig` each feed their `ID` token into the shared
  `meta_id_err` error action (`vcf_v43.ragel:107`/the `chrom_basic` production respectively), the same action
  `meta_info`/`meta_filter`/`meta_sample`/`meta_meta` use, so all six share one message string verbatim --
  only `##ALT` (footnote below) uses a different production and a different message. **One further, genuinely
  new finding from that re-derivation, previously unrecorded here:** the message text is measurably
  *inaccurate* for `##contig` specifically -- `chrom_basic` (contig's own ID-position production) tolerates
  several punctuation characters (e.g. colon, `!`) that `identifier` (the production every other type uses)
  does not, so the charset the message describes is narrower than what `##contig` actually accepts. This is a
  tool-message-wording quirk, not a behavior divergence: the empty-`ID` fixture itself is still measurably
  rejected with this exact text, confirmed by direct invocation, not inferred from the charset claim.
- **[d]** **Confirmed 2026-08-21**, same independent re-derivation as [c]: `##INFO`/`##FILTER`'s empty-`ID`
  case does produce the identical "ID contains a character different from..." message as `##SAMPLE`/`##META`.
  `meta_info`/`meta_filter` both feed `identifier` into `meta_id_err` too (`vcf_v43.ragel:113`/`:103`), the
  same shared action as [c] above -- unlike [c], no message-accuracy quirk was found here, since `identifier`'s
  charset is exactly what the message describes for these two types.
- **[e]** VCFv4.4.tex line 201, VCFv4.5.tex line 250: "ALT meta-information lines are structured lines with
  require[d] fields of ID and Description..." (the spec's own text reads "require," not "required" -- the
  bracketed insertion marks the apparent typo, matching this file's "Missing ID is spec-grounded" section's own
  quote of the identical sentence). An `##ALT`-specific restatement of fact (a) above, on top of the shared [b]
  citation every other type also carries at 4.4/4.5.
- **[f]** `##META` does not exist as a structured declaration type before VCFv4.3 -- see
  `VALIDATION_REFERENCE.md`'s own entry for the citation and for why this is enforced only at parse time, not at
  construction or write time. Same reasoning as this document's "Reference table" footnote [b] above.
- **[g]** No dedicated fixture: each of this table's own missing-ID fixtures other than `contig-missing-id.vcf`
  itself (`info-missing-id.vcf`, `format-missing-id.vcf`, `filter-missing-id.vcf`, `alt-missing-id.vcf`,
  `sample-missing-id.vcf`, `meta-missing-id.vcf`) -- and, identically, each of the seven `*-empty-id.vcf`
  fixtures other than `contig-empty-id.vcf` -- already contains exactly one well-formed, non-empty-`ID`
  `##contig=<ID=chr1,length=1000000>` declaration alongside that row's own missing/empty-`ID` case under test,
  confirmed present in each file. Each of those twelve fixtures' own passing parse and bcftools run is
  therefore already a live control instance in the same run as its row's decision-bearing case -- the same
  reuse-rather-than-duplicate pattern as this document's "Reference table" footnote [f] above. vcf_validator is
  deliberately *not* claimed as part of that control: it reports every one of these fixtures **Invalid** (this
  table's own `vcf_validator (missing)`/`(empty)` cells), so its verdict on them cannot double as a clean
  control run, and none of the 14 fixtures carries a vcf_validator `##Authority=` line at all. What does cover
  vcf_validator is the "Reference table" section's own control row, which separately confirmed that exact
  `##contig=<ID=chr1,length=1000000>` value accepted by both tools.
- **[h]** `VCFv4.2.tex` line 66, immediately preceding the `##INFO` template at line 69: "INFO fields should be
  described as follows (first four keys are required, source and version are recommended):". Line 69 is,
  verbatim,
  `##INFO=<ID=ID,Number=number,Type=type,Description="description",Source="source",Version="version">`, whose
  first four keys are `ID`, `Number`, `Type` and `Description`. So VCFv4.2 does explicitly require `ID` for
  `##INFO`, unlike the five other pre-4.3 types in this table, whose 4.2 cells rest on the template argument in
  footnote [a] instead. This is the only per-type "required" wording VCFv4.2 carries; the general "all
  structured lines require an ID" rule footnote [b] cites arrives only at VCFv4.3.

`##ALT`/`##SAMPLE`/`##META` aren't part of BCF's binary dictionary encoding, so htslib never validates their `ID`
at all. `##INFO`/`##FORMAT`/`##contig`/`##FILTER` ARE part of BCF's dictionary, so a *missing* `ID` invalidates
the whole declaration -- but an *empty* `ID=` is, inconsistently, tolerated (`warn + keep verbatim`, never
`DROP decl`). `vcf-parser` does not replicate bcftools's per-type inconsistency for missing IDs (fact (a) above
-- the spec settles that dimension), and does not replicate bcftools's empty-ID leniency either (see below):
every one of the 14 cells' own `Fixture` citation above links a `local-fixtures/failed/*.vcf` case whose
`##ExpectParser=warn` declaration confirms `vcf-parser` itself applies `warn + DROP decl` uniformly, regardless
of which column above it falls into.

### Empty/whitespace ID is a deliberate stricter-than-bcftools choice

No VCF spec version (4.2 through 4.5) or hts-specs conformance fixture addresses the empty-ID case at all --
this dimension is genuinely unresolved by spec text (fact (b) in the "bcftools/vcf_validator behavior
(live-tested)" section's table above). Treating an empty or whitespace-only `ID=` the same as a missing one is a
deliberate editorial decision, NOT something bcftools's own behavior supports (bcftools actually keeps an
empty-ID `##ALT`/`##SAMPLE`/`##FILTER`/`##META` declaration verbatim with no warning at all, and an empty-ID
`##INFO`/`##FORMAT`/`##contig` declaration with only a warning -- never dropped, for any of the seven types; see
that same table above) -- consistent instead with this project's existing precedent of being stricter than
bcftools for other empty-structural-field content (see `EMPTY_FIELD_HANDLING.md`).

### `##FILTER` warns even though bcftools doesn't

`##FILTER`'s missing/empty-`ID` drop warns in `vcf-parser`, even though bcftools itself is silent for *both* the
missing-ID case (`DROP decl`, no warning at all) and the empty-ID case (`keep verbatim`, no warning at all) --
matching this project's established more-vocal-than-bcftools convention (see this file's "Why this warns instead
of throwing" section above, applied the same way here).

## Why "keep first," not "keep last"

`vcf-parser` keeps the *first* declaration and discards each subsequent duplicate, matching bcftools' confirmed
behavior exactly (per `AGENTS.md`: "If still in doubt, consult bcftools behavior"). This also means a discarded
duplicate's own attributes are never validated against that type's own rules (e.g. `##ALT`'s structural-variant-code
check) — a dropped declaration is never stored, so its internal validity is irrelevant, the same way bcftools never
surfaces any complaint about a dropped duplicate's content either.

## Where this is implemented

- `VcfMetadata.warnIfDuplicate(Map, String, String, VcfContext)` — the shared helper, used by
  `VcfMetadata.Builder`'s `addXxx` methods; a strict context throws instead of warning (see
  `VALIDATION_PHILOSOPHY.md`).
- `VcfMetadata.Builder.addAlt()` / `addInfo()` / `addFormat()` / `addFilter()` / `addContig()` / `addSample()` /
  `addMeta()` — each calls `warnIfDuplicate()` before storing a new declaration, and returns immediately (without storing) if
  it reports a duplicate. There is no `removeXxx` anywhere: a built `VcfMetadata` is immutable. To produce a
  `VcfMetadata` without some declaration entirely, build a fresh one rather than editing an existing one --
  `Builder` has no way to drop one. To overwrite an existing declaration under the same `ID` instead, use the
  `replaceXxx` counterpart described below -- routing that through `addXxx` hits `warnIfDuplicate()` above,
  which discards the edit (warn-and-keep-first) instead of applying it.
- `VcfMetadata.Builder.addPedigree()` — warns and keeps the first entry when a duplicate `ID` property is found
  (compared unquoted -- see this file's "Quoting is not part of the identity" section); adds unconditionally
  when no `ID` property is present, **below VCFv4.3**. At VCFv4.3+, an absent
  or blank `ID` instead warns "has no ID" (or throws, under a strict context) but is still kept -- see this
  file's "Missing or empty ID" section's eighth-type paragraph.
- `VcfWriter.validateMetadata()`'s PEDIGREE loop — the same duplicate-ID check, but warn-only (never drops),
  since it runs post-construction on content the caller already holds a reference to. Also mirrors
  `addPedigree()`'s missing/blank-`ID` gate at VCFv4.3+ (same eighth-type paragraph as above): a blank `ID`
  warns and is kept, and is skipped before this method's own duplicate-ID dedupe set to avoid the same
  spurious-collision failure mode.
- `IdMetadata`'s shared `IdMetadata(VcfContext, String label, String id)` constructor -- used by every
  typed, named-parameter constructor across `IdDescriptionMetadata`/`InfoMetadata`/`FormatMetadata`/
  `ContigMetadata` (every declaration constructor is package-private; `VcfMetadata.Builder`'s raw-value
  `addXxx` overloads are the supported way in) -- reports a missing or blank `ID` through
  `BaseMetadata.warnOrThrow`, so it throws `VcfFormatException` under a strict context and warns under a
  lenient one. The separate `Map<String,String>`-based constructors `VcfParser` uses behave identically for
  the same content: the input shape does not decide strictness, the context does.
- `IdMetadata.init()` -- the Map-based (lenient) constructors' shared post-construction check, and also run
  again by `validate()` -- separately warns ("`{} metadata ID is blank`") for a missing or blank `ID`, one
  step before a declaration ever reaches the `addXxx`-level check below. This is a genuinely independent
  check, not a restatement: parsing one malformed header line produces both this warning and the `addXxx`-level
  one in the same log; see `WONT_FIX.md`'s "Missing or blank metadata ID" sub-case (referenced above) for why
  neither is suppressed in favor of the other.
- `VcfMetadata.Builder`'s 7 `addXxx` methods each check for a missing or blank `ID` before calling
  `warnIfDuplicate()`, warning and dropping the declaration entirely (never storing it) if so -- this is what
  actually removes a malformed declaration from storage, built via the parser's lenient `Map`-based
  construction path. Under a strict `VcfContext`, both checks throw `VcfFormatException` instead -- see
  `VALIDATION_PHILOSOPHY.md`. Note that a strict context already rejects a missing/blank `ID` at
  declaration-construction time, so the `addXxx`-level check is reachable there only for a declaration whose
  `ID` was removed afterward via the package-private `BaseMetadata.trustedMutableProperties()` bypass.

## `replaceXxx`: the supported way to overwrite a declaration

`VcfMetadata`'s model layer is immutable, with no instance `removeXxx` method for any of the seven keyed types
(see the "Where this is implemented" section above). Without a `removeXxx`, the natural-seeming "remove then
add" idiom for overwriting a declaration (`toBuilder().removeInfo(id).addInfo(id, ...)`) has no supported path:
`toBuilder().addInfo(existingId,
...)` reaches `warnIfDuplicate()` above like any other `addXxx` call, which -- correctly, for `addXxx` -- warns
and keeps the *first* declaration under a lenient context, silently discarding the caller's intended edit, and
throws under a strict one. Neither outcome lets a caller actually replace a declaration.

`VcfMetadata.Builder.replaceAlt()` / `replaceInfo()` / `replaceFormat()` / `replaceFilter()` / `replaceContig()` /
`replaceSample()` are the fix: one pair of overloads per keyed type (a typed overload and a raw-`Map` overload,
mirroring `addXxx`'s own pairing, so leniency keeps coming from the `VcfContext` rather than the overload shape),
each bypassing `warnIfDuplicate()` entirely for what is, by calling `replaceXxx`, always an intentional overwrite.
`replaceMeta()` follows the same present-check-then-mutate shape but breaks the pairing: `##META` has no simple
`ID`+`Description` schema (see `MetaMetadata`'s own Javadoc), so it gets only the raw-`Map` overload, not a typed
one. There is no `replacePedigree`: `##PEDIGREE`'s `List` backing (see below) has no single `Map` entry to overwrite,
and its `ID` is optional rather than mandatory.

**The missing-target contract is deliberately not context-dependent.** Replacing an `ID` that has no existing
declaration throws `VcfFormatException` -- in *both* a lenient and a strict context -- and never appends a new
declaration under that `ID`. This is the one place in this file where lenient and strict behave identically:
`replaceXxx` exists specifically to be the typo-resistant alternative to the retired remove-then-add idiom, so a
missing target is a programming error in either mode, not a content violation the way an invalid `Number`/`Type`
value is. Appending on a mistyped `ID` would reintroduce the exact failure `replaceXxx` exists to prevent (a
"replace" that actually creates a stray extra declaration), and warning-and-continuing under a lenient context
would leave the transformed `VcfMetadata` silently unchanged -- exactly the failure mode described above that
motivated adding `replaceXxx` in the first place. Adding a genuinely new declaration remains `addXxx`'s job.
`VcfMetadata.Builder.requirePresentForReplace(Map, String, String)` is the single, shared check every `replaceXxx`
method runs before touching its map, and unconditionally throws rather than delegating to `warnOrThrow`.

**Replacement is atomic with respect to a rejected replacement.** Only `##ALT` has a post-construction content
check at add time (`VcfMetadata.validateAlt()`, checking a declared `Number`/`Type` extra), and it is the reason
this needed care rather than being a mechanical wrapper around `addAlt()`: `addAlt()` runs `warnIfDuplicate()`
*before* `validateAlt()`, so routing a same-`ID` replacement through it would short-circuit on the duplicate-ID
warning before content is ever validated at all. `replaceAlt()` instead confirms the target `ID` is present, then
validates the fully-constructed replacement declaration completely, and only then performs the single map
mutation (`m_alt.put(id, md)`) -- so a rejected replacement (invalid `Number`/`Type`) leaves the existing
declaration installed and the builder otherwise unchanged. Neither of the two mechanical-looking alternatives
gives that for free: *remove-then-add* loses the valid original the moment validation throws (nothing left to put
back), and *put-then-validate* leaves the invalid declaration stored -- `build()` does not re-run add-time
validation, so it would silently survive into the built `VcfMetadata`. The other six keyed types have no
add-time content check to preserve this ordering for, but `replaceInfo()`/`replaceFilter()`/`replaceFormat()`/
`replaceContig()`/`replaceSample()`/`replaceMeta()` follow the same present-check-then-mutate shape for
consistency.

## `##PEDIGREE`: a conditional case

Unlike the seven keyed types above, `##PEDIGREE`'s grammar (`VCFv4.2.tex` line 152: `##PEDIGREE=<Name_0=G0-ID,...,
Name_N=GN-ID>`) has no mandatory `ID` attribute — the spec's own worked example (lines 904-908) declares three
`##PEDIGREE` lines in one file, each with a different, non-`ID` attribute schema (`Derived=...,Original=...`).
This is why `m_pedigree` is a `List<PedigreeMetadata>`, not a `Map<String, ...>` like the seven keyed types — there is
no guaranteed key to map on.

Per `BEHAVIOR_TABLE_STANDARD.md`. **Probe shape:** one hand-built fixture per case, each declaring two
`##PEDIGREE` lines differing only in a non-`ID` attribute (`Name_0`), immediately followed by a well-formed
`##contig=<ID=chr1,length=1000000>` line. Rows 1 and 2's fixtures declare `##fileformat=VCFv4.2` (the
version whose grammar has no `ID` attribute at all, isolating the no-`ID`/real-`ID` contrast from the
`ID`-required rule); row 3's fixture declares `##fileformat=VCFv4.3` (the version from which an empty
`ID=` is a value the general "structured lines require an ID" rule applies to) -- see each row's own
citation below. **Discriminating observable:** for bcftools, whether both `##PEDIGREE=` lines survive to
`bcftools view -h`'s output, not merely that the file re-parses; for vcf_validator, its own Valid/Invalid
verdict plus, for row 3, its diagnostic message text.

Column notes, per the standard: **bcftools** gets one unqualified column, same reasoning as the "Reference
table" section above (`bcf_hdr_parse_line` never consults `##fileformat`). **vcf_validator** is pinned
per-row to the grammar that row's own fixture actually declares (rows 1-2: `VCFv4.2`; row 3: `VCFv4.3`)
rather than one shared header pin, since -- unlike the "Reference table" and "Missing or empty ID" tables
above, whose every probe fixture shares one `##fileformat` -- these three rows deliberately probe two
different versions (see probe shape above); a single header pin would misstate two of the three rows.
**Parse** and **Public construction** collapse into one column: both reach the identical
`VcfMetadata.Builder.addPedigree(PedigreeMetadata)` (confirmed by reading `VcfParser.parseMetadata`'s
`case "PEDIGREE":`, which calls `addPedigreeTrusted` -> `addPedigree(properties, false)`, and the public
`addPedigree(Map)` -> `addPedigree(properties, true)` overload -- the `validateContent` boolean the two
differ on gates only the pre-dedup `checkMetadataPropertyEncoding` call, never the dedup call itself, the
same collapse shape as the seven keyed types). **Parse / Public construction** and **Validated write** are
kept as two separate columns, not collapsed into each other: reading `addPedigree(PedigreeMetadata)` and
`VcfWriter.validateMetadata()`'s PEDIGREE loop directly (this task, current source) -- rather than assuming
the "Where this is implemented" section's own "mirrors" prose above without checking, per this task's own
instructions -- shows the two agree for rows 1 and 3 (neither path ever removes an entry: the no-`ID`
pass-through and, at VCFv4.3+, the blank-`ID` early-return in each method add/warn-and-keep unconditionally,
without ever reaching either method's own duplicate-`ID` dedup set) but genuinely diverge for row 2:
`addPedigree` warns and returns without storing the duplicate (`m_pedigree.add(md)` is never reached for
it), so the duplicate never exists in a `VcfMetadata` built through the public `Builder` path at all;
`VcfWriter.validateMetadata()`'s loop runs over `metadata.getPedigrees()`, an already-built, immutable list
it cannot remove an entry from -- it can only warn, so if a duplicate real-`ID` pair ever reaches this loop
(only possible via `VcfMetadata`'s package-private trusted constructor, which bypasses `addPedigree()`
entirely), both entries are written. This is the source of the real-`ID` fixture's own
`##RoundTripDivergenceReason=`; see footnote [c]. **Default write** is `N/A` for all three rows: per
`AGENTS.md`'s Writer Validation and Round-Trip Policy, the default write path performs no VCF-semantic
re-validation and emits the model as-is, so it has no mechanism to detect or react to a duplicate `ID` at
all -- stated once here rather than per row, per `BEHAVIOR_TABLE_STANDARD.md`'s "Default write is usually a
constant" rule.

| # | Case | 4.2 | 4.3 | 4.4 | 4.5 | bcftools 1.23.1 | vcf_validator 0.10.2 | Parse / Public construction | Validated write | Fixture / `bcftoolsDependencyTest` |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Two `##PEDIGREE` lines, no `ID` property, otherwise identical | N/A [a] | required [b] | required [b] | required [b] | keep both, no warning | Valid (4.2) | kept, no warning [e] | kept, no warning [e] | `pedigree-duplicate-no-id.vcf`; `testPedigreeNoIdDuplicatesBcftoolsKeepsBoth` |
| 2 | Two `##PEDIGREE` lines sharing `ID=P1` | N/A [a] | required [b] | required [b] | required [b] | keep first, DROP dup, no warning | Valid (4.2) | warn + keep first (DROP dup) | warn only, keeps BOTH [c] | `pedigree-duplicate-real-id.vcf`; `testPedigreeRealIdDuplicatesBcftoolsKeepsFirst` |
| 3 | Two `##PEDIGREE` lines each with an empty `ID=` | N/A [a] | required [b] | required [b] | required [b] | keep first, DROP dup, no warning | Invalid (4.3) [d] | warn "has no ID", keeps both | warn "has no ID", keeps both | `pedigree-duplicate-empty-id.vcf`; `testPedigreeEmptyIdDuplicatesBcftoolsKeepsFirst` |
| 4 | **Control** -- single, non-duplicated PEDIGREE block | n/a (not a duplicate case) | n/a | n/a | n/a | kept, no warning | Valid | kept, no warning | kept, no warning | see note [f] |

**Notes:**

- **[a]** `VCFv4.2.tex` line 152's `##PEDIGREE` grammar (`##PEDIGREE=<Name_0=G0-ID,...,Name_N=GN-ID>`) has no
  `ID` attribute at all -- a structural absence, not `silent`: the grammar simply never defines the
  attribute this table's rows key on, so there is no "does the spec require it" question for VCFv4.2 to be
  silent about. Same grammar this section's opening paragraph already cites.
- **[b]** VCFv4.3.tex line 121, VCFv4.4.tex line 121, VCFv4.5.tex line 119 -- identical text in all three:
  "All structured lines require an ID which must be unique within their type, i.e., within all the
  meta-information lines with the same ``##``*key*`=` prefix." Same citation as this document's "Reference
  table" footnote [a] and "Missing or empty ID" section's footnote [b] above, reused rather than re-derived.
- **[c]** Verified by reading `VcfWriter.validateMetadata()`'s PEDIGREE loop directly (current source, this
  task): its duplicate-`ID` branch only calls `m_warnings.warn(...)`, never removes an entry from
  `metadata.getPedigrees()` -- it cannot, since that list is an unmodifiable view of an already-built,
  immutable `VcfMetadata`. See the column notes above for the full divergence and how such a duplicate can
  reach this loop at all despite `addPedigree()` itself never storing one.
- **[d]** "Line 3: Metadata ID contains a character different from alphanumeric, dot, underscore, dash and
  plus." (and identically for line 4) -- measured live via `vcf_validator -i pedigree-duplicate-empty-id.vcf
  -r summary,text` on 2026-08-16 (re-confirmed during this task); matches the fixture's own `##Authority=`
  line.
- **[e]** Row 1's own fixture declares `##fileformat=VCFv4.2`, whose grammar has no `ID` attribute at all
  (footnote [a]), so its "kept, no warning" is specific to 4.2 and below. At VCFv4.3+, a wholly-absent `ID`
  is routed through the identical early-return branch as row 3's present-but-empty `ID=`
  (`id == null || id.trim().isEmpty()`, in both `addPedigree()` and `VcfWriter.validateMetadata()`'s
  PEDIGREE loop): warn "has no ID" and keep the entry unconditionally, without ever reaching either
  method's own duplicate-`ID` dedup set -- i.e., at VCFv4.3+ row 1 converges with row 3's own cells. No
  divergence between Parse/Public construction and Validated write is possible for either row at any
  version, since neither path can drop an entry this way -- unlike row 2 (footnote [c]).
- **[f]** No dedicated local fixture: hts-specs' own `test/vcf/4.3/passed/passed_meta_pedigree.vcf` (part of
  the opt-in `htsSpecsTest` corpus, not this project's `local-fixtures`) declares four `##PEDIGREE` lines,
  each with a distinct `ID` (`TumourSample`, `SomaticNonTumour`, `ChildID`, `SampleID`), none duplicated --
  confirmed live on 2026-08-16: `bcftools view -h` keeps all four unchanged, exit 0, no warning;
  `vcf_validator 0.10.2` reports the file valid.

So the rule generalizes: bcftools dedupes by `ID` wherever that key is present, regardless of the declaration's
tag name — not seven special cases plus an unrelated PEDIGREE rule, but one rule (dedupe on `ID` when present) that
the seven keyed types happen to always trigger (their grammar makes `ID` mandatory) and PEDIGREE only sometimes
triggers (its grammar makes `ID` optional). `VcfMetadata.Builder.addPedigree()` implements this rule directly, at
every version, whenever `ID` is present and duplicated: warn and keep the first, dropping the duplicate before it
is ever stored (table row 2). `VcfWriter.validateMetadata()`'s PEDIGREE loop applies the identical *detection*
logic but, since it runs over an already-built, immutable list it has no way to remove an entry from, can only
warn -- it never drops a duplicate, so if one reaches this loop at all (only possible by bypassing `addPedigree()`
-- see the column notes above), both entries are written. The "add unconditionally when `ID` is absent" half of
this rule (table row 1) IS version-gated -- see the table and footnote [e] above for exactly where.

**The empty-`ID=` row is version-split.** Below VCFv4.3, PEDIGREE's own grammar has no `ID` attribute at
all, so this library follows bcftools's observed behavior and dedupes an empty `ID=` like any other
shared value (the same rule table row 2 demonstrates for a real `ID` -- not itself shown as a separate row
above, since row 3's own fixture demonstrates the VCFv4.3+ rule instead; see footnote [e]). At VCFv4.3+,
VCFv4.3.tex line 121 requires an ID that a fully-optional-grammar reading of PEDIGREE could otherwise skip,
so this library deliberately diverges from bcftools here, per `AGENTS.md`'s decision hierarchy (spec text
governs where it isn't silent): two `##PEDIGREE` lines each with an empty `ID=` are both **kept**, each
independently warning "has no ID" rather than the second being dropped as a duplicate — see the "Missing or
empty ID" section's eighth-type paragraph for the full construction/write-time rule.

# VCF spec ambiguities

This file catalogs every case where the VCF spec (4.2 through 4.5) is silent, self-contradictory, or
insufficiently specific to determine this library's behavior on its own, and where **bcftools** and/or
**vcf_validator** were consulted per `AGENTS.md`'s decision hierarchy to resolve it. It is an index for future
agents: each entry states the ambiguity, what each source actually says (verified live, not assumed), the
decision made, and where that decision is implemented — so the next person who hits the same question doesn't
have to re-derive it from git history or re-run the tools from scratch. A small number of entries (marked 🟡)
document an ambiguity that was investigated this way but is still genuinely open — no decision has shipped yet
— so a future agent doesn't re-run the same tool checks before finding that out; see the active findings
backlog for the current status of any 🟡 entry.

This file does **not** duplicate two topics that already have their own dedicated, deeper reference docs:

- Duplicate metadata declaration IDs (`##contig`/`##INFO`/`##FORMAT`/`##FILTER`/`##ALT`/`##SAMPLE`/`##PEDIGREE`)
  — see [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md).
- Zero-length ("empty") delimited field entries across ID/FILTER/ALT/FORMAT/INFO/sample values — see
  [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md).

Tool versions referenced throughout, unless a specific entry states otherwise: **bcftools 1.23.1** (htslib
1.23.1) and **vcf_validator 0.10.2**. Per `AGENTS.md`, bcftools is the tie-breaker when both tools disagree —
but several entries below show that rule applied *with* its stated exceptions: bcftools's own silence or
permissiveness is sometimes judged a coverage gap rather than a considered position (see "Whitespace in
`##FILTER`/`##SAMPLE` declaration IDs" and "CNP's `Number=G`" below), in which case vcf_validator, an hts-specs
fixture, or the spec's own prose governs instead. Read each entry's reasoning, not just its one-line decision,
before assuming it generalizes to a similar-looking case.

**Status legend:** ✅ resolved and implemented · 🟡 open / pending a decision.

## Summary

| Topic | Decision | Status |
|---|---|---|
| Duplicate attribute within one metadata declaration | Keep last (matches bcftools) | ✅ |
| Whitespace in `##FILTER`/`##SAMPLE` declaration IDs | Reject (bcftools's silence is a coverage gap) | ✅ |
| `##FORMAT`/`##INFO` ID grammar vs. `##FILTER`/`##SAMPLE`/`##ALT` | Stricter grammar for FORMAT/INFO only | ✅ |
| `"1000G"` legacy INFO tag-name exception | Allowed (matches bcftools) | ✅ |
| `##contig` ID character set | SAM reference-name rules; does not extend to INFO/FORMAT tag names | ✅ |
| `##contig` extra/unmodeled attributes | Allowed, no warning | ✅ |
| Generic metadata property with an empty value | Drop (deliberately stricter than bcftools) | ✅ |
| `##pedigreeDB`/`##assembly` bracket-wrapped values colliding with attribute-list grammar | Reject when empty or no `=` inside the brackets; accept when it contains `=` | ✅ |
| FORMAT declared but zero sample columns | Reject (both tools agree) | ✅ |
| Empty sample name in `#CHROM` header | Reject (both tools agree) | ✅ |
| CHROM contiguity / POS ordering within a CHROM block | Warn, don't reject (neither tool rejects it) | ✅ |
| Trailing blank line at EOF | Reject (matches bcftools, stricter than vcf_validator) | ✅ |
| BOM-prefixed file / `+`-prefixed POS | Existing behavior already matches both tools | ✅ |
| ALT breakend contig-ID shorthand (`AC<ctg1>GT`, `<ctg1><ctg2>`) | Left permissive; neither tool is decisive | ✅ (judgment call) |
| FILTER value `"0"` | Reject (reserved value) | ✅ |
| `PASS` combined with other FILTER codes | Warn (lenient), throw (strict/direct construction) | ✅ |
| FILTER status (`PASSED`/`FAILED`/`NONE`) derivation | Derive purely from list contents, matching bcftools's clear-never-promotes-to-PASS convention | ✅ |
| Duplicate ID/FILTER entries within one record | Warn, keep both (no dedup) in lenient mode; throw in strict mode | ✅ |
| INFO value containing a bare `=` | Warn, don't throw (matches bcftools) | ✅ |
| Duplicate INFO key within one record | Warn, keep first occurrence (matches bcftools) | ✅ |
| Negative/out-of-range values for reserved INFO keys `AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF` | Warn (vcf_validator-sourced; bcftools silent) | ✅ |
| CIGAR grammar (SAM compliance) | Warn on violation (vcf_validator-sourced; bcftools silent) | ✅ |
| `FORMAT="."` meaning no per-sample fields | Accept (matches bcftools) | ✅ |
| Undeclared `##FORMAT` `Number` attribute | Treat as `Number=.` uniformly regardless of `Type` (matches bcftools) | ✅ |
| `CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G` | Exempt `CNP` from the genotype-ordering cardinality check | ✅ |
| `rsidsOnly()` discard semantics | Modeled on bcftools's own exclusion-filter mental model | ✅ (design analogy, not a format ambiguity) |

## Metadata declarations

### Duplicate attribute within one metadata declaration

A single declaration's own `<ID=...,Number=...,...>` structure repeating one attribute (e.g.
`Type=Integer,...,Type=String`) is a different scenario from a duplicate *declaration* ID across separate
lines (see `DUPLICATE_METADATA_ID_HANDLING.md`) — hts-specs' conformance suite has no fixture for it either
way. Confirmed live against bcftools: forcing conversion to binary BCF (which enforces the declared type,
unlike plain-text VCF) shows bcftools always resolves to the **last**-declared value regardless of which type
came first. vcf_validator rejects this case outright, but per the tie-breaker hierarchy its stricter
structural-field reading is non-authoritative here.

**Decision:** keep the last value (the opposite of every other duplicate-key case in this codebase, which
keep the first), with a warning. Implemented in `InternalUtils.extractProperties`.

### Whitespace in `##FILTER`/`##SAMPLE` declaration IDs

VCFv4.2.tex/VCFv4.3.tex only state the no-whitespace-in-ID rule for `##ALT` explicitly, but hts-specs'
own conformance fixtures extend it to `##contig` and `##SAMPLE`. bcftools empirically warns
("Invalid tag name"/"Invalid contig name") for `##INFO`/`##FORMAT`/`##contig` ID whitespace, but **accepts
whitespace silently for `##FILTER`/`##SAMPLE`** — it has no dedicated header check for either, directly
contradicting hts-specs' own `failed_meta_sample_001.vcf` fixture.

**Decision:** this is treated as a bcftools *coverage gap*, not a considered position — the hts-specs fixture
and vcf_validator's uniform rejection govern instead, so whitespace warns across every structured metadata ID
type, `##FILTER`/`##SAMPLE` included. Implemented in `IdMetadata`'s shared ID-whitespace check.

### `##FORMAT`/`##INFO` declaration ID grammar is stricter than `##FILTER`/`##SAMPLE`/`##ALT`

VCFv4.2 doesn't table a formal FORMAT-key grammar (only "colon-separated alphanumeric String" in prose);
VCFv4.3.tex states the exact regex (`^[A-Za-z_][0-9A-Za-z_.]*$`) for FORMAT keys, and a similar one with a
`"1000G"` legacy exception for INFO keys (VCFv4.3.tex:368). Confirmed live against bcftools that it enforces
this stricter grammar specifically for `##FORMAT`/`##INFO` declaration IDs (an asterisk anywhere but the first
character triggers "Invalid tag name") — but accepts the identical character silently in `##FILTER`/
`##SAMPLE`/`##ALT` declaration IDs.

**Decision:** the stricter grammar applies only to `##FORMAT`/`##INFO` IDs (and to a data-line FORMAT key);
`##FILTER`/`##SAMPLE`/`##ALT` IDs are only held to `IdMetadata`'s shared, looser whitespace/comma check.
Implemented via `InternalUtils.FORMAT_PATTERN`/`INFO_PATTERN`.

### `"1000G"` legacy INFO tag-name exception

VCFv4.3.tex's body text (line 368) states the INFO-key regex with an explicit `"1000G"` exception; the
VCFv4.2→4.3 changelog (line 2150) summarizes the same rule for "INFO and FORMAT tag names" together with no
exception mentioned. Confirmed live via bcftools, which silently accepts `##INFO=<ID=1000G,...>`.

**Decision:** the more specific body text (with the exception) governs over the changelog's general summary.
Implemented via `InternalUtils.INFO_PATTERN`.

### `##contig` ID character set

VCFv4.2.tex line 179 only bans whitespace in CHROM, and says nothing about `##contig` IDs' character set.
VCFv4.3.tex's own "Changes to VCFv4.3" errata (~line 2129-2144) extends SAM reference-sequence-name rules to
"VCF contig names" generically. Confirmed live via bcftools that this rule does **not** extend to `##INFO`/
`##FORMAT` tag names, which are validated under the separate, stricter grammar above instead.

**Decision:** `VcfPosition.checkChromosome` (CHROM values) and `ContigMetadata` (`##contig` IDs) share one
character-set check (`InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN`); `##INFO`/`##FORMAT` IDs use a
different, unrelated pattern. Colon is deliberately excluded from the invalid set — the same errata text
confirms disallowing it was itself a mistake ("this was unnecessary").

### `##contig` extra/unmodeled attributes

VCFv4.2.tex line 123 says "extra fields can be included after the default fields," without naming `##contig`
specifically, but its own worked example (line 140) ends in `...`, the same "more tags may follow" signal
`##INFO`/`##FORMAT`/`##ALT`/`##SAMPLE` share. `ContigMetadata` was, at one point, the only class in the model
package still calling `ensureNoExtras()`, warning about attributes the others tolerate silently.

**Decision:** confirmed via hts-specs' own `passed_meta_contig.vcf` fixture (declares an unmodeled `accession`
attribute, expected to parse cleanly) and live bcftools testing (silently round-trips a custom contig
attribute with zero warning) that `##contig` should tolerate extra attributes the same as every other
declaration type. `ContigMetadata.ensureNoExtras()` call removed.

### Generic metadata property with an empty value

**Decision:** drop the property with a warning, even though bcftools 1.23.1 silently preserves an empty
generic metadata line unchanged (confirmed live: `##reference=` round-trips through `bcftools view -h`
verbatim, no warning) — a deliberate choice to be stricter than bcftools here. Full reasoning (the `VCFv4.3.tex`
line 39 citation, and why this row's drop-the-whole-line behavior differs from every other empty-field row) and
implementation pointer live in `EMPTY_FIELD_HANDLING.md`'s "Generic metadata property" row and the paragraph
following its reference table.

### `##pedigreeDB`/`##assembly` bracket-wrapped values colliding with attribute-list grammar

`##pedigreeDB=<>` (hts-specs' `failed_meta_pedigreedb_001.vcf`) has no meaningful content between the
brackets. Confirmed directly that this isn't pedigreeDB-specific, and isn't limited to the empty case either:
bcftools 1.23.1 rejects both `##assembly=<>` (empty content) and `##pedigreeDB=<http://foo.com>` (non-empty
content with no `=` anywhere in it) with `"[E::bcf_hdr_parse_line] Could not parse the header line"` — by the
same htslib attribute-list-grammar collision, regardless of which key it's attached to.

**Decision:** `VcfMetadata.collidesWithAttributeListGrammar(String value)` rejects a bracket-wrapped value
whose trimmed inner content is either empty or contains no `=` (covering both `##assembly=<>` and
`##pedigreeDB=<http://foo.com>`). A bracket-wrapped value containing at least one `=` (e.g. `<ID=foo>`, or the
real hts-specs `##META=<ID=Assay,Number=.,Type=String,Values=[WholeGenome, Exome]>` fixture) is left alone,
since bcftools genuinely accepts and round-trips both. Separately: `##pedigreeDB`'s own spec template
(VCFv4.2/4.3/4.4.tex) is bracket-*less* (`##pedigreeDB=URL`), confirmed by hts-specs' own passing fixture and
live bcftools acceptance of the bracket-less form — brackets are not this field's own syntax, unlike
`##ALT`/`##INFO`'s `<ID=...>` structure.

## Data-line / record structure

### FORMAT declared but zero sample columns

Confirmed live that both bcftools (hard-errors) and vcf_validator (reports invalid) reject a header where
column 9 is `FORMAT` but no sample columns follow it.

**Decision:** structural rejection, matching both tools. Implemented in `VcfParser`'s header-parsing method.

### Empty sample name in `#CHROM` header

Confirmed live that both bcftools (`"[E::bcf_hdr_add_sample_len] Empty sample name..."`) and vcf_validator
report a file invalid for an empty sample column name, whether trailing or interior.

**Decision:** structural rejection, matching both tools. Implemented in `VcfParser`'s header-parsing method.

### CHROM contiguity / POS ordering within a CHROM block

VCFv4.2.tex line 179/180 says CHROM entries should form a contiguous block and POS should sort increasing
within a CHROM (repeated POS values are explicitly permitted). Confirmed that neither bcftools nor
vcf_validator treats a violation of either as rejection-worthy.

**Decision:** warn, don't throw — this doesn't cross this library's structural floor, and no reference tool
rejects it either. Implemented in `VcfParser.checkPositionOrdering`.

### Trailing blank line at EOF

`vcf-parser` rejects a trailing blank line as "Empty line"; vcf_validator reports the file valid. Confirmed
live that bcftools **also hard-errors** on this input (exit code 8, "Invalid contig name").

**Decision:** keep the rejection — it's the bcftools-consistent choice per the tie-breaker hierarchy, not an
unwarranted stricter-than-necessary reading of vcf_validator's more lenient verdict.

### BOM-prefixed file / `+`-prefixed POS

Both cross-checked directly against bcftools and/or vcf_validator during a full-codebase sweep; this
library's existing behavior (BOM-prefixed file rejected; a `+`-prefixed POS accepted) already matched both
reference tools in both cases, with no code change needed.

### ALT breakend contig-ID shorthand forms (`AC<ctg1>GT`, `<ctg1><ctg2>`)

One confirmed-valid form (a base run directly followed by a symbolic ID with no breakend bracket between
them, e.g. `T C<ctg1>`) is real (a prior attempt to tighten the regex broke it). Broader forms with no
precedent either way — bases before *and* after a symbolic ID, or multiple concatenated symbolic IDs — have
no spec text, no hts-specs fixture, and no decisive tool verdict: vcf_validator rejects *all* of them
(including the confirmed-valid one, so it can't discriminate), and bcftools accepts all of them but also
accepts outright invalid ALT text like `"XYZ123!!!"` with no complaint (it doesn't validate ALT syntax at
all, so its acceptance carries no evidentiary weight).

**Decision:** left intentionally permissive. Given no source can confirm or deny the broader forms, and a
previous narrowing attempt incorrectly broke the one form that *is* confirmed valid, this is not re-attempted
without new evidence. Implemented in `InternalUtils.sf_simpleAltPattern`.

## FILTER field

### FILTER value `"0"`

hts-specs' `failed_body_filter_004.vcf` documents `##CauseOfFailure=FILTER contains reserved value 0`.
Independently confirmed live via vcf_validator ("FILTER string must not be 0 (reserved value)"). bcftools is
not separately cited for this specific case in this codebase.

**Decision:** reject. Implemented in `VcfPosition.checkFilters`.

### `PASS` combined with other FILTER codes

VCFv4.2.tex line 185 only *implies* PASS is exclusive ("PASS if this position has passed all filters...
Otherwise... a semicolon-separated list of codes for filters that fail"); it never states this as a hard
rule. hts-specs' own `complexfile_passed_000.vcf` fixture combines PASS with another code
(`"STD_FILTER;PASS"`) and is expected to parse. Confirmed valid by vcf_validator and accepted silently by
bcftools.

**Decision:** warn (don't reject) in the parser's default lenient mode, matching the passing fixture and both
tools; still throw in strict mode or direct/programmatic construction, consistent with this library's general
strict-vs-lenient split. Implemented in `VcfPosition.checkFilters`.

### FILTER status (`PASSED`/`FAILED`/`NONE`) derivation

A boolean `m_filtersApplied` field, independent of the FILTER list's actual contents, could leave a position
that genuinely *failed* a filter (e.g. `FILTER=q10`) reporting `PASSED` after its filters were cleared via a
transformation pipeline. Confirmed this contradicts bcftools's own convention: `bcftools annotate -x FILTER`
converts every FILTER value, including a real failing code, to `.` when clearing it — never to `PASS`; a
literal empty FILTER string is likewise never silently promoted to `PASS` by bcftools.

**Decision:** removed the independent boolean; `FilterStatus` is now derived purely from `getFilters()`'s
contents every time (empty → `NONE`, exactly `["PASS"]` → `PASSED`, anything else → `FAILED`), matching
bcftools's clear-never-promotes-to-PASS convention. Implemented in `VcfPosition`.

### Duplicate ID/FILTER entries within one record

VCFv4.2.tex (target) is silent on duplicate values for both fields: ID (line 181) and FILTER (line 185) are
each described only as "(String, no whitespace or semicolons permitted)". VCFv4.3.tex adds the identical
clause to both: "duplicate values not allowed" (ID: line 338; FILTER: line 362), used here as VCFv4.2-silent
clarifying guidance. hts-specs' own `failed/` suite documents both exact cases:
`test/vcf/4.3/failed/failed_body_id_003.vcf` (`##CauseOfFailure=ID contains duplicate values in a single
line`) and `test/vcf/4.3/failed/failed_body_filter_004.vcf` (`##CauseOfFailure=FILTER contains duplicate
values`, literal `hp23;hp23`). Confirmed directly that both bcftools 1.23.1 and vcf_validator 0.10.2 silently
accept a duplicate value in either field.

**Decision:** warn (not throw) in lenient mode, and leave both entries in place -- no dedup -- matching this
library's general permissive stance for non-structural content. Neither field is looked up by key (both are
ordinary `List`s, unlike the duplicate-FORMAT-key case below, where a `Map`-based lookup would silently drop
all but one value) nor referenced positionally by any other field (unlike ALT, which GT indexes into by
position -- see `EMPTY_FIELD_HANDLING.md`'s ALT row), so a duplicate causes no data loss or misalignment
either way. This also differs from "Duplicate INFO key within one record" below, which warns-and-*dedupes*
specifically because bcftools itself dedupes there -- FILTER/ID get no such signal from bcftools, so there is
no basis to drop either copy. Strict-mode direct construction still throws for either field, matching this
library's usual construct-vs-parse dispatch shape (see `VALIDATION_PHILOSOPHY.md`).

ID's own duplicate check previously threw unconditionally, regardless of strict/lenient mode -- an older
decision made without this level of citation rigor (no bcftools/vcf_validator consultation, no data-loss/
indexing analysis on record). Revisited and aligned with FILTER's treatment here, since both fields share the
identical spec clause, identical hts-specs evidence shape, and the identical non-data-loss/non-indexed
characteristics that justify warn-and-keep over throw. Implemented in `VcfPosition.checkIds`/`checkFilters`.

## INFO/FORMAT sample value content

### INFO value containing a bare `=`

VCFv4.2's INFO grammar note ("no whitespace, semicolons, or equals-signs permitted") applies to each
key/value pair, the same convention already enforced for whitespace/`;`/`,` — so a bare `=` in a value (e.g.
`DP=1=2`) is technically non-compliant. Confirmed live that bcftools accepts this silently, with no warning
at all (`bcftools query -f '%INFO/DP'` on `DP=1=2` returns `1=2`, splitting on the first `=` only — the same
behavior this parser already had).

**Decision:** warn, don't throw, consistent with not being stricter than bcftools on input it demonstrably
tolerates. Implemented in `VcfPosition.checkInfoEntries`.

### Duplicate INFO key within one record

VCFv4.3 states outright that duplicate INFO keys (`DP=1;DP=2`) are not allowed, mirroring the existing FORMAT
rule. Confirmed live against bcftools before deciding how to treat it: `bcftools query -f '%INFO/DP'` on
`DP=1;DP=2` returns `1`, not `1,2` — bcftools keeps only the first occurrence rather than merging it into a
list.

**Decision:** warn (not throw), and keep only the first occurrence, matching bcftools exactly instead of
silently merging into what looked like a legitimate multi-value list. Implemented in `VcfPosition`.

### Negative/out-of-range values for reserved INFO keys `AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF`

These keys' declared `Long`/`Float` `Type` accepts a negative value with no exception; no separate range
check existed anywhere. Confirmed via vcf_validator against the relevant hts-specs fixtures:
`AC`/`AN`/`DP`/`END`/`MQ0`/`NS` must be non-negative integers; `AF` must lie in `[0, 1]` inclusive (both
bounds verified). bcftools is silent on all seven.

**Decision:** warn on violation (vcf_validator-sourced, since bcftools offers no signal either way).
Implemented in `InternalUtils.checkReservedInfoConstraints`, reachable from both the read path
(`VcfPosition.getInfo(ReservedInfoProperty)`) and `VcfWriter.validateInfoValue`.

### CIGAR grammar (SAM compliance)

No VCF spec version (checked 4.2 through 4.5 directly) ties INFO `CIGAR`'s grammar to the SAM specification
the way 4.3+ explicitly does for CHROM/contig names — every version just describes it in prose with no BNF
and no cross-reference. The only source connecting `CIGAR` to SAM's grammar is vcf_validator's own message
text ("value is not an alphanumeric string compliant with the SAM specification"), confirmed directly against
`failed_body_info_010.vcf` (illegal trailing characters) and `_011.vcf` (a decimal number, not
`(length, operation)` pairs). bcftools is silent on both.

**Decision:** warn on violation, cited as a vcf_validator-sourced convention, not overstated as
spec-mandated. Implemented in `InternalUtils.checkReservedInfoConstraints` (CIGAR-specific branch).

### `FORMAT="."` meaning no per-sample fields

VCFv4.2 through 4.5 are all silent on whether FORMAT can be `.` when sample columns are declared but a record
has no per-sample data — FORMAT is only described as required "if genotype information is present," with no
explicit statement either way for this exact case. Confirmed live against bcftools that it accepts a record
with `FORMAT=.` and correctly *interprets* it: `bcftools query -f '[%GT]'` returns the missing value for such
a sample regardless of that sample's own raw column content, and `bcftools view` round-trips such a sample as
`.` even given other input text there — a real semantic recognition, not passive pass-through.

**Decision:** accept, matching bcftools; a sample whose raw content isn't itself `.` warns (doesn't reject)
about content that will be discarded, since this parser prefers surfacing recoverable non-compliance over
silent, unannounced data loss. Implemented in `VcfPosition.checkFormat`/`VcfParser`.

### Undeclared `##FORMAT` `Number` attribute

`Number` is a spec-required `##FORMAT`/`##INFO` declaration attribute (VCFv4.2.tex line 66/97, restated
directly for FORMAT at VCFv4.4.tex line 187/VCFv4.5.tex line 185), but this library already tolerates a
declaration missing it (warns, doesn't reject), independent of this decision. Confirmed live that bcftools
treats a missing `Number` exactly like an explicit `Number=.` declaration
(`"A FORMAT field has no Number defined. Assuming '.'"`), uniformly regardless of `Type` — including
`Type=String`, where bcftools genuinely splits a comma-joined value into separate list elements rather than
keeping it as one opaque scalar string. vcf_validator rejects the file outright (consistent with its general
stricter-on-declarations stance, not treated as controlling here).

**Decision:** substitute `Number="."` whenever a non-reserved key's `##FORMAT` declaration has no declared
`Number`, matching bcftools exactly — every undeclared-`Number` value becomes list-shaped, not just a
genuinely multi-valued one. This changed a previously-tested contract (a `Type=String` value used to return
as one opaque scalar string). Implemented in `VcfSample.getProperty(VcfPosition, VcfMetadata, String)`.

### `CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G`

`CNP`/`CNL`'s own prose defines `Number=G` as "per potential copy number" — an application-defined dimension
unrelated to ALT allele count/ploidy, unlike `GT`/`GL`/`PL`/`GP`/`PP`'s ploidy-derived genotype-ordering
`Number=G`. A first fix attempt substituted `Number="."` for `CNP`, reasoning from an incorrectly-applied
"bcftools always wins" heuristic — but bcftools does not validate `Number=G` cardinality for *any* key
(confirmed by testing a deliberately-mismatched `PL` value, which bcftools accepted silently), so there was
no real tool disagreement to arbitrate in the first place, and the `"."` substitution broke
`FormatMetadata`'s ability to accept a correctly-declared `Number=G` `CNP` header.

**Decision:** keep `CNP`'s declared `Number="G"` (so a genuinely wrong declared `Number` is still caught), and
instead generalize the pre-existing `GLE`-only value-*cardinality*-check exemption
(`InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck`) to also cover `CNP`. The lesson generalizes:
bcftools's silence on a specific check is not evidence it endorses either side of a disagreement — verify
what the tool actually validates before treating its behavior as decisive.

### `rsidsOnly()` discard semantics

Not a VCF format ambiguity, but included since resolving it required the same bcftools consultation: this
library's own `VcfParser.Builder.rsidsOnly()` convenience pre-filter needed a definition for "discarded" that
a discarded line's own violations (out-of-order POS, malformed CHROM, etc.) shouldn't still be visible to.

**Decision:** confirmed against bcftools that "as if the record had never been in the file at all" (the same
effective outcome as `bcftools view` excluding a record) is the right mental model, even though bcftools's
own per-field strictness varies (a negative POS hard-fails bcftools regardless of ID-based filtering, while a
malformed CHROM or REF does not). Implemented by moving the discard decision in `VcfParser.parseNextLine`
before anything that inspects the record's specific content.

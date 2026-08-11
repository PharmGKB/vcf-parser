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
| Malformed attribute inside a metadata declaration's `<...>` list | Warn and drop just that attribute (matches bcftools; more lenient than two hts-specs `failed/` fixtures) | ✅ |
| Whitespace in `##FILTER`/`##SAMPLE` declaration IDs | Reject (bcftools's silence is a coverage gap) | ✅ |
| `##FORMAT`/`##INFO` ID grammar vs. `##FILTER`/`##SAMPLE`/`##ALT` | Stricter grammar for FORMAT/INFO only | ✅ |
| `"1000G"` legacy INFO tag-name exception | Allowed (matches bcftools) | ✅ |
| `##contig` ID character set | SAM reference-name rules; does not extend to INFO/FORMAT tag names | ✅ |
| Contig ID with a non-leading asterisk (hts-specs' `failed/` fixtures vs. VCFv4.3 errata) | Accept (matches bcftools; matches this library's existing implementation) | ✅ |
| `##contig` extra/unmodeled attributes | Allowed, no warning | ✅ |
| Optional metadata fields must be quoted (`VCFv4.3.tex:128`) vs. `##contig`'s own unquoted examples | No optional-field quoting check added; unquoted is valid | ✅ |
| Generic metadata property with an empty value | Drop (deliberately stricter than bcftools) | ✅ |
| `##pedigreeDB`/`##assembly` bracket-wrapped values colliding with attribute-list grammar | Reject when empty or no `=` inside the brackets; accept when it contains `=` | ✅ |
| A literal `%` in a metadata declaration ID | No code change; existing `##ALT`/`##FILTER`/`##SAMPLE` looser-grammar decision stands despite vcf_validator flagging `%` in `##FILTER`/`##SAMPLE` | ✅ |
| FORMAT declared but zero sample columns | Reject (both tools agree) | ✅ |
| Empty sample name in `#CHROM` header | Reject (both tools agree) | ✅ |
| CHROM contiguity / POS ordering within a CHROM block | Warn, don't reject (neither tool rejects it) | ✅ |
| Trailing blank line at EOF | Reject (matches bcftools, stricter than vcf_validator) | ✅ |
| BOM-prefixed file / `+`-prefixed POS | Existing behavior already matches both tools | ✅ |
| ALT breakend contig-ID shorthand (`AC<ctg1>GT`, `<ctg1><ctg2>`) | Left permissive; neither tool is decisive | ✅ (judgment call) |
| Reserved structural variant code re-used as a subtype (`DUP:INV`) | Reject (deliberately stricter than bcftools) | ✅ |
| FILTER value `"0"` | Reject (reserved value) | ✅ |
| `PASS` combined with other FILTER codes | Warn (lenient), throw (strict/direct construction) | ✅ |
| FILTER status (`PASSED`/`FAILED`/`NONE`) derivation | Derive purely from list contents, matching bcftools's clear-never-promotes-to-PASS convention | ✅ |
| Duplicate ID/FILTER entries within one record | Warn, keep both (no dedup) in lenient mode; throw in strict mode | ✅ |
| Percent-encoding field scope, and the eight-sequence limit | INFO/FORMAT/structured-attribute values only, not ID/FILTER/keys/IDs/ALT; decode only the eight tabled sequences | ✅ |
| INFO value containing a bare `=` | Warn, don't throw (matches bcftools) | ✅ |
| Duplicate INFO key within one record | Warn, keep first occurrence (matches bcftools) | ✅ |
| Negative/out-of-range values for reserved INFO keys `AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF` | Warn (vcf_validator-sourced; bcftools silent) | ✅ |
| CIGAR grammar (SAM compliance) | Warn on violation (vcf_validator-sourced; bcftools silent) | ✅ |
| `FORMAT="."` meaning no per-sample fields | Accept (matches bcftools) | ✅ |
| Data-line FORMAT key fails tag-name grammar | Warn (lenient), throw (strict/direct construction) -- matches bcftools | ✅ |
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

### Malformed attribute inside a metadata declaration's `<...>` list

A comma-delimited attribute that doesn't split into exactly one `key=value` pair (a stray/trailing/interior
comma producing an empty attribute, a bare token with no `=` at all, or an unquoted value that happens to
contain `=`) previously made `InternalUtils.extractProperties` rethrow unconditionally, aborting the entire
file parse over one malformed attribute in one declaration — contradicting `AGENTS.md`'s own Parser Validation
Policy, which lists metadata declarations among the fields this parser is lenient about. Confirmed live
against bcftools 1.23.1 (htslib 1.23.1): it never aborts the whole file for this — it drops the one malformed
*declaration* and continues (`[E::bcf_hdr_parse_line] Could not parse the header line`, exit 0); the
unquoted-`=`-in-value shape is accepted completely silently, no warning at all.

hts-specs' own conformance suite disagrees with unconditional leniency for one narrow sub-case: an unescaped
comma inside an unquoted ID, splitting it into two bogus top-level attributes.
`test/vcf/{4.1,4.2}/failed/failed_meta_contig_002.vcf` (`##CauseOfFailure=contig ID contains a comma`,
`##contig=<ID=1,A,length=123456>`) and `test/vcf/{4.1,4.2}/failed/failed_meta_alt_007.vcf`
(`##CauseOfFailure=The ALT ID contains a comma`, `##ALT=<ID=DEL:A,B,Description="Deletion with invalid
suffix">`) are both marked `failed/` for exactly this shape — both fixtures have a single data line, so
`HtsSpecsConformanceTest`'s `parser-throw-or-warn` check (which only requires *some* warning, not a specific
one, for a single-data-line fixture) accepts either a throw or a warn here and doesn't force a decision; no
`hts-specs-baseline.tsv` entry results either way.

**Decision:** side with bcftools over the fixtures for this sub-case: warn and drop just the one malformed
attribute (not the whole declaration — finer-grained than bcftools), continuing to parse the rest of the
declaration and the file. This project's leniency philosophy (`AGENTS.md`: "'Strict' in this project means
structural correctness and transparent diagnostics, not eager rejection of every semantic VCF violation")
treats an unquoted comma inside an ID as a semantic grammar defect, not the structural kind this library hard-
rejects for. Every downstream metadata constructor's lenient `(Map, VcfWarnings)` path already warns rather
than throws for a missing required attribute (e.g. `InfoMetadata.init()`'s `warnIfMissing(TYPE, ...)`), so a
declaration left short one attribute by this drop was already a supported, tested state before this decision.
Implemented in `InternalUtils.extractProperties`.

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

### Contig ID with a non-leading asterisk (hts-specs' `failed/` fixtures vs. VCFv4.3 errata)

VCFv4.3.tex's own "Changes to VCFv4.3" errata (lines 2136-2138, confirmed verbatim) states: "The characters
`\,"`'(){}` are now invalid in VCF contig names, while `*` is now valid when not the first character. (The
characters `[]<>` and initial `*`/`=` were already invalid and remain so.)" Both hts-specs'
`test/vcf/4.3/failed/failed_body_chrom_004.vcf` (`CHROM`=`chr*1`) and `failed_meta_contig_003.vcf`
(`##contig=<ID=1.*,length=123456>`) carry `##CauseOfFailure=contig ID contains illegal character` — disagreeing
with the spec's own prose for a non-leading `*` (via vcf_validator 0.10.2, the tool hts-specs itself uses to
generate this corpus).

**Decision:** bcftools is the tie-breaker per `AGENTS.md`. bcftools 1.23.1 accepts the non-leading-asterisk
forms silently — no warning on either `##contig=<ID=1.*>` or a `CHROM` of `chr*1`. For a **leading** asterisk
it emits `[W::bcf_hrec_check] Invalid contig name: "*chr"` but still exits 0 and preserves the declaration: it
warns rather than rejecting, so preservation there is not acceptance. This matches this library's own existing
implementation (`VcfPosition.checkChromosome`/`ContigMetadata`, which already permit `*` except as the first
character) — no production code change was required, only recording the ruling. Pinned by
`BcftoolsDependencyTest#testContigIdWithNonLeadingAsteriskBcftoolsAccepts` and
`#testContigIdWithLeadingAsteriskBcftoolsWarnsButPreserves`. See `HTS_SPECS_BASELINE_STATUS.md`'s "Contig ID
asterisk fixture" section for the two baseline rows this resolves.

### `##contig` extra/unmodeled attributes

VCFv4.2.tex line 123 says "extra fields can be included after the default fields," without naming `##contig`
specifically, but its own worked example (line 140) ends in `...`, the same "more tags may follow" signal
`##INFO`/`##FORMAT`/`##ALT`/`##SAMPLE` share. `ContigMetadata` was, at one point, the only class in the model
package still calling `ensureNoExtras()`, warning about attributes the others tolerate silently.

**Decision:** confirmed via hts-specs' own `passed_meta_contig.vcf` fixture (declares an unmodeled `accession`
attribute, expected to parse cleanly) and live bcftools testing (silently round-trips a custom contig
attribute with zero warning) that `##contig` should tolerate extra attributes the same as every other
declaration type. `ContigMetadata.ensureNoExtras()` call removed.

### Optional metadata fields must be quoted (`VCFv4.3.tex:128`) vs. `##contig`'s own unquoted examples

`VCFv4.3.tex:128` states, verbatim: *"The values of optional fields must be written as quoted strings, even
for numeric values."* Carried unchanged at `VCFv4.4.tex:128` and `VCFv4.5.tex:126`. Taken at face value, this
would make any unquoted optional-field value (`##INFO`'s `Source`/`Version`, `##contig`'s `assembly`/`md5`/
`taxonomy`/`URL`, or any unmodeled generic attribute) invalid.

But the same three specs call a `##contig`'s non-ID attributes "additional optional attributes"
(`VCFv4.3.tex:239`, `VCFv4.4.tex:253`, `VCFv4.5.tex:302` -- "The structured \texttt{contig} field must include
the ID attribute and can include additional optional attributes with the following ones reserved:") and then
show several of those same attributes unquoted in their own worked examples: `VCFv4.3.tex:49`
(`assembly=B36,md5=f126cdf8a6e0c7f379d618ff66beb2da,...,taxonomy=x`) and `:254`
(`URL=ftp://example.com/assembly.fa,md5=f126cdf8a6e0c7f379d618ff66beb2da,...`). Three consecutive spec
versions carry both the quoting rule and the contradicting unquoted examples unchanged.

**Resolved in favour of unquoted-is-valid by the conformance corpus.**
`test/vcf/4.3/passed/passed_meta_contig.vcf:7` declares `##fileformat=VCFv4.3` and contains
`##contig=<ID=contig_accession,species="Homo sapiens",accession=GCA_000001405.1>` -- `accession=
GCA_000001405.1` is an arbitrary, non-reserved optional attribute, unquoted, in a **passing** 4.3 fixture.
Confirmed directly: the 4.3 `failed/` set (224 fixtures) has zero entries about an unquoted optional field --
every quoting-related `##CauseOfFailure=` in that set (`failed_meta_001.vcf`, `failed_meta_006.vcf`,
`failed_meta_007.vcf`, `failed_meta_008.vcf`, `failed_meta_alt_003.vcf`, `failed_meta_info_002.vcf`) concerns
`Description`, which has its own explicit, unambiguous quoting rule at `VCFv4.3.tex:172`.

**Decision:** no optional-field quoting check is added -- it would warn on files hts-specs itself certifies as
valid. No per-version list of what counts as an "optional" field is needed either, since the contradiction is
resolved rather than narrowed. This is also the resolution behind `VALIDATION_PHILOSOPHY.md`'s raw-vs-unquoted
rule treating quoting as an encoding choice rather than a correctness signal: a metadata property's value can
be legitimately unquoted, so a public getter returning that value must return the same content whether or not
the source spelled it with quotes. Drafted as an upstream hts-specs issue in the untracked
`spec-contradictions.md` (never committed).

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

### A literal `%` in a metadata declaration ID (vcf_validator vs. hts-specs)

Task 4's `VcfPercentEncoding` scope investigation (see "Percent-encoding field scope" below) surfaced a
disagreement worth recording separately, since it's about metadata declaration IDs, not values. hts-specs' own
`test/vcf/4.3/passed/passed_meta_alt.vcf:9` declares an `##ALT` ID (`complexcustomcontig` followed by a long
run of non-alphanumeric punctuation, including a literal, un-encoded `%`) and expects it to parse as valid.
Confirmed live with vcf_validator 0.10.2 that it agrees for `##ALT` specifically (both the fixture and an
isolated `##ALT` ID containing only `%` validate clean), but rejects the identical bare `%` character when it
appears in an `##INFO`/`##FILTER` declaration ID instead ("Metadata ID contains a character different from
alphanumeric, dot, underscore, dash and plus") — a blanket structural-field-ID grammar with no exception for
`##ALT`. This matches `AGENTS.md`'s general note that vcf_validator is stricter than bcftools on structural
fields, but here it's inconsistent with itself across declaration types rather than just stricter uniformly,
and hts-specs has no fixture at all exercising `%` in a `##FILTER`/`##SAMPLE`/`##INFO` declaration ID to
confirm which side is right for those three.

**Decision:** no code change. This library's already-settled, bcftools-informed rule (see "`##FORMAT`/`##INFO`
declaration ID grammar is stricter than `##FILTER`/`##SAMPLE`/`##ALT`" above) already allows a bare `%` in a
`##FILTER`/`##SAMPLE`/`##ALT` ID via `IdMetadata`'s shared, looser whitespace/comma-only check, and rejects one
in `##FORMAT`/`##INFO` IDs via the stricter tag-name grammar — unchanged by this task. Recorded here so a
future agent who runs vcf_validator against a `%`-containing `##FILTER`/`##SAMPLE` ID and sees it flagged
"invalid" doesn't treat that as a fresh finding: vcf_validator's own inconsistency here, unsupported by any
hts-specs fixture for those three types, is not treated as controlling, per the tie-breaker hierarchy.

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

### Reserved structural variant code re-used as a subtype (e.g. `DUP:INV`)

`AltStructuralVariant`'s level-mismatch check (`level != type.getLevelInSpecification()`) rejects a reserved
code used at any level other than its own -- most obviously a level-1 subtype used bare at level 0 (e.g.
`TANDEM`, directly unsupported by VCFv4.2.tex lines 105/107-111's top-level list). But the same check also
rejects the *other* direction: a level-0 code re-used as a subtype under a different parent (e.g. `DUP:INV`,
`INV` being a level-0 reserved code, not a defined subtype of `DUP`). VCFv4.2.tex:114's "Reserved subtypes
include:" is explicitly non-exhaustive, and VCFv4.4.tex:221 ("Implementations are free to define their own
subtypes") confirms VCF doesn't reserve the space of valid subtypes at all -- nothing in the spec forbids a
level-0 code's name from also being used as a subtype string. Confirmed live against bcftools 1.23.1 (htslib
1.23.1): `##ALT=<ID=DUP:INV,Description="...">` is accepted silently, exit 0, no warning.

**Decision:** keep rejecting (deliberately stricter than bcftools) -- this is pre-existing, unchanged
behavior, not a new restriction. The level-mismatch check's real purpose is catching a bare reserved subtype
used at the wrong level (a plausible typo, e.g. writing `TANDEM` instead of `DUP:TANDEM`); a level-0 code
reused as a subtype is a sufficiently unusual construction that treating it as suspect is a reasonable,
conservative default even though the spec doesn't mandate it. Revisit if a real-world VCF is ever found using
this construction legitimately. Implemented in `AltStructuralVariant`'s constructor.

## FILTER field

### FILTER value `"0"`

hts-specs' `failed_body_filter_004.vcf` (4.1, 4.2 -- the 4.3 copy of this same filename documents an unrelated
cause, "FILTER contains duplicate values", see "Duplicate ID/FILTER entries within one record" below)
documents `##CauseOfFailure=FILTER contains reserved value 0`. Independently confirmed live via vcf_validator
("FILTER string must not be 0 (reserved value)"). bcftools is not separately cited for this specific case in
this codebase.

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

VCFv4.3.tex (target) states directly, for both fields, "duplicate values not allowed" (ID: line 338; FILTER:
line 362) -- plain conformance to the target, not guidance borrowed from a later version. This rule is applied
uniformly, including to VCFv4.1/4.2 files: VCFv4.2.tex is silent on duplicate values for both fields, describing
ID (line 181) and FILTER (line 185) each only as "(String, no whitespace or semicolons permitted)", which
predates the target's rule rather than conflicting with it. hts-specs' own `failed/` suite documents both exact
cases:
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

### Percent-encoding field scope, and the eight-sequence limit

Task 4 designed `VcfPercentEncoding`, an opt-in caller utility for the eight percent-encoded sequences
VCFv4.3.tex's Character Encoding section tables (`VCFv4.3.tex:83,86-95`: `%3A %3B %3D %25 %2C %0D %0A %09`).
Two questions had to be settled first: which fields it applies to, and whether it should decode *any*
well-formed `%`-plus-two-hex-digits sequence (general percent-decoding) or only those eight specific byte
values.

**Field scope (ID/FILTER prohibit vs. INFO/FORMAT permit):** VCFv4.3.tex's own fixed-field grammar notes treat
these two groups differently. ID's grammar note (line 338: "String, no whitespace or semicolons permitted")
and FILTER's (line 362, identical wording) both prohibit a literal semicolon outright, with no escape
mechanism mentioned anywhere in either field's description. INFO's grammar note (line 365), by contrast,
explicitly carves out percent-encoding as the escape mechanism for its own semicolon/equals-sign/comma
restriction: "characters with special meaning can be encoded using percent encoding, see Section
[Character encoding]". The "Changes between VCFv4.2 and VCFv4.3" errata (line 2152) extends the identical
statement to FORMAT (sample) values ("`;` in INFO, `:` in FORMAT, and `%` in both"). Confirmed live against
bcftools 1.23.1 as well, for both a data-line INFO value and a structured metadata declaration's own attribute
value (`##FILTER=<ID=q10,Description="q10;desc%3Btest">`): both round-trip an un-decoded `%3B` unchanged
through `bcftools view -h`/`query`, consistent with treating percent-encoding as meaningful there but never
as something bcftools itself acts on.

**Decision:** `VcfPercentEncoding` documents its own scope (INFO values, sample/FORMAT values, and structured
metadata declaration attribute values) as spec-derived, explicitly excluding ID, FILTER, FORMAT/INFO/
declaration keys and IDs, and symbolic ALT -- none of which permit a literal delimiter character in the first
place, so there's nothing for percent-encoding to escape there. The class cannot enforce this itself (it's a
pure `String` function with no way to know which field it was handed), so this is documentation, not a
runtime check.

**The eight-sequence limit:** hts-specs' own `test/vcf/4.3/passed/passed_body_info.vcf:63` contains
`MY3=0.777%03A-0.123%03B0.5%3D-0.987%0A;RSQ=0.4319` as a `passed` (valid) fixture. `%03A` is not `%3A` — it is
the three literal characters `%`, `0`, `3` followed by an ordinary `A`, since `%3A` in this exact position
would require the `03` two-character span to be a *disallowed* control character U+0003 (VCFv4.3.tex line 81
disallows U+0000–U+0008), which the fixture cannot possibly intend. A general percent-decoder recognizing any
well-formed `%`-plus-two-hex-digits span would misdecode `%03` as that disallowed control character and
corrupt this exact passing fixture.

**Decision:** `VcfPercentEncoding.decode` recognizes only the eight exact two-hex-digit codes the table lists
(`3A 3B 3D 25 2C 0D 0A 09`), matched case-insensitively; any other `%`-plus-two-characters span, however
well-formed as a general percent-encoding, is left as literal text. Confirmed this also matches bcftools
1.23.1: it percent-decodes nothing at all, anywhere (see `BCFTOOLS_VERSION_DEPENDENCIES.md`'s "Percent-encoding
is never decoded" entry), so there is no tool precedent for general percent-decoding to defer to either.

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

### Data-line FORMAT key fails tag-name grammar

`VcfPosition.checkFormat` previously threw `VcfFormatException` unconditionally when a data-line FORMAT key
failed `InternalUtils.FORMAT_PATTERN` (`^[A-Za-z_][0-9A-Za-z_.]*$`), with no lenient/`warnings`-gated path at
all -- the only check in that method with this shape, unlike the empty-sub-field-name check immediately above
it in the same method, or the identical grammar rule applied to a `##FORMAT` declaration ID in
`FormatMetadata.init()`, both of which warn under a lenient path (`AGENTS.md`'s Parser Validation Policy lists
FORMAT among the fields this parser is lenient about, and `VALIDATION_REFERENCE.md`'s strict-section
enumeration for FORMAT names only two floor items -- `GT` present but not first, and a non-empty key
duplicated -- deliberately not this grammar rule). Since `VcfParser`'s lenient path has no per-line
recovery, this meant a single non-standard FORMAT key anywhere in a file aborted parsing of the entire file,
discarding every record including ones already successfully parsed.

Confirmed live against bcftools 1.23.1: `bcftools view`/`bcftools query` against a file with a data-line
FORMAT key like `1KG` (starts with a digit, fails the pattern) only warns
(`[W::bcf_hrec_check] Invalid tag name: "1KG"`) and correctly parses and re-emits every record, including the
one with the offending key.

**Decision:** warn (not throw) under the lenient path, keeping the offending key as-is -- matching the
empty-sub-field-name precedent immediately above it in the same method, except that (unlike a blank ID,
which is always dropped before storage) a grammar-invalid key *can* still be looked up in metadata if a
matching, equally grammar-invalid `##FORMAT` declaration exists, since `FormatMetadata.init()` warns about
that same defect rather than dropping the declaration. Throw only under the strict (no-`warnings`)
construction path, consistent with every other non-structural content check `checkFormat` performs and with
bcftools's own behavior. Implemented in `VcfPosition.checkFormat`. Found by the `findings37.md` sweep.

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
(`InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck`) to also cover `CNP`. `GLE`'s exemption applies
only under VCFv4.2 and earlier (VCFv4.3 removed the key), while `CNP`'s — whose rationale is this entry's, not
`GLE`'s — is unconditional. The lesson generalizes: bcftools's silence on a specific check is not evidence it
endorses either side of a disagreement — verify what the tool actually validates before treating its behavior
as decisive.

**Update:** `CNL` was later found to have the same "per potential copy number" `Number=G` rationale as `CNP`,
once its own expected `Number` is made version-aware: VCFv4.2.tex line 308 declares it `Number=.`, while
VCFv4.3.tex line 678 (the target) redeclares it `Number=G` -- a real drift a prior pass had mischaracterized as
"unchanged" (see `ReservedFormatProperty.CopyNumberLikelihood`'s own comment for the full citation and
`FormatMetadataTest.testCnlReservedNumberIsVersionAware` for the regression coverage). `CNL` was added to the
same exemption set, gated the opposite way from `GLE`: it applies only under VCFv4.3 and later, since that is
the one version range where `CNL`'s own expected `Number` is actually `G` -- below that, its `Number` is `.`,
which the generic check already skips for that reason alone.

### `rsidsOnly()` discard semantics

Not a VCF format ambiguity, but included since resolving it required the same bcftools consultation: this
library's own `VcfParser.Builder.rsidsOnly()` convenience pre-filter needed a definition for "discarded" that
a discarded line's own violations (out-of-order POS, malformed CHROM, etc.) shouldn't still be visible to.

**Decision:** confirmed against bcftools that "as if the record had never been in the file at all" (the same
effective outcome as `bcftools view` excluding a record) is the right mental model, even though bcftools's
own per-field strictness varies (a negative POS hard-fails bcftools regardless of ID-based filtering, while a
malformed CHROM or REF does not). Implemented by moving the discard decision in `VcfParser.parseNextLine`
before anything that inspects the record's specific content.

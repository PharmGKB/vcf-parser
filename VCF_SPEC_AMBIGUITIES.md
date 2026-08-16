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

- Duplicate metadata declaration IDs (`##contig`/`##INFO`/`##FORMAT`/`##FILTER`/`##ALT`/`##SAMPLE`/`##META`/`##PEDIGREE`)
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
| Duplicate attribute within one metadata declaration | Keep first (library choice; diverges from bcftools for a non-`ID` attribute) | ✅ |
| Malformed attribute inside a metadata declaration's `<...>` list | Warn and drop just that attribute (matches bcftools; more lenient than two hts-specs `failed/` fixtures) -- an unquoted `=` in a value is no longer one of the malformed shapes this covers; see below | ✅ |
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
| Metadata attribute NAME grammar (parse and write warn, construction throws) | `^[A-Za-z_][0-9A-Za-z_.]*$` (matches bcftools); warn at parse time, throw at construction, warn at `validateBeforeWrite()` | ✅ |
| Unquoted metadata attribute value: malformed bracket vs. `<`/`>`/`;` | Throw (both modes) for an unquoted comma or malformed bracket value; warn at `validateBeforeWrite()` only for `<`/`>`/`"`/space; no check for `;` (bcftools gives it no special meaning here) -- whether a `,` *inside* an unquoted `<...>` span still ends the attribute (it does) is a separate question, see "Angle brackets are not a quoting form in a metadata attribute value" below | ✅ |
| Angle brackets are not a quoting form in a metadata attribute value | Comma splitting unchanged; unlike htslib's `<`/`>` nesting counter, a value needing `,`/`<`/`>` must be double-quoted (only `[...]` is a comma-separation exception) | ✅ |
| Generalizing `[...]` beyond `##META`'s `Values` to any attribute | Scope acceptance to `##META`'s `Values` only, in both parsing and construction (maintainer choice on a genuine tie -- the spec's one narrow example is silence, not prohibition, but bcftools' generalization is not followed); the fatal unterminated-`[` floor stays universal regardless | ✅ |
| `##META` attribute order | Not checked, for ANY keyed declaration type (bcftools ignores ordering universally; vcf_validator rejects the spec's own worked example, disqualifying its verdict) | ✅ |
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
| Negative/out-of-range values for reserved INFO keys `AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF` | Warn in lenient mode, throw in strict mode (vcf_validator-sourced; bcftools silent) | ✅ |
| CIGAR grammar (SAM compliance) | Warn in lenient mode, throw in strict mode (vcf_validator-sourced; bcftools silent) | ✅ |
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
way, and no VCF version (4.2-4.5) contemplates a repeated attribute name within one declaration either. There
is therefore no spec to defer to, and this is a library choice rather than a spec question.

**Decision:** keep the first value, with a warning — the same shape, applied one level down, as
`AGENTS.md:118`'s duplicate-declaration-ID rule ("warns and keeps the first declaration... never
keep-last/overwrite"). Implemented in `InternalUtils.foldAttributes`, shared by `extractPropertiesFromLine`
(real parsing) and `extractProperties` (the test-facing array-based entry point).

This is a deliberate, documented divergence from bcftools rather than an oversight: confirmed live against
bcftools 1.23.1 by forcing conversion to binary BCF (which enforces the declared type, unlike plain-text VCF,
making the survivor observable), declaring `Type` twice resolves to the **last**-declared value regardless of
which type came first, while declaring `ID` twice resolves to the **first**-declared value (control-verified
against the same mechanism). Per `AGENTS.md`'s Spec Ambiguity Decision Hierarchy, declining to adopt a
measured tool behavior must be written down rather than left implicit — this entry is that record. vcf_validator
rejects the `Type`-duplicate case outright, but per the tie-breaker hierarchy its stricter structural-field
reading is non-authoritative here regardless.

### Malformed attribute inside a metadata declaration's `<...>` list

A comma-delimited attribute that doesn't split into exactly one `key=value` pair (a stray/trailing/interior
comma producing an empty attribute, or a bare token with no `=` at all) previously made
`InternalUtils.extractProperties` rethrow unconditionally, aborting the entire file parse over one malformed
attribute in one declaration — contradicting `AGENTS.md`'s own Parser Validation Policy, which lists metadata
declarations among the fields this parser is lenient about. Confirmed live against bcftools 1.23.1 (htslib
1.23.1): it never aborts the whole file for this — it drops the one malformed *declaration* and continues
(`[E::bcf_hdr_parse_line] Could not parse the header line`, exit 0).

**Update (attribute-list tokenizer):** a third shape used to belong in this same paragraph -- an unquoted
value that happens to contain `=` -- but no longer does, since the retired two-pass `splitTopLevel`/
`splitProperty` pair (which found *every* top-level `=` and rejected the whole attribute unless there were
exactly two) was replaced by `InternalUtils.tokenizeAttributeList`/`parseAttribute`, whose name scan stops at
the *first* `=` only, so a second one is ordinary value content. This is not a reversal of the decision below;
it makes this shape's behavior match bcftools *more* closely, not less: bcftools already accepted this shape
"completely silently, no warning at all" (as this entry stated even before this change), and this shape now
does too -- it parses cleanly, with no warning, rather than warning and dropping. Pinned by
`InternalUtilsTest#testExtractPropertiesTreatsEmbeddedEqualsSignInValueAsOrdinaryContent` and
`AttributeListTokenizerTest#anEqualsSignInAValueIsOrdinary`.

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
Implemented in `InternalUtils.tokenizeAttributeList`/`parseAttribute` (via the shared `parseOneAttribute`) for
real parsing; `InternalUtils.extractProperties` is now only the test-facing array-based entry point sharing
the same underlying grammar.

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
`failed_meta_007.vcf`, `failed_meta_008.vcf`, `failed_meta_alt_003.vcf`, `failed_meta_info_002.vcf`,
`failed_meta_format_002.vcf`) concerns `Description`, which has its own explicit, unambiguous quoting rule at
`VCFv4.3.tex:172`.

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
implementation pointer live in `EMPTY_FIELD_HANDLING.md`'s Row 23 ("Generic metadata property").

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

### Metadata attribute NAME grammar (parse and write warn, construction throws)

`VCFv4.3.tex` line 117 states a structured declaration's value is "a comma-separated list of key=value pairs,
enclosed within `<' and `>' characters" but never gives `key` (the attribute NAME) a character-set grammar of
its own — silent, identically in 4.2/4.4/4.5. Per `AGENTS.md`'s Decision Hierarchy, bcftools governs. htslib
1.23.1 `vcf.c:695` carries the exact grammar as a comment directly above its own name-scanning code —
`^[A-Za-z_][0-9A-Za-z_.]*$` — confirmed by reading the scanner immediately below the comment, which walks
`isalpha_c(*q) || *q=='_'` for the first character and `isalnum_c(*q) || *q=='_' || *q=='.'` for the rest
(`vcf.c:696-699`). Confirmed live: `##contig=<ID=1,a-b=v,length=100>` makes bcftools discard the WHOLE
declaration (`[E::bcf_hdr_parse_line] Could not parse the header line`) while the file itself stays readable
(exit 0, every other declaration and every data line still emitted) — the exact `AGENTS.md` worked example for
carried-forward content.

**Decision:** `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN` enforces this same regex at all three stages. The
parse path (`InternalUtils.foldAttributes`'s post-fold loop, once per surviving key rather than once per parsed
attribute — a repeated invalid name is one bad name, and the fold loop's own duplicate-attribute warning already
reports the repetition) warns and preserves the value, per the Parser Validation Policy's "warn, never drop"
rule for carried-forward content. Construction (`InternalUtils.checkMetadataPropertyEncoding`, all 27
`VcfMetadata.Builder` call sites) throws for a name outside it, since originated content must never reach the
header in a form bcftools silently discards. `validateBeforeWrite()`'s counterpart
(`checkMetadataPropertyEncodingForWrite`) only warns, since the file stays readable and this route's content may
be carried-forward (parsed, not originated) rather than originated by the write call itself. Tests:
`MetadataPropertyEncodingTest#constructionRejectsANameOutsideTheAllowlist`/
`#constructionAcceptsSpecConformantNames`/`#aNameOutsideTheAllowlistSurvivesParsing`,
`InternalUtilsTest#anInvalidAttributeNameWarnsAtParseTimeAndIsStillStored`/
`#aRepeatedInvalidNameWarnsOnceNotOncePerOccurrence`,
`BcftoolsDependencyTest#testMetadataAttributeNameOutsideAllowlistBcftoolsDropsDeclarationSilently`.

### Unquoted metadata attribute value: malformed bracket vs. `<`/`>`/`;`

Once a value isn't quoted (`"..."`/`[...]`), htslib 1.23.1's unquoted-value scan (`vcf.c:730-733`, inside
`bcf_hdr_parse_line`) checks exactly three characters: `<` increments its `nopen` bracket-nesting counter, `>`
decrements it (closing the whole declaration early if it reaches zero), and `,` ends the value only while
`nopen==1`. No other character — including `;` — is special to it at all.

Confirmed live (bcftools 1.23.1):
- An unescaped `<` (`##contig=<ID=1,description=a<b,length=100>`) makes the scan look past this declaration's
  own closing `>` for a nonexistent match, producing `[W::bcf_hdr_parse_line] Incomplete header line, trying
  to proceed anyway` — file stays readable (exit 0), declaration garbled but not lost.
- An unescaped `>` (`##contig=<ID=1,description=a>b,length=100>`) closes the declaration early, producing
  `[W::bcf_hdr_parse_line] Dropped trailing junk from header line ...` and truncating the declaration to
  `##contig=<ID=1,description=a>` — file stays readable, `length` silently lost.
- An unquoted `,` still truncates the value at the comma (unchanged from before this task).
- A malformed bracket value has two distinguishable but equally lossy sub-shapes: `[a=b` (no closing `]` at
  all) makes the WHOLE FILE unreadable (`[E::bcf_hdr_parse_line] Missing ']' in header line`, exit 255);
  `[a]b]`/`[a] junk` (closes, but has non-comma junk before the next attribute) makes bcftools drop just the
  one declaration, file otherwise readable (exit 0, same outcome as the name-allowlist entry above).
- An unescaped `;` (`##contig=<ID=1,length=100,description=a;b>`) round-trips completely unchanged, with no
  warning at all — confirming there is no bcftools leniency around `;` in this grammar to replicate. (This is
  unrelated to the very different, and unaffected, `;`-separated-codes grammar this library enforces elsewhere
  for reserved `FORMAT`/`FT` and the `FILTER` column — see `InternalUtils.checkFilterLikeValue` — a different
  field's own value grammar, not this declaration-attribute-list grammar; it must not be mistaken for
  precedent here.)

**Decision:** `InternalUtils.findEncodingProblems` sorts these into `EncodingProblem.unreadableOutput=true`
(throws at both construction and `validateBeforeWrite()`) or `=false` (throws at construction only; warns at
`validateBeforeWrite()`) -- but `unreadableOutput=true` is not one reason, it is three (the classifier's own
Javadoc says so explicitly), and only one earns it on file-readability grounds; the other two are about a
different kind of unreadability -- this library's own re-parse, not bcftools':
- **The unterminated bracket** throws UNCONDITIONALLY -- on any attribute of any declaration type, not just
  `##META`'s `Values` -- because the file genuinely becomes unreadable (exit 255), the absolute floor a warn
  branch would violate. This check runs BEFORE the `Values`-only gate for exactly that reason: bcftools' own
  generic bracket handling is unaffected by this library's narrower quoting-acceptance scope (see "General
  bracket-list quoting beyond `##META`'s `Values`" below), so the floor stays universal even though acceptance
  of a well-formed bracket value does not.
- **The unquoted comma** throws for a weaker, different reason: measured, it only makes bcftools discard the
  one declaration (exit 0) -- the SAME outcome the invalid-name case below only warns for -- but the
  attribute-list tokenizer can never produce a stored value containing an unquoted comma (it always
  terminates at the first one), so throwing here harms no carried-forward content and costs nothing.
- **The bracket-with-trailing-junk sub-shape** (`[a]b]`) throws for the same reason, but this classification
  now applies ONLY on `##META`'s `Values` (`InternalUtils.isWellFormedMetaValuesBracket`): outside `Values`, a
  leading `[` that clears the universal unterminated-floor above (i.e. has a `]` somewhere) is not
  bracket-checked at all -- it falls through to the generic "unquoted structural delimiter" check instead
  (`unreadableOutput=false`), since bracket grammar no longer applies to it either way.
- **A malformed quoted value (leading `"`, no valid closing `"`) that also contains a comma** (e.g.
  `x="abc,def`) throws for a reason specific to THIS library, not bcftools: `parseOneAttribute`'s own
  recovery for a malformed quoted value (its `close<0` branch) re-scans for the next COMMA rather than the
  value's own end, so emitting `x="abc,def` verbatim would re-parse to just `x="abc`, with `def` silently
  dropped (it has no `=` of its own) -- genuinely unreadable by THIS library's own tokenizer, independent of
  what bcftools would do with it. The comma-free sibling (`x="ab`, no comma at all) is real too but is
  `unreadableOutput=false`: bcftools itself does not hard-fail on an unterminated leading quote, only warns
  and mangles the declaration, and this library's own re-parse recovers it losslessly since there is no comma
  for the recovery scan to stop at early.
- `unreadableOutput=false` (throws at construction only; warns at `validateBeforeWrite()`): an invalid
  attribute name, an unescaped `<`/`>`, an unescaped `"`, leading/trailing space, a malformed quoted value with
  no comma (above), and (outside `##META`'s `Values`) a leading `[` that isn't dangling -- each of these DOES
  leave the file readable and re-parseable, and the tokenizer CAN produce some of them on the parse path, so
  warning is the only option that does not regress carried-forward content.
- No check at all: `;` — bcftools gives it no special meaning in this grammar, and neither does this library.

Tests: `MetadataPropertyEncodingTest#constructionRejectsABracketValueOutsideMetaValues`/
`#constructionAcceptsAWellFormedBracketValueOnMetaValuesOnly`/
`#constructionRejectsAMalformedBracketValueOnMetaValues`/
`#constructionRejectsAngleBracketsAndLayoutWhitespaceInALiteralValue`/`#constructionAcceptsAnEqualsSignInAValue`/
`#diagnosticsModeThrowsForAMalformedQuotedValueContainingAComma`,
`BcftoolsDependencyTest#testUnterminatedBracketValueBcftoolsHardErrors`/
`#testBracketValueWithTrailingJunkBcftoolsDropsDeclarationSilently`/
`#testUnescapedLessThanInMetadataValueBcftoolsMisparsesButSurvives`/
`#testUnescapedGreaterThanInMetadataValueBcftoolsTruncatesDeclaration`/
`#testUnescapedSemicolonInMetadataValueBcftoolsTreatsAsOrdinaryContent`.

### Angle brackets are not a quoting form in a metadata attribute value

`InternalUtils.tokenizeAttributeList`/`parseAttribute` (via the shared `parseOneAttribute`) split a metadata
declaration's attribute list on every raw, unquoted comma. htslib 1.23.1 instead keeps an angle-bracket
nesting counter (`~/Dev/htslib/vcf.c:730-733`, `if ( *q=='<' ) nopen++;` ... `if ( *q==',' && nopen==1 )
break;`), so it reads `Description=A<1,2>B` as one value where this library truncates it to `A<1`.

**For a non-`ID` value the target spec is silent, not prohibitive** -- the argument rests on that silence, not
on a prohibition:

- `VCFv4.3.tex:117` -- "A *structured* meta-information line is similar, but the value is itself a
  comma-separated list of key=value pairs, enclosed within `<' and `>' characters". `,` is the separator with
  no stated exception, so under the spec's own grammar `Extra=A<1,2>B` is two list entries, the second having
  no `=`.
- `VCFv4.3.tex:173` -- "Double-quote character must be escaped with backslash $\backslash$ and backslash as
  $\backslash\backslash$" -- the only escaping mechanism the target spec states. (`VCFv4.2.tex:81` says
  double-quote "**can** be escaped", not "must"; do not group the two under one quote.)
- Scoped, usable only where they apply: `VCFv4.3.tex:200` ("ID values are case sensitive strings and must not
  contain whitespace, commas or angle brackets") governs the **`##ALT` ID** specifically (scope set by the
  "Alternative allele field format" section heading at `:192`, restated `:354`, "no whitespace, commas, or
  angle-brackets are permitted in the ID String itself"); `VCFv4.2.tex:105` states the same ALT-ID rule but
  omits "commas", which 4.3 added; `VCFv4.3.tex:265` ("In particular, excluding commas facilitates parsing
  `##contig` lines, and excluding the characters `<>[]` and initial `*` avoids clashes with symbolic
  alleles") is the rationale for the **contig-name character class**, an identifier charset, not a general
  attribute-value rule.
- hts-specs: `test/vcf/4.3/failed/failed_meta_alt_008.vcf` (`##CauseOfFailure=The ALT ID contains an opening
  angle bracket`) and `failed_meta_alt_009.vcf` (`##CauseOfFailure=The ALT ID contains a closing angle
  bracket`) -- also `##ALT` ID findings, not general attribute-value findings.

**The one comma-separation exception the spec evidences is `[...]`**: `VCFv4.3.tex:273`'s
`##META=<ID=Assay,Type=String,Number=.,Values=[WholeGenome, Exome]>` carries a comma *inside* brackets, and
`test/vcf/4.3/passed/passed_meta_meta.vcf` contains the same form in a `passed/` fixture. This library
implements that exception (`parseOneAttribute`'s bracket branch) and not the angle-bracket one, which the spec
evidences nowhere.

**Decision:** comma splitting is unchanged. A value that must contain `,`, `<`, or `>` has to be double-quoted,
which already round-trips byte-for-byte and silently on both write paths. Implemented (or rather, deliberately
NOT implemented) via `InternalUtils.parseOneAttribute`, whose Javadoc now states the divergence explicitly so
it is not "fixed" back to match htslib.

**Accepted cost, both directions.** On some malformed input bcftools destroys the declaration's `Number`/`Type`
and we do not; on other malformed input bcftools is silent and correct and **we** produce the worse reading.
Earlier drafts asserted only the favourable direction.

**Evidence (descriptive, not the decision basis).** This table records interop behavior for future readers. It
is **not** what the Decision rests on -- that is `VCFv4.3.tex:117`. Byte-for-byte re-emission is not evidence
about splitting; the observable is a typed read. Tools: **bcftools 1.23.1 / htslib 1.23.1** (grammar is
version-independent -- `bcf_hdr_parse_line` never reads `##fileformat`), **vcf_validator 0.10.2** (4.3 grammar;
neither known 0.10.2 defect is in range). All four spec columns (4.2-4.5) read `silent` for every row -- the
most important cells here, since they are why the argument runs from `:117` rather than from a prohibition.

- **Shape A** (bcftools): `##INFO=<ID=X,Extra=«suspect»,Number=1,Type=Integer,Description="d">`, record
  `1 100 . A G . . X=abc`, `bcftools view <file>`. If later attributes parsed, `Type` registers and `X=abc`
  coerces to `X=.`; if swallowed, "no Type defined. Assuming String" and `abc` passes.
- **Shape B** (vcf_validator): suspect placed **last**,
  `##INFO=<ID=X,Number=1,Type=Integer,Description="d",Extra=«suspect»>`, record `1 100 . A G . . X=1` (**not**
  `X=abc` -- that payload is itself a Type violation and makes every cell, control included, read "not valid"),
  plus a `##reference` line; invoked as `vcf_validator -i <file>`.

| `«suspect»` | bcftools 1.23.1 (A) | vcf_validator 0.10.2 (B) | this library |
|---|---|---|---|
| *(control: no `Extra`)* | `X=.` | valid | Type registered |
| `Extra=plain` *(control B)* | `X=.` | **not valid** — so B's verdicts are about quoting, not brackets | preserved |
| `"A<1,2>B"` — conforming | `X=.` | **valid** | intact, byte-for-byte, silent |
| `A<1,2>B` | `X=.`; one value | not valid | truncated to `A<1` |
| `A<1,"2">B` | `X=.`; one value | not valid | truncated to `A<1` |
| `A<1,[2>B` | `X=.`; one value | not valid | truncated to `A<1` |
| `a<b` | "no Type defined"; `X=abc`; doubled `>` emitted | not valid | all attributes preserved |
| `a>b` | truncates to `##INFO=<ID=X,Extra=a>`, losing `Number`/`Type`/`Description`; also `X=abc` + "no Type defined" | not valid | all attributes preserved |
| `a<b,…,Description=">"` | "no Type defined"; `X=abc` | not valid | all attributes preserved |

No new `bcftoolsDependencyTest` case is added for this table: it is purely descriptive, and this Decision
depends on `VCFv4.3.tex:117`, not on bcftools.

### General bracket-list quoting beyond `##META`'s `Values`

`InternalUtils.findEncodingProblems`'s bracket-well-formedness check (shared by construction and
`validateBeforeWrite()`) used to accept a well-formed `[...]` value for *any* attribute of *any* declaration
type -- not just `##META`'s `Values`, and so did the attribute-list tokenizer at parse time. TODO.md raised
whether the amended "if still in doubt is a precondition" rule (see the angle-bracket entry above) flips this:
since the spec only ever shows `[...]` for one specific attribute, does accepting it more broadly need its own
justification, the way angle brackets did?

**This is a genuine tie, not a rerun of the angle-bracket decision -- and a maintainer judgment call, not a
forced conclusion.** Angle brackets had two pieces of forbidding evidence: `VCFv4.3.tex:200`/`VCFv4.2.tex:105`
exclude `<`/`>` from an `##ALT` ID specifically, and two `failed/*.vcf` hts-specs fixtures
(`failed_meta_alt_008/009.vcf`) reject them directly -- so accepting htslib's generalization there really would
mean implementing a construct the spec forbids; there was no tie to break. Square brackets have neither.
`VCFv4.3.tex:265`'s "excluding the characters `<>[]`... avoids clashes with symbolic alleles" (the same
sentence verbatim at `VCFv4.4.tex:279`/`VCFv4.5.tex:328`) looks similar at first glance, but read in its full
context (`VCFv4.3.tex:250-266`, under "Contig names follow the same rules as the SAM format's reference
sequence names") it governs the **contig-name character set** -- what characters may appear inside a contig's
`ID` -- not whether `[...]` may wrap an attribute's *value*. No `failed/*.vcf` fixture rejects a
`[...]`-wrapped value on a non-`META` attribute either -- confirmed by grepping the entire conformance corpus
(`test/vcf/{4.1,4.2,4.3,4.5}/{passed,failed}/*.vcf`; hts-specs publishes no 4.4 fixture directory) for `[`.
htslib's own bracket handling (`~/Dev/htslib/vcf.c:684-757`, `bcf_hdr_parse_line`) is attribute/declaration-
type-agnostic, exactly like its angle-bracket `nopen` counter -- confirmed live, not just from source: nesting
a duplicate `Type=String` inside a *different* `##INFO` attribute's bracketed value and querying the resulting
typed value showed bcftools 1.23.1 protects the interior comma there too, regardless of attribute name or
declaration type (a plain `view -h` header echo cannot show this -- pure text passthrough -- so this used the
same type-coercion technique the "Duplicate attribute within one metadata declaration" entry above describes).
So the spec's posture toward the general form genuinely is **silence, not prohibition** -- one narrow positive
example, nothing forbidding it elsewhere -- and that silence is a tie bcftools is entitled to break, per
`AGENTS.md`'s amended text.

**Decision: take the narrower reading anyway.** Construction, `validateBeforeWrite()`, and parsing all scope
bracket-list *acceptance* to `##META`'s `Values` specifically -- the one place any VCF version (4.3-4.5)
actually evidences the form -- rather than following bcftools' generalization. This is a deliberate choice to
track the spec's literal evidence rather than htslib's convenience, made explicitly because the tie exists
(nothing here claims the spec forbids the general form): `Values` staying otherwise completely opaque
(`MetaMetadata`'s own Javadoc) already makes it the one attribute this codebase gives any special value-form
recognition to at all, and extending that recognition to every attribute of every declaration type -- for a
form the spec itself never shows anywhere else -- was judged to buy nothing but an unevidenced dependency on
htslib's own convenience. A leading `[` on any other attribute now gets exactly the same treatment `<`/`>`
already get: ordinary content, warned about, never protecting an interior comma (`InternalUtils.
parseOneAttribute`'s `isMetaValues` gate; `findEncodingProblems`'s identical gate on its bracket branch).
Within `Values` itself, the bracket scan has no escape mechanism (the spec defines none for `[`/`]`, unlike
double-quote's backslash escaping) -- the closing `]` is found by a plain, unescaped scan, exactly the way
`VcfParser.removeAngleBrackets` finds the declaration's own outer `<`/`>` by plain byte matching, not a
quoting scan.

**One fatal-vs-non-fatal asymmetry survives the narrowing, and stays universal rather than being re-scoped
to `Values`.** Every measured `<`/`>` mishandling shape stays at bcftools exit 0 (corrupted but readable) --
none are fatal. An unterminated `[` uniquely is (measured: `[E::bcf_hdr_parse_line] Missing ']' in header
line`, exit 255), and that fact is unaffected by this library's own acceptance-scoping decision: bcftools'
bracket handling still treats *any* leading `[` as opening a quoted span regardless of which attribute or
declaration type carries it. So `InternalUtils.findEncodingProblems`'s "no `]` anywhere" check stays
unconditional -- construction refuses to originate an unterminated `[` on any attribute, not just `Values`.

**A real bug, caught by external adversarial review after this decision first shipped: the PARSE side also
had to close this gap, not just construction.** The first cut of this decision treated a non-`Values`
unterminated `[` exactly like `<` at parse time too (warned, preserved verbatim, no defensive rewrite) --
reasoning that the risk was narrow, since it seemed to require deliberately malformed input. That reasoning
was wrong: splitting at the comma (since `[` doesn't protect it outside `Values`) can turn a **well-formed**
bracket value into a dangling one purely as a side effect of truncation. `##contig=<ID=c1,x=[a, b],
length=999>` is perfectly valid input, but truncating `x`'s value at its interior comma yields `[a` -- no `]`
anywhere -- and writing that back out raw manufactures `##contig=<ID=c1,x=[a,length=999>`, which bcftools
cannot read at all (confirmed live: exit 255). That is not carrying forward already-malformed content; it is
this library's own splitting *creating* new fatal content from safe input, which the absolute floor on
originated output does not distinguish from originated-and-fatal. `InternalUtils.parseOneAttribute`'s literal
branch now re-quotes (`InternalUtils.quote`) whenever the stored value dangles a `[` with no `]` anywhere,
regardless of whether that happened via truncation or because the input was already unterminated -- the two
are indistinguishable once stored, so one check covers both, and for a **non-`Values`** attribute the
tokenizer once again can never produce a bare, unquoted, unterminated `[` as a stored value (the only route
left is the package-private `BaseMetadata#trustedMutableProperties()` bypass, per `MetadataPropertyEncodingTest
#diagnosticsModeStillThrowsWhenOutputWouldBeUnreadable`'s own comment -- that test, like this paragraph, is
about the non-`Values` shape; see the next paragraph for why `Values` itself is exempt from this same
re-quote). bcftools failing on this shape at all is arguably its own defect -- filed upstream at
[samtools/htslib#2065](https://github.com/samtools/htslib/issues/2065) -- but that is why this library
defends against the shape rather than a reason to leave it unhandled: the fix costs nothing (parsing still
does not protect the comma, matching the design decision), and closes a hole neither the original design
discussion nor its first implementation had actually scoped correctly. See `BCFTOOLS_VERSION_DEPENDENCIES.md`'s
"Unterminated bracket value in a metadata attribute" entry for the write-path verification.

**`##META`'s `Values` itself deliberately does NOT get this same re-quote, and that is a distinct decision,
not a gap in the one above.** `InternalUtils.parseOneAttribute`'s `isMetaValues` branch finds `Values`'s
closing `]` with a plain `s.indexOf(']', ...)` over the ENTIRE remainder of the attribute list, not bounded by
the next comma (`AttributeListTokenizerTest#aMetaValuesClosingBracketIsSearchedForAcrossTheWholeRemainder`) --
deliberately, so the spec's own worked example (`VCFv4.3.tex:273-276`, e.g. `Values=[WholeGenome, Exome]`,
whose list has interior commas) is never misread as unterminated. Because that scan is comma-unbounded, this
library's own splitting can never turn a well-formed `Values` value into a dangling one the way it can outside
`Values` -- the failure mode the re-quote above exists to close simply cannot arise here. Combined with
`findEncodingProblems`'s unconditional, ungated "no `]` anywhere" check (confirmed: `addMeta(Map.of("ID", "x",
"Values", "[a"))` throws `"META metadata property Values has an unterminated bracket value bcftools cannot
read"` even under a lenient context), the only way `parseOneAttribute`'s `Values` branch is ever reached with
no closing `]` at all is if the ORIGINAL file's `Values` value already had none anywhere in the rest of the
line -- this library's parsing can only observe that state, never create it. bcftools would already exit
fatally reading that same original file, before this library was ever involved, so re-emitting the value as-is
on the default write path is a parse-only, purely carried-forward artifact of someone else's malformed file
(the originated-vs-carried-forward distinction `AGENTS.md` states), not a case of this library manufacturing
new corruption the way the pre-fix non-`Values` case did. That bcftools fails fatally on an unterminated `[`
at all is the same filed defect, [samtools/htslib#2065](https://github.com/samtools/htslib/issues/2065) --
which is exactly why this library does not additionally paper over it here the way it does for the genuinely
self-inflicted non-`Values` case above.

Pinned by `BcftoolsDependencyTest#testUnterminatedBracketValueBcftoolsHardErrors` (pins bcftools' own behavior
on this byte sequence, independent of this library), `#testBracketValueWithTrailingJunkBcftoolsDropsDeclarationSilently`,
and `#testNonMetaValuesBracketTruncationDoesNotManufactureAnUnreadableHeader` (the regression test -- parses
and writes the well-formed-input example above through this library's own pipeline, then pipes the actual
output through bcftools to prove it stays readable). Also
`AttributeListTokenizerTest#bracketValueDoesNotProtectCommasElsewhereButStaysBcftoolsSafe` and
`WriterRoundTripTest#testNonMetaValuesBracketTruncationStaysBcftoolsSafeOnWrite`.

### `##META` attribute order

`MetaMetadata`'s constructor was given a `BaseMetadata.checkAttributeOrder()` call by analogy with
`ContigMetadata` (the other direct `IdMetadata` subclass), rather than by a decision this file recorded. Once
`##META` was routed through the parser/writer, the call went live and warned -- and, under a strict context,
threw -- on text copied verbatim from the specification itself.

**What the spec text says, by version:**
- VCFv4.3 (target): nothing. No ordering prose for `##META` at all.
- **VCFv4.2: nothing either.** Neither 4.2 nor 4.3 contains any structured-header-line attribute-ordering
  prose. (An earlier draft cited `VCFv4.2.tex:1160`, *"Note that the specific order of fields isn't
  defined…"* -- that sentence is in the BCF2 genotype-field encoding section and concerns FORMAT field order
  in a BCF2 genotypes block, not header attributes; VCFv4.3 carries the equivalent at `VCFv4.3.tex:1657`.)
- VCFv4.4/4.5 (`VCFv4.4.tex:134-135`): "To aid human readability, the order of fields **should** be ID,
  Number, Type, Description, then any optional fields. Implementations **must not rely on the order** of the
  fields within structured lines and are not required to preserve field ordering."

No version *requires* an order; the only normative statement across all four is a read-side "must not rely
on," which argues against enforcing one, not for it.

**Measured, 2026-08-12 (bcftools 1.23.1, vcf_validator 0.10.2):**

| shape | bcftools | vcf_validator |
|---|---|---|
| `##META=<ID=Assay,Number=.,Type=String,Values=[a, b]>` *(canonical; control)* | exit 0, silent | valid |
| `##META=<ID=Assay,Type=String,Number=.,Values=[a, b]>` -- `VCFv4.3.tex:273` verbatim | exit 0, silent | **not valid** |
| `##META=<Number=1,Type=Integer,Values="d",ID=Assay>` *(ID last)* | exit 0, silent | not valid |
| `##INFO=<Number=1,Type=Integer,Description="d",ID=DP>` *(ID last)* | exit 0, silent | not valid |
| `##ALT=<ID=DEL,Type=String,Number=1,Description="Deletion">` *(Type before Number)* | exit 0, silent | not valid |

bcftools ignores attribute order entirely, on every shape probed, including the non-`##META` control rows
included to confirm this isn't `##META`-specific tolerance. vcf_validator rejects every non-canonical
ordering -- including the specification's own worked `##META` example -- which disqualifies its verdict here
per `AGENTS.md`'s guidance on a tool result that contradicts the spec text it's meant to be enforcing.

**Decision, extended to every keyed declaration type, 2026-08-14 (originally scoped to `##META` alone):**
bcftools is the tie-breaker per `AGENTS.md`'s Decision Hierarchy, and the target spec is silent besides. The
identical reasoning that justified relaxing `##META` applies without modification to
`##INFO`/`##FORMAT`/`##ALT`/`##FILTER`/`##contig`/`##SAMPLE` -- no VCF version requires an order for any of
them either, and the measured table above already includes non-`##META` control rows (`##INFO`/`##ALT`, ID
declared last or `Type` before `Number`) confirming bcftools ignores order universally, not just for
`##META`. A narrower "ID must come first" compromise was considered and rejected: the ID-last `##INFO`
control row shows bcftools doesn't privilege ID's position over any other attribute's either, so that
narrower rule would have been an unmeasured, unjustified special case, not something the evidence supports.

**`BaseMetadata.checkAttributeOrder()` and the `IdMetadata.validateAttributeOrder()` dispatch hook that
called it were removed entirely, not just disabled per-type.** After extending this decision to every keyed
type, every concrete subclass overrode the hook to a no-op, leaving `IdMetadata`'s own base implementation
permanently unreachable -- genuinely dead code, and (unlike `checkAttributeOrder()` itself) never public, so
deleting it was a pure simplification with nothing to disclose. `checkAttributeOrder()` itself was `public`
API, but was introduced 2026-07-24 (confirmed via `git log`), entirely after the last real release (`v0.3.1`,
2022-12-13) -- it never shipped, so there was no external consumer to protect, and removing it costs nothing
a hypothetical deprecation cycle would have bought. The three call sites that used it as a convenient
"trigger a warning directly" mechanism for otherwise-unrelated test purposes (a `PEDIGREE` warnings-routing
test, two "typed constructor's own property order" tests) were rewritten against different, still-live
mechanisms rather than reproducing the deleted check as test-only code -- see
`VcfParserTest#testPedigreeMetadataWarningsSinkIsWiredToParserWarningsFile` (now triggered by a missing
`##PEDIGREE` `ID` under VCFv4.3+) and `InfoMetadataTest#testTypedConstructorInsertsPropertiesInCanonicalOrder`/
`FormatMetadataTest`'s sibling (now asserting `getPropertyKeys()`'s iteration order directly, since the typed
constructors still insert properties in a sensible default order for output readability -- just unenforced).

`test/vcf/4.3/failed/failed_meta_meta_003.vcf` (`##CauseOfFailure=Incorrect order of META fields`) and the
newly-added `failed_meta_info_003.vcf`/`failed_meta_format_003.vcf`/`failed_meta_alt_004.vcf` (one row per
spec version each, four fixture families x up to three versions = ten total baseline rows) all return to
`hts-specs-baseline.tsv` as **permanently accepted** divergences -- see `HTS_SPECS_BASELINE_STATUS.md`.
Pinned by `MetaMetadataTest#aMetaDeclarationIsNotAttributeOrderChecked`,
`InfoMetadataTest#testMapConstructorIsNotAttributeOrderChecked`,
`FormatMetadataTest#testMapConstructorIsNotAttributeOrderChecked`,
`IdDescriptionMetadataTest#testMapConstructorIsNotAttributeOrderChecked` (covers `##ALT`/`##FILTER`/`##SAMPLE`),
`ContigMetadataTest#testMapConstructorIsNotAttributeOrderChecked`,
`WriterRoundTripTest#testOutOfOrderInfoDeclarationRoundTripsWithoutRewriting`, and, on the bcftools side,
`BCFTOOLS_VERSION_DEPENDENCIES.md`'s "`##META` attribute order" entry
(`BcftoolsDependencyTest#testMetaAttributeOrderBcftoolsIgnoresCanonicalOrdering`/
`#testMetaAttributeOrderBcftoolsAcceptsTheSpecsOwnWorkedExample`/
`#testInfoAttributeOrderBcftoolsIgnoresIdPosition`, the last one generalizing the decision's evidence beyond
`##META` with its own dedicated case per `BEHAVIOR_TABLE_STANDARD.md`'s evidence-preservation tier 2).

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
(`VcfPosition.getInfo(ReservedInfoProperty)`) and `VcfWriter.validateInfoValue`. The read path is
`isLenient()`-aware: warns under a lenient `VcfContext`, throws `VcfFormatException` under a strict one,
matching `VALIDATION_PHILOSOPHY.md`'s universal `isLenient()` dispatch; the write path always warns,
regardless of the written object's own strict/lenient origin, per `AGENTS.md`'s Writer Validation Policy.

### CIGAR grammar (SAM compliance)

No VCF spec version (checked 4.2 through 4.5 directly) ties INFO `CIGAR`'s grammar to the SAM specification
the way 4.3+ explicitly does for CHROM/contig names — every version just describes it in prose with no BNF
and no cross-reference. The only source connecting `CIGAR` to SAM's grammar is vcf_validator's own message
text ("value is not an alphanumeric string compliant with the SAM specification"), confirmed directly against
`failed_body_info_010.vcf` (illegal trailing characters) and `_011.vcf` (a decimal number, not
`(length, operation)` pairs). bcftools is silent on both.

**Decision:** warn on violation, cited as a vcf_validator-sourced convention, not overstated as
spec-mandated. Implemented in `InternalUtils.checkReservedInfoConstraints` (CIGAR-specific branch). The
read path is `isLenient()`-aware: warns under a lenient `VcfContext`, throws `VcfFormatException` under a
strict one, matching `VALIDATION_PHILOSOPHY.md`'s universal `isLenient()` dispatch; the write path always
warns, regardless of the written object's own strict/lenient origin, per `AGENTS.md`'s Writer Validation
Policy.

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

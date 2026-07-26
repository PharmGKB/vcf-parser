# Duplicate metadata declaration ID handling

VCF metadata declarations (`##contig`, `##INFO`, `##FORMAT`, `##FILTER`, `##ALT`, `##SAMPLE`) are each keyed by an
`ID` attribute. Neither [VCFv4.2.tex](https://github.com/samtools/hts-specs) nor VCFv4.3.tex states what happens
when the same `ID` is declared twice for the same type within one file — the spec is simply silent on it.

## Why this warns instead of throwing

`vcf-parser` used to reject any file with a repeated declaration ID (`VcfFormatException: Duplicate ID ... for
...`). This is stricter than either reference tool this project consults when spec text is silent (see
`AGENTS.md`'s decision hierarchy):

- **vcf_validator** (the EBI reference validator; the tool [hts-specs](https://github.com/samtools/hts-specs) uses
  to classify its own `passed/`/`failed/` conformance fixtures) reports a file with a repeated declaration ID as
  **valid**, for every one of the six declaration types.
- **bcftools**, the reference implementation most of the ecosystem treats as canonical, keeps the **first**
  declaration and silently drops each duplicate — no warning, no error — again for every one of the six types.

hts-specs' own `test/vcf/{4.1,4.2}/passed/passed_meta_contig.vcf` fixture declares `##contig=<ID=1>` and then
`##contig=<ID=1,length=123456>`, and is expected to parse successfully — direct confirmation that this is not a
structural violation. Rejecting the file outright would make `vcf-parser` reject input that hts-specs' own
conformance suite, its own reference validator, and the ecosystem's reference implementation all treat as
acceptable, so `vcf-parser` now matches that practical tolerance: it logs a warning and keeps the first declaration,
rather than throwing `VcfFormatException`.

This is a construction-time-only concern: once a `VcfMetadata` is built, each declaration type is stored in a
`Map<String, ...>` keyed by ID, so there is no way for a "duplicate" to exist in the built object — only the
process of adding declarations (via `VcfMetadata.Builder` or `VcfParser`, which calls the same `Builder` methods)
can encounter one.

## Reference table

Verified with **vcf_validator 0.10.2** and **bcftools 1.23.1 (htslib 1.23.1)** against a hand-built fixture per
declaration type, each declaring the same ID twice with different attributes.

| Declaration type | Target spec (VCFv4.2/4.3) | vcf_validator 0.10.2 | bcftools 1.23.1 | `vcf-parser` |
|---|---|---|---|---|
| `##contig` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##INFO` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##FORMAT` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##FILTER` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##ALT` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##SAMPLE` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |

Contrast with a duplicate `#CHROM` sample-column name, which remains a structural error in `vcf-parser`. This is a
different *kind* of duplication, not merely a stricter policy choice: `#CHROM`'s sample columns are *positional* —
every data line's per-sample values are aligned to them by index, and `VcfMetadata.getSampleIndex(id)` resolves a
name via `m_columns.indexOf(id)`. A duplicate name makes the *second* column's data permanently unreachable by
name (only reachable positionally), silently misdirecting any name-based lookup to the first column's data instead
— a real data-integrity hazard none of the six declaration types above have, since a declaration merely describes
something; nothing else in the file indexes into it by position.

Both reference tools independently draw this same line:

| Case | vcf_validator 0.10.2 | bcftools 1.23.1 |
|---|---|---|
| Duplicate `##contig`/`##INFO`/etc. ID | Valid | Keeps first, drops duplicate, no warning |
| Duplicate `#CHROM` sample-column name | **Invalid** | **Hard error — refuses to parse the file at all** |

(Verified directly: `bcftools view` on a file with two `S1` sample columns emits `[E::bcf_hdr_add_sample_len]
Duplicated sample name 'S1'` and fails to parse; `vcf_validator` reports the file invalid.)

## Why "keep first," not "keep last"

`vcf-parser` keeps the *first* declaration and discards each subsequent duplicate, matching bcftools' confirmed
behavior exactly (per `AGENTS.md`: "If still in doubt, consult bcftools behavior"). This also means a discarded
duplicate's own attributes are never validated against that type's own rules (e.g. `##ALT`'s structural-variant-code
check) — a dropped declaration is never stored, so its internal validity is irrelevant, the same way bcftools never
surfaces any complaint about a dropped duplicate's content either.

## Where this is implemented

- `VcfMetadata.warnIfDuplicate(Map, String, String)` — the shared helper, used by both `VcfMetadata`'s own
  instance `addXxx` methods and `VcfMetadata.Builder`'s `addXxx` methods.
- `VcfMetadata.Builder.addAlt()` / `addInfo()` / `addFormat()` / `addFilter()` / `addContig()` / `addSample()`, and
  `VcfMetadata.addAlt()` / `addInfo()` / `addFormat()` / `addFilter()` / `addContig()` (the same five, minus
  `SAMPLE`, which has no instance-level equivalent) — each calls `warnIfDuplicate()` before storing a new
  declaration, and returns immediately (without storing) if it reports a duplicate. To replace an existing entry,
  call the corresponding `removeXxx()` method first, then add — there is no implicit-overwrite path anywhere in
  this bucket.
- `VcfMetadata.Builder.addPedigree()` — warns and keeps the first entry when a duplicate literal `ID` property is
  found; always adds when no `ID` property is present.
- `VcfWriter.validateMetadata()`'s PEDIGREE loop — the same check, but warn-only (never drops), since it runs
  post-construction on content the caller already holds a reference to.

## `##PEDIGREE`: a conditional case

Unlike the six keyed types above, `##PEDIGREE`'s grammar (`VCFv4.2.tex` line 152: `##PEDIGREE=<Name_0=G0-ID,...,
Name_N=GN-ID>`) has no mandatory `ID` attribute — the spec's own worked example (lines 904-908) declares three
`##PEDIGREE` lines in one file, each with a different, non-`ID` attribute schema (`Derived=...,Original=...`).
This is why `m_pedigree` is a `List<BaseMetadata>`, not a `Map<String, ...>` like the six keyed types — there is
no guaranteed key to map on.

Empirical testing shows bcftools still dedupes PEDIGREE by `ID` whenever a line happens to declare one:

| Case | bcftools 1.23.1 | vcf_validator 0.10.2 |
|---|---|---|
| Two `##PEDIGREE` lines, no `ID` property, otherwise identical | Keeps **both** | Valid |
| Two `##PEDIGREE` lines sharing `ID=P1` | Keeps **first**, drops duplicate | Valid |

So the rule generalizes: bcftools dedupes by `ID` wherever that key is present, regardless of the declaration's
tag name — not six special cases plus an unrelated PEDIGREE rule, but one rule (dedupe on `ID` when present) that
the six keyed types happen to always trigger (their grammar makes `ID` mandatory) and PEDIGREE only sometimes
triggers (its grammar makes `ID` optional). `VcfMetadata.Builder.addPedigree()` and `VcfWriter`'s post-construction
check both implement this: warn and keep first when `ID` is present and duplicated; always add when `ID` is
absent.

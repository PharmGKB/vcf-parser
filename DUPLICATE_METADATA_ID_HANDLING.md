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

Contrast with a duplicate `#CHROM` sample-column name, which remains a structural error in `vcf-parser` per
`AGENTS.md`'s Parser Validation Policy — that is a different kind of duplication (positional data columns, not a
keyed metadata declaration) and is out of scope for this doc.

## Why "keep first," not "keep last"

`vcf-parser` keeps the *first* declaration and discards each subsequent duplicate, matching bcftools' confirmed
behavior exactly (per `AGENTS.md`: "If still in doubt, consult bcftools behavior"). This also means a discarded
duplicate's own attributes are never validated against that type's own rules (e.g. `##ALT`'s structural-variant-code
check) — a dropped declaration is never stored, so its internal validity is irrelevant, the same way bcftools never
surfaces any complaint about a dropped duplicate's content either.

## Where this is implemented

- `VcfMetadata.Builder.warnIfDuplicate(Map, String, String)` — the shared helper.
- `VcfMetadata.Builder.addAlt()` / `addInfo()` / `addFormat()` / `addFilter()` / `addContig()` / `addSample()` —
  each calls `warnIfDuplicate()` before storing a new declaration, and returns immediately (without storing) if it
  reports a duplicate.

## Out of scope

1. `##PEDIGREE` declarations are not covered by this fix. `VcfMetadata.Builder.addPedigree()` has no duplicate-ID
   check at all and always accepts every declaration unconditionally, while `VcfWriter.validateBeforeWrite()` still
   throws `VcfFormatException` on a duplicate PEDIGREE ID (see `VcfWriter.java` around line 243-251, search for
   "for PEDIGREE metadata") — an inconsistency with the six keyed types above, left as-is since no hts-specs
   fixture or tool evidence motivates touching it in this pass.
2. `VcfMetadata`'s own instance methods (`addAlt`/`addInfo`/`addFormat`/`addFilter`/`addContig`, defined directly
   on `VcfMetadata`, not on `VcfMetadata.Builder`) retain a separate, pre-existing behavior on a duplicate ID:
   silent overwrite (effectively "keep last"), with no warning at all. This differs from `Builder`'s new
   warn-and-keep-first behavior documented above, and is intentionally out of scope for this fix — only
   `Builder`'s construction-time path was changed.

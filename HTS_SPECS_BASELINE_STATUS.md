# hts-specs conformance baseline status

**This document is bound to `htsSpecsPinnedSha = da617203a9527537746e200abda2885bec3a822c` (see `build.gradle`).**
If that value has changed since, treat every row below as unverified until re-checked: hts-specs can add, remove,
rename, or reclassify fixtures across commits, silently invalidating the fixture-path/cause mapping here. To
re-verify: run `./gradlew htsSpecsUpdateBaseline`, then `git diff
src/test/resources/org/pharmgkb/parser/vcf/spec/hts-specs-baseline.tsv` against the fixture list in each row
below — update both this document and the rows before trusting it again.

This is an index, not a rationale document — each row points to where the full reasoning already lives. Don't
duplicate that reasoning here; if a row's linked doc doesn't exist or doesn't cover the claim, that's a sign this
document itself is stale.

## `parser-throw-or-warn` entries (10) — `failed/` fixtures whose invalidity isn't detected

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Genome ID with whitespace/punctuation in `##PEDIGREE` | `failed_meta_pedigree_000.vcf`, `failed_meta_pedigree_001.vcf` (4.1, 4.2) | 4 | Candidate for upstream filing, not yet written | bcftools 1.23.1 accepts both silently (no rule enforced); `VCFv4.2.tex` line 152 gives no character grammar for genome IDs at all. Investigated but no issue doc written yet — unlike the URL cluster below. (`pedigree-issue.md` exists in the repo root but covers a different, already-closed `##CauseOfFailure=` wording-inconsistency cluster, not this one -- "not yet written" still stands for this cluster.) |
| Non-valid URL / mislabeled cause | `failed_meta_assembly_001.vcf` (4.1), `failed_meta_pedigreedb_002.vcf` (4.1, 4.2) | 3 | Filed upstream, pending | See `non-valid-url-issue.md` — written, not yet submitted to hts-specs. |
| Incorrect ALT ID value (`ID=DEL1`) | `failed_meta_alt_000.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | A documented false negative: the reserved structural-variant-code hierarchy is only enforced when an ALT ID contains `:`, since a bare token has other spec-sanctioned uses (IUPAC codes, `CN0`-style copy-number codes). See `README.md`'s hts-specs conformance section. |
| Meta-data entry is not a key-value pair | `failed_meta_assembly_001.vcf` (4.2 only) | 1 | Filed upstream, pending | Same fixture/value as the URL row above, but this version copy has a different, inconsistent `##CauseOfFailure=` label. See `non-valid-url-issue.md`, which documents the labeling inconsistency directly. |

## `writer-bytediff` entries (9) — `passed/` fixtures whose round trip isn't byte-identical

| Cause | Fixtures | Count | Status | Details |
|---|---|---|---|---|
| Metadata declaration reordering | `complexfile_passed_000.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | `VcfWriter.buildHeaderLines()` has a pre-existing, fixed per-type metadata grouping order (INFO, FILTER, FORMAT, ALT, contig, SAMPLE, PEDIGREE, then generic properties); this fixture interleaves types in a different order. See `README.md`'s hts-specs conformance section. |
| `QUAL` scientific-notation case | `passed_body_qual.vcf` (4.1, 4.2, 4.3) | 3 | Permanently accepted | `2e+1` round-trips as `2E+1` — `BigDecimal`'s own string formatting. See `README.md`'s hts-specs conformance section. |
| Duplicate `##contig` line dropped | `passed_meta_contig.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | Direct, intended consequence of the duplicate-metadata-ID keep-first policy. See `DUPLICATE_METADATA_ID_HANDLING.md`. |
| Empty sample value substituted | `zero_length_LAA.vcf` (4.5) | 1 | Permanently accepted | Matches this library's established empty-field normalization policy. See `EMPTY_FIELD_HANDLING.md`. |

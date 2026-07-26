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
| Genome ID with whitespace/punctuation in `##PEDIGREE` | `failed_meta_pedigree_000.vcf`, `failed_meta_pedigree_001.vcf` (4.1, 4.2) | 4 | Candidate for upstream filing, not yet written | bcftools 1.23.1 accepts both silently (no rule enforced); `VCFv4.2.tex` line 152 gives no character grammar for genome IDs at all. Investigated but no issue doc written yet. |
| Non-valid URL / mislabeled cause | `failed_meta_assembly_001.vcf` (4.1), `failed_meta_pedigreedb_002.vcf` (4.1, 4.2) | 3 | Permanently accepted | See "Non-valid URL fixtures" below. |
| Incorrect ALT ID value (`ID=DEL1`) | `failed_meta_alt_000.vcf` (4.1, 4.2) | 2 | Permanently accepted, deliberate | A documented false negative: the reserved structural-variant-code hierarchy is only enforced when an ALT ID contains `:`, since a bare token has other spec-sanctioned uses (IUPAC codes, `CN0`-style copy-number codes). |
| Meta-data entry is not a key-value pair | `failed_meta_assembly_001.vcf` (4.2 only) | 1 | Permanently accepted | Same fixture/value as the URL row above, but this version copy has a different, inconsistent `##CauseOfFailure=` label than its 4.1/4.3 siblings — doesn't matter to us, both labels get the same treatment. See "Non-valid URL fixtures" below. |

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

**Why permanently accepted, not filed upstream:** there is no rule here — spec or tool — for hts-specs
to clarify that would change this library's behavior. The reference implementation (bcftools) and a
standard URI parser both already accept this value; rejecting it would mean this library inventing its
own unenforced heuristic (e.g. "reject all-digit hostnames") that no spec or tool actually applies. Not
worth doing, and not worth filing upstream over, since there's nothing broken to fix — just an
unenforceable fixture.

Affected fixtures:

| Fixture | Version(s) | `##CauseOfFailure=` |
|---|---|---|
| `failed_meta_assembly_001.vcf` | 4.1 | `Non-valid URL` |
| `failed_meta_assembly_001.vcf` | 4.2 (file internally declares `VCFv4.3` despite living under the `4.2/` directory), 4.3 | `Meta-data entry is not a key-value pair` |
| `failed_meta_pedigreedb_002.vcf` | 4.1, 4.2, 4.3 | `Non-valid URL` (consistent across versions) |

The `##CauseOfFailure=` label differs across these copies, but that's incidental to hts-specs' own
fixture bookkeeping — it doesn't change what this library does with any of them, so all three are
treated identically and tracked as one cause above.

# Overview

[![Build Status](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml/badge.svg)](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml)
[![codecov.io](https://codecov.io/github/PharmGKB/vcf-parser/coverage.svg?branch=main)](https://codecov.io/github/PharmGKB/vcf-parser?branch=main)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser)

This is a streaming parser and writer for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format). It validates record
structure strictly but defers validation of non-structural content for performance — see
[Validation: strict vs. lenient](#validation-strict-vs-lenient) below.

The main parser class ([`VcfParser`](src/main/java/org/pharmgkb/parser/vcf/VcfParser.java)) is responsible for reading all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out [`VcfParserTest.java`](src/test/java/org/pharmgkb/parser/vcf/VcfParserTest.java) for a quick and dirty view of it in action.

[`MemoryMappedVcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/MemoryMappedVcfLineParser.java) is an example
`VcfLineParser` implementation, showing how to read an entire file into memory for constant-time record lookup; it
is not part of the core parser. VCF permits multiple records at the same locus, so each locus is stored as a list of
records, and every record seen at a locus is kept with no configuration needed. A repeated `ID`, by contrast, is not
a normal VCF occurrence, so it remains single-valued and `Builder.setDuplicateIdHandler` controls whether it's
rejected or which record wins.

**VCF version support:** The target specification is VCF 4.2. The parser accepts representable VCF 4.x input from
before and after that version. Where VCF versions differ or input is ambiguous, it uses VCF 4.2 semantics. Later 4.x
features that cannot be represented safely may be warned about, normalized, or rejected; they are not necessarily
specially interpreted.


## Validation: strict vs. lenient

The parser draws a deliberate line between the **structure of a VCF record**, which it validates strictly, and
**non-structural content** (including INFO, FILTER, FORMAT, sample values, and metadata declarations), which it may
validate lazily.

**Strict — throws `VcfFormatException`.** The parser rejects a file whose structure or mandatory record fields are
invalid:

- A missing, duplicate, or non-first `##fileformat`; a file format outside VCF 4.x; any line other than a `##`
  metadata line before the `#CHROM` header; or a `#`-prefixed line after it (VCF has no comment syntax).
- A structured metadata declaration (`##INFO`, `##FORMAT`, `##contig`, `##FILTER`, `##ALT`, `##SAMPLE`,
  `##PEDIGREE`) whose value is not wrapped in angle brackets (`<...>`).
- A `##` metadata line with no `=` at all (e.g. a bare `##sometext` or `##INFO` with no `=`) -- distinct from the
  missing-angle-brackets case above, which requires a well-formed `key=value` structured declaration whose value
  isn't wrapped in `<...>`.
- A missing `#CHROM` header, wrong fixed column names, a missing `FORMAT` column when samples are present, a
  `FORMAT` column with no sample columns following it, an empty sample name, or a duplicate sample name.
- A data line with the wrong number of tab-separated columns.
- An empty mandatory fixed field (the missing value must be `.`), an empty `CHROM`, or a negative `POS`.
- Invalid `REF`/`ALT` bases; whitespace where the spec forbids it (`CHROM`, `ID`, `FILTER`, `INFO`); a duplicate
  identifier within a single `ID` field; a `FILTER` of `0`; an `INFO` missing value (`.`) combined with a real
  property; or a `FORMAT` in which `GT` is present but not the first sub-field or a (non-empty) key is
  duplicated.
- A sample with more sub-fields than its `FORMAT` declares (trailing sub-fields may be dropped, but not added).
- A malformed or out-of-range `GT` allele index looked up through `MemoryMappedVcfDataStore`, or a failed
  conversion when a typed value is requested.
- A key or value set through `BaseMetadata`'s, `VcfSample`'s, or `VcfMetadata`'s mutators (e.g. a `Description`, a
  sample's `GT` value, or an `##assembly` line) containing a line terminator.
- A `VcfSample` key or value set through its constructors or `putProperty` containing a colon or tab, which would
  otherwise add a spurious FORMAT sub-field or sample column when written back out. The one exception is a value for
  the key `GLE` (VCFv4.1/4.2's genotype-likelihoods-of-heterogeneous-ploidy key, removed from the spec in VCFv4.3+):
  its own spec example (e.g. `0:-75.22,1:-223.42,...`) is one opaque String that uses colons internally as part of
  its own encoding, not a delimiter between independent sub-fields; a colon in the key `GLE` itself, or a tab in its
  value, is still rejected.
- `VcfWriter.writeLine` is given a number of samples that disagrees with the header's declared sample count, any
  FORMAT/sample data at all when the header declares no samples, or an empty `REF` (which has no missing-value
  sentinel in the spec, unlike `ALT`/`ID`/`FILTER`): in every case the output would not be valid, re-parseable VCF.
- A typed metadata constructor given directly (e.g. `new InfoMetadata(id, ...)`, `new ContigMetadata(id, ...)`,
  `new IdDescriptionMetadata(label, id, description)`) with a missing, empty, or whitespace-only `ID` — every VCF
  structured metadata line requires an ID (VCFv4.3.tex line 121). This only applies to direct/programmatic
  construction; a file parsed by `VcfParser` with a malformed declaration is handled per the lenient section below,
  not rejected outright.

These checks run when a `VcfPosition` is constructed (including by the parser). Its setters and mutations made
through its accessors (e.g. `getAltBases()`, `getFilters()`) do *not* re-run them, to support transformation
pipelines that mutate a position in place; call `VcfPosition.validate()` after such mutations to re-check validity —
this also re-normalizes a lone `.` `FILTER` value left by such a mutation, matching construction. `PASS` is never
normalized away -- it is itself the sole representation of a passing FILTER (see `VcfPosition.getFilterStatus()`).

Mutating `position.getFormat()` (adding or removing a FORMAT key) does *not* automatically update that line's
`VcfSample` properties -- the two must be kept in agreement by the caller. If they diverge, the default writer path
warns and reconciles by key name: a FORMAT key with no corresponding sample value is substituted with `.`, and a
sample property not declared in FORMAT is dropped. `VcfWriter.Builder.validateBeforeWrite()` still substitutes `.`
for the first case, but rejects the second (a sample property not declared in FORMAT) with `VcfFormatException`.

The default writer path is optimized for data written directly after parsing. It preserves parser-normalized values and
maintains record and sample-column structure without full revalidation. If data has been mutated and a full structural
validity and diagnostic check is required, build the writer with `VcfWriter.Builder.validateBeforeWrite()`. In that
mode, structurally invalid output is rejected and detected semantic non-compliance is reported with a warning. This
mode also enforces file-level consistency: `writeHeader()` may not be called more than once, `writeLine()` may not be
called before `writeHeader()`, every `writeLine()` call must be given the same `VcfMetadata` object `writeHeader()`
was given, and every `INFO`/`FORMAT`/`FILTER`/`ALT`/`contig`/`SAMPLE` metadata entry's own `ID` property must still
match the key it's stored under in `VcfMetadata`'s maps (mutating an entry's raw `ID` through `getPropertiesRaw()`
without it being reflected in the map key would otherwise make the header declare a different ID than what records
reference by that entry's map key).

The writer detects a write failure (disk full, broken pipe, or an encoding failure) and reports it as an
unchecked `java.io.UncheckedIOException`, but only checks periodically (every 1000 lines) and at
`writeHeader()`/`close()` -- not after every `writeLine()` call, to avoid forcing a flush past the writer's
internal buffer on every line. A failure detected mid-stream releases the underlying resource immediately, as
part of the same detection, rather than leaving it open until some later `close()` call. Calling `close()` is
still required to guarantee that all remaining buffered output is flushed and that a failure not yet caught by a
periodic check is reported. A detected write failure, or simply closing the writer at all (even cleanly, with no
error), permanently invalidates the `VcfWriter` (and, transitively, a `TransformingVcfLineParser` built on it):
`isValid()` becomes `false`, and any further `writeHeader()`/`writeLine()` call throws `IllegalStateException`
instead of attempting to write again -- retrying after a write failure, or reusing a writer after closing it, is
never safe, since the underlying writer may already be in a partially-written or otherwise inconsistent state, or
simply already released. `close()` itself is idempotent: calling it again after the first call has no effect,
matching `java.io.Closeable#close()`'s own documented contract.

**Lenient — warns when encountered or accessed and preserves usable data.** Non-structural content may be accepted
initially and validated only when the relevant getter or typed conversion is used. A metadata declaration
(`##INFO`, `##FORMAT`, `##contig`, `##FILTER`, `##ALT`, ...) that is well-formed as a declaration -- wrapped in
angle brackets -- but has malformed *attribute values* inside it warns rather than throws; most are still stored
as-is, with this exception (a declaration missing its angle-bracket wrapper entirely is a structural failure and
still throws -- see the strict section above):

- A declaration missing its `ID`, or with an empty/whitespace-only `ID=` — every VCF structured metadata line
  requires an ID (VCFv4.3.tex line 121); the parser warns and drops the whole declaration rather than storing it.
  See [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md) for the full per-type
  bcftools/vcf_validator comparison and the empty-vs-missing rationale.
- A missing or invalid `Number`, `Type`, or `Description`; an unquoted `Description`; or `Type=Flag` with a `Number`
  other than `0`. An unparseable `Type` leaves the metadata's type `null`.
- A header/metadata sample-count mismatch, or a sample named in the header but absent from the metadata.
- INFO, FILTER, FORMAT, and sample content that is semantically malformed but can be preserved or normalized safely.
- A non-numeric `QUAL`: the column itself is still well-formed, so this is treated as the missing value rather than
  a structural failure.
- A reserved `FORMAT/FT` value that doesn't follow the FILTER-like grammar (`PASS`, `.`, or a semicolon-separated
  list of codes with no whitespace, none of which may be combined with `PASS` or `.`), or a reserved `FORMAT/PS`
  value that isn't a non-negative 32-bit integer: both constraints go beyond what `FT`'s/`PS`'s declared `Type` can
  express, so they're checked separately, both when a typed value is requested and by
  `VcfWriter.Builder.validateBeforeWrite()`.
- A `FILTER` value combining `PASS` with another code (e.g. `STD_FILTER;PASS`): VCFv4.2 only implies `PASS` is
  exclusive, without stating it as a hard rule, and hts-specs' own `complexfile_passed_000.vcf` fixture combines
  them and is expected to parse; confirmed accepted silently by bcftools 1.23.1 and valid per vcf_validator
  0.10.2.
- An INFO value containing `=` (e.g. `DP=1=2`), or a duplicate top-level INFO key (e.g. `DP=1;DP=2`): VCFv4.2's INFO
  grammar note technically disallows the former, and VCFv4.3 explicitly disallows the latter, but bcftools 1.23.1
  accepts both silently — splitting an `=`-containing value on the first `=` only (same as this parser), and
  keeping only the first occurrence of a duplicate key rather than merging the two into a list. This parser matches
  that bcftools tolerance rather than being stricter than it, consistent with the empty-field policy below.
- A declared `Number`/`Type` for a well-known reserved `INFO`/`FORMAT` key (e.g. `AC`, `AN`, `GT`, `PL`) that doesn't
  match VCF's canonical definition for that key.
- An `##INFO`/`##FORMAT`/`##ALT`/`##FILTER`/`##SAMPLE` declaration whose attributes are out of the canonical
  `ID, Number, Type, Description, Source, Version` order every spec example uses.
- An `##ALT` structural-variant ID (e.g. `<DEL:ME:ALU>`) that doesn't match VCF's reserved code hierarchy
  (DEL/INS/DUP/INV/CNV and colon-delimited subtypes) — but only when the ID actually contains a colon; a colon-less
  ID (e.g. an IUPAC ambiguity code, or a `CN0`-through-`CN124`-style copy-number allele used by 1000 Genomes) is not
  checked against that hierarchy at all, since colon-less custom codes have other spec-sanctioned, non-structural-
  variant uses this parser must not reject.
- A `##contig`/`##INFO`/`##FORMAT`/`##FILTER`/`##SAMPLE`/`##ALT` declaration's `ID` containing whitespace (as
  opposed to being entirely blank/missing, handled separately above): warns. VCFv4.2/4.3 state this explicitly
  only for `##ALT` (VCFv4.2.tex line 105); hts-specs' own `failed_meta_contig_001.vcf` (4.1/4.2/4.3) and
  `failed_meta_sample_001.vcf` (4.3 only) fixtures extend it to `##contig`/`##SAMPLE`, bcftools 1.23.1 warns
  (`Invalid tag name`/`Invalid contig name`) for `##INFO`/`##FORMAT`/`##contig` specifically, and vcf_validator
  0.10.2 rejects all five uniformly. For `##FILTER`/`##SAMPLE` specifically, bcftools 1.23.1 accepts whitespace
  silently (no dedicated header check exists for either), directly contradicting hts-specs' own
  `failed_meta_sample_001.vcf` fixture — treated as a bcftools coverage gap rather than a considered position, so
  the hts-specs fixture and vcf_validator's uniform treatment govern instead. `##ALT` IDs also separately warn on
  angle brackets (`<`/`>`), per the same VCFv4.2.tex line 105 sentence — an `##ALT`-specific restriction not
  extended to the other five types, since no fixture or spec passage evidences it for them.

An empty ("zero-length") entry in a delimited record field — e.g. `ID=rs1;;rs2`, `ALT=T,`, `FILTER=q10;`,
`FORMAT=GT:DP:`, a sample value like `0/1:`, or `INFO=AD=1,,2` — is also lenient rather than strict when it can be
recovered without changing field alignment. See [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md) for the full
field-by-field table of what each case normalizes to and why.

Typed metadata accessors (e.g. `InfoMetadata.getType()` and `getNumber()`) are annotated `@Nullable` and return `null`
when the corresponding metadata was malformed.

Reserved `Float`-typed `INFO`/`FORMAT` properties (e.g. `AF`, `MQ`, `GL`) are converted to
[`VcfFloat`](src/main/java/org/pharmgkb/parser/vcf/model/VcfFloat.java), not a plain `BigDecimal`: VCFv4.2 doesn't
define `Float`'s literal grammar, and VCFv4.5 clarifies it accepts `NAN`/`INF`/`INFINITY` (case-insensitive,
optionally signed) in addition to a normal decimal number — values a plain `BigDecimal` cannot represent at all.

A `FORMAT` value of `.` (with one or more samples present) means no per-sample format fields at all for that
record; each sample is read as having zero properties, and any raw sample-column content there is ignored (with a
warning). VCFv4.2 through 4.5 are all silent on this exact case — FORMAT is only described as required "if
genotype information is present" — but bcftools 1.23.1 accepts and correctly interprets it (verified directly:
`bcftools query` returns the missing value for such a sample's `GT` regardless of that sample's own raw column
content, and `bcftools view` round-trips it as `.`), so this parser and writer both treat it as valid, matching
bcftools 1.23.1 behavior.


## hts-specs conformance test

`./gradlew htsSpecsTest` runs this parser and writer against
[hts-specs](https://github.com/samtools/hts-specs)' official VCF conformance fixture suite
(`test/vcf/{4.1,4.2,4.3,4.5}/{passed,failed}`), fetched at build time and pinned to a specific commit
(`htsSpecsPinnedSha` in `build.gradle`). It runs four checks:

- `parser-no-throw`: every `passed/` fixture (all versions) parses without error.
- `parser-throw-or-warn`: every `failed/` fixture (for versions up to this library's VCFv4.2 target) either throws
  `VcfFormatException` or logs a warning — this library is deliberately more permissive than strict compliance for
  non-structural violations, so a `failed/` fixture parsing without error is not itself a failure; parsing it with
  *no signal at all* is.
- `writer-semantic` and `writer-bytediff`: every `passed/` fixture (all versions) is parsed, written back out via
  `VcfWriter`, and re-parsed; the round trip is compared both semantically (parsed data equal) and as raw text
  (byte-for-byte equal, ignoring line-ending differences).

Every check's outcome is compared against a committed baseline file
(`src/test/resources/org/pharmgkb/parser/vcf/spec/hts-specs-baseline.tsv`): a run fails only on a NEW divergence
(a fixture/check-type pair not yet in the baseline) or a STALE entry (baselined but no longer reproduces) — an
already-accepted divergence never fails the build. A fully green `htsSpecsTest` run means current behavior exactly
matches the committed baseline. After a deliberate behavior change, run `./gradlew htsSpecsUpdateBaseline` to
regenerate the baseline from current results, then review the `git diff` of that file before committing — removed
entries mean improved behavior, added entries mean a new divergence is being accepted.

This task requires network access (to `github.com`) and is **not** part of the required `./gradlew test` check,
which stays network-free. The pinned commit is bumped deliberately, not automatically; `htsSpecsCheckout` (which
`htsSpecsTest` depends on) prints a warning if hts-specs has upstream changes to `test/vcf` that the current pin
doesn't include.

For the current baseline's contents — every fixture this library still doesn't detect as invalid, or doesn't
round-trip byte-identically, along with each entry's status and rationale — see
[`HTS_SPECS_BASELINE_STATUS.md`](HTS_SPECS_BASELINE_STATUS.md).

Every `htsSpecsTest`/`htsSpecsUpdateBaseline` run writes two (gitignored, not committed) reports under
`build/reports/hts-specs-conformance/`: `summary.md` (counts by version x check type) and `findings.tsv` (only
NEW/STALE rows — empty except for the header on a clean run).


## Get It

### Maven

```xml
<dependencies>
  ...
  <dependency>
    <groupId>org.pharmgkb</groupId>
    <artifactId>vcf-parser</artifactId>
    <version>0.3.1</version>
  </dependency>
  ...
</dependencies>
```

### Non-Maven

You can download jars from the [Central Maven Repository](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.pharmgkb%22%20a%3A%22vcf-parser%22).

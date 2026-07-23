# Overview

[![Build Status](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml/badge.svg)](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml)
[![codecov.io](https://codecov.io/github/PharmGKB/vcf-parser/coverage.svg?branch=main)](https://codecov.io/github/PharmGKB/vcf-parser?branch=main)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser)

This is a streaming parser and writer for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format). It validates record
structure strictly but defers validation of non-structural content for performance — see
[Validation: strict vs. lenient](#validation-strict-vs-lenient) below.

The main parser class ([`VcfParser`](src/main/java/org/pharmgkb/parser/vcf/VcfParser.java)) is responsible for reading all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out [`VcfParserTest.java`](src/test/java/org/pharmgkb/parser/vcf/VcfParserTest.java) for a quick and dirty view of it in action.

[`MemoryMappedVcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/MemoryMappedVcfLineParser.java) is an implementation of `VcfLineParser` that reads everything into memory.

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
- A missing `#CHROM` header, wrong fixed column names, a missing `FORMAT` column when samples are present, or duplicate
  sample names.
- A data line with the wrong number of tab-separated columns.
- An empty mandatory fixed field (the missing value must be `.`), an empty `CHROM`, or a negative `POS`.
- Invalid `REF`/`ALT` bases; an `ALT` missing value (`.`) combined with a real allele; whitespace where the spec forbids
  it (`CHROM`, `ID`, `FILTER`, `INFO`); a duplicate identifier within a single `ID` field; a `FILTER` of `0` or `PASS`
  combined with other filters; an `INFO` missing value (`.`) combined with a real property; or a `FORMAT` in which
  `GT` is present but not the first sub-field or a (non-empty) key is duplicated.
- A sample with more sub-fields than its `FORMAT` declares (trailing sub-fields may be dropped, but not added).
- An invalid genotype passed to `VcfGenotype`, a malformed or out-of-range `GT` allele index looked up through
  `MemoryMappedVcfDataStore`, or a failed conversion when a typed value is requested.
- A key or value set through `BaseMetadata`'s, `VcfSample`'s, or `VcfMetadata`'s mutators (e.g. a `Description`, a
  sample's `GT` value, or an `##assembly` line) containing a line terminator.
- A `VcfSample` key or value set through its constructors or `putProperty` containing a colon or tab, which would
  otherwise add a spurious FORMAT sub-field or sample column when written back out. The one exception is a value for
  the key `GLE` (VCFv4.1/4.2's genotype-likelihoods-of-heterogeneous-ploidy key, removed from the spec in VCFv4.3+):
  its own spec example (e.g. `0:-75.22,1:-223.42,...`) is one opaque String that uses colons internally as part of
  its own encoding, not a delimiter between independent sub-fields; a colon in the key `GLE` itself, or a tab in its
  value, is still rejected.
- `VcfWriter.writeLine` is given a number of samples that disagrees with the header's declared sample count, any
  FORMAT/sample data at all when the header declares no samples, an empty `FORMAT` when the header declares one or
  more samples, or an empty `REF` (which has no missing-value sentinel in the spec, unlike `ALT`/`ID`/`FILTER`): in
  every case the output would not be valid, re-parseable VCF.

These checks run when a `VcfPosition` is constructed (including by the parser). Its setters and the mutable lists
returned by its accessors (e.g. `getAltBases()`, `getFilters()`) do *not* re-run them, to support transformation
pipelines that mutate a position in place; call `VcfPosition.validate()` after such mutations to re-check validity —
this also re-normalizes a lone `PASS` or `.` `FILTER` value left by such a mutation, matching construction.

The default writer path is optimized for data written directly after parsing. It preserves parser-normalized values and
maintains record and sample-column structure without full revalidation. If data has been mutated and a full structural
validity and diagnostic check is required, build the writer with `VcfWriter.Builder.validateBeforeWrite()`. In that
mode, structurally invalid output is rejected and detected semantic non-compliance is reported with a warning.

**Lenient — warns when encountered or accessed and preserves usable data.** Non-structural content may be accepted
initially and validated only when the relevant getter or typed conversion is used. Malformed metadata declarations
(`##INFO`, `##FORMAT`, `##contig`, `##FILTER`, `##ALT`, ...) warn rather than throw, and the declaration is still
stored:

- A missing or invalid `Number`, `Type`, or `Description`; an unquoted `Description`; or `Type=Flag` with a `Number`
  other than `0`. An unparseable `Type` leaves the metadata's type `null`.
- A header/metadata sample-count mismatch, or a sample named in the header but absent from the metadata.
- INFO, FILTER, FORMAT, and sample content that is semantically malformed but can be preserved or normalized safely.
- A non-numeric `QUAL`: the column itself is still well-formed, so this is treated as the missing value rather than
  a structural failure.

An empty ("zero-length") entry in a delimited record field — e.g. `ID=rs1;;rs2`, `ALT=T,`, `FILTER=q10;`,
`FORMAT=GT:DP:`, a sample value like `0/1:`, or `INFO=AD=1,,2` — is also lenient rather than strict when it can be
recovered without changing field alignment. See [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md) for the full
field-by-field table of what each case normalizes to and why.

Typed metadata accessors (e.g. `InfoMetadata.getType()` and `getNumber()`) are annotated `@Nullable` and return `null`
when the corresponding metadata was malformed.


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

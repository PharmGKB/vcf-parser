# Overview

[![Build Status](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml/badge.svg)](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml)
[![codecov.io](https://codecov.io/github/PharmGKB/vcf-parser/coverage.svg?branch=main)](https://codecov.io/github/PharmGKB/vcf-parser?branch=main)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser)

This is a streaming parser for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format). It validates record
structure strictly but is deliberately lenient about the quality of metadata declarations — see
[Validation: strict vs. lenient](#validation-strict-vs-lenient) below.

The main parser class ([`VcfParser`](src/main/java/org/pharmgkb/parser/vcf/VcfParser.java)) is responsible for reading all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out [`VcfParserTest.java`](src/test/java/org/pharmgkb/parser/vcf/VcfParserTest.java) for a quick and dirty view of it in action.

[`MemoryMappedVcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/MemoryMappedVcfLineParser.java) is an implementation of `VcfLineParser` that reads everything into memory.

**VCF version support:** `##fileformat` accepts any `VCFv<major>.<minor>` at or above `4.0`; the parser does not
hard-code a version ceiling. It was written against, and its reserved INFO/FORMAT/ALT/structural-variant definitions
reflect, VCF 4.1/4.2. Later versions generally parse correctly too — an unrecognized reserved key or `Number` value is
stored as a plain string rather than rejected — but version-specific features introduced after 4.2 (e.g.
percent-encoding, local alleles) are not specially interpreted.


## Validation: strict vs. lenient

The parser draws a deliberate line between the **structure of a VCF record**, which it validates strictly, and the
**quality of metadata declarations**, which it validates leniently.

**Strict — throws `VcfFormatException`.** The parser rejects a file whose structure or mandatory record fields are
invalid:

- A missing, duplicate, or non-first `##fileformat`; a version below the `VCFv4.0` floor; any line other than a `##`
  metadata line before the `#CHROM` header; or a `#`-prefixed line after it (VCF has no comment syntax).
- A missing `#CHROM` header, wrong fixed column names, a missing `FORMAT` column when samples are present, or duplicate
  sample names.
- A data line with the wrong number of tab-separated columns.
- An empty mandatory fixed field (the missing value must be `.`), an empty `CHROM`, or a negative `POS`.
- Invalid `REF`/`ALT` bases; an `ALT` missing value (`.`) combined with a real allele; whitespace where the spec forbids
  it (`CHROM`, `ID`, `FILTER`, `INFO`); a duplicate identifier within a single `ID` field; a `FILTER` of `0` or `PASS`
  combined with other filters; or a `FORMAT` in which `GT` is present but not the first sub-field.
- A sample with more sub-fields than its `FORMAT` declares (trailing sub-fields may be dropped, but not added).
- An invalid genotype passed to `VcfGenotype`, a malformed or out-of-range `GT` allele index looked up through
  `MemoryMappedVcfDataStore`, or a failed conversion when a typed value is requested.
- A key or value set through `BaseMetadata`'s, `VcfSample`'s, or `VcfMetadata`'s mutators (e.g. a `Description`, a
  sample's `GT` value, or an `##assembly` line) containing a line terminator.
- A `VcfSample` key or value set through its constructors or `putProperty` containing a colon or tab, which would
  otherwise add a spurious FORMAT sub-field or sample column when written back out.
- `VcfWriter.writeLine` is given a number of samples that disagrees with the header's declared sample count, or any
  FORMAT/sample data at all when the header declares no samples: the output would not parse back against its own
  header.

These checks run when a `VcfPosition` is constructed (including by the parser). Its setters and the mutable lists
returned by its accessors (e.g. `getAltBases()`, `getFilters()`) do *not* re-run them, to support transformation
pipelines that mutate a position in place; call `VcfPosition.validate()` after such mutations to re-check validity —
this also re-normalizes a lone `PASS` or `.` `FILTER` value left by such a mutation, matching construction.

**Lenient — logs a warning and keeps parsing.** Malformed *metadata declarations* (`##INFO`, `##FORMAT`, `##contig`,
`##FILTER`, `##ALT`, ...) warn rather than throw, and the declaration is still stored:

- A missing or invalid `Number`, `Type`, or `Description`; an unquoted `Description`; or `Type=Flag` with a `Number`
  other than `0`. An unparseable `Type` leaves the metadata's type `null`.
- A header/metadata sample-count mismatch, or a sample named in the header but absent from the metadata.
- The writer's consistency checks (a value that does not match its declared type, a record referring to undeclared
  `FILTER`/`INFO`/`FORMAT` metadata, a missing or extra sample sub-field, and similar).

An empty ("zero-length") entry in a delimited record field — e.g. `ID=rs1;;rs2`, `ALT=T,`, `FILTER=q10;`,
`FORMAT=GT:DP:`, a sample value like `0/1:`, or `INFO=AD=1,,2` — is also lenient rather than strict, for the same
reason: bcftools, the reference implementation most of the ecosystem treats as canonical, tolerates all of these in
practice. See [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md) for the full field-by-field table of what each
case normalizes to and why.

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

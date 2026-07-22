# Overview

[![Build Status](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml/badge.svg)](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml)
[![codecov.io](https://codecov.io/github/PharmGKB/vcf-parser/coverage.svg?branch=main)](https://codecov.io/github/PharmGKB/vcf-parser?branch=main)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser)

This is a streaming parser for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format) 4.1/4.2. It validates record
structure strictly but is deliberately lenient about the quality of metadata declarations — see
[Validation: strict vs. lenient](#validation-strict-vs-lenient) below.

The main parser class ([`VcfParser`](src/main/java/org/pharmgkb/parser/vcf/VcfParser.java)) is responsible for reading all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out [`VcfParserTest.java`](src/test/java/org/pharmgkb/parser/vcf/VcfParserTest.java) for a quick and dirty view of it in action.

[`MemoryMappedVcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/MemoryMappedVcfLineParser.java) is an implementation of `VcfLineParser` that reads everything into memory.


## Validation: strict vs. lenient

The parser draws a deliberate line between the **structure of a VCF record**, which it validates strictly, and the
**quality of metadata declarations**, which it validates leniently.

**Strict — throws `VcfFormatException`.** The parser rejects a file whose structure or mandatory record fields are
invalid:

- A missing, duplicate, or non-first `##fileformat`; a version below the `VCFv4.0` floor; or any line other than a `##`
  metadata line before the `#CHROM` header.
- A missing `#CHROM` header, wrong fixed column names, a missing `FORMAT` column when samples are present, or duplicate
  sample names.
- A data line with the wrong number of tab-separated columns.
- An empty mandatory fixed field (the missing value must be `.`), an empty `CHROM`, or a negative `POS`.
- Invalid `REF`/`ALT` bases; whitespace where the spec forbids it (`CHROM`, `ID`, `FILTER`, `INFO`); a duplicate
  identifier within a single `ID` field; a `FILTER` of `0` or `PASS` combined with other filters; or a `FORMAT` in which
  `GT` is present but not the first sub-field.
- A sample with more sub-fields than its `FORMAT` declares (trailing sub-fields may be dropped, but not added).
- An invalid genotype passed to `VcfGenotype`, or a failed conversion when a typed value is requested.

**Lenient — logs a warning and keeps parsing.** Malformed *metadata declarations* (`##INFO`, `##FORMAT`, `##contig`,
`##FILTER`, `##ALT`, ...) warn rather than throw, and the declaration is still stored:

- A missing or invalid `Number`, `Type`, or `Description`; an unquoted `Description`; or `Type=Flag` with a `Number`
  other than `0`. An unparseable `Type` leaves the metadata's type `null`.
- A header/metadata sample-count mismatch, or a sample named in the header but absent from the metadata.
- The writer's consistency checks (a value that does not match its declared type, a record referring to undeclared
  `FILTER`/`INFO`/`FORMAT` metadata, a missing or extra sample sub-field, and similar).

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

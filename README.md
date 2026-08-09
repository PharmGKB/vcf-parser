# Overview

[![Build Status](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml/badge.svg)](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml)
[![codecov.io](https://codecov.io/github/PharmGKB/vcf-parser/coverage.svg?branch=main)](https://codecov.io/github/PharmGKB/vcf-parser?branch=main)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser)

This is a Java 17 streaming parser and writer for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format),
requiring no runtime configuration beyond what's shown below. The target specification is VCF 4.2;
representable VCF 4.x input from before and after that version is accepted, using VCF 4.2 semantics where
versions differ or input is ambiguous (see `AGENTS.md`'s Spec Ambiguity Decision Hierarchy for how those
decisions get made).

It validates record structure strictly but defers validation of non-structural content (INFO, FILTER,
FORMAT, sample values, and metadata declarations) for performance -- see
[Validation](#validation) below for a summary and where to find the full itemized reference.

- [Reading a file](#reading-a-file)
- [Writing a file](#writing-a-file)
- [Validation](#validation)
- [`VcfContext`: building VCF objects yourself](#vcfcontext-building-vcf-objects-yourself)
- [`VcfWarnings`: explicit warning routing](#vcfwarnings-explicit-warning-routing)
- [Conformance and dependency tests](#conformance-and-dependency-tests)
- [Get It](#get-it)


## Reading a file

The main parser class ([`VcfParser`](src/main/java/org/pharmgkb/parser/vcf/VcfParser.java)) reads all
metadata and constructs a [`VcfRecord`](src/main/java/org/pharmgkb/parser/vcf/VcfRecord.java) for each data line
-- its [`VcfPosition`](src/main/java/org/pharmgkb/parser/vcf/VcfPosition.java) plus the per-sample
[`VcfSample`](src/main/java/org/pharmgkb/parser/vcf/VcfSample.java)s in column order; actual handling of each line
is delegated to an implementation of
[`VcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/VcfLineParser.java) that the caller supplies.
Reserved INFO/FORMAT keys (e.g. `AC`, `DP`, `GT`, `PL`) are exposed as typed values through
[`ReservedInfoProperty`](src/main/java/org/pharmgkb/parser/vcf/ReservedInfoProperty.java)/
[`ReservedFormatProperty`](src/main/java/org/pharmgkb/parser/vcf/ReservedFormatProperty.java), via
`VcfPosition.getInfo(ReservedInfoProperty)`/`VcfSample.getProperty(ReservedFormatProperty)`.

```java
try (VcfParser parser = new VcfParser.Builder()
    .fromFile(path)
    .parseWith((metadata, record) -> {
      // handle one data line: record.getPosition(), record.getSamples()
    })
    .build()) {
  parser.parse();
}
```

Check out [`VcfParserTest.java`](src/test/java/org/pharmgkb/parser/vcf/VcfParserTest.java) for a quick and
dirty view of it in action.

[`MemoryMappedVcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/MemoryMappedVcfLineParser.java) is an example
`VcfLineParser` implementation, showing how to read an entire file into memory for constant-time record lookup; it
is not part of the core parser. VCF permits multiple records at the same locus, so each locus is stored as a list of
records, and every record seen at a locus is kept with no configuration needed. A repeated `ID`, by contrast, is not
a normal VCF occurrence, so it remains single-valued and `Builder.setDuplicateIdHandler` controls whether it's
rejected or which record wins.


## Writing a file

[`VcfWriter`](src/main/java/org/pharmgkb/parser/vcf/VcfWriter.java) writes a `VcfMetadata` header followed by
each data line. Where `VcfParser` hands you a single `VcfRecord`, `writeLine` takes its `VcfPosition` and
sample list as loose arguments:

```java
try (VcfWriter writer = new VcfWriter.Builder().toFile(path).build()) {
  writer.writeHeader(metadata);
  writer.writeLine(metadata, position, samples);
}
```

The default path is optimized for data written directly after parsing; build with
`VcfWriter.Builder.validateBeforeWrite()` for full structural and semantic diagnostics against
data that may have been mutated first -- see [Validation](#validation) below for the difference.


## Validation

The parser draws a deliberate line between the **structure of a VCF record**, which it validates strictly, and
**non-structural content**, which it may validate lazily: a structural violation (a malformed `#CHROM` header,
wrong column counts, invalid mandatory fixed-field structure, a duplicate sample name, invalid REF/ALT syntax,
a negative `POS`) throws `VcfFormatException` immediately, while non-structural content that's recoverable
(a malformed metadata attribute, an out-of-range reserved value, an empty delimited-field entry) is preserved
or normalized with a warning instead, deferred until the relevant getter or typed conversion is used.

See [`VALIDATION_REFERENCE.md`](VALIDATION_REFERENCE.md) for the full itemized list of exactly what throws vs.
warns, with spec/hts-specs/bcftools/vcf_validator citations for every non-obvious case, and
[`VALIDATION_PHILOSOPHY.md`](VALIDATION_PHILOSOPHY.md) for the architectural rationale behind the split.
[`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md) and
[`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md) cover two specific recurring cases
(an empty delimited-field entry; a duplicate or missing metadata declaration ID) in full per-field/per-type
detail.


## `VcfContext`: building VCF objects yourself

Most callers never construct a model object by hand -- `VcfParser` hands you finished ones. When you do need
to (building a file from scratch, or a synthetic record for a test), everything is created through a
[`VcfContext`](src/main/java/org/pharmgkb/parser/vcf/VcfContext.java): one instance per file, carrying its
`##fileformat`, its warning destination, and whether a recoverable content violation warns or throws.

Every model object (`VcfPosition`, `VcfSample`, `VcfRecord`, and the metadata layer) is immutable once
constructed -- there are no setters. To derive a changed copy of a `VcfPosition`, `VcfSample`, or `VcfMetadata`,
call its `toBuilder()`, change what you need on the resulting builder, and call `build()`.
`VcfRecord` itself has no `toBuilder()` -- rebuild it from a changed `VcfPosition`/sample list via
`VcfContext.newRecord` instead.

```java
VcfContext ctx = VcfContext.strict("VCFv4.2");     // or VcfContext.lenient(fileFormat, warnings)
VcfPosition position = ctx.newPosition("chr1", 100, null, "A", List.of("T"), null, null, null,
    List.of("GT"), -1);
VcfSample sample = ctx.newSample(List.of("GT"), List.of("0/1"));
VcfRecord record = ctx.newRecord(position, List.of(sample));
VcfMetadata metadata = new VcfMetadata.Builder(ctx)
    .addInfo("DP", "Total depth", InfoType.Integer, "1", null, null)
    .setColumns(columns)
    .build();
```

`strict` rejects a recoverable content violation outright; `lenient` warns and recovers, which is what
`VcfParser` uses for a real file. The choice belongs to the context, not to which overload you call -- the
same invalid content behaves the same way whether you pass typed values or a raw property map. Every object
made this way is bound to that one context, and mixing objects from two different contexts into one record or
one `VcfMetadata` is rejected.


## `VcfWarnings`: explicit warning routing

Every lenient (warn-and-recover) code path above needs somewhere to send its warning. By default, that's
this library's shared SLF4J logger (`org.pharmgkb.parser.vcf.VcfWarnings`) -- but a caller can redirect it
explicitly by constructing a [`VcfWarnings`](src/main/java/org/pharmgkb/parser/vcf/VcfWarnings.java) instance
(backed by any `Writer`, or via the `openFile(Path)` convenience factory) and passing it to
`VcfContext.lenient(fileFormat, warnings)`, the context every model object it creates then inherits it from.
`VcfParser.Builder.warningsToFile()` and
`VcfWriter.Builder.warningsToFile()` build and manage one of these internally for their own parse/write call --
most callers never need to construct a `VcfWarnings` by hand at all.

A `VcfPosition`/`VcfSample`/metadata declaration's warning destination is fixed at construction, from its own
context, for its whole lifetime -- nothing re-points it afterward, including a `VcfWriter` it is later handed
to. A `VcfWriter`'s own `warningsToFile()` only captures diagnostics the writer generates itself (e.g. an
undeclared INFO/FORMAT key); a lazy content warning from an object built under a different context still goes
to that object's own context's sink, never to the writer's destination. A caller who wants every warning
collected in one place must build every object under the same context up front.


## Conformance and dependency tests

Two opt-in Gradle tasks verify this library's behavior against outside references, beyond the required
`./gradlew test` (which stays network- and external-binary-free):

- **`./gradlew htsSpecsTest`** runs this parser and writer against
  [hts-specs](https://github.com/samtools/hts-specs)' official VCF conformance fixture suite, pinned to a
  specific commit and compared against a committed baseline of already-accepted divergences. Requires network
  access. See [`HTS_SPECS_BASELINE_STATUS.md`](HTS_SPECS_BASELINE_STATUS.md) for the current baseline's
  contents and how to update it after a deliberate behavior change.
- **`./gradlew bcftoolsDependencyTest`** runs against a real, installed `bcftools` binary to verify every
  decision this library has made based on bcftools's specific current behavior. Requires a local `bcftools`
  on `PATH`; no network access. See [`BCFTOOLS_VERSION_DEPENDENCIES.md`](BCFTOOLS_VERSION_DEPENDENCIES.md)
  for the index of every decision this verifies.


## Get It

Requires Java 17 or later. Licensed under the [Mozilla Public License 2.0](LICENSE).

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

## Project Overview

This repository is a Java 17 library for high-performance, streaming parsing and writing of VCF 4.x data.
It prioritizes preserving and usefully exposing VCF data rather than rejecting every specification violation during initial parsing.  

Target VCF specification: 4.2

The library accepts VCF 4.x input from before and after the target version where it can be represented by this library. When VCF versions differ or an input is ambiguous, behavior is interpreted according to target VCF specification semantics. Features that cannot be represented safely under those semantics may be warned about, normalized, or rejected according to the parser and writer policies below.

The goal is maximum practical compliance with the target VCF specification while preserving and usefully exposing recoverable VCF 4.x data.  "Strict" in this project means structural correctness and transparent diagnostics, not eager rejection of every semantic VCF violation.

When newer 4.x VCF specifications clarify an ambiguity not resolved by the target VCF specification, use them as guidance. Do not let newer-version rules override target VCF specification semantics for a conflict.

If still in doubt, consult bcftools behavior.  Decisions made this way should be documented and have a regression fixture demonstrating the behavior.  If bcftools was consulted, include bcftools version.

Minimum supported bcftools version: 1.18.

VCF specifications can be found at https://github.com/samtools/hts-specs.  Use the `.tex` instead of `.pdf` formatted files.


### Parser Validation Policy

The parser is intentionally permissive for non-structural VCF violations in performance-sensitive fields, including INFO, FILTER, FORMAT, sample values, and their metadata declarations.

- Do not require eager validation of these fields during `parse()` or `parseNextLine()`.
- Validation may be deferred until the relevant getter or typed conversion is used.
- When invalid content is encountered during parsing or deferred access, emit a warning that identifies the non-compliance and preserve or normalize the value in the least surprising usable form.
- Report a parser finding only when invalid content is silently accepted, corrupts/misaligns data, prevents later detection when the relevant value is accessed.
- Do not report warning-and-recovery behavior as a defect merely because the current target VCF specification would require rejection.
- Structural violations that make record boundaries or column alignment ambiguous remain errors: malformed #CHROM header structure, wrong column counts, invalid mandatory fixed-field structure, duplicate sample IDs, invalid REF/ALT syntax, negative POS, and invalid delimiter handling that changes field alignment.



### Writer Validation and Round-Trip Policy

Writer output is governed by the semantics of the target VCF specification. A writer must not emit a `fileformat` declaration for another VCF version unless the emitted output is also valid under that declared version.

A record accepted by the parser must be writable without losing fields or producing malformed column structure.

- The default writer path is performance-sensitive. It assumes metadata, positions, and samples have not been mutated into an invalid state after parsing, and must preserve parser-normalized values and maintain record/sample column structure without full revalidation.
- The writer may canonicalize parser-recovered invalid input (for example, an empty recoverable value normalized to `.`), but must preserve the usable meaning and emit a syntactically parseable VCF record.
- `VcfWriter.Builder.validateBeforeWrite()` is the full-output diagnostics mode. Before every write, it must validate metadata, positions, FORMAT keys, INFO fields, and sample values sufficiently to ensure structural validity of the emitted header and records and report all detected semantic non-compliance.
- In full-output diagnostics mode, content that is semantically non-compliant but can be represented safely must emit a warning identifying the issue and be preserved or canonicalized. Content that would make output structurally invalid or unparseable — including incorrect column count, invalid delimiter placement, line terminators, invalid mandatory fixed fields, or sample data inconsistent with FORMAT — must be rejected with `VcfFormatException`.
- Callers that mutate parsed data and require a full structural-validity and diagnostic guarantee must enable `validateBeforeWrite()`.
- Reviewers should report a default-writer issue when an unmodified parser-produced record cannot be written and parsed again without field loss or structural corruption. They should report a full-output-diagnostics issue when `validateBeforeWrite()` permits structurally invalid output or fails to issue a warning for detected semantic non-compliance.


## Repository Guidelines

- Use the checked-in Gradle wrapper. The Node/Yarn files support semantic-release; they are not the application build.
- **Keep parser changes streaming-friendly**. Do not load an entire VCF into memory unless the affected class explicitly provides an in-memory data store.

### Repository Layout

- `src/main/java/org/pharmgkb/parser/vcf/`: parser, writer, transformation, and data-store code.
- `src/main/java/org/pharmgkb/parser/vcf/model/`: VCF metadata, position, sample, and genotype models.
- `src/test/java/`: JUnit tests mirroring the production packages.
- `src/test/resources/`: small VCF fixtures used by parser and writer tests.
- `build.gradle` and `pgkb-build.gradle`: dependencies, Java version, tests, coverage, packaging, and publishing configuration.

### Build and Test

Run commands from the repository root.

```powershell
.\gradlew.bat test
```

Run a focused test while iterating:

```powershell
.\gradlew.bat test --tests "org.pharmgkb.parser.vcf.VcfParserTest"
```

On Unix-like systems, use `./gradlew` instead. The full `test` task is the required verification for code changes and also generates the JaCoCo report used by CI.

#### Code Conventions

- Match the existing Java style: two-space indentation, four-space continuation indentation, braces on control-flow statements, and two blank lines after imports.
- Preserve the established field prefixes (`m_` for instance fields and `s_`/`sf_` for static fields) in classes that use them.
- Use JSpecify annotations for explicit nullability and follow the surrounding API's collection/null conventions.
- Keep changes narrow. This is a published library, so avoid changing public behavior or signatures unless the task requires it.
- Preserve lazy parsing and allocation-conscious code in hot per-record paths unless tests and measurements justify a change.

### Tests and Fixtures

- Add or update a focused JUnit test for every behavior change or bug fix.
- Use JUnit Jupiter assertions and Hamcrest consistently with the surrounding test class.
- Put reusable VCF inputs in `src/test/resources` and load them through the existing test utilities.
- VCF delimiters are significant: preserve literal tabs, empty fields, header ordering, and line structure in fixtures.
- Cover successful parsing, accepted-with-warning recovery, deferred validation, and structural rejection as applicable. For parser-to-writer changes, add a round-trip test showing that accepted input can be written and parsed again without column loss or delimiter corruption.

### Release Files

- Versioning and changelog updates are managed by semantic-release.
- Do not manually edit `CHANGELOG.md`, the package version, or the README dependency version unless the task is specifically a release change.

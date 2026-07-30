## Project Overview

This repository is a Java 17 library for high-performance, streaming parsing and writing of VCF 4.x data.
It prioritizes preserving and usefully exposing VCF data rather than rejecting every specification violation during initial parsing.  

Target VCF specification: 4.2

The library accepts VCF 4.x input from before and after the target version where it can be represented by this library. When VCF versions differ or an input is ambiguous, behavior is interpreted according to target VCF specification semantics. Features that cannot be represented safely under those semantics may be warned about, normalized, or rejected according to the parser and writer policies below.

The goal is maximum practical compliance with the target VCF specification while preserving and usefully exposing recoverable VCF 4.x data.  "Strict" in this project means structural correctness and transparent diagnostics, not eager rejection of every semantic VCF violation.

When newer 4.x VCF specifications clarify an ambiguity not resolved by the target VCF specification, use them as guidance. Do not let newer-version rules override target VCF specification semantics for a conflict.

hts-specs also publishes an official VCF conformance fixture suite (`test/vcf/{version}/{passed,failed}/*.vcf`; wired into this project as the opt-in `./gradlew htsSpecsTest` task, see README). Treat this suite as part of the spec: a `failed/*.vcf` fixture's `##CauseOfFailure=` line documents the spec authors' own interpretation of what makes a file invalid, and is authoritative guidance on the same footing as spec prose or a newer-version clarifying table -- consult it when the target VCF specification's text is ambiguous or silent.

Before treating a baseline entry as a new problem to fix, check `HTS_SPECS_BASELINE_STATUS.md` -- it catalogs every current baseline entry's status (permanently accepted / filed upstream / candidate for filing) and is bound to a specific `htsSpecsPinnedSha`; treat it as stale if that pin has changed since.

Before treating any other code-quality observation as a new finding, check `WONT_FIX.md` -- it catalogs known issues deliberately left unaddressed, with the reasoning, so they aren't rediscovered and re-proposed.

If still in doubt, consult bcftools behavior.  Decisions made this way should be documented and have a regression fixture demonstrating the behavior.  If bcftools was consulted, include bcftools version.

When hts-specs itself is silent (no fixture covers the exact case), running vcf_validator (the EBI reference validator; the tool hts-specs itself uses to generate its `passed`/`failed` corpus) directly against a hand-built input is informative but is *not* equivalent to a documented hts-specs fixture -- cite it as a direct tool run ("vcf_validator `<version>`"), not as "the suite says." vcf_validator tends to be stricter than bcftools on zero-length-field cases in structural fields (ID/FILTER/ALT/INFO) but does not apply the same rigor to sample-column sub-structure (GT, reserved FORMAT values) -- don't treat a vcf_validator "invalid" verdict there as load-bearing. When bcftools and vcf_validator disagree, bcftools remains the tie-breaker per the hierarchy above, but document the disagreement rather than silently picking a side.

When code behavior is guided by a specific spec passage (a table, a sentence, a worked example) rather than being self-evident from the VCF column/record structure, cite it in a code comment specific enough that a future agent can find the exact passage without re-deriving it: name the spec file (e.g. `VCFv4.3.tex`), and the section/table/example it comes from or a short verbatim quote -- not just "per the VCF spec" or a bare version number. When the target VCF specification is silent and a later version is used as clarifying guidance (per the paragraph above), say so explicitly in the comment (e.g. "VCFv4.2 does not table this; VCFv4.3's Reserved Genotype Keys table gives Number=G") so the reasoning survives independently of whichever agent wrote it. The same specificity requirement applies when hts-specs' conformance suite is the source of a decision: name the fixture path (e.g. `test/vcf/4.2/failed/failed_meta_alt_001.vcf`) and quote or summarize its `##CauseOfFailure=` line -- not just "per hts-specs" or "the conformance suite requires it".

Minimum supported bcftools version: 1.18.

VCF specifications can be found at https://github.com/samtools/hts-specs.  Use the `.tex` instead of `.pdf` formatted files.


### Parser Validation Policy

The parser is intentionally permissive for non-structural VCF violations in performance-sensitive fields, including INFO, FILTER, FORMAT, sample values, and their metadata declarations.

- Do not require eager validation of these fields during `parse()` or `parseNextLine()`.
- Validation may be deferred until the relevant getter or typed conversion is used.
- When invalid content is encountered during parsing or deferred access, emit a warning that identifies the non-compliance and preserve or normalize the value in the least surprising usable form.
- Report a parser finding only when invalid content is silently accepted, corrupts/misaligns data, prevents later detection when the relevant value is accessed.
- Do not report warning-and-recovery behavior as a defect merely because the current target VCF specification would require rejection.
- Structural violations that make record boundaries or column alignment ambiguous remain errors: malformed #CHROM header structure, wrong column counts, invalid mandatory fixed-field structure, duplicate sample IDs, invalid REF/ALT syntax, negative POS, and invalid delimiter handling that changes field alignment. This is the floor, not the ceiling: a throw in an otherwise-permissive field is also justified when a value collides with a reserved, spec-defined meaning rather than merely being malformed freeform content. Every such exception needs the same citation discipline as any other decision in this file (spec text, an hts-specs fixture, or bcftools behavior) at its point of enforcement in the code, and should appear in README's strict/lenient breakdown. This file doesn't try to enumerate every exception -- finding one that's cited but not named here isn't a policy violation; finding one with no citation anywhere is.
- A duplicate metadata-declaration ID (`##INFO`/`##FORMAT`/`##FILTER`/`##ALT`/`##contig`/`##SAMPLE`, and `##PEDIGREE` when it declares one) warns and keeps the first declaration, matching bcftools -- never keep-last/overwrite. This applies uniformly regardless of when the duplicate is introduced: at parse time, via `VcfMetadata.Builder`, or via later programmatic mutation through `VcfMetadata`'s own instance methods. Do not introduce divergent duplicate-ID handling for a new declaration type or entry point without a documented, tool- or spec-backed reason (see `DUPLICATE_METADATA_ID_HANDLING.md`).
- Before choosing to drop vs. substitute-with-`.` for an empty entry in any list-like field, check whether another field indexes into that list positionally (e.g. `GT` indexes into `ALT`; FORMAT sub-field position matches sample values by index). Dropping an entry shifts every later index -- this can silently misdirect a lookup to the wrong element with no error at the point of misuse. When such a cross-reference exists, substitute the missing-value sentinel instead of dropping, even if the field wasn't previously known to have one.
- A zero-length entry produced by a delimiter within an otherwise well-formed field (ID/FILTER/ALT/INFO/FORMAT keys, sample values) is normalized per a documented, field-specific strategy -- dropped, substituted with `.`, or (FORMAT's own key list only) kept as-is with just a warning -- see `EMPTY_FIELD_HANDLING.md` before inventing a new rule for a field not already covered there.



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
- Commit messages use conventional-commits style (`type(scope): summary`). `scope` is drawn from `parser`, `writer`, and `memoryMappedImpl` -- never a class name. `org.pharmgkb.parser.vcf.model` classes use `parser` (there is no separate `model` scope); `VcfWriter.java` and its `Builder` use `writer`; `MemoryMappedVcfDataStore.java`/`MemoryMappedVcfLineParser.java` use `memoryMappedImpl`, including doc-only changes to either. A commit touching more than one of these areas may list multiple, comma-separated (e.g. `type(parser,writer): summary`), rather than picking one to cover the rest.

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

### Code Conventions

- Match the existing Java style: two-space indentation, four-space continuation indentation, braces on control-flow statements, and two blank lines after imports.
- Preserve the established field prefixes (`m_` for instance fields and `s_`/`sf_` for static fields) in classes that use them.
- Use JSpecify annotations for explicit nullability and follow the surrounding API's collection/null conventions.
- Keep changes narrow. This is a published library, so avoid changing public behavior or signatures unless the task requires it.
- Preserve lazy parsing and allocation-conscious code in hot per-record paths unless tests and measurements justify a change.
- Helpers that normalize a `List` in place (e.g. `VcfUtils.fillEmptyEntriesWithDot`) mutate their argument directly and require a mutable list. Callers must never pass a constructor/method parameter straight through to such a helper -- defensively copy first (`new ArrayList<>(list)`), since external callers may supply an immutable list (`List.of(...)`) or one they don't expect to be mutated (`Arrays.asList(...)`).
- JDK immutable collections (`List.of(...)`, `Set.of(...)`, `Map.copyOf(...)`, `Collectors.toUnmodifiableMap(...)`) null-check lookup arguments and throw `NullPointerException` from `.contains(null)`/`.get(null)`/`.indexOf(null)`, unlike `HashMap`/`ArrayList`, which tolerate `null` there. For a lookup over a fixed, known domain where `null` trivially isn't a match (e.g. `isReservedMetadataKey`, `getColumnIndex`), guard the lookup explicitly rather than letting this throw. Prefer `Collections.unmodifiableList(Arrays.asList(...))` over `List.of(...)` only when wrapping data provably owned by no one else (e.g. a varargs parameter's freshly-allocated array) -- it is zero-copy and null-tolerant, but does not defensively copy, so it is unsafe for data a caller might still hold a mutable reference to.
- String case-folding used for parsing or dispatch logic (`toUpperCase()`/`toLowerCase()`) must use `Locale.ROOT` explicitly (`value.toUpperCase(Locale.ROOT)`), never the default-locale overload -- under a Turkish/Azeri locale, default-locale case-folding on `I`/`i` produces different code points than ASCII expects, which can silently break `String.equals`-based dispatch or comparison.

### Tests and Fixtures

- Add or update a focused JUnit test for every behavior change or bug fix.
- Use JUnit Jupiter assertions and Hamcrest consistently with the surrounding test class.
- Put reusable VCF inputs in `src/test/resources` and load them through the existing test utilities.
- VCF delimiters are significant: preserve literal tabs, empty fields, header ordering, and line structure in fixtures.
- Cover successful parsing, accepted-with-warning recovery, deferred validation, and structural rejection as applicable. For parser-to-writer changes, add a round-trip test showing that accepted input can be written and parsed again without column loss or delimiter corruption.

### Release Files

- Versioning and changelog updates are managed by semantic-release.
- Do not manually edit `CHANGELOG.md`, the package version, or the README dependency version unless the task is specifically a release change.

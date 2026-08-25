## Scope and precedence

These are repository-wide instructions for the Java VCF parser/writer library. Follow
the user's request first; use the rules below for decisions the request does not
specify. Preserve unrelated worktree changes, keep review/audit requests read-only,
and do not create commits or external changes unless asked.

The specialized documents below are the source of truth for their subjects. Keep this
file as a routing document and durable workflow contract; put detailed rule tables,
tool measurements, and implementation inventories in those documents instead of
duplicating them here.

## Project overview

This is a Java 17 library for high-performance, streaming parsing and writing of VCF
4.x data. The target specification is VCF 4.3, but input is interpreted under its
declared version where a VCF rule genuinely changes meaning. Unsupported declared
versions fall back to the nearest implemented version. Otherwise, target VCF 4.3
semantics apply. The library preserves recoverable violations rather than rejecting
every semantic defect during initial parsing.

The minimum supported bcftools version is 1.18. VCF specifications are in the
`.tex` files from [hts-specs](https://github.com/samtools/hts-specs), not the PDFs.

## Spec Ambiguity Decision Hierarchy

Before investigating a new issue, check the document that owns it:

- [HTS_SPECS_BASELINE_STATUS.md](HTS_SPECS_BASELINE_STATUS.md): current hts-specs
  conformance baseline and its pinned checkout.
- [WONT_FIX.md](WONT_FIX.md): deliberately unresolved code-quality observations.
- [VCF_SPEC_AMBIGUITIES.md](VCF_SPEC_AMBIGUITIES.md): decisions that required tool
  behavior to resolve a spec ambiguity.
- [VALIDATION_PHILOSOPHY.md](VALIDATION_PHILOSOPHY.md): architectural validation
  principles and declared-version dispatch.
- [VALIDATION_REFERENCE.md](VALIDATION_REFERENCE.md): itemized throw/warn behavior
  and citations.
- [EMPTY_FIELD_HANDLING.md](EMPTY_FIELD_HANDLING.md),
  [DUPLICATE_METADATA_ID_HANDLING.md](DUPLICATE_METADATA_ID_HANDLING.md), and
  [STRUCTURAL_DELIMITER_HANDLING.md](STRUCTURAL_DELIMITER_HANDLING.md): narrow
  behavior references for those topics.
- [BEHAVIOR_TABLE_STANDARD.md](BEHAVIOR_TABLE_STANDARD.md): requirements for
  measured spec/tool behavior tables.
- [BCFTOOLS_VERSION_DEPENDENCIES.md](BCFTOOLS_VERSION_DEPENDENCIES.md): decisions
  that must be rechecked against bcftools upgrades.

When the spec and official fixtures are silent or genuinely ambiguous, derive the
case list from the relevant parser source, then run the installed tool and record its
version, probe shape, controls, and result. bcftools acceptance is evidence of
leniency, not proof of validity; bcftools rejection is important for output
interoperability. Do not use bcftools to override explicit spec text. Preserve a
decision in the appropriate reference document and add a regression fixture or
dependency test when the decision affects behavior.

For external-tool work, verify that checked-out source matches the binary being run.
The installed binary answers “what does this version do?”; source answers “what rule
does it implement?” Neither alone establishes a complete grammar. See
[BEHAVIOR_TABLE_STANDARD.md](BEHAVIOR_TABLE_STANDARD.md) for the evidence workflow,
including known `vcf_validator` limitations.

The angle-bracket decision is the canonical example: angle brackets are not a VCF
quoting form, so this library does not implement htslib's extra nesting tolerance.
The full measured treatment is in [VCF_SPEC_AMBIGUITIES.md](VCF_SPEC_AMBIGUITIES.md)
and [STRUCTURAL_DELIMITER_HANDLING.md](STRUCTURAL_DELIMITER_HANDLING.md).

## Documentation Map

Add new content to whichever document above would answer the matching question in
the Decision Hierarchy; do not grow this file or `README.md` with it. `README.md`
stays user-facing only -- no spec citations, no tool-version-pinned behavior, no
itemized throw/warn rules.

A rename (a class, method, or other identifier named in prose, not just in code) is
not finished until every doc in the Decision Hierarchy above, plus `README.md`, is
checked for the old name.

## Spec Citation Discipline

When code behavior depends on a spec passage, cite the spec file and section/table
or include a short quote. If line numbers are used, include the quote because pinned
line numbers can move; read the current surrounding lines before citing rather than
copying a number from a plan or another comment. Cite hts-specs fixtures by path and
their `##CauseOfFailure=` meaning. Put the durable citation and decision in the
relevant reference document.

A citation to an older spec version remains valid for a rule that version still
states; re-point or dual-cite it only when the surrounding reasoning is itself
version-relative (e.g. an argument from that version's silence).

## Parser Validation Policy

The parser is permissive for recoverable semantic violations in INFO, FILTER,
FORMAT, sample values, and metadata declarations. It warns, preserves or
normalizes usable data, and may defer validation until a getter or typed conversion.
It throws for structural defects that make record boundaries or column alignment
ambiguous, such as malformed headers, wrong column counts, invalid mandatory fixed
fields, duplicate sample names, invalid REF/ALT structure, negative POS, or unsafe
delimiter handling. Reserved meanings may justify a throw even inside an otherwise
permissive field. See [VALIDATION_PHILOSOPHY.md](VALIDATION_PHILOSOPHY.md) and
[VALIDATION_REFERENCE.md](VALIDATION_REFERENCE.md) for the complete contract.

Report a parser defect only when invalid content is silently accepted, corrupts or
misaligns data, or blocks later detection when the value is accessed -- not merely
because the target spec would require rejection of content this library warns about
and recovers.

Duplicate metadata IDs keep the first declaration and warn. Comparisons and
validation of metadata property values use their unquoted content; raw encoding is
opt-in. See [DUPLICATE_METADATA_ID_HANDLING.md](DUPLICATE_METADATA_ID_HANDLING.md)
and [VALIDATION_REFERENCE.md](VALIDATION_REFERENCE.md).

### Unquoting before internal use

Every internal comparison or validation of a metadata property reads the unquoted
value, never the raw header encoding. Public typed getters return unquoted values;
raw access is explicitly opt-in.

When a delimited list is positionally indexed by another field, do not drop an empty
entry if doing so would shift indexes; use the field's documented missing-value
strategy in [EMPTY_FIELD_HANDLING.md](EMPTY_FIELD_HANDLING.md).

## Writer Validation and Round-Trip Policy

The default writer is performance-sensitive and preserves parser-produced usable
content without full semantic revalidation. A parsed record must remain writable
without field loss or malformed column structure. `validateBeforeWrite()` performs
full output diagnostics: preserve or canonicalize safely representable semantic
violations with warnings, but reject structurally unparseable output with
`VcfFormatException`. Null-safety is separate from semantic revalidation and always
applies to production writer paths. See [VALIDATION_PHILOSOPHY.md](VALIDATION_PHILOSOPHY.md)
and [VALIDATION_REFERENCE.md](VALIDATION_REFERENCE.md).

Content originated through public construction is subject to the bcftools output
floor; malformed content carried forward from parsing may be re-emitted faithfully,
provided the output remains readable. Round-trip tests must cover parser recovery
and any writer behavior that changes structure.

Report a default-writer defect when an unmodified parsed record cannot be written
and reparsed without field loss or structural corruption. Report a diagnostics
defect when `validateBeforeWrite()` permits structurally invalid output or omits a
warning for semantic non-compliance it should have caught.

## Public API Null-Safety Policy

Public methods that construct or mutate VCF content reject null content immediately
with `VcfFormatException`. Lookup-by-key methods follow their existing not-present
contract. Required caller-configuration collaborators retain the established raw
`NullPointerException` convention. Package-private trusted factories and explicitly
unsupported internal APIs are implementation details, not public contracts. The
complete exceptions and examples are in [VALIDATION_PHILOSOPHY.md](VALIDATION_PHILOSOPHY.md)
and [WONT_FIX.md](WONT_FIX.md).

## Repository workflow

- Use the checked-in Gradle wrapper. Node/Yarn files are for semantic-release, not
  the application build.
- Keep parser changes streaming-friendly and allocation-conscious in hot paths.
- Keep changes narrow; do not change public behavior or signatures unless requested.
- Always scope repository-root `rg` searches with `--glob '!build/**'`. Target
  `build/hts-specs-checkout/*.tex` explicitly when spec text is needed.
- An identifier grep is not a complete inventory. For signature or implementor
  changes, let `./gradlew compileJava compileTestJava` enumerate compiler errors and
  reconcile that result with source searches.
- Never use `git add -A`, `git add .`, or `git add -u`; stage explicit filenames.
- Keep behavior changes and design decisions isolated in commits. Bundle only
  independently trivial documentation/formatting changes. Use conventional commits
  with scopes `parser`, `writer`, `memoryMappedImpl`, and `spec`; model classes use
  `parser`. `spec` also covers a commit touching only a reference doc linked in the
  Decision Hierarchy above, not just the `spec` test-infrastructure directory.
- On a rename, follow the Documentation Map's rename-checklist rule above.
- Do not hand-edit `CHANGELOG.md`, package versions, or the README dependency version
  except for an explicit release task.

## Repository layout

- `src/main/java/org/pharmgkb/parser/vcf/`: parser, writer, data store, and model.
- `src/test/java/`: JUnit tests mirroring production packages.
- `src/test/resources/`: VCF fixtures and local conformance fixtures.
- `build.gradle` and `pgkb-build.gradle`: dependencies, tests, packaging, and release
  configuration.

## Build and test

Run commands from the repository root:

```sh
./gradlew test
./gradlew test --tests "org.pharmgkb.parser.vcf.VcfParserTest"
```

`./gradlew test` is required for code changes. Run the opt-in gates when relevant:

- `./gradlew htsSpecsTest` for the pinned hts-specs conformance corpus; inspect its
  reported counts and [HTS_SPECS_BASELINE_STATUS.md](HTS_SPECS_BASELINE_STATUS.md).
- `./gradlew bcftoolsDependencyTest` after adding or changing a bcftools-informed
  decision; inspect the reported re-verification count and
  [BCFTOOLS_VERSION_DEPENDENCIES.md](BCFTOOLS_VERSION_DEPENDENCIES.md).

Do not treat `BUILD SUCCESSFUL`, JUnit test counts, or an `UP-TO-DATE` task as proof
that an opt-in gate actually ran and verified its expected evidence.

## Code and test conventions

- Match the surrounding Java style: two-space indentation, four-space continuation
  indentation, braces on control-flow statements, and two blank lines after imports.
- Preserve established `m_`, `s_`, and `sf_` field prefixes where used.
- Use JSpecify annotations and the surrounding API's collection/null conventions.
- Use `Locale.ROOT` for parsing/dispatch case folding.
- Defensively copy caller-owned lists before passing them to in-place normalizers.
- Declare compile-time-only dependencies explicitly instead of relying on undeclared
  transitive dependencies; preserve the existing publication treatment.
- Add or update a focused JUnit test for every behavior change or bug fix. Add a
  parse/write round-trip test when output structure or recovered content is involved.
- Preserve literal tabs, empty fields, header ordering, and line structure in VCF
  fixtures. Local conformance fixtures must include a `##Authority=` line; see
  `LocalFixtureMetadata` and `LocalFixtureConformanceTest`.

On Windows, use `./gradlew.bat` equivalents.

## Tests and Fixtures

Reusable VCF inputs belong under `src/test/resources` and should be loaded through
the existing test utilities. Cover successful parsing, warning-and-recovery,
deferred validation, and structural rejection as applicable. For parser-to-writer
changes, verify that accepted input can be written and parsed again without column
loss or delimiter corruption.

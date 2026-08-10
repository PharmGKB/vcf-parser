## Project Overview

This repository is a Java 17 library for high-performance, streaming parsing and writing of VCF 4.x data.
It prioritizes preserving and usefully exposing VCF data rather than rejecting every specification violation during initial parsing.  

Target VCF specification: 4.2

The library accepts VCF 4.x input from before and after the target version where it can be represented by this library. A file is interpreted under the version its own `##fileformat` declares: where a rule's *meaning* genuinely differs across 4.x and both readings are valid, the declared version decides, not the target version -- today that is `FORMAT/GP` and `FORMAT/GLE`, and see `VALIDATION_PHILOSOPHY.md`'s "How the declared version is dispatched" for the mechanism and the `isAtLeast` discipline it must use. A declared version this library does not implement falls back to the nearest one it does. Where versions do not differ in meaning, or an input is ambiguous, behavior is interpreted according to target VCF specification semantics. Features that cannot be represented safely under those semantics may be warned about, normalized, or rejected according to the parser and writer policies below.

The goal is maximum practical compliance with the target VCF specification while preserving and usefully exposing recoverable VCF 4.x data.  "Strict" in this project means structural correctness and transparent diagnostics, not eager rejection of every semantic VCF violation.

When newer 4.x VCF specifications clarify an ambiguity not resolved by the target VCF specification, use them as guidance. Do not let newer-version rules override target VCF specification semantics for a conflict.

One narrow exception, expected to be transitional: VCFv4.3 allows a space in an INFO value (`VCFv4.3.tex` line 366, "Space characters are allowed in values") where VCFv4.2 forbids all whitespace (`VCFv4.2.tex` line 186, "no whitespace, semicolons, or equals-signs permitted"). This library adopts the 4.3 rule unconditionally rather than version-gating it -- a space is not a delimiter and is not meaning-bearing in any version's grammar, so no already-valid input's interpretation changes, and the acceptance is symmetric between read and write. See `VALIDATION_REFERENCE.md`'s "Rules adopted from a later VCF version, unconditionally", whose own preamble is otherwise about target-version *silence*. Delete this paragraph in the same commit that changes the target line above to 4.3: at a 4.3 target, accepting a space in an INFO value is plain conformance rather than an exception to anything -- the behavior does not change, only the justification for it does. A conflict that changes what an already-valid value **means** -- `GP`, `GLE` -- is not this case and must be version-gated.

Minimum supported bcftools version: 1.18.

VCF specifications can be found at https://github.com/samtools/hts-specs.  Use the `.tex` instead of `.pdf` formatted files.

### Spec Ambiguity Decision Hierarchy

Before treating something as a new problem to investigate from scratch, check whether it's already resolved:

- **`HTS_SPECS_BASELINE_STATUS.md`** -- before treating a baseline entry as a new problem to fix. Catalogs every current baseline entry's status (permanently accepted / filed upstream / candidate for filing) and is bound to a specific `htsSpecsPinnedSha`; treat it as stale if that pin has changed since.
- **`WONT_FIX.md`** -- before treating any other code-quality observation as a new finding. Catalogs known issues deliberately left unaddressed, with the reasoning, so they aren't rediscovered and re-proposed.
- **`VCF_SPEC_AMBIGUITIES.md`** -- before re-deriving a decision to resolve a VCF spec ambiguity via bcftools/vcf_validator consultation. Catalogs every ambiguity already resolved this way, so the next person who hits the same question doesn't have to re-run the same tool checks.
- **`VALIDATION_PHILOSOPHY.md`** -- before touching any validation logic. States, as a stable design contract, when this library rejects content immediately, when it warns and recovers, and when it skips validation altogether. The Parser Validation Policy, Writer Validation and Round-Trip Policy, and Public API Null-Safety Policy sections below are each an application of that file's principles to a specific surface.
- **`VALIDATION_REFERENCE.md`** -- before treating an apparently-missing check as a new finding. Catalogs, per rule, exactly what throws and what warns and the citation behind it, and records where a rule is deliberately cited but left unenforced (a `**Scope note:**` entry) -- so a missing check there is not automatically a new finding.

hts-specs also publishes an official VCF conformance fixture suite (`test/vcf/{version}/{passed,failed}/*.vcf`; wired into this project as the opt-in `./gradlew htsSpecsTest` task, see README). Treat this suite as part of the spec: a `failed/*.vcf` fixture's `##CauseOfFailure=` line documents the spec authors' own interpretation of what makes a file invalid, and is authoritative guidance on the same footing as spec prose or a newer-version clarifying table -- consult it when the target VCF specification's text is ambiguous or silent.

If still in doubt, consult bcftools behavior.  Decisions made this way should be documented and have a regression fixture demonstrating the behavior.  If bcftools was consulted, include bcftools version.  If the decision should be re-checked on a bcftools upgrade (round-trip-critical decisions especially), also index it in `BCFTOOLS_VERSION_DEPENDENCIES.md`.

When hts-specs itself is silent (no fixture covers the exact case), running vcf_validator (the EBI reference validator; the tool hts-specs itself uses to generate its `passed`/`failed` corpus) directly against a hand-built input is informative but is *not* equivalent to a documented hts-specs fixture -- cite it as a direct tool run ("vcf_validator `<version>`"), not as "the suite says." vcf_validator tends to be stricter than bcftools on zero-length-field cases in structural fields (ID/FILTER/ALT/INFO) but does not apply the same rigor to sample-column sub-structure (GT, reserved FORMAT values) -- don't treat a vcf_validator "invalid" verdict there as load-bearing. When bcftools and vcf_validator disagree, bcftools remains the tie-breaker per the hierarchy above, but document the disagreement rather than silently picking a side.

### Documentation Map

Before adding new documentation content, place it in the file that already owns that kind of content, rather than growing `README.md` or duplicating across files. This is the complementary direction to the Decision Hierarchy above (the two doc lists aren't identical -- this one also covers `README.md`, which isn't a place you'd check whether an ambiguity is already resolved): that section says which file to check before treating something as new; this one says which file new content belongs in once you've decided to write it.

- **`README.md`** -- user-facing overview only: what the library is, how to get it, a high-level tour of the main classes (`VcfParser`, `VcfWriter`, `VcfLineParser`, `VcfRecord`). No spec-line citations, no bcftools/vcf_validator version-pinned behavior, no itemized throw/warn enumeration. If a README edit would cite a spec line or a tool version, it belongs in one of the docs below instead.
- **`VALIDATION_PHILOSOPHY.md`** -- the architectural "why": which of the four principles applies to a class, and how the single `VcfContext`-driven strict/lenient dispatch reaches it. Not an itemized rule list.
- **`VALIDATION_REFERENCE.md`** -- the itemized "what throws, what warns, exact citation" reference -- one entry per checkable rule.
- **`VCF_SPEC_AMBIGUITIES.md`** -- a rule whose resolution required consulting bcftools/vcf_validator behavior specifically (not just "this is structurally invalid").
- **`EMPTY_FIELD_HANDLING.md`** / **`DUPLICATE_METADATA_ID_HANDLING.md`** -- narrow, single-topic reference tables; extend these rather than re-describing the same rule in `VALIDATION_REFERENCE.md`.
- **`WONT_FIX.md`** -- a known issue deliberately left unaddressed.
- **`HTS_SPECS_BASELINE_STATUS.md`** / **`BCFTOOLS_VERSION_DEPENDENCIES.md`** -- decisions tied specifically to the `htsSpecsTest`/`bcftoolsDependencyTest` opt-in tasks.

A rename (a class, method, or other identifier that appears by name in prose, not just in code) isn't finished until every doc in this list is checked for the old name too -- at minimum `AGENTS.md` itself, `WONT_FIX.md`, `VALIDATION_PHILOSOPHY.md`, `VALIDATION_REFERENCE.md`, `EMPTY_FIELD_HANDLING.md`, `DUPLICATE_METADATA_ID_HANDLING.md`, `HTS_SPECS_BASELINE_STATUS.md`, `VCF_SPEC_AMBIGUITIES.md`, `BCFTOOLS_VERSION_DEPENDENCIES.md`, and `README.md` (not `CHANGELOG.md`, which is semantic-release-owned history, never hand-edited). `BCFTOOLS_VERSION_DEPENDENCIES.md` and `VCF_SPEC_AMBIGUITIES.md` in particular name methods/classes by exact name throughout and are exactly as rename-fragile as the others. A code-scoped diff review only covers the code; a doc citing the old identifier in an example or a cross-reference goes stale silently and won't surface unless something specifically greps for it.

### Spec Citation Discipline

When code behavior is guided by a specific spec passage (a table, a sentence, a worked example) rather than being self-evident from the VCF column/record structure, cite it in a code comment specific enough that a future agent can find the exact passage without re-deriving it: name the spec file (e.g. `VCFv4.3.tex`), and the section/table/example it comes from or a short verbatim quote -- not just "per the VCF spec" or a bare version number. **If you cite a line number, include a short verbatim quote alongside it, always.** A line number is pinned to whatever `htsSpecsPinnedSha` was current when it was written and silently rots when that pin moves, whereas a quote stays checkable forever and makes an off-by-one obvious on sight. Print the surrounding lines with real line numbers and read them before writing any citation, rather than copying one from a plan, a review report, or another comment -- a phase of this project spent a full fix round on two citations that were each off by one, both inherited that way. When the target VCF specification is silent and a later version is used as clarifying guidance (per the Decision Hierarchy above), say so explicitly in the comment (e.g. "VCFv4.2 does not table this; VCFv4.3's Reserved Genotype Keys table gives Number=G") so the reasoning survives independently of whichever agent wrote it. The same specificity requirement applies when hts-specs' conformance suite is the source of a decision: name the fixture path (e.g. `test/vcf/4.2/failed/failed_meta_alt_001.vcf`) and quote or summarize its `##CauseOfFailure=` line -- not just "per hts-specs" or "the conformance suite requires it".


### Parser Validation Policy

The parser is intentionally permissive for non-structural VCF violations in performance-sensitive fields, including INFO, FILTER, FORMAT, sample values, and their metadata declarations.

- Do not require eager validation of these fields during `parse()` or `parseNextLine()`.
- Validation may be deferred until the relevant getter or typed conversion is used.
- When invalid content is encountered during parsing or deferred access, emit a warning that identifies the non-compliance and preserve or normalize the value in the least surprising usable form.
- Report a parser finding only when invalid content is silently accepted, corrupts/misaligns data, prevents later detection when the relevant value is accessed.
- Do not report warning-and-recovery behavior as a defect merely because the current target VCF specification would require rejection.
- Structural violations that make record boundaries or column alignment ambiguous remain errors: malformed #CHROM header structure, wrong column counts, invalid mandatory fixed-field structure, duplicate sample IDs, invalid REF/ALT syntax, negative POS, and invalid delimiter handling that changes field alignment. This is the floor, not the ceiling: a throw in an otherwise-permissive field is also justified when a value collides with a reserved, spec-defined meaning rather than merely being malformed freeform content. Every such exception needs the same citation discipline as any other decision in this file (spec text, an hts-specs fixture, or bcftools behavior) at its point of enforcement in the code, and should appear in `VALIDATION_REFERENCE.md`'s strict/lenient breakdown. This file doesn't try to enumerate every exception -- finding one that's cited but not named here isn't a policy violation; finding one with no citation anywhere is.
- A duplicate metadata-declaration ID (`##INFO`/`##FORMAT`/`##FILTER`/`##ALT`/`##contig`/`##SAMPLE`, and `##PEDIGREE` when it declares one) warns and keeps the first declaration, matching bcftools -- never keep-last/overwrite. This is enforced at the single point where a declaration can be added -- `VcfMetadata.Builder` (reached directly, by `VcfParser` at parse time, or via `VcfMetadata.toBuilder()`); a built `VcfMetadata` is immutable, so there is no second entry point. `##PEDIGREE` additionally keeps a write-time check, since its `List` backing (unlike the six keyed types' `Map`) does not itself prevent two same-ID entries. Do not introduce divergent duplicate-ID handling for a new declaration type or entry point without a documented, tool- or spec-backed reason (see `DUPLICATE_METADATA_ID_HANDLING.md`).
- Before choosing to drop vs. substitute-with-`.` for an empty entry in any list-like field, check whether another field indexes into that list positionally (e.g. `GT` indexes into `ALT`; FORMAT sub-field position matches sample values by index). Dropping an entry shifts every later index -- this can silently misdirect a lookup to the wrong element with no error at the point of misuse. When such a cross-reference exists, substitute the missing-value sentinel instead of dropping, even if the field wasn't previously known to have one.
- A zero-length entry produced by a delimiter within an otherwise well-formed field (ID/FILTER/ALT/INFO/FORMAT keys, sample values) is normalized per a documented, field-specific strategy -- dropped, substituted with `.`, or (FORMAT's own key list only) kept as-is with just a warning -- see `EMPTY_FIELD_HANDLING.md` before inventing a new rule for a field not already covered there.



### Writer Validation and Round-Trip Policy

Writer output is governed by the semantics of the target VCF specification. A writer must not emit a `fileformat` declaration for another VCF version unless the emitted output is also valid under that declared version.

A record accepted by the parser must be writable without losing fields or producing malformed column structure.

- The default writer path is performance-sensitive. It assumes metadata, positions, and samples have not been mutated into an invalid state after parsing, and must preserve parser-normalized values and maintain record/sample column structure without full revalidation.
- That "trusts already-valid input" exemption covers VCF-semantic re-validation only -- it does not extend to basic Java null-safety, a separate axis never subject to this exemption (see `VALIDATION_PHILOSOPHY.md`'s "How the principles interact").
- The writer may canonicalize parser-recovered invalid input (for example, an empty recoverable value normalized to `.`), but must preserve the usable meaning and emit a syntactically parseable VCF record.
- `VcfWriter.Builder.validateBeforeWrite()` is the full-output diagnostics mode. Before every write, it must validate metadata, positions, FORMAT keys, INFO fields, and sample values sufficiently to ensure structural validity of the emitted header and records and report all detected semantic non-compliance.
- In full-output diagnostics mode, content that is semantically non-compliant but can be represented safely must emit a warning identifying the issue and be preserved or canonicalized. Content that would make output structurally invalid or unparseable — including incorrect column count, invalid delimiter placement, line terminators, invalid mandatory fixed fields, or sample data inconsistent with FORMAT — must be rejected with `VcfFormatException`.
- Callers that mutate parsed data and require a full structural-validity and diagnostic guarantee must enable `validateBeforeWrite()`.
- Reviewers should report a default-writer issue when an unmodified parser-produced record cannot be written and parsed again without field loss or structural corruption. They should report a full-output-diagnostics issue when `validateBeforeWrite()` permits structurally invalid output or fails to issue a warning for detected semantic non-compliance.


### Public API Null-Safety Policy

The Writer Validation policy above carves basic Java null-safety out of the default write path's "trusts
already-valid input" exemption. The case that carve-out was written for -- a caller mutating a `null` into a
model *after* it had been validated, through a live collection view -- no longer exists: the model layer is
immutable, so the only routes left into `VcfWriter`'s output are construction paths. What survives is
narrower and specific: the package-private trusted factories listed below skip validation by design, and
`VcfWriter`'s per-record helpers (`addListOrElse`, `addInfoOrDot`, `addFormatConditionally`) still reject a
`null` unconditionally on the default path because those factories are real production fast paths. The
header-building path makes no such check, because no construction path can put a `null` there at all -- see
`VALIDATION_PHILOSOPHY.md`'s "How the principles interact".
The same underlying rule applies across this library's whole public API surface, not just that one path: a
public constructor, static factory, or method whose parameter is typed as a non-`@Nullable` reference (the
default when no `@Nullable` annotation is present) must not raw-crash with `NullPointerException` when a
caller passes `null` at runtime. See `VALIDATION_PHILOSOPHY.md`'s "How the principles interact" section for
why this is a distinct axis from that file's four principles and why annotations alone can't be relied on
here. The rest of this section states the concrete per-shape rules that follow from it.

- For a method that constructs or mutates state (a constructor, a `Builder` setter that stores
  caller-supplied VCF content, an `addXxx`/`putXxx`/factory method), reject a `null` argument immediately
  with `VcfFormatException`, before it can be stored or used. Never let a `null` silently produce an
  internally-inconsistent object whose corruption only surfaces later, at an arbitrary unrelated call site
  (e.g. `toString()`) -- that is a strictly worse failure mode than an immediate crash, since the exception
  no longer points at the actual mistake.
- For a lookup-by-key query or predicate method (e.g. `getXxx(key)`, `hasXxx(key)`, `containsXxx(key)`),
  match whatever null-tolerant "not present" contract the equivalent `String`-keyed/plain-typed overload of
  the same operation already has, rather than uniformly rejecting -- e.g.
  `VcfSample.getProperty(ReservedFormatProperty)`/`VcfPosition.getInfo(ReservedInfoProperty)` each match
  their own `String`-keyed sibling's existing behavior instead of introducing a new, inconsistent rule.
  Only reject with `VcfFormatException` if the sibling itself already does (a mutator matching the
  constructs-or-mutates-state bullet above).
- This is a distinct question from a `Builder`'s own required-argument check on a caller-configuration
  parameter (a `Path`, a `Reader`, a `VcfLineParser` -- never VCF content, always something the calling
  code constructs and passes in before parsing/writing starts). `Preconditions.checkNotNull`'s raw
  `NullPointerException` remains the correct, established convention there -- see `WONT_FIX.md`'s
  `VcfParser.Builder` entries for the full reasoning and the exact boundary between the two cases.
  The `VcfWarnings` instance `VcfContext.lenient(...)` requires, and the `VcfContext` that
  `VcfMetadata.Builder`'s constructor and every model constructor in turn require, are the same category of
  caller-configuration object, not VCF content -- each is rejected the same way (raw `NullPointerException`
  via `Preconditions.checkNotNull`), not with `VcfFormatException`. `VcfWriter.Builder.targetFileFormat` is
  the counterexample that shows where the line falls: it looks like a `Builder` configuration setter, but its
  value is written verbatim into the output as `##fileformat`, so it is VCF content and takes
  `VcfFormatException`.
- A class or method explicitly documented as not part of this library's supported public API is exempt from
  this policy's null-rejection requirement -- currently `InternalUtils` (renamed from `VcfUtils`; most of its
  members are `public` only because Java has no cross-package-within-a-library visibility modifier),
  `VcfMetadata.validateAlt`/`isEmptyMetadataValue`/`collidesWithAttributeListGrammar`,
  and `VcfFloat.parse(String, VcfWarnings)`/`isSpecialValue`/`exceedsMaxDecimalLength`. These are marked
  `@RestrictedApi`/`@hidden` -- a documentation-only exemption, not a technical guarantee.
  The library's five package-private fast-path **model** factories -- `VcfContext.newPositionTakingOwnership`,
  `newPositionWithTrustedFormat`, `newPositionCopyingTrustedFields`, `newTrustedSample`, and `newTrustedRecord`
  (that count is of trusted model factories, not of `VcfContext`'s package-private members: the sixth,
  `VcfContext.lenientWithoutFallbackWarning`, builds a context rather than a model object and bypasses the
  version-fallback *warning* rather than a content check, so it is deliberately outside this list -- see
  `VALIDATION_PHILOSOPHY.md`'s "How the principles interact") --
  are *not* on this list: they are package-private, so visibility enforces what an annotation could only
  advertise, and an external caller cannot reach them at all. Only one of the five is not named `*Trusted*`;
  `newPositionTakingOwnership` deliberately is not, because it skips only the defensive copies and still runs
  every validation check, FORMAT included -- it trusts the caller's exclusive ownership of the collections, not
  the collections' content, which is a materially weaker claim than the other four make. They differ from each
  other in *what* they skip (`newPositionTakingOwnership` skips only the defensive copies;
  `newPositionWithTrustedFormat` skips those same copies *and* FORMAT validation entirely;
  `newPositionCopyingTrustedFields` is the inverse of both -- it *never* skips the copies (used only by
  `VcfPosition.Builder#build()`, which is reusable and must not alias one built position's collections to
  another's or to the builder's own retained fields) but skips ID/ALT/FILTER/INFO/FORMAT validation entirely,
  trusting `Builder#build()` itself to have already re-run whichever of those five checks a setter actually
  changed; `newTrustedSample` skips a delimiter scan; `newTrustedRecord` skips the defensive copy and the
  context-identity check, but never the null-position/null-sample-list rejection) -- check which checks a given
  trusted-bypass method actually skips before assuming one is a precedent for another. See
  `VALIDATION_PHILOSOPHY.md`'s "How the principles interact" section. Two of `InternalUtils`'s null-guards are
  kept as exceptions to its own exemption, since they're genuinely load-bearing: `unquote` (see its own
  comment) and the per-element checks in `dropEmptyEntries`/`fillEmptyEntriesWithDot` (reachable via a
  caller-supplied `List` containing a `null` element passed directly to `VcfContext.newPosition` or
  `VcfPosition.Builder`'s `ids`/`altBases`/`filters` setters -- both defensively copy via a plain `new
  ArrayList<>(...)`, which preserves rather than rejects a `null` element, so it still reaches `checkIds`/
  `checkAltBases`/`checkFilters` at construction time. `VcfPosition` itself has no mutation route since Task 8
  froze it -- every getter now returns an unmodifiable view -- so this is no longer reachable via a live
  mutation the way it was before that task; the caller-supplied-list route above is what survives it).


## Repository Guidelines

- Use the checked-in Gradle wrapper. The Node/Yarn files support semantic-release; they are not the application build.
- **Keep parser changes streaming-friendly**. Do not load an entire VCF into memory unless the affected class explicitly provides an in-memory data store.
- Commit messages use conventional-commits style (`type(scope): summary`). `scope` is drawn from `parser`, `writer`, `memoryMappedImpl`, and `spec` -- never a class name. Model classes (`VcfPosition`, `VcfSample`, `VcfMetadata`, etc.) use `parser` (there is no separate `model` scope); `VcfWriter.java` and its `Builder` use `writer`; `MemoryMappedVcfDataStore.java`/`MemoryMappedVcfLineParser.java` use `memoryMappedImpl`, including doc-only changes to either; `spec` covers commits touching `src/test/java/org/pharmgkb/parser/vcf/spec/` (the hts-specs conformance test infrastructure). A commit touching more than one of these areas may list multiple, comma-separated (e.g. `type(parser,writer): summary`), rather than picking one to cover the rest.
- **Always scope a repo-root `rg` with `--glob '!build/**'`.** `build/hts-specs-checkout/` is a ~30MB sparse clone of hts-specs (defined once as `htsSpecsCheckoutDir` at `build.gradle:122`, so it is always under `build/`), and an unscoped search runs for minutes and buries real hits under fixture noise. When you deliberately need the spec text, target `build/hts-specs-checkout/*.tex` by path instead of widening the search.
- **An identifier grep is not an inventory.** Before claiming you have found every call site or implementor, let the compiler enumerate them -- change the signature and read `./gradlew compileJava compileTestJava`. Two ways `rg` has produced a confidently wrong count in this repo: it cannot see a lambda (a plan once claimed "five implementors" of an interface where the real number was 84), and it cannot see a parameter declared as a *supertype* of the type you searched for (a sweep for `ReservedFormatProperty` missed a method taking `ReservedProperty`, for several review rounds running). When a set must be complete, derive it more than one way and reconcile; if the derivations disagree, the compiler wins.
- **Never `git add -A`, `git add .`, or `git add -u`** -- stage explicit filenames only. `.gitignore` is not sufficient protection here, because the risk is not only untracked files: `.idea/vcs.xml` is *tracked* and drifts on its own as the IDE is used, so a bulk add sweeps unrelated IDE state into whatever commit you are building. Untracked local files that must also stay out (large `.vcf.gz` corpora, scratch findings/notes, anything under `docs/superpowers/` or `.superpowers/`) are covered by `.gitignore` today, but the tracked-and-drifting case is the one no ignore rule can catch.
- A single logical change should land as one commit. Squash incidental churn (fix-loop iterations, a reconsidered design, a follow-up correction) into the commit for the item it belongs to rather than leaving it split across commits, and bundle several individually-trivial fixes -- pure documentation/comment changes especially -- into one commit even when they're unrelated to each other. Reserve a dedicated commit for anything with a real design decision, a behavior change, or genuine risk, so it can be reviewed and reverted independently. See `SOP.md`'s Standing Conventions for the squashing/bundling mechanics used when following the findings-review batch workflow.

### Repository Layout

- `src/main/java/org/pharmgkb/parser/vcf/`: parser, writer, data-store, and model (VCF metadata, position,
  sample, and genotype) code.
- `src/test/java/`: JUnit tests mirroring the production packages.
- `src/test/resources/`: small VCF fixtures used by parser and writer tests.
- `build.gradle` and `pgkb-build.gradle`: dependencies, Java version, tests, coverage, packaging, and publishing configuration.

### Build and Test

Run commands from the repository root.

```
./gradlew test
```

Run a focused test while iterating:

```
./gradlew test --tests "org.pharmgkb.parser.vcf.VcfParserTest"
```

On Windows, use `.\gradlew.bat` instead. The full `test` task is the required verification for code changes and also generates the JaCoCo report used by CI.

Two further opt-in tasks are excluded from `test` (network access or an external binary, respectively) but should be run when relevant: `./gradlew htsSpecsTest` (the hts-specs conformance suite; see `HTS_SPECS_BASELINE_STATUS.md` for details) and `./gradlew bcftoolsDependencyTest` (verifies every bcftools-informed decision this library has made against a real installed `bcftools` binary -- see `BCFTOOLS_VERSION_DEPENDENCIES.md` for details). Run `bcftoolsDependencyTest` after adding any new bcftools-informed decision, per the Spec Ambiguity Decision Hierarchy above.

**`htsSpecsTest` prints nothing to the console on success, so `BUILD SUCCESSFUL` is not evidence that it passed.** The authoritative readout is `build/reports/hts-specs-conformance/summary.md` -- check its `**Total**` row *and* that every per-version row reads `0` in the new/stale column -- together with `build/reports/hts-specs-conformance/findings.tsv`, which must stay header-only (exactly 1 line). Never infer the outcome from `BUILD SUCCESSFUL`, from JUnit's `tests=` count, or from the absence of console output. A moved row is a defect, never a re-baseline: `hts-specs-baseline.tsv` is only ever edited to *remove* an entry that legitimately stopped reproducing, which is a different act from re-recording a new divergence. This gate is the only regression alarm covering writer round-tripping, and it is fixture-driven -- so it cannot see anything reachable only through public construction (`VcfContext.newPosition`, a `Builder`). Treat "the gate will catch it" as a claim to check, not an assumption.

### Code Conventions

- Match the existing Java style: two-space indentation, four-space continuation indentation, braces on control-flow statements, and two blank lines after imports.
- Preserve the established field prefixes (`m_` for instance fields and `s_`/`sf_` for static fields) in classes that use them.
- Use JSpecify annotations for explicit nullability and follow the surrounding API's collection/null conventions.
- Keep changes narrow. This is a published library, so avoid changing public behavior or signatures unless the task requires it.
- Preserve lazy parsing and allocation-conscious code in hot per-record paths unless tests and measurements justify a change.
- Helpers that normalize a `List` in place (e.g. `InternalUtils.fillEmptyEntriesWithDot`) mutate their argument directly and require a mutable list. Callers must never pass a constructor/method parameter straight through to such a helper -- defensively copy first (`new ArrayList<>(list)`), since external callers may supply an immutable list (`List.of(...)`) or one they don't expect to be mutated (`Arrays.asList(...)`).
- JDK immutable collections (`List.of(...)`, `Set.of(...)`, `Map.copyOf(...)`, `Collectors.toUnmodifiableMap(...)`) null-check lookup arguments and throw `NullPointerException` from `.contains(null)`/`.get(null)`/`.indexOf(null)`, unlike `HashMap`/`ArrayList`, which tolerate `null` there. For a lookup over a fixed, known domain where `null` trivially isn't a match (e.g. `isReservedMetadataKey`, `getColumnIndex`), guard the lookup explicitly rather than letting this throw. Prefer `Collections.unmodifiableList(Arrays.asList(...))` over `List.of(...)` only when wrapping data provably owned by no one else (e.g. a varargs parameter's freshly-allocated array) -- it is zero-copy and null-tolerant, but does not defensively copy, so it is unsafe for data a caller might still hold a mutable reference to.
- String case-folding used for parsing or dispatch logic (`toUpperCase()`/`toLowerCase()`) must use `Locale.ROOT` explicitly (`value.toUpperCase(Locale.ROOT)`), never the default-locale overload -- under a Turkish/Azeri locale, default-locale case-folding on `I`/`i` produces different code points than ASCII expects, which can silently break `String.equals`-based dispatch or comparison.
- Before writing code against a type that's only reachable today via an undeclared transitive dependency (e.g. an annotation that happens to ride in through `com.google.guava:guava`'s own dependencies), declare that dependency explicitly in `build.gradle` instead of relying on the transitive -- `compileOnlyApi` for a compile-time-only/documentation-only type, matching the existing `org.jspecify:jspecify` declaration, including the same `pom.withXml` stripping treatment (see the `publishing` block) so it doesn't leak onto a downstream Maven consumer's runtime classpath. An undeclared transitive is fragile (a future upgrade of the dependency that brought it in can silently drop it) and makes the actual dependency graph harder to audit.

### Tests and Fixtures

- Add or update a focused JUnit test for every behavior change or bug fix.
- Use JUnit Jupiter assertions and Hamcrest consistently with the surrounding test class.
- Put reusable VCF inputs in `src/test/resources` and load them through the existing test utilities.
- VCF delimiters are significant: preserve literal tabs, empty fields, header ordering, and line structure in fixtures.
- Cover successful parsing, accepted-with-warning recovery, deferred validation, and structural rejection as applicable. For parser-to-writer changes, add a round-trip test showing that accepted input can be written and parsed again without column loss or delimiter corruption.

### Release Files

- Versioning and changelog updates are managed by semantic-release.
- Do not manually edit `CHANGELOG.md`, the package version, or the README dependency version unless the task is specifically a release change.

## Project Overview

This repository is a Java 17 library for high-performance, streaming parsing and writing of VCF 4.x data.
It prioritizes preserving and usefully exposing VCF data rather than rejecting every specification violation during initial parsing.  

Target VCF specification: 4.3

The library accepts VCF 4.x input from before and after the target version where it can be represented by this library. A file is interpreted under the version its own `##fileformat` declares: where a rule's *meaning* genuinely differs across 4.x and both readings are valid, the declared version decides, not the target version -- today that is `FORMAT/GP`, `FORMAT/GLE`, and `FORMAT/CNL`'s expected `Number` -- plus `FORMAT/AD`, `FORMAT/ADF`, `FORMAT/ADR`, `FORMAT/PP`, and `FORMAT/CNP`'s existence as reserved keys at all, and `INFO/AD`, `INFO/ADF`, and `INFO/ADR`'s existence as reserved keys at all (VCFv4.3 additions with no earlier declaration, the mirror image of `GLE`'s removal, not a `Number`-meaning change) -- plus two further, differently-shaped cases: `##PEDIGREE` requiring an `ID` at VCFv4.3+ (a required-field rule on a declaration, not a value- or `Number`-meaning change), and `##META` not existing as a structured declaration type at all before VCFv4.3 (an existence/routing rule on the declaration type itself, deliberately enforced only at parse time, not at construction or write time -- see `VALIDATION_REFERENCE.md`'s own entry for why) -- and see `VALIDATION_PHILOSOPHY.md`'s "How the declared version is dispatched" for the mechanism and the `isAtLeast` discipline it must use. A declared version this library does not implement falls back to the nearest one it does. Where versions do not differ in meaning, or an input is ambiguous, behavior is interpreted according to target VCF specification semantics. Features that cannot be represented safely under those semantics may be warned about, normalized, or rejected according to the parser and writer policies below.

The goal is maximum practical compliance with the target VCF specification while preserving and usefully exposing recoverable VCF 4.x data.  "Strict" in this project means structural correctness and transparent diagnostics, not eager rejection of every semantic VCF violation.

When newer 4.x VCF specifications clarify an ambiguity not resolved by the target VCF specification, use them as guidance. Do not let newer-version rules override target VCF specification semantics for a conflict.

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

**"If still in doubt" is a precondition, not a formality.** bcftools breaks a *tie* -- a case the spec leaves silent, or where its text genuinely admits two readings. It is not an authority *against* the spec. Where the spec is explicit and bcftools diverges from it -- tolerating a construct the spec forbids, or implementing one the spec never defines -- there is no tie to break, and "bcftools does X" does not settle what this library should do. Such behavior is a fact about interoperability, to be measured, recorded, and then decided on deliberately; adopting it is a choice that needs its own justification, and declining to adopt it is equally a decision that must be written down rather than left implicit. This is the same asymmetry the "bcftools is asymmetric evidence" paragraph below states for acceptance, applied one level up: to whether the question was ever bcftools' to answer.

The worked example is this project's angle-bracket decision. htslib's header tokenizer keeps an angle-bracket nesting counter, so a `,` inside a `<...>` span in an attribute value does not split the attribute (`~/Dev/htslib/vcf.c`, `bcf_hdr_parse_line`'s unquoted-value scan). No VCF version defines `<...>` as a quoting or nesting form for a value: the spec's only escaping mechanism is the double quote (`VCFv4.3.tex:173`, "Double-quote character must be escaped with backslash $\backslash$ and backslash as $\backslash\backslash$"), and `VCFv4.3.tex:265` excludes those very characters from contig names because doing so "avoids clashes with symbolic alleles". hts-specs' own corpus agrees -- `test/vcf/{4.1,4.2,4.3}/failed/failed_meta_alt_008.vcf` gives `##CauseOfFailure=The ALT ID contains an opening angle bracket`. So the counter is a tolerance for spec-forbidden input, the spec was never ambiguous, and this library deliberately does not implement it. A round of design work was spent building the tolerance before that question was asked; asking it first is cheaper.

**Both tools' source is checked out locally, pinned to the installed versions** -- `~/Dev/htslib` (tag `1.23.1`, matching the installed bcftools/htslib) and `~/Dev/vcf-validator` (tag `v0.10.2`, matching the installed `vcf_validator`). Re-clone at the matching tag if missing; a source read against a *different* version than the installed binary documents a grammar nobody is running. The files that matter: `htslib/vcf.c`'s `bcf_hdr_parse_line` (the entire header tokenizer) and `vcf-validator/src/vcf/vcf_v4{1,2,3,4}.ragel` plus the generated `inc/vcf/validator_detail_v4*.hpp` that the shipped binary actually compiles.

**Reading source and running the binary answer different questions; pick deliberately.**

- *"What does this version do with input X?"* -> **run the tool.** Only the binary is authoritative about behavior, and behavior is what the Decision Hierarchy above is built on.
- *"What is the rule, exactly?"* or *"why does it do that?"* -> **read the source.** A behavioral probe gives you one point; the source gives you the rule the points lie on.
- *"Is my model complete -- what cases am I not testing?"* -> **read the source.** This is the one where probing systematically fails, because you can only probe shapes you already thought of. Deriving the attribute-list grammar this way took several rounds of confidently wrong tables built from probes that all happened to share an unrepresentative shape; the source read then enumerated the cases the parser actually distinguishes, including ones no one had guessed (a second `[...]` quoting form, whitespace skipped before an opening quote, a fatal-to-the-whole-header failure mode).
- *"Is this tool's verdict trustworthy here?"* -> **read the source.** Two vcf_validator verdicts that fit no behavioral model turned out to be token-capture defects (see below), which black-box probing could describe but never explain.

The strongest form is both, used against each other: derive the rule from source, write down the predictions *before* running anything, then measure. Agreement means the model is right and the harness is sound; disagreement localizes which one is wrong. Never let a source read alone settle a behavioral claim -- it is a hypothesis until the installed binary confirms it.

When hts-specs itself is silent (no fixture covers the exact case), running vcf_validator (the EBI reference validator; the tool hts-specs itself uses to generate its `passed`/`failed` corpus) directly against a hand-built input is informative but is *not* equivalent to a documented hts-specs fixture -- cite it as a direct tool run ("vcf_validator `<version>`"), not as "the suite says." vcf_validator tends to be stricter than bcftools on zero-length-field cases in structural fields (ID/FILTER/ALT/INFO) but does not apply the same rigor to sample-column sub-structure (GT, reserved FORMAT values) -- don't treat a vcf_validator "invalid" verdict there as load-bearing. When bcftools and vcf_validator disagree, bcftools remains the tie-breaker per the hierarchy above -- subject to the same precondition, so a bcftools behavior that itself contradicts explicit spec text does not win the tie merely by being bcftools' -- but document the disagreement rather than silently picking a side.

**Three confirmed vcf_validator 0.10.2 defects make some of its verdicts worthless rather than merely weak.** The first two are token-capture faults in its Ragel grammar, so each can make a well-formed line invalid *and* a malformed one valid -- meaning neither an "invalid" nor a "valid" verdict is safe in their blast radius:

- **Token-parity.** A `\"` inside a quoted attribute value emits a spurious token, and the final check is a parity test on the token count. A line's validity therefore flips on the *count* of `\"` it contains: 1 -> invalid, 2 -> valid, 3 -> invalid (measured). It rejects exactly the escaping `VCFv4.3.tex:173` mandates, and the "valid" case is stored as a shifted, garbage key/value map.
- **Leading-punctuation truncation.** Identifier-like tokens are captured such that leading punctuation is consumed but never stored, so `##INFO=<ID=_AF,...>` is validated as the *reserved* tag `AF` (confirmed: the error text names `AF`, not `_AF`). Distinct IDs are silently conflated.
- **`<*>`-with-no-samples segfault.** A record whose ALT is the gVCF unspecified allele `<*>` crashes the binary outright (exit 139, no verdict at all) when the record has no `FORMAT`/sample columns; the identical record with even one sample column (e.g. `FORMAT=GT`, a `GT` value) completes normally (measured, both cases, vcf_validator 0.10.2). The crash site is `validate_optional_policy.cpp`'s unguarded `record.format[0] == vcf::GT` check, reached only for `<*>` among all ALT shapes. Unlike the two token-capture faults above, this produces *no* verdict rather than a wrong one -- so a future `<*>` probe built without a sample column silently measures nothing, not "valid" or "invalid."

A verdict produced by either of the first two defects is **no evidence, not weak evidence** -- do not downgrade it to "corroborating," exclude it and say why. When citing vcf_validator, name the grammar version its probe actually exercised (it ships one grammar per VCF version, selected from the file's `##fileformat`, and has **none for 4.5** -- it maxes at 4.4). By contrast bcftools' header tokenizer is version-independent: `bcf_hdr_parse_line` never consults `##fileformat` (htslib 1.23.1 `vcf.c`), so a bcftools header-parsing result needs no version qualifier beyond the tool version. See `BEHAVIOR_TABLE_STANDARD.md` for how this shapes a behavior table's columns.

**bcftools is asymmetric evidence.** Its *acceptance* of an input is weak -- it is permissive about
header-field encoding in particular, so never infer "this is valid" from "bcftools didn't complain." Its
*rejection* is strong, and it matters most on the output side: VCF this library writes must not fail in
bcftools.

That output constraint binds on content this library **originates**, not on content it faithfully carries
forward. The distinction is provenance, not code path:

- **Originated content** -- anything reaching the output through public construction (`VcfMetadata.Builder`'s
  `addXxx`/`replaceXxx`, the model constructors), and anything written under `validateBeforeWrite()`, where
  the caller has explicitly asked whether the output is structurally valid. Here the constraint is absolute:
  if bcftools would reject the line or silently drop the declaration, this library must refuse to build it.
  Where such a route is reachable it is a defect to fix by tightening construction, not a licence to relax
  this rule.
- **Carried-forward content** -- a value the parser read from an input file, warned about, preserved, and the
  default write path then echoed unchanged. Here the constraint does **not** bind. The input was already
  malformed; re-emitting it faithfully is this library doing its job, and bcftools discarding that
  declaration is bcftools' decision about someone else's data, not a defect introduced here. The floor that
  still applies: our output must not make bcftools **fail** -- a file it cannot read at all (e.g. htslib's
  fatal unclosed-`[` path) is never acceptable, even for echoed content.

So the same byte sequence can be legal to emit on the default path and illegal to construct, and that is
deliberate rather than an inconsistency. `##contig=<ID=1,a-b=v>` read from a file is preserved and re-emitted;
the same declaration built through `addContig(...)` is rejected, because bcftools discards it and we would be
the ones who created it.

Treat "bcftools parses our output without error" as a necessary condition on **originated** output, and
"bcftools accepts this input" as one data point about leniency, not a rule.


### Documentation Map

Before adding new documentation content, place it in the file that already owns that kind of content, rather than growing `README.md` or duplicating across files. This is the complementary direction to the Decision Hierarchy above (the two doc lists aren't identical -- this one also covers `README.md`, which isn't a place you'd check whether an ambiguity is already resolved): that section says which file to check before treating something as new; this one says which file new content belongs in once you've decided to write it.

- **`README.md`** -- user-facing overview only: what the library is, how to get it, a high-level tour of the main classes (`VcfParser`, `VcfWriter`, `VcfLineParser`, `VcfRecord`). No spec-line citations, no bcftools/vcf_validator version-pinned behavior, no itemized throw/warn enumeration. If a README edit would cite a spec line or a tool version, it belongs in one of the docs below instead.
- **`VALIDATION_PHILOSOPHY.md`** -- the architectural "why": which of the four principles applies to a class, and how the single `VcfContext`-driven strict/lenient dispatch reaches it. Not an itemized rule list.
- **`VALIDATION_REFERENCE.md`** -- the itemized "what throws, what warns, exact citation" reference -- one entry per checkable rule.
- **`VCF_SPEC_AMBIGUITIES.md`** -- a rule whose resolution required consulting bcftools/vcf_validator behavior specifically (not just "this is structurally invalid").
- **`EMPTY_FIELD_HANDLING.md`** / **`DUPLICATE_METADATA_ID_HANDLING.md`** / **`STRUCTURAL_DELIMITER_HANDLING.md`** -- narrow, single-topic reference tables; extend these rather than re-describing the same rule in `VALIDATION_REFERENCE.md`.
- **`BEHAVIOR_TABLE_STANDARD.md`** -- *how* to write any of the above, not what goes in them. Read it before adding or editing a table that records measured spec/tool behavior anywhere in this list. It fixes the column set (one column per spec version 4.2-4.5, then each tool version-pinned, then this library's paths), requires `silent` as an explicit entry rather than a blank, requires every table to state its probe shape and carry a control row, names the four library paths a results column may refer to (parse / public construction / validated write / default write) and when they may be collapsed, and sets the three-tier rule for preserving the evidence (prose, `bcftoolsDependencyTest` case plus a committed probe script, fixture). A tool verdict recorded without its probe shape is a fact about one field, not about the grammar -- see that file's worked example of vcf_validator disagreeing with itself between an ALT ID and a `##contig` attribute value.
- **`WONT_FIX.md`** -- a known issue deliberately left unaddressed.
- **`HTS_SPECS_BASELINE_STATUS.md`** / **`BCFTOOLS_VERSION_DEPENDENCIES.md`** -- decisions tied specifically to the `htsSpecsTest`/`bcftoolsDependencyTest` opt-in tasks.

A rename (a class, method, or other identifier that appears by name in prose, not just in code) isn't finished until every doc in this list is checked for the old name too -- at minimum `AGENTS.md` itself, `WONT_FIX.md`, `VALIDATION_PHILOSOPHY.md`, `VALIDATION_REFERENCE.md`, `EMPTY_FIELD_HANDLING.md`, `DUPLICATE_METADATA_ID_HANDLING.md`, `STRUCTURAL_DELIMITER_HANDLING.md`, `HTS_SPECS_BASELINE_STATUS.md`, `VCF_SPEC_AMBIGUITIES.md`, `BCFTOOLS_VERSION_DEPENDENCIES.md`, and `README.md` (not `CHANGELOG.md`, which is semantic-release-owned history, never hand-edited). `BCFTOOLS_VERSION_DEPENDENCIES.md` and `VCF_SPEC_AMBIGUITIES.md` in particular name methods/classes by exact name throughout and are exactly as rename-fragile as the others. A code-scoped diff review only covers the code; a doc citing the old identifier in an example or a cross-reference goes stale silently and won't surface unless something specifically greps for it.

### Spec Citation Discipline

When code behavior is guided by a specific spec passage (a table, a sentence, a worked example) rather than being self-evident from the VCF column/record structure, cite it in a code comment specific enough that a future agent can find the exact passage without re-deriving it: name the spec file (e.g. `VCFv4.3.tex`), and the section/table/example it comes from or a short verbatim quote -- not just "per the VCF spec" or a bare version number. **If you cite a line number, include a short verbatim quote alongside it, always.** A line number is pinned to whatever `htsSpecsPinnedSha` was current when it was written and silently rots when that pin moves, whereas a quote stays checkable forever and makes an off-by-one obvious on sight. Print the surrounding lines with real line numbers and read them before writing any citation, rather than copying one from a plan, a review report, or another comment -- a phase of this project spent a full fix round on two citations that were each off by one, both inherited that way. When the target VCF specification is silent and a later version is used as clarifying guidance (per the Decision Hierarchy above), say so explicitly in the comment (e.g. "VCFv4.3 does not table PSQ; VCFv4.4's Reserved Genotype Keys table gives it Number=P") so the reasoning survives independently of whichever agent wrote it. The same specificity requirement applies when hts-specs' conformance suite is the source of a decision: name the fixture path (e.g. `test/vcf/4.2/failed/failed_meta_alt_001.vcf`) and quote or summarize its `##CauseOfFailure=` line -- not just "per hts-specs" or "the conformance suite requires it".

A citation naming `VCFv4.2.tex` for a rule VCFv4.3 did not change remains valid and must not be mechanically re-pointed: a citation is a pointer to evidence, and VCFv4.2.tex genuinely states what it quotes. Re-point or dual-cite only when the surrounding reasoning is target-relative -- an argument from VCFv4.2's *silence*, or from VCFv4.2 being the target -- because that argument, not the quote, is what a target change falsifies.


### Parser Validation Policy

The parser is intentionally permissive for non-structural VCF violations in performance-sensitive fields, including INFO, FILTER, FORMAT, sample values, and their metadata declarations.

- Do not require eager validation of these fields during `parse()` or `parseNextLine()`.
- Validation may be deferred until the relevant getter or typed conversion is used.
- When invalid content is encountered during parsing or deferred access, emit a warning that identifies the non-compliance and preserve or normalize the value in the least surprising usable form.
- Report a parser finding only when invalid content is silently accepted, corrupts/misaligns data, prevents later detection when the relevant value is accessed.
- Do not report warning-and-recovery behavior as a defect merely because the current target VCF specification would require rejection.
- Structural violations that make record boundaries or column alignment ambiguous remain errors: malformed #CHROM header structure, wrong column counts, invalid mandatory fixed-field structure, duplicate sample IDs, invalid REF/ALT syntax, negative POS, and invalid delimiter handling that changes field alignment. This is the floor, not the ceiling: a throw in an otherwise-permissive field is also justified when a value collides with a reserved, spec-defined meaning rather than merely being malformed freeform content. Every such exception needs the same citation discipline as any other decision in this file (spec text, an hts-specs fixture, or bcftools behavior) at its point of enforcement in the code, and should appear in `VALIDATION_REFERENCE.md`'s strict/lenient breakdown. This file doesn't try to enumerate every exception -- finding one that's cited but not named here isn't a policy violation; finding one with no citation anywhere is.
- A duplicate metadata-declaration ID (`##INFO`/`##FORMAT`/`##FILTER`/`##ALT`/`##contig`/`##SAMPLE`/`##META`, and `##PEDIGREE` when it declares one) warns and keeps the first declaration, matching bcftools -- never keep-last/overwrite. This is enforced at the single point where a declaration can be added -- `VcfMetadata.Builder` (reached directly, by `VcfParser` at parse time, or via `VcfMetadata.toBuilder()`); a built `VcfMetadata` is immutable, so there is no second entry point. `##PEDIGREE` additionally keeps a write-time check, since its `List` backing (unlike the seven keyed types' `Map`) does not itself prevent two same-ID entries. Do not introduce divergent duplicate-ID handling for a new declaration type or entry point without a documented, tool- or spec-backed reason (see `DUPLICATE_METADATA_ID_HANDLING.md`).
- **Unquoting before internal use.** A `BaseMetadata` property's value may be wrapped in a quoted-string encoding (`ID="foo"`). Every comparison or validation this library performs against a property's value -- blank/whitespace checks, grammar checks, reserved-key/reserved-value comparisons, duplicate-ID dedup -- must read `getPropertyUnquoted`, never `getPropertyRaw`, so the check sees the value's actual content rather than its header-syntax encoding. This is not merely an internal-comparison rule: a property has a value and an encoding, quoting is the encoding, and the value (unquoted) is the default everywhere, including the public surface -- every public typed getter that returns a property's value returns the unquoted value, and raw is the opt-in (`getPropertyRaw(key)`/`getPropertiesRaw()`), not the default. See `VALIDATION_PHILOSOPHY.md`'s "How the principles interact" for the full statement of this rule, the naming invariant it produces, and why it is a distinct axis from strict-vs-lenient dispatch and from null-safety, and `DUPLICATE_METADATA_ID_HANDLING.md`'s "Quoting is not part of the identity" section for the dedup consequence.
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
- The Parser Validation Policy's "Unquoting before internal use" rule above applies here too, not just on the parse side: `VcfWriter.validateMetadata()`'s PEDIGREE loop and `validateKeyedMetadataEntries()` (both part of `validateBeforeWrite()`'s full-output diagnostics pass) compare a declaration's `ID` property unquoted for the identical reason.


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
- A third category sits between the two above: a parameter that is itself VCF content, but supplied as a
  required read-time collaborator a lookup method needs to resolve its answer, not content being validated
  for correctness or the key being looked up. `VcfSample.getProperty(VcfPosition position, VcfMetadata
  metadata, String key)`'s `position`/`metadata` and `VcfPosition.getInfo(VcfMetadata metadata, String id)`'s
  `metadata` are this category: each read resolves its version and warnings destination from that argument,
  so a `null` there gives the method nothing to resolve against -- not a well-formed "not present" answer to
  defer, the way a `null` `key`/`id` is. Both reject `null` with a raw `NullPointerException` via
  `Preconditions.checkNotNull`, the same mechanism as the caller-configuration bullet above, for a different
  reason (the value's role in the call, not its provenance) -- see `VALIDATION_PHILOSOPHY.md`'s "How the
  principles interact" for the distinction. `key`/`id` themselves remain covered by the lookup-by-key bullet
  above (null-tolerant, matching the `String`-keyed sibling); both implementations check `key`/`id` for `null`
  first, so a caller passing `null` for both arguments together gets the null-tolerant `null` return, not the
  NPE -- the NPE fires only when a real `key`/`id` is paired with a missing collaborator.
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
- **A probe is not a grammar.** The companion to the rule above, for external tools rather than for this codebase. You can only test an input shape you already thought of, so a rule induced from hand-built probes is complete only by luck -- and the shapes that come to mind are systematically the convenient ones, not the representative ones. Deriving the metadata attribute-list grammar this way produced several rounds of confidently wrong tables: every probe happened to put the interesting attribute *last* and to use an *uninterpreted* attribute name, and both choices silently inverted the result (bcftools warns and keeps a malformed value in that position, but discards the whole declaration in the layout real headers actually use, because a later `Description="..."` changes the parse). Neither a control row nor payload verification nor a code review of the probe script can catch this; only reading the parser can, because that is what enumerates the cases it actually distinguishes. Both tools' source is checked out for exactly this -- see the Decision Hierarchy above -- and `BEHAVIOR_TABLE_STANDARD.md` gives the method: derive the case list from source, write the predictions down *before* running anything, then measure. Two further traps worth naming, both of which produced a wrong table here: byte-identical re-emission proves nothing about how an input was *split* (an opaque value containing a comma re-emits exactly like two parsed attributes), and a tool verdict is field-sensitive, so a result measured on one declaration type or attribute name may not transfer to another.
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

**Both opt-in tasks print a one-line result on success, and that line carries the actual numbers** -- `htsSpecsTest` echoes the matched/total counts, the new/stale count, how many per-version rows moved, and `findings.tsv`'s line count, read back out of the report; `bcftoolsDependencyTest` echoes how many decisions it re-verified. Read that line. **`BUILD SUCCESSFUL` on its own is still not evidence**, and neither is the absence of console output: the line is emitted from a `doLast`, so it does not appear when the task **fails** (failure is already loud and names `findings.tsv`) and it does not appear when the task is **`UP-TO-DATE`**, where nothing executed at all -- Gradle prints the `UP-TO-DATE` marker beside the task name, which is how you tell "ran and passed" from "did not run." If you need the per-version breakdown rather than the totals, `build/reports/hts-specs-conformance/summary.md` remains authoritative and is what the line is read from; `findings.tsv` alongside it must stay header-only (exactly 1 line). Never infer the outcome from JUnit's `tests=` count. A moved row is a defect, never a re-baseline: `hts-specs-baseline.tsv` is only ever edited to *remove* an entry that legitimately stopped reproducing, which is a different act from re-recording a new divergence. This gate is the only regression alarm covering writer round-tripping, and it is fixture-driven -- so it cannot see anything reachable only through public construction (`VcfContext.newPosition`, a `Builder`). Treat "the gate will catch it" as a claim to check, not an assumption.

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
- This project also maintains its own locally-authored VCF conformance fixture corpus, hts-specs style, under
  `src/test/resources/org/pharmgkb/parser/vcf/spec/local-fixtures/{passed,failed}/` (exercised by
  `LocalFixtureConformanceTest`, with each fixture's expectation declared inline via the header-comment keys
  documented on `LocalFixtureMetadata`). Every fixture must carry at least one `##Authority=` line citing an
  external authority (spec text, a tool version, an upstream hts-specs fixture) for its expected behavior --
  never "this is what this library does."

### Release Files

- Versioning and changelog updates are managed by semantic-release.
- Do not manually edit `CHANGELOG.md`, the package version, or the README dependency version unless the task is specifically a release change.

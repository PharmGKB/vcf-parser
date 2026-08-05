# Won't Fix

This file tracks known, minor code-shape observations in this library that have been deliberately left
unaddressed, along with the reasoning, so they aren't rediscovered and re-proposed as new findings. Each
entry is self-contained: it names the exact code location and behavior, explains why it's not being fixed,
and states what would change that decision.

## InfoMetadata and FormatMetadata each independently duplicate their Number/Type validation logic

`InfoMetadata`'s and `FormatMetadata`'s private `init()` methods are structurally near-identical: both
check a `Number` property, parse a `Type` property against an enum (`InfoType` vs. `FormatType`) with a
warn-on-failure fallback, then cross-check the declared `Number`/`Type` against a reserved-property lookup
(`ReservedInfoProperty` vs. `ReservedFormatProperty`) and warn on a mismatch. The overall shape (Number
check → Type parse-with-warn → reserved-property Number/Type cross-check → "extra fields allowed" comment)
is line-for-line parallel between the two classes.

They are not, however, fully identical: `InfoMetadata.init()` has two blocks with no `FormatMetadata`
analog — a `Type=Flag`-specific check (`FormatType` has no `Flag` constant at all) and a loop warning if
`SOURCE`/`VERSION` properties aren't quoted (`FormatMetadata` declares no `SOURCE`/`VERSION` constants or
fields). Both classes also independently redeclare identical `DESCRIPTION`/`NUMBER`/`TYPE` property-key constants
and separately call the same inherited `checkNumberProperty(number)`/`warnIfMissing(TYPE, type)` helpers.

Each class now duplicates this logic a second way too, not just across the two classes: since `VALIDATION_PHILOSOPHY.md`'s
strict/lenient constructor split gave each typed constructor its own inline `Number`-format/`Type=Flag`/
reserved-property checks (thrown), the same three conditions are checked twice within a single class -- once
inline in the typed constructor (using the already-typed `InfoType`/`FormatType` enum and local variables,
no parsing needed), once in the shared `init()` (parsing a raw `Type` string, used by the Map-based
constructor). This is a different duplication shape than the cross-class one above (throw vs. warn, not
`InfoType` vs. `FormatType`), so it doesn't change this entry's own reasoning -- there's still no single
shared helper that could serve both without being parameterized over both the strict/lenient dispatch and
the two enum hierarchies at once, for a handful of few-line checks.

**Decision: won't fix.** A shared abstraction would need to be parameterized over two unrelated enum
hierarchies (`InfoType`/`ReservedInfoProperty` vs. `FormatType`/`ReservedFormatProperty`) for exactly 2 call
sites, and would still need special-cased branches for the `Flag`-type check and the `SOURCE`/`VERSION`
quoting check that only apply to one side — real complexity for marginal DRY benefit given the small,
fixed number of call sites. Revisit if a third sibling with the same Number/Type/reserved-property shape is
ever added, at which point the shared-abstraction case gets stronger.

## VcfParser.Builder's required-argument setters raise a raw NullPointerException, not VcfFormatException

`VcfParser.Builder.fromFile`/`fromReader`/`parseWith`/`warningsToFile(Path)` reject a null argument via
`Preconditions.checkNotNull`, which throws a plain `java.lang.NullPointerException` with no message -- the
same "wrong exception type for a null argument" shape that a long fix chain closed elsewhere across this
codebase (`VcfWriter`, `VcfPosition`, `VcfMetadata`, `BaseMetadata`, `InfoMetadata`, `FormatMetadata`,
`InternalUtils`, `VcfSample`, `MemoryMappedVcfDataStore`, `AltStructuralVariant`, and `VcfFloat`) by
converting it to `VcfFormatException`.

This is a different case, though: those other fixes closed a gap reachable from parsed VCF *content* --
a caller has no control over what a file contains, so a hostile or malformed value silently corrupting
output or crashing with an unhelpful exception is this library's problem to prevent. `fromFile`/
`fromReader`/`parseWith`/`warningsToFile(Path)` are the opposite: they are pure `Builder` configuration
entry points, called exactly once per parse, before any file content is read, with a `Path`/
`BufferedReader`/`VcfLineParser` the calling code itself constructs and passes in. Confirmed by grep: the
only two matches for these method names anywhere in `src/main` are Javadoc example snippets (in
`TransformingVcfLineParser.java` and `VcfWriter.java`), not real code -- nothing internal to this library
ever calls them, and their arguments can never be derived from a parsed VCF file's own data. A `null` here
is therefore always the direct library caller's own programming mistake (forgot to check a variable before
passing it in), never something an untrusted `.vcf` file could trigger -- exactly the case
`Preconditions.checkNotNull`'s fail-fast-with-NPE idiom is meant for, and why converting it to
`VcfFormatException` (a *content*-rejection exception type) would be the wrong fix even taken in
isolation, not just an inconsistent one. Contrast with
`TransformingVcfLineParser.Builder.transformation(VcfTransformation, VcfWriter)`'s `writer` argument,
which also uses `Preconditions.checkNotNull` -- the correct, existing pattern this decision matches, not a
defect.

**Decision: won't fix.** Converting `Preconditions.checkNotNull`'s NPE to `VcfFormatException` here would
mean re-litigating an established, consistently-applied convention across every `Builder` in this codebase,
not just `VcfParser.Builder`, for arguments that can never reach parsed VCF *content* -- a caller who never
passes a `Path`/`Reader` to `fromFile`/`fromReader` has a programming error, not a data-quality problem the
way a null CHROM or a null metadata property is. Revisit only alongside a deliberate, codebase-wide
decision to migrate every `Builder`'s required-argument convention away from `Preconditions.checkNotNull`,
not as a one-off triggered by this fix chain.

## AltStructuralVariant's parent-check no-ops when a component's parent isn't itself a reserved code

`AltStructuralVariant`'s constructor validates that a reserved code's declared parent (per
`ReservedStructuralVariantCode.getParentCodes()`) is actually present at the preceding colon-delimited
level -- but only when that preceding component *is itself* a recognized reserved code
(`ReservedStructuralVariantCode.fromId(...)` returns non-null). If the preceding component is not a
recognized reserved code (e.g. a custom, non-reserved token), the entire parent-check block is skipped, so
a colon-delimited ID like `CustomFoo:ME` (where `ME`/`MobileElement` declares `Insertion`/`Deletion` as its
only valid parents) is accepted without any parent-relationship check at all.

This is *not* reachable today with the current reserved-code set. The constructor unconditionally requires
the level-0 component to already be a recognized reserved code (`if (type == null && level == 0) throw
...`, `AltStructuralVariant.java:75-78`), and a reserved code is only accepted at the level matching its own
`getLevelInSpecification()` (lines 81-84). Since the only reserved codes today are five level-0 codes and two
level-1 codes (`Tandem`, `MobileElement`), the parent-check block (`level > 0`) can only ever fire at level
1, checking against the level-0 predecessor -- which is unconditionally guaranteed to already be a
recognized reserved code by the throw above. So `realParent` can never actually be null with today's
reserved-code set; the `CustomFoo:ME`-style example does not reach the parent-check at all, since
`CustomFoo` already throws at level 0. It is not clearly a bug even so: this library already treats an
unrecognized single-token component as a legitimate custom/non-reserved code (the same reasoning that
accepts the real-world `CN0`-style copy-number-allele convention elsewhere in this class). Since a
non-reserved parent could legitimately be anything, there is no defined "must be X" rule for the
parent-check to enforce in that case -- silently accepting it is plausibly the correct behavior, not a
validation gap.

**Decision: won't fix.** No current reserved code is deep enough (level 2+) to make a genuinely invalid
nesting reachable through *only* reserved codes, and the non-reserved-parent case is arguably correct
behavior rather than a gap, for the reason above. Revisit if a level-2+ reserved code is ever added to
`ReservedStructuralVariantCode`, since that would be the first case where a real "was this actually a valid
nesting" question has a definite right answer to enforce.

## `getDevVersionName()` in build.gradle has no call site

`build.gradle:21-33` defines `getDevVersionName()` (shells out to `git describe --tags` to compute a dev
version string), but nothing in `build.gradle` or `pgkb-build.gradle` currently calls it -- `getVersionName()`
(the function actually assigned to `project.version`) uses `git describe --tags --abbrev=0` instead.

**Decision: won't fix.** This is release-tooling dead code, not library behavior -- removing or wiring it in
doesn't affect anything this library does at runtime, and it's not clear which was intended (delete it, or
wire it into the version-computation path its name suggests it was written for). Revisit if someone
determines which was actually intended, or needs a dev-build version-naming scheme distinct from
`getVersionName()`'s tagged-release one.

## `VcfPosition.checkFilters` emits redundant warnings for a FILTER list with multiple `PASS` entries

`checkFilters` runs two independent checks within the same per-element loop over the FILTER list: one warns
whenever an element equals `PASS` and the list has more than one entry ("FILTER contains PASS along with
other filters"), and another warns whenever an element has already been seen earlier in the same list
("Duplicate FILTER code"). Neither check tracks whether it has already fired for the list as a whole --
each re-evaluates independently, once per matching element.

For `FILTER=["PASS","PASS"]`, this produces three warnings from one call: the PASS-combined check fires once
per `PASS` occurrence (twice, with an identical message both times, since it doesn't distinguish "the first
PASS" from "a later PASS"), and the duplicate-code check fires once more for the second occurrence. No data
is lost or corrupted -- both entries are preserved as-is -- this is purely warning-output noise.

**Decision: won't fix.** `FILTER=PASS;PASS` is already a degenerate, spec-invalid input on its own terms
(`PASS` is defined as meaning no filters failed; repeating it conveys no additional information), so
real-world occurrence is unlikely. Deduplicating the warning output would require either an early-exit flag
or restructuring the per-element loop into two passes -- real complexity for a purely cosmetic issue with no
functional impact. Revisit if a caller reports genuinely noisy warning output from this pattern, or if
`checkFilters`/`checkIds` are restructured for an unrelated reason that would make deduplication free.

## A malformed `GT` value with a leading phase marker produces two independent warnings, not one

`VcfSample.getProperty(VcfPosition, VcfMetadata, String)` and `VcfSample.getGenotype(VcfPosition)` each parse
a sample's raw `GT` string independently, for different purposes: the former calls
`InternalUtils.getPloidy` to resolve ploidy for a *different* key's `Number=G` cardinality check (or when
`GT` itself is the key requested), while the latter interprets `GT`'s own allele indices. Both split the
same raw string on `[|/]` and both react to the same defect -- a leading separator (VCFv4.4 syntax this
library's VCFv4.2 target doesn't implement, e.g. `"/0"`) producing an empty leading allele token -- from
their own independent code paths: `getPloidy` reports `"{} has invalid GT {}"` when `Integer.parseInt` fails
on the empty token, and `getGenotype` separately reports `"GT \"{}\" contains an empty allele..."` from its
own empty-base handling.

A consumer who calls both methods for the same sample (e.g. this library's own test-only
`DeferredValidationLineParser`, which deliberately exercises every deferred check) sees both warnings for
the one malformed value. A consumer who calls only one of the two methods -- the common case -- only ever
sees the warning from whichever one they called.

**Decision: won't fix.** These are two genuinely independent, correctly-functioning checks in two
independently-reachable public methods, each legitimately flagging the same bad input from its own angle --
not one check firing twice (the shape the `checkFilters`/`PASS` entry above describes). No data is lost or
corrupted in either path. Coordinating the two to suppress a second warning would require new cross-method
state (e.g. threading a "already warned about this GT" flag between `getPloidy` and `getGenotype`, which
have no other coupling today) for a purely cosmetic double-warning that only a deliberately exhaustive
caller would ever observe. Revisit if a real (non-test-harness) caller reports this as genuinely noisy, or
if `getPloidy` and `getGenotype` are unified onto one shared GT-parsing helper for an unrelated reason that
would make deduplication free.

## `BaseMetadata`/`IdDescriptionMetadata` constructors' `label` parameter has no null-guard, unlike every sibling parameter in the same constructors

`BaseMetadata(String label, Map<String, String> properties)`, `IdDescriptionMetadata(String label, String
id, String description)`, and `IdDescriptionMetadata(String label, Map<String, String> properties,
VcfWarnings warnings)` are all `public` constructors on `public` classes -- externally callable, not
internal-only, though the only in-repo caller (`VcfParser.java`) always passes a hardcoded literal
(`"ALT"`, `"FILTER"`, `"SAMPLE"`). Every other parameter in each of these constructors (`properties`, `id`,
`description`, `warnings`) is null-checked and throws `VcfFormatException` per the Public API Null-Safety
Policy; `label` is not. A caller passing `label = null` gets a fully-constructed object whose `getLabel()`
permanently returns `null`, and every future diagnostic this instance produces embeds the literal text
`"null"` (SLF4J's `MessageFormatter`, which every `m_warnings.warn("{} ...", getLabel(), ...)` call site
routes through, explicitly renders a `null` argument as `"null"`, not blank) -- e.g. `"null metadata ID is
blank"` instead of a message clearly attributed to `FILTER`/`INFO`/etc.

**Decision: won't fix.** `label` is caller-configuration, not VCF content, the same category
`VcfParser.Builder`'s required-argument setters (above) are exempted from this policy for: at every real
call site -- in this repo's own code, and for any external caller building `VcfMetadata` programmatically
without going through `VcfParser` -- `label` is a hardcoded classification string the calling code chooses
for itself (`"FILTER"`, `"INFO"`, a caller's own custom tag), never something derived from parsed VCF file
content the way `properties`/`id`/`description` are. A `null` here is the caller's own programming mistake,
not a data-quality problem an untrusted `.vcf` file could trigger. Revisit if a real caller reports this as
a genuine (non-hypothetical) source of confusing diagnostics, or if `label` ever becomes something this
library itself derives from parsed content rather than something every caller always supplies as a literal.

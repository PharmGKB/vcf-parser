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
...`, `AltStructuralVariant.java:67-69`), and a reserved code is only accepted at the level matching its own
`getLevelInSpecification()` (line 73-76). Since the only reserved codes today are five level-0 codes and two
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

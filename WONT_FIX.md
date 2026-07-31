# Won't Fix

This file tracks known, minor code-shape observations in this library that have been deliberately left
unaddressed, along with the reasoning, so they aren't rediscovered and re-proposed as new findings. Each
entry is self-contained: it names the exact code location and behavior, explains why it's not being fixed,
and states what would change that decision.

## IdMetadata subclasses built directly with an empty (not null) ID get no diagnostic

`IdMetadata`'s `(label, id)` typed constructor (`IdMetadata.java:21-23`) throws for a null/blank `id`, but
the `Map`-based constructor (and `init()`) never re-check it.

This is not a gap in normal VCF parsing: `VcfMetadata`'s `addAlt`/`addFilter`/`addInfo`/`addFormat`/
`addContig`/`addSample` methods (`VcfMetadata.java:141,249,265,281,297`) each independently check
`getId() == null || getId().trim().isEmpty()` and warn-and-drop before storing, and
`VcfParser.parseMetadataProperty` (`VcfParser.java:405-423`) always routes a parsed declaration through one
of those methods. Every declaration that reaches storage via real parsing is already checked there.

The one residual gap: constructing a subclass directly (e.g. `new ContigMetadata(someMap)`) with `ID=""`
and never passing it through one of `VcfMetadata`'s `addXxx` methods produces a blank-ID object with zero
diagnostic emitted (`warnIfMissing` doesn't fire because the value is non-null; the whitespace check
doesn't fire because there are no characters to contain whitespace). This is reachable only by hand-
building metadata objects outside the normal `VcfParser`/`VcfMetadata.Builder` construction paths, which is
not how any current caller in this library operates.

**Decision: won't fix.** The typed-constructor-throws / parse-path-warns split is an intentional design
(permissive parser, strict direct construction), and the residual gap only affects hand-built objects that
bypass the library's own dedup/validation entry points. Revisit if a real caller starts constructing
metadata subclasses directly from untrusted data outside `VcfParser`/`VcfMetadata.Builder`.

## BaseMetadata tolerates a null property value inserted via getPropertiesRaw()

`BaseMetadata.putPropertyRaw`/`putAndQuoteProperty` (`BaseMetadata.java:92,99`) throw `VcfFormatException`
on a null value, but `validateProperties()` only checks for null *keys*, not null values. A property map
containing a null value survives both construction (via the `Map`-based constructor, which bypasses the
put methods) and a later `validate()` call, as long as the null value was inserted directly through
`getPropertiesRaw()` rather than through `putPropertyRaw`/`putAndQuoteProperty`.

This is unreachable from real VCF parsing: `InternalUtils.splitProperty` always returns two non-null halves or
throws, so a parsed property value is never null. It's reachable only via a caller directly mutating the
map returned by `getPropertiesRaw()` with a hand-built null value. `BaseMetadata.checkNoLineTerminator`
already deliberately tolerates a null `value` parameter elsewhere in this class, suggesting this asymmetry
(reject a null value at the put methods, tolerate it if inserted directly via the raw map) is an
intentional design choice, not an oversight.

**Decision: won't fix.** Not reachable through any real parsing path; only affects a caller who bypasses
this class's own put methods to mutate the backing map directly. Revisit if `validate()` is ever expected
to provide a full structural guarantee regardless of how the map was populated.

## VcfWriter never re-validates ##SAMPLE declarations against #CHROM sample columns

`VcfParser.java:129-139` warns during parsing when a file's `##SAMPLE` declaration count/names don't match
its `#CHROM` header's sample columns. `VcfWriter.validateMetadata()` has no equivalent post-construction
check: its `validateKeyedMetadataEntries("SAMPLE", ...)` call only verifies that each `##SAMPLE` entry's
map key matches its own `ID` property (internal self-consistency), not that the full set of `##SAMPLE`
declarations agrees with the `#CHROM` sample-column list.

This means a caller who adds/removes a `##SAMPLE` declaration after construction (via
`VcfMetadata.addSample()`/`removeSample()`, or by mutating the map returned by `getSamples()` directly) and
then writes the file with `validateBeforeWrite()` enabled gets no warning if the result no longer matches
the `#CHROM` columns, even though the equivalent mismatch would have been caught had the file been
re-parsed from scratch.

**Decision: won't fix.** No current caller in this library performs this kind of post-construction SAMPLE
mutation followed by a validated write. Revisit if a real use case requiring this cross-check at write time
emerges.

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
same "wrong exception type for a null argument" shape that a long fix chain (see `findings22.md`'s
`## Completed`, Batches 22.4-22.5) closed elsewhere in this codebase by converting to `VcfFormatException`.

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
isolation, not just an inconsistent one. Contrast with `TransformingVcfLineParser.Builder`'s *missing*
check (`findings22.md`'s own Minor #1), which cites `Preconditions.checkNotNull` as the correct, existing
pattern other builders should match, not a defect.

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

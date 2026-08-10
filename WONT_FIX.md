# Won't Fix

This file tracks known, minor code-shape observations in this library that have been deliberately left
unaddressed, along with the reasoning, so they aren't rediscovered and re-proposed as new findings. Each
entry is self-contained: it names the exact code location and behavior, explains why it's not being fixed,
and states what would change that decision. Where several entries share the same underlying shape or
decision, they're grouped under one heading with sub-cases, rather than repeated as separate entries.

An entry that a later change closes -- deliberately or as a side effect -- is kept and marked `RESOLVED:` in
its heading, with a paragraph naming what closed it, rather than deleted. The original reasoning is what stops
the same observation being re-filed, and it stays useful as the record of why the trade-off was once made the
other way.

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

Each class now duplicates this logic a second way too, not just across the two classes: since each typed
constructor has its own inline `Number`-format/`Type=Flag`/reserved-property checks (routed through
`BaseMetadata.warnOrThrow`), the same three conditions are checked twice within a single class -- once
inline in the typed constructor (using the already-typed `InfoType`/`FormatType` enum and local variables,
no parsing needed), once in the shared `init()` (parsing a raw `Type` string, used by the Map-based
constructor). This is a different duplication shape than the cross-class one above (typed enum vs. raw string
input, not `InfoType` vs. `FormatType`), so it doesn't change this entry's own reasoning -- there's still no
single shared helper that could serve both without being parameterized over both the input shape and
the two enum hierarchies at once, for a handful of few-line checks.

**Decision: won't fix.** A shared abstraction would need to be parameterized over two unrelated enum
hierarchies (`InfoType`/`ReservedInfoProperty` vs. `FormatType`/`ReservedFormatProperty`) for exactly 2 call
sites, and would still need special-cased branches for the `Flag`-type check and the `SOURCE`/`VERSION`
quoting check that only apply to one side — real complexity for marginal DRY benefit given the small,
fixed number of call sites. Revisit if a third sibling with the same Number/Type/reserved-property shape is
ever added, at which point the shared-abstraction case gets stronger.

## Caller-configuration parameters use a raw `NullPointerException`, not `VcfFormatException`

Two unrelated call sites both reject a `null` caller-configuration argument via `Preconditions.checkNotNull`
(a plain `java.lang.NullPointerException`, no message) instead of this library's usual `VcfFormatException`
-- the same "wrong exception type for a null argument" shape that a long fix chain closed elsewhere across
this codebase (`VcfWriter`, `VcfPosition`, `VcfMetadata`, `BaseMetadata`, `InfoMetadata`, `FormatMetadata`,
`InternalUtils`, `VcfSample`, `MemoryMappedVcfDataStore`, `AltStructuralVariant`, and `VcfFloat`) by
converting it to `VcfFormatException`. Both are deliberate exceptions to that fix chain, for the same reason:
see the shared decision below.

### `VcfParser.Builder`'s required-argument setters

`VcfParser.Builder.fromFile`/`fromReader`/`parseWith`/`warningsToFile(Path)` reject a null argument via
`Preconditions.checkNotNull`. These are pure `Builder` configuration entry points, called exactly once per
parse, before any file content is read, with a `Path`/`BufferedReader`/`VcfLineParser` the calling code
itself constructs and passes in. Confirmed by grep: nothing internal to this library ever calls them, and
their arguments can never be derived from a parsed VCF file's own data.

### `BaseMetadata`/`IdDescriptionMetadata` constructors' `label` parameter

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
blank"` instead of a message clearly attributed to `FILTER`/`INFO`/etc. `label` is caller-configuration, not
VCF content, the same category `VcfParser.Builder`'s required-argument setters (above) are exempted from
this policy for: at every real call site -- in this repo's own code, and for any external caller building
`VcfMetadata` programmatically without going through `VcfParser` -- `label` is a hardcoded classification
string the calling code chooses for itself (`"FILTER"`, `"INFO"`, a caller's own custom tag), never
something derived from parsed VCF file content the way `properties`/`id`/`description` are.

**Decision: won't fix, for both cases above.** A `null` here is always the direct caller's own programming
mistake (forgot to check a variable before passing it in), never something an untrusted `.vcf` file could
trigger -- exactly the case `Preconditions.checkNotNull`'s fail-fast-with-NPE idiom is meant for, and why
converting either to `VcfFormatException` (a *content*-rejection exception type) would be the wrong fix even
taken in isolation, not just an inconsistent one. Converting `VcfParser.Builder`'s case specifically would
also mean re-litigating an established, consistently-applied convention across every `Builder` in this
codebase, not just this one. Revisit `VcfParser.Builder`'s case only alongside a deliberate, codebase-wide
decision to migrate every `Builder`'s required-argument convention away from `Preconditions.checkNotNull`,
not as a one-off. Revisit `label`'s case if a real caller reports this as a genuine (non-hypothetical) source
of confusing diagnostics, or if `label` ever becomes something this library itself derives from parsed
content rather than something every caller always supplies as a literal.

## AltStructuralVariant's parent-check no-ops when a component's parent isn't itself a reserved code

`AltStructuralVariant`'s constructor validates that a reserved code's declared parent (per
`ReservedStructuralVariantCode.getParentCodes()`) is actually present at the preceding colon-delimited
level -- but only when that preceding component *is itself* a recognized reserved code
(`ReservedStructuralVariantCode.fromId(...)` returns non-null). If the preceding component is not a
recognized reserved code (e.g. a custom, non-reserved token), the entire parent-check block is skipped, so
a colon-delimited ID like `CustomFoo:ME` (where `ME`/`MobileElement` declares `Insertion`/`Deletion` as its
only valid parents) is accepted without any parent-relationship check at all.

This is *not* reachable today with the current reserved-code set. The constructor unconditionally requires
the level-0 component to already be a recognized reserved code (the `if (type == null && level == 0) throw
...` guard in `AltStructuralVariant`'s constructor), and a reserved code is only accepted at the level
matching its own `getLevelInSpecification()` (the constructor's own
`if (type != null && level != type.getLevelInSpecification())` guard). Since the only reserved codes today
are six level-0 codes (`Deletion`, `Insertion`, `Duplication`, `Inversion`, `Cnv`, `Breakend`) and two
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

`build.gradle:21-34` defines `getDevVersionName()` (shells out to `git describe --tags` to compute a dev
version string), but nothing in `build.gradle` or `pgkb-build.gradle` currently calls it -- `getVersionName()`
(the function actually assigned to `project.version`) uses `git describe --tags --abbrev=0` instead.

**Decision: won't fix.** This is release-tooling dead code, not library behavior -- removing or wiring it in
doesn't affect anything this library does at runtime, and it's not clear which was intended (delete it, or
wire it into the version-computation path its name suggests it was written for). Revisit if someone
determines which was actually intended, or needs a dev-build version-naming scheme distinct from
`getVersionName()`'s tagged-release one.

## Multiple independent checks warn separately about the same underlying defect

Three unrelated cases all produce more than one warning for what is, from a caller's point of view, a single
defect. In every case, no data is lost or corrupted, and coordinating the checks to suppress a duplicate
warning would require new complexity (a shared flag, cross-method state, or a loop restructure) for a purely
cosmetic issue. The shape differs per case (documented in each sub-case below), which is why they're
recorded separately rather than as one code location, but the underlying reasoning is the same in all three.

### `VcfPosition.checkFilters`: repeated `PASS` entries

`checkFilters` runs two independent checks within the same per-element loop over the FILTER list: one warns
whenever an element equals `PASS` and the list has more than one entry ("FILTER contains PASS along with
other filters"), and another warns whenever an element has already been seen earlier in the same list
("Duplicate FILTER code"). Neither check tracks whether it has already fired for the list as a whole --
each re-evaluates independently, once per matching element. For `FILTER=["PASS","PASS"]`, this produces
three warnings from one call: the PASS-combined check fires once per `PASS` occurrence (twice, with an
identical message both times, since it doesn't distinguish "the first PASS" from "a later PASS"), and the
duplicate-code check fires once more for the second occurrence.

This is the "one check firing repeatedly within itself" shape -- contrast with the two sub-cases below,
where two *separate* checks each independently notice the same defect from their own vantage point, not one
check re-firing. `FILTER=PASS;PASS` is already a degenerate, spec-invalid input on its own terms (`PASS` is
defined as meaning no filters failed; repeating it conveys no additional information), so real-world
occurrence is unlikely. Deduplicating this specific warning output would require either an early-exit flag
or restructuring the per-element loop into two passes.

### A malformed `GT` value with a leading phase marker

`VcfSample.getProperty(VcfPosition, VcfMetadata, String)` and `VcfSample.getGenotype(VcfPosition)` each parse
a sample's raw `GT` string independently, for different purposes: the former calls
`InternalUtils.getPloidy` to resolve ploidy for a *different* key's `Number=G` cardinality check (or when
`GT` itself is the key requested), while the latter interprets `GT`'s own allele indices. Both split the
same raw string on `[|/]` and both react to the same defect -- a leading separator (VCFv4.4 syntax this
library's VCFv4.3 target doesn't implement, e.g. `"/0"`) producing an empty leading allele token -- from
their own independent code paths: `getPloidy` reports `"{} has invalid GT {}"` when `Integer.parseInt` fails
on the empty token, and `getGenotype` separately reports `"GT \"{}\" contains an empty allele..."` from its
own empty-base handling.

A consumer who calls both methods for the same sample (e.g. this library's own test-only
`DeferredValidationLineParser`, which deliberately exercises every deferred check) sees both warnings for
the one malformed value. A consumer who calls only one of the two methods -- the common case -- only ever
sees the warning from whichever one they called. These are two genuinely independent, correctly-functioning
checks in two independently-reachable public methods, each legitimately flagging the same bad input from its
own angle -- not one check firing twice, the shape the `checkFilters`/`PASS` case above describes.
Coordinating the two to suppress a second warning would require new cross-method state (e.g. threading an
"already warned about this GT" flag between `getPloidy` and `getGenotype`, which have no other coupling
today) for a purely cosmetic double-warning that only a deliberately exhaustive caller would ever observe.

### Missing or blank metadata ID

A missing or blank `ID` on any of the six keyed metadata declaration types (INFO/FORMAT/FILTER/ALT/contig/
SAMPLE) is independently caught at two layers: first at construction time, by the declaration object's own
`IdMetadata.init()` (e.g. "INFO metadata ID is blank"), and again at registration time, by
`VcfMetadata`/`Builder`'s own `addXxx` method, which finds no usable ID to key the declaration by and warns
before dropping it (e.g. "INFO metadata declaration has no ID and cannot be stored; dropped: {...}"). Like
the `GT` case above (not the `checkFilters` case), this is two independently-reachable checks, not one check
firing twice: parsing one malformed header line produces both warnings in the same log, both correct, both
about the same root cause.

**Decision: won't fix, for all three sub-cases above.** Each is either a single check re-firing on a
degenerate, unlikely-in-practice input (`checkFilters`), or two independently-reachable, independently
correct checks with no other coupling (`GT`, missing/blank ID) -- coordinating either shape would add real
complexity for output that is informative rather than misleading. Revisit any sub-case if a real
(non-test-harness) caller reports it as genuinely noisy, or if the relevant methods are ever unified onto one
shared implementation for an unrelated reason that would make deduplication free.

## A quoted-empty or quoted-whitespace-only `ID` escapes the missing/blank-`ID` check

Every missing/blank-`ID` check across all seven structured declaration types (INFO/FORMAT/FILTER/ALT/contig/
SAMPLE via `IdMetadata.init()`, and PEDIGREE via `VcfMetadata.Builder.addPedigree`, gated on VCFv4.3+) reads
`getPropertyRaw(ID)` -- the still-quoted string as it appeared in the header line -- not
`getPropertyUnquoted(ID)`, the only method in this hierarchy that actually strips a surrounding pair of
quotes. `ID=""` (a quoted, genuinely-empty value) is unaffected by `id.trim().isEmpty()`: the raw two-character
string `""` is neither `null` nor blank once trimmed (the quote characters aren't whitespace), so it passes
every check in `IdMetadata.init()` -- no "is blank" warning, no whitespace warning, no comma warning -- and is
stored verbatim as the literal two-character ID `""`, even though the declaration has no real ID at all. A
quoted-whitespace value (`ID="   "`) fares only slightly better for the six `IdMetadata`-based types: the raw
string does contain real space characters between the quotes, so `InternalUtils.containsWhitespace` happens to
fire and produce a warning -- but the wrong one ("must not contain whitespace" instead of "is blank"), and the
declaration is still kept under a functionally-empty ID rather than treated as missing. **Not PEDIGREE**,
though: `VcfMetadata.Builder.addPedigree` has only the `id.trim().isEmpty()` test (no whitespace check at all),
so a quoted-whitespace `ID="   "` there produces zero warnings -- a fully silent escape, worse than the
misleading-but-present warning the six keyed types produce. `VCFv4.3.tex` line 121 ("All structured lines
require an ID which must be unique within their type") makes no exception for a quoted-but-empty value.

This is pre-existing and uniform across all seven types, which is why no earlier task noticed or fixed it
while working on any one declaration type individually -- each type's own ID check delegates to the exact
same `IdMetadata.init()`/`addPedigree` logic, so this was never a per-type oversight to catch. It's a real gap
in "every structured line requires an ID," not the informative-double-warning shape the "Missing or blank
metadata ID" entry above describes -- there the ID truly is caught, just by two independent checks; here a
plausible-looking input escapes detection (or is flagged with a misleading message) entirely.

**Decision: won't fix for now.** Fixing it means teaching `IdMetadata.init()`/`addPedigree` to check
`getPropertyUnquoted(ID)` (or an unquoted-and-trimmed copy of the raw value) instead of the raw string
directly, which changes behavior across all seven declaration types at once and is a real, deliberate design
change, not the docs sweep that surfaced it. Flagged here rather than silently left to be rediscovered.
Revisit by fixing `IdMetadata.init()`/`addPedigree` to test the unquoted value, with regression fixtures for
both `ID=""` and `ID="   "` across at least one keyed type and PEDIGREE.

## `BaseMetadata.checkAttributeOrder()` doesn't detect a custom/unrecognized attribute wedged between two canonical ones

`checkAttributeOrder()` only checks relative order among the six well-known VCF
metadata keys (`ID`/`Number`/`Type`/`Description`/`Source`/`Version`); any other key is filtered out before
the order comparison. Empirically confirmed: an `InfoMetadata` declared in the order `ID, Number,
CustomExtra, Type, Description` produces zero warnings -- `CustomExtra` sitting between `Number` and `Type`
is silently accepted, even though `VCFv4.2.tex`'s prose ("extra fields can be included after the default
fields") implies extras belong at the end, not interleaved. The three hts-specs fixtures this method's own
Javadoc cites (`failed_meta_{info,format}_003.vcf`, `failed_meta_alt_004.vcf`) only rearrange canonical
fields relative to each other -- none test an extra field wedged between two canonical ones, so this exact
shape is genuinely untested by the cited evidence, not merely unfixed despite being covered.

**Decision: won't fix.** The method's own Javadoc already documents this scope limitation explicitly ("A key
this declaration kind doesn't use, or any other extra/unrecognized key, is ignored"), so this is a
documented design choice, not a hidden gap. It also matches this project's broader deliberate leniency
toward extra/custom fields elsewhere (e.g. `ContigMetadata.ensureNoExtras()` was removed per
`VCF_SPEC_AMBIGUITIES.md`, choosing not to reject unrecognized `##contig` attributes). Tightening this to
also flag an interleaved extra would be a real behavior change (a new warning for a previously-silent case)
with no bcftools/vcf_validator evidence motivating it. Revisit if a real fixture or caller report surfaces a
concrete case where this ordering gap causes actual confusion, or if bcftools/vcf_validator consultation
finds either tool treats an interleaved extra attribute as invalid.

## `PedigreeMetadata` duplicate-ID dedup treats a present-but-blank `ID` the same as a real ID

**Scoped to VCFv4.2 only as of the VCF 4.3-target phase's Task 4.** Task 4 gave `##PEDIGREE` its own,
version-gated missing/blank-`ID` rule at VCFv4.3+ (VCFv4.3.tex line 121, "All structured lines require an
ID"), on **both** routes described below -- `VcfMetadata.Builder.addPedigree` at construction, and
`VcfWriter.validateMetadata()`'s PEDIGREE loop at write time: under a 4.3+ context, a blank `ID` is now caught
*before* the duplicate-ID branch each describes, warns "has no ID", and is deliberately never added to the
respective dedupe set (`m_pedigreeIds` / the write-time `pedigreeIds`) -- so two blank-ID `##PEDIGREE`
declarations under 4.3+ each warn independently rather than the second colliding with the first as a spurious
`Duplicate ID ""` the way this entry documents. The reasoning and "won't fix" decision below are unchanged and
still current for VCFv4.2, where `##PEDIGREE` has no `ID` attribute in its own grammar at all and this task's
version gate never activates -- see `DUPLICATE_METADATA_ID_HANDLING.md`'s "Missing or empty ID" section's
seventh-type paragraph for the current, version-split rule.

`VcfMetadata.addPedigree`/`VcfMetadata.Builder.addPedigree` and `VcfWriter.validateMetadata()`'s PEDIGREE loop
gate their duplicate-ID dedup on `id != null` only, with no blank-string check, **below VCFv4.3** (see the
scoping paragraph just above for the 4.3+ behavior) -- unlike the six keyed types (INFO/FORMAT/FILTER/ALT/
contig/SAMPLE), whose own `addXxx` methods explicitly drop a declaration with a missing OR blank `ID` before
ever reaching the duplicate check (`DUPLICATE_METADATA_ID_HANDLING.md`'s "Missing or empty ID" section). This
looked, on first read, like an inconsistency: two `##PEDIGREE=<ID=,...>` lines each declaring a literal empty
`ID=` collide on that empty string and the second is silently dropped, where the six-keyed-type convention
would keep both.

It isn't an inconsistency once bcftools is actually consulted. `DUPLICATE_METADATA_ID_HANDLING.md`'s own
"Empty/whitespace ID is a deliberate stricter-than-bcftools choice" section already establishes that the
six keyed types' blank-ID exemption is a project-specific editorial decision that bcftools itself does NOT
follow (bcftools keeps an empty-ID `INFO`/`FORMAT`/`contig`/`FILTER` declaration). PEDIGREE was never given
that same editorial exemption -- its own rule, per the same file's "`##PEDIGREE`: a conditional case"
section, is simply "dedupe on `ID` when present, regardless of tag name," chosen specifically to match
bcftools's actual observed behavior (bcftools keeps both of two ID-*less* PEDIGREE lines, but drops the
second of two sharing a literal `ID=P1`).

**Verified directly (bcftools 1.23.1, htslib 1.23.1):** a hand-built fixture with two `##PEDIGREE=<ID=,...>`
lines (empty `ID=`, differing `Name_0`) fed to `bcftools view -h` keeps only the first line and silently
drops the second -- confirming bcftools treats an empty `ID=` on `##PEDIGREE` as a real, dedupe-worthy
value, the opposite of its own leniency for the six keyed types. This specific case (empty, not merely
missing, `ID=` on `##PEDIGREE`) was not previously covered by `DUPLICATE_METADATA_ID_HANDLING.md`'s existing
bcftools-verified table, which only tested "no `ID` property at all" vs. "two lines sharing `ID=P1`."

**Decision: won't fix.** The current behavior already matches bcftools; adding a blank-ID exemption to
`addPedigree` (mirroring the six keyed types) would make PEDIGREE diverge from bcftools's actual behavior
for no documented reason, since PEDIGREE was never granted that stricter-than-bcftools carve-out in the
first place. `DUPLICATE_METADATA_ID_HANDLING.md`'s PEDIGREE table has been updated with this empty-`ID=`
case so a future sweep doesn't re-derive the same question. Revisit only if a future bcftools version
changes this specific behavior (see `BCFTOOLS_VERSION_DEPENDENCIES.md`).

## RESOLVED: Metadata duplicate-ID dedup caches had narrow staleness gaps for uncommon mutation patterns

**Both sub-cases below were closed by freezing the metadata layer** (`refactor(parser,writer)!: make metadata
declarations immutable, closing the live-ID/key gap`), which removed the public mutation routes both depended
on. The entry is kept, with its original reasoning intact below, because it records exactly what the two gaps
were and why they were once accepted -- reintroducing any public post-construction mutation route to a
`VcfMetadata` collection or a `BaseMetadata`'s properties reopens them.

- **`List.set`/`ListIterator.set` on `getPedigrees()`**: `VcfMetadata.getPedigrees()` now returns
  `Collections.unmodifiableList(...)`, so `set(i, replacement)` and `listIterator().set(...)` both throw
  `UnsupportedOperationException`. There is no instance `addPedigree` left either, so the built object holds
  no ID cache at all -- only `VcfMetadata.Builder` does, over a list it never exposes. (Because nothing can
  desync it any more, that cache was simplified from a `modCount`-guarded, rebuild-on-demand
  `TrackedPedigreeList` to a plain incrementally-maintained `Set<String>`; the staleness machinery this entry
  describes no longer exists to be gapped.)
- **The six `HashMap`-keyed types' in-place `ID` mutation**: this needed an already-added entry's `ID` to
  change while its map key did not, which required `putPropertyRaw` or a live `getPropertiesRaw()`. Both are
  gone from the public API: `getPropertiesRaw()` returns an unmodifiable view, and `putPropertyRaw`/
  `putAndQuoteProperty` are package-private (a subclass constructor's own populate step). Point 1 of the
  original reasoning below -- "immutability isn't available as an option here, the mutation is real, existing,
  tested functionality" -- is what changed: the freeze removed that functionality deliberately, from the whole
  hierarchy at once, rather than for this gap alone. `VcfWriter.validateKeyedMetadataEntries()`'s ID-vs-key
  mismatch check remains live and remains tested (via the package-private `trustedMutableProperties()` bypass);
  whether it can now go is Task 11's question, not this entry's.

  **Scope of "closed": the public API, not this package's own code.** For the six keyed types the inserters
  still work in-package (their typed constructors need them), so a same-package caller can still drift an
  entry's `ID` away from its map key -- which is exactly what the writer check above exists to catch, and what
  `VcfWriterTest` uses `trustedMutableProperties()` to exercise. `PedigreeMetadata` is the one exception: it
  overrides both inserters to throw even in-package, because `Builder.addPedigree`'s dedup `Set` has no
  recompute path and would silently go stale. Do not generalise PEDIGREE's stronger guarantee to the other
  five types, or the other five's weaker one to PEDIGREE.

Original reasoning, unedited:

Both of this library's duplicate-ID dedup caches -- `PedigreeMetadata`'s list-based one and the six
`HashMap`-keyed types' (INFO/FORMAT/FILTER/ALT/contig/SAMPLE) map-based one -- have a residual staleness gap
for a mutation pattern more unusual than the ones their respective fixes/DoS-prevention mechanisms already
cover. Both were found while working on the same area of code (an O(n^2) header-parsing DoS fix for
`PedigreeMetadata`, which raised the natural question of whether comparable gaps exist elsewhere), and both
are left unfixed for a shared reason: see the decision below.

### `PedigreeMetadata`'s list-based cache: `List.set`/`ListIterator.set`

`VcfMetadata.addPedigree`/`Builder.addPedigree` cache seen PEDIGREE IDs for performance (the O(n^2)
header-parsing DoS fix -- see the commit that introduced `TrackedPedigreeList`/`hasPedigreeIdCollision`). The
cache's staleness signal is `TrackedPedigreeList.modCountSnapshot()`, which mirrors `java.util.ArrayList`'s
own internal `modCount` bookkeeping: it increments on every *structural* modification (`add`, `remove`,
`clear`, iterator removal, a remove-then-add pair, etc.), so all of those are correctly detected and force a
cache rebuild. `List.set(int, Object)` is, by `java.util.AbstractList`'s own definition, NOT a structural
modification (it doesn't change the list's size or otherwise invalidate an in-progress iteration) and does
not increment `modCount` -- so replacing an already-added `PedigreeMetadata` with a different one at the
same list index, via `metadata.getPedigrees().set(i, replacement)`, would go undetected: the cache would
still associate that slot with the OLD entry's ID, not the new one, until some other structural change
forces a rebuild. `ListIterator.set(...)` (obtained via `metadata.getPedigrees().listIterator()`) is the
same underlying operation -- `ArrayList`'s own iterator implementation routes it to the identical
non-structural `set` -- so it shares this exact gap too, not a separate one.

No existing test, precedent, or documented usage anywhere in this codebase calls `List.set(...)`/
`ListIterator.set(...)` on the value returned by `getPedigrees()` (or on any of the other "live view" getters
this library exposes) -- every real mutation pattern this library's own tests exercise for a live list/map
getter is add/remove/put, never an in-place index replacement. `PedigreeMetadata` was separately made
immutable after construction (see its own class Javadoc) specifically to close a *related but distinct*
vector -- mutating an already-added entry's own `ID` property in place -- because that one WAS realistically
reachable (the identical mutation pattern is already used elsewhere in this codebase's own tests, just on
other metadata types). This `List.set`/`ListIterator.set` vector requires a caller to deliberately replace a
list slot's occupant via low-level `List` API misuse with no existing analog anywhere in this project.

### The six `HashMap`-keyed types' `warnIfDuplicate`: in-place `ID` mutation

`VcfMetadata`'s/`Builder`'s six keyed types (INFO/FORMAT/FILTER/ALT/contig/SAMPLE) dedupe via
`warnIfDuplicate(Map<String, XxxMetadata>, id, ...)`, an O(1) `HashMap.containsKey(id)` check keyed by
`md.getId()` captured at add time. `IdMetadata.getId()` reads live from the object's own mutable raw-properties
map, and every `BaseMetadata` subclass exposes `putPropertyRaw`/`putAndQuoteProperty` plus a live
`getPropertiesRaw()` map as a real, tested, load-bearing mutation path --
`VcfWriterTest.testHeaderContentRejectionAllowsCorrectedRetry` calls
`metadata.getInfo().get("AF").putPropertyRaw("ID", ...)` directly on an already-added, already-keyed entry.

So an already-added entry's `ID` can change without the map's key ever updating: it stays reachable at its OLD
key, but `getId()` reports the NEW value. If a caller later adds a new declaration whose ID happens to match
that new value, `warnIfDuplicate`'s `containsKey` check misses it (the map's key set still only has the old
value), and the add silently succeeds -- leaving two entries in the map, under different keys, that both report
the same `getId()`.

**Verified empirically** (`FILTER`, representative of all six -- same shared `warnIfDuplicate` mechanism): add
`md1` (id="A") and `md2` (id="B"); mutate `md2.getPropertiesRaw().put("ID", "C")` (now reports `getId()=="C"`,
still keyed under `"B"`); add `md5` (id="C") -- wrongly accepted, since the map has no key `"C"` yet. Result:
`{A: md1, B: md2(getId=C), C: md5(getId=C)}` -- two entries reporting `getId()==C`.

Two things distinguish this from the `PedigreeMetadata` sub-case above, beyond both being cache-staleness
gaps found in the same investigation:

1. **Immutability isn't available as an option here.** `PedigreeMetadata` was made immutable because nothing
   anywhere in this codebase mutates it post-construction -- that fix was free. The exact mutation pattern
   that triggers this gap (`putPropertyRaw("ID", ...)` on an already-added entry) is itself real, existing,
   tested functionality for these six types. Removing it would break real behavior, not just close a
   theoretical gap.
2. **The failure mode is one-directional and already caught downstream, unlike `PedigreeMetadata`'s DoS bug.**
   A stale `HashMap` key can only ever cause a false-negative (silently accepting a duplicate) -- it can never
   cause a false-positive rejection of valid content, since mutating an existing entry's `ID` can only break an
   association the map already had, never fabricate a new key the map didn't have. `PedigreeMetadata`'s DoS
   bug (in the add-time duplicate-ID cache introduced by that fix, not in any write-time check) could go the
   *other* way too -- wrongly rejecting or silently dropping genuinely valid content at the moment of the
   `addPedigree` call, before it was ever stored, which no write-time check could ever recover. This gap's one
   failure direction, by contrast, is already independently caught before anything is written:
   `VcfWriter.validateKeyedMetadataEntries()` (used by `VcfWriter.Builder.validateBeforeWrite()`) verifies every
   entry's own reported `ID` matches its map key and throws `VcfFormatException` if they've drifted apart --
   see `VcfWriterTest.testHeaderContentRejectionAllowsCorrectedRetry`, which pins exactly this ("MISMATCHED" ID)
   scenario. (This check originally only fired when the drifted-to `ID` was a different non-null value; a
   later sweep found that removing `ID` entirely, rather than changing it, escaped detection --
   `validateKeyedMetadataEntries()`'s `id != null` guard short-circuited before the mismatch check ever ran,
   so `validateBeforeWrite()` silently wrote a spec-invalid declaration with no `ID` at all; this was confirmed
   to cause real round-trip data loss on re-parse. Fixed by removing that guard -- a missing `ID` (`null`) can
   never equal a non-null map key, so the existing `!mapKey.equals(id)` comparison alone now correctly catches
   both shapes; see `VcfWriterTest.testValidateBeforeWriteRejectsEntirelyRemovedId`. This point's claim is now
   accurate for both the changed-ID and removed-ID shapes.)
3. **Not reachable from untrusted file content at all.** A normal `VcfParser` parse only ever adds each
   declaration once; nothing during parsing mutates an already-added entry's `ID`. This gap requires
   deliberate, first-party post-parse programmatic mutation (e.g. code that renames an `ID`),
   not something a crafted `.vcf` file could trigger -- a materially different risk profile than the
   `PedigreeMetadata` DoS or the `BigDecimal`/CIGAR findings, all of which were reachable from file content
   alone.

**Decision: won't fix, for both sub-cases above.** Neither is a comparable situation to the
`PedigreeMetadata` DoS fix that surfaced them: the `List.set` gap has no realistic caller pattern to trigger
it at all, and the `HashMap`-key gap can't be fixed the same way (immutability isn't available, since the
mutation it would remove is real load-bearing functionality) but is also lower-risk (one-directional,
downstream-caught, not reachable from file content). Revisit the `List.set` sub-case if a real caller or
fixture ever demonstrates that pattern in practice, or if `getPedigrees()`'s live-view contract is ever
revisited more broadly. Revisit the `HashMap`-key sub-case if a real caller reports actually hitting this (as
opposed to it being found by deliberate investigation), or if `validateBeforeWrite()`'s existing mismatch
check is ever found to have another gap of its own beyond the removed-ID one already found and fixed above.

## `VcfPosition`'s eager (`ListMultimap`) constructor still keeps a lone `.` INFO key that the lazy raw-text path drops entirely

`VcfPosition.checkInfoEntries` (used by the eager `VcfContext.newPosition` path and `validate()`) rejects a `.` (missing-value
sentinel) key combined with any other property, matching `info()`'s own lazy raw-text-parsing path. But when
a `.` key is the *only* entry present (e.g. `info.put(".", "")` with nothing else), the eager path keeps it
as a real property named `.` in the resulting map; the lazy path instead produces a completely empty map for
the equivalent raw text (a bare `"."` or `".;"`), per `testLazyInfoDotCannotCombineWithOtherProperties`.

**Verified empirically**: `VcfContext.newPosition(..., info, ...)` where `info` contains only `{".": [""]}`
succeeds, and `position.getInfo(".")` returns `[""]` -- not an empty map the way the equivalent raw-text case
produces.

**Decision: won't fix; low-impact, narrow residual asymmetry.** Discovered incidentally while fixing a real,
confirmed bug in `VcfPosition.checkInfoEntries` (the eager constructor/`validate()` INFO exclusivity/
empty-key gap; see that method's own code and Javadoc); this narrower case is not comparable in severity:

1. **No data loss or corruption.** The writer emits this position's INFO column as literally `.` (a
   single-key multimap with an empty value under `.` round-trips to the `.` sentinel on write), which
   re-parses via this library's own `VcfParser` as "no INFO at all" -- the same end state the lazy path
   reaches directly. The two paths converge on write/re-parse; they only differ in what `getInfo()` reports
   for the position object's in-memory lifetime before that.
2. **Only reachable via direct/programmatic construction** with a hand-built `ListMultimap` explicitly
   containing a `.` key -- not reachable from untrusted file content, since the parser always uses the lazy
   path.

Revisit if a real caller reports actually depending on `getInfo()` reporting no INFO at all (rather than a
literal `.` key) for this exact input shape, or if the two paths are ever unified more broadly.

## `InternalUtils.checkFilterLikeValue`'s FT grammar diverges from `VcfPosition.checkFilters`'s FILTER-column grammar for repeated `PASS`/`.` codes

`checkFilterLikeValue`'s "combines PASS or '.' with other filter codes" warning was fixed to use a
distinct-code count instead of a raw kept-code count (see its own code comment at `InternalUtils.java` and
the regression test `InternalUtilsTest.testCheckReservedFormatConstraintsFtDoesNotWarnWhenPassOrMissingIsNotActuallyCombinedWithAnotherCode`);
it now only fires when at least two *distinct* kept codes are present, one of which is `PASS`/`.`. This means
`FT="PASS;PASS"` and `FT=".;."` now produce zero diagnostics -- no warning at all, not even for the
repetition itself.

`VcfPosition.checkFilters` (the FILTER *column*, a different method for a different field) does not treat
these the same way: `["PASS","PASS"]` warns ("Duplicate FILTER code", see this file's own "Multiple
independent checks warn separately about the same underlying defect" entry's `checkFilters` sub-case
above), and `[".","."]` throws unconditionally (`.` combined with anything, including another `.`, is never
allowed for FILTER). FT's own method's code comment cites `checkFilters`'s `.`-exclusivity precedent for why
`PASS;.` must still warn, which could read as implying full parity with `checkFilters` -- it does not extend
to same-code repetition.

**Decision: won't fix; deliberate, narrower scope than full FILTER/FT grammar parity.** `checkFilterLikeValue`
already diverges from `checkFilters` in enforcement style for good, previously-established reasons: FT is
non-structural sample content (warns, never throws, unlike FILTER's own structural floor for the `.`-combined
case), and bcftools 1.23.1 treats `FT` as an opaque `String` with no knowledge of its own reserved grammar
(verified live: `FT=q10;q10` round-trips silently, no warning) -- FT duplicate-code detection was never
implemented to begin with, independent of this fix. This fix's scope was narrowly the misleading wording of
one specific warning (firing for same-code repetition when its own text implies a genuinely different code is
present), not introducing new diagnostics FT never had. Extending full `checkFilters`-parity duplicate-code
detection to FT is a legitimate future enhancement, not a defect this fix introduced or regressed.

Revisit if a real caller reports actually wanting FT duplicate-code detection, or if `checkFilterLikeValue`
and `checkFilters` are ever intentionally unified into one shared implementation.

## RESOLVED: `MemoryMappedVcfDataStore.getSamplesAtLocus`'s inner per-record sample lists were still live-mutable after the outer-list fix

`getPositionsAtLocus`/`getSamplesAtLocus` were fixed to return `Collections.unmodifiableList(...)` views of
their outer lists, closing a real bug where an ordinary `List.remove()`/`.clear()` on either list (via the
public getter) desynced `getGenotypeAtLocus`'s `recordIndex` alignment between the two, causing either a raw
`IndexOutOfBoundsException` or a silently wrong genotype-to-position pairing.

That fix is scoped to the *outer* list only. Each element of `getSamplesAtLocus`'s returned
`List<List<VcfSample>>` is still the same live, directly-mutable `List<VcfSample>` also returned by
`getSamplesForId(id)` -- `MemoryMappedVcfLineParser.parseLine()` stores the one object in both places. A
caller who mutates one of those inner lists (e.g. `dataStore.getSamplesAtLocus(...).get(0).remove(0)`) can
similarly desync `getSampleAtLocus`/`getGenotypeAtLocus`'s (and the by-ID overloads') sample-index lookups
*within that one record*: `m_metadata.getSampleIndex(sampleId)` returns a fixed index derived from the
`#CHROM` line's declared sample columns, so removing an element shifts every later sample by one, silently
returning the wrong sample's data (or `null` if the shifted index now falls off the end).

**Original decision: won't fix in this pass; deliberately narrower scope than fully closing this class of
bug.** Wrapping the inner lists too would require either wrapping on every `getSamplesAtLocus`/
`getSamplesForId` call (an allocation per element per call, on a class whose whole purpose is being the fast
in-memory convenience API) or eagerly wrapping at storage time (a deliberate design change to how
`MemoryMappedVcfLineParser` populates these maps, not a narrow fix). The outer-list fix closes the
specific, confirmed bug found this cycle (recordIndex-vs-recordIndex misalignment); this inner-list gap
is the same failure *shape* one level down (sample-index-vs-`#CHROM`-columns misalignment), found
incidentally while reviewing that fix, not independently confirmed to have caused a real failure.

**Now resolved, as a side effect of making `VcfRecord` the `VcfLineParser.parseLine` payload.** That change
took exactly the "eagerly wrapping at storage time" route this entry had ruled out as too broad for a narrow
fix -- but it arrived for an unrelated reason (`VcfRecord` is structurally immutable, so `getSamples()`
returns `Collections.unmodifiableList(...)` on every construction path, including the `newTrusted` fast
path), so it cost nothing extra here. `MemoryMappedVcfLineParser.parseLine` now stores that unmodifiable view
into both `m_idToSamples` and `m_locusToSamplesList`, and those are the only writers to either map, so every
inner list handed out by `getSamplesForId`/`getSamplesAtLocus`/`getAllSamples` is unmodifiable. A caller can
no longer shift a record's own sample indices out of alignment with the `#CHROM` line's declared columns; the
attempt now throws `UnsupportedOperationException` at the caller's own mistake. The contained `VcfSample`
objects themselves remained mutable at the time this entry was written -- that was a separate axis, deferred to
Plan A's later immutable-model tasks. **Now also resolved**: Task 9 froze `VcfSample` itself (no mutators;
`getPropertyKeys()`/`propertyEntrySet()` are unmodifiable views whose entries also reject `setValue`), the
identical freeze Task 8 applied to `VcfPosition`.

## `VcfParser`'s legacy `GLE` FORMAT-value splitter can silently misattribute content from a corrupted GLE value to the next FORMAT key

`GLE_VALUE_PATTERN`/`toSampleValues` (`VcfParser.java`) greedily match as many `genotype:likelihood` pairs as
syntactically fit a GLE value, treating whatever's left as the ordinary colon-delimited remainder of later
FORMAT fields. This match is purely syntactic: it can't distinguish "GLE legitimately ends here" from "GLE
was corrupted and the match just happened to stop at a shorter valid prefix." A corrupted GLE value (e.g. a
comma between two `genotype:likelihood` pairs mistyped as a colon) can still match a shorter prefix, silently
misattributing the leftover fragment to the next FORMAT key with zero warnings -- e.g. FORMAT `GT:GLE:DP`
with sample value `0/1:0:-75.221:-223.42` (a typo'd version of the well-formed
`0/1:0:-75.22,1:-223.42:50`) parses with `GLE` truncated to `0:-75.221` and `DP` wrongly set to `-223.42`, a
fragment of the broken GLE value, not a real depth.

A real fix would need to know how many `genotype:likelihood` pairs GLE's value *should* contain (its
`Number` is `G`, the same ploidy/allele-count-derived cardinality this codebase already computes elsewhere
via `InternalUtils.getExpectedCardinality`/`combinationsWithRepetition`) so a too-short match could be
flagged as suspicious. But `toSampleValues` is a static tokenizer that sees only the FORMAT key list, one
sample's raw text, and the parse's resolved `VcfVersion` (threaded in so the splitter can be skipped
entirely from VCFv4.3 on) -- it has no access to `GT`/ALT-allele-count at that point in parsing.
Threading that context in would mean changing this hot-path method's signature and its call site's data
flow, not a narrow fix.

Checked whether bcftools or vcf_validator's behavior could substitute for designing this from scratch, per
`AGENTS.md`'s decision hierarchy: neither can parse GLE at all, even the well-formed
`VCFv4.2.tex` line 225 example (`GLE=0:-75.22,1:-223.42,0/0:-323.03,1/0:-99.29,1/1:-802.53`) --
bcftools 1.23.1 rejects it (`[E::vcf_parse_format_max3] Incorrect number of FORMAT fields`) and
vcf_validator 0.10.2 rejects it too ("Sample #1 has more fields than specified in the FORMAT column"). Both
treat GLE as an ordinary colon-delimited scalar with no special-case handling, so there's no reference
behavior to consult for what "correct recovery" should look like on the corrupted-input case -- this
library's `GLE_VALUE_PATTERN` support is already more permissive than either tool attempts. `GLE` itself is
VCFv4.1/4.2-only: VCFv4.3, 4.4, and 4.5 each remove it outright, with the identical changelog line "Removed
unused and ill-defined GLE FORMAT tag" in all three -- the spec's own maintainers gave up on tightening its
grammar rather than clarifying it in a later version.

**Decision: won't fix.** Narrow blast radius -- a VCFv4.1/4.2-only key, requiring a specifically-corrupted
value to trigger, and the splitter now runs only when the file declares VCFv4.2 or earlier (from VCFv4.3 a
key named `GLE` is split on colons like any other, so this misattribution cannot occur there at all) --
no reference-tool behavior or later-spec clarification to design a fix against, and a
real fix requires threading ploidy/allele-count context into a currently context-free hot-path tokenizer --
disproportionate design/coupling cost for a legacy field the spec itself calls "ill-defined." This does not
affect `GLE`'s well-formed-value handling (`VcfParser.java`'s `GLE_VALUE_PATTERN` splitter and its
possessive-quantifier StackOverflowError hardening) or the separate `Number=G` cardinality-check exemption
for `GLE`/`CNP` (`InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck`, see
`VCF_SPEC_AMBIGUITIES.md`) -- both remain correct and are unrelated to this gap.

Revisit if a real caller reports hitting this on an actual corrupted GLE value, or if a future VCF spec
version ever re-adds and tightens GLE's grammar.

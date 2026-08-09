# Validation Philosophy

This file states, as a stable design contract, when this library rejects content immediately, when it warns
and recovers, and when it skips validation altogether. `AGENTS.md`'s Parser Validation Policy, Writer
Validation and Round-Trip Policy, and Public API Null-Safety Policy sections are each an application of the
principles below to a specific surface -- read this file first for the "why," then those sections for
"exactly which field, exactly which citation."

## The four principles

1. **Fail fast on direct, programmatic input.** A caller constructing library objects directly -- through
   `VcfContext.strict(fileFormat)` and the factories/Builder it hands out -- gets `VcfFormatException`
   immediately for content that violates a declared, checkable rule. The caller wrote this value themselves;
   there's no reason to defer the diagnosis. This is fully implemented today: every metadata declaration class
   (`IdMetadata`, `IdDescriptionMetadata`, `InfoMetadata`, `FormatMetadata`, `ContigMetadata`), `VcfPosition`,
   and `VcfMetadata`/`Builder` throw under a strict context for every condition their own declaration-level
   checks cover. A few conditions a naive reading of "every declaration-level check" might expect to see
   thrown are structurally impossible via the typed path rather than unimplemented -- see "Where this applies"
   below.
2. **Be lenient -- warn and auto-recover -- for parsed and auto-generated content.** `VcfParser` builds its
   `VcfContext` with `VcfContext.lenient(...)`, routed to an explicit `VcfWarnings` instance it owns, so every
   metadata declaration, position, sample and record it creates warns and preserves or normalizes rather than
   rejecting. The
   same leniency applies symmetrically on write: the default `VcfWriter` path recovers from recoverable input
   (canonicalizes an empty value, drops content it can't represent) rather than throwing, and a file the parser
   accepted must remain writable without loss.
3. **Minimize validation on the hot path.** Parsing and writing a record is the common case and must stay
   fast. Validation that isn't needed to keep record/column structure unambiguous is deferred:
   `VcfPosition.getQuality()`/`getInfo()` parse lazily on first access, not at construction; `VcfSample` keeps
   a lean parallel-list representation and only materializes a `Map` when one is actually needed;
   `VcfWriter`'s default path skips the full structural/semantic revalidation `validateBeforeWrite()` performs.
4. **Once a header is written, further changes to that header's metadata are rejected.** A `VcfWriter` that
   already flushed a header must not silently emit records against metadata that no longer matches what was
   written. This is currently only enforced under `validateBeforeWrite()` (see the interaction note below) --
   the default path does not detect this.

## How principles 1 and 2 are dispatched

One shape implements principles 1 and 2, uniformly:

- **`VcfContext.isLenient()` as the single strict/lenient signal** (`VcfPosition`'s content checks,
  `BaseMetadata.warnOrThrow` and therefore every declaration's `init()`, `VcfMetadata`'s private
  `warnOrThrow`/`warnIfDuplicate` helpers, `VcfMetadata.validateAlt`): a single shared check consults the
  context that created the object -- strict throws, lenient warns to that context's own `VcfWarnings`.
  Inside `VcfPosition` and `InternalUtils.dropEmptyEntries`/`fillEmptyEntriesWithDot` this is still *carried*
  as a `@Nullable VcfWarnings` parameter whose `null` means strict, but that is now an internal calling
  convention between those private helpers, not a caller-visible signal: `VcfPosition` derives it from
  `context.isLenient()` at the top of its constructor.

**The input shape never decides strictness.** A declaration class still has two constructors -- a typed
`(id, description, ...)` one and a `Map`-based one -- but they differ only in input format: the typed one
still performs its own inline checks rather than deferring to the shared `init()` (it works from already-typed
enum parameters, and its checks would be unreachable or differently-worded through `init()`), yet both route
every recoverable condition through `BaseMetadata.warnOrThrow` and therefore through the same context. This
was not always true: before `VcfContext`, the typed constructor threw unconditionally and the `Map`-based one
warned unconditionally, so equivalent invalid content behaved differently purely by which overload the caller
reached for -- a typed overload staying strict under a lenient builder, a map overload staying lenient under a
strict one.

There is no third case for a built `VcfMetadata`. It is immutable: it has no instance `addXxx`/`removeXxx`
methods and every collection getter returns an unmodifiable view, so the only way to produce a changed
`VcfMetadata` is `toBuilder()` -- which carries the original's `VcfContext` over by reference, keeping the
strict/lenient decision and the warnings sink identical across the copy. A declaration built under one context
cannot be moved into a metadata object built under another by any route (see `VcfMetadata.checkSameContext`).

`VcfPosition`'s own structural checks (`checkChromosome`, `checkPosition`, `checkRef`) are not part of either
shape above and take no `warnings` parameter at all -- they always throw, in every constructor. This is
Parser Validation Policy's own structural floor (record/column-boundary safety), not a direct-construction-
vs-parsing distinction; see that policy for why these specific fields stay unconditional.

`AltStructuralVariant`'s constructor is the same kind of always-throwing check (it never takes a `warnings`
parameter at all), but reached through a separate wiring rather than the dispatch above: `VcfMetadata.validateAlt`
(itself an ordinary shape-2 `@Nullable VcfWarnings` check) wraps `new AltStructuralVariant(id)` in a
try/catch and re-dispatches its `VcfFormatException` through `warnOrThrow` -- so the warn-vs-throw decision
is still made by `validateAlt`'s own `VcfContext` parameter, exactly the mechanism above; only the underlying
condition check is an always-throwing helper class rather than an inline branch.

## How the declared version is dispatched

Strict-vs-lenient is not the only axis a check dispatches on. A few VCF rules genuinely *changed meaning*
between 4.x versions, so what a value means depends on the version the file declared -- a separate question
from whether a violation warns or throws, and resolved by a separate mechanism.

**Where the version comes from.** `VcfContext` resolves the declared `##fileformat` exactly once, at its own
construction, through `VcfVersion.resolve` -- or, for `VcfWriter`, through `VcfVersion.resolveWithoutWarning`
plus `VcfContext.lenientWithoutFallbackWarning`, which apply the identical rules but return an unimplemented-
version fallback as a fact instead of emitting it -- and exposes the result as `getVersion()`. Every model
object stores its `VcfContext`, so `VcfPosition`, `VcfSample`, `VcfRecord` and every metadata declaration can
reach the resolved version with no additional plumbing.

**Why that matters at deferred-access time.** Principle 3 defers `getQuality()`/`getInfo()`/`getProperty(...)`
to first access, which can be arbitrarily long after the header was read and after the parser itself has moved
on. Because the version rides on the context the object was built with, rather than on parser-local state, a
lazily-validated value can still answer "which version's rule applies to me" at the moment it is finally read.
This is the single property the version-context work existed to establish.

**One context per operation, so one version per record.** The version has to be unambiguous at the point of
use, which is the second reason (alongside the warnings destination) that the reference-identity checks exist:
`VcfContext.newRecord`, `VcfMetadata.Builder`, `VcfWriter.writeLine`, and
`VcfSample.getProperty(VcfPosition, VcfMetadata, String)` each reject components built under different
contexts, because two contexts mean two possible resolved versions and nothing to choose between them. On the
write path the record's version is the *metadata's* context's version, derived once per `writeLine` and threaded
explicitly downward -- deliberately never read off `VcfWriter`'s own `m_context`, which is writer-local
diagnostics state rebuilt on every `writeHeader()` and never reference-identical to the context that built the
data.

**Version and warnings sink travel as separate arguments, never as one object.**
`InternalUtils.convertProperty(ReservedProperty, ...)`, `checkReservedFormatConstraints` and
`checkReservedInfoConstraints` each take a `VcfVersion` and a `VcfWarnings`, not a `VcfContext`. They are
independent concerns -- on the write path the version comes from the record while the warnings go to the
writer's own sink -- so there is deliberately nothing in scope inside those helpers to read a version off but
the value the caller explicitly supplied.

**The `isAtLeast` discipline.** Every version gate is written `version.isAtLeast(VcfVersion.V4_3)` (or its
negation), never a string comparison against the declared `##fileformat` and never a raw ordinal comparison. A
string comparison misses the equivalent spellings `VcfVersion.canonicalize` exists to fold together (`VCFv4.02`
is `VCFv4.2`), and a bare ordinal comparison silently changes meaning if a version is ever inserted into the
enum. `isAtLeast` also rejects a `null` argument with `VcfFormatException` rather than answering it.

**Gate only a genuine change of meaning.** A version branch is reserved for a rule whose meaning differs
between versions with both readings valid. Today that is exactly two rules: `FORMAT/GP` (phred-scaled under
VCFv4.2 and earlier per VCFv4.2.tex line 227, a 0-1 probability from VCFv4.3 per VCFv4.3.tex line 491) and
`FORMAT/GLE` (a reserved key at VCFv4.1.tex line 208/VCFv4.2.tex line 225, removed by VCFv4.3 per VCFv4.3.tex
line 2168). Where a later version merely *clarifies* something VCFv4.2 left unsaid, the later rule is adopted
at every version instead, with no branch at all -- see `VALIDATION_REFERENCE.md`'s "Rules adopted from a later
VCF version, unconditionally" for that list, and its lenient section for the two gated rules' exact per-version
behavior.

## Where this applies

- **`InfoMetadata`/`FormatMetadata`**: the typed constructor reports (throws under a strict context, warns
  under a lenient one) an ID that doesn't match the required
  tag-name grammar (must start with a letter or underscore, followed only by letters, digits, underscores, or
  periods -- or, for `InfoMetadata` only, the legacy value `1000G`), a malformed `Number`, a `Type=Flag`/
  non-zero-`Number` mismatch (`InfoMetadata` only -- `FormatType` has no `Flag`), and a reserved property's
  `Number`/`Type` mismatch. It cannot report an "invalid `Type` string" or "unquoted `Description`/
  `Source`/`Version`" -- not because these are unimplemented, but because they are structurally impossible via
  this path: the `type` parameter is already a real `InfoType`/`FormatType` enum, never a raw string, and
  `putAndQuoteProperty` always quotes whatever it's given. The Map-based constructor's shared `init()` still
  warns for all of these, including the two that are unreachable from the typed side.
- **`ContigMetadata`**: the typed constructor reports a malformed `URL`, an ID starting with `*`/`=`, and an
  ID containing a character `InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN` flags (VCFv4.2-silent, VCFv4.3.tex's
  own "Changes to VCFv4.3" errata used as clarifying guidance -- see that pattern's own comment and
  `VCF_SPEC_AMBIGUITIES.md`'s "##contig ID character set" entry). A malformed `length` is similarly
  unreachable via this path -- the parameter is `@Nullable Long`, already parsed by the time it arrives, so
  only `null` (legitimately absent per VCFv4.2.tex line 138) or an already-valid value can be passed. `assembly`
  has no format constraint to check in either constructor.
- **`IdMetadata`/`IdDescriptionMetadata`**: the typed constructor reports a null/blank ID, whitespace in an
  ID, and a comma in an ID; `IdDescriptionMetadata`'s own typed constructor additionally cannot produce an
  unquoted `Description` (same `putAndQuoteProperty` reasoning as above), so it skips calling `init()`
  entirely rather than needing an equivalent for a condition it can't reach. It does report an absent
  `Description` with `init()`'s own `warnIfMissing` message, rather than letting `putAndQuoteProperty`'s
  generic null rejection fire -- that would hard-throw even under a lenient context.
- **`VcfPosition`**: reached through five `VcfContext.newPosition*` factories -- a full public one, a minimal
  public 5-argument one (`chromosome, position, refBases, quality, lineNumber` only -- no ID/ALT/FILTER/INFO/FORMAT
  checks at all, since those fields are absent), and three package-private fast paths, `newPositionTakingOwnership`,
  `newPositionWithTrustedFormat`, and `newPositionCopyingTrustedFields`. The content checks that vary with
  strictness (an empty ID/ALT/FILTER entry, an empty INFO key, an empty FORMAT sub-field name, FILTER combining
  `PASS` with another code, a duplicate value within `ID` or `FILTER`, `=` inside an INFO value) all go through
  the `isLenient()`-as-signal mechanism above, and all three package-private factories are principle-3 cases
  layered on top of that dispatch, not additional branches of it. `VcfContext.newPositionTakingOwnership()` skips
  only the defensive copies the full factory makes -- every validation check still runs, `checkFormat` included --
  for a caller-owned collection the parser built itself moments earlier and never shares. `VcfContext.
  newPositionWithTrustedFormat()` skips that same copying *and* skips `checkFormat` (and therefore both its
  throw-vs-warn dispatch and any warning it would emit) entirely, for `VcfParser`'s own per-record
  FORMAT-validation cache -- real VCFs often repeat the same FORMAT column text across large blocks of
  consecutive records, and re-validating an already-validated one is redundant work. Unlike `newTrustedSample()`
  (which keeps its own null/size checks and skips only one narrower check), `newPositionWithTrustedFormat` is a
  full skip of `checkFormat`, including its null-element check; all three package-private factories are
  unreachable from outside the package. `VcfContext.newPositionCopyingTrustedFields()` is the inverse trade-off
  from the other two: it *always* copies (used exclusively by the reusable `VcfPosition.Builder#build()`, which
  must not alias one built position's collections to another's or to the builder's own retained fields across
  repeated `build()` calls) but skips `checkIds`/`checkAltBases`/`checkFilters`/`checkInfoEntries`/`checkFormat`
  entirely, trusting `Builder#build()` itself to have already re-run whichever of those five checks a setter
  actually changed since the builder was seeded -- an unchanged field, seeded from an already-valid (or
  already-warned-about) source, is correctly never re-checked, so copying alone never re-emits a diagnostic the
  source already emitted once. A deliberate, chosen side effect of the FORMAT cache: a FORMAT that would warn
  (e.g. an empty sub-field name) now warns once per contiguous block of
  identical FORMAT text, not once per record, since a cache hit skips re-emitting that warning too.
- **`VcfMetadata`/`Builder`**: covered in "How principles 1 and 2 are dispatched" above.
- **`VcfSample`**: not part of that dispatch at all -- `VcfContext.newSample` and `newTrustedSample` already
  only throw (a null or mismatched-size key/value list, or -- outside `newTrustedSample` -- a structural
  delimiter in a key/value), never warn, so there is no throw-vs-warn choice to make at construction for this
  class. Every warn site it does have is post-construction -- `validate()`'s empty-value normalization,
  `properties()`'s duplicate-FORMAT-key handling, and the deferred typed-read diagnostics in
  `getProperty(VcfPosition, VcfMetadata, String)`, `getGenotype(VcfPosition)` and
  `containsProperty(ReservedFormatProperty)`. (No count is asserted, deliberately: the list has grown since it
  was first written down as two.) `newTrustedSample` is a principle-3 case, not principle 1/2: it skips
  the structural-delimiter check entirely (rather than warning in place of throwing) for `VcfParser`'s own
  already-guaranteed-clean input, and is package-private so no external caller can reach it -- see its own
  Javadoc for the measured cost this avoids.
- **`PedigreeMetadata`**: also not part of it -- it has no `init()` and no construction-time warn
  site of its own to route. Its only interesting behavior, duplicate-ID handling, lives in
  `Builder.addPedigree`, which is covered by the `VcfMetadata`/`Builder` entry above.
- **The `BaseMetadata` hierarchy's immutability**: not part of the strict/lenient split either. Every
  declaration's properties are fixed after construction -- `getPropertiesRaw()`/`getPropertyKeys()` return
  unmodifiable views, whose mutation attempts throw `UnsupportedOperationException`, not `VcfFormatException`.
  That isn't a content-validity rejection subject to the split; it's "this operation is never supported,"
  the same category as any other JDK unmodifiable-collection contract. `PedigreeMetadata` reached this state
  first (so `Builder.addPedigree`'s duplicate-ID cache could trust that a stored entry's `ID` can never change
  out from under it) via its own local overrides of `putPropertyRaw`/`putAndQuoteProperty`, which throw
  `UnsupportedOperationException` even in-package. Those overrides were kept, not subsumed, once the base
  class itself became immutable: deleting them reopens the exact stale-dedup-cache bug they exist to prevent,
  since the six keyed types' own `putPropertyRaw`/`putAndQuoteProperty` remain callable in-package for their
  typed constructors' own populate step, and `PedigreeMetadata`'s dedup `Set` (unlike the six keyed types' own
  `HashMap`, which is keyed by ID and so is self-correcting) has no recompute path if a stored entry's `ID`
  changes out from under it. See `WONT_FIX.md`'s "Scope of 'closed': the public API, not this package's own
  code" paragraph and `PedigreeMetadata`'s own class Javadoc.
- **`VcfFloat`**: a different case again. A genuinely malformed value (neither the special-value pattern nor a
  valid decimal) always throws from `parse(String)` -- in both the public single-argument overload and the
  `public` (but `@RestrictedApi`/`@hidden`, not part of this library's supported public API --
  see `AGENTS.md`'s Null-Safety Policy exemption list) `VcfWarnings`-taking one -- there is no lenient variant
  of that rejection. The warn-vs-throw
  axis it does have sits one level down, in the shared decimal-parsing grammar check (does the string match the
  stricter `FLOAT_PATTERN`, e.g. `"5."` does not): that is a data-value tolerance judgment, not a
  declaration-level correctness question, and it warns unconditionally for every caller (`VcfFloat.parse()`
  directly, or `VcfPosition.getQuality()`) rather than participating in the strict/lenient split above.

## How the principles interact

- Principle 3 gated principle 4's scope, until the thing being guarded stopped being possible. Detecting a
  post-header metadata mutation required rebuilding the entire header on every `writeLine()` call -- a real
  per-record cost the default path could not absorb -- so the check lived only under `validateBeforeWrite()`.
  `VcfMetadata` and its declarations are immutable now, so the same object cannot produce different header
  text than the one already emitted, and the check has been removed from that mode too. Object identity
  (`metadata == m_headerMetadata`, unconditional in both modes) is what remains.
- Basic null-safety is not the same axis as principles 1/2, and is never subject to principle 3's exemption. A
  `null` reaching string-building code doesn't produce a validation question with a "warn and recover" answer
  -- it silently fabricates the literal text `"null"` in output that was never there. This is why several
  checks that look like hot-path validation are unconditional even on `VcfWriter`'s default path:
  `addListOrElse`, `addInfoOrDot` and `addFormatConditionally` all throw on a null entry regardless of
  `validateBeforeWrite()`, because `VcfContext`'s package-private trusted position factories skip
  `checkIds`/`checkAltBases`/`checkFilters`/`checkInfoEntries`/`checkFormat` outright and are real production
  fast paths (`VcfParser`'s per-record splitting and FORMAT cache, `VcfPosition.Builder.build()`). Principle 3
  exempts the default path from re-doing *VCF-semantic* validation the parser or an earlier check already
  performed, not from basic Java null-safety.
  <br>The header-building path is the one place this reasoning stops applying, and it is worth being precise
  about why: `buildHeaderLines()` and its helpers no longer null-check declarations or properties, because
  there is no analogous production route that could put a null there. `VcfMetadata.Builder` rejects a null
  declaration and a null raw-property name/value at insertion, and nothing can mutate one in afterward. That
  is an absence of any reachable `null`, not an exemption from checking for one.
  This generalizes beyond `VcfWriter` to the whole public API surface: JSpecify's `@Nullable` annotations are
  documentation only in this codebase (no compile-time or runtime enforcement), so a caller can always pass
  `null` regardless of a parameter's declared type -- a public constructor, static factory, or method must
  defend against that itself rather than relying on the annotation. `AGENTS.md`'s Public API Null-Safety
  Policy states the concrete per-shape rules this produces.
- A class or method's `@RestrictedApi`/`@hidden` disclaimer (e.g. `InternalUtils`, `VcfMetadata.validateAlt`,
  `VcfFloat.parse(String, VcfWarnings)`) is the identical kind of documentation-only signal: no ErrorProne
  plugin is configured in this codebase, so nothing enforces it at compile time or runtime either. It is a
  purely documentation-level exemption from the null-safety policy above, not a technical guarantee -- a
  caller who calls one of these methods directly despite the disclaimer and hits a raw `NullPointerException`
  is getting an acceptable outcome for ignoring an explicit "not supported" contract. See `AGENTS.md`'s
  Null-Safety Policy for the current list of exempt members.
- The five package-private fast paths (`VcfContext.newPositionTakingOwnership`, `newPositionWithTrustedFormat`,
  `newPositionCopyingTrustedFields`, `newTrustedSample`, `newTrustedRecord`) are a different case from the
  disclaimer above, on two counts. First, they are *package-private*, so visibility genuinely enforces "don't
  call this from outside its one trusted call site" rather than merely advertising it -- that is what
  collapsing the model package into `org.pharmgkb.parser.vcf` bought. Second, what each skips is a principle-3
  hot-path validation skip, not a null-safety exemption, and the five skip genuinely different things:
  `newPositionTakingOwnership` skips only the defensive copies the full `newPosition` factory makes, and still
  runs every validation check, `checkFormat` included -- this is exactly why it is not named `*Trusted*` like
  the other four: it trusts the caller's exclusive ownership of the collections, not the collections' content,
  which is a strictly weaker claim than the other four make;
  `newPositionWithTrustedFormat` skips that same copying *and* skips `checkFormat` entirely, including its
  null-element check;
  `newPositionCopyingTrustedFields` inverts that trade-off -- it never skips the copy (used only by the reusable
  `VcfPosition.Builder#build()`, so every `build()` call must produce collections independent of the builder's
  own retained fields and of any other position built from it) but skips `checkIds`/`checkAltBases`/
  `checkFilters`/`checkInfoEntries`/`checkFormat` entirely, since `Builder#build()` itself already re-ran
  exactly the checks a setter actually changed before reaching this factory;
  `newTrustedSample` skips only the structural-delimiter scan and keeps its null/mismatched-length checks;
  `newTrustedRecord` skips the defensive copy of the sample list and the context-identity verification, but
  still rejects a null position and a null sample list -- `MemoryMappedVcfLineParser.parseLine` relies on that
  last guarantee and deliberately does not re-check either. They look like one pattern; check which checks a
  given trusted-bypass method actually skips before assuming one is a precedent for another.
  <br>**"Five" counts trusted *model* factories, and is not a count of `VcfContext`'s package-private members.**
  `VcfContext.lenientWithoutFallbackWarning` is a sixth package-private factory of a different kind: it builds a
  `VcfContext` rather than a model object, and what it bypasses is the version-fallback *warning*
  `VcfVersion.resolve` would emit (see "How the declared version is dispatched" above), not a content check. It
  is not a hot-path skip and not a null-safety exemption, so it is deliberately outside the five. Its one
  caller, `VcfWriter.writeHeader`, has already run `VcfVersion.resolveWithoutWarning` on the same
  `declaredFileFormat`, so the null/malformed rejection `VcfVersion.resolve` performs for the public `lenient`/
  `strict` factories has still happened before this one is reached.
- The metadata layer has two package-private bypasses of the same family, added when it was frozen, and they
  differ from the five above in one important way: **neither has a production call site.**
  `BaseMetadata.trustedMutableProperties()` hands back the live properties map (skipping every check
  `putPropertyRaw`/`putAndQuoteProperty` run), and `VcfMetadata`'s own constructor is package-private rather
  than private (it copies its collections but never re-checks their contents -- a null declaration under a map
  key, a null raw-property key, a duplicate PEDIGREE ID all pass through). `VcfMetadata.Builder#build()` is the
  constructor's one production caller; `trustedMutableProperties()` has none at all. They exist so this
  package's own tests can still synthesize the malformed input `VcfWriter`'s defensive checks are written
  against, now that no public API route can: every metadata collection getter is an unmodifiable view and there
  are no instance mutators left. A test using either is asserting on a writer-side guard, not on supported
  behavior.

## Known, permanent deviations

- A `Builder`'s own required-configuration arguments (`Path`, `Reader`, `Writer`, `VcfLineParser`) use
  `Preconditions.checkNotNull` (a raw `NullPointerException`), not `VcfFormatException`, and are not part of
  principles 1/2 at all: these are never derived from parsed VCF content -- a caller who passes a null `Path`
  to `fromFile()` has a programming error, not a data-quality problem. See `WONT_FIX.md`'s `VcfParser.Builder`
  entry, and `AGENTS.md`'s Public API Null-Safety Policy for the full boundary between this case and VCF
  content.
- `VcfWriter.addInfoOrDot()` has two writer-side *content* checks (as distinct from the null-safety checks
  carved out under "How the principles interact" above, which are unconditional for a different reason) that
  run on the default path too, not only under `validateBeforeWrite()`: its Flag-ambiguity canonicalization
  (a single empty-string value looking identical to a `Type=Flag`, which would otherwise silently corrupt a
  field's semantic type even on the default path) and its per-entry empty-value substitution for a
  multi-value INFO list (a position-dependent list whose element count can matter for a `Number=`-declared
  arity, so an empty entry is substituted with `.` regardless of write mode, the same reasoning
  `checkAltBases`/sample-value handling already apply elsewhere). Both are principle-3 exceptions, not
  principle-1/2 ones -- see `EMPTY_FIELD_HANDLING.md`.

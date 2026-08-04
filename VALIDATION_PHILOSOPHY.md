# Validation Philosophy

This file states, as a stable design contract, when this library rejects content immediately, when it warns
and recovers, and when it skips validation altogether. `AGENTS.md`'s Parser Validation Policy, Writer
Validation and Round-Trip Policy, and Public API Null-Safety Policy sections are each an application of the
principles below to a specific surface -- read this file first for the "why," then those sections for
"exactly which field, exactly which citation."

## The four principles

1. **Fail fast on direct, programmatic input.** A caller constructing library objects directly -- a typed
   metadata constructor (`new InfoMetadata(id, description, type, number, ...)`), a `VcfPosition`/`VcfSample`
   constructor, or `VcfMetadata.Builder` used without `.lenient(...)` -- gets `VcfFormatException` immediately
   for content that violates a declared, checkable rule. The caller wrote this value themselves; there's no
   reason to defer the diagnosis. This is fully implemented today: every metadata declaration class with both
   a typed and a Map-based constructor (`IdMetadata`, `IdDescriptionMetadata`, `InfoMetadata`, `FormatMetadata`,
   `ContigMetadata`), `VcfPosition`, and `VcfMetadata.Builder` throw on direct construction for every condition
   their own declaration-level checks cover. A few conditions a naive reading of "every declaration-level
   check" might expect to see thrown are structurally impossible via the typed path rather than unimplemented
   -- see "Where this applies" below.
2. **Be lenient -- warn and auto-recover -- for parsed and auto-generated content.** `VcfParser` never uses a
   typed constructor or an unadorned `VcfMetadata.Builder`: every metadata declaration it builds goes through
   the Map-based constructor, and its own internal `Builder` always calls `.lenient(...)` -- both routed to an
   explicit `VcfWarnings` instance it owns -- warning and preserving or normalizing rather than rejecting. The
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

Two distinct shapes implement principles 1 and 2 -- not one shared mechanism, and not per-class duplication of
the same condition logic:

- **Constructor short-circuit** (`IdMetadata`, `IdDescriptionMetadata`, `InfoMetadata`, `FormatMetadata`,
  `ContigMetadata`): the typed constructor performs its own inline checks and throws *before ever calling* the
  shared, warn-only `init()`; the Map-based constructor calls only `init()`. There is no boolean or shared
  parameter threading strict-vs-lenient through one method -- the two constructors run genuinely different
  code paths.
- **`@Nullable VcfWarnings` as an explicit strict/lenient signal** (`VcfPosition`'s content checks,
  `InternalUtils.dropEmptyEntries`/`fillEmptyEntriesWithDot`, `VcfMetadata`'s private `warnOrThrow`/
  `warnIfDuplicate` helpers, `VcfMetadata.validateAlt`): a single shared check takes a `@Nullable VcfWarnings
  warnings` parameter and branches internally -- `warnings == null` throws, a non-null instance warns to it.
  `VcfPosition`'s lenient 11-argument constructor and `VcfMetadata.Builder.lenient(VcfWarnings)` are the two
  public entry points that supply a non-null instance; their strict counterparts (the 10-argument and minimal
  5-argument `VcfPosition` constructors, and `Builder`'s own default) pass `null` internally.

A built `VcfMetadata` object's own post-build instance methods (`addInfo`, `addAlt`, `removeSample`, etc.) are
not a separate, third case: `VcfMetadata` stores whichever `VcfWarnings` instance (or `null`) the `Builder`
that built it was using, and its own `addXxx` methods reuse that same field -- so a `VcfMetadata` built via a
bare `new VcfMetadata.Builder()...build()` (no `.lenient(...)`) stays strict for its own later `addXxx` calls
too, and one built via `.lenient(warnings)` stays lenient, routed to that same instance. This is worth stating
explicitly since it is not obvious from reading `Builder` alone, and is easy to mistake for "`addXxx` is
always lenient, regardless of how the object was built."

`VcfPosition`'s own structural checks (`checkChromosome`, `checkPosition`, `checkRef`) are not part of either
shape above and take no `warnings` parameter at all -- they always throw, in every constructor. This is
Parser Validation Policy's own structural floor (record/column-boundary safety), not a direct-construction-
vs-parsing distinction; see that policy for why these specific fields stay unconditional.

## Where this applies

- **`InfoMetadata`/`FormatMetadata`**: the typed constructor throws for a malformed `Number`, a `Type=Flag`/
  non-zero-`Number` mismatch (`InfoMetadata` only -- `FormatType` has no `Flag`), and a reserved property's
  `Number`/`Type` mismatch. It cannot throw for an "invalid `Type` string" or "unquoted `Description`/
  `Source`/`Version`" -- not because these are unimplemented, but because they are structurally impossible via
  this path: the `type` parameter is already a real `InfoType`/`FormatType` enum, never a raw string, and
  `putAndQuoteProperty` always quotes whatever it's given. The Map-based constructor's shared `init()` still
  warns for all of these, including the two that are unreachable from the typed side.
- **`ContigMetadata`**: the typed constructor throws for a malformed `URL`. A malformed `length` is similarly
  unreachable via this path -- the parameter is `@Nullable Long`, already parsed by the time it arrives, so
  only `null` (legitimately absent per VCFv4.2.tex line 138) or an already-valid value can be passed. `assembly`
  has no format constraint to check in either constructor.
- **`IdMetadata`/`IdDescriptionMetadata`**: the typed constructor throws for a null/blank ID, whitespace in an
  ID, and a comma in an ID; `IdDescriptionMetadata`'s own typed constructor additionally cannot produce an
  unquoted `Description` (same `putAndQuoteProperty` reasoning as above), so it skips calling `init()`
  entirely rather than needing a throw-equivalent for a condition it can't reach.
- **`VcfPosition`**: has three public constructors -- a fully strict 10-argument one, a fully strict minimal
  5-argument one (`chromosome, position, refBases, quality, lineNumber` only -- no ID/ALT/FILTER/INFO/FORMAT
  checks at all, since those fields are absent), and an 11-argument lenient overload taking an explicit
  `VcfWarnings`. The content checks that differ between the two full constructors (an empty ID/ALT/FILTER
  entry, an empty FORMAT sub-field name, FILTER combining `PASS` with another code, `=` inside an INFO value)
  all go through the `@Nullable VcfWarnings`-as-signal mechanism above.
- **`VcfMetadata`/`Builder`**: covered in "How principles 1 and 2 are dispatched" above.
- **`VcfSample`**: not part of either dispatch shape -- both of its constructors already only throw (a null or
  mismatched-size key/value list, or a structural delimiter in a key/value), never warn, so there is no
  throw-vs-warn choice to make at construction for this class. Its two warn sites (`validate()`'s empty-value
  normalization, `properties()`'s duplicate-FORMAT-key handling) are both post-construction.
- **`PedigreeMetadata`**: also not part of either shape -- it has no `init()` and no construction-time warn
  site of its own to route. Its only interesting behavior, duplicate-ID handling, lives in
  `VcfMetadata.addPedigree`/`Builder.addPedigree`, which are covered by the `VcfMetadata`/`Builder` entry above.
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

- Principle 3 gates principle 4's scope. Detecting a post-header metadata mutation requires rebuilding the
  entire header on every `writeLine()` call -- a real per-record cost the default path can't absorb -- so the
  check exists only under `validateBeforeWrite()` today (`VcfWriter.m_headerText`, compared each `writeLine()`
  call). This is a direct case of principle 3 constraining principle 4's scope, not a contradiction between
  them.
- Basic null-safety is not the same axis as principles 1/2, and is never subject to principle 3's exemption. A
  `null` reaching string-building code doesn't produce a validation question with a "warn and recover" answer
  -- it silently fabricates the literal text `"null"` in output that was never there. This is why several
  checks that look like hot-path validation are unconditional even on `VcfWriter`'s default path
  (`addListOrElse`, `addInfoOrDot`, `addFormatConditionally`, and the raw-property-line writer all throw on a
  null entry regardless of `validateBeforeWrite()`): principle 3 exempts the default path from re-doing
  *VCF-semantic* validation the parser or an earlier check already performed, not from basic Java null-safety.

## Known, permanent deviations

- A `Builder`'s own required-configuration arguments (`Path`, `Reader`, `Writer`, `VcfLineParser`) use
  `Preconditions.checkNotNull` (a raw `NullPointerException`), not `VcfFormatException`, and are not part of
  principles 1/2 at all: these are never derived from parsed VCF content -- a caller who passes a null `Path`
  to `fromFile()` has a programming error, not a data-quality problem. See `WONT_FIX.md`'s `VcfParser.Builder`
  entry, and `AGENTS.md`'s Public API Null-Safety Policy for the full boundary between this case and VCF
  content.
- `VcfWriter.addInfoOrDot()`'s Flag-ambiguity canonicalization runs on the default path too, not only under
  `validateBeforeWrite()` -- the one writer-side *content* check (as distinct from the null-safety checks
  carved out under "How the principles interact" above, which are unconditional for a different reason) that
  isn't `validateBeforeWrite()`-gated, because the ambiguity (an empty value looking identical to a
  `Type=Flag`) would otherwise silently corrupt a field's semantic type even on the default path. This is a
  principle-3 exception, not a principle-1/2 one -- see `EMPTY_FIELD_HANDLING.md`.

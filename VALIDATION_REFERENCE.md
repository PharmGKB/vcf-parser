# Validation Reference

This is the itemized, per-case reference for exactly which VCF content this library rejects immediately
(`VcfFormatException`) versus which it warns about and recovers from. It's the detailed complement to
[`VALIDATION_PHILOSOPHY.md`](VALIDATION_PHILOSOPHY.md), which states the architectural "why" (which of the
four principles applies, how the `VcfContext`-driven strict/lenient dispatch reaches a class) but deliberately doesn't itemize
every individual rule. See `README.md`'s "Validation" section for a short summary and
where this file fits into the rest of this repo's docs.

The parser draws a deliberate line between the **structure of a VCF record**, which it validates strictly, and
**non-structural content** (including INFO, FILTER, FORMAT, sample values, and metadata declarations), which it
may validate lazily.

## Strict — throws `VcfFormatException`

### File and record structure

The parser rejects a file whose structure or mandatory record fields are invalid:

- A missing, duplicate, or non-first `##fileformat`; a file format outside VCF 4.x; any line other than a `##`
  metadata line before the `#CHROM` header; or a `#`-prefixed line after it (VCF has no comment syntax).
- A structured metadata declaration (`##INFO`, `##FORMAT`, `##contig`, `##FILTER`, `##ALT`, `##SAMPLE`,
  `##PEDIGREE`) whose value is not wrapped in angle brackets (`<...>`).
- A `##` metadata line with no `=` at all (e.g. a bare `##sometext` or `##INFO` with no `=`) -- distinct from the
  missing-angle-brackets case above, which requires a well-formed `key=value` structured declaration whose value
  isn't wrapped in `<...>`.
- A missing `#CHROM` header, wrong fixed column names, a missing `FORMAT` column when samples are present, a
  `FORMAT` column with no sample columns following it, an empty sample name, or a duplicate sample name.
- A data line with the wrong number of tab-separated columns.
- An empty mandatory fixed field (the missing value must be `.`), an empty `CHROM`, or a negative `POS`.
- Invalid `REF`/`ALT` bases; whitespace where the spec forbids it (`CHROM`, `ID`, `FILTER`, `INFO`); a `FILTER`
  of `0`; a `FILTER` missing value (`.`) combined with another filter code; an `INFO` missing value (`.`)
  combined with a real property; or a `FORMAT` in which `GT` is present but not the first sub-field or a
  (non-empty) key is duplicated.
- `CHROM` additionally rejects `\`, `,`, `"`, `` ` ``, `'`, `(`, `)`, `{`, `}`, `[`, `]`, or `<`/`>` (unless the
  entire value is a well-formed, fully angle-bracket-wrapped symbolic contig reference like `<1>`) anywhere in
  the value, and a leading `*` or `=` -- matching the SAM reference-sequence-name character set VCFv4.3 aligned
  CHROM to. A colon is explicitly allowed.
- A sample with more sub-fields than its `FORMAT` declares (trailing sub-fields may be dropped, but not added).
- A malformed or out-of-range `GT` allele index looked up through `VcfSample.getGenotype(VcfPosition)` (or
  `MemoryMappedVcfDataStore`'s `getGenotypeForId`/`getGenotypeAtLocus`, which delegate to it), or a failed
  conversion when a typed value is requested.
- A key or value set through `BaseMetadata`'s, `VcfSample`'s, or `VcfMetadata`'s mutators (e.g. a `Description`, a
  sample's `GT` value, or an `##assembly` line) containing a line terminator.
- A `VcfSample` key or value set through its constructors or `Builder#property` (validated at `build()` time, not
  at `property()`'s own call site) containing a colon or tab, which would otherwise add a spurious FORMAT
  sub-field or sample column when written back out. The one exception is a value for
  the key `GLE` (VCFv4.1/4.2's genotype-likelihoods-of-heterogeneous-ploidy key, removed from the spec in VCFv4.3+):
  its own spec example (e.g. `0:-75.22,1:-223.42,...`) is one opaque String that uses colons internally as part of
  its own encoding, not a delimiter between independent sub-fields; a colon in the key `GLE` itself, or a tab in its
  value, is still rejected.
- `VcfWriter.writeLine` is given a number of samples that disagrees with the header's declared sample count, any
  FORMAT/sample data at all when the header declares no samples, or an empty `REF` (which has no missing-value
  sentinel in the spec, unlike `ALT`/`ID`/`FILTER`): in every case the output would not be valid, re-parseable VCF.
### Direct/programmatic construction

- A metadata declaration built under a strict `VcfContext` (e.g.
  `new VcfMetadata.Builder(VcfContext.strict("VCFv4.2")).addInfo(id, description, type, number, source,
  version)`) throws, *at construction time*, for every condition that class's own declaration-level checks
  would otherwise flag -- a
  missing/empty/whitespace-only `ID` (VCFv4.3.tex line 121), an `##INFO`/`##FORMAT` `ID` that doesn't match
  the required tag-name grammar (VCFv4.3.tex line 368/429: must start with a letter or underscore, followed
  only by letters, digits, underscores, or periods -- `##INFO` additionally allows the legacy value
  `1000G`), an invalid `Number`, a `Type=Flag`/non-zero-`Number` mismatch, a reserved property's Number/Type
  mismatch, a malformed `##contig` URL, or a `##contig` ID starting with `*`/`=` or containing an invalid
  character (see `VCF_SPEC_AMBIGUITIES.md`'s "`##contig` ID character set" entry). This is a
  property of the context, not of the moment of construction: calling `validate()` later on that same object
  re-runs the same checks under that same context, so a declaration built under a *lenient* context warns
  there and one built under a strict context throws there. (Since the metadata layer was frozen this is a
  no-op re-check for any declaration reached through the public API -- nothing can change one after
  construction. It stays because `VcfWriter.Builder.validateBeforeWrite()` calls it on every declaration it is
  handed, and because the package-private `BaseMetadata.trustedMutableProperties()` bypass can still produce a
  state no public route can.)
  **`VcfPosition.validate()` and `VcfSample.validate()` do not work this way for their recoverable conditions**
  -- those always warn, whatever context created them, because they pass their warnings sink to their check
  helpers unconditionally rather than deriving strictness from the context the way the constructor path does.
  Each also has structural checks that are a separate case and still throw unconditionally in both contexts:
  `VcfSample.validate()` throws via `checkNoStructuralDelimiter` (`VcfSample.java`) for a null property
  key, a line terminator, or a `:`/tab in a key or (non-`GLE`) value; `VcfPosition.validate()` throws via
  `checkChromosome`/`checkPosition`/`checkRef` (none of which take a warnings sink at all) and via
  `checkFormat`'s null-sub-field-name, duplicate-key, and GT-not-first checks, none of which consult the
  warnings sink either. Neither class can be mutated after construction any more: Task 8 froze `VcfPosition`
  (no setters, every getter an unmodifiable view) and Task 9 did the identical freeze for `VcfSample` (no
  mutators; `getPropertyKeys()`/`propertyEntrySet()` are unmodifiable views whose entries also reject
  `setValue`). A sample or position obtained through the public API -- whether via a constructor or
  `Builder#build()` -- is therefore always already in the state each class's own `validate()` structural checks
  would produce, so calling `checkNoStructuralDelimiter`/`checkChromosome`/etc. from `validate()` is a no-op in
  practice there. `VcfSample.validate()`'s `checkNoStructuralDelimiter` throw remains genuinely reachable only
  for a sample produced via the package-private, validation-skipping `VcfContext.newTrustedSample`
  (`VcfParser`'s own fast path); its empty-value-to-`.` canonicalization is a separate, still-ordinarily-
  reachable case, since a legitimately-constructed sample can hold an empty (not structurally invalid) value.
  Both `validate()` methods remain public and behave exactly as described above if a caller still invokes them
  (e.g. `VcfWriter`'s `validateBeforeWrite()` does, defensively). This strict-throws-on-mutation behavior only
  applies to direct/programmatic construction (and, for a metadata declaration, later mutation) under a strict context;
  `VcfParser`'s own internal use always builds a lenient context, so a file it parses is never rejected outright
  for the same content. **Which overload shape you use does not affect this**: the typed `addInfo(id, description, ...)`
  and the raw-map `addInfo(Map)` shapes behave identically for equivalent invalid content, because the
  strict-vs-lenient decision comes from the `VcfContext` alone. A caller that wants warn-and-recover behavior
  for direct construction builds its objects from `VcfContext.lenient(fileFormat, warnings)` instead, which
  also supplies the [`VcfWarnings`](README.md#vcfwarnings-explicit-warning-routing) instance the warnings are
  routed to.
- `VcfContext.newPosition(...)` under a strict context throws, at construction time, for FILTER combining `PASS`
  with another code, a duplicate value within `ID` or `FILTER`, an INFO value containing `=`, an empty INFO
  key, an empty FORMAT sub-field name, a FORMAT sub-field name that doesn't match the required tag-name
  grammar (the same grammar an `##INFO`/`##FORMAT` declaration `ID` enforces above -- see
  `VCF_SPEC_AMBIGUITIES.md`'s "Data-line FORMAT key fails tag-name grammar" entry), or an empty
  ID/ALT/FILTER entry -- the same conditions the lenient
  section below describes as warned-and-recovered when parsed. The identical factory called on a lenient
  context warns and recovers instead; that is what `VcfParser` itself uses.
  `VcfParser` caches FORMAT-column validation across consecutive records sharing the exact same FORMAT text
  (real VCFs often repeat one FORMAT string across large blocks of records) -- a FORMAT that would warn (e.g.
  an empty sub-field name) warns once per contiguous block of identical FORMAT text, not once per record.
- `VcfMetadata.Builder` constructed with a strict `VcfContext` throws for a missing/duplicate declaration ID,
  an invalid `##ALT` structural code, an empty `assembly`/`pedigreeDB` value, or an
  `assembly`/`pedigreeDB`/other generic property value shaped like an empty or no-`=` bracketed attribute list
  (e.g. `<>` or `<http://foo.com>`). Constructed with a lenient one, each of those warns and keeps the first
  declaration instead (matching bcftools; see
  [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md)), routed to that context's
  `VcfWarnings`. `VcfParser`'s own internal use always builds a lenient context, so parsing a file's behavior
  is unchanged by any of this.
- `VcfMetadata.Builder` throws `VcfFormatException` for a declaration built under a *different* `VcfContext`
  instance (compared by reference, not value) than the builder's own. `VcfMetadata` itself has no instance
  mutators to add this check to -- it is immutable, and `getInfo()`/its siblings return memoized unmodifiable
  views over declarations that are themselves immutable. The check still matters because a declaration is a
  standalone object before it is ever added to a builder: without it, a caller could construct one under one
  `VcfContext` and add it to a `VcfMetadata.Builder` built under a different one, silently routing that
  declaration's own future validation (e.g. a re-run of `validate()`) to the wrong `VcfWarnings` sink than the
  metadata object it now lives in uses. See `VALIDATION_PHILOSOPHY.md:60` and
  `DUPLICATE_METADATA_ID_HANDLING.md:34`.
- `VcfContext.newRecord` throws `VcfFormatException` if its position or any of its samples was created under a
  different `VcfContext` instance, for the same reason -- a record assembled from two contexts has no
  unambiguous warnings destination.
- `VcfWriter.Builder.targetFileFormat(String)` throws `VcfFormatException` from `writeHeader` if it names a
  different VCF version than the metadata declares, before emitting any output: this library implements no
  cross-version conversion, so emitting that declaration would advertise a version the data does not conform
  to (AGENTS.md's Writer Validation and Round-Trip Policy). The comparison is canonicalized via
  `VcfVersion.canonicalize`, so `VCFv4.02` matches `VCFv4.2`; the requested spelling is still what gets
  emitted.

These checks run once, when a `VcfPosition` is constructed (including by the parser) or rebuilt via
`toBuilder()`/`Builder#build()` -- `VcfPosition` has no setters and every accessor returns an unmodifiable view,
so there is no way to mutate a position in place and no reason to re-check it afterward. `Builder#build()`
re-runs the same checks for any field a setter marked
dirty since the builder was seeded; an unchanged field is left exactly as seeded, already known valid (see
`VcfPosition.Builder`'s own Javadoc). A lone `.` `FILTER` value is normalized to empty every time a position is
constructed or rebuilt, matching construction. `PASS` is never normalized away -- it is itself the sole
representation of a passing FILTER (see `VcfPosition.getFilterStatus()`).

One exception: `Builder.rsidsOnly()` is a true pre-filter, not a post-parse one -- a data line whose ID doesn't
match an RSID is treated as though it were never in the file at all, the same as if it had been removed by an
external tool (e.g. `bcftools view`) beforehand (see its Javadoc; and `VCF_SPEC_AMBIGUITIES.md`'s `rsidsOnly()`
discard-semantics entry for the bcftools-consulted "as if never in the file" decision). `VcfPosition` is never
constructed for a discarded line, so none of the structural-floor checks above apply to it -- not just a negative
`POS`, `CHROM`'s character-set rules, invalid `REF`/`ALT`, or the `FILTER`/`FORMAT` checks, but not even confirming
`POS` parses as a number at all, or CHROM/POS ordering. Only the column-count check and the check that no
fixed field is empty still apply to every line regardless, since both are needed just to read the ID field in
the first place. A `VcfLineParser`-based filter can't replicate this: it only sees a record after
`VcfPosition` has already been fully constructed and validated, so a structurally invalid record `rsidsOnly()`
would silently discard instead aborts parsing before a line parser ever gets a chance to reject it.

### Writer output

Building a new position via `position.toBuilder().format(...).build()` with a FORMAT list that no longer matches
an already-built `VcfSample`'s own keys does *not* automatically update that sample's properties -- the two must
be kept in agreement by the caller. If they diverge, the default writer path
warns and reconciles by key name: a FORMAT key with no corresponding sample value is substituted with `.`, and a
sample property not declared in FORMAT is dropped. `VcfWriter.Builder.validateBeforeWrite()` still substitutes `.`
for the first case, but rejects the second (a sample property not declared in FORMAT) with `VcfFormatException`.

`writeHeader()` may not be called more than once, `writeLine()` may not be called before `writeHeader()`, and every
`writeLine()` call must be given the same `VcfMetadata` object `writeHeader()` was given -- these three checks are
enforced unconditionally, in both the default and `validateBeforeWrite()` modes, since each is a cheap, one-time
check rather than a per-record cost. There used to be a stricter fourth check -- that the same `VcfMetadata`
object's emitted header text hadn't changed since `writeHeader()` was called, comparing a recomputed header
against a snapshot on every `writeLine()` under `validateBeforeWrite()`. It has been removed: `VcfMetadata` is
immutable (no instance mutators, and every collection getter, including each declaration's own properties map,
is an unmodifiable view), so the same object cannot produce different header text than the one already emitted,
and object identity is now sufficient on its own.

The default writer path is optimized for data written directly after parsing. It preserves parser-normalized values
and maintains record and sample-column structure without full revalidation. If data has been mutated and a full
structural validity and diagnostic check is required, build the writer with `VcfWriter.Builder.validateBeforeWrite()`.
In that mode, structurally invalid output is rejected and detected semantic non-compliance is reported with a
warning. This mode also enforces that every `INFO`/`FORMAT`/`FILTER`/`ALT`/`contig`/`SAMPLE` metadata entry's own
`ID` property must still match the key it's stored under in `VcfMetadata`'s maps (an entry's raw `ID` changing to
a different value, or being removed entirely, without it being reflected in the map key would otherwise make the
header declare a different (or missing) ID than what records reference by that entry's map key). No public API
route can produce that divergence any more; the check stays because `validateBeforeWrite()` is the full-output
diagnostics mode, and is exercised through the package-private `BaseMetadata.trustedMutableProperties()` bypass.
The same reasoning applies to that mode's null-entry/null-key/null-value metadata checks. The default
header-building path, by contrast, no longer re-checks metadata for nulls at all -- `VcfMetadata.Builder`
rejects a null declaration or a null raw-property name/value at insertion, and nothing can mutate one in
afterward, so those guards were removed as part of the same change.

The writer detects a write failure (disk full, broken pipe, or an encoding failure) and reports it as an
unchecked `java.io.UncheckedIOException`, but only checks periodically (every 1000 lines) and at
`writeHeader()`/`close()` -- not after every `writeLine()` call, to avoid forcing a flush past the writer's
internal buffer on every line. A failure detected mid-stream releases the underlying resource immediately, as
part of the same detection, rather than leaving it open until some later `close()` call. Calling `close()` is
still required to guarantee that all remaining buffered output is flushed and that a failure not yet caught by a
periodic check is reported. A detected write failure, or simply closing the writer at all (even cleanly, with no
error), permanently invalidates the `VcfWriter`: `isValid()` becomes `false`, and any further
`writeHeader()`/`writeLine()` call throws `IllegalStateException`
instead of attempting to write again -- retrying after a write failure, or reusing a writer after closing it, is
never safe, since the underlying writer may already be in a partially-written or otherwise inconsistent state, or
simply already released. `close()` itself is idempotent: calling it again after the first call has no effect,
matching `java.io.Closeable#close()`'s own documented contract.

## Lenient — warns when encountered or accessed and preserves usable data

Non-structural content may be accepted initially and validated only when the relevant getter or typed conversion
is used. A metadata declaration (`##INFO`, `##FORMAT`, `##contig`, `##FILTER`, `##ALT`, ...) that is well-formed
as a declaration -- wrapped in angle brackets -- but has malformed *attribute values* inside it warns rather than
throws; most are still stored as-is, with this exception (a declaration missing its angle-bracket wrapper entirely
is a structural failure and still throws -- see the strict section above):

### Metadata declarations

- A declaration missing its `ID`, or with an empty/whitespace-only `ID=` — every VCF structured metadata line
  requires an ID (VCFv4.3.tex line 121); the parser warns and drops the whole declaration rather than storing it.
  See [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md) for the full per-type
  bcftools/vcf_validator comparison and the empty-vs-missing rationale, including the distinct already-stored
  case (an object whose `ID` is live-mutated to blank after being stored is not retroactively removed).
- A missing or invalid `Number`, `Type`, or `Description`; an unquoted `Description`; or `Type=Flag` with a `Number`
  other than `0`. An unparseable `Type` leaves the metadata's type `null`. A non-reserved `##FORMAT` declaration
  missing `Number` is treated as list-shaped -- see `VCF_SPEC_AMBIGUITIES.md`'s "Undeclared `##FORMAT` `Number`
  attribute" entry for the bcftools-matched rationale.
- An `##INFO`/`##FORMAT` `ID` that doesn't match the required tag-name grammar (the same grammar the strict
  section above enforces at construction time): warned, not dropped -- the declaration is still stored and
  usable. See `VCF_SPEC_AMBIGUITIES.md`'s "`##FORMAT`/`##INFO` declaration ID grammar is stricter than
  `##FILTER`/`##SAMPLE`/`##ALT`" entry.
- A header/metadata sample-count mismatch, or a sample named in the header but absent from the metadata: warned
  during parsing, and separately by `VcfWriter.Builder.validateBeforeWrite()`'s `writeHeader()` (checked once,
  since `#CHROM`'s sample columns can't change after a `VcfMetadata` is built).
### INFO/FORMAT/sample value content

- INFO, FILTER, FORMAT, and sample content that is semantically malformed but can be preserved or normalized safely.
- A non-numeric `QUAL`: the column itself is still well-formed, so this is treated as the missing value rather than
  a structural failure.
- A data-line FORMAT sub-field name that doesn't match the required tag-name grammar (the same grammar the
  strict section above enforces at construction time, and the same one an `##INFO`/`##FORMAT` declaration
  `ID` is warned about, not rejected for, above): warned, kept as-is. Unlike the empty-sub-field-name case it
  sits next to in `VcfPosition.checkFormat`, this key can still be looked up in metadata if a matching (also
  grammar-invalid) `##FORMAT` declaration exists, since that declaration is itself warned about rather than
  dropped -- see `VCF_SPEC_AMBIGUITIES.md`'s "Data-line FORMAT key fails tag-name grammar" entry
  (bcftools-confirmed).
- A reserved `FORMAT/FT` value that doesn't follow the FILTER-like grammar (`PASS`, `.`, or a semicolon-separated
  list of codes with no whitespace, where `PASS`/`.` may not be combined with a *different* code -- repeating the
  same code, e.g. `PASS;PASS` or `.;.`, produces no warning at all, unlike the FILTER column's own duplicate-code
  check; see `WONT_FIX.md`'s "FT grammar diverges from `checkFilters`" entry), or a reserved `FORMAT/PS`
  value that isn't a non-negative 32-bit integer: both constraints go beyond what `FT`'s/`PS`'s declared `Type` can
  express, so they're checked separately, both when a typed value is requested and by
  `VcfWriter.Builder.validateBeforeWrite()`.
- A reserved `FORMAT/PL`/`GL`/`GP`/`PP` value whose element count doesn't match the `Number=G` cardinality
  implied by the sample's ploidy and the record's ALT allele count. When `GT` is absent, ploidy can't
  otherwise be determined at all, but these four keys specifically default to assumed diploidy rather than
  skipping the check, since their own spec definition states that default explicitly -- no other `Number=G`
  field, reserved or not, has any such spec-stated default, so this is scoped to exactly these four keys.
  `GLE`/`CNP` are separately exempted from this check entirely despite declaring `Number=G` themselves -- see
  `VCF_SPEC_AMBIGUITIES.md`'s "`CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G`" entry (`CNL` is
  unaffected by this exemption since its own declared `Number` is `.`, not `G`, under this library's VCFv4.2
  target). Also, any `FORMAT` key's value whose element count doesn't match its declared
  `Number`, or whose value doesn't match its declared `Type` (a non-reserved key needs an explicit `##FORMAT`
  declaration for either check to run; a reserved key's own canonical `Number`/`Type` apply even with no
  declaration at all): both cardinality checks (the reserved `Number=G` check above and this declared-`Number`
  check) are checked and warned about (never thrown) both when
  `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` is called and by
  `VcfWriter.Builder.validateBeforeWrite()`; a genuine `Type` mismatch throws `VcfFormatException` when read via
  `VcfSample.getProperty(VcfPosition, VcfMetadata, String)`, unchanged from
  `getProperty(ReservedFormatProperty)`'s existing behavior for a reserved key -- but
  `VcfWriter.Builder.validateBeforeWrite()` downgrades the identical `Type` mismatch to a warning instead of
  throwing, preserving this library's general policy of never aborting a write over recoverable content. A
  reserved key that *is* declared with a non-canonical `Number`/`Type` is the one case where the read and write
  paths can disagree: the read path always uses the reserved canonical value regardless of the declaration,
  while `validateBeforeWrite()` validates against what was actually declared.
- A reserved, list-typed `INFO` key's value whose element count doesn't match the cardinality its `Number`
  implies for the record's own ALT allele count (e.g. `AC`/`AF`'s `Number=A`, `CIPOS`/`CIEND`'s fixed
  `Number=2`) -- no reserved `INFO` key declares `Number=G`, so there's no ploidy-dependent case here the way
  there is for `FORMAT` -- or any `INFO` key's value whose element count doesn't match its declared `Number`
  (same declared-vs-reserved-fallback split as `FORMAT` above): checked and warned about (never thrown) both
  when a typed value is requested via `getInfo(ReservedInfoProperty)` and by
  `VcfWriter.Builder.validateBeforeWrite()`.
- A negative value for the reserved `INFO` keys `AC`, `AN`, `DP`, `END`, `MQ0`, or `NS`, an `AF` value outside
  the interval `[0, 1]`, or a reserved `INFO/CIGAR` value that doesn't match SAM's grammar: same treatment as
  `FT`/`PS` above. See `VCF_SPEC_AMBIGUITIES.md`'s "Negative/out-of-range values for reserved INFO keys" and
  "CIGAR grammar (SAM compliance)" entries.
- A `FILTER` value combining `PASS` with another code (e.g. `STD_FILTER;PASS`): see `VCF_SPEC_AMBIGUITIES.md`'s
  "`PASS` combined with other FILTER codes" entry.
- A duplicate value within a single `ID` or `FILTER` field (e.g. `ID=rs1;rs1`, `FILTER=hp23;hp23`): see
  `VCF_SPEC_AMBIGUITIES.md`'s "Duplicate ID/FILTER entries within one record" entry.
- An INFO value containing `=` (e.g. `DP=1=2`), or a duplicate top-level INFO key (e.g. `DP=1;DP=2`): see
  `VCF_SPEC_AMBIGUITIES.md`'s "INFO value containing a bare `=`" and "Duplicate INFO key within one record" entries.
- More than one comma-separated raw value for a scalar (`Number=0`/`1`) reserved `INFO` key (e.g. `AA=1,23`): a
  typed read of that key warns and keeps only the first value, the same "keep the first, warn about the rest"
  treatment as a duplicate top-level `INFO` key above.
- A declared `Number`/`Type` for a well-known reserved `INFO`/`FORMAT` key (e.g. `AC`, `AN`, `GT`, `PL`) that doesn't
  match VCF's canonical definition for that key.
- An `##INFO`/`##FORMAT`/`##ALT`/`##FILTER`/`##SAMPLE` declaration whose attributes are out of the canonical
  `ID, Number, Type, Description, Source, Version` order every spec example uses.
- An `##ALT` structural-variant ID (e.g. `<DEL:ME:ALU>`) that doesn't match VCF's reserved code hierarchy
  (DEL/INS/DUP/INV/CNV/BND and colon-delimited subtypes) — but only when the ID actually contains a colon; a
  colon-less ID (e.g. an IUPAC ambiguity code, or a `CN0`-through-`CN124`-style copy-number allele used by 1000
  Genomes) is not checked against that hierarchy at all, since colon-less custom codes have other spec-sanctioned,
  non-structural-variant uses this parser must not reject. A reserved code re-used as a subtype of another
  reserved code (e.g. `DUP:INV`) is a deliberate exception to bcftools -- see `VCF_SPEC_AMBIGUITIES.md`'s
  "Reserved structural variant code re-used as a subtype" entry.
### Metadata declaration IDs: additional edge cases

- Any of those same six types' `ID` that is present but blank or whitespace-only (`ID=` or `ID=   `) on an
  object that already exists in storage — as opposed to a declaration still being parsed, which is dropped
  before storage per the missing/blank-ID bullet above: warns (VCFv4.3.tex line 121, the same citation as
  that bullet). This covers a declaration built directly or live-mutated after construction, which the
  parser's own drop-before-storage check never sees. A whitespace-only `ID` falls under this bullet, not the
  whitespace-containing-`ID` bullet below (which fires only when trimming the `ID` would leave usable
  content).
- A `##contig`/`##INFO`/`##FORMAT`/`##FILTER`/`##SAMPLE`/`##ALT` declaration's `ID` containing whitespace (as
  opposed to being entirely blank/missing, handled by the two bullets above): warns uniformly across all six
  types. See `VCF_SPEC_AMBIGUITIES.md`'s "Whitespace in `##FILTER`/`##SAMPLE` declaration IDs" entry (titled
  after the two types with the least direct spec support, but the decision covers all six) for the full
  per-version spec/hts-specs-fixture/bcftools/vcf_validator comparison. `##ALT` IDs also separately warn on
  angle brackets (`<`/`>`), per VCFv4.2.tex line 105 -- an `##ALT`-specific restriction not extended to the
  other five types, since no fixture or spec passage evidences it for them.
- Any of those same six declaration types' `ID` containing a comma: warns, with no exception for `##ALT` (unlike
  angle brackets above) — a comma is never part of `##ALT`'s own colon-delimited structural-variant-code grammar
  or its colonless conventions (an IUPAC ambiguity code or a `CN0`-style copy-number allele) either. This follows
  directly from every `##INFO`/`##FILTER`/`##FORMAT`/`##ALT`/`##contig`/`##SAMPLE` worked example's own
  comma-delimited attribute-list syntax, not a per-type policy choice, so it needs no bcftools/vcf_validator
  corroboration the way the whitespace check above does.

### Other lenient behavior

A `##fileformat` declaring a VCF 4.x version this library does not implement (`VCFv4.0` and below, `VCFv4.4`
and above) is warned about once and interpreted under the nearest implemented version — 4.1 for anything below
it, 4.3 for anything above. The declared value itself is preserved verbatim and re-emitted unchanged on write,
so such a file still round-trips under its own declaration; the fallback governs interpretation only. A
structurally unusable value (not matching `VCFv4.<digits>`) still throws, in both strict and lenient contexts —
this is a well-formed declaration for an unimplemented version, not invalid content. See
`VcfVersion.resolve`.

Note that both `VcfParser` and `VcfWriter` build their own `VcfContext`, so this warning is emitted once per
parse *and* once per write: a parse-then-write round trip of a `VCFv4.4`/`VCFv4.5` file reports it twice. That
duplication is known and deferred rather than suppressed — whether the emitted output is actually valid under
the declared version is not answerable until version-gated behavior exists, so the writer has nothing more
specific to say yet. Pinned by
`VcfWriterTest.testWriteSideVersionFallbackWarningIsEmittedForAnUnimplementedDeclaration`.

An empty ("zero-length") entry in a delimited record field — e.g. `ID=rs1;;rs2`, `ALT=T,`, `FILTER=q10;`,
`FORMAT=GT:DP:`, a sample value like `0/1:`, or `INFO=AD=1,,2` — is also lenient rather than strict when it can be
recovered without changing field alignment. See [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md) for the full
field-by-field table of what each case normalizes to and why.

Typed metadata accessors (e.g. `InfoMetadata.getType()` and `getNumber()`) are annotated `@Nullable` and return `null`
when the corresponding metadata was malformed.

Reserved `Float`-typed `INFO`/`FORMAT` properties (e.g. `INFO`'s `AF`/`MQ`, `FORMAT`'s `GL`/`GP` -- note that a
key reused on both sides isn't always the same `Type` on each; `FORMAT`'s own `MQ`, for example, is `Long`,
not `Float`) are converted to
[`VcfFloat`](src/main/java/org/pharmgkb/parser/vcf/VcfFloat.java), not a plain `BigDecimal`: VCFv4.2 doesn't
define `Float`'s literal grammar, and VCFv4.5 clarifies it accepts `NAN`/`INF`/`INFINITY` (case-insensitive,
optionally signed) in addition to a normal decimal number — values a plain `BigDecimal` cannot represent at all.

A `FORMAT` value of `.` (with one or more samples present) means no per-sample format fields at all for that
record; each sample is read as having zero properties, and any raw sample-column content there is ignored (with a
warning). See `VCF_SPEC_AMBIGUITIES.md`'s "`FORMAT=\".\"` meaning no per-sample fields" entry for the
bcftools-confirmed rationale.

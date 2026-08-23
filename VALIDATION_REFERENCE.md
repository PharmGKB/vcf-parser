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

- `VcfParser.Builder.fromFile(Path)` rejects a file whose bytes are not valid UTF-8, and (indirectly, via the
  `##fileformat` check below) a file beginning with a byte order mark, since a BOM-prefixed file's first line
  is then not a valid `##fileformat` declaration -- see `VCF_SPEC_AMBIGUITIES.md`'s "BOM-prefixed file /
  `+`-prefixed POS" entry, which confirms this matches both bcftools and vcf_validator. VCFv4.3.tex line 77,
  "The character encoding of VCF files is UTF-8," and line 80, "VCF files must not contain a byte order mark"
  (verbatim at VCFv4.4.tex lines 77/80 and VCFv4.5.tex lines 75/78); VCFv4.2 never names an encoding.
  `fromFile(Path)` reads through `Files.newBufferedReader`, whose UTF-8 decoder reports malformed input rather
  than silently replacing it, and `VcfWriter` encodes with a reporting `CharsetEncoder` for the same reason.
  **Scope note:** this guarantee is a property of the path-based builder only --
  `VcfParser.Builder.fromReader(BufferedReader)` takes an already-decoded character stream, so the charset, and
  whether malformed input is reported or silently replaced with U+FFFD, are entirely the caller's to choose;
  this library cannot observe or enforce it on that route.
- A missing, duplicate, or non-first `##fileformat`; a file format outside VCF 4.x; or any line other than a
  `##` metadata line before the `#CHROM` header. A line starting with `#` **after** the header is no longer
  specially rejected: VCF has no comment syntax at all (no version's `.tex` uses the word "comment"), and the
  spec's own contig-name grammar permits `#` as an ordinary CHROM character (VCFv4.3.tex:262) -- confirmed
  live that bcftools 1.23.1 accepts a `#`-led CHROM value as an ordinary data line. Such a line is now parsed
  as ordinary data and rejected only if it's actually malformed by the usual column-count/per-field checks
  below, the same as any other line -- see `VCF_SPEC_AMBIGUITIES.md`'s "A data line whose CHROM begins with
  `#`" entry.
- A structured metadata declaration (`##INFO`, `##FORMAT`, `##contig`, `##FILTER`, `##ALT`, `##SAMPLE`,
  `##PEDIGREE`) whose value is not wrapped in angle brackets (`<...>`).
- A `##` metadata line with no `=` at all (e.g. a bare `##sometext` or `##INFO` with no `=`) -- distinct from the
  missing-angle-brackets case above, which requires a well-formed `key=value` structured declaration whose value
  isn't wrapped in `<...>`.
- A missing `#CHROM` header, wrong fixed column names, a missing `FORMAT` column when samples are present, a
  `FORMAT` column with no sample columns following it, an empty sample name, or a duplicate sample name.
- A data line with the wrong number of tab-separated columns.
- An empty mandatory fixed field (the missing value must be `.`), an empty `CHROM`, or a negative `POS`.
- Invalid `REF`/`ALT` bases; whitespace where the spec forbids it (`CHROM`, `ID`, `FILTER`, and an `INFO` key --
  a `FORMAT` key's own, narrower rule is its own bullet below); a `FILTER` of `0`; a `FILTER` missing value
  (`.`) combined with another filter code; an `INFO` missing value (`.`) combined with a real property; or a
  `FORMAT` in which `GT` is present but not the first sub-field or a (non-empty) key is duplicated.
- A `FILTER` entry containing a literal `;`, or a `FORMAT` key containing a literal `:` -- both unconditional
  (thrown regardless of strict/lenient), matching this same section's `ID`/`INFO` treatment of their own
  delimiters. VCFv4.3.tex line 362 states, for `FILTER`, "String, no whitespace or semicolons permitted,
  duplicate values not allowed" (the same sentence `ID`'s own semicolon rule at line 338 uses); VCFv4.3.tex
  line 429 states, of the FORMAT field, "colon-separated FORMAT keys matching the regular expression
  `^[A-Za-z_][0-9A-Za-z_.]*$`, duplicate keys are not allowed". Both are reachable only through direct construction
  (`VcfContext.newPosition`, `VcfPosition.Builder`) -- confirmed live (bcftools 1.23.1) that either shape
  corrupts silently if ever written and re-read: a `FILTER` entry `"a;b"` round-trips as two filter codes with
  no warning at all, and a `FORMAT` key list containing a colon-corrupted key misassigns every subsequent
  sample value on re-parse. See `docs/superpowers/specs/2026-08-18-1000-vcfposition-corrupting-character-surface-design.md`
  for the full measured evidence.
- A `FORMAT` key containing a literal tab, LF, or CR -- unconditional, added alongside the `:` check as a
  distinct structural floor. **Deliberately narrower** than `ID`/`FILTER`/`INFO`-key's full-whitespace ban
  above: a plain space, vertical tab, or form feed in a `FORMAT` key is a tag-name-grammar violation like any
  other (a leading digit, illegal punctuation) and stays warn-or-throw via the pre-existing `FORMAT_PATTERN`
  check (`findings37.md` item 1's deliberate leniency, matching bcftools' own measured tolerance -- confirmed
  live that bcftools only warns on a space/VT/FF-corrupted key, exit 0, no column shift, no data loss). Tab
  and LF/CR are different in kind: `VcfWriter.addFormatConditionally` appends a `FORMAT` key verbatim
  (`VcfWriter.java:486`), so an embedded tab adds a column (tab is the record's own column delimiter) and an
  embedded LF/CR ends the record early on write, corrupting record structure rather than merely the key text
  -- confirmed live (bcftools 1.23.1) that a tab-corrupted `FORMAT` key makes bcftools fail outright to read
  the file at all (`[E::vcf_parse_format_fill5]`, exit code 8), a worse outcome than either of the two
  delimiter-corruption shapes above. Reachable only through direct construction, for the same reason as the
  `:` check.
- An `INFO` **value** is the one exception to that whitespace rule: a literal space (U+0020) is accepted at
  every declared version, while a tab, LF, CR, vertical tab, or form feed still throws. VCFv4.2.tex line 186
  forbids all whitespace in an INFO value, but VCFv4.3.tex line 366 states "Space characters are allowed in
  values" (verbatim at VCFv4.4.tex line 382 and VCFv4.5.tex line 431), corroborated by the VCFv4.2→4.3
  changelog at VCFv4.3.tex line 2151 ("Spaces are allowed in INFO field values"), and this library applies that
  rule unconditionally, including to `VCFv4.1`/`VCFv4.2` files that predate it — see "Rules applied at every
  declared version" below. This is the one rule there where the target version was not merely silent but in
  direct conflict before the target moved to 4.3: under a VCFv4.2 target this was a named exception in
  `AGENTS.md`; at a VCFv4.3 target it is plain conformance and that exception paragraph has been deleted, though
  the unconditional-application behavior itself hasn't changed. The relaxation stops at U+0020 deliberately: the
  spec says *space characters*, not whitespace, and the remaining five are structural rather than merely
  non-conformant, since `VcfWriter` writes an INFO value straight into the record column, where a tab adds a
  column and an LF/CR ends the record. Reachable only through direct construction (`VcfContext.newPosition`,
  `VcfPosition.Builder.info`) or a raw INFO column containing a vertical tab/form feed — a parsed value can
  never contain a tab or line terminator, since those are the column and record delimiters. **Scope note:**
  because the relaxation is unconditional, nothing diagnoses a space in an INFO value on either path --
  including `VcfWriter.Builder.validateBeforeWrite()` writing under a declared `##fileformat=VCFv4.2`, where
  the emitted value is legal by this library's reading but not by VCFv4.2.tex line 186's. That silence is
  settled behavior, not an oversight in diagnostics mode: the value was already accepted without a diagnostic
  on construction and on parse, so warning about it on the way out would make the library's own acceptance
  asymmetric between read and write rather than closing a gap. The only reachable case is a 4.2 context written
  under 4.2, since `VcfWriter.writeHeader` rejects any target/metadata version mismatch outright and no
  re-contextualization route exists.
- `CHROM` additionally rejects `\`, `,`, `"`, `` ` ``, `'`, `(`, `)`, `{`, `}`, `[`, `]`, or `<`/`>` (unless the
  entire value is a well-formed, fully angle-bracket-wrapped symbolic contig reference like `<1>`) anywhere in
  the value, and a leading `*` or `=` -- matching the SAM reference-sequence-name character set VCFv4.3 aligned
  CHROM to. A colon is explicitly allowed. VCFv4.3.tex lines 2136-2138 (the "Changes to VCFv4.3" errata
  subsection at 2129-2144) state that the set of characters allowed in VCF contig names is now the same as
  SAM's reference-sequence-name rules, with `` \ , " ` ' ( ) { } `` newly invalid and `*` valid when not first;
  restated in the body at VCFv4.3.tex lines 258-259 (verbatim at VCFv4.4.tex lines 272/2599 and VCFv4.5.tex
  lines 321/2781). A colon is explicitly valid (VCFv4.3.tex lines 2140-2141, "this was unnecessary"). VCFv4.2.tex
  line 179 states only "no whitespace permitted" for `CHROM` and says nothing at all about `##contig` IDs.
  Applied via `InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN` in both `VcfPosition.checkChromosome` (this
  check) and `ContigMetadata` (the `##contig` ID check below). This blocklist does not enforce that same
  errata's *outer* bound (VCFv4.3.tex:258-259's `[!-~]` printable-ASCII range) -- see the "Other lenient
  behavior" section below for that check, which warns rather than throwing.
- A sample with more sub-fields than its `FORMAT` declares (trailing sub-fields may be dropped, but not added).
- A malformed or out-of-range `GT` allele index looked up through `VcfSample.getGenotype(VcfPosition)` (or
  `MemoryMappedVcfDataStore`'s `getGenotypeForId`/`getGenotypeAtLocus`, which delegate to it), or a failed
  conversion when a typed value is requested. **Scope note:** this covers a *non-empty* allele token only --
  `VcfSample.parseAlleleIndex` throws for a token that isn't parseable as an `int`, or that is negative or
  greater than `position.getAltBases().size()`, and it is never reached for a zero-length token, which
  `getGenotype`'s own preceding branch handles instead. A zero-length allele token warns unconditionally on
  both contexts -- see the `GT` bullet in "INFO/FORMAT/sample value content" below. An empty (but present)
  whole `GT` value under a strict `VcfContext`
  also throws here, for the same `isLenient()`-aware reason as every other reserved/non-reserved typed read's
  empty-value substitution below -- `getGenotype` reads `GT` through `getProperty(ReservedFormatProperty)`,
  which shares that dispatch; under a lenient `VcfContext` an empty `GT` still returns `null`, unchanged.
- A key or value set through `BaseMetadata`'s, `VcfSample`'s, or `VcfMetadata`'s mutators (e.g. a `Description`, a
  sample's `GT` value, or an `##assembly` line) containing a line terminator. VCFv4.3.tex line 81 ("non-printable
  characters U+0000--U+0008, U+000B--U+000C, U+000E--U+001F are disallowed") and line 82 ("Line separators must
  be CR+LF or LF and they are allowed only as line separators at end of line") -- verbatim at VCFv4.4.tex lines
  81/82 and VCFv4.5.tex lines 79/80 -- state this generally; VCFv4.2 is silent. Applied via `InternalUtils.checkNoLineTerminator`
  (metadata keys and values), `VcfSample.checkNoStructuralDelimiter` (a tab or line terminator in a sample key
  or value), and `VcfPosition.checkInfoEntries`'s value-side check (tab, LF, CR, vertical tab, or form feed in
  an INFO value). See "Rules applied at every declared version" below for the known gap in this check's range.
- A `VcfSample` key or value set through its constructors or `Builder#property` (validated at `build()` time, not
  at `property()`'s own call site) containing a colon or tab, which would otherwise add a spurious FORMAT
  sub-field or sample column when written back out. The one exception, **under VCFv4.2 and earlier only**, is a
  value for the key `GLE` (VCFv4.1.tex line 208/VCFv4.2.tex line 225's genotype-likelihoods-of-heterogeneous-ploidy
  key): its own spec example (e.g. `0:-75.22,1:-223.42,...`) is one opaque String that uses colons internally as part
  of its own encoding, not a delimiter between independent sub-fields; a colon in the key `GLE` itself, or a tab in
  its value, is still rejected. VCFv4.3 removed the key (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE
  FORMAT tag"), so from VCFv4.3 a key named `GLE` is an ordinary custom key and its value is colon-rejected like
  every other key's.
- `VcfWriter.writeLine` throws `VcfFormatException` if it is given a number of samples that disagrees with the
  header's declared sample count, or any FORMAT/sample data at all when the header declares no samples: in
  either case the output would not be valid, re-parseable VCF. (An empty `REF` -- which has no missing-value
  sentinel in the spec, unlike `ALT`/`ID`/`FILTER` -- needs no corresponding guard here: `VcfPosition`'s own
  `checkRef()` runs unconditionally in every constructor, so a `VcfPosition` can never carry an empty `REF`
  into this method to begin with.)
### Direct/programmatic construction

- A metadata declaration built under a strict `VcfContext` (e.g.
  `new VcfMetadata.Builder(VcfContext.strict("VCFv4.2")).addInfo(id, description, type, number, source,
  version)`) throws, *at construction time*, for every condition that class's own declaration-level checks
  would otherwise flag -- a
  missing/empty/whitespace-only `ID` (VCFv4.3.tex line 121), an `##INFO`/`##FORMAT` `ID` that doesn't match
  the required tag-name grammar (VCFv4.3.tex line 368, "INFO keys must match the regular expression
  `^([A-Za-z_][0-9A-Za-z_.]*|1000G)$`" -- verbatim at VCFv4.4.tex line 384, VCFv4.5.tex line 433 -- and line 429
  for FORMAT's parallel grammar, `^[A-Za-z_][0-9A-Za-z_.]*$`, which has no `1000G` exception; the
  VCFv4.2→4.3 changelog states the rule for INFO and FORMAT together at VCFv4.3.tex line 2150. VCFv4.2 gives
  no tag-name grammar for either: must start with a letter or underscore, followed
  only by letters, digits, underscores, or periods -- `##INFO` additionally allows the legacy value
  `1000G`), an invalid `Number`, a `Type=Flag`/non-zero-`Number` mismatch, a reserved property's Number/Type
  mismatch, a malformed `##contig` URL or `md5` (VCFv4.3.tex line 247, "a 32 character lowercase
  hexadecimal number"), a `##contig` ID starting with `*`/`=` or containing an invalid
  character (see `VCF_SPEC_AMBIGUITIES.md`'s "`##contig` ID character set" entry), or a `##contig` ID that is
  exactly a reserved symbolic allele name (`VCFv4.3.tex` line 266, "The contig names must not use a reserved
  symbolic allele name," new at 4.3 -- see `ReservedStructuralVariantCode.isReservedSymbolicAlleleName`'s own
  comment for exactly which names count as reserved). This is a
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
  **The same is true of the lazy first-access getters, which is the route a caller reaches this by in
  practice**, not just of an explicit `validate()` call: raw QUAL/INFO text supplied via
  `VcfPosition.Builder`'s `rawQuality`/`rawInfo` is not checked at `build()` time at all, so it is first
  parsed (and checked) by `getQuality()`/`getInfo()`, which pass the position's warnings sink unconditionally
  the same way `validate()` does. Identical content therefore behaves differently under a strict context
  depending on which setter supplied it -- `info(ListMultimap)` with an empty INFO key throws from `build()`,
  while `rawInfo("=orphan")` warns on first access and drops the entry. That is deliberate: `rawInfo`/
  `rawQuality` are the explicit named wire-text escape hatch (the one route by which un-decoded, file-shaped
  text legitimately enters the model), and leaving them unvalidated at build time is what preserves lazy
  parsing across a `toBuilder()` copy. See `VcfPositionTest`'s
  `testValidateAlwaysWarnsRegardlessOfOriginalConstructor`, which pins it.
  Each also has structural checks that are a separate case and still throw unconditionally in both contexts:
  `VcfSample.validate()` throws via `checkNoStructuralDelimiter` (`VcfSample.java`) for a null property
  key, a line terminator, or a `:`/tab in a key or (non-`GLE`-under-VCFv4.2-and-earlier) value;
  `VcfPosition.validate()` throws via
  `checkChromosome`/`checkPosition`/`checkRef` and via `checkFormat`'s null-sub-field-name, duplicate-key, and
  GT-not-first checks. `checkPosition`/`checkRef` and `checkFormat`'s three checks take no warnings sink at
  all and always throw; `checkChromosome` is the one exception -- it takes a warnings sink for its single
  printable-ASCII-range check (see "Other lenient behavior" below), which `validate()` always warns through
  rather than throwing, matching this same paragraph's own "always warns for a recoverable condition" rule.
  Neither class can be mutated after construction any more: Task 8 froze `VcfPosition`
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
- `VcfMetadata.Builder` constructed with a strict `VcfContext` throws for a missing/duplicate declaration ID
  (VCFv4.3.tex line 121, "All structured lines require an ID which must be unique within their type" --
  verbatim at VCFv4.4.tex line 121, VCFv4.5.tex line 119 -- corroborated by the VCFv4.2→4.3 changelog at
  VCFv4.3.tex lines 2161-2163; VCFv4.2 is silent),
  an invalid `##ALT` structural code, an empty `assembly`/`pedigreeDB` value, or an
  `assembly`/`pedigreeDB`/other generic property value shaped like an empty or no-`=` bracketed attribute list
  (e.g. `<>` or `<http://foo.com>`). Constructed with a lenient one, each of those warns and keeps the first
  declaration instead (matching bcftools; see
  [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md)), routed to that context's
  `VcfWarnings`. `VcfParser`'s own internal use always builds a lenient context, so parsing a file's behavior
  is unchanged by any of this. `##PEDIGREE`'s own missing-ID rule differs from the seven keyed types just
  described in one respect -- see [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md)'s
  eighth-type paragraph in "Missing or empty ID": under VCFv4.3+ a strict context throws exactly like the seven
  keyed types, but a lenient one keeps the declaration rather than dropping it, since `##PEDIGREE` is
  `List`-backed and has no map slot to fail to claim.
- `VcfMetadata.Builder.replaceAlt()` / `replaceInfo()` / `replaceFormat()` / `replaceFilter()` / `replaceContig()`
  / `replaceSample()` / `replaceMeta()` throw `VcfFormatException` for an `ID` with no existing declaration to replace -- in both
  a lenient and a strict context, unlike most rules in this section. This is deliberate, not an oversight: a
  missing replace target is a programming error in either mode, not a content violation, so it is not routed
  through `warnOrThrow`. See
  [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md)'s "`replaceXxx`: the supported way to
  overwrite a declaration" section.
- `VcfMetadata.Builder`'s content-accepting `addXxx`/`replaceXxx` routes (27 checked call sites across the
  eight kinds' typed and `Map` overloads) call `InternalUtils.checkMetadataPropertyEncoding`, which throws
  `VcfFormatException`, unconditionally in both a lenient and a strict context, for ANY problem
  `InternalUtils.findEncodingProblems` finds in the constructed declaration's `getPropertiesRaw()`: a property
  name outside `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN` (`^[A-Za-z_][0-9A-Za-z_.]*$`, matching bcftools
  -- see `VCF_SPEC_AMBIGUITIES.md`'s "Metadata attribute NAME grammar" entry), a value starting with `[` and
  never closing with a `]` anywhere (checked UNCONDITIONALLY, on any attribute of any declaration type, since
  bcftools' own generic bracket handling hard-fails the whole file on this shape regardless of attribute name
  -- see `STRUCTURAL_DELIMITER_HANDLING.md`'s "Brackets: a second value-quoting form, scoped to `##META`'s
  `Values`"), beyond that a malformed bracket value on `##META`'s `Values` specifically (one that does close
  but not with its FIRST LITERAL `]`, checked via `InternalUtils.isWellFormedMetaValuesBracket`'s plain,
  unescaped scan -- unlike `"`, the spec defines no escape mechanism for `[`/`]`, so this deliberately does
  NOT use `indexOfUnescaped`), or -- for a value that is neither quoted nor bracket-shaped -- an unquoted `,`, `<`, `>`, an
  unescaped `"`, or leading/trailing space. `=` is deliberately NOT one of these: an unquoted value may contain
  it freely, unlike before this check was aligned with the attribute-list tokenizer's own grammar. This matches
  `checkNoLineTerminator` rather than being routed through `warnOrThrow`, since this is structural corruption
  (`AGENTS.md`'s Parser Validation Policy: "invalid delimiter handling that changes field alignment"), not a
  case a lenient warning could usefully recover from. `VcfParser`'s own eight package-private
  `addXxxTrusted(Map)` routes skip this check, because the parser deliberately warns-and-recovers on an
  unescaped quote and stores the recovered value; `Builder.addRawProperty` applies a narrower, also-
  unconditional rule instead (rejects `=` only in the generic property's *name*, since a `##key=value` line is
  re-read by splitting on the first `=` only). See
  [`STRUCTURAL_DELIMITER_HANDLING.md`](STRUCTURAL_DELIMITER_HANDLING.md) for the Reference table's per-shape
  throw/warn outcomes, the severity ranking, and the parse-path exemption's rationale.
- `VcfWriter.validateBeforeWrite()`'s `validateMetadataPropertyEncodings` (run once per header, from
  `writeHeader` only) calls `InternalUtils.checkMetadataPropertyEncodingForWrite`, the write-time counterpart
  to the construction-time check above, sharing the identical `findEncodingProblems` classifier so the two
  routes cannot drift about what counts as a problem. It throws only for a problem
  `EncodingProblem.unreadableOutput()` marks -- an unquoted comma, or a malformed bracket value -- but that
  flag is two different reasons, not one: an unterminated bracket value throws because it genuinely makes
  bcftools unable to read the file at all (exit 255), while the comma and the closed-but-junk bracket
  sub-shape throw for a weaker reason (bcftools merely discards the one declaration, exit 0 -- the same
  outcome an invalid attribute name only warns for -- but the attribute-list tokenizer can never produce
  either shape as a stored value while parsing, so throwing there costs nothing and harms no carried-forward
  content). See `VCF_SPEC_AMBIGUITIES.md`'s "Unquoted metadata attribute value" entry and
  `InternalUtils.EncodingProblem`'s own Javadoc for the live-measured evidence behind each. Every other
  problem (an invalid property name, an unquoted `<`/`>`, an unescaped `"`, or leading/trailing space) only
  warns to the writer's own `m_warnings` sink and leaves the value unchanged, since the file stays
  bcftools-readable and this route's content may be carried-forward (parsed, not originated) rather than
  something this write call created -- the same originated-vs-carried-forward distinction `AGENTS.md`'s
  "bcftools is asymmetric evidence" section states, using `##contig=<ID=1,a-b=v>` as its own worked example. An
  invalid property *name* specifically is not write-only: the parse path warns on it too (see "Metadata
  declarations" in the Lenient section below) rather than only surfacing it here for the first time.
- `VcfMetadata.Builder` throws `VcfFormatException` for a declaration built under a *different* `VcfContext`
  instance (compared by reference, not value) than the builder's own. `VcfMetadata` itself has no instance
  mutators to add this check to -- it is immutable, and `getInfo()`/its siblings return memoized unmodifiable
  views over declarations that are themselves immutable. The check still matters because a declaration is a
  standalone object before it is ever added to a builder: without it, a caller could construct one under one
  `VcfContext` and add it to a `VcfMetadata.Builder` built under a different one, silently routing that
  declaration's own future validation (e.g. a re-run of `validate()`) to the wrong `VcfWarnings` sink than the
  metadata object it now lives in uses. See `VALIDATION_PHILOSOPHY.md:82`
  ("A declaration built under one context cannot be moved into a metadata object built under another by any
  route (see `VcfMetadata.checkSameContext`)").
- `VcfContext.newRecord` throws `VcfFormatException` if its position or any of its samples was created under a
  different `VcfContext` instance, for the same reason -- a record assembled from two contexts has no
  unambiguous warnings destination, and no unambiguous resolved VCF version either.
- `VcfWriter.writeLine` throws `VcfFormatException` if the position, or any sample, was created under a
  different `VcfContext` instance than the `metadata` argument's. The metadata's context is the pivot, so the
  position-vs-samples case is covered by transitivity. This is a structural check, not the default path's
  exempted VCF-semantic re-validation, so it runs in **both** write modes -- `writeLine` derives the record's
  resolved version from that one context and threads it into the reserved-INFO/FORMAT constraint checks, which
  is only well-defined if there is exactly one. (It is deliberately not `VcfWriter`'s own `m_context`: that is
  writer-local diagnostics state, rebuilt on every `writeHeader()` and never reference-identical to the context
  that built the data.)
- `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` and `VcfPosition.getInfo(VcfMetadata, String)` both
  throw `VcfFormatException` if `position`/`metadata` (whichever the method takes) was created under a
  different `VcfContext` instance than the receiver, for the same reason: the read resolves its version and
  its warnings sink from the receiver's own context, which is meaningless if they disagree. Checked before the
  property lookup, so a mismatch does not depend on whether the requested key happens to have a value. This is
  a separate axis from the method's null tolerance: `key`/`id` still returns `null` (matching the plain
  `getProperty(String)`/`getInfo(String)` sibling's "not present" contract), but `position`/`metadata` reject
  `null` with a raw `NullPointerException` instead -- a deliberate exception to `AGENTS.md`'s Public API
  Null-Safety Policy's general rule, since these two arguments are VCF content used as a read-time collaborator
  rather than content being validated for correctness itself; see `TODO.md`'s "Document a third Public API
  Null-Safety Policy category" entry for the full reasoning.
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
warning. This mode also enforces that every `INFO`/`FORMAT`/`FILTER`/`ALT`/`contig`/`SAMPLE`/`META` metadata entry's own
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
  `##PEDIGREE` is an eighth, differently-shaped case gated on VCFv4.3+ specifically (VCFv4.2's own `##PEDIGREE`
  grammar has no `ID` attribute at all, so this never fires under 4.2): a missing/empty/whitespace-only `ID`
  warns but the declaration is **kept**, not dropped, and `VcfWriter.validateMetadata()` re-checks the same
  condition at write time under `validateBeforeWrite()`. Every one of these checks compares the `ID` unquoted
  (see AGENTS.md's "Unquoting before internal use" policy), so a quoted-empty (`ID=""`) or quoted-whitespace
  (`ID="   "`) value is caught identically to its unquoted equivalent. See
  [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md)'s eighth-type paragraph for why
  (`List`-backed, so nothing is "cannot be stored" for).
- `##META` itself does not exist as a structured declaration type before VCFv4.3 -- 0 occurrences in
  `VCFv4.1.tex`/`VCFv4.2.tex`; `VCFv4.3.tex`'s changelog at line 2155 reads "Introduced ##META header lines
  for defining phenotype metadata". Below VCFv4.3, `VcfParser.parseMetadata`'s `case "META":` falls through to
  `addRawProperty` (the same path an unrecognized key takes) instead of `removeAngleBrackets`/
  `parseMetadataProperty`, so a pre-4.3 `##META` line -- bracketed or freeform -- is preserved as an ordinary
  raw property, not parsed into a `MetaMetadata`. `InternalUtils.isReservedMetadataKey` takes the declared
  `VcfVersion` for the same reason: below 4.3, `"META"` is excluded from the reserved set so `addRawProperty`
  (`VcfMetadata.java`) and `VcfWriter`'s reserved-key check don't drop/reject that raw property either.
  **Deliberately not gated at `Builder.addMeta` or at write time** (unlike `##PEDIGREE`'s ID rule above): every
  VCF version has always permitted an arbitrary unrecognized `##key=value` metadata line, and bcftools' header
  tokenizer never consults `##fileformat` at all, so constructing or writing a structured `##META` under a
  pre-4.3 declared version is not spec-non-compliant and there is no "bcftools would reject this" argument for
  refusing it -- gating construction/write would be a stricter rule than parsing itself enforces, for no spec
  or interop reason. **Consequence, not itself gated either:** a `##META` this library originates via
  `addMeta`/`replaceMeta` under a pre-4.3 declared version writes cleanly (`##META=<ID=Assay>`) but is not
  reproducible by parsing that same output back under the same pre-4.3 version -- the parse-only routing above
  applies equally to this library's own output, so the re-parsed file has no `MetaMetadata` at all (only the
  raw property). This is a direct, unavoidable consequence of the non-gating decision, not a separate defect:
  constructing under a pre-4.3 version and expecting a structured round trip is a caller mistake, since
  `##META` genuinely isn't a structured type at that version.
- A missing or invalid `Number`, `Type`, or `Description`; an unquoted `Description`; `Type=Flag` with a `Number`
  other than `0` (`##INFO` only -- `##FORMAT` has no `Flag` type); or `Number=0` with a `Type` other than `Flag`
  (both directions of `VCFv4.3.tex` line 171's "the Number must be 0 in this case" -- for `##FORMAT`, which has
  no `Flag` type at all, `Number=0` is invalid unconditionally, regardless of the declared `Type`). `Number`/
  `Type` validity is checked unquoted (a quoted `Number="1"` is treated the same as
  `Number=1`); the `Description` quoting check itself is deliberately the exception -- see AGENTS.md's
  "Unquoting before internal use" policy for why. An unparseable `Type` leaves the metadata's type `null`. A
  non-reserved `##FORMAT` declaration
  missing `Number` is treated as list-shaped -- see `VCF_SPEC_AMBIGUITIES.md`'s "Undeclared `##FORMAT` `Number`
  attribute" entry for the bcftools-matched rationale.
- An `##INFO`/`##FORMAT` `ID` that doesn't match the required tag-name grammar (the same grammar the strict
  section above enforces at construction time): warned, not dropped -- the declaration is still stored and
  usable. See `VCF_SPEC_AMBIGUITIES.md`'s "`##FORMAT`/`##INFO` declaration ID grammar is stricter than
  `##FILTER`/`##SAMPLE`/`##ALT`" entry.
- A metadata declaration attribute NAME (the `key` in the `key=value` pairs a structured line's `<...>` value is
  a comma-separated list of, per VCFv4.3.tex line 117, "a comma-separated list of key=value pairs, enclosed
  within `<' and `>' characters" -- verbatim at VCFv4.4/4.5, silent on `key`'s own character-set grammar at
  every version, so bcftools governs) that doesn't match
  `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN` (`^[A-Za-z_][0-9A-Za-z_.]*$`, matching bcftools): warned, not
  dropped -- the value is still stored and re-emitted. Checked in `InternalUtils.foldAttributes`'s post-fold
  loop, once per surviving key rather than once per parsed attribute, since a name repeated across two
  invalid-named occurrences is one bad name and the fold loop's own duplicate-attribute warning already reports
  the repetition. The same regex is also enforced at construction time (throws, see the strict section above)
  and by `VcfWriter.Builder.validateBeforeWrite()` (warns, see above) -- see `VCF_SPEC_AMBIGUITIES.md`'s
  "Metadata attribute NAME grammar" entry for the full three-stage picture.
- A header/metadata sample-count mismatch, or a sample named in the header but absent from the metadata: warned
  during parsing, and separately by `VcfWriter.Builder.validateBeforeWrite()`'s `writeHeader()` (checked once,
  since `#CHROM`'s sample columns can't change after a `VcfMetadata` is built).
### INFO/FORMAT/sample value content

- INFO, FILTER, FORMAT, and sample content that is semantically malformed but can be preserved or normalized safely.
- **A `QUAL`/reserved INFO/FORMAT value that cannot be parsed as its declared `Type` at all** -- distinct from a
  value that parses fine but is out of range or the wrong cardinality (both of which warn-and-recover under a
  lenient `VcfContext`, per the bullets below): `VcfPosition.getQuality()`, `InternalUtils.convertElement`'s
  `Character`/`Boolean`/`Long`/`VcfFloat` branches (reached from `getInfo(ReservedInfoProperty)`,
  `getInfo(VcfMetadata, String)`, `VcfSample.getProperty(ReservedFormatProperty)`, and
  `getProperty(VcfPosition, VcfMetadata, String)`) all throw `VcfFormatException` **unconditionally, regardless of
  context** -- not gated by `isLenient()` the way every other check in this section is. This is deliberate, not an
  oversight: unlike an out-of-range number (still a real, storable value) or a wrong-cardinality list (the raw
  pieces are all individually still valid), there is no sane value to preserve or normalize a genuinely
  non-numeric/non-boolean/non-single-character string to. Nothing is lost by throwing either way: the original
  wire text remains permanently retrievable afterward via a sibling raw accessor the typed conversion never
  mutates -- `VcfPosition.getRawQuality()`/`getInfo(String)`/`getInfo()` for QUAL/INFO, `VcfSample.getProperty(String)`
  for FORMAT. This is a genuinely deferred check: `VcfParser` itself stays lenient and finishes parsing the file
  regardless of what a later typed-getter call throws. Before 2026-08-20, `getQuality()` instead warned and
  treated an unparseable QUAL as the missing value, silently conflating "genuinely absent" with "present but
  malformed" -- changed to match `convertElement`'s pre-existing behavior for the identical failure mode elsewhere.
  As of this fix, `getQuality()` returns `VcfFloat` (not `BigDecimal`) and calls `VcfFloat.parse()` directly,
  matching every other Float-typed field, so it now accepts VCFv4.3.tex line 100's Special Float values
  (`NAN`/`INF`/`INFINITY`) too. One side effect: the grammar-mismatch warning text for a value `BigDecimal`
  accepts but the Float grammar doesn't (e.g. `"5."`) changed from `"QUAL value '5.' does not match..."` to
  the generic `"Float value '5.' does not match..."`, since `getQuality()` no longer passes `"QUAL"` as a
  field-name parameter -- it's now identical to every other Float-typed field's warning wording. No test
  asserts the field-specific wording, so this was a deliberate, accepted simplification, not an oversight.
- **The same reserved-key `Type` check also runs eagerly at manual construction time**, not just lazily at read
  time, for `VcfPosition`/`VcfSample` alike -- `VcfPosition.checkReservedInfoTypes` (called only when a caller
  passes an already-split `ListMultimap` directly to `Builder.info(...)`/`VcfContext.newPosition`, never on the
  lazy `Builder.rawInfo(...)` path `VcfParser` itself exclusively uses) and `VcfSample.checkReservedFormatTypes`
  (called on every public construction path except the parser's own `trusted` fast path). Both reuse the exact
  same `InternalUtils.convertProperty(ReservedProperty, ...)` call the read-side getters use, purely as a
  validation probe, so they inherit the identical dispatch: a cardinality/range mismatch still only warns under a
  lenient context; an unparseable value still throws unconditionally. Construction-only by design (provably
  unreachable via `VcfParser`, matching this project's "provably cannot fire during genuine parsing" bar for
  every other unconditional-throw check) -- gives Principle 1 ("fail fast on direct, programmatic input") the
  same reach for a reserved key's declared `Type` that it already had for structural checks like
  `checkInfoEntries`/`checkNoStructuralDelimiter`.
- A **zero-length allele token inside an otherwise non-empty `GT` value** (e.g. `0/`, `/1`, `0//1`): warned
  ("GT \"{}\" contains an empty allele (VCF does not allow zero-length fields); treating it as the missing
  value '.'") and substituted with `.` in the returned `Genotype`, leaving the sample's own stored `GT` text
  unchanged. **This one is not `isLenient()`-aware:** `VcfSample.getGenotype`'s empty-allele branch calls
  `m_warnings.warn(...)` directly, never `warnOrThrow`, so it warns and recovers under a **strict**
  `VcfContext` too -- deliberately, and pinned by
  `VcfSampleTest.testGetGenotypeEmptyAlleleWarnsRatherThanThrowsUnderAStrictContext`. Dropping the token
  instead would silently change the call's ploidy, so `.` is substituted to preserve every allele's position
  (`GT` indexes into `ALT` positionally: `VCFv4.2.tex` line 217 / `VCFv4.3.tex` line 493 / `VCFv4.4.tex` line
  516 / `VCFv4.5.tex` line 615, "The allele values are 0 for the reference allele (what is in the REF field),
  1 for the first allele listed in ALT, 2 for the second allele list in ALT and so on."). This is a narrower
  scope than the strict section's `GT` bullet above, which covers a non-empty malformed/out-of-range index and
  a wholly-empty `GT` value; see `EMPTY_FIELD_HANDLING.md`'s row 19 for the full evidence, including bcftools
  1.23.1's own divergence (it hard-errors, exit code 8) and vcf_validator 0.10.2's interior-vs-trailing
  asymmetry.
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
  `VcfWriter.Builder.validateBeforeWrite()`. VCFv4.2.tex line 230 gives `PS` its own `Type` as "Non-negative
  32-bit Integer"; VCFv4.3.tex generalizes this to every Integer value (line 100, "Integer (32-bit, signed)";
  line 103, the values −2³¹ through −2³¹+7 are disallowed in VCF and BCF alike -- verbatim at VCFv4.4.tex lines
  100/103, VCFv4.5.tex lines 98/101). Applied via `InternalUtils.checkFilterLikeValue` for `FT` and
  `checkNonNegative32BitInteger` for `PS`. Both are `isLenient()`-aware on the read side (warns under a lenient
  `VcfContext`, throws `VcfFormatException` under a strict one, matching the dispatch `VALIDATION_PHILOSOPHY.md`
  documents as the universal contract); the write side always warns, regardless of the written object's own
  strict/lenient origin, per `AGENTS.md`'s Writer Validation Policy. See the next bullet for the general 32-bit
  range check that now covers every other Integer-typed value.
- Any Integer-typed INFO/FORMAT value (reserved or not) that is not representable as a 32-bit signed integer,
  or that collides with one of the eight BCF-reserved sentinel bit patterns (`VCFv4.3.tex:1708`/`:1710`,
  `0x80000000`-`0x80000007`): checked by `InternalUtils.checkInteger32BitRange`, applied unconditionally
  regardless of declared version for the same reason as `PS` above (VCFv4.2.tex states no general bound, but
  this is not a case where Integer's *meaning* differs across versions, only whether the spec bothered to
  state it). Wired into every Integer-typed read path -- `InternalUtils.convertProperty` for a reserved
  INFO/FORMAT key, `VcfSample.getProperty(VcfPosition, VcfMetadata, String)` for a non-reserved FORMAT key, and
  `VcfPosition.getInfo(VcfMetadata, String)` for a non-reserved INFO key (this last accessor's addition is what
  makes a non-reserved INFO key's declared `Type=Integer` checkable via a metadata-aware typed getter at all,
  the same role `getInfo(ReservedInfoProperty)` already played for a reserved key) -- and into both write-side
  diagnostic paths, `VcfWriter.validateInfoValue`/`validateFormatValue`. The read side is `isLenient()`-aware
  (warns under a lenient `VcfContext`, throws `VcfFormatException` under a strict one) -- this matches the
  dispatch `VALIDATION_PHILOSOPHY.md` documents as the universal contract, which every other reserved-key
  content check in this section (`FT`/`PS` above, `GP` and `AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF`/`CIGAR` below)
  now also follows; the write side always warns, regardless of the written object's own
  strict/lenient origin, per `AGENTS.md`'s Writer Validation Policy. Deliberately not excluded for `FORMAT/PS`:
  `checkNonNegative32BitInteger` above already flags every value this check would too, so under a lenient
  `VcfContext` a reserved-sentinel or overflowing `PS` value produces two warnings for one input -- accepted,
  matching this codebase's existing "multiple independent checks warn separately about the same defect"
  pattern (see `WONT_FIX.md`). Under a strict one, `checkNonNegative32BitInteger` runs first and throws
  before this check ever runs, so the caller sees only its message, never this check's more specific
  BCF-reserved-sentinel one. Closes the
  hts-specs conformance gap covered by `failed_body_info_integer_overflow.vcf`/`_underflow.vcf`/`_reserved.vcf`
  -- see `HTS_SPECS_BASELINE_STATUS.md`'s "INFO reserved/non-reserved Integer value outside the 32-bit signed
  range" entry and `VcfParserTest`'s three `testHtsSpecsInteger*FixtureWarnsOnTypedAccess` tests.
- Any INFO/FORMAT value (reserved or not, scalar or list) whose actual comma-split element count doesn't match
  its declared/reserved `Number` for the record's ALT allele count (and, for `Number=G`, the sample's ploidy):
  checked by `InternalUtils.warnIfWrongCardinality`, wired into `VcfPosition.getInfo(ReservedInfoProperty)`,
  `VcfPosition.getInfo(VcfMetadata, String)`, and `VcfSample.getProperty(VcfPosition, VcfMetadata, String)`.
  Includes, as one instance of this general check rather than a separate rule, a scalar-declared key (`Number=0`
  or `Number=1`) given a value containing a literal comma -- e.g. a `FORMAT/FT` value written as
  `"STD_FILTER,extra"` -- which previously had no read-side detection at all for a non-reserved/undeclared key,
  and silently kept only the first comma-piece with no diagnostic for a reserved one. The read side is
  `isLenient()`-aware (warns under a lenient `VcfContext` and keeps only the first comma-piece for a
  scalar-declared key, or the full list unchanged for a list-declared one; throws `VcfFormatException` under a
  strict one) -- this matches the dispatch `VALIDATION_PHILOSOPHY.md` documents as the universal contract, the
  same family as `FT`/`PS`/`GP`/`AC`/`AN`/`DP`/`END`/`MQ0`/`NS`/`AF`/`CIGAR` in this section; the write side
  (`VcfWriter.validateInfoValue`/`validateFormatValue`) always warns, regardless of the written object's own
  strict/lenient origin, per `AGENTS.md`'s Writer Validation Policy -- confirmed live (bcftools 1.23.1) that
  writing this shape unchanged is not itself unsafe: bcftools never splits or validates FORMAT/INFO value
  cardinality on read at all, so the full round trip (write, then re-parse) loses nothing at the raw/untyped
  level; the truncation is purely an artifact of this library's own typed accessor, independent of whether a
  round trip ever occurred. `VcfPosition`'s two `getInfo` overloads previously duplicated this same "scalar key
  given more than one value" logic as a separate, hand-written, always-warn message instead of routing through
  `warnIfWrongCardinality` -- unified into the one shared, now-`isLenient()`-aware check instead of a second,
  independently-drifting copy.
- A reserved `FORMAT/GP` value outside the interval `[0, 1]`, but **only under VCFv4.3 and later** -- VCFv4.3
  redefined `GP` from VCFv4.2's phred-scaled encoding to a 0-1 probability (VCFv4.3.tex line 491; see
  `ReservedFormatProperty.GenotypePosterior`'s own comment for the full citation), so no range check applies
  under VCFv4.2 and earlier, where any `Float` is a legitimate phred value. `GP` is `Number=G` and list-shaped,
  so each comma-separated element is checked independently, reusing the same unit-interval helper (and its two
  exemptions, a special `VcfFloat` value and an overlong value) as `INFO/AF` below. Checked both when a typed
  value is requested and by `VcfWriter.Builder.validateBeforeWrite()`; the read side is `isLenient()`-aware
  (warns under a lenient `VcfContext`, throws `VcfFormatException` under a strict one, once the version gate
  above is satisfied) like every other reserved-key content check in this section, the write side always warns.
- A read of the **removed** reserved key `GLE` under VCFv4.3 or later. VCFv4.3 removed it (VCFv4.3.tex line
  2168, "Removed unused and ill-defined GLE FORMAT tag"; present as a reserved key at VCFv4.1.tex line 208 and
  VCFv4.2.tex line 225), so from VCFv4.3 `GLE` has no reserved status at all: it is classified as an ordinary
  custom key (validated against its own `##FORMAT` declaration when declared, given the standard "no metadata
  declaration" treatment when not -- never this library's reserved-canonical `Number=G`/`Type=String`
  fallback), its value splits on commas and colons like any other key's, and it is not exempt from the
  `Number=G` value-cardinality check. Two public enum-key accessors still let a caller name the removed
  constant directly; both warn rather than failing silently, and neither throws (they are lookup-style queries
  whose `String`-keyed siblings are null-tolerant "not present" -- see `AGENTS.md`'s Public API Null-Safety
  Policy): `VcfSample.getProperty(ReservedFormatProperty)` (via
  `InternalUtils.convertProperty(ReservedProperty, ...)`, where the single check lives) warns and returns
  `null`, and `VcfSample.containsProperty(ReservedFormatProperty)` warns and returns `false`. Use the
  `String`-keyed overloads to reach a VCFv4.3+ custom key literally named `GLE`. Unchanged under VCFv4.2 and
  earlier.
- A reserved `FORMAT/PL`/`GL`/`GP`/`PP` value whose element count doesn't match the `Number=G` cardinality
  implied by the sample's ploidy and the record's ALT allele count. When `GT` is absent, ploidy can't
  otherwise be determined at all, but these four keys specifically default to assumed diploidy rather than
  skipping the check, since their own spec definition states that default explicitly -- no other `Number=G`
  field, reserved or not, has any such spec-stated default, so this is scoped to exactly these four keys.
  `GLE`/`CNP`/`CNL` are separately exempted from this check entirely despite each having `Number=G` under some
  version -- see `VCF_SPEC_AMBIGUITIES.md`'s "`CNP`'s `Number=G` vs. `GT`/`GL`/`PL`/`GP`/`PP`'s `Number=G`"
  entry. All three rationales are unrelated to each other, and two of the three exemptions are version-gated,
  in opposite directions: `GLE`'s applies under VCFv4.2 and earlier only, since VCFv4.3 removed the key; `CNL`'s
  applies under VCFv4.3 and later, since that is the version its own expected `Number` becomes `G` at all
  (VCFv4.2.tex line 308 declares it `Number=.`; VCFv4.3.tex line 678, the target, redeclares it `Number=G` --
  see `ReservedFormatProperty.CopyNumberLikelihood`'s own comment) -- **and only while the `Number` actually in
  force (a `##FORMAT` declaration's own stated `Number`, or the reserved canonical fallback) genuinely resolves
  to `G`**: a declaration that overrides `CNL` to a concrete count (e.g. `Number=4`) states a real, checkable
  cardinality, so `InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck` takes that resolved `Number` as
  a parameter and stops exempting the key in that case; `CNP`, reserved with `Number=G` at every version this
  library interprets, is exempted unconditionally -- not version-gated, and not gated on the resolved `Number`
  either, since a caller-declared override doesn't change what a `CNP` value structurally means. Also, any
  `FORMAT` key's value whose element
  count doesn't match its declared
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
  match VCF's canonical definition for that key. Which keys count as reserved is itself version-dependent for
  exactly one key: from VCFv4.3 a `##FORMAT=<ID=GLE,...>` is no longer measured against the historical
  `Number=G`/`Type=String` at all (see the removed-`GLE` entry above), so a non-canonical `GLE` declaration
  warns here under VCFv4.2 and earlier but is accepted without comment from VCFv4.3, where `GLE` is an ordinary
  custom key whose declaration is the only definition it has.
- An `##ALT` structural-variant ID (e.g. `<DEL:ME:ALU>`) that doesn't match VCF's reserved code hierarchy
  (DEL/INS/DUP/INV/CNV/BND and colon-delimited subtypes) — but only when the ID actually contains a colon; a
  colon-less ID (e.g. an IUPAC ambiguity code, or a `CN0`-through-`CN124`-style copy-number allele used by 1000
  Genomes) is not checked against that hierarchy at all, since colon-less custom codes have other spec-sanctioned,
  non-structural-variant uses this parser must not reject. A reserved code re-used as a subtype of another
  reserved code (e.g. `DUP:INV`) is a deliberate exception to bcftools -- see `VCF_SPEC_AMBIGUITIES.md`'s
  "Reserved structural variant code re-used as a subtype" entry.
### Metadata declaration IDs: additional edge cases

- Any of the seven ID-bearing declaration types' (`##contig`/`##INFO`/`##FORMAT`/`##FILTER`/`##SAMPLE`/`##ALT`/
  `##META`) `ID` that is present but blank or whitespace-only (`ID=` or `ID=   `) on an
  object that already exists in storage — as opposed to a declaration still being parsed, which is dropped
  before storage per the missing/blank-ID bullet above: warns (VCFv4.3.tex line 121, the same citation as
  that bullet). This covers a declaration built directly or live-mutated after construction, which the
  parser's own drop-before-storage check never sees. A whitespace-only `ID` falls under this bullet, not the
  whitespace-containing-`ID` bullet below (which fires only when trimming the `ID` would leave usable
  content).
- A `##contig`/`##INFO`/`##FORMAT`/`##FILTER`/`##SAMPLE`/`##ALT`/`##META`/`##PEDIGREE` declaration's `ID`
  containing whitespace (as opposed to being entirely blank/missing, handled by the two bullets above): warns
  uniformly across all eight types. The first seven extend `IdMetadata` and share its `init()` check;
  `##PEDIGREE` extends `BaseMetadata` directly instead (pre-4.3 it may legitimately have no `ID` at all), so
  `VcfMetadata.Builder.addPedigree` carries its own, equivalent check rather than inheriting one. See
  `VCF_SPEC_AMBIGUITIES.md`'s "Whitespace in `##FILTER`/`##SAMPLE` declaration IDs" entry (titled after the two
  types with the least direct spec support, but the decision covers all eight) for the full
  per-version spec/hts-specs-fixture/bcftools/vcf_validator comparison (`##META`/`##PEDIGREE` were not
  themselves part of that comparison, since neither yet routed through a whitespace check at the time -- each
  inherits the same rule once it gained one). `##ALT` IDs also separately warn on angle brackets (`<`/`>`),
  per VCFv4.2.tex line 105 -- an `##ALT`-specific restriction not extended to the other seven types, since no
  fixture or spec passage evidences it for them.
- Any of those same seven declaration types' `ID` containing a comma: warns, with no exception for `##ALT`
  (unlike angle brackets above) — a comma is never part of `##ALT`'s own colon-delimited structural-variant-code
  grammar or its colonless conventions (an IUPAC ambiguity code or a `CN0`-style copy-number allele) either.
  This follows directly from every `##INFO`/`##FILTER`/`##FORMAT`/`##ALT`/`##contig`/`##SAMPLE` worked example's
  own comma-delimited attribute-list syntax (and, for `##META`, the same `IdMetadata.init()` check those six
  share), not a per-type policy choice, so it needs no bcftools/vcf_validator corroboration the way the
  whitespace check above does.
- A `##FILTER` declaration's `ID` that is exactly `"0"` (VCFv4.3.tex line 360, "`0' is reserved and must not
  be used as a filter String") or that contains a semicolon (VCFv4.3.tex line 362, "String, no whitespace or
  semicolons permitted" -- restated verbatim at VCFv4.4.tex lines 376/378 and VCFv4.5.tex lines 425/427):
  warns. Both rules are already enforced for the FILTER data-line column itself (`VcfPosition`'s own FILTER
  checks); this closes the matching gap at declaration time, so a `##FILTER` declaration a real record could
  never legally use is flagged when it's declared, not left silently unusable. `##FILTER`-specific: not
  extended to `##ALT`/`##SAMPLE`, which share this class (`IdDescriptionMetadata`) but have no such
  column-level rule of their own. Whitespace and comma in a `##FILTER` ID are already covered by the two
  bullets above (shared with the other six ID-bearing types); this adds only the two rules those don't cover.

### Other lenient behavior

A `##fileformat` declaring a VCF 4.x version this library does not implement (`VCFv4.0` and below, `VCFv4.4`
and above) is warned about (once per parse or write -- see below) and interpreted under the nearest implemented
version — 4.1 for anything below it, 4.3 for anything above. The declared value itself is preserved verbatim
and re-emitted unchanged on write, so such a file still round-trips under its own declaration; the fallback
governs interpretation only. A structurally unusable value (not matching `VCFv4.<digits>`) still throws, in
both strict and lenient contexts — this is a well-formed declaration for an unimplemented version, not invalid
content. See `VcfVersion.resolve`.

`VcfParser` and `VcfWriter` each build their own `VcfContext`, but the two sides report this differently, and
both are unconditional — neither is gated on `validateBeforeWrite()`. On the parse side it is straightforward:
`VcfVersion.resolve` warns once per parse, in strict and lenient contexts alike. On the write side, `writeHeader`
resolves through `VcfVersion.resolveWithoutWarning`, which returns the fallback as a fact (resolved version plus
a "fell back" flag) instead of emitting it, and reports it itself later, in both write modes — deferred that way
because the context is built before `writeHeader`'s own later rejections (a failed validation, diagnostics-mode
only, and a line-terminator check, which runs in both modes) run, and warning at context-construction time
would report on output one of those rejections might still prevent from ever existing. The writer emits its
own, differently-worded message: the parser's wording ("Reading ##fileformat={} as VCF {} -- this library does
not implement {} semantics") is false for a writer, which emits the declaration verbatim rather than
interpreting it, so the write-side message says instead ("Writing ##fileformat={} verbatim, but its content was
accepted and normalized under VCF {} rules; validity under the declared version is not guaranteed") that the
output's content was accepted and normalized under the resolved version's rules and that its validity under the
declared version is not guaranteed. It names the version actually applied rather than hardcoding 4.3 — a
`VCFv4.0` declaration falls *up* to 4.1. It is emitted once per header that is actually written, after every
header rejection has been passed and immediately before the first header byte, so a rejected `writeHeader`
attempt followed by a corrected retry reports it once in total, not once per attempt (this also means a plain
parse-then-write round trip of a file declaring an unimplemented version logs two lines total — one from the
parse side, one from the write side — which is intended, not a duplicate: the two messages describe different
facts, about different data flow directions). Pinned by
`VcfWriterTest.testReportsAnUnimplementedDeclarationExactlyOnce`,
`testRejectedHeaderAttemptDoesNotReportTheUnimplementedDeclaration`, and
`VcfVersionTest.testResolveWithoutWarningReportsEveryFallbackRouteAsAFact`.

An empty ("zero-length") entry in a delimited record field — e.g. `ID=rs1;;rs2`, `ALT=T,`, `FILTER=q10;`,
`FORMAT=GT:DP:`, a sample value like `0/1:`, or `INFO=AD=1,,2` — is also lenient rather than strict when it can be
recovered without changing field alignment. VCFv4.3.tex line 39, "Zero length fields are not allowed, a dot
(`.`) must be used instead" (verbatim at VCFv4.4.tex line 39, VCFv4.5.tex line 37; the changelog restates it at
VCFv4.3.tex line 2159); VCFv4.2 is silent. See [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md) for the full
field-by-field table of what each case normalizes to and why.

Typed metadata accessors (e.g. `InfoMetadata.getType()` and `getNumber()`) are annotated `@Nullable` and return `null`
when the corresponding metadata was malformed.

Reserved `Float`-typed `INFO`/`FORMAT` properties (e.g. `INFO`'s `AF`/`MQ`, `FORMAT`'s `GL`/`GP` -- note that a
key reused on both sides isn't always the same `Type` on each; `FORMAT`'s own `MQ`, for example, is `Long`,
not `Float`) are converted to
[`VcfFloat`](src/main/java/org/pharmgkb/parser/vcf/VcfFloat.java), not a plain `BigDecimal`: `VCFv4.3.tex` (the
target) states it accepts `NAN`/`INF`/`INFINITY` (case-insensitive, optionally signed) in addition to a normal
decimal number -- special values a plain `BigDecimal` cannot represent at all. `VCFv4.2` doesn't define
`Float`'s literal grammar at all.

A `FORMAT` value of `.` (with one or more samples present) means no per-sample format fields at all for that
record; each sample is read as having zero properties, and any raw sample-column content there is ignored (with a
warning). See `VCF_SPEC_AMBIGUITIES.md`'s "`FORMAT=\".\"` meaning no per-sample fields" entry for the
bcftools-confirmed rationale.

A `CHROM` value or `##contig` `ID` containing a character outside VCFv4.3.tex:258-259's printable-ASCII
range (`[!-~]`) warns rather than throwing, deliberately unlike every OTHER `CHROM`/`##contig` character-set
check (the "Strict" section above): bcftools 1.23.1 itself only warns on this one (`[W::bcf_hrec_check]
Invalid contig name`), never rejects. See `VCF_SPEC_AMBIGUITIES.md`'s "CHROM / contig ID containing a
character outside the printable-ASCII range" entry.

## Rules applied at every declared version

This library targets VCFv4.3. The eight rules indexed below are all target-spec rules that this library
applies to *every* declared `##fileformat`, including `VCFv4.1` and `VCFv4.2` files that predate the rule --
none of them is version-gated. A version branch is reserved instead for a rule whose *meaning* genuinely
differs between versions and where both readings are valid (`GP`, `GLE`, `CNL`), plus two differently-shaped
cases -- `##PEDIGREE` requiring an `ID` at VCFv4.3+, a required-field rule on a declaration rather than a
value- or `Number`-meaning change (see its own entry in the lenient section above), and `##META` not existing
as a structured declaration type at all before VCFv4.3, gating the declaration type's existence rather than
one of its fields (see its own entry in the lenient section above too) -- see the entries for
those above, and `VALIDATION_PHILOSOPHY.md`'s "How the declared version is dispatched" for the mechanism.
Each rule is now tabled with the rest of its subject matter rather than gathered here as a separate citation
appendix:

- INFO/FORMAT tag-name grammar -- see the `##INFO`/`##FORMAT` `ID` bullet in "Direct/programmatic construction"
  above.
- Contig/CHROM character set -- see the `CHROM` character-set bullet in "File and record structure" above.
- Spaces in INFO values -- see the `INFO` value bullet in "File and record structure" above.
- Integer is 32-bit signed -- see the `FORMAT/PS` bullet and the general 32-bit range check bullet directly
  after it in "INFO/FORMAT/sample value content" above.
- Non-printable characters and line separators -- see the line-terminator bullet in "File and record structure"
  above.
- Zero-length fields are not allowed -- see the empty-entry paragraph in "Other lenient behavior" above, and
  [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md).
- Structured metadata IDs must be unique -- see the `VcfMetadata.Builder` duplicate-ID bullet in "File and
  record structure" above, and [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md).
- UTF-8, and no byte order mark -- see the new UTF-8/BOM bullet at the top of "File and record structure"
  above.

Three of these are unimplemented rules of the VCFv4.3 target, not merely gaps relative to a later version:

- A data-line INFO *key* is checked for whitespace and `;`/`=` but is not matched against
  `InternalUtils.INFO_PATTERN`, so a key like `1BAD` is accepted. No fixture in the 4.3 corpus demands this; it
  remains unimplemented.
- The wider C0 control-character range (VCFv4.3.tex line 81) is not scanned outside the specific
  tab/LF/CR/vertical-tab/form-feed checks named above, so a NUL or other C0 control elsewhere in a value
  survives undetected. No fixture in the 4.3 corpus demands this; it remains unimplemented.
- An unrecognized structured metadata line (a `##KEY=<...>` line for a key none of the eight modeled types
  use) is stored as one opaque raw string (`VcfMetadata.Builder.addRawProperty`) rather than tokenized as an
  attribute list, so neither VCFv4.3.tex line 129's "the only default field for such lines is the required
  ID field" nor line 121's per-type uniqueness rule is checked against it -- two identical `##FOO=<ID=x>`
  lines are both kept, and `##FOO=<Number=1>` (no ID at all) parses silently. This is deliberate, not an
  oversight: implementing it would mean partially attribute-list-tokenizing any unrecognized structured
  line purely to extract an ID, new parsing machinery that cuts against the specific reason unknown lines
  are kept opaque in the first place (a guaranteed lossless round trip with zero reinterpretation risk). No
  fixture in the 4.3 corpus demands this; it remains unimplemented.

Do not treat this re-framing as an implementation trigger for any of these gaps -- none has 4.3 fixture
pressure and all three stay unimplemented. (Formerly the second entry in this list, the general 32-bit Integer range check
did have fixture pressure and has since been implemented and removed from this list -- see the general 32-bit
range check bullet in "INFO/FORMAT/sample value content" above and `HTS_SPECS_BASELINE_STATUS.md`'s "INFO
reserved/non-reserved Integer value outside the 32-bit signed range" entry.)

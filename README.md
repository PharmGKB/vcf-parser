# Overview

[![Build Status](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml/badge.svg)](https://github.com/PharmGKB/vcf-parser/actions/workflows/build.yml)
[![codecov.io](https://codecov.io/github/PharmGKB/vcf-parser/coverage.svg?branch=main)](https://codecov.io/github/PharmGKB/vcf-parser?branch=main)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.pharmgkb/vcf-parser)

This is a streaming parser and writer for [VCF](http://en.wikipedia.org/wiki/Variant_Call_Format). It validates record
structure strictly but defers validation of non-structural content for performance — see
[Validation: strict vs. lenient](#validation-strict-vs-lenient) below; lenient (warn-and-recover) paths route their
warnings through an explicit sink — see [`VcfWarnings`](#vcfwarnings-explicit-warning-routing) below.

The main parser class ([`VcfParser`](src/main/java/org/pharmgkb/parser/vcf/VcfParser.java)) is responsible for reading all metadata and initial position data.  Then actual handling of each position line is delegated to an implementation of `VcfLineParser`.

Check out [`VcfParserTest.java`](src/test/java/org/pharmgkb/parser/vcf/VcfParserTest.java) for a quick and dirty view of it in action.

[`MemoryMappedVcfLineParser`](src/main/java/org/pharmgkb/parser/vcf/MemoryMappedVcfLineParser.java) is an example
`VcfLineParser` implementation, showing how to read an entire file into memory for constant-time record lookup; it
is not part of the core parser. VCF permits multiple records at the same locus, so each locus is stored as a list of
records, and every record seen at a locus is kept with no configuration needed. A repeated `ID`, by contrast, is not
a normal VCF occurrence, so it remains single-valued and `Builder.setDuplicateIdHandler` controls whether it's
rejected or which record wins.

**VCF version support:** The target specification is VCF 4.2. The parser accepts representable VCF 4.x input from
before and after that version. Where VCF versions differ or input is ambiguous, it uses VCF 4.2 semantics. Later 4.x
features that cannot be represented safely may be warned about, normalized, or rejected; they are not necessarily
specially interpreted.


## Validation: strict vs. lenient

The parser draws a deliberate line between the **structure of a VCF record**, which it validates strictly, and
**non-structural content** (including INFO, FILTER, FORMAT, sample values, and metadata declarations), which it may
validate lazily.

**Strict — throws `VcfFormatException`.** The parser rejects a file whose structure or mandatory record fields are
invalid:

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
- A `VcfSample` key or value set through its constructors or `putProperty` containing a colon or tab, which would
  otherwise add a spurious FORMAT sub-field or sample column when written back out. The one exception is a value for
  the key `GLE` (VCFv4.1/4.2's genotype-likelihoods-of-heterogeneous-ploidy key, removed from the spec in VCFv4.3+):
  its own spec example (e.g. `0:-75.22,1:-223.42,...`) is one opaque String that uses colons internally as part of
  its own encoding, not a delimiter between independent sub-fields; a colon in the key `GLE` itself, or a tab in its
  value, is still rejected.
- `VcfWriter.writeLine` is given a number of samples that disagrees with the header's declared sample count, any
  FORMAT/sample data at all when the header declares no samples, or an empty `REF` (which has no missing-value
  sentinel in the spec, unlike `ALT`/`ID`/`FILTER`): in every case the output would not be valid, re-parseable VCF.
- A typed metadata constructor given directly (e.g. `new InfoMetadata(id, ...)`, `new FormatMetadata(id,
  ...)`, `new ContigMetadata(id, ...)`, `new IdDescriptionMetadata(label, id, description)`) throws, *at
  construction time*, for every condition that class's own declaration-level checks would otherwise flag -- a
  missing/empty/whitespace-only `ID` (VCFv4.3.tex line 121), an `##INFO`/`##FORMAT` `ID` that doesn't match
  the required tag-name grammar (VCFv4.3.tex line 368/429: must start with a letter or underscore, followed
  only by letters, digits, underscores, or periods -- `##INFO` additionally allows the legacy value
  `1000G`), an invalid `Number`, a `Type=Flag`/non-zero-`Number` mismatch, a reserved property's Number/Type
  mismatch, or a malformed `##contig` URL. This is a
  property of the constructor only, not of the resulting object: calling `validate()` later on that same
  object (e.g. after mutating it through `getPropertiesRaw()`) always warns instead of throwing, the same as
  an object built via the lenient constructor -- see [`VcfWarnings`](#vcfwarnings-explicit-warning-routing)
  below for why. `VcfPosition`'s own constructor (see below) follows the identical construction-time-only
  rule for FILTER/INFO/FORMAT declaration-level mistakes. This only applies to direct/programmatic
  construction; a file parsed by `VcfParser` with the same malformed content is handled per the lenient
  section below, not rejected outright. A caller that wants today's warn-and-recover behavior for direct
  construction instead of a throw can use each metadata class's lenient `Map`-based constructor (or, for
  `VcfPosition`, its 11-argument lenient overload described below), which takes an explicit
  [`VcfWarnings`](#vcfwarnings-explicit-warning-routing) instance to route the warnings to (see that section
  below).
- `new VcfPosition(...)`'s 10-argument constructor throws, at construction time, for FILTER combining `PASS`
  with another code, a duplicate value within `ID` or `FILTER`, an INFO value containing `=`, an empty FORMAT
  sub-field name, or an empty ID/ALT/FILTER entry -- the same conditions the lenient section below describes
  as warned-and-recovered when parsed; its
  own `validate()` method always warns instead, matching the metadata classes above. A separate 11-argument
  overload, taking an explicit [`VcfWarnings`](#vcfwarnings-explicit-warning-routing) instance as its last
  parameter, restores exactly that warn-and-recover behavior; this is the overload `VcfParser` itself uses.
- `VcfMetadata.Builder` throws for a missing/duplicate declaration ID, an invalid `##ALT` structural code, an
  empty `assembly`/`pedigreeDB` value, or an `assembly`/`pedigreeDB`/other generic property value shaped like
  an empty or no-`=` bracketed attribute list (e.g. `<>` or `<http://foo.com>`), unless built with
  `.lenient(VcfWarnings)`, which restores today's warn-and-keep-first behavior (matching bcftools; see
  [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md)) routed to the supplied instance.
  `VcfParser`'s own internal use always calls `.lenient(...)`, so parsing a file's behavior is unchanged by
  any of this.

These checks run when a `VcfPosition` is constructed (including by the parser). Its setters and mutations made
through its accessors (e.g. `getAltBases()`, `getFilters()`) do *not* re-run them, to support transformation
pipelines that mutate a position in place; call `VcfPosition.validate()` after such mutations to re-check validity —
this also re-normalizes a lone `.` `FILTER` value left by such a mutation, matching construction. `PASS` is never
normalized away -- it is itself the sole representation of a passing FILTER (see `VcfPosition.getFilterStatus()`).

One exception: `Builder.rsidsOnly()` is a true pre-filter, not a post-parse one -- a data line whose ID doesn't
match an RSID is treated as though it were never in the file at all, the same as if it had been removed by an
external tool (e.g. `bcftools view`) beforehand (see its Javadoc). `VcfPosition` is never constructed for a
discarded line, so none of the structural-floor checks above apply to it -- not just a negative `POS`,
`CHROM`'s character-set rules, invalid `REF`/`ALT`, or the `FILTER`/`FORMAT` checks, but not even confirming
`POS` parses as a number at all, or CHROM/POS ordering. Only the column-count check and the check that no
fixed field is empty still apply to every line regardless, since both are needed just to read the ID field in
the first place. A `VcfTransformation`-based filter can't replicate this: it only sees a record after
`VcfPosition` has already been fully constructed and validated, so a structurally invalid record `rsidsOnly()`
would silently discard instead aborts parsing before a transformation ever gets a chance to reject it.

Mutating `position.getFormat()` (adding or removing a FORMAT key) does *not* automatically update that line's
`VcfSample` properties -- the two must be kept in agreement by the caller. If they diverge, the default writer path
warns and reconciles by key name: a FORMAT key with no corresponding sample value is substituted with `.`, and a
sample property not declared in FORMAT is dropped. `VcfWriter.Builder.validateBeforeWrite()` still substitutes `.`
for the first case, but rejects the second (a sample property not declared in FORMAT) with `VcfFormatException`.

`writeHeader()` may not be called more than once, `writeLine()` may not be called before `writeHeader()`, and every
`writeLine()` call must be given the same `VcfMetadata` object `writeHeader()` was given -- these three checks are
enforced unconditionally, in both the default and `validateBeforeWrite()` modes, since each is a cheap, one-time
check rather than a per-record cost. A stricter check -- that the same `VcfMetadata` object hasn't been mutated in
place since `writeHeader()` was called (e.g. via `addInfo()`/`removeInfo()`) -- is `validateBeforeWrite()`-only,
since detecting it requires rebuilding the entire header on every `writeLine()` call, a real per-record cost the
default path avoids.

The default writer path is optimized for data written directly after parsing. It preserves parser-normalized values and
maintains record and sample-column structure without full revalidation. If data has been mutated and a full structural
validity and diagnostic check is required, build the writer with `VcfWriter.Builder.validateBeforeWrite()`. In that
mode, structurally invalid output is rejected and detected semantic non-compliance is reported with a warning. This
mode also enforces that every `INFO`/`FORMAT`/`FILTER`/`ALT`/`contig`/`SAMPLE` metadata entry's own `ID` property must
still match the key it's stored under in `VcfMetadata`'s maps (mutating an entry's raw `ID` through
`getPropertiesRaw()` -- changing it to a different value, or removing it entirely -- without it being reflected in
the map key would otherwise make the header declare a different (or missing) ID than what records reference by that
entry's map key).

The writer detects a write failure (disk full, broken pipe, or an encoding failure) and reports it as an
unchecked `java.io.UncheckedIOException`, but only checks periodically (every 1000 lines) and at
`writeHeader()`/`close()` -- not after every `writeLine()` call, to avoid forcing a flush past the writer's
internal buffer on every line. A failure detected mid-stream releases the underlying resource immediately, as
part of the same detection, rather than leaving it open until some later `close()` call. Calling `close()` is
still required to guarantee that all remaining buffered output is flushed and that a failure not yet caught by a
periodic check is reported. A detected write failure, or simply closing the writer at all (even cleanly, with no
error), permanently invalidates the `VcfWriter` (and, transitively, a `TransformingVcfLineParser` built on it):
`isValid()` becomes `false`, and any further `writeHeader()`/`writeLine()` call throws `IllegalStateException`
instead of attempting to write again -- retrying after a write failure, or reusing a writer after closing it, is
never safe, since the underlying writer may already be in a partially-written or otherwise inconsistent state, or
simply already released. `close()` itself is idempotent: calling it again after the first call has no effect,
matching `java.io.Closeable#close()`'s own documented contract.

**Lenient — warns when encountered or accessed and preserves usable data.** Non-structural content may be accepted
initially and validated only when the relevant getter or typed conversion is used. A metadata declaration
(`##INFO`, `##FORMAT`, `##contig`, `##FILTER`, `##ALT`, ...) that is well-formed as a declaration -- wrapped in
angle brackets -- but has malformed *attribute values* inside it warns rather than throws; most are still stored
as-is, with this exception (a declaration missing its angle-bracket wrapper entirely is a structural failure and
still throws -- see the strict section above):

- A declaration missing its `ID`, or with an empty/whitespace-only `ID=` — every VCF structured metadata line
  requires an ID (VCFv4.3.tex line 121); the parser warns and drops the whole declaration rather than storing it.
  See [`DUPLICATE_METADATA_ID_HANDLING.md`](DUPLICATE_METADATA_ID_HANDLING.md) for the full per-type
  bcftools/vcf_validator comparison and the empty-vs-missing rationale.
- A missing or invalid `Number`, `Type`, or `Description`; an unquoted `Description`; or `Type=Flag` with a `Number`
  other than `0`. An unparseable `Type` leaves the metadata's type `null`. A non-reserved `##FORMAT` declaration
  missing `Number` is treated as `Number=.` (list-shaped) when its value is later read via
  `VcfSample.getProperty(VcfPosition, VcfMetadata, String)`, uniformly regardless of `Type` -- matching bcftools
  1.23.1's own "Assuming '.'" behavior for the identical case -- rather than the scalar interpretation this
  library previously (incorrectly) forced.
- An `##INFO`/`##FORMAT` `ID` that doesn't match the required tag-name grammar (the same grammar the strict
  section above enforces at construction time): warned, not dropped -- the declaration is still stored and
  usable. `##FILTER`/`##SAMPLE`/`##ALT` declaration IDs are not held to this stricter grammar (only
  `IdMetadata`'s shared whitespace/comma check above applies to them), confirmed directly against bcftools
  1.23.1, which accepts the identical character in a `##FILTER`/`##SAMPLE`/`##ALT` ID silently.
- A header/metadata sample-count mismatch, or a sample named in the header but absent from the metadata: warned
  during parsing, and separately by `VcfWriter.Builder.validateBeforeWrite()`'s `writeHeader()` (checked once,
  since `#CHROM`'s sample columns can't change after a `VcfMetadata` is built).
- INFO, FILTER, FORMAT, and sample content that is semantically malformed but can be preserved or normalized safely.
- A non-numeric `QUAL`: the column itself is still well-formed, so this is treated as the missing value rather than
  a structural failure.
- A reserved `FORMAT/FT` value that doesn't follow the FILTER-like grammar (`PASS`, `.`, or a semicolon-separated
  list of codes with no whitespace, none of which may be combined with `PASS` or `.`), or a reserved `FORMAT/PS`
  value that isn't a non-negative 32-bit integer: both constraints go beyond what `FT`'s/`PS`'s declared `Type` can
  express, so they're checked separately, both when a typed value is requested and by
  `VcfWriter.Builder.validateBeforeWrite()`.
- A reserved `FORMAT/PL`/`GL`/`GP`/`PP` value whose element count doesn't match the `Number=G` cardinality
  implied by the sample's ploidy and the record's ALT allele count. When `GT` is absent, ploidy can't
  otherwise be determined at all, but these four keys specifically default to assumed diploidy rather than
  skipping the check, since their own spec definition states that default explicitly -- no other `Number=G`
  field, reserved or not, has any such spec-stated default, so this is scoped to exactly these four keys.
  (`GLE`/`CNP` are separately exempted from this check entirely, despite declaring `Number=G` themselves:
  neither key's "G" describes this ploidy-derived ordering -- `GLE`'s value isn't a comma-delimited list at
  all, and `CNP`'s own list is indexed by "potential copy number," a dimension unrelated to ploidy/ALT allele
  count; `CNL` is unaffected by this exemption since its own declared `Number` is `.`, not `G`, under this
  library's VCFv4.2 target.) Also, any `FORMAT` key's value whose element count doesn't match its declared
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
- A negative value for the reserved `INFO` keys `AC`, `AN`, `DP`, `END`, `MQ0`, or `NS`, or an `AF` value outside
  the interval `[0, 1]`, or a reserved `INFO/CIGAR` value that doesn't match a lone `*` or one or more
  `<length><operation>` pairs: same treatment as `FT`/`PS` above -- these constraints go beyond what each key's
  declared `Type` can express, so they're checked separately, both when a typed value is requested and by
  `VcfWriter.Builder.validateBeforeWrite()`.
- A `FILTER` value combining `PASS` with another code (e.g. `STD_FILTER;PASS`): VCFv4.2 only implies `PASS` is
  exclusive, without stating it as a hard rule, and hts-specs' own `complexfile_passed_000.vcf` fixture combines
  them and is expected to parse; confirmed accepted silently by bcftools 1.23.1 and valid per vcf_validator
  0.10.2.
- A duplicate value within a single `ID` or `FILTER` field (e.g. `ID=rs1;rs1`, `FILTER=hp23;hp23`): VCFv4.2 is
  silent on this for both fields; VCFv4.3 adds an identical "duplicate values not allowed" clause to both, but
  bcftools 1.23.1 and vcf_validator 0.10.2 both accept either silently. Both entries are kept as-is (no
  deduplication) -- neither field is looked up by key or referenced positionally by any other field, so a
  duplicate causes no data loss or misalignment.
- An INFO value containing `=` (e.g. `DP=1=2`), or a duplicate top-level INFO key (e.g. `DP=1;DP=2`): VCFv4.2's INFO
  grammar note technically disallows the former, and VCFv4.3 explicitly disallows the latter, but bcftools 1.23.1
  accepts both silently — splitting an `=`-containing value on the first `=` only (same as this parser), and
  keeping only the first occurrence of a duplicate key rather than merging the two into a list. This parser matches
  that bcftools tolerance rather than being stricter than it, consistent with the empty-field policy below.
- More than one comma-separated raw value for a scalar (`Number=0`/`1`) reserved `INFO` key (e.g. `AA=1,23`): a
  typed read of that key warns and keeps only the first value, the same "keep the first, warn about the rest"
  treatment as a duplicate top-level `INFO` key above.
- A declared `Number`/`Type` for a well-known reserved `INFO`/`FORMAT` key (e.g. `AC`, `AN`, `GT`, `PL`) that doesn't
  match VCF's canonical definition for that key.
- An `##INFO`/`##FORMAT`/`##ALT`/`##FILTER`/`##SAMPLE` declaration whose attributes are out of the canonical
  `ID, Number, Type, Description, Source, Version` order every spec example uses.
- An `##ALT` structural-variant ID (e.g. `<DEL:ME:ALU>`) that doesn't match VCF's reserved code hierarchy
  (DEL/INS/DUP/INV/CNV and colon-delimited subtypes) — but only when the ID actually contains a colon; a colon-less
  ID (e.g. an IUPAC ambiguity code, or a `CN0`-through-`CN124`-style copy-number allele used by 1000 Genomes) is not
  checked against that hierarchy at all, since colon-less custom codes have other spec-sanctioned, non-structural-
  variant uses this parser must not reject.
- Any of those same six types' `ID` that is present but blank (`ID=`) on an object that already exists in
  storage — as opposed to a declaration still being parsed, which is dropped before storage per the missing/
  blank-ID bullet above: warns (VCFv4.3.tex line 121, the same citation as that bullet). This covers a
  declaration built directly or live-mutated after construction, which the parser's own drop-before-storage
  check never sees.
- A `##contig`/`##INFO`/`##FORMAT`/`##FILTER`/`##SAMPLE`/`##ALT` declaration's `ID` containing whitespace (as
  opposed to being entirely blank/missing, handled by the two bullets above): warns. VCFv4.2/4.3 state this explicitly
  only for `##ALT` (VCFv4.2.tex line 105); hts-specs' own `failed_meta_contig_001.vcf` (4.1/4.2/4.3) and
  `failed_meta_sample_001.vcf` (4.3 only) fixtures extend it to `##contig`/`##SAMPLE`, bcftools 1.23.1 warns
  (`Invalid tag name`/`Invalid contig name`) for `##INFO`/`##FORMAT`/`##contig` specifically, and vcf_validator
  0.10.2 rejects all five uniformly. For `##FILTER`/`##SAMPLE` specifically, bcftools 1.23.1 accepts whitespace
  silently (no dedicated header check exists for either), directly contradicting hts-specs' own
  `failed_meta_sample_001.vcf` fixture — treated as a bcftools coverage gap rather than a considered position, so
  the hts-specs fixture and vcf_validator's uniform treatment govern instead. `##ALT` IDs also separately warn on
  angle brackets (`<`/`>`), per the same VCFv4.2.tex line 105 sentence — an `##ALT`-specific restriction not
  extended to the other five types, since no fixture or spec passage evidences it for them.
- Any of those same six declaration types' `ID` containing a comma: warns, with no exception for `##ALT` (unlike
  angle brackets above) — a comma is never part of `##ALT`'s own colon-delimited structural-variant-code grammar
  or its colonless conventions (an IUPAC ambiguity code or a `CN0`-style copy-number allele) either. This follows
  directly from every `##INFO`/`##FILTER`/`##FORMAT`/`##ALT`/`##contig`/`##SAMPLE` worked example's own
  comma-delimited attribute-list syntax, not a per-type policy choice, so it needs no bcftools/vcf_validator
  corroboration the way the whitespace check above does.

An empty ("zero-length") entry in a delimited record field — e.g. `ID=rs1;;rs2`, `ALT=T,`, `FILTER=q10;`,
`FORMAT=GT:DP:`, a sample value like `0/1:`, or `INFO=AD=1,,2` — is also lenient rather than strict when it can be
recovered without changing field alignment. See [`EMPTY_FIELD_HANDLING.md`](EMPTY_FIELD_HANDLING.md) for the full
field-by-field table of what each case normalizes to and why.

Typed metadata accessors (e.g. `InfoMetadata.getType()` and `getNumber()`) are annotated `@Nullable` and return `null`
when the corresponding metadata was malformed.

Reserved `Float`-typed `INFO`/`FORMAT` properties (e.g. `AF`, `MQ`, `GL`) are converted to
[`VcfFloat`](src/main/java/org/pharmgkb/parser/vcf/model/VcfFloat.java), not a plain `BigDecimal`: VCFv4.2 doesn't
define `Float`'s literal grammar, and VCFv4.5 clarifies it accepts `NAN`/`INF`/`INFINITY` (case-insensitive,
optionally signed) in addition to a normal decimal number — values a plain `BigDecimal` cannot represent at all.

A `FORMAT` value of `.` (with one or more samples present) means no per-sample format fields at all for that
record; each sample is read as having zero properties, and any raw sample-column content there is ignored (with a
warning). VCFv4.2 through 4.5 are all silent on this exact case — FORMAT is only described as required "if
genotype information is present" — but bcftools 1.23.1 accepts and correctly interprets it (verified directly:
`bcftools query` returns the missing value for such a sample's `GT` regardless of that sample's own raw column
content, and `bcftools view` round-trips it as `.`), so this parser and writer both treat it as valid, matching
bcftools 1.23.1 behavior.


## `VcfWarnings`: explicit warning routing

Every lenient (warn-and-recover) code path above needs somewhere to send its warning. By default, that's
this library's shared SLF4J logger (`org.pharmgkb.parser.vcf.VcfWarnings`) -- but a caller can redirect it
explicitly by constructing a [`VcfWarnings`](src/main/java/org/pharmgkb/parser/vcf/VcfWarnings.java) instance
(backed by any `Writer`, or via the `openFile(Path)` convenience factory) and passing it into whichever
lenient constructor/`Builder.lenient(...)` call it's using. `VcfParser.Builder.warningsToFile()` and
`VcfWriter.Builder.warningsToFile()` build and manage one of these internally for their own parse/write call --
most callers never need to construct a `VcfWarnings` by hand at all.

A `VcfPosition`/`VcfSample`/metadata declaration's own warning destination can be redirected after
construction via `setWarningsSink(VcfWarnings)` -- `VcfWriter` uses this itself, claiming each object for its
own destination immediately before writing it, so a lazy warning fired during that write is captured even if
the object was originally parsed or built under a different destination (or none at all).


## hts-specs conformance test

`./gradlew htsSpecsTest` runs this parser and writer against
[hts-specs](https://github.com/samtools/hts-specs)' official VCF conformance fixture suite
(`test/vcf/{4.1,4.2,4.3,4.5}/{passed,failed}`), fetched at build time and pinned to a specific commit
(`htsSpecsPinnedSha` in `build.gradle`). It runs five checks:

- `parser-no-throw`: every `passed/` fixture (all versions) parses without error.
- `parser-throw-or-warn`: every `failed/` fixture (for versions up to this library's VCFv4.2 target) either throws
  `VcfFormatException` or logs a warning — this library is deliberately more permissive than strict compliance for
  non-structural violations, so a `failed/` fixture parsing without error is not itself a failure; parsing it with
  *no signal at all* is.
- `parser-warn-content`: for a `failed/` fixture with more than one real data record, the captured warning content
  must match a specific, hand-verified expected substring, not merely "some warning fired" — a warning fired for
  the wrong reason would otherwise pass `parser-throw-or-warn` undetected. Expected substrings are catalogued in
  `HtsSpecsConformanceTest.EXPECTED_WARNING_SUBSTRINGS`; see that constant's own comment for the maintenance
  protocol for adding an entry.
- `writer-semantic` and `writer-bytediff`: every `passed/` fixture (all versions) is parsed, written back out via
  `VcfWriter`, and re-parsed; the round trip is compared both semantically (parsed data equal) and as raw text
  (byte-for-byte equal, ignoring line-ending differences).

Every check's outcome is compared against a committed baseline file
(`src/test/resources/org/pharmgkb/parser/vcf/spec/hts-specs-baseline.tsv`): a run fails only on a NEW divergence
(a fixture/check-type pair not yet in the baseline) or a STALE entry (baselined but no longer reproduces) — an
already-accepted divergence never fails the build. One exception: a multi-line `failed/` fixture with no entry in
`EXPECTED_WARNING_SUBSTRINGS` fails `parser-warn-content` unconditionally via a direct assertion failure, bypassing
the baseline mechanism entirely — it can never be suppressed by `./gradlew htsSpecsUpdateBaseline`. This is
deliberate: it's what makes the harness fail loudly on a fixture a future hts-specs update adds, rather than
silently missing it. A fully green `htsSpecsTest` run means current behavior exactly
matches the committed baseline. After a deliberate behavior change, run `./gradlew htsSpecsUpdateBaseline` to
regenerate the baseline from current results, then review the `git diff` of that file before committing — removed
entries mean improved behavior, added entries mean a new divergence is being accepted.

This task requires network access (to `github.com`) and is **not** part of the required `./gradlew test` check,
which stays network-free. The pinned commit is bumped deliberately, not automatically; `htsSpecsCheckout` (which
`htsSpecsTest` depends on) prints a warning if hts-specs has upstream changes to `test/vcf` that the current pin
doesn't include.

For the current baseline's contents — every fixture this library still doesn't detect as invalid, or doesn't
round-trip byte-identically, along with each entry's status and rationale — see
[`HTS_SPECS_BASELINE_STATUS.md`](HTS_SPECS_BASELINE_STATUS.md).

Every `htsSpecsTest`/`htsSpecsUpdateBaseline` run writes two (gitignored, not committed) reports under
`build/reports/hts-specs-conformance/`: `summary.md` (counts by version x check type) and `findings.tsv` (only
NEW/STALE rows — empty except for the header on a clean run).


## bcftools dependency test

`./gradlew bcftoolsDependencyTest` runs `BcftoolsDependencyTest` against a real, installed `bcftools` binary
(version 1.23.1) to verify every decision this library has made based on bcftools's specific current behavior
— the same decisions cited throughout `VCF_SPEC_AMBIGUITIES.md` and elsewhere as "confirmed live with
bcftools." Like `htsSpecsTest`, it is opt-in and is **not** part of the required `./gradlew test` check.

- `testBcftoolsIsExpectedVersion` checks the installed `bcftools --version` output against the pinned
  `1.23.1` independently of every other test in the class; a version mismatch here does not block the other
  tests from running, since seeing exactly which specific behaviors changed under a new bcftools version is
  the point of the suite.
- A missing or unlaunchable `bcftools` binary, by contrast, fails the whole class loudly via a `@BeforeAll`
  check — deliberate, since the task is already opt-in, so there's no risk of breaking an environment that
  never invited this check, and a silent skip could be mistaken for "everything passed."
- The task does not require network access: each test shells out only to a local `bcftools` binary on
  `PATH`, never to a remote host.

For the index of every decision this test class verifies, see
[`BCFTOOLS_VERSION_DEPENDENCIES.md`](BCFTOOLS_VERSION_DEPENDENCIES.md).


## Get It

### Maven

```xml
<dependencies>
  ...
  <dependency>
    <groupId>org.pharmgkb</groupId>
    <artifactId>vcf-parser</artifactId>
    <version>0.3.1</version>
  </dependency>
  ...
</dependencies>
```

### Non-Maven

You can download jars from the [Central Maven Repository](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.pharmgkb%22%20a%3A%22vcf-parser%22).

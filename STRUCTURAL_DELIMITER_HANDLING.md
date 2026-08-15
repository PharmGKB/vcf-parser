# Structural delimiter handling in metadata property values

This document describes `VcfMetadata.Builder`'s construction-time rejection of a property key or value that
would corrupt the `##KEY=<key=value,key=value,...>` attribute list it is emitted into, and the deliberately
narrower rule that applies instead to a generic `##key=value` property added via
`Builder.addRawProperty(String, String)`.

Unlike most rules described in `EMPTY_FIELD_HANDLING.md`/`DUPLICATE_METADATA_ID_HANDLING.md`, this check
is **not** gated by `VcfContext.isLenient()`. It throws `VcfFormatException` unconditionally, in both a strict
and a lenient context -- see "Severity: unconditional throw" below for why. It is not the only such exception:
`DUPLICATE_METADATA_ID_HANDLING.md`'s `replaceXxx` missing-replace-target rule (`VALIDATION_REFERENCE.md`'s
replace bullet) is also unconditional, for an unrelated reason -- a missing replace target is a programming
error, not content a warning could recover from.

## The defect

`VcfWriter` has no encoding step. `getAllProperties` (`VcfWriter.java:1154-1168`) emits each `##KEY=<...>` line
by walking `BaseMetadata.getPropertiesRaw()` and appending every key and value **verbatim**, separated by `,`
and `=` with no quoting and no escaping. Output is therefore well-formed only if the encoding
was already valid when the declaration was **constructed** -- and before this check existed, construction did
not ensure that. A caller could build a `ContigMetadata` with an `assembly` value such as `a,b`, write it on
the default path, and silently get back something other than what was passed in.

## The corruption matrix

Verified empirically: constructing a `ContigMetadata` with the given `assembly` value, writing it on the
default (non-`validateBeforeWrite()`) path, and re-parsing the output.

| Value passed in | Construct | Default write | Re-parse yields | Verdict |
|---|---|---|---|---|
| `B36` | ok | ok | `B36` | correct |
| `"a,b"` (quoted) | ok | ok | `"a,b"` | correct |
| `"a=b"` (quoted) | ok | ok | `"a=b"` | correct |
| `a,b` | ok | ok | **`a`** | silently **truncated** |
| `a=b` | ok | ok | **attribute absent** | silently **deleted** |
| `a"b` | ok | ok | **`a"b,md5=deadbeef,taxonomy=tx`** | silently **absorbs every following attribute** |
| `a\nb` | **throws** | -- | -- | already correct (line terminator, see below) |

The last row is not new: a line terminator (`\n`/`\r`) is already rejected at construction by
`InternalUtils.checkNoLineTerminator`, independent of this check -- see `VALIDATION_REFERENCE.md`'s
line-terminator bullet. The other three "silently ..." rows are the actual defect this check closes.

## Severity ranking: unescaped quote > `=` > comma

**Severity runs opposite to intuition.** A comma looks the most dangerous (it is the attribute-list
delimiter), `=` looks structural (it separates key from value), and a stray quote looks cosmetic. The measured
outcome above inverts that:

- **A comma** splits one attribute into two at the write-time serialization. On re-parse,
  `InternalUtils.splitTopLevel` (the top-level comma splitter used by `extractPropertiesFromLine`,
  `InternalUtils.java:276-288`) treats the piece after the comma as a second, separate attribute. That second
  piece has no `=` in it, so `splitProperty` (`InternalUtils.java:373-379`) throws, `extractProperties`
  catches it, warns, and drops it. Net effect: the value is truncated at the comma.
- **An `=`** makes `splitProperty`'s `splitTopLevel(prop, '=')` return three parts where it requires exactly
  two (`InternalUtils.java:374-377`, `"There were " + (parts.size() - 1) + " equals signs for: " + prop`). It
  throws, `extractProperties` catches it, warns, and drops the **whole attribute** -- not just the part after
  the `=`.
- **An unescaped quote** is the worst of the three, and is **position-dependent**. `splitTopLevel` tracks
  whether it is "inside a quoted value" with a boolean toggle that flips on every unescaped `"` it sees. A
  stray, unescaped quote inside an otherwise-unquoted value leaves that toggle switched on for the rest of the
  declaration, so every comma from that point on is treated as *inside* a quoted value rather than as an
  attribute separator -- every subsequent attribute is swallowed whole into the value of the one that had the
  stray quote. Concretely: emitting
  `##contig=<ID=1,length=100,assembly=a"b,md5=deadbeef,taxonomy=tx>` re-parses to keys `[ID, length, assembly]`
  only, with `md5` and `taxonomy` both `null` -- not merely truncated or dropped, but **absent from the
  declaration entirely**, indistinguishable from a file that never declared them.

**The unescaped-quote case is the one a careless test would miss.** A test that places the quote-bearing value
in the *last* attribute of a declaration would falsely pass: with nothing left to absorb, the "inside a
quoted value" toggle never gets a chance to swallow a later attribute, and re-parsing looks correct by
accident. `MetadataPropertyEncodingTest` guards against exactly this
(`testParsingStillRecoversAnUnescapedQuoteWithAWarning`'s third `RecoveredCase`, and see "Reference table"
below) by using a **non-final** attribute as the probe.

## Severity: unconditional throw

Most construction-time checks `VcfMetadata.Builder` runs are dispatched through `BaseMetadata.warnOrThrow` --
throwing under a strict `VcfContext` and warning-and-recovering under a lenient one, per
`VALIDATION_PHILOSOPHY.md`. This check is not, and it is not alone in that: `InternalUtils.checkNoLineTerminator`,
`replaceXxx`'s missing-replace-target check (`requirePresentForReplace`), and the cross-`VcfContext`-instance
check (`checkSameContext`) are all also construction-time checks that throw unconditionally rather than going
through `warnOrThrow`. This check joins that group for two reasons:

- This is structural corruption, which `AGENTS.md`'s Parser Validation Policy already classifies as an error
  rather than a warn-and-recover case ("invalid delimiter handling that changes field alignment").
- A lenient *warning* would leave the actual defect in place. The whole point of this check is that a lenient
  caller currently emits output that silently loses data on the default write path -- warning at construction
  time and then proceeding to store the value anyway would fix nothing; the corruption would still happen
  later, at write time, exactly as it does today.

## Where the check runs

`InternalUtils.checkMetadataPropertyEncoding(String kind, Map<String, String> properties)`
(`InternalUtils.java:1200-1213`) is the shared predicate, reused (as `VcfWriter.validateMetadataProperties`'s
sole delegate, `VcfWriter.java:673-675`) rather than duplicated between the write-time diagnostics check and
this construction-time one. It rejects:

- a property **key** that is `null`, empty, or contains `,`, `=`, or an unescaped `"`;
- an **unquoted** value containing `,`, `=`, or an unescaped `"` -- `InternalUtils.isQuoted`
  (`InternalUtils.java:1215-1232`) and `InternalUtils.containsUnescapedQuote` (`InternalUtils.java:1245-1255`)
  are the two helpers behind that, checked independently because a value can contain a stray unescaped `"`
  without looking quoted at all (no leading/trailing `"`).

**Only three of those four shapes are new here.** A `null` key is already rejected during construction, for
every construction route, by `BaseMetadata.validateProperties()` (`BaseMetadata.java:141-145`, "Metadata
contains a null property name") -- this fires from `BaseMetadata`'s `Map`-based constructor
(`BaseMetadata.java:78-91`, called at line 90) before a declaration is ever handed back to the `Builder`, so
the `Builder`'s own check never sees a `null` key: it is genuinely unreachable there. Reusing
`checkMetadataPropertyEncoding`'s key predicate wholesale keeps that branch anyway, as harmless redundancy
that lets both call sites share one implementation -- but a test asserting on a `null` key is pinning
`validateProperties`'s message, not this check's (see `testANullKeyIsRejectedByConstructionNotByThisCheck`).

**The check runs against the constructed declaration's `getPropertiesRaw()`, never against the `Builder`
method's own arguments.** Every one of the six keyed types' `addXxx(id, description, ...)` typed overloads
builds the declaration object first (e.g. `addAlt(String, String)` constructs an `IdDescriptionMetadata` from
its two arguments), and only then calls `InternalUtils.checkMetadataPropertyEncoding` against that object's
own `getPropertiesRaw()` -- not against `id`/`description` directly (`VcfMetadata.java:701-706`, and the same
shape repeated for the other five kinds).

This is deliberate, not incidental: `BaseMetadata.putAndQuoteProperty` (`BaseMetadata.java:240`) quotes
`Description`/`Source`/`Version`/`species` on the way into several of the typed constructors, so checking the
*arguments* would reject a value the constructor was about to make safe by quoting it. Checking the
*stored result* sees exactly what the writer will actually emit. `MetadataPropertyEncodingTest`
(`testAddTypedAcceptsADelimiterInContigsOwnAutoQuotedField`,
`testAddTypedAcceptsADelimiterInAnAutoQuotedField`) pins this directly: the same character (`,`), same
`Builder`, same call shape, opposite outcome depending only on which inserter (`putPropertyRaw` vs.
`putAndQuoteProperty`) the typed constructor happens to use for that particular argument. If both throw, the
check has regressed to running against the arguments instead of the stored properties.

**25 call sites are checked, across two families.** Every one of the seven kinds' `addXxx` and (six of the
seven -- there is no `replacePedigree`) `replaceXxx` routes -- both the typed overload and the `Map` overload
of each -- runs this check: 13 `addXxx` call sites (7 kinds x typed+Map, except `PEDIGREE` which has no typed
route) + 12 `replaceXxx` call sites (6 kinds x typed+Map) = 25. `replaceXxx` is checked unconditionally,
without an exemption of any kind: there is no parse path through it to exempt (`VcfParser` never replaces --
see below), and its object-taking overloads are `private`, so every public route into it is one of these
twelve. Wiring the check into a shared *add-side* helper only, leaving `replaceXxx` unchecked, would have left
all twelve replace routes able to store a delimiter-bearing declaration that the default writer still
corrupts; `MetadataPropertyEncodingTest`'s `testEveryPublicMapReplaceRouteRejectsAnUnquotedDelimiter` and
`testTypedReplaceRoutesWithAReachableRawFieldRejectAnUnquotedDelimiter` are the regression guard for that.

## Why the parse path is exempt

**Parsing can produce values this check would reject, and exempting the parse path is mandatory, not
optional.** `AGENTS.md`'s Parser Validation Policy deliberately warns and recovers, rather than rejects, on an
unescaped quote in a metadata attribute list (see `InternalUtils.extractPropertiesFromLine`'s
`hasUnbalancedQuotes` warning, `InternalUtils.java:276-288`), and stores the recovered value rather than
discarding the declaration. If construction applied this check indiscriminately, it would reject files this
library accepts and has always accepted, which would be a regression in the warn-and-recover contract, not a
tightening of it.

The clearest example: a **non-final** unescaped quote makes `Description` absorb everything after it,
delimiters included. Parsing
`##INFO=<ID=XX,Number=1,Type=String,Description="a"b",Source="s">`
recovers `Description` as `"a"b",Source="s"` -- one value that contains an embedded `"`, a comma, *and* an
`=`, all three of the characters this check exists to reject, and there is no `Source` key in the result at
all. This is precisely the shape the parser must still be allowed to store; `checkMetadataPropertyEncoding`
would otherwise reject the whole declaration on `toBuilder()`'s round trip, or reject the parse itself if it
ran there.

This is not a new corruption introduced by writing the recovered value back out: for parsed input, the
absorption already happened at parse time, and re-emitting the same (already-absorbed) value re-parses to the
same absorbed state. It is stable, not progressively degrading. The genuinely new failure this check closes is
a **caller supplying a clean value that only corrupts on the way out** -- the corruption matrix above.

The exemption is wider than unescaped-quote recovery alone justifies, though: it skips
`checkMetadataPropertyEncoding`'s whole predicate, not just the quote check, and one other rejectable shape is
also reachable from parsing -- an **empty property key**. An attribute like `,=x,` inside a declaration's
attribute list splits (via `splitProperty`) into key `""` and value `"x"`; `BaseMetadata.validateProperties()`
only rejects a `null` key, not an empty one, so this survives construction through the trusted routes. It is
harmless for the identical reason the quote case is: the empty key was already produced at parse time, and
re-emitting it re-parses to the same key/value pair, not a degrading one.

**Two routes are covered by the exemption, and both are needed -- neither substitutes for the other:**

1. **`VcfParser`'s seven trusted variants.** `VcfMetadata.Builder` has one package-private `addXxxTrusted(Map)`
   sibling per kind (`addAltTrusted`, `addFilterTrusted`, `addInfoTrusted`, `addFormatTrusted`,
   `addContigTrusted`, `addSampleTrusted`, `addPedigreeTrusted`), each a thin wrapper that calls the same
   private `addXxx(properties, validateContent)` helper with `validateContent = false`. `VcfParser.
   parseMetadataProperty` (`VcfParser.java:594-628`) calls these trusted variants exclusively -- see the
   `switch` at lines 605-626 -- never the public, checked `addXxx(Map)` overloads. `VcfParser.parseMetadata`
   (`VcfParser.java:548-578`) is the caller one level up: for a generic property it splits the whole `##`
   line on the first `=` (`line.indexOf("=")`, line 550) and hands the two halves to `addRawProperty`, which
   has its own, separately-checked rule (below); for one of the seven reserved keywords it strips the
   surrounding angle brackets and delegates to `parseMetadataProperty`.
2. **`toBuilder()`'s package-private object-taking overloads.** `VcfMetadata.toBuilder()`
   (`VcfMetadata.java:145-174`) rebuilds a fresh `Builder` by re-adding every already-built declaration through
   a package-private `addXxx(md)` overload that takes the declaration object itself, not raw content -- none
   of these seven overloads calls `checkMetadataPropertyEncoding` at all. This is the load-bearing half of the
   exemption, not just a test bypass: without it, `toBuilder()` would throw on metadata that parsed
   successfully, meaning a public round-trip API would reject a file the library itself accepted just because
   the caller asked to copy-and-modify it. `MetadataPropertyEncodingTest.
   testToBuilderRebuildsParserRecoveredMetadataWithoutThrowing` is the regression guard: it is what catches an
   implementation mistake that puts the check into a shared insertion helper reachable from both `addXxxTrusted`
   and the object-taking overloads -- the parser's own direct route (half one,
   `testParsingStillRecoversAnUnescapedQuoteWithAWarning`) would still pass in that broken scenario, because it
   goes through the trusted variant either way, while only the `toBuilder()` half would newly start throwing.
   Neither half of this test pair substitutes for the other.

`Builder.addAlt(IdDescriptionMetadata, boolean)`'s existing `validateContent` boolean parameter
(`VcfMetadata.java:1010`) is the shape this design followed for the trusted/untrusted split, rather than
introducing a second convention -- it already existed to let `toBuilder()`'s ALT re-add skip re-running
`validateAlt` a second time on unchanged content (`VcfMetadata.java:149`, `builder.addAlt(md, false)`).

## The generic `##key=value` grammar is deliberately different

`Builder.addRawProperty(String name, String value)` (`VcfMetadata.java:1324-1374`) is a fourth public content
route, and it is **not** an attribute list -- there is no `<...>` and no comma-separated structure at all. A
generic property emits `##name=value` directly, and `VcfParser.parseMetadata` re-reads it by splitting the
whole line on the **first** `=` only (`VcfParser.java:550`, `int idx = line.indexOf("=");`). Applying the
structured rule above here would be wrong in both directions:

| Shape | Re-parse | Rule |
|---|---|---|
| `=` in the **name** | the split point shifts: `##foo=bar=value` comes back as name `foo`, value `bar=value` | **reject** |
| `=` in the **value** | split-on-first means the value is returned intact | **allow** |
| `,` in either | no attribute list; nothing splits on a comma | **allow** |
| newline in either | breaks the line entirely | already rejected by `InternalUtils.checkNoLineTerminator` |

This table is not exhaustive: a fifth shape, leading/trailing whitespace, silently corrupts too and is
deliberately left alone. `VcfParser.parseMetadata` trims both the name and the value it derives
(`VcfParser.java:554-555`, `line.substring(2, idx).trim()` and `line.substring(idx + 1).trim()`), so
`addRawProperty(" spaced", "v")` emits `## spaced=v` but re-parses with name `spaced`, not `" spaced"`. This is
pre-existing behavior, unrelated to this change, and out of scope to fix here -- noted only so a future reader
knows it was considered, not missed.

So `addRawProperty` rejects `=` in the *name* only (`VcfMetadata.java:1341-1344`), with the message "Raw
metadata property name contains '=', which would shift the re-parse split point."

`VcfWriter.Builder.validateBeforeWrite()` already rejects exactly this, pinned by
`VcfWriterTest.testValidateBeforeWriteRejectsEqualsSignInRawPropertyKey`, whose own comment records the
corruption as verified live. Making construction narrower than the writer here would have preserved the same
default-path-vs-`validateBeforeWrite()` asymmetry this whole change exists to remove.

**No trusted variant is needed for `addRawProperty`.** `VcfParser` (`VcfParser.java:576`,
`mdBuilder.addRawProperty(propName, propValue)`) derives `propName` by splitting the line on the first `=`
(the same mechanism the table above describes), so a parser-produced name can never itself contain a second
`=`. Unlike the seven structured routes, this check is safe to apply to the parser unconditionally, and does.

## Path asymmetry closed

Before this check existed, the two write paths disagreed about whether the same object was writable:
`VcfWriter.Builder.validateBeforeWrite()` already rejected all three corrupting shapes via
`VcfWriter.validateMetadataProperties` (now a thin delegate to the shared `InternalUtils.
checkMetadataPropertyEncoding`, `VcfWriter.java:670-677`), while the default write path emitted them verbatim
and lost data silently. This check closes that gap at the point of construction, which is the earliest point
that can see the fully-assembled declaration `VcfWriter` will later serialize -- but `VcfWriter.
validateMetadataProperties` still has work to do: it remains the net for the two routes that reach a built
`VcfMetadata` without ever passing through this construction-time check. One is the package-private, test-only
`BaseMetadata.trustedMutableProperties()` bypass. The other is the parse path itself -- `VcfParser`'s own
`addXxxTrusted`/object-taking routes (see "Why the parse path is exempt" above) deliberately skip this check so
a parser-recovered value (e.g. a non-final unescaped quote that absorbed a later attribute) can still be
stored, and that same value is exactly what `validateBeforeWrite()`'s call into `validateMetadataProperties` is
left to catch at write time. Neither route substitutes for the other.

## Deliberately not checked

Three routes skip this check, each for a distinct, worth-stating reason:

- **`BaseMetadata.putAndQuoteProperty`** -- quoting neutralizes all three characters (`a,b` becomes `"a,b"`,
  safe to emit). It keeps its own, separate line-terminator check, because quoting does **not** neutralize a
  newline the way it neutralizes a comma, `=`, or quote.
- **The package-private, object-taking `addXxx(T)` overloads** (`addInfo(InfoMetadata)`,
  `addFilter(IdDescriptionMetadata)`, etc.) -- see "Why the parse path is exempt" above. They re-add a
  declaration whose content already exists, including parser-recovered content; checking here would
  reintroduce the parse-path regression this design exists to avoid.

  `toBuilder()` also re-adds generic properties via `addRawProperty` (`VcfMetadata.java:171`), which **is**
  checked unconditionally there, and that is safe: `m_properties` can only ever have been populated through
  `addRawProperty` itself (there is no other route to it), so every name already in it has either already
  passed this exact check, or -- for parser-built metadata -- came from a first-`=` split and therefore cannot
  contain an `=` at all. There is no way to get a rejectable name into a built `VcfMetadata` and then round-trip
  it back out through `toBuilder()`.
- **`BaseMetadata.trustedMutableProperties()`** -- the test-only bypass this package's own tests use to
  synthesize the malformed containers `VcfWriter`'s defensive checks are written against. Unchanged by this
  work: `VcfWriter.validateMetadataProperties` stays as the net covering it -- and, per "Path asymmetry closed"
  above, the parse path too, since neither route passes through this construction-time check.

## Reference table

Verified via `MetadataPropertyEncodingTest`, the test class this table is drawn from.

| Route | Input | Outcome |
|---|---|---|
| `addContig(Map)` | `assembly=a,b` (unquoted comma) | throws |
| `addContig(Map)` | `assembly=a=b` (unquoted `=`) | throws |
| `addContig(Map)` | `assembly=a"b` (unescaped quote) | throws |
| `addContig(Map)` | `assembly="a,b"` (quoted) | succeeds; round-trips through default write + re-parse |
| `addContig(Map)` | `assembly="a=b"` (quoted) | succeeds |
| `addContig(Map)` | `assembly=B36` | succeeds |
| `addContig(id, length, assembly, md5, species, taxonomy, url)` | `,` in `assembly`, `md5`, `taxonomy`, or `url` | throws (each is `putPropertyRaw`-stored) |
| `addContig(...)` | `,` in `species` | succeeds; round-trips through default write + re-parse (the one contig argument routed through `putAndQuoteProperty`) |
| `addInfo(id, description, ...)` | `,` in `description` | succeeds; round-trips through default write + re-parse (`putAndQuoteProperty`) |
| any of the six kinds' typed `addXxx` | `=` in `id` | throws (the one raw-stored argument every typed route shares) |
| `replaceContig(Map)` / `replaceContig(typed...)` | unquoted `,` | throws (only a comma is exercised on the replace path; `=`/`"` share the identical underlying check, already proven on the `addContig` rows above) |
| `replaceContig(Map)` | a valid replacement (e.g. `assembly=GRCh38`) after a prior valid `addContig` | succeeds |
| `replaceInfo`/`replaceFormat` (typed) | `,` in `Number` | throws (the only other raw-stored typed field reachable without disturbing the already-stored `ID`) |
| `addContig(Map)` | key `""`, `"bad,key"`, `"bad=key"`, or `"a\"b"` (unescaped quote) | throws (this Builder check) |
| Parse `##contig=<ID=1,a"b=v,length=100>` (unescaped quote in a property NAME) | -- | parses; re-parsed contig's properties are `{ID=1}` only -- the quote flips `splitTopLevel`'s quote-mode toggle at the name, absorbing `a"b=v`, the following comma, and the whole `length=100` attribute; see below |
| `addContig(Map)` | key `null` (via a `LinkedHashMap`, not `Map.of`) | throws, but from `BaseMetadata.validateProperties()` *before* this check ever runs -- distinct message ("Metadata contains a null property name") |
| `addRawProperty(name, value)` | `=` in `name` (e.g. `"bad=name"`) | throws |
| `addRawProperty(name, value)` | `,` in `name` (e.g. `"bad,name"`) | succeeds (control: proves the structured comma rule was not applied to the generic grammar) |
| `addRawProperty(name, value)` | `=` in `value` (e.g. `"a=b"`) | succeeds; round-trips through default write + re-parse unchanged |
| `addRawProperty(name, value)` | `,` in `value` (e.g. `"a,b"`) | succeeds; round-trips through default write + re-parse unchanged |
| Parse `Description="a"b"` (unescaped quote inside a quoted value) | -- | parses; warns "contains an unescaped quote"; recovers `Description` as `"a"b"` verbatim |
| Parse `Description=ab"` (unquoted value ending in a quote) | -- | parses; warns **both** "contains an unescaped quote" and "should be quoted but was"; recovers `Description` as `ab"` |
| Parse `Description="a"b",Source="s"` (non-final unescaped quote) | -- | parses; warns "contains an unescaped quote"; recovers `Description` as `"a"b",Source="s"` -- absorbing the comma, the `=`, and the whole `Source` attribute; no `Source` key in the result at all |
| `parsed.toBuilder().build()` for any of the three parses above | -- | succeeds, with the identical recovered raw properties -- the object-taking overloads' exemption is what makes this possible |

The five "round-trips through default write + re-parse" rows are not redundant with the "succeeds" rows above
them: a change that accepted a value at construction but still corrupted it on the way out would pass every
`assertDoesNotThrow`/`assertThrows` assertion in this table while leaving the actual defect the corruption
matrix describes fully in place. Only the round-trip assertion proves the accept-decision was actually correct.

## Known gap: an escaped quote in a property name

**This check rejects only an *unescaped* quote in a property name, not every quote.** An escaped quote
(`a\"b=v`) round-trips cleanly through this library: constructing, writing on the default path, and
re-parsing `##contig=<ID=1,a\"b=v,length=100>` yields `{ID=1, a\"b=v, length=100}` back -- all three
attributes intact (verified live). bcftools 1.23.1 disagrees and rejects the line outright:

```
[E::bcf_hdr_parse_line] Could not parse the header line: "##contig=<ID=1,a\\\"b=v,length=100>"
```

(bcftools's own message escapes the literal `\"` in the input line for display inside its own double-quoted
diagnostic string, hence `\\\"` rather than `\"`.)

Closing this is deliberately out of scope for the unescaped-quote fix above: the parse path is exempt from
this whole check (see "Why the parse path is exempt" above), so a header whose property name already
contains an escaped quote parses through untouched today, and widening the check to also reject an escaped
quote -- without also deciding what the parser itself should do -- would make `validateBeforeWrite()` throw
on content this library's own parser just accepted. That is because `validateBeforeWrite()`'s
`validateMetadataProperties` (`VcfWriter.java:675-677`) is the write-time net described in "Path asymmetry
closed" above: it runs `checkMetadataPropertyEncoding` against every entry's properties unconditionally at
write time, regardless of whether that entry came from a checked construction route or from the parser's
exempt trusted routes. `toBuilder()` is *not* the mechanism here -- it re-adds every declaration through the
package-private object-taking `addXxx(md)` overloads (`VcfMetadata.java:145-174`), none of which calls
`checkMetadataPropertyEncoding` at all (see "Deliberately not checked" above), so it would keep succeeding on
this content however the check were widened. A parse-path decision is needed first. Recorded here so it is
not rediscovered as a new finding; to be addressed separately.

## Where this is implemented

- `InternalUtils.checkMetadataPropertyEncoding(String, Map<String, String>)` -- the single shared predicate,
  called by both `VcfMetadata.Builder`'s 25 checked routes and `VcfWriter.validateMetadataProperties`.
  `InternalUtils.isQuoted`/`InternalUtils.containsUnescapedQuote` are its two helpers, moved here from
  `VcfWriter` (where they were previously private) so both call sites share one implementation rather than
  risking two copies of a predicate this subtle drifting apart.
- `VcfMetadata.Builder`'s seven `addXxx` families and six `replaceXxx` families -- each kind's typed and `Map`
  overload calls the check against the constructed declaration's `getPropertiesRaw()`, never against the
  method's own arguments.
- `VcfMetadata.Builder`'s seven package-private `addXxxTrusted(Map)` siblings (`addAltTrusted`,
  `addFilterTrusted`, `addInfoTrusted`, `addFormatTrusted`, `addContigTrusted`, `addSampleTrusted`,
  `addPedigreeTrusted`) -- skip the check; used exclusively by `VcfParser`.
- `VcfMetadata.Builder.addRawProperty(String, String)` -- applies the narrower, unconditional "reject `=` in
  the name only" rule instead of the structured one.
- `VcfParser.parseMetadataProperty` -- calls the seven trusted variants. `VcfParser.parseMetadata` -- calls
  `addRawProperty` for a generic property, and derives its `name` argument by splitting on the first `=`.
- `VcfMetadata.toBuilder()` -- re-adds every declaration through the package-private object-taking overloads
  (none of which calls this check), and re-adds every generic property through the same, unconditionally
  checked `addRawProperty`.
- `VcfWriter.validateMetadataProperties` -- now a thin delegate to `InternalUtils.checkMetadataPropertyEncoding`,
  kept as the net for the two routes that still bypass this construction-time check: `BaseMetadata.
  trustedMutableProperties()`'s test-only bypass, and the parse path's own trusted/object-taking routes.
- `MetadataPropertyEncodingTest` -- the test class covering every route and shape described in this document.

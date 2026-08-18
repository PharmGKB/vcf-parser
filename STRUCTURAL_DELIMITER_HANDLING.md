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

**Construction-time unconditionality is not the whole story.** `InternalUtils.checkMetadataPropertyEncoding`
is the construction-time entry point (still unconditional -- every problem throws), and
`checkMetadataPropertyEncodingForWrite` is its `validateBeforeWrite()` counterpart, which throws only for a
problem that would make the emitted FILE unreadable by bcftools and warns otherwise. Both share one classifier,
`InternalUtils.findEncodingProblems`, so they cannot disagree about what counts as a problem, only about what
to do with one -- see "Where write-time and construction-time checks meet" and the Reference table below for
the specifics, and `VALIDATION_PHILOSOPHY.md`'s originated-vs-carried-forward paragraph for why the two routes
are allowed to differ at all.

## The defect

`VcfWriter` has no encoding step. `getAllProperties` (`VcfWriter.java:1201-1215`) emits each `##KEY=<...>` line
by walking `BaseMetadata.getPropertiesRaw()` and appending every key and value **verbatim**, separated by `,`
and `=` with no quoting and no escaping. Output is therefore well-formed only if the encoding was already
valid when the declaration was **constructed** -- construction is what guarantees that (see "Severity:
unconditional throw" below). A line terminator (`\n`/`\r`) is separately rejected at construction by
`InternalUtils.checkNoLineTerminator`, independent of this check -- see `VALIDATION_REFERENCE.md`'s
line-terminator bullet.

## Severity ranking: comma > everything else

The comma's primacy over `<`/`>` below -- that an unquoted value's own comma still ends it even inside an
angle-bracket span -- is a recorded decision with its own entry: see `VCF_SPEC_AMBIGUITIES.md`'s "Angle
brackets are not a quoting form in a metadata attribute value".

**Under the attribute-list tokenizer, only a comma genuinely loses data for an otherwise unquoted,
single-attribute value.** Neither an unescaped `=` nor an unescaped `"` does, because each attribute's
boundary is found independently -- no parse state of any kind is shared across the whole declaration:

- **A comma** is still the one character an unquoted value has no way to escape: `attributeLimit`'s
  comma-bounded search (`InternalUtils.java`, shared by `tokenizeAttributeList` and `parseAttribute` via
  `parseOneAttribute`) always stops the value there, so `assembly=a,b` still truncates to `a`, and the `b`
  left over becomes its own bogus attribute (a "has no '='" warning, then dropped).
- **An `=`** in the value is ordinary content once the name's own first `=` has been consumed -- nothing
  truncates, nothing drops, no warning fires at all.
- **An unescaped `"`** only matters if it is the value's *first* byte after the optional leading-space skip
  (`AttributeListTokenizerTest#onlyALeadingDelimiterQuotes`: "a non-leading quote is an ordinary byte and
  protects nothing"). `assembly=a"b`'s quote is not leading, so the value stays entirely literal and the
  quote is inert past a warning. A *leading* quote, and only a leading one, does still matter:
  `tokenizeAttributeList`'s closing-quote search spans the *whole remainder*
  of the line, not just up to the next comma (the same whole-remainder search the bracket form's own version
  of this rule gets its dedicated test for, `AttributeListTokenizerTest#aMetaValuesClosingBracketIsSearchedForAcrossTheWholeRemainder`
  -- deliberate, so a `##META` example's own bracket value isn't falsely "unterminated" at its first interior
  comma; the quoted-value case shares the identical scan and has no separate test of its own). If a value opens with `"` and never finds its own closing `"` before the next
  attribute's opening `"`, that next attribute's opening quote is mistaken for the closing one, and everything
  in between -- including that next attribute's own name and `=` -- is absorbed. This requires a *leading*,
  unclosed quote specifically, not any non-leading one anywhere in the declaration;
  see the `Description="ab,Source="s"` case in "Why the parse path is exempt" below for a live example,
  guarded by `MetadataPropertyEncodingTest`'s third `RecoveredCase`.

## Brackets: a second value-quoting form, scoped to `##META`'s `Values`

`InternalUtils.tokenizeAttributeList`/`parseAttribute` recognize a second value-quoting form alongside `"`:
`[...]`, used by VCFv4.3.tex's `##META` worked example (`Values=[WholeGenome, Exome]`-style bracket lists).
Unlike `"`, this form is **not** attribute/declaration-type-agnostic: it applies only when the declaration
type is `"META"` and the attribute name is exactly `"Values"` (`InternalUtils.parseOneAttribute`'s
`isMetaValues` gate) -- the one place any VCF version (4.3-4.5) actually evidences the form; see
`VCF_SPEC_AMBIGUITIES.md`'s "General bracket-list quoting beyond `##META`'s `Values`" entry for why this is
narrower than htslib's own generalized handling, and why that narrowing is a deliberate choice on a genuine
tie rather than something the spec forces. Everywhere else, a leading `[` is an ordinary byte with no
quoting role at all, exactly like `<`/`>` (see "Severity ranking" above) -- it does
not protect an interior comma, and a value like `assembly=[WholeGenome, Exome]` on `##contig` truncates at
the comma just like any other unquoted value would.

**Within `Values`'s bracket form, the closing search has no escape mechanism, unlike `"`.** A quoted
(`"`-first) value's closing search (`indexOfUnescaped(s, '"', ...)`) respects backslash escaping. `Values`'s
bracket-closing search (a plain `String.indexOf(']', ...)`) does not -- the spec defines no way to escape `[`
or `]`, so the first LITERAL `]` closes the value regardless of any preceding backslash; a backslash before
`]` is itself just ordinary content. That search still spans the whole remainder of the line, not just up to
the next comma (`AttributeListTokenizerTest#aMetaValuesClosingBracketIsSearchedForAcrossTheWholeRemainder` --
deliberate, so the spec's own worked example's interior comma doesn't falsely make the value "unterminated").
An unterminated `[` on `Values` (no closing `]` anywhere) is parse-only leniency: it is emitted **as-is,
unmodified** -- not re-quoted -- with a warning that there is no closing `]`, letting a downstream tool decide
what to do with it; construction rejects this shape outright (below), so a value reaching this branch can only
ever be carried-forward content from someone else's file.

**This is a deliberate exemption from the re-quote the non-`Values` case below gets for the same "dangling
`[`, no `]` anywhere" shape, not an inconsistency.** That re-quote exists because outside `Values`, this
library's OWN comma-splitting can turn a well-formed bracket value into a dangling one (truncating
`x=[a, b]` at its interior comma yields `x=[a`) -- manufacturing new bcftools-fatal content from input that
was never malformed. `Values` cannot suffer that failure mode: its closing-`]` scan (above) is unbounded by
comma, so this library's own splitting can never be the cause of a dangling `[` here. The only way this
branch is ever reached is if the ORIGINAL file's `Values` value already lacked a closing `]` anywhere in the
rest of the line -- this library's parsing only observes that state, never creates it -- so bcftools would
already exit fatally reading that same original file, before this library was ever involved. Re-emitting the
value as-is therefore introduces no new failure mode; per `AGENTS.md`'s originated-vs-carried-forward
distinction, it is a purely carried-forward artifact of someone else's malformed file, not this library
manufacturing corruption. bcftools failing fatally on an unterminated `[` at all is the same filed defect as
the non-`Values` case cites, [samtools/htslib#2065](https://github.com/samtools/htslib/issues/2065) -- which
is exactly why this library does not additionally paper over it here.

**Outside `Values`, a dangling `[` is defensively re-quoted -- not because the general bracket form is
recognized, but because this library must never manufacture new bcftools-fatal output from its own
splitting.** A well-formed, comma-containing bracket value on some other attribute (e.g.
`x=[WholeGenome, Exome]` on `##contig`) truncates at the interior comma to just `x=[WholeGenome` -- and
writing that raw would create a value with a dangling `[` and no `]` anywhere, which bcftools cannot read at
all (exit 255), even though the ORIGINAL input was perfectly well-formed. `InternalUtils.parseOneAttribute`'s
literal (unquoted) branch checks for exactly this: if the value starts with `[` and, after whatever
truncation happened, contains no `]` anywhere, it is re-quoted (`InternalUtils.quote`) rather than stored raw,
with a warning distinct from the ordinary "structural delimiter" one. This applies identically whether the
dangling `[` came from truncating an originally well-formed value or from input that was already malformed --
the two are indistinguishable once stored, so both are covered by the same check, and for a **non-`Values`**
attribute the tokenizer once again can never produce a bare, unquoted, unterminated `[` as a stored value
(matching the invariant `MetadataPropertyEncodingTest#diagnosticsModeStillThrowsWhenOutputWouldBeUnreadable`'s
comment relies on -- the only route to that state is the package-private
`BaseMetadata#trustedMutableProperties()` bypass; that test, like this paragraph, is scoped to the non-`Values`
shape -- see "Brackets: a second value-quoting form" above for why `Values` itself is deliberately exempt from
this same re-quote).

**Construction mirrors this with an unconditional, non-gated check.** `InternalUtils.findEncodingProblems`
checks, BEFORE the `##META`'s-`Values`-only gate, whether a value starting with `[` has no `]` anywhere at
all -- if so, it throws (`unreadableOutput=true`) regardless of attribute name or declaration type, since
bcftools' own generic bracket handling is unaffected by this library's narrower acceptance scope. Only once
that universal floor is cleared does the `##META`'s-`Values`-only check run:
`InternalUtils.isWellFormedMetaValuesBracket` checks that a `Values` value's FIRST literal `]` (no escape
awareness, via the same plain scan the tokenizer uses, so construction and parsing cannot disagree about
where the value ends) is its last character -- a well-formed one is accepted with no further checks, so
`Values=[WholeGenome, Exome]` (the spec's own shape) succeeds at construction. A bracket that closes but has
non-comma junk before the next attribute (`Values=[a]b]`) is rejected too, as an `unreadableOutput` problem
(bcftools drops just the declaration for this sub-shape, but the tokenizer can never produce it as a stored
value on the parse path either, so throwing costs nothing). Outside `Values`, that same closes-but-junk-after
shape (e.g. `x=[a]b]` on `##contig`) is NOT malformed-bracket-checked at all -- it falls through to the
generic "unquoted structural delimiter" check (`unreadableOutput=false`), since it has a `]` somewhere and so
never trips the universal floor; construction still rejects it (`checkMetadataPropertyEncoding` throws for
ANY problem regardless of severity), but `validateBeforeWrite()` only warns. See `VCF_SPEC_AMBIGUITIES.md`'s
"General bracket-list quoting beyond `##META`'s `Values`" entry for the full evidence and the accepted
fatal-vs-non-fatal asymmetry this leaves in place.

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
  caller currently emits output that silently loses data on the default write path for the comma shape (still
  a genuine truncation today) -- warning at construction time and then proceeding to store the value anyway
  would fix nothing; the corruption would still happen later, at write time.

**`=` and an unescaped `"` are treated asymmetrically.** `=` does not appear in the classifier at all: an
unquoted value may contain it freely, at construction and at write time alike, since the tokenizer's own
name/value split already makes it ordinary content with no corruption to guard against. An unescaped `"`
remains a problem at construction (`checkMetadataPropertyEncoding` throws unconditionally for it) but is not
an *unconditional-everywhere* throw: `validateBeforeWrite()`'s counterpart warns for it instead, since
`InternalUtils.EncodingProblem` classifies it as not `unreadableOutput` (the file stays bcftools-readable
even when one declaration's value is corrupted this way). See `VALIDATION_PHILOSOPHY.md`'s
originated-vs-carried-forward paragraph for why construction and `validateBeforeWrite()` are allowed to answer
this differently at all, and the Reference table below for exactly which shapes throw on both paths versus
which throw at construction only.

## Where the check runs

`InternalUtils.findEncodingProblems(String kind, Map<String, String> properties)`
(`InternalUtils.java:1679-1767`) is the shared classifier, called by both `InternalUtils.
checkMetadataPropertyEncoding` (`InternalUtils.java:1611-1615`, the construction-time entry point -- every
problem it finds throws) and `InternalUtils.checkMetadataPropertyEncodingForWrite`
(`InternalUtils.java:1627-1635`, the `validateBeforeWrite()` entry point -- throws only for a problem marked
`unreadableOutput`, warns otherwise). It selects a branch on the property value's first byte -- exactly as the tokenizer itself does (see "Brackets: a second
value-quoting form" above) -- then checks within it. It rejects:

- a property **name** that does not match `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN`
  (`^[A-Za-z_][0-9A-Za-z_.]*$`, the bcftools-derived allowlist -- one check that also covers a `null` name, an
  empty name, and a name containing `,`, `=`, or `"`, since none of those is in the allowlist's character set
  either);
- a value starting with `"` (the **quoted** branch) that is not validly quoted (`InternalUtils.isQuoted`,
  `InternalUtils.java:1783-1800`) -- and within that, one further split: a malformed value that also contains
  a comma is `unreadableOutput=true` (this library's own tokenizer recovers a value with no valid closing `"`
  by scanning for the next comma rather than the value's own end, so an embedded comma silently drops
  everything after it on re-parse -- see `EncodingProblem`'s own Javadoc); a malformed value with no comma is
  `unreadableOutput=false` (bcftools itself does not hard-fail on an unterminated leading quote, only warns
  and mangles the declaration);
- a value starting with `[` and containing no `]` anywhere (`InternalUtils.java:1730`,
  `if (value.startsWith("[") && value.indexOf(']', 1) < 0) {`) -- checked
  UNCONDITIONALLY, before the `##META`'s-`Values`-only gate below, since bcftools' own generic bracket
  handling still treats any leading `[` as opening a quoted span regardless of attribute/declaration type,
  and hard-fails (exit 255) if it never finds a closing `]`;
- beyond that universal floor, a value starting with `[` on `##META`'s `Values` specifically
  (`InternalUtils.java:1736`, `if (value.startsWith("[") && isMetaValues(kind, name)) {`) that is not a
  well-formed bracket value (`InternalUtils.
  isWellFormedMetaValuesBracket`, `InternalUtils.java:1779` -- its first LITERAL `]`, no escape awareness,
  must be its last character); a leading `[` on any other attribute is not bracket-checked at all past the
  universal floor -- see "Brackets: a second value-quoting form, scoped to `##META`'s `Values`" above;
- otherwise (the **literal** branch, for a value starting with neither `"` nor `[`): an unescaped `,`, `<`,
  `>`, or `"` -- `InternalUtils.containsUnescapedQuote` (`InternalUtils.java:1817-1827`) is the helper behind
  the last of those -- or leading/trailing space.

`=` is deliberately NOT one of these (see "Severity: unconditional throw" above), and a value inside a well-formed bracket pair is not checked for any of the above at all, including
an interior comma (matching VCFv4.3.tex's own `##META` example).

**The `null`-key shape is not new here, and is not reachable through this check at all.** A `null` key is
already rejected during construction, for every construction route, by `BaseMetadata.validateProperties()`
(`BaseMetadata.java:139-143`, "Metadata contains a null property name") -- this fires from `BaseMetadata`'s
`Map`-based constructor (`BaseMetadata.java:76-89`, called at line 88) before a declaration is ever handed back
to the `Builder`, so the `Builder`'s own check never sees a `null` key. `findEncodingProblems` keeps its own
`name == null` guard anyway (it costs nothing, and matches this method's defensive style), but a test asserting
on a `null` key is pinning `validateProperties`'s message, not this check's (see
`testANullKeyIsRejectedByConstructionNotByThisCheck`).

**The check runs against the constructed declaration's `getPropertiesRaw()`, never against the `Builder`
method's own arguments.** Every one of the six `id`/`description`-shaped keyed types' `addXxx(id, description,
...)` typed overloads builds the declaration object first (e.g. `addAlt(String, String)` constructs an
`IdDescriptionMetadata` from its two arguments), and only then calls
`InternalUtils.checkMetadataPropertyEncoding` against that object's own `getPropertiesRaw()` -- not against
`id`/`description` directly (`VcfMetadata.java:721-726`, `public Builder addAlt(@Nullable String id, @Nullable
String description) { ... InternalUtils.checkMetadataPropertyEncoding("ALT", md.getPropertiesRaw()); ... }`,
and the same shape repeated for the other five kinds).
`##META` has no typed overload of this shape at all (see below), so this specific pattern doesn't extend to it.

This is deliberate, not incidental: `BaseMetadata.putAndQuoteProperty` (`BaseMetadata.java:238`) quotes
`Description`/`Source`/`Version`/`species` on the way into several of the typed constructors, so checking the
*arguments* would reject a value the constructor was about to make safe by quoting it. Checking the
*stored result* sees exactly what the writer will actually emit. `MetadataPropertyEncodingTest`
(`testAddTypedAcceptsADelimiterInContigsOwnAutoQuotedField`,
`testAddTypedAcceptsADelimiterInAnAutoQuotedField`) pins this directly: the same character (`,`), same
`Builder`, same call shape, opposite outcome depending only on which inserter (`putPropertyRaw` vs.
`putAndQuoteProperty`) the typed constructor happens to use for that particular argument. If both throw, the
check has regressed to running against the arguments instead of the stored properties.

**27 call sites are checked, across two families.** Every one of the eight kinds' `addXxx` and (seven of the
eight -- there is no `replacePedigree`) `replaceXxx` routes -- both the typed overload and the `Map` overload
of each, where a typed overload exists -- runs this check: 14 `addXxx` call sites (6 `id`/`description`-shaped
kinds x typed+Map, plus `META` and `PEDIGREE`, each Map-only since neither has a typed overload) + 13
`replaceXxx` call sites (6 kinds x typed+Map, plus `META` Map-only) = 27. `replaceXxx` is checked
unconditionally, without an exemption of any kind: there is no parse path through it to exempt (`VcfParser`
never replaces -- see below), and its object-taking overloads are `private`, so every public route into it is
one of these thirteen. Wiring the check into a shared *add-side* helper only, leaving `replaceXxx` unchecked,
would have left all thirteen replace routes able to store a delimiter-bearing declaration that the default
writer still corrupts; `MetadataPropertyEncodingTest`'s `testEveryPublicMapReplaceRouteRejectsAnUnquotedDelimiter`
and `testTypedReplaceRoutesWithAReachableRawFieldRejectAnUnquotedDelimiter` are the regression guard for that.

## Why the parse path is exempt

**Parsing can produce values this check would reject, and exempting the parse path is mandatory, not
optional.** `AGENTS.md`'s Parser Validation Policy deliberately warns and recovers, rather than rejects, on an
unescaped quote in a metadata attribute list (see `InternalUtils.tokenizeAttributeList`'s "unterminated ...
value" and "trailing content ... discarding it" warnings), and stores the recovered value rather than
discarding the declaration. If construction applied this check indiscriminately, it would reject files this
library accepts and has always accepted, which would be a regression in the warn-and-recover contract, not a
tightening of it.

A non-leading, non-final quote does not absorb anything (see the severity-ranking section above), but a
**leading, unclosed** quote still can, because the closing-quote search spans the whole remainder of the line
rather than stopping at the next comma.
Parsing
`##INFO=<ID=XX,Number=1,Type=String,Description="ab,Source="s">`
recovers `Description` as `"ab,Source="` -- Description's own opening quote never finds a closing quote
before the comma, so the search continues past it and mistakes `Source`'s opening quote for Description's
closing one, swallowing `,Source=` whole. The result contains an embedded `"`, a comma, *and* an `=`, all
three of the characters this check exists to reject, and there is no `Source` key in the result at all
(the leftover `s"` past that point is separately warned about and discarded as trailing content). This is
precisely the shape the parser must still be allowed to store; `checkMetadataPropertyEncoding` would
otherwise reject the whole declaration on `toBuilder()`'s round trip, or reject the parse itself if it ran
there.

This is not a new corruption introduced by writing the recovered value back out: for parsed input, the
absorption already happened at parse time, and re-emitting the same (already-absorbed) value re-parses to the
same absorbed state. It is stable, not progressively degrading. The genuinely new failure this check closes is
a **caller supplying a clean value that only corrupts on the way out**.

**An empty property key is not reachable from parsing.** `InternalUtils.parseOneAttribute` (shared by
`tokenizeAttributeList` and `parseAttribute`) detects an empty name directly and drops the whole attribute
with a warning ("Metadata attribute has an empty name; dropping it") before it can ever reach a properties
map -- see `AttributeListTokenizerTest#degenerateAttributesAreDroppedIndividually`.

**Two routes are covered by the exemption, and both are needed -- neither substitutes for the other:**

1. **`VcfParser`'s eight trusted variants.** `VcfMetadata.Builder` has one package-private `addXxxTrusted(Map)`
   sibling per kind (`addAltTrusted`, `addFilterTrusted`, `addInfoTrusted`, `addFormatTrusted`,
   `addContigTrusted`, `addSampleTrusted`, `addMetaTrusted`, `addPedigreeTrusted`), each a thin wrapper that
   calls the same private `addXxx(properties, validateContent)` helper with `validateContent = false`.
   `VcfParser.parseMetadataProperty` (`VcfParser.java:612-649`) calls these trusted variants exclusively -- see
   the `switch` at lines 623-648 -- never the public, checked `addXxx(Map)` overloads. `VcfParser.parseMetadata`
   (`VcfParser.java:549-596`) is the caller one level up: for a generic property it splits the whole `##`
   line on the first `=` (`VcfParser.java:551`, `int idx = line.indexOf("=");`) and hands the two halves to
   `addRawProperty`, which
   has its own, separately-checked rule (below); for one of the eight reserved keywords it strips the
   surrounding angle brackets and delegates to `parseMetadataProperty`.
2. **`toBuilder()`'s package-private object-taking overloads.** `VcfMetadata.toBuilder()`
   (`VcfMetadata.java:149-181`, `public Builder toBuilder() {` ... `return builder;`) rebuilds a fresh `Builder`
   by re-adding every already-built declaration through
   a package-private `addXxx(md)` overload that takes the declaration object itself, not raw content -- none
   of these eight overloads calls `checkMetadataPropertyEncoding` at all. This is the load-bearing half of the
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
(`VcfMetadata.java:1059`, `private Builder addAlt(IdDescriptionMetadata md, boolean validateContent) {`) is the
shape this design followed for the trusted/untrusted split, rather than introducing a second convention -- it
already existed to let `toBuilder()`'s ALT re-add skip re-running `validateAlt` a second time on unchanged
content (`VcfMetadata.java:153`, `builder.addAlt(md, false);`).

## The generic `##key=value` grammar is deliberately different

`Builder.addRawProperty(String name, String value)` (`VcfMetadata.java:1411-1464`,
`public Builder addRawProperty(String name, String value) {` ... closing brace) is a fourth public content
route, and it is **not** an attribute list -- there is no `<...>` and no comma-separated structure at all. A
generic property emits `##name=value` directly, and `VcfParser.parseMetadata` re-reads it by splitting the
whole line on the **first** `=` only (`VcfParser.java:551`, `int idx = line.indexOf("=");`). Applying the
structured rule above here would be wrong in both directions:

| Shape | Re-parse | Rule |
|---|---|---|
| `=` in the **name** | the split point shifts: `##foo=bar=value` comes back as name `foo`, value `bar=value` | **reject** |
| `=` in the **value** | split-on-first means the value is returned intact | **allow** |
| `,` in either | no attribute list; nothing splits on a comma | **allow** |
| newline in either | breaks the line entirely | already rejected by `InternalUtils.checkNoLineTerminator` |

This table is not exhaustive: a fifth shape, leading/trailing whitespace, silently corrupts too and is
deliberately left alone. `VcfParser.parseMetadata` trims both the name and the value it derives
(`VcfParser.java:555-556`, `line.substring(2, idx).trim()` and `line.substring(idx + 1).trim()`), so
`addRawProperty(" spaced", "v")` emits `## spaced=v` but re-parses with name `spaced`, not `" spaced"`. This is
a known gap, deliberately left unaddressed: the trim happens in `VcfParser.parseMetadata`, not in
`addRawProperty`'s own rule, and closing it would mean deciding whether the parser should stop trimming --
a separate question this document does not settle. Noted only so a future reader knows it was considered,
not missed.

So `addRawProperty` rejects `=` in the *name* only (`VcfMetadata.java:1428-1431`, `if (name.contains("=")) {
throw new VcfFormatException("Raw metadata property name contains '=', ...`), with the message "Raw
metadata property name contains '=', which would shift the re-parse split point."

`VcfWriter.Builder.validateBeforeWrite()` already rejects exactly this, pinned by
`VcfWriterTest.testValidateBeforeWriteRejectsEqualsSignInRawPropertyKey`, whose own comment records the
corruption as verified live. Construction deliberately matches that rather than being narrower: a rule
construction declined to enforce would let `addRawProperty` originate a name `validateBeforeWrite()` then
rejects, leaving the default write path and `validateBeforeWrite()` disagreeing about content this library
itself created.

**No trusted variant is needed for `addRawProperty`.** `VcfParser` (`VcfParser.java:588` and `:594`, both
`mdBuilder.addRawProperty(propName, propValue)` -- the pre-4.3 `##META` fallthrough and the generic `default`
case respectively) derives `propName` by splitting the line on the first `=`
(the same mechanism the table above describes), so a parser-produced name can never itself contain a second
`=`. Unlike the eight structured routes, this check is safe to apply to the parser unconditionally, and does.

## Where write-time and construction-time checks meet

Construction rejects all three corrupting shapes at the point of construction, which is the earliest point
that can see the fully-assembled declaration `VcfWriter` will later serialize -- but `VcfWriter`'s write-time
check still has work to do: it remains the net for the two routes that reach a built `VcfMetadata` without
ever passing through the construction-time check. One is the package-private, test-only
`BaseMetadata.trustedMutableProperties()` bypass. The other is the parse path itself -- `VcfParser`'s own
`addXxxTrusted`/object-taking routes (see "Why the parse path is exempt" above) deliberately skip this check so
a parser-recovered value (e.g. a leading, unclosed quote that absorbed a later attribute's name and `=`) can
still be stored, and that same value is exactly what `validateBeforeWrite()` is left to catch at write time.
Neither route substitutes for the other.

**"Catch" does not mean "throw" uniformly.** `VcfWriter.validateMetadataProperties` calls
`InternalUtils.checkMetadataPropertyEncodingForWrite`, not `checkMetadataPropertyEncoding` -- the same
classifier (`findEncodingProblems`), but a different response to what it finds: it throws only for a problem
marked `unreadableOutput` (an unquoted comma, a malformed bracket value, or a malformed quoted value that
itself contains a comma; see `EncodingProblem`'s own Javadoc for why these throw for genuinely different
reasons, not one shared one) and warns for everything else (an invalid property name, a malformed quoted value
with no comma, an unescaped `<`/`>`/`"` in a literal value, or leading/trailing space), preserving the value
unchanged either way.

The leading-unclosed-quote case this paragraph opened with (`Description="ab,Source="s"`, recovered as
`Description="ab,Source="`) is, perhaps counter-intuitively, not caught at write time at all:
`findEncodingProblems` selects its **quoted** branch for this value on its leading `"` (the first byte, per
"Where the check runs" above), and within that branch `InternalUtils.isQuoted` returns `true` for it, so
nothing is flagged. The recovered value's own comma and `=` both fall INSIDE its start/end `"` pair with no
unescaped `"` between them, so `isQuoted` sees a validly-quoted string and there is nothing left to flag --
and because it IS validly quoted, the malformed-quoted-value comma check (which only fires when `isQuoted`
returns `false`) does not apply to it either. This is not a silent-acceptance defect either: the parse path
itself already warns for this exact input (pinned in `MetadataPropertyEncodingTest`'s `RECOVERED_QUOTE_CASES`),
and the value is genuinely safe to re-emit exactly as stored (the whole point of "Why the parse path is
exempt" above -- the absorption already happened once, at parse time, and is a fixed point on re-emission),
so a check whose entire job is spotting encoding that would corrupt on the way OUT has nothing left to flag
once the value is already wrapped in a syntactically valid quote pair. It is a reminder that "was this value
produced by parser recovery" and "does this value still look encoding-unsafe" are different questions -- this
check only ever answers the second one.

See `VALIDATION_PHILOSOPHY.md`'s originated-vs-carried-forward paragraph for why the construction/write split
exists at all, and this document's own Reference table below for the throws-vs-warns line, shape by shape.

## Deliberately not checked

Three routes skip this check, each for a distinct, worth-stating reason:

- **`BaseMetadata.putAndQuoteProperty`** -- quoting neutralizes every character `findEncodingProblems` checks
  for an unquoted value (`a,b` becomes `"a,b"`, safe to emit; the same holds for `<`, `>`, and leading/trailing
  space -- `=` is not in this set at all, see "Severity: unconditional throw" above). It keeps its own,
  separate line-terminator check, because quoting does **not** neutralize a newline the way it neutralizes a
  comma, `<`, `>`, a quote, or layout whitespace.
- **The package-private, object-taking `addXxx(T)` overloads** (`addInfo(InfoMetadata)`,
  `addFilter(IdDescriptionMetadata)`, etc.) -- see "Why the parse path is exempt" above. They re-add a
  declaration whose content already exists, including parser-recovered content; checking here would
  reintroduce the parse-path regression this design exists to avoid.

  `toBuilder()` also re-adds generic properties via `addRawProperty` (`VcfMetadata.java:178`,
  `builder.addRawProperty(entry.getKey(), entry.getValue());`), which **is**
  checked unconditionally there, and that is safe: `m_properties` can only ever have been populated through
  `addRawProperty` itself (there is no other route to it), so every name already in it has either already
  passed this exact check, or -- for parser-built metadata -- came from a first-`=` split and therefore cannot
  contain an `=` at all. There is no way to get a rejectable name into a built `VcfMetadata` and then round-trip
  it back out through `toBuilder()`.
- **`BaseMetadata.trustedMutableProperties()`** -- the test-only bypass this package's own tests use to
  synthesize the malformed containers `VcfWriter`'s defensive checks are written against.
  `VcfWriter.validateMetadataProperties` is the net covering it -- and, per "Where write-time and
  construction-time checks meet" above, the parse path too, since neither route passes through this
  construction-time check.

## Reference table

Verified via this library's own test suite -- principally `MetadataPropertyEncodingTest`, plus
`BcftoolsDependencyTest`, `InternalUtilsTest`, `VcfWriterTest`, and the `local-fixtures/` conformance corpus.
Each row names the specific tests and fixtures it rests on.

**Tier 2 evidence disclosure** (`BEHAVIOR_TABLE_STANDARD.md`'s "Preserving the evidence"): no separate probe
script is committed for this table. Each decision-bearing row's bcftools evidence lives in its own
`BcftoolsDependencyTest` case instead -- seven of the eight do; row 1's gap is recorded in that row -- and the
two vcf_validator runs behind rows 3 and 30 are recorded verbatim, invocation included, in their
`local-fixtures/` fixtures' own `##Authority=` lines.

Rows 1-22 cover the "structured attribute-list family" -- every route that assembles a `##KEY=<key=value,...>`
declaration through `VcfMetadata.Builder`'s public construction (`addContig`/`addMeta`/`addInfo`/`addAlt`/
`addFilter`/`addFormat`/`addSample`) and its `replaceXxx` counterparts, all checked by the shared
`InternalUtils.findEncodingProblems` classifier described above (rows for `addRawProperty`'s separate grammar
and the parse-path-only rows follow after row 22). Per `BEHAVIOR_TABLE_STANDARD.md`, each row below states:

- its **probe shape** -- declaration type, attribute name, and (for the raw-header-text evidence bcftools is
  measured against) position within the attribute list, since an unterminated value's closing search behaves
  differently at the end of a declaration than mid-list. A decision-bearing row always states this explicitly,
  as its own bullet. A descriptive-only row's heading already carries the route and payload and serves as its
  probe context, so most do not repeat it as a bullet; the four descriptive-only parse-path rows
  (23, 24, 31, 32) do state one anyway, since the exact raw header line matters there. Position does not apply to this family's
  `addXxx(Map)`/typed construction probes themselves: `findEncodingProblems` checks each already-assembled
  property value directly, not via a positional re-scan across attribute boundaries, so only the raw-header
  evidence (bcftools' own tokenizer, and this library's own parser) has a "position" dimension at all;
- a `4.2`/`4.3`/`4.4`/`4.5` spec cell, each a citation plus a verbatim quote, or the literal word `silent`;
- a `bcftools 1.23.1` cell naming the exact diagnostic bcftools produces. Only row 3 states a `vcf_validator`
  cell inline, pinned to the grammar its probe exercised (`vcf_validator 0.10.2 (4.3)`), because it is the one
  row where the two tools disagree; row 30's vcf_validator run is recorded in its fixture's `##Authority=` line
  rather than as a cell. No other row carries one: none of these rows' grammars falls into
  `BEHAVIOR_TABLE_STANDARD.md`'s list of known version-sensitive vcf_validator divergences, and no
  vcf_validator run exists for the rest, so a cell is omitted rather than guessed;
- a `Public construction` and `Validated write` outcome, `VcfMetadata.Builder`'s checked `addXxx`/`replaceXxx`
  routes and `VcfWriter.Builder.validateBeforeWrite()` respectively, named per `BEHAVIOR_TABLE_STANDARD.md`'s
  "Naming our paths" table -- `Parse` and `Default write` do not apply to this family, since every row here
  originates through public construction, never through the parser's exempt trusted routes (see "Why the
  parse path is exempt" above).

Each row also states whether it is **decision-bearing** (the throw/warn/accept line rests on measured
bcftools behavior against spec-silent input) or **descriptive-only** (fully explained by a spec quote or by
reading this library's own code, per `BEHAVIOR_TABLE_STANDARD.md`'s descriptive-only exemption) -- a
descriptive-only row still pins its evidence and identifies its probe via its own heading (route + payload),
but collapses the four spec columns to one sentence and does not require its own `bcftoolsDependencyTest`
case. Row 11 (`assembly=B36`) is this
table's control row: a plain, unambiguous value that must, and does, come back clean under every path.

### Index

One line per row, for scanning; each row's own sub-section below carries the citations, probe shape, and
evidence. `Payload` is a paraphrase, not the literal probe text. (Row 15 does not exist -- see row 16.)

| Row | Route | Payload | Classification | Outcome |
|---|---|---|---|---|
| [1](#row-1----addcontigmap----assemblyab-unquoted-comma) | `addContig(Map)` | unquoted `,` in a value | Decision-bearing | throws at construction; throws at validated write (`unreadableOutput`) |
| [2](#row-2----addcontigmap----assemblyab-unquoted-) | `addContig(Map)` | unquoted `=` in a value | Descriptive-only | succeeds -- `=` is not checked at all |
| [3](#row-3----addcontigmap----assemblyab-unescaped-non-leading-quote) | `addContig(Map)` | unescaped, non-leading `"` in a value | Decision-bearing | throws at construction; warns at validated write |
| [4](#row-4----addcontigmap----assemblyab--assemblyab-unquoted-) | `addContig(Map)` | unquoted `<` or `>` in a value | Decision-bearing | throws at construction; warns at validated write |
| [5](#row-5----addcontigmap----assemblyab-unterminated-bracket-value) | `addContig(Map)` | leading `[` with no `]` anywhere | Decision-bearing | throws at construction and at validated write (bcftools-fatal) |
| [6](#row-6----addcontigmap----assemblyab--assemblya-junk-bracket-closes-trailing-junk) | `addContig(Map)` | bracket closes, then trailing junk | Decision-bearing | throws at construction; warns at validated write |
| [7](#row-7----addcontigmap----assemblywholegenome-exome-well-formed-bracket-shape-outside-values) | `addContig(Map)` | well-formed bracket list outside `Values` | Decision-bearing | throws at construction and at validated write (an ordinary unquoted comma) |
| [8](#row-8----addmetamap----valueswholegenome-exome-well-formed-bracket-value) | `addMeta(Map)` | well-formed bracket list on `Values` | Descriptive-only | succeeds -- the one scope bracket quoting applies to |
| [9](#row-9----addcontigmap----assemblyab-quoted) | `addContig(Map)` | quoted value containing `,` | Descriptive-only | succeeds; round-trips through default write |
| [10](#row-10----addcontigmap----assemblyab-quoted) | `addContig(Map)` | quoted value containing `=` | Descriptive-only | succeeds |
| [11](#row-11----addcontigmap----assemblyb36-this-tables-control-row) | `addContig(Map)` | plain value (this table's control row) | Descriptive-only | succeeds on every path, no warning |
| [12](#row-12----addcontigid-length-assembly-md5-species-taxonomy-url-----in-assemblymd5taxonomyurl) | `addContig` (typed) | `,` in `assembly`/`md5`/`taxonomy`/`url` | Descriptive-only | throws -- each is raw-stored, so each reaches row 1's check |
| [13](#row-13----addcontig-----in-species) | `addContig` (typed) | `,` in `species` | Descriptive-only | succeeds -- `species` is auto-quoted on the way in |
| [14](#row-14----addinfoid-description------in-description) | `addInfo` (typed) | `,` in `description` | Descriptive-only | succeeds -- `description` is auto-quoted on the way in |
| [16](#row-16----any-of-the-six-iddescription-shaped-kinds-typed-addxxx-----in-id) | six kinds' typed `addXxx` | `<` in `id` | Descriptive-only | throws at construction (the one raw-stored shared argument) |
| [17](#row-17----addcontigmap----a-b-1abc-or-a-b-as-a-property-name) | `addContig(Map)` | property NAME outside the allowlist | Decision-bearing | throws at construction; warns at validated write |
| [18](#row-18----addcontigmap----name_1-or-_xy1-as-a-property-name) | `addContig(Map)` | spec-conformant property NAME | Descriptive-only | succeeds -- both match the allowlist |
| [19](#row-19----replacecontigmap--replacecontigtyped----unquoted-) | `replaceContig` (Map + typed) | unquoted `,` in a value | Descriptive-only | throws on both replace routes |
| [20](#row-20----replacecontigmap----a-valid-replacement-eg-assemblygrch38-after-a-prior-valid-addcontig) | `replaceContig(Map)` | a valid replacement value | Descriptive-only | succeeds |
| [21](#row-21----replaceinforeplaceformat-typed-----in-number) | `replaceInfo`/`replaceFormat` (typed) | `,` in `Number` | Descriptive-only | throws on both |
| [22](#row-22----addcontigmap----key--badkey-badkey-or-ab-unescaped-quote-in-a-name) | `addContig(Map)` | empty, comma-, equals-, or quote-bearing NAME | Descriptive-only | throws for all four, via the same name-allowlist branch |
| [23](#row-23----parse-contigid1abvlength100-unescaped-non-leading-quote-in-a-property-name) | Parse | non-leading `"` in a property NAME | Descriptive-only | parses, all attributes intact; warns that the name is invalid |
| [24](#row-24----parse-contigid1a-bvlength100-a-name-outside-the-allowlist) | Parse | NAME outside the allowlist | Descriptive-only | parses, all attributes intact; warns that the name is invalid |
| [25](#row-25----addcontigmap----key-null-via-a-linkedhashmap-not-mapof) | `addContig(Map)` | `null` property key | Descriptive-only | throws, but from `BaseMetadata.validateProperties()` first |
| [26](#row-26----addrawpropertyname-value-----in-name-eg-badname) | `addRawProperty` | `=` in `name` | Descriptive-only | throws at construction and at validated write |
| [27](#row-27----addrawpropertyname-value-----in-name-eg-badname----this-familys-control-row) | `addRawProperty` | `,` in `name` (this family's control row) | Descriptive-only | succeeds -- a generic line never splits on a comma |
| [28](#row-28----addrawpropertyname-value-----in-value-eg-ab) | `addRawProperty` | `=` in `value` | Descriptive-only | succeeds; round-trips unchanged (split on the FIRST `=`) |
| [29](#row-29----addrawpropertyname-value-----in-value-eg-ab) | `addRawProperty` | `,` in `value` | Descriptive-only | succeeds; round-trips unchanged |
| [30](#row-30----parse-descriptionab-unescaped-quote-inside-a-quoted-value) | Parse | unescaped `"` inside a quoted value | Decision-bearing | parses, recovers `"a"`, warns about the trailing content; bcftools instead drops the whole declaration |
| [31](#row-31----parse-descriptionab-unquoted-value-ending-in-a-quote) | Parse | unquoted value ending in `"` | Descriptive-only | parses, recovers the value in full; warns twice |
| [32](#row-32----parse-descriptionabsources-leading-unclosed-quote) | Parse | leading, unclosed `"` | Descriptive-only | parses, absorbs the following attribute; warns; stable on re-emission |
| [33](#row-33----parsedtobuilderbuild-for-any-of-the-three-description-parses-above) | `toBuilder().build()` | any of rows 30-32's recovered parses | Descriptive-only | succeeds, with the identical recovered raw properties |

### Row 1 -- `addContig(Map)` -- `assembly=a,b` (unquoted comma)

- **Classification:** Decision-bearing, but split (per `BEHAVIOR_TABLE_STANDARD.md`'s Step 2 -- do not conflate
  a spec-plain observation with a tool-derived severity): that an unquoted comma ends the value there is
  spec-plain (the attribute list is comma-delimited and no version defines an escape for a literal comma); that
  this specific truncation makes the declaration `unreadableOutput=true` (rather than merely warn-worthy, like
  `<`/`>`/`"` below) is bcftools-measured.
- **Probe shape:** `##contig`, attribute name `assembly`, as the sole non-`ID` attribute in the `addContig(Map)`
  probe (no "position" dimension applies to the Map-argument probe itself, per the note above). The closest raw
  -header-text evidence instead measures the same underlying mechanism on `##contig`'s `ID` attribute, NOT last
  in the list (`length=1000000` follows): `##contig=<ID=chr,1,length=1000000>`.
- **4.2:** `silent` -- VCFv4.2.tex has no general "structured meta-information line" grammar statement at all
  (only worked examples per declaration type, e.g. `VCFv4.2.tex:69`'s `##INFO=<ID=ID,Number=number,...>`); no
  escape mechanism for a comma in an attribute value is described anywhere in it.
- **4.3:** `VCFv4.3.tex:117` -- "A *structured* meta-information line is similar, but the value is itself a
  comma-separated list of key=value pairs, enclosed within `<' and `>' characters" -- states the comma
  delimiter but defines no escape mechanism for a literal comma inside an unquoted value.
- **4.4:** `VCFv4.4.tex:117` -- identical wording to the 4.3 citation above.
- **4.5:** `VCFv4.5.tex:115` -- identical wording to the 4.3 citation above (line number shifts by two; text
  unchanged).
- **bcftools 1.23.1:** measured on the closest available shape (a comma inside `##contig`'s `ID`, not
  `assembly`): `##contig=<ID=chr,1,length=1000000>` -> `"Could not parse the header line"`, the whole
  declaration dropped, file otherwise readable -- the same non-fatal outcome
  `testMetadataAttributeNameOutsideAllowlistBcftoolsDropsDeclarationSilently`/
  `testBracketValueWithTrailingJunkBcftoolsDropsDeclarationSilently` independently confirm at `exitCode == 0`
  for the identical diagnostic message. htslib 1.23.1's own scanner
  (`~/Dev/htslib/vcf.c:733`, `if ( *q==',' && nopen==1 ) break;`, tag `1.23.1`) always ends an unquoted value at
  the first unescaped comma regardless of which attribute carries it, so this generalizes from `ID` to
  `assembly`.
- **Public construction:** throws (`checkMetadataPropertyEncoding` throws for every problem, unconditionally).
- **Validated write:** throws (`unreadableOutput=true` -- the attribute-list tokenizer can never itself produce
  a *stored* value containing an unquoted comma, since it always terminates the value there first, so throwing
  here can never reject carried-forward content; see `VCF_SPEC_AMBIGUITIES.md`'s "The unquoted comma throws for
  a weaker, different reason" paragraph).
- **Test evidence:** `MetadataPropertyEncodingTest#testAddMapRejectsEachUnquotedDelimiter` (construction);
  `BcftoolsDependencyTest#testUnquotedCommaInMetadataAttributeValueBcftoolsDropsDeclaration` (a plain,
  non-`ID` `assembly=a,b` shape, asserting `exitCode` -- closes the evidence gap this row previously
  recorded; `#testContigIdCharacterSetBcftoolsRejectsCommaSameAsChromDoes` measures the same underlying
  mechanism via `##contig`'s `ID` specifically, a different decision).

### Row 2 -- `addContig(Map)` -- `assembly=a=b` (unquoted `=`)

- **Classification:** Descriptive-only -- a pure code-reading fact about `InternalUtils.findEncodingProblems`'s
  own definition, no tool measurement needed: `=` does not appear in the classifier's literal-value branch at
  all, matching the attribute-list tokenizer's own name/value split (the name's first `=` is already consumed
  before the value is ever scanned, so a later `=` inside the value has nothing left to collide with).
- **Outcome:** succeeds, at both Public construction and Validated write.
- **Test evidence:** `MetadataPropertyEncodingTest#constructionAcceptsAnEqualsSignInAValue`.

### Row 3 -- `addContig(Map)` -- `assembly=a"b` (unescaped, non-leading quote)

- **Classification:** Decision-bearing -- the throw-at-construction-but-warn-at-write split rests on bcftools'
  own measured leniency for this shape (the file stays readable), not on spec text.
- **Probe shape:** `##contig`, attribute name `assembly`, non-leading quote (the first byte after `ID=1,` is
  `a`, not `"`); raw-header probe: `##contig=<ID=1,assembly=a"b,length=100>` (`assembly` not last;
  `length=100` follows).
- **4.2:** `silent` on this exact shape; `VCFv4.2.tex:81` only states double-quote "**can** be escaped" (not
  "must"), scoped to `Description`/`Source`/`Version` values specifically, not to an arbitrary attribute's
  unquoted value.
- **4.3:** `VCFv4.3.tex:173` -- "Double-quote character must be escaped with backslash $\backslash$ and
  backslash as $\backslash\backslash$." -- the spec's only escaping mechanism, but it says nothing about a
  *non-leading* quote in an otherwise-unquoted value, which is not "escaping" a quoted string at all.
- **4.4:** `VCFv4.4.tex:176` -- identical wording to the 4.3 citation above.
- **4.5:** `VCFv4.5.tex:174` -- identical wording to the 4.3 citation above.
- **bcftools 1.23.1:** accepts `assembly=a"b` unchanged, exit 0, no warning or error to stderr -- htslib
  1.23.1's unquoted-value scan (`vcf.c:711-736`) special-cases only `<`, `>`, and `,`, never `"`.
- **vcf_validator 0.10.2 (4.3):** disagrees with bcftools -- reports the file invalid, `"Line 2: Error in
  contig metadata."`, apparently rejecting any embedded unescaped `"` in an unquoted attribute value outright.
  Neither known 0.10.2 token-capture defect applies here (no backslash-escaped quote, so the token-parity
  defect's blast radius is not in play), so this is a genuine tool disagreement, not a defect artifact; per
  `AGENTS.md`'s Decision Hierarchy, bcftools remains the tie-breaker.
- **Public construction:** throws (`checkMetadataPropertyEncoding` throws for every problem).
- **Validated write:** warns, not throws -- not `unreadableOutput`, since the value still closes on re-parse,
  just not where the caller intended.
- **Test/fixture evidence:** `MetadataPropertyEncodingTest#testAddMapRejectsEachUnquotedDelimiter` (construction);
  `BcftoolsDependencyTest#testContigUnquotedValueNonLeadingQuoteBcftoolsAcceptsSilently`;
  `local-fixtures/passed/contig-unquoted-value-non-leading-quote.vcf` (carries both tools' `##Authority=`
  citations verbatim, measured live 2026-08-15).

### Row 4 -- `addContig(Map)` -- `assembly=a<b` / `assembly=a>b` (unquoted `<`/`>`)

- **Classification:** Decision-bearing -- the spec never defines escaping for `<`/`>` in an attribute value;
  the warn-not-throw-at-write classification rests on bcftools' measured leniency (the file stays readable).
- **Probe shape:** `##contig`, attribute name `description`, not last in the list (`length=100` follows); raw
  -header probes: `##contig=<ID=1,description=a<b,length=100>` and `##contig=<ID=1,description=a>b,length=100>`.
- **4.2 - 4.5:** `silent` on escaping `<`/`>` in a generic attribute value in all four versions. The only
  angle-bracket text any version carries is scoped elsewhere and does not generalize: `VCFv4.3.tex:200`/
  `VCFv4.4.tex:209`/`VCFv4.5.tex:258` ("ID values are case sensitive strings and must not contain whitespace,
  commas or angle brackets") governs the **`##ALT` `ID`** field specifically, and `VCFv4.3.tex:265`/
  `VCFv4.4.tex:279`/`VCFv4.5.tex:328` ("In particular, excluding commas facilitates parsing `##contig` lines,
  and excluding the characters `<>[]` and initial `*` avoids clashes with symbolic alleles") governs
  **contig names**, not a generic structured attribute's value. VCFv4.2.tex has neither of these two
  clauses at all -- its own `##ALT` `ID` rule (`VCFv4.2.tex:105`) reads only "may not contain whitespace or
  angle brackets" (no comma exclusion yet), and it has no contig-name character-set rule of any kind.
- **bcftools 1.23.1:** `<` increments htslib's own `nopen` bracket-nesting counter
  (`~/Dev/htslib/vcf.c:730`, `if ( *q=='<' ) nopen++;`), which can make it scan past the declaration's own
  closing `>` -- measured: `##contig=<ID=1,description=a<b,length=100>` produces `"Incomplete header line"`,
  file still reads at exit 0. `>` decrements the same counter
  (`vcf.c:731`, `if ( *q=='>' ) nopen--;`), which can close the declaration early -- measured:
  `##contig=<ID=1,description=a>b,length=100>` truncates the declaration to
  `##contig=<ID=1,description=a>` with `"Dropped trailing junk from header line"`, `length` silently lost, file
  still reads at exit 0.
- **Parse:** parses; warns "has an unquoted value \"a<b\" containing a character bcftools treats as
  structural; preserving it" (`InternalUtils.parseOneAttribute`'s own unconditional check,
  `InternalUtils.java:504-505` -- the same check Row 31 below documents for an unquoted trailing quote); this
  is a SEPARATE, unconditional check from the `EncodingProblem`/construction/`validateBeforeWrite()`
  machinery the rest of this row describes, and it runs on every parse regardless of `isLenient()`. Filed
  originally as a gap in `VCF_SPEC_AMBIGUITIES.md`'s own "Unquoted metadata attribute value..." entry (see
  `TODO.md`), whose prose read, by omission, as if parse were silent for this shape -- it isn't, and this row
  is the parallel gap in this document that the same filed item also names.
- **Public construction:** throws (`checkMetadataPropertyEncoding` throws for every problem).
- **Validated write:** warns, not throws -- not `unreadableOutput` (bcftools garbles or truncates the
  declaration but the file stays readable).
- **Test evidence:** `MetadataPropertyEncodingTest#testAddMapRejectsEachUnquotedDelimiter`/
  `#constructionRejectsAngleBracketsAndLayoutWhitespaceInALiteralValue` (construction);
  `#diagnosticsModeWarnsRatherThanThrowingOnAnUnquotedAngleBracket` (write);
  `BcftoolsDependencyTest#testUnescapedLessThanInMetadataValueBcftoolsMisparsesButSurvives`/
  `#testUnescapedGreaterThanInMetadataValueBcftoolsTruncatesDeclaration`.

### Row 5 -- `addContig(Map)` -- `assembly=[a=b` (unterminated bracket value)

- **Classification:** Decision-bearing -- the spec has no general prose defining bracket-list quoting at all
  outside `##META`'s own worked example (see row 8), so what an unterminated bracket does is bcftools-measured,
  not spec-derived.
- **Probe shape:** `##contig`, attribute name `x`, LAST attribute before the declaration's closing `>` (an
  unterminated closing search behaves differently at the end of a declaration than mid-list -- there is
  nothing left to swallow); raw-header probe: `##contig=<ID=1,length=100,x=[a=b>`.
- **4.2:** `silent` -- `##META` (the only declaration type any version shows using `[...]`) does not exist as a
  declaration type at all before VCFv4.3.
- **4.3 - 4.5:** `silent` on error-handling for an unterminated bracket. `VCFv4.3.tex:273`
  (`##META=<ID=Assay,Type=String,Number=.,Values=[WholeGenome, Exome]>`) and its 4.4/4.5 counterparts
  (`VCFv4.4.tex:287`, `VCFv4.5.tex:336`) show only the well-formed shape, with no surrounding prose describing
  the bracket form's grammar or its failure modes.
- **bcftools 1.23.1:** a bracket value with no closing `]` before end of line makes bcftools abort the ENTIRE
  file (`"[E::bcf_hdr_parse_line] Missing ']' in header line"`, non-zero exit) -- unlike every other malformed
  shape in this family, which bcftools recovers from at exit 0. This is arguably bcftools' own defect, filed
  upstream at [samtools/htslib#2065](https://github.com/samtools/htslib/issues/2065), but this library does not
  attempt to route around it.
- **Public construction:** throws (checked UNCONDITIONALLY, before the `##META`'s-`Values`-only gate).
- **Validated write:** throws -- the absolute floor a warn branch would violate, since the file becomes
  genuinely unreadable; this check runs before the `Values`-only gate for exactly that reason.
- **Test evidence:** `MetadataPropertyEncodingTest#constructionRejectsABracketValueOutsideMetaValues`
  (construction); `#diagnosticsModeStillThrowsWhenOutputWouldBeUnreadable` (write, reached only via the
  package-private `trustedMutableProperties()` bypass, since the tokenizer can never itself produce this as a
  stored value -- see "Where write-time and construction-time checks meet" above);
  `BcftoolsDependencyTest#testUnterminatedBracketValueBcftoolsHardErrors`.

### Row 6 -- `addContig(Map)` -- `assembly=[a]b]` / `assembly=[a] junk` (bracket closes, trailing junk)

- **Classification:** Decision-bearing, for the same reason as row 5 -- no spec prose defines this grammar's
  failure modes outside `##META`'s `Values`; the warn-not-throw-at-write classification is bcftools-measured.
- **Probe shape:** `##contig`, attribute name `x`, LAST attribute before the closing `>`; raw-header probe:
  `##contig=<ID=1,length=100,x=[a]b]>`.
- **4.2 - 4.5:** `silent`, for the identical reasons given under row 5 (4.2: `##META` does not exist yet; 4.3-4.5:
  the worked example shows only the well-formed shape).
- **bcftools 1.23.1:** the bracket DOES close, but is followed by non-comma junk before the next attribute (or
  the declaration's own `>`) -- bcftools survives this at exit 0 but drops the whole declaration
  (`"Could not parse the header line"`), the same non-fatal-but-silent-loss outcome row 17's invalid-name case
  gets.
- **Public construction:** throws (every problem throws there, regardless of severity).
- **Validated write:** warns, not throws -- NOT `unreadableOutput` outside `##META`'s `Values` (it has a `]`
  somewhere, so it clears the universal unterminated-floor and falls through to the generic "unquoted
  structural delimiter" check instead of the `Values`-only malformed-bracket check; on `##META`'s `Values`
  itself, this identical shape stays `unreadableOutput=true`, since the tokenizer can never produce it as a
  stored value there either, so the extra caution costs nothing -- see row 8).
- **Test evidence:** `MetadataPropertyEncodingTest#constructionRejectsABracketValueOutsideMetaValues`
  (construction); `#diagnosticsModeWarnsRatherThanThrowingOnABracketOutsideMetaValues` (write);
  `BcftoolsDependencyTest#testBracketValueWithTrailingJunkBcftoolsDropsDeclarationSilently`.

### Row 7 -- `addContig(Map)` -- `assembly=[WholeGenome, Exome]` (well-formed bracket shape, outside `Values`)

- **Classification:** Decision-bearing -- whether this reduces to "an ordinary unquoted comma" outside `##META`'s
  `Values` rests on the same bcftools-measured floor as rows 5-6: bcftools' own generic bracket handling is
  unaffected by this library's narrower, `Values`-only quoting scope.
- **Probe shape:** `##contig`, attribute name `assembly`, sole non-`ID` attribute (Map-argument probe -- no
  position dimension applies, per the table intro); this is the spec's own `##META` `Values` shape
  (`VCFv4.3.tex:273`) applied to a non-`##META`, non-`Values` attribute, which this library treats identically
  to any other unquoted `[`-leading value once it clears the universal unterminated-bracket floor.
- **4.2:** `silent` -- `##META`/`Values` does not exist yet, and no other version-4.2 text discusses
  bracket-list quoting on any attribute.
- **4.3 - 4.5:** `silent` on whether bracket-list quoting extends beyond `##META`'s `Values` to any other
  attribute or declaration type -- the worked example (`VCFv4.3.tex:273` and its 4.4/4.5 counterparts) is
  scoped to `##META`'s `Values` by its own placement in the `##META` subsection, and no other structured-line
  text in any version mentions `[`/`]` at all.
- **bcftools 1.23.1:** the regression-guard test below round-trips this exact shape through this library's own
  parser/writer against a real bcftools binary: `##contig=<ID=c1,x=[a, b],length=999>` truncates at parse time
  to a dangling `x=[a` (well-formed input, this library's own comma-splitting); writing it raw would produce a
  value bcftools cannot read at all (exit 255) -- confirming the well-formed shape's interior comma is
  bcftools-fatal once truncated outside `Values`, the reason construction rejects it unconditionally rather
  than risk ever emitting it.
- **Public construction:** throws (`[` is not comma-protecting outside `Values`, so this is an ordinary
  unquoted comma).
- **Validated write:** throws -- `unreadableOutput=true`, for the identical reason row 1's plain comma throws:
  this library's own re-parse of a stored value at this shape would misread it.
- **Test evidence:** `MetadataPropertyEncodingTest#constructionRejectsABracketValueOutsideMetaValues`
  (construction); `BcftoolsDependencyTest#testNonMetaValuesBracketTruncationDoesNotManufactureAnUnreadableHeader`
  (the parser/writer regression guard -- note this test measures the *truncation* consequence via this
  library's own parser/writer, not the raw well-formed shape via a hand-built header line the way rows 1, 4-6
  do; no separate hand-built-header bcftools probe of the exact well-formed `[WholeGenome, Exome]` value exists,
  since the truncation test already proves the point that matters -- what this library must never emit).

### Row 8 -- `addMeta(Map)` -- `Values=[WholeGenome, Exome]` (well-formed bracket value)

- **Classification:** Descriptive-only -- this is a statement about what this library's own code does with the
  spec's own worked example, not a tool-measured claim. No `bcftoolsDependencyTest` case is required for this
  row: `VCFv4.3.tex:273`'s own worked example already settles that this exact shape must succeed, independent
  of any tool's behavior.
- **Spec citation:** `VCFv4.3.tex:273` -- `##META=<ID=Assay,Type=String,Number=.,Values=[WholeGenome, Exome]>`
  (identically in `VCFv4.4.tex:287`/`VCFv4.5.tex:336`; `silent`/nonexistent in 4.2, since `##META` does not
  exist as a declaration type before 4.3).
- **Outcome:** succeeds -- the comma inside a well-formed bracket value on `##META`'s `Values` specifically is
  not checked at all. See `VCF_SPEC_AMBIGUITIES.md`'s "General bracket-list quoting beyond `##META`'s `Values`"
  entry -- this is the ONE case bracket-list quoting is scoped to.
- **Test evidence:** `MetadataPropertyEncodingTest#constructionAcceptsAWellFormedBracketValueOnMetaValuesOnly`.

### Row 9 -- `addContig(Map)` -- `assembly="a,b"` (quoted)

- **Classification:** Descriptive-only -- quoting a value is unambiguously fine under any reading of the spec;
  no tool measurement is needed to justify "succeeds". No `bcftoolsDependencyTest` case is required for this
  table entry: this Decision depends on the value being validly quoted (`InternalUtils.isQuoted`), not on
  bcftools.
- **Spec citation (collapsed, per the descriptive-only exemption):** all four spec versions agree that a
  double-quoted value is the normal, unambiguous form (e.g. `VCFv4.3.tex:153`'s own
  `Description="description"` template), so the four spec columns are not shown separately here.
- **Outcome:** succeeds; round-trips through default write + re-parse.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddMapAcceptsTheQuotedCounterparts`;
  `#testAnAcceptedDeclarationRoundTripsThroughTheDefaultWritePath`.

### Row 10 -- `addContig(Map)` -- `assembly="a=b"` (quoted)

- **Classification:** Descriptive-only, for the identical reason as row 9 -- a quoted value containing `=` is
  ordinary quoted content; `=` is not even checked in the unquoted (literal) branch, let alone the quoted one.
- **Outcome:** succeeds.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddMapAcceptsTheQuotedCounterparts`.

### Row 11 -- `addContig(Map)` -- `assembly=B36` (this table's control row)

- **Classification:** Descriptive-only -- a plain, unambiguous value with none of the characters any check in
  this family inspects. Per `BEHAVIOR_TABLE_STANDARD.md`'s "Include a control row" rule, this is the row that
  must come back clean under every path, distinguishing "everything is rejected" from "the harness is broken."
- **Outcome:** succeeds at Public construction and Validated write alike, with no warning from either path.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddMapAcceptsTheQuotedCounterparts` (adjacent assertion
  in the same test); also the shared `good` payload for `contig` in
  `testEveryPublicMapAddRouteStillAcceptsAValidDeclaration`'s route matrix.

### Row 12 -- `addContig(id, length, assembly, md5, species, taxonomy, url)` -- `,` in `assembly`/`md5`/`taxonomy`/`url`

- **Classification:** Descriptive-only -- which of `ContigMetadata`'s typed constructor arguments is stored via
  `putPropertyRaw` (unquoted, checked) vs. `putAndQuoteProperty` (auto-quoted, safe) is purely a statement about
  this library's own internal storage-path choice, not a tool-measured claim. The comma's own throw/warn
  classification is already established by row 1; this row is only about which *fields* reach that same check.
- **Outcome:** throws for `assembly`, `md5`, `taxonomy`, or `url` (each `putPropertyRaw`-stored, so each reaches
  the identical literal-branch comma check row 1 already establishes).
- **Test evidence:** `MetadataPropertyEncodingTest#testAddTypedRejectsAnUnquotedDelimiterInEveryRawStoredField`.

### Row 13 -- `addContig(...)` -- `,` in `species`

- **Classification:** Descriptive-only, for the same reason as row 12 -- `species` is the one contig argument
  `ContigMetadata`'s typed constructor routes through `putAndQuoteProperty` instead, which quotes it
  unconditionally on the way in, so no comma ever reaches the unquoted literal-branch check at all. This is a
  statement about which inserter the constructor calls, not about tool behavior.
- **Outcome:** succeeds; round-trips through default write + re-parse.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddTypedAcceptsADelimiterInContigsOwnAutoQuotedField`.

### Row 14 -- `addInfo(id, description, ...)` -- `,` in `description`

- **Classification:** Descriptive-only -- `InfoMetadata`'s typed constructor routes `description` through
  `putAndQuoteProperty` the same way `ContigMetadata`'s `species` does (row 13); this is the identical
  storage-path fact applied to a different kind, not a new tool-measured claim.
- **Outcome:** succeeds; round-trips through default write + re-parse.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddTypedAcceptsADelimiterInAnAutoQuotedField`.

### Row 16 -- any of the six `id`/`description`-shaped kinds' typed `addXxx` -- `<` in `id`

(Row 15 -- the retired `=`-in-`id` probe, which this row's `<` probe replaced -- is deleted entirely, not
renumbered: it was fully redundant with this row, which already documents `<` as the current NAME-shape probe.
Rows keep their original numbers below rather than closing the gap, so this document's numbering stays stable
for anything that cites a row by number.)

- **Classification:** Descriptive-only -- `id` is the one raw-stored typed argument every one of the six kinds
  (ALT, FILTER, FORMAT, INFO, contig, SAMPLE; `META` and `PEDIGREE` have no typed overload of this shape) shares,
  stored via `putPropertyRaw` into the `ID` property exactly like `##contig`'s `assembly` argument is (row 4).
  Once stored, `findEncodingProblems`'s literal-branch `<`/`>` check applies identically regardless of which
  attribute name carries the value -- htslib's own scanner (`vcf.c:730-731`) is attribute-name-agnostic for
  `<`/`>`, so this row reuses row 4's bcftools evidence rather than re-deriving it. No new
  `bcftoolsDependencyTest` case is added for this row: it is purely descriptive, and this Decision depends on
  row 4's already-established classification, not on a fresh bcftools measurement of the `id`/`ID` field
  specifically.
- **Outcome:** throws, at Public construction (`id` is the one raw-stored argument every typed route shares,
  which is why this row is the table's typed-route NAME-shape probe; an `=` would not do, since `=` is not
  checked at all -- see row 2).
- **Test evidence:** `MetadataPropertyEncodingTest#testAddTypedRejectsAnAngleBracketInTheId`;
  `#testEveryPublicTypedAddRouteRejectsAnUnquotedDelimiter` (all six kinds).

### Row 17 -- `addContig(Map)` -- `a-b`, `1abc`, or `a b` as a property NAME

- **Classification:** Decision-bearing -- `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN` (the allowlist
  itself) is bcftools-derived, not spec-derived.
- **Probe shape:** `##contig`, three separate attribute names each probed individually as the sole non-`ID`
  attribute (Map-argument probe; no position dimension). Raw-header equivalent already measured:
  `##contig=<ID=1,a-b=v,length=100>` (`a-b` not last in the raw-text probe; `length` follows).
- **4.2:** `silent` -- no character-set constraint on a structured attribute's NAME is stated anywhere in
  VCFv4.2.tex.
- **4.3:** `silent` on THIS grammar specifically -- `VCFv4.3.tex:119`'s structured-line template
  (`##key=<key=value,key=value,...>`) leaves the inner `key` an undefined nonterminal, stating no character
  set. (`VCFv4.3.tex:368`'s `"INFO keys must match the regular expression
  ^([A-Za-z_][0-9A-Za-z_.]*|1000G)$"` governs a DIFFERENT grammar -- the per-record INFO column's own
  semicolon-separated subfield keys -- and must not be conflated with this metadata-declaration
  attribute-list NAME grammar, even though the character class happens to coincide.)
- **4.4:** `silent`, identically to 4.3 -- `VCFv4.4.tex:117`'s template is unchanged; the coincidentally
  similar-but-distinct INFO-key grammar is at `VCFv4.4.tex:384`.
- **4.5:** `silent`, identically to 4.3/4.4 -- `VCFv4.5.tex:115`'s template is unchanged; the INFO-key grammar
  equivalent is at `VCFv4.5.tex:433`.
- **bcftools 1.23.1:** discards the WHOLE declaration for an attribute name outside
  `^[A-Za-z_][0-9A-Za-z_.]*$` (`"Could not parse the header line"`), but the file itself stays readable (exit
  0, every other declaration and every data line still emitted). htslib 1.23.1 carries this exact regex as a
  comment above its own scanner (`~/Dev/htslib/vcf.c:695`, `// ^[A-Za-z_][0-9A-Za-z_.]*$`), which
  `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN`'s own Javadoc cites directly.
- **Public construction:** throws (fails `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN`).
- **Validated write:** warns, not throws -- bcftools drops just the one declaration, file stays readable.
- **Test evidence:** `MetadataPropertyEncodingTest#constructionRejectsANameOutsideTheAllowlist` (construction);
  `#diagnosticsModeWarnsRatherThanThrowingOnCarriedForwardContent`/`#aNameOutsideTheAllowlistSurvivesParsing`
  (write/parse); `BcftoolsDependencyTest#testMetadataAttributeNameOutsideAllowlistBcftoolsDropsDeclarationSilently`.

### Row 18 -- `addContig(Map)` -- `Name_1` or `_x.y1` as a property NAME

- **Classification:** Descriptive-only -- this restates row 17's already-established allowlist boundary from
  the accepting side; confirming the regex `^[A-Za-z_][0-9A-Za-z_.]*$` matches these two specific strings is a
  pure regex/code fact, not a new bcftools measurement. `Name_1` is additionally the spec's own literal example
  name (`VCFv4.3.tex:287`, `##PEDIGREE=<ID=SampleID,Name_1=Ancestor_1,...,Name_N=Ancestor_N>`), so this row also
  doubles as a positive control that a real spec-used name clears the allowlist. No new
  `bcftoolsDependencyTest` case is added: this Decision depends on row 17's classification, not a fresh
  measurement.
- **Outcome:** succeeds -- both match the allowlist.
- **Test evidence:** `MetadataPropertyEncodingTest#constructionAcceptsSpecConformantNames`.

### Row 19 -- `replaceContig(Map)` / `replaceContig(typed...)` -- unquoted `,`

- **Classification:** Descriptive-only -- `replaceXxx`'s object-taking implementation calls the identical
  `checkMetadataPropertyEncoding`/`findEncodingProblems` classifier the `addXxx` rows above already establish;
  only a comma is exercised on the replace path in this library's own test suite, since `"`/name-allowlist/
  bracket share the identical underlying check already proven on the `addContig` rows above (rows 1, 3, 5-7,
  17). No new `bcftoolsDependencyTest` case is added for this row: it reuses row 1's classification rather than
  re-deriving it for the replace path specifically.
- **Outcome:** throws, at both `replaceContig(Map)` and the typed overload.
- **Test evidence:** `MetadataPropertyEncodingTest#testReplaceMapRejectsAnUnquotedDelimiter`/
  `#testReplaceTypedRejectsAnUnquotedDelimiter`; `#testEveryPublicMapReplaceRouteRejectsAnUnquotedDelimiter`
  (all eight kinds' Map route).

### Row 20 -- `replaceContig(Map)` -- a valid replacement (e.g. `assembly=GRCh38`) after a prior valid `addContig`

- **Classification:** Descriptive-only -- a plain, unambiguous replacement value; this row's role is
  confirming the replace path doesn't reject every replacement outright, not measuring tool behavior.
- **Outcome:** succeeds.
- **Test evidence:** `MetadataPropertyEncodingTest#testAValidReplacementStillSucceeds`;
  `#testEveryPublicMapReplaceRouteStillAcceptsAValidReplacement`.

### Row 21 -- `replaceInfo`/`replaceFormat` (typed) -- `,` in `Number`

- **Classification:** Descriptive-only -- `Number` is the one other raw-stored typed field reachable on the
  replace path without disturbing the already-stored `ID` (ALT/FILTER/SAMPLE's typed replace routes have no
  such reachable raw-stored field at all, since their only other argument, `Description`, is auto-quoted -- see
  row 13/14's storage-path reasoning applied to the replace path). This reuses the identical comma
  classification rows 1/12 already establish; no new `bcftoolsDependencyTest` case is added.
- **Outcome:** throws, for both `replaceInfo` and `replaceFormat`.
- **Test evidence:** `MetadataPropertyEncodingTest#testTypedReplaceRoutesWithAReachableRawFieldRejectAnUnquotedDelimiter`.

### Row 22 -- `addContig(Map)` -- key `""`, `"bad,key"`, `"bad=key"`, or `"a\"b"` (unescaped quote in a NAME)

- **Classification:** Descriptive-only -- that all four shapes fail via the SAME name-allowlist branch is a
  statement about which branch of `findEncodingProblems` fires, derivable by reading the
  classifier: none of `,`, `=`, `"`, or an empty string matches
  `^[A-Za-z_][0-9A-Za-z_.]*$`. The underlying allowlist classification itself is row 17's decision, not a new
  one; this row only confirms which four inputs land in it.
- **Outcome:** throws, for all four key shapes, at Public construction.
- **Test evidence:** `MetadataPropertyEncodingTest#testRejectsEveryInvalidKeyShape`.

Rows 23-24 cover the two parse-path-only NAME probes on `##contig`. Row 25 restates the `null`-key shape.
Rows 26-29 cover `addRawProperty`'s own, deliberately different `##key=value` grammar (the "## The generic
`##key=value` grammar is deliberately different" section above already derives the underlying rule; these rows
pin the four probe shapes against it). Rows 30-32 cover the three parse-time `Description=...` quote-recovery
shapes. Row 33 covers `toBuilder()`.

### Row 23 -- Parse `##contig=<ID=1,a"b=v,length=100>` (unescaped, non-leading quote in a property NAME)

- **Classification:** Descriptive-only -- a pure code-reading fact about `InternalUtils.parseOneAttribute`'s own
  definition, no tool measurement needed: the NAME-scanning loop (`InternalUtils.java:404-406`, `while (nameEnd
  < limit && s.charAt(nameEnd) != '=') { nameEnd++; }`) has no quote-mode branch of any kind, unlike value
  parsing 20 lines later (`InternalUtils.java:426`, `if (first == '"' || ...)`). A quote character anywhere
  in a NAME -- leading or not -- is always ordinary content; there is no "leading quote absorbs the remainder"
  nuance for names the way there is for values (see "Severity ranking" above).
- **Probe shape:** `##contig`, property NAME `a"b`, not last in the declaration (`length=100` follows):
  `##contig=<ID=1,a"b=v,length=100>`.
- **Outcome (Parse):** parses; the re-parsed contig's properties are `{ID=1, a"b=v, length=100}`, all three
  attributes intact. It also warns "Metadata attribute name \"a\"b\" is not valid" --
  `InternalUtils.java:577-580`'s post-fold `METADATA_ATTRIBUTE_NAME_PATTERN` check runs against every surviving
  key unconditionally, regardless of which character made it fail, so this shape trips the identical parse-time
  name warning Row 24 documents (confirmed by tracing `foldAttributes`: the loop has no branch on which
  character caused the mismatch). Parsing accepting this shape does not soften Row 22's construction-time
  throw above, which still rejects the same shape (an unescaped quote in a NAME) unconditionally: the two are
  different paths, not a single rule stated twice.
- **Evidence gap, recorded rather than papered over:** no committed test currently pins the exact warning
  message for THIS shape (`a"b` as a NAME) -- `MetadataPropertyEncodingTest#testAnUnescapedQuoteInAPropertyNameNoLongerCorruptsFollowingAttributes`
  asserts only the recovered property map, and the general parse-time-name-warning mechanism is instead pinned
  on a different shape (`2>B`) by `InternalUtilsTest#anInvalidAttributeNameWarnsAtParseTimeAndIsStillStored`.
  Recorded here rather than filled, so it is not rediscovered as a new finding.
- **Test evidence:** `MetadataPropertyEncodingTest#testAnUnescapedQuoteInAPropertyNameNoLongerCorruptsFollowingAttributes`
  (recovered property map); `InternalUtilsTest#anInvalidAttributeNameWarnsAtParseTimeAndIsStillStored` (the
  general parse-time name-warning mechanism, measured on a different invalid-name shape).

### Row 24 -- Parse `##contig=<ID=1,a-b=v,length=100>` (a NAME outside the allowlist)

- **Classification:** Descriptive-only -- restates Row 17's already-established, bcftools-derived allowlist
  decision from the parse side: the identical `METADATA_ATTRIBUTE_NAME_PATTERN` check also runs at parse
  time (`InternalUtils.java:577-580`, in `foldAttributes`, post-fold, once
  per surviving key), warning rather than dropping the attribute -- matching `AGENTS.md`'s carried-forward-content
  policy for content a prior parse already accepted. No new `bcftoolsDependencyTest` case is added for this row:
  it reuses Row 17's classification rather than re-deriving it for the parse path specifically, the same pattern
  Rows 18/19/21/22 already use for reusing Row 17/1's classification.
- **Probe shape:** `##contig`, property NAME `a-b`, not last in the declaration: `##contig=<ID=1,a-b=v,length=100>`
  (the committed tests below use the equivalent `##contig=<ID=c1,a-b=v,length=999>` -- same shape, different
  literal ID/length values).
- **Outcome (Parse):** parses; the re-parsed contig's properties are `{ID=1, a-b=v, length=100}`, all three
  intact, plus a parse-time warning ("Metadata attribute name \"a-b\" is not valid") -- the allowlist is
  enforced on all three paths: Parse (warns, leaving the attributes intact), Public construction (throws,
  Row 22), and Validated write (warns, Row 17).
- **Test evidence:** `MetadataPropertyEncodingTest#aNameOutsideTheAllowlistSurvivesParsing` (value survival);
  `InternalUtilsTest#anInvalidAttributeNameWarnsAtParseTimeAndIsStillStored` (the general parse-time
  name-warning mechanism); `MetadataPropertyEncodingTest#diagnosticsModeWarnsRatherThanThrowingOnCarriedForwardContent`
  (the same `a-b` shape surviving `validateBeforeWrite()` too).

### Row 25 -- `addContig(Map)` -- key `null` (via a `LinkedHashMap`, not `Map.of`)

- **Classification:** Descriptive-only -- purely about which Java-level check fires first
  (`BaseMetadata.validateProperties()` vs. `findEncodingProblems`), not a tool-measured claim. Already explained
  in full in "The `null`-key shape is not new here..." above.
- **Outcome:** throws, but from `BaseMetadata.validateProperties()` (`BaseMetadata.java:139-143`) *before*
  `findEncodingProblems` ever runs -- a distinct message, "Metadata contains a null property name". A test
  asserting on a `null` key pins `validateProperties`'s message, not this check's; the `null`-name branch inside
  `findEncodingProblems` (`name == null` at `InternalUtils.java:1683`) is genuinely unreachable through any
  public construction route.
- **Test evidence:** `MetadataPropertyEncodingTest#testANullKeyIsRejectedByConstructionNotByThisCheck`.

Rows 26-29 share one classification, stated once here and referenced by each row rather than repeated: none of
the four is a case where a decision rests on measured tool behavior against spec-silent input
(`BEHAVIOR_TABLE_STANDARD.md`'s own test for when a behavior table is warranted at all). The unstructured
`##key=value` line IS defined by the spec (`VCFv4.3.tex:109`, "An *unstructured* meta-information line consists
of a key ... and a value ..., separated by an `=` character" -- identically `VCFv4.5.tex:107`/`VCFv4.4.tex:109`;
`silent`/undefined as its own named grammar in `VCFv4.2.tex`, whose closest text is line 57's "File
meta-information is included after the \#\# string and must be key=value pairs" -- no split mechanism or
character-set rule stated in any version), but no version of any of them says what happens if the KEY portion
itself contains a second `=` or a `,` -- that is purely a fact about `VcfParser.parseMetadata`'s own re-parse
mechanism (`VcfParser.java:551`, `int idx = line.indexOf("=");`, splitting on the FIRST occurrence only), fully
derivable by reading the code, not by measuring a tool. The "## The generic `##key=value` grammar is
deliberately different" section above already derives this exact rule in full (its own Shape/Re-parse/Rule
table); these four rows exist to pin the four concrete probe shapes against that already-derived rule, not to
re-derive it. No `bcftoolsDependencyTest` case is required for any of the four: this is a fact about
`VcfParser`'s own re-parse mechanism, not about bcftools.

### Row 26 -- `addRawProperty(name, value)` -- `=` in `name` (e.g. `"bad=name"`)

- **Classification:** Descriptive-only -- see the shared reasoning above.
- **Outcome:** throws at Public construction (`VcfMetadata.java:1428-1429`, `if (name.contains("=")) { throw new
  VcfFormatException("Raw metadata property name contains '=', ...`) -- an `=` in the name would shift
  `VcfParser.parseMetadata`'s first-`=` split point on re-parse (`##foo=bar=value` would come back as name
  `foo`, value `bar=value`). Also throws at Validated write (`VcfWriter.Builder.validateBeforeWrite()` already
  rejects exactly this, reached via a trusted bypass since `addRawProperty` itself already rejects it at
  construction) -- construction is not narrower than the writer here.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddRawPropertyRejectsAnEqualsSignInTheName` (construction);
  `VcfWriterTest#testValidateBeforeWriteRejectsEqualsSignInRawPropertyKey` (write, via a trusted bypass).

### Row 27 -- `addRawProperty(name, value)` -- `,` in `name` (e.g. `"bad,name"`) -- this family's control row

- **Classification:** Descriptive-only -- see the shared reasoning above.
- This is the table's control for the `addRawProperty` family specifically: it proves the structured
  attribute-list comma rule (Row 1; "Severity ranking" above, "A comma is still the one character an unquoted
  value has no way to escape") was NOT applied to this deliberately different, unstructured generic grammar,
  which has no comma-separated structure to protect at all.
- **Outcome:** succeeds -- `VcfParser.parseMetadata` never splits a generic line on a comma, so nothing there can
  be corrupted by one.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddRawPropertyAcceptsACommaInTheName`.

### Row 28 -- `addRawProperty(name, value)` -- `=` in `value` (e.g. `"a=b"`)

- **Classification:** Descriptive-only -- see the shared reasoning above.
- **Outcome:** succeeds; round-trips through default write + re-parse unchanged -- splitting on the FIRST `=`
  only means everything after it, including a second `=`, is returned intact as the value.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddRawPropertyValueKeepsDelimitersThroughAWriteAndReparse`.

### Row 29 -- `addRawProperty(name, value)` -- `,` in `value` (e.g. `"a,b"`)

- **Classification:** Descriptive-only -- see the shared reasoning above.
- **Outcome:** succeeds; round-trips through default write + re-parse unchanged -- there is no attribute-list
  structure here for a comma to end a value at.
- **Test evidence:** `MetadataPropertyEncodingTest#testAddRawPropertyValueKeepsDelimitersThroughAWriteAndReparse`
  (the same test method exercises both Row 28 and Row 29 in one pass, via two `addRawProperty` calls).

### Row 30 -- Parse `Description="a"b"` (unescaped quote inside a quoted value)

- **Classification:** Decision-bearing -- the parser's own recovery choice (close the value at the first
  unescaped `"` by position) is not spec-derived, and this row's bcftools divergence is already measured and
  fixture-pinned.
- **Probe shape:** `##FILTER`, attribute name `Description`, LAST attribute in the declaration:
  `##FILTER=<ID=q10,Description="a"b">`.
- **4.2:** `silent` on recovery from this shape -- `VCFv4.2.tex:81` states only that the double-quote "**can**
  be escaped with backslash", nothing about what happens when it is not.
- **4.3:** `silent` on recovery -- `VCFv4.3.tex:173`, "Double-quote character must be escaped with backslash
  $\backslash$ and backslash as $\backslash\backslash$." states the escaping requirement, not a recovery
  strategy for a value that violates it.
- **4.4:** `silent`, identically -- `VCFv4.4.tex:176`, same wording.
- **4.5:** `silent`, identically -- `VCFv4.5.tex:174`, same wording.
- **bcftools 1.23.1:** measured on `##INFO`, not `##FILTER`
  (`##INFO=<ID=XX,Number=1,Type=String,Description="a"b">`, `Description` still LAST in the declaration) --
  the underlying mechanism is htslib's declaration-type-agnostic quoted-value scan in `bcf_hdr_parse_line`,
  which never consults the declaration type, so this generalizes from `##INFO` to `##FILTER` the same way
  row 1 generalizes from `ID` to `assembly` and row 17's raw-header probe stands in for its Map probe.
  It diverges from this library -- drops the WHOLE declaration ("Could not parse the header
  line"), file stays readable at exit 0, rather than recovering a truncated `Description`. The htslib mechanism
  (the quoted-value scan closing at the first unescaped `"`, then the next attribute-name scan landing one
  character short of the true next token) is already cited in detail, with its own verified line numbers, by
  `BcftoolsDependencyTest#testInfoQuotedValueUnescapedInteriorQuoteBcftoolsDropsDeclaration`'s own comment --
  not repeated here to avoid a second, independently-drifting copy of the same htslib citation.
- **Outcome (Parse):** parses; warns "has trailing content \"b\"\" after its closing delimiter; discarding it"
  for the leftover `b"`; recovers `Description` as the well-formed `"a"` -- the value closes at the first
  unescaped `"` by position, not by unbalanced-quote parity.
- **Test/fixture evidence:** `MetadataPropertyEncodingTest#testParsingStillRecoversAnUnescapedQuoteWithAWarning`;
  `BcftoolsDependencyTest#testInfoQuotedValueUnescapedInteriorQuoteBcftoolsDropsDeclaration`;
  `local-fixtures/failed/info-quoted-value-unescaped-interior-quote.vcf` (carries both tools' `##Authority=`
  citations, bcftools 1.23.1 and vcf_validator 0.10.2, measured live 2026-08-15).

### Row 31 -- Parse `Description=ab"` (unquoted value ending in a quote)

- **Classification:** Descriptive-only -- a mechanical fact about this library's own recovery, not a claim
  about what bcftools does with this exact input: the value's first byte is neither `"` nor `[`, so it is
  literal from the start and nothing truncates it, regardless of what its last byte is. No
  `bcftoolsDependencyTest` case exists for this exact shape, and none is required: this row does not depend on
  bcftools behavior, only on `InternalUtils.parseOneAttribute`'s literal-value branch (`InternalUtils.java:500-507`).
- **Probe shape:** `##FILTER`, attribute name `Description`, LAST attribute in the declaration:
  `##FILTER=<ID=q10,Description=ab">`.
- **Outcome (Parse):** parses; warns **both** "has an unquoted value \"ab\"\" containing a character bcftools
  treats as structural; preserving it" (`InternalUtils.java:504-505`) and "... should be quoted but was: ab\""
  (`IdDescriptionMetadata.java:72`, the reserved-property quoting check that fires independently for
  `Description` specifically); recovers `Description` as `ab"` in full.
- **Test evidence:** `MetadataPropertyEncodingTest#testParsingStillRecoversAnUnescapedQuoteWithAWarning`.

### Row 32 -- Parse `Description="ab,Source="s"` (leading, unclosed quote)

- **Classification:** Descriptive-only -- a mechanical fact about this library's own recovery algorithm (the
  closing-quote search spans the whole remainder of the line, per "Severity ranking" above), not a claim about
  bcftools' behavior on this exact input. No `bcftoolsDependencyTest` case exists for this shape, and none is
  required: the point of this row is what THIS library's tokenizer does when it cannot find its own closing
  quote before the next comma, not a tool comparison.
- **Probe shape:** `##INFO`, attribute name `Description`, NOT last in the declaration (`Source` follows):
  `##INFO=<ID=XX,Number=1,Type=String,Description="ab,Source="s">`.
- **Outcome (Parse):** parses; warns "has trailing content \"s\"\" after its closing delimiter; discarding it"
  for the leftover `s"`; recovers `Description` as `"ab,Source="` -- absorbing the comma and the `=` by matching
  `Source`'s own opening quote as its closing one; there is no `Source` key in the result at all. This is stable
  on re-emission, not progressively degrading: `MetadataPropertyEncodingTest#testWritingParserRecoveredMetadataOnTheDefaultPathIsAFixedPointOnReparse`
  confirms writing this recovered value on the default path and re-parsing it again reaches the identical
  absorbed state, not a further-corrupted one.
- **Test evidence:** `MetadataPropertyEncodingTest#testParsingStillRecoversAnUnescapedQuoteWithAWarning`;
  `#testWritingParserRecoveredMetadataOnTheDefaultPathIsAFixedPointOnReparse`.

### Row 33 -- `parsed.toBuilder().build()` for any of the three `Description=...` parses above

- **Classification:** Descriptive-only -- a code-reading fact about the object-taking overloads' exemption
  (`toBuilder()`'s package-private `addXxx(md)` overloads never call `checkMetadataPropertyEncoding` at all),
  already explained in full in "Why the parse path is exempt" above ("`toBuilder()`'s package-private
  object-taking overloads" route).
- **Outcome:** succeeds, with the identical recovered raw properties -- the object-taking overloads' exemption is
  what makes this possible; without it, `toBuilder()` would reject metadata the library itself just accepted.
- **Test evidence:** `MetadataPropertyEncodingTest#testToBuilderRebuildsParserRecoveredMetadataWithoutThrowing`,
  which iterates all FOUR of that class's `RECOVERED_QUOTE_CASES` -- one more than the three tabled here. The
  fourth (`##INFO=<ID=XX,Number=1,Type=String,Description="a"b",Source="s">`, the variant of row 32's shape in
  which the leading quote DOES find its own closing quote and absorbs nothing) is not tabled separately
  because it recovers exactly as row 30 does: `Description` becomes the well-formed `"a"`, the leftover `b"`
  is warned about as trailing content, and `Source` survives intact.

The "round-trips through default write + re-parse" rows are not redundant with the "succeeds" rows above them:
a change that accepted a value at construction but still corrupted it on the way out would pass every
`assertDoesNotThrow`/`assertThrows` assertion in this table while `VcfWriter`'s default path (see "The defect"
above) still emitted a value other than what was passed in. Only the round-trip assertion proves the
accept-decision was actually correct.

## Known gap: an escaped quote in a property name

**An escaped quote in a property name round-trips cleanly through the PARSE path -- construction has always
rejected any quote in a name, escaped or not.** Parsing `##contig=<ID=1,a\"b=v,length=100>` (a literal
backslash-quote sequence in the NAME), writing the result on the default path, and re-parsing it again yields
`{ID=1, a\"b=v, length=100}` back -- all three attributes intact (verified live), though parsing emits a
name-allowlist warning for this shape (see below). That round-trip is a property of the PARSE path being
exempt from this check's throw/drop behavior (see "Why the parse path is exempt" above), not of the check
itself distinguishing escaped from unescaped: construction (`addContig(Map)` and every other checked route)
has never made that distinction -- neither `"` nor `\` is in `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN`'s
`[A-Za-z_][0-9A-Za-z_.]*`. bcftools 1.23.1 agrees with construction, not with the parse path, and rejects the
line outright:

```
[E::bcf_hdr_parse_line] Could not parse the header line: "##contig=<ID=1,a\\\"b=v,length=100>"
```

(bcftools's own message escapes the literal `\"` in the input line for display inside its own double-quoted
diagnostic string, hence `\\\"` rather than `\"`.)

Closing this is deliberately out of scope: the parse path is exempt from this check's throw/drop behavior
(see "Why the parse path is exempt" above), so a header whose property name already contains an escaped quote
still parses through today, with a parse-time name warning rather than silently. Closing the write-time gap
requires a parse-path decision first -- whether the parser itself should reject this shape outright -- which
has not been made. Recorded here so it is not rediscovered as a new finding; to be addressed separately.

**A property name containing any quote character -- escaped or not -- still fails
`InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN`'s allowlist, since neither `"` nor `\` is in
`[A-Za-z_][0-9A-Za-z_.]*`.** `checkMetadataPropertyEncodingForWrite` classifies an invalid name as
`unreadableOutput=false` (bcftools drops the one declaration but keeps reading the file), so it WARNS rather
than throws. Widening the parse path today to also reject this shape -- the still-open question above is
outright rejection, not detection, since parsing already warns -- would not make `validateBeforeWrite()`
throw on the newly-rejected content, because of the current construction/write split.

## Where this is implemented

- `InternalUtils.findEncodingProblems(String, Map<String, String>)` -- the single shared classifier both entry
  points below call, so the rule for what counts as a problem exists once. Returns a list of
  `InternalUtils.EncodingProblem(String message, boolean unreadableOutput)` records.
- `InternalUtils.checkMetadataPropertyEncoding(String, Map<String, String>)` -- the construction-time entry
  point: throws for every problem `findEncodingProblems` finds, unconditionally. Called by all 27 of
  `VcfMetadata.Builder`'s checked `addXxx`/`replaceXxx` routes.
- `InternalUtils.checkMetadataPropertyEncodingForWrite(String, Map<String, String>, VcfWarnings)` -- the
  `validateBeforeWrite()` entry point: throws only for a problem `unreadableOutput` marks, warns to the given
  sink otherwise, preserving the value unchanged either way. Called from `VcfWriter.validateMetadataProperties`,
  itself called once per declaration from `validateMetadataPropertiesForEntries`, itself called once per kind
  (INFO/FILTER/FORMAT/ALT/contig/SAMPLE/META/PEDIGREE) from `validateMetadataPropertyEncodings` -- which runs once
  per header, from `writeHeader` only, not from `writeLine` -- load-bearing, not just an optimization, because
  `checkMetadataPropertyEncodingForWrite` warns rather than throws on most problems: running it from every
  record (as `validateMetadata`, called from both `writeHeader` and `writeLine`, would) multiplies each
  warning by (bad properties) x (declarations) x (records) on a large file instead of emitting it once
  (`VcfWriter.validateMetadataPropertyEncodings`'s own Javadoc states this reasoning directly).
- `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN` -- the bcftools-derived attribute-NAME allowlist
  (`^[A-Za-z_][0-9A-Za-z_.]*$`) `findEncodingProblems` checks first.
- `InternalUtils.isWellFormedMetaValuesBracket(String)` -- the malformed-bracket-value check, scoped to
  `##META`'s `Values` (the only attribute bracket-list quoting applies to). Unlike the quoted-value form, this
  is a plain, unescaped scan (the spec defines no escape mechanism for `[`/`]`), not `indexOfUnescaped` --
  but it is the same plain scan the tokenizer's `isMetaValues` branch uses, so construction and parsing still
  cannot disagree about where a bracket value ends. A separate, unconditional check in `findEncodingProblems`
  (not gated to `Values`) rejects a leading `[` with no `]` anywhere on ANY attribute, since bcftools' own
  bracket handling is unaffected by this library's narrower acceptance scope.
- `InternalUtils.isQuoted`/`InternalUtils.containsUnescapedQuote` -- `findEncodingProblems`'s two other helpers,
  so every call site shares one implementation rather than risking two copies of a predicate this subtle
  drifting apart.
- `VcfMetadata.Builder`'s eight `addXxx` families and seven `replaceXxx` families -- each kind's typed and `Map`
  overload (where a typed one exists) calls `checkMetadataPropertyEncoding` against the constructed
  declaration's `getPropertiesRaw()`, never against the method's own arguments.
- `VcfMetadata.Builder`'s eight package-private `addXxxTrusted(Map)` siblings (`addAltTrusted`,
  `addFilterTrusted`, `addInfoTrusted`, `addFormatTrusted`, `addContigTrusted`, `addSampleTrusted`,
  `addMetaTrusted`, `addPedigreeTrusted`) -- skip the check; used exclusively by `VcfParser`.
- `VcfMetadata.Builder.addRawProperty(String, String)` -- applies the narrower, unconditional "reject `=` in
  the name only" rule instead of the structured one.
- `VcfParser.parseMetadataProperty` -- calls the eight trusted variants. `VcfParser.parseMetadata` -- calls
  `addRawProperty` for a generic property, and derives its `name` argument by splitting on the first `=`.
- `VcfMetadata.toBuilder()` -- re-adds every declaration through the package-private object-taking overloads
  (none of which calls this check), and re-adds every generic property through the same, unconditionally
  checked `addRawProperty`.
- `VcfWriter.validateMetadataPropertyEncodings`/`validateMetadataProperties` -- the write-time net for the two
  routes that still bypass the construction-time check: `BaseMetadata.trustedMutableProperties()`'s test-only
  bypass, and the parse path's own trusted/object-taking routes. `validateMetadataProperties` is a thin
  delegate to `checkMetadataPropertyEncodingForWrite`, not `checkMetadataPropertyEncoding`.
- `InternalUtils.tokenizeAttributeList`/`InternalUtils.parseAttribute` (sharing one per-attribute grammar via
  the private `parseOneAttribute`) -- the parse-side mechanism this whole document's "why the parse path is
  exempt" reasoning is about.
- `MetadataPropertyEncodingTest` -- the test class covering every route and shape described in this document.
- `AttributeListTokenizerTest` -- the test class covering the tokenizer's own per-attribute grammar (leading-
  byte classification, escape handling, junk-after-close recovery) referenced throughout this document.

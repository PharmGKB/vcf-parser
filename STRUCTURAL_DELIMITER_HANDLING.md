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

**Construction-time unconditionality is not the whole story any more.** The attribute-list tokenizer task
(`InternalUtils.checkMetadataPropertyEncoding`'s Task 3) split this single predicate into a construction-time
entry point (still unconditional -- every problem throws) and a `validateBeforeWrite()` counterpart,
`checkMetadataPropertyEncodingForWrite`, that throws only for a problem that would make the emitted FILE
unreadable by bcftools and warns otherwise. Both share one classifier, `InternalUtils.findEncodingProblems`,
so they cannot disagree about what counts as a problem, only about what to do with one -- see "Path asymmetry
closed" and the Reference table below for the specifics, and `VALIDATION_PHILOSOPHY.md`'s
originated-vs-carried-forward paragraph for why the two routes are allowed to differ at all.

## The defect

`VcfWriter` has no encoding step. `getAllProperties` (`VcfWriter.java:1201-1215`) emits each `##KEY=<...>` line
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
| `a=b` | ok | ok | **attribute absent**\* | silently **deleted**\* |
| `a"b` | ok | ok | **`a"b,md5=deadbeef,taxonomy=tx`**\* | silently **absorbs every following attribute**\* |
| `a\nb` | **throws** | -- | -- | already correct (line terminator, see below) |

The last row is not new: a line terminator (`\n`/`\r`) is already rejected at construction by
`InternalUtils.checkNoLineTerminator`, independent of this check -- see `VALIDATION_REFERENCE.md`'s
line-terminator bullet. The other three "silently ..." rows were the defect that originally motivated this
check.

\* **These two rows describe the retired two-pass splitter (`InternalUtils.splitTopLevel`/`splitProperty`),
not the current re-parse mechanism.** The attribute-list tokenizer (`InternalUtils.tokenizeAttributeList`,
`InternalUtils.parseAttribute`, sharing one per-attribute grammar via `parseOneAttribute`) that superseded it
finds each attribute's own boundary independently rather than tracking one "inside a quoted value" toggle
across the whole declaration, and no longer reproduces either defect: re-measured directly against
`##contig=<ID=1,length=100,assembly=a=b,md5=deadbeef,taxonomy=tx>` and the `a"b` equivalent,

- `a=b` now re-parses as `assembly=a=b` in full, with **no warning at all** -- the value's own `=` is ordinary
  content once the name's first `=` has been consumed, the same rule
  `AttributeListTokenizerTest#anEqualsSignInAValueIsOrdinary` pins.
- `a"b` now re-parses as `assembly=a"b` in full, `md5` and `taxonomy` both intact -- only a warning fires
  ("...containing a character bcftools treats as structural; preserving it"). Pinned by
  `local-fixtures/passed/contig-unquoted-value-non-leading-quote.vcf`, which also records bcftools 1.23.1's
  own silent acceptance of the same value as its `##Authority=`.

Only the `a,b` row still reproduces exactly as measured: `assembly` still truncates to `a` at the comma (a
comma is still the one character with no escaping mechanism in an unquoted value), though the leftover `b`
segment now produces its own "has no '='" warning rather than the old "Could not parse metadata attribute"
wrapper text.

**This did not change what `VcfMetadata.Builder` accepts, at the time it was written.** At that point,
`checkMetadataPropertyEncoding`'s predicate still rejected all three shapes -- `a,b`, `a=b`, and `a"b` -- at
`addContig(Map)` and every other construction route unconditionally, and loosening it to match the tokenizer's
improved recovery for `=` and `"` was named as a separate design decision, deliberately not made at that time.

**That decision was made in Task 3 of the attribute-list tokenizer design, for `=` only.** `=` no longer
appears anywhere in `InternalUtils.findEncodingProblems`'s classifier: an unquoted value may contain it freely,
both at construction and at write time. `"` was NOT loosened the same way -- an unescaped `"` is still a
problem (see the updated Reference table below), just no longer one that throws unconditionally everywhere:
construction still throws for it, but `validateBeforeWrite()` now warns, since a non-catastrophic unescaped
quote (one that still finds its own closing `"`, just not where the caller intended) leaves the file
bcftools-readable, unlike an unquoted comma or a malformed bracket value.

## Severity ranking: comma > everything else (current), historically unescaped quote > `=` > comma

The comma's primacy over `<`/`>` below -- that an unquoted value's own comma still ends it even inside an
angle-bracket span -- is now a recorded decision with its own entry: see `VCF_SPEC_AMBIGUITIES.md`'s "Angle
brackets are not a quoting form in a metadata attribute value".

**Under the current attribute-list tokenizer, only a comma still genuinely loses data for an otherwise
unquoted, single-attribute value.** Neither an unescaped `=` nor an unescaped `"` does anymore, because each
attribute's boundary is now found independently rather than via one toggle shared across the whole
declaration (see the corruption-matrix footnote above):

- **A comma** is still the one character an unquoted value has no way to escape: `attributeLimit`'s
  comma-bounded search (`InternalUtils.java`, shared by `tokenizeAttributeList` and `parseAttribute` via
  `parseOneAttribute`) always stops the value there, so `assembly=a,b` still truncates to `a`, and the `b`
  left over becomes its own bogus attribute (a "has no '='" warning, then dropped).
- **An `=`** in the value is ordinary content once the name's own first `=` has been consumed -- nothing
  truncates, nothing drops, no warning fires at all.
- **An unescaped `"`** only matters if it is the value's *first* byte after the optional leading-space skip
  (`AttributeListTokenizerTest#onlyALeadingDelimiterQuotes`: "a non-leading quote is an ordinary byte and
  protects nothing"). `assembly=a"b`'s quote is not leading, so the value stays entirely literal and the
  quote is inert past a warning. A *leading* quote does still matter, though not by absorbing subsequent
  attributes wholesale the old way: `tokenizeAttributeList`'s closing-quote search spans the *whole remainder*
  of the line, not just up to the next comma (the same whole-remainder search the bracket form's own version
  of this rule gets its dedicated test for, `AttributeListTokenizerTest#aMetaValuesClosingBracketIsSearchedForAcrossTheWholeRemainder`
  -- deliberate, so a `##META` example's own bracket value isn't falsely "unterminated" at its first interior
  comma; the quoted-value case shares the identical scan and has no separate test of its own). If a value opens with `"` and never finds its own closing `"` before the next
  attribute's opening `"`, that next attribute's opening quote is mistaken for the closing one, and everything
  in between -- including that next attribute's own name and `=` -- is absorbed. This is narrower than the
  retired splitter's defect (it requires a *leading*, unclosed quote specifically, not any non-leading one
  anywhere in the declaration) but not eliminated; see the `Description="ab,Source="s"` case in "Why the
  parse path is exempt" below for a live example, still guarded by `MetadataPropertyEncodingTest`'s third
  `RecoveredCase`.

**Historically (the retired two-pass splitter, `InternalUtils.splitTopLevel`/`splitProperty`), the ranking ran
the other way and every one of the three lost data:** a comma still truncated the value the same way; an `=`
made `splitProperty` see three parts where it required exactly two and dropped the *whole* attribute; and an
unescaped quote anywhere (leading or not) flipped one "inside a quoted value" toggle shared across the entire
declaration, silently absorbing every attribute after it, comma, `=`, and all. `assembly=a"b` under that
mechanism re-parsed to keys `[ID, length, assembly]` only, with `md5`/`taxonomy` both absent entirely.

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

**The deferred question this section used to leave open -- whether rejecting `=` and a non-leading `"` still
made sense now that the tokenizer no longer corrupts either one -- was answered by Task 3, and the two got
different answers.** `=` was dropped from the classifier entirely: an unquoted value may now contain it
freely, at construction and at write time alike, since the tokenizer's own name/value split already makes it
ordinary content with no corruption to guard against. An unescaped `"` was NOT loosened the same way -- it
remains a problem at construction (`checkMetadataPropertyEncoding` still throws unconditionally for it) -- but
it did stop being an *unconditional-everywhere* throw: `validateBeforeWrite()`'s counterpart now warns for it
instead, since `InternalUtils.EncodingProblem` classifies it as not `unreadableOutput` (the file stays
bcftools-readable even when one declaration's value is corrupted this way). See `VALIDATION_PHILOSOPHY.md`'s
originated-vs-carried-forward paragraph for why construction and `validateBeforeWrite()` are allowed to answer
this differently at all, and the Reference table below for exactly which shapes still throw everywhere versus
which now only throw at construction.

## Where the check runs

`InternalUtils.findEncodingProblems(String kind, Map<String, String> properties)`
(`InternalUtils.java:1656-1744`) is the shared classifier, called by both `InternalUtils.
checkMetadataPropertyEncoding` (`InternalUtils.java:1588-1592`, the construction-time entry point -- every
problem it finds throws) and `InternalUtils.checkMetadataPropertyEncodingForWrite`
(`InternalUtils.java:1604-1612`, the `validateBeforeWrite()` entry point -- throws only for a problem marked
`unreadableOutput`, warns otherwise). Since the attribute-list-tokenizer phase's Task 6, it selects a branch on
the property value's first byte -- exactly as the tokenizer itself does (see "Brackets: a second
value-quoting form" above) -- then checks within it. It rejects:

- a property **name** that does not match `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN`
  (`^[A-Za-z_][0-9A-Za-z_.]*$`, the bcftools-derived allowlist -- this also catches `null`, empty, and every
  shape the four-check list below this paragraph used to enumerate individually, since none of `,`, `=`, or
  `"` are in the allowlist's character set either);
- a value starting with `"` (the **quoted** branch) that is not validly quoted (`InternalUtils.isQuoted`,
  `InternalUtils.java:1760-1777`) -- and within that, one further split: a malformed value that also contains
  a comma is `unreadableOutput=true` (this library's own tokenizer recovers a value with no valid closing `"`
  by scanning for the next comma rather than the value's own end, so an embedded comma silently drops
  everything after it on re-parse -- see `EncodingProblem`'s own Javadoc); a malformed value with no comma is
  `unreadableOutput=false` (bcftools itself does not hard-fail on an unterminated leading quote, only warns
  and mangles the declaration);
- a value starting with `[` and containing no `]` anywhere (`InternalUtils.java:1707`) -- checked
  UNCONDITIONALLY, before the `##META`'s-`Values`-only gate below, since bcftools' own generic bracket
  handling still treats any leading `[` as opening a quoted span regardless of attribute/declaration type,
  and hard-fails (exit 255) if it never finds a closing `]`;
- beyond that universal floor, a value starting with `[` on `##META`'s `Values` specifically
  (`InternalUtils.java:1713`) that is not a well-formed bracket value (`InternalUtils.
  isWellFormedMetaValuesBracket`, `InternalUtils.java:1756` -- its first LITERAL `]`, no escape awareness,
  must be its last character); a leading `[` on any other attribute is not bracket-checked at all past the
  universal floor -- see "Brackets: a second value-quoting form, scoped to `##META`'s `Values`" above;
- otherwise (the **literal** branch, for a value starting with neither `"` nor `[`): an unescaped `,`, `<`,
  `>`, or `"` -- `InternalUtils.containsUnescapedQuote` (`InternalUtils.java:1792-1802`) is the helper behind
  the last of those -- or leading/trailing space.

`=` is deliberately NOT one of these (see "Severity: unconditional throw" above for why this changed in
Task 3), and a value inside a well-formed bracket pair is not checked for any of the above at all, including
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

The clearest example today is narrower than it used to be (see the severity-ranking section above: a
non-leading, non-final quote no longer absorbs anything), but a **leading, unclosed** quote still can, because
the closing-quote search spans the whole remainder of the line rather than stopping at the next comma.
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
a **caller supplying a clean value that only corrupts on the way out** -- the corruption matrix above.

**Unlike before, an empty property key is no longer reachable from parsing at all, and no longer part of what
this exemption needs to cover.** An attribute like `,=x,` inside a declaration's attribute list used to split
(via the retired `splitProperty`) into key `""` and value `"x"`, which `BaseMetadata.validateProperties()`
would let through (it only rejects a `null` key, not an empty one). `InternalUtils.parseOneAttribute` (shared
by `tokenizeAttributeList` and `parseAttribute`) now detects an empty name directly and drops the whole
attribute with a warning ("Metadata attribute has an empty name; dropping it") before it can ever reach a
properties map -- see `AttributeListTokenizerTest#degenerateAttributesAreDroppedIndividually`. Recorded here
so this is not mistaken for a still-open gap.

**Two routes are covered by the exemption, and both are needed -- neither substitutes for the other:**

1. **`VcfParser`'s eight trusted variants.** `VcfMetadata.Builder` has one package-private `addXxxTrusted(Map)`
   sibling per kind (`addAltTrusted`, `addFilterTrusted`, `addInfoTrusted`, `addFormatTrusted`,
   `addContigTrusted`, `addSampleTrusted`, `addMetaTrusted`, `addPedigreeTrusted`), each a thin wrapper that
   calls the same private `addXxx(properties, validateContent)` helper with `validateContent = false`.
   `VcfParser.parseMetadataProperty` (`VcfParser.java:611-648`) calls these trusted variants exclusively -- see
   the `switch` at lines 622-647 -- never the public, checked `addXxx(Map)` overloads. `VcfParser.parseMetadata`
   (`VcfParser.java:548-595`) is the caller one level up: for a generic property it splits the whole `##`
   line on the first `=` (`line.indexOf("=")`, line 550) and hands the two halves to `addRawProperty`, which
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

So `addRawProperty` rejects `=` in the *name* only (`VcfMetadata.java:1428-1431`, `if (name.contains("=")) {
throw new VcfFormatException("Raw metadata property name contains '=', ...`), with the message "Raw
metadata property name contains '=', which would shift the re-parse split point."

`VcfWriter.Builder.validateBeforeWrite()` already rejects exactly this, pinned by
`VcfWriterTest.testValidateBeforeWriteRejectsEqualsSignInRawPropertyKey`, whose own comment records the
corruption as verified live. Making construction narrower than the writer here would have preserved the same
default-path-vs-`validateBeforeWrite()` asymmetry this whole change exists to remove.

**No trusted variant is needed for `addRawProperty`.** `VcfParser` (`VcfParser.java:587` and `:593`, both
`mdBuilder.addRawProperty(propName, propValue)` -- the pre-4.3 `##META` fallthrough and the generic `default`
case respectively) derives `propName` by splitting the line on the first `=`
(the same mechanism the table above describes), so a parser-produced name can never itself contain a second
`=`. Unlike the eight structured routes, this check is safe to apply to the parser unconditionally, and does.

## Path asymmetry closed

Before this check existed, the two write paths disagreed about whether the same object was writable:
`VcfWriter.Builder.validateBeforeWrite()` already rejected all three corrupting shapes via
`VcfWriter.validateMetadataProperties`, while the default write path emitted them verbatim and lost data
silently. This check closes that gap at the point of construction, which is the earliest point
that can see the fully-assembled declaration `VcfWriter` will later serialize -- but `VcfWriter`'s write-time
check still has work to do: it remains the net for the two routes that reach a built `VcfMetadata` without
ever passing through this construction-time check. One is the package-private, test-only
`BaseMetadata.trustedMutableProperties()` bypass. The other is the parse path itself -- `VcfParser`'s own
`addXxxTrusted`/object-taking routes (see "Why the parse path is exempt" above) deliberately skip this check so
a parser-recovered value (e.g. a leading, unclosed quote that absorbed a later attribute's name and `=`) can
still be stored, and that same value is exactly what `validateBeforeWrite()` is left to catch at write time.
Neither route substitutes for the other.

**"Catch" no longer means "throw" uniformly, since Task 3.** `VcfWriter.validateMetadataProperties` now calls
`InternalUtils.checkMetadataPropertyEncodingForWrite`, not `checkMetadataPropertyEncoding` -- the same
classifier (`findEncodingProblems`), but a different response to what it finds: it throws only for a problem
marked `unreadableOutput` (an unquoted comma, a malformed bracket value, or -- added by the
attribute-list-tokenizer phase's Task 6 -- a malformed quoted value that itself contains a comma; see
`EncodingProblem`'s own Javadoc for why these throw for genuinely different reasons, not one shared one) and
warns for everything else (an invalid property name, a malformed quoted value with no comma, an unescaped
`<`/`>`/`"` in a literal value, or leading/trailing space), preserving the value unchanged either way.

The leading-unclosed-quote case this paragraph opened with (`Description="ab,Source="s"`, recovered as
`Description="ab,Source="`) is, perhaps counter-intuitively, not caught at write time at all -- confirmed
live, and not a regression from this task: `findEncodingProblems` selects its **quoted** branch for this
value on its leading `"` (the first byte, per "Where the check runs" above), and within that branch
`InternalUtils.isQuoted` returns `true` for it, so nothing is flagged -- exactly as the single pre-Task-3
predicate's own leading `!isQuoted(value) && (...)` guard already did, so this value was silently unchecked
at write time before Task 3 too. Neither refactoring the check into `findEncodingProblems`, nor later
re-selecting its branch on the leading byte rather than on `isQuoted`'s own well-formedness (Task 6), changed
this value's treatment -- only its surrounding code shape. The recovered value's own comma and `=` both fall
INSIDE its start/end `"` pair with no unescaped `"` between them, so `isQuoted` sees a validly-quoted string
and there is nothing left to flag -- and because it IS validly quoted, Task 6's new malformed-quoted-value
comma check (which only fires when `isQuoted` returns `false`) does not apply to it either. This is not a
silent-acceptance defect either: the parse path itself already warns for this exact input
(pinned in `MetadataPropertyEncodingTest`'s `RECOVERED_QUOTE_CASES`), and the value is genuinely safe to
re-emit exactly as stored (the whole point of "Why the parse path is exempt" above -- the absorption already
happened once, at parse time, and is a fixed point on re-emission), so a check whose entire job is spotting
encoding that would corrupt on the way OUT has nothing left to flag once the value is already wrapped in a
syntactically valid quote pair. It is a reminder that "was this value produced by parser recovery" and "does
this value still look encoding-unsafe" are different questions -- this check only ever answers the second
one.

See `VALIDATION_PHILOSOPHY.md`'s originated-vs-carried-forward paragraph for why the construction/write split
exists at all, and this document's own Reference table above for the throws-vs-warns line, shape by shape.

## Deliberately not checked

Three routes skip this check, each for a distinct, worth-stating reason:

- **`BaseMetadata.putAndQuoteProperty`** -- quoting neutralizes every character `findEncodingProblems` checks
  for an unquoted value (`a,b` becomes `"a,b"`, safe to emit; the same holds for `<`, `>`, and leading/trailing
  space -- `=` is not in this set at all any more, see "Severity: unconditional throw" above). It keeps its own,
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
  synthesize the malformed containers `VcfWriter`'s defensive checks are written against. Unchanged by this
  work: `VcfWriter.validateMetadataProperties` stays as the net covering it -- and, per "Path asymmetry closed"
  above, the parse path too, since neither route passes through this construction-time check.

## Reference table

Verified via `MetadataPropertyEncodingTest`, the test class this table is drawn from. **Two rows below are
superseded by Task 3** (marked ⚠) -- kept rather than deleted, with the new outcome recorded alongside, so a
reader who only remembers the old behavior isn't left to wonder whether it silently changed underneath them.

| Route | Input | Outcome |
|---|---|---|
| `addContig(Map)` | `assembly=a,b` (unquoted comma) | throws (both construction and `validateBeforeWrite()` -- an unquoted comma is `unreadableOutput`) |
| `addContig(Map)` | ⚠ `assembly=a=b` (unquoted `=`) | **was** throws; **now** succeeds -- `=` no longer appears in `findEncodingProblems`'s classifier at all, matching the attribute-list tokenizer's own name/value split |
| `addContig(Map)` | `assembly=a"b` (unescaped quote) | throws at construction; warns (not throws) at `validateBeforeWrite()` -- not `unreadableOutput`, since the value still closes on re-parse, just not where the caller intended |
| `addContig(Map)` | `assembly=a<b` / `assembly=a>b` (unquoted `<`/`>`) | throws at construction; warns at `validateBeforeWrite()` -- new checks Task 3 added, not `unreadableOutput` (bcftools garbles or truncates the declaration but the file stays readable) |
| `addContig(Map)` | `assembly=[a=b` (unterminated bracket value) | throws (both modes -- UNCONDITIONALLY, before the `##META`'s-`Values`-only gate, since bcftools' generic bracket handling hard-fails on this shape regardless of attribute/declaration type; exits 255) |
| `addContig(Map)` | `assembly=[a]b]` / `assembly=[a] junk` (bracket closes, but has trailing junk) | throws at construction (every problem throws there, regardless of severity); warns (not throws) at `validateBeforeWrite()` -- NOT `unreadableOutput` outside `##META`'s `Values` (it has a `]` somewhere, so it clears the universal floor and falls through to the generic "unquoted structural delimiter" check instead of the `Values`-only malformed-bracket check) |
| `addContig(Map)` | `assembly=[WholeGenome, Exome]` (well-formed bracket value, i.e. WOULD be well-formed on `##META`'s `Values`) | throws (both modes -- `[` is not comma-protecting outside `Values`, so this is an ordinary unquoted comma, `unreadableOutput=true`) |
| `addMeta(Map)` | `Values=[WholeGenome, Exome]` (well-formed bracket value) | succeeds -- the comma inside a well-formed bracket value on `##META`'s `Values` specifically is not checked at all, matching VCFv4.3.tex's own worked example. See VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting beyond `##META`'s `Values`" entry -- this is the ONE case bracket-list quoting is scoped to. |
| `addContig(Map)` | `assembly="a,b"` (quoted) | succeeds; round-trips through default write + re-parse |
| `addContig(Map)` | `assembly="a=b"` (quoted) | succeeds |
| `addContig(Map)` | `assembly=B36` | succeeds |
| `addContig(id, length, assembly, md5, species, taxonomy, url)` | `,` in `assembly`, `md5`, `taxonomy`, or `url` | throws (each is `putPropertyRaw`-stored) |
| `addContig(...)` | `,` in `species` | succeeds; round-trips through default write + re-parse (the one contig argument routed through `putAndQuoteProperty`) |
| `addInfo(id, description, ...)` | `,` in `description` | succeeds; round-trips through default write + re-parse (`putAndQuoteProperty`) |
| any of the six kinds' typed `addXxx` | ⚠ `=` in `id` | **was** throws; **now** succeeds, for the same reason as the `assembly=a=b` row above |
| any of the six kinds' typed `addXxx` | `<` in `id` (the current probe, replacing `=` above) | throws (the one raw-stored argument every typed route shares) |
| `addContig(Map)` | `a-b`, `1abc`, or `a b` as a property NAME | throws at construction (fails `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN`); warns (not throws) at `validateBeforeWrite()` -- bcftools drops just the one declaration, file stays readable |
| `addContig(Map)` | `Name_1` or `_x.y1` as a property NAME | succeeds -- both match the allowlist |
| `replaceContig(Map)` / `replaceContig(typed...)` | unquoted `,` | throws (only a comma is exercised on the replace path; `"`/name-allowlist/bracket share the identical underlying check, already proven on the `addContig` rows above) |
| `replaceContig(Map)` | a valid replacement (e.g. `assembly=GRCh38`) after a prior valid `addContig` | succeeds |
| `replaceInfo`/`replaceFormat` (typed) | `,` in `Number` | throws (the only other raw-stored typed field reachable without disturbing the already-stored `ID`) |
| `addContig(Map)` | key `""`, `"bad,key"`, `"bad=key"`, or `"a\"b"` (unescaped quote) | throws (all four now fail via the name-allowlist branch rather than the old ad hoc null/empty/comma/equals/quote checks -- same outcome, different mechanism) |
| Parse `##contig=<ID=1,a"b=v,length=100>` (unescaped quote in a property NAME) | -- | parses; re-parsed contig's properties are `{ID=1, a"b=v, length=100}`, all three intact -- the attribute-list tokenizer's name scan has no quote-mode toggle to flip, so a non-leading quote in a NAME is ordinary content the same as in a value (`AttributeListTokenizerTest#onlyALeadingDelimiterQuotes`); this is a *parsing* fix, and does not change the construction-time throw two rows below, which still rejects the same shape unconditionally |
| Parse `##contig=<ID=1,a-b=v,length=100>` (a NAME outside the allowlist) | -- | parses; re-parsed contig's properties are `{ID=1, a-b=v, length=100}`, all three intact -- the allowlist is now enforced at parse time too (warns, leaving the attributes intact), in addition to construction (throws) and `validateBeforeWrite()` (warns), matching AGENTS.md's own worked example for carried-forward content |
| `addContig(Map)` | key `null` (via a `LinkedHashMap`, not `Map.of`) | throws, but from `BaseMetadata.validateProperties()` *before* this check ever runs -- distinct message ("Metadata contains a null property name") |
| `addRawProperty(name, value)` | `=` in `name` (e.g. `"bad=name"`) | throws |
| `addRawProperty(name, value)` | `,` in `name` (e.g. `"bad,name"`) | succeeds (control: proves the structured comma rule was not applied to the generic grammar) |
| `addRawProperty(name, value)` | `=` in `value` (e.g. `"a=b"`) | succeeds; round-trips through default write + re-parse unchanged |
| `addRawProperty(name, value)` | `,` in `value` (e.g. `"a,b"`) | succeeds; round-trips through default write + re-parse unchanged |
| Parse `Description="a"b"` (unescaped quote inside a quoted value) | -- | parses; warns "trailing content ... discarding it" for the leftover `b"`; recovers `Description` as the well-formed `"a"` -- the value closes at the first unescaped `"` by position, not by unbalanced-quote parity. Pinned by `local-fixtures/failed/info-quoted-value-unescaped-interior-quote.vcf`, which also records bcftools 1.23.1's own divergence (drops the whole declaration instead) as its `##Authority=`. |
| Parse `Description=ab"` (unquoted value ending in a quote) | -- | parses; warns **both** "...containing a character bcftools treats as structural; preserving it" and "should be quoted but was"; recovers `Description` as `ab"` in full (the value is literal since its first byte isn't `"` or `[`, so nothing truncates) |
| Parse `Description="ab,Source="s"` (leading, unclosed quote) | -- | parses; warns "trailing content ... discarding it" for the leftover `s"`; recovers `Description` as `"ab,Source="` -- absorbing the comma and the `=` by matching `Source`'s own opening quote as its closing one; no `Source` key in the result at all |
| `parsed.toBuilder().build()` for any of the three parses above | -- | succeeds, with the identical recovered raw properties -- the object-taking overloads' exemption is what makes this possible |

The five "round-trips through default write + re-parse" rows are not redundant with the "succeeds" rows above
them: a change that accepted a value at construction but still corrupted it on the way out would pass every
`assertDoesNotThrow`/`assertThrows` assertion in this table while leaving the actual defect the corruption
matrix describes fully in place. Only the round-trip assertion proves the accept-decision was actually correct.

## Known gap: an escaped quote in a property name

**An escaped quote in a property name round-trips cleanly through the PARSE path -- construction has always
rejected any quote in a name, escaped or not.** Parsing `##contig=<ID=1,a\"b=v,length=100>` (a literal
backslash-quote sequence in the NAME), writing the result on the default path, and re-parsing it again yields
`{ID=1, a\"b=v, length=100}` back -- all three attributes intact (verified live), though parsing now emits a
name-allowlist warning for this shape (see below). That round-trip is a property of the PARSE path being
exempt from this check's throw/drop behavior (see "Why the parse path is exempt" above), not of the check
itself distinguishing escaped from unescaped: construction (`addContig(Map)` and every other checked route)
has never made that distinction. The pre-Task-3 predicate's `propertyName.contains("\"")` matched any quote
character in a name regardless of what preceded it, and Task 3's allowlist rejects it for the same reason --
neither `"` nor `\` is in `[A-Za-z_][0-9A-Za-z_.]*`. bcftools 1.23.1 agrees with construction, not with the
parse path, and rejects the line outright:

```
[E::bcf_hdr_parse_line] Could not parse the header line: "##contig=<ID=1,a\\\"b=v,length=100>"
```

(bcftools's own message escapes the literal `\"` in the input line for display inside its own double-quoted
diagnostic string, hence `\\\"` rather than `\"`.)

Closing this is deliberately out of scope for the unescaped-quote fix above: the parse path is exempt from
this check's throw/drop behavior (see "Why the parse path is exempt" above), so a header whose property name
already contains an escaped quote still parses through today, now with a parse-time name warning rather than
silently. Before Task 3, widening the check to also reject an
escaped quote -- without also deciding what the parser itself should do -- would have made `validateBeforeWrite()`
throw on content this library's own parser just accepted, because `validateBeforeWrite()`'s
`validateMetadataProperties` (`VcfWriter.java:704-706`) was, at that time, the write-time net described in
"Path asymmetry closed" above, running `checkMetadataPropertyEncoding` (the SAME unconditional-throw predicate
construction used) against every entry's properties at write time, regardless of whether that entry came from
a checked construction route or from the parser's exempt trusted routes. `toBuilder()` is *not* the mechanism
here -- it re-adds every declaration through the package-private object-taking `addXxx(md)` overloads
(`VcfMetadata.java:149-181`, `public Builder toBuilder() {` ... `return builder;`), none of which calls this
check at all (see "Deliberately not checked" above), so
it would keep succeeding on this content however the check were widened. A parse-path decision is needed
first. Recorded here so it is not rediscovered as a new finding; to be addressed separately.

**Task 3 narrows, but does not eliminate, the specific write-time risk this section warns about.** A property
name is now rejected via `InternalUtils.METADATA_ATTRIBUTE_NAME_PATTERN` rather than the old ad hoc
comma/equals/quote checks -- a name containing any quote character (escaped or not) still fails that
allowlist, since `"`/`\` are not in `[A-Za-z_][0-9A-Za-z_.]*`. What changed is what `validateBeforeWrite()`
does about it: `checkMetadataPropertyEncodingForWrite` classifies an invalid name as `unreadableOutput=false`
(bcftools drops the one declaration but keeps reading the file), so it now WARNS rather than throws. Widening
the parse path itself to also reject this shape -- narrower now that parsing already warns about it, so the
still-open question is outright rejection, not detection -- would therefore no longer make
`validateBeforeWrite()` throw on content the parser just accepted -- that specific consequence is resolved by
the construction/write split, independent of whatever the parse-path decision above eventually is.

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
  per header, from `writeHeader` only, not from `writeLine` (see "Path asymmetry closed" above for why this had
  to move once the name check stopped being an unconditional throw).
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
  moved here from `VcfWriter` (where they were previously private) so every call site shares one implementation
  rather than risking two copies of a predicate this subtle drifting apart.
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
  bypass, and the parse path's own trusted/object-taking routes. `validateMetadataProperties` is now a thin
  delegate to `checkMetadataPropertyEncodingForWrite`, not `checkMetadataPropertyEncoding`.
- `InternalUtils.tokenizeAttributeList`/`InternalUtils.parseAttribute` (sharing one per-attribute grammar via
  the private `parseOneAttribute`) -- the parse-side mechanism this whole document's "why the parse path is
  exempt" reasoning is about. Superseded the retired two-pass `splitTopLevel`/`splitProperty`/
  `hasUnbalancedQuotes`, which the severity-ranking and corruption-matrix sections above describe historically.
- `MetadataPropertyEncodingTest` -- the test class covering every route and shape described in this document.
- `AttributeListTokenizerTest` -- the test class covering the tokenizer's own per-attribute grammar (leading-
  byte classification, escape handling, junk-after-close recovery) referenced throughout this document.

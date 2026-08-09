# Duplicate metadata declaration ID handling

This document describes `VcfMetadata.Builder`'s/`VcfMetadata`'s behavior under a lenient `VcfContext`
(`VcfContext.lenient(fileFormat, warnings)`) -- the mode `VcfParser` always uses internally, so a parsed
file's behavior is exactly as described below. Under a strict context (`VcfContext.strict(fileFormat)`) each
of these conditions throws `VcfFormatException` immediately instead; see `VALIDATION_PHILOSOPHY.md`.

VCF metadata declarations (`##contig`, `##INFO`, `##FORMAT`, `##FILTER`, `##ALT`, `##SAMPLE`) are each keyed by an
`ID` attribute. Neither [VCFv4.2.tex](https://github.com/samtools/hts-specs) nor VCFv4.3.tex states what happens
when the same `ID` is declared twice for the same type within one file — the spec is simply silent on it.

## Why this warns instead of throwing

`vcf-parser` used to reject any file with a repeated declaration ID (`VcfFormatException: Duplicate ID ... for
...`). This is stricter than either reference tool this project consults when spec text is silent (see
`AGENTS.md`'s decision hierarchy):

- **vcf_validator** (the EBI reference validator; the tool [hts-specs](https://github.com/samtools/hts-specs) uses
  to classify its own `passed/`/`failed/` conformance fixtures) reports a file with a repeated declaration ID as
  **valid**, for every one of the six declaration types.
- **bcftools**, the reference implementation most of the ecosystem treats as canonical, keeps the **first**
  declaration and silently drops each duplicate — no warning, no error — again for every one of the six types.

hts-specs' own `test/vcf/{4.1,4.2}/passed/passed_meta_contig.vcf` fixture declares `##contig=<ID=1>` and then
`##contig=<ID=1,length=123456>`, and is expected to parse successfully — direct confirmation that this is not a
structural violation. Rejecting the file outright would make `vcf-parser` reject input that hts-specs' own
conformance suite, its own reference validator, and the ecosystem's reference implementation all treat as
acceptable, so `vcf-parser` now matches that practical tolerance: it logs a warning and keeps the first declaration,
rather than throwing `VcfFormatException`.

For the six `Map`-backed types above, this is construction-time-only: once stored, a `Map<String, ...>` can't
hold two entries under one key, so a "duplicate" can only be encountered while *adding* a declaration, which
now happens in exactly one place -- `VcfMetadata.Builder` (used directly, or by `VcfParser` at parse time, or
via `VcfMetadata.toBuilder()`). A built `VcfMetadata` is immutable, with no instance `addXxx` methods and no
mutable collection getters, so there is no second entry point for this rule to also cover.

`##PEDIGREE` is the one shape that still needs a second, write-time check: it is `List`-backed, so unlike the
keyed types nothing about the storage itself prevents two same-`ID` entries from coexisting. Its
`VcfWriter.validateMetadata()` check is retained (warn-only) rather than dropped along with the mutation
routes -- it also covers a list assembled outside `Builder.addPedigree`, and the writer keeps its own
defensive validation pass self-contained rather than depending on how its input was built.

## Reference table

Verified with **vcf_validator 0.10.2** and **bcftools 1.23.1 (htslib 1.23.1)** against a hand-built fixture per
declaration type, each declaring the same ID twice with different attributes.

| Declaration type | Target spec (VCFv4.2/4.3) | vcf_validator 0.10.2 | bcftools 1.23.1 | `vcf-parser` |
|---|---|---|---|---|
| `##contig` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##INFO` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##FORMAT` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##FILTER` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##ALT` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |
| `##SAMPLE` | Silent | Valid | Keeps first, drops duplicate, no warning | Warn + keep first |

Contrast with a duplicate `#CHROM` sample-column name, which remains a structural error in `vcf-parser`. This is a
different *kind* of duplication, not merely a stricter policy choice: `#CHROM`'s sample columns are *positional* —
every data line's per-sample values are aligned to them by index, and `VcfMetadata.getSampleIndex(id)` resolves a
name via `m_columns.indexOf(id)`. A duplicate name makes the *second* column's data permanently unreachable by
name (only reachable positionally), silently misdirecting any name-based lookup to the first column's data instead
— a real data-integrity hazard none of the six declaration types above have, since a declaration merely describes
something; nothing else in the file indexes into it by position.

Both reference tools independently draw this same line:

| Case | vcf_validator 0.10.2 | bcftools 1.23.1 |
|---|---|---|
| Duplicate `##contig`/`##INFO`/etc. ID | Valid | Keeps first, drops duplicate, no warning |
| Duplicate `#CHROM` sample-column name | **Invalid** | **Hard error — refuses to parse the file at all** |

(Verified directly: `bcftools view` on a file with two `S1` sample columns emits `[E::bcf_hdr_add_sample_len]
Duplicated sample name 'S1'` and fails to parse; `vcf_validator` reports the file invalid.)

## Missing or empty ID

Unlike a duplicate ID (above), a metadata declaration whose `ID` is **absent, empty, or whitespace-only** is a
different failure mode entirely: it isn't a collision between two declarations, it's a single declaration that
has no ID to dedupe on. At *add* time, `vcf-parser` warns and drops such a declaration in full, for all six keyed
types -- never stored, unlike a duplicate-ID-when-present (which keeps the first and drops the rest). This
add-time drop is independently preceded by a first warning of its own, from the declaration object's own
`IdMetadata.init()` at construction (before `addXxx` ever sees it) -- parsing one malformed header line produces
both warnings; see `WONT_FIX.md`'s "Multiple independent checks warn separately about the same underlying
defect" entry's "Missing or blank metadata ID" sub-case. "Never stored" is also specifically an add-time
guarantee, not a lifetime one: an object *already* in storage whose `ID` somehow became blank afterward is not
retroactively removed -- a `Map` can't un-key itself reactively -- `validate()`/`IdMetadata.init()` only warns
on it from then on. Since the metadata layer was frozen there is no public route to that state at all
(`getPropertiesRaw()` is an unmodifiable view and `putPropertyRaw` is package-private); it survives only
through the package-private `BaseMetadata.trustedMutableProperties()` bypass, which is what keeps
`VcfWriter`'s equivalent write-time check tested. See `VALIDATION_REFERENCE.md`'s equivalent bullet.

### Missing ID is spec-grounded

VCFv4.3.tex line 121 (unchanged in VCFv4.4/VCFv4.5): "All structured lines require an ID which must be unique
within their type, i.e., within all the meta-information lines with the same `##key=` prefix." VCFv4.2 never
uses the word "required," but its own templates for all six types show `ID=` unconditionally first, with no
optional-field notation -- consistent with, not contradicted by, VCFv4.3's explicit statement. VCFv4.4/VCFv4.5
additionally give `##ALT` its own explicit restatement: "ALT meta-information lines are structured lines with
require[d] fields of ID and Description..." This applies uniformly to all six types, regardless of what bcftools
actually does per type (see below) -- per `AGENTS.md`'s decision hierarchy, spec text governs when it isn't
silent.

### bcftools/vcf_validator behavior (live-tested)

Verified with **bcftools 1.23.1 (htslib 1.23.1)** and **vcf_validator 0.10.2** against hand-built fixtures, one
declaration of each type, for both a wholly missing `ID` attribute and an empty `ID=`:

| Type | Missing ID -- bcftools | Missing ID -- vcf_validator | Empty `ID=` -- bcftools | Empty `ID=` -- vcf_validator |
|---|---|---|---|---|
| `##ALT` | Kept, no warning | Invalid ("Error in ALT metadata") | Kept (`ID=,...` verbatim), no warning | Invalid ("ID not prefixed by DEL/INS/...") |
| `##SAMPLE` | Kept, no warning | Invalid | Kept, no warning | Invalid ("ID contains a character different from...") |
| `##FILTER` | Silently dropped | Invalid | Kept, no warning | Invalid (same charset message) |
| `##INFO` | Warn + dropped | Invalid | Warn ("Invalid tag name") + kept | Invalid (same charset message) |
| `##FORMAT` | Warn + dropped | Invalid | Warn + kept | Invalid (presumed same pattern) |
| `##contig` | Warn + dropped | Invalid | Warn ("Invalid contig name") + kept | Invalid (presumed same pattern) |

`##ALT`/`##SAMPLE` aren't part of BCF's binary dictionary encoding, so htslib never validates their `ID` at all.
`##INFO`/`##FORMAT`/`##contig`/`##FILTER` ARE part of BCF's dictionary, so a *missing* `ID` invalidates the whole
declaration -- but an *empty* `ID=` is, inconsistently, tolerated. `vcf-parser` does not replicate bcftools's
per-type inconsistency for missing IDs (see above -- the spec settles that dimension), and does not replicate
bcftools's empty-ID leniency either (see below).

### Empty/whitespace ID is a deliberate stricter-than-bcftools choice

No VCF spec version (4.2 through 4.5) or hts-specs conformance fixture addresses the empty-ID case at all --
this dimension is genuinely unresolved by spec text. Treating an empty or whitespace-only `ID=` the same as a
missing one is a deliberate editorial decision, NOT something bcftools's own behavior supports (bcftools actually
keeps an empty-ID `INFO`/`FORMAT`/`contig`/`FILTER` declaration, sometimes with a warning) -- consistent instead
with this project's existing precedent of being stricter than bcftools for other empty-structural-field content
(see `EMPTY_FIELD_HANDLING.md`).

### `##FILTER` warns even though bcftools doesn't

`##FILTER`'s missing/empty-ID drop warns in `vcf-parser`, even though bcftools itself is silent for the
missing-ID case -- matching this project's established more-vocal-than-bcftools convention (see this file's
"Why this warns instead of throwing" section above, applied the same way here).

## Why "keep first," not "keep last"

`vcf-parser` keeps the *first* declaration and discards each subsequent duplicate, matching bcftools' confirmed
behavior exactly (per `AGENTS.md`: "If still in doubt, consult bcftools behavior"). This also means a discarded
duplicate's own attributes are never validated against that type's own rules (e.g. `##ALT`'s structural-variant-code
check) — a dropped declaration is never stored, so its internal validity is irrelevant, the same way bcftools never
surfaces any complaint about a dropped duplicate's content either.

## Where this is implemented

- `VcfMetadata.warnIfDuplicate(Map, String, String, VcfContext)` — the shared helper, used by
  `VcfMetadata.Builder`'s `addXxx` methods; a strict context throws instead of warning (see
  `VALIDATION_PHILOSOPHY.md`).
- `VcfMetadata.Builder.addAlt()` / `addInfo()` / `addFormat()` / `addFilter()` / `addContig()` / `addSample()` —
  each calls `warnIfDuplicate()` before storing a new declaration, and returns immediately (without storing) if
  it reports a duplicate. There is no `removeXxx` anywhere: a built `VcfMetadata` is immutable. To produce a
  `VcfMetadata` without some declaration entirely, build a fresh one rather than editing an existing one --
  `Builder` has no way to drop one. To overwrite an existing declaration under the same `ID` instead, use the
  `replaceXxx` counterpart described below -- routing that through `addXxx` hits `warnIfDuplicate()` above,
  which discards the edit (warn-and-keep-first) instead of applying it.
- `VcfMetadata.Builder.addPedigree()` — warns and keeps the first entry when a duplicate literal `ID` property
  is found; always adds when no `ID` property is present.
- `VcfWriter.validateMetadata()`'s PEDIGREE loop — the same check, but warn-only (never drops), since it runs
  post-construction on content the caller already holds a reference to.
- `IdMetadata`'s shared `IdMetadata(VcfContext, String label, String id)` constructor -- used by every
  typed, named-parameter constructor across `IdDescriptionMetadata`/`InfoMetadata`/`FormatMetadata`/
  `ContigMetadata` (every declaration constructor is package-private; `VcfMetadata.Builder`'s raw-value
  `addXxx` overloads are the supported way in) -- reports a missing or blank `ID` through
  `BaseMetadata.warnOrThrow`, so it throws `VcfFormatException` under a strict context and warns under a
  lenient one. The separate `Map<String,String>`-based constructors `VcfParser` uses behave identically for
  the same content: the input shape does not decide strictness, the context does.
- `IdMetadata.init()` -- the Map-based (lenient) constructors' shared post-construction check, and also run
  again by `validate()` -- separately warns ("`{} metadata ID is blank`") for a missing or blank `ID`, one
  step before a declaration ever reaches the `addXxx`-level check below. This is a genuinely independent
  check, not a restatement: parsing one malformed header line produces both this warning and the `addXxx`-level
  one in the same log; see `WONT_FIX.md`'s "Missing or blank metadata ID" sub-case (referenced above) for why
  neither is suppressed in favor of the other.
- `VcfMetadata.Builder`'s 6 `addXxx` methods each check for a missing or blank `ID` before calling
  `warnIfDuplicate()`, warning and dropping the declaration entirely (never storing it) if so -- this is what
  actually removes a malformed declaration from storage, built via the parser's lenient `Map`-based
  construction path. Under a strict `VcfContext`, both checks throw `VcfFormatException` instead -- see
  `VALIDATION_PHILOSOPHY.md`. Note that a strict context already rejects a missing/blank `ID` at
  declaration-construction time, so the `addXxx`-level check is reachable there only for a declaration whose
  `ID` was removed afterward via the package-private `BaseMetadata.trustedMutableProperties()` bypass.

## `replaceXxx`: the supported way to overwrite a declaration

`VcfMetadata`'s model layer was made immutable before this section was written, which retired every instance
`removeXxx` method. That left the previously-documented "remove then add" idiom for overwriting a declaration
(`toBuilder().removeInfo(id).addInfo(id, ...)`) with no supported replacement path: `toBuilder().addInfo(existingId,
...)` reaches `warnIfDuplicate()` above like any other `addXxx` call, which -- correctly, for `addXxx` -- warns
and keeps the *first* declaration under a lenient context, silently discarding the caller's intended edit, and
throws under a strict one. Neither outcome lets a caller actually replace a declaration.

`VcfMetadata.Builder.replaceAlt()` / `replaceInfo()` / `replaceFormat()` / `replaceFilter()` / `replaceContig()` /
`replaceSample()` are the fix: one pair of overloads per keyed type (a typed overload and a raw-`Map` overload,
mirroring `addXxx`'s own pairing, so leniency keeps coming from the `VcfContext` rather than the overload shape),
each bypassing `warnIfDuplicate()` entirely for what is, by calling `replaceXxx`, always an intentional overwrite.
There is no `replacePedigree`: `##PEDIGREE`'s `List` backing (see below) has no single `Map` entry to overwrite,
and its `ID` is optional rather than mandatory.

**The missing-target contract is deliberately not context-dependent.** Replacing an `ID` that has no existing
declaration throws `VcfFormatException` -- in *both* a lenient and a strict context -- and never appends a new
declaration under that `ID`. This is the one place in this file where lenient and strict behave identically:
`replaceXxx` exists specifically to be the typo-resistant alternative to the retired remove-then-add idiom, so a
missing target is a programming error in either mode, not a content violation the way an invalid `Number`/`Type`
value is. Appending on a mistyped `ID` would reintroduce the exact failure `replaceXxx` exists to prevent (a
"replace" that actually creates a stray extra declaration), and warning-and-continuing under a lenient context
would leave the transformed `VcfMetadata` silently unchanged -- exactly the failure mode described above that
motivated adding `replaceXxx` in the first place. Adding a genuinely new declaration remains `addXxx`'s job.
`VcfMetadata.Builder.requirePresentForReplace(Map, String, String)` is the single, shared check every `replaceXxx`
method runs before touching its map, and unconditionally throws rather than delegating to `warnOrThrow`.

**Replacement is atomic with respect to a rejected replacement.** Only `##ALT` has a post-construction content
check at add time (`VcfMetadata.validateAlt()`, checking a declared `Number`/`Type` extra), and it is the reason
this needed care rather than being a mechanical wrapper around `addAlt()`: `addAlt()` runs `warnIfDuplicate()`
*before* `validateAlt()`, so routing a same-`ID` replacement through it would short-circuit on the duplicate-ID
warning before content is ever validated at all. `replaceAlt()` instead confirms the target `ID` is present, then
validates the fully-constructed replacement declaration completely, and only then performs the single map
mutation (`m_alt.put(id, md)`) -- so a rejected replacement (invalid `Number`/`Type`) leaves the existing
declaration installed and the builder otherwise unchanged. Neither of the two mechanical-looking alternatives
gives that for free: *remove-then-add* loses the valid original the moment validation throws (nothing left to put
back), and *put-then-validate* leaves the invalid declaration stored -- `build()` does not re-run add-time
validation, so it would silently survive into the built `VcfMetadata`. The other five keyed types have no
add-time content check to preserve this ordering for, but `replaceInfo()`/`replaceFilter()`/`replaceFormat()`/
`replaceContig()`/`replaceSample()` follow the same present-check-then-mutate shape for consistency.

## `##PEDIGREE`: a conditional case

Unlike the six keyed types above, `##PEDIGREE`'s grammar (`VCFv4.2.tex` line 152: `##PEDIGREE=<Name_0=G0-ID,...,
Name_N=GN-ID>`) has no mandatory `ID` attribute — the spec's own worked example (lines 904-908) declares three
`##PEDIGREE` lines in one file, each with a different, non-`ID` attribute schema (`Derived=...,Original=...`).
This is why `m_pedigree` is a `List<PedigreeMetadata>`, not a `Map<String, ...>` like the six keyed types — there is
no guaranteed key to map on.

Empirical testing shows bcftools still dedupes PEDIGREE by `ID` whenever a line happens to declare one:

| Case | bcftools 1.23.1 | vcf_validator 0.10.2 |
|---|---|---|
| Two `##PEDIGREE` lines, no `ID` property, otherwise identical | Keeps **both** | Valid |
| Two `##PEDIGREE` lines sharing `ID=P1` | Keeps **first**, drops duplicate | Valid |
| Two `##PEDIGREE` lines each with an empty `ID=` | Keeps **first**, drops duplicate | Valid |

So the rule generalizes: bcftools dedupes by `ID` wherever that key is present, regardless of the declaration's
tag name — not six special cases plus an unrelated PEDIGREE rule, but one rule (dedupe on `ID` when present) that
the six keyed types happen to always trigger (their grammar makes `ID` mandatory) and PEDIGREE only sometimes
triggers (its grammar makes `ID` optional). `VcfMetadata.Builder.addPedigree()` and `VcfWriter`'s
post-construction check both implement this: warn and keep first when `ID` is present and duplicated; always
add when `ID` is absent.

# Empty ("zero-length") field handling

This document describes the parser's (lenient) behavior -- reached via `VcfParser`, `VcfPosition.validate()`,
or `VcfPosition`'s own 11-argument lenient constructor. `VcfPosition`'s 10-argument (default) constructor
throws `VcfFormatException` immediately for an empty ID/ALT/FILTER entry or an empty FORMAT sub-field name
instead of warning-and-normalizing per the table below; see `AGENTS.md`'s "Direct/Manual Construction vs.
Parser Construction" section.

The VCF spec (explicitly stated starting with VCFv4.3; VCFv4.1/4.2 are silent on it) says:

> Zero length fields are not allowed, a dot (`.`) must be used instead.

That means an empty entry produced by a delimiter — a leading, interior, or trailing `;`, `,`, or `:` with
nothing between it and the next delimiter (or the end of the field) — is not valid VCF.

## Why this warns instead of throwing

A hard rejection would seem like the natural way to enforce this. In practice, the reference implementation most
of the ecosystem treats as canonical, [bcftools](https://github.com/samtools/bcftools)/htslib, does **not** reject
any of these cases. Tested directly against **bcftools 1.23.1 (htslib 1.23.1)**: every example below parses with
exit code 0 (except where noted), and bcftools either normalizes the empty entry away, substitutes `.` for it, or
(for two field types) warns but still keeps the record.

Rejecting these outright would make `vcf-parser` stricter than bcftools on inputs that demonstrably occur and are
tolerated in practice, so `vcf-parser` matches that practical tolerance: it logs a warning and normalizes, rather
than throwing `VcfFormatException`. This is consistent with the "strict for structure, lenient for practical
realities" split described in [`README.md`](README.md#validation-strict-vs-lenient) — the difference here is that
the "reality" is demonstrated by the reference tool itself, not just by metadata-quality concerns.

**vcf_validator 0.10.2** (the EBI reference validator; the tool [hts-specs](https://github.com/samtools/hts-specs)
uses to classify its own `passed/`/`failed/` conformance fixtures) was also checked against every row below.
Structural, top-level fields (`ID`, `FILTER`, `ALT`, `INFO`) are consistently reported **invalid** for every empty
entry, consistent with the spec's own "zero length fields are not allowed" text — with one asymmetry noted in the
table below (`AD=1,2,`). Sample-column values (`GT`, reserved `FORMAT` sub-fields) are reported **valid** in every
case tested — vcf_validator does not validate sample-value sub-structure with the same rigor. No hts-specs
conformance fixture exercises any of these exact scenarios, so this is a direct tool run, not a documented suite
fixture; per `AGENTS.md`'s decision hierarchy, bcftools remains the tie-breaker for these spec-silent/ambiguous
cases.

## How each field is normalized

Two different normalization strategies are used, depending on whether the field's list position carries meaning:

- **Position-independent lists** (ID, FILTER, and INFO's top-level `;`-separated entries): an empty entry is
  **dropped**. Nothing else depends on these lists' element order or exact count, so removing the bad entry is a
  clean fix.
- **Position-dependent lists** (a sample's `:`-separated values, matched by index to the FORMAT keys; INFO's
  per-key `,`-separated values, whose count can matter for a `Number=`-declared arity; and ALT's alternate-allele
  list, since `GT` genotype values index into it positionally): an empty entry is **replaced with `.`**, the VCF
  missing-value sentinel, so every other element keeps its original index.
- **FORMAT's `:`-separated key list** is a special case: it's a position-dependent list (every sample's values are
  matched to it by index), but "empty" isn't a value that can be replaced with `.` — an empty key is kept exactly
  as parsed, and only a warning is logged. Every other FORMAT key keeps its index this way, matching what bcftools
  itself does here (it warns "not defined in header" but does not drop the entry either).

ALT is a position-dependent list because `GT` genotype values index into it positionally (`VCFv4.2.tex` line 217:
"1 for the first allele listed in ALT, 2 for the second allele listed in ALT and so on"); `VcfPosition.getAllele
(int)` implements this as `m_altBases.get(index - 1)`. Dropping an empty entry (the previous behavior) would shift
every subsequent allele's index down by one, silently misdirecting any `GT` value referencing a position after the
dropped entry to the wrong allele — a real correctness bug, not just a cosmetic list-length difference. Substituting
`.` instead, matching bcftools exactly, preserves every index. This also required removing a separate, pre-existing
rule that rejected `.` combined with a real allele: `VCFv4.2.tex` (the target spec) only states that `.` replaces
the *entire* ALT field "if there are no alternative alleles" (line 183), and is silent on `.` as one entry among
several; `VCFv4.3.tex` line 350 lists a MISSING value `.` (no variant) as one of several options for "these
alleles" (the same comma-separated list, worded slightly differently than the newer versions), and `VCFv4.4.tex`
line 367 / `VCFv4.5.tex` line 416 state this more explicitly: "Each allele in this list must be one of: ... the
MISSING value `.` (no variant); ..." — `.` is a formally sanctioned per-entry ALT token in every VCF version from
4.3 onward. Confirmed bcftools 1.23.1 has no equivalent exclusivity rule either, accepting `T,.,C` with zero
warning whether the `.` is substituted or literal, and with genotype indices before, at, and after the dot all
resolving correctly.

This reading is contested by tooling, worth stating plainly rather than omitting: vcf_validator 0.10.2 itself
rejects the library's own substituted output (`T,.,C`) as invalid under every version's `##fileformat` declaration
tested, including 4.3+ — it applies `VCFv4.3.tex`'s own whole-field regex (`^([ACGTNacgtn]+|\*|\.)$`) literally, as
"a single dot, OR a comma-separated list of pure bases," not "a dot allowed inside such a list." bcftools 1.23.1 is
the tie-breaker here per `AGENTS.md` ("If still in doubt, consult bcftools behavior"), and it accepts this
construction with zero warning, consistent with the newer versions' prose. This library follows bcftools and the
newer-version prose, not vcf_validator's stricter regex reading, for the reasons given above.

## Reference table

| Field | Input | bcftools 1.23.1 behavior | vcf_validator 0.10.2 | `vcf-parser` behavior | Round-trip output |
|---|---|---|---|---|---|
| ID | `rs1;;rs2` | Silent passthrough, no warning | Invalid | Warn + drop | `rs1;rs2` |
| ID | `rs3;` | Silent passthrough, no warning | Invalid | Warn + drop | `rs3` |
| FILTER | `q10;;q20` | Warns "FILTER '' is not defined in the header" | Invalid | Warn + drop | `q10;q20` |
| FILTER | `q10;` | Silently dropped | Invalid | Warn + drop | `q10` |
| ALT | `T,,C` | Silently substitutes `.` (`T,.,C`) | Invalid | Warn + substitute `.` | `T,.,C` |
| ALT | `T,` | Silently substitutes `.` (`T,.`) | Invalid | Warn + substitute `.` | `T,.` |
| FORMAT key | `GT::GQ` | Warns "FORMAT '' ... not defined in header" + "Invalid tag name", kept as-is | Invalid | Warn, kept as-is | `GT::GQ` (unchanged) |
| FORMAT key | `GT:DP:` | Same warnings, kept as-is | Invalid | Warn, kept as-is | `GT:DP:` (unchanged) |
| Sample value (FORMAT `GT:DP`) | `0/1:` | Silently substitutes `.` (`0/1:.`) | Invalid | Warn + substitute `.` | `0/1:.` |
| Sample value (FORMAT `GT:DP:GQ`, Integer field) | `0/1::30` | Warns ("Extreme FORMAT/DP value ... set to missing") and substitutes `.` | Invalid | Warn + substitute `.` | `0/1:.:30` |
| Sample value (FORMAT `GT:XX:GQ`, String field) | `0/1::30` | Silent; represents the empty value internally as a raw NUL byte (`0x00`), not `.` — confirmed via `cat -A` on its own text output | Invalid | Warn + substitute `.` | `0/1:.:30` |
| INFO top-level entry | `DP=10;;AF=0.5` | Silently dropped | Invalid | Warn + drop | `DP=10;AF=0.5` |
| INFO top-level entry | `DP=10;` | Silently dropped | Invalid | Warn + drop | `DP=10` |
| INFO value | `AD=1,,2` | Silently substitutes `.` (`AD=1,.,2`) | Invalid | Warn + substitute `.` | `AD=1,.,2` |
| INFO value | `AD=1,2,` | Silently substitutes `.` (`AD=1,2,.`) | **Valid** (see note below) | Warn + substitute `.` | `AD=1,2,.` |
| INFO value | `AD=` | Silently substitutes `.` (`AD=.`) | Invalid | Warn + substitute `.` | `AD=.` |
| INFO flag (no `=`) | `DB;DP=10` | Fine, as expected | Valid | Fine, unaffected | `DB;DP=10` (unchanged) |
| GT allele (`:`-separated by `/` or `\|`) | `0/` | **Hard error** (exit code 8: "Couldn't read GT data: value not a number or '.'") | Valid | Warn + substitute `.` (deliberate divergence, see note below) | `0/.` |
| Reserved list-typed FORMAT value (e.g. `HQ`) | `1,2,` | Warns ("Extreme FORMAT/HQ value ... set to missing") and substitutes `.` | Valid | Warn + substitute `.` (`null` in the returned list) | n/a (read-side conversion only) |
| Reserved `FORMAT/FT` value | `q10;;q20` | Silent passthrough, no warning (FT is an opaque String to bcftools; it has no knowledge of `FT`'s reserved grammar) | Valid | Warn + drop (read-side only, like the row above) | n/a (read-side conversion only) |
| Generic metadata property (`##reference=`, `##assembly=`, `##pedigreeDB=`, or any other) | `##reference=` | Silent passthrough, no warning (verified for `reference`/`assembly`/`pedigreeDB`) | Invalid | Warn + drop the entire property entry | Line omitted entirely |

The INFO flag row is included as a contrast, not an empty-field case: a flag key with no `=` sign (e.g. `DB`) is
stored internally with an empty-string sentinel value, the same representation used for the "was this value actually
empty" cases above. `vcf-parser` distinguishes them at parse time (before the sentinel is created), by checking
whether an `=` sign was present at all — a flag's absent value is never touched by this normalization.

The `AD=1,2,` row is a confirmed asymmetry within vcf_validator's own handling of INFO fields specifically: every
other INFO/ID/FILTER/ALT empty-field case, interior or trailing, is reported invalid — except a *trailing* empty
entry on an unbounded (`Number=.`) INFO field, which vcf_validator reports as valid (confirmed directly from its
own text report, which shows no error for this case, only unrelated header-completeness warnings). An *interior*
empty entry in the same field (`AD=1,,2`) is still invalid. This doesn't change `vcf-parser`'s own behavior — both
cases already warn and substitute `.` — it's simply worth recording as a real, reproducible quirk of vcf_validator
itself, not a testing mistake.

The GT row follows the same position-dependent substitution as a sample value, not the ID/FILTER drop strategy:
dropping an empty allele (e.g. turning `0/` into `0`) would silently change the call's ploidy from diploid to
haploid, which is a materially different, incorrect result — not just a cosmetic list-length difference. Now
confirmed against bcftools 1.23.1: it actually hard-errors on this input (exit code 8), stricter than
`vcf-parser`'s own warn-and-substitute. This is a deliberate, unchanged divergence: `AGENTS.md`'s Parser Validation
Policy classifies sample values (which GT is) as a deliberately permissive category independent of any single
tool's behavior, and an empty GT sub-allele creates no structural ambiguity (splitting `0/` by `/` is unambiguous)
— so this was never a "spec silent, defer to bcftools" case. The reserved-list-value row (`HQ`) is now also
confirmed against bcftools directly: it warns and substitutes `.`, matching `vcf-parser`'s existing choice.

The generic-metadata-property row differs from every other row in this table in what gets dropped: every other
row drops or substitutes one *element* of a delimited list, but a generic property (`##reference=`, `##assembly=`,
`##pedigreeDB=`, or any unrecognized `##key=value` line) has no internal delimiter to drop an element from -- the
entire line is empty. Unlike every other row above, bcftools does *not* diverge from silent passthrough here --
it preserves an empty-value generic property line unchanged. Dropping the whole entry anyway is a deliberate
stricter choice, the same way this library is already stricter than bcftools for an empty ID/FILTER list entry
(the "position-independent list: drop" strategy above), and is justified directly by `VCFv4.3.tex` line 39 ("Zero
length fields are not allowed, a dot ('.') must be used instead").

`VcfWriter.Builder.validateBeforeWrite()` warns on this same condition but does not drop the entry -- unlike
the parse-time path, the writer must not silently remove content the caller is already holding a reference to.

## A related, but distinct, fix: duplicate FORMAT keys

`VcfPosition.checkFormat` also rejects a FORMAT declaration with a duplicate key (e.g. `GT:DP:DP`), throwing rather
than warning. This is not an empty-field case — VCFv4.3 and VCFv4.4 both state outright that "duplicate keys are not
allowed" for FORMAT, and without this check a duplicate key caused silent data loss (`VcfSample.getProperty` returns
only the first occurrence's value, discarding the rest) rather than a merely-malformed-looking value. An empty
FORMAT key (see above) is excluded from this duplicate check, since it's already handled, and warned about,
separately — two empty keys are not "duplicates" in the sense the spec means.

This exclusion is only about `checkFormat`'s rejection check, not an end-to-end guarantee: `VcfSample`'s own
property map still collapses same-named keys by key string, including two empty-string keys, so a FORMAT
declaration like `GT::` that reaches a real `VcfSample` (excluded from rejection for the reason above) still loses
every occurrence after the first when that sample is read or written -- now with a warning (see
`VcfSample.properties()`), rather than the prior silent last-wins overwrite. Two empty keys are not "duplicates"
for rejection purposes, but they are still a data-loss case in practice.

## Where this is implemented

- `InternalUtils.dropEmptyEntries` / `InternalUtils.fillEmptyEntriesWithDot` — the two shared normalization strategies.
- `VcfPosition.checkIds` / `checkFilters` — drop empty entries (ID, FILTER).
- `VcfPosition.checkAltBases` — substitutes `.` for an empty entry (position-dependent, like the sample/INFO
  values below), and no longer rejects `.` combined with a real allele.
- `VcfPosition.checkFormat` — warns and keeps an empty FORMAT key as-is; separately, throws on a duplicate
  (non-empty) FORMAT key.
- `VcfPosition.info()` — warns and drops an empty top-level entry or empty key; warns and substitutes `.` for an
  empty value.
- `VcfParser`'s per-sample value parsing — warns and substitutes `.` for an empty sample value.
- `InternalUtils.convertProperty` — warns and substitutes `.` (`null`) for an empty entry in a reserved list-typed
  property's comma-separated value; warns and drops an empty semicolon-separated code in a reserved `FORMAT/FT`
  value (`InternalUtils.checkReservedFormatConstraints`).
- `MemoryMappedVcfDataStore.doGetGenotype` — warns and substitutes `.` for an empty GT allele.
- `VcfSample.validate()` — warns and substitutes `.` for a sample property whose value was mutated to empty through
  `propertyEntrySet()`, consistent with `VcfWriter`'s own warn-only treatment (in `validateBeforeWrite()` mode) of
  an empty comma-separated FORMAT element.
- `VcfWriter.addInfoOrDot()` — warns and substitutes `.` for an INFO key's value list mutated to a single empty
  string through `VcfPosition.getInfo()`'s live view, but only when the key is declared with a `Type` other than
  `Flag`. A key declared `Type=Flag`, or one with no `##INFO` declaration at all (the "INFO flag (no `=`)" row
  above), still writes bare: an empty value there is either a genuine flag or indistinguishable from one, since
  `VcfPosition.info()` produces the identical single-empty-string representation for both. Unlike every other row
  in this table, this runs in **both** write modes, not just `validateBeforeWrite()` — the ambiguity this resolves
  (a live-mutated empty value that looks identical to a Flag) would otherwise silently corrupt the field's
  semantic type on the default write path too, not just under full diagnostics.
- `VcfMetadata.Builder.addRawProperty()`, and `VcfMetadata.addAssembly()` / `addPedigreeDatabase()` -- warn and
  drop a generic metadata property with an empty value, or an empty bracket pair (`<>`).
  `VcfWriter.Builder.validateBeforeWrite()` runs the identical check but only warns, without dropping.

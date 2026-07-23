# Empty ("zero-length") field handling

The VCF spec (explicitly stated starting with VCFv4.3; VCFv4.1/4.2 are silent on it) says:

> Zero length fields are not allowed, a dot (`.`) must be used instead.

That means an empty entry produced by a delimiter — a leading, interior, or trailing `;`, `,`, or `:` with
nothing between it and the next delimiter (or the end of the field) — is not valid VCF.

## Why this warns instead of throwing

A hard rejection would seem like the natural way to enforce this. In practice, the reference implementation most
of the ecosystem treats as canonical, [bcftools](https://github.com/samtools/bcftools)/htslib, does **not** reject
any of these cases. Tested directly against **bcftools 1.23.1 (htslib 1.23.1)**: every example below parses with
exit code 0, and bcftools either normalizes the empty entry away, substitutes `.` for it, or (for two field types)
warns but still keeps the record.

Rejecting these outright would make `vcf-parser` stricter than bcftools on inputs that demonstrably occur and are
tolerated in practice, so `vcf-parser` matches that practical tolerance: it logs a warning and normalizes, rather
than throwing `VcfFormatException`. This is consistent with the "strict for structure, lenient for practical
realities" split described in [`README.md`](README.md#validation-strict-vs-lenient) — the difference here is that
the "reality" is demonstrated by the reference tool itself, not just by metadata-quality concerns.

## How each field is normalized

Two different normalization strategies are used, depending on whether the field's list position carries meaning:

- **Position-independent lists** (ID, FILTER, and INFO's top-level `;`-separated entries): an empty entry is
  **dropped**. Nothing else depends on these lists' element order or exact count, so removing the bad entry is a
  clean fix.
- **Position-dependent lists** (a sample's `:`-separated values, matched by index to the FORMAT keys; and INFO's
  per-key `,`-separated values, whose count can matter for a `Number=`-declared arity): an empty entry is
  **replaced with `.`**, the VCF missing-value sentinel, so every other element keeps its original index.
- **FORMAT's `:`-separated key list** is a special case: it's a position-dependent list (every sample's values are
  matched to it by index), but "empty" isn't a value that can be replaced with `.` — an empty key is kept exactly
  as parsed, and only a warning is logged. Every other FORMAT key keeps its index this way, matching what bcftools
  itself does here (it warns "not defined in header" but does not drop the entry either).

ALT is the one field where `vcf-parser` deliberately diverges from bcftools's own normalization: bcftools
substitutes `.` for an empty ALT entry, but `vcf-parser` already rejects a `.` mixed with a real allele
(`VcfPosition.checkAltBases`, an unrelated, pre-existing rule: `.` means "no alternate allele" and cannot appear
alongside one). Substituting `.` here would immediately trip that other rule and throw, defeating the point of
normalizing instead of throwing. Dropping the empty entry (as done for ID/FILTER) avoids the conflict and is the
more useful reading of what's normally just a stray delimiter.

## Reference table

| Field | Input | bcftools 1.23.1 behavior | `vcf-parser` behavior | Round-trip output |
|---|---|---|---|---|
| ID | `rs1;;rs2` | Silent passthrough, no warning | Warn + drop | `rs1;rs2` |
| ID | `rs3;` | Silent passthrough, no warning | Warn + drop | `rs3` |
| FILTER | `q10;;q20` | Warns "FILTER '' is not defined in the header" | Warn + drop | `q10;q20` |
| FILTER | `q10;` | Silently dropped | Warn + drop | `q10` |
| ALT | `T,,C` | Silently substitutes `.` (`T,.,C`) | Warn + drop | `T,C` |
| ALT | `T,` | Silently substitutes `.` (`T,.`) | Warn + drop | `T` |
| FORMAT key | `GT::GQ` | Warns "FORMAT '' ... not defined in header" + "Invalid tag name", kept as-is | Warn, kept as-is | `GT::GQ` (unchanged) |
| FORMAT key | `GT:DP:` | Same warnings, kept as-is | Warn, kept as-is | `GT:DP:` (unchanged) |
| Sample value (FORMAT `GT:DP`) | `0/1:` | Silently substitutes `.` (`0/1:.`) | Warn + substitute `.` | `0/1:.` |
| Sample value (FORMAT `GT:DP:GQ`) | `0/1::30` | Warns only for Integer-typed fields ("Extreme FORMAT/DP value ... set to missing"); silent for String-typed fields | Warn + substitute `.` | `0/1:.:30` |
| INFO top-level entry | `DP=10;;AF=0.5` | Silently dropped | Warn + drop | `DP=10;AF=0.5` |
| INFO top-level entry | `DP=10;` | Silently dropped | Warn + drop | `DP=10` |
| INFO value | `AD=1,,2` | Silently substitutes `.` (`AD=1,.,2`) | Warn + substitute `.` | `AD=1,.,2` |
| INFO value | `AD=1,2,` | Silently substitutes `.` (`AD=1,2,.`) | Warn + substitute `.` | `AD=1,2,.` |
| INFO value | `AD=` | Silently substitutes `.` (`AD=.`) | Warn + substitute `.` | `AD=.` |
| INFO flag (no `=`) | `DB;DP=10` | Fine, as expected | Fine, unaffected | `DB;DP=10` (unchanged) |
| GT allele (`:`-separated by `/` or `\|`) | `0/` | Not tested against bcftools | Warn + substitute `.` | `0/.` |
| Reserved list-typed FORMAT value (e.g. `HQ`) | `1,2,` | Not tested against bcftools | Warn + substitute `.` (`null` in the returned list) | n/a (read-side conversion only) |
| Reserved `FORMAT/FT` value | `q10;;q20` | Not tested against bcftools | Warn + drop (read-side only, like the row above) | n/a (read-side conversion only) |

The INFO flag row is included as a contrast, not an empty-field case: a flag key with no `=` sign (e.g. `DB`) is
stored internally with an empty-string sentinel value, the same representation used for the "was this value actually
empty" cases above. `vcf-parser` distinguishes them at parse time (before the sentinel is created), by checking
whether an `=` sign was present at all — a flag's absent value is never touched by this normalization.

The GT row follows the same position-dependent substitution as a sample value, not the ID/FILTER/ALT drop strategy:
dropping an empty allele (e.g. turning `0/` into `0`) would silently change the call's ploidy from diploid to
haploid, which is a materially different, incorrect result — not just a cosmetic list-length difference. Unlike the
other rows in this table, this and the reserved-list-value row were not verified against bcftools; the "warn and
substitute" treatment was chosen for consistency with the rest of this table rather than from observed reference
behavior.

## A related, but distinct, fix: duplicate FORMAT keys

`VcfPosition.checkFormat` also rejects a FORMAT declaration with a duplicate key (e.g. `GT:DP:DP`), throwing rather
than warning. This is not an empty-field case — VCFv4.3 and VCFv4.4 both state outright that "duplicate keys are not
allowed" for FORMAT, and without this check a duplicate key caused silent data loss (`VcfSample.getProperty` returns
only the first occurrence's value, discarding the rest) rather than a merely-malformed-looking value. An empty
FORMAT key (see above) is excluded from this duplicate check, since it's already handled, and warned about,
separately — two empty keys are not "duplicates" in the sense the spec means.

## Where this is implemented

- `VcfUtils.dropEmptyEntries` / `VcfUtils.fillEmptyEntriesWithDot` — the two shared normalization strategies.
- `VcfPosition.checkIds` / `checkFilters` / `checkAltBases` — drop empty entries (ID, FILTER, ALT).
- `VcfPosition.checkFormat` — warns and keeps an empty FORMAT key as-is; separately, throws on a duplicate
  (non-empty) FORMAT key.
- `VcfPosition.info()` — warns and drops an empty top-level entry or empty key; warns and substitutes `.` for an
  empty value.
- `VcfParser`'s per-sample value parsing — warns and substitutes `.` for an empty sample value.
- `VcfUtils.convertProperty` — warns and substitutes `.` (`null`) for an empty entry in a reserved list-typed
  property's comma-separated value; warns and drops an empty semicolon-separated code in a reserved `FORMAT/FT`
  value (`VcfUtils.checkReservedFormatConstraints`).
- `MemoryMappedVcfDataStore.doGetGenotype` — warns and substitutes `.` for an empty GT allele.
- `VcfSample.validate()` — warns and substitutes `.` for a sample property whose value was mutated to empty through
  `propertyEntrySet()`, consistent with `VcfWriter`'s own warn-only treatment of an empty INFO value or an empty
  comma-separated FORMAT element in `validateBeforeWrite()` mode.

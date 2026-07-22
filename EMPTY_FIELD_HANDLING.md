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

The INFO flag row is included as a contrast, not an empty-field case: a flag key with no `=` sign (e.g. `DB`) is
stored internally with an empty-string sentinel value, the same representation used for the "was this value actually
empty" cases above. `vcf-parser` distinguishes them at parse time (before the sentinel is created), by checking
whether an `=` sign was present at all — a flag's absent value is never touched by this normalization.

## Where this is implemented

- `VcfUtils.dropEmptyEntries` / `VcfUtils.fillEmptyEntriesWithDot` — the two shared normalization strategies.
- `VcfPosition.checkIds` / `checkFilters` / `checkAltBases` — drop empty entries (ID, FILTER, ALT).
- `VcfPosition.checkFormat` — warns and keeps an empty FORMAT key as-is.
- `VcfPosition.info()` — warns and drops an empty top-level entry or empty key; warns and substitutes `.` for an
  empty value.
- `VcfParser`'s per-sample value parsing — warns and substitutes `.` for an empty sample value.

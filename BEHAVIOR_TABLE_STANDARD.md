# Behavior Table Standard

How this project documents measured spec and tool behavior.

A **behavior table** records, for one grammar or one field rule, what each VCF specification version says,
what the reference tools do, and what this library does. `VCF_SPEC_AMBIGUITIES.md`,
`STRUCTURAL_DELIMITER_HANDLING.md`, `EMPTY_FIELD_HANDLING.md` and
`DUPLICATE_METADATA_ID_HANDLING.md` are the usual homes; `VALIDATION_REFERENCE.md` indexes the field
exceptions (below).

Use a behavior table whenever a decision rests on *measured* behavior rather than on a spec sentence you
can quote outright. Do not use one to restate a rule the spec states plainly — cite the spec instead, per
`AGENTS.md`'s Spec Citation Discipline.

## The columns

```
| # | Payload | 4.2 | 4.3 | 4.4 | 4.5 | bcftools <ver> | vcf_validator <ver> | Parse (<mode>) | <our write path> |
```

**Facts (do not change except when their source changes):**

- **The four spec columns.** One per version, 4.2 through 4.5. Each cell is a line reference plus a
  verbatim quote in a footnote, or the literal word `silent`.
  - **Four columns, not one.** The versions diverge in ways a merged column hides: the same ALT-ID clause
    reads *"may not"* in 4.2 and *"must not"* in 4.3+, and 4.3 added "commas" to a list 4.2 lacked. That
    drift is exactly what a version-dispatch decision turns on.
  - **`silent` is a required entry, never a blank.** "The spec does not say" is a finding, and is often
    the most important cell in the table — it is what makes a rule tool-derived rather than spec-derived.
- **The tool columns.** Version-pinned in the header. State the outcome precisely enough to distinguish
  *kept with a warning* from *discarded*: "warns" alone is ambiguous, and bcftools does both for
  different inputs in the same grammar. Prefer `keep`, `keep, mangled to X`, `warn + swallow`,
  `DROP decl`.

  The two tools are **not symmetric**, and the columns must reflect that rather than looking tidy:

  - **bcftools gets one unqualified column.** Its header tokeniser never consults `##fileformat` —
    `bcf_hdr_parse_line` reads no version at all (htslib 1.23.1 `vcf.c`). Four per-version bcftools
    columns would be four identical copies, and would assert a version-sensitivity it does not have.
  - **vcf_validator gets one column pinned to the grammar the probe actually exercised**, written into
    the header: `vcf_validator 0.10.2 (4.3)`. It ships four separate grammars (`vcf_v4{1,2,3,4}.ragel`)
    and selects one from the file's `##fileformat`, so an unqualified verdict is a fact about one machine
    misreported as a fact about the tool.
  - **Expand a vcf_validator row to per-version cells only where its grammars genuinely differ.** Known
    divergences: extra attributes on `##ALT`/`##FILTER`/`##FORMAT`/`##INFO` (rejected in 4.1, allowed
    4.2+); `##META` recognised only from 4.3; the contig ID character class tightened at 4.3;
    `##SAMPLE`/`##PEDIGREE` restructured at 4.3; `Number=R` added at 4.2 and FORMAT `Number=P` at 4.4;
    ALT/FILTER `Description` mandatory before 4.4 and optional from 4.4. Outside that list, one column.
  - **vcf_validator 0.10.2 has no 4.5 grammar** (it maxes at 4.4). A 4.5 cell for it is `n/a`, never a
    verdict. The four *spec* columns still run 4.2-4.5, because the spec does.

- **A verdict produced by a known tool defect is not evidence.** Mark the cell as a defect, cite the
  write-up, and do not let it influence the decision — including as "corroboration". Two confirmed
  defects in vcf_validator 0.10.2 currently matter here: a token-parity bug that flips a line's validity
  on the *count* of `\"` it contains, and silent truncation of leading punctuation from captured
  identifiers. Both are token-capture faults, so they can make a well-formed line invalid and a
  malformed one valid. See `AGENTS.md`'s Spec Ambiguity Decision Hierarchy.

**Decisions (ours to change):**

- **The remaining columns** name our paths (below) and state *intended* behavior.
  - **Never label a column "target".** In this project "target" means the target VCF specification. A
    column headed "Parse (target)" sitting beside columns headed 4.2/4.3/4.4/4.5 reads as "what the
    target spec requires", which is the opposite of what it means. Say *intended*, or just name the mode.

## Rules that are not optional

**Byte-identical output does not prove correct parsing.** This is the subtlest failure and the one that
survives every other check. If a declaration re-emits unchanged, that is equally consistent with "the tool
split it into separate attributes" and "the tool kept it as one opaque value containing a comma". A
control row does not catch it; verifying the payload does not catch it; reviewing the script does not
catch it. Only a *discriminating observable* catches it.

So for any claim about how an input was **split**, use something the tool interprets and reports on.
Worked example: place `Number` and `Type` *after* the suspicious attribute in an `##INFO` declaration.
If the tool failed to see them it emits `An INFO field has no Type defined. Assuming String`. Silence
means it parsed them; the warning means it swallowed them. State in the table which observable was used.

**A probe shape is more than the declaration type.** At minimum it includes:

- the **declaration type** (`##INFO` and `##contig` do not behave alike),
- the **attribute name** (a whitelisted key like `Source` may carry rules of its own — vcf_validator
  rejects *any* unquoted `Source` value regardless of content, which will masquerade as a
  character-specific result),
- the **position in the attribute list** — whether the case under test is the *last* attribute or has
  others after it. An unterminated quoted string behaves differently in the two positions, because in
  the last position there is nothing left to swallow.

Change any of these and you may be measuring a different rule. If a result is claimed to generalise, test
at least two shapes and say which.

**Derive the case list from source, not from imagination.** You can only probe a shape you thought of,
so a table built purely from invented inputs is complete only by luck. Both tools' source is checked out
at the installed version (`~/Dev/htslib`, `~/Dev/vcf-validator` — see `AGENTS.md`'s Decision Hierarchy for
which files and why the version pin matters). Read the relevant parser first and enumerate the cases it
actually *distinguishes*; that becomes the row list. Then write the predicted outcome for each row
**before running anything**, and measure. Agreement confirms both the model and the harness; disagreement
tells you which to fix. A source read is never itself a table cell — cells record measured behavior, and
the source goes in the "why" note.

**Load-bearing tables get an independent re-derivation.** For a table that a design decision rests on,
have a second party measure it from a statement of *what to measure* — never from the script, and never
from the expected answers, which would only produce agreement-shaped noise. Compare the two tables cell by
cell and re-measure every disagreement. Two independent derivations agreeing is materially stronger than
one reviewed derivation, and disagreements localise the defect. This is not ceremony: the first time it
was done here it found a flatly wrong row and an unstated scope limitation that four rounds of self-review
had missed.

## Two further rules that are not optional

**State the probe shape.** Tool verdicts are field-sensitive. vcf_validator 0.10.2 calls a bare `"` valid
in an ALT ID and invalid in a `##contig` attribute value; bcftools rejects an unquoted `Description`
where it accepts an unquoted custom attribute. A verdict without its probe shape is not a fact about the
grammar, it is a fact about one field. Give the exact template, the tool invocations, and how the verdict
was read. If a result is claimed field-independent, say how that was verified — e.g. re-running with two
different attribute names.

**Include a control row** — a known-good input that must come back clean from every tool. Producing the
two tables in the attribute-list grammar work took four harness faults: `printf` consuming backslashes
and silently corrupting a payload; a verdict parser that classified every case including the control as
invalid; an invalid `-r` flag that made vcf_validator emit nothing; and a probe shape that was itself
invalid because `##ALT` requires a quoted `Description`. Three of the four were caught **only** because a
control row was present. A table without a control cannot distinguish "everything is rejected" from "the
harness is broken."

## Naming our paths

This library has four paths that can disagree. Work out which ones your table needs before choosing
columns, and say which mode each column means — do not write a bare "Write".

| Path | Reached how | Notes |
|---|---|---|
| **Parse** | `VcfParser`, which routes through the package-private `addXxxTrusted` variants | Always lenient: `VcfParser` only ever builds a `VcfContext.lenient(...)`. The trusted variants skip content checks by design. |
| **Public construction** | `VcfMetadata.Builder`'s public `addXxx`/`replaceXxx`, model constructors | Where a caller originates content. |
| **Validated write** | `VcfWriter` with `VcfWriter.Builder.validateBeforeWrite()` enabled | Full diagnostics mode. |
| **Default write** | `VcfWriter` without that option — the default | Performs no VCF-semantic re-validation; emits the model as-is. |

Two collapses are usually legitimate, and are invariants worth stating rather than silently assuming:

- **Public construction and validated write sharing a predicate does not, by itself, mean they cannot
  diverge — check what the predicate does with a problem, not just whether it's the same predicate.** A rule
  can be implemented as one shared classifier behind two different entry points, one per path, each free to
  react differently to what the classifier finds. The metadata-property-encoding rule is exactly this shape
  as of the attribute-list tokenizer's Task 3: `InternalUtils.checkMetadataPropertyEncoding` (construction)
  and `checkMetadataPropertyEncodingForWrite` (validated write) both call the same `findEncodingProblems`
  classifier, so the two paths can never disagree about WHAT counts as a problem — but construction throws
  for every problem unconditionally (originated content must never reach the header in a form bcftools would
  choke on or discard), while validated write throws only for the subset that would leave the emitted FILE
  unreadable and warns for the rest (content it may be carrying forward from a parse, not originating). That
  is a real, user-visible outcome difference — a caller who previously relied on `validateBeforeWrite()`
  throwing for, say, an invalid attribute name now only gets a warning — so it needs two columns, not one.
  **Collapse public construction and validated write into one column only when they are verified to produce
  the identical outcome for every row, not merely because they call the same method or share a classifier.**
  When they diverge, footnote or column the specific rows that differ, the same way a context-sensitive
  `warnOrThrow` cell gets its own footnote below.
- **Default write is usually a constant** — "emits verbatim", every row. Do not table a column that
  repeats one value; state the policy once instead, so it reads as the policy it is (`AGENTS.md`'s Writer
  Validation and Round-Trip Policy: the default path trusts its input) rather than as a per-row result.

Where a rule is **context-sensitive** — anything reached through `warnOrThrow`, which warns under a
lenient `VcfContext` and throws under a strict one — footnote the specific cell. Do not add a global
strict/lenient column for a sensitivity that affects one row.

## Field exceptions

A table describes a **universal** rule. Field-specific deviations belong in the field-exception register
in `VALIDATION_REFERENCE.md` — one row per *declaration · field · deviation · authority · where
documented*. The register is an index that points at the prose bullet or `VCF_SPEC_AMBIGUITIES.md` entry
already owning each rule; it never restates them.

This matters because a table verified field-independent invites the reader to assume it is the whole
story. Keep two kinds of row visibly distinct:

- a **spec rule** scoped to one field (`Description` must be quoted; an ALT `ID` may not contain angle
  brackets), and
- a **tool artifact** (vcf_validator's stricter `##contig` validator).

Citing the second as though it were the first is the specific failure this register exists to prevent.

Adding a new field rule means adding a register row, not only a prose bullet.

## Preserving the evidence

Measured evidence decays. Preserve it at three levels, weakest to strongest:

1. **Prose** — the table itself, in the doc that owns the subject, with tool versions and probe shape.
2. **Executable** — a case in `bcftoolsDependencyTest` for every bcftools row, so an upgrade re-verifies
   against a real binary rather than trusting the table. This is the only tier that detects the evidence
   going *stale*. Index round-trip-critical rows in `BCFTOOLS_VERSION_DEPENDENCIES.md`.
   **Commit the probe script** that generated the table — payload list, probe template, both tools'
   invocations, verdict parsing, control row. vcf_validator has no gate of its own, so without the script
   its column can only be regenerated by hand, which is where the four harness faults came from.
3. **Fixture** — a case in the local conformance corpus once it exists, with `##Authority=` lines naming
   each tool version and the spec's silence.

Re-deriving from prose is not free and not safe. Assume the next person to touch this has no memory of
how the numbers were produced, because they will not.

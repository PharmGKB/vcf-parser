# findings13.md Fixes — Design

## Problem

`tmp/findings13.md` (an untracked prior review) contains six findings. All six were independently
re-verified against the current codebase in this session and confirmed still present (line numbers may have
drifted slightly but the underlying issues are unchanged). One additional item in that document — collapsing
the `assembly`/`pedigreeDB`/`default` branches in `VcfParser.parseMetadata()` — is already implemented in the
current code (they already share one `case ... case ... default:` fallthrough block) and is excluded from this
plan.

The module-name issue, `getDevVersionName()`, and the Sonatype staging-profile item are excluded per prior,
separate user direction and are not part of `findings13.md` in any case.

## 1. Reserved raw-property-name collision (High)

`VcfMetadata.getRawProperties()` is a documented mutable escape hatch (a live `ListMultimap`). A caller can
insert a raw property whose key collides with a structured declaration keyword (`fileformat`, `ALT`, `FILTER`,
`INFO`, `FORMAT`, `contig`, `SAMPLE`, `PEDIGREE`), producing a VCF that `VcfWriter` will write but `VcfParser`
cannot read back correctly. `VcfWriter.validateMetadata()` (`VcfWriter.java:275-294`) currently only checks
raw keys for `=` and values for line terminators/emptiness — no reserved-name check exists anywhere.

Separately, `VcfMetadata.Builder.addRawProperty()` (`VcfMetadata.java:541-556`) already calls
`VcfUtils.checkNoLineTerminator()` and drops-with-warning on an empty value — the same two checks
`VcfWriter.validateMetadata()`'s raw-property loop repeats on the already-built metadata. This is genuine
duplication worth removing while closing the High finding.

**Fix:** add `VcfUtils.isReservedMetadataKey(String name)`, a static predicate checking membership in
`{fileformat, ALT, FILTER, INFO, FORMAT, contig, SAMPLE, PEDIGREE}` (the same keyword set already switched on
in `VcfParser.parseMetadata()`). Use it in two places:

- `VcfMetadata.Builder.addRawProperty()`: if the name is reserved, warn-and-drop, matching this method's
  existing style for the empty-value case. This cannot currently be reached through normal parsing (the
  parser's switch statement routes every reserved name to the structured path, never to `addRawProperty()`),
  but it closes the hole for direct/programmatic `VcfMetadata.Builder` use.
- `VcfWriter.validateMetadata()`: if a raw key is reserved, throw `VcfFormatException`, matching the existing
  adjacent `key.contains("=")` check's style and AGENTS.md's diagnostics-mode reject policy ("content that
  would make output structurally invalid ... must be rejected with `VcfFormatException`"). This is what
  actually closes the mutable-escape-hatch bypass, since the builder-side check cannot see a post-construction
  mutation of the live multimap.

## 2. Charset mismatch between file-based parser and writer (Medium)

`VcfParser.Builder.build()` uses `Files.newBufferedReader(m_vcfFile)` (UTF-8 by default, `VcfParser.java:571`).
`VcfWriter.Builder.build()` uses `new FileWriter(m_file.toFile())` (platform-default charset,
`VcfWriter.java:779`), which can corrupt or fail to round-trip non-ASCII content on a JVM whose default
charset is not UTF-8.

**Fix:** change `VcfWriter.Builder.build()` to `Files.newBufferedWriter(m_file, StandardCharsets.UTF_8)`,
matching the parser side. No new charset-configuration API — not requested, and the parser side isn't
configurable either.

## 3. `rsidsOnly()` substring match instead of per-component match (Medium)

`VcfParser.parseNextLine()` filters with `VcfUtils.RSID_PATTERN.matcher(data.get(2)).find()`
(`VcfParser.java:225`), an unanchored search against the *entire* raw ID field. An ID such as
`notrs123suffix` is treated as RSID-bearing even though no individual semicolon-delimited ID component
equals `rs\d+`.

**Fix:** reorder the existing block so the semicolon split (`toList(SEMICOLON, data.get(2))`) happens once,
then test `m_rsidsOnly` against each parsed component with `RSID_PATTERN.matcher(id).matches()` (anchored),
keeping the record only if at least one component matches:

```java
List<String> ids = null;
if (!data.get(2).equals(".")) {
  List<String> parsedIds = toList(SEMICOLON, data.get(2));
  if (m_rsidsOnly && parsedIds.stream().noneMatch(id -> VcfUtils.RSID_PATTERN.matcher(id).matches())) {
    return true;
  }
  ids = parsedIds;
} else if (m_rsidsOnly) {
  return true;
}
```

New tests: an ID field of `notrs123suffix` alone is excluded (the bug case); `foo` alone is excluded;
`foo;rs123` is included.

## 4. OSGi manifest exports nothing (Medium)

The bnd-generated manifest has `Private-Package: org.pharmgkb.parser.vcf,org.pharmgkb.parser.vcf.model` and no
`Export-Package` (confirmed by building the jar and inspecting `META-INF/MANIFEST.MF` directly). OSGi
consumers cannot import this library's API. Per direct confirmation, OSGi packaging is intended to actually
work for consumers.

**Fix:** add a bnd `Export-Package` instruction to the `jar` task in `pgkb-build.gradle` covering the three
public packages: `org.pharmgkb.parser.vcf`, `org.pharmgkb.parser.vcf.model`,
`org.pharmgkb.parser.vcf.model.genotype`. Exact bnd Gradle DSL syntax (`jar { bnd(...) }` vs. a `bnd.bnd`
instructions block) is an implementation detail to confirm against the installed
`biz.aQute.bnd.gradle:7.3.0` plugin during the task.

**Verification:** rebuild the jar and inspect the manifest directly —
`unzip -p build/libs/vcf-parser-*.jar META-INF/MANIFEST.MF` — confirming `Export-Package` now lists the three
packages and `Private-Package` no longer includes them. This is the same method used to confirm the bug, so
it's a reliable acceptance check independent of bnd DSL details.

## 5. Two `ContigMetadataTest` cases never run (Low)

`ContigMetadataTest.testNoId()` and `testBadLength()` (`ContigMetadataTest.java:19`, `:38`) lack `@Test`, so
JUnit silently never executes them. Both scenarios do warn today: `IdMetadata.init()`'s `warnIfMissing` for a
missing `ID`, and `ContigMetadata.init()`'s `catch (NumberFormatException)` block for a non-numeric `length` —
confirmed by reading both methods. This is a real gap, not dead code to delete.

**Fix:** add `@Test` to both methods and wrap each body in the existing `WarnCapturingAppender` pattern already
used in `InfoMetadataTest`/`FormatMetadataTest` (attach to the root logback logger, run the constructor call,
assert `appender.hasWarnOrHigher()`, detach/stop in a `finally`).

## 6. Javadoc and nullability inconsistencies (Low)

Four unrelated one-line fixes, bundled into a single task since none share code:

- `InfoMetadata.m_type` (`InfoMetadata.java:39`): the package is `@NullMarked` (non-null by default), but
  `init()` assigns `m_type = null` on an invalid/missing type, and `getType()` is already annotated
  `@Nullable`. Annotate the field `@Nullable` to match actual behavior — no behavior change.
- `VcfMetadata.getRawValuesOfProperty()` (`VcfMetadata.java:369-375`): the Javadoc claims it returns `null`
  when the property is absent; Guava's `ListMultimap.get()` actually returns a live, possibly-empty `List`.
  Correct the Javadoc to describe the real (empty-list) behavior.
- `VcfParser.parseNextLine()` (`VcfParser.java:165`): the `@return` doc says "false only if and only if this
  is the last line available," but the method actually returns `true` for the call that reads the last line,
  and `false` only on the *subsequent* call once the reader hits EOF. Reword to state this precisely.
- `build.gradle:58`: `description = 'A streaming parser for VCF 4.1/4.2.'` understates current scope. Update
  to reflect the README's documented scope (target specification VCF 4.2; accepts representable VCF 4.x
  input). This is a POM/Gradle project description string, not the package version or CHANGELOG, so it is not
  covered by the Release Files "don't manually edit" restriction.

## Testing

- Item 1: new tests for `VcfMetadata.Builder.addRawProperty()` (warn-and-drop on each reserved name) and
  `VcfWriter.validateMetadata()` in `validateBeforeWrite()` mode (throws `VcfFormatException` when a raw
  property with a reserved key is present, via the mutable `getRawProperties()` escape hatch).
- Item 2: a round-trip test writing then reading back non-ASCII content (e.g. a sample name or INFO value with
  a non-ASCII character) via `VcfWriter.Builder.toFile()`/`VcfParser.Builder.fromFile()`, run in a way that
  would fail under a non-UTF-8 platform default charset assumption.
- Item 3: the three `rsidsOnly()` cases described above.
- Item 4: manifest inspection as described above; not a JUnit test.
- Item 5: the two now-annotated tests themselves are the deliverable.
- Item 6: no behavior to test; Javadoc/description changes only.

## Global constraints

- One plan, six independent tasks, each producing an independently testable, committable deliverable.
- Conventional-commits style; scope is exactly one of `parser`, `writer`, or `memoryMappedImpl`, never a class
  name. Item 1 touches both `VcfMetadata` (parser-built model) and `VcfWriter`: commit the `VcfMetadata.Builder`
  change under scope `parser` and the `VcfWriter.validateMetadata()` change under scope `writer` as two
  separate commits, rather than picking one scope to cover both files.
- No new public API surface beyond what's already implied (e.g. no new charset-configuration parameters, no
  new `Export-Package` beyond the three existing public packages).
- Item 4's exact bnd Gradle syntax is confirmed empirically (manifest inspection) rather than assumed correct
  from documentation alone.

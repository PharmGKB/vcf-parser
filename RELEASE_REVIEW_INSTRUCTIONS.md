# Release-Readiness Review Instructions

Use this document when repeating a comprehensive review of the VCF parser/writer
library. The objective is to determine whether the current source is ready for
release, not merely to accumulate observations.

## Review objective

Audit the implementation against three contracts:

1. VCF 4.3 compliance, including the repository's declared-version rules and
   documented deliberate divergences.
2. The public API and user-facing documentation.
3. Release-quality expectations for correctness, compatibility, safety,
   performance, testing, and packaging.

The final result must be one of:

- **Ready for release** — no release-blocking findings remain and required gates
  have fresh evidence.
- **Ready with documented exceptions** — remaining deviations are explicit,
  intentional, tested or externally justified, and not release blockers.
- **Not ready** — at least one release-blocking finding remains, or a required
  release gate is unverifiable.

## Required review order

### 1. Establish the baseline

Before drawing conclusions:

- read `AGENTS.md` and every document in its Decision Hierarchy;
- inspect the worktree and preserve unrelated changes;
- record Java, Gradle, bcftools, htslib, vcf-validator, and pinned hts-specs
  versions;
- record the exact commit this audit is run against (`git rev-parse HEAD`);
- run `./gradlew clean test javadoc`;
- run relevant opt-in gates with `--rerun-tasks`, especially
  `./gradlew htsSpecsTest` and `./gradlew bcftoolsDependencyTest`;
- distinguish a real clean pass from an up-to-date task, generated report, or
  infrastructure failure such as an EOF/transport error.

If a required gate cannot be verified, report that as a release-readiness risk;
do not silently treat it as passing.

If an earlier report exists (in `docs/issues/`), do not cite its counts, gate
results, or evidence claims as still true. Diff the commit it recorded against
the current `HEAD` for the files its claims depend on (test files, dependency-
decision index docs, baseline files) and re-run the affected gate fresh. A
prior report's claims are a lead to re-verify, not a baseline to inherit --
they can go stale the moment a later commit changes the code or docs they
described, with no warning that they now disagree.

### 2. Specification and contract mapping

Build a rule matrix covering:

- parser construction and deferred-access paths;
- public/manual construction;
- default writer output;
- `validateBeforeWrite()` output;
- parse → write → parse behavior;
- relevant warnings, normalization, and exceptions;
- the governing VCF rule, policy document, fixture, or tool measurement.

Check the policy hierarchy before inventing a new interpretation. Treat
bcftools acceptance as evidence of leniency, not proof of validity. Treat
bcftools rejection of originated output as a serious interoperability constraint.

### 3. Adversarial parser and writer review

Use generated or hand-built cases covering:

- empty, doubled, trailing, and embedded delimiters;
- malformed quotes, brackets, escapes, tabs, CR/LF variants, and BOMs;
- huge fields, many samples, many FORMAT keys, large INFO lists, and long
  metadata declarations;
- extreme and negative numeric values;
- duplicate declarations, duplicate keys, conflicting contexts, and version
  aliases or unsupported versions;
- malformed-but-representable values that trigger lazy conversion;
- manual objects built through every public construction route.

For every interesting case, check for crashes, hangs, raw unexpected runtime
exceptions, column/sample misalignment, data loss, warning loss, duplicate
warnings, and non-reparsable output.

### 4. Property and metamorphic testing

Test invariants rather than only examples:

- parse → write → parse preserves semantic content where promised;
- raw values remain available when typed conversion fails;
- default and diagnostics writer modes differ only where documented;
- repeated validation is idempotent;
- equivalent cached and uncached FORMAT paths produce the same records and
  diagnostics;
- changing only the declared version changes only documented version-sensitive
  interpretations;
- manual construction and parse-derived construction converge on equivalent
  output where the contract says they should;
- expected normalization preserves positional indexes;
- writer output can be parsed by this library and, for originated content,
  accepted by bcftools.

Capture parser, writer, and reparse warnings as part of the oracle. A semantic
oracle must compare raw wire values separately from typed getters so an expected
lazy conversion failure does not invalidate the oracle itself.

### 5. Public API, lifecycle, and concurrency review

Exercise:

- nulls for every public non-null reference parameter;
- missing metadata and pre-parse datastore lookups;
- mixed-context objects;
- builder reuse and resource ownership;
- parse/write/close ordering;
- repeated writes after failures and after close;
- reader/writer close failures;
- concurrent warning, parse, write, close, and lazy-getter operations;
- memory-mapped datastore callbacks with changing metadata or sample layouts.

Every public lifecycle and thread-safety guarantee must be either enforced,
explicitly documented as unsupported, or reported as a gap.

### 6. Security and resource review

Look for denial-of-service and reliability risks:

- quadratic or worse behavior in parser and writer hot paths;
- unbounded line, field, warning, or collection growth;
- repeated warning amplification;
- integer overflow and size-conversion hazards;
- malformed input that causes excessive backtracking or allocation;
- resources that remain open after parse/write failures or builder reuse;
- output that can make downstream tools fail fatally.

Use representative measurements rather than inferring performance from source
alone. Record throughput, allocation rate, peak memory, and scaling dimensions.

### 7. Differential and compatibility review

Compare deliberately selected cases with:

- the pinned hts-specs fixtures;
- installed bcftools and matching htslib source;
- vcf-validator, while excluding its documented defect surfaces;
- supported Java/runtime and publication configurations.

For each disagreement, determine whether the spec, a deliberate project policy,
or tool interoperability should control the result. Record tool versions and
probe shapes.

### 8. Test effectiveness review

Do not equate coverage with protection. Inspect whether tests verify the claim
they name, especially:

- warning identity rather than arbitrary warning presence;
- exact emitted lines and byte-level output where promised;
- both default and diagnostics writer modes;
- manual and parse-derived object paths;
- lazy getters and raw-value fallbacks;
- failure atomicity and retry behavior.

Use mutation testing or targeted branch perturbations where practical. A test
suite should fail when a validation branch, warning, normalization step, cache
boundary, or serialization delimiter is removed or inverted.

### 9. Documentation and policy audit

Extract claims from `README.md`, public Javadocs, `AGENTS.md`, and the policy
documents. Classify each claim as:

- enforced and tested;
- enforced but insufficiently tested;
- documented but unenforced;
- intentionally unsupported;
- stale or contradictory.

Do not treat a detailed policy document as evidence that the implementation or
tests actually satisfy the policy.

## Finding standard

For every finding, provide:

- severity: **release blocker**, **fix before release**, **follow-up**, or
  **accepted exception**;
- exact source, test, fixture, or documentation anchors;
- a minimal reproduction or discriminating test;
- the violated contract: VCF specification, public API, project policy,
  interoperability, safety, or quality goal;
- impact and likely trigger conditions;
- recommended remediation;
- whether the issue is confirmed, suspected, or an evidence gap.

Do not report intentional warning-and-recovery behavior as a defect merely
because the VCF specification would prefer rejection. Do report silent
misalignment, data loss, malformed originated output, raw unexpected exceptions,
unbounded resource behavior, and documentation that promises behavior the code
does not provide.

## Delegation and worktree rules

Independent read-only reviews may be delegated in parallel, with disjoint scopes:

- property/fuzz and metamorphic behavior;
- API misuse, lifecycle, and concurrency;
- security, resource use, and performance;
- documentation and policy contracts.

The coordinating review must reconcile all reports against current source and
tests. Do not trust subagent conclusions without checking their anchors. Do not
let agents edit overlapping files during an audit. Preserve unrelated worktree
changes and do not modify library code unless the user explicitly requests
implementation.

## Deliverable

Write the final report to `docs/issues/YYYY-MM-DD-HHMM-release-audit.md` (an
actual timestamp, not literal placeholder text -- matching this repo's spec/
plan filename convention) unless the user specifies another path. Never
overwrite an earlier report; each run gets its own timestamped file so a
later reader can tell which audit is current and diff against the recorded
commit hash of an earlier one. Include:

1. the exact commit this audit was run against (`git rev-parse HEAD`);
2. executive release decision;
3. blocking findings first;
4. non-blocking findings and accepted exceptions;
5. the contract matrix;
6. commands run and exact outcomes;
7. unresolved infrastructure or evidence gaps;
8. prioritized remediation plan.

Never conclude “ready for release” while a required gate is unverified or while
a suspected release blocker lacks a discriminating test.

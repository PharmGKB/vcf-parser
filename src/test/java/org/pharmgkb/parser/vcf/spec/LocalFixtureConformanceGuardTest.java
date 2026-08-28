package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LocalFixtureConformanceTest#checkRoundTripSemantic}'s exception-to-clean-failure guards
 * directly (regression test for the Phase 3 writer-compliance review's Path B kickoff, finding #8), using a
 * throwaway probe file rather than a committed fixture -- a fixture that actually triggers one of these
 * guards would leave {@code localFailedFixturesRoundTripSemantically} permanently red, since the underlying
 * condition (e.g. a non-numeric QUAL, unconditionally fatal on typed access -- see
 * {@code HTS_SPECS_BASELINE_STATUS.md}'s "Non-numeric QUAL" row) is itself unfixable/accepted, not a bug the
 * guard could make pass.
 * <p>
 * Covers one of the three guarded phases: the {@code semanticDiff}/comparison phase below. The {@code write}
 * phase's guard has no test here: there is no known input shape, lazily-deferred INFO/QUAL/FORMAT check
 * included, that reaches it without being a new production bug in its own right -- the same reason the
 * {@code parseText} (reparse) phase already has no test here either. Constructing an input that still
 * reaches either branch would mean deliberately reproducing a genuine round-trip defect (the exact thing
 * AGENTS.md's Writer Validation and Round-Trip Policy exists to prevent), not exercising an already-accepted,
 * permanent leniency case the way the probe below does. {@code checkRoundTripSemantic}'s own {@code write}-
 * and {@code parseText}-phase guards are left in place regardless (speculative defensive code, matching their
 * own comments there), simply untested pending a future genuine trigger.
 */
class LocalFixtureConformanceGuardTest {

  // Written under the same root LocalFixtureConformanceTest#relativeName resolves against (FIXTURES_ROOT's
  // parent), so that method's own Path#relativize call succeeds -- an absolute @TempDir path would throw
  // "different type of Path" there instead of letting the guard's own fail() message build cleanly.
  private static final Path PROBE_FILE = Path.of(
      "src/test/resources/org/pharmgkb/parser/vcf/spec/local-fixtures/scratch-guard-test-probe.vcf");

  @AfterEach
  void cleanUp() throws IOException {
    Files.deleteIfExists(PROBE_FILE);
  }

  @Test
  void testSemanticDiffExceptionDuringComparisonIsReportedAsCleanFailureNotRawException() throws IOException {
    // a non-numeric QUAL writes back out fine (VcfWriter's default path writes the preserved raw text
    // verbatim, per VcfPosition's own rawQuality handling), but WriterRoundTrip.semanticDiff's
    // comparePosition calls getQuality() on both "before" and "after" to compare them, and getQuality()
    // throws VcfFormatException unconditionally for unparseable text -- the exact shape
    // HTS_SPECS_BASELINE_STATUS.md's "Non-numeric QUAL" row documents, now exercised through this class's
    // own (previously unguarded) round-trip path instead.
    String vcf = "##fileformat=VCFv4.3\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t100\t.\tA\tT\tnot-a-number\t.\t.\n";
    Files.writeString(PROBE_FILE, vcf);

    AssertionFailedError e = assertThrows(AssertionFailedError.class,
        () -> LocalFixtureConformanceTest.checkRoundTripSemantic(PROBE_FILE));
    // a clean, categorized JUnit assertion failure naming the fixture and the exact phase that threw --
    // not the raw VcfFormatException/its stack trace escaping uncaught, which is what happened before this
    // guard existed
    assertTrue(e.getMessage().contains("unexpectedly threw while comparing before/after"), e.getMessage());
    assertTrue(e.getMessage().contains("QUAL"), e.getMessage());
  }
}

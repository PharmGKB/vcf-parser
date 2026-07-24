package org.pharmgkb.parser.vcf.spec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link Baseline}.
 */
class BaselineTest {

  private static InputStream emptyBaseline() {
    return null;
  }

  private static InputStream baselineWith(String... tsvLines) {
    StringBuilder sb = new StringBuilder("fixture_path\tcheck_type\tcategory\treason\n");
    for (String line : tsvLines) {
      sb.append(line).append("\n");
    }
    return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void testNotBaselinedAndNotDivergedIsAcceptable() throws IOException {
    Baseline baseline = new Baseline(emptyBaseline(), false);
    assertTrue(baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", false, "", ""));
    assertFalse(baseline.hasFindings());
  }

  @Test
  void testNotBaselinedAndDivergedIsNewFinding() throws IOException {
    Baseline baseline = new Baseline(emptyBaseline(), false);
    assertFalse(baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", true, "cat", "boom"));
    assertTrue(baseline.hasFindings());
    assertEquals(1, baseline.findingsCount());
  }

  @Test
  void testBaselinedAndStillDivergedIsAcceptable() throws IOException {
    Baseline baseline = new Baseline(
        baselineWith("test/vcf/4.1/passed/a.vcf\tparser-no-throw\tcat\told-reason"), false);
    assertTrue(baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", true, "cat", "boom"));
    assertFalse(baseline.hasFindings());
  }

  @Test
  void testBaselinedButNoLongerDivergingIsStaleFinding() throws IOException {
    Baseline baseline = new Baseline(
        baselineWith("test/vcf/4.1/passed/a.vcf\tparser-no-throw\tcat\told-reason"), false);
    assertFalse(baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", false, "", ""));
    assertTrue(baseline.hasFindings());
  }

  @Test
  void testUpdateModeAlwaysAcceptableRegardlessOfOutcome() throws IOException {
    Baseline baseline = new Baseline(emptyBaseline(), true);
    assertTrue(baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", true, "cat", "boom"));
    assertTrue(baseline.check("test/vcf/4.1/passed/b.vcf", "parser-no-throw", false, "", ""));
  }

  @Test
  void testWriteUpdatedBaselineWritesOnlyCurrentlyDivergingEntries(@TempDir Path tempDir) throws IOException {
    Baseline baseline = new Baseline(emptyBaseline(), true);
    baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", true, "cat", "boom");
    baseline.check("test/vcf/4.1/passed/b.vcf", "parser-no-throw", false, "", "");
    Path out = tempDir.resolve("baseline.tsv");
    baseline.writeUpdatedBaseline(out);
    List<String> lines = Files.readAllLines(out);
    assertEquals(2, lines.size()); // header + one diverging entry
    assertTrue(lines.get(1).startsWith("test/vcf/4.1/passed/a.vcf\tparser-no-throw\tcat\tboom"));
  }

  @Test
  void testWriteFindingsIncludesStatusColumn(@TempDir Path tempDir) throws IOException {
    Baseline baseline = new Baseline(emptyBaseline(), false);
    baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", true, "cat", "boom");
    Path out = tempDir.resolve("findings.tsv");
    baseline.writeFindings(out);
    List<String> lines = Files.readAllLines(out);
    assertEquals(2, lines.size());
    assertTrue(lines.get(1).endsWith("\tNEW"));
  }

  @Test
  void testWriteSummaryTalliesByVersionAndCheckType(@TempDir Path tempDir) throws IOException {
    Baseline baseline = new Baseline(emptyBaseline(), false);
    baseline.check("test/vcf/4.1/passed/a.vcf", "parser-no-throw", false, "", "");
    baseline.check("test/vcf/4.1/passed/b.vcf", "parser-no-throw", false, "", "");
    baseline.check("test/vcf/4.2/passed/c.vcf", "parser-no-throw", false, "", "");
    Path out = tempDir.resolve("summary.md");
    baseline.writeSummary(out);
    String content = Files.readString(out);
    assertTrue(content.contains("| 4.1 | parser-no-throw | 2 | 2 | 0 |"));
    assertTrue(content.contains("| 4.2 | parser-no-throw | 1 | 1 | 0 |"));
  }
}

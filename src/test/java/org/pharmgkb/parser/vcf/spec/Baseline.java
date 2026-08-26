package org.pharmgkb.parser.vcf.spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;


/**
 * Reads the committed hts-specs-baseline.tsv file and, for every (fixture, check type) pair a test run evaluates,
 * decides whether the actual outcome matches expectation: not-baselined-and-didn't-diverge,
 * baselined-and-still-diverges-the-same-way, or one of three mismatches -- {@code NEW} (diverges now but wasn't
 * baselined), {@code STALE} (was baselined but no longer diverges), or {@code CHANGED} (still diverges, and is
 * still baselined, but its {@code category} or {@code reason} no longer matches the baselined row -- i.e. it
 * diverges for a different reason than the one on record). Also tallies totals for the version x check-type
 * summary report, and collects every mismatch (NEW, STALE, or CHANGED) for the findings report. One instance is
 * shared across a whole {@code HtsSpecsConformanceTest} run, since results accumulate across all six check
 * factories.
 *
 * <p>In update mode (system property {@code htsSpecsUpdateBaseline=true}), {@link #check} never reports a
 * mismatch as unacceptable -- every outcome is accepted, and {@link #writeUpdatedBaseline} rewrites the baseline
 * file to reflect exactly what currently diverges, instead of {@link #writeFindings} reporting a diff against the
 * old baseline.
 */
final class Baseline {

  record Entry(String fixturePath, String checkType, String category, String reason) {
    String key() {
      return fixturePath + "\t" + checkType;
    }
  }

  // For a CHANGED finding, entry() holds the current (category, reason) and baselineCategory/baselineReason hold
  // the baselined values being superseded, so both sides of the change are visible in one row. Null for NEW and
  // STALE findings, which have only one side to show.
  record Finding(Entry entry, String status, @Nullable String baselineCategory, @Nullable String baselineReason) {}

  private final Map<String, Entry> m_baseline = new LinkedHashMap<>();
  private final List<Finding> m_findings = new ArrayList<>();
  private final Map<String, Entry> m_currentlyDiverging = new LinkedHashMap<>();
  // key: version + "\t" + checkType -> {total evaluated, total acceptable}
  private final Map<String, int[]> m_tally = new TreeMap<>();
  private final boolean m_updateMode;

  Baseline(@Nullable InputStream baselineInput, boolean updateMode) throws IOException {
    m_updateMode = updateMode;
    if (baselineInput != null) {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(baselineInput, StandardCharsets.UTF_8))) {
        reader.readLine(); // header
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          // limit 4, not -1: reason (writer-bytediff's especially) can itself embed literal tab characters from
          // the diffed VCF line, so a plain split would truncate it at the first embedded tab instead of keeping
          // it whole in parts[3].
          String[] parts = line.split("\t", 4);
          Entry entry = new Entry(parts[0], parts[1], parts[2], parts[3]);
          m_baseline.put(entry.key(), entry);
        }
      }
    }
  }

  /**
   * Records the outcome of one (fixture, check type) evaluation.
   * @return whether this outcome is acceptable and the caller's assertion should pass. Always true in update mode.
   */
  synchronized boolean check(String fixturePath, String checkType, boolean diverged, String category,
      String reason) {
    String version = extractVersion(fixturePath);
    int[] tally = m_tally.computeIfAbsent(version + "\t" + checkType, k -> new int[2]);
    tally[0]++;

    String key = fixturePath + "\t" + checkType;
    Entry baselineEntry = m_baseline.get(key);
    boolean acceptable;
    if (diverged) {
      Entry currentEntry = new Entry(fixturePath, checkType, category, reason);
      m_currentlyDiverging.put(key, currentEntry);
      if (baselineEntry == null) {
        m_findings.add(new Finding(currentEntry, "NEW", null, null));
        acceptable = m_updateMode;
      } else if (!baselineEntry.category().equals(category) || !baselineEntry.reason().equals(reason)) {
        m_findings.add(new Finding(currentEntry, "CHANGED", baselineEntry.category(), baselineEntry.reason()));
        acceptable = m_updateMode;
      } else {
        acceptable = true;
      }
    } else {
      if (baselineEntry != null) {
        m_findings.add(new Finding(baselineEntry, "STALE", null, null));
        acceptable = m_updateMode;
      } else {
        acceptable = true;
      }
    }
    if (acceptable) {
      tally[1]++;
    }
    return acceptable;
  }

  private static String extractVersion(String fixturePath) {
    // fixturePath looks like "test/vcf/4.1/passed/foo.vcf" -- version is the 3rd path segment
    String[] parts = fixturePath.split("/");
    return parts.length > 2 ? parts[2] : "unknown";
  }

  void writeFindings(Path findingsFile) throws IOException {
    List<String> lines = new ArrayList<>();
    // baseline_category/baseline_reason are populated only for a CHANGED finding, showing the superseded
    // baselined values alongside entry()'s current ones; empty for NEW/STALE, which have only one side to show.
    lines.add("fixture_path\tcheck_type\tcategory\treason\tstatus\tbaseline_category\tbaseline_reason");
    for (Finding f : m_findings) {
      lines.add(f.entry().fixturePath() + "\t" + f.entry().checkType() + "\t" + f.entry().category() + "\t" +
          f.entry().reason() + "\t" + f.status() + "\t" +
          (f.baselineCategory() == null ? "" : f.baselineCategory()) + "\t" +
          (f.baselineReason() == null ? "" : f.baselineReason()));
    }
    if (findingsFile.getParent() != null) {
      Files.createDirectories(findingsFile.getParent());
    }
    Files.write(findingsFile, lines);
  }

  void writeUpdatedBaseline(Path baselineFile) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add("fixture_path\tcheck_type\tcategory\treason");
    m_currentlyDiverging.values().stream()
        .sorted(Comparator.comparing(Entry::checkType).thenComparing(Entry::fixturePath))
        .forEach(entry -> lines.add(entry.fixturePath() + "\t" + entry.checkType() + "\t" + entry.category() + "\t" +
            entry.reason()));
    if (baselineFile.getParent() != null) {
      Files.createDirectories(baselineFile.getParent());
    }
    Files.write(baselineFile, lines);
  }

  void writeSummary(Path summaryFile) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add("# hts-specs conformance summary");
    lines.add("");
    lines.add("| Version | Check | Total | Matches baseline | New/stale (FAIL) |");
    lines.add("|---|---|---|---|---|");
    int grandTotal = 0;
    int grandMatched = 0;
    for (Map.Entry<String, int[]> e : m_tally.entrySet()) {
      String[] keyParts = e.getKey().split("\t", 2);
      int total = e.getValue()[0];
      int matched = e.getValue()[1];
      grandTotal += total;
      grandMatched += matched;
      lines.add("| " + keyParts[0] + " | " + keyParts[1] + " | " + total + " | " + matched + " | " +
          (total - matched) + " |");
    }
    lines.add("| **Total** | | **" + grandTotal + "** | **" + grandMatched + "** | **" +
        (grandTotal - grandMatched) + "** |");
    if (summaryFile.getParent() != null) {
      Files.createDirectories(summaryFile.getParent());
    }
    Files.write(summaryFile, lines);
  }

  boolean hasFindings() {
    return !m_findings.isEmpty();
  }

  int findingsCount() {
    return m_findings.size();
  }
}

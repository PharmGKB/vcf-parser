package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * Reads the local-fixtures corpus's own header-comment convention directly off a fixture's raw lines --
 * {@code ##Authority=}, {@code ##CauseOfFailure=}, {@code ##ExpectParser=}, {@code ##ExpectWarnContains=},
 * {@code ##RoundTripDivergenceReason=} -- the same way {@link HtsSpecsConformanceTest}'s
 * {@code readCauseOfFailure} reads {@code ##CauseOfFailure=} directly off an hts-specs fixture without going
 * through {@code VcfParser}'s structured metadata model. These lines also parse cleanly through a real
 * {@code VcfParser} as ordinary unstructured generic metadata (exactly like {@code ##CauseOfFailure=} already
 * does in every hts-specs fixture) -- no special-casing is needed there.
 *
 * <p>Unlike the hts-specs corpus, this one is fully authored by this project, so there is no
 * baseline-divergence file the way {@link Baseline} provides -- every fixture's expected outcome is declared
 * inline via these keys, and a mismatch is simply a test failure. The keys:
 * <ul>
 *   <li>{@code ##Authority=} -- every fixture, 1 or more. Names the external authority the expectation rests
 *       on: spec text with a verbatim quote, a tool name and version, an upstream hts-specs fixture path, or
 *       several together. Never "this is what this library does" -- that's the entire point of this corpus.
 *   <li>{@code ##CauseOfFailure=} -- {@code failed/} only, exactly 1. A short prose statement of what makes
 *       the input invalid, matching hts-specs' own convention.
 *   <li>{@code ##ExpectParser=} -- {@code failed/} only, exactly 1. One of {@code throw}, {@code warn},
 *       {@code silent}. {@code silent} is a legitimate, deliberate value: a {@code failed/} fixture records
 *       "the cited authority calls this invalid," not "our library must reject it."
 *   <li>{@code ##ExpectWarnContains=} -- either directory, 0 or more. A substring the captured warning text
 *       must contain. Required in practice when {@code ExpectParser=warn}, to pin content and avoid a
 *       generic-noise false positive; optional on {@code passed/} fixtures expected to warn without rejecting.
 *   <li>{@code ##RoundTripDivergenceReason=} -- {@code passed/} only, 0 or 1. Presence means the fixture's
 *       round trip is not required to be byte-identical, and states why. Absence means the default: must
 *       round-trip byte-identical. There is no equivalent override for the semantic round trip, which stays
 *       unconditional for every {@code passed/} fixture.
 * </ul>
 */
record LocalFixtureMetadata(List<String> authorities, Optional<String> causeOfFailure,
    Optional<String> expectParser, List<String> expectWarnContains,
    Optional<String> roundTripDivergenceReason) {

  private static final String AUTHORITY_PREFIX = "##Authority=";
  private static final String CAUSE_OF_FAILURE_PREFIX = "##CauseOfFailure=";
  private static final String EXPECT_PARSER_PREFIX = "##ExpectParser=";
  private static final String EXPECT_WARN_CONTAINS_PREFIX = "##ExpectWarnContains=";
  private static final String ROUND_TRIP_DIVERGENCE_REASON_PREFIX = "##RoundTripDivergenceReason=";

  static LocalFixtureMetadata parse(Path file) throws IOException {
    List<String> authorities = new ArrayList<>();
    List<String> expectWarnContains = new ArrayList<>();
    String causeOfFailure = null;
    String expectParser = null;
    String roundTripDivergenceReason = null;
    for (String line : Files.readAllLines(file)) {
      if (line.startsWith(AUTHORITY_PREFIX)) {
        authorities.add(line.substring(AUTHORITY_PREFIX.length()));
      } else if (line.startsWith(CAUSE_OF_FAILURE_PREFIX)) {
        causeOfFailure = line.substring(CAUSE_OF_FAILURE_PREFIX.length());
      } else if (line.startsWith(EXPECT_PARSER_PREFIX)) {
        expectParser = line.substring(EXPECT_PARSER_PREFIX.length());
      } else if (line.startsWith(EXPECT_WARN_CONTAINS_PREFIX)) {
        expectWarnContains.add(line.substring(EXPECT_WARN_CONTAINS_PREFIX.length()));
      } else if (line.startsWith(ROUND_TRIP_DIVERGENCE_REASON_PREFIX)) {
        roundTripDivergenceReason = line.substring(ROUND_TRIP_DIVERGENCE_REASON_PREFIX.length());
      } else if (!line.startsWith("#")) {
        break; // past the header block -- no need to scan data lines
      }
    }
    return new LocalFixtureMetadata(authorities, Optional.ofNullable(causeOfFailure),
        Optional.ofNullable(expectParser), expectWarnContains, Optional.ofNullable(roundTripDivergenceReason));
  }
}

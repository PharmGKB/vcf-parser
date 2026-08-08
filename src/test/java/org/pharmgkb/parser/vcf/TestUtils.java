package org.pharmgkb.parser.vcf;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;


/**
 * Utilities for testing vcf-parser.
 * @author Douglas Myers-Turnbull
 */
public class TestUtils {

  /**
   * The shared context for tests that construct model objects directly and expect a content violation to be
   * rejected outright -- the behavior the former public strict constructors had.
   * <p>
   * A single shared instance is deliberate: {@code VcfContext} identity is what
   * {@code VcfContext.newRecord}/{@code VcfMetadata.Builder} check components against, so tests that build a
   * position, its samples, and its metadata separately still produce a consistent set. A test that needs two
   * genuinely different contexts (to exercise those very checks) constructs its own.
   */
  public static final VcfContext STRICT_CONTEXT = VcfContext.strict("VCFv4.2");

  /**
   * The shared context for tests that expect a content violation to be warned about and recovered from -- the
   * behavior a parsed file gets, and the behavior the former {@code VcfMetadata.Builder} lenient opt-in and the
   * {@code Map}-based declaration constructors had.
   */
  public static final VcfContext LENIENT_CONTEXT = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);

  /** @return a lenient context routing warnings to {@code warnings}, for asserting on warning text. */
  public static VcfContext lenientContext(VcfWarnings warnings) {
    return VcfContext.lenient("VCFv4.2", warnings);
  }

  /**
   * @return The contents of the file, with linebreaks included
   */
  public static String readFileToString(Path file) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (FileReader fr = new FileReader(file.toFile())) {
      int chr;
      while ((chr = fr.read()) != -1) {
        sb.append((char)chr);
      }
    }
    return sb.toString();
  }
}

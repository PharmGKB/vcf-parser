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

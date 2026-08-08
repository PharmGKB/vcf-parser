package org.pharmgkb.parser.vcf;

/**
 * A field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public interface ReservedProperty {

  String getId();

  String getDescription();

  Class getType();

  boolean isList();

  /**
   * @return The exact VCF {@code Number} value this reserved key's declaration should have: an integer literal
   * (e.g. {@code "1"}, {@code "4"}) or one of {@code "A"}, {@code "G"}, {@code "R"}, {@code "."}.
   */
  String getExpectedNumber();
}

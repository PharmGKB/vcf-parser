package org.pharmgkb.parser.vcf.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ReservedFormatPropertyTest {

  @Test
  public void testFromIdResolvesReservedKeys() {
    assertEquals(ReservedFormatProperty.Genotype, ReservedFormatProperty.fromId("GT"));
    assertEquals(ReservedFormatProperty.PhredScaledGenotypeLikelihoods, ReservedFormatProperty.fromId("PL"));
    assertEquals(ReservedFormatProperty.AncestralHaplotypeId, ReservedFormatProperty.fromId("AHAP"));
  }

  @Test
  public void testFromIdReturnsNullForNonReservedKey() {
    assertNull(ReservedFormatProperty.fromId("NOT_A_RESERVED_KEY"));
  }

  @Test
  public void testGetExpectedNumberForStandardKeys() {
    // sourced from VCFv4.3's Reserved genotype keys table (table:reserved-genotypes)
    assertEquals("1", ReservedFormatProperty.Genotype.getExpectedNumber());
    assertEquals("G", ReservedFormatProperty.GenotypeLikelihoods.getExpectedNumber());
    assertEquals("G", ReservedFormatProperty.PhredScaledGenotypeLikelihoods.getExpectedNumber());
    assertEquals("A", ReservedFormatProperty.ExpectedAlleleCounts.getExpectedNumber());
  }

  @Test
  public void testGetExpectedNumberForFixedCountAndStructuralVariantKeys() {
    // HQ: fixed count of 2, declared natively in VCFv4.2.tex (no VCFv4.3 clarification needed)
    assertEquals("2", ReservedFormatProperty.HaplotypeQualities.getExpectedNumber());
    // structural variant genotype keys are also declared natively in VCFv4.2.tex
    assertEquals("1", ReservedFormatProperty.CopyNumber.getExpectedNumber());
    assertEquals(".", ReservedFormatProperty.CopyNumberLikelihood.getExpectedNumber());
  }

  @Test
  public void testGetExpectedNumberForGleSpecialCase() {
    // GLE has no formal Number in VCFv4.1.tex or VCFv4.2.tex (both prose-only, "(String)" type given but no
    // Number), and VCFv4.3 removed the key entirely ("Removed unused and ill-defined GLE FORMAT tag"). No spec
    // table exists for it. Sourced instead directly from hts-specs' own fixtures, which consistently expect it:
    // test/vcf/4.1/failed/failed_meta_format_030.vcf and test/vcf/4.2/failed/failed_meta_format_029.vcf both have
    // ##CauseOfFailure=FORMAT GLE Number is not G.
    assertEquals("G", ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy.getExpectedNumber());
  }
}

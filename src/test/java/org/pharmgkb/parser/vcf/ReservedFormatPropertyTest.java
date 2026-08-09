package org.pharmgkb.parser.vcf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReservedFormatPropertyTest {

  @Test
  public void testFromIdResolvesReservedKeys() {
    assertEquals(ReservedFormatProperty.Genotype, ReservedFormatProperty.fromId("GT"));
    assertEquals(ReservedFormatProperty.PhredScaledGenotypeLikelihoods, ReservedFormatProperty.fromId("PL"));
    assertEquals(ReservedFormatProperty.AncestralHaplotypeId, ReservedFormatProperty.fromId("AHAP"));
  }

  @Test
  public void testVersionAwareFromIdDropsGleFrom43() {
    // GLE is reserved in VCFv4.1.tex line 208 and VCFv4.2.tex line 225, and was removed by VCFv4.3
    // (VCFv4.3.tex line 2168, "Removed unused and ill-defined GLE FORMAT tag", restated in VCFv4.4.tex line
    // 2630 and VCFv4.5.tex line 2812). It is the only key this overload treats differently from fromId(String).
    assertEquals(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy,
        ReservedFormatProperty.fromId("GLE", VcfVersion.V4_1));
    assertEquals(ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy,
        ReservedFormatProperty.fromId("GLE", VcfVersion.V4_2));
    assertNull(ReservedFormatProperty.fromId("GLE", VcfVersion.V4_3));
    // every other reserved key resolves identically at both versions -- this overload is a GLE-specific
    // existence check, not a version-aware reserved-key table
    for (ReservedFormatProperty property : ReservedFormatProperty.values()) {
      if (property == ReservedFormatProperty.GenotypeLikelihoodsOfHeterogenousPloidy) {
        continue;
      }
      assertEquals(property, ReservedFormatProperty.fromId(property.getId(), VcfVersion.V4_2));
      assertEquals(property, ReservedFormatProperty.fromId(property.getId(), VcfVersion.V4_3));
    }
    assertNull(ReservedFormatProperty.fromId(null, VcfVersion.V4_3));
    assertNull(ReservedFormatProperty.fromId("NOT_A_RESERVED_KEY", VcfVersion.V4_2));
  }

  @Test
  public void testFromIdReturnsNullForNonReservedKey() {
    assertNull(ReservedFormatProperty.fromId("NOT_A_RESERVED_KEY"));
  }

  @Test
  public void testFromIdReturnsNullForNullId() {
    assertNull(ReservedFormatProperty.fromId(null));
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
  public void testFromIdResolvesAlleleDepthAndPosteriorProbabilityKeys() {
    // AD/ADF/ADR/PP are sourced from VCFv4.3's Reserved genotype keys table (table:reserved-genotypes) --
    // VCFv4.2 does not table them at all. AD in particular is one of the most commonly emitted FORMAT fields
    // in real-world VCFs (allelic depths).
    assertEquals(ReservedFormatProperty.AlleleDepth, ReservedFormatProperty.fromId("AD"));
    assertEquals(ReservedFormatProperty.AlleleDepthForward, ReservedFormatProperty.fromId("ADF"));
    assertEquals(ReservedFormatProperty.AlleleDepthReverse, ReservedFormatProperty.fromId("ADR"));
    assertEquals(ReservedFormatProperty.PhredScaledPosteriorProbabilities, ReservedFormatProperty.fromId("PP"));
    assertEquals("R", ReservedFormatProperty.AlleleDepth.getExpectedNumber());
    assertEquals("R", ReservedFormatProperty.AlleleDepthForward.getExpectedNumber());
    assertEquals("R", ReservedFormatProperty.AlleleDepthReverse.getExpectedNumber());
    assertEquals("G", ReservedFormatProperty.PhredScaledPosteriorProbabilities.getExpectedNumber());
    assertEquals(Long.class, ReservedFormatProperty.AlleleDepth.getType());
    assertTrue(ReservedFormatProperty.AlleleDepth.isList());
    assertTrue(ReservedFormatProperty.PhredScaledPosteriorProbabilities.isList());
  }

  @Test
  public void testFromIdResolvesCopyNumberPosteriorProbabilities() {
    // CNP has no VCFv4.2 declaration at all (introduced by VCFv4.3's "FORMAT keys used for structural
    // variants" section, sv-format-keys) -- same gap shape as AD/ADF/ADR/PP above, found during this
    // batch's own reserved-key sweep rather than the original finding
    assertEquals(ReservedFormatProperty.CopyNumberPosteriorProbabilities, ReservedFormatProperty.fromId("CNP"));
    // expectedNumber stays "G" (the literal, only spec value -- see the enum entry's own comment) even though
    // CNP's own "G" means "per possible copy number," not the ploidy-derived genotype ordering GT/GL/PL/GP/PP's
    // "G" means. Keeping "G" here still lets FormatMetadata correctly flag a declared Number that isn't "G";
    // it's specifically the generic value-cardinality check that's skipped for CNP -- see
    // InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck.
    assertEquals("G", ReservedFormatProperty.CopyNumberPosteriorProbabilities.getExpectedNumber());
    // and, unlike GLE's exemption, CNP's is not version-gated: CNP is reserved at every version this library
    // interprets, so the exemption holds under 4.2 and 4.3 alike
    assertTrue(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNP", VcfVersion.V4_2));
    assertTrue(InternalUtils.isExemptFromGenotypeOrderingCardinalityCheck("CNP", VcfVersion.V4_3));
    assertEquals(VcfFloat.class, ReservedFormatProperty.CopyNumberPosteriorProbabilities.getType());
    assertTrue(ReservedFormatProperty.CopyNumberPosteriorProbabilities.isList());
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
  public void testFromIdResolvesRenamedGenotypePosteriorConstant() {
    // GenotypePosterior was renamed from GenotypePosteriorProbabilitiesPhredScaled: the old name asserted a
    // VCFv4.2-only interpretation (phred-scaled) that VCFv4.3 changed to a 0-1 probability (VCFv4.3.tex line
    // 491; see the enum entry's own comment). No compatibility alias was kept. The line above is the one
    // deliberate surviving mention of the old identifier outside semantic-release-owned history: it records
    // what the constant was renamed from and asserts nothing about the old meaning. A rename sweep grepping
    // for that identifier should expect exactly that one hit and no other.
    assertEquals(ReservedFormatProperty.GenotypePosterior, ReservedFormatProperty.fromId("GP"));
    assertEquals("G", ReservedFormatProperty.GenotypePosterior.getExpectedNumber());
    assertEquals(VcfFloat.class, ReservedFormatProperty.GenotypePosterior.getType());
    assertTrue(ReservedFormatProperty.GenotypePosterior.isList());
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

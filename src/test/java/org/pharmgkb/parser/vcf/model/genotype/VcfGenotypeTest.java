package org.pharmgkb.parser.vcf.model.genotype;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import com.google.common.collect.ArrayListMultimap;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import static org.junit.jupiter.api.Assertions.*;


public class VcfGenotypeTest {

  @Test
  public void testFromString() throws Exception {

    VcfGenotype genotype1 = VcfGenotype.fromString("A/TT");
    assertEquals("A/TT", genotype1.toString());
    assertFalse(genotype1.isPhased());

    VcfGenotype genotype2 = VcfGenotype.fromString("A");
    assertTrue(genotype2.isPhased());
    assertEquals("A|A", genotype2.toString());
    assertTrue(genotype2.isPhased());

    VcfGenotype genotype3 = VcfGenotype.fromString("<xx>");
    assertEquals("<xx>|<xx>", genotype3.toString());
    assertTrue(genotype3.isPhased());

    // mate locations need a ":pos" (a breakend chromosome/contig is never valid without one)
    VcfGenotype genotype4 = VcfGenotype.fromString("]12:1]<xx>/ag]20:25]");
    assertFalse(genotype4.isPhased());
    assertEquals("]12:1]<xx>/ag]20:25]", genotype4.toString());

    VcfGenotype genotype5 = VcfGenotype.fromString("AA/AA");
    assertTrue(genotype5.isPhased());
    assertEquals("AA|AA", genotype5.toString());
    assertTrue(genotype5.isPhased());

  }

  @Test
  public void testFromVcf() throws Exception {

    List<String> alts = Arrays.asList("G", "AAA", "<ID>", "C[2:321682[");
    VcfPosition position = new VcfPosition("chr1", 1, Arrays.asList("id"), "A", alts, new BigDecimal("0.0"),
        Arrays.asList(), ArrayListMultimap.create(), Arrays.asList("GT"));

    VcfGenotype genotype1 = makeGenotype(position, "0/1");
    assertEquals("A/G", genotype1.toString());

    VcfGenotype genotype2 = makeGenotype(position, "0|3");
    assertEquals("A|<ID>", genotype2.toString());

    VcfGenotype genotype3 = makeGenotype(position, "2/4");
    assertEquals("AAA/C[2:321682[", genotype3.toString());

    VcfGenotype genotype4 = makeGenotype(position, "1");
    assertEquals("G|G", genotype4.toString());

    VcfGenotype genotype5 = makeGenotype(position, "./.");
    assertEquals("./.", genotype5.toString());

    VcfGenotype genotype6 = makeGenotype(position, ".");
    assertEquals(".|.", genotype6.toString());

    VcfGenotype genotype7 = makeGenotype(position, "2/2");
    assertEquals("AAA|AAA", genotype7.toString());

  }

  @Test
  public void testFromStringNoCall() {
    VcfGenotype dot = VcfGenotype.fromString(".");
    assertTrue(dot.isNoCall());
    assertEquals(".|.", dot.toString());

    VcfGenotype dotSlash = VcfGenotype.fromString("./.");
    assertTrue(dotSlash.isNoCall());
    assertFalse(dotSlash.isPhased());
    assertEquals("./.", dotSlash.toString());

    VcfGenotype dotPipe = VcfGenotype.fromString(".|.");
    assertTrue(dotPipe.isNoCall());
    assertTrue(dotPipe.isPhased());
    assertEquals(".|.", dotPipe.toString());

    // a partial call keeps its concrete allele and is not a no-call
    VcfGenotype partial = VcfGenotype.fromString("A/.");
    assertFalse(partial.isNoCall());
    assertNotNull(partial.getAllele1());
    assertEquals("A", partial.getAllele1().toString());
    assertNull(partial.getAllele2());
    assertEquals("A/.", partial.toString());
  }

  @Test
  public void testFromNumberStringMissing() {
    VcfPosition position = numberPosition();

    VcfGenotype partial = VcfGenotype.fromNumberString(position, "0/.");
    assertFalse(partial.isNoCall());
    assertNull(partial.getAllele2());
    assertEquals("A/.", partial.toString());

    assertTrue(VcfGenotype.fromNumberString(position, "./.").isNoCall());

    VcfGenotype phasedPartial = VcfGenotype.fromNumberString(position, ".|1");
    assertTrue(phasedPartial.isPhased());
    assertNull(phasedPartial.getAllele1());
    assertEquals(".|G", phasedPartial.toString());
  }

  @Test
  public void testInvalidAlleleIndex() {
    VcfPosition position = numberPosition(); // REF + 4 ALTs -> valid indices 0..4
    assertThrows(VcfFormatException.class, () -> VcfGenotype.fromNumberString(position, "0/9"));
    assertThrows(VcfFormatException.class, () -> VcfGenotype.fromNumberString(position, "5"));
  }

  @Test
  public void testMakeGtRoundTrip() {
    VcfPosition position = numberPosition();
    assertEquals("0/1", VcfGenotype.fromNumberString(position, "0/1").makeGt(position));
    assertEquals("1|0", VcfGenotype.fromNumberString(position, "1|0").makeGt(position));
    assertEquals("0/.", VcfGenotype.fromNumberString(position, "0/.").makeGt(position));
    assertEquals("./.", VcfGenotype.fromNumberString(position, "./.").makeGt(position));
    // a fromString no-call now round-trips through makeGt instead of throwing
    assertEquals(".|.", VcfGenotype.fromString(".").makeGt(position));
  }

  @Test
  public void testMissingGt() {
    VcfPosition position = numberPosition();
    VcfSample sample = new VcfSample(new LinkedHashMap<>()); // no GT property
    assertNull(VcfGenotype.fromVcf(position, sample));
  }

  @Test
  public void testCanonicalization() {
    // haploid -> phased homozygous
    VcfGenotype haploid = VcfGenotype.fromString("A");
    assertTrue(haploid.isPhased());
    assertEquals("A|A", haploid.toString());
    // homozygous unphased -> phased
    VcfGenotype homozygous = VcfGenotype.fromString("A/A");
    assertTrue(homozygous.isPhased());
    assertEquals("A|A", homozygous.toString());
  }

  private static VcfPosition numberPosition() {
    List<String> alts = Arrays.asList("G", "AAA", "<ID>", "C[2:321682[");
    return new VcfPosition("chr1", 1, Arrays.asList("id"), "A", alts, new BigDecimal("0.0"),
        Arrays.asList(), ArrayListMultimap.create(), Arrays.asList("GT"));
  }

  private VcfGenotype makeGenotype(VcfPosition position, String genotype) {
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, genotype);
    return VcfGenotype.fromVcf(position, sample);
  }

}

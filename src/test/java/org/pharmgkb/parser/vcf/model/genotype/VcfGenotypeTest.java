package org.pharmgkb.parser.vcf.model.genotype;

import com.google.common.collect.ArrayListMultimap;
import org.junit.Test;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    VcfGenotype genotype4 = VcfGenotype.fromString("]12]<xx>/ag]20:25]");
    assertFalse(genotype4.isPhased());
    assertEquals("]12]<xx>/ag]20:25]", genotype4.toString());

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

  private VcfGenotype makeGenotype(@Nonnull VcfPosition position, @Nonnull String genotype) {
    VcfSample sample = new VcfSample(new LinkedHashMap<>());
    sample.putProperty(ReservedFormatProperty.Genotype, genotype);
    return VcfGenotype.fromVcf(position, sample);
  }

}
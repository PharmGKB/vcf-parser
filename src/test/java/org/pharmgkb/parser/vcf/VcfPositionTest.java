package org.pharmgkb.parser.vcf;

import org.junit.Test;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfSample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author Douglas Myers-Turnbull
 */
public class VcfPositionTest {

  @Test
  public void test() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/vcfposition.vcf")))) {
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith((metadata, position, sampleData) -> {
            switch ((int) position.getPosition()) {

              case 1:
                assertEquals("A", position.getInfo(ReservedInfoProperty.AncestralAllele));
                assertEquals(true, position.getInfo(ReservedInfoProperty.Hapmap2)); // flag is set
                assertNull(position.getInfo(ReservedInfoProperty.ThousandGenomes)); // property doesn't exist

                List<BigDecimal> b = position.getInfo(ReservedInfoProperty.AlleleFrequency);
                assertNotNull(b);
                assertEquals(1, b.size());
                assertEquals(new BigDecimal("0.124"), b.get(0));

                List<Long> c = position.getInfo(ReservedInfoProperty.AlleleCount);
                assertNotNull(c);
                assertEquals(2, c.size());
                assertEquals(5l, (long)c.get(0));
                assertEquals(10l, (long)c.get(1));
                break;

              case 2:
                VcfSample sample = sampleData.get(0);
                List<BigDecimal> d = sample.getProperty(ReservedFormatProperty.GenotypePosteriorProbabilitiesPhredScaled);
                assertNotNull(d);
                assertEquals(2, d.size());
                assertEquals(new BigDecimal("0.05"), d.get(0));
                assertEquals(new BigDecimal("0.06"), d.get(1));
                break;
            }
          })
          .build().parse();
    }
  }
}

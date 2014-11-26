package org.pharmgkb.parser.vcf;

import org.junit.Test;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.VcfMetadata;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import static org.junit.Assert.*;

/**
 * JUnit test case for {@link VcfParser}.
 *
 * @author Mark Woon
 */
public class VcfParserTest {


  @Test
  public void testCnv() throws Exception {

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/cnv.vcf")))) {
      VcfParser parser = new VcfParser()
          .reader(reader)
          .parseWith((metadata, position, sampleData) -> {
            for (String base : position.getAltBases()) {
              assertTrue(base.startsWith("<CN"));
              assertTrue(base.endsWith(">"));
            }
            assertEquals(metadata.getNumSamples(), sampleData.size());
          })
          .parse();
      VcfMetadata vcfMetadata = parser.getMetadata();
      IdDescriptionMetadata md1 = vcfMetadata.getAlt("CN0");
      assertNotNull(md1);
      IdDescriptionMetadata md2 = vcfMetadata.getAlt("<CN0>");
      assertNotNull(md2);
      assertEquals(md1, md2);

      assertEquals("HG00096", vcfMetadata.getSampleName(0));
      assertEquals("HG00099", vcfMetadata.getSampleName(2));
    }
  }
}

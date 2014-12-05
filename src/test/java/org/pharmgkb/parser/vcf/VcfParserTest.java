package org.pharmgkb.parser.vcf;

import org.junit.Test;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.VcfMetadata;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
      VcfParser parser = new VcfParser.Builder()
          .withReader(reader)
          .parseWith((metadata, position, sampleData) -> {
            for (String base : position.getAltBases()) {
              assertTrue(base.startsWith("<CN"));
              assertTrue(base.endsWith(">"));
            }
            assertEquals(metadata.getNumSamples(), sampleData.size());
          })
          .build();
      parser.parse();
      VcfMetadata vcfMetadata = parser.getMetadata();
      assertNotNull(vcfMetadata);
      IdDescriptionMetadata md1 = vcfMetadata.getAlt("CN0");
      assertNotNull(md1);
      IdDescriptionMetadata md2 = vcfMetadata.getAlt("<CN0>");
      assertNotNull(md2);
      assertEquals(md1, md2);

      assertEquals("HG00096", vcfMetadata.getSampleName(0));
      assertEquals("HG00099", vcfMetadata.getSampleName(2));
    }
  }


  @Test
  public void testRsidOnly() throws Exception {

    VcfLineParser lineParser = (metadata, position, sampleData) -> {
      assertEquals(1, position.getIds().size());
      assertTrue(position.getIds().get(0).matches("rs\\d+"));
      assertEquals(metadata.getNumSamples(), sampleData.size());
    };

    // read from reader
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/rsid.vcf")))) {
      VcfParser parser = new VcfParser.Builder()
          .withReader(reader)
          .rsidsOnly()
          .parseWith(lineParser)
          .build();
      parser.parse();
    }

    // read from file
    Path dataFile = Paths.get(VcfParserTest.class.getResource("/rsid.vcf").toURI());
    assertTrue(Files.exists(dataFile));
    try (VcfParser parser = new VcfParser.Builder().withFile(dataFile).rsidsOnly().parseWith(lineParser).build()) {
      parser.parse();
    }
  }


  @Test
  public void testFile() throws Exception {

    try {
      new VcfParser.Builder()
          .withFile(Paths.get("foo.txt"))
          .parseWith((metadata, position, sampleData) -> {})
          .build();
      fail("Didn't catch invalid path");
    } catch (IllegalArgumentException ex) {
      // expected
      assertTrue(ex.getMessage().contains("Not a VCF file"));
    }

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/notvcf.vcf")))) {
      VcfParser parser = new VcfParser.Builder()
          .withReader(reader)
          .parseWith((metadata, position, sampleData) -> {})
          .build();
      parser.parseMetadata();
      fail("Didn't catch invalid version");
    } catch (IllegalStateException ex) {
      // expected
      assertTrue(ex.getMessage().contains("Not a VCF file"));
    }
  }
}

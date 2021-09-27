package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfSample;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit test case for {@link VcfParser}.
 *
 * @author Mark Woon
 */
public class VcfParserTest {

  @Test
  public void testBasic() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/basic.vcf")))) {
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith((metadata, position, sampleData) -> {
            assertEquals("chr1", position.getChromosome());
            assertEquals(5, position.getPosition());
            assertEquals(Arrays.asList("rsa", "rsb"), position.getIds());
            assertEquals("Aa", position.getRef());
            assertEquals(Arrays.asList("Tt", "Gg", "Cc"), position.getAltBases());
            assertEquals(new BigDecimal("5.2e-10"), position.getQuality());
            assertTrue(position.getFilters().isEmpty());
            assertTrue(position.getInfo().isEmpty());
          })
          .build().parse();
    }
  }

  @Test
  public void testHasComment() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/has_comment.vcf")))) {
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith((metadata, position, sampleData) -> {
            assertEquals("chr1", position.getChromosome());
            assertEquals(5, position.getPosition());
            assertEquals(Arrays.asList("rsa", "rsb"), position.getIds());
            assertEquals("Aa", position.getRef());
            assertEquals(Arrays.asList("Tt", "Gg", "Cc"), position.getAltBases());
            assertEquals(new BigDecimal("5.2e-10"), position.getQuality());
            assertTrue(position.getFilters().isEmpty());
            assertTrue(position.getInfo().isEmpty());
          })
          .build().parse();
    }
  }

  @Test
  public void testWithSamples() throws IOException {
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


  @Test
  public void testIsModifiable() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/vcfposition.vcf")))) {
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith((metadata, position, sampleData) -> {
            position.setRef("test");
            position.getAltBases().add("test");
            position.getIds().add("test");
            position.getFormat().add("test");
            position.getFilters().add("none");
          })
          .build().parse();
    }
  }

  @Test
  public void testNoSamples() throws IOException {

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/no_samples.vcf")))) {
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith((metadata, position, sampleData) -> {
            assertNotNull(position.getFormat());
            assertTrue(position.getFormat().isEmpty());
            assertTrue(sampleData.isEmpty());
            assertNotNull(position.getInfo());
            if (position.getPosition() == 1) {
              assertEquals(1, position.getInfo().size());
              assertEquals(Arrays.asList("0"), position.getInfo().get("NS"));
            } else if (position.getPosition() == 2) {
              assertTrue(position.getInfo().isEmpty());
            }
          })
          .build().parse();
    }
  }

  @Test
  public void testCnv() throws Exception {

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/cnv.vcf")))) {
      VcfParser parser = new VcfParser.Builder()
          .fromReader(reader)
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
          .fromReader(reader)
          .rsidsOnly()
          .parseWith(lineParser)
          .build();
      parser.parse();
    }

    // read from file
    Path dataFile = Paths.get(VcfParserTest.class.getResource("/rsid.vcf").toURI());
    assertTrue(Files.exists(dataFile));
    try (VcfParser parser = new VcfParser.Builder().fromFile(dataFile).rsidsOnly().parseWith(lineParser).build()) {
      parser.parse();
    }
  }


  @Test
  public void testFile() throws Exception {

    try {
      new VcfParser.Builder()
          .fromFile(Paths.get("foo.txt"))
          .parseWith((metadata, position, sampleData) -> {})
          .build();
      fail("Didn't catch invalid path");
    } catch (IllegalArgumentException ex) {
      // expected
      assertTrue(ex.getMessage().contains("Not a VCF file"));
    }

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream("/notvcf.vcf")))) {
      VcfParser parser = new VcfParser.Builder()
          .fromReader(reader)
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

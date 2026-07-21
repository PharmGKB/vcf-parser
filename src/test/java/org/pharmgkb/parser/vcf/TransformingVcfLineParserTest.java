package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class TransformingVcfLineParserTest {

  @Test
  public void simpleIntegrationTest() throws Exception {

    VcfTransformation transformation = new VcfTransformation() {
      @Override
      public void transformMetadata(VcfMetadata metadata) {
        // this is the only place where we can alter the metadata
        metadata.getInfo().put("TestInfo", new InfoMetadata("an_id", "a_description", InfoType.String, "G", null, null));
        metadata.getFilters().put("Transformation", new IdDescriptionMetadata("Transformation", "A transformation was applied"));
      }

      @Override
      public boolean transformDataLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {

        position.getFilters().clear();
        metadata.getFilters().clear(); // since we're in transformatDataLine, this should do nothing!

        if (position.getPosition() == 1) {
          // This implies that the correct encoding for a no-value INFO annotation (e.g. DB) is with a single "" value,
          // NOT with an empty list:
          assertEquals(Collections.singletonList(""), position.getInfo().get(ReservedInfoProperty.Imprecise.getId()));
          // Similarly, the correct encoding for no info is a nonexistent list, NOT with an empty list:
          position.getInfo().get(ReservedInfoProperty.Imprecise.getId()).clear();
          // Again, put "" INSTEAD OF doing:
          // position.getInfo().replaceValues(ReservedInfoProperty.Dbsnp.getId(), Collections.emptyList());
          position.getInfo().put(ReservedInfoProperty.Dbsnp.getId(), "");
        }
        position.setPosition(position.getPosition() + 10);
        return true;
      }
    };

    StringWriter sw = new StringWriter();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder().
        addTransformation(transformation, new PrintWriter(sw)).build();
    Path dataFile = Paths.get(VcfParserTest.class.getResource("/to_transform.vcf").toURI());
    try (VcfParser parser = new VcfParser.Builder().parseWith(lineParser).fromFile(dataFile).build()) {
      parser.parse();
    }
    String actualResult = sw.toString();

    // avoid using IOUtils.contentEquals so that we can see the difference if the tests fails
    Path expected = Paths.get(VcfParserTest.class.getResource("/transformed.vcf").toURI());
    String expectedResult;
    try (BufferedReader br = new BufferedReader(new FileReader(expected.toFile()))) {
      expectedResult = IOUtils.toString(br);
    }
    assertEquals(expectedResult, actualResult);
  }

  /**
   * An identity transformation (no changes) must reproduce its input exactly, given input already in the writer's
   * canonical form. {@code transformed.vcf} is the writer's own output, so reading and re-writing it must be idempotent.
   */
  @Test
  public void testIdentityRoundTrip() throws Exception {
    StringWriter sw = new StringWriter();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .addTransformation(new VcfTransformation() {}, new PrintWriter(sw)).build();
    Path dataFile = Paths.get(VcfParserTest.class.getResource("/transformed.vcf").toURI());
    try (VcfParser parser = new VcfParser.Builder().parseWith(lineParser).fromFile(dataFile).build()) {
      parser.parse();
    }

    String input;
    try (BufferedReader br = new BufferedReader(new FileReader(dataFile.toFile()))) {
      input = IOUtils.toString(br);
    }
    assertEquals(input, sw.toString());
  }

  /**
   * A VCF with metadata but no data lines must still get its header written (the header write must not depend on a data
   * line being present).
   */
  @Test
  public void testHeaderOnlyVcfWritesHeader() throws Exception {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    StringWriter sw = new StringWriter();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .addTransformation(new VcfTransformation() {}, new PrintWriter(sw)).build();
    try (VcfParser parser = new VcfParser.Builder()
        .parseWith(lineParser)
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .build()) {
      parser.parse();
    }
    assertEquals(vcf, sw.toString());
  }

}

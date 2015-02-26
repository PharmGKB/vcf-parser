package org.pharmgkb.parser.vcf;

import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.pharmgkb.parser.vcf.model.*;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TransformingVcfLineParserTest {

  @Test
  public void simpleIntegrationTest() throws Exception {

    VcfTransformation transformation = new VcfTransformation() {
      @Override
      public void transformMetadata(@Nonnull VcfMetadata metadata) {
        metadata.getInfo().put("TestInfo", new InfoMetadata("an_id", "a_description", "String", "G", null, null));
        metadata.getFilters().put("Transformation", new IdDescriptionMetadata("Transformation", "A transformation was applied"));
      }

      @Override
      public boolean transformDataLine(@Nonnull VcfMetadata metadata, @Nonnull VcfPosition position, @Nonnull List<VcfSample> sampleData) {
        position.getFilters().clear();
        metadata.getFilters().clear(); // should do nothing!
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

}
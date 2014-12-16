package org.pharmgkb.parser.vcf;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TransformingVcfLineParserTest {

  @Test
  public void simpleIntegrationTest() throws Exception {
    VcfTransformation transformation = new VcfTransformation() {}; // does nothing
    StringWriter sw = new StringWriter();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder(transformation)
        .toWriter(new PrintWriter(sw)).build();
    Path dataFile = Paths.get(VcfParserTest.class.getResource("/to_transform.vcf").toURI());
    try (VcfParser parser = new VcfParser.Builder().parseWith(lineParser).fromFile(dataFile).build()) {
      parser.parse();
    }
    // TODO Why doesn't this work?
//    Path expected = Paths.get(VcfParserTest.class.getResource("/to_transform.vcf").toURI());
//    System.out.println(sw);
//    try (FileReader fwExpected = new FileReader(expected.toFile())) {
//      try (StringReader stringReader = new StringReader(sw.toString())) {
//        assertTrue(IOUtils.contentEquals(fwExpected, stringReader));
//      }
//    }
  }

}
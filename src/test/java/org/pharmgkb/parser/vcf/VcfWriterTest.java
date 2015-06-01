package org.pharmgkb.parser.vcf;

import org.junit.Test;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link VcfWriter}.
 * @author Douglas Myers-Turnbull
 */
public class VcfWriterTest {

  @Test
  public void testNoSamples() throws Exception {
    StringWriter sw = new StringWriter();
    VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build();
    // ##INFO=<ID=NS,Number=1,Type=Integer,Description="Number of samples with data">
    InfoMetadata info = new InfoMetadata("NS", "Number of samples with data", InfoType.Integer, "1", null, null);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addInfo(info).build();
    VcfPosition first = new VcfPosition("chr1", 1, "A", new BigDecimal("0"));
    first.getAltBases().add("T");
    first.getInfo().put("NS", "0");
    first.getIds().add("rsa");
    VcfPosition second = new VcfPosition("chr1", 2, "A", new BigDecimal("0"));
    second.getAltBases().add("T");
    second.getIds().add("rsb");
    writer.writeHeader(metadata);
    writer.writeLine(metadata, first, Collections.emptyList());
    writer.writeLine(metadata, second, Collections.emptyList());
    String expected = TestUtils.readFileToString(Paths.get(getClass().getResource("/no_samples.vcf").toURI()));
    assertEquals(expected, sw.toString());
  }

}
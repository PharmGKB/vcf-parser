package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.ReservedFormatProperty;
import org.pharmgkb.parser.vcf.model.VcfSample;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Douglas Myers-Turnbull
 */
public class MemoryMappedVcfDataStoreParserTest {

  @Test
  public void test() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream(
        "/vcfposition.vcf")))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();
      VcfSample sample = dataStore.getSampleForId("rsb", "sample1");
      assertNotNull(sample);
      assertEquals("0|1", sample.getProperty(ReservedFormatProperty.Genotype));
      MemoryMappedVcfDataStore.Genotype genotype = dataStore.getGenotypeForId("rsb", "sample1");
      assertNotNull(genotype);
      assertTrue(genotype.isPhased());
      assertEquals("A", genotype.getAlleles().get(0));
      assertEquals("T", genotype.getAlleles().get(1));
      assertNull(dataStore.getGenotypeForId("rsa", "sample1"));
    }
  }

  @Test
  public void testMissingAndUnknownLookups() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream(
        "/vcfposition.vcf")))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();

      // unknown id / locus lookups return null instead of throwing
      assertNull(dataStore.getSampleForId("nope", "sample1"));
      assertNull(dataStore.getGenotypeForId("nope", "sample1"));
      assertNull(dataStore.getSampleAtLocus("chrX", 999, "sample1"));
      assertNull(dataStore.getGenotypeAtLocus("chrX", 999, "sample1"));

      // an unknown sample name (for a known record) returns null instead of throwing IndexOutOfBoundsException
      assertNull(dataStore.getSampleForId("rsb", "nosuchsample"));
      assertNull(dataStore.getGenotypeForId("rsb", "nosuchsample"));
      assertNull(dataStore.getSampleAtLocus("chr1", 2, "nosuchsample"));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 2, "nosuchsample"));

      // a partial no-call ("0/.") keeps the missing allele as "." instead of failing to parse it
      MemoryMappedVcfDataStore.Genotype partial = dataStore.getGenotypeForId("rse", "sample1");
      assertNotNull(partial);
      assertEquals("A", partial.getAlleles().get(0));
      assertEquals(".", partial.getAlleles().get(1));

      // a fully missing call ("./.") has no genotype
      assertNull(dataStore.getGenotypeForId("rsf", "sample1"));
    }
  }

  @Test
  public void testMalformedGenotypeConvertsToVcfFormatException() throws IOException {
    // ALT has one allele (T), so valid GT indices are 0 (REF) and 1; index 5 is out of range
    String outOfRange = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t5/0\n";
    assertThrows(VcfFormatException.class, () -> getGenotype(outOfRange, "rsx"));

    // a non-numeric GT allele index (not ".") must also convert to VcfFormatException, not NumberFormatException
    String notANumber = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\tX/0\n";
    assertThrows(VcfFormatException.class, () -> getGenotype(notANumber, "rsx"));
  }

  private static void getGenotype(String vcf, String id) throws IOException {
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      lineParser.getDataStore().getGenotypeForId(id, "sample1");
    }
  }

  @Test
  public void testGenotypeAllelesAreImmutable() {
    List<String> alleles = new ArrayList<>(Arrays.asList("A", "T"));
    MemoryMappedVcfDataStore.Genotype genotype = new MemoryMappedVcfDataStore.Genotype(alleles, true);

    // mutating the list passed to the constructor must not affect the Genotype (defensive copy)
    alleles.add("G");
    assertEquals(Arrays.asList("A", "T"), genotype.getAlleles());

    // the returned list must itself be immutable
    assertThrows(UnsupportedOperationException.class, () -> genotype.getAlleles().add("G"));
  }
}

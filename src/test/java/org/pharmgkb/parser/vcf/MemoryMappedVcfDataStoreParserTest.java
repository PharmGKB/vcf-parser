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
import org.pharmgkb.parser.vcf.model.VcfPosition;
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
      assertNull(dataStore.getSampleAtLocus("chrX", 999, 0, "sample1"));
      assertNull(dataStore.getGenotypeAtLocus("chrX", 999, 0, "sample1"));

      // an unknown sample name (for a known record) returns null instead of throwing IndexOutOfBoundsException
      assertNull(dataStore.getSampleForId("rsb", "nosuchsample"));
      assertNull(dataStore.getGenotypeForId("rsb", "nosuchsample"));
      assertNull(dataStore.getSampleAtLocus("chr1", 2, 0, "nosuchsample"));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 2, 0, "nosuchsample"));

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
  public void testGenotypeTrailingSeparatorTreatedAsMissingAllele() throws IOException {
    // a trailing '/' (e.g. "0/") is a zero-length allele where '.' should have been used; this must not silently
    // change the call's ploidy (previously: genotype.split("[|/]") dropped the trailing empty, turning a malformed
    // diploid "0/" into a haploid "0")
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t0/\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore.Genotype genotype = lineParser.getDataStore().getGenotypeForId("rsx", "sample1");
      assertNotNull(genotype);
      assertEquals(2, genotype.getAlleles().size());
      assertEquals("A", genotype.getAlleles().get(0));
      assertEquals(".", genotype.getAlleles().get(1));
    }
  }

  @Test
  public void testDuplicateLocusDefaultsToFail() throws IOException {
    // VCF permits multiple records at the same locus (e.g. multi-allelic sites split across lines), but this
    // parser's default is still to reject them unless KEEP_ALL is requested
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t0/1\n" +
        "chr1\t1\trsy\tA\tC\t.\tPASS\t.\tGT\t1/1\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      VcfParser parser = new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build();
      assertThrows(VcfFormatException.class, parser::parse);
    }
  }

  @Test
  public void testDuplicateLocusKeepAllRetainsAllRecords() throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t0/1\n" +
        "chr1\t1\trsy\tA\tC\t.\tPASS\t.\tGT\t1/1\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder()
          .setDuplicateLocusHandler(MemoryMappedVcfLineParser.LocusDuplicateHandler.KEEP_ALL)
          .build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();

      List<VcfPosition> positions = dataStore.getPositionsAtLocus("chr1", 1);
      assertNotNull(positions);
      assertEquals(2, positions.size());
      assertEquals("rsx", positions.get(0).getIds().get(0));
      assertEquals("rsy", positions.get(1).getIds().get(0));

      List<List<VcfSample>> samplesList = dataStore.getSamplesAtLocus("chr1", 1);
      assertNotNull(samplesList);
      assertEquals(2, samplesList.size());
      assertEquals("0/1", samplesList.get(0).get(0).getProperty(ReservedFormatProperty.Genotype));
      assertEquals("1/1", samplesList.get(1).get(0).getProperty(ReservedFormatProperty.Genotype));

      // recordIndex-aware accessors reach the correct record, not just the first one at the locus
      VcfSample secondSample = dataStore.getSampleAtLocus("chr1", 1, 1, "sample1");
      assertNotNull(secondSample);
      assertEquals("1/1", secondSample.getProperty(ReservedFormatProperty.Genotype));

      MemoryMappedVcfDataStore.Genotype genotype = dataStore.getGenotypeAtLocus("chr1", 1, 1, "sample1");
      assertNotNull(genotype);
      assertEquals("C", genotype.getAlleles().get(0));
      assertEquals("C", genotype.getAlleles().get(1));
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

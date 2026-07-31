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
  public void testSetDuplicateIdHandlerRejectsNullInsteadOfSilentlyAccepting() {
    // regression test: handler is a caller-configuration object, never VCF content -- a raw
    // NullPointerException is this codebase's established convention for this category. Before this fix, a
    // null handler silently fell through every == comparison against DuplicateHandler.FAIL/KEEP_LAST in
    // MemoryMappedVcfLineParser.parseLine(), substituting the remaining behavior with no warning that the
    // caller's intended handler was never applied.
    assertThrows(NullPointerException.class, () -> new MemoryMappedVcfLineParser.Builder().setDuplicateIdHandler(null));
  }

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
  public void testGetPositionForId() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream(
        "/vcfposition.vcf")))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();

      VcfPosition position = dataStore.getPositionForId("rsb");
      assertNotNull(position);
      assertEquals("chr1", position.getChromosome());
      assertEquals(2, position.getPosition());

      assertNull(dataStore.getPositionForId("nope"));
    }
  }

  @Test
  public void testGetSamplesForId() throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(VcfParserTest.class.getResourceAsStream(
        "/vcfposition.vcf")))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();

      List<VcfSample> samples = dataStore.getSamplesForId("rsb");
      assertNotNull(samples);
      assertEquals(1, samples.size());
      assertEquals("0|1", samples.get(0).getProperty(ReservedFormatProperty.Genotype));

      assertNull(dataStore.getSamplesForId("nope"));
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
  public void testDuplicateLocusRetainsAllRecords() throws IOException {
    // VCF permits multiple records at the same locus (e.g. multi-allelic sites split across lines); every record
    // seen at a locus is kept, with no handler/configuration needed -- it's up to the consumer to decide what, if
    // anything, to do about duplicates
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t0/1\n" +
        "chr1\t1\trsy\tA\tC\t.\tPASS\t.\tGT\t1/1\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
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

  @Test
  public void testGenotypePhasedBoundariesAreImmutable() {
    List<Boolean> phasedBoundaries = new ArrayList<>(Arrays.asList(true, false));
    MemoryMappedVcfDataStore.Genotype genotype =
        new MemoryMappedVcfDataStore.Genotype(Arrays.asList("A", "T", "G"), phasedBoundaries);

    // mutating the list passed to the constructor must not affect the Genotype (defensive copy)
    phasedBoundaries.add(true);
    assertEquals(Arrays.asList(true, false), genotype.getPhasedBoundaries());

    // the returned list must itself be immutable
    assertThrows(UnsupportedOperationException.class, () -> genotype.getPhasedBoundaries().add(true));
  }

  @Test
  public void testGenotypeToStringPreservesMixedPhasing() {
    // "0|1/2" -- boundary 0-1 is phased, boundary 1-2 is unphased; a single whole-genotype boolean cannot
    // represent this, so toString() must reconstruct using the actual per-boundary separator, not one uniform one
    MemoryMappedVcfDataStore.Genotype genotype =
        new MemoryMappedVcfDataStore.Genotype(List.of("0", "1", "2"), List.of(true, false));
    assertEquals("0|1/2", genotype.toString());
    assertEquals(List.of(true, false), genotype.getPhasedBoundaries());
    // isPhased() reports "fully phased" -- false here, since the second boundary is unphased
    assertFalse(genotype.isPhased());
  }

  @Test
  public void testGenotypeToStringHandlesEmptyAllelesList() {
    // toString() must not throw for an empty alleles list -- the old Joiner-based implementation returned "" here;
    // a naive rewrite that special-cases the first element (m_alleles.get(0)) would throw IndexOutOfBoundsException
    MemoryMappedVcfDataStore.Genotype genotype = new MemoryMappedVcfDataStore.Genotype(List.of(), List.of());
    assertEquals("", genotype.toString());
  }

  @Test
  public void testGenotypePhasedBoundariesArityValidation() {
    assertThrows(IllegalArgumentException.class, () ->
        new MemoryMappedVcfDataStore.Genotype(List.of("0", "1", "2"), List.of(true)));
  }

  @Test
  public void testGenotypeConstructorsRejectNullInsteadOfThrowingNpe() {
    // alleles/phasedBoundaries are typed non-@Nullable, but nothing enforces that at compile time; both
    // constructors must reject a null with VcfFormatException, not raw-NPE at alleles.size()/phasedBoundaries.size()
    assertThrows(VcfFormatException.class, () -> new MemoryMappedVcfDataStore.Genotype(null, true));
    assertThrows(VcfFormatException.class, () -> new MemoryMappedVcfDataStore.Genotype(null, List.of(true)));
    assertThrows(VcfFormatException.class,
        () -> new MemoryMappedVcfDataStore.Genotype(List.of("A", "T"), (List<Boolean>) null));
  }

  @Test
  public void testGenotypeLegacyConstructorSingleAlleleIsNeverPhased() {
    // a single-allele genotype has zero boundaries, so isPhased() is now always false regardless of the flag
    // passed to the legacy constructor -- matches what doGetGenotype's real parsing already produced for a
    // haploid GT (no separator to be "|"), so this only affects direct construction, not parsing
    MemoryMappedVcfDataStore.Genotype genotype = new MemoryMappedVcfDataStore.Genotype(List.of("1"), true);
    assertFalse(genotype.isPhased());
    assertTrue(genotype.getPhasedBoundaries().isEmpty());
  }

  @Test
  public void testMixedPhasingGenotypeRoundTripsThroughParser() throws IOException {
    // "0|1/2" (3 alleles, REF=A ALT=T,C) -- previously isPhased=true (since the whole string "contains |") caused
    // toString() to reconstruct "A|T|C" instead of the actual "A|T/C"; the fix tracks each boundary's separator
    // independently instead of collapsing the whole call into one boolean
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT,C\t.\tPASS\t.\tGT\t0|1/2\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore.Genotype genotype = lineParser.getDataStore().getGenotypeForId("rsx", "sample1");
      assertNotNull(genotype);
      assertEquals(List.of("A", "T", "C"), genotype.getAlleles());
      assertEquals(List.of(true, false), genotype.getPhasedBoundaries());
      assertEquals("A|T/C", genotype.toString());
    }
  }

  @Test
  public void testGenotypeWithMoreThanTwoAllelesParsesCorrectly() throws IOException {
    // regression test for the doGetGenotype scan refactor (substring-based, pre-sized lists) -- a genotype with
    // more than the common diploid 2 alleles exercises the separator-counting pre-sizing path with a non-trivial
    // count
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT,C,G\t.\tPASS\t.\tGT\t0|1|2|3\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore.Genotype genotype = lineParser.getDataStore().getGenotypeForId("rsx", "sample1");
      assertNotNull(genotype);
      assertEquals(List.of("A", "T", "C", "G"), genotype.getAlleles());
      assertEquals(List.of(true, true, true), genotype.getPhasedBoundaries());
    }
  }

  @Test
  public void testHeaderOnlyVcfPopulatesMetadata() throws IOException {
    // a VCF with metadata but zero data lines must still populate the data store's metadata -- parseMetadata()
    // must be called, not just parseLine() as a side effect
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();
      assertNotNull(dataStore.getMetadata());
      assertEquals("VCFv4.2", dataStore.getMetadata().getFileFormat());
    }
  }

  @Test
  public void testHeaderOnlyVcfReturnsEmptyNotNullPositionsAndSamples() throws IOException {
    // parsing completed successfully with zero data lines -- getAllPositions()/getAllSamples() must return an
    // empty collection (parsing happened), not null (null now means "parsing never happened at all")
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();
      assertNotNull(dataStore.getAllPositions());
      assertTrue(dataStore.getAllPositions().isEmpty());
      assertNotNull(dataStore.getAllSamples());
      assertTrue(dataStore.getAllSamples().isEmpty());
    }
  }

  @Test
  public void testDuplicateIdRejectionIsAtomicForWholeRecord() throws IOException {
    // a record with two IDs where the second is a pre-existing duplicate must be rejected as a whole: the first
    // (non-duplicate) ID must not end up linked, and the record must not appear in locus-based lookup either
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "chr1\t1\trs1\tA\tT\t.\t.\t.\n" +
        "chr1\t2\trs2;rs1\tA\tC\t.\t.\t.\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      // DuplicateHandler.FAIL is the default
      assertThrows(VcfFormatException.class, () ->
          new VcfParser.Builder().fromReader(reader).parseWith(lineParser).build().parse());

      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();

      // rs2 must not be linked -- it was rejected together with (the already-registered) rs1 in the same record,
      // atomically; getIdToPosition() is package-protected, accessible here since this test is in the same package
      assertFalse(dataStore.getIdToPosition().containsKey("rs2"));

      // the rejected record's own locus (chr1:2) must never have been populated either
      assertNull(dataStore.getPositionsAtLocus("chr1", 2));
    }
  }

  @Test
  public void testAtLocusAndIndexOverloadsReturnNullForOutOfRangeIndicesInsteadOfThrowing() throws IOException {
    // the String-id overloads already return null for an unresolved sample name; the int overloads previously let
    // List.get throw IndexOutOfBoundsException for an out-of-range index instead -- this brings them in line
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t0/1\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();

      assertNull(dataStore.getSampleForId("rsx", 99));
      assertNull(dataStore.getGenotypeForId("rsx", 99));
      assertNull(dataStore.getSampleAtLocus("chr1", 1, 0, 99));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, 0, 99));

      // a negative index is equally out-of-range, for every int-overload -- not just getSampleForId
      assertNull(dataStore.getSampleForId("rsx", -1));
      assertNull(dataStore.getGenotypeForId("rsx", -1));
      assertNull(dataStore.getSampleAtLocus("chr1", 1, 0, -1));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, 0, -1));

      // an out-of-range recordIndex is equally out-of-range, for every *AtLocus overload -- not just
      // sampleIndex/sampleId; this fixture has exactly 1 record at chr1:1, so recordIndex 1+ is out of range
      assertNull(dataStore.getSampleAtLocus("chr1", 1, 99, "sample1"));
      assertNull(dataStore.getSampleAtLocus("chr1", 1, 99, 0));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, 99, "sample1"));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, 99, 0));

      // a negative recordIndex is equally out-of-range
      assertNull(dataStore.getSampleAtLocus("chr1", 1, -1, "sample1"));
      assertNull(dataStore.getSampleAtLocus("chr1", 1, -1, 0));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, -1, "sample1"));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, -1, 0));

      // the exact boundary (recordIndex == the record count, i.e. one past the last valid index) must also be
      // out-of-range -- pins ">=" rather than ">", which would otherwise let this one value slip through
      assertNull(dataStore.getSampleAtLocus("chr1", 1, 1, "sample1"));
      assertNull(dataStore.getSampleAtLocus("chr1", 1, 1, 0));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, 1, "sample1"));
      assertNull(dataStore.getGenotypeAtLocus("chr1", 1, 1, 0));

      // sanity: the valid index still works for every int-overload
      assertNotNull(dataStore.getSampleForId("rsx", 0));
      assertNotNull(dataStore.getGenotypeForId("rsx", 0));
      assertNotNull(dataStore.getSampleAtLocus("chr1", 1, 0, 0));
      assertNotNull(dataStore.getGenotypeAtLocus("chr1", 1, 0, 0));
    }
  }
}

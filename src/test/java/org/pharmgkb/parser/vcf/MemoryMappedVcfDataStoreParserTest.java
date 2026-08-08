package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

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
  public void testSetMetadataRejectsNullInsteadOfSilentlyAccepting() {
    // regression test, called directly (rather than only transitively through parseLine()/parseMetadata()):
    // before this fix, a null metadata was accepted silently and stored, so a later by-name lookup
    // (getSampleForId, and the String-sampleId overloads of getSampleAtLocus/getGenotypeForId/
    // getGenotypeAtLocus) raw-crashed with NullPointerException at that unrelated call site instead of at the
    // actual mistake here. setMetadata() is package-protected, accessible here since this test is in the
    // same package.
    assertThrows(VcfFormatException.class, () -> new MemoryMappedVcfDataStore().setMetadata(null));
  }

  @Test
  public void testParseLineRejectsNullMetadataInsteadOfCorruptingLaterLookups() {
    MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    assertThrows(VcfFormatException.class,
        () -> lineParser.parseLine(null, position, Collections.emptyList()));
  }

  @Test
  public void testParseMetadataRejectsNullInsteadOfSilentlyAccepting() {
    MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
    assertThrows(VcfFormatException.class, () -> lineParser.parseMetadata(null));
  }

  @Test
  public void testParseLineRejectsNullPositionInsteadOfNullPointerException() {
    // regression test: before this fix, a null position raw-crashed with NullPointerException at
    // position.getIds() in the FAIL-mode duplicate-ID check loop, instead of being rejected here
    MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    assertThrows(VcfFormatException.class,
        () -> lineParser.parseLine(metadata, null, Collections.emptyList()));
  }

  @Test
  public void testParseLineRejectsNullSampleDataInsteadOfStoringNullElement() {
    // regression test: before this fix, a null sampleData list was accepted and stored as a literal null
    // element inside getSamplesAtLocus()'s per-locus List<List<VcfSample>>, crashing later at
    // getSampleAtLocus/getGenotypeAtLocus with NullPointerException instead of here
    MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    assertThrows(VcfFormatException.class, () -> lineParser.parseLine(metadata, position, null));
  }

  @Test
  public void testParseLineRejectsNullElementInSampleDataInsteadOfStoringItAsIs() {
    // regression test: before this fix, a sampleData list containing a null element (as opposed to the list
    // itself being null, the sibling case above) was accepted and stored as-is; getGenotypeAtLocus/
    // getGenotypeForId then raw-crashed with NullPointerException at the genotype-parsing logic's sample.getProperty(...)
    // call, while getSampleAtLocus/getSampleForId silently returned the null, indistinguishable from
    // "not present". The null is placed at index 1 (behind one valid sample), and the exception message is
    // asserted, so this pins that the check scans every element -- not just index 0 -- and reports the
    // correct index, rather than merely that a null anywhere in the list is rejected.
    MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    VcfSample validSample = new VcfSample(List.of("GT"), List.of("0/1"));
    VcfFormatException e = assertThrows(VcfFormatException.class,
        () -> lineParser.parseLine(metadata, position, Arrays.asList(validSample, null)));
    assertTrue(e.getMessage().contains("index 1"));
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
      VcfSample.Genotype genotype = dataStore.getGenotypeForId("rsb", "sample1");
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
      VcfSample.Genotype partial = dataStore.getGenotypeForId("rse", "sample1");
      assertNotNull(partial);
      assertEquals("A", partial.getAlleles().get(0));
      assertEquals(".", partial.getAlleles().get(1));

      // a fully missing call ("./.") has no genotype
      assertNull(dataStore.getGenotypeForId("rsf", "sample1"));
    }
  }

  @Test
  public void testByNameLookupsReturnNullInsteadOfCrashingWhenARecordHasFewerSamplesThanMetadataDeclares() {
    // regression test: MemoryMappedVcfLineParser.parseLine() can be called directly (its own comment
    // anticipates this, "not through VcfParser"), so a record's sample list can legitimately be shorter than
    // VcfMetadata.getNumSamples() -- e.g. a caller re-using one MemoryMappedVcfDataStore across files with
    // different sample columns. Before this fix, every by-sample-NAME lookup indexed straight into that
    // record's own (possibly-short) list with no bounds check, unlike its by-index siblings, and crashed with
    // IndexOutOfBoundsException instead of returning null.
    VcfMetadata metadata = new VcfMetadata.Builder()
        .setFileFormat("VCFv4.2")
        .setColumns(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "S1", "S2"))
        .build();
    VcfPosition position = new VcfPosition("1", 100, List.of("rs1"), "A", List.of("T"), null, null, null, null, -1);
    VcfSample onlySample = new VcfSample(List.of("GT"), List.of("0/1"));

    MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
    lineParser.parseLine(metadata, position, List.of(onlySample)); // only 1 sample, though metadata declares 2

    MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();
    // "S1" (index 0) is present and resolves normally through all four fixed methods -- confirms the fix
    // didn't just make every by-name lookup unconditionally return null
    assertNotNull(dataStore.getSampleForId("rs1", "S1"));
    assertNotNull(dataStore.getGenotypeForId("rs1", "S1"));
    assertNotNull(dataStore.getSampleAtLocus("1", 100, 0, "S1"));
    assertNotNull(dataStore.getGenotypeAtLocus("1", 100, 0, "S1"));
    // "S2" (index 1) is a real, declared sample column -- getSampleIndex("S2") returns 1, not -1 -- but this
    // record's own sample list only has 1 element. Must return null, not throw.
    assertNull(dataStore.getSampleForId("rs1", "S2"));
    assertNull(dataStore.getGenotypeForId("rs1", "S2"));
    assertNull(dataStore.getSampleAtLocus("1", 100, 0, "S2"));
    assertNull(dataStore.getGenotypeAtLocus("1", 100, 0, "S2"));
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
      VcfSample.Genotype genotype = lineParser.getDataStore().getGenotypeForId("rsx", "sample1");
      assertNotNull(genotype);
      assertEquals(2, genotype.getAlleles().size());
      assertEquals("A", genotype.getAlleles().get(0));
      assertEquals(".", genotype.getAlleles().get(1));
    }
  }

  @Test
  public void testGenotypeWithEmptyAlleleWarnsToConfiguredSink() throws IOException {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t0/\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();
      VcfSample sample = dataStore.getSampleForId("rsx", "sample1");
      assertNotNull(sample);
      StringWriter sink = new StringWriter();
      sample.setWarningsSink(new VcfWarnings(sink));
      dataStore.getGenotypeForId("rsx", "sample1");
      assertTrue(sink.toString().contains("empty allele"));
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

      VcfSample.Genotype genotype = dataStore.getGenotypeAtLocus("chr1", 1, 1, "sample1");
      assertNotNull(genotype);
      assertEquals("C", genotype.getAlleles().get(0));
      assertEquals("C", genotype.getAlleles().get(1));
    }
  }

  @Test
  public void testPositionsAndSamplesAtLocusRejectCallerMutationInsteadOfSilentlyDesyncingIndexAlignment() throws IOException {
    // regression test: getPositionsAtLocus()/getSamplesAtLocus() previously returned the live, directly-mutable
    // backing lists. getGenotypeAtLocus()'s recordIndex indexes into both in lockstep (its own comment relies on
    // positions.size() == samplesList.size() always holding for a locus) -- an ordinary List.remove() on either
    // list, via the public getter, silently broke that alignment: removing index 0 from one list while the other
    // stayed full-size made a later valid-looking recordIndex either raw-crash with an unrelated
    // IndexOutOfBoundsException, or (worse) silently pair a sample with the WRONG position, no exception at all.
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "chr1\t1\trsx\tA\tT\t.\tPASS\t.\tGT\t0/1\n" +
        "chr1\t1\trsy\tC\tG\t.\tPASS\t.\tGT\t1/1\n";
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf))) {
      MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build();
      new VcfParser.Builder()
          .fromReader(reader)
          .parseWith(lineParser)
          .build().parse();
      MemoryMappedVcfDataStore dataStore = lineParser.getDataStore();

      List<VcfPosition> positions = dataStore.getPositionsAtLocus("chr1", 1);
      assertNotNull(positions);
      assertThrows(UnsupportedOperationException.class, () -> positions.remove(0));

      List<List<VcfSample>> samplesList = dataStore.getSamplesAtLocus("chr1", 1);
      assertNotNull(samplesList);
      assertThrows(UnsupportedOperationException.class, () -> samplesList.remove(0));

      // the lists remain usable for reading, and index alignment is intact (would have desynced under the old
      // live-mutable behavior if the mutation above had silently succeeded)
      assertEquals(2, positions.size());
      assertEquals(2, samplesList.size());
      VcfSample.Genotype genotype = dataStore.getGenotypeAtLocus("chr1", 1, 1, "sample1");
      assertNotNull(genotype);
      assertEquals("G", genotype.getAlleles().get(0));
      assertEquals("G", genotype.getAlleles().get(1));
    }
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
      VcfSample.Genotype genotype = lineParser.getDataStore().getGenotypeForId("rsx", "sample1");
      assertNotNull(genotype);
      assertEquals(List.of("A", "T", "C"), genotype.getAlleles());
      assertEquals(List.of(true, false), genotype.getPhasedBoundaries());
      assertEquals("A|T/C", genotype.toString());
    }
  }

  @Test
  public void testGenotypeWithMoreThanTwoAllelesParsesCorrectly() throws IOException {
    // regression test for the VcfSample.getGenotype scan refactor (substring-based, pre-sized lists) -- a genotype with
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
      VcfSample.Genotype genotype = lineParser.getDataStore().getGenotypeForId("rsx", "sample1");
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
  public void testDuplicateIdWithinSameRecordIsRejectedUnderFail() {
    // a single record whose own ID list repeats an ID is a duplicate DuplicateHandler.FAIL must catch too -- the
    // pre-check loop only compared against previously-committed records, so neither occurrence of a same-record
    // duplicate was in the data store yet and the collision went undetected; the later linking loop then
    // silently kept only the first occurrence (KEEP_FIRST-equivalent behavior) with no exception at all. Built
    // by calling parseLine() directly (not through VcfParser/a raw VCF string): parsing "ID=rs1;rs1" through
    // VcfParser would have VcfPosition's own constructor-time checkIds() reject the duplicate first, before
    // this class's own gap could ever be exercised -- getIds() is a live list that bypasses that check.
    MemoryMappedVcfLineParser lineParser = new MemoryMappedVcfLineParser.Builder().build(); // FAIL is the default
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    lineParser.parseMetadata(metadata);
    VcfPosition position = new VcfPosition("chr1", 1, "A", new BigDecimal("0"), -1);
    position.getIds().add("rs1");
    position.getIds().add("rs1");

    assertThrows(VcfFormatException.class, () -> lineParser.parseLine(metadata, position, Collections.emptyList()));
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

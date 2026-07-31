package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import com.google.common.io.CharStreams;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.model.IdDescriptionMetadata;
import org.pharmgkb.parser.vcf.model.InfoMetadata;
import org.pharmgkb.parser.vcf.model.InfoType;
import org.pharmgkb.parser.vcf.model.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;
import org.pharmgkb.parser.vcf.spec.FailingWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TransformingVcfLineParserTest {

  @Test
  public void simpleIntegrationTest() throws Exception {

    VcfTransformation transformation = new VcfTransformation() {
      @Override
      public void transformMetadata(VcfMetadata metadata) {
        // this is the only place where we can alter the metadata
        metadata.getInfo().put("TestInfo", new InfoMetadata("an_id", "a_description", InfoType.String, "G", null, null));
        metadata.getFilters().put("Transformation", new IdDescriptionMetadata("FILTER", "Transformation", "A transformation was applied"));
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
        transformation(transformation, new PrintWriter(sw)).build();
    Path dataFile = Paths.get(VcfParserTest.class.getResource("/to_transform.vcf").toURI());
    try (VcfParser parser = new VcfParser.Builder().parseWith(lineParser).fromFile(dataFile).build()) {
      parser.parse();
    }
    String actualResult = sw.toString();

    // avoid a whole-file content comparison so we can see the difference if the test fails
    Path expected = Paths.get(VcfParserTest.class.getResource("/transformed.vcf").toURI());
    String expectedResult;
    try (BufferedReader br = new BufferedReader(new FileReader(expected.toFile()))) {
      expectedResult = CharStreams.toString(br);
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
        .transformation(new VcfTransformation() {}, new PrintWriter(sw)).build();
    Path dataFile = Paths.get(VcfParserTest.class.getResource("/transformed.vcf").toURI());
    try (VcfParser parser = new VcfParser.Builder().parseWith(lineParser).fromFile(dataFile).build()) {
      parser.parse();
    }

    String input;
    try (BufferedReader br = new BufferedReader(new FileReader(dataFile.toFile()))) {
      input = CharStreams.toString(br);
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
        .transformation(new VcfTransformation() {}, new PrintWriter(sw)).build();
    try (VcfParser parser = new VcfParser.Builder()
        .parseWith(lineParser)
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .build()) {
      parser.parse();
    }
    assertEquals(vcf, sw.toString());
  }

  /**
   * Calling parseMetadata() directly (without parsing any data lines) must still deliver the metadata callback and write
   * the header.
   */
  @Test
  public void testParseMetadataOnlyWritesHeader() throws Exception {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    StringWriter sw = new StringWriter();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(new VcfTransformation() {}, new PrintWriter(sw)).build();
    try (VcfParser parser = new VcfParser.Builder()
        .parseWith(lineParser)
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .build()) {
      parser.parseMetadata();
    }
    assertEquals(vcf, sw.toString());
  }

  /**
   * A second {@code transformation(...)} call on the same builder must fail fast rather than silently discarding
   * the first writer -- the {@code Path}/{@code PrintWriter} overloads open their {@link VcfWriter} eagerly, so a
   * silent overwrite would leak an open, never-closed file handle.
   */
  @Test
  public void testTransformationCannotBeSetTwice() throws Exception {
    StringWriter sw1 = new StringWriter();
    StringWriter sw2 = new StringWriter();
    TransformingVcfLineParser.Builder builder = new TransformingVcfLineParser.Builder()
        .transformation(new VcfTransformation() {}, new PrintWriter(sw1));
    assertThrows(IllegalStateException.class,
        () -> builder.transformation(new VcfTransformation() {}, new PrintWriter(sw2)));
  }

  @Test
  public void testTransformationWithNullWriterRejectsFastInsteadOfThrowingNpeLater() {
    // regression test: this 2-arg overload previously had no null check on writer, so build() succeeded and
    // the failure only surfaced as a bare NullPointerException deep inside writeHeader()/close() on first use
    TransformingVcfLineParser.Builder builder = new TransformingVcfLineParser.Builder();
    assertThrows(NullPointerException.class,
        () -> builder.transformation(new VcfTransformation() {}, (VcfWriter) null));
  }

  @Test
  public void testTransformationWithNullTransformationRejectsFastInsteadOfDefeatingAlreadySetGuard() {
    // regression test: this 2-arg overload previously had no null check on transformation, so a null-
    // transformation call left m_transformation indistinguishable from "never called," silently defeating
    // checkNotAlreadySet()'s guard for every later call -- including the Path/PrintWriter overloads, which
    // open a real VcfWriter/file handle before delegating here, leaking it on a silent overwrite
    TransformingVcfLineParser.Builder builder = new TransformingVcfLineParser.Builder();
    assertThrows(NullPointerException.class,
        () -> builder.transformation((VcfTransformation) null, new PrintWriter(new StringWriter())));
  }

  @Test
  public void testTransformationWithPathOverload(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
    // the Path overload had zero test coverage anywhere in the repo before this batch, despite being the
    // overload most exposed to the file-handle-leak consequence the null-transformation guard exists to
    // prevent
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    Path outputFile = tempDir.resolve("out.vcf");
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(new VcfTransformation() {}, outputFile).build();
    try (VcfParser parser = new VcfParser.Builder()
        .parseWith(lineParser)
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .build()) {
      parser.parse();
    }
    lineParser.close();
    assertEquals(vcf, java.nio.file.Files.readString(outputFile));
  }

  @Test
  public void testTransformMetadataFailureDoesNotInvalidateAndIsRetried() throws Exception {
    // a transformation-logic failure (not a write failure) must not permanently invalidate the parser -- nothing
    // was written yet, so a later call retries the whole header attempt from scratch
    int[] callCount = {0};
    VcfTransformation flakyThenOk = new VcfTransformation() {
      @Override
      public void transformMetadata(VcfMetadata metadata) {
        callCount[0]++;
        if (callCount[0] == 1) {
          throw new RuntimeException("simulated transformation failure");
        }
      }
    };
    StringWriter sw = new StringWriter();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(flakyThenOk, new PrintWriter(sw)).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    assertThrows(RuntimeException.class, () -> lineParser.parseMetadata(metadata));
    assertTrue(lineParser.isValid());

    lineParser.parseMetadata(metadata); // retried: succeeds this time
    assertEquals(2, callCount[0]);
    assertTrue(lineParser.isValid());
  }

  @Test
  public void testWriteFailureInvalidatesParserAndBlocksFurtherUse() throws Exception {
    FailingWriter failingWriter = new FailingWriter();
    failingWriter.startFailing();
    VcfWriter vcfWriter = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(new VcfTransformation() {}, vcfWriter).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    assertThrows(UncheckedIOException.class, () -> lineParser.parseMetadata(metadata));
    assertFalse(lineParser.isValid());
    assertThrows(IllegalStateException.class, () -> lineParser.parseMetadata(metadata));
  }

  @Test
  public void testTransformDataLineFailureDoesNotInvalidate() throws Exception {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t1\t.\tA\tT\t.\t.\t.\n";
    StringWriter sw = new StringWriter();
    VcfTransformation throwsOnDataLine = new VcfTransformation() {
      @Override
      public boolean transformDataLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {
        throw new RuntimeException("simulated per-record failure");
      }
    };
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(throwsOnDataLine, new PrintWriter(sw)).build();
    try (VcfParser parser = new VcfParser.Builder()
        .parseWith(lineParser)
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
    assertTrue(lineParser.isValid());
  }

  @Test
  public void testWriteLineContentRejectionDoesNotInvalidateParser() throws Exception {
    // VcfWriter.writeLine() throws VcfFormatException (not UncheckedIOException) for a per-record content
    // rejection like a sample-count mismatch -- this must NOT invalidate the parser, unlike a genuine write
    // (I/O) failure. A subsequent valid record must still be processed normally.
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsample1\n" +
        "1\t1\t.\tA\tT\t.\t.\t.\tGT\t0/1\n" +
        "1\t2\t.\tA\tT\t.\t.\t.\tGT\t0/1\n";
    StringWriter sw = new StringWriter();
    VcfTransformation clearsSamplesOnFirstRecordOnly = new VcfTransformation() {
      @Override
      public boolean transformDataLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {
        if (position.getPosition() == 1) {
          sampleData.clear(); // causes a sample-count mismatch in VcfWriter.writeLine() for this record only
        }
        return true;
      }
    };
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(clearsSamplesOnFirstRecordOnly, new PrintWriter(sw)).build();
    try (VcfParser parser = new VcfParser.Builder()
        .parseWith(lineParser)
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .build()) {
      assertThrows(VcfFormatException.class, parser::parse);
    }
    assertTrue(lineParser.isValid());
  }

  @Test
  public void testHeaderContentRejectionDoesNotPermanentlyBlockRetry() throws Exception {
    // regression test for the bug this batch fixed: a validateBeforeWrite() content rejection during
    // m_writer.writeHeader() must not leave m_headerWritten stuck true -- a corrected retry must succeed
    int[] callCount = {0};
    VcfTransformation transformation = new VcfTransformation() {
      @Override
      public void transformMetadata(VcfMetadata metadata) {
        callCount[0]++;
        if (callCount[0] == 1) {
          // makes VcfWriter.writeHeader() throw VcfFormatException via validateBeforeWrite()'s content checks:
          // an INFO entry whose own ID no longer matches the map key it's stored under
          metadata.addInfo(new InfoMetadata("AF", "d", InfoType.String, "1", null, null));
          metadata.getInfo().get("AF").putPropertyRaw("ID", "MISMATCHED");
        } else {
          // On retry, fix the mismatch: remove and re-add with correct ID
          metadata.getInfo().remove("AF");
          metadata.addInfo(new InfoMetadata("AF", "d", InfoType.String, "1", null, null));
        }
      }
    };
    StringWriter sw = new StringWriter();
    VcfWriter vcfWriter = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).validateBeforeWrite().build();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(transformation, vcfWriter).build();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();

    assertThrows(VcfFormatException.class, () -> lineParser.parseMetadata(metadata));
    assertTrue(lineParser.isValid());

    lineParser.parseMetadata(metadata); // retried: transformMetadata() runs again (callCount now 2), no mismatch this time, succeeds
    assertEquals(2, callCount[0]);
    assertTrue(lineParser.isValid());
    assertTrue(sw.toString().contains("##INFO=<ID=AF"));
  }

  /**
   * Regression test for the FORMAT-list-aliasing fix: mutating {@code position.getFormat()} in a transformation
   * without also updating the sample's own properties must not corrupt or misalign the written sample column --
   * {@link VcfWriter}'s default-mode reconciliation (by FORMAT key name) must warn and drop the now-undeclared
   * sample property, keeping the FORMAT and sample columns in sync.
   */
  @Test
  public void testRemovingFormatKeyWithoutUpdatingSampleDropsItInWrittenOutput() throws Exception {
    String vcf = "##fileformat=VCFv4.2\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSAMPLE1\n" +
        "1\t1\t.\tA\tT\t.\t.\t.\tGT:DP:FT\t0/1:5:PASS\n";
    StringWriter sw = new StringWriter();
    VcfTransformation transformation = new VcfTransformation() {
      @Override
      public boolean transformDataLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData) {
        // mutate VcfPosition's own FORMAT list without touching the sample's properties -- this used to corrupt
        // the written sample column (values shifted to the wrong key, or silently dropped) before the fix that
        // gives each VcfSample its own independent copy of the FORMAT list
        position.getFormat().remove("DP");
        return true;
      }
    };
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(transformation, new PrintWriter(sw)).build();
    try (VcfParser parser = new VcfParser.Builder()
        .parseWith(lineParser)
        .fromReader(new BufferedReader(new StringReader(vcf)))
        .build()) {
      parser.parse();
    }
    String[] lines = sw.toString().split("\n");
    String[] fields = lines[lines.length - 1].split("\t");
    // FORMAT no longer declares DP; the sample's now-undeclared DP property is dropped, not misaligned
    assertEquals("GT:FT", fields[8]);
    assertEquals("0/1:PASS", fields[9]);
  }

  @Test
  public void testIsValidReflectsWriterPoisonedByADirectExternalCall() throws Exception {
    // the whole point of delegating isValid() to m_writer.isValid() instead of tracking separately: a caller
    // holding their own reference to the same VcfWriter can poison it via a direct call this
    // TransformingVcfLineParser never sees, and isValid() must still correctly reflect that
    FailingWriter failingWriter = new FailingWriter();
    VcfWriter vcfWriter = new VcfWriter.Builder().toWriter(new PrintWriter(failingWriter)).build();
    TransformingVcfLineParser lineParser = new TransformingVcfLineParser.Builder()
        .transformation(new VcfTransformation() {}, vcfWriter).build();

    assertTrue(lineParser.isValid());

    // the caller uses their own reference to the SAME VcfWriter directly, bypassing lineParser entirely
    failingWriter.startFailing();
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    assertThrows(UncheckedIOException.class, () -> vcfWriter.writeHeader(metadata));

    // lineParser never called anything itself, but must still report invalid, because it delegates to the
    // same underlying writer rather than tracking its own separate (and here, unaware) state
    assertFalse(lineParser.isValid());
    assertThrows(IllegalStateException.class, () -> lineParser.parseMetadata(metadata));
  }

}

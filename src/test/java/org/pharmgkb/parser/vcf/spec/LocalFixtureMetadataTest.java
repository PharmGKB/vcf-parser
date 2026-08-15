package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LocalFixtureMetadataTest {

  @Test
  void parsesAllHeaderKeysIncludingRepeatedOnes(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("test.vcf");
    Files.writeString(file, "##fileformat=VCFv4.3\n" +
        "##CauseOfFailure=example cause\n" +
        "##Authority=first authority\n" +
        "##Authority=second authority\n" +
        "##ExpectParser=warn\n" +
        "##ExpectWarnContains=some warning text\n" +
        "##contig=<ID=1,length=100>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t1\t.\tA\tT\t.\t.\t.\n");

    LocalFixtureMetadata metadata = LocalFixtureMetadata.parse(file);

    assertEquals(List.of("first authority", "second authority"), metadata.authorities());
    assertTrue(metadata.causeOfFailure().isPresent());
    assertEquals("example cause", metadata.causeOfFailure().get());
    assertTrue(metadata.expectParser().isPresent());
    assertEquals("warn", metadata.expectParser().get());
    assertEquals(List.of("some warning text"), metadata.expectWarnContains());
    assertTrue(metadata.roundTripDivergenceReason().isEmpty());
  }

  @Test
  void roundTripDivergenceReasonIsReadWhenPresent(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("test.vcf");
    Files.writeString(file, "##fileformat=VCFv4.3\n" +
        "##Authority=some authority\n" +
        "##RoundTripDivergenceReason=byte-diff is expected here\n" +
        "##contig=<ID=1,length=100>\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" +
        "1\t1\t.\tA\tT\t.\t.\t.\n");

    LocalFixtureMetadata metadata = LocalFixtureMetadata.parse(file);

    assertTrue(metadata.roundTripDivergenceReason().isPresent());
    assertEquals("byte-diff is expected here", metadata.roundTripDivergenceReason().get());
    assertTrue(metadata.causeOfFailure().isEmpty());
    assertTrue(metadata.expectParser().isEmpty());
  }
}

package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class VcfRecordTest {

  private static VcfPosition newPosition() {
    return new VcfPosition("chr1", 100, null, "A", List.of("T"), null, null, null, null, -1);
  }

  @Test
  void testExposesPositionAndSamples() {
    VcfPosition pos = newPosition();
    VcfSample sample = new VcfSample(List.of("GT"), List.of("0/1"));
    VcfRecord record = new VcfRecord(pos, List.of(sample));
    assertEquals(pos, record.getPosition());
    assertEquals(List.of(sample), record.getSamples());
  }

  @Test
  void testSamplesViewIsUnmodifiable() {
    VcfRecord record = new VcfRecord(newPosition(), List.of());
    assertThrows(UnsupportedOperationException.class,
        () -> record.getSamples().add(new VcfSample(List.of("GT"), List.of("0/1"))));
  }

  @Test
  void testPublicConstructorDefensivelyCopies() {
    List<VcfSample> mutable = new ArrayList<>();
    mutable.add(new VcfSample(List.of("GT"), List.of("0/1")));
    VcfRecord record = new VcfRecord(newPosition(), mutable);
    mutable.clear();
    assertEquals(1, record.getSamples().size());
  }

  @Test
  void testNewTrustedDoesNotCopyButStillExposesUnmodifiably() {
    List<VcfSample> owned = new ArrayList<>();
    owned.add(new VcfSample(List.of("GT"), List.of("0/1")));
    VcfRecord record = VcfRecord.newTrusted(newPosition(), owned);
    assertEquals(1, record.getSamples().size());
    assertThrows(UnsupportedOperationException.class,
        () -> record.getSamples().add(new VcfSample(List.of("GT"), List.of("1/1"))));
  }

  // The next two pin the property MemoryMappedVcfLineParser.parseLine() relies on: "trusted" here means only
  // "skip the defensive copy and the per-element null check", never "skip validation". parseLine() dropped its
  // own null-position and null-sample-list checks because BOTH VcfRecord construction paths reject those, so
  // any future trusted factory -- notably the VcfContext.newTrustedRecord(...) that replaces newTrusted -- must
  // keep rejecting them. Implementing a trusted path as a direct field assignment would reopen that hole while
  // every other test still passed.

  @Test
  void testNewTrustedStillRejectsNullPositionSoParseLineNeedNotRecheck() {
    assertThrows(VcfFormatException.class, () -> VcfRecord.newTrusted(null, List.of()));
  }

  @Test
  void testNewTrustedStillRejectsNullSampleListSoParseLineNeedNotRecheck() {
    assertThrows(VcfFormatException.class, () -> VcfRecord.newTrusted(newPosition(), null));
  }

  @Test
  void testRejectsNullPosition() {
    assertThrows(VcfFormatException.class, () -> new VcfRecord(null, List.of()));
  }

  @Test
  void testRejectsNullSampleList() {
    assertThrows(VcfFormatException.class, () -> new VcfRecord(newPosition(), null));
  }

  @Test
  void testRejectsNullSampleElement() {
    List<VcfSample> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(VcfFormatException.class, () -> new VcfRecord(newPosition(), withNull));
  }
}

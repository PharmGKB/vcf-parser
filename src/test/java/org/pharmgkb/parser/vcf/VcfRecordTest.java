package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class VcfRecordTest {

  private static VcfPosition newPosition() {
    return STRICT_CONTEXT.newPosition("chr1", 100, null, "A", List.of("T"), null, null, null, null, -1);
  }

  @Test
  void testExposesPositionAndSamples() {
    VcfPosition pos = newPosition();
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1"));
    VcfRecord record = STRICT_CONTEXT.newRecord(pos, List.of(sample));
    assertEquals(pos, record.getPosition());
    assertEquals(List.of(sample), record.getSamples());
  }

  @Test
  void testSamplesViewIsUnmodifiable() {
    VcfRecord record = STRICT_CONTEXT.newRecord(newPosition(), List.of());
    assertThrows(UnsupportedOperationException.class,
        () -> record.getSamples().add(STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1"))));
  }

  @Test
  void testPublicConstructorDefensivelyCopies() {
    List<VcfSample> mutable = new ArrayList<>();
    mutable.add(STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1")));
    VcfRecord record = STRICT_CONTEXT.newRecord(newPosition(), mutable);
    mutable.clear();
    assertEquals(1, record.getSamples().size());
  }

  @Test
  void testNewTrustedDoesNotCopyButStillExposesUnmodifiably() {
    List<VcfSample> owned = new ArrayList<>();
    owned.add(STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1")));
    VcfRecord record = STRICT_CONTEXT.newTrustedRecord(newPosition(), owned);
    assertEquals(1, record.getSamples().size());
    assertThrows(UnsupportedOperationException.class,
        () -> record.getSamples().add(STRICT_CONTEXT.newSample(List.of("GT"), List.of("1/1"))));
  }

  // The next two pin the property MemoryMappedVcfLineParser.parseLine() relies on: "trusted" here means only
  // "skip the defensive copy and the per-element null check", never "skip validation". parseLine() dropped its
  // own null-position and null-sample-list checks because BOTH VcfRecord construction paths reject those, so
  // any future trusted factory -- notably the VcfContext.newTrustedRecord(...) that replaces newTrusted -- must
  // keep rejecting them. Implementing a trusted path as a direct field assignment would reopen that hole while
  // every other test still passed.

  @Test
  void testNewTrustedStillRejectsNullPositionSoParseLineNeedNotRecheck() {
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newTrustedRecord(null, List.of()));
  }

  @Test
  void testNewTrustedStillRejectsNullSampleListSoParseLineNeedNotRecheck() {
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newTrustedRecord(newPosition(), null));
  }

  @Test
  void testRejectsNullPosition() {
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newRecord(null, List.of()));
  }

  @Test
  void testRejectsNullSampleList() {
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newRecord(newPosition(), null));
  }

  @Test
  void testRejectsNullSampleElement() {
    List<VcfSample> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(VcfFormatException.class, () -> STRICT_CONTEXT.newRecord(newPosition(), withNull));
  }
}

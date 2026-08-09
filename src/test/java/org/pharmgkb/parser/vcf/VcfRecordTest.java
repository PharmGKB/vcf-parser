package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

  @Test
  void testToBuilderCopiesPositionAndSamplesAndPreservesContext() {
    VcfPosition pos = newPosition();
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1"));
    VcfRecord original = STRICT_CONTEXT.newRecord(pos, List.of(sample));
    VcfRecord copy = original.toBuilder().build();
    assertEquals(pos, copy.getPosition());
    assertEquals(List.of(sample), copy.getSamples());
    assertSame(original.getContext(), copy.getContext());
  }

  @Test
  void testToBuilderChangeSamplesLeavesSourceUntouched() {
    VcfPosition pos = newPosition();
    VcfSample original = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1"));
    VcfSample replacement = STRICT_CONTEXT.newSample(List.of("GT"), List.of("1/1"));
    VcfRecord source = STRICT_CONTEXT.newRecord(pos, List.of(original));
    VcfRecord modified = source.toBuilder().samples(List.of(replacement)).build();
    assertEquals(List.of(original), source.getSamples());
    assertEquals(List.of(replacement), modified.getSamples());
  }

  @Test
  void testBuilderSamplesSetterDefensivelyCopiesInput() {
    // independence test: mutating the caller's list AFTER build() must not affect the already-built record.
    // This alone does not prove the setter itself copies -- VcfContext.newRecord() also copies -- so it cannot
    // substitute for testBuilderSamplesSetterCopiesBeforeBuildSoLaterMutationDoesNotLeak below.
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1"));
    List<VcfSample> mutable = new ArrayList<>();
    mutable.add(sample);
    VcfRecord record = STRICT_CONTEXT.newRecord(newPosition(), List.of()).toBuilder().samples(mutable).build();
    mutable.add(STRICT_CONTEXT.newSample(List.of("GT"), List.of("1/1")));
    assertEquals(1, record.getSamples().size());
    assertEquals(sample, record.getSamples().get(0));
  }

  @Test
  void testBuilderSamplesSetterCopiesBeforeBuildSoLaterMutationDoesNotLeak() {
    // build() delegates to VcfContext.newRecord, which copies the list itself -- so a builder that merely
    // retains the caller's list reference would still yield an independent record as long as nothing mutates
    // that list before build() runs. The setter's own copy-on-the-way-in contract is only observable by
    // mutating the source list BEFORE calling build(): if Builder.samples() had only stored the reference,
    // this mutation would already be visible to the builder at build() time, and the resulting record would
    // silently reflect it instead of the one sample present when samples() was called.
    VcfPosition pos = newPosition();
    VcfSample sample = STRICT_CONTEXT.newSample(List.of("GT"), List.of("0/1"));
    VcfRecord original = STRICT_CONTEXT.newRecord(pos, List.of(sample));
    List<VcfSample> mutable = new ArrayList<>();
    mutable.add(sample);
    VcfRecord.Builder builder = original.toBuilder().samples(mutable);
    mutable.clear();
    VcfRecord built = builder.build();
    assertEquals(List.of(sample), built.getSamples());
    assertEquals(List.of(sample), original.getSamples());
  }

  @Test
  void testBuilderRejectsNullPosition() {
    VcfRecord.Builder builder = STRICT_CONTEXT.newRecord(newPosition(), List.of()).toBuilder();
    assertThrows(VcfFormatException.class, () -> builder.position(null));
  }

  @Test
  void testBuilderRejectsNullSamples() {
    VcfRecord.Builder builder = STRICT_CONTEXT.newRecord(newPosition(), List.of()).toBuilder();
    assertThrows(VcfFormatException.class, () -> builder.samples(null));
  }
}

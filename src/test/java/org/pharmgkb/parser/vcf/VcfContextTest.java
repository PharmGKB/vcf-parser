package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class VcfContextTest {

  @Test
  void testLenientCarriesDeclaredAndResolvedVersions() {
    VcfWarnings warnings = new VcfWarnings(new StringWriter());
    VcfContext ctx = VcfContext.lenient("VCFv4.2", warnings);
    assertEquals("VCFv4.2", ctx.getDeclaredFileFormat());
    assertEquals(VcfVersion.V4_2, ctx.getVersion());
    assertSame(warnings, ctx.getWarnings());
    assertTrue(ctx.isLenient());
  }

  @Test
  void testDeclaredIsPreservedVerbatimWhenResolutionFallsBack() {
    VcfWarnings warnings = new VcfWarnings(new StringWriter());
    VcfContext ctx = VcfContext.lenient("VCFv4.5", warnings);
    assertEquals("VCFv4.5", ctx.getDeclaredFileFormat());
    assertEquals(VcfVersion.HIGHEST_IMPLEMENTED, ctx.getVersion());
  }

  @Test
  void testStrictIsNotLenientButStillFallsBackRatherThanThrowing() {
    // strict governs CONTENT validation; version resolution is uniform across both modes.
    VcfContext ctx = VcfContext.strict("VCFv4.9");
    assertFalse(ctx.isLenient());
    assertEquals("VCFv4.9", ctx.getDeclaredFileFormat());
    assertEquals(VcfVersion.HIGHEST_IMPLEMENTED, ctx.getVersion());
    assertSame(VcfWarnings.DEFAULT, ctx.getWarnings());
  }

  @Test
  void testMalformedFileFormatThrowsInBothModes() {
    VcfWarnings warnings = new VcfWarnings(new StringWriter());
    assertThrows(VcfFormatException.class, () -> VcfContext.lenient("banana", warnings));
    assertThrows(VcfFormatException.class, () -> VcfContext.strict("banana"));
  }

  @Test
  void testNullWarningsThrowsNullPointerException() {
    // VcfWarnings is caller configuration, not VCF content -- raw NPE per AGENTS.md's Null-Safety Policy.
    assertThrows(NullPointerException.class, () -> VcfContext.lenient("VCFv4.2", null));
  }


  // ---- factories: every model object is bound to the context that created it ----

  @Test
  void testFactoriesBindTheirOwnContext() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    VcfPosition position = ctx.newPosition("chr1", 100, null, "A", List.of("T"), null, null, null,
        List.of("GT"), -1);
    VcfSample sample = ctx.newSample(List.of("GT"), List.of("0/1"));
    VcfRecord record = ctx.newRecord(position, List.of(sample));
    assertSame(ctx, position.getContext());
    assertSame(ctx, sample.getContext());
    assertSame(ctx, record.getContext());
    assertSame(ctx, ctx.newPosition("chr1", 100, "A", null, -1).getContext());
    assertSame(ctx, ctx.newSample(new LinkedHashMap<>(Map.of("GT", "0/1"))).getContext());
  }

  @Test
  void testNewPositionCopiesEveryMutableInput() {
    // Unmodifiable getters (Tasks 8-10) would not be enough on their own: if the factory stored the caller's
    // collections by reference, the caller would keep a live handle into the position's state.
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    List<String> ids = new ArrayList<>(List.of("rs1"));
    List<String> alt = new ArrayList<>(List.of("T"));
    List<String> filter = new ArrayList<>(List.of("q10"));
    List<String> format = new ArrayList<>(List.of("GT"));
    ListMultimap<String, String> info = LinkedListMultimap.create();
    info.put("DP", "5");

    VcfPosition position = ctx.newPosition("chr1", 100, ids, "A", alt, null, filter, info, format, -1);
    ids.add("rs2");
    alt.add("C");
    filter.add("q20");
    format.add("DP");
    info.put("AF", "0.5");

    assertEquals(List.of("rs1"), position.getIds());
    assertEquals(List.of("T"), position.getAltBases());
    assertEquals(List.of("q10"), position.getFilters());
    assertEquals(List.of("GT"), position.getFormat());
    assertEquals(Set.of("DP"), position.getInfoKeys());
  }

  @Test
  void testNewSampleCopiesBothListsAndTheMap() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    List<String> keys = new ArrayList<>(List.of("GT"));
    List<String> values = new ArrayList<>(List.of("0/1"));
    VcfSample fromLists = ctx.newSample(keys, values);
    keys.add("DP");
    values.add("5");
    assertEquals(Set.of("GT"), fromLists.getPropertyKeys());

    LinkedHashMap<String, String> properties = new LinkedHashMap<>();
    properties.put("GT", "0/1");
    VcfSample fromMap = ctx.newSample(properties);
    properties.put("DP", "5");
    assertEquals(Set.of("GT"), fromMap.getPropertyKeys());
  }

  @Test
  void testNewSampleRejectsNullMapInsteadOfThrowingNpe() {
    assertThrows(VcfFormatException.class,
        () -> VcfContext.strict("VCFv4.2").newSample((LinkedHashMap<String, String>) null));
  }


  // ---- newRecord: carries forward every guarantee VcfRecord's former public constructor made ----

  @Test
  void testNewRecordRejectsNulls() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    VcfPosition position = ctx.newPosition("chr1", 100, "A", null, -1);
    List<VcfSample> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(VcfFormatException.class, () -> ctx.newRecord(null, List.of()));
    assertThrows(VcfFormatException.class, () -> ctx.newRecord(position, null));
    assertThrows(VcfFormatException.class, () -> ctx.newRecord(position, withNull));
  }

  @Test
  void testNewRecordCopiesTheSampleList() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    VcfPosition position = ctx.newPosition("chr1", 100, "A", null, -1);
    List<VcfSample> samples = new ArrayList<>();
    samples.add(ctx.newSample(List.of("GT"), List.of("0/1")));
    VcfRecord record = ctx.newRecord(position, samples);
    samples.add(ctx.newSample(List.of("GT"), List.of("1/1")));
    assertEquals(1, record.getSamples().size());
  }

  @Test
  void testRecordRejectsComponentsFromDifferentContexts() {
    // The two contexts are deliberately equal in value and differ only by identity, so this fails if the
    // implementation ever compares context values instead of references.
    VcfContext ctxA = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfContext ctxB = VcfContext.lenient("VCFv4.2", new VcfWarnings(new StringWriter()));
    VcfPosition posA = ctxA.newPosition("chr1", 100, null, "A", List.of("T"), null, null, null,
        List.of("GT"), -1);
    VcfPosition posB = ctxB.newPosition("chr1", 100, null, "A", List.of("T"), null, null, null,
        List.of("GT"), -1);
    VcfSample sampleA = ctxA.newSample(List.of("GT"), List.of("0/1"));
    VcfSample sampleB = ctxB.newSample(List.of("GT"), List.of("0/1"));
    assertThrows(VcfFormatException.class, () -> ctxA.newRecord(posA, List.of(sampleB)));
    assertThrows(VcfFormatException.class, () -> ctxA.newRecord(posB, List.of(sampleA)));
    // and the matching-context case still works, so the check isn't just rejecting everything
    assertSame(ctxA, ctxA.newRecord(posA, List.of(sampleA)).getContext());
  }

  @Test
  void testNewTrustedRecordStillRejectsNullPositionAndNullSampleList() {
    // "Trusted" means skip the defensive copy and the per-element/context checks -- never skip these two.
    // MemoryMappedVcfLineParser.parseLine relies on that: it deliberately does not re-check either.
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> ctx.newTrustedRecord(null, List.of()));
    assertThrows(VcfFormatException.class,
        () -> ctx.newTrustedRecord(ctx.newPosition("chr1", 100, "A", null, -1), null));
  }

  @Test
  void testNewTrustedRecordSkipsTheCopyAndTheContextCheck() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    VcfContext other = VcfContext.strict("VCFv4.2");
    VcfPosition position = ctx.newPosition("chr1", 100, "A", null, -1);
    List<VcfSample> samples = new ArrayList<>();
    samples.add(other.newSample(List.of("GT"), List.of("0/1")));
    // no copy: a later add to the caller's list IS visible through the record (which is exactly why this is
    // restricted to VcfParser, the one caller that owns its list exclusively)
    VcfRecord record = ctx.newTrustedRecord(position, samples);
    samples.add(other.newSample(List.of("GT"), List.of("1/1")));
    assertEquals(2, record.getSamples().size());
  }

  @Test
  void testNewPositionTakingOwnershipValidatesFormatButSkipsTheCopies() {
    // The distinction from newPositionWithTrustedFormat, which the parser's cache-miss path depends on: this
    // one still runs checkFormat. A cache miss is precisely the case that has to.
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    List<String> badFormat = new ArrayList<>(List.of("DP", "GT"));   // GT present but not first
    assertThrows(VcfFormatException.class, () -> ctx.newPositionTakingOwnership("chr1", 100, null, "A", null,
        null, null, null, badFormat, -1));
    // ...and newPositionWithTrustedFormat, which skips checkFormat entirely, accepts the same list
    assertEquals(List.of("DP", "GT"), ctx.newPositionWithTrustedFormat("chr1", 100, null, "A", null, null, null,
        null, new ArrayList<>(List.of("DP", "GT")), -1).getFormat());
  }

  @Test
  void testNewPositionTakingOwnershipDoesNotCopyItsCollections() {
    // the whole reason it exists: the caller-owned lists are stored by reference, so the per-record parse path
    // pays no copy. Contrast testNewPositionCopiesEveryMutableInput for the public factory.
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    List<String> ids = new ArrayList<>(List.of("rs1"));
    List<String> alt = new ArrayList<>(List.of("T"));
    List<String> filter = new ArrayList<>(List.of("q10"));
    List<String> format = new ArrayList<>(List.of("GT"));
    VcfPosition position = ctx.newPositionTakingOwnership("chr1", 100, ids, "A", alt, null, filter, null,
        format, -1);
    assertSame(ids, position.getIds());
    assertSame(alt, position.getAltBases());
    assertSame(filter, position.getFilters());
    assertSame(format, position.getFormat());
  }

}

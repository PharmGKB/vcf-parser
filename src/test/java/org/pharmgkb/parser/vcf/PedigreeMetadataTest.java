package org.pharmgkb.parser.vcf;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link PedigreeMetadata}.
 */
public class PedigreeMetadataTest {

  @Test
  public void testConstructionSucceedsAndPropertiesRoundTrip() {
    Map<String, String> props = new LinkedHashMap<>();
    props.put("Name_0", "G0-ID");
    props.put("Name_1", "G1-ID");
    PedigreeMetadata md = new PedigreeMetadata(LENIENT_CONTEXT, props);
    assertEquals("G0-ID", md.getPropertyRaw("Name_0"));
    assertEquals("G1-ID", md.getPropertyRaw("Name_1"));
  }

  @Test
  public void testLabelIsPedigree() {
    PedigreeMetadata md = new PedigreeMetadata(LENIENT_CONTEXT, new LinkedHashMap<>());
    assertEquals("PEDIGREE", md.getLabel());
  }

  @Test
  public void testGetPropertiesRawRejectsDirectMutationInsteadOfSilentlyAllowingIt() {
    // Covers the getPropertiesRaw() route only; testPutMethodsThrowEvenThoughTheyArePackagePrivate below
    // covers the two package-private inserters, which are a genuinely separate route for this class.
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "Child");
    PedigreeMetadata md = new PedigreeMetadata(LENIENT_CONTEXT, props);
    assertThrows(UnsupportedOperationException.class, () -> md.getPropertiesRaw().put("ID", "Other"));
    assertThrows(UnsupportedOperationException.class, () -> md.getPropertiesRaw().remove("ID"));
    // beyond put/remove: an UnmodifiableMap's entrySet()/keySet() iterators and Map.Entry.setValue() are
    // separate back doors into "mutating" a supposedly-unmodifiable map -- pin that Collections.unmodifiableMap
    // blocks these too, not just the two most obvious methods
    assertThrows(UnsupportedOperationException.class,
        () -> md.getPropertiesRaw().entrySet().iterator().next().setValue("Other"));
    assertThrows(UnsupportedOperationException.class, () -> {
      java.util.Iterator<String> it = md.getPropertiesRaw().keySet().iterator();
      it.next();
      it.remove();
    });
    // confirm none of the rejected mutation attempts partially applied -- ID is still exactly what it was
    assertEquals("Child", md.getPropertyRaw("ID"));
  }

  @Test
  public void testPutMethodsThrowEvenThoughTheyArePackagePrivate() {
    // Regression test. Freezing the metadata layer made BaseMetadata.putPropertyRaw/putAndQuoteProperty
    // package-private, and this class's overrides of them were briefly deleted as "redundant" on the grounds
    // that package-private visibility already blocks every external caller. It does -- but not this package's
    // own code, and that is exactly who must not reach them here: VcfMetadata.Builder.addPedigree dedupes
    // against an incrementally-maintained Set of seen IDs that nothing recomputes, so an in-place ID change on
    // a stored entry silently desyncs it (see testInPlaceIdChangeCannotDesyncTheBuildersDedupSet below for the
    // exact failure). Unlike the six keyed types, this class has no typed constructor that needs an inserter,
    // so blocking them costs nothing.
    PedigreeMetadata md = new PedigreeMetadata(LENIENT_CONTEXT, new LinkedHashMap<>());
    assertThrows(UnsupportedOperationException.class, () -> md.putPropertyRaw("ID", "New"));
    assertThrows(UnsupportedOperationException.class, () -> md.putAndQuoteProperty("ID", "New"));
    assertTrue(md.getPropertiesRaw().isEmpty(), "neither rejected insert may partially apply");
  }

  @Test
  public void testInPlaceIdChangeCannotDesyncTheBuildersDedupSet() {
    // The concrete consequence the override above protects. If a stored entry's ID could change from "A" to
    // "B", the builder's dedup set would still hold "A" and no longer hold "B": a genuinely new ID=A would be
    // rejected as a duplicate of an A that no longer exists, and a real duplicate ID=B would be accepted.
    Map<String, String> props = new LinkedHashMap<>();
    props.put("ID", "A");
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2");
    PedigreeMetadata stored = new PedigreeMetadata(LENIENT_CONTEXT, props);
    builder.addPedigree(stored);

    assertThrows(UnsupportedOperationException.class, () -> stored.putPropertyRaw("ID", "B"));
    assertEquals("A", stored.getPropertyRaw("ID"));

    // the set is still in step with the list: a second "A" is still caught as the duplicate it is...
    Map<String, String> duplicateA = new LinkedHashMap<>();
    duplicateA.put("ID", "A");
    builder.addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, duplicateA));
    assertEquals(1, builder.build().getPedigrees().size());

    // ...and a genuinely new "B" is still accepted
    Map<String, String> newB = new LinkedHashMap<>();
    newB.put("ID", "B");
    builder.addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, newB));
    assertEquals(2, builder.build().getPedigrees().size());
  }
}

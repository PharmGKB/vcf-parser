package org.pharmgkb.parser.vcf;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  public void testPutPropertyRawThrowsInsteadOfMutating() {
    PedigreeMetadata md = new PedigreeMetadata(LENIENT_CONTEXT, new LinkedHashMap<>());
    assertThrows(UnsupportedOperationException.class, () -> md.putPropertyRaw("ID", "New"));
  }

  @Test
  public void testPutAndQuotePropertyThrowsInsteadOfMutating() {
    PedigreeMetadata md = new PedigreeMetadata(LENIENT_CONTEXT, new LinkedHashMap<>());
    assertThrows(UnsupportedOperationException.class, () -> md.putAndQuoteProperty("ID", "New"));
  }
}

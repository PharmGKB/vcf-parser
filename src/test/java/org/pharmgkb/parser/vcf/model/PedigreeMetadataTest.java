package org.pharmgkb.parser.vcf.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests {@link PedigreeMetadata}.
 */
public class PedigreeMetadataTest {

  @Test
  public void testConstructionSucceedsAndPropertiesRoundTrip() {
    Map<String, String> props = new LinkedHashMap<>();
    props.put("Name_0", "G0-ID");
    props.put("Name_1", "G1-ID");
    PedigreeMetadata md = new PedigreeMetadata(props);
    assertEquals("G0-ID", md.getPropertyRaw("Name_0"));
    assertEquals("G1-ID", md.getPropertyRaw("Name_1"));
  }

  @Test
  public void testLabelIsPedigree() {
    PedigreeMetadata md = new PedigreeMetadata(new LinkedHashMap<>());
    assertEquals("PEDIGREE", md.getLabel());
  }
}

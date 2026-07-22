package org.pharmgkb.parser.vcf.model;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.VcfFormatException;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests {@link VcfMetadata}.
 */
public class VcfMetadataTest {

  @Test
  public void testGetSampleIndex() {
    VcfMetadata metadata = new VcfMetadata.Builder()
        .setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT",
            "sample1", "sample2"))
        .build();
    assertEquals(0, metadata.getSampleIndex("sample1"));
    assertEquals(1, metadata.getSampleIndex("sample2"));
    // an unknown sample name, and a name that collides with a fixed column, both return -1 (not -10)
    assertEquals(-1, metadata.getSampleIndex("nosuchsample"));
    assertEquals(-1, metadata.getSampleIndex("INFO"));
  }

  @Test
  public void testAddAssemblyRejectsLineTerminator() {
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    assertThrows(VcfFormatException.class, () -> metadata.addAssembly("bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> metadata.addAssembly("bad\rvalue"));
  }

  @Test
  public void testAddPedigreeDatabaseRejectsLineTerminator() {
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    assertThrows(VcfFormatException.class, () -> metadata.addPedigreeDatabase("<bad\nvalue>"));
  }

  @Test
  public void testBuilderAddRawPropertyRejectsLineTerminator() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder().setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addRawProperty("name", "bad\nvalue"));
    assertThrows(VcfFormatException.class, () -> builder.addRawProperty("bad\nname", "value"));
  }
}

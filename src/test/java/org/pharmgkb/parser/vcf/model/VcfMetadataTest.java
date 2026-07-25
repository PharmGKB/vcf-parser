package org.pharmgkb.parser.vcf.model;

import java.util.ArrayList;
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
  public void testGetInfoPreservesDeclarationOrder() {
    // five distinct IDs added in a deliberately non-alphabetical order; HashMap's hash-bucket order for five
    // distinct keys is extremely unlikely to coincidentally match this insertion order
    VcfMetadata metadata = new VcfMetadata.Builder()
        .setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata("ZED", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata("YAK", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata("XEN", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata("WOW", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata("VIM", "d", InfoType.String, "1", null, null))
        .build();
    assertEquals(Arrays.asList("ZED", "YAK", "XEN", "WOW", "VIM"), new ArrayList<>(metadata.getInfo().keySet()));
  }

  @Test
  public void testGetRawPropertyKeysPreservesInsertionOrder() {
    // "phasing" would sort after "assembly" and "source" alphabetically; this asserts insertion order instead
    VcfMetadata metadata = new VcfMetadata.Builder()
        .setFileFormat("VCFv4.2")
        .addRawProperty("phasing", "partial")
        .addRawProperty("source", "myImputationProgramV3.1")
        .addRawProperty("assembly", "file://somewhere")
        .build();
    assertEquals(Arrays.asList("phasing", "source", "assembly"), new ArrayList<>(metadata.getRawPropertyKeys()));
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

  @Test
  public void testBuilderAddRawPropertyWarnsAndDropsEmptyValue() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_009.vcf's ##reference= (empty value)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addRawProperty("reference", "").build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertFalse(metadata.getRawPropertyKeys().contains("reference"));
  }

  @Test
  public void testBuilderAddRawPropertyWarnsAndDropsEmptyValueForAnyKey() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_assembly_000.vcf's ##assembly= (empty value) -- the check
    // applies uniformly to any key, not just a hardcoded list
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addRawProperty("assembly", "")
          .addRawProperty("myCustomKey", "")
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertFalse(metadata.getRawPropertyKeys().contains("assembly"));
    assertFalse(metadata.getRawPropertyKeys().contains("myCustomKey"));
  }

  @Test
  public void testBuilderAddRawPropertyDoesNotWarnForNonEmptyValue() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addRawProperty("source", "myImputationProgramV3.1")
          .build();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(java.util.Collections.singletonList("myImputationProgramV3.1"),
        metadata.getRawValuesOfProperty("source"));
  }

  @Test
  public void testIsEmptyMetadataValue() {
    assertTrue(VcfMetadata.isEmptyMetadataValue("reference", ""));
    assertTrue(VcfMetadata.isEmptyMetadataValue("reference", "   "));
    assertTrue(VcfMetadata.isEmptyMetadataValue("pedigreeDB", "<>"));
    assertFalse(VcfMetadata.isEmptyMetadataValue("assembly", "ftp://example.com/db"));
    // the "<>" special case is pedigreeDB-specific -- no fixture exercises the analogous case for other keys
    assertFalse(VcfMetadata.isEmptyMetadataValue("assembly", "<>"));
  }

  @Test
  public void testAddAltWarnsOnInvalidStructuralVariantCode() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_alt_005.vcf's ##CauseOfFailure=The ALT prefix is none of
    // the supported ones (INS, DEL, etc)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata("THIS:IS-NOT-VALID", "Not a real code"));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testBuilderAddAltWarnsOnInvalidStructuralVariantCode() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_alt_005.vcf's ##CauseOfFailure=The ALT prefix is none of
    // the supported ones (INS, DEL, etc)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata("THIS:IS-NOT-VALID", "Not a real code"));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltDoesNotWarnForValidStructuralVariantCode() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata("DEL", "Deletion"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltWarnsOnInvalidExtraNumber() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_alt_001.vcf's ##CauseOfFailure=Incorrect ALT Number value
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata(
          org.pharmgkb.parser.vcf.VcfUtils.extractProperties("ID=DEL", "Number=B", "Type=String",
              "Description=\"Deletion\""), true));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltWarnsOnInvalidExtraType() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_alt_002.vcf's ##CauseOfFailure=Incorrect ALT Type value
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata(
          org.pharmgkb.parser.vcf.VcfUtils.extractProperties("ID=DEL", "Number=1", "Type=Text",
              "Description=\"Deletion\""), true));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltDoesNotWarnForWellFormedExtraNumberAndType() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata(
          org.pharmgkb.parser.vcf.VcfUtils.extractProperties("ID=DEL", "Number=1", "Type=String",
              "Description=\"Deletion\""), true));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltDoesNotWarnForCustomSingleTokenId() {
    // CN0 is not a reserved structural-variant code, but it's a legitimate real-world convention (1000 Genomes
    // copy-number alleles, see src/test/resources/cnv.vcf) with no colon -- must not be rejected as an invalid SV
    // code (see VcfMetadata.validateAlt's comment for the full reasoning)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.1").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata("CN0", "Copy number allele: 0 copies"));
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltWarnsOnWhitespaceEvenWithoutColon() {
    // the character-set check is not SV-specific -- it applies to every ALT ID regardless of colon
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata("AB CD", "d"));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}

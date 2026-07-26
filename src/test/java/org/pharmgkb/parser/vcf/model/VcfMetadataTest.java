package org.pharmgkb.parser.vcf.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
  public void testAddAssemblyWarnsAndDropsEmptyValue() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAssembly("");
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getAssemblies().isEmpty());
  }

  @Test
  public void testAddPedigreeDatabaseWarnsAndDropsEmptyValue() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_pedigreedb_000.vcf's ##pedigreeDB= (empty value)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addPedigreeDatabase("");
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getPedigreeDatabases().isEmpty());
  }

  @Test
  public void testAddPedigreeDatabaseWarnsAndDropsEmptyBrackets() {
    // reproduces test/vcf/{4.1,4.2}/failed/failed_meta_pedigreedb_001.vcf's ##pedigreeDB=<>
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addPedigreeDatabase("<>");
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getPedigreeDatabases().isEmpty());
  }

  @Test
  public void testAddPedigreeDatabaseAcceptsBracketLessUrl() {
    // regression test for the bracket-requirement bug: VCFv4.2.tex/4.3.tex/4.4.tex's Pedigree field format section
    // all template this bracket-less ("##pedigreeDB=URL"); hts-specs' own passed/passed_meta_pedigreedb.vcf
    // fixture (all versions) is bracket-less and expected to parse; confirmed bcftools 1.23.1 accepts it
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.addPedigreeDatabase("ftp://www.ebi.ac.uk:8080/valid/host/to/file.db");
    assertEquals(java.util.Collections.singletonList("ftp://www.ebi.ac.uk:8080/valid/host/to/file.db"),
        metadata.getPedigreeDatabases());
  }

  @Test
  public void testRemovePedigreeDbAcceptsBracketLessUrl() {
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    metadata.addPedigreeDatabase("ftp://www.ebi.ac.uk:8080/valid/host/to/file.db");
    metadata.removePedigreeDb("ftp://www.ebi.ac.uk:8080/valid/host/to/file.db");
    assertTrue(metadata.getPedigreeDatabases().isEmpty());
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
  public void testBuilderAddRawPropertyWarnsAndDropsReservedKey() {
    // "INFO" collides with the structured ##INFO=<...> declaration keyword; VcfParser.parseMetadata's switch
    // would route a reparsed "INFO" key to the structured path, not back to a raw property, so keeping it here
    // would silently misrepresent what gets written
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").addRawProperty("INFO", "bad").build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertFalse(metadata.getRawPropertyKeys().contains("INFO"));
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
    assertTrue(VcfMetadata.isEmptyMetadataValue(""));
    assertTrue(VcfMetadata.isEmptyMetadataValue("   "));
    assertTrue(VcfMetadata.isEmptyMetadataValue("<>"));
    assertFalse(VcfMetadata.isEmptyMetadataValue("ftp://example.com/db"));
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

  @Test
  public void testAddAltWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata("DEL", "First"))
          .addAlt(new IdDescriptionMetadata("DEL", "Second"))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First", metadata.getAlts().get("DEL").getDescription());
  }

  @Test
  public void testAddInfoWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addInfo(new InfoMetadata("DP", "First depth", InfoType.Integer, "1", null, null))
          .addInfo(new InfoMetadata("DP", "Second depth", InfoType.Integer, "1", null, null))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First depth", metadata.getInfo().get("DP").getDescription());
  }

  @Test
  public void testAddFormatWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addFormat(new FormatMetadata("GT", "First genotype", "1", FormatType.String))
          .addFormat(new FormatMetadata("GT", "Second genotype", "1", FormatType.String))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First genotype", metadata.getFormats().get("GT").getDescription());
  }

  @Test
  public void testAddFilterWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addFilter(new IdDescriptionMetadata("q10", "First"))
          .addFilter(new IdDescriptionMetadata("q10", "Second"))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First", metadata.getFilters().get("q10").getDescription());
  }

  @Test
  public void testAddContigWarnsAndKeepsFirstOnDuplicateId() {
    // reproduces test/vcf/{4.1,4.2}/passed/passed_meta_contig.vcf's repeated ##contig ID=1 declaration
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addContig(new ContigMetadata("1", 100L, "GRCh38", null, null, null, null))
          .addContig(new ContigMetadata("1", 200L, "GRCh37", null, null, null, null))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(100L, metadata.getContigs().get("1").getLength());
  }

  @Test
  public void testAddSampleWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addSample(new IdDescriptionMetadata("S1", "First"))
          .addSample(new IdDescriptionMetadata("S1", "Second"))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First", metadata.getSamples().get("S1").getDescription());
  }

  @Test
  public void testAddPedigreeWarnsAndKeepsFirstOnDuplicateId() {
    java.util.Map<String, String> childProps = new java.util.LinkedHashMap<>();
    childProps.put("ID", "Child");
    childProps.put("Father", "Dad");
    java.util.Map<String, String> duplicateProps = new java.util.LinkedHashMap<>();
    duplicateProps.put("ID", "Child");
    duplicateProps.put("Mother", "Mom");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addPedigree(new BaseMetadata(childProps))
          .addPedigree(new BaseMetadata(duplicateProps))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(1, metadata.getPedigrees().size());
    assertEquals("Dad", metadata.getPedigrees().get(0).getPropertiesRaw().get("Father"));
  }

  @Test
  public void testAddPedigreeKeepsBothWhenIdAbsent() {
    // VCFv4.2.tex line 152's own grammar (##PEDIGREE=<Name_0=G0-ID,...,Name_N=GN-ID>) has no mandatory ID
    // attribute; confirmed bcftools 1.23.1 keeps both of two ID-less duplicate PEDIGREE lines (no dedup key to
    // compare), unlike the ID-bearing case above
    java.util.Map<String, String> props1 = new java.util.LinkedHashMap<>();
    props1.put("Name_0", "G0-ID");
    props1.put("Name_1", "G1-ID");
    java.util.Map<String, String> props2 = new java.util.LinkedHashMap<>();
    props2.put("Name_0", "G0-ID");
    props2.put("Name_1", "G1-ID");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
          .addPedigree(new BaseMetadata(props1))
          .addPedigree(new BaseMetadata(props2))
          .build();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(2, metadata.getPedigrees().size());
  }

  @Test
  public void testInstanceAddAltWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addAlt(new IdDescriptionMetadata("DEL", "First"));
      metadata.addAlt(new IdDescriptionMetadata("DEL", "Second"));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First", metadata.getAlts().get("DEL").getDescription());
  }

  @Test
  public void testInstanceAddInfoWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addInfo(new InfoMetadata("DP", "First depth", InfoType.Integer, "1", null, null));
      metadata.addInfo(new InfoMetadata("DP", "Second depth", InfoType.Integer, "1", null, null));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First depth", metadata.getInfo().get("DP").getDescription());
  }

  @Test
  public void testInstanceAddFormatWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addFormat(new FormatMetadata("GT", "First genotype", "1", FormatType.String));
      metadata.addFormat(new FormatMetadata("GT", "Second genotype", "1", FormatType.String));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First genotype", metadata.getFormats().get("GT").getDescription());
  }

  @Test
  public void testInstanceAddContigWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addContig(new ContigMetadata("1", 100L, "GRCh38", null, null, null, null));
      metadata.addContig(new ContigMetadata("1", 200L, "GRCh37", null, null, null, null));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(100L, metadata.getContigs().get("1").getLength());
  }

  @Test
  public void testInstanceAddFilterWarnsAndKeepsFirstOnDuplicateId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2").build();
    try {
      metadata.addFilter(new IdDescriptionMetadata("q10", "First"));
      metadata.addFilter(new IdDescriptionMetadata("q10", "Second"));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First", metadata.getFilters().get("q10").getDescription());
  }

  @Test
  public void testAddPedigreeIdPresentDoesNotConflictWithIdAbsentEntries() {
    // an ID-bearing entry's dedup check must not be confused by an unrelated ID-less entry already in the list
    java.util.Map<String, String> idLessProps = new java.util.LinkedHashMap<>();
    idLessProps.put("Name_0", "G0-ID");
    idLessProps.put("Name_1", "G1-ID");
    java.util.Map<String, String> idProps = new java.util.LinkedHashMap<>();
    idProps.put("ID", "Child");
    idProps.put("Father", "Dad");
    VcfMetadata metadata = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addPedigree(new BaseMetadata(idLessProps))
        .addPedigree(new BaseMetadata(idProps))
        .build();
    assertEquals(2, metadata.getPedigrees().size());
  }

  @Test
  public void testColumnsAreDefensivelyCopied() {
    // Builder.setColumns()/the private constructor must not alias the caller's own list -- mutating it after
    // build() should not be able to change this VcfMetadata's own column data
    List<String> columns = new ArrayList<>(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER",
        "INFO", "FORMAT", "sample1"));
    VcfMetadata metadata = new VcfMetadata.Builder()
        .setFileFormat("VCFv4.2")
        .setColumns(columns)
        .build();
    columns.add("sample2");
    columns.set(9, "renamed");
    assertEquals(1, metadata.getNumSamples());
    assertEquals("sample1", metadata.getSampleName(0));
    assertEquals(9, metadata.getColumnIndex("sample1"));
  }

  @Test
  public void testBuilderDoesNotShareStateAcrossTwoBuildCalls() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null));
    VcfMetadata first = builder.build();
    VcfMetadata second = builder.build();
    first.getInfo().put("EXTRA", new InfoMetadata("EXTRA", "d", InfoType.Integer, "1", null, null));
    assertEquals(2, first.getInfo().size());
    assertEquals(1, second.getInfo().size());
  }

  @Test
  public void testBuilderMutationAfterBuildDoesNotAffectAlreadyBuiltObject() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder().setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata("NS", "d", InfoType.Integer, "1", null, null));
    VcfMetadata metadata = builder.build();
    builder.addInfo(new InfoMetadata("AC", "d", InfoType.Integer, "A", null, null));
    assertEquals(1, metadata.getInfo().size());
  }
}

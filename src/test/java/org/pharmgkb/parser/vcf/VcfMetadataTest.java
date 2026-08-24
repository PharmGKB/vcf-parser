package org.pharmgkb.parser.vcf;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.pharmgkb.parser.vcf.TestUtils.STRICT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.pharmgkb.parser.vcf.TestUtils.lenientContext;
import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests {@link VcfMetadata}.
 */
public class VcfMetadataTest {

  @Test
  public void testGetSampleIndex() {
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT",
            "sample1", "sample2"))
        .build();
    assertEquals(0, metadata.getSampleIndex("sample1"));
    assertEquals(1, metadata.getSampleIndex("sample2"));
    // an unknown sample name, and a name that collides with a fixed column, both return -1 (not -10)
    assertEquals(-1, metadata.getSampleIndex("nosuchsample"));
    assertEquals(-1, metadata.getSampleIndex("INFO"));
    assertEquals(-1, metadata.getSampleIndex(null));
  }

  @Test
  public void testGetColumnIndex() {
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT",
            "sample1", "sample2"))
        .build();
    assertEquals(3, metadata.getColumnIndex("REF"));
    assertEquals(-1, metadata.getColumnIndex("nosuchcolumn"));
    assertEquals(-1, metadata.getColumnIndex(null));
  }

  @Test
  public void testGetAltIsNullTolerantInsteadOfThrowingNpe() {
    // null-tolerant "not present," matching getColumnIndex()/getSampleIndex()'s own null-tolerant contract
    // above -- id.startsWith(...) below m_alt.get(id) would otherwise raw-NPE for a null id
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2").build();
    assertNull(metadata.getAlt(null));
  }

  @Test
  public void testBuilderSetFileFormatRejectsNullInsteadOfThrowingNpe() {
    // fileFormat is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at FILE_FORMAT_PATTERN.matcher(fileFormat)
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat(null));
  }

  @Test
  public void testBuilderSetFileFormatRejectionMessageEscapesEmbeddedCarriageReturn() {
    // regression test found by the Phase 2 review's fourth re-verification round: this message previously
    // embedded fileFormat verbatim and unbounded -- the identical message VcfParser.java's own fileformat
    // check was fixed for (third re-verification round), but this public Builder setter takes arbitrary
    // caller content directly, with no length bound this method can rely on either.
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2\rBAD"));
    assertTrue(ex.getMessage().contains("VCFv4.2\\rBAD"), ex.getMessage());
    assertFalse(ex.getMessage().contains("VCFv4.2\rBAD"));
  }

  @Test
  public void testBuilderSetColumnsRejectsNullEntryInsteadOfThrowingNpe() {
    // setColumns() does not validate its argument at set time (deferred to build(), where columns is actually
    // consumed via List.copyOf(columns)); a null entry must be rejected with VcfFormatException there, not a raw
    // NullPointerException from List.copyOf
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", null));
    assertThrows(VcfFormatException.class, builder::build);
  }

  @Test
  public void testBuilderSetColumnsRejectsNullListInsteadOfThrowingNpe() {
    // cols is typed non-@Nullable, but nothing enforces that at compile time; a null list itself (as opposed to
    // a null entry, covered above) must be rejected with VcfFormatException at setColumns() itself, not deferred
    // to a raw NullPointerException from Preconditions.checkNotNull(columns) at build() time
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .setColumns(null));
  }

  @Test
  public void testBuilderAddRawPropertyRejectsNullNameInsteadOfThrowingNpe() {
    // name is typed non-@Nullable, but nothing enforces that at compile time; must reject a null with
    // VcfFormatException, not let it reach a raw NullPointerException at InternalUtils.checkNoLineTerminator
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addRawProperty(null, "value"));
  }

  @Test
  public void testBuilderAddRawPropertyRejectsNullValueInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addRawProperty("name", null));
  }

  // Every Builder.addXxx method below is typed to take a non-@Nullable metadata object, but nothing enforces
  // that at compile time; each must reject a null with VcfFormatException via the shared requireNonNull() guard,
  // not let it reach a raw NullPointerException at value.getId()/value.getPropertiesRaw().

  @Test
  public void testBuilderAddAltRejectsNullInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addAlt((IdDescriptionMetadata) null));
  }

  @Test
  public void testBuilderAddInfoRejectsNullInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addInfo((InfoMetadata) null));
  }

  @Test
  public void testBuilderAddFilterRejectsNullInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addFilter((IdDescriptionMetadata) null));
  }

  @Test
  public void testBuilderAddFormatRejectsNullInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addFormat((FormatMetadata) null));
  }

  @Test
  public void testBuilderAddContigRejectsNullInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addContig((ContigMetadata) null));
  }

  @Test
  public void testBuilderAddSampleRejectsNullInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addSample((IdDescriptionMetadata) null));
  }

  @Test
  public void testBuilderAddPedigreeRejectsNullInsteadOfThrowingNpe() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addPedigree((PedigreeMetadata) null));
  }

  @Test
  public void testGetInfoPreservesDeclarationOrder() {
    // five distinct IDs added in a deliberately non-alphabetical order; HashMap's hash-bucket order for five
    // distinct keys is extremely unlikely to coincidentally match this insertion order
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(STRICT_CONTEXT, "ZED", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata(STRICT_CONTEXT, "YAK", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata(STRICT_CONTEXT, "XEN", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata(STRICT_CONTEXT, "WOW", "d", InfoType.String, "1", null, null))
        .addInfo(new InfoMetadata(STRICT_CONTEXT, "VIM", "d", InfoType.String, "1", null, null))
        .build();
    assertEquals(Arrays.asList("ZED", "YAK", "XEN", "WOW", "VIM"), new ArrayList<>(metadata.getInfo().keySet()));
  }

  @Test
  public void testGetRawPropertyKeysPreservesInsertionOrder() {
    // "phasing" would sort after "assembly" and "source" alphabetically; this asserts insertion order instead
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .addRawProperty("phasing", "partial")
        .addRawProperty("source", "myImputationProgramV3.1")
        .addRawProperty("assembly", "file://somewhere")
        .build();
    assertEquals(Arrays.asList("phasing", "source", "assembly"), new ArrayList<>(metadata.getRawPropertyKeys()));
  }

  @Test
  public void testGetRawPropertyKeysRejectsDirectMutationInsteadOfSilentlyDefeatingIt() {
    // regression test: getRawPropertyKeys() must fail fast on a direct mutation attempt (matching
    // BaseMetadata.getPropertyKeys()'s identical fix) rather than silently no-opping on a detached copy or
    // corrupting the live backing multimap
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .addRawProperty("phasing", "partial")
        .build();
    assertThrows(UnsupportedOperationException.class, () -> metadata.getRawPropertyKeys().remove("phasing"));
    assertTrue(metadata.getRawPropertyKeys().contains("phasing"));
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
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("pedigreeDB", "").build();
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
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("pedigreeDB", "<>").build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getPedigreeDatabases().isEmpty());
  }

  @Test
  public void testAddAssemblyWarnsAndDropsBracketedValue() {
    // regression test: a non-empty bracket-wrapped value (unlike the empty "<>" case above) was previously
    // accepted by addRawProperty("assembly", ...) as a literal string, even though bcftools 1.23.1 hard-errors
    // ("Could not parse the header line") trying to read "##assembly=<...>" back -- see
    // VcfMetadata.collidesWithAttributeListGrammar's own comment
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("assembly", "<http://foo.com>").build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getAssemblies().isEmpty());
  }

  @Test
  public void testAddPedigreeDatabaseWarnsAndDropsBracketedValueWithNoEquals() {
    // regression test: before this fix, a non-empty bracket-wrapped value was accepted by
    // addRawProperty("pedigreeDB", ...) as a literal string with no warning at all. Confirmed directly against
    // bcftools 1.23.1: "##pedigreeDB=<http://foo.com>" hard-errors ("Could not parse the header line") since
    // "http://foo.com" isn't valid key=value attribute syntax. See
    // VcfMetadata.collidesWithAttributeListGrammar's own comment.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("pedigreeDB", "<http://foo.com>").build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getPedigreeDatabases().isEmpty());
  }

  @Test
  public void testAddPedigreeDatabaseAcceptsBracketedValueContainingEquals() {
    // "##pedigreeDB=<ID=foo>" is not this field's own documented grammar (VCFv4.2.tex/4.3.tex/4.4.tex's
    // Pedigree field format section templates it bracket-less), but confirmed directly that bcftools 1.23.1
    // accepts and round-trips it unchanged (it parses as a trivial, valid attribute list) -- so this library
    // does not reject it either, per collidesWithAttributeListGrammar's deliberately conservative rule
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addRawProperty("pedigreeDB", "<ID=foo>")
        .build();
    assertEquals(java.util.Collections.singletonList("<ID=foo>"), metadata.getPedigreeDatabases());
  }

  @Test
  public void testAddPedigreeDatabaseAcceptsBracketLessUrl() {
    // regression test for the bracket-requirement bug: VCFv4.2.tex/4.3.tex/4.4.tex's Pedigree field format section
    // all template this bracket-less ("##pedigreeDB=URL"); hts-specs' own passed/passed_meta_pedigreedb.vcf
    // fixture (all versions) is bracket-less and expected to parse; confirmed bcftools 1.23.1 accepts it
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addRawProperty("pedigreeDB", "ftp://www.ebi.ac.uk:8080/valid/host/to/file.db")
        .build();
    assertEquals(java.util.Collections.singletonList("ftp://www.ebi.ac.uk:8080/valid/host/to/file.db"),
        metadata.getPedigreeDatabases());
  }

  @Test
  public void testBuilderAddRawPropertyRejectsLineTerminator() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("reference", "").build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertFalse(metadata.getRawPropertyKeys().contains("reference"));
  }

  @Test
  public void testBuilderAddRawPropertyWarnsAndDropsEmptyKey() {
    // regression test: reproduces hts-specs-style "##=value" (an empty key, the mirror image of the empty-
    // value test just above) -- previously silently accepted with no warning at all. VCFv4.3.tex:109's
    // key/value structure implies a key must be present; bcftools 1.23.1 rejects the line outright.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("", "somevalue").build();
      assertTrue(appender.anyMessageContains("Metadata property name is empty"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertFalse(metadata.getRawPropertyKeys().contains(""));
  }

  @Test
  public void testBuilderAddRawPropertyThrowsForEmptyKeyUnderStrictContext() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addRawProperty("", "somevalue"));
  }

  @Test
  public void testBuilderAddRawPropertyDoesNotWarnForWhitespaceOnlyKeyViaDirectConstruction() {
    // deliberately scoped to a literally empty key, not a whitespace-only one, reaching THIS method directly
    // -- bcftools 1.23.1 tolerates and preserves a whitespace-only key verbatim, unlike a truly empty one
    // (see EMPTY_FIELD_HANDLING.md's Row 24), so calling addRawProperty("  ", "x") directly matches bcftools.
    // This does NOT hold for the same input read from a real file -- see
    // testParsingAWhitespaceOnlyMetadataKeyDropsItUnlikeBcftools below for that divergence.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      VcfMetadata metadata =
          new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2").addRawProperty("  ", "x").build();
      assertFalse(appender.anyMessageContains("Metadata property name is empty"));
      assertTrue(metadata.getRawPropertyKeys().contains("  "));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testParsingAWhitespaceOnlyMetadataKeyDropsItUnlikeBcftools() throws java.io.IOException {
    // regression test for a documentation/test inaccuracy the Phase 2 review's re-verification round found:
    // unlike direct construction (see the test above), VcfParser.parseMetadata() trims a generic metadata
    // line's key via line.substring(2, idx).trim() BEFORE it ever reaches addRawProperty's name.isEmpty()
    // check -- so a whitespace-only key from a real file arrives already reduced to "" and IS dropped, unlike
    // bcftools (which preserves "##  =x" verbatim, per EMPTY_FIELD_HANDLING.md's Row 24). This is a disclosed,
    // pre-existing consequence of the parser's own trim (needed so "##fileformat =VCFv4.3" still resolves to
    // the "fileformat" case), not a new decision -- see VcfMetadata.Builder.addRawProperty's own comment.
    String vcf = "##fileformat=VCFv4.2\n" +
        "##  =somevalue\n" +
        "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try (VcfParser parser = new VcfParser.Builder()
        .fromReader(new java.io.BufferedReader(new java.io.StringReader(vcf)))
        .parseWith((md, record) -> {})
        .build()) {
      metadata = parser.parseMetadata();
      assertTrue(appender.anyMessageContains("Metadata property name is empty"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertFalse(metadata.getRawPropertyKeys().contains("  "));
    assertFalse(metadata.getRawPropertyKeys().contains(""));
  }

  @Test
  public void testBuilderAddRawPropertyWarnsAndDropsUnclosedAngleBracket() {
    // regression test: "##foo=<abc" (no closing '>') previously parsed with zero warnings --
    // collidesWithAttributeListGrammar's own endsWith(">") guard never even looked at it. Confirmed
    // separately (bcftools 1.23.1) that this genuinely breaks bcftools too -- see
    // BcftoolsDependencyTest#testGenericPropertyUnclosedBracketNoEqualsBcftoolsRejects.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("foo", "<abc").build();
      assertTrue(appender.anyMessageContains("has no closing '>'"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getRawProperties().get("foo").isEmpty());
  }

  @Test
  public void testBuilderAddRawPropertyThrowsForUnclosedAngleBracketUnderStrictContext() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> builder.addRawProperty("foo", "<abc"));
  }

  @Test
  public void testHasUnclosedAngleBracket() {
    assertTrue(VcfMetadata.hasUnclosedAngleBracket("<abc"));
    assertTrue(VcfMetadata.hasUnclosedAngleBracket("<a=b"));
    // well-formed shapes must not be flagged -- collidesWithAttributeListGrammar's own domain, not this one's
    assertFalse(VcfMetadata.hasUnclosedAngleBracket("<ID=foo>"));
    assertFalse(VcfMetadata.hasUnclosedAngleBracket("<>"));
    assertFalse(VcfMetadata.hasUnclosedAngleBracket("http://foo.com"));
    // doesn't start with '<' at all
    assertFalse(VcfMetadata.hasUnclosedAngleBracket("abc>"));
  }

  @Test
  public void testBuilderAddRawPropertyWarnsAndDropsBracketedValue() {
    // regression test: confirms the bracket-collision check applies to an arbitrary generic property key, not
    // just "assembly"/"pedigreeDB" -- htslib's own header-line parser special-cases any generic property value
    // shaped like "<...>" regardless of key. See VcfMetadata.collidesWithAttributeListGrammar's own comment.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("myCustomKey", "<http://foo.com>").build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertFalse(metadata.getRawPropertyKeys().contains("myCustomKey"));
  }

  @Test
  public void testBuilderAddRawPropertyAcceptsSquareBracketSubListValue() {
    // regression test for the bug this predicate was corrected to fix: the exact hts-specs
    // test/vcf/4.3/passed/passed_meta_meta.vcf fixture content is a real, legitimate value that happens to be
    // bracket-wrapped and contain a square-bracket sub-list -- an earlier, over-broad version of
    // collidesWithAttributeListGrammar (reject ANY bracket-wrapped value) incorrectly rejected this; confirmed
    // directly that bcftools 1.23.1 accepts and round-trips it unchanged. Stored under a non-reserved generic
    // key here, not the literal "META" the fixture uses: this library now routes "META" to the structured
    // ##META path (InternalUtils.RESERVED_METADATA_KEYS), so this test uses a different key to keep exercising
    // a genuinely generic raw property with the same bracket-sub-list payload.
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addRawProperty("customMeta", "<ID=Assay,Number=.,Type=String,Values=[WholeGenome, Exome]>")
        .build();
    assertEquals(java.util.Collections.singletonList("<ID=Assay,Number=.,Type=String,Values=[WholeGenome, Exome]>"),
        metadata.getRawValuesOfProperty("customMeta"));
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addRawProperty("INFO", "bad").build();
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
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
      metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
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
    // "<>" is no longer classified as "empty" -- it's handled separately by collidesWithAttributeListGrammar below,
    // since a bracket-wrapped value needs rejecting regardless of whether its content is empty
    assertFalse(VcfMetadata.isEmptyMetadataValue("<>"));
    assertFalse(VcfMetadata.isEmptyMetadataValue("ftp://example.com/db"));
  }

  @Test
  public void testCollidesWithAttributeListGrammar() {
    assertTrue(VcfMetadata.collidesWithAttributeListGrammar("<>"));
    assertTrue(VcfMetadata.collidesWithAttributeListGrammar("<http://foo.com>"));
    assertTrue(VcfMetadata.collidesWithAttributeListGrammar("  <http://foo.com>  "));
    // a bracket-wrapped value containing at least one "=" is left alone, even though it isn't the field's own
    // documented bracket-less grammar -- bcftools 1.23.1 confirmed to accept and round-trip both of these
    assertFalse(VcfMetadata.collidesWithAttributeListGrammar("<ID=foo>"));
    // regression test: the exact hts-specs test/vcf/4.3/passed/passed_meta_meta.vcf fixture content -- an
    // earlier, over-broad version of this predicate (reject ANY bracket-wrapped value) incorrectly rejected
    // this real, legitimate ##META line, which bcftools genuinely accepts and round-trips
    assertFalse(VcfMetadata.collidesWithAttributeListGrammar(
        "<ID=Assay,Number=.,Type=String,Values=[WholeGenome, Exome]>"));
    assertFalse(VcfMetadata.collidesWithAttributeListGrammar("ftp://example.com/db"));
    assertFalse(VcfMetadata.collidesWithAttributeListGrammar(""));
    assertFalse(VcfMetadata.collidesWithAttributeListGrammar("<"));
    assertFalse(VcfMetadata.collidesWithAttributeListGrammar(">"));
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
      new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "THIS:IS-NOT-VALID", "Not a real code"));
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
    try {
      new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(STRICT_CONTEXT, "ALT", "DEL", "Deletion"))
          .build();
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
    try {
      new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"ID=DEL", "Number=B", "Type=String", "Description=\"Deletion\""},
                  VcfWarnings.DEFAULT)))
          .build();
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
    try {
      new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"ID=DEL", "Number=1", "Type=Text", "Description=\"Deletion\""},
                  VcfWarnings.DEFAULT)))
          .build();
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
    try {
      new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(STRICT_CONTEXT, "ALT",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"ID=DEL", "Number=1", "Type=String", "Description=\"Deletion\""},
                  VcfWarnings.DEFAULT)))
          .build();
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
    VcfContext ctx = VcfContext.strict("VCFv4.1");
    try {
      new VcfMetadata.Builder(ctx)
          .addAlt(new IdDescriptionMetadata(ctx, "ALT", "CN0", "Copy number allele: 0 copies"))
          .build();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltWarnsOnWhitespaceEvenWithoutColon() {
    // the whitespace check is not SV-specific -- it applies to every ALT ID regardless of colon. It now comes
    // from IdMetadata.init() at construction time (shared across all ID-based metadata types), not from
    // VcfMetadata.validateAlt() itself, which was narrowed to angle-brackets-only -- see IdMetadata.java.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      // The whitespace check is IdMetadata's, made at construction. Whether it warns or throws is the context's
      // decision, not the overload's, so this uses a lenient context -- under a strict one the identical check
      // would throw instead. This addAlt() call itself doesn't warn/throw for "AB CD" (a non-blank ID with no
      // colon and no angle bracket).
      new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT",
              InternalUtils.extractProperties(new String[]{"ID=AB CD", "Description=\"d\""}, VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltWarnsOnAngleBracketEvenWithoutColon() {
    // VcfMetadata.validateAlt()'s own angle-bracket check remains ALT-specific (no other type's ID grammar
    // restricts angle brackets); this proves it still fires correctly after being narrowed from its old combined
    // whitespace-or-angle-bracket condition to angle-brackets-only
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "AB<CD", "d"))
          .build();
      assertTrue(appender.anyMessageContains("must not contain angle brackets"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAddAltWarnsOnBothWhitespaceAndInvalidStructuralVariantCodeWhenBothPresent() {
    // "DEL:A B" combines a colon (invoking the structural-variant-code grammar) with an embedded space --
    // regression test: after narrowing validateAlt()'s own check to angle-brackets only, both the generic
    // whitespace warning (from IdMetadata.init(), at construction) and validateAlt()'s own "not a valid
    // structural variant code" warning (since AltStructuralVariant("DEL:A B") itself rejects the embedded space,
    // per AltStructuralVariantTest#testWhitespaceInComponentRejected) must fire, not just one
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      // IdDescriptionMetadata's typed (label, id, description) constructor now throws immediately on whitespace
      // (IdMetadata's strict construction-time check, see IdMetadata.java) -- use the Map-based (lenient)
      // constructor with VcfWarnings.DEFAULT instead so the whitespace check still warns here rather than
      // throwing before this test can exercise VcfMetadata.validateAlt()'s own SV-code warning.
      new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT",
              InternalUtils.extractProperties(new String[]{"ID=DEL:A B", "Description=\"d\""},
                  VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
      assertTrue(appender.anyMessageContains("not a valid structural variant code"));
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "First"))
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "Second"))
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addInfo(new InfoMetadata(LENIENT_CONTEXT, "DP", "First depth", InfoType.Integer, "1", null, null))
          .addInfo(new InfoMetadata(LENIENT_CONTEXT, "DP", "Second depth", InfoType.Integer, "1", null, null))
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "First genotype", "1", FormatType.String))
          .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "Second genotype", "1", FormatType.String))
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER", "q10", "First"))
          .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER", "q10", "Second"))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("First", metadata.getFilters().get("q10").getDescription());
  }

  @Test
  public void testAddFilterQuotedIdDedupesAgainstUnquotedId() {
    // dedup semantics change this task introduces: ID="q10" and ID=q10 are the same ID -- quoting is an
    // encoding, not part of the identifier -- so the second declaration below must be reported as a duplicate
    // of the first, and the map must be reachable by the plain (unquoted) key either way.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER",
              InternalUtils.extractProperties(new String[]{"ID=\"q10\"", "Description=\"First\""},
                  VcfWarnings.DEFAULT)))
          .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER", "q10", "Second"))
          .build();
      assertTrue(appender.anyMessageContains("Duplicate ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(1, metadata.getFilters().size());
    assertEquals("First", metadata.getFilters().get("q10").getDescription());
  }

  @Test
  public void testAddFilterDropsQuotedBlankId() {
    // regression test for the retired WONT_FIX.md entry: a quoted-empty (ID="") or quoted-whitespace
    // (ID="   ") FILTER ID was previously retained in metadata (and would have been re-emitted by the default
    // writer) because the raw two-/five-character string is never blank once trimmed. Both must now be dropped,
    // exactly like their unquoted equivalents (ID=, ID=   ).
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER",
              InternalUtils.extractProperties(new String[]{"ID=\"\"", "Description=\"d\""}, VcfWarnings.DEFAULT)))
          .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER",
              InternalUtils.extractProperties(new String[]{"ID=\"   \"", "Description=\"d\""},
                  VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.anyMessageContains("has no ID and cannot be stored"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(0, metadata.getFilters().size());
  }

  @Test
  public void testAddFilterViaTypedOverloadDropsQuotedWhitespaceIdWithoutMisleadingWhitespaceWarning() {
    // regression test: addFilter(String, String) -- the public, raw-value overload, not the Map-based one
    // above -- constructs IdDescriptionMetadata via the typed (label, id, description) constructor, which
    // used to test its id parameter raw. A literal quoted-whitespace string passed there (as if a caller had
    // copied a raw header value verbatim) fired "must not contain whitespace" from that constructor, one step
    // before addFilter's own blank check correctly dropped the declaration as blank. Both must now agree: only
    // "has no ID and cannot be stored" fires, never the misleading whitespace warning.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addFilter("\"   \"", "d")
          .build();
      assertTrue(appender.anyMessageContains("has no ID and cannot be stored"));
      assertFalse(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(0, metadata.getFilters().size());
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addContig(new ContigMetadata(LENIENT_CONTEXT, "1", 100L, "GRCh38", null, null, null, null))
          .addContig(new ContigMetadata(LENIENT_CONTEXT, "1", 200L, "GRCh37", null, null, null, null))
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addSample(new IdDescriptionMetadata(LENIENT_CONTEXT, "SAMPLE", "S1", "First"))
          .addSample(new IdDescriptionMetadata(LENIENT_CONTEXT, "SAMPLE", "S1", "Second"))
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, childProps))
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, duplicateProps))
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
  public void testAddPedigreeQuotedIdDedupesAgainstUnquotedId() {
    // same dedup semantics change as testAddFilterQuotedIdDedupesAgainstUnquotedId, for the List-backed
    // ##PEDIGREE dedup set: ID="Child" and ID=Child are the same ID.
    java.util.Map<String, String> quotedProps = new java.util.LinkedHashMap<>();
    quotedProps.put("ID", "\"Child\"");
    quotedProps.put("Father", "Dad");
    java.util.Map<String, String> unquotedProps = new java.util.LinkedHashMap<>();
    unquotedProps.put("ID", "Child");
    unquotedProps.put("Mother", "Mom");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, quotedProps))
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, unquotedProps))
          .build();
      assertTrue(appender.anyMessageContains("Duplicate ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(1, metadata.getPedigrees().size());
    assertEquals("Dad", metadata.getPedigrees().get(0).getPropertiesRaw().get("Father"));
  }

  @Test
  public void testAddPedigreeQuotedBlankIdUnder43WarnsAndIsKept() {
    // regression test for the retired WONT_FIX.md entry: under VCFv4.3, a quoted-empty (ID="") or
    // quoted-whitespace (ID="   ") PEDIGREE ID previously produced zero warnings at all -- addPedigree's own
    // blank check had no whitespace check to even partially catch it, unlike the seven keyed types -- so this was
    // the fully silent escape the WONT_FIX.md entry called out. Both must now warn "has no ID", exactly like
    // the unquoted shapes, and (matching ##PEDIGREE's own kept-not-dropped rule) both must still be kept.
    java.util.Map<String, String> emptyProps = new java.util.LinkedHashMap<>();
    emptyProps.put("ID", "\"\"");
    java.util.Map<String, String> whitespaceProps = new java.util.LinkedHashMap<>();
    whitespaceProps.put("ID", "\"   \"");
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
          .addPedigree(new PedigreeMetadata(lenient43, emptyProps))
          .addPedigree(new PedigreeMetadata(lenient43, whitespaceProps))
          .build();
      assertEquals(2, appender.countMessagesContaining("has no ID"));
      assertFalse(appender.anyMessageContains("Duplicate ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(2, metadata.getPedigrees().size());
  }

  /** Strict-context counterpart of testAddPedigreeQuotedBlankIdUnder43WarnsAndIsKept. */
  @Test
  public void testAddPedigreeThrowsOnQuotedBlankIdUnderStrict43() {
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    Map<String, String> emptyProps = new java.util.LinkedHashMap<>();
    emptyProps.put("ID", "\"\"");
    VcfMetadata.Builder builder = new VcfMetadata.Builder(strict43).setFileFormat("VCFv4.3");
    assertThrows(VcfFormatException.class,
        () -> builder.addPedigree(new PedigreeMetadata(strict43, emptyProps)));
    Map<String, String> whitespaceProps = new java.util.LinkedHashMap<>();
    whitespaceProps.put("ID", "\"   \"");
    assertThrows(VcfFormatException.class,
        () -> builder.addPedigree(new PedigreeMetadata(strict43, whitespaceProps)));
  }

  @Test
  public void testAddPedigreeWarnsButKeepsPresentIdContainingWhitespace() {
    // regression test: every other one of the seven ID-bearing declaration types warns on whitespace in a
    // present (non-blank) ID via IdMetadata's shared init() check (VCF_SPEC_AMBIGUITIES.md's "Whitespace in
    // ##FILTER/##SAMPLE declaration IDs" entry). PedigreeMetadata extends BaseMetadata directly, not
    // IdMetadata, so it never inherited that check -- "A B" (present, non-blank, but containing whitespace)
    // previously parsed with zero warnings.
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("ID", "A B");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
          .addPedigree(new PedigreeMetadata(lenient43, props))
          .build();
      assertTrue(appender.anyMessageContains("PEDIGREE metadata ID \"A B\" must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // warn-and-keep, matching every other whitespace-in-ID warning elsewhere in this codebase -- not dropped
    assertEquals(1, metadata.getPedigrees().size());
  }

  @Test
  public void testAddPedigreeThrowsOnPresentIdContainingWhitespaceUnderStrictContext() {
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("ID", "A B");
    VcfMetadata.Builder builder = new VcfMetadata.Builder(strict43).setFileFormat("VCFv4.3");
    assertThrows(VcfFormatException.class,
        () -> builder.addPedigree(new PedigreeMetadata(strict43, props)));
  }

  @Test
  public void testAddPedigreeWhitespaceCheckIsNotGatedOnVersion() {
    // unlike the missing-ID check (isAtLeast(V4_3)-gated, since ID itself isn't required before 4.3), the
    // whitespace check is unconditional -- matching the other six ID-bearing types' own unconditional
    // treatment: a PRESENT ID's whitespace is invalid at every version, even though whether an ID is
    // required at all is version-dependent
    VcfContext lenient42 = VcfContext.lenient("VCFv4.2", VcfWarnings.DEFAULT);
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("ID", "A B");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      new VcfMetadata.Builder(lenient42).setFileFormat("VCFv4.2")
          .addPedigree(new PedigreeMetadata(lenient42, props))
          .build();
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
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
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props1))
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props2))
          .build();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(2, metadata.getPedigrees().size());
  }

  @Test
  public void testDirectBuilderUseThrowsOnDuplicateIdForPedigree() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    java.util.Map<String, String> childProps = new java.util.LinkedHashMap<>();
    childProps.put("ID", "Child");
    childProps.put("Father", "Dad");
    builder.addPedigree(childProps);
    java.util.Map<String, String> duplicateProps = new java.util.LinkedHashMap<>();
    duplicateProps.put("ID", "Child");
    duplicateProps.put("Mother", "Mom");
    PedigreeMetadata dup = new PedigreeMetadata(STRICT_CONTEXT, duplicateProps);
    assertThrows(VcfFormatException.class, () -> builder.addPedigree(dup));
  }

  @Test
  public void testBuilderAddPedigreeDoesNotDegradeQuadraticallyWithManyUniqueIds() {
    // 3 seconds is a wide margin above the fixed behavior's near-instant completion at this size; the unfixed
    // O(n) m_pedigree-rescan-per-add code was empirically ~1.2s at n=16,000 and scaled quadratically (~4x cost
    // for 2x n), so n=30,000 unfixed would take several seconds -- per the finding that motivated this fix
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
      VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
      for (int i = 0; i < 30_000; i++) {
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        props.put("ID", "P" + i);
        props.put("Name_0", "G0-" + i);
        builder.addPedigree(props);
      }
      assertEquals(30_000, builder.build().getPedigrees().size());
    });
  }

  @Test
  public void testAddPedigreeCacheHandlesIdLessAddsInterleavedWithIdBearingAdds() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2");
    java.util.Map<String, String> idLess1 = new java.util.LinkedHashMap<>();
    idLess1.put("Name_0", "G0-1");
    builder.addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, idLess1));
    java.util.Map<String, String> propsA = new java.util.LinkedHashMap<>();
    propsA.put("ID", "A");
    builder.addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, propsA));
    java.util.Map<String, String> idLess2 = new java.util.LinkedHashMap<>();
    idLess2.put("Name_0", "G0-2");
    builder.addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, idLess2));
    assertEquals(3, builder.build().getPedigrees().size());
    // a genuine duplicate of "A" must still be caught after the ID-less adds in between
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      java.util.Map<String, String> dupA = new java.util.LinkedHashMap<>();
      dupA.put("ID", "A");
      builder.addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, dupA));
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(3, builder.build().getPedigrees().size());
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
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, idLessProps))
        .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, idProps))
        .build();
    assertEquals(2, metadata.getPedigrees().size());
  }

  /**
   * VCFv4.3 requires an ID on every structured line, so a 4.3 ##PEDIGREE without one is diagnosed --
   * for an absent ID, an empty ID=, and a whitespace-only ID alike. Unlike the seven keyed declaration
   * types, the declaration is KEPT: ##PEDIGREE is List-backed, so the "cannot be stored" reason those
   * types drop for does not apply. See DUPLICATE_METADATA_ID_HANDLING.md's "Missing or empty ID".
   */
  @Test
  public void testAddPedigreeWarnsAndKeepsAbsentIdUnder43() {
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("Original", "G0-ID");
    assertBlankPedigreeIdWarnsAndIsKeptUnder43(props);
  }

  @Test
  public void testAddPedigreeWarnsAndKeepsEmptyIdUnder43() {
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("ID", "");
    props.put("Original", "G0-ID");
    assertBlankPedigreeIdWarnsAndIsKeptUnder43(props);
  }

  @Test
  public void testAddPedigreeWarnsAndKeepsWhitespaceOnlyIdUnder43() {
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("ID", "   ");
    props.put("Original", "G0-ID");
    assertBlankPedigreeIdWarnsAndIsKeptUnder43(props);
  }

  private static void assertBlankPedigreeIdWarnsAndIsKeptUnder43(Map<String, String> props) {
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
          .addPedigree(new PedigreeMetadata(lenient43, props))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(1, metadata.getPedigrees().size(), "the declaration must be kept, not dropped");
    assertTrue(appender.anyMessageContains("has no ID"));
  }

  /** A strict 4.3 context rejects rather than warns, matching every other missing-ID declaration. */
  @Test
  public void testAddPedigreeThrowsOnBlankIdUnderStrict43() {
    VcfContext strict43 = VcfContext.strict("VCFv4.3");
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("ID", "");
    VcfMetadata.Builder builder = new VcfMetadata.Builder(strict43).setFileFormat("VCFv4.3");
    assertThrows(VcfFormatException.class,
        () -> builder.addPedigree(new PedigreeMetadata(strict43, props)));
  }

  /**
   * A blank ID must never enter the dedupe set at 4.3: two blank-ID declarations are two missing-ID
   * defects, not a collision. Asserting the absence of the duplicate wording is the point -- before the
   * ordering fix, the second line reported Duplicate ID "" INSTEAD of the diagnostic that matters.
   */
  @Test
  public void testTwoBlankIdPedigreesUnder43WarnTwiceAndNeverReportDuplicate() {
    VcfContext lenient43 = VcfContext.lenient("VCFv4.3", VcfWarnings.DEFAULT);
    Map<String, String> props1 = new java.util.LinkedHashMap<>();
    props1.put("ID", "");
    Map<String, String> props2 = new java.util.LinkedHashMap<>();
    props2.put("ID", "");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(lenient43).setFileFormat("VCFv4.3")
          .addPedigree(new PedigreeMetadata(lenient43, props1))
          .addPedigree(new PedigreeMetadata(lenient43, props2))
          .build();
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(2, metadata.getPedigrees().size());
    assertEquals(2, appender.countMessagesContaining("has no ID"));
    assertFalse(appender.anyMessageContains("Duplicate ID"));
  }

  /**
   * A declared version this library does not implement resolves to 4.3, so the 4.3 rule must apply to it.
   * This is the case that proves the gate reads the RESOLVED version off the context rather than comparing
   * the raw ##fileformat string: every other test here would still pass if someone rewrote the guard as an
   * exact-4.3 check. VcfVersion.resolveWithoutWarning maps anything at or above HIGHEST_IMPLEMENTED down to
   * V4_3, so VCFv4.4 gets V4_3 semantics plus a one-time fallback warning.
   */
  @Test
  public void testAddPedigreeBlankIdUnderFallbackTo43IsDiagnosed() {
    // built BEFORE the appender is attached: VcfContext.lenient() emits the fallback warning during
    // construction, and capturing it here would blur which diagnostic this test is actually asserting
    VcfContext lenient44 = VcfContext.lenient("VCFv4.4", VcfWarnings.DEFAULT);
    Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put("ID", "");
    props.put("Original", "G0-ID");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(lenient44).setFileFormat("VCFv4.4")
          .addPedigree(new PedigreeMetadata(lenient44, props))
          .build();
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(appender.anyMessageContains("has no ID"),
        "a VCFv4.4 declaration resolves to 4.3, so the 4.3 PEDIGREE ID rule must apply to it");
    assertEquals(1, metadata.getPedigrees().size());
  }

  /** The 4.2 arm is unchanged: VCFv4.2's ##PEDIGREE template has no ID attribute at all. */
  @Test
  public void testAddPedigreeBlankIdUnder42IsSilentAndStillDedupes() {
    Map<String, String> props1 = new java.util.LinkedHashMap<>();
    props1.put("ID", "");
    Map<String, String> props2 = new java.util.LinkedHashMap<>();
    props2.put("ID", "");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props1))
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props2))
          .build();
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // pre-existing 4.2 behavior: the empty string IS a dedupe key, so the second is a duplicate
    assertEquals(1, metadata.getPedigrees().size());
    assertTrue(appender.anyMessageContains("Duplicate ID"));
    assertFalse(appender.anyMessageContains("has no ID"));
  }

  /** 4.2 regression, absent-ID shape: extends the existing empty-ID-only coverage above to all three shapes. */
  @Test
  public void testAddPedigreeAbsentIdUnder42IsSilentAndStillDedupes() {
    Map<String, String> props1 = new java.util.LinkedHashMap<>();
    props1.put("Original", "G0-ID");
    Map<String, String> props2 = new java.util.LinkedHashMap<>();
    props2.put("Original", "G0-ID");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props1))
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props2))
          .build();
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // no ID property at all means no dedupe key -- both are kept, matching testAddPedigreeKeepsBothWhenIdAbsent
    assertEquals(2, metadata.getPedigrees().size());
    assertFalse(appender.anyMessageContains("has no ID"));
  }

  /** 4.2 regression, whitespace-only-ID shape: extends the existing empty-ID-only coverage above to all three shapes. */
  @Test
  public void testAddPedigreeWhitespaceOnlyIdUnder42IsSilentAndStillDedupes() {
    Map<String, String> props1 = new java.util.LinkedHashMap<>();
    props1.put("ID", "   ");
    Map<String, String> props2 = new java.util.LinkedHashMap<>();
    props2.put("ID", "   ");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props1))
          .addPedigree(new PedigreeMetadata(LENIENT_CONTEXT, props2))
          .build();
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    // pre-existing 4.2 behavior: "   " IS a dedupe key at 4.2 (no blank-ID special-casing pre-4.3), so the
    // second is a duplicate -- same shape as the empty-ID case above, just with a whitespace-only ID literal
    assertEquals(1, metadata.getPedigrees().size());
    assertTrue(appender.anyMessageContains("Duplicate ID"));
    assertFalse(appender.anyMessageContains("has no ID"));
    // regression test found by the Phase 2 review's re-verification round: this whitespace-only ID must NOT
    // also be misdiagnosed as "must not contain whitespace" -- there is no usable ID content once trimmed, so
    // that message would misleadingly imply trimming it fixes the declaration (matching IdMetadata.init()'s
    // own reasoning for treating blank-when-trimmed and whitespace-in-otherwise-valid-content as mutually
    // exclusive). Only the "Duplicate ID" message (checked above) should fire for the second declaration.
    assertFalse(appender.anyMessageContains("must not contain whitespace"));
  }

  @Test
  public void testColumnsAreDefensivelyCopied() {
    // Builder.setColumns()/the private constructor must not alias the caller's own list -- mutating it after
    // build() should not be able to change this VcfMetadata's own column data
    List<String> columns = new ArrayList<>(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER",
        "INFO", "FORMAT", "sample1"));
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
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
  public void testGetSampleNameThrowsForNegativeIndex() {
    // regression test: getSampleName(-1) previously silently returned "FORMAT" (the fixed column at
    // index 9 + (-1) = 8) instead of signaling an invalid index -- the unguarded inverse of
    // getSampleIndex(), which correctly guards idx < 9 on the way out
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT",
            "sample1"))
        .build();
    assertThrows(IndexOutOfBoundsException.class, () -> metadata.getSampleName(-1));
  }

  @Test
  public void testGetSampleNameThrowsForIndexAtOrBeyondNumSamples() {
    // pins the upper-bound behavior too (already correctly thrown by the underlying List.get(), but
    // previously unpinned by any test) -- there is 1 sample (index 0), so index 1 is out of range
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .setColumns(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT",
            "sample1"))
        .build();
    assertThrows(IndexOutOfBoundsException.class, () -> metadata.getSampleName(1));
  }

  @Test
  public void testBuilderDoesNotShareStateAcrossTwoBuildCalls() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(STRICT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null));
    VcfMetadata first = builder.build();
    VcfMetadata second = builder.build();
    // the original form of this test mutated the already-built object directly (first.getInfo().put(...)); that
    // is impossible now that getInfo() is an unmodifiable view, so the surviving observable form of the same
    // non-aliasing property is to add through the builder AFTER both builds and check neither built object saw it
    builder.addInfo("EXTRA", "d", InfoType.Integer, "1", null, null);
    assertEquals(1, first.getInfo().size());
    assertEquals(1, second.getInfo().size());
  }

  @Test
  public void testBuilderMutationAfterBuildDoesNotAffectAlreadyBuiltObject() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(STRICT_CONTEXT, "NS", "d", InfoType.Integer, "1", null, null));
    VcfMetadata metadata = builder.build();
    builder.addInfo("AC", "d", InfoType.Integer, "A", null, null);
    assertEquals(1, metadata.getInfo().size());
  }

  @Test
  public void testAddAltWarnsAndDropsMissingOrBlankId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"Description=\"missing\""}, VcfWarnings.DEFAULT)))
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"ID=", "Description=\"empty\""}, VcfWarnings.DEFAULT)))
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"ID=   ", "Description=\"blank\""}, VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getAlts().isEmpty());
  }

  @Test
  public void testTwoSeparateMissingIdDeclarationsAreEachDroppedIndependently() {
    // regression test for the original finding: two UNRELATED declarations that both happen to lack an ID must
    // not be treated as duplicates of each other -- each is warned about and dropped independently, not silently
    // kept-then-collided because containsKey(null) happened to be true for the second one
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addInfo(new InfoMetadata(LENIENT_CONTEXT, org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"Number=1", "Type=String", "Description=\"zzzalphamarkerzzz\""}, VcfWarnings.DEFAULT)))
          .addInfo(new InfoMetadata(LENIENT_CONTEXT, org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"Number=2", "Type=String", "Description=\"zzzbetamarkerzzz\""}, VcfWarnings.DEFAULT)))
          .build();
      // each declaration's own warning must reference its own content -- proves both were independently warned
      // about and dropped, not that the second was silently treated as a duplicate of the first with no warning
      // of its own. These marker strings are deliberately unlike any word already used in warnIfDuplicate's own
      // message text ("Duplicate ID ... keeping the first declaration") -- a plain "first"/"second" description
      // would coincidentally match that pre-existing phrase and pass even without this fix, for the wrong reason.
      assertTrue(appender.anyMessageContains("zzzalphamarkerzzz"));
      assertTrue(appender.anyMessageContains("zzzbetamarkerzzz"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getInfo().isEmpty());
  }

  @Test
  public void testAddFilterWarnsAndDropsMissingId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"Description=\"missing\""}, VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getFilters().isEmpty());
  }

  @Test
  public void testAddFormatWarnsAndDropsMissingId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addFormat(new FormatMetadata(LENIENT_CONTEXT, org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
              new String[]{"Number=1", "Type=String", "Description=\"missing\""}, VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getFormats().isEmpty());
  }

  @Test
  public void testAddContigWarnsAndDropsMissingId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addContig(new ContigMetadata(LENIENT_CONTEXT, org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
              new String[]{"length=100", "assembly=\"GRCh38\""}, VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getContigs().isEmpty());
  }

  @Test
  public void testAddSampleWarnsAndDropsMissingId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addSample(new IdDescriptionMetadata(LENIENT_CONTEXT, "SAMPLE",
              org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
                  new String[]{"Description=\"missing\""}, VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getSamples().isEmpty());
  }

  @Test
  public void testAddInfoWarnsAndDropsMissingId() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addInfo(new InfoMetadata(LENIENT_CONTEXT, org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
              new String[]{"Number=1", "Type=String", "Description=\"missing\""}, VcfWarnings.DEFAULT)))
          .build();
      assertTrue(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertTrue(metadata.getInfo().isEmpty());
  }

  @Test
  public void testDirectBuilderUseThrowsOnMissingId() {
    // A strict context rejects a missing ID at construction, so the only way a declaration with no ID can now
    // reach the builder's own check is the package-private trustedMutableProperties() bypass, used here to
    // synthesize a state the public API can no longer reach -- exactly what this exercises.
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    IdDescriptionMetadata noId = new IdDescriptionMetadata(STRICT_CONTEXT, "FILTER", "q10", "d");
    noId.trustedMutableProperties().remove(IdMetadata.ID);
    assertThrows(VcfFormatException.class, () -> builder.addFilter(noId));
  }

  @Test
  public void testLenientBuilderWarnsAndDropsMissingId() {
    StringWriter sink = new StringWriter();
    VcfContext ctx = lenientContext(new VcfWarnings(sink));
    VcfMetadata.Builder builder = new VcfMetadata.Builder(ctx).setFileFormat("VCFv4.2");
    IdDescriptionMetadata noId = new IdDescriptionMetadata(ctx, "FILTER",
        InternalUtils.extractProperties(new String[] {"Description=\"d\""}, VcfWarnings.DEFAULT));
    assertDoesNotThrow(() -> builder.addFilter(noId));
    assertTrue(sink.toString().contains("has no ID and cannot be stored"));
    assertTrue(builder.build().getFilters().isEmpty());
  }

  @Test
  public void testBuilderRejectsNullContext() {
    // VcfContext is a caller-configuration object, not VCF content, so a raw NullPointerException is the
    // established convention here -- the same one lenient(VcfWarnings) used to make for its own sink.
    assertThrows(NullPointerException.class, () -> new VcfMetadata.Builder(null));
  }

  @Test
  public void testDirectBuilderUseThrowsOnDuplicateId() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    builder.addFilter("q10", "d");
    IdDescriptionMetadata dup = new IdDescriptionMetadata(STRICT_CONTEXT, "FILTER", "q10", "d2");
    assertThrows(VcfFormatException.class, () -> builder.addFilter(dup));
  }

  @Test
  public void testDirectBuilderUseThrowsOnInvalidAltStructuralCode() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    // "DEL:BOGUS" is deliberately NOT used here (unlike the brief's original literal value) -- AltStructuralVariant
    // treats an unrecognized sub-identifier under a reserved top-level code as a legitimate custom extension (see
    // AltStructuralVariant's own class Javadoc/INS:ME:LINE example and this file's testAddAltDoesNotWarnFor...
    // tests), so it does not throw and this test would fail with "nothing was thrown". "THIS:IS-NOT-VALID" (already
    // used and proven invalid elsewhere in this file, e.g. testAddAltWarnsOnInvalidStructuralVariantCode) has an
    // unrecognized top-level code, which AltStructuralVariant does reject.
    IdDescriptionMetadata badAlt = new IdDescriptionMetadata(STRICT_CONTEXT, "ALT", "THIS:IS-NOT-VALID", "d");
    assertThrows(VcfFormatException.class, () -> builder.addAlt(badAlt));
  }

  @Test
  public void testAddAltAngleBracketRejectionMessageIsTruncated() {
    // regression test for the fifth re-verification round's finding #1: validateAlt()'s "must not contain
    // angle brackets" throw goes through VcfMetadata's own private static warnOrThrow, one of the three
    // shared warn-or-throw implementations that previously embedded a String arg verbatim and unbounded. A
    // huge angle-bracketed ID reaches this message directly from the caller's own input (no CR/LF here --
    // that would be caught earlier, by BaseMetadata.putPropertyRaw's own checkNoLineTerminator call, and is
    // already covered by testCheckNoLineTerminatorMessageIsTruncatedAndEscaped in InternalUtilsTest).
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2");
    String hugeId = "<" + "A".repeat(200) + ">";
    IdDescriptionMetadata badAlt = new IdDescriptionMetadata(STRICT_CONTEXT, "ALT", hugeId, "d");
    VcfFormatException ex = assertThrows(VcfFormatException.class, () -> builder.addAlt(badAlt));
    assertTrue(ex.getMessage().contains("must not contain angle brackets"), ex.getMessage());
    assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
    assertTrue(ex.getMessage().length() < 250, "message was not bounded: " + ex.getMessage().length() + " chars");
  }

  @Test
  public void testDeclarationCannotBeTransferredBetweenDifferentlyConfiguredBuilders() {
    // The two contexts are equal in value -- same declared version, same leniency -- and differ only by
    // identity and sink. That is deliberate: this fails if the implementation compares context values instead
    // of references. getInfo() and its siblings hand out live declarations, so without this check a caller
    // could move one built under A into a builder configured for B and silently route its later validation
    // warnings to A's sink.
    StringWriter sinkA = new StringWriter();
    StringWriter sinkB = new StringWriter();
    VcfContext ctxA = lenientContext(new VcfWarnings(sinkA));
    VcfContext ctxB = lenientContext(new VcfWarnings(sinkB));

    VcfMetadata metadataA = new VcfMetadata.Builder(ctxA)
        .addInfo("AF", "Allele frequency", InfoType.Float, "A", null, null)
        .build();
    InfoMetadata borrowed = metadataA.getInfo().get("AF");

    VcfMetadata.Builder builderB = new VcfMetadata.Builder(ctxB);
    assertThrows(VcfFormatException.class, () -> builderB.addInfo(borrowed));
    // the same declaration is still accepted by a builder sharing its own context
    assertDoesNotThrow(() -> new VcfMetadata.Builder(ctxA).addInfo(borrowed));
  }

  @Test
  public void testDeclarationFromAnotherContextCannotEnterMetadataByAnyRoute() {
    // The two contexts are equal in value -- same declared version, same leniency -- and differ only by
    // identity, so this fails if an implementation compares context values instead of references.
    VcfContext ctxA = lenientContext(new VcfWarnings(new StringWriter()));
    VcfContext ctxB = lenientContext(new VcfWarnings(new StringWriter()));
    VcfMetadata metadataA = new VcfMetadata.Builder(ctxA)
        .addInfo("AF", "Allele frequency", InfoType.Float, "A", null, null).build();
    InfoMetadata borrowed = metadataA.getInfo().get("AF");
    VcfMetadata metadataB = new VcfMetadata.Builder(ctxB).build();

    // route 1: the builder overload
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(ctxB).addInfo(borrowed));
    // route 2: the instance mutator -- deleted when the metadata layer was frozen, so this must not even
    //          compile. Kept as a comment rather than dropped, as the reason the route is safe.
    // metadataB.addInfo(borrowed);
    // route 3: the live collection view -- now an unmodifiable view
    assertThrows(UnsupportedOperationException.class, () -> metadataB.getInfo().put("AF", borrowed));
  }

  @Test
  public void testLeniencyComesFromContextNotOverloadShape() {
    // All four combinations: the same invalid content, through both the typed and the Map overload, under both
    // context modes. Before VcfContext, strictness was bound to WHICH constructor you called -- the typed one
    // always threw and the Map one always warned -- so a typed overload stayed strict under a lenient builder
    // and a Map overload stayed lenient under a strict one.
    String badNumber = "banana";   // fails NUMBER_PATTERN identically either way

    VcfContext strict = VcfContext.strict("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(strict)
        .addInfo("AF", "d", InfoType.Float, badNumber, null, null));
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(strict)
        .addInfo(InternalUtils.extractProperties(
            new String[] {"ID=AF", "Number=" + badNumber, "Type=Float", "Description=\"d\""},
            VcfWarnings.DEFAULT)));

    StringWriter typedSink = new StringWriter();
    new VcfMetadata.Builder(lenientContext(new VcfWarnings(typedSink)))
        .addInfo("AF", "d", InfoType.Float, badNumber, null, null);
    assertTrue(typedSink.toString().contains(badNumber), typedSink::toString);

    StringWriter mapSink = new StringWriter();
    new VcfMetadata.Builder(lenientContext(new VcfWarnings(mapSink)))
        .addInfo(InternalUtils.extractProperties(
            new String[] {"ID=AF", "Number=" + badNumber, "Type=Float", "Description=\"d\""},
            VcfWarnings.DEFAULT));
    assertTrue(mapSink.toString().contains(badNumber), mapSink::toString);
  }

  @Test
  public void testLeniencyComesFromContextNotOverloadShapeForFormatAndContig() {
    // the same four-combination check for the other two declaration types with a typed/Map pair
    VcfContext strict = VcfContext.strict("VCFv4.2");
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(strict)
        .addFormat("GT2", "d", "banana", FormatType.String));
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(strict)
        .addFormat(InternalUtils.extractProperties(
            new String[] {"ID=GT2", "Number=banana", "Type=String", "Description=\"d\""},
            VcfWarnings.DEFAULT)));
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(strict)
        .addContig("1", null, null, null, null, null, "not a url"));
    assertThrows(VcfFormatException.class, () -> new VcfMetadata.Builder(strict)
        .addContig(InternalUtils.extractProperties(new String[] {"ID=1", "URL=not a url"},
            VcfWarnings.DEFAULT)));

    StringWriter sink = new StringWriter();
    VcfContext lenient = lenientContext(new VcfWarnings(sink));
    assertDoesNotThrow(() -> new VcfMetadata.Builder(lenient)
        .addFormat("GT2", "d", "banana", FormatType.String));
    assertDoesNotThrow(() -> new VcfMetadata.Builder(lenient)
        .addFormat(InternalUtils.extractProperties(
            new String[] {"ID=GT2", "Number=banana", "Type=String", "Description=\"d\""},
            VcfWarnings.DEFAULT)));
    assertDoesNotThrow(() -> new VcfMetadata.Builder(lenient)
        .addContig("1", null, null, null, null, null, "not a url"));
    assertDoesNotThrow(() -> new VcfMetadata.Builder(lenient)
        .addContig(InternalUtils.extractProperties(new String[] {"ID=1", "URL=not a url"},
            VcfWarnings.DEFAULT)));
    assertTrue(sink.toString().contains("banana"), sink::toString);
    assertTrue(sink.toString().contains("is malformed"), sink::toString);
  }

  @Test
  public void testBuilderFileFormatMustAgreeWithItsContext() {
    // a metadata object whose ##fileformat contradicts its own context is internally inconsistent
    assertThrows(VcfFormatException.class,
        () -> new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.3"));
    // ...but VCFv4.02 and VCFv4.2 are the same version (compared canonicalized), and the caller's own
    // spelling is what gets stored
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.02").build();
    assertEquals("VCFv4.02", metadata.getFileFormat());
  }

  @Test
  public void testBuiltMetadataCarriesItsBuildersContext() {
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    assertSame(ctx, new VcfMetadata.Builder(ctx).build().getContext());
  }

  @Test
  void testMetadataToBuilderPreservesContextIdentityWhileCopyingDeclarations() {
    // toBuilder() re-adds existing declarations through the package-private object-taking overloads, so it
    // must carry the SAME context instance -- otherwise VcfContext.newRecord's identity assertion would reject
    // its own copy. Reference identity, not equality: two equal-valued contexts are still two operations.
    VcfContext ctx = VcfContext.strict("VCFv4.2");
    VcfMetadata original = new VcfMetadata.Builder(ctx)
        .addInfo("AF", "Allele frequency", InfoType.Float, "A", null, null)
        .build();
    VcfMetadata copy = original.toBuilder().build();
    assertSame(ctx, copy.getContext());
    assertSame(ctx, copy.getInfo().get("AF").getContext());
    assertSame(original.getInfo().get("AF").getContext(), copy.getInfo().get("AF").getContext());
  }

  @Test
  void testToBuilderCopiesEveryDeclarationTypeFileFormatColumnsAndRawProperties() {
    java.util.Map<String, String> pedigreeProps = new java.util.LinkedHashMap<>();
    pedigreeProps.put("Name_1", "child");
    pedigreeProps.put("Name_2", "father");
    VcfMetadata original = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .addInfo("NS", "Number of samples", InfoType.Integer, "1", null, null)
        .addFilter("q10", "quality below 10")
        .addFormat("GT", "genotype", "1", FormatType.String)
        .addContig("1", 1000L, null, null, null, null, null)
        .addAlt("DEL", "deletion")
        .addSample("sample1", "desc")
        .addPedigree(pedigreeProps)
        .setColumns(Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO"))
        .addRawProperty("source", "myImputationProgram")
        .build();
    VcfMetadata copy = original.toBuilder().build();
    assertEquals(original.getFileFormat(), copy.getFileFormat());
    assertEquals(original.getInfo().keySet(), copy.getInfo().keySet());
    assertEquals(original.getFilters().keySet(), copy.getFilters().keySet());
    assertEquals(original.getFormats().keySet(), copy.getFormats().keySet());
    assertEquals(original.getContigs().keySet(), copy.getContigs().keySet());
    assertEquals(original.getAlts().keySet(), copy.getAlts().keySet());
    assertEquals(original.getSamples().keySet(), copy.getSamples().keySet());
    assertEquals(original.getPedigrees().size(), copy.getPedigrees().size());
    assertEquals(original.getNumColumns(), copy.getNumColumns());
    assertEquals(original.getRawValuesOfProperty("source"), copy.getRawValuesOfProperty("source"));
    assertSame(original.getContext(), copy.getContext());
  }

  @Test
  void testToBuilderCopyDoesNotShareDeclarationMapsWithItsSource() {
    // the original form of this test mutated the source through its own instance mutator; those are gone, so
    // the copy's builder is now the direction a declaration can be added -- the source must not see it
    VcfMetadata original = new VcfMetadata.Builder(STRICT_CONTEXT)
        .setFileFormat("VCFv4.2")
        .addInfo("NS", "Number of samples", InfoType.Integer, "1", null, null)
        .build();
    VcfMetadata copy = original.toBuilder()
        .addInfo("DP", "Depth", InfoType.Integer, "1", null, null)
        .build();
    assertTrue(copy.getInfo().containsKey("DP"));
    assertFalse(original.getInfo().containsKey("DP"));
  }

  @Test
  void testToBuilderBuildEmitsNoDiagnosticsForAnAltThatAlreadyWarnedAtConstruction() {
    // declaring ALT with a syntactically invalid Number warns once (validateAlt); copying that already-warned,
    // unchanged declaration via toBuilder() must not re-emit the identical diagnostic a second time
    StringWriter sink = new StringWriter();
    java.util.Map<String, String> altProps = new java.util.LinkedHashMap<>();
    altProps.put("ID", "DEL");
    altProps.put("Description", "Deletion");
    altProps.put("Number", "bogus");
    VcfMetadata original = new VcfMetadata.Builder(lenientContext(new VcfWarnings(sink)))
        .setFileFormat("VCFv4.2")
        .addAlt(altProps)
        .build();
    assertTrue(sink.toString().contains("is not a valid VCF number"), sink::toString);
    sink.getBuffer().setLength(0);
    VcfMetadata copy = original.toBuilder().build();
    assertEquals("", sink.toString(), "copying alone must not re-emit a diagnostic the source already emitted");
    assertEquals("bogus", copy.getAlt("DEL").getPropertyRaw("Number"));
  }

  @Test
  public void testEveryCollectionGetterRejectsMutation() throws Exception {
    // Freezing the metadata layer means no getter may hand out a mutation route into this object.
    //
    // Driven off reflection rather than a hand-written list on purpose: a hardcoded list is complete only on
    // the day it is written, and this test's whole job is to fail when someone adds a THIRTEENTH
    // collection-returning getter without an unmodifiable view. Enumerating the class's own public
    // Collection/Map/Multimap-returning methods means a new one is covered the moment it appears.
    java.util.Map<String, String> pedigreeProps = new java.util.LinkedHashMap<>();
    pedigreeProps.put("ID", "P1");
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo("AF", "d", InfoType.Float, "A", null, null)
        .addAlt("DEL", "d")
        .addFilter("q10", "d")
        .addFormat("GT", "d", "1", FormatType.String)
        .addContig("1", 100L, null, null, null, null, null)
        .addSample("S1", "d")
        .addPedigree(pedigreeProps)
        .addRawProperty("assembly", "http://example.org/a")
        .addRawProperty("pedigreeDB", "http://example.org/p")
        .addRawProperty("reference", "ref")
        .build();

    java.util.List<String> checked = new ArrayList<>();
    for (java.lang.reflect.Method method : VcfMetadata.class.getDeclaredMethods()) {
      if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
          || java.lang.reflect.Modifier.isStatic(method.getModifiers())
          || method.isSynthetic()) {
        continue;
      }
      Class<?> returnType = method.getReturnType();
      if (!java.util.Collection.class.isAssignableFrom(returnType)
          && !java.util.Map.class.isAssignableFrom(returnType)
          && !com.google.common.collect.Multimap.class.isAssignableFrom(returnType)) {
        continue;
      }
      // zero-arg getters, plus the one String-keyed lookup that returns a collection
      // (getRawValuesOfProperty); anything else would need a value this loop can't invent
      Object[] args;
      if (method.getParameterCount() == 0) {
        args = new Object[0];
      } else if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
        args = new Object[] {"reference"};
      } else {
        fail("Unhandled collection-returning getter shape: " + method
            + " -- extend this test rather than skipping it, or the getter goes uncovered");
        continue;
      }
      Object returned = method.invoke(metadata, args);
      assertNotNull(returned, method.getName() + " returned null");
      Runnable mutate;
      if (returned instanceof java.util.Collection<?> collection) {
        mutate = collection::clear;
      } else if (returned instanceof java.util.Map<?, ?> map) {
        mutate = map::clear;
      } else {
        mutate = ((com.google.common.collect.Multimap<?, ?>) returned)::clear;
      }
      assertThrows(UnsupportedOperationException.class, mutate::run,
          () -> method.getName() + "() handed back a mutable collection -- every VcfMetadata collection getter "
              + "must return an unmodifiable view");
      checked.add(method.getName());
    }
    // guards against the loop silently matching nothing (e.g. after a refactor that moves these getters)
    assertEquals(13, checked.size(), () -> "expected 13 collection-returning getters, found " + checked);

    // an unmodifiable Map still hands out Map.Entry objects; setValue() must not reach through either
    assertThrows(UnsupportedOperationException.class,
        () -> metadata.getInfo().entrySet().iterator().next().setValue(null));
    // and the same holds one level down, for a declaration's own properties
    assertThrows(UnsupportedOperationException.class,
        () -> metadata.getInfo().get("AF").getPropertiesRaw().put("Source", "x"));
    assertThrows(UnsupportedOperationException.class,
        () -> metadata.getInfo().get("AF").getPropertyKeys().remove("ID"));
  }

  @Test
  public void testReplaceAltReplacesExistingDeclarationUnderLenient() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "Deletion"))
          .replaceAlt("DEL", "Replaced deletion")
          .build();
      // the bug this task fixes: toBuilder().addInfo(existingId, ...) used to warn "Duplicate ID" and keep the
      // first (unreplaced) declaration -- replaceXxx must not go through that path at all
      assertFalse(appender.anyMessageContains("Duplicate ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals(1, metadata.getAlts().size());
    assertEquals("Replaced deletion", metadata.getAlts().get("DEL").getDescription());
  }

  @Test
  public void testReplaceAltReplacesExistingDeclarationUnderStrict() {
    VcfMetadata metadata = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addAlt(new IdDescriptionMetadata(STRICT_CONTEXT, "ALT", "DEL", "Deletion"))
        .replaceAlt("DEL", "Replaced deletion")
        .build();
    assertEquals(1, metadata.getAlts().size());
    assertEquals("Replaced deletion", metadata.getAlts().get("DEL").getDescription());
  }

  @Test
  public void testReplaceAltRawMapReplacesExistingDeclaration() {
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    VcfMetadata metadata;
    try {
      metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
          .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "Deletion"))
          .replaceAlt(org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
              new String[]{"ID=DEL", "Number=1", "Type=String", "Description=\"Replaced deletion\""},
              VcfWarnings.DEFAULT))
          .build();
      assertFalse(appender.anyMessageContains("Duplicate ID"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    assertEquals("Replaced deletion", metadata.getAlts().get("DEL").getDescription());
    assertEquals("1", metadata.getAlts().get("DEL").getPropertyRaw("Number"));
  }

  @Test
  public void testReplaceInfoReplacesExistingDeclaration() {
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "DP", "First depth", InfoType.Integer, "1", null, null))
        .replaceInfo("DP", "Replaced depth", InfoType.Integer, "1", null, null)
        .build();
    assertEquals(1, metadata.getInfo().size());
    assertEquals("Replaced depth", metadata.getInfo().get("DP").getDescription());
  }

  @Test
  public void testReplaceFilterReplacesExistingDeclaration() {
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER", "q10", "First"))
        .replaceFilter("q10", "Replaced")
        .build();
    assertEquals(1, metadata.getFilters().size());
    assertEquals("Replaced", metadata.getFilters().get("q10").getDescription());
  }

  @Test
  public void testReplaceFormatReplacesExistingDeclaration() {
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "First genotype", "1", FormatType.String))
        .replaceFormat("GT", "Replaced genotype", "1", FormatType.String)
        .build();
    assertEquals(1, metadata.getFormats().size());
    assertEquals("Replaced genotype", metadata.getFormats().get("GT").getDescription());
  }

  @Test
  public void testReplaceContigReplacesExistingDeclaration() {
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addContig(new ContigMetadata(LENIENT_CONTEXT, "1", 100L, "GRCh38", null, null, null, null))
        .replaceContig("1", 200L, "GRCh37", null, null, null, null)
        .build();
    assertEquals(1, metadata.getContigs().size());
    assertEquals(200L, metadata.getContigs().get("1").getLength());
  }

  @Test
  public void testReplaceSampleReplacesExistingDeclaration() {
    VcfMetadata metadata = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addSample(new IdDescriptionMetadata(LENIENT_CONTEXT, "SAMPLE", "S1", "First"))
        .replaceSample("S1", "Replaced")
        .build();
    assertEquals(1, metadata.getSamples().size());
    assertEquals("Replaced", metadata.getSamples().get("S1").getDescription());
  }

  @Test
  public void testReplaceAltMissingIdThrowsUnderLenientTypedOverload() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "Deletion"));
    // "INS" was never added -- a typo'd/nonexistent target must throw even under a lenient context, never
    // silently append a new declaration
    assertThrows(VcfFormatException.class, () -> builder.replaceAlt("INS", "Insertion"));
    assertEquals(1, builder.build().getAlts().size());
  }

  @Test
  public void testReplaceAltMissingIdThrowsUnderStrictTypedOverload() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addAlt(new IdDescriptionMetadata(STRICT_CONTEXT, "ALT", "DEL", "Deletion"));
    assertThrows(VcfFormatException.class, () -> builder.replaceAlt("INS", "Insertion"));
  }

  @Test
  public void testReplaceAltMissingIdThrowsUnderLenientMapOverload() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "Deletion"));
    assertThrows(VcfFormatException.class, () -> builder.replaceAlt(
        org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
            new String[]{"ID=INS", "Description=\"Insertion\""}, VcfWarnings.DEFAULT)));
  }

  @Test
  public void testReplaceAltMissingIdThrowsUnderStrictMapOverload() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addAlt(new IdDescriptionMetadata(STRICT_CONTEXT, "ALT", "DEL", "Deletion"));
    assertThrows(VcfFormatException.class, () -> builder.replaceAlt(
        org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
            new String[]{"ID=INS", "Description=\"Insertion\""}, VcfWarnings.DEFAULT)));
  }

  @Test
  public void testReplaceAltNullIdThrowsUnderLenient() {
    // reaches requirePresentForReplace's id == null branch directly: under a lenient context, IdDescriptionMetadata's
    // typed constructor warns (rather than throws) on a null id, so md.getId() is null by the time replaceAlt(md)
    // runs its own check
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addAlt(new IdDescriptionMetadata(LENIENT_CONTEXT, "ALT", "DEL", "Deletion"));
    assertThrows(VcfFormatException.class, () -> builder.replaceAlt(null, "Insertion"));
  }

  @Test
  public void testReplaceInfoMissingIdThrows() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addInfo(new InfoMetadata(LENIENT_CONTEXT, "DP", "First depth", InfoType.Integer, "1", null, null));
    assertThrows(VcfFormatException.class,
        () -> builder.replaceInfo("AF", "Allele frequency", InfoType.Float, "1", null, null));
  }

  @Test
  public void testReplaceFilterMissingIdThrows() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFilter(new IdDescriptionMetadata(LENIENT_CONTEXT, "FILTER", "q10", "First"));
    assertThrows(VcfFormatException.class, () -> builder.replaceFilter("s50", "Replaced"));
  }

  @Test
  public void testReplaceFormatMissingIdThrows() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addFormat(new FormatMetadata(LENIENT_CONTEXT, "GT", "First genotype", "1", FormatType.String));
    assertThrows(VcfFormatException.class,
        () -> builder.replaceFormat("DP", "Read depth", "1", FormatType.Integer));
  }

  @Test
  public void testReplaceContigMissingIdThrows() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addContig(new ContigMetadata(LENIENT_CONTEXT, "1", 100L, "GRCh38", null, null, null, null));
    assertThrows(VcfFormatException.class,
        () -> builder.replaceContig("2", 200L, "GRCh38", null, null, null, null));
  }

  @Test
  public void testReplaceSampleMissingIdThrows() {
    VcfMetadata.Builder builder = new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2")
        .addSample(new IdDescriptionMetadata(LENIENT_CONTEXT, "SAMPLE", "S1", "First"));
    assertThrows(VcfFormatException.class, () -> builder.replaceSample("S2", "Replaced"));
  }

  @Test
  public void testReplaceAltRawMapAtomicOnRejectedContent() {
    // Step 2a regression: neither "obvious" implementation is atomic. remove-then-addXxx loses the valid
    // original when validateAlt throws; put-then-validate leaves the invalid declaration stored (build() does not
    // re-run add-time validation). This seeds a valid ALT=DEL, attempts a same-ID replacement carrying an
    // invalid Number, and confirms the original DEL declaration survives completely unmodified -- which also
    // proves replaceAlt retained addAlt's validateAlt call rather than degenerating into an unchecked map put.
    VcfMetadata.Builder builder = new VcfMetadata.Builder(STRICT_CONTEXT).setFileFormat("VCFv4.2")
        .addAlt(new IdDescriptionMetadata(STRICT_CONTEXT, "ALT", "DEL", "Deletion"));
    java.util.Map<String, String> badReplacement = org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
        new String[]{"ID=DEL", "Number=B", "Type=String", "Description=\"Replaced deletion\""}, VcfWarnings.DEFAULT);
    assertThrows(VcfFormatException.class, () -> builder.replaceAlt(badReplacement));
    VcfMetadata metadata = builder.build();
    assertEquals(1, metadata.getAlts().size());
    assertEquals("Deletion", metadata.getAlts().get("DEL").getDescription());
    assertNull(metadata.getAlts().get("DEL").getPropertyRaw("Number"));
  }

}

package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;


/**
 * Tests {@link MetaMetadata}, reached through {@link VcfMetadata.Builder}.
 */
public class MetaMetadataTest {

  private static VcfContext ctx() {
    return TestUtils.STRICT_CONTEXT;
  }

  private static VcfContext lenientCtx() {
    return TestUtils.LENIENT_CONTEXT;
  }

  private static VcfContext lenientCtx(StringWriter sink) {
    return TestUtils.lenientContext(new VcfWarnings(sink));
  }

  private static List<String> columns() {
    return Arrays.asList("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO");
  }

  /**
   * An order-preserving map built in canonical attribute order (ID, Number, Type, Description, then any
   * extras) -- {@code BaseMetadata}'s own constructor javadoc warns that {@code Map.of(...)}'s unspecified
   * iteration order "risks a meaningless (nondeterministic or simply wrong) attribute-order warning," so tests
   * exercising a declaration with more than one canonical attribute must use this instead, matching the shape
   * {@code VcfParser}'s real parsing path always constructs.
   */
  private static Map<String, String> orderedProperties(String... keysAndValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  @Test
  void aMetaDeclarationExposesItsProperties() {
    VcfMetadata md = new VcfMetadata.Builder(ctx())
        .addMeta(orderedProperties("ID", "Assay", "Number", ".", "Type", "String",
            "Values", "[WholeGenome, Exome]"))
        .setColumns(columns())
        .build();
    assertEquals("[WholeGenome, Exome]", md.getMetas().get("Assay").getPropertyRaw("Values"));
  }

  @Test
  void aMetaDeclarationRequiresAnId() {
    // Deliberate divergence: bcftools accepts an ID-less ##META silently (measured, bcftools 1.23.1).
    // VCFv4.3.tex:121 -- "All structured lines require an ID which must be unique within their type."
    assertThrows(VcfFormatException.class,
        () -> new VcfMetadata.Builder(ctx()).addMeta(Map.of("Type", "String")));
  }

  @Test
  void aDuplicateMetaIdWarnsAndKeepsTheFirst() {
    VcfMetadata md = new VcfMetadata.Builder(lenientCtx())
        .addMeta(orderedProperties("ID", "A", "Type", "String"))
        .addMeta(orderedProperties("ID", "A", "Type", "Integer"))
        .setColumns(columns())
        .build();
    assertEquals(1, md.getMetas().size());
    assertEquals("String", md.getMetas().get("A").getPropertyRaw("Type"));
  }

  @Test
  void metaDeclarationsSurviveAToBuilderRoundTrip() {
    VcfMetadata original = new VcfMetadata.Builder(ctx())
        .addMeta(orderedProperties("ID", "Assay", "Type", "String", "Values", "[WholeGenome, Exome]"))
        .addMeta(orderedProperties("ID", "Tissue", "Type", "String"))
        .setColumns(columns())
        .build();
    VcfMetadata rebuilt = original.toBuilder().build();
    assertEquals(List.of("Assay", "Tissue"), List.copyOf(rebuilt.getMetas().keySet()));   // order preserved
    assertEquals("[WholeGenome, Exome]", rebuilt.getMetas().get("Assay").getPropertyRaw("Values"));
    assertEquals("String", rebuilt.getMetas().get("Tissue").getPropertyRaw("Type"));
  }

  @Test
  void getMetasReturnsAnUnmodifiableView() {
    VcfMetadata md = new VcfMetadata.Builder(ctx())
        .addMeta(orderedProperties("ID", "Assay", "Type", "String")).setColumns(columns()).build();
    assertThrows(UnsupportedOperationException.class, () -> md.getMetas().clear());
  }

  @Test
  void aMetaDeclarationIsNotAttributeOrderChecked() throws Exception {
    // VCFv4.3.tex:273 verbatim -- the spec's own worked example uses ID,Type,Number. No spec version
    // REQUIRES an order (4.3 is silent; 4.4/4.5 say implementations "must not rely on the order"), and
    // bcftools 1.23.1 is silent on every ordering. See VCF_SPEC_AMBIGUITIES.md's "##META attribute order"
    // entry -- attribute-order checking was removed from every keyed declaration type entirely, not just
    // ##META, so there is no longer any hook or code path left to check here. Covers three routes: direct
    // construction (via addMeta), a direct strict-context validate() call, and parsing metadata_meta.vcf,
    // the fixture that copies the spec's own worked example verbatim.
    Map<String, String> outOfOrder = orderedProperties("ID", "Assay", "Type", "String", "Number", ".",
        "Values", "[WholeGenome, Exome]");

    StringWriter sink = new StringWriter();
    new VcfMetadata.Builder(lenientCtx(sink))
        .addMeta(outOfOrder)
        .setColumns(columns())
        .build();
    assertThat(sink.toString(), not(containsString("out of canonical order")));

    MetaMetadata strict = new MetaMetadata(ctx(), outOfOrder);
    assertDoesNotThrow(strict::validate);

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try (BufferedReader reader =
             Files.newBufferedReader(org.pharmgkb.common.util.PathUtils.getPathToResource("/metadata_meta.vcf"));
         VcfParser parser =
             new VcfParser.Builder().fromReader(reader).parseWith((metadata, record) -> { }).build()) {
      parser.parseMetadata();
      assertFalse(appender.anyMessageContains("out of canonical order"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

}

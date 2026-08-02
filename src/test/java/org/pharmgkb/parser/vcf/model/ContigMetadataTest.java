package org.pharmgkb.parser.vcf.model;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ContigMetadata}.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadataTest {

  @Test
  public void testNoId() {
    // IdMetadata.init()'s warnIfMissing() should warn when ID is absent, and (regression) attribute the
    // warning to ContigMetadata by embedding its label ("CONTIG"), set via the required label constructor
    // parameter, in the message text, since every warning now routes through the single shared VcfWarnings logger
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.URL, "one");
      new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertTrue(appender.hasWarnOrHigher());
      assertTrue(appender.anyMessageContains("CONTIG"));
      assertTrue(appender.anyMessageContains("required metadata property \"ID\" is missing"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testExtraPropertyRetainedWithoutWarning() {
    // VCFv4.2.tex line 123's "extra fields can be included after the default fields" sentence doesn't name
    // ##contig directly, but its own worked example at line 140 ends in "..." (more tags may follow) --
    // matching InfoMetadata/FormatMetadata/IdDescriptionMetadata's identical no-warn-on-extras treatment.
    // Confirmed against hts-specs' own passed_meta_contig.vcf fixture, which declares an unmodeled
    // "accession" attribute and is expected to parse without warning (see testAcceptsHtsSpecsExtraAttributes
    // below), and bcftools 1.23.1, which silently round-trips a custom contig attribute with zero warning.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "id");
      map.put(ContigMetadata.ASSEMBLY, "assembly");
      map.put(ContigMetadata.LENGTH, "23");
      map.put("test", "one");
      ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
      assertEquals("one", contig.getPropertyRaw("test"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testAcceptsHtsSpecsExtraAttributes() {
    // reproduces hts-specs' own test/vcf/4.2/passed/passed_meta_contig.vcf fixture's
    // "##contig=<ID=contig_accession,species=\"Homo sapiens\",accession=GCA_000001405.1>" line: a passed/
    // fixture, i.e. expected to parse without incident. "accession" is not one of ContigMetadata's own
    // modeled attributes.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "contig_accession");
      map.put(ContigMetadata.SPECIES, "\"Homo sapiens\"");
      map.put("accession", "GCA_000001405.1");
      ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
      assertEquals("GCA_000001405.1", contig.getPropertyRaw("accession"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testIdWithWhitespaceWarns() {
    // regression test: a contig ID containing whitespace was previously silently accepted -- reproduces
    // test/vcf/{4.1,4.2,4.3}/failed/failed_meta_contig_001.vcf's ##CauseOfFailure=contig ID contains a whitespace
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "1 A");
      new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertTrue(appender.anyMessageContains("must not contain whitespace"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testBadLength() {
    // ContigMetadata.init()'s catch (NumberFormatException) block should warn on a non-numeric length
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "id");
      map.put(ContigMetadata.ASSEMBLY, "assembly");
      map.put(ContigMetadata.LENGTH, "234asdgasdgasdg");
      map.put(ContigMetadata.URL, "url");
      new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertTrue(appender.hasWarnOrHigher());
      assertTrue(appender.anyMessageContains("is not a number"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testGetLength() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "23");
    map.put(ContigMetadata.URL, "url");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertEquals(23, contig.getLength());
  }

  @Test
  public void testGetLengthAcceptsQuotedNumber() {
    // init()'s own construction-time validation already unquotes before checking (so a quoted length
    // like "249250621" parses fine and warns nothing), but getLength() used to parse the raw, still-quoted
    // value and threw VcfFormatException on the exact same data init() had silently accepted -- getLength()
    // must agree with init() and use the unquoted value too
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "\"23\"");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertEquals(23, contig.getLength());
  }

  @Test
  public void testNullUrlNotSerialized() {
    // a null URL argument must not be stored (it would otherwise serialize as the literal "URL=null")
    ContigMetadata contig = new ContigMetadata("chr1", 10L, "asm", null, null, null, null);
    assertNull(contig.getUrl());
    assertFalse(contig.getPropertyKeys().contains(ContigMetadata.URL));
  }

  @Test
  public void testGetUrl() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "23");
    map.put(ContigMetadata.URL, "url");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertEquals("url", contig.getUrl());
  }

  @Test
  public void testGetAssembly() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "GRCh38");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertEquals("GRCh38", contig.getAssembly());
  }

  @Test
  public void testGetMd5() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.MD5, "d7d0c95cd23db914d68f9ce9e2d0c7c8");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertEquals("d7d0c95cd23db914d68f9ce9e2d0c7c8", contig.getMd5());
  }

  @Test
  public void testGetTaxonomy() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.TAXONOMY, "9606");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertEquals("9606", contig.getTaxonomy());
  }

  @Test
  public void testGetSpeciesUnquotes() {
    // regression-shaped test: getLength()'s own quoted-value mismatch (fixed in findings21.md Batch 21.3) was
    // hidden by the same kind of gap this closes -- a getter that unquotes but was never actually exercised
    // against a quoted value
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.SPECIES, "\"Homo sapiens\"");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertEquals("Homo sapiens", contig.getSpecies());
  }

  @Test
  public void testTypedConstructorAssemblyMd5TaxonomySpeciesRoundTrip() {
    // the typed constructor auto-quotes species via putAndQuoteProperty(); getSpecies() must unquote it back
    // to the original value, not just avoid throwing
    ContigMetadata contig = new ContigMetadata("chr1", 100L, "GRCh38",
        "d7d0c95cd23db914d68f9ce9e2d0c7c8", "Homo sapiens", "9606", "http://example.com");
    assertEquals("GRCh38", contig.getAssembly());
    assertEquals("d7d0c95cd23db914d68f9ce9e2d0c7c8", contig.getMd5());
    assertEquals("9606", contig.getTaxonomy());
    assertEquals("Homo sapiens", contig.getSpecies());
  }

  @Test
  public void testTypedConstructorAcceptsNullAssembly() {
    // assembly is spec-optional (VCFv4.2.tex line 121: "all tags are optional"); a caller building a minimal
    // declaration must be able to omit it via the typed constructor, not only the Map-based one. A null
    // argument must not be stored (it would otherwise serialize as the literal "assembly=null").
    ContigMetadata contig = new ContigMetadata("chr1", 100L, null, null, null, null, null);
    assertNull(contig.getAssembly());
    assertFalse(contig.getPropertyKeys().contains(ContigMetadata.ASSEMBLY));
    assertEquals(100L, contig.getLength());
  }

  @Test
  public void testTypedConstructorAcceptsNullLength() {
    // length is spec-optional too (VCFv4.2.tex line 138: "highly recommended (but not required)"); a null
    // argument must not be stored (it would otherwise serialize as the literal "length=null"), and
    // getLength() must still throw VcfFormatException afterward, matching its own documented contract for an
    // absent length regardless of how the object was constructed.
    ContigMetadata contig = new ContigMetadata("chr1", null, "GRCh38", null, null, null, null);
    assertFalse(contig.getPropertyKeys().contains(ContigMetadata.LENGTH));
    assertThrows(VcfFormatException.class, contig::getLength);
    assertEquals("GRCh38", contig.getAssembly());
  }

  @Test
  public void testTypedConstructorAcceptsMinimalSpecCompliantContig() {
    // VCFv4.2.tex line 121 ("all tags are optional") and line 138 ("highly recommended (but not required)"
    // for length specifically) establish that ID alone is a spec-compliant ##contig declaration; the spec's
    // own worked examples (line 33, line 140) all include additional tags, but the prose does not require
    // them. Before this fix, only the Map-based constructor could represent an ID-only declaration; the
    // typed constructor required both assembly and length.
    ContigMetadata contig = new ContigMetadata("ctg1", null, null, null, null, null, null);
    assertEquals("ctg1", contig.getId());
    assertNull(contig.getAssembly());
    assertThrows(VcfFormatException.class, contig::getLength);
  }

  @Test
  public void testGetLengthMissingThrowsVcfFormatException() {
    // length is not required by the spec (absence doesn't even warn at construction -- see
    // testMissingAssemblyAndLengthDoesNotWarn), but getLength() previously let a raw, confusing
    // NumberFormatException("Cannot parse null string") leak out instead of VcfFormatException, unlike every
    // other typed getter in the codebase (getQuality, convertProperty, parseAlleleIndex, etc.)
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertThrows(VcfFormatException.class, contig::getLength);
  }

  @Test
  public void testGetLengthNotANumberThrowsVcfFormatException() {
    Map<String, String> map = new HashMap<>();
    map.put(ContigMetadata.ID, "id");
    map.put(ContigMetadata.ASSEMBLY, "assembly");
    map.put(ContigMetadata.LENGTH, "not-a-number");
    ContigMetadata contig = new ContigMetadata(map, VcfWarnings.DEFAULT);
    assertThrows(VcfFormatException.class, contig::getLength);
  }

  @Test
  public void testMissingAssemblyAndLengthDoesNotWarn() {
    // VCFv4.2.tex line 121: "highly recommended (but not required) that the header include tags describing
    // the reference and contigs... all tags are optional." Line 138 repeats "highly recommended (but not
    // required)" specifically for ##contig. Neither assembly nor length is spec-required, so their absence
    // must not warn.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "id");
      new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertFalse(appender.hasWarnOrHigher());
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testUrlWithInvalidUriSyntaxThrowsViaTypedConstructor() {
    // regression test: URI.create(url) is stricter than the old new URL(url) -- a pipe character is invalid URI
    // syntax and now throws IllegalArgumentException, which the typed constructor wraps and rethrows as
    // VcfFormatException instead of warning (previously silently accepted with no warning, then briefly a warn
    // once URI.create was introduced) -- exercises one half of ContigMetadata's multi-catch
    assertThrows(VcfFormatException.class,
        () -> new ContigMetadata("chr1", 100L, "GRCh38", null, null, null, "http://example.com/a|b"));
  }

  @Test
  public void testUrlWithUnregisteredProtocolThrowsViaTypedConstructor() {
    // "foo://bar" IS syntactically valid with a real scheme ("foo") -- URI.create(url).getScheme() returns "foo",
    // not null. The failure is that "foo" is an unregistered/unknown protocol, which URI.create(url).toURL()
    // rejects with MalformedURLException -- exercising the other half of ContigMetadata's multi-catch
    assertThrows(VcfFormatException.class,
        () -> new ContigMetadata("chr1", 100L, "GRCh38", null, null, null, "foo://bar"));
  }

  @Test
  public void testUrlWithInvalidUriSyntaxWarnsViaMapConstructor() {
    // regression test: the Map-based constructor is the one VcfParser actually uses for real ##contig lines --
    // it must warn on the same malformed URL that the typed constructor now rejects with a throw instead
    // (testUrlWithInvalidUriSyntaxThrowsViaTypedConstructor)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "chr1");
      map.put(ContigMetadata.URL, "http://example.com/a|b");
      new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertTrue(appender.anyMessageContains("is malformed"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testUrlWithUnregisteredProtocolWarnsViaMapConstructor() {
    // regression test: the Map-based constructor is the one VcfParser actually uses for real ##contig lines --
    // it must warn on the same malformed URL that the typed constructor now rejects with a throw instead
    // (testUrlWithUnregisteredProtocolThrowsViaTypedConstructor)
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      Map<String, String> map = new HashMap<>();
      map.put(ContigMetadata.ID, "chr1");
      map.put(ContigMetadata.URL, "foo://bar");
      new ContigMetadata(map, VcfWarnings.DEFAULT);
      assertTrue(appender.anyMessageContains("is malformed"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testDirectValidateCatchesOutOfCanonicalOrderAttributes() {
    // regression test: IdMetadata.validate() now calls checkAttributeOrder() (previously only IdDescriptionMetadata
    // did, so ContigMetadata -- which extends IdMetadata directly, not IdDescriptionMetadata -- was never covered
    // by a direct .validate() call, only by VcfWriter's own explicit checkAttributeOrder() call). ContigMetadata
    // doesn't model Number/Type/Description/Source/Version itself, but checkAttributeOrder() checks order among
    // whichever of those well-known keys are present in the raw property map, regardless of whether this metadata
    // kind's own getters use them -- so a raw "Number" property placed before "ID" still exercises the check.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    org.pharmgkb.parser.vcf.spec.WarnCapturingAppender appender =
        new org.pharmgkb.parser.vcf.spec.WarnCapturingAppender();
    Map<String, String> props = org.pharmgkb.parser.vcf.InternalUtils.extractProperties(
        new String[]{"Number=1", "ID=chr1"}, VcfWarnings.DEFAULT);
    ContigMetadata contig = new ContigMetadata(props, VcfWarnings.DEFAULT);
    appender.start();
    root.addAppender(appender);
    try {
      contig.validate();
      assertTrue(appender.anyMessageContains("declared out of canonical order"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void testTypedConstructorRejectsNullOrBlankId() {
    assertThrows(VcfFormatException.class, () -> new ContigMetadata(null, 100L, "GRCh38", null, null, null, null));
    assertThrows(VcfFormatException.class, () -> new ContigMetadata("", 100L, "GRCh38", null, null, null, null));
    assertThrows(VcfFormatException.class, () -> new ContigMetadata("   ", 100L, "GRCh38", null, null, null, null));
  }

  @Test
  public void testTypedConstructorRejectsMalformedUrl() {
    VcfFormatException ex = assertThrows(VcfFormatException.class,
        () -> new ContigMetadata("1", 1000L, "GRCh38", null, null, null, "not a url"));
    assertTrue(ex.getMessage().contains("is malformed"));
  }

  @Test
  public void testTypedConstructorAcceptsValidContig() {
    ContigMetadata md = new ContigMetadata("1", 1000L, "GRCh38", null, null, null, null);
    assertEquals(1000L, md.getLength());
  }

  @Test
  public void testMapConstructorRequiresWarningsInstance() {
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=1", "URL=not a url"}, VcfWarnings.DEFAULT);
    assertThrows(NullPointerException.class, () -> new ContigMetadata(props, null));
  }

  @Test
  public void testMapConstructorWarnsInsteadOfThrowingForMalformedUrl() {
    Map<String, String> props = InternalUtils.extractProperties(
        new String[] {"ID=1", "URL=not a url"}, VcfWarnings.DEFAULT);
    StringWriter sink = new StringWriter();
    ContigMetadata md = new ContigMetadata(props, new VcfWarnings(sink));
    assertEquals("not a url", md.getUrl());
    assertTrue(sink.toString().contains("is malformed"));
  }

}

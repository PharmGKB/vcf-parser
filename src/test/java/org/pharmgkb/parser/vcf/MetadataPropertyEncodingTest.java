package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;
import org.slf4j.LoggerFactory;

import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Construction-time rejection of a structural delimiter in a metadata property, and the deliberately narrower
 * rule for generic {@code ##key=value} properties. See STRUCTURAL_DELIMITER_HANDLING.md.
 */
class MetadataPropertyEncodingTest {

  private static VcfMetadata.Builder builder() {
    return new VcfMetadata.Builder(LENIENT_CONTEXT).setFileFormat("VCFv4.2");
  }

  private static LinkedHashMap<String, String> contigProps(String assembly) {
    return contigProps(assembly, ContigMetadata.ASSEMBLY);
  }

  private static LinkedHashMap<String, String> contigProps(String value, String name) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put(IdMetadata.ID, "1");
    map.put(name, value);
    return map;
  }

  @Test
  void testAddMapRejectsEachUnquotedDelimiter() {
    // the three verified corruption modes: comma truncates, an unquoted '<'/'>' collides with the surrounding
    // attribute-list's own opening/closing delimiter, and an unescaped quote absorbs every following attribute.
    // '=' is no longer probed here -- checkMetadataPropertyEncoding no longer rejects it in an unquoted value;
    // see testAddTypedRejectsAnAngleBracketInTheId's history for why '=' was the original probe and why it
    // stopped isolating this check.
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("a,b")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("a<b")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("a\"b")));
  }

  @Test
  void testAddMapAcceptsTheQuotedCounterparts() {
    // distinguishes "rejects a delimiter" from "rejects any value containing these characters" -- quoting
    // neutralizes all three, and the writer emits them safely
    assertDoesNotThrow(() -> builder().addContig(contigProps("\"a,b\"")));
    assertDoesNotThrow(() -> builder().addContig(contigProps("\"a=b\"")));
    assertDoesNotThrow(() -> builder().addContig(contigProps("B36")));
  }

  @Test
  void constructionRejectsANameOutsideTheAllowlist() {
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("v", "a-b")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("v", "1abc")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("v", "a b")));
  }

  @Test
  void constructionAcceptsSpecConformantNames() {
    // Name_1 comes from the spec's own ##PEDIGREE example, VCFv4.3.tex:287
    builder().addContig(contigProps("v", "Name_1"));
    builder().addContig(contigProps("v", "_x.y1"));
  }

  private static LinkedHashMap<String, String> metaProps(String values) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put(IdMetadata.ID, "Assay");
    map.put("Values", values);
    return map;
  }

  @Test
  void constructionRejectsABracketValueOutsideMetaValues() {
    // the spec-evidenced shape (VCFv4.3.tex:273's Values=[WholeGenome, Exome]) is no longer accepted outside
    // ##META's Values -- an unquoted comma inside the brackets is now rejected exactly like any other
    // unquoted comma, since '[' carries no protecting meaning here (see VCF_SPEC_AMBIGUITIES.md's "General
    // bracket-list quoting beyond ##META's Values" entry)
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("[WholeGenome, Exome]")));
    // '[a=b' still throws too -- bcftools' OWN generic bracket handling is unaffected by this library's
    // narrower quoting decision, so an unterminated '[' is still universally unreadable to it (exit 255)
    // regardless of attribute/declaration type
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("[a=b")));
    // these still throw too, but now for the generic "unquoted structural delimiter" reason (a leading '['
    // is folded into the same check '<'/'>' already get, and checkMetadataPropertyEncoding throws for ANY
    // problem regardless of severity) -- there is no bracket grammar left to be "malformed" outside Values
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("[a]b]")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("[a] junk")));
  }

  @Test
  void constructionAcceptsAWellFormedBracketValueOnMetaValuesOnly() {
    builder().addMeta(metaProps("[WholeGenome, Exome]"));   // VCFv4.3.tex:273's own shape
  }

  @Test
  void constructionRejectsAMalformedBracketValueOnMetaValues() {
    // '[a=b' makes bcftools exit 255 -- the file becomes unreadable, so we must never originate it
    assertThrows(VcfFormatException.class, () -> builder().addMeta(metaProps("[a=b")));
    assertThrows(VcfFormatException.class, () -> builder().addMeta(metaProps("[a]b]")));
    assertThrows(VcfFormatException.class, () -> builder().addMeta(metaProps("[a] junk")));
  }

  @Test
  void metaValuesBracketHasNoEscapeMechanism() {
    // these must agree with AttributeListTokenizerTest#metaValuesBracketsHaveNoEscapeMechanism -- one shared
    // scanner. Unlike the quoted-value form, the spec defines no escape for '[' / ']', so a backslash before
    // ']' does not protect it from closing the value.
    builder().addMeta(metaProps("[]"));
    builder().addMeta(metaProps("[a\\\\]"));   // no escaping applies, so this is well-formed regardless
    // an odd backslash run before ']' does NOT protect it (unlike the quoted-value form): the first LITERAL
    // ']' is not this value's last character, so it is malformed junk-after-close, not an escaped one
    assertThrows(VcfFormatException.class, () -> builder().addMeta(metaProps("[a\\]b]")));
  }

  @Test
  void constructionAcceptsAnEqualsSignInAValue() {
    // c1=x is a spec-conformant contig name (VCFv4.3.tex:262) that the old predicate rejected
    builder().addContig(contigProps("c1=x"));
  }

  @Test
  void constructionRejectsAngleBracketsAndLayoutWhitespaceInALiteralValue() {
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("a<b")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("a>b")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("abc ")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps(" abc")));
  }

  @Test
  void testAnAcceptedDeclarationRoundTripsThroughTheDefaultWritePath() throws Exception {
    // the property this whole change exists to establish: anything construction now accepts must survive a
    // default write and re-parse intact. Accepting a value and then corrupting it downstream would satisfy
    // every assertThrows test above while leaving the actual defect in place.
    VcfMetadata metadata = builder().addContig(contigProps("\"a,b\"")).build();
    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
      writer.writeHeader(metadata);
    }
    try (BufferedReader reader = new BufferedReader(new StringReader(sw + "chr1\t1\t.\tA\tT\t.\t.\t.\n"));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, rec) -> { }).build()) {
      parser.parse();
      ContigMetadata back = parser.getMetadata().getContigs().get("1");
      assertEquals("\"a,b\"", back.getPropertyRaw(ContigMetadata.ASSEMBLY));
      assertEquals("a,b", back.getAssembly());
    }
  }

  @Test
  void testAddTypedRejectsAnUnquotedDelimiterInEveryRawStoredField() {
    // The check reads the whole constructed declaration, so cover every argument the typed contig constructor
    // stores verbatim via putPropertyRaw (ContigMetadata.java:36-52: length, assembly, md5, taxonomy, URL --
    // species is the lone exception, see the control test below). Testing only assembly would leave a
    // field-specific or mis-scoped check passing while a delimiter-bearing md5/taxonomy/URL/ID still corrupts
    // default output. length takes a Long and is stringified, so it cannot carry a delimiter -- not covered.
    assertThrows(VcfFormatException.class,
        () -> builder().addContig("1", 100L, "a,b", null, null, null, null), "assembly");
    assertThrows(VcfFormatException.class,
        () -> builder().addContig("1", 100L, null, "a,b", null, null, null), "md5");
    assertThrows(VcfFormatException.class,
        () -> builder().addContig("1", 100L, null, null, null, "a,b", null), "taxonomy");
    assertThrows(VcfFormatException.class,
        () -> builder().addContig("1", 100L, null, null, null, null, "http://x.example/a,b"), "URL");
  }

  @Test
  void testAddTypedRejectsAnAngleBracketInTheId() {
    // ID is stored raw too (IdMetadata's typed constructor ends in putPropertyRaw(ID, id)). The probe used to
    // be a non-leading '=', chosen for durability under a hypothetical STRICT_CONTEXT: '=' is not among the 13
    // characters InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN rejects (backslash, comma, double quote,
    // backtick, single quote, the two parentheses, the two braces, the two square brackets, and the two angle
    // brackets -- described here rather than re-quoted, since the Java string literal's escaping makes a copied
    // regex easy to transcribe wrong), and ContigMetadata rejects only a LEADING '=' (ContigMetadata.java:87).
    // checkMetadataPropertyEncoding no longer rejects '=' in an unquoted value at all, so '<' replaces it here.
    // '<' IS one of those 13 characters, so this probe no longer isolates the check under a hypothetical
    // STRICT_CONTEXT the way '=' once did -- under this file's actual LENIENT_CONTEXT it still isolates cleanly,
    // because ContigMetadata's own charset check routes through BaseMetadata.warnOrThrow, which only warns
    // (rather than throws) when isLenient() (BaseMetadata.java:110-115); confirmed by temporarily widening the
    // predicate to accept '<' and re-running this test, which then failed with no other check taking its place.
    assertThrows(VcfFormatException.class,
        () -> builder().addContig("a<b", 100L, null, null, null, null, null));
  }

  @Test
  void testAddTypedAcceptsADelimiterInContigsOwnAutoQuotedField() throws Exception {
    // species is the one contig argument stored via putAndQuoteProperty (ContigMetadata.java:45), which quotes
    // unconditionally (BaseMetadata.java:248), so "a,b" becomes "\"a,b\"" and is safe to emit. Same
    // constructor, same call shape, same character, opposite outcome from the rejection test above: the only
    // difference is which inserter the constructor happens to use for that argument. If this throws, the check
    // was applied to the ARGUMENTS instead of to the stored properties.
    // "Does not throw" alone would not prove the accept-decision correct -- see the Reference table's closing
    // paragraph in STRUCTURAL_DELIMITER_HANDLING.md -- so this also round-trips through the default write path.
    VcfMetadata metadata =
        assertDoesNotThrow(() -> builder().addContig("1", 100L, null, null, "a,b", null, null).build());
    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
      writer.writeHeader(metadata);
    }
    try (BufferedReader reader = new BufferedReader(new StringReader(sw + "chr1\t1\t.\tA\tT\t.\t.\t.\n"));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, rec) -> { }).build()) {
      parser.parse();
      assertEquals("a,b", parser.getMetadata().getContigs().get("1").getSpecies());
    }
  }

  @Test
  void testAddTypedAcceptsADelimiterInAnAutoQuotedField() throws Exception {
    // the same opposite-outcome property as above, across a different class: InfoMetadata's Description also
    // goes through putAndQuoteProperty. Kept alongside the contig case so the rule reads as a property of the
    // inserter rather than of ContigMetadata specifically.
    // Round-tripped for the same reason as the contig case above: construction not throwing proves nothing on
    // its own.
    VcfMetadata metadata = assertDoesNotThrow(
        () -> builder().addInfo("XX", "a,b", InfoType.String, "1", null, null).build());
    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
      writer.writeHeader(metadata);
    }
    try (BufferedReader reader = new BufferedReader(new StringReader(sw + "chr1\t1\t.\tA\tT\t.\t.\t.\n"));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, rec) -> { }).build()) {
      parser.parse();
      assertEquals("a,b", parser.getMetadata().getInfo().get("XX").getDescription());
    }
  }

  @Test
  void testReplaceMapRejectsAnUnquotedDelimiter() {
    // replace has its own private object-taking path; a check wired only into the add-side helper would leave
    // all twelve replace routes open
    VcfMetadata.Builder b = builder().addContig(contigProps("B36"));
    assertThrows(VcfFormatException.class, () -> b.replaceContig(contigProps("a,b")));
  }

  @Test
  void testReplaceTypedRejectsAnUnquotedDelimiter() {
    VcfMetadata.Builder b = builder().addContig(contigProps("B36"));
    assertThrows(VcfFormatException.class,
        () -> b.replaceContig("1", 100L, "a,b", null, null, null, null));
  }

  @Test
  void testAValidReplacementStillSucceeds() {
    // without this, a check that rejected every replacement outright would pass the whole replace group
    VcfMetadata.Builder b = builder().addContig(contigProps("B36"));
    assertDoesNotThrow(() -> b.replaceContig(contigProps("GRCh38")));
    assertEquals("GRCh38", b.build().getContigs().get("1").getPropertyRaw(ContigMetadata.ASSEMBLY));
  }

  // ---- route coverage: every public construction route must actually call the check ----

  private static LinkedHashMap<String, String> map(String... keyValues) {
    LinkedHashMap<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      m.put(keyValues[i], keyValues[i + 1]);
    }
    return m;
  }

  /** A kind's public {@code Map} routes, plus a property map whose only flaw is an unquoted comma. */
  private record MapRoute(String kind, LinkedHashMap<String, String> good, LinkedHashMap<String, String> bad,
      BiConsumer<VcfMetadata.Builder, Map<String, String>> add) {}

  // All eight kinds. The comma sits in a raw-stored attribute in each: Description for the five
  // IdDescriptionMetadata-shaped kinds (the Map route stores it verbatim -- only the TYPED route auto-quotes
  // it), assembly for contig, an arbitrary attribute for PEDIGREE (which has no fixed schema), and Values for
  // META (which has no auto-quoted field either -- see MetaMetadata's own Javadoc).
  private static final MapRoute[] MAP_ROUTES = {
      new MapRoute("ALT", map("ID", "DEL", "Description", "\"d\""), map("ID", "DEL", "Description", "a,b"),
          VcfMetadata.Builder::addAlt),
      new MapRoute("INFO", map("ID", "XX", "Number", "1", "Type", "String", "Description", "\"d\""),
          map("ID", "XX", "Number", "1", "Type", "String", "Description", "a,b"),
          VcfMetadata.Builder::addInfo),
      new MapRoute("FILTER", map("ID", "q10", "Description", "\"d\""), map("ID", "q10", "Description", "a,b"),
          VcfMetadata.Builder::addFilter),
      new MapRoute("FORMAT", map("ID", "XX", "Number", "1", "Type", "String", "Description", "\"d\""),
          map("ID", "XX", "Number", "1", "Type", "String", "Description", "a,b"),
          VcfMetadata.Builder::addFormat),
      new MapRoute("contig", map("ID", "1", "assembly", "B36"), map("ID", "1", "assembly", "a,b"),
          VcfMetadata.Builder::addContig),
      new MapRoute("SAMPLE", map("ID", "s1", "Description", "\"d\""), map("ID", "s1", "Description", "a,b"),
          VcfMetadata.Builder::addSample),
      new MapRoute("PEDIGREE", map("ID", "p1", "Original", "g0"), map("ID", "p1", "Original", "a,b"),
          VcfMetadata.Builder::addPedigree),
      new MapRoute("META", map("ID", "Assay", "Number", "1", "Type", "String", "Values", "x"),
          map("ID", "Assay", "Number", "1", "Type", "String", "Values", "a,b"),
          VcfMetadata.Builder::addMeta),
  };

  @Test
  void testEveryPublicMapAddRouteRejectsAnUnquotedDelimiter() {
    for (MapRoute r : MAP_ROUTES) {
      // fresh copy per call: the maps are shared across tests and a constructor may normalize in place
      assertThrows(VcfFormatException.class, () -> r.add().accept(builder(), new LinkedHashMap<>(r.bad())),
          "no rejection from add" + r.kind() + "(Map)");
    }
  }

  @Test
  void testEveryPublicMapAddRouteStillAcceptsAValidDeclaration() {
    // the counterweight: without this, a check that rejected everything would pass the matrix above
    for (MapRoute r : MAP_ROUTES) {
      assertDoesNotThrow(() -> r.add().accept(builder(), new LinkedHashMap<>(r.good())),
          "valid declaration rejected by add" + r.kind() + "(Map)");
    }
  }

  @Test
  void testEveryPublicMapReplaceRouteRejectsAnUnquotedDelimiter() {
    // seven, not eight: there is no replacePedigree (VcfMetadata.java:928, "public Builder addPedigree(Map<...>
    // properties) {", is addPedigree(Map); the comment at VcfMetadata.java:955-956, "There is no replacePedigree:
    // PEDIGREE is List-backed, not Map-backed", explains why -- so it has no replace
    // counterpart), so PEDIGREE is add-only and is deliberately absent here.
    record ReplaceRoute(String kind, BiConsumer<VcfMetadata.Builder, Map<String, String>> replace) {}
    ReplaceRoute[] routes = {
        new ReplaceRoute("ALT", VcfMetadata.Builder::replaceAlt),
        new ReplaceRoute("INFO", VcfMetadata.Builder::replaceInfo),
        new ReplaceRoute("FILTER", VcfMetadata.Builder::replaceFilter),
        new ReplaceRoute("FORMAT", VcfMetadata.Builder::replaceFormat),
        new ReplaceRoute("contig", VcfMetadata.Builder::replaceContig),
        new ReplaceRoute("SAMPLE", VcfMetadata.Builder::replaceSample),
        new ReplaceRoute("META", VcfMetadata.Builder::replaceMeta),
    };
    for (ReplaceRoute rr : routes) {
      MapRoute r = mapRoute(rr.kind());
      // replace requires an entry already stored under the same ID, so seed with the valid map first --
      // otherwise requirePresentForReplace throws VcfFormatException for "not present" and the assertion below
      // passes for entirely the wrong reason
      VcfMetadata.Builder b = builder();
      r.add().accept(b, new LinkedHashMap<>(r.good()));
      assertThrows(VcfFormatException.class, () -> rr.replace().accept(b, new LinkedHashMap<>(r.bad())),
          "no rejection from replace" + rr.kind() + "(Map)");
    }
  }

  @Test
  void testEveryPublicMapReplaceRouteStillAcceptsAValidReplacement() {
    // the map-replace matrix's counterweight, matching the add matrix's. Without it, a Task 4 wiring that
    // rejected every replacement outright would pass the whole replace group.
    for (String kind : new String[] {"ALT", "INFO", "FILTER", "FORMAT", "contig", "SAMPLE", "META"}) {
      MapRoute r = mapRoute(kind);
      VcfMetadata.Builder b = builder();
      r.add().accept(b, new LinkedHashMap<>(r.good()));
      assertDoesNotThrow(() -> replaceOf(kind).accept(b, new LinkedHashMap<>(r.good())),
          "valid replacement rejected by replace" + kind + "(Map)");
    }
  }

  private static BiConsumer<VcfMetadata.Builder, Map<String, String>> replaceOf(String kind) {
    return switch (kind) {
      case "ALT" -> VcfMetadata.Builder::replaceAlt;
      case "INFO" -> VcfMetadata.Builder::replaceInfo;
      case "FILTER" -> VcfMetadata.Builder::replaceFilter;
      case "FORMAT" -> VcfMetadata.Builder::replaceFormat;
      case "contig" -> VcfMetadata.Builder::replaceContig;
      case "SAMPLE" -> VcfMetadata.Builder::replaceSample;
      case "META" -> VcfMetadata.Builder::replaceMeta;
      default -> throw new IllegalArgumentException(kind);
    };
  }

  @Test
  void testEveryPublicTypedAddRouteStillAcceptsAValidDeclaration() {
    // the typed-add matrix's counterweight. ALT/FILTER/FORMAT/SAMPLE typed acceptance is otherwise never
    // asserted in this file at all -- VcfMetadataTest happens to cover it, but this file should not depend on
    // another file to prove its own matrix is not over-rejecting.
    record TypedRoute(String kind, Consumer<VcfMetadata.Builder> call) {}
    TypedRoute[] routes = {
        new TypedRoute("ALT", b -> b.addAlt("DEL", "d")),
        new TypedRoute("INFO", b -> b.addInfo("XX", "d", InfoType.String, "1", null, null)),
        new TypedRoute("FILTER", b -> b.addFilter("q10", "d")),
        new TypedRoute("FORMAT", b -> b.addFormat("XX", "d", "1", FormatType.String)),
        new TypedRoute("contig", b -> b.addContig("1", 100L, null, null, null, null, null)),
        new TypedRoute("SAMPLE", b -> b.addSample("s1", "d")),
    };
    for (TypedRoute r : routes) {
      assertDoesNotThrow(() -> r.call().accept(builder()),
          "valid declaration rejected by typed add" + r.kind());
    }
  }

  private static MapRoute mapRoute(String kind) {
    for (MapRoute r : MAP_ROUTES) {
      if (r.kind().equals(kind)) {
        return r;
      }
    }
    throw new IllegalArgumentException(kind);
  }

  @Test
  void testEveryPublicTypedAddRouteRejectsAnUnquotedDelimiter() {
    // Six kinds; PEDIGREE has no typed route at all (no fixed schema -- see PedigreeMetadata's Javadoc).
    // The probe is '<' in the ID, because ID is the ONLY raw-stored typed argument shared by all six: the
    // typed constructors route Description (and INFO's Source/Version, and contig's species) through
    // putAndQuoteProperty, which quotes unconditionally, so a delimiter there is legitimately safe. '<' replaces
    // the original '=' probe -- see testAddTypedRejectsAnAngleBracketInTheId for the full reasoning; briefly,
    // '=' no longer throws unconditionally from checkMetadataPropertyEncoding, and '<' does isolate this check
    // under this file's lenient context (confirmed by temporarily widening the predicate and re-running).
    record TypedRoute(String kind, Consumer<VcfMetadata.Builder> call) {}
    TypedRoute[] routes = {
        new TypedRoute("ALT", b -> b.addAlt("a<b", "d")),
        new TypedRoute("INFO", b -> b.addInfo("a<b", "d", InfoType.String, "1", null, null)),
        new TypedRoute("FILTER", b -> b.addFilter("a<b", "d")),
        new TypedRoute("FORMAT", b -> b.addFormat("a<b", "d", "1", FormatType.String)),
        new TypedRoute("contig", b -> b.addContig("a<b", 100L, null, null, null, null, null)),
        new TypedRoute("SAMPLE", b -> b.addSample("a<b", "d")),
    };
    for (TypedRoute r : routes) {
      assertThrows(VcfFormatException.class, () -> r.call().accept(builder()),
          "no rejection from typed add" + r.kind());
    }
  }

  @Test
  void testTypedReplaceRoutesWithAReachableRawFieldRejectAnUnquotedDelimiter() {
    // Only three of the six typed replace routes can be tested, and the reason is structural rather than an
    // oversight. A replace needs an ID that is already stored, so the ID cannot carry the probe -- which
    // leaves whatever OTHER argument the typed constructor stores raw. INFO and FORMAT have Number; contig has
    // assembly/md5/taxonomy/URL. ALT, FILTER and SAMPLE have none: their typed constructors take only
    // (id, description), and Description is auto-quoted, so no rejectable value can reach them while the ID
    // stays valid. Their replaceXxx(Map) sibling is covered by the map matrix above; do not "fix" this by
    // passing an unstored ID, which throws for not-present and would pass the assertion for the wrong reason.
    VcfMetadata.Builder info = builder();
    info.addInfo(map("ID", "XX", "Number", "1", "Type", "String", "Description", "\"d\""));
    assertThrows(VcfFormatException.class,
        () -> info.replaceInfo("XX", "d", InfoType.String, "a,b", null, null), "typed replaceInfo");

    VcfMetadata.Builder format = builder();
    format.addFormat(map("ID", "XX", "Number", "1", "Type", "String", "Description", "\"d\""));
    assertThrows(VcfFormatException.class,
        () -> format.replaceFormat("XX", "d", "a,b", FormatType.String), "typed replaceFormat");

    // contig's own typed replace is covered in detail by testReplaceTypedRejectsAnUnquotedDelimiter above
  }

  @Test
  void testRejectsEveryInvalidKeyShape() {
    // a"b: an unescaped quote in the NAME is checked the same way as in the value, via the shared
    // containsUnescapedQuote helper. bcftools 1.23.1 refuses the whole header line for a name like this (see
    // testRejectsEveryQuoteBearingPropertyName below); this construction-time check keeps this library's own
    // output from producing a declaration bcftools cannot read back, independent of whether this library's
    // own parser can re-read it cleanly (see
    // testAnUnescapedQuoteInAPropertyNameNoLongerCorruptsFollowingAttributes for the parse-time behavior this
    // once also guarded against).
    for (String badKey : new String[] {"", "bad,key", "bad=key", "a\"b"}) {
      LinkedHashMap<String, String> map = new LinkedHashMap<>();
      map.put(IdMetadata.ID, "1");
      map.put(badKey, "v");
      assertThrows(VcfFormatException.class, () -> builder().addContig(map),
          "expected rejection for key [" + badKey + "]");
    }
  }

  @Test
  void testAnUnescapedQuoteInAPropertyNameNoLongerCorruptsFollowingAttributes() throws Exception {
    // regression guard for the fix, not the bug: the retired two-pass splitter (whole-list split on top-level
    // comma, THEN each part split on top-level '=') tracked one "inside a quoted value" toggle across the
    // WHOLE declaration, so a stray '"' in a NAME flipped it and every delimiter after that point -- the rest
    // of "a"b", "=v", the comma, and the whole "length=100" attribute -- was absorbed into one value instead of
    // being split. The attribute-list tokenizer (InternalUtils.tokenizeAttributeList, via parseOneAttribute)
    // has no such shared toggle: each attribute's own boundary is found independently, and (per
    // AttributeListTokenizerTest#onlyALeadingDelimiterQuotes) a non-leading quote protects nothing, so "a\"b"
    // is ordinary NAME content. All three attributes now parse correctly; a quote-bearing NAME is still only
    // rejected at construction time (see testRejectsEveryQuoteBearingPropertyName below), never during parsing.
    VcfMetadata metadata = parseHeader("##contig=<ID=1,a\"b=v,length=100>").metadata();
    ContigMetadata contig = metadata.getContigs().get("1");
    assertEquals(Map.of(IdMetadata.ID, "1", "a\"b", "v", "length", "100"), contig.getPropertiesRaw());
  }

  @Test
  void testRejectsEveryQuoteBearingPropertyName() {
    // bcftools 1.23.1 refuses a header line with ANY quote in an attribute name, regardless of pairing or
    // escaping -- measured on all three forms below, each producing
    // "[E::bcf_hdr_parse_line] Could not parse the header line" and dropping the declaration. The VCF spec's
    // structured-line grammar (VCFv4.3.tex line 119, "##key=<key=value,key=value,...>") states no character
    // set for the inner key, so it is silent; per AGENTS.md's Decision Hierarchy bcftools governs, and its
    // rejection is the strong direction of its evidence. This library round-trips the escaped forms cleanly
    // on its own, which is exactly why an internal round-trip test was not sufficient evidence here.
    for (String badName : new String[] {"a\"b", "a\\\"b", "a\\\"b\\\"", "\"ab\""}) {
      LinkedHashMap<String, String> map = new LinkedHashMap<>();
      map.put(IdMetadata.ID, "1");
      map.put(badName, "v");
      assertThrows(VcfFormatException.class, () -> builder().addContig(map),
          "expected rejection for name [" + badName + "]");
    }
  }

  @Test
  void testAnEscapedQuoteIsStillFineInsideAQuotedValue() {
    // the counterweight, and a real hts-specs passed/ fixture shape
    // (test/vcf/4.3/passed/passed_meta_info.vcf line 3). bcftools accepts and echoes it intact. Rejecting
    // this would break a file hts-specs certifies as valid.
    assertDoesNotThrow(() -> builder().addInfo(
        infoProps("\"Info field with \\\"escaped quotes\\\"\"")));
  }

  private static LinkedHashMap<String, String> infoProps(String description) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put(IdMetadata.ID, "ID2");
    map.put("Number", "2");
    map.put("Type", "Float");
    map.put(InfoMetadata.DESCRIPTION, description);
    return map;
  }

  @Test
  void testANullKeyIsRejectedByConstructionNotByThisCheck() {
    // LinkedHashMap, not Map.of: Map.of rejects a null key itself, which would test the JDK rather than this
    // library. The exception here comes from BaseMetadata.validateProperties() DURING construction, before the
    // Builder check runs -- assert its distinct message so this row is not misread as evidence that the
    // Builder check handles null. The null branch in the shared predicate is genuinely unreachable.
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put(IdMetadata.ID, "1");
    map.put(null, "v");
    VcfFormatException e =
        assertThrows(VcfFormatException.class, () -> builder().addContig(map));
    assertEquals("Metadata contains a null property name", e.getMessage());
  }

  // ---- generic ##key=value properties: a deliberately DIFFERENT grammar ----
  // VcfParser splits a generic line at line.indexOf("=") (VcfParser.java:550-555) -- the FIRST occurrence --
  // taking everything after it as the value. So an '=' in the NAME shifts the split point and corrupts, while
  // an '=' or ',' in the VALUE is harmless. This happens to now AGREE with the structured attribute-list
  // grammar's own treatment of a second '=' (InternalUtils.parseAttribute's name scan also stops at the first
  // '=' only, taking the rest as the value) -- but the two grammars are still not the same mechanism: a
  // structured attribute's value is also bounded by an unescaped ',' (the attribute-list separator) and by
  // quote/bracket-quoting rules this generic, line-level grammar has no equivalent of at all. Keeping these
  // tests beside the structured ones above is deliberate, so the two grammars' different delimiter sets stay
  // visible rather than looking like an inconsistency.

  @Test
  void testAddRawPropertyRejectsAnEqualsSignInTheName() {
    assertThrows(VcfFormatException.class, () -> builder().addRawProperty("bad=name", "v"));
  }

  @Test
  void testAddRawPropertyAcceptsACommaInTheName() {
    // control: proves the structured comma rule was not applied to the generic grammar
    assertDoesNotThrow(() -> builder().addRawProperty("bad,name", "v"));
  }

  @Test
  void testAddRawPropertyValueKeepsDelimitersThroughAWriteAndReparse() throws Exception {
    // construction-did-not-throw proves nothing on its own here -- the allow-decision is only correct if the
    // value actually survives the round trip. Note the value has no leading/trailing spaces on purpose:
    // VcfParser.java:555 trims both name and value, so a spaced value would not round-trip regardless of this
    // change, and that pre-existing behavior must not be mistaken for a regression.
    VcfMetadata metadata = builder()
        .addRawProperty("eq", "a=b")
        .addRawProperty("comma", "a,b")
        .build();
    assertEquals("a=b", metadata.getRawValuesOfProperty("eq").get(0));
    assertEquals("a,b", metadata.getRawValuesOfProperty("comma").get(0));

    StringWriter sw = new StringWriter();
    try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
      writer.writeHeader(metadata);
    }
    try (BufferedReader reader = new BufferedReader(new StringReader(sw + "chr1\t1\t.\tA\tT\t.\t.\t.\n"));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, rec) -> { }).build()) {
      parser.parse();
      assertEquals("a=b", parser.getMetadata().getRawValuesOfProperty("eq").get(0));
      assertEquals("a,b", parser.getMetadata().getRawValuesOfProperty("comma").get(0));
    }
  }

  // ---- the parse path must stay lenient: two halves, neither substitutes for the other ----

  /**
   * A malformed-quote header and exactly what the parser recovers from it. Every expected value below was
   * measured by running these three headers through the parser, not predicted -- re-measure rather than
   * hand-edit if one ever changes.
   */
  private record RecoveredCase(String declaration, String recoveredDescription, String... warningFragments) {}

  private static final RecoveredCase[] RECOVERED_QUOTE_CASES = {
      // a stray, non-leading quote inside a quoted value closes it at that position (the attribute-list
      // tokenizer finds a value's closing delimiter by POSITION, not by unbalanced-quote parity): the
      // recovered value is the well-formed "\"a\"", and the remaining "b\"" is trailing content, warned about
      // and discarded rather than absorbed
      new RecoveredCase("##FILTER=<ID=q10,Description=\"a\"b\">", "\"a\"",
          "trailing content"),
      // an unquoted value ending in a quote: warns TWICE -- the tokenizer's own "structural character" warning
      // plus Description's own "should be quoted but was". Pinning both is what keeps a later change from
      // silencing one of them unnoticed.
      new RecoveredCase("##FILTER=<ID=q10,Description=ab\">", "ab\"",
          "structural", "should be quoted but was"),
      // the important one: Description's opening quote never finds a closing quote before the next comma, so
      // recovery searches the WHOLE remainder of the line (AttributeListTokenizerTest's own
      // aClosingDelimiterIsSearchedForAcrossTheWholeRemainder) and finds Source's OWN opening quote instead --
      // swallowing ",Source=" wholesale into Description's value, which ends up carrying both a comma and an
      // '=' (precisely what the construction-time check rejects). There is no Source key in the result at all.
      // This is the case that proves the parse path must stay exempt.
      new RecoveredCase("##INFO=<ID=XX,Number=1,Type=String,Description=\"ab,Source=\"s\">",
          "\"ab,Source=\"", "trailing content"),
      // the fixed variant of the case above: a LEADING quote that DOES find its own closing quote before the
      // next comma (unlike the unclosed-quote case above) no longer swallows anything at all. This is the
      // original probe this suite used before the attribute-list tokenizer existed -- it used to demonstrate
      // Description swallowing ",Source=\"s\"" wholesale (the old two-pass splitter's toggle-based defect,
      // see STRUCTURAL_DELIMITER_HANDLING.md's historical severity-ranking section) -- and now demonstrates the
      // fix instead: Description still truncates at its own closing quote (the same "b\"" trailing-content
      // recovery as the first case above), but Source is recovered fully intact, not swallowed.
      new RecoveredCase("##INFO=<ID=XX,Number=1,Type=String,Description=\"a\"b\",Source=\"s\">",
          "\"a\"", "trailing content"),
  };

  private record ParsedHeader(VcfMetadata metadata, WarnCapturingAppender warnings) {}

  /**
   * Parses a one-declaration header, capturing warnings. {@code VcfParser.Builder} exposes no in-memory
   * warnings sink -- only {@code warningsToFile(...)} -- so this attaches an appender to the root logger,
   * the established pattern in this suite (see {@code VcfPositionTest}'s quality-warning tests). The appender
   * is detached in a {@code finally}; its captured messages remain readable after {@code stop()}.
   */
  private static ParsedHeader parseHeader(String declaration) throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      String vcf = "##fileformat=VCFv4.2\n" + declaration + "\n"
          + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
      try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
           VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, rec) -> { }).build()) {
        parser.parse();
        return new ParsedHeader(parser.getMetadata(), appender);
      }
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /** The recovered {@code Description}, from whichever of the two declaration types the case used. */
  private static String recoveredDescriptionOf(VcfMetadata metadata) {
    // if/else, not a ternary: getFilters() and getInfo() have different value types, and the inferred lub of
    // a ternary over them is not a type with getPropertyRaw
    if (metadata.getFilters().containsKey("q10")) {
      return metadata.getFilters().get("q10").getPropertyRaw(IdDescriptionMetadata.DESCRIPTION);
    }
    return metadata.getInfo().get("XX").getPropertyRaw(InfoMetadata.DESCRIPTION);
  }

  @Test
  void testParsingStillRecoversAnUnescapedQuoteWithAWarning() throws Exception {
    // half one: the public-to-trusted handoff. "Does not throw" is too weak to be the whole assertion -- the
    // exemption requires the parser to keep warning AND to keep recovering the same value. Without the value
    // check, a change that silently dropped the attribute or "repaired" it would pass; without the warning
    // check, a change that recovered silently would pass. Both failures would be regressions in the
    // warn-and-recover contract this exemption exists to protect.
    for (RecoveredCase c : RECOVERED_QUOTE_CASES) {
      ParsedHeader parsed = parseHeader(c.declaration());
      for (String fragment : c.warningFragments()) {
        assertTrue(parsed.warnings().anyMessageContains(fragment),
            "missing warning \"" + fragment + "\" for: " + c.declaration());
      }
      assertEquals(c.recoveredDescription(), recoveredDescriptionOf(parsed.metadata()),
          "recovered value changed for: " + c.declaration());
    }
  }

  @Test
  void testToBuilderRebuildsParserRecoveredMetadataWithoutThrowing() throws Exception {
    // half two: the rebuild. toBuilder() re-adds through the package-private object-taking overloads, which
    // are exempt. This is what catches the check being put into a shared insertion helper -- half one would
    // still pass, because the parser's trusted route bypasses that helper, while toBuilder() goes through it.
    for (RecoveredCase c : RECOVERED_QUOTE_CASES) {
      VcfMetadata parsed = parseHeader(c.declaration()).metadata();
      VcfMetadata rebuilt = assertDoesNotThrow(() -> parsed.toBuilder().build(),
          "toBuilder() should not reject parser-recovered content: " + c.declaration());
      assertEquals(parsed.getFilters().keySet(), rebuilt.getFilters().keySet());
      assertEquals(parsed.getInfo().keySet(), rebuilt.getInfo().keySet());
      parsed.getInfo().forEach((id, md) ->
          assertEquals(md.getPropertiesRaw(), rebuilt.getInfo().get(id).getPropertiesRaw()));
      parsed.getFilters().forEach((id, md) ->
          assertEquals(md.getPropertiesRaw(), rebuilt.getFilters().get(id).getPropertiesRaw()));
    }
  }

  /** The recovered declaration itself, from whichever of the two declaration types the case used. */
  private static BaseMetadata recoveredDeclarationOf(VcfMetadata metadata) {
    if (metadata.getFilters().containsKey("q10")) {
      return metadata.getFilters().get("q10");
    }
    return metadata.getInfo().get("XX");
  }

  @Test
  void testWritingParserRecoveredMetadataOnTheDefaultPathIsAFixedPointOnReparse() throws Exception {
    // The exemption's safety premise (STRUCTURAL_DELIMITER_HANDLING.md's "Why the parse path is exempt") is
    // that a parser-recovered value has already been absorbed at parse time, so writing it back out on the
    // DEFAULT (non-validateBeforeWrite()) path and re-parsing it again reaches the SAME absorbed state rather
    // than degrading further. Neither half above tests this: half one never writes, and half two rebuilds via
    // toBuilder() but never writes either. If this premise were false, a caller who simply parses and writes a
    // file unmodified would see recovered content keep mutating on every write/re-parse cycle.
    for (RecoveredCase c : RECOVERED_QUOTE_CASES) {
      VcfMetadata parsed = parseHeader(c.declaration()).metadata();
      Map<String, String> before = recoveredDeclarationOf(parsed).getPropertiesRaw();

      StringWriter sw = new StringWriter();
      try (VcfWriter writer = new VcfWriter.Builder().toWriter(new PrintWriter(sw)).build()) {
        writer.writeHeader(parsed);
      }
      try (BufferedReader reader = new BufferedReader(new StringReader(sw + "chr1\t1\t.\tA\tT\t.\t.\t.\n"));
           VcfParser reparser = new VcfParser.Builder().fromReader(reader).parseWith((md, rec) -> { }).build()) {
        reparser.parse();
        Map<String, String> after = recoveredDeclarationOf(reparser.getMetadata()).getPropertiesRaw();
        assertEquals(before, after, "write/re-parse was not a fixed point for: " + c.declaration());
      }
    }
  }

  // ---- the construction-vs-diagnostics split: throw at construction, warn (except when unreadable) at write ----

  @Test
  void diagnosticsModeEmitsTheNameWarningExactlyOnce() throws Exception {
    // Pins Step 7's hoist: validateMetadataPropertyEncodings() runs once from writeHeader(), not once per
    // writeLine() -- a header-only write already exercises writeHeader(), so a regression that left the pass
    // wired into the per-record path would still need at least one writeLine() call to double-count; this test
    // still catches the more common regression, an accidental duplicate call within writeHeader() itself.
    VcfMetadata md = parseHeader("##contig=<ID=c1,a-b=v,length=999>").metadata();
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      StringWriter out = new StringWriter();
      new VcfWriter.Builder().validateBeforeWrite().toWriter(new PrintWriter(out)).build().writeHeader(md);
      assertEquals(1, appender.countMessagesContaining("invalid property name"));
      assertThat(out.toString(), containsString("a-b=v"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void diagnosticsModeWarnsRatherThanThrowingOnCarriedForwardContent() throws Exception {
    // ##contig=<ID=1,a-b=v> is AGENTS.md's worked example of content we preserve and re-emit.
    // parseHeader (this file's own helper, above) returns a ParsedHeader record (metadata(), warnings()) and
    // declares `throws Exception`. Do not treat it as returning VcfMetadata directly.
    VcfMetadata md = parseHeader("##contig=<ID=c1,a-b=v,length=999>").metadata();
    StringWriter out = new StringWriter();
    new VcfWriter.Builder().validateBeforeWrite().toWriter(new PrintWriter(out)).build().writeHeader(md);
    assertThat(out.toString(), containsString("a-b=v"));
  }

  @Test
  void diagnosticsModeStillThrowsWhenOutputWouldBeUnreadable() throws Exception {
    // For a non-##META's-Values attribute like "x" here, the tokenizer can never PRODUCE a bare "[a=b" -- an
    // unterminated bracket is requoted at parse time (Task 1). So the only route to this state is the
    // package-private BaseMetadata#trustedMutableProperties() bypass (BaseMetadata.java:132), which is what
    // this library's other writer-defence tests use. (##META's Values itself is deliberately exempt from
    // this same re-quote -- see InternalUtils.parseOneAttribute's isMetaValues branch and
    // VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting beyond ##META's Values" entry for why.)
    VcfMetadata md = parseHeader("##contig=<ID=c1,length=999>").metadata();
    md.getContigs().get("c1").trustedMutableProperties().put("x", "[a=b");
    assertThrows(VcfFormatException.class, () ->
        new VcfWriter.Builder().validateBeforeWrite().toWriter(new PrintWriter(new StringWriter())).build()
            .writeHeader(md));
  }

  @Test
  void diagnosticsModeThrowsForAMalformedQuotedValueContainingAComma() throws Exception {
    // Public construction already throws for this shape too (checkMetadataPropertyEncoding throws for ANY
    // problem, regardless of unreadableOutput), so trustedMutableProperties() -- the same bypass the test
    // above uses -- is the only way to isolate validateBeforeWrite()'s own throw-vs-warn classification.
    // Our own round-trip, not bcftools, is the reason: a malformed quoted value with no valid closing '"' is
    // recovered by parseOneAttribute's close<0 branch, which re-scans for the next COMMA rather than the
    // value's own end -- so `x="abc,def` emitted verbatim re-parses to just `x="abc`, with "def" silently
    // dropped (it has no '=' of its own). That is genuinely unreadable by this library, so validateBeforeWrite()
    // must throw here, not merely warn.
    VcfMetadata md = parseHeader("##contig=<ID=c1,length=999>").metadata();
    md.getContigs().get("c1").trustedMutableProperties().put("x", "\"abc,def");
    assertThrows(VcfFormatException.class, () ->
        new VcfWriter.Builder().validateBeforeWrite().toWriter(new PrintWriter(new StringWriter())).build()
            .writeHeader(md));
  }

  @Test
  void aNameOutsideTheAllowlistSurvivesParsing() throws Exception {
    VcfMetadata md = parseHeader("##contig=<ID=c1,a-b=v,length=999>").metadata();
    assertEquals("v", md.getContigs().get("c1").getPropertyRaw("a-b"));
  }

  @Test
  void diagnosticsModeWarnsRatherThanThrowingOnAnUnquotedAngleBracket() throws Exception {
    // Pins the '<'/'>' warn-at-write cell against a regression that flips it to unreadableOutput=true (throw).
    // This exact declaration parses and writes cleanly today, and bcftools reads the result at exit 0 (the
    // declaration is truncated, not the file rejected -- see
    // BcftoolsDependencyTest#testUnescapedGreaterThanInMetadataValueBcftoolsTruncatesDeclaration), so throwing
    // here would regress carried-forward content with nothing left to catch it. Control: flipping '<'/'>' to
    // unreadableOutput=true in InternalUtils.findEncodingProblems must make this fail.
    VcfMetadata md = parseHeader("##contig=<ID=c1,x=a>b,length=999>").metadata();
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      StringWriter out = new StringWriter();
      assertDoesNotThrow(() ->
          new VcfWriter.Builder().validateBeforeWrite().toWriter(new PrintWriter(out)).build().writeHeader(md));
      assertTrue(appender.anyMessageContains("unquoted structural delimiter"));
      assertThat(out.toString(), containsString("x=a>b"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void diagnosticsModeWarnsRatherThanThrowingOnABracketOutsideMetaValues() throws Exception {
    // The '['-flavoured twin of diagnosticsModeWarnsRatherThanThrowingOnAnUnquotedAngleBracket above, flagged
    // by external review as an untested reclassification: outside ##META's Values, a bracket that closes but
    // has trailing junk after it ("x=[a]b]") is no longer malformed-bracket-checked at all -- it has a ']'
    // somewhere, so it clears findEncodingProblems' universal unterminated-bracket floor and falls through to
    // the same generic "unquoted structural delimiter" check '<'/'>' get (unreadableOutput=false). Before this
    // fix's narrowing, this exact shape was grouped with the genuinely-unterminated case and threw at BOTH
    // construction and validateBeforeWrite(); now it only throws at construction (checkMetadataPropertyEncoding
    // throws for ANY problem, regardless of severity) and warns at validateBeforeWrite(). Control: flipping
    // this back to unreadableOutput=true in InternalUtils.findEncodingProblems must make this fail.
    VcfMetadata md = parseHeader("##contig=<ID=c1,x=[a]b],length=999>").metadata();
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      StringWriter out = new StringWriter();
      assertDoesNotThrow(() ->
          new VcfWriter.Builder().validateBeforeWrite().toWriter(new PrintWriter(out)).build().writeHeader(md));
      assertTrue(appender.anyMessageContains("unquoted structural delimiter"));
      assertThat(out.toString(), containsString("x=[a]b]"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void diagnosticsModeEmitsTheNameWarningOncePerHeaderNotOncePerRecord() throws Exception {
    // diagnosticsModeEmitsTheNameWarningExactlyOnce (above) writes only a header, so it cannot see a regression
    // that put validateMetadataPropertyEncodings(metadata) back inside validateMetadata() (called from every
    // writeLine() too) -- a header-only write would show exactly one warning either way, regression or not.
    // This test writes two data records after the header and asserts the warning still appears exactly once.
    // Control: reintroducing a validateMetadataPropertyEncodings(metadata) call inside VcfWriter.writeLine's
    // validateBeforeWrite() branch must make this fail (three occurrences total instead of one).
    String vcf = "##fileformat=VCFv4.2\n##contig=<ID=c1,a-b=v,length=999>\n"
        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
        + "c1\t1\t.\tA\tT\t.\t.\t.\n"
        + "c1\t2\t.\tA\tT\t.\t.\t.\n";
    List<VcfRecord> records = new ArrayList<>();
    VcfMetadata[] metadataHolder = new VcfMetadata[1];
    try (BufferedReader reader = new BufferedReader(new StringReader(vcf));
         VcfParser parser = new VcfParser.Builder().fromReader(reader).parseWith((md, rec) -> {
           metadataHolder[0] = md;
           records.add(rec);
         }).build()) {
      parser.parse();
    }
    VcfMetadata metadata = metadataHolder[0];
    assertEquals(2, records.size());

    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    WarnCapturingAppender appender = new WarnCapturingAppender();
    appender.start();
    root.addAppender(appender);
    try {
      StringWriter out = new StringWriter();
      VcfWriter writer = new VcfWriter.Builder().validateBeforeWrite().toWriter(new PrintWriter(out)).build();
      writer.writeHeader(metadata);
      for (VcfRecord record : records) {
        writer.writeLine(metadata, record.getPosition(), record.getSamples());
      }
      assertEquals(1, appender.countMessagesContaining("invalid property name"));
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }
}

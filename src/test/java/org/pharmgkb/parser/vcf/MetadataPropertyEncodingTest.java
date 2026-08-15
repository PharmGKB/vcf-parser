package org.pharmgkb.parser.vcf;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.pharmgkb.parser.vcf.spec.WarnCapturingAppender;
import org.slf4j.LoggerFactory;

import static org.pharmgkb.parser.vcf.TestUtils.LENIENT_CONTEXT;
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
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    map.put(IdMetadata.ID, "1");
    map.put(ContigMetadata.ASSEMBLY, assembly);
    return map;
  }

  @Test
  void testAddMapRejectsEachUnquotedDelimiter() {
    // the three verified corruption modes: comma truncates, '=' deletes the attribute, an unescaped quote
    // absorbs every following attribute
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("a,b")));
    assertThrows(VcfFormatException.class, () -> builder().addContig(contigProps("a=b")));
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
  void testAddTypedRejectsAnEqualsSignInTheId() {
    // ID is stored raw too (IdMetadata's typed constructor ends in putPropertyRaw(ID, id)), and the probe
    // character is chosen for durability rather than for today's behavior. Under this file's LENIENT_CONTEXT
    // NEITHER a comma nor a non-leading '=' throws today: IdMetadata's comma check and ContigMetadata's
    // charset/leading-'=' checks all route through BaseMetadata.warnOrThrow, which throws only when
    // !isLenient() (BaseMetadata.java:112-117) and merely warns here. So either probe would isolate the new
    // check as things stand. '=' is used because it stays unambiguous if this test is ever moved to
    // STRICT_CONTEXT: there, a comma in an ID IS rejected by IdMetadata's own check, and for contig also by
    // InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN, so a comma assertThrows would pass with this whole
    // change reverted. That pattern matches 13 characters -- backslash, comma, double quote, backtick, single
    // quote, the two parentheses, the two braces, the two square brackets, and the two angle brackets --
    // deliberately described here rather than re-quoted, since the Java string literal's escaping makes a
    // copied regex easy to transcribe wrong. '=' is not among them, and ContigMetadata rejects only a LEADING
    // '=' (ContigMetadata.java:87), so a non-leading '=' isolates the new check under both contexts.
    assertThrows(VcfFormatException.class,
        () -> builder().addContig("a=b", 100L, null, null, null, null, null));
  }

  @Test
  void testAddTypedAcceptsADelimiterInContigsOwnAutoQuotedField() throws Exception {
    // species is the one contig argument stored via putAndQuoteProperty (ContigMetadata.java:45), which quotes
    // unconditionally (BaseMetadata.java:250), so "a,b" becomes "\"a,b\"" and is safe to emit. Same
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

  // All seven kinds. The comma sits in a raw-stored attribute in each: Description for the five
  // IdDescriptionMetadata-shaped kinds (the Map route stores it verbatim -- only the TYPED route auto-quotes
  // it), assembly for contig, and an arbitrary attribute for PEDIGREE, which has no fixed schema.
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
    // six, not seven: there is no replacePedigree (VcfMetadata.java:885 is addPedigree(Map); the comment at
    // VcfMetadata.java:908-915 explains why -- PEDIGREE is List-backed, not Map-backed, so it has no replace
    // counterpart), so PEDIGREE is add-only and is deliberately absent here.
    record ReplaceRoute(String kind, BiConsumer<VcfMetadata.Builder, Map<String, String>> replace) {}
    ReplaceRoute[] routes = {
        new ReplaceRoute("ALT", VcfMetadata.Builder::replaceAlt),
        new ReplaceRoute("INFO", VcfMetadata.Builder::replaceInfo),
        new ReplaceRoute("FILTER", VcfMetadata.Builder::replaceFilter),
        new ReplaceRoute("FORMAT", VcfMetadata.Builder::replaceFormat),
        new ReplaceRoute("contig", VcfMetadata.Builder::replaceContig),
        new ReplaceRoute("SAMPLE", VcfMetadata.Builder::replaceSample),
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
    for (String kind : new String[] {"ALT", "INFO", "FILTER", "FORMAT", "contig", "SAMPLE"}) {
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
    // The probe is '=' in the ID, because ID is the ONLY raw-stored typed argument shared by all six: the
    // typed constructors route Description (and INFO's Source/Version, and contig's species) through
    // putAndQuoteProperty, which quotes unconditionally, so a delimiter there is legitimately safe. '=' rather
    // than a comma -- see testAddTypedRejectsAnEqualsSignInTheId for the full reasoning; briefly, both work
    // under this file's lenient context, but only '=' also isolates the new check under a strict one.
    record TypedRoute(String kind, Consumer<VcfMetadata.Builder> call) {}
    TypedRoute[] routes = {
        new TypedRoute("ALT", b -> b.addAlt("a=b", "d")),
        new TypedRoute("INFO", b -> b.addInfo("a=b", "d", InfoType.String, "1", null, null)),
        new TypedRoute("FILTER", b -> b.addFilter("a=b", "d")),
        new TypedRoute("FORMAT", b -> b.addFormat("a=b", "d", "1", FormatType.String)),
        new TypedRoute("contig", b -> b.addContig("a=b", 100L, null, null, null, null, null)),
        new TypedRoute("SAMPLE", b -> b.addSample("a=b", "d")),
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
    // containsUnescapedQuote helper -- see testAnUnescapedQuoteInAPropertyNameCorruptsTheFollowingAttributes for
    // the corruption this closes
    for (String badKey : new String[] {"", "bad,key", "bad=key", "a\"b"}) {
      LinkedHashMap<String, String> map = new LinkedHashMap<>();
      map.put(IdMetadata.ID, "1");
      map.put(badKey, "v");
      assertThrows(VcfFormatException.class, () -> builder().addContig(map),
          "expected rejection for key [" + badKey + "]");
    }
  }

  @Test
  void testAnUnescapedQuoteInAPropertyNameCorruptsTheFollowingAttributes() throws Exception {
    // proves WHY a quote-bearing property name must be rejected: parsing "##contig=<ID=1,a"b=v,length=100>"
    // flips splitTopLevel's "inside a quoted value" toggle at the stray quote in the name, so everything from
    // that point on -- the rest of "a"b", "=v", the comma, and the whole "length=100" attribute -- is absorbed
    // rather than split. Verified live: the re-parsed contig has only ID; both "a\"b" and "length" are gone.
    VcfMetadata metadata = parseHeader("##contig=<ID=1,a\"b=v,length=100>").metadata();
    ContigMetadata contig = metadata.getContigs().get("1");
    assertEquals(Map.of(IdMetadata.ID, "1"), contig.getPropertiesRaw());
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
  // an '=' or ',' in the VALUE is harmless. This is NOT the same mechanism as InternalUtils.splitProperty,
  // which rejects a second '=' outright; keeping these tests beside the structured ones above is deliberate,
  // so the two grammars' different delimiters stay visible rather than looking like an inconsistency.

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
      // a stray quote inside a quoted value: kept verbatim, quotes and all
      new RecoveredCase("##FILTER=<ID=q10,Description=\"a\"b\">", "\"a\"b\"",
          "contains an unescaped quote"),
      // an unquoted value ending in a quote: warns TWICE -- the shared unescaped-quote warning plus
      // Description's own "should be quoted but was". Pinning both is what keeps a later change from
      // silencing one of them unnoticed.
      new RecoveredCase("##FILTER=<ID=q10,Description=ab\">", "ab\"",
          "contains an unescaped quote", "should be quoted but was"),
      // non-final quote, the important one: Description ABSORBS the following ",Source=\"s\"" wholesale, so
      // the recovered value carries both a comma and an '=' -- precisely what the new check rejects. There is
      // no Source key in the result at all. This is the case that proves the parse path must stay exempt.
      new RecoveredCase("##INFO=<ID=XX,Number=1,Type=String,Description=\"a\"b\",Source=\"s\">",
          "\"a\"b\",Source=\"s\"", "contains an unescaped quote"),
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
}

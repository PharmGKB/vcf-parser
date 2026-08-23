package org.pharmgkb.parser.vcf;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;


/**
 * VCF metadata for contig=&lt;&gt; elements.
 * @author Douglas Myers-Turnbull
 */
public final class ContigMetadata extends IdMetadata {

  public static final String LENGTH = "length";
  public static final String ASSEMBLY = "assembly";
  public static final String MD5 = "md5";
  public static final String SPECIES = "species";
  public static final String TAXONOMY = "taxonomy";
  public static final String URL = "URL";

  // VCFv4.3.tex line 247 (new at 4.3; unchanged at VCFv4.4.tex:261/VCFv4.5.tex:310; VCFv4.1/4.2 show md5= only
  // in worked examples, with no accompanying format definition, predating this rule rather than conflicting
  // with it): the MD5 digest "is calculated as described in RFC 1321 and presented as a 32 character
  // lowercase hexadecimal number."
  private static final Pattern MD5_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

  /**
   * @param length {@code null} if absent -- VCFv4.2.tex line 138 documents {@code length} as "highly
   * recommended (but not required)" for {@code ##contig}, so a caller building a minimal, spec-compliant
   * declaration must be able to omit it, the same as {@code assembly}/{@code md5}/{@code species}/
   * {@code taxonomy}/{@code url}
   * @param assembly {@code null} if absent (see VCFv4.2.tex line 121, "all tags are optional")
   */
  ContigMetadata(VcfContext context, @Nullable String id, @Nullable Long length, @Nullable String assembly,
      @Nullable String md5, @Nullable String species, @Nullable String taxonomy, @Nullable String url) {
    super(context, "CONTIG", id);
    if (length != null) {
      putPropertyRaw(LENGTH, String.valueOf(length));
    }
    if (assembly != null) {
      putPropertyRaw(ASSEMBLY, assembly);
    }
    if (md5 != null) {
      putPropertyRaw(MD5, md5);
    }
    if (species != null) {
      putAndQuoteProperty(SPECIES, species);
    }
    if (taxonomy != null) {
      putPropertyRaw(TAXONOMY, taxonomy);
    }
    if (url != null) {
      putPropertyRaw(URL, url);
    }
    // length's not-a-number check in init() is structurally unreachable here (length, if present, is already a
    // parsed Long, never a malformed string) -- no equivalent check added for it; a null length is legitimately
    // absent per VCFv4.2.tex line 138, not malformed. The negative-length check IS reachable here, unlike the
    // not-a-number one: a Long can still be negative, so this mirrors init()'s own check (see its comment).
    if (length != null && length < 0) {
      warnOrThrow("{} length \"{}\" is negative", getLabel(), length);
    }
    if (url != null) {
      // Checked unquoted -- see IdMetadata's typed constructor's identical comment for why (there is no
      // stored property to read yet, so this unquotes the constructor parameter directly via
      // InternalUtils.unquote rather than getPropertyUnquoted; url itself is still stored raw above).
      String unquotedUrl = InternalUtils.unquote(url);
      try {
        URI.create(unquotedUrl).toURL();
      } catch (IllegalArgumentException | MalformedURLException e) {
        warnOrThrow("{} URL {} is malformed: {}", getLabel(), unquotedUrl, e.getMessage());
      }
    }
    if (md5 != null) {
      // Checked unquoted, same reasoning as the URL check just above.
      String unquotedMd5 = InternalUtils.unquote(md5);
      if (!MD5_PATTERN.matcher(unquotedMd5).matches()) {
        warnOrThrow("{} md5 \"{}\" is not a 32-character lowercase hexadecimal MD5 digest", getLabel(),
            unquotedMd5);
      }
    }
    // VCFv4.3.tex (the target) states this fuller character-set grammar for ##contig IDs, beyond IdMetadata's
    // own shared whitespace/comma checks above, in its own "Changes to VCFv4.3" errata (~line 2129-2144,
    // unchanged in 4.4/4.5), which states the rule for "VCF contig names" specifically; VCFv4.2.tex predates
    // this grammar rather than conflicting with it -- confirmed by direct bcftools 1.23.1 testing that this does
    // NOT extend to INFO/FORMAT tag names, which bcftools validates under a different, stricter rule (see
    // VCF_SPEC_AMBIGUITIES.md's "##contig ID character set" entry). InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN itself has 13 characters,
    // including a comma, so its coverage overlaps IdMetadata's own dedicated comma check above. Under a strict
    // context that overlap is unreachable -- IdMetadata's own super("CONTIG", id) call throws on any
    // comma-containing ID before this check runs. Under a lenient context it can be reached, so this loops and
    // skips a comma match explicitly (IdMetadata's own super(...) call already warned about it), the same as
    // init()'s own version of this check just below -- a single non-looping matcher.find() previously stopped
    // at the first match, which silently dropped a second, genuinely different invalid character whenever a
    // comma happened to come first (e.g. "chr,(1)" warned about the comma alone, never the '('); looping fixes
    // that regardless of which invalid character comes first, matching init()'s own comment for the identical
    // reasoning.
    // id can also be null or empty here under a lenient context (IdMetadata's own super(...) call warns rather
    // than throwing for either) -- guard rather than letting id.charAt(0) raw-crash.
    if (id != null && !id.isEmpty()) {
      // Checked unquoted, same reasoning as the URL check just above.
      String unquotedId = InternalUtils.unquote(id);
      if (!unquotedId.isEmpty()) {
        char first = unquotedId.charAt(0);
        if (first == '*' || first == '=') {
          warnOrThrow("{} ID \"{}\" must not start with '{}'", getLabel(), unquotedId, first);
        }
        Matcher matcher = InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN.matcher(unquotedId);
        Set<String> warnedChars = null;
        while (matcher.find()) {
          String invalid = matcher.group();
          if (invalid.equals(",")) {
            continue;
          }
          if (warnedChars == null) {
            warnedChars = new HashSet<>();
          }
          if (warnedChars.add(invalid)) {
            warnOrThrow("{} ID \"{}\" contains the invalid character '{}'", getLabel(), unquotedId, invalid);
          }
        }
        // see InternalUtils.NON_PRINTABLE_ASCII_CONTIG_NAME_CHAR_PATTERN's own comment -- the same errata's
        // outer bound ([!-~], VCFv4.3.tex:258-259), never enforced by the blocklist just above
        Matcher nonPrintableMatcher = InternalUtils.NON_PRINTABLE_ASCII_CONTIG_NAME_CHAR_PATTERN.matcher(unquotedId);
        if (nonPrintableMatcher.find()) {
          warnOrThrow("{} ID \"{}\" contains a non-printable-ASCII character (code point {})", getLabel(),
              unquotedId, (int) nonPrintableMatcher.group().charAt(0));
        }
        // VCFv4.3.tex line 266 (the target, unchanged through 4.4/4.5): "The contig names must not use a
        // reserved symbolic allele name." New at 4.3 -- the sentence doesn't appear in VCFv4.1.tex/VCFv4.2.tex.
        // See ReservedStructuralVariantCode.isReservedSymbolicAlleleName's own comment for exactly which names
        // count as reserved (e.g. "DEL", "DUP:TANDEM" -- not a custom subtype like "INS:ME:LINE").
        if (ReservedStructuralVariantCode.isReservedSymbolicAlleleName(unquotedId)) {
          warnOrThrow("{} ID \"{}\" must not use a reserved symbolic allele name", getLabel(), unquotedId);
        }
      }
    }
  }

  ContigMetadata(VcfContext context, Map<String, String> properties) {
    super(context, "CONTIG", properties);
    init();
  }

  /**
   * @throws VcfFormatException if {@code length} is absent (an optional tag -- see {@link #getAssembly()}'s
   * Javadoc for the same absent-is-normal caveat; check {@code getPropertiesRaw().containsKey(LENGTH)} first if
   * distinguishing "absent" from "present but malformed" matters to the caller) or not a valid number
   */
  @SuppressWarnings("ConstantConditions")
  public long getLength() {
    // getPropertyUnquoted, not getPropertyRaw, matching init()'s own validation below -- a quoted length
    // (e.g. length="249250621") is otherwise silently accepted at construction (init() already unquotes
    // before checking) but threw here, since this getter still parsed the raw, still-quoted value
    String length = getPropertyUnquoted(LENGTH);
    long parsedLength;
    try {
      parsedLength = Long.parseLong(length);
    } catch (NumberFormatException e) {
      throw new VcfFormatException("Contig " + getId() + " length '" + length + "' is not a number", e);
    }
    // matches init()'s own negative-length check (see its comment): a negative sequence length is just as
    // unusable to a caller asking for a real long value as a non-numeric one is, so this typed getter throws
    // unconditionally for both -- the same shape init()'s own not-a-number branch above already uses, not a
    // new leniency policy choice. init() itself only warns under a lenient context and still stores the
    // value, so a caller reading it back here still needs its own floor.
    if (parsedLength < 0) {
      throw new VcfFormatException("Contig " + getId() + " length '" + length + "' is negative");
    }
    return parsedLength;
  }

  /**
   * @return The {@code assembly} tag's value, <em>unquoted</em>: a declaration written
   * {@code assembly="GRCh38"} returns {@code GRCh38}, with any {@code \"}/{@code \\} escapes decoded. Quoting
   * is an encoding of the header syntax, not part of the value -- see VALIDATION_PHILOSOPHY.md's
   * value/encoding rule. Use {@link #getPropertyRaw(String) getPropertyRaw(assembly)} for the original
   * spelling as it appeared in the file.
   * <p>
   * Null when the optional {@code assembly} tag is absent (spec-compliant -- see VCFv4.2.tex line 121,
   * "all tags are optional") or
   * otherwise unusable.
   */
  @Nullable
  public String getAssembly() {
    return getPropertyUnquoted(ASSEMBLY);
  }

  /**
   * @return The {@code taxonomy} tag's value, <em>unquoted</em>: a declaration written {@code taxonomy="9606"}
   * returns {@code 9606}, with any {@code \"}/{@code \\} escapes decoded. Quoting is an encoding of the header
   * syntax, not part of the value -- see VALIDATION_PHILOSOPHY.md's value/encoding rule. Use
   * {@link #getPropertyRaw(String) getPropertyRaw(taxonomy)} for the original spelling as it appeared in the
   * file.
   * <p>
   * Null when the optional {@code taxonomy} tag is absent (spec-compliant -- see VCFv4.2.tex line 121,
   * "all tags are optional").
   */
  @Nullable
  public String getTaxonomy() {
    return getPropertyUnquoted(TAXONOMY);
  }

  @Nullable
  public String getSpecies() {
    return getPropertyUnquoted(SPECIES);
  }

  /**
   * @return The {@code md5} tag's value, <em>unquoted</em>: a declaration written {@code md5="d7d0..."}
   * returns {@code d7d0...}, with any {@code \"}/{@code \\} escapes decoded. Quoting is an encoding of the
   * header syntax, not part of the value -- see VALIDATION_PHILOSOPHY.md's value/encoding rule. Use
   * {@link #getPropertyRaw(String) getPropertyRaw(md5)} for the original spelling as it appeared in the file.
   * <p>
   * Null when the optional {@code md5} tag is absent (spec-compliant -- see VCFv4.2.tex line 121,
   * "all tags are optional").
   */
  @Nullable
  public String getMd5() {
    return getPropertyUnquoted(MD5);
  }

  /**
   * @return The {@code URL} tag's value, <em>unquoted</em>: a declaration written {@code URL="ftp://x/y.fa"}
   * returns {@code ftp://x/y.fa}, with any {@code \"}/{@code \\} escapes decoded. Quoting is an encoding of
   * the header syntax, not part of the value -- see VALIDATION_PHILOSOPHY.md's value/encoding rule. Use
   * {@link #getPropertyRaw(String) getPropertyRaw(URL)} for the original spelling as it appeared in the file.
   * <p>
   * Null when the optional {@code URL} tag is absent (spec-compliant -- see VCFv4.2.tex line 121,
   * "all tags are optional").
   */
  @Nullable
  public String getUrl() {
    return getPropertyUnquoted(URL);
  }

  private void init() {
    // assembly and length are optional, not required: VCFv4.2.tex line 121 ("all tags are optional" for the
    // ##contig/##assembly header tags in general) and line 138 ("highly recommended (but not required)"
    // specifically for ##contig) both say so directly -- unlike ID (required by IdMetadata's own init()),
    // neither of these has any spec basis for a missing-value warning
    String length = getPropertyUnquoted(LENGTH);
    if (length != null) {
      try {
        long parsedLength = Long.parseLong(length);
        // no VCF version states this explicitly (unlike md5/URL below, which at least have a format this
        // library checks against), but a negative sequence length is meaningless -- the same parity gap the
        // md5/URL checks below already close for their own optional attributes. Confirmed live (bcftools
        // 1.23.1): a negative length causes the entire ##contig declaration to be silently discarded on
        // ingestion ("Contig '1' is not defined in the header", declaration absent from bcftools' own
        // re-emitted output) -- not merely a warning about the length value itself, so this is a case where
        // bcftools' behavior is a real signal, not silence.
        if (parsedLength < 0) {
          warnOrThrow("{} length \"{}\" is negative", getLabel(), length);
        }
      } catch (NumberFormatException e) {
        warnOrThrow("{} length \"{}\" is not a number", getLabel(), length);
      }
    }
    // HTS_SPECS_BASELINE_STATUS.md's "Non-valid URL fixtures" section: "Neither VCFv4.1, VCFv4.2, nor VCFv4.3
    // defines a formal grammar for the url value in ##assembly=url / ##pedigreeDB=URL" -- the same is true of
    // ##contig's url tag, so this check is a library-level well-formedness convenience with no spec mandate,
    // unlike the ID/length checks above.
    // getPropertyUnquoted, not getPropertyRaw: this is well-formedness validation (URI.create/toURL), which
    // must see the URL's actual content rather than its header-syntax quoting -- see AGENTS.md's "Unquoting
    // before internal use" policy. A quoted URL (e.g. URL="http://example.com") otherwise failed this check
    // purely because of its own surrounding quote characters.
    String url = getPropertyUnquoted(URL);
    if (url != null) {
      try {
        URI.create(url).toURL();
      } catch (IllegalArgumentException | MalformedURLException e) {
        warnOrThrow("{} URL {} is malformed: {}", getLabel(), url, e.getMessage());
      }
    }
    // see the typed constructor's identical check for the citation
    String md5 = getPropertyUnquoted(MD5);
    if (md5 != null && !MD5_PATTERN.matcher(md5).matches()) {
      warnOrThrow("{} md5 \"{}\" is not a 32-character lowercase hexadecimal MD5 digest", getLabel(), md5);
    }
    // getPropertyUnquoted: the character-set/first-character checks below must see the ID's actual content,
    // per AGENTS.md's "Unquoting before internal use" policy -- see this method's own URL comment just above.
    String id = getPropertyUnquoted(ID);
    if (id != null && !id.isEmpty()) {
      char first = id.charAt(0);
      if (first == '*' || first == '=') {
        warnOrThrow("{} ID \"{}\" must not start with '{}'", getLabel(), id, first);
      }
      Matcher matcher = InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN.matcher(id);
      // a comma match is skipped (searching continues past it, rather than stopping) because IdMetadata's own
      // dedicated comma check already warned about it earlier during construction (via super(...), before this
      // init() ever runs) -- without "continue searching" here, an id containing both a comma and a genuinely
      // new defect (e.g. "1,(2)") would never reach the defect this check exists to catch, since the comma
      // would be the first match found. Searching continues past every non-comma match too (no break), so a
      // SECOND genuinely different invalid character (e.g. "1(2)[3]" has both '(' and '[') is also reported,
      // not silently dropped -- warnedChars dedupes only exact repeats of the same character (e.g. "1((2"),
      // matching InfoMetadata/FormatMetadata's own "one warning per genuinely distinct defect" precedent
      // (commit f2fed6f), adapted for a per-character scan instead of a whole-string pattern.
      Set<String> warnedChars = null;
      while (matcher.find()) {
        String invalid = matcher.group();
        if (invalid.equals(",")) {
          continue;
        }
        if (warnedChars == null) {
          warnedChars = new HashSet<>();
        }
        if (warnedChars.add(invalid)) {
          warnOrThrow("{} ID \"{}\" contains the invalid character '{}'", getLabel(), id, invalid);
        }
      }
      // see the typed constructor's identical check for the citation
      Matcher nonPrintableMatcher = InternalUtils.NON_PRINTABLE_ASCII_CONTIG_NAME_CHAR_PATTERN.matcher(id);
      if (nonPrintableMatcher.find()) {
        warnOrThrow("{} ID \"{}\" contains a non-printable-ASCII character (code point {})", getLabel(), id,
            (int) nonPrintableMatcher.group().charAt(0));
      }
      // see the typed constructor's identical check for the VCFv4.3.tex:266 citation
      if (ReservedStructuralVariantCode.isReservedSymbolicAlleleName(id)) {
        warnOrThrow("{} ID \"{}\" must not use a reserved symbolic allele name", getLabel(), id);
      }
    }
    // No unrecognized-attribute check here, matching InfoMetadata/FormatMetadata/IdDescriptionMetadata's
    // identical no-warn-on-extras treatment: VCFv4.2.tex line 123's "extra fields can be included after the default
    // fields" sentence doesn't name ##contig directly, but ##contig's own worked example a few lines later
    // (line 140) ends in "..." (`##contig=<ID=ctg1,URL=ftp://somewhere.org/assembly.fa,...>`), signaling the
    // identical "more tags may follow" contract. Confirmed against hts-specs' own passed/passed_meta_contig.vcf
    // fixture, which declares an unmodeled `accession` attribute and is expected to parse without warning, and
    // against bcftools 1.23.1, which silently round-trips a custom contig attribute with zero warning.
  }

  @Override
  public void validate() {
    super.validate();
    init();
  }

}

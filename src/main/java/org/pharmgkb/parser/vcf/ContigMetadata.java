package org.pharmgkb.parser.vcf;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
    // absent per VCFv4.2.tex line 138, not malformed.
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
    // VCFv4.3.tex (the target) states this fuller character-set grammar for ##contig IDs, beyond IdMetadata's
    // own shared whitespace/comma checks above, in its own "Changes to VCFv4.3" errata (~line 2129-2144,
    // unchanged in 4.4/4.5), which states the rule for "VCF contig names" specifically; VCFv4.2.tex predates
    // this grammar rather than conflicting with it -- confirmed by direct bcftools 1.23.1 testing that this does
    // NOT extend to INFO/FORMAT tag names, which bcftools validates under a different, stricter rule (see
    // VCF_SPEC_AMBIGUITIES.md's "##contig ID character set" entry). InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN itself has 13 characters,
    // including a comma, so its coverage overlaps IdMetadata's own dedicated comma check above. Under a strict
    // context that overlap is unreachable -- IdMetadata's own super("CONTIG", id) call throws on any
    // comma-containing ID before this check runs -- so the single (non-looping) matcher.find() below has no
    // comma case to special-case. Under a lenient context it can be reached, and then a comma-containing ID
    // gets one warning here in addition to IdMetadata's own; init()'s own version of this check just below
    // instead skips a comma match explicitly, since it also has to keep searching past it for a genuinely
    // different defect -- see that loop's own comment.
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
        if (matcher.find()) {
          warnOrThrow("{} ID \"{}\" contains the invalid character '{}'", getLabel(), unquotedId, matcher.group());
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
    try {
      return Long.parseLong(length);
    } catch (NumberFormatException e) {
      throw new VcfFormatException("Contig " + getId() + " length '" + length + "' is not a number", e);
    }
  }

  /**
   * @return The {@code assembly} tag's value, <em>unquoted</em>: a declaration written
   * {@code assembly="GRCh38"} returns {@code GRCh38}, with any {@code \"}/{@code \\} escapes decoded. Quoting
   * is an encoding of the header syntax, not part of the value -- see VALIDATION_PHILOSOPHY.md's
   * value/encoding rule. Use {@link #getPropertyRaw(String) getPropertyRaw(assembly)} for the original
   * spelling as it appeared in the file.
   * <p>
   * Null when the optional {@code assembly} tag is absent (spec-compliant -- see VCFv4.2.tex line 121) or
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
   * Null when the optional {@code taxonomy} tag is absent (spec-compliant -- see VCFv4.2.tex line 121).
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
   * Null when the optional {@code md5} tag is absent (spec-compliant -- see VCFv4.2.tex line 121).
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
   * Null when the optional {@code URL} tag is absent (spec-compliant -- see VCFv4.2.tex line 121).
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
        //noinspection ResultOfMethodCallIgnored
        Long.parseLong(length);
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

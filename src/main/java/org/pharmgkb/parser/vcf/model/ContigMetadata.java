package org.pharmgkb.parser.vcf.model;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * VCF metadata for contig=&lt;&gt; elements.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadata extends IdMetadata {

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
  public ContigMetadata(String id, @Nullable Long length, @Nullable String assembly, @Nullable String md5,
      @Nullable String species, @Nullable String taxonomy, @Nullable String url) {
    super("CONTIG", id);
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
    // strict construction: length's not-a-number check in init() is structurally unreachable here (length, if
    // present, is already a parsed Long, never a malformed string) -- no throw-equivalent added for it; a null
    // length is legitimately absent per VCFv4.2.tex line 138, not malformed.
    if (url != null) {
      try {
        URI.create(url).toURL();
      } catch (IllegalArgumentException | MalformedURLException e) {
        throw new VcfFormatException(getLabel() + " URL " + url + " is malformed: " + e.getMessage(), e);
      }
    }
    // VCFv4.2.tex doesn't table a character-set grammar for ##contig IDs beyond IdMetadata's own shared
    // whitespace/comma checks above; this fuller grammar is VCFv4.2-silent clarifying guidance from
    // VCFv4.3.tex's own "Changes to VCFv4.3" errata (~line 2129-2144, unchanged in 4.4/4.5), which states the
    // rule for "VCF contig names" specifically -- confirmed by direct bcftools 1.23.1 testing that this does
    // NOT extend to INFO/FORMAT tag names, which bcftools validates under a different, stricter rule (see
    // VCF_SPEC_AMBIGUITIES.md's "##contig ID character set" entry). InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN itself has 13 characters,
    // including a comma, so its coverage overlaps IdMetadata's own dedicated comma check above. This overlap
    // is harmless on this strict/throw path regardless: IdMetadata's own constructor (the super("CONTIG", id)
    // call above) already throws on any comma-containing ID before this pattern check ever runs, so the
    // single (non-looping) matcher.find() below never actually needs to special-case a comma match here --
    // unlike init()'s own version of this check just below (the lenient/warn path, reached by the Map-based
    // constructor), which DOES reach a comma-containing id and explicitly skips re-warning about it there,
    // deferring to IdMetadata's own warning -- see that loop's own comment for why.
    char first = id.charAt(0);
    if (first == '*' || first == '=') {
      throw new VcfFormatException(getLabel() + " ID \"" + id + "\" must not start with '" + first + "'");
    }
    Matcher matcher = InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN.matcher(id);
    if (matcher.find()) {
      throw new VcfFormatException(getLabel() + " ID \"" + id + "\" contains the invalid character '" +
          matcher.group() + "'");
    }
  }

  public ContigMetadata(Map<String, String> properties, VcfWarnings warnings) {
    super("CONTIG", properties, warnings);
    init();
    // ContigMetadata extends IdMetadata directly, not IdDescriptionMetadata -- IdMetadata's own Map-based
    // constructor doesn't call checkAttributeOrder() (only validate() does), unlike IdDescriptionMetadata's
    // Map-based constructor, which calls it directly and is how InfoMetadata/FormatMetadata (which extend
    // IdDescriptionMetadata) inherit the same check at construction time. Call it explicitly here to match.
    checkAttributeOrder();
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
   * @return Null when the optional {@code assembly} tag is absent (spec-compliant -- see VCFv4.2.tex line 121)
   * or otherwise unusable
   */
  @Nullable
  public String getAssembly() {
    return getPropertyRaw(ASSEMBLY);
  }

  @Nullable
  public String getTaxonomy() {
    return getPropertyRaw(TAXONOMY);
  }

  @Nullable
  public String getSpecies() {
    return getPropertyUnquoted(SPECIES);
  }

  @Nullable
  public String getMd5() {
    return getPropertyRaw(MD5);
  }

  @Nullable
  public String getUrl() {
    return getPropertyRaw(URL);
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
        m_warnings.warn("{} length \"{}\" is not a number", getLabel(), length);
      }
    }
    // HTS_SPECS_BASELINE_STATUS.md's "Non-valid URL fixtures" section: "Neither VCFv4.1, VCFv4.2, nor VCFv4.3
    // defines a formal grammar for the url value in ##assembly=url / ##pedigreeDB=URL" -- the same is true of
    // ##contig's url tag, so this check is a library-level well-formedness convenience with no spec mandate,
    // unlike the ID/length checks above.
    String url = getPropertyRaw(URL);
    if (url != null) {
      try {
        URI.create(url).toURL();
      } catch (IllegalArgumentException | MalformedURLException e) {
        m_warnings.warn("{} URL {} is malformed: {}", getLabel(), url, e.getMessage());
      }
    }
    String id = getId();
    if (id != null && !id.isEmpty()) {
      char first = id.charAt(0);
      if (first == '*' || first == '=') {
        m_warnings.warn("{} ID \"{}\" must not start with '{}'", getLabel(), id, first);
      }
      Matcher matcher = InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN.matcher(id);
      while (matcher.find()) {
        // a comma match is skipped (searching continues past it, rather than stopping) because IdMetadata's
        // own dedicated comma check already warned about it earlier during construction (via super(...),
        // before this init() ever runs) -- without "continue searching" here, an id
        // containing both a comma and a genuinely new defect (e.g. "1,(2)") would never reach the defect this
        // check exists to catch, since the comma would be the first match found
        if (!matcher.group().equals(",")) {
          m_warnings.warn("{} ID \"{}\" contains the invalid character '{}'", getLabel(), id, matcher.group());
          break;
        }
      }
    }
    // No ensureNoExtras() call here, matching InfoMetadata/FormatMetadata/IdDescriptionMetadata's identical
    // no-warn-on-extras treatment: VCFv4.2.tex line 123's "extra fields can be included after the default
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

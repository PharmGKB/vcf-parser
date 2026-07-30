package org.pharmgkb.parser.vcf.model;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * VCF metadata for contig=&lt;&gt; elements.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadata extends IdMetadata {

  public static final String ID = "ID";
  public static final String LENGTH = "length";
  public static final String ASSEMBLY = "assembly";
  public static final String MD5 = "md5";
  public static final String SPECIES = "species";
  public static final String TAXONOMY = "taxonomy";
  public static final String URL = "URL";

  public ContigMetadata(String id, long length, String assembly, @Nullable String md5,
      @Nullable String species, @Nullable String taxonomy, @Nullable String url) {
    super("CONTIG", id);
    putPropertyRaw(LENGTH, String.valueOf(length));
    putPropertyRaw(ASSEMBLY, assembly);
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
    init();
  }

  public ContigMetadata(Map<String, String> properties) {
    super("CONTIG", properties);
    init();
  }

  /**
   * @throws VcfFormatException if {@code length} is absent (an optional tag -- see {@link #getAssembly()}'s
   * Javadoc for the same absent-is-normal caveat; check {@code getPropertiesRaw().containsKey(LENGTH)} first if
   * distinguishing "absent" from "present but malformed" matters to the caller) or not a valid number
   */
  @SuppressWarnings("ConstantConditions")
  public long getLength() {
    String length = getPropertyRaw(LENGTH);
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
        VcfWarnings.warn("{} length \"{}\" is not a number", getLabel(), length);
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
        VcfWarnings.warn("{} URL {} is malformed: {}", getLabel(), url, e.getMessage());
      }
    }
    ensureNoExtras(ID, LENGTH, ASSEMBLY, MD5, SPECIES, TAXONOMY, URL);
  }

  @Override
  public void validate() {
    super.validate();
    init();
  }

}

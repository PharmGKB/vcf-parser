package org.pharmgkb.parser.vcf.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

/**
 * VCF metadata for contig=&lt;&gt; elements.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadata extends IdMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";
  public static final String LENGTH = "length";
  public static final String ASSEMBLY = "assembly";
  public static final String MD5 = "md5";
  public static final String SPECIES = "species";
  public static final String TAXONOMY = "taxonomy";
  public static final String URL = "URL";

  public ContigMetadata(@Nonnull String id, long length, @Nonnull String assembly, @Nullable String md5,
      @Nullable String species, @Nullable String taxonomy, @Nullable String url) {
    super(id, false);
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
      try {
        new URL(url);
      } catch (MalformedURLException e) {
        sf_logger.warn("URL {} is malformed", url, e);
      }
    }
    putPropertyRaw(URL, url);
    init();
  }

  public ContigMetadata(@Nonnull Map<String, String> properties) {
    super(properties, false);
    init();
  }

  @SuppressWarnings("ConstantConditions")
  public long getLength() {
    return Long.parseLong(getPropertyRaw(LENGTH));
  }

  /**
   * @return Null only when invalid
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
    if (getPropertyUnquoted(ASSEMBLY) == null) {
      sf_logger.warn("Required metadata property \"{}\" is missing", ASSEMBLY);
    }
    String length = getPropertyUnquoted(LENGTH);
    if (length == null) {
      sf_logger.warn("Required metadata property \"{}\" is missing", LENGTH);
    } else {
      try {
        //noinspection ResultOfMethodCallIgnored
        Long.parseLong(length);
      } catch (NumberFormatException e) {
        sf_logger.warn("Length is not a number", e);
      }
    }
    ensureNoExtras(ID, LENGTH, ASSEMBLY, MD5, SPECIES, TAXONOMY, URL);
  }

}

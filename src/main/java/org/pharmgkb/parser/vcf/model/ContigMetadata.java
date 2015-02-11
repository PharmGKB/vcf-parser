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
    getProperties().put(LENGTH, String.valueOf(length));
    getProperties().put(ASSEMBLY, assembly);
    if (md5 != null) {
      getProperties().put(MD5, md5);
    }
    if (species != null) {
      getProperties().put(SPECIES, species);
    }
    if (taxonomy != null) {
      getProperties().put(TAXONOMY, taxonomy);
    }
    if (url != null) {
      try {
        new URL(url);
      } catch (MalformedURLException e) {
        sf_logger.warn("URL {} is malformed", url, e);
      }
    }
    getProperties().put(URL, url);
    init();
  }

  public ContigMetadata(@Nonnull Map<String, String> properties) {
    super(properties, false);
    init();
  }

  @SuppressWarnings("ConstantConditions")
  public long getLength() {
    return Long.parseLong(getProperty(LENGTH));
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getAssembly() {
    return getProperty(ASSEMBLY);
  }

  @Nullable
  public String getTaxonomy() {
    return getProperty(TAXONOMY);
  }

  @Nullable
  public String getSpecies() {
    return getProperty(SPECIES);
  }

  @Nullable
  public String getMd5() {
    return getProperty(MD5);
  }

  @Nullable
  public String getUrl() {
    return getProperty(URL);
  }

  private void init() {
    if (getProperty(ASSEMBLY) == null) {
      throw new IllegalArgumentException("Required metadata property \"" + ASSEMBLY + "\" is missing");
    }
    String length = getProperty(LENGTH);
    if (length == null) {
      throw new IllegalArgumentException("Required metadata property \"" + LENGTH + "\" is missing");
    }
    try {
      //noinspection ResultOfMethodCallIgnored
      Long.parseLong(length);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Length is not a number", e);
    }
    ensureNoExtras(ID, LENGTH, ASSEMBLY, MD5, SPECIES, TAXONOMY, URL);
  }

}

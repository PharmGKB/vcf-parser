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
  public static final String URL = "URL";

  public ContigMetadata(@Nonnull String id, @Nullable String url) {
    super(id);
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
  @Nullable
  public String getUrl() {
    return getProperty(URL);
  }

  private void init() {
    ensureNoExtras(ID, URL);
  }

}

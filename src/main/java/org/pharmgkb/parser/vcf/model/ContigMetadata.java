package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * VCF metadata for contig=&lt;&gt; elements.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadata extends IdMetadata {

  public static final String ID = "ID";
  public static final String URL = "URL";

  public ContigMetadata(@Nonnull String id, @Nullable String url) {
    super(id);
    getProperties().put(URL, url);
  }

  public ContigMetadata(@Nonnull Map<String, String> properties) {
    super(properties);
  }

  @SuppressWarnings("ConstantConditions")
  @Nullable
  public String getUrl() {
    return getProperty(URL);
  }

}

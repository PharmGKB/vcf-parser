package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;

/**
 * VCF metadata for contig=&lt;&gt; elements.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadata extends BaseMetadata {

  public ContigMetadata(@Nonnull String[] props) {
    super(props);
    if (getProperty("ID") == null) {
      throw new IllegalArgumentException("Required metadata property \"ID\" is missing");
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getId() {
    return getProperty("ID");
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getURL() {
    return getProperty("URL");
  }

}

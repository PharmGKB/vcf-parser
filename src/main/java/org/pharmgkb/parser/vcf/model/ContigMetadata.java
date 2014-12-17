package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * VCF metadata for contig=&lt;&gt; elements.
 * @author Douglas Myers-Turnbull
 */
public class ContigMetadata extends IdMetadata {

  public ContigMetadata(@Nonnull String[] props) {
    super(props);
  }

  @SuppressWarnings("ConstantConditions")
  @Nullable
  public String getUrl() {
    return getProperty("URL");
  }

}

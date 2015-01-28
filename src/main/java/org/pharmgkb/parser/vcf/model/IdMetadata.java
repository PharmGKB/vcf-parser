package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;

/**
 * A VCF metadata element with an Id (called "ID").
 */
public class IdMetadata extends BaseMetadata {

  public IdMetadata(@Nonnull String... props) {
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

}

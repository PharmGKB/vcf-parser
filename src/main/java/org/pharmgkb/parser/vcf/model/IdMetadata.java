package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * A VCF metadata element with an Id (called "ID").
 */
public class IdMetadata extends BaseMetadata {

  public static final String ID = "ID";

  public IdMetadata(@Nonnull String id) {
    super(new HashMap<>());
    getProperties().put(ID, id);
    init();
  }

  public IdMetadata(@Nonnull Map<String, String> properties) {
    super(properties);
    init();
  }

  private void init() {
    if (getProperty(ID) == null) {
      throw new IllegalArgumentException("Required metadata property \"" + ID + "\" is missing");
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getId() {
    return getProperty(ID);
  }

}

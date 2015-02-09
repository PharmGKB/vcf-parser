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
    init(true);
  }

  public IdMetadata(@Nonnull Map<String, String> properties, boolean isBaseType) {
    super(properties);
    init(isBaseType);
  }

  private void init(boolean isBaseType) {
    if (getProperty(ID) == null) {
      throw new IllegalArgumentException("Required metadata property \"" + ID + "\" is missing");
    }
    if (isBaseType) {
      ensureNoExtras(ID);
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getId() {
    return getProperty(ID);
  }

}
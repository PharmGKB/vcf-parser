package org.pharmgkb.parser.vcf.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

/**
 * A VCF metadata element with an Id (called "ID").
 */
public class IdMetadata extends BaseMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";

  public IdMetadata(@Nonnull String id) {
    this(id, true);
  }

  public IdMetadata(@Nonnull Map<String, String> properties) {
    this(properties, true);
  }

  protected IdMetadata(@Nonnull String id, boolean isBaseType) {
    super(new HashMap<>());
    putPropertyRaw(ID, id);
    init(isBaseType);
  }

  protected IdMetadata(@Nonnull Map<String, String> properties, boolean isBaseType) {
    super(properties);
    init(isBaseType);
  }

  private void init(boolean isBaseType) {
    if (getPropertyRaw(ID) == null) {
      sf_logger.warn("Required metadata property \"{}\" is missing", ID);
    }
    if (isBaseType) {
      ensureNoExtras(ID);
    }
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public String getId() {
    return getPropertyRaw(ID);
  }

}

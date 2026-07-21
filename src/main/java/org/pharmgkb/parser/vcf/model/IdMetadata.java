package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A VCF metadata element with an Id (called "ID").
 */
public class IdMetadata extends BaseMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";

  public IdMetadata(String id) {
    this(id, true);
  }

  public IdMetadata(Map<String, String> properties) {
    this(properties, true);
  }

  protected IdMetadata(String id, boolean isBaseType) {
    super(new HashMap<>());
    putPropertyRaw(ID, id);
    init(isBaseType);
  }

  protected IdMetadata(Map<String, String> properties, boolean isBaseType) {
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

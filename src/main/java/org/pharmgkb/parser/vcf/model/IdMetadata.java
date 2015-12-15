package org.pharmgkb.parser.vcf.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A VCF metadata element with an Id (called "ID").
 */
public class IdMetadata extends BaseMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";

  protected IdMetadata() {
    super();
  }

  @Override
  protected void init() {
    super.init();
    if (getPropertyRaw(ID) == null) {
      sf_logger.warn("Required metadata property \"{}\" is missing", ID);
    }
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nonnull
  public String getId() {
    return getPropertyRaw(ID);
  }

}

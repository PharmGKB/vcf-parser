package org.pharmgkb.parser.vcf.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * This class represents a single VCF metadata line with an id and description.
 *
 * In 4.1/4.2:
 * <code>
 * ##ALT=<ID=type,Description="description">
 * ##FILTER=<ID=ID,Description="description">
 * </code>
 *
 * @author Mark Woon
 */
public class IdDescriptionMetadata extends IdMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";

  protected IdDescriptionMetadata() {
    super();
  }

  protected void init() {
    super.init();
    if (getPropertyRaw(DESCRIPTION) == null) {
      sf_logger.warn("Required metadata property \"{}\" is missing", DESCRIPTION);
    }
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public String getDescription() {
    return getPropertyUnquoted(DESCRIPTION);
  }

}

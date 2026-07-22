package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class represents a single VCF metadata line with an id and description.
 * <p>
 * In 4.1/4.2:
 * <pre>{@code
 * ##ALT=<ID=type,Description="description">
 * ##FILTER=<ID=ID,Description="description">
 * }
 * </pre>
 *
 * @author Mark Woon
 */
public class IdDescriptionMetadata extends IdMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";

  public IdDescriptionMetadata(String id, String description) {
    this(id, description, true);
  }

  public IdDescriptionMetadata(Map<String, String> properties, boolean isBaseType) {
    super(properties, false);
    init(isBaseType);
  }

  protected IdDescriptionMetadata(String id, String description, boolean isBaseType) {
    super(id);
    putAndQuoteProperty(DESCRIPTION, description);
    init(isBaseType);
  }

  private void init(boolean isBaseType) {
    String description = getPropertyRaw(DESCRIPTION);
    warnIfMissing(sf_logger, DESCRIPTION, description);
    if (description != null && (!description.startsWith("\"") || !description.endsWith("\""))) {
      sf_logger.warn("Metadata property \"{}\" should be quoted but was: {}", DESCRIPTION, description);
    }
    if (isBaseType) {
      ensureNoExtras(ID, DESCRIPTION);
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

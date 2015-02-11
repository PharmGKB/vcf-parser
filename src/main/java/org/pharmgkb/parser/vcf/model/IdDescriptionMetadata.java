package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
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

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";

  public IdDescriptionMetadata(@Nonnull String id, @Nonnull String description) {
    this(id, description, true);
  }

  public IdDescriptionMetadata(@Nonnull Map<String, String> properties, boolean isBaseType) {
    super(properties, false);
    init(isBaseType);
  }

  protected IdDescriptionMetadata(@Nonnull String id, @Nonnull String description, boolean isBaseType) {
    super(id);
    getProperties().put(DESCRIPTION, description);
    init(isBaseType);
  }

  private void init(boolean isBaseType) {
    if (getProperty(DESCRIPTION) == null) {
      throw new IllegalArgumentException("Required metadata property \"" + DESCRIPTION + "\" is missing");
    }
    if (isBaseType) {
      ensureNoExtras(ID, DESCRIPTION);
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getDescription() {
    return getProperty(DESCRIPTION);
  }


}

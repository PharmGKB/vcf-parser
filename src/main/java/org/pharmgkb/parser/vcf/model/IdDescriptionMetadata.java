package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;


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
public class IdDescriptionMetadata extends BaseMetadata {

  public IdDescriptionMetadata(@Nonnull String[] props) {
    super(props);
    if (getProperty("ID") == null) {
      throw new IllegalArgumentException("Required metadata property \"ID\" is missing");
    }
    if (getProperty("Description") == null) {
      throw new IllegalArgumentException("Required metadata property \"Description\" is missing");
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getId() {
    return getProperty("ID");
  }

  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getDescription() {
    return getProperty("Description");
  }


}

package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;

/**
 * A field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public interface ReservedProperty {

  @Nonnull
  public String getId();

  @Nonnull
  public String getDescription();

  @Nonnull
  public Class getType();

  boolean isList();
}

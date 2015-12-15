package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;

/**
 * Metadata for FORMAT types.
 * @author Douglas Myers-Turnbull.
 */
public final class FilterMetadata extends IdDescriptionMetadata {

  public FilterMetadata(@Nonnull String id, @Nonnull String description) {
    super();
    putPropertyRaw(ID, id);
    putAndQuoteProperty(DESCRIPTION, description);
  }

}

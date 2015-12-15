package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;

/**
 * Metadata for FORMAT types.
 * @author Douglas Myers-Turnbull.
 */
public final class FilterMetadata extends IdDescriptionMetadata {

  public FilterMetadata(@Nonnull LinkedHashMap<String, String> props) {
    this(props.get(AltMetadata.ID), props.get(AltMetadata.DESCRIPTION));
  }

  public FilterMetadata(@Nonnull String id, @Nonnull String description) {
    super();
    putPropertyRaw(ID, id);
    putAndQuoteProperty(DESCRIPTION, description);
  }

}

package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;

/**
 * Metadata for FORMAT types.
 * @author Douglas Myers-Turnbull.
 */
public final class SampleMetadata extends IdDescriptionMetadata {

  public SampleMetadata(@Nonnull String id, @Nonnull String description) {
    super();
    putPropertyRaw(ID, id);
    putAndQuoteProperty(DESCRIPTION, description);
  }

  public SampleMetadata(@Nonnull LinkedHashMap<String, String> props) {
    this(
        props.get(SampleMetadata.ID),
        props.get(SampleMetadata.DESCRIPTION)
    );
  }
}

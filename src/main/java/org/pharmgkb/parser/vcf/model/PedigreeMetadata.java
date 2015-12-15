package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@link #getId() ID} is everything enclosed in the ##&lt;&gt;
 */
public final class PedigreeMetadata extends IdMetadata {

  public PedigreeMetadata(@Nonnull Map<String, String> map) {
    super();
    putPropertyRaw("ID", map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";")));
  }

  @Nonnull
  public String asVcfString() {
    return "##" + getId();
  }

  @Nonnull
  @Override
  public String asVcfString(@Nonnull String metadataTypeName) {
    return "##PEDIGREE=<" + getId() + ">";
  }

}

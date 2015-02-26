package org.pharmgkb.parser.vcf;

import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A transformation applied to VCF data lines and metadata.
 * @author Douglas Myers-Turnbull
 */
public interface VcfTransformation {

  /**
   * Modifies the argument {@code metadata}. The default method does nothing.
   */
  default void transformMetadata(@Nonnull VcfMetadata metadata) {}

  /**
   * Modifies the arguments {@code position} and {@code sampleData}. Should not modify {@code metadata}.
   * The default method does nothing.
   * @return If false, the VcfPosition will be removed
   */
  default boolean transformDataLine(@Nonnull VcfMetadata metadata, @Nonnull VcfPosition position,
      @Nonnull List<VcfSample> sampleData) {
    return true;
  }

}

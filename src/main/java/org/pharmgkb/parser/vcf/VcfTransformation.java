package org.pharmgkb.parser.vcf;

import java.util.List;
import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;


/**
 * A transformation applied to VCF data lines and metadata.
 * @author Douglas Myers-Turnbull
 */
public interface VcfTransformation {

  /**
   * Modifies the argument {@code metadata}. The default method does nothing.
   */
  default void transformMetadata(VcfMetadata metadata) {}

  /**
   * Modifies the arguments {@code position} and {@code sampleData}. Should not modify {@code metadata}.
   * The default method does nothing.
   * @return If false, the VcfPosition will be removed
   */
  default boolean transformDataLine(VcfMetadata metadata, VcfPosition position,
      List<VcfSample> sampleData) {
    return true;
  }

}

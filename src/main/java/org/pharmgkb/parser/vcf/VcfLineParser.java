package org.pharmgkb.parser.vcf;

import org.pharmgkb.parser.vcf.model.VcfMetadata;
import org.pharmgkb.parser.vcf.model.VcfPosition;
import org.pharmgkb.parser.vcf.model.VcfSample;

import java.util.List;


/**
 * This interface controls what is actually done for each data line in a VCF file.
 *
 * @author Mark Woon
 */
public interface VcfLineParser {

  void parseLine(VcfMetadata metadata, VcfPosition position, List<VcfSample> sampleData);

  /**
   * Called once after the metadata has been parsed and before any {@link #parseLine} calls, even when the file contains
   * no data lines. Implementations that need the metadata regardless of whether data lines follow (e.g. to write a
   * header) can use this. The default does nothing.
   */
  default void parseMetadata(VcfMetadata metadata) {}
}

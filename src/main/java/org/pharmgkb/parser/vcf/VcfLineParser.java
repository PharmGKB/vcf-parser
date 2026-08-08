package org.pharmgkb.parser.vcf;


/**
 * This interface controls what is actually done for each data line in a VCF file.
 *
 * @author Mark Woon
 */
public interface VcfLineParser {

  /** Called for each data line in the VCF file. */
  void parseLine(VcfMetadata metadata, VcfRecord record);

  /**
   * Called once after the metadata has been parsed and before any {@link #parseLine} calls, even when the file contains
   * no data lines. Implementations that need the metadata regardless of whether data lines follow (e.g. to write a
   * header) can use this. The default does nothing.
   */
  default void parseMetadata(VcfMetadata metadata) {}
}

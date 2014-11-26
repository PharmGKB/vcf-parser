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
}

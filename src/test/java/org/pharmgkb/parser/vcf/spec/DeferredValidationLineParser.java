package org.pharmgkb.parser.vcf.spec;

import java.util.List;
import org.pharmgkb.parser.vcf.ReservedInfoProperty;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfLineParser;
import org.pharmgkb.parser.vcf.VcfMetadata;
import org.pharmgkb.parser.vcf.VcfPosition;
import org.pharmgkb.parser.vcf.VcfRecord;
import org.pharmgkb.parser.vcf.VcfSample;


/**
 * Test-only {@link VcfLineParser} that triggers every deferred/lazy validation check this library exposes, on
 * every record: QUAL, raw INFO, every {@link ReservedInfoProperty}, every present FORMAT key (typed, via
 * {@link VcfSample#getProperty(VcfPosition, VcfMetadata, String)}), and GT allele-index interpretation (via
 * {@link VcfSample#getGenotype}). Exists so a fixture regression test only has to call
 * {@code .parseWith(new DeferredValidationLineParser())} instead of hand-rolling a subset of these calls -- a
 * prior hand-rolled lambda in this test suite once used a no-op callback and silently missed 36 of 38 expected
 * warnings from a deferred-only check.
 * <p>
 * Does not catch {@link VcfFormatException}: a genuine hard failure (e.g. a GT allele index out of range)
 * propagates, matching how any real consumer calling these same public getters would experience it.
 */
public class DeferredValidationLineParser implements VcfLineParser {

  @Override
  public void parseLine(VcfMetadata metadata, VcfRecord record) {
    VcfPosition position = record.getPosition();
    List<VcfSample> sampleData = record.getSamples();
    position.getQuality();
    position.getInfo();
    for (ReservedInfoProperty prop : ReservedInfoProperty.values()) {
      position.getInfo(prop);
    }
    for (VcfSample sample : sampleData) {
      for (String key : sample.getPropertyKeys()) {
        sample.getProperty(position, metadata, key);
      }
      sample.getGenotype(position);
    }
  }
}

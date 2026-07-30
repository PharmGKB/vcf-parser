package org.pharmgkb.parser.vcf.model;

import java.util.Map;


/**
 * This class represents a single VCF PEDIGREE metadata line.
 * <p>
 * VCFv4.2.tex line 152: {@code ##PEDIGREE=<Name_0=ID,...,Name_N=ID>} -- unlike {@code ##INFO}/{@code ##FORMAT}/
 * {@code ##contig}, this declaration has no fixed schema (no mandatory {@code ID}, no {@code Description}), so
 * this class has no typed constructor mirroring a fixed field set -- only the raw properties map is exposed,
 * via the inherited {@link BaseMetadata} accessors.
 *
 * @author Mark Woon
 */
public class PedigreeMetadata extends BaseMetadata {

  public PedigreeMetadata(Map<String, String> properties) {
    super("PEDIGREE", properties);
  }
}

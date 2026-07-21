package org.pharmgkb.parser.vcf.model;

/**
 * A field specified as reserved in the VCF specification.
 * @author Douglas Myers-Turnbull
 */
public interface ReservedProperty {

  String getId();

  String getDescription();

  Class getType();

  boolean isList();
}

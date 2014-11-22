package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfParser;

import javax.annotation.Nonnull;
import java.util.regex.Pattern;


/**
 * This class represents a single VCF FORMAT metadata line.
 *
 * <code>
 * ##FORMAT=<ID=ID,Number=number,Type=type,Description="description">
 * </code>
 *
 * @author Mark Woon
 */
public class FormatMetadata extends IdDescriptionMetadata {
  private static final Pattern sf_numberPattern = Pattern.compile("(?:\\d+|\\.)");
  private String m_number;
  private FormatType m_type;


  public FormatMetadata(@Nonnull String[] props) {
    super(props, 3);
    m_number = VcfParser.splitProperty(props[1], false)[1];
    if (!sf_numberPattern.matcher(m_number).matches()) {
      throw new IllegalArgumentException("[Number] Not a number: '" + m_number + "'");
    }
    m_type = FormatType.valueOf(VcfParser.splitProperty(props[2], false)[1]);
  }


  /**
   * Value is either an integer or ".".
   */
  @Nonnull
  public String getNumber() {
    return m_number;
  }

  @Nonnull
  public FormatType getType() {
    return m_type;
  }
}

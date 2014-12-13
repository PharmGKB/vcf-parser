package org.pharmgkb.parser.vcf.model;

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
  private FormatType m_type;


  @SuppressWarnings("ConstantConditions")
  public FormatMetadata(@Nonnull String[] props) {
    super(props);
    String number = getProperty("Number");
    if (!sf_numberPattern.matcher(number).matches()) {
      throw new IllegalArgumentException("[Number] Not a number: '" + number + "'");
    }
    m_type = FormatType.valueOf(getProperty("Type"));
  }


  /**
   * Value is either an integer or ".".
   */
  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getNumber() {
    return getProperty("Number");
  }

  @Nonnull
  public FormatType getType() {
    return m_type;
  }
}

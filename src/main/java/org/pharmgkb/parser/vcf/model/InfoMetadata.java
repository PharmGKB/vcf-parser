package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfParser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.regex.Pattern;


/**
 * This class represents a single VCF INFO metadata line.
 *
 * In 4.1:
 * <code>
 * ##INFO=<ID=ID,Number=number,Type=type,Description="description">
 * </code>
 *
 * In 4.2:
 * <code>
 * ##INFO=<ID=ID,Number=number,Type=type,Description="description",Source="source",Version="version">
 * </code>
 *
 * @author Mark Woon
 */
public class InfoMetadata extends IdDescriptionMetadata {
  private static final Pattern sf_numberPattern = Pattern.compile("(?:\\d+|[\\.AaGgRr])");
  private String m_number;
  private InfoType m_type;


  public InfoMetadata(@Nonnull String[] props) {
    super(props, 3);
    m_number = VcfParser.splitProperty(props[1], false)[1];
    if (!sf_numberPattern.matcher(m_number).matches()) {
      throw new IllegalArgumentException("[Number] Not a number: '" + m_number + "'");
    }
    m_type = InfoType.valueOf(VcfParser.splitProperty(props[2], false)[1]);
  }


  /**
   * Value is either an integer or "A", "G", "R", or ".".
   */
  @Nonnull
  public String getNumber() {
    return m_number;
  }

  @Nonnull
  public InfoType getType() {
    return m_type;
  }

  @Nullable
  public String getSource() {
    return getProperty("source");
  }

  @Nullable
  public String getVersion() {
    return getProperty("version");
  }
}

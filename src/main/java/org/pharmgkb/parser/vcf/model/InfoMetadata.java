package org.pharmgkb.parser.vcf.model;

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
  private InfoType m_type;

  @SuppressWarnings("ConstantConditions")
  public InfoMetadata(@Nonnull String[] props) {
    super(props);
    String number = getProperty("Number");
    if (!sf_numberPattern.matcher(number).matches()) {
      throw new IllegalArgumentException("[Number] Not a number: '" + number + "'");
    }
    m_type = InfoType.valueOf(getProperty("Type"));
  }


  /**
   * Value is either an integer or "A", "G", "R", or ".".
   */
  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getNumber() {
    return getProperty("Number");
  }

  /**
   * @return A special (reserved) <em>Number</em> ("A", "G", "R", or "."), or null if the Number is not reserved
   * (it is numerical).
   */
  @SuppressWarnings("ConstantConditions")
  @Nullable
  public ReservedInfoNumber getReservedNumber() {
    return ReservedInfoNumber.fromId(getProperty("Number"));
  }

  @Nonnull
  public InfoType getType() {
    return m_type;
  }

  @Nullable
  public String getSource() {
    return getProperty("Source");
  }

  @Nullable
  public String getVersion() {
    return getProperty("Version");
  }
}

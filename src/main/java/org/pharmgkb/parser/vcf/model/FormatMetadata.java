package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import java.util.Map;
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

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";

  private static final Pattern sf_numberPattern = Pattern.compile("(?:\\d+|\\.)");
  private FormatType m_type;

  public FormatMetadata(@Nonnull String id, @Nonnull String description, @Nonnull String number, @Nonnull String type) {
    super(id, description);
    getProperties().put(NUMBER, number);
    getProperties().put(TYPE, type);
    init();
  }

  public FormatMetadata(@Nonnull Map<String, String> properties) {
    super(properties, false);
    init();
  }

  public void init() {
    String number = getProperty(NUMBER);
    assert number != null;
    if (!sf_numberPattern.matcher(number).matches()) {
      throw new IllegalArgumentException(NUMBER + " is not a VCF number: '" + number + "'");
    }
    m_type = FormatType.valueOf(getProperty(TYPE));
    ensureNoExtras(ID, DESCRIPTION, NUMBER, TYPE);
  }

  /**
   * Value is either an integer or ".".
   */
  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getNumber() {
    return getProperty(NUMBER);
  }

  @Nonnull
  public FormatType getType() {
    return m_type;
  }
}

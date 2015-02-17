package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;


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

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";
  public static final String SOURCE = "Source";
  public static final String VERSION = "Version";

  private InfoType m_type;

  public InfoMetadata(@Nonnull String id, @Nonnull String description, @Nonnull String type, @Nonnull String number,
      @Nullable String source, @Nullable String version) {
    super(id, description);
    getProperties().put(NUMBER, number);
    getProperties().put(TYPE, type);
    if (source != null) {
      getProperties().put(SOURCE, source);
    }
    if (version != null) {
      getProperties().put(VERSION, version);
    }
    init();
  }

  public InfoMetadata(@Nonnull Map<String, String> properties) {
    super(properties, false);
    init();
  }

  private void init() {
    String number = getProperty(NUMBER);
    assert number != null;
    if (!VcfUtils.NUMBER_PATTERN.matcher(number).matches()) {
      throw new IllegalArgumentException(NUMBER + " is not a number: '" + number + "'");
    }
    m_type = InfoType.valueOf(getProperty(TYPE));
    ensureNoExtras(ID, DESCRIPTION, NUMBER, TYPE, SOURCE, VERSION);
  }

  /**
   * Value is either an integer or "A", "G", "R", or ".".
   */
  @SuppressWarnings("ConstantConditions")
  @Nonnull
  public String getNumber() {
    return getProperty(NUMBER);
  }

  /**
   * @return A special (reserved) <em>Number</em> ("A", "G", "R", or "."), or null if the Number is not reserved
   * (it is numerical).
   */
  @SuppressWarnings("ConstantConditions")
  @Nullable
  public SpecialVcfNumber getReservedNumber() {
    return SpecialVcfNumber.fromId(getProperty(NUMBER));
  }

  @Nonnull
  public InfoType getType() {
    return m_type;
  }

  @Nullable
  public String getSource() {
    return getProperty(SOURCE);
  }

  @Nullable
  public String getVersion() {
    return getProperty(VERSION);
  }
}

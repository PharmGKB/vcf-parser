package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
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

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description"; // should be quoted
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";
  public static final String SOURCE = "Source"; // should be quoted
  public static final String VERSION = "Version"; // should be quoted

  private InfoType m_type;

  public InfoMetadata(@Nonnull String id, @Nonnull String description, @Nonnull InfoType type, @Nonnull String number,
      @Nullable String source, @Nullable String version) {
    super(id, description);
    // awful hack to ensure that number and type precede description, which the GATK requires
    getPropertiesRaw().remove(DESCRIPTION);
    putPropertyRaw(NUMBER, number);
    putPropertyRaw(TYPE, type.name());
    putAndQuoteProperty(DESCRIPTION, description);
    if (source != null) {
      putAndQuoteProperty(SOURCE, source);
    }
    if (version != null) {
      putAndQuoteProperty(VERSION, version);
    }
    init();
  }

  public InfoMetadata(@Nonnull LinkedHashMap<String, String> properties) {
    super(properties, false);
    init();
  }

  private void init() {
    String number = getPropertyRaw(NUMBER);
    assert number != null;
    if (!VcfUtils.NUMBER_PATTERN.matcher(number).matches()) {
      sf_logger.warn("{} is not a number: '{}'", NUMBER, number);
    }
    m_type = InfoType.valueOf(getPropertyRaw(TYPE));
    ensureNoExtras(ID, DESCRIPTION, NUMBER, TYPE, SOURCE, VERSION);
  }

  /**
   * Value is either an integer or "A", "G", "R", or ".".
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public String getNumber() {
    return getPropertyRaw(NUMBER);
  }

  /**
   * @return A special (reserved) <em>Number</em> ("A", "G", "R", or "."), or null if the Number is not reserved
   * (it is numerical).
   */
  @SuppressWarnings("ConstantConditions")
  @Nullable
  public SpecialVcfNumber getReservedNumber() {
    return SpecialVcfNumber.fromId(getPropertyRaw(NUMBER));
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public InfoType getType() {
    return m_type;
  }

  @Nullable
  public String getSource() {
    return getPropertyUnquoted(SOURCE);
  }

  @Nullable
  public String getVersion() {
    return getPropertyUnquoted(VERSION);
  }
}

package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class represents a single VCF INFO metadata line.
 * <p></p>
 * In 4.1:
 * <pre>{@code
 * ##INFO=<ID=ID,Number=number,Type=type,Description="description">
 * }
 * </pre>
 * <p>
 * In 4.2:
 * <pre>{@code
 * ##INFO=<ID=ID,Number=number,Type=type,Description="description",Source="source",Version="version">
 * }
 * </pre>
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

  public InfoMetadata(String id, String description, InfoType type, String number,
      @Nullable String source, @Nullable String version) {
    super(id, description);
    putPropertyRaw(NUMBER, number);
    putPropertyRaw(TYPE, type.name());
    if (source != null) {
      putAndQuoteProperty(SOURCE, source);
    }
    if (version != null) {
      putAndQuoteProperty(VERSION, version);
    }
    init();
  }

  public InfoMetadata(Map<String, String> properties) {
    super(properties, false);
    init();
  }

  private void init() {
    String number = getPropertyRaw(NUMBER);
    checkNumberProperty(sf_logger, number);
    String type = getPropertyRaw(TYPE);
    warnIfMissing(sf_logger, TYPE, type);
    if (type != null) {
      try {
        m_type = InfoType.valueOf(type);
      } catch (IllegalArgumentException e) {
        sf_logger.warn("{} '{}' is not a valid INFO type", TYPE, type);
      }
    }
    if (m_type == InfoType.Flag && !"0".equals(number)) {
      sf_logger.warn("INFO {} has Type=Flag but Number is '{}' (should be 0)", getId(), number);
    }
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

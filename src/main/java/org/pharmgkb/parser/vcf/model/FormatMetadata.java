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
 * This class represents a single VCF FORMAT metadata line.
 *
 * <code>
 * ##FORMAT=<ID=ID,Number=number,Type=type,Description="description">
 * </code>
 *
 * @author Mark Woon
 */
public final class FormatMetadata extends IdDescriptionMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";

  private FormatType m_type;

  public FormatMetadata(@Nonnull String id, @Nonnull String description, @Nonnull String number, @Nonnull FormatType type) {
    super();
    putPropertyRaw(ID, id);
    putPropertyRaw(NUMBER, number);
    putPropertyRaw(TYPE, type.name());
    putAndQuoteProperty(DESCRIPTION, description);
    init();
  }

  public void init() {
    String number = getPropertyRaw(NUMBER);
    if (number == null) {
      sf_logger.warn("Required metadata property \"{}\" is missing", NUMBER);
    } else if (!VcfUtils.NUMBER_PATTERN.matcher(number).matches()) {
      sf_logger.warn("{} is not a VCF number: '{}'", NUMBER, number);
    }
    m_type = FormatType.valueOf(getPropertyRaw(TYPE));
    ensureNoExtras(ID, DESCRIPTION, NUMBER, TYPE);
  }

  /**
   * Value is either an integer or ".".
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
  public FormatType getType() {
    return m_type;
  }
}

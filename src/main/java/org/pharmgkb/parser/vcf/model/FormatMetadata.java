package org.pharmgkb.parser.vcf.model;

import org.pharmgkb.parser.vcf.VcfUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
public class FormatMetadata extends IdDescriptionMetadata {

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";

  private FormatType m_type;

  public FormatMetadata(@Nonnull String id, @Nonnull String description, @Nonnull String number, @Nonnull String type) {
    super(id, description, false);
    putPropertyRaw(NUMBER, number);
    putPropertyRaw(TYPE, type);
    init();
  }

  public FormatMetadata(@Nonnull Map<String, String> properties) {
    super(properties, false);
    init();
  }

  public void init() {
    String number = getPropertyRaw(NUMBER);
    if (number == null) {
      throw new IllegalArgumentException("Required metadata property \"" + NUMBER + "\" is missing");
    }
    if (!VcfUtils.NUMBER_PATTERN.matcher(number).matches()) {
      throw new IllegalArgumentException(NUMBER + " is not a VCF number: '" + number + "'");
    }
    m_type = FormatType.valueOf(getPropertyRaw(TYPE));
    ensureNoExtras(ID, DESCRIPTION, NUMBER, TYPE);
  }

  /**
   * Value is either an integer or ".".
   */
  @SuppressWarnings("ConstantConditions")
  @Nonnull
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

  @Nonnull
  public FormatType getType() {
    return m_type;
  }
}

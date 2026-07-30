package org.pharmgkb.parser.vcf.model;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * This class represents a single VCF FORMAT metadata line.
 *
 * <pre>{@code
 * ##FORMAT=<ID=ID,Number=number,Type=type,Description="description">
 * }
 * </pre>
 *
 * @author Mark Woon
 */
public class FormatMetadata extends IdDescriptionMetadata {

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";

  private @Nullable FormatType m_type;

  public FormatMetadata(String id, String description, String number, FormatType type) {
    super("FORMAT", id);
    putPropertyRaw(NUMBER, number);
    putPropertyRaw(TYPE, type.name());
    // Description inserted last -- see InfoMetadata's identical constructor for why (matches canonical
    // ID, Number, Type, Description order per VCFv4.2.tex line 94).
    putAndQuoteProperty(DESCRIPTION, description);
    init();
  }

  public FormatMetadata(Map<String, String> properties) {
    super("FORMAT", properties);
    init();
  }

  public void init() {
    m_type = null;
    String number = getPropertyRaw(NUMBER);
    checkNumberProperty(number);
    String type = getPropertyRaw(TYPE);
    warnIfMissing(TYPE, type);
    if (type != null) {
      try {
        m_type = FormatType.valueOf(type);
      } catch (IllegalArgumentException e) {
        VcfWarnings.warn("{} {} '{}' is not a valid {} type", getLabel(), TYPE, type, getLabel());
      }
    }
    String id = getId();
    ReservedFormatProperty reserved = id == null ? null : ReservedFormatProperty.fromId(id);
    if (reserved != null) {
      if (number != null && !reserved.getExpectedNumber().equals(number)) {
        VcfWarnings.warn("{} {} has Number '{}' but the reserved definition expects '{}'", getLabel(), getId(),
            number, reserved.getExpectedNumber());
      }
      if (m_type != null && !VcfUtils.toJavaType(m_type).equals(reserved.getType())) {
        VcfWarnings.warn("{} {} has Type '{}' but the reserved definition expects '{}'", getLabel(), getId(),
            m_type, reserved.getType().getSimpleName());
      }
    }
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- so an unrecognized property here is not itself non-compliant and must not warn
  }

  @Override
  public void validate() {
    super.validate();
    init();
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
  public FormatType getType() {
    return m_type;
  }
}

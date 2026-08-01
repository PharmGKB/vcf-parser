package org.pharmgkb.parser.vcf.model;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
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

  public static final String DESCRIPTION = "Description";
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";

  private @Nullable FormatType m_type;

  public FormatMetadata(String id, String description, String number, FormatType type) {
    super("FORMAT", id);
    putPropertyRaw(NUMBER, number);
    // type is typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting
    // type.name() below throw a raw NullPointerException
    if (type == null) {
      throw new VcfFormatException("FORMAT type is null");
    }
    putPropertyRaw(TYPE, type.name());
    // Description inserted last -- see InfoMetadata's identical constructor for why (matches canonical
    // ID, Number, Type, Description order per VCFv4.2.tex line 94).
    putAndQuoteProperty(DESCRIPTION, description);
    // strict construction: see InfoMetadata's identical constructor for why Description-quoting and
    // Type-parse-failure have no throw-equivalent here.
    if (number == null || !InternalUtils.NUMBER_PATTERN.matcher(number).matches()) {
      throw new VcfFormatException("FORMAT Number is not a valid VCF number: '" + number + "'");
    }
    ReservedFormatProperty reserved = ReservedFormatProperty.fromId(id);
    if (reserved != null) {
      if (!reserved.getExpectedNumber().equals(number)) {
        throw new VcfFormatException("FORMAT " + id + " has Number '" + number + "' but the reserved definition " +
            "expects '" + reserved.getExpectedNumber() + "'");
      }
      if (!InternalUtils.toJavaType(type).equals(reserved.getType())) {
        throw new VcfFormatException("FORMAT " + id + " has Type '" + type + "' but the reserved definition " +
            "expects '" + reserved.getType().getSimpleName() + "'");
      }
    }
    m_type = type;
  }

  public FormatMetadata(Map<String, String> properties, VcfWarnings warnings) {
    super("FORMAT", properties, warnings);
    init();
  }

  private void init() {
    m_type = null;
    String number = getPropertyRaw(NUMBER);
    checkNumberProperty(number);
    String type = getPropertyRaw(TYPE);
    warnIfMissing(TYPE, type);
    if (type != null) {
      try {
        m_type = FormatType.valueOf(type);
      } catch (IllegalArgumentException e) {
        m_warnings.warn("{} {} '{}' is not a valid {} type", getLabel(), TYPE, type, getLabel());
      }
    }
    String id = getId();
    ReservedFormatProperty reserved = ReservedFormatProperty.fromId(id);
    if (reserved != null) {
      if (number != null && !reserved.getExpectedNumber().equals(number)) {
        m_warnings.warn("{} {} has Number '{}' but the reserved definition expects '{}'", getLabel(), getId(),
            number, reserved.getExpectedNumber());
      }
      if (m_type != null && !InternalUtils.toJavaType(m_type).equals(reserved.getType())) {
        m_warnings.warn("{} {} has Type '{}' but the reserved definition expects '{}'", getLabel(), getId(),
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
   * (it is numerical) or absent.
   */
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

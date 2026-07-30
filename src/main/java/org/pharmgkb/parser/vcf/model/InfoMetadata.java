package org.pharmgkb.parser.vcf.model;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.pharmgkb.parser.vcf.VcfWarnings;


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

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description"; // should be quoted
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";
  public static final String SOURCE = "Source"; // should be quoted
  public static final String VERSION = "Version"; // should be quoted

  @Nullable
  private InfoType m_type;

  public InfoMetadata(String id, String description, InfoType type, String number,
      @Nullable String source, @Nullable String version) {
    super("INFO", id);
    putPropertyRaw(NUMBER, number);
    putPropertyRaw(TYPE, type.name());
    // Description is inserted here, before Source/Version and not immediately after ID, so this constructor's
    // property-insertion order matches the canonical ID, Number, Type, Description, Source, Version order that
    // BaseMetadata.checkAttributeOrder() checks (VCFv4.2.tex line 69) -- inserting it via the (id, description)
    // super constructor instead would produce ID, Description, Number, Type, ..., which fails that
    // check on any parse of this constructor's own written-out output.
    putAndQuoteProperty(DESCRIPTION, description);
    if (source != null) {
      putAndQuoteProperty(SOURCE, source);
    }
    if (version != null) {
      putAndQuoteProperty(VERSION, version);
    }
    init();
  }

  public InfoMetadata(Map<String, String> properties) {
    super("INFO", properties);
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
        m_type = InfoType.valueOf(type);
      } catch (IllegalArgumentException e) {
        VcfWarnings.warn("{} {} '{}' is not a valid {} type", getLabel(), TYPE, type, getLabel());
      }
    }
    if (m_type == InfoType.Flag && !"0".equals(number)) {
      VcfWarnings.warn("{} {} has Type=Flag but Number is '{}' (should be 0)", getLabel(), getId(), number);
    }
    for (String optional : new String[] { SOURCE, VERSION }) {
      String value = getPropertyRaw(optional);
      if (value != null && (!value.startsWith("\"") || !value.endsWith("\""))) {
        VcfWarnings.warn("{} metadata property \"{}\" should be quoted but was: {}", getLabel(), optional, value);
      }
    }
    String id = getId();
    ReservedInfoProperty reserved = id == null ? null : ReservedInfoProperty.fromId(id);
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

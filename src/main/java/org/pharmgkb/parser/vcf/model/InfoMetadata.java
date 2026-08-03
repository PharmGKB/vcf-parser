package org.pharmgkb.parser.vcf.model;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.InternalUtils;
import org.pharmgkb.parser.vcf.VcfFormatException;
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
    // structurally unreachable for a whitespace- or comma-containing id: IdMetadata's own super(...) call above
    // already throws first for either -- see findings24.md item 20 and ContigMetadata's identical strict-path
    // reasoning for its own analogous character-set check
    if (!InternalUtils.INFO_PATTERN.matcher(id).matches()) {
      throw new VcfFormatException(getLabel() + " ID \"" + id + "\" does not match the required tag-name " +
          "grammar (must start with a letter or underscore, followed only by letters, digits, underscores, " +
          "or periods -- or be the legacy value '1000G')");
    }
    // Validated before putPropertyRaw stores it below: putPropertyRaw only null-checks (with a generic
    // "Metadata property ... has a null value" message), so checking format here first is what makes a null
    // or malformed Number actually produce this class's own, more specific message.
    if (number == null || !InternalUtils.NUMBER_PATTERN.matcher(number).matches()) {
      throw new VcfFormatException("INFO Number is not a valid VCF number: '" + number + "'");
    }
    putPropertyRaw(NUMBER, number);
    if (type == null) {
      throw new VcfFormatException("INFO type is null");
    }
    putPropertyRaw(TYPE, type.name());
    putAndQuoteProperty(DESCRIPTION, description);
    if (source != null) {
      putAndQuoteProperty(SOURCE, source);
    }
    if (version != null) {
      putAndQuoteProperty(VERSION, version);
    }
    // strict construction: throws immediately instead of deferring to init()'s warn-based checks below. The
    // Source/Version-quoting and Type-parse-failure checks init() also performs are structurally unreachable here
    // (putAndQuoteProperty always quotes; type is already a real InfoType enum, not a raw string) and have no
    // throw-equivalent added, per this project's "no error handling for impossible scenarios" convention.
    if (type == InfoType.Flag && !"0".equals(number)) {
      throw new VcfFormatException("INFO " + id + " has Type=Flag but Number is '" + number + "' (should be 0)");
    }
    ReservedInfoProperty reserved = ReservedInfoProperty.fromId(id);
    if (reserved != null) {
      if (!reserved.getExpectedNumber().equals(number)) {
        throw new VcfFormatException("INFO " + id + " has Number '" + number + "' but the reserved definition " +
            "expects '" + reserved.getExpectedNumber() + "'");
      }
      if (!InternalUtils.toJavaType(type).equals(reserved.getType())) {
        throw new VcfFormatException("INFO " + id + " has Type '" + type + "' but the reserved definition " +
            "expects '" + reserved.getType().getSimpleName() + "'");
      }
    }
    m_type = type;
  }

  public InfoMetadata(Map<String, String> properties, VcfWarnings warnings) {
    super("INFO", properties, warnings);
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
        m_warnings.warn("{} {} '{}' is not a valid {} type", getLabel(), TYPE, type, getLabel());
      }
    }
    if (m_type == InfoType.Flag && number != null && !"0".equals(number)) {
      m_warnings.warn("{} {} has Type=Flag but Number is '{}' (should be 0)", getLabel(), getId(), number);
    }
    for (String optional : new String[] { SOURCE, VERSION }) {
      String value = getPropertyRaw(optional);
      if (value != null && (!value.startsWith("\"") || !value.endsWith("\""))) {
        m_warnings.warn("{} metadata property \"{}\" should be quoted but was: {}", getLabel(), optional, value);
      }
    }
    String id = getId();
    if (id != null && !id.isEmpty()) {
      // strip commas/whitespace before checking: IdMetadata's own init() (which already ran, before this method,
      // during construction) already warned about either separately -- stripping them first means this check only
      // fires for a genuinely different defect, not a second warning about the same one. Matches ContigMetadata's
      // own "keep searching past a comma for a different defect" precedent for this exact situation, adapted for a
      // whole-string pattern instead of a per-character scan.
      String stripped = id.replaceAll("[,\\s]", "");
      if (!stripped.isEmpty() && !InternalUtils.INFO_PATTERN.matcher(stripped).matches()) {
        m_warnings.warn("{} ID \"{}\" does not match the required tag-name grammar (must start with a letter or " +
            "underscore, followed only by letters, digits, underscores, or periods -- or be the legacy value " +
            "'1000G')", getLabel(), id);
      }
    }
    ReservedInfoProperty reserved = ReservedInfoProperty.fromId(id);
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

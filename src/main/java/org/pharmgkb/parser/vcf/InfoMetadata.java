package org.pharmgkb.parser.vcf;

import java.util.Map;
import org.jspecify.annotations.Nullable;


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
public final class InfoMetadata extends IdDescriptionMetadata {

  public static final String DESCRIPTION = "Description"; // should be quoted
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";
  public static final String SOURCE = "Source"; // should be quoted
  public static final String VERSION = "Version"; // should be quoted

  @Nullable
  private InfoType m_type;

  InfoMetadata(VcfContext context, @Nullable String id, @Nullable String description, @Nullable InfoType type,
      @Nullable String number, @Nullable String source, @Nullable String version) {
    super(context, "INFO", id);
    // Checked unquoted, not raw -- see IdMetadata's typed constructor's identical comment for why (there is no
    // stored property to read yet, so this unquotes the constructor parameter directly via InternalUtils.unquote
    // rather than getPropertyUnquoted). Without this, a quoted reserved ID (e.g. ID="AF") both false-rejected the
    // tag-name-grammar check below and silently skipped the ReservedInfoProperty comparison entirely, since
    // neither ever saw the value with its quotes stripped -- matching init()'s own getPropertyUnquoted(ID)/
    // getPropertyUnquoted(NUMBER) reasoning for the Map-based constructor just below. unquotedId/unquotedNumber
    // change only what is tested, not what is stored: putPropertyRaw below still stores the caller's original
    // spelling.
    String unquotedId = id == null ? null : InternalUtils.unquote(id);
    String unquotedNumber = number == null ? null : InternalUtils.unquote(number);
    // Under a strict context IdMetadata's own super(...) call above already threw for a null, blank,
    // whitespace- or comma-containing id, so this check only ever sees a well-formed one there. Under a lenient
    // context that call warns and continues instead, so id genuinely can be null (or blank/comma-containing)
    // here -- guard rather than letting INFO_PATTERN.matcher(id) raw-crash, and strip commas/whitespace before
    // checking so this only fires for a genuinely different defect, matching init()'s own identical reasoning
    // for the Map-based constructor just below (a prior version of this check omitted the stripping, which
    // re-warned about a comma IdMetadata's own super(...) call had already reported).
    if (unquotedId != null && !unquotedId.isEmpty()) {
      String stripped = unquotedId.replaceAll("[,\\s]", "");
      if (!stripped.isEmpty() && !InternalUtils.INFO_PATTERN.matcher(stripped).matches()) {
        warnOrThrow("{} ID \"{}\" does not match the required tag-name grammar (must start with a letter or " +
            "underscore, followed only by letters, digits, underscores, or periods -- or be the legacy value " +
            "'1000G')", getLabel(), unquotedId);
      }
    }
    // Validated before putPropertyRaw stores it below: putPropertyRaw only null-checks (with a generic
    // "Metadata property ... has a null value" message), so checking format here first is what makes a null
    // or malformed Number actually produce this class's own, more specific message.
    if (unquotedNumber == null || !InternalUtils.NUMBER_PATTERN.matcher(unquotedNumber).matches()) {
      warnOrThrow("INFO Number is not a valid VCF number: '{}'", unquotedNumber);
    }
    if (number != null) {
      putPropertyRaw(NUMBER, number);
    }
    if (type == null) {
      warnOrThrow("INFO type is null");
    } else {
      putPropertyRaw(TYPE, type.name());
    }
    if (description == null) {
      warnOrThrow("{} required metadata property \"{}\" is missing", getLabel(), DESCRIPTION);
    } else {
      putAndQuoteProperty(DESCRIPTION, description);
    }
    if (source != null) {
      putAndQuoteProperty(SOURCE, source);
    }
    if (version != null) {
      putAndQuoteProperty(VERSION, version);
    }
    // no init() call: the Source/Version-quoting and Type-parse-failure checks init() also performs are
    // structurally unreachable here (putAndQuoteProperty always quotes; type is already a real InfoType enum,
    // not a raw string), per this project's "no error handling for impossible scenarios" convention.
    // unquotedNumber != null: without this guard, a missing Number (already warned about above, at the
    // "INFO Number is not a valid VCF number" check) also spuriously failed this one -- !"0".equals(null) is
    // true -- stacking a second, redundant warning on top of the first. init()'s own identical Flag check
    // (a few lines below, in the Map-based constructor) already has this guard; this typed constructor's copy
    // didn't. Found by the fifth re-verification round's finding #7.
    if (type == InfoType.Flag && unquotedNumber != null && !"0".equals(unquotedNumber)) {
      warnOrThrow("INFO {} has Type=Flag but Number is '{}' (should be 0)", unquotedId, unquotedNumber);
    }
    // the converse of the check just above (VCFv4.3.tex line 171, "the Number must be 0 in this case" --
    // "this case" being Type=Flag specifically, not Number=0 in general): a non-Flag type declaring Number=0
    // is equally non-compliant, since 0 has no meaning for a field that DOES carry a value. type != null
    // guards against double-warning a missing Type, which this constructor already warns about separately.
    if (type != null && type != InfoType.Flag && "0".equals(unquotedNumber)) {
      warnOrThrow("INFO {} has Number '0' but Type is '{}' (Number=0 is only valid for Type=Flag)", unquotedId,
          type);
    }
    // version-aware: AD/ADF/ADR have no VCFv4.2 declaration at all (VCFv4.3 additions, the INFO-side mirror of
    // FORMAT's own AD/ADF/ADR/PP/CNP gap -- see ReservedInfoProperty.ADDED_AT_V4_3's own comment), so a
    // pre-4.3 ##INFO=<ID=AD,...> is validated only against its own declared Number/Type, like any custom key.
    ReservedInfoProperty reserved = ReservedInfoProperty.fromId(unquotedId, getContext().getVersion());
    if (reserved != null) {
      // unquotedNumber != null: without this guard, a missing Number (already warned about above) also
      // spuriously failed this check -- X.equals(null) is always false, so !X.equals(null) is always true --
      // stacking a second, redundant warning on top of the first. init()'s own identical check (the Map-based
      // constructor below) already has this guard; this typed constructor's copy didn't. Found by the fifth
      // re-verification round's finding #7.
      if (unquotedNumber != null && !reserved.getExpectedNumber().equals(unquotedNumber)) {
        warnOrThrow("INFO {} has Number '{}' but the reserved definition expects '{}'", unquotedId, unquotedNumber,
            reserved.getExpectedNumber());
      }
      if (type != null && !InternalUtils.toJavaType(type).equals(reserved.getType())) {
        warnOrThrow("INFO {} has Type '{}' but the reserved definition expects '{}'", unquotedId, type,
            InternalUtils.describeJavaTypeAsVcfType(reserved.getType()));
      }
    }
    m_type = type;
  }

  InfoMetadata(VcfContext context, Map<String, String> properties) {
    super(context, "INFO", properties);
    init();
  }

  private void init() {
    m_type = null;
    // getPropertyUnquoted, not getPropertyRaw, for Number/Type/ID below: every use here is a comparison or
    // validation (NUMBER_PATTERN, InfoType.valueOf, the tag-name grammar, the ReservedInfoProperty lookup), so
    // each must see the value's actual content rather than its header-syntax quoting -- see AGENTS.md's
    // "Unquoting before internal use" policy. Without this, a quoted Number/Type/ID (e.g. Number="1") kept
    // its literal quote characters and either failed a check it should have passed, or (for ID) silently
    // skipped the ReservedInfoProperty comparison entirely because the quoted string never matched a reserved
    // key's exact name.
    String number = getPropertyUnquoted(NUMBER);
    checkNumberProperty(number);
    String type = getPropertyUnquoted(TYPE);
    warnIfMissing(TYPE, type);
    if (type != null) {
      try {
        m_type = InfoType.valueOf(type);
      } catch (IllegalArgumentException e) {
        warnOrThrow("{} {} '{}' is not a valid {} type", getLabel(), TYPE, type, getLabel());
      }
    }
    if (m_type == InfoType.Flag && number != null && !"0".equals(number)) {
      warnOrThrow("{} {} has Type=Flag but Number is '{}' (should be 0)", getLabel(), getId(), number);
    }
    // see the typed constructor's identical check for the citation
    if (m_type != null && m_type != InfoType.Flag && "0".equals(number)) {
      warnOrThrow("{} {} has Number '0' but Type is '{}' (Number=0 is only valid for Type=Flag)", getLabel(),
          getId(), m_type);
    }
    // getPropertyRaw here is deliberate, not an oversight -- see IdDescriptionMetadata.init()'s identical
    // Description check for why: this inspects the raw quoting itself, not content that must be unquoted first.
    for (String optional : new String[] { SOURCE, VERSION }) {
      String value = getPropertyRaw(optional);
      // length() < 2 checked first: a single '"' character satisfies both startsWith("\"") and endsWith("\"")
      // on that same character, so without this guard an unterminated single quote silently passed as
      // "properly quoted" -- InternalUtils.unquote() already guards this identical shape the same way.
      // Found by the fifth re-verification round's finding #3.
      if (value != null && (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\""))) {
        warnOrThrow("{} metadata property \"{}\" should be quoted but was: {}", getLabel(), optional, value);
      }
    }
    String id = getPropertyUnquoted(ID);
    if (id != null && !id.isEmpty()) {
      // strip commas/whitespace before checking: IdMetadata's own init() (which already ran, before this method,
      // during construction) already warned about either separately -- stripping them first means this check only
      // fires for a genuinely different defect, not a second warning about the same one. Matches ContigMetadata's
      // own "keep searching past a comma for a different defect" precedent for this exact situation, adapted for a
      // whole-string pattern instead of a per-character scan.
      String stripped = id.replaceAll("[,\\s]", "");
      if (!stripped.isEmpty() && !InternalUtils.INFO_PATTERN.matcher(stripped).matches()) {
        warnOrThrow("{} ID \"{}\" does not match the required tag-name grammar (must start with a letter or " +
            "underscore, followed only by letters, digits, underscores, or periods -- or be the legacy value " +
            "'1000G')", getLabel(), id);
      }
    }
    // version-aware; see the attribute constructor's identical call above for the citation
    ReservedInfoProperty reserved = ReservedInfoProperty.fromId(id, getContext().getVersion());
    if (reserved != null) {
      if (number != null && !reserved.getExpectedNumber().equals(number)) {
        warnOrThrow("{} {} has Number '{}' but the reserved definition expects '{}'", getLabel(), getId(),
            number, reserved.getExpectedNumber());
      }
      if (m_type != null && !InternalUtils.toJavaType(m_type).equals(reserved.getType())) {
        warnOrThrow("{} {} has Type '{}' but the reserved definition expects '{}'", getLabel(), getId(),
            m_type, InternalUtils.describeJavaTypeAsVcfType(reserved.getType()));
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
   * @return The {@code Number} tag's value, <em>unquoted</em>: a declaration written {@code Number="1"}
   * returns {@code 1}, with any {@code \"}/{@code \\} escapes decoded. Quoting is an encoding of the header
   * syntax, not part of the value -- see VALIDATION_PHILOSOPHY.md's value/encoding rule. Use
   * {@link #getPropertyRaw(String) getPropertyRaw(Number)} for the original spelling as it appeared in the
   * file.
   * <p>
   * Null only when incorrectly constructed without one.
   */
  @Nullable
  public String getNumber() {
    return getPropertyUnquoted(NUMBER);
  }

  /**
   * @return A special (reserved) <em>Number</em> ("A", "G", "R", or "."), or null if the Number is not reserved
   * (it is numerical) or absent.
   */
  @Nullable
  public SpecialVcfNumber getReservedNumber() {
    // getPropertyUnquoted, not getPropertyRaw: fromId() is an exact-match comparison against a fixed set
    // ("A"/"G"/"R"/"."), so it must see the Number's actual content rather than its header-syntax quoting.
    // See AGENTS.md's "Unquoting before internal use" policy.
    return SpecialVcfNumber.fromId(getPropertyUnquoted(NUMBER));
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

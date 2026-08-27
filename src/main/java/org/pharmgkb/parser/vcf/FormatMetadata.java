package org.pharmgkb.parser.vcf;

import java.util.Map;
import org.jspecify.annotations.Nullable;


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
public final class FormatMetadata extends IdDescriptionMetadata {

  public static final String DESCRIPTION = "Description";
  public static final String NUMBER = "Number";
  public static final String TYPE = "Type";

  private @Nullable FormatType m_type;

  FormatMetadata(VcfContext context, @Nullable String id, @Nullable String description, @Nullable String number,
      @Nullable FormatType type) {
    super(context, "FORMAT", id);
    // Checked unquoted, not raw -- see InfoMetadata's identical constructor for why (there is no stored property
    // to read yet, so this unquotes the constructor parameters directly via InternalUtils.unquote rather than
    // getPropertyUnquoted). Without this, a quoted reserved ID (e.g. ID="GT") both false-rejected the
    // tag-name-grammar check below and silently skipped the ReservedFormatProperty comparison entirely, since
    // neither ever saw the value with its quotes stripped -- matching init()'s own getPropertyUnquoted(ID)/
    // getPropertyUnquoted(NUMBER) reasoning for the Map-based constructor just below. unquotedId/unquotedNumber
    // change only what is tested, not what is stored: putPropertyRaw below still stores the caller's original
    // spelling.
    String unquotedId = id == null ? null : InternalUtils.unquote(id);
    String unquotedNumber = number == null ? null : InternalUtils.unquote(number);
    // see InfoMetadata's identical constructor for why id can be null (or blank/comma-containing) here under a
    // lenient context, and for why this strips commas/whitespace before checking -- matches init()'s own
    // identical reasoning for the Map-based constructor just below (a prior version of this check omitted the
    // stripping, which re-warned about a comma IdMetadata's own super(...) call had already reported).
    if (unquotedId != null && !unquotedId.isEmpty()) {
      String stripped = unquotedId.replaceAll("[,\\s]", "");
      if (!stripped.isEmpty() && !InternalUtils.FORMAT_PATTERN.matcher(stripped).matches()) {
        warnOrThrow("{} ID \"{}\" does not match the required tag-name grammar (must start with a letter or " +
            "underscore, followed only by letters, digits, underscores, or periods)", getLabel(), unquotedId);
      }
    }
    // Validated before putPropertyRaw stores it below: see InfoMetadata's identical constructor for why
    // checking format here first (rather than after) is what makes a null or malformed Number actually
    // produce this class's own, more specific message instead of putPropertyRaw's generic null-check one.
    if (unquotedNumber == null || !InternalUtils.NUMBER_PATTERN.matcher(unquotedNumber).matches()) {
      warnOrThrow("FORMAT Number is not a valid VCF number: '{}'", unquotedNumber);
    }
    // Number=0 is spec-tied exclusively to Type=Flag (VCFv4.3.tex line 171, "The 'Flag' type indicates that
    // the INFO field does not contain a Value entry, and hence the Number must be 0 in this case"; line 190,
    // "Possible Types for FORMAT fields are: Integer, Float, Character, and String" -- FORMAT has no Flag type
    // at all), so it can never legitimately appear on a real ##FORMAT declaration, regardless of the declared
    // Type. Unlike InfoMetadata's identical rule (checked the other direction there: Type=Flag implies
    // Number=0), there is no valid Type to pair Number=0 with here, so this fires unconditionally.
    if ("0".equals(unquotedNumber)) {
      warnOrThrow("FORMAT {} has Number '0', but FORMAT has no Flag type for it to pair with (Number=0 is " +
          "only valid for an INFO Flag)", unquotedId);
    }
    if (number != null) {
      putPropertyRaw(NUMBER, number);
    }
    // type is typed @Nullable only so a lenient context can recover from its absence; guard here rather than
    // letting type.name() below throw a raw NullPointerException
    if (type == null) {
      warnOrThrow("FORMAT type is null");
    } else {
      putPropertyRaw(TYPE, type.name());
    }
    // Description inserted last -- see InfoMetadata's identical constructor for why (matches canonical
    // ID, Number, Type, Description order per VCFv4.2.tex line 94, "##FORMAT=<ID=ID,Number=number,
    // Type=type,Description=\"description\">").
    if (description == null) {
      warnOrThrow("{} required metadata property \"{}\" is missing", getLabel(), DESCRIPTION);
    } else {
      putAndQuoteProperty(DESCRIPTION, description);
    }
    // see InfoMetadata's identical constructor for why Description-quoting and Type-parse-failure have no
    // check here.
    // version-aware: GLE stops being a reserved key at VCFv4.3 (VCFv4.3.tex line 2168, "Removed unused and
    // ill-defined GLE FORMAT tag"), so a 4.3+ ##FORMAT=<ID=GLE,...> is validated only against its own declared
    // Number/Type, like any custom key -- never against this library's 4.1/4.2 canonical values
    VcfVersion version = getContext().getVersion();
    ReservedFormatProperty reserved = ReservedFormatProperty.fromId(unquotedId, version);
    if (reserved != null) {
      // version-aware: CNL is the one reserved key whose expected Number differs by version (VCFv4.2.tex:308
      // "Number=.", VCFv4.3.tex:678 "Number=G") -- see that constant's own comment for the citations
      //
      // unquotedNumber != null: without this guard, a missing Number (already warned about above) also
      // spuriously failed this check -- X.equals(null) is always false, so !X.equals(null) is always true --
      // stacking a second, redundant warning on top of the first. init()'s own identical check (the Map-based
      // constructor below) already has this guard; this typed constructor's copy didn't. Found by the fifth
      // re-verification round's finding #7.
      if (unquotedNumber != null && !reserved.getExpectedNumber(version).equals(unquotedNumber)) {
        warnOrThrow("FORMAT {} has Number '{}' but the reserved definition expects '{}'", unquotedId, unquotedNumber,
            reserved.getExpectedNumber(version));
      }
      if (type != null && !InternalUtils.toJavaType(type).equals(reserved.getType())) {
        warnOrThrow("FORMAT {} has Type '{}' but the reserved definition expects '{}'", unquotedId, type,
            InternalUtils.describeJavaTypeAsVcfType(reserved.getType()));
      }
    }
    m_type = type;
  }

  FormatMetadata(VcfContext context, Map<String, String> properties) {
    super(context, "FORMAT", properties);
    init();
  }

  private void init() {
    m_type = null;
    // getPropertyUnquoted, not getPropertyRaw, for Number/Type/ID below -- see InfoMetadata.init()'s identical
    // comment (this class's own Number/Type/ID validation mirrors it exactly) and AGENTS.md's "Unquoting
    // before internal use" policy.
    String number = getPropertyUnquoted(NUMBER);
    checkNumberProperty(number);
    // see the typed constructor's identical check for the citation
    if ("0".equals(number)) {
      warnOrThrow("{} {} has Number '0', but FORMAT has no Flag type for it to pair with (Number=0 is only " +
          "valid for an INFO Flag)", getLabel(), getId());
    }
    String type = getPropertyUnquoted(TYPE);
    warnIfMissing(TYPE, type);
    if (type != null) {
      try {
        m_type = FormatType.valueOf(type);
      } catch (IllegalArgumentException e) {
        warnOrThrow("{} {} '{}' is not a valid {} type", getLabel(), TYPE, type, getLabel());
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
      if (!stripped.isEmpty() && !InternalUtils.FORMAT_PATTERN.matcher(stripped).matches()) {
        warnOrThrow("{} ID \"{}\" does not match the required tag-name grammar (must start with a letter or " +
            "underscore, followed only by letters, digits, underscores, or periods)", getLabel(), id);
      }
    }
    // version-aware; see the attribute constructor's identical call above for the citation
    VcfVersion version = getContext().getVersion();
    ReservedFormatProperty reserved = ReservedFormatProperty.fromId(id, version);
    if (reserved != null) {
      if (number != null && !reserved.getExpectedNumber(version).equals(number)) {
        warnOrThrow("{} {} has Number '{}' but the reserved definition expects '{}'", getLabel(), getId(),
            number, reserved.getExpectedNumber(version));
      }
      if (m_type != null && !InternalUtils.toJavaType(m_type).equals(reserved.getType())) {
        warnOrThrow("{} {} has Type '{}' but the reserved definition expects '{}'", getLabel(), getId(),
            m_type, InternalUtils.describeJavaTypeAsVcfType(reserved.getType()));
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
    // getPropertyUnquoted, not getPropertyRaw -- see InfoMetadata.getReservedNumber()'s identical comment.
    return SpecialVcfNumber.fromId(getPropertyUnquoted(NUMBER));
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public FormatType getType() {
    return m_type;
  }
}

package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;


/**
 * A strictly validated VCF metadata ALT code of the form:
 * {@code
 *   ##ALT=<ID=type,Description=description>
 * }
 * Where {@code ID} is a colon-delimited list of identifiers. Some of these identifiers are reserved, as coded in the
 * {@link ReservedStructuralVariantCode} class. The first identifier (at level 0) is required to be reserved.
 * As explicitly stated in the spec, these codes are case-sensitive.
 * <p>
 * Example:
 * <pre>{@code
 *   AltStructuralVariant alt = new AltStructuralVariant("INS:ME:LINE");
 *   alt.getReservedComponent(0); // ReservedStructuralVariantCode.Insertion
 *   alt.getReservedComponent(1); // ReservedStructuralVariantCode.MobileElement
 *   alt.getReservedComponent(2); // null, because it's not a reserved code
 *   alt.getComponent(2); // "LINE"
 * }
 * </pre>
 *
 * @author Douglas Myers-Turnbull
 */
public class AltStructuralVariant {

  private static final Pattern sf_colon = Pattern.compile(":");

  private final List<String> m_components;

  /**
   * @param string The full code (e.g. INS:ME:LINE:type-a1)
   */
  public AltStructuralVariant(String string) {

    if (string == null) {
      throw new VcfFormatException("Structural variant code is null");
    }
    if (string.isEmpty()) { // could be replaced with javax.validation.constraints.Size(min=1);
      throw new VcfFormatException("Structural variant code must not be empty");
    }
    if (InternalUtils.containsWhitespace(string) || string.chars().anyMatch(c -> c == '<' || c == '>' || c == ',')) {
      // VCFv4.3.tex line 200 (the target, unchanged through VCFv4.4.tex:209/VCFv4.5.tex:258) states directly:
      // "ID values are case sensitive strings and must not contain whitespace, commas or angle brackets."
      // VCFv4.2.tex line 105 states the same sentence without the comma ("...may not contain whitespace or
      // angle brackets"), predating the comma rule rather than conflicting with it. hts-specs'
      // own test/vcf/4.2/failed/failed_meta_alt_007.vcf (and an identical test/vcf/4.3 counterpart) declares
      // ID=DEL:A,B with ##CauseOfFailure=The ALT ID contains a comma, confirming this is rejected under the
      // VCFv4.2 spec too, not just a 4.3+-only rule.
      throw new VcfFormatException("Structural variant code \"" + string + "\" must not contain whitespace, " +
          "commas, or angle brackets");
    }

    // limit -1 preserves trailing empty components (e.g. a trailing colon), which are rejected below rather than
    // silently dropped as Pattern.split's default limit of 0 would do
    String[] components = sf_colon.split(string, -1);
    m_components = new ArrayList<>(components.length);

    for (int level = 0; level < components.length; level++) {
      if (components[level].isEmpty()) {
        // VCFv4.3.tex line 39 (the target) states directly "Zero length fields are not allowed, a dot ('.')
        // must be used instead" -- the general rule this component-level check applies. VCFv4.2.tex does not
        // table this rule at all, predating it rather than conflicting with it -- consistent with
        // EMPTY_FIELD_HANDLING.md's citation of the identical rule for generic metadata properties.
        throw new VcfFormatException("Structural variant code \"" + string + "\" has an empty component at level " +
            level);
      }
      ReservedStructuralVariantCode type = ReservedStructuralVariantCode.fromId(components[level]);

      // Make sure the top-level code exists
      if (type == null && level == 0) {
          // VCFv4.2.tex line 105 ("The first level type must be one of the following:") introduces the
          // itemized list at lines 107-111 (DEL/INS/DUP/INV/CNV); ReservedStructuralVariantCode's own comment
          // adds BND (VCFv4.3.tex:208) as a sixth accepted level-0 code -- an unrecognized level-0 code matches
          // none of these six and has no reserved meaning to fall back on.
          throw new VcfFormatException("Top-level structural variant code was " + components[level] +
              " but must be a top-level reserved code (e.g. DEL or CNV)");
      }

      // If this is a reserved code, make sure the level matches
      if (type != null && level != type.getLevelInSpecification()) {
        // VCFv4.2.tex lines 105/107-111 establish DEL/INS/DUP/INV/CNV (plus BND, VCFv4.3.tex:208) as level-0
        // reserved codes; line 114's "Reserved subtypes" (DUP:TANDEM, DEL:ME, INS:ME) establishes TANDEM/ME as
        // level-1 -- a level-1 reserved code used bare at level 0 (e.g. "TANDEM") has drifted from its only
        // defined position in that table, directly supported by this text. The OTHER direction this same
        // check also rejects -- a level-0 code re-used at a non-zero level (e.g. "DUP:INV") -- is NOT
        // spec-mandated: VCFv4.2:114's "Reserved subtypes include:" is explicitly non-exhaustive, and
        // VCFv4.4.tex:221 ("Implementations are free to define their own subtypes") confirms VCF doesn't
        // reserve the space of valid subtypes at all. Rejecting that direction is this library's own stricter
        // reading, not a spec rule -- see VCF_SPEC_AMBIGUITIES.md's "Reserved structural variant code re-used
        // as a subtype" entry if this needs revisiting against bcftools.
        throw new VcfFormatException("Structural variant code " + components[level] +
            " is a reserved code of level " + type.getLevelInSpecification() + ", not " + level);
      }

      // If this is a reserved code, make sure its parent is correct
      if (type != null && level > 0) {
        ReservedStructuralVariantCode realParent = ReservedStructuralVariantCode.fromId(m_components.get(level - 1));
        if (realParent != null) {
          boolean foundMatch = false;
          for (ReservedStructuralVariantCode parent : type.getParentCodes()) {
            if (realParent == parent) {
              foundMatch = true;
              break;
            }
          }
          if (!foundMatch) {
            // VCFv4.2.tex lines 114-118's three worked examples (DUP:TANDEM, DEL:ME, INS:ME) each pair a
            // level-1 reserved subtype with exactly one level-0 parent -- e.g. TANDEM is a reserved subtype
            // only under DUP, never under DEL or INS.
            throw new VcfFormatException("Structural variant code " + components[level] +
                " was not a child of reserved code " + realParent.getId());
          }
        }
      }

      // We're good!
      m_components.add(components[level]);
    }
  }

  /**
   * @return An unmodifiable view of the codes in order from level 0 to level n; for example ("INS", "ME", "LINE")
   */
  public List<String> getComponents() {
    return Collections.unmodifiableList(m_components);
  }

  /**
   * @return The code at the specified level (e.g. CNV)
   * @throws IndexOutOfBoundsException
   */
  public String getComponent(int level) {
    return m_components.get(level);
  }

  /**
   * @return The code at the specified level (e.g. CNV), or null if it is not a reserved code
   * @throws IndexOutOfBoundsException
   */
  @Nullable
  public ReservedStructuralVariantCode getReservedComponent(int level) {
    return ReservedStructuralVariantCode.fromId(m_components.get(level));
  }

  /**
   * @return The original string (e.g. INS:ME:LINE:type-a1)
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(m_components.get(0));
    for (int i = 1; i < m_components.size(); i++) {
      sb.append(":").append(m_components.get(i));
    }
    return sb.toString();
  }
}

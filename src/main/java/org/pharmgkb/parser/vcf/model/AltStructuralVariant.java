package org.pharmgkb.parser.vcf.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
 *   alt.getComponent(); // "LINE"
 * }
 * </pre>
 *
 * @author Douglas Myers-Turnbull
 */
public class AltStructuralVariant {

  private static final Pattern sf_colon = Pattern.compile(":");

  private List<String> m_components;

  /**
   * @param string The full code (e.g. INS:ME:LINE:type-a1)
   */
  public AltStructuralVariant(@Nonnull String string) {

    if (string.isEmpty()) { // could be replaced with javax.validation.constraints.Size(min=1);
      throw new IllegalArgumentException("Structural variant code must not be empty");
    }

    String[] components = sf_colon.split(string);
    m_components = new ArrayList<>(components.length);

    for (int level = 0; level < components.length; level++) {
      ReservedStructuralVariantCode type = ReservedStructuralVariantCode.fromId(components[level]);

      // Make sure the top-level code exists
      if (type == null && level == 0) {
          throw new IllegalArgumentException("Top-level structural variant code was " + components[level]
          + " but must be a top-level reserved code (e.g. DEL or CNV)");
      }

      // If this is a reserved code, make sure the level matches
      if (type != null && level != type.getLevelInSpecification()) {
        throw new IllegalArgumentException("Structural variant code " + components[level]
            + " is a reserved code of level " + type.getLevelInSpecification() + ", not " + level);
      }

      // If this is a reserved code, make sure its parent is correct
      if (type != null && level > 0) {
        ReservedStructuralVariantCode realParent = ReservedStructuralVariantCode.fromId(m_components.get(level - 1));
        if (realParent != null) {
          boolean foundMatch = false;
          for (ReservedStructuralVariantCode parent : type.getParentCodes()) {
            if (realParent == parent) {
              foundMatch = true;
            }
          }
          if (!foundMatch) {
            throw new IllegalArgumentException("Structural variant code " + components[level]
               + " was not a child of reserved code " + realParent.getId());
          }
        }
      }

      // We're good!
      m_components.add(components[level]);
    }
  }

  /**
   * @return The list of codes in order from level 0 to level n; for example ("INS", "ME", "LINE")
   */
  @Nonnull
  public List<String> getComponents() {
    return m_components;
  }

  /**
   * @return The code at the specified level (e.g. CNV)
   * @throws ArrayIndexOutOfBoundsException
   */
  @Nonnull
  public String getComponent(int level) {
    return m_components.get(level);
  }

  /**
   * @return The code at the specified level (e.g. CNV), or null if it is not a reserved code
   * @throws ArrayIndexOutOfBoundsException
   */
  @Nullable
  public ReservedStructuralVariantCode getReservedComponent(int level) {
    return ReservedStructuralVariantCode.fromId(m_components.get(level));
  }

  /**
   * @return The original string (e.g. INS:ME:LINE:type-a1)
   */
  @Override
  @Nonnull
  public String toString() {
    StringBuilder sb = new StringBuilder(m_components.get(0));
    for (int i = 1; i < m_components.size(); i++) {
      sb.append(":").append(m_components.get(i));
    }
    return sb.toString();
  }

}

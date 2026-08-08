package org.pharmgkb.parser.vcf;

import java.util.Map;
import org.jspecify.annotations.Nullable;


/**
 * This class represents a single VCF metadata line with an id and description.
 * <p>
 * In 4.1/4.2:
 * <pre>{@code
 * ##ALT=<ID=type,Description="description">
 * ##FILTER=<ID=ID,Description="description">
 * }
 * </pre>
 *
 * @author Mark Woon
 */
public class IdDescriptionMetadata extends IdMetadata {

  public static final String DESCRIPTION = "Description";

  public IdDescriptionMetadata(String label, String id, String description) {
    super(label, id);
    putAndQuoteProperty(DESCRIPTION, description);
    // strict construction: no init() call. init()'s only check (Description must be quoted) can never fail here
    // -- putAndQuoteProperty above always wraps description in quotes itself -- so there is no throw-equivalent
    // to add; see this class's own init() for the warn-based check the lenient (Map-based) constructor still runs.
  }

  public IdDescriptionMetadata(String label, Map<String, String> properties, VcfWarnings warnings) {
    super(label, properties, warnings);
    init();
    checkAttributeOrder();
  }

  /**
   * For subclasses (InfoMetadata, FormatMetadata) whose own typed constructor must insert additional properties
   * between ID and Description to match their own canonical attribute order (see
   * BaseMetadata#checkAttributeOrder) -- unlike the (label, id, description) constructor above, this does
   * not insert Description; the caller must add it itself afterward, via putAndQuoteProperty, in whatever
   * position is canonical for its own subtype.
   */
  protected IdDescriptionMetadata(String label, String id) {
    super(label, id);
  }

  @Override
  public void validate() {
    super.validate();
    init();
  }

  private void init() {
    String description = getPropertyRaw(DESCRIPTION);
    warnIfMissing(DESCRIPTION, description);
    if (description != null && (!description.startsWith("\"") || !description.endsWith("\""))) {
      m_warnings.warn("{} metadata property \"{}\" should be quoted but was: {}", getLabel(),
          DESCRIPTION, description);
    }
    // VCFv4.2: "For all of the ##INFO, ##FORMAT, ##FILTER, and ##ALT metainformation, extra fields can be included
    // after the default fields" -- so an unrecognized property here is not itself non-compliant and must not warn.
    // This class is also used for ##SAMPLE (not named in that sentence, but its own extra fields, e.g. Genomes and
    // Mixture, aren't modeled by this class either way, so the same leniency applies rather than warning about them.)
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public String getDescription() {
    return getPropertyUnquoted(DESCRIPTION);
  }


}

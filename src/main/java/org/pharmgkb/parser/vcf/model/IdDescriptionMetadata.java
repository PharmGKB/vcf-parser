package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ID = "ID";
  public static final String DESCRIPTION = "Description";

  public IdDescriptionMetadata(String id, String description) {
    super(id, false);
    putAndQuoteProperty(DESCRIPTION, description);
    init();
  }

  public IdDescriptionMetadata(Map<String, String> properties) {
    super(properties, false);
    init();
    checkAttributeOrder(sf_logger);
  }

  /**
   * For subclasses (InfoMetadata, FormatMetadata) whose own typed constructor must insert additional properties
   * between ID and Description to match their own canonical attribute order (see
   * BaseMetadata#checkAttributeOrder) -- unlike the (id, description) constructor above, this does
   * not insert Description; the caller must add it itself afterward, via putAndQuoteProperty, in whatever
   * position is canonical for its own subtype.
   */
  protected IdDescriptionMetadata(String id) {
    super(id, false);
  }

  @Override
  public void validate() {
    super.validate();
    init();
  }

  private void init() {
    String description = getPropertyRaw(DESCRIPTION);
    warnIfMissing(sf_logger, DESCRIPTION, description);
    if (description != null && (!description.startsWith("\"") || !description.endsWith("\""))) {
      sf_logger.warn("Metadata property \"{}\" should be quoted but was: {}", DESCRIPTION, description);
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

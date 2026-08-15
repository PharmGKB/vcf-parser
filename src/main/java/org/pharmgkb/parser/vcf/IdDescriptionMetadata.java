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
 * <p>
 * {@code sealed}: see {@link BaseMetadata}'s class Javadoc for why the hierarchy is closed. This class is
 * itself directly instantiable (it backs {@code ##ALT}, {@code ##FILTER} and {@code ##SAMPLE}, which share this
 * shape), so it is {@code sealed} rather than {@code final}.
 *
 * @author Mark Woon
 */
public sealed class IdDescriptionMetadata extends IdMetadata permits InfoMetadata, FormatMetadata {

  public static final String DESCRIPTION = "Description";

  IdDescriptionMetadata(VcfContext context, String label, @Nullable String id, @Nullable String description) {
    super(context, label, id);
    // no init() call: init()'s only check (Description must be quoted) can never fail here -- putAndQuoteProperty
    // below always wraps description in quotes itself. An absent Description is the one condition this shape
    // shares with init(), so it reports it with init()'s own warnIfMissing message rather than letting
    // putAndQuoteProperty's generic null rejection fire -- that would hard-throw even under a lenient context,
    // which is exactly the overload-shape-decides-strictness coupling warnOrThrow exists to remove.
    if (description == null) {
      warnOrThrow("{} required metadata property \"{}\" is missing", getLabel(), DESCRIPTION);
    } else {
      putAndQuoteProperty(DESCRIPTION, description);
    }
  }

  IdDescriptionMetadata(VcfContext context, String label, Map<String, String> properties) {
    super(context, label, properties);
    init();
  }

  /**
   * For subclasses (InfoMetadata, FormatMetadata) whose own typed constructor must insert additional properties
   * between ID and Description to match their own canonical attribute order (ID, Number, Type, Description,
   * Source, Version -- unenforced, but still followed for output readability; see VCF_SPEC_AMBIGUITIES.md's
   * "##META attribute order" entry) -- unlike the (label, id, description) constructor above, this does
   * not insert Description; the caller must add it itself afterward, via putAndQuoteProperty, in whatever
   * position is canonical for its own subtype.
   */
  IdDescriptionMetadata(VcfContext context, String label, @Nullable String id) {
    super(context, label, id);
  }

  @Override
  public void validate() {
    super.validate();
    init();
  }

  private void init() {
    // getPropertyRaw is correct here, deliberately not getPropertyUnquoted: this check inspects the raw
    // quoting itself (are the surrounding quote characters actually present), not the ID-style "unquote before
    // comparing content" case AGENTS.md's "Unquoting before internal use" policy covers -- unquoting first
    // would make startsWith/endsWith("\"") trivially false for every validly-quoted value.
    String description = getPropertyRaw(DESCRIPTION);
    warnIfMissing(DESCRIPTION, description);
    if (description != null && (!description.startsWith("\"") || !description.endsWith("\""))) {
      warnOrThrow("{} metadata property \"{}\" should be quoted but was: {}", getLabel(),
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
  public final String getDescription() {
    return getPropertyUnquoted(DESCRIPTION);
  }


}

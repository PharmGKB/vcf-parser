package org.pharmgkb.parser.vcf;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;


/**
 * A VCF metadata element with an Id (called "ID").
 * <p>
 * {@code sealed}: see {@link BaseMetadata}'s class Javadoc for why the hierarchy is closed.
 */
public abstract sealed class IdMetadata extends BaseMetadata permits IdDescriptionMetadata, ContigMetadata {

  public static final String ID = "ID";

  /**
   * Typed shape. Package-private, like every declaration constructor: {@code VcfMetadata.Builder} is the only
   * supported construction path, so a declaration can never be built outside the context it will live in.
   * <p>
   * Every check here routes through {@link #warnOrThrow}, so a strict context throws exactly what this
   * constructor used to throw unconditionally, and a lenient one warns and recovers -- the same content
   * producing the same outcome through this shape and through the {@code Map}-based one below.
   */
  IdMetadata(VcfContext context, String label, @Nullable String id) {
    super(context, label, new LinkedHashMap<>());
    // Checked unquoted, not raw -- same reason as every other comparison/validation site in this hierarchy;
    // see AGENTS.md's "Unquoting before internal use" policy. Unlike init() below, there is no stored property
    // to read yet at this point (putPropertyRaw only runs once validation passes, at the bottom of this
    // constructor), so this unquotes the constructor parameter directly via InternalUtils.unquote rather than
    // getPropertyUnquoted. id itself is still stored unmodified (putPropertyRaw below keeps the caller's
    // spelling verbatim, which is what getPropertyRaw(ID) hands back and what the writer emits; getId()
    // unquotes on read) -- unquoting here changes only what is tested, not what is stored: a caller passing
    // id="\"   \"" (e.g. via VcfMetadata.Builder.addFilter(String, String)) previously got the misleading
    // "must not contain whitespace" from the raw quote/space characters here, one step before addFilter's own
    // blank check correctly dropped it as blank; both checks now agree.
    String unquotedId = id == null ? null : InternalUtils.unquote(id);
    // VCFv4.3.tex line 121: "All structured lines require an ID..." -- VCFv4.2 doesn't use the word "required" but
    // its own templates for all six keyed types show ID unconditionally first; see DUPLICATE_METADATA_ID_HANDLING.md
    if (unquotedId == null || unquotedId.trim().isEmpty()) {
      warnOrThrow("ID is required and must not be blank");
      // nothing usable to store: putPropertyRaw would itself reject a null, and a blank ID leaves this
      // declaration keyed on nothing. init() reports the same condition for the Map-based shape.
      return;
    }
    // see init()'s own comment (cited from VCFv4.2.tex/VCFv4.3.tex line 105/200 and the hts-specs fixtures named
    // there) for why an ID may not contain whitespace
    if (InternalUtils.containsWhitespace(unquotedId)) {
      warnOrThrow("{} metadata ID \"{}\" must not contain whitespace", label, unquotedId);
    }
    // same reasoning as the whitespace check above -- see init()'s own comment (cited from every
    // ##INFO/##FILTER/##FORMAT/##ALT/##contig/##SAMPLE worked example's shared comma-delimited attribute-list
    // grammar) for why this applies uniformly, including to ALT, with no exception.
    if (unquotedId.contains(",")) {
      warnOrThrow("{} metadata ID \"{}\" must not contain a comma", label, unquotedId);
    }
    putPropertyRaw(ID, id);
  }

  /**
   * {@code Map}-based (parser) shape. Takes no {@code VcfWarnings}: the context supplies the sink, and
   * {@link #warnOrThrow} decides warn-vs-throw from that same context.
   */
  IdMetadata(VcfContext context, String label, Map<String, String> properties) {
    super(context, label, properties);
    init();
  }

  @Override
  public void validate() {
    super.validate();
    init();
    checkAttributeOrder();
  }

  private void init() {
    // getPropertyUnquoted, not getPropertyRaw: this is a comparison/validation use (blank- and
    // whitespace-checking the ID's actual content), so it must see the value with any surrounding quote pair
    // already stripped -- see AGENTS.md's "Unquoting before internal use" policy and VALIDATION_PHILOSOPHY.md's
    // "How the principles interact." Without this, a quoted-empty ID (ID="") or quoted-whitespace ID
    // (ID="   ") kept its literal quote/space characters, which are never blank once trimmed, and escaped
    // every check below -- see the retired WONT_FIX.md entry.
    String id = getPropertyUnquoted(ID);
    warnIfMissing(ID, id);
    // warnIfMissing only tests for null; a present-but-blank ID (id.equals("")) previously got no diagnostic at
    // all here -- containsWhitespace(id) below doesn't fire either, since an empty string has no characters to
    // contain whitespace. This class's own typed (label, id) constructor already rejects both null and blank
    // (VCFv4.3.tex line 121: "All structured lines require an ID..."); this closes the identical gap for the
    // Map-based constructor and for validate() re-running on a live-mutated blank ID, via the same
    // already-active validate()/VcfWriter.validateBeforeWrite() mechanism the checks below rely on.
    // id.trim().isEmpty() (not id.isEmpty()) also catches a whitespace-only ID (e.g. "   "): that constructor's
    // own blank check uses the same trim()-based test, so this mirrors it instead of letting a whitespace-only
    // ID fall through to only the whitespace check below, which would misleadingly read as "trim it and it's
    // fine" when there is no usable content at all once trimmed. The two checks are mutually exclusive (else
    // if): a blank-when-trimmed ID gets exactly one warning, not both.
    if (id != null && id.trim().isEmpty()) {
      warnOrThrow("{} metadata ID is blank", getLabel());
    } else if (id != null && InternalUtils.containsWhitespace(id)) {
      // VCFv4.2.tex/VCFv4.3.tex line 105/200 only states this rule for ##ALT's own ID explicitly, but hts-specs'
      // own conformance fixtures (test/vcf/{4.1,4.2,4.3}/failed/failed_meta_contig_001.vcf,
      // test/vcf/4.3/failed/failed_meta_sample_001.vcf) extend it to ##contig and ##SAMPLE, bcftools 1.23.1
      // empirically warns ("Invalid tag name"/"Invalid contig name") for ##INFO/##FORMAT/##contig ID
      // whitespace, and vcf_validator 0.10.2 rejects whitespace uniformly across every structured metadata ID
      // type. For ##FILTER/##SAMPLE specifically, bcftools 1.23.1 accepts whitespace silently -- it has no
      // dedicated header check for either -- directly contradicting hts-specs' own failed_meta_sample_001.vcf
      // fixture; this is treated as a bcftools coverage gap rather than a considered position, so the
      // hts-specs fixture and vcf_validator's uniform treatment govern instead, per this project's decision
      // hierarchy.
      warnOrThrow("{} metadata ID \"{}\" must not contain whitespace", getLabel(), id);
    }
    // Every ##INFO/##FILTER/##FORMAT/##ALT/##contig/##SAMPLE worked example in VCFv4.2.tex (e.g. line 35:
    // "##INFO=<ID=NS,Number=1,...>") delimits attributes with an unescaped comma; a literal comma inside an ID
    // value would be indistinguishable from the end of the ID attribute itself, for every one of these six
    // types uniformly -- unlike the whitespace check above, this needs no bcftools/vcf_validator corroboration,
    // since it follows directly from the attribute-list grammar every example shares, not a per-type policy
    // choice. Deliberately not scoped away from ALT the way angle brackets are (see VcfMetadata.validateAlt()'s
    // own comment): a comma is never part of either of ALT's spec-sanctioned colonless conventions (an IUPAC
    // ambiguity code or a CN0-style copy-number allele) or its colon-delimited structural-variant-code grammar,
    // so this check applies to every type this class covers, with no exception.
    if (id != null && id.contains(",")) {
      warnOrThrow("{} metadata ID \"{}\" must not contain a comma", getLabel(), id);
    }
  }

  /**
   * @return The {@code ID} tag's value, <em>unquoted</em>: a declaration written {@code ID="foo"} returns
   * {@code foo}, with any {@code \"}/{@code \\} escapes decoded. Quoting is an encoding of the header syntax,
   * not part of the value -- see VALIDATION_PHILOSOPHY.md's value/encoding rule. This can therefore no longer
   * disagree over quoting <em>alone</em> with the string this declaration is keyed under in
   * {@link VcfMetadata#getAlts()} and its siblings, which key by the same unquoted form. (The two can still be
   * made to diverge by mutating a stored declaration's {@code ID} in place through the package-private
   * {@code BaseMetadata.trustedMutableProperties()} bypass, which is why
   * {@code VcfWriter.validateBeforeWrite()} still checks them against each other.) Use
   * {@link #getPropertyRaw(String) getPropertyRaw(ID)} for the original spelling as it appeared in the file.
   * <p>
   * Null only when incorrectly constructed without one.
   */
  @Nullable
  public final String getId() {
    return getPropertyUnquoted(ID);
  }

}

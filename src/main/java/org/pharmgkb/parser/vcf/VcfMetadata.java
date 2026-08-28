package org.pharmgkb.parser.vcf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import com.google.common.base.Preconditions;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.errorprone.annotations.RestrictedApi;
import org.jspecify.annotations.Nullable;


/**
 * This class captures all the VCF metadata from a VCF file.
 * <p>
 * <strong>Effectively immutable through this library's public API, and not thread-safe.</strong> Every
 * collection-returning getter hands back an unmodifiable view, and there are no instance mutators: the only way
 * to produce a changed {@code VcfMetadata} is {@link #toBuilder()}, edit the {@link Builder}, and
 * {@link Builder#build()} a new one. The declarations it holds are immutable too (see {@link BaseMetadata}'s
 * class Javadoc). "Effectively" rather than outright immutable because each unmodifiable view is memoized into
 * a field on first access -- an unsynchronized lazy write with no externally observable effect for a
 * single-threaded caller, but a data race across threads, so this class makes a <em>value</em> guarantee, not a
 * concurrency guarantee.
 * <p>
 * Both its warnings destination and its strict-vs-lenient behavior come from the {@link VcfContext} its
 * {@link Builder} was constructed with, and that context is fixed for this object's whole lifetime -- the same
 * is true of every individual <em>declaration</em> it holds ({@link InfoMetadata}, {@link ContigMetadata},
 * etc.), each fixed to its own construction-time context. The consequence for a caller handing a
 * {@code VcfMetadata} to a {@link org.pharmgkb.parser.vcf.VcfWriter}: the writer cannot re-route this
 * container's own warn sites ({@link Builder#addInfo(String, String, InfoType, String, String, String)},
 * {@link Builder#addRawProperty}, and friends) or any individual declaration's -- they always go wherever their
 * own context points -- for a parsed file, the parser's sink, which by write time may already be closed, in
 * which case {@link VcfWarnings} falls back to its shared SLF4J logger. See {@link VcfWarnings}'s class Javadoc
 * for the full attribution rule.
 *
 * @author Mark Woon
 */
public final class VcfMetadata {

  // keep in sync with InternalUtils.RESTRICTED_API_EXPLANATION (this string is intentionally duplicated,
  // not shared, since sharing it would require making InternalUtils's copy public, defeating its own purpose)
  private static final String RESTRICTED_API_EXPLANATION =
      "Internal helper; public only because Java requires it for cross-package use within this library. Not "
          + "part of this library's supported public API and may change or be removed without notice.";

  private final String m_fileFormat;
  private final Map<String, IdDescriptionMetadata> m_alt;
  private final Map<String, InfoMetadata> m_info;
  private final Map<String, IdDescriptionMetadata> m_filter;
  private final Map<String, FormatMetadata> m_format;
  private final List<String> m_columns;
  private final ListMultimap<String, String> m_properties;
  private final Map<String, ContigMetadata> m_contig;
  private final Map<String, IdDescriptionMetadata> m_sample;
  private final Map<String, MetaMetadata> m_meta;
  private final List<PedigreeMetadata> m_pedigree;
  private final VcfContext m_context;

  /**
   * Memoized unmodifiable views over the backing collections above. Built on first access and reused
   * thereafter, so a repeated getter call in a hot path does not allocate a fresh wrapper each time. Safe to
   * cache because none of the backing fields is ever reassigned or mutated after construction.
   */
  private @Nullable Map<String, IdDescriptionMetadata> m_altView;
  private @Nullable Map<String, InfoMetadata> m_infoView;
  private @Nullable Map<String, IdDescriptionMetadata> m_filterView;
  private @Nullable Map<String, FormatMetadata> m_formatView;
  private @Nullable Map<String, ContigMetadata> m_contigView;
  private @Nullable Map<String, IdDescriptionMetadata> m_sampleView;
  private @Nullable Map<String, MetaMetadata> m_metaView;
  private @Nullable List<PedigreeMetadata> m_pedigreeView;
  private @Nullable ListMultimap<String, String> m_propertiesView;
  private @Nullable Set<String> m_propertyKeysView;
  private @Nullable List<String> m_assembliesView;
  private @Nullable List<String> m_pedigreeDatabasesView;


  /**
   * Package-private, not private: {@link Builder#build()} is the one production caller, but this is also the
   * metadata-layer counterpart of {@code VcfContext.newPositionCopyingTrustedFields} -- a trusted,
   * non-revalidating construction path this package's own tests use to synthesize the malformed containers
   * {@code VcfWriter}'s defensive checks are written against (a {@code null} declaration under a map key, a
   * {@code null} raw-property key, a duplicate PEDIGREE ID), none of which any public API route can produce now
   * that this class is immutable. Every collection argument is copied, but its <em>contents</em> are trusted
   * and not re-checked.
   */
  VcfMetadata(String fileFormat, @Nullable Map<String, IdDescriptionMetadata> alt,
      @Nullable Map<String, InfoMetadata> info, @Nullable Map<String, IdDescriptionMetadata> filter,
      @Nullable Map<String, FormatMetadata> format, @Nullable Map<String, ContigMetadata> contig,
      @Nullable Map<String, IdDescriptionMetadata> sample, @Nullable Map<String, MetaMetadata> meta,
      @Nullable List<PedigreeMetadata> pedigree,
      List<String> columns, @Nullable ListMultimap<String, String> properties, VcfContext context) {
    Preconditions.checkNotNull(fileFormat);
    Preconditions.checkNotNull(columns);
    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_fileFormat = fileFormat;
    // Fresh copies -- NOT List.copyOf/Map.copyOf, which would reject the null keys/values the trusted
    // construction path documented above deliberately allows through. The copy is what stops a built
    // VcfMetadata from sharing state with the Builder it came from (or with another VcfMetadata built from that
    // same Builder); each copy is then never mutated again, and is only ever exposed through the unmodifiable
    // views the getters below memoize.
    m_alt        = alt==null?        new LinkedHashMap<>()      : new LinkedHashMap<>(alt);
    m_info       = info==null?       new LinkedHashMap<>()      : new LinkedHashMap<>(info);
    m_filter     = filter==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(filter);
    m_format     = format==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(format);
    m_contig     = contig==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(contig);
    m_sample     = sample==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(sample);
    m_meta       = meta==null?       new LinkedHashMap<>()      : new LinkedHashMap<>(meta);
    m_pedigree   = pedigree==null?   new ArrayList<>()          : new ArrayList<>(pedigree);
    m_properties = properties==null? LinkedListMultimap.create() : LinkedListMultimap.create(properties);
    // m_columns alone is a true immutable copy rather than a mutable one behind an unmodifiable view: unlike
    // the eight fields above it has no getter exposing it at all (only derived read-only accessors like
    // getColumnIndex()), so there is no view to build and List.copyOf's own null rejection is welcome here.
    // Builder.setColumns() does not validate its argument (deferred to here, the single point where columns is
    // actually consumed, matching this class's other checkXxx-at-construction-time pattern); a null element would
    // otherwise reach List.copyOf(columns) below and throw a raw NullPointerException instead of a clean
    // VcfFormatException.
    for (String column : columns) {
      if (column == null) {
        throw new VcfFormatException("Column list contains a null entry");
      }
    }
    // A duplicate or empty sample name is a structural violation, not recoverable content -- AGENTS.md's
    // Parser Validation Policy lists "duplicate sample IDs" explicitly among the always-error cases, and
    // VcfParser.parseColumnInfo already enforces this identically (unconditionally, never warnOrThrow) at
    // header-parse time. This closes the same gap for direct construction (VcfMetadata.Builder.setColumns()
    // itself does not validate, matching this constructor's other checkXxx-at-construction-time pattern
    // noted above for the null check) -- found by the fifth re-verification round's finding #5. Samples
    // start at index 9, matching getNumSamples()/getSampleName()'s own indexing.
    Set<String> sampleNames = new HashSet<>();
    for (int i = 9; i < columns.size(); i++) {
      String sampleName = columns.get(i);
      if (sampleName.isEmpty()) {
        throw new VcfFormatException("Empty sample name in header (check for a trailing or duplicated tab)");
      }
      // A tab or line terminator in a sample name is structural, the same class of gap as the empty/duplicate
      // checks above and below: VcfWriter.buildHeaderLines (the default write path) appends
      // metadata.getSampleName(i) verbatim into the #CHROM header line with no check of its own -- a tab adds
      // a spurious column (silently inflating the header's own declared sample count beyond
      // getNumSamples()), and an LF/CR would end the header line early, corrupting or truncating every record
      // that follows. Confirmed live against installed bcftools 1.23.1: a metadata with one sample named
      // "S1\tS2" writes a #CHROM line bcftools reads as two samples ("S1", "S2"), silently, while
      // getNumSamples() still reports 1 -- writing that metadata's own data lines (with exactly 1 sample's
      // FORMAT data, matching what this library actually knows about) then makes bcftools fail outright to
      // parse them: "[E::vcf_parse_format_check7] Number of columns ... does not match the number of samples
      // (1 vs 2)", exit code 8 -- see BcftoolsDependencyTest#testTabCorruptedSampleNameBcftoolsSilentlyInflatesSampleCount
      // and BCFTOOLS_VERSION_DEPENDENCIES.md's matching entry. VcfWriter.validateMetadata() (validateBeforeWrite() mode only) already
      // rejects both the tab and the line terminator, but the default path has nothing else guarding against
      // either, and this constructor -- the single point where columns is actually consumed, per this
      // method's own null-check comment above -- had neither check either, so a caller-constructed sample
      // name reached the default write path completely unvalidated. Only reachable through direct
      // construction: VcfParser.parseColumnInfo splits the header line on tab, so a sample name produced by
      // parsing a real file can never itself contain a tab. A lone CR/LF is NOT excluded the same way --
      // readPhysicalLine() stopped treating a lone CR as a line separator, so it survives as ordinary
      // content within one physical line (see this same file's own parseColumnInfo, which is why it runs
      // its own unconditional InternalUtils.checkNoLineTerminator("sample name", ...) on every sample name
      // before ever calling setColumns(); that pre-existing, independent parse-time check, not any property
      // of readPhysicalLine() itself, is what actually keeps a real parsed file's sample name free of a line
      // terminator). Found by the Phase 3 writer-compliance review's Path A kickoff (finding #2); the
      // readPhysicalLine() misattribution above was itself found and corrected by that same review's first
      // re-verification round.
      InternalUtils.checkNoLineTerminator("sample name", sampleName);
      if (sampleName.contains("\t")) {
        throw new VcfFormatException("Sample name \"" + InternalUtils.truncateAndEscapeForMessage(sampleName) +
            "\" contains a tab");
      }
      if (!sampleNames.add(sampleName)) {
        throw new VcfFormatException("Duplicate sample name '" +
            InternalUtils.truncateAndEscapeForMessage(sampleName) + "' in header");
      }
    }
    m_columns    = List.copyOf(columns);
  }

  /** @return the context this metadata was built under; never {@code null}. */
  public VcfContext getContext() {
    return m_context;
  }

  /**
   * @return a new {@link Builder}, seeded with a copy of every field of this metadata (file format, every
   * declaration map, the PEDIGREE list, columns, and raw properties), bound to this metadata's own
   * {@link VcfContext} by reference (not a re-derived or newly constructed one -- see {@link VcfContext}'s
   * {@code newRecord}, which rejects a component built under a different context instance).
   * <p>
   * Re-adds each structured declaration (ALT/INFO/FILTER/FORMAT/contig/SAMPLE/META/PEDIGREE) through {@link Builder}'s
   * package-private object-taking overloads, carrying the existing declaration object over by reference rather
   * than reconstructing it from its raw properties -- see those overloads' own Javadoc. ALT is re-added through
   * {@code Builder}'s private {@code addAlt(IdDescriptionMetadata, boolean)} with content validation turned off:
   * every declaration here already passed {@link #validateAlt} once, and copying alone must not re-emit that
   * same diagnostic a second time.
   */
  public Builder toBuilder() {
    Builder builder = new Builder(m_context);
    builder.setFileFormat(m_fileFormat);
    for (IdDescriptionMetadata md : m_alt.values()) {
      builder.addAlt(md, false);
    }
    for (InfoMetadata md : m_info.values()) {
      builder.addInfo(md);
    }
    for (IdDescriptionMetadata md : m_filter.values()) {
      builder.addFilter(md);
    }
    for (FormatMetadata md : m_format.values()) {
      builder.addFormat(md);
    }
    for (ContigMetadata md : m_contig.values()) {
      builder.addContig(md);
    }
    for (IdDescriptionMetadata md : m_sample.values()) {
      builder.addSample(md);
    }
    for (MetaMetadata md : m_meta.values()) {
      builder.addMeta(md);
    }
    for (PedigreeMetadata md : m_pedigree) {
      builder.addPedigree(md);
    }
    builder.setColumns(m_columns);
    for (Map.Entry<String, String> entry : m_properties.entries()) {
      builder.addRawProperty(entry.getKey(), entry.getValue());
    }
    return builder;
  }

  public String getFileFormat() {
    return m_fileFormat;
  }

  /**
   * Rejects a {@code ##fileformat} value that names a different VCF version than {@code context} was built for:
   * a metadata object whose declaration contradicts its own context is internally inconsistent, and every
   * version-dependent decision made from that context would then be made for the wrong version.
   * <p>
   * Compared canonicalized ({@link VcfVersion#canonicalize}), not raw, so re-declaring {@code VCFv4.02} over a
   * {@code VCFv4.2} context is accepted -- those are the same version -- while the caller's own spelling is
   * still what gets stored and emitted.
   */
  private static void checkAgreesWithContext(String fileFormat, VcfContext context) {
    if (!VcfVersion.canonicalize(fileFormat).equals(VcfVersion.canonicalize(context.getDeclaredFileFormat()))) {
      // truncated and escaped, not embedded verbatim: both values already passed FILE_FORMAT_PATTERN's shape
      // check (setFileFormat above, VcfContext.lenient/strict for the other), which has no length bound --
      // found by the seventh re-verification round's fresh bug hunt, sibling gap to setFileFormat's own
      // already-fixed malformed-fileformat check a few lines above.
      throw new VcfFormatException("##fileformat " + InternalUtils.truncateAndEscapeForMessage(fileFormat) +
          " disagrees with this metadata's VcfContext, which declares " +
          InternalUtils.truncateAndEscapeForMessage(context.getDeclaredFileFormat()));
    }
  }

  /**
   * @return An unmodifiable view of the ALT declarations, keyed by each declaration's <em>unquoted</em>
   * {@code ID} -- which can no longer disagree over quoting <em>alone</em> with that same declaration's own
   * {@link IdMetadata#getId()}, since that getter is unquoted too: a declaration written {@code ID="X"} is
   * keyed {@code "X"} here and {@code getId()} on that entry also returns {@code "X"}. Only
   * {@link BaseMetadata#getPropertyRaw(String) getPropertyRaw(ID)} still reports the {@code "\"X\""} encoding.
   * (The two can still be made to diverge by mutating a stored declaration's {@code ID} in place through the
   * package-private {@code BaseMetadata.trustedMutableProperties()} bypass -- see
   * {@code VcfWriter.validateKeyedMetadataEntries}, which exists to detect exactly that.) See AGENTS.md's
   * "Unquoting before internal use" policy. A mutation attempt on the returned map throws
   * {@link UnsupportedOperationException}
   * -- use {@link #toBuilder()} to produce a changed {@code VcfMetadata}.
   */
  public Map<String, IdDescriptionMetadata> getAlts() {
    if (m_altView == null) {
      m_altView = Collections.unmodifiableMap(m_alt);
    }
    return m_altView;
  }

  /**
   * Gets the ALT metadata for the given ID.
   *
   * @param id the ID to lookup, will unwrap ID's enclosed in angle brackets (e.g. &lt;CN1&gt; will get converted to CN1)
   */
  @Nullable
  public IdDescriptionMetadata getAlt(String id) {
    // null-tolerant, matching this codebase's established "not present" contract for other getters taking a
    // lookup key (e.g. getInfo(String)/hasInfo(String)) -- m_alt.get(null) is already null-tolerant, but
    // id.startsWith(...) below is not
    if (id == null) {
      return null;
    }
    IdDescriptionMetadata md = m_alt.get(id);
    if (md == null && id.startsWith("<") && id.endsWith(">")) {
      md = m_alt.get(id.substring(1, id.length() - 1));
    }
    return md;
  }


  /**
   * @return An unmodifiable view of the INFO declarations, keyed by each declaration's unquoted ID. See {@link #getAlts()}.
   */
  public Map<String, InfoMetadata> getInfo() {
    if (m_infoView == null) {
      m_infoView = Collections.unmodifiableMap(m_info);
    }
    return m_infoView;
  }

  /**
   * @return An unmodifiable view of the FILTER declarations, keyed by each declaration's unquoted ID. See {@link #getAlts()}.
   */
  public Map<String, IdDescriptionMetadata> getFilters() {
    if (m_filterView == null) {
      m_filterView = Collections.unmodifiableMap(m_filter);
    }
    return m_filterView;
  }

  /**
   * @return An unmodifiable view of the FORMAT declarations, keyed by each declaration's unquoted ID. See {@link #getAlts()}.
   */
  public Map<String, FormatMetadata> getFormats() {
    if (m_formatView == null) {
      m_formatView = Collections.unmodifiableMap(m_format);
    }
    return m_formatView;
  }

  /**
   * @return An unmodifiable view of the contig declarations, keyed by each declaration's unquoted ID. See {@link #getAlts()}.
   */
  public Map<String, ContigMetadata> getContigs() {
    if (m_contigView == null) {
      m_contigView = Collections.unmodifiableMap(m_contig);
    }
    return m_contigView;
  }

  /**
   * @return An unmodifiable view of the PEDIGREE declarations, in declaration order. See {@link #getAlts()}.
   */
  public List<PedigreeMetadata> getPedigrees() {
    if (m_pedigreeView == null) {
      m_pedigreeView = Collections.unmodifiableList(m_pedigree);
    }
    return m_pedigreeView;
  }

  /**
   * @return An unmodifiable view of the SAMPLE declarations, keyed by each declaration's unquoted ID. See {@link #getAlts()}.
   */
  public Map<String, IdDescriptionMetadata> getSamples() {
    if (m_sampleView == null) {
      m_sampleView = Collections.unmodifiableMap(m_sample);
    }
    return m_sampleView;
  }

  /**
   * @return An unmodifiable view of the META declarations, keyed by each declaration's unquoted ID. See {@link #getAlts()}.
   */
  public Map<String, MetaMetadata> getMetas() {
    if (m_metaView == null) {
      m_metaView = Collections.unmodifiableMap(m_meta);
    }
    return m_metaView;
  }

  /**
   * @return An unmodifiable view of the URLs from the field(s) in the <em>assembly</em> metadata line(s). The
   * value {@code LinkedListMultimap.get(...)} returns is itself a live, mutable view into the backing multimap,
   * so wrapping it is what keeps this getter from handing out a mutation route into this metadata.
   */
  public List<String> getAssemblies() {
    // spec says: ##assembly=url (without angle brackets)
    if (m_assembliesView == null) {
      m_assembliesView = Collections.unmodifiableList(m_properties.get("assembly"));
    }
    return m_assembliesView;
  }

  /**
   * @return An unmodifiable view of the URLs from the field(s) in the <em>pedigreeDB</em> metadata line(s). See
   * {@link #getAssemblies()} for why this is wrapped.
   */
  public List<String> getPedigreeDatabases() {
    // spec says: ##pedigreeDB=url (no angle brackets)
    if (m_pedigreeDatabasesView == null) {
      m_pedigreeDatabasesView = Collections.unmodifiableList(m_properties.get("pedigreeDB"));
    }
    return m_pedigreeDatabasesView;
  }

  /**
   * Reports it if {@code value}'s ID is not a valid ALT structural variant code, or if it declares a Number/Type
   * extra property with a syntactically invalid value. Under a lenient {@code context} this warns and does not
   * throw, matching this project's warn-and-preserve policy for metadata declarations; under a strict one the
   * same conditions throw {@link org.pharmgkb.parser.vcf.VcfFormatException} instead.
   * <p>Public only because {@link org.pharmgkb.parser.vcf.VcfWriter} needs cross-package access; not part of
   * this library's supported public API.
   *
   * @param context supplies both the warnings destination and the warn-vs-throw decision: under
   * {@link VcfContext#strict}, every condition this method checks is thrown as
   * {@link org.pharmgkb.parser.vcf.VcfFormatException} instead of warned about
   * @hidden
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static void validateAlt(IdDescriptionMetadata value, VcfContext context) {
    // getPropertyUnquoted: every use of id/number/type below is a comparison or validation
    // (angle-bracket/colon scanning, AltStructuralVariant parsing, NUMBER_PATTERN, InfoType.valueOf), so each
    // must see the value's actual content rather than its header-syntax quoting -- see AGENTS.md's "Unquoting
    // before internal use" policy. A quoted ID (e.g. ID="DEL:ME") otherwise failed AltStructuralVariant
    // parsing purely because of its own surrounding quote characters, and a quoted Number/Type failed
    // NUMBER_PATTERN/InfoType.valueOf the same way.
    String id = value.getPropertyUnquoted(IdMetadata.ID);
    if (id != null) {
      // whitespace and commas are already checked generically by IdMetadata.init() for every ID-based metadata
      // type, including this one (a colonless ID with a comma is caught there, not here); angle brackets are
      // checked here instead since VCFv4.2.tex line 105's "or angle brackets" clause has only ever been
      // evidenced for ALT specifically, not extended to ##contig/##INFO/##FORMAT/##FILTER/##SAMPLE.
      if (id.chars().anyMatch(c -> c == '<' || c == '>')) {
        warnOrThrow(context, "ALT {} must not contain angle brackets", id);
      } else if (id.contains(":")) {
        // Only an ID containing ':' unambiguously invokes the structural-variant-code grammar (VCFv4.2.tex lines
        // 100-117: DEL/INS/DUP/INV/CNV and their colon-delimited subtypes, e.g. DEL:ME). A bare single-token ID
        // has other spec-sanctioned, non-SV uses this library must not reject: VCFv4.3.tex's IUPAC ambiguity
        // codes (##ALT=<ID=R,Description="IUPAC code R = A/G">) and the real-world copy-number-allele convention
        // (##ALT=<ID=CN0,...> through CN124, used by 1000 Genomes; see src/test/resources/cnv.vcf, itself a
        // VCFv4.1 file) that predates and coexists with every VCF version. There is no way to distinguish "CN0"
        // (legitimate) from "DEL1" (a typo of the reserved code DEL) by ID shape alone when there is no colon, so
        // the reserved-code hierarchy is only enforced when the ID's own colon-delimited structure signals that
        // is what was intended.
        try {
          new AltStructuralVariant(id);
        } catch (VcfFormatException e) {
          warnOrThrow(context, "ALT {} is not a valid structural variant code: {}", id, e.getMessage());
        }
      }
    }
    // ##ALT's only defined fields are ID/Description (VCFv4.2.tex line 103: ##ALT=<ID=type,Description="description">);
    // Number/Type are unrecognized extras, but test/vcf/4.2/failed/failed_meta_alt_001.vcf and
    // .../failed_meta_alt_002.vcf (##CauseOfFailure=Incorrect ALT Number/Type value) establish that, once
    // declared, they're still expected to be syntactically valid VCF Number/Type tokens.
    String number = value.getPropertyUnquoted(InfoMetadata.NUMBER);
    if (number != null && !InternalUtils.NUMBER_PATTERN.matcher(number).matches()) {
      warnOrThrow(context, "ALT {} has Number '{}' but it is not a valid VCF number", id, number);
    }
    String type = value.getPropertyUnquoted(InfoMetadata.TYPE);
    if (type != null) {
      try {
        InfoType.valueOf(type);
      } catch (IllegalArgumentException e) {
        warnOrThrow(context, "ALT {} has Type '{}' but it is not a recognized VCF type", id, type);
      }
    }
  }

  /**
   * @return Whether {@code value} has no meaningful content once trimmed.
   * <p>Public only because {@link org.pharmgkb.parser.vcf.VcfWriter} needs cross-package access; not part of
   * this library's supported public API.
   *
   * @hidden
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean isEmptyMetadataValue(String value) {
    return value.trim().isEmpty();
  }

  /**
   * @return Whether {@code value} (trimmed) is wrapped in angle brackets AND its content could not plausibly be
   * a {@code key=value,...} attribute list -- either because there's nothing between the brackets, or because
   * there's no {@code =} anywhere in it.
   * <p>A generic (raw, non-structured) metadata property's value was never meant to have this shape -- unlike
   * {@code ##ALT}/{@code ##INFO}/{@code ##FORMAT}/etc, which are {@code <key=value,...>} attribute lists by
   * design, a property like {@code assembly}/{@code pedigreeDB} is documented (VCFv4.2.tex/4.3.tex/4.4.tex's
   * Pedigree field format section) as a bracket-less bare value. htslib's own header-line parser doesn't know
   * that, though: it special-cases any generic property value shaped like this, attempting to parse it as an
   * attribute list regardless of which key it's attached to. Confirmed directly that bcftools 1.23.1
   * hard-errors ("[E::bcf_hdr_parse_line] Could not parse the header line") on both {@code ##assembly=<>}
   * (empty content) and {@code ##pedigreeDB=<http://foo.com>} (content with no {@code =} at all) -- not scoped
   * to either key specifically.
   * <p>A bracket-wrapped value that DOES contain at least one {@code =} (e.g. {@code "<ID=foo>"}, or
   * {@code "<ID=Assay,Number=.,Type=String,Values=[WholeGenome, Exome]>"} -- hts-specs'
   * {@code test/vcf/4.3/passed/passed_meta_meta.vcf} fixture's real, legitimate {@code ##META} line) is treated
   * as safe and left alone: confirmed directly that bcftools accepts and round-trips both. This deliberately
   * does not attempt to fully replicate htslib's actual attribute-list grammar (this library's own closest
   * equivalent, {@link org.pharmgkb.parser.vcf.InternalUtils#extractPropertiesFromLine}, can't do that correctly
   * either -- its comma-splitter doesn't understand a square-bracket-delimited sub-list like
   * {@code Values=[...]}'s own internal comma, so reusing it here would incorrectly reject the {@code ##META}
   * case above). The presence-of-{@code =} heuristic is deliberately conservative: it only rejects the two
   * shapes empirically confirmed to break bcftools, and accepts everything else, rather than risking a false
   * rejection of content this library can't fully validate the true grammar of anyway.
   * <p>Public only because {@link org.pharmgkb.parser.vcf.VcfWriter} needs cross-package access; not part of
   * this library's supported public API.
   *
   * @hidden
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean collidesWithAttributeListGrammar(String value) {
    String trimmed = value.trim();
    if (!(trimmed.startsWith("<") && trimmed.endsWith(">"))) {
      return false;
    }
    String inner = trimmed.substring(1, trimmed.length() - 1).trim();
    return inner.isEmpty() || !inner.contains("=");
  }

  /**
   * @return Whether {@code value} (trimmed) starts with {@code <} but does not end with a closing {@code >}
   * -- distinct from {@link #collidesWithAttributeListGrammar}, which requires BOTH a leading {@code <} and a
   * trailing {@code >} before it even looks at what's between them. A value like {@code "<abc"} (VCFv4.3.tex
   * line 109: an unstructured value "may not be empty and must not start with a {@code '<'} character") never reaches
   * that method's own check at all, since it fails the {@code endsWith(">")} half immediately -- so it was
   * never rejected by anything and parsed with zero warnings.
   * <p>Confirmed directly (bcftools 1.23.1) that this shape is genuinely malformed to bcftools too, in two
   * different ways depending on whether it happens to contain an {@code =}: {@code ##foo=<abc} (no {@code =})
   * hard-errors ("[E::bcf_hdr_parse_line] Could not parse the header line") and the whole declaration is
   * dropped, exit 0; {@code ##foo=<a=b} (has one) instead warns ("Incomplete header line, trying to proceed
   * anyway") and salvage-reconstructs a closed {@code ##foo=<a=b>} from it, exit 0 -- neither is the
   * genuinely fatal, read-aborting failure the unterminated-{@code [} case elsewhere in this file is (see
   * {@code InternalUtils}'s own "unterminated bracket" handling and {@code VCF_SPEC_AMBIGUITIES.md}'s entry
   * for that unconditional-throw case), so this one is warn-and-drop like every other malformed-generic-
   * property shape in this method, not an unconditional throw.
   * <p>Public only because {@link org.pharmgkb.parser.vcf.VcfWriter} needs cross-package access; not part of
   * this library's supported public API.
   *
   * @hidden
   */
  @RestrictedApi(explanation = RESTRICTED_API_EXPLANATION)
  public static boolean hasUnclosedAngleBracket(String value) {
    String trimmed = value.trim();
    return trimmed.startsWith("<") && !trimmed.endsWith(">");
  }

  /**
   * Guards every {@code addXxx}/{@code removeXxx} method (both {@code VcfMetadata}'s own instance methods and
   * {@code Builder}'s mirrors) against a null metadata object. Each parameter is typed non-@Nullable, but nothing
   * enforces that at runtime; without this, {@code value.getId()}/{@code value.getPropertiesRaw()} would throw a
   * raw {@link NullPointerException} instead of a clean {@link VcfFormatException}.
   */
  private static void requireNonNull(@Nullable Object value, String kind) {
    if (value == null) {
      throw new VcfFormatException(kind + " metadata is null");
    }
  }

  /**
   * Throws {@link VcfFormatException} with the formatted message if {@code warnings} is {@code null} (strict
   * mode); otherwise warns to it. Shared by {@code VcfMetadata}'s own instance methods and {@code Builder}'s
   * mirrors, each passing their own {@code m_context} field.
   * <p>
   * The throw branch runs {@code args} through {@link InternalUtils#sanitizeArgsForException} first, so a
   * {@code String}-typed arg (a raw property value, a raw ID) can't blow up the thrown message's length or
   * embed a raw control character -- see that method's own doc for why this is shared with {@link
   * BaseMetadata#warnOrThrow} and {@code InternalUtils}'s own private {@code warnOrThrow} implementations.
   */
  private static void warnOrThrow(VcfContext context, String message, Object... args) {
    if (!context.isLenient()) {
      throw new VcfFormatException(org.slf4j.helpers.MessageFormatter.arrayFormat(
          message, InternalUtils.sanitizeArgsForException(args)).getMessage());
    }
    context.getWarnings().warn(message, args);
  }

  /**
   * Rejects a declaration built under a different {@link VcfContext} than the one it is being added to.
   * <p>
   * Reference identity is the right check, not equality: one {@code VcfContext} instance per parse and per
   * write is the whole model, so two equal-valued contexts still represent two different operations with
   * different warnings sinks. Without this, {@link #getInfo()} and its siblings hand out an existing
   * declaration that a caller could push into a differently-configured builder or metadata object, silently
   * routing that declaration's later validation warnings to the wrong sink.
   */
  private static void checkSameContext(BaseMetadata md, VcfContext context, String kind) {
    if (md.getContext() != context) {
      // getPropertyRaw, deliberately: this message's job is to point the reader at a declaration as they wrote
      // it, so it shows the source spelling (quotes included) -- raw-use item 3, "source-form diagnostics", in
      // VALIDATION_PHILOSOPHY.md's value/encoding rule. Every other ID read in this class is unquoted.
      // truncateAndEscapeForMessage still applies on top of that raw read: the ID's own length is never
      // bounded, even though this branch is reachable only through the package-private addXxx(md)/
      // addPedigree(md)/replaceXxx(md) overloads (not directly by an external caller -- those constructors
      // are themselves package-private) -- found by the seventh re-verification round's fresh bug hunt;
      // corrected by the eighth round, which traced that this is internal defensive hardening, not an
      // externally-reachable gap the way the other five sites this same finding covered are.
      throw new VcfFormatException("Declaration " + kind + " " +
          InternalUtils.truncateAndEscapeForMessage(md.getPropertyRaw("ID")) + " was built under a " +
          "different VcfContext; warnings and version semantics would route to the wrong context");
    }
  }

  /**
   * Warns and returns {@code true} (does not throw) when {@code id} is already present in {@code map} -- the
   * caller must skip storing its value and keep the existing (first, or previously-added) entry. Shared by
   * {@code VcfMetadata}'s own instance {@code addXxx} methods and {@code VcfMetadata.Builder}'s {@code addXxx}
   * methods, so both a parsed file's construction-time declarations and any later programmatic mutation follow
   * the same duplicate-ID rule.
   * Every caller already guards against a null or blank {@code id} before calling this method (a missing/blank ID
   * means the declaration has no ID to dedupe on at all, and is dropped entirely before reaching here -- see each
   * {@code addXxx} method) -- {@code id} is therefore guaranteed non-null and non-blank on every call. VCFv4.3.tex
   * line 121 requires an ID for every structured metadata line; see DUPLICATE_METADATA_ID_HANDLING.md's "Missing or
   * empty ID" section for the full citation and the bcftools/vcf_validator comparison this is grounded in.
   * <p>
   * Neither VCFv4.2.tex nor VCFv4.3.tex states that a repeated declaration ID is an error for CONTIG/INFO/FORMAT/
   * FILTER/ALT/SAMPLE/META (contrast with duplicate #CHROM sample-column names, which remain a structural error --
   * see DUPLICATE_METADATA_ID_HANDLING.md for why). hts-specs' own test/vcf/{4.1,4.2}/passed/passed_meta_contig.vcf
   * fixture declares ##contig=&lt;ID=1&gt; twice and is expected to parse successfully. Confirmed valid by
   * vcf_validator 0.10.2 for all seven declaration types tested; bcftools 1.23.1 (htslib 1.23.1) keeps the first
   * declaration and silently drops each duplicate, with no warning, for all seven -- see
   * DUPLICATE_METADATA_ID_HANDLING.md.
   */
  private static boolean warnIfDuplicate(Map<String, ?> map, String id, String typeName, VcfContext context) {
    if (map.containsKey(id)) {
      warnOrThrow(context, "Duplicate ID \"{}\" for {} metadata declaration ignored; keeping the first declaration",
          id, typeName);
      return true;
    }
    return false;
  }

  /**
   * Returns a map from every property key to each of its values.
   * Call {@link ListMultimap#asMap} to get a Map&lt;String, Collection&lt;String&gt;&gt;.
   * @return <em>Contains every property except those contained in:</em>
   * <ul>
   *   <li>{@link #getAlts}</li>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getFilters}</li>
   *   <li>{@link #getFormats}</li>
   *   <li>{@link #getContigs}</li>
   *   <li>{@link #getPedigrees}</li>
   *   <li>{@link #getSamples}</li>
   *   <li>{@link #getMetas}</li>
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   * <p>
   * An unmodifiable view: a mutation attempt on the returned multimap throws
   * {@link UnsupportedOperationException}. Use {@link #toBuilder()} and {@link Builder#addRawProperty} to
   * produce a changed {@code VcfMetadata}.
   */
  public ListMultimap<String, String> getRawProperties() {
    if (m_propertiesView == null) {
      m_propertiesView = Multimaps.unmodifiableListMultimap(m_properties);
    }
    return m_propertiesView;
  }

  /**
   * Returns the values of a property, or an empty list if the property is not set (this delegates directly to
   * Guava's {@link ListMultimap#get}, which never returns {@code null}).
   * <strong>This method will return an empty list for a reserved property of the form
   * XX=&lt;ID=value,ID=value,...&gt;; {@code assembly} and {@code pedigreeDB} are still included.</strong>
   * <p>
   * Unmodifiable, like {@link #getRawProperties()} -- this reads through that same memoized view, whose own
   * {@code get(key)} already returns an unmodifiable list, rather than wrapping the backing multimap's live one
   * a second time.
   */
  public List<String> getRawValuesOfProperty(String propertyKey) {
    return getRawProperties().get(propertyKey);
  }

  /**
   * Returns a list of the properties defined.
   * <strong>Reserved properties of the form XX=&lt;ID=value,ID=value,...&gt; are excluded, though {@code assembly}
   * and {@code pedigreeDB} are still included.</strong>
   * @return <em>Contains every property except those contained in:</em>
   * <ul>
   *   <li>{@link #getAlts}</li>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getFilters}</li>
   *   <li>{@link #getFormats}</li>
   *   <li>{@link #getContigs}</li>
   *   <li>{@link #getPedigrees}</li>
   *   <li>{@link #getSamples}</li>
   *   <li>{@link #getMetas}</li>
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   * <p>
   * Iterates in the order each key was first added, not alphabetically. Like {@link #getRawProperties()}, a
   * mutation attempt directly on the returned set (e.g. {@link Set#remove}) throws
   * {@link UnsupportedOperationException}.
   */
  public Set<String> getRawPropertyKeys() {
    if (m_propertyKeysView == null) {
      m_propertyKeysView = Collections.unmodifiableSet(m_properties.keySet());
    }
    return m_propertyKeysView;
  }

  /**
   * @return the column's index, or {@code -1} if {@code column} is {@code null} or not a column
   */
  public int getColumnIndex(@Nullable String column) {
    return column == null ? -1 : m_columns.indexOf(column);
  }

  /**
   * Sample numbering starts at 0.
   *
   * @return the sample's index, or {@code -1} if {@code sampleId} is {@code null} or not a sample column
   */
  public int getSampleIndex(@Nullable String sampleId) {
    int idx = sampleId == null ? -1 : m_columns.indexOf(sampleId);
    return idx < 9 ? -1 : idx - 9;
  }

  /**
   * Gets the number of samples in the VCF file.
   */
  public int getNumSamples() {
    if (m_columns.size() < 9) {
      return 0; // necessary because if we have no samples, we'll be missing FORMAT
    }
    return m_columns.size() - 9;
  }

  /**
   * Gets the number of columns in the VCF file.
   */
  public int getNumColumns() {
    return m_columns.size();
  }


  /**
   * Gets the sample name (column name).
   *
   * @param idx sample index, first sample is at index 0
   *
   * @throws IndexOutOfBoundsException If the sample doesn't exist
   */
  public String getSampleName(int idx) {
    // explicit upper bound too, not just the negative guard: a positive out-of-range idx would otherwise fall
    // through to m_columns.get(9 + idx) and report the shifted COLUMN index in its own message (e.g. a
    // 1-sample file's getSampleName(1) reporting "Index 10"), confusing for a caller who passed sample index 1.
    if (idx < 0 || idx >= getNumSamples()) {
      throw new IndexOutOfBoundsException("Sample index " + idx + " is out of bounds");
    }
    return m_columns.get(9 + idx);
  }



  public static class Builder {
    private String m_fileFormat;
    private final Map<String, IdDescriptionMetadata> m_alt = new LinkedHashMap<>();
    private final Map<String, InfoMetadata> m_info = new LinkedHashMap<>();
    private final Map<String, IdDescriptionMetadata> m_filter = new LinkedHashMap<>();
    private final Map<String, FormatMetadata> m_format = new LinkedHashMap<>();
    private final Map<String, ContigMetadata> m_contig = new LinkedHashMap<>();
    private final Map<String, IdDescriptionMetadata> m_sample = new LinkedHashMap<>();
    private final Map<String, MetaMetadata> m_meta = new LinkedHashMap<>();
    private final List<PedigreeMetadata> m_pedigree = new ArrayList<>();
    /**
     * The {@code ID}s already added to {@link #m_pedigree}, maintained incrementally by {@link #addPedigree}.
     * This is what keeps a long run of {@code ##PEDIGREE} lines amortized O(1) per line instead of the O(n)
     * rescan it replaced -- which made header parsing an O(n^2) CPU-exhaustion vector (a ~30KB crafted file
     * with 30,000 {@code ##PEDIGREE} lines took 28.5s to parse before this cache; pinned by
     * {@code VcfMetadataTest.testBuilderAddPedigreeDoesNotDegradeQuadraticallyWithManyUniqueIds}).
     * <p>
     * A plain {@link Set} with no staleness signal is correct here because nothing can desync it:
     * {@code Builder} exposes no getter for {@link #m_pedigree} and {@link #addPedigree} is its only mutator,
     * and {@link PedigreeMetadata} rejects every post-construction property change (including the
     * package-private inserters), so a stored entry's {@code ID} cannot change either. This replaced a
     * {@code modCount}-guarded, rebuild-on-demand cache that existed to survive direct mutation of a live
     * pedigree list; freezing the metadata layer removed every route to that, leaving the invalidation branch
     * dead and -- once the three tests that drove it went with the mutation routes they used -- untested.
     */
    private final Set<String> m_pedigreeIds = new HashSet<>();
    private List<String> m_columns = new ArrayList<>();
    private final ListMultimap<String, String> m_properties = LinkedListMultimap.create();
    private final VcfContext m_context;

    /**
     * @param context the one context for this parse or write. It supplies the warnings destination for every
     * check this builder and the {@link VcfMetadata} it builds perform, and decides whether each of those
     * checks warns and recovers ({@link VcfContext#lenient}) or throws ({@link VcfContext#strict}). It is also
     * what every declaration built through this builder is bound to.
     */
    public Builder(VcfContext context) {
      m_context = Preconditions.checkNotNull(context, "context must not be null");
      // the context already carries the declared ##fileformat, so a builder is never in a no-fileformat state;
      // setFileFormat below exists to let a caller keep its own spelling of the same version.
      m_fileFormat = context.getDeclaredFileFormat();
    }

    /**
     * Sets the VCF version string. Must name the same version this builder's {@link VcfContext} was built for
     * (compared canonicalized, so {@code VCFv4.02} over a {@code VCFv4.2} context is accepted and keeps its own
     * spelling).
     *
     * @param fileFormat Ex: "VCFv4.2"
     */
    public Builder setFileFormat(String fileFormat) {
      // fileFormat is typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting
      // FILE_FORMAT_PATTERN.matcher(fileFormat) below throw a raw NullPointerException
      if (fileFormat == null) {
        throw new VcfFormatException("VCF file format is null");
      }
      if (!InternalUtils.FILE_FORMAT_PATTERN.matcher(fileFormat).matches()) {
        // truncated and escaped, not embedded verbatim -- the identical message VcfParser.java's own
        // fileformat check was fixed for (third re-verification round); this public setter takes arbitrary
        // caller content directly, with no length bound this method can rely on either. Found still missing
        // here by the Phase 2 review's fourth re-verification round.
        throw new VcfFormatException("Not a VCF file: fileformat is " +
            InternalUtils.truncateAndEscapeForMessage(fileFormat));
      }
      checkAgreesWithContext(fileFormat, m_context);
      m_fileFormat = fileFormat;
      return this;
    }

    /*
     * Raw-value declaration overloads. These, not the object-taking siblings below, are the supported public
     * way to declare metadata: the builder constructs each declaration from its own VcfContext, so a
     * declaration can never arrive already bound to a different one. The Map-based overloads (the parser's own
     * shape) deliberately take no VcfWarnings -- the context supplies the sink, and BaseMetadata.warnOrThrow
     * decides warn-vs-throw from that same context, so equivalent invalid content behaves identically through
     * both the typed and the Map shape.
     */

    public Builder addAlt(@Nullable String id, @Nullable String description) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "ALT", id, description);
      // checked on the CONSTRUCTED declaration, not on the arguments -- see addAlt(Map, boolean)'s comment below
      InternalUtils.checkMetadataPropertyEncoding("ALT", md.getPropertiesRaw());
      return addAlt(md);
    }

    public Builder addAlt(Map<String, String> properties) {
      return addAlt(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addAltTrusted(Map<String, String> properties) {
      return addAlt(properties, false);
    }

    private Builder addAlt(Map<String, String> properties, boolean validateContent) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "ALT", properties);
      if (validateContent) {
        // checked on the CONSTRUCTED declaration, not on `properties`: the typed routes above transform their
        // arguments first (putAndQuoteProperty quotes Description), so checking the arguments would reject
        // values the constructor was about to make safe
        InternalUtils.checkMetadataPropertyEncoding("ALT", md.getPropertiesRaw());
      }
      return addAlt(md);
    }

    public Builder addInfo(@Nullable String id, @Nullable String description, @Nullable InfoType type,
        @Nullable String number, @Nullable String source, @Nullable String version) {
      InfoMetadata md = new InfoMetadata(m_context, id, description, type, number, source, version);
      InternalUtils.checkMetadataPropertyEncoding("INFO", md.getPropertiesRaw());
      return addInfo(md);
    }

    public Builder addInfo(Map<String, String> properties) {
      return addInfo(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addInfoTrusted(Map<String, String> properties) {
      return addInfo(properties, false);
    }

    private Builder addInfo(Map<String, String> properties, boolean validateContent) {
      InfoMetadata md = new InfoMetadata(m_context, properties);
      if (validateContent) {
        // checked on the CONSTRUCTED declaration, not on `properties`: the typed routes above transform their
        // arguments first (putAndQuoteProperty quotes Description/Source/Version), so checking the arguments
        // would reject values the constructor was about to make safe
        InternalUtils.checkMetadataPropertyEncoding("INFO", md.getPropertiesRaw());
      }
      return addInfo(md);
    }

    public Builder addFilter(@Nullable String id, @Nullable String description) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "FILTER", id, description);
      InternalUtils.checkMetadataPropertyEncoding("FILTER", md.getPropertiesRaw());
      return addFilter(md);
    }

    public Builder addFilter(Map<String, String> properties) {
      return addFilter(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addFilterTrusted(Map<String, String> properties) {
      return addFilter(properties, false);
    }

    private Builder addFilter(Map<String, String> properties, boolean validateContent) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "FILTER", properties);
      if (validateContent) {
        // checked on the CONSTRUCTED declaration, not on `properties`: the typed routes above transform their
        // arguments first (putAndQuoteProperty quotes Description), so checking the arguments would reject
        // values the constructor was about to make safe
        InternalUtils.checkMetadataPropertyEncoding("FILTER", md.getPropertiesRaw());
      }
      return addFilter(md);
    }

    public Builder addFormat(@Nullable String id, @Nullable String description, @Nullable String number,
        @Nullable FormatType type) {
      FormatMetadata md = new FormatMetadata(m_context, id, description, number, type);
      InternalUtils.checkMetadataPropertyEncoding("FORMAT", md.getPropertiesRaw());
      return addFormat(md);
    }

    public Builder addFormat(Map<String, String> properties) {
      return addFormat(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addFormatTrusted(Map<String, String> properties) {
      return addFormat(properties, false);
    }

    private Builder addFormat(Map<String, String> properties, boolean validateContent) {
      FormatMetadata md = new FormatMetadata(m_context, properties);
      if (validateContent) {
        // checked on the CONSTRUCTED declaration, not on `properties`: the typed routes above transform their
        // arguments first (putAndQuoteProperty quotes Description), so checking the arguments would reject
        // values the constructor was about to make safe
        InternalUtils.checkMetadataPropertyEncoding("FORMAT", md.getPropertiesRaw());
      }
      return addFormat(md);
    }

    public Builder addContig(@Nullable String id, @Nullable Long length, @Nullable String assembly,
        @Nullable String md5, @Nullable String species, @Nullable String taxonomy, @Nullable String url) {
      ContigMetadata md = new ContigMetadata(m_context, id, length, assembly, md5, species, taxonomy, url);
      InternalUtils.checkMetadataPropertyEncoding("contig", md.getPropertiesRaw());
      return addContig(md);
    }

    public Builder addContig(Map<String, String> properties) {
      return addContig(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addContigTrusted(Map<String, String> properties) {
      return addContig(properties, false);
    }

    private Builder addContig(Map<String, String> properties, boolean validateContent) {
      ContigMetadata md = new ContigMetadata(m_context, properties);
      if (validateContent) {
        // checked on the CONSTRUCTED declaration, not on `properties`: the typed routes above transform their
        // arguments first (putAndQuoteProperty quotes species), so checking the arguments would reject values
        // the constructor was about to make safe
        InternalUtils.checkMetadataPropertyEncoding("contig", md.getPropertiesRaw());
      }
      return addContig(md);
    }

    public Builder addSample(@Nullable String id, @Nullable String description) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "SAMPLE", id, description);
      InternalUtils.checkMetadataPropertyEncoding("SAMPLE", md.getPropertiesRaw());
      return addSample(md);
    }

    public Builder addSample(Map<String, String> properties) {
      return addSample(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addSampleTrusted(Map<String, String> properties) {
      return addSample(properties, false);
    }

    private Builder addSample(Map<String, String> properties, boolean validateContent) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "SAMPLE", properties);
      if (validateContent) {
        // checked on the CONSTRUCTED declaration, not on `properties`: the typed routes above transform their
        // arguments first (putAndQuoteProperty quotes Description), so checking the arguments would reject
        // values the constructor was about to make safe
        InternalUtils.checkMetadataPropertyEncoding("SAMPLE", md.getPropertiesRaw());
      }
      return addSample(md);
    }

    public Builder addMeta(Map<String, String> properties) {
      return addMeta(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addMetaTrusted(Map<String, String> properties) {
      return addMeta(properties, false);
    }

    private Builder addMeta(Map<String, String> properties, boolean validateContent) {
      MetaMetadata md = new MetaMetadata(m_context, properties);
      if (validateContent) {
        // META has no auto-quoted field (Values stays opaque, per MetaMetadata's own Javadoc), but the rule
        // stays consistent with the other six keyed types
        InternalUtils.checkMetadataPropertyEncoding("META", md.getPropertiesRaw());
      }
      return addMeta(md);
    }

    public Builder addPedigree(Map<String, String> properties) {
      return addPedigree(properties, true);
    }

    /**
     * Package-private, for {@code VcfParser} only. Skips the structural-delimiter check because the parser
     * deliberately warns-and-recovers on an unescaped quote and stores the recovered value -- checking its
     * route would reject files this library accepts today. See STRUCTURAL_DELIMITER_HANDLING.md.
     */
    Builder addPedigreeTrusted(Map<String, String> properties) {
      return addPedigree(properties, false);
    }

    private Builder addPedigree(Map<String, String> properties, boolean validateContent) {
      PedigreeMetadata md = new PedigreeMetadata(m_context, properties);
      if (validateContent) {
        // checked on the CONSTRUCTED declaration, not on `properties`: PEDIGREE has no fixed schema, so unlike
        // the other six kinds there is no auto-quoted field to worry about here, but the rule stays consistent
        InternalUtils.checkMetadataPropertyEncoding("PEDIGREE", md.getPropertiesRaw());
      }
      return addPedigree(md);
    }

    /*
     * Raw-value replace overloads, mirroring the raw-value add overloads' typed/Map pairing per type above so
     * leniency keeps coming from the context rather than the overload shape. Unlike addXxx, replaceXxx requires a
     * declaration already stored under the same ID -- see requirePresentForReplace's own comment below for why a
     * missing target throws unconditionally, in both a lenient and a strict context. There is no replacePedigree:
     * PEDIGREE is List-backed, not Map-backed (DUPLICATE_METADATA_ID_HANDLING.md's "##PEDIGREE: a conditional
     * case"), so it has no Map to replace a single entry into.
     */

    public Builder replaceAlt(@Nullable String id, @Nullable String description) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "ALT", id, description);
      InternalUtils.checkMetadataPropertyEncoding("ALT", md.getPropertiesRaw());
      return replaceAlt(md);
    }

    public Builder replaceAlt(Map<String, String> properties) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "ALT", properties);
      InternalUtils.checkMetadataPropertyEncoding("ALT", md.getPropertiesRaw());
      return replaceAlt(md);
    }

    public Builder replaceInfo(@Nullable String id, @Nullable String description, @Nullable InfoType type,
        @Nullable String number, @Nullable String source, @Nullable String version) {
      InfoMetadata md = new InfoMetadata(m_context, id, description, type, number, source, version);
      InternalUtils.checkMetadataPropertyEncoding("INFO", md.getPropertiesRaw());
      return replaceInfo(md);
    }

    public Builder replaceInfo(Map<String, String> properties) {
      InfoMetadata md = new InfoMetadata(m_context, properties);
      InternalUtils.checkMetadataPropertyEncoding("INFO", md.getPropertiesRaw());
      return replaceInfo(md);
    }

    public Builder replaceFilter(@Nullable String id, @Nullable String description) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "FILTER", id, description);
      InternalUtils.checkMetadataPropertyEncoding("FILTER", md.getPropertiesRaw());
      return replaceFilter(md);
    }

    public Builder replaceFilter(Map<String, String> properties) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "FILTER", properties);
      InternalUtils.checkMetadataPropertyEncoding("FILTER", md.getPropertiesRaw());
      return replaceFilter(md);
    }

    public Builder replaceFormat(@Nullable String id, @Nullable String description, @Nullable String number,
        @Nullable FormatType type) {
      FormatMetadata md = new FormatMetadata(m_context, id, description, number, type);
      InternalUtils.checkMetadataPropertyEncoding("FORMAT", md.getPropertiesRaw());
      return replaceFormat(md);
    }

    public Builder replaceFormat(Map<String, String> properties) {
      FormatMetadata md = new FormatMetadata(m_context, properties);
      InternalUtils.checkMetadataPropertyEncoding("FORMAT", md.getPropertiesRaw());
      return replaceFormat(md);
    }

    public Builder replaceContig(@Nullable String id, @Nullable Long length, @Nullable String assembly,
        @Nullable String md5, @Nullable String species, @Nullable String taxonomy, @Nullable String url) {
      ContigMetadata md = new ContigMetadata(m_context, id, length, assembly, md5, species, taxonomy, url);
      InternalUtils.checkMetadataPropertyEncoding("contig", md.getPropertiesRaw());
      return replaceContig(md);
    }

    public Builder replaceContig(Map<String, String> properties) {
      ContigMetadata md = new ContigMetadata(m_context, properties);
      InternalUtils.checkMetadataPropertyEncoding("contig", md.getPropertiesRaw());
      return replaceContig(md);
    }

    public Builder replaceSample(@Nullable String id, @Nullable String description) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "SAMPLE", id, description);
      InternalUtils.checkMetadataPropertyEncoding("SAMPLE", md.getPropertiesRaw());
      return replaceSample(md);
    }

    public Builder replaceSample(Map<String, String> properties) {
      IdDescriptionMetadata md = new IdDescriptionMetadata(m_context, "SAMPLE", properties);
      InternalUtils.checkMetadataPropertyEncoding("SAMPLE", md.getPropertiesRaw());
      return replaceSample(md);
    }

    public Builder replaceMeta(Map<String, String> properties) {
      MetaMetadata md = new MetaMetadata(m_context, properties);
      InternalUtils.checkMetadataPropertyEncoding("META", md.getPropertiesRaw());
      return replaceMeta(md);
    }

    /**
     * Adds an already-built declaration, validating its content via {@link #validateAlt}. Package-private: this
     * shape is retained only for internal same-context callers -- the public raw-value overloads above, which
     * always construct genuinely new, not-yet-validated content. It is not public because
     * {@link VcfMetadata#getAlt}-style getters hand out existing declarations, so a public overload would let a
     * caller move one built under another context into this builder -- see {@link #checkSameContext}.
     */
    Builder addAlt(IdDescriptionMetadata md) {
      return addAlt(md, true);
    }

    /**
     * @param validateContent {@code false} only for {@code VcfMetadata.toBuilder()}'s re-add of a declaration
     * that already passed {@link #validateAlt} once, when it was first added to the source metadata this builder
     * was seeded from -- re-running it here on unchanged content would re-emit the identical diagnostic a second
     * time purely from copying. {@code true} for every other caller (the overload above, and through it the
     * public raw-value overloads), which always add genuinely new, not-yet-validated content.
     */
    private Builder addAlt(IdDescriptionMetadata md, boolean validateContent) {
      return addKeyedDeclaration(m_alt, md, "ALT", validateContent ? m -> validateAlt(m, m_context) : null);
    }

    /**
     * Shared add-declaration logic for the seven {@code addXxx(md)} overloads below: null/context checks, ID
     * extraction, blank-ID drop, duplicate-ID drop, and map storage -- these differ only in the map, the
     * declaration's own type, the type-name string used in messages, and (ALT only) an extra
     * post-construction content check. {@code validateContent}, if non-null, runs only when the declaration is
     * actually going to be stored (never for a dropped blank/duplicate ID) -- ALT is the only caller that
     * supplies one, since it is the only keyed type with its own extra content check ({@link #validateAlt}).
     */
    private <T extends BaseMetadata> Builder addKeyedDeclaration(Map<String, T> map, T md, String typeName,
        @Nullable Consumer<T> validateContent) {
      requireNonNull(md, typeName);
      checkSameContext(md, m_context, typeName);
      // getPropertyUnquoted(ID): the blank check, the duplicate check, and the map storage key itself must all
      // see the ID's actual content rather than its header-syntax quoting -- see AGENTS.md's "Unquoting before
      // internal use" policy. Without this, a quoted-empty (ID="") or quoted-whitespace (ID="   ") ID
      // was neither blank nor a match for an existing unquoted key, and ID="A" never deduped against ID=A --
      // see the retired WONT_FIX.md entry and DUPLICATE_METADATA_ID_HANDLING.md's "Missing or empty ID" section.
      String id = md.getPropertyUnquoted(IdMetadata.ID);
      if (id == null || id.trim().isEmpty()) {
        warnOrThrow(m_context, "{} metadata declaration has no ID and cannot be stored; dropped: {}", typeName,
            md.getPropertiesRaw());
        return this;
      }
      if (warnIfDuplicate(map, id, typeName, m_context)) {
        return this;
      }
      if (validateContent != null) {
        validateContent.accept(md);
      }
      map.put(id, md);
      return this;
    }

    /**
     * Adds an already-built declaration. Package-private: this shape is retained only for internal
     * same-context callers (notably {@code VcfMetadata.toBuilder()}). It is not public because
     * {@link VcfMetadata#getInfo}-style getters hand out existing declarations, so a public
     * overload would let a caller move one built under another context into this builder -- see
     * {@link #checkSameContext}.
     */
    Builder addInfo(InfoMetadata md) {
      return addKeyedDeclaration(m_info, md, "INFO", null);
    }

    /**
     * Adds an already-built declaration. Package-private: this shape is retained only for internal
     * same-context callers (notably {@code VcfMetadata.toBuilder()}). It is not public because
     * {@link VcfMetadata#getFilter}-style getters hand out existing declarations, so a public
     * overload would let a caller move one built under another context into this builder -- see
     * {@link #checkSameContext}.
     */
    Builder addFilter(IdDescriptionMetadata md) {
      return addKeyedDeclaration(m_filter, md, "FILTER", null);
    }

    /**
     * Adds an already-built declaration. Package-private: this shape is retained only for internal
     * same-context callers (notably {@code VcfMetadata.toBuilder()}). It is not public because
     * {@link VcfMetadata#getFormat}-style getters hand out existing declarations, so a public
     * overload would let a caller move one built under another context into this builder -- see
     * {@link #checkSameContext}.
     */
    Builder addFormat(FormatMetadata md) {
      return addKeyedDeclaration(m_format, md, "FORMAT", null);
    }

    /**
     * Adds an already-built declaration. Package-private: this shape is retained only for internal
     * same-context callers (notably {@code VcfMetadata.toBuilder()}). It is not public because
     * {@link VcfMetadata#getContig}-style getters hand out existing declarations, so a public
     * overload would let a caller move one built under another context into this builder -- see
     * {@link #checkSameContext}.
     */
    Builder addContig(ContigMetadata md) {
      return addKeyedDeclaration(m_contig, md, "CONTIG", null);
    }

    /**
     * Adds an already-built declaration. Package-private: this shape is retained only for internal
     * same-context callers (notably {@code VcfMetadata.toBuilder()}). It is not public because
     * {@link VcfMetadata#getSample}-style getters hand out existing declarations, so a public
     * overload would let a caller move one built under another context into this builder -- see
     * {@link #checkSameContext}.
     */
    Builder addSample(IdDescriptionMetadata md) {
      return addKeyedDeclaration(m_sample, md, "SAMPLE", null);
    }

    /**
     * Adds an already-built declaration. Package-private: this shape is retained only for internal
     * same-context callers (notably {@code VcfMetadata.toBuilder()}). It is not public because
     * {@link VcfMetadata#getMetas}-style getters hand out existing declarations, so a public
     * overload would let a caller move one built under another context into this builder -- see
     * {@link #checkSameContext}.
     */
    Builder addMeta(MetaMetadata md) {
      return addKeyedDeclaration(m_meta, md, "META", null);
    }

    /**
     * Adds {@code md} to the list of PEDIGREE metadata. If {@code md} declares an {@code ID} property that
     * duplicates an earlier PEDIGREE entry's {@code ID} once both are unquoted ({@code ID="A"} duplicates
     * {@code ID=A} -- quoting is an encoding, not part of the identifier), warns and keeps the first entry
     * instead of storing {@code md} -- matching the seven keyed types' warn-and-keep-first rule. A PEDIGREE entry
     * with no {@code ID} property (VCFv4.2.tex line 152's own grammar, e.g.
     * {@code Name_0=G0-ID,...,Name_N=GN-ID}, has no mandatory ID attribute) is always added under VCFv4.2:
     * confirmed bcftools 1.23.1 keeps both of two ID-less duplicate
     * PEDIGREE lines, since there is no key to dedupe on -- see DUPLICATE_METADATA_ID_HANDLING.md. Under VCFv4.3+
     * (VCFv4.3.tex line 121, "All structured lines require an ID"), a missing/blank {@code ID} instead warns (or
     * throws, under a strict context) but is still kept, since a {@code List} has no key to fail to store against.
     * <p>
     * Dedupes against {@link #m_pedigreeIds}, an incrementally-maintained {@link Set} -- see its own Javadoc
     * for why no staleness signal is needed.
     */
    Builder addPedigree(PedigreeMetadata md) {
      requireNonNull(md, "PEDIGREE");
      checkSameContext(md, m_context, "PEDIGREE");
      // getPropertyUnquoted, not getPropertyRaw: the blank check and the dedupe set below are both
      // comparisons/internal use, so each must see the ID's actual content rather than its header-syntax
      // quoting -- see AGENTS.md's "Unquoting before internal use" policy. Without this, a quoted-empty
      // (ID="") or quoted-whitespace (ID="   ") ID was never blank once trimmed (its literal quote/space
      // characters survived), and ID="A" never deduped against ID=A -- see the retired WONT_FIX.md entry and
      // DUPLICATE_METADATA_ID_HANDLING.md.
      String id = md.getPropertyUnquoted("ID");
      // VCFv4.3.tex line 121, "All structured lines require an ID which must be unique within their type" --
      // VCFv4.2's own ##PEDIGREE template (VCFv4.2.tex line 152, ##PEDIGREE=<Name_0=G0-ID,...,Name_N=GN-ID>)
      // has no ID attribute at all, so this is gated on the declared version, not applied everywhere.
      // Blank counts as absent, matching IdMetadata.init()'s trim()-based test and the seven keyed types'
      // inline check -- but unlike those seven, the declaration is KEPT: they drop because a Map has no key
      // for them ("cannot be stored"), and ##PEDIGREE is List-backed. See DUPLICATE_METADATA_ID_HANDLING.md.
      // Checked BEFORE the duplicate branch, and deliberately not added to m_pedigreeIds, so two blank-ID
      // declarations each report their own missing ID instead of colliding as a spurious Duplicate ID "".
      boolean idBlankWhenTrimmed = id != null && id.trim().isEmpty();
      if ((id == null || idBlankWhenTrimmed) && m_context.getVersion().isAtLeast(VcfVersion.V4_3)) {
        warnOrThrow(m_context, "PEDIGREE metadata declaration has no ID, which VCF {} requires; keeping it: {}",
            m_context.getVersion().getId(), md.getPropertiesRaw());
        m_pedigree.add(md);
        return this;
      }
      // Every other one of the seven ID-bearing declaration types warns on whitespace in ID via IdMetadata's
      // shared init() check (VCF_SPEC_AMBIGUITIES.md's "Whitespace in ##FILTER/##SAMPLE declaration IDs"
      // entry -- treated as a bcftools coverage gap, not a considered position, so this warns uniformly
      // across every structured metadata ID type). PedigreeMetadata extends BaseMetadata directly, not
      // IdMetadata (pre-4.3 it may legitimately have no ID at all), so it never inherited that check --
      // unconditional, not gated on isAtLeast(V4_3) like the missing-ID check above, matching the other six
      // types' own unconditional treatment: a present ID's whitespace is invalid at every version, even
      // though whether an ID is REQUIRED at all is version-dependent.
      //
      // Guarded on !idBlankWhenTrimmed (not just id != null, as originally written): a pre-4.3 whitespace-only
      // ID (e.g. "   ") is blank-when-trimmed but doesn't take the branch above (that branch requires
      // isAtLeast(V4_3)), so it used to fall through here unconditionally and get misdiagnosed as "must not
      // contain whitespace" -- misleading, since trimming it would leave nothing, not a valid ID, matching
      // IdMetadata.init()'s own reasoning for ordering its equivalent blank-vs-whitespace checks as a mutually
      // exclusive else-if (see that method's own comment). This guard suppresses only that one message for
      // that one shape; the duplicate-check/dedupe-participation logic below this block is untouched, so a
      // pre-4.3 blank or whitespace-only ID still participates in m_pedigreeIds exactly as before (see
      // DUPLICATE_METADATA_ID_HANDLING.md and VcfMetadataTest's testAddPedigree*Under42IsSilentAndStillDedupes
      // tests) -- unlike a null ID, which was never added to m_pedigreeIds and still isn't.
      if (id != null && !idBlankWhenTrimmed && InternalUtils.containsWhitespace(id)) {
        warnOrThrow(m_context, "PEDIGREE metadata ID \"{}\" must not contain whitespace", id);
      }
      // Set.add reports whether the ID was new, so this is one lookup rather than a contains-then-add pair. On
      // the duplicate branch the set is unchanged (add returned false because the ID was already there), so it
      // stays consistent with m_pedigree whether warnOrThrow warns or throws.
      if (id != null && !m_pedigreeIds.add(id)) {
        warnOrThrow(m_context, "Duplicate ID \"{}\" for PEDIGREE metadata declaration ignored; keeping the first " +
            "declaration", id);
        return this;
      }
      m_pedigree.add(md);
      return this;
    }

    /**
     * Confirms a declaration with {@code id} is already stored in {@code map} before a {@code replaceXxx} call is
     * allowed to overwrite it -- otherwise throws {@link VcfFormatException}, in both a lenient and a strict
     * context. This is deliberately not context-dependent, unlike {@link #warnIfDuplicate}: {@code replaceXxx} is
     * the typo-resistant alternative to the retired remove-then-add idiom (see
     * DUPLICATE_METADATA_ID_HANDLING.md's "`replaceXxx`: the supported way to overwrite a declaration" section),
     * so a missing target is a programming error in either mode, not a content violation -- appending on a
     * mistyped ID would reintroduce the very failure this method exists to prevent, and warning-and-continuing
     * would leave the transformed {@code VcfMetadata} silently unchanged. Adding a new declaration is
     * {@code addXxx}'s job.
     * <p>
     * {@code id} may be {@code null} (the unquoted {@code ID}, per this class's {@code addXxx}/{@code replaceXxx}
     * convention -- see {@link #addAlt(IdDescriptionMetadata, boolean)}'s comment -- is {@code null} only for a
     * declaration built from a blank/missing ID under a lenient context) -- reported with its own, more specific message
     * rather than falling through to {@code map.containsKey(null)} (which would just report absence, since a
     * blank/missing ID is never stored under {@code addXxx}'s own rule -- see each {@code addXxx} method's "has
     * no ID and cannot be stored" branch above -- so the map can never hold a {@code null} key to match against).
     */
    private static void requirePresentForReplace(Map<String, ?> map, @Nullable String id, String typeName) {
      if (id == null) {
        throw new VcfFormatException(typeName + " metadata declaration has no ID to replace by");
      }
      if (!map.containsKey(id)) {
        // truncated and escaped, not embedded verbatim: id comes from the caller-constructed md's own ID
        // property, unbounded by public construction -- found by the seventh re-verification round's fresh
        // bug hunt.
        throw new VcfFormatException("No existing " + typeName + " metadata declaration with ID \"" +
            InternalUtils.truncateAndEscapeForMessage(id) + "\" to replace; use addXxx to add a new declaration " +
            "instead");
      }
    }

    /**
     * Replaces the existing ALT declaration under {@code md}'s unquoted {@code ID} (the map's own storage key --
     * see {@code addAlt(IdDescriptionMetadata, boolean)}'s comment), throwing via
     * {@link #requirePresentForReplace} if none is stored. Content is validated (via {@link #validateAlt})
     * completely before the single map mutation, and {@link #warnIfDuplicate} is never consulted -- so a
     * rejected replacement (e.g. an invalid Number/Type extra) leaves the existing declaration installed and this
     * builder otherwise unchanged. {@code addAlt(md, boolean)} above cannot be reused here: it runs
     * {@code warnIfDuplicate} before {@code validateAlt}, which would short-circuit a same-ID replacement on the
     * duplicate-ID warning before content is ever validated (Step 2a of the task that introduced this method).
     */
    private Builder replaceAlt(IdDescriptionMetadata md) {
      return replaceKeyedDeclaration(m_alt, md, "ALT", m -> validateAlt(m, m_context));
    }

    /**
     * Shared replace-declaration logic for the seven {@code replaceXxx(md)} overloads below: null/context
     * checks, ID extraction, {@link #requirePresentForReplace}, and map storage -- these differ only in the
     * map, the declaration's own type, the type-name string used in messages, and (ALT only) an extra
     * post-construction content check, exactly the same shape {@link #addKeyedDeclaration} shares for the
     * addXxx(md) overloads. {@code validateContent}, if non-null, always runs (unlike
     * {@link #addKeyedDeclaration}'s duplicate-ID short-circuit, there is none here to skip it for) --
     * content is validated completely before the single map mutation. {@code addAlt(md, boolean)} cannot be
     * reused here: it runs {@code warnIfDuplicate} before validating content, which would short-circuit a
     * same-ID replacement on the duplicate-ID warning before content is ever validated (Step 2a of the task
     * that introduced this method).
     */
    private <T extends BaseMetadata> Builder replaceKeyedDeclaration(Map<String, T> map, T md, String typeName,
        @Nullable Consumer<T> validateContent) {
      requireNonNull(md, typeName);
      checkSameContext(md, m_context, typeName);
      // getPropertyUnquoted(ID) -- see addKeyedDeclaration's identical comment; the map is keyed by the
      // unquoted ID, so a lookup/store here must match.
      String id = md.getPropertyUnquoted(IdMetadata.ID);
      requirePresentForReplace(map, id, typeName);
      if (validateContent != null) {
        validateContent.accept(md);
      }
      map.put(id, md);
      return this;
    }

    /**
     * Replaces the existing INFO declaration under {@code md}'s unquoted {@code ID}, throwing via
     * {@link #requirePresentForReplace} if none is stored. See {@link #replaceAlt(IdDescriptionMetadata)} for the
     * shared reasoning; INFO has no equivalent post-construction content check to run before the map mutation.
     */
    private Builder replaceInfo(InfoMetadata md) {
      return replaceKeyedDeclaration(m_info, md, "INFO", null);
    }

    /**
     * Replaces the existing FILTER declaration under {@code md}'s unquoted {@code ID}, throwing via
     * {@link #requirePresentForReplace} if none is stored. See {@link #replaceAlt(IdDescriptionMetadata)} for the
     * shared reasoning; FILTER has no equivalent post-construction content check to run before the map mutation.
     */
    private Builder replaceFilter(IdDescriptionMetadata md) {
      return replaceKeyedDeclaration(m_filter, md, "FILTER", null);
    }

    /**
     * Replaces the existing FORMAT declaration under {@code md}'s unquoted {@code ID}, throwing via
     * {@link #requirePresentForReplace} if none is stored. See {@link #replaceAlt(IdDescriptionMetadata)} for the
     * shared reasoning; FORMAT has no equivalent post-construction content check to run before the map mutation.
     */
    private Builder replaceFormat(FormatMetadata md) {
      return replaceKeyedDeclaration(m_format, md, "FORMAT", null);
    }

    /**
     * Replaces the existing CONTIG declaration under {@code md}'s unquoted {@code ID}, throwing via
     * {@link #requirePresentForReplace} if none is stored. See {@link #replaceAlt(IdDescriptionMetadata)} for the
     * shared reasoning; CONTIG has no equivalent post-construction content check to run before the map mutation.
     */
    private Builder replaceContig(ContigMetadata md) {
      return replaceKeyedDeclaration(m_contig, md, "CONTIG", null);
    }

    /**
     * Replaces the existing SAMPLE declaration under {@code md}'s unquoted {@code ID}, throwing via
     * {@link #requirePresentForReplace} if none is stored. See {@link #replaceAlt(IdDescriptionMetadata)} for the
     * shared reasoning; SAMPLE has no equivalent post-construction content check to run before the map mutation.
     */
    private Builder replaceSample(IdDescriptionMetadata md) {
      return replaceKeyedDeclaration(m_sample, md, "SAMPLE", null);
    }

    /**
     * Replaces the existing META declaration under {@code md}'s unquoted {@code ID}, throwing via
     * {@link #requirePresentForReplace} if none is stored. See {@link #replaceAlt(IdDescriptionMetadata)} for the
     * shared reasoning; META has no equivalent post-construction content check to run before the map mutation.
     */
    private Builder replaceMeta(MetaMetadata md) {
      return replaceKeyedDeclaration(m_meta, md, "META", null);
    }

    public Builder addRawProperty(String name, String value) {
      // name/value are typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting
      // a null name reach InternalUtils.checkNoLineTerminator(name, value) below (name.contains(...) would NPE) or a
      // null value reach isEmptyMetadataValue(value) further down (value.trim() would NPE)
      if (name == null) {
        throw new VcfFormatException("Metadata property name is null");
      }
      if (value == null) {
        // truncated and escaped, not embedded verbatim: name is a directly public-caller-supplied argument
        // with no length bound -- found by the seventh re-verification round's fresh bug hunt.
        throw new VcfFormatException("Metadata property \"" + InternalUtils.truncateAndEscapeForMessage(name) +
            "\" has a null value");
      }
      // Deliberately narrower than the structured rule, and deliberately unconditional. A generic ##name=value
      // line is re-read by splitting on the FIRST '=' (VcfParser.java:680-689), so an '=' in the name shifts
      // that split point and corrupts, while an '=' or ',' in the value is returned intact. No trusted variant
      // is needed: the parser derives the name from that same first-'=' split, so it cannot produce one
      // containing '='. Placed below the null guards above, not at the top of the method: this check itself
      // calls name.contains(...), which is what those guards exist to protect. See
      // STRUCTURAL_DELIMITER_HANDLING.md.
      if (name.contains("=")) {
        // truncated and escaped, not embedded verbatim: name is a directly public-caller-supplied argument
        // with no length bound -- found by the seventh re-verification round's fresh bug hunt.
        throw new VcfFormatException("Raw metadata property name contains '=', which would shift the "
            + "re-parse split point: " + InternalUtils.truncateAndEscapeForMessage(name));
      }
      InternalUtils.checkNoLineTerminator(name, value);
      if (name.isEmpty()) {
        // VCFv4.3.tex line 109: an unstructured meta-information line "consists of a key ... and a value ...
        // separated by an '=' character" -- a genuinely empty key (##=value) has no key at all, not just a
        // malformed one. VCFv4.3.tex line 39's general "Zero length fields are not allowed" applies the same
        // way it already does to the empty-value case just below. Deliberately scoped to a literally
        // zero-length name reaching THIS check, not a whitespace-only one: bcftools 1.23.1 tolerates "##  =x"
        // (a whitespace-only key) and keeps it verbatim, but rejects "##=x" outright -- confirmed live
        // ([E::bcf_hdr_parse_line] Could not parse the header line, declaration absent from output) -- so only
        // the empty case has the bcftools evidence AGENTS.md's Decision Hierarchy requires for a stricter-
        // than-parse-alone rule. That distinction is real when this method is called directly (e.g.
        // addRawProperty("  ", "x")): "  ".isEmpty() is false, so it's preserved verbatim, matching bcftools.
        // It does NOT hold on the real parse path: VcfParser.parseMetadata() extracts this method's own name
        // argument via line.substring(2, idx).trim() (needed so a structured-type keyword tolerates
        // surrounding whitespace, e.g. "##fileformat =VCFv4.3" -- see m_sawFileFormat's own field comment),
        // so a whitespace-only key from a real file already arrives here as "" and IS dropped by this check
        // too -- diverging from bcftools for that one input shape when read from a file, even though direct
        // construction still matches it. See EMPTY_FIELD_HANDLING.md's Row 24 for the same caveat spelled out.
        // Warn-and-drop, not throw-unconditionally, mirrors the empty-value branch just below: this is an
        // originated-content route too (addRawProperty is the Builder's own public API), so under a strict
        // context warnOrThrow still throws.
        warnOrThrow(m_context, "Metadata property name is empty and was dropped: {}", value);
        return this;
      }
      if (InternalUtils.isReservedMetadataKey(name, m_context.getVersion())) {
        // for every reserved name except a pre-4.3 "META", this cannot currently be reached through normal
        // parsing -- VcfParser.parseMetadata's switch routes every other reserved name to the structured path,
        // never to addRawProperty -- but it closes the hole for direct/programmatic Builder use, matching the
        // empty-value case below's warn-and-drop style. A pre-4.3 "META" IS reached through normal parsing
        // (##META doesn't exist before VCFv4.3, so isReservedMetadataKey already excludes it there and this
        // branch is never taken for it).
        warnOrThrow(m_context, "Metadata property name \"{}\" collides with a structured declaration keyword and was " +
            "dropped: {}", name, value);
        return this;
      }
      if (isEmptyMetadataValue(value)) {
        // VCFv4.3.tex line 39: "Zero length fields are not allowed, a dot ('.') must be used instead" -- stated
        // generally for meta-information/header/data lines, not scoped to data fields only; VCFv4.1/4.2 are
        // silent. bcftools 1.23.1 (htslib 1.23.1) actually silently PRESERVES a generic metadata line with a
        // completely empty value unchanged, with no warning -- confirmed directly ("##reference=" round-trips
        // through "bcftools view -h" unmodified). Dropping it here is a deliberate stricter choice, the same way
        // this library is already stricter than bcftools for an empty ID/FILTER/ALT list entry -- see
        // EMPTY_FIELD_HANDLING.md's "position-independent list: drop" strategy, used there for the same reason.
        warnOrThrow(m_context, "Metadata property \"{}\" has an empty value and was dropped: {}", name, value);
        return this;
      }
      if (collidesWithAttributeListGrammar(value)) {
        // see collidesWithAttributeListGrammar's own comment: htslib special-cases any generic property value shaped
        // like this regardless of key, so this is not scoped to "assembly"/"pedigreeDB" specifically
        warnOrThrow(m_context, "Metadata property \"{}\" is wrapped in angle brackets with no key=value content, " +
            "which bcftools cannot parse as a generic property value, and was dropped: {}", name, value);
        return this;
      }
      if (hasUnclosedAngleBracket(value)) {
        // see hasUnclosedAngleBracket's own comment: this is the shape collidesWithAttributeListGrammar's own
        // endsWith(">") guard lets straight through unchecked
        warnOrThrow(m_context, "Metadata property \"{}\" starts with '<' but has no closing '>', which " +
            "bcftools cannot parse as a generic property value, and was dropped: {}", name, value);
        return this;
      }
      m_properties.put(name, value);
      return this;
    }

    public Builder setColumns(List<String> cols) {
      // cols is typed non-@Nullable, but nothing enforces that at runtime; guard here rather than letting
      // Preconditions.checkNotNull(columns) in the constructor throw a raw NullPointerException at build() time
      if (cols == null) {
        throw new VcfFormatException("Column list is null");
      }
      m_columns = cols;
      return this;
    }

    public VcfMetadata build() {
      return new VcfMetadata(m_fileFormat, m_alt, m_info, m_filter, m_format, m_contig, m_sample, m_meta, m_pedigree,
          m_columns, m_properties, m_context);
    }
  }
}

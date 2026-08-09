package org.pharmgkb.parser.vcf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import org.jspecify.annotations.Nullable;


/**
 * This class contains the basic data (the first 9 columns) for a VCF position line.
 * <pre>
 * 0 - CHROM
 * 1 - POS
 * 2 - ID
 * 3 - REF
 * 4 - ALT
 * 5 - QUAL
 * 6 - FILTER
 * 7 - INFO
 * 8 - FORMAT
 * </pre>
 *
 * <p>
 * Effectively immutable: there are no setters, and every collection-returning getter returns an unmodifiable
 * view, so no method changes any value a caller can observe once construction (or {@link Builder#build()}) has
 * returned. QUAL and INFO are the one internal exception -- {@link #getQuality()}/{@link #getInfo()} and
 * friends parse lazily on first access, writing {@link #m_quality}/{@link #m_info} (and clearing
 * {@link #m_rawQuality}/{@link #m_rawInfo}) the first time either is actually read. That caching has no
 * observable effect on a single-threaded caller, but it is unsynchronized, so <b>this class is not
 * thread-safe</b>: sharing one instance across threads before its QUAL/INFO have both been read at least once
 * is not safe without external synchronization. A caller that needs a changed position derives a new one via
 * {@link #toBuilder()}.
 *
 * @author Mark Woon
 */
public final class VcfPosition {

  private static final Joiner sf_commaJoiner = Joiner.on(",");
  private String m_chromosome;
  private long m_position;
  private List<String> m_ids = new ArrayList<>();
  private String m_refBases;
  private List<String> m_altBases = new ArrayList<>();
  // QUAL is stored either as a parsed BigDecimal (eager path) or as raw text parsed lazily on first getQuality()
  // (parser path via this class's own constructor rawQual parameter); many consumers never read QUAL.
  private @Nullable BigDecimal m_quality;
  private @Nullable String m_rawQuality;
  private List<String> m_filter = new ArrayList<>();
  // INFO is stored either as a parsed multimap (eager constructor path) or as raw text parsed lazily on first access
  // (parser path via this class's own constructor rawInfo parameter); at most one is non-null at a time.
  private @Nullable ListMultimap<String, String> m_info;
  private @Nullable String m_rawInfo;
  private List<String> m_format = new ArrayList<>();
  // Memoized unmodifiable views over m_ids/m_altBases/m_filter/m_format/m_info, returned by the getters below
  // instead of allocating a fresh wrapper on every call -- VcfWriter calls these getters repeatedly per record
  // (once per sample property key, in VcfSample-materialization loops), and VcfParser's FORMAT-validation cache
  // calls getFormat() once per cache-miss record, so a per-call allocation here is a measurable hot-path cost.
  // m_ids/m_altBases/m_filter/m_format/m_info are reassigned only by validate() (never by these getters, and
  // never externally, since this class has no setters) -- validate() clears all six caches below before it
  // reassigns anything, so a stale cache is never returned; see its own comment.
  private @Nullable List<String> m_idsView;
  private @Nullable List<String> m_altBasesView;
  private @Nullable List<String> m_filtersView;
  private @Nullable List<String> m_formatView;
  private @Nullable ListMultimap<String, String> m_infoView;
  private @Nullable Set<String> m_infoKeysView;
  // originating line number in the source VCF file, or -1 if this position wasn't produced by parsing a real
  // file (e.g. constructed directly by a caller/test) -- used to attach line-number context to any
  // VcfFormatException this instance's own validation throws, guarded so a lineNumber of -1 (not produced by
  // parsing) leaves exception behavior unchanged.
  private int m_lineNumber = -1;
  private final VcfContext m_context;
  // Fixed at construction, from m_context's own sink, for this instance's entire lifetime -- nothing (including
  // a VcfWriter this instance is later handed to) can re-point it afterward. See VcfWarnings's class Javadoc for
  // the full attribution rule.
  private final VcfWarnings m_warnings;


  /**
   * The single construction path for a fully-specified position. Package-private: every caller goes through
   * {@link VcfContext#newPosition(String, long, List, String, List, BigDecimal, List, ListMultimap, List, int)}
   * (or, for {@code VcfParser}'s own per-record construction, {@code VcfContext.newPositionTakingOwnership}, for
   * its FORMAT cache specifically {@code VcfContext.newPositionWithTrustedFormat}, or for
   * {@link Builder#build()}, {@code VcfContext.newPositionCopyingTrustedFields}), which is what supplies the
   * {@code context} this instance derives both its warnings sink and its strict-vs-lenient behavior from.
   * <p>
   * <b>This constructor takes ownership of {@code ids}, {@code altBases}, {@code filter}, {@code format}, and
   * {@code info}</b> -- it does not defensively copy them. {@link VcfContext#newPosition} is where the copy for
   * an externally-supplied list happens; {@code newPositionTakingOwnership} and {@code newPositionWithTrustedFormat}
   * are two of the three factories that skip it ({@code newPositionCopyingTrustedFields} is the third, but -- true
   * to its name -- copies anyway, for a different reason; see its own Javadoc).
   * {@link #getIds()}, {@link #getAltBases()}, {@link #getFilters()}, {@link #getFormat()}, and
   * {@link #getInfo()} return unmodifiable views over these same objects (or an equivalent list/multimap
   * produced by normalization) -- taking ownership rather than copying is still worth doing, since it skips a
   * copy the caller has no way to observe the absence of once every getter's view is unmodifiable.
   * <p>
   * Strictness comes from {@code context}, never from which overload was called: under
   * {@link VcfContext#strict}, every recoverable condition ({@code checkWarnings == null} below) throws
   * {@link VcfFormatException}; under {@link VcfContext#lenient} the identical condition warns and recovers.
   *
   * @param lineNumber the originating line number in the source VCF file, or {@code -1} if this position was not
   * produced by parsing a real file (e.g. constructed directly by a caller or test)
   * @param trustedFormat when {@code true}, skips {@link #checkFormat} entirely (every check it performs,
   * including the null-element check and any warning it would emit), relying on the caller to guarantee
   * {@code format} is a fresh, independently-owned copy whose elements already passed {@code checkFormat} once,
   * unchanged. Every other check still runs. See {@code VcfContext.newPositionWithTrustedFormat}.
   * @param trustedFields when {@code true}, skips {@code checkIds}/{@code checkAltBases}/{@code checkFilters}/
   * {@code checkInfoEntries} entirely (the same "every check including any warning" skip {@code trustedFormat}
   * makes for {@code checkFormat}, just for the other four validation-relevant fields), relying on the caller to
   * guarantee each of {@code ids}/{@code altBases}/{@code filter}/{@code info} already passed its own check once,
   * unchanged. {@code checkChromosome}/{@code checkPosition}/{@code checkRef} are never skipped by this flag --
   * unlike the other five checks, none of them has a warn-and-recover branch (only throw-or-pass), so re-running
   * them on already-valid content can never re-emit a diagnostic; skipping them would only remove a correctness
   * safety net for a value a caller-supplied setter changed without full validation (see
   * {@code VcfPosition.Builder#chromosome}/{@code #ref}, which only reject {@code null}). See
   * {@code VcfContext.newPositionCopyingTrustedFields}, used exclusively by {@link Builder#build()}: the builder
   * itself re-runs whichever of these four checks a field actually changed since it was seeded, so by the time
   * that factory is reached every field is either freshly re-validated or provably unchanged from an
   * already-valid source -- {@code trustedFields=true} tells this constructor not to redundantly check it again.
   * @param rawQual raw, not-yet-parsed QUAL column text to store as {@link #m_rawQuality}, parsed lazily on first
   * {@link #getQuality()}, when {@code qual} is {@code null} -- lets a caller construct a lazily-parsed position
   * in one call instead of a separate post-construction mutation. Ignored (and never stored) when {@code qual}
   * is non-null, preserving this class's "parsed and raw are never both non-null" invariant for QUAL.
   * @param rawInfo the {@code rawQual} parameter's exact counterpart for INFO.
   */
  VcfPosition(VcfContext context, String chr, long pos,
      @Nullable List<String> ids,
      String ref,
      @Nullable List<String> altBases,
      @Nullable BigDecimal qual,
      @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info,
      @Nullable List<String> format,
      int lineNumber,
      boolean trustedFormat,
      boolean trustedFields,
      @Nullable String rawQual,
      @Nullable String rawInfo) {

    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_warnings = context.getWarnings();

    /*
      1. Check the arguments, in order. `checkWarnings` is null under a strict context -- every check method
      below throws instead of warning when it receives null; see each one's own signature. That null-means-strict
      encoding is now purely an internal convention between this class and its own (and InternalUtils's) private
      check helpers: the strict-vs-lenient DECISION comes from the context, never from which constructor or
      factory the caller reached.
     */
    VcfWarnings checkWarnings = context.isLenient() ? context.getWarnings() : null;

    checkChromosome(chr);
    checkPosition(pos);
    if (ids != null && !trustedFields) {
      ids = checkIds(ids, checkWarnings);
    }
    checkRef(ref);
    if (altBases != null && !trustedFields) {
      altBases = checkAltBases(altBases, checkWarnings);
    }
    if (filter != null && !trustedFields) {
      filter = checkFilters(filter, checkWarnings);
    }
    if (info != null && !trustedFields) {
      info = checkInfoEntries(info, checkWarnings);
    }
    if (format != null && !trustedFormat) {
      format = checkFormat(format, checkWarnings);
    }

    /*
      2. Set the fields, in order
     */

    // not resolving ID string
    m_chromosome = chr; // required
    m_position = pos; // required
    m_lineNumber = lineNumber; // required (-1 if not produced by parsing a real line)

    if (ids != null) {
      m_ids = ids;
    }

    m_refBases = ref; // required

    if (altBases != null) {
      m_altBases = altBases;
    }

    m_quality = qual; // required

    if (filter != null) {
      m_filter = filter;
    }

    if (info != null) {
      m_info = info;
    }

    if (format != null) {
      m_format = format;
    }

    /*
      3. Normalize a lone "PASS" or "." FILTER value, now that m_filter is set
     */

    normalizeFilters();

    /*
      4. Attach raw, not-yet-parsed QUAL/INFO text, if supplied and not superseded by a parsed value -- see this
      constructor's own rawQual/rawInfo Javadoc.
     */
    if (qual == null && rawQual != null) {
      m_rawQuality = rawQual;
    }
    if (info == null && rawInfo != null) {
      m_rawInfo = rawInfo;
    }
  }

  /**
   * Convenience shape for a position with no ID/ALT/FILTER/INFO/FORMAT content. Package-private for the same
   * reason as the primary constructor above; reached via
   * {@link VcfContext#newPosition(String, long, String, BigDecimal, int)}. There is nothing mutable to take
   * ownership of here, so that factory has no defensive copy to make.
   *
   * @param lineNumber the originating line number in the source VCF file, or {@code -1} if this position was not
   * produced by parsing a real file (e.g. constructed directly by a caller or test)
   */
  VcfPosition(VcfContext context, String chromosome, long position, String refBases, @Nullable BigDecimal quality,
      int lineNumber) {
    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_warnings = context.getWarnings();
    checkChromosome(chromosome);
    checkPosition(position);
    checkRef(refBases);
    m_chromosome = chromosome;
    m_position = position;
    m_refBases = refBases;
    m_quality = quality;
    m_lineNumber = lineNumber; // required (-1 if not produced by parsing a real line)
  }

  /** @return the context this position was created under; never {@code null}. */
  public VcfContext getContext() {
    return m_context;
  }

  /**
   * @return a new {@link Builder}, seeded with a copy of every field of this position, bound to this position's
   * own {@link VcfContext} by reference (not a re-derived or newly constructed one -- see {@link VcfContext}'s
   * {@code newRecord}, which rejects a component built under a different context instance).
   * <p>
   * QUAL and INFO are seeded from {@link #m_quality}/{@link #m_rawQuality} and {@link #m_info}/{@link #m_rawInfo}
   * directly, never through {@link #getQuality()}/{@link #getInfo()}: those getters force lazy parsing (emitting
   * any parse warning) as a side effect, and {@link #getQuality()} additionally nulls {@link #m_rawQuality} when
   * it does, so reading through them here would both parse-and-warn at copy time and destroy the source's own
   * lazy state.
   * <p>
   * {@code ids}/{@code altBases}/{@code filter}/{@code format}/{@code info} are seeded through {@link Builder}'s
   * private {@code seedXxx} methods, not its public setters -- this position already validated (or already
   * warned about and recovered from) every one of them once, so seeding must not mark them dirty for
   * {@link Builder#build()} to re-check. A caller who then calls the corresponding public setter on the
   * returned builder marks that one field dirty, and only that field is re-validated on {@link Builder#build()}.
   */
  public Builder toBuilder() {
    Builder builder = new Builder(m_context)
        .chromosome(m_chromosome)
        .position(m_position)
        .ref(m_refBases)
        .lineNumber(m_lineNumber);
    builder.seedIds(m_ids);
    builder.seedAltBases(m_altBases);
    builder.seedFilters(m_filter);
    builder.seedFormat(m_format);
    if (m_quality != null) {
      builder.quality(m_quality);
    } else {
      builder.rawQuality(m_rawQuality);
    }
    if (m_info != null) {
      builder.seedInfo(m_info);
    } else {
      builder.rawInfo(m_rawInfo);
    }
    return builder;
  }

  private static void checkChromosome(String chr) {
    // chr is typed non-@Nullable, but nothing enforces that at runtime -- a caller can still pass null to the
    // public VcfContext.newPosition factory. Guard here rather than letting chr.isEmpty() below throw a raw
    // NullPointerException, per AGENTS.md's Public API Null-Safety Policy; this runs from both constructors and
    // validate(), so it also closes the same gap there.
    if (chr == null) {
      throw new VcfFormatException("CHROM is null");
    }
    // hts-specs test/vcf/4.2/failed/failed_body_chrom_002.vcf, ##CauseOfFailure=contig ID contains a
    // whitespace (literal CHROM="chr 1").
    if (chr.isEmpty() || InternalUtils.containsWhitespace(chr)) {
      throw new VcfFormatException("CHROM column \"" + chr + "\" is empty or contains whitespace");
    }
    // VCFv4.2.tex line 179 also documents CHROM's other valid form, a fully angle-bracket-wrapped symbolic
    // reference ("<ID>") pointing to a contig in the assembly file -- unwrap it before applying the character
    // check below so a well-formed "<1>" isn't rejected for the very brackets that make it valid; an
    // unwrapped/malformed use of '<' or '>' (e.g. a lone leading "<1") still hits the check below.
    String content = chr;
    if (chr.length() > 1 && chr.charAt(0) == '<' && chr.charAt(chr.length() - 1) == '>') {
      content = chr.substring(1, chr.length() - 1);
    }
    if (content.isEmpty()) {
      throw new VcfFormatException("CHROM column \"" + chr + "\" is empty or contains whitespace");
    }
    // VCFv4.3.tex's appendix: "* is now valid when not the first character"; "*"/"=" as the first character
    // "were already invalid and remain so" -- see also this class's own INVALID_CONTIG_NAME_CHAR_PATTERN,
    // whose character class deliberately does not include '*'/'=' since they're only invalid in this one
    // first-character position, not everywhere in CHROM.
    char first = content.charAt(0);
    if (first == '*' || first == '=') {
      throw new VcfFormatException("CHROM column \"" + chr + "\" must not start with '" + first + "'");
    }
    Matcher matcher = InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN.matcher(content);
    if (matcher.find()) {
      throw new VcfFormatException("CHROM column \"" + chr + "\" contains the invalid character '" +
          matcher.group() + "'");
    }
  }

  private static void checkPosition(long pos) {
    // POS 0 is reserved for telomeres, but a negative position is invalid
    if (pos < 0) {
      throw new VcfFormatException("POS " + pos + " is negative");
    }
  }

  private static void checkRef(String ref) {
    // ref is typed non-@Nullable, but nothing enforces that at runtime -- a caller can still pass null to the
    // public VcfContext.newPosition factory. Guard here rather than letting isValidRefBases(ref) below throw
    // a raw NullPointerException, per AGENTS.md's Public API Null-Safety Policy; this runs from both constructors
    // and validate(), so it also closes the same gap there.
    if (ref == null) {
      throw new VcfFormatException("REF is null");
    }
    // VCFv4.2.tex line 182: "REF - reference base(s): Each base must be one of A,C,G,T,N (case insensitive)."
    // hts-specs test/vcf/4.2/failed/failed_body_ref_001.vcf (##CauseOfFailure=REF has a non-valid base,
    // REF="B") confirms an out-of-alphabet base is a structural rejection, not a warn-and-recover case.
    if (!InternalUtils.isValidRefBases(ref)) {
      throw new VcfFormatException("Invalid reference base '" + ref +
          "' (must match " + InternalUtils.REF_BASE_PATTERN + ")");
    }
  }

  /**
   * @return {@code ids} unchanged, or a new list with any empty entry dropped (with a warning, or a thrown
   * {@link VcfFormatException} if {@code warnings} is {@code null} -- strict mode); see
   * {@code EMPTY_FIELD_HANDLING.md}. A duplicate ID is left in place (with the same warn-or-throw signal) rather
   * than dropped or deduplicated; see {@code VCF_SPEC_AMBIGUITIES.md}.
   */
  private static List<String> checkIds(List<String> ids, @Nullable VcfWarnings warnings) {
    ids = InternalUtils.dropEmptyEntries("ID", ids, warnings);
    Set<String> seen = ids.size() > DUPLICATE_SCAN_THRESHOLD ? new HashSet<>() : null;
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i);
      // hts-specs' own conformance fixtures document both rejections directly: test/vcf/4.2/failed/
      // failed_body_id_000.vcf ("ID has a whitespace"), failed_body_id_001.vcf ("ID begins with a semi-colon"),
      // and failed_body_id_002.vcf ("ID ends with a semi-colon").
      if (InternalUtils.containsWhitespace(id) || id.contains(";")) {
        throw new VcfFormatException("ID \"" + id + "\" contains whitespace or semicolons");
      }
      // VCFv4.2.tex line 181 has no duplicate-value clause for ID ("String, no whitespace or semicolons
      // permitted"); VCFv4.3.tex line 338 adds "duplicate values not allowed" verbatim, used here as
      // VCFv4.2-silent clarifying guidance -- matching hts-specs' own test/vcf/4.3/failed/failed_body_id_003.vcf
      // (##CauseOfFailure=ID contains duplicate values in a single line, literal "ID=rs180734498;rs180734498").
      // Confirmed directly that both bcftools 1.23.1 and vcf_validator 0.10.2 silently accept a duplicate ID.
      // Warn rather than reject: ID is stored/emitted as an ordinary List (m_ids), never looked up by key or
      // indexed positionally by any other field (unlike ALT, which GT indexes into -- see checkAltBases), so a
      // duplicate causes no data loss or misalignment -- matching this library's general permissive stance for
      // non-structural content. See VCF_SPEC_AMBIGUITIES.md's "Duplicate ID/FILTER entries within one record"
      // entry, and checkFilters() below for the identical treatment of the same field shape.
      //
      // isDuplicateAt (see its own Javadoc for the DUPLICATE_SCAN_THRESHOLD hybrid this dispatches through).
      if (isDuplicateAt(ids, i, seen)) {
        if (warnings == null) {
          throw new VcfFormatException("Duplicate ID \"" + id + "\"");
        }
        warnings.warn("Duplicate ID \"{}\"", id);
      }
    }
    return ids;
  }

  // ID/FILTER/FORMAT are delimiter-separated lists inside a single column, so their element count is bounded
  // only by line length -- attacker-controlled and unbounded, NOT bounded by this record's own column
  // structure (an earlier version of this comment/threshold was wrong about that). Measured (see the reviewer
  // report that caught this): a per-call HashSet<String> costs ~2.7x more than an allocation-free O(n) scan
  // for a typical tiny list (crossover around n=8), but the scan is O(n^2) overall -- a 2.7 MB single-record
  // file with 100,000 IDs took the scan 25.4 SECONDS vs. 91 ms for the HashSet version, the same CPU-exhaustion
  // shape as the ##PEDIGREE O(n^2) DoS already fixed in VcfMetadata.Builder.addPedigree. This threshold
  // keeps the scan's win for the common case while falling back to a HashSet before the list can grow large
  // enough to matter.
  private static final int DUPLICATE_SCAN_THRESHOLD = 8;

  /**
   * @return Whether {@code list.get(index)} equals any earlier element in {@code list}. If {@code seen} is
   * non-null, it is used (and mutated) as an accumulating membership set instead of scanning {@code list}
   * directly -- see {@link #DUPLICATE_SCAN_THRESHOLD}'s own comment for why a caller must supply one once
   * {@code list} is no longer small. A caller skipping this method entirely for one index (e.g. an empty
   * FORMAT sub-field name) must not call it for that index at all, not call it and ignore the result: skipping
   * the call is also what keeps that index's value out of {@code seen} for every later comparison.
   */
  private static boolean isDuplicateAt(List<String> list, int index, @Nullable Set<String> seen) {
    String value = list.get(index);
    if (seen != null) {
      return !seen.add(value);
    }
    for (int i = 0; i < index; i++) {
      if (list.get(i).equals(value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return {@code altBases} unchanged, or a new list derived from it with any empty entry substituted with
   * {@code "."} (with a warning, or a thrown {@link VcfFormatException} if {@code warnings} is {@code null} --
   * strict mode); see {@code EMPTY_FIELD_HANDLING.md}
   */
  private static List<String> checkAltBases(List<String> altBases, @Nullable VcfWarnings warnings) {
    // ALT is position-dependent: GT genotype values index into it positionally (VCFv4.2.tex line 217: "1 for the
    // first allele listed in ALT, 2 for the second allele listed in ALT and so on"; see getAllele(int)). Dropping
    // an empty entry (the previous behavior) would shift every subsequent allele's index down by one, silently
    // misdirecting a GT value referencing a position after the dropped entry to the wrong allele. Substituting
    // '.' instead preserves every index, matching bcftools 1.23.1 exactly (confirmed empirically: "T,,C" becomes
    // "T,.,C", with no warning, and genotype indices before/at/after the dot still resolve correctly).
    if (altBases.contains("")) {
      // Copy first: fillEmptyEntriesWithDot mutates its argument in place, but altBases may be an immutable or
      // caller-owned list (e.g. List.of(...), Arrays.asList(...)) -- mutating it directly would throw
      // UnsupportedOperationException or silently alias-mutate a list the caller still holds a reference to. Only
      // copying when an empty entry actually needs substituting avoids an allocation on the common case (no
      // empty ALT entry), matching checkIds()/checkFilters()'s existing lazy-copy style.
      altBases = new ArrayList<>(altBases);
      InternalUtils.fillEmptyEntriesWithDot("ALT", altBases, warnings);
    }
    for (String base : altBases) {
      if (base == null) {
        throw new VcfFormatException("ALT contains a null entry");
      }
      if (!InternalUtils.ALT_BASE_PATTERN.matcher(base).matches()) {
        throw new VcfFormatException("Invalid alternate base '" + base + "' (must match " + InternalUtils.ALT_BASE_PATTERN + ")");
      }
    }
    return altBases;
  }

  /**
   * Checks the given FILTER values for validity. This does not perform the construction-time normalization of a lone
   * {@code "PASS"} or {@code "."} value (see the constructor); those are legal single-element lists on their own.
   *
   * @return {@code filters} unchanged, or a new list with any empty entry dropped (with a warning, or a thrown
   * {@link VcfFormatException} if {@code warnings} is {@code null} -- strict mode, which likewise throws rather
   * than warns for PASS combined with other filters); see {@code EMPTY_FIELD_HANDLING.md}. A duplicate FILTER
   * code is left in place (with the same warn-or-throw signal) rather than dropped or deduplicated; see
   * {@code VCF_SPEC_AMBIGUITIES.md}.
   */
  private static List<String> checkFilters(List<String> filters, @Nullable VcfWarnings warnings) {
    filters = InternalUtils.dropEmptyEntries("FILTER", filters, warnings);
    Set<String> seen = filters.size() > DUPLICATE_SCAN_THRESHOLD ? new HashSet<>() : null;
    for (int i = 0; i < filters.size(); i++) {
      String f = filters.get(i);
      // hts-specs test/vcf/4.2/failed/failed_body_filter_003.vcf, ##CauseOfFailure=FILTER contains whitespaces
      // (literal FILTER="my filter").
      if (InternalUtils.containsWhitespace(f)) {
        throw new VcfFormatException("FILTER column entry \"" + f + "\" contains whitespace");
      }
      if (f.equals("0")) {
        // hts-specs test/vcf/4.2/failed/failed_body_filter_004.vcf, ##CauseOfFailure=FILTER contains reserved
        // value 0. Independently confirmed live via vcf_validator 0.10.2 ("FILTER string must not be 0
        // (reserved value)").
        throw new VcfFormatException("FILTER column entry should not be 0");
      }
      if (f.equals("PASS") && filters.size() > 1) {
        // VCFv4.2.tex line 185 only implies PASS is exclusive ("PASS if this position has passed all filters ...
        // Otherwise ... a semicolon-separated list of codes for filters that fail"); it never states this as a
        // hard rule. hts-specs' own test/vcf/{4.1,4.2,4.3}/passed/complexfile_passed_000.vcf fixture combines PASS
        // with another code (line 61: "STD_FILTER;PASS") and is expected to parse. Confirmed valid by
        // vcf_validator 0.10.2 and accepted silently by bcftools 1.23.1 (htslib 1.23.1).
        if (warnings == null) {
          throw new VcfFormatException("FILTER contains PASS along with other filters: " + filters);
        }
        warnings.warn("FILTER contains PASS along with other filters: {}", filters);
      }
      if (f.equals(".") && filters.size() > 1) {
        // hts-specs test/vcf/4.2/failed/failed_body_filter_000.vcf, ##CauseOfFailure=FILTER contains missing
        // in addition to a value. Unconditional (not gated on warnings == null like the PASS-combined check
        // above): unlike PASS, `.` has no legitimate combined-with-another-code use documented anywhere.
        throw new VcfFormatException("FILTER contains '.' (missing value) along with other filters!");
      }
      // VCFv4.2.tex line 185 has no duplicate-value clause for FILTER ("String, no whitespace or semicolons
      // permitted"); VCFv4.3.tex line 362 adds "duplicate values not allowed" verbatim, used here as
      // VCFv4.2-silent clarifying guidance -- matching hts-specs' own
      // test/vcf/4.3/failed/failed_body_filter_004.vcf (##CauseOfFailure=FILTER contains duplicate values,
      // literal "hp23;hp23"). Confirmed directly that both bcftools 1.23.1 and vcf_validator 0.10.2 silently
      // accept a duplicate FILTER code. Warn rather than reject: FILTER is stored/emitted as an ordinary List
      // (m_filter), never looked up by key or indexed positionally by any other field, so a duplicate causes no
      // data loss or misalignment -- matching this library's general permissive stance for non-structural
      // content, and matching checkIds()'s identical treatment of the same field shape. See
      // VCF_SPEC_AMBIGUITIES.md's "Duplicate ID/FILTER entries within one record" entry.
      //
      // isDuplicateAt (see checkIds() above and DUPLICATE_SCAN_THRESHOLD's own comment) dispatches between an
      // allocation-free scan and a HashSet<String> depending on list size.
      if (isDuplicateAt(filters, i, seen)) {
        if (warnings == null) {
          throw new VcfFormatException("Duplicate FILTER code \"" + f + "\"");
        }
        warnings.warn("Duplicate FILTER code \"{}\"", f);
      }
    }
    return filters;
  }

  /**
   * @return {@code info} unchanged, or a new multimap derived from it with any empty-key entry dropped (with a
   * warning, or a thrown {@link VcfFormatException} if {@code warnings} is {@code null} -- strict mode); see
   * {@code EMPTY_FIELD_HANDLING.md}. Also throws {@link VcfFormatException} -- unconditionally, even when
   * {@code warnings} is non-null, the same as {@link #checkFilters}'s identical {@code '.'}-combined-with-other-
   * filters check -- if a bare {@code "."} (missing value) entry is combined with any other property.
   */
  private static ListMultimap<String, String> checkInfoEntries(ListMultimap<String, String> info,
      @Nullable VcfWarnings warnings) {
    // same reasoning: info()'s own raw-text-parsing branch already drops an empty key (with a warning) before it
    // ever reaches a built multimap there, so this only has bite for a ListMultimap passed directly to the eager
    // constructor or set later via getInfo() -- previously, neither this constructor nor validate() (which also
    // calls this method) ever detected or dropped it
    boolean hasEmptyKey = info.containsKey("");
    ListMultimap<String, String> result = hasEmptyKey ? LinkedListMultimap.create() : info;
    for (Map.Entry<String, String> entry : info.entries()) {
      String key = entry.getKey();
      String value = entry.getValue();
      // a parsed entry's key/value can never be null; only a value set directly via getInfo().put(...) could be
      // (LinkedListMultimap permits both null keys and null values)
      if (key == null) {
        throw new VcfFormatException("INFO column contains a null key");
      }
      if (value == null) {
        throw new VcfFormatException("INFO value for key \"" + key + "\" is null");
      }
      if (key.isEmpty()) {
        if (warnings == null) {
          throw new VcfFormatException("INFO entry \"=" + value + "\" has an empty key");
        }
        warnings.warn("INFO entry \"{}\" has an empty key (VCF does not allow zero-length fields); dropping it",
            "=" + value);
        continue;
      }
      // hts-specs test/vcf/4.2/failed/failed_body_info_028.vcf, ##CauseOfFailure=INFO contains a whitespace
      // (literal INFO="RS Q=123").
      if (InternalUtils.containsWhitespace(key) || InternalUtils.containsWhitespace(value)) {
        throw new VcfFormatException("INFO column entry \"" + key + "=" + value + "\" contains whitespace");
      }
      // a parsed value can never contain these (they were already split on to arrive at this value), but a value set
      // directly via getInfo().put(...) could; reject them so the writer's output remains re-parseable
      if (key.contains(";") || key.contains("=")) {
        throw new VcfFormatException("INFO key \"" + key + "\" contains ';' or '='");
      }
      if (value.contains(";") || value.contains(",")) {
        throw new VcfFormatException("INFO value \"" + value + "\" for key \"" + key + "\" contains ';' or ','");
      }
      // VCFv4.2's INFO grammar note ("String, no whitespace, semicolons, or equals-signs permitted") applies this
      // constraint to the individual key and value making up each entry, the same convention already used above
      // for whitespace/';'/',' -- so a '=' here is technically non-compliant. But bcftools 1.23.1 accepts it
      // silently with no warning at all (verified live: "DP=1=2" round-trips as-is, splitting only on the first
      // '=', exactly like this parser already does), so this is warned about rather than rejected, consistent
      // with this project's policy of not being stricter than bcftools on input it demonstrably tolerates (see
      // EMPTY_FIELD_HANDLING.md).
      if (value.contains("=")) {
        if (warnings == null) {
          throw new VcfFormatException("INFO value \"" + value + "\" for key \"" + key + "\" contains '='");
        }
        warnings.warn("INFO value \"{}\" for key \"{}\" contains '='; VCFv4.2's grammar doesn't permit this, but " +
            "bcftools 1.23.1 accepts it silently, so it is preserved as-is", value, key);
      }
      if (hasEmptyKey) {
        result.put(key, value);
      }
    }
    // "." (missing value: no INFO at all) cannot be combined with a real property, the same as FILTER's own
    // exclusivity rule for its "." sentinel (see checkFilters above) -- unconditional (not gated on
    // warnings == null), matching that precedent: "." has no legitimate combined-with-another-property use
    // documented anywhere. Deliberately runs AFTER the loop above, over the already-cleaned `result` (empty
    // keys dropped, null keys/values already rejected) rather than the raw `info` argument: checking beforehand
    // let a null value under key "." reach a raw NullPointerException here instead of the loop's own
    // VcfFormatException, and let a "." entry alongside only an empty-key entry (which the loop above drops)
    // wrongly hard-throw in lenient mode instead of correctly reducing to just "." once the empty key is
    // dropped. Scoped to a "." key whose OWN value is blank (the bare-sentinel shape): a "." key holding a
    // real, non-empty value (raw text ".=5") is a merely-unusual literal key name info()'s own
    // raw-text-parsing branch below has always left alone -- its props.contains(".") only matches a bare "."
    // token, never ".=value" -- so conflating the two here would newly reject input the parser has always
    // accepted. This scoping also means result.size() (which counts key-VALUE pairs, not distinct keys) can't
    // be used directly: a single "." key with multiple comma-derived values (raw text ".=1,2") would otherwise
    // false-positive with no other property present at all.
    if (result.containsKey(".")) {
      List<String> dotValues = result.get(".");
      boolean hasBareDotSentinel = dotValues.contains("");
      boolean hasOtherContent = result.keySet().size() > 1 || dotValues.stream().anyMatch(v -> !v.isEmpty());
      if (hasBareDotSentinel && hasOtherContent) {
        throw new VcfFormatException("INFO contains '.' (missing value) along with other properties");
      }
    }
    return result;
  }

  /**
   * @return {@code format} unchanged, or a new empty list if {@code format} was the single-element list
   * {@code ["."]} -- FORMAT being "." means no per-sample format fields at all when sample columns are present.
   * VCFv4.2 through 4.5 are all silent on this exact case (FORMAT is only described as required "if genotype
   * information is present"), but bcftools 1.23.1 accepts and correctly interprets it: {@code bcftools query -f
   * '[%GT]'} against a FORMAT=. record returns the missing value regardless of that sample's own raw column
   * content, and {@code bcftools view} round-trips such a sample as "." even given other input text there.
   * Verified live against bcftools 1.23.1; per this project's policy of falling back to bcftools when the spec is
   * silent, this is treated as valid here too. An empty sub-field name is warned about and kept as-is, or -- if
   * {@code warnings} is {@code null} (strict mode) -- throws {@link VcfFormatException} instead.
   */
  private static List<String> checkFormat(List<String> format, @Nullable VcfWarnings warnings) {
    if (format.size() == 1 && ".".equals(format.get(0))) {
      return new ArrayList<>();
    }
    // duplicate keys are explicitly disallowed by the VCF spec (VCFv4.3+ states this outright; empty and
    // grammar-invalid keys are excluded from this check since they're already handled, and warned about,
    // separately below). isDuplicateAt (see checkIds() above and DUPLICATE_SCAN_THRESHOLD's own comment)
    // dispatches between an allocation-free scan and a HashSet<String> depending on list size.
    Set<String> seen = format.size() > DUPLICATE_SCAN_THRESHOLD ? new HashSet<>() : null;
    for (int i = 0; i < format.size(); i++) {
      String f = format.get(i);
      if (f == null) {
        throw new VcfFormatException("FORMAT contains a null sub-field name");
      }
      // an empty sub-field name is kept as-is rather than dropped: every sample's colon-split values are matched to
      // FORMAT keys by index, and dropping this key would misalign every sample's values with the wrong key. See
      // EMPTY_FIELD_HANDLING.md.
      if (f.isEmpty()) {
        if (warnings == null) {
          throw new VcfFormatException("FORMAT contains an empty sub-field name (VCF does not allow zero-length fields)");
        }
        warnings.warn("FORMAT contains an empty sub-field name (VCF does not allow zero-length fields); keeping " +
            "it as-is, but it cannot be looked up in metadata");
        continue;
      }
      // f.contains(":") is not checked separately: FORMAT_PATTERN's character class ([0-9A-Za-z_.]) never
      // includes ':', so matches() alone already rejects any f containing one. Lenient, not a structural floor:
      // matches FormatMetadata's identical warn-only treatment of this same grammar rule for a ##FORMAT
      // declaration ID (see FormatMetadata.init()) -- a data-line FORMAT key failing it must not abort the whole
      // parse (findings37.md item 1; confirmed against bcftools 1.23.1, which only warns on this input).
      if (!InternalUtils.FORMAT_PATTERN.matcher(f).matches()) {
        if (warnings == null) {
          throw new VcfFormatException("FORMAT ID \"" + f + "\" does not match the required tag-name grammar " +
              "(must start with a letter or underscore, followed only by letters, digits, underscores, or periods)");
        }
        warnings.warn("FORMAT ID \"{}\" does not match the required tag-name grammar (must start with a letter " +
            "or underscore, followed only by letters, digits, underscores, or periods); keeping it as-is", f);
        continue;
      }
      if (isDuplicateAt(format, i, seen)) {
        throw new VcfFormatException("Duplicate FORMAT key \"" + f + "\"");
      }
    }
    // VCFv4.2.tex line 211: "The first sub-field must always be the genotype (GT) if it is present." Restated
    // standalone at VCFv4.3.tex line 431 ("The first key must always be the genotype (GT) if it is present.").
    if (format.indexOf("GT") > 0) {
      throw new VcfFormatException("FORMAT GT must be the first sub-field when present");
    }
    return format;
  }

  /**
   * Normalizes a lone {@code "."} in {@link #m_filter} to an empty list ({@link FilterStatus#NONE}). A lone
   * {@code "PASS"} needs no normalization at all -- it is already the sole representation of
   * {@link FilterStatus#PASSED} exactly as given, and {@link #getFilters()} returns an unmodifiable view
   * regardless of whether the list backing it is the caller's own original object, so (unlike before
   * {@code VcfPosition} was frozen) there is no remaining reason to copy it into an independently-mutable list.
   * Assumes {@link #checkFilters} has already validated {@link #m_filter}. Idempotent: a no-op once
   * {@link #m_filter} is no longer the single-element list {@code ["."]}.
   */
  private void normalizeFilters() {
    if (m_filter.size() == 1 && m_filter.get(0).equals(".")) {
      m_filter = new ArrayList<>();
    }
  }

  /**
   * Re-validates this position's current field values, throwing {@link VcfFormatException} if any are no longer valid.
   * As a side effect (matching the constructor), normalizes a lone {@code "."} FILTER value so that
   * {@link #getFilterStatus()} continues to report correctly.
   * <p>
   * {@link VcfPosition} has no public mutation route for its ID/ALT/FILTER/FORMAT/INFO-multimap fields -- every
   * value reaching those fields via {@link VcfContext#newPosition} or {@link Builder#build()} (which re-runs
   * exactly the checks below for any field a setter marked dirty since the builder was seeded; see its own
   * Javadoc) is already validated by the time either returns. <b>QUAL and INFO are the one exception, and this
   * method remains genuinely load-bearing because of it</b>: {@link Builder#rawQuality}/{@link Builder#rawInfo}
   * (and the matching {@code rawQual}/{@code rawInfo} parameters {@code VcfContext.newPositionTakingOwnership}/
   * {@code newPositionWithTrustedFormat} accept) attach raw, not-yet-parsed text without validating it at
   * construction or build time at all -- that is deferred to first access ({@link #getQuality()}/
   * {@link #getInfo()}) or to an explicit call to this method. A position built this way can therefore still
   * hold invalid raw QUAL/INFO text after {@link Builder#build()} returns, and this method is the only way to
   * surface that without also triggering the ordinary lazy-access warning path. {@code VcfWriter}'s
   * {@code validateBeforeWrite()} relies on exactly this, calling it on every position before writing -- do not
   * remove or bypass this method, and do not assume every field it checks is already guaranteed valid the way
   * ID/ALT/FILTER/FORMAT/INFO-multimap are.
   * <p>
   * {@link #getFilterStatus()} is always derived directly from {@link #getFilters()}'s current contents (see its
   * Javadoc); {@link FilterStatus#PASSED} is always an explicit opt-in -- construct or rebuild (via
   * {@link #toBuilder()}) with the single-element FILTER list {@code ["PASS"]} to reach it.
   * <p>
   * The whole method body is wrapped in a single guarded catch rather than annotating individual checks:
   * {@link #m_lineNumber} identifies which line this record came from, not which line a specific value came from,
   * so any validation failure on this record gets the same line-number attachment.
   */
  public void validate() {
    // Clear every memoized getter view BEFORE running the checks below, not after: each check may reassign the
    // field it validates to a new object (e.g. checkIds drops an empty entry into a new list), which would leave
    // a cache still wrapping the OLD object if cleared only on success. Clearing first means a check that throws
    // partway through still leaves no stale cache behind -- the cost is that a getter called after a caught
    // exception rebuilds its view from whatever the fields ended up at, which is correct, not stale, either way.
    m_idsView = null;
    m_altBasesView = null;
    m_filtersView = null;
    m_formatView = null;
    m_infoView = null;
    m_infoKeysView = null;
    try {
      checkChromosome(m_chromosome);
      checkPosition(m_position);
      m_ids = checkIds(m_ids, m_warnings);
      checkRef(m_refBases);
      m_altBases = checkAltBases(m_altBases, m_warnings);
      m_filter = checkFilters(m_filter, m_warnings);
      normalizeFilters();
      m_info = checkInfoEntries(info(), m_warnings);
      m_format = checkFormat(m_format, m_warnings);
    } catch (VcfFormatException ex) {
      if (m_lineNumber >= 0) {
        ex.addMetadata(m_lineNumber, "data");
      }
      throw ex;
    }
  }

  /**
   * Gets an identifier from the reference genome or an angle-bracketed ID String ("{@code <ID>}") pointing to a contig
   * in the assembly file.
   */
  public String getChromosome() {
    return m_chromosome;
  }

  public long getPosition() {
    return m_position;
  }

  /**
   * Gets the list of unique identifiers for this position.
   */
  public List<String> getIds() {
    List<String> view = m_idsView;
    if (view == null) {
      view = Collections.unmodifiableList(m_ids);
      m_idsView = view;
    }
    return view;
  }

  /**
   * Gets the reference base(s) for this position.  Each base must be an A, C, G, T, or N.
   */
  public String getRef() {
    return m_refBases;
  }

  /**
   * Gets the alternate base(s) for this position.  Each base must be an A, C, G, T, N or * unless it's an
   * angle-bracketed ID string ("{@code <ID>}").
   * <p>
   * ID strings should reference a specific ALT metadata (obtainable via {@link VcfMetadata#getAlt(java.lang.String)}).
   * </p>
   */
  public List<String> getAltBases() {
    List<String> view = m_altBasesView;
    if (view == null) {
      view = Collections.unmodifiableList(m_altBases);
      m_altBasesView = view;
    }
    return view;
  }

  /**
   * Gets the allele at the given index from a list of containing refBases + altBases.
   *
   * @throws IndexOutOfBoundsException if index is out of range
   */
  public String getAllele(int index) {
    if (index == 0) {
      return m_refBases;
    }
    return m_altBases.get(index - 1);
  }


  public @Nullable BigDecimal getQuality() {
    if (m_quality == null && m_rawQuality != null) {
      String raw = m_rawQuality;
      m_rawQuality = null;
      if (!raw.isEmpty() && !raw.equals(".")) {
        try {
          // QUAL is VCF-spec-typed Float; VcfFloat.parseDecimal shares the same grammar check (and warning)
          // VcfFloat.parse() uses, so this class doesn't need its own copy of FLOAT_PATTERN -- only the
          // unparseable-input handling differs (warn-and-fall-back-to-missing here, vs. VcfFormatException
          // in VcfFloat.parse()), which is exactly why that's a bare NumberFormatException below, not thrown
          // from inside parseDecimal itself
          m_quality = VcfFloat.parseDecimal("QUAL", raw, m_warnings);
        } catch (NumberFormatException e) {
          // a non-numeric QUAL is a value-quality issue, not a structural one (the column itself is well-formed);
          // warn and recover as the missing value, consistent with how other non-structural content issues are
          // handled, rather than throwing
          m_warnings.warn("QUAL '{}' is not a number; treating it as the missing value", raw);
        }
      }
    }
    return m_quality;
  }

  /**
   * Returns the FILTER status for this position, derived purely from {@link #getFilters()}'s current contents:
   * <ul>
   *   <li>{@link FilterStatus#NONE} if the list is empty -- no filter information was recorded (FILTER was the
   *       missing value "{@code .}", or no FILTER argument was given at construction)</li>
   *   <li>{@link FilterStatus#PASSED} if the list is exactly the single element {@code "PASS"}</li>
   *   <li>{@link FilterStatus#FAILED} for any other non-empty list</li>
   * </ul>
   * {@code PASSED} is always an explicit opt-in -- it is never the default for an empty or unspecified list. This
   * matches {@code bcftools annotate -x FILTER} (bcftools 1.23.1), which converts every FILTER value (including an
   * existing {@code PASS} or a real failing code) to {@code "."} when clearing it, never to {@code "PASS"} --
   * verified live: a fixture with FILTER values {@code "."}, {@code "PASS"}, and a real filter code all become
   * {@code "."} after {@code bcftools annotate -x FILTER}. A literal empty FILTER string is likewise never
   * silently promoted to {@code "PASS"} by bcftools -- confirmed only for an <em>interior</em> empty entry
   * (e.g. {@code "q10;;q20"}), where bcftools warns it is an undeclared filter name; a <em>trailing</em> empty
   * entry (e.g. {@code "q10;"}) is silently dropped by bcftools instead, per {@code EMPTY_FIELD_HANDLING.md}.
   * This library's own FILTER handling does not distinguish the two: any empty entry, interior or trailing, is
   * warned about and dropped uniformly, so a FILTER column that reduces to nothing after dropping always
   * resolves to {@link FilterStatus#NONE} here, never a FAILED-like "undefined filter name" state.
   */
  public FilterStatus getFilterStatus() {
    if (m_filter.isEmpty()) {
      return FilterStatus.NONE;
    }
    if (m_filter.size() == 1 && m_filter.get(0).equals("PASS")) {
      return FilterStatus.PASSED;
    }
    return FilterStatus.FAILED;
  }

  /**
   * Returns true if this position did not fail any filters. Note that this is true for both {@link FilterStatus#PASSED}
   * and {@link FilterStatus#NONE} (no filters applied is treated as passing); use {@link #getFilterStatus()} to
   * distinguish those two cases.
   */
  public boolean isPassingAllFilters() {
    return getFilterStatus() != FilterStatus.FAILED;
  }

  /**
   * Returns this position's FILTER contents: empty for {@link FilterStatus#NONE}, the single-element list
   * {@code ["PASS"]} for {@link FilterStatus#PASSED}, or the actual failing filter code(s) for
   * {@link FilterStatus#FAILED}. Use {@link #getFilterStatus()} to interpret this without re-deriving it by hand.
   */
  public List<String> getFilters() {
    List<String> view = m_filtersView;
    if (view == null) {
      view = Collections.unmodifiableList(m_filter);
      m_filtersView = view;
    }
    return view;
  }

  /**
   * Returns the INFO multimap, parsing (and validating) the raw INFO text on first access.
   */
  private ListMultimap<String, String> info() {
    ListMultimap<String, String> info = m_info;
    if (info == null) {
      info = LinkedListMultimap.create();
      String raw = m_rawInfo;
      if (raw != null && !raw.isEmpty() && !raw.equals(".")) {
        // limit -1 preserves an empty prop/value (e.g. a trailing ';' or ','), handled below rather than silently
        // dropped; VCF does not allow zero-length fields. See EMPTY_FIELD_HANDLING.md.
        List<String> props = new ArrayList<>();
        for (String prop : raw.split(";", -1)) {
          if (prop.isEmpty()) {
            m_warnings.warn("INFO contains an empty entry between ';'s (VCF does not allow zero-length fields); " +
                "dropping it");
            continue;
          }
          props.add(prop);
        }
        // "." (missing value: no INFO at all) cannot be combined with a real property, the same as ALT/FILTER's
        // exclusivity rule for their own "." sentinel; unlike an empty prop (dropped above), this isn't a stray
        // delimiter artifact, it directly contradicts "." meaning "nothing here"
        if (props.contains(".") && props.size() > 1) {
          VcfFormatException ex = new VcfFormatException(
              "INFO contains '.' (missing value) along with other properties");
          if (m_lineNumber >= 0) {
            ex.addMetadata(m_lineNumber, "data");
          }
          throw ex;
        }
        // VCFv4.2 is silent on whether a top-level INFO key may appear more than once, but VCFv4.3 explicitly
        // states "Duplicate keys are not allowed" for INFO (mirroring the same rule for FORMAT keys). bcftools
        // 1.23.1 does not reject this, though: verified live, "DP=1;DP=2" is accepted with no warning, and
        // `bcftools query -f '%INFO/DP'` returns "1", not "1,2" -- bcftools keeps only the first occurrence rather
        // than merging duplicates into a list. This parser matches that bcftools behavior (warn and keep the
        // first occurrence) instead of throwing, consistent with this project's policy of not being stricter than
        // bcftools on input it demonstrably tolerates (see EMPTY_FIELD_HANDLING.md), and instead of silently
        // merging duplicates into what would look like a legitimate multi-value list.
        Set<String> seenKeys = new HashSet<>();
        for (String prop : props) {
          if (prop.equals(".")) {
            // reaching here without having thrown above means "." is the only entry left once delimiter-artifact
            // empties are dropped (e.g. raw was ".;"); that's the same "no INFO at all" sentinel as a bare ".", not
            // a real property named "."
            continue;
          }
          int idx = prop.indexOf('=');
          String key = idx == -1 ? prop : prop.substring(0, idx);
          if (idx != -1 && key.isEmpty()) {
            m_warnings.warn("INFO entry \"{}\" has an empty key (VCF does not allow zero-length fields); " +
                "dropping it", prop);
            continue;
          }
          if (!seenKeys.add(key)) {
            m_warnings.warn("INFO contains duplicate key \"{}\"; keeping only the first occurrence, matching " +
                "bcftools 1.23.1 behavior", key);
            continue;
          }
          if (idx == -1) {
            info.put(key, "");
          } else {
            for (String value : prop.substring(idx + 1).split(",", -1)) {
              if (value.isEmpty()) {
                m_warnings.warn("INFO value for key \"{}\" contains an empty entry (VCF does not allow zero-length " +
                    "fields); treating it as the missing value '.'", key);
                value = ".";
              }
              info.put(key, value);
            }
          }
        }
        try {
          info = checkInfoEntries(info, m_warnings);
        } catch (VcfFormatException ex) {
          if (m_lineNumber >= 0) {
            ex.addMetadata(m_lineNumber, "data");
          }
          throw ex;
        }
      }
      m_info = info;
      m_rawInfo = null;
    }
    return info;
  }

  /**
   * Gets all INFO fields for every key.
   */
  public ListMultimap<String, String> getInfo() {
    ListMultimap<String, String> view = m_infoView;
    if (view == null) {
      view = Multimaps.unmodifiableListMultimap(info());
      m_infoView = view;
    }
    return view;
  }

  /**
   * Get INFO metadata with the specified ID.
   *
   * @return list of values or null if there is no INFO metadata for the specified id
   */
  public @Nullable List<String> getInfo(String id) {
    // reuses the same memoized, already-unmodifiable view getInfo() caches -- Multimaps.unmodifiableListMultimap
    // wraps get(key) too, so there is no need for this method's own Collections.unmodifiableList(...) wrapper on
    // top of it
    ListMultimap<String, String> info = getInfo();
    if (info.containsKey(id)) {
      return info.get(id);
    }
    return null;
  }

  /**
   * Returns the value for the reserved property as the type specified by both {@link ReservedInfoProperty#getType()}
   * and {@link ReservedInfoProperty#isList()}.
   * <p>
   * <em>Note that this method does NOT always return a list.</em>
   * <p>
   * For example:
   * <pre>{@code
   *   VcfFloat bq = vcfPosition.getInfo(ReservedInfoProperty.BaseQuality);
   * }
   * </pre>
   *
   * @param <T> The type specified by {@code ReservedInfoProperty.getType()} if {@code ReservedInfoProperty.isList()}
   *           is false;
   *           otherwise {@code List<V>} where V is the type specified by {@code ReservedInfoProperty.getType()}.
   */
  public @Nullable <T> T getInfo(ReservedInfoProperty key) {
    // matches getInfo(String)'s own null-tolerant "not present" contract (see hasInfo(ReservedInfoProperty)
    // below for the same pattern) -- returning null here also avoids ever reaching
    // InternalUtils.convertProperty(null, ...); this method guards against a null key itself rather than
    // relying on convertProperty to reject it
    if (key == null) {
      return null;
    }
    List<String> list = info().get(key.getId());
    if (list.isEmpty()) {
      return null;
    }
    // info() unconditionally comma-splits every raw INFO value regardless of the key's declared Number/isList
    // (it has no access to that metadata) -- for a scalar key (isList=false), more than one split piece is
    // unambiguously a Number=1/0 cardinality violation, since VCF's comma is the multi-value separator. Naively
    // rejoining those pieces with "," before Type conversion (the pre-fix behavior) silently discarded that
    // violation for a String-typed key like AA (a joined "1,23" round-trips as one opaque string) and crashed
    // for a numeric/Boolean-typed key like DP (Long.parseLong("1,23") throws), even though this INFO field is
    // supposed to warn-and-recover rather than throw for a non-structural problem (see AGENTS.md's Parser
    // Validation Policy). Warn and fall back to the first piece -- the same "keep the first, warn about the
    // rest" convention this library already uses for a duplicate metadata ID and a duplicate INFO key.
    String value;
    if (!key.isList() && list.size() > 1) {
      // getExpectedNumber() is always the literal "0" or "1" for a scalar (isList=false) key -- unlike
      // VcfWriter.warnIfWrongCardinality's Number=A/G/R cases, there's no combinatorial expected count to compute
      // here, so the same string fills both message slots
      m_warnings.warn("INFO {} has {} value(s), but Number={} requires {}", key.getId(), list.size(),
          key.getExpectedNumber(), key.getExpectedNumber());
      value = list.get(0);
    } else {
      // for a list-typed key, check the actual element count against what getExpectedNumber() implies for this
      // record's own ALT allele count (the INFO-side counterpart of VcfSample.getProperty(VcfPosition,
      // VcfMetadata, String)'s identical FORMAT-side check) -- e.g. AC/AF (Number=A) must have exactly one value
      // per ALT allele, CIPOS/CIEND (Number=2) exactly two. No reserved INFO key declares Number=G, so ploidy
      // (irrelevant for INFO, which has no per-sample concept) is always null here. Skipped when list is the
      // single-element "." sentinel: that represents this key's value being entirely absent (e.g. an INFO
      // string of "AD=" normalizes to a single "." entry, per EMPTY_FIELD_HANDLING.md), not a real value whose
      // count could violate cardinality.
      if (key.isList() && !(list.size() == 1 && list.get(0).equals("."))) {
        InternalUtils.warnIfWrongCardinality("INFO " + key.getId(), key.getExpectedNumber(), list.size(),
            getAltBases().size(), null, m_warnings);
      }
      value = sf_commaJoiner.join(list);
    }
    try {
      return InternalUtils.convertProperty(key, value, m_warnings);
    } catch (VcfFormatException ex) {
      if (m_lineNumber >= 0) {
        ex.addMetadata(m_lineNumber, "data");
      }
      throw ex;
    }
  }

  /**
   * Checks if there is INFO metadata with the specified ID.
   */
  public boolean hasInfo(String id) {
    return info().containsKey(id);
  }

  /**
   * Checks if there is INFO metadata with the specified ID.
   */
  public boolean hasInfo(ReservedInfoProperty key) {
    // null-tolerant, matching hasInfo(String)'s own null-tolerant multimap lookup (LinkedListMultimap.containsKey
    // never throws for a null key)
    return key != null && hasInfo(key.getId());
  }

  public List<String> getFormat() {
    List<String> view = m_formatView;
    if (view == null) {
      view = Collections.unmodifiableList(m_format);
      m_formatView = view;
    }
    return view;
  }

  public Set<String> getInfoKeys() {
    Set<String> view = m_infoKeysView;
    if (view == null) {
      view = Collections.unmodifiableSet(info().keySet());
      m_infoKeysView = view;
    }
    return view;
  }

  /**
   * The FILTER status of a position, per the VCF spec. See {@link VcfPosition#getFilterStatus()}.
   */
  public enum FilterStatus {
    /** No filter information was recorded -- FILTER was the missing value "{@code .}", was never specified at
     * construction, or was cleared via mutation. */
    NONE,
    /** The position passed all filters (FILTER was "{@code PASS}"). */
    PASSED,
    /** The position failed one or more filters. */
    FAILED
  }


  /**
   * A copy-and-modify builder for {@link VcfPosition}, obtained via {@link #toBuilder()}. Every setter that
   * takes a mutable {@link List}/{@link ListMultimap} defensively copies it on the way in -- a caller mutating a
   * list handed to a setter, or a list obtained from the source position via {@link #toBuilder()}, must never be
   * able to reach back into a position {@link #build()} produces.
   * <p>
   * This instance is reusable: calling {@link #build()} more than once returns independent positions that share
   * no mutable state with each other or with this builder -- {@link #build()} always copies (see its own
   * Javadoc), so a caller mutating one built position, or this builder's own retained fields, can never affect
   * another position built from the same builder.
   * <p>
   * {@code ids}/{@code altBases}/{@code filter}/{@code info}/{@code format} are each re-validated by
   * {@link #build()} only if the corresponding public setter was called on this instance -- {@link #toBuilder()}
   * seeds these five fields through private, non-dirtying {@code seedXxx} methods instead, so a field copied
   * unchanged from an already-valid (or already-warned-about) source is never re-checked, and copying alone
   * never re-emits a diagnostic the source already emitted once. A dirty field is re-validated by directly
   * re-running the exact same {@code checkXxx} method the constructor itself would have run, so the resulting
   * position is validated identically to one built fresh via {@link VcfContext#newPosition}.
   */
  public static class Builder {
    private @Nullable String m_chromosome;
    private long m_position;
    private @Nullable List<String> m_ids;
    private boolean m_idsDirty;
    private @Nullable String m_ref;
    private @Nullable List<String> m_altBases;
    private boolean m_altBasesDirty;
    private @Nullable BigDecimal m_quality;
    private @Nullable String m_rawQuality;
    private @Nullable List<String> m_filter;
    private boolean m_filterDirty;
    private @Nullable ListMultimap<String, String> m_info;
    private boolean m_infoDirty;
    private @Nullable String m_rawInfo;
    private @Nullable List<String> m_format;
    private boolean m_formatDirty;
    private int m_lineNumber = -1;
    private final VcfContext m_context;

    Builder(VcfContext context) {
      m_context = Preconditions.checkNotNull(context, "context must not be null");
    }

    public Builder chromosome(String chromosome) {
      // chromosome is typed non-@Nullable, but nothing enforces that at runtime; reject immediately per
      // AGENTS.md's Public API Null-Safety Policy rather than letting build() surface a less specific failure
      if (chromosome == null) {
        throw new VcfFormatException("CHROM is null");
      }
      m_chromosome = chromosome;
      return this;
    }

    public Builder position(long position) {
      m_position = position;
      return this;
    }

    /** Defensively copies {@code ids}; see this class's own Javadoc for why. Marks {@code ids} dirty for
     * {@link #build()} to re-validate. */
    public Builder ids(@Nullable List<String> ids) {
      seedIds(ids);
      m_idsDirty = true;
      return this;
    }

    /** Seeds {@code ids} from an already-validated source without marking it dirty; see this class's own
     * Javadoc. Still defensively copies -- called on every {@link #toBuilder()} regardless of whether the field
     * ends up dirty. */
    private void seedIds(@Nullable List<String> ids) {
      m_ids = ids == null ? null : new ArrayList<>(ids);
    }

    public Builder ref(String ref) {
      // ref is typed non-@Nullable, but nothing enforces that at runtime; see chromosome(String)'s identical
      // reasoning above
      if (ref == null) {
        throw new VcfFormatException("REF is null");
      }
      m_ref = ref;
      return this;
    }

    /** Defensively copies {@code altBases}; see this class's own Javadoc for why. Marks {@code altBases} dirty
     * for {@link #build()} to re-validate. */
    public Builder altBases(@Nullable List<String> altBases) {
      seedAltBases(altBases);
      m_altBasesDirty = true;
      return this;
    }

    /** See {@link #seedIds}'s identical reasoning, for {@code altBases}. */
    private void seedAltBases(@Nullable List<String> altBases) {
      m_altBases = altBases == null ? null : new ArrayList<>(altBases);
    }

    /** Sets a parsed QUAL value, clearing any raw QUAL text set via {@link #rawQuality}. QUAL has no
     * warn-and-recover validation at construction (only lazy parsing on first {@link #getQuality()}), so it has
     * no dirty flag to set -- there is nothing for {@link #build()} to skip or re-run either way. */
    public Builder quality(@Nullable BigDecimal quality) {
      m_quality = quality;
      m_rawQuality = null;
      return this;
    }

    /**
     * Sets raw, not-yet-parsed QUAL column text, to be parsed lazily on the built position's first
     * {@link #getQuality()} -- clears any parsed value set via {@link #quality}. For a caller rebuilding a
     * position via {@link VcfPosition#toBuilder()} that wants to preserve lazy QUAL parsing. {@code VcfParser}'s
     * own per-record construction does not go through this builder at all -- it attaches raw QUAL/INFO directly
     * via {@code VcfContext.newPositionTakingOwnership}/{@code newPositionWithTrustedFormat}'s own
     * {@code rawQual}/{@code rawInfo} parameters, to avoid this builder's per-record allocation cost.
     */
    public Builder rawQuality(@Nullable String rawQuality) {
      m_rawQuality = rawQuality;
      m_quality = null;
      return this;
    }

    /** Defensively copies {@code filters}; see this class's own Javadoc for why. Marks {@code filter} dirty for
     * {@link #build()} to re-validate. */
    public Builder filters(@Nullable List<String> filters) {
      seedFilters(filters);
      m_filterDirty = true;
      return this;
    }

    /** See {@link #seedIds}'s identical reasoning, for {@code filter}. */
    private void seedFilters(@Nullable List<String> filters) {
      m_filter = filters == null ? null : new ArrayList<>(filters);
    }

    /** Sets a parsed INFO multimap, clearing any raw INFO text set via {@link #rawInfo}. Defensively copies
     * {@code info}; see this class's own Javadoc for why. Marks {@code info} dirty for {@link #build()} to
     * re-validate. */
    public Builder info(@Nullable ListMultimap<String, String> info) {
      seedInfo(info);
      m_rawInfo = null;
      m_infoDirty = true;
      return this;
    }

    /** See {@link #seedIds}'s identical reasoning, for {@code info}. Unlike {@link #info}, does not touch
     * {@link #m_rawInfo} -- {@link #toBuilder()} only calls this when the source's own raw INFO was already
     * null (its "at most one of parsed/raw" invariant), so there is nothing to clear. */
    private void seedInfo(@Nullable ListMultimap<String, String> info) {
      m_info = info == null ? null : LinkedListMultimap.create(info);
    }

    /**
     * Sets raw, not-yet-parsed INFO column text, to be parsed lazily on the built position's first
     * {@link #getInfo()} -- clears any parsed value set via {@link #info}. See {@link #rawQuality}'s identical
     * reasoning for why {@code VcfParser} does not use this method either.
     */
    public Builder rawInfo(@Nullable String rawInfo) {
      m_rawInfo = rawInfo;
      m_info = null;
      return this;
    }

    /** Defensively copies {@code format}; see this class's own Javadoc for why. Marks {@code format} dirty for
     * {@link #build()} to re-validate. */
    public Builder format(@Nullable List<String> format) {
      seedFormat(format);
      m_formatDirty = true;
      return this;
    }

    /** See {@link #seedIds}'s identical reasoning, for {@code format}. */
    private void seedFormat(@Nullable List<String> format) {
      m_format = format == null ? null : new ArrayList<>(format);
    }

    public Builder lineNumber(int lineNumber) {
      m_lineNumber = lineNumber;
      return this;
    }

    /**
     * @return a new {@link VcfPosition}. First, re-runs {@link VcfPosition}'s own {@code checkXxx} validation
     * directly (this class is nested inside {@link VcfPosition}, so it can call those {@code private static}
     * methods the same way {@link VcfPosition}'s own constructor does) for exactly the fields a setter marked
     * dirty since this builder was seeded -- an unchanged field is left exactly as seeded, already known valid.
     * Then delegates to the package-private {@code VcfContext.newPositionCopyingTrustedFields}, which both
     * copies every list/multimap field (so this reusable builder's own retained references, and any previously
     * built position's fields, are never aliased into the new one -- see this class's own Javadoc) and skips
     * re-running that same validation a second time inside the constructor, since it was just run here (or
     * correctly skipped here) instead.
     */
    public VcfPosition build() {
      VcfWarnings checkWarnings = m_context.isLenient() ? m_context.getWarnings() : null;
      if (m_idsDirty && m_ids != null) {
        m_ids = checkIds(m_ids, checkWarnings);
      }
      if (m_altBasesDirty && m_altBases != null) {
        m_altBases = checkAltBases(m_altBases, checkWarnings);
      }
      if (m_filterDirty && m_filter != null) {
        m_filter = checkFilters(m_filter, checkWarnings);
      }
      if (m_infoDirty && m_info != null) {
        m_info = checkInfoEntries(m_info, checkWarnings);
      }
      if (m_formatDirty && m_format != null) {
        m_format = checkFormat(m_format, checkWarnings);
      }
      // This factory's "always copies, so two build() calls (or build() and this builder's own retained fields)
      // never alias each other" contract is no longer independently pinned by a dedicated test: Task 8 made
      // every getter return an unmodifiable view, which removed the only way a test had to make sharing
      // externally observable (mutate one result, check the other is unaffected) -- see task-8-report.md's
      // Group B. The contract itself is unchanged and still enforced by this call; a regression here would
      // currently surface only via internal/reflective inspection, not a public-API-level test failure.
      return m_context.newPositionCopyingTrustedFields(m_chromosome, m_position, m_ids, m_ref, m_altBases,
          m_quality, m_filter, m_info, m_format, m_lineNumber, m_rawQuality, m_rawInfo);
    }
  }
}

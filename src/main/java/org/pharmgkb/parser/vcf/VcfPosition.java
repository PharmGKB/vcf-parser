package org.pharmgkb.parser.vcf;

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
 * friends parse lazily on first access, writing {@link #m_quality}/{@link #m_info} the first time either is
 * actually read. Neither {@link #m_rawQuality} nor {@link #m_rawInfo} (the whole unsplit INFO text) is ever
 * cleared once set -- both remain permanently available via {@link #getRawQuality()}/{@link #getRawInfo()}.
 * That caching has no observable
 * effect on a single-threaded caller, but it is unsynchronized, so <b>this class is not thread-safe</b>:
 * sharing one instance across threads before its QUAL/INFO have both been read at least once is not safe
 * without external synchronization. A caller that needs a changed position derives a new one via
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
  // QUAL is stored either as a parsed VcfFloat (eager path) or as raw text parsed lazily on first getQuality()
  // (parser path via this class's own constructor rawQual parameter); many consumers never read QUAL.
  // m_rawQuality is never nulled out once set -- it remains permanently available via getRawQuality(), the same
  // way INFO's own per-key raw string values remain permanently available via getInfo(String) regardless of
  // whether the reserved-key typed getter has been called, and the same way m_rawInfo below remains permanently
  // available via getRawInfo().
  private @Nullable VcfFloat m_quality;
  private @Nullable String m_rawQuality;
  // true once getQuality()'s lazy parse has produced a final (cacheable) outcome -- success, or "nothing to
  // parse" (missing/"."). Deliberately NOT set when getQuality() throws for an unparseable value: see that
  // method's own Javadoc for why that specific outcome re-fires on every call instead of being cached.
  private boolean m_qualityResolved;
  private List<String> m_filter = new ArrayList<>();
  // INFO is stored either as a parsed multimap (eager constructor path) or as raw text parsed lazily on first
  // access (parser path via this class's own constructor rawInfo parameter). m_rawInfo is never nulled out once
  // set -- like m_rawQuality above, it remains permanently available via getRawInfo() -- so the two can both be
  // non-null at once, once info() has parsed the raw text into m_info, mirroring m_quality/m_rawQuality's
  // identical documented shape; see the constructor's own rawInfo Javadoc.
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
   * {@link VcfContext#newPosition(String, long, List, String, List, VcfFloat, List, ListMultimap, List, int)}
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
   * @param originatedPublicly {@code true} only from {@link VcfContext#newPosition}'s public factories --
   * {@code false} from every parser-trusted factory ({@code newPositionTakingOwnership}/
   * {@code newPositionWithTrustedFormat}/{@code newPositionCopyingTrustedFields}). See the comment inside this
   * constructor's own body for why this is a narrow, deliberate exception to the "never from which factory"
   * principle stated there.
   * @param rawQual raw, not-yet-parsed QUAL column text to store as {@link #m_rawQuality}, parsed lazily on first
   * {@link #getQuality()}, when {@code qual} is {@code null} -- lets a caller construct a lazily-parsed position
   * in one call instead of a separate post-construction mutation. Ignored (and never stored) when {@code qual}
   * is non-null -- {@link #m_rawQuality} and {@link #m_quality} are never both non-null immediately after
   * construction, though a subsequent successful {@link #getQuality()} call sets {@link #m_quality} without
   * clearing {@link #m_rawQuality} (see that method's own Javadoc), so the two can both be non-null afterward.
   * @param rawInfo the {@code rawQual} parameter's exact counterpart for INFO.
   */
  VcfPosition(VcfContext context, String chr, long pos,
      @Nullable List<String> ids,
      String ref,
      @Nullable List<String> altBases,
      @Nullable VcfFloat qual,
      @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info,
      @Nullable List<String> format,
      int lineNumber,
      boolean trustedFormat,
      boolean trustedFields,
      boolean originatedPublicly,
      @Nullable String rawQual,
      @Nullable String rawInfo) {

    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_warnings = context.getWarnings();

    /*
      1. Check the arguments, in order. `checkWarnings` is null under a strict context -- every check method
      below throws instead of warning when it receives null; see each one's own signature. That null-means-strict
      encoding is now purely an internal convention between this class and its own (and InternalUtils's) private
      check helpers: the strict-vs-lenient DECISION comes from the context, never from which constructor or
      factory the caller reached. `originatedPublicly` is a deliberate, separate exception to that principle,
      not a contradiction of it: it does not affect the warn-vs-throw decision for any EXISTING check (all of
      which still answer only to checkWarnings/isLenient()), and gates only the NEW bcftools-interoperability-
      floor checks inside checkFilters/checkInfoEntries/checkFormat (a comma in a key, or too many FORMAT
      keys) that must apply regardless of leniency for genuinely public construction, yet must never apply to
      the parser's own trusted factories -- unlike every other check here, a real parsed file can legitimately
      contain either shape today, so making these unconditional across every factory the way e.g. checkFilters'
      own semicolon check is would reject re-parsing/re-emitting a file that parses successfully today. See
      VCF_SPEC_AMBIGUITIES.md's "Comma in a data-line FILTER code, INFO key, or FORMAT key" and "More than 255
      FORMAT keys" entries.
     */
    VcfWarnings checkWarnings = context.isLenient() ? context.getWarnings() : null;

    checkChromosome(chr, checkWarnings);
    checkPosition(pos);
    if (ids != null && !trustedFields) {
      ids = checkIds(ids, checkWarnings);
    }
    checkRef(ref);
    if (altBases != null && !trustedFields) {
      altBases = checkAltBases(altBases, ref, checkWarnings);
    }
    if (filter != null && !trustedFields) {
      filter = checkFilters(filter, checkWarnings, originatedPublicly);
    }
    if (info != null && !trustedFields) {
      info = checkInfoEntries(info, checkWarnings, originatedPublicly);
      checkReservedInfoTypes(info, context);
    }
    if (format != null && !trustedFormat) {
      format = checkFormat(format, checkWarnings, originatedPublicly);
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
      // Unconditional, matching every other column's own tab/CR/LF check (CHROM/POS/REF/ALT/FILTER/FORMAT
      // keys) -- an embedded line terminator is a structural violation regardless of which column it's in.
      // QUAL's own value-parsing (VcfFloat) is deferred to first getQuality() call for performance (many
      // consumers never read QUAL), but that deferral must not also defer THIS check: unlike a malformed-
      // but-otherwise-ordinary QUAL value, a raw CR/LF surviving unchecked into m_rawQuality reaches
      // VcfWriter's default write path completely unvalidated (that path writes rawQuality verbatim and only
      // calls getQuality() under validateBeforeWrite() -- see VcfWriter.writeLine's own comment), and crashes
      // VcfWriter.printLine's own defense-in-depth check later instead, producing a truncated output file for
      // a record the parser accepted with zero warnings -- a round-trip violation of AGENTS.md's Writer
      // Validation and Round-Trip Policy. Found by the Phase 2 review's third re-verification round, the same
      // shape as VcfSample's own newTrustedSample-skips-the-line-terminator-check gap (see that class's
      // trusted-constructor Javadoc).
      InternalUtils.checkNoLineTerminator("QUAL", rawQual);
      // checkNoLineTerminator above only catches \n/\r -- a tab is not a line terminator, but is exactly as
      // structural here: VcfWriter's default write path writes m_rawQuality verbatim into the QUAL column, so
      // an embedded tab would add a spurious extra column, shifting every field after QUAL out of alignment on
      // write, completely unvalidated (the same class of gap the \n/\r check above closes, and the same
      // rationale InternalUtils.containsWhitespaceOtherThanSpace's own Javadoc gives for rejecting a tab in an
      // INFO value). Only reachable through direct construction (Builder.rawQuality) -- a real parsed file's
      // QUAL column can never itself contain a tab, since VcfParser's own column split already excludes it.
      // Found by the Phase 3 writer-compliance review's Path A kickoff (confirmed live against installed
      // bcftools 1.23.1: given "1\t100\t.\tA\tT\t10\t20\tPASS\t.", a raw tab-corrupted QUAL="10\t20" is
      // written straight through, and bcftools reads FILTER="20"/INFO="PASS" -- exit 0, only warnings about
      // the resulting undefined FILTER/INFO values, no diagnostic naming the actual column shift at all --
      // see BcftoolsDependencyTest#testTabCorruptedQualBcftoolsMisreadsFilterAndInfo and
      // BCFTOOLS_VERSION_DEPENDENCIES.md's matching entry).
      if (rawQual.contains("\t")) {
        throw new VcfFormatException("QUAL text \"" + InternalUtils.truncateAndEscapeForMessage(rawQual) +
            "\" contains a tab");
      }
      m_rawQuality = rawQual;
    }
    if (info == null && rawInfo != null) {
      // Unconditional, matching the rawQual check just above -- see that check's own comment for the full
      // rationale, which applies identically here: INFO's own value-parsing (checkInfoEntries) is deferred to
      // first getInfo()/info() call for performance, but that deferral must not also defer this check, since
      // an unchecked CR reaches VcfWriter's default write path unvalidated (that path writes each INFO
      // key/value pair from rawInfo's own split pieces, never re-scanning the whole raw text for a line
      // terminator) and crashes VcfWriter.printLine's own defense-in-depth check later, producing a truncated
      // output file for a record the parser accepted with zero warnings. Found by the Phase 2 review's fourth
      // re-verification round: the QUAL fix above (third round) closed this identical gap for QUAL but missed
      // its own INFO sibling.
      InternalUtils.checkNoLineTerminator("INFO", rawInfo);
      // Same reasoning as the rawQual tab check above: a tab embedded anywhere in this not-yet-parsed INFO
      // text would add a spurious column when VcfWriter's default write path writes rawInfo's split pieces
      // verbatim, before checkInfoEntries's own per-value tab check (containsWhitespaceOtherThanSpace) ever
      // gets a chance to run via a later getInfo()/info() call. Found by the Phase 3 writer-compliance review's
      // Path A kickoff, the same shape as the rawQual gap just above.
      if (rawInfo.contains("\t")) {
        throw new VcfFormatException("INFO text \"" + InternalUtils.truncateAndEscapeForMessage(rawInfo) +
            "\" contains a tab");
      }
      m_rawInfo = rawInfo;
    }
  }

  /**
   * Convenience shape for a position with no ID/ALT/FILTER/INFO/FORMAT content. Package-private for the same
   * reason as the primary constructor above; reached via
   * {@link VcfContext#newPosition(String, long, String, VcfFloat, int)}. There is nothing mutable to take
   * ownership of here, so that factory has no defensive copy to make.
   *
   * @param lineNumber the originating line number in the source VCF file, or {@code -1} if this position was not
   * produced by parsing a real file (e.g. constructed directly by a caller or test)
   */
  VcfPosition(VcfContext context, String chromosome, long position, String refBases, @Nullable VcfFloat quality,
      int lineNumber) {
    m_context = Preconditions.checkNotNull(context, "context must not be null");
    m_warnings = context.getWarnings();
    checkChromosome(chromosome, context.isLenient() ? m_warnings : null);
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
   * any parse warning, or throwing for an unparseable QUAL) as a side effect, so reading through them here would
   * re-parse-and-warn-or-throw at copy time, redundantly re-emitting a warning {@link #info()} already emitted
   * once on this source instance. Both fields prefer the raw text over the already-parsed value when both are
   * set (i.e. {@link #getQuality()}/{@link #getInfo()} or a sibling accessor already ran successfully on the
   * source): {@link #m_rawQuality}/{@link #m_rawInfo} are never cleared once populated (see their own field
   * comments), so each is always at least as complete as its parsed counterpart -- the copy re-derives the same
   * parsed value lazily on its own first {@link #getQuality()}/{@link #getInfo()} call, and preserves a
   * malformed/warned-about source's original text too, which {@link #m_quality}/{@link #m_info} alone cannot
   * (for QUAL, {@link #m_quality} stays {@code null} after an unparseable value; for INFO, {@link #getRawInfo()}
   * would otherwise return {@code null} on the copy even though the source's own raw text is still available).
   * <p>
   * {@code ref}/{@code ids}/{@code altBases}/{@code filter}/{@code format}/{@code info} are seeded through
   * {@link Builder}'s private {@code seedXxx} methods, not its public setters -- this position already
   * validated (or already warned about and recovered from) every one of them once, so seeding must not mark
   * them dirty for {@link Builder#build()} to re-check. A caller who then calls the corresponding public
   * setter on the returned builder marks that one field dirty, and only that field is re-validated on
   * {@link Builder#build()} -- {@code ref} additionally marks {@code altBases} dirty too when set this way,
   * since {@code checkAltBases}'s ALT-identical-to-REF check depends on both fields (see {@link
   * Builder#ref(String)}'s own comment).
   */
  public Builder toBuilder() {
    Builder builder = new Builder(m_context)
        .chromosome(m_chromosome)
        .position(m_position)
        .lineNumber(m_lineNumber);
    builder.seedRef(m_refBases);
    builder.seedIds(m_ids);
    builder.seedAltBases(m_altBases);
    builder.seedFilters(m_filter);
    builder.seedFormat(m_format);
    if (m_rawQuality != null) {
      builder.rawQuality(m_rawQuality);
    } else {
      builder.quality(m_quality);
    }
    if (m_rawInfo != null) {
      builder.rawInfo(m_rawInfo);
    } else {
      builder.seedInfo(m_info);
    }
    return builder;
  }

  /**
   * @param checkWarnings null-means-strict, matching this class's own established convention (see the
   * constructor's own comment on {@code checkWarnings}) -- used only by the two trailing checks below
   * (printable-ASCII range, reserved symbolic allele name). Every OTHER check in this method remains an
   * unconditional throw regardless of this parameter: CHROM's whitespace/leading-character/blocklisted-
   * punctuation violations are treated as structural (this is a fixed, mandatory column), matching
   * {@code checkAltBases}'s identical unconditional treatment of a malformed ALT. The printable-ASCII outer
   * bound gets a warn path because bcftools itself only warns (never rejects) on it -- see
   * {@code InternalUtils.NON_PRINTABLE_ASCII_CONTIG_NAME_CHAR_PATTERN}'s own comment. The reserved-symbolic-
   * allele-name check gets the same warn path for a different reason -- sibling consistency with
   * {@code ContigMetadata}'s identical, already-warn-or-throw enforcement of the same spec sentence for
   * {@code ##contig} -- see that check's own comment below.
   */
  private static void checkChromosome(String chr, @Nullable VcfWarnings checkWarnings) {
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
      throw new VcfFormatException("CHROM column \"" + InternalUtils.truncateAndEscapeForMessage(chr) +
          "\" is empty or contains whitespace");
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
      throw new VcfFormatException("CHROM column \"" + InternalUtils.truncateAndEscapeForMessage(chr) +
          "\" is empty or contains whitespace");
    }
    // VCFv4.3.tex's appendix: "* is now valid when not the first character"; "*"/"=" as the first character
    // "were already invalid and remain so" -- see also this class's own INVALID_CONTIG_NAME_CHAR_PATTERN,
    // whose character class deliberately does not include '*'/'=' since they're only invalid in this one
    // first-character position, not everywhere in CHROM.
    char first = content.charAt(0);
    if (first == '*' || first == '=') {
      throw new VcfFormatException("CHROM column \"" + InternalUtils.truncateAndEscapeForMessage(chr) +
          "\" must not start with '" + first + "'");
    }
    Matcher matcher = InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN.matcher(content);
    if (matcher.find()) {
      throw new VcfFormatException("CHROM column \"" + InternalUtils.truncateAndEscapeForMessage(chr) +
          "\" contains the invalid character '" + matcher.group() + "'");
    }
    // see NON_PRINTABLE_ASCII_CONTIG_NAME_CHAR_PATTERN's own comment -- same errata's outer bound
    // ([!-~], VCFv4.3.tex:258-259). Unlike every check above, bcftools 1.23.1 only warns on this one (never
    // rejects) -- see this method's own checkWarnings parameter comment.
    Matcher nonPrintableMatcher = InternalUtils.NON_PRINTABLE_ASCII_CONTIG_NAME_CHAR_PATTERN.matcher(content);
    if (nonPrintableMatcher.find()) {
      String message = "CHROM column \"" + chr + "\" contains a non-printable-ASCII character (code point " +
          (int) nonPrintableMatcher.group().charAt(0) + ")";
      if (checkWarnings == null) {
        // truncateAndEscapeForMessage applied to the whole already-built message, not just chr: this is the
        // thrown-message-only half of the same bound/escape treatment every other unconditional-throw site in
        // this method got; the warn path below stays unsanitized, matching the scope this project's exception-
        // message hardening has always used (thrown messages only, not logged warnings).
        throw new VcfFormatException(InternalUtils.truncateAndEscapeForMessage(message));
      }
      checkWarnings.warn(message);
    }
    // VCFv4.3.tex line 266 (new at 4.3; absent from VCFv4.1.tex/VCFv4.2.tex): "The contig names must not use
    // a reserved symbolic allele name." Enforced for a ##contig declaration's own ID by ContigMetadata.init()
    // (warn-or-throw, not an unconditional throw -- see that method's own comment for exactly which names
    // count as reserved, e.g. "DEL", "DUP:TANDEM", never a bare "TANDEM"). CHROM and ##contig are documented
    // as sharing the same "VCF contig name" rules by InternalUtils.INVALID_CONTIG_NAME_CHAR_PATTERN's own
    // comment ("Used by both VcfPosition.checkChromosome (CHROM values) and ContigMetadata (##contig IDs) --
    // both are 'VCF contig names' per that same errata text"), so this check is given the same warn-or-throw
    // shape as ContigMetadata's, rather than this method's own otherwise-unconditional throw, for sibling
    // consistency between the two enforcements of the identical spec sentence. bcftools 1.23.1 gives no
    // signal either way for CHROM specifically here -- confirmed live, it accepts both "DEL" and "<DEL>" as
    // CHROM with only an unrelated "contig not defined in header" warning, exit 0 -- so unlike the
    // non-printable-ASCII check just above, this is a sibling-consistency decision, not one bcftools' own
    // behavior settles.
    if (ReservedStructuralVariantCode.isReservedSymbolicAlleleName(content)) {
      String message = "CHROM column \"" + chr + "\" must not use a reserved symbolic allele name";
      if (checkWarnings == null) {
        // see the non-printable-ASCII check just above for why this wraps the whole built message, and why
        // only the throw path (not the warn below) is sanitized
        throw new VcfFormatException(InternalUtils.truncateAndEscapeForMessage(message));
      }
      checkWarnings.warn(message);
    }
  }

  private static void checkPosition(long pos) {
    // POS 0 is reserved for telomeres, but a negative position is invalid
    if (pos < 0) {
      throw new VcfFormatException("POS " + pos + " is negative");
    }
    // bcftools 1.23.1's own position type (hts_pos_t) is a 64-bit SIGNED integer with a narrower usable
    // range than Java's long: bisected live, POS 2^62-1 is accepted and POS 2^62 fails outright (exit 8).
    // A POS this large is never a legitimate coordinate (real chromosome lengths are in the hundreds of
    // millions); see VCF_SPEC_AMBIGUITIES.md's "POS beyond the 32-bit signed Integer range" entry for the
    // full measured boundary.
    if (pos >= (1L << 62)) {
      throw new VcfFormatException("POS " + pos + " is at or beyond 2^62, which bcftools cannot read");
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
      throw new VcfFormatException("Invalid reference base '" + InternalUtils.truncateAndEscapeForMessage(ref) +
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
        throw new VcfFormatException("ID \"" + InternalUtils.truncateAndEscapeForMessage(id) +
            "\" contains whitespace or semicolons");
      }
      // VCFv4.3.tex line 338 (the target) states directly "String, no whitespace or semicolons permitted,
      // duplicate values not allowed."; VCFv4.2.tex line 181 has no duplicate-value clause for ID, predating
      // this rule rather than conflicting with it -- matching hts-specs' own test/vcf/4.3/failed/failed_body_id_003.vcf
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
          throw new VcfFormatException("Duplicate ID \"" + InternalUtils.truncateAndEscapeForMessage(id) + "\"");
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
   * {@code list} is no longer small. A caller that skips this method entirely for one index must not call it
   * for that index at all, not call it and ignore the result: skipping the call is also what keeps that
   * index's value out of {@code seen} for every later comparison. As of the Phase 3 writer-compliance
   * review's Task 5 first re-verification round (finding B), every {@code checkFormat} branch -- including a
   * grammar-invalid key and an empty key -- calls this for every index; there is currently no FORMAT-key
   * shape this method is skipped for.
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
   * @param refBases this record's own {@code REF} value, used only for the reference-identical-allele check
   * below; {@code null} skips that one check entirely (never reached with {@code null} from any real call
   * site, since {@code REF} is mandatory and always checked/set before this method runs -- accepted as
   * {@code @Nullable} purely so a caller with no {@code REF} in scope yet isn't forced to fabricate one)
   * @return {@code altBases} unchanged, or a new list derived from it with any empty entry substituted with
   * {@code "."} (with a warning, or a thrown {@link VcfFormatException} if {@code warnings} is {@code null} --
   * strict mode); see {@code EMPTY_FIELD_HANDLING.md}
   */
  private static List<String> checkAltBases(List<String> altBases, @Nullable String refBases,
      @Nullable VcfWarnings warnings) {
    // ALT is position-dependent: GT genotype values index into it positionally (VCFv4.2.tex line 217: "1 for the
    // first allele listed in ALT, 2 for the second allele list [sic] in ALT and so on"; see getAllele(int)). Dropping
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
        throw new VcfFormatException("Invalid alternate base '" + InternalUtils.truncateAndEscapeForMessage(base) +
            "' (must match " + InternalUtils.ALT_BASE_PATTERN + ")");
      }
      // VCFv4.3.tex line 348: "ALT --- alternate base(s): Comma-separated list of alternate non-reference
      // alleles" -- identical wording at VCFv4.1.tex:166/VCFv4.2.tex:183/VCFv4.4.tex:364/VCFv4.5.tex:413, so
      // this is a version-independent rule, not a 4.3-only one. Only an ordinary base-sequence entry can
      // collide with REF this way -- skip the missing-value sentinel, the spanning-deletion "*", any
      // angle-bracket-wrapped symbolic allele (including the unspecified "<*>"), and any breakend
      // replacement string (always contains "[" or "]"), none of which can equal REF as plain text anyway.
      // Case-sensitive: VCFv4.3.tex:344 ("Tools processing VCF files are not required to preserve case in
      // the allele Strings", REF's own statement) and :353 ("Tools processing VCF files are not required to
      // preserve case in the allele String, except for IDs, which are case sensitive", ALT's own, slightly
      // narrower statement -- found by the Phase 2 review's fourth re-verification round to be a distinct
      // sentence, not identical wording as an earlier draft of this comment claimed) together mean case is
      // not spec-guaranteed significant for an ordinary base-sequence allele, but confirmed live that
      // vcf_validator 0.10.2 -- whose own dedicated check for exactly this shape ("Reference and alternate
      // alleles must not be the same") is the sole evidence for this rule at all, since no hts-specs fixture
      // covers it and bcftools 1.23.1 is silent -- itself treats REF=C/ALT=c as valid, so this matches that
      // tool's own case-sensitivity rather than picking an unmeasured stricter reading. Found by the Phase 2
      // review's third re-verification round.
      if (refBases != null && base.equals(refBases) && !base.equals(".") && !base.equals("*") &&
          base.indexOf('<') != 0 && base.indexOf('[') < 0 && base.indexOf(']') < 0) {
        String message = "ALT allele \"" + base + "\" is identical to REF; alleles must be non-reference";
        if (warnings == null) {
          // see checkChromosome's identical non-printable-ASCII check for why this wraps the whole built
          // message (bounding/escaping the thrown form only, not the warn below)
          throw new VcfFormatException(InternalUtils.truncateAndEscapeForMessage(message));
        }
        warnings.warn(message);
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
   * @param originatedPublicly {@code true} only for content reaching this method via genuinely public
   * construction ({@link VcfContext#newPosition}/{@link Builder#build()}'s dirty-field re-validation) --
   * {@code false} for the parser's own trusted internal factories. Gates the comma check just below,
   * unconditionally (regardless of {@code warnings}): a comma here produces a file bcftools cannot read at
   * all (see {@code VCF_SPEC_AMBIGUITIES.md}'s "Comma in a data-line FILTER code, INFO key, or FORMAT key"
   * entry), but unlike the semicolon check just above it, a real parsed file's FILTER column CAN legitimately
   * contain a comma (comma is not this field's own split delimiter), so this cannot be unconditional
   * regardless of origin the way the semicolon check is -- doing so would reject re-parsing/re-emitting a
   * file that parses successfully today with only a warning.
   */
  private static List<String> checkFilters(List<String> filters, @Nullable VcfWarnings warnings,
      boolean originatedPublicly) {
    filters = InternalUtils.dropEmptyEntries("FILTER", filters, warnings);
    Set<String> seen = filters.size() > DUPLICATE_SCAN_THRESHOLD ? new HashSet<>() : null;
    for (int i = 0; i < filters.size(); i++) {
      String f = filters.get(i);
      // hts-specs test/vcf/4.2/failed/failed_body_filter_003.vcf, ##CauseOfFailure=FILTER contains whitespaces
      // (literal FILTER="my filter").
      if (InternalUtils.containsWhitespace(f)) {
        throw new VcfFormatException("FILTER column entry \"" + InternalUtils.truncateAndEscapeForMessage(f) +
            "\" contains whitespace");
      }
      // VCFv4.3.tex:362 (the target, already cited a few lines below for this method's duplicate-value
      // check) states directly, for both ID and FILTER, "String, no whitespace or semicolons permitted,
      // duplicate values not allowed." -- the whitespace half is checked immediately above; this is the
      // semicolon half, previously missing here despite checkIds() enforcing it for the identical spec
      // sentence at VCFv4.3.tex:338 (see checkIds() above). Unconditional, matching checkIds(): FILTER's own
      // list-join delimiter can never appear inside one of its own entries and
      // round-trip safely -- confirmed live (bcftools 1.23.1): a single intended FILTER entry "a;b", written
      // joined with ';' between entries, produces the wire text "a;b", which bcftools silently accepts as
      // TWO FILTER codes, exit 0, no warning at all (see BcftoolsDependencyTest#testFilterSemicolonEntryBcftoolsAcceptsAsTwoCodes,
      // added in Task 2). This check can only ever fire on content reaching VcfPosition via public
      // construction (Builder.filters() or a direct VcfContext.newPosition call): VcfParser.toList
      // (VcfParser.java:794-822) is a plain, no-escaping split, so no filter entry produced by parsing a real
      // file's FILTER column can ever itself contain the ';' it would have been split on.
      if (f.contains(";")) {
        throw new VcfFormatException("FILTER column entry \"" + InternalUtils.truncateAndEscapeForMessage(f) +
            "\" contains a semicolon");
      }
      // Comma is not FILTER's own list delimiter (semicolon is), so unlike the check just above, a real
      // parsed file's FILTER column can legitimately contain one -- confirmed live (bcftools 1.23.1) that
      // an undeclared FILTER code containing a comma makes bcftools synthesize a dummy ##FILTER header line
      // whose own comma breaks its attribute tokenizer, failing the WHOLE file (exit 8), not just this
      // record. Gated on originatedPublicly, not on warnings==null, so it applies regardless of a lenient or
      // strict context -- see this method's own Javadoc for why. See VCF_SPEC_AMBIGUITIES.md's "Comma in a
      // data-line FILTER code, INFO key, or FORMAT key" entry.
      if (originatedPublicly && f.contains(",")) {
        throw new VcfFormatException("FILTER column entry \"" + InternalUtils.truncateAndEscapeForMessage(f) +
            "\" contains a comma");
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
          // filters is a List<String>, not a String -- truncateAndEscapeForMessage applied to its toString()
          // rather than to filters itself, since an arbitrarily long or numerous FILTER list is the same
          // unbounded-content risk as any single embedded value
          throw new VcfFormatException("FILTER contains PASS along with other filters: " +
              InternalUtils.truncateAndEscapeForMessage(filters.toString()));
        }
        warnings.warn("FILTER contains PASS along with other filters: {}", filters);
      }
      if (f.equals(".") && filters.size() > 1) {
        // hts-specs test/vcf/4.2/failed/failed_body_filter_000.vcf, ##CauseOfFailure=FILTER contains missing
        // in addition to a value. Unconditional (not gated on warnings == null like the PASS-combined check
        // above): unlike PASS, `.` has no legitimate combined-with-another-code use documented anywhere.
        throw new VcfFormatException("FILTER contains '.' (missing value) along with other filters!");
      }
      // VCFv4.3.tex line 362 (the target) states directly "String, no whitespace or semicolons permitted,
      // duplicate values not allowed."; VCFv4.2.tex line 185 has no duplicate-value clause for FILTER, predating
      // this rule rather than conflicting with it -- matching hts-specs' own
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
          throw new VcfFormatException("Duplicate FILTER code \"" + InternalUtils.truncateAndEscapeForMessage(f) +
              "\"");
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
   * @param originatedPublicly see {@link #checkFilters}'s identical parameter -- gates the comma-in-key check
   * below the same way and for the same reason (a comma is not this field's own key/value split delimiter, so
   * a real parsed file's INFO column can legitimately contain one in a key).
   */
  private static ListMultimap<String, String> checkInfoEntries(ListMultimap<String, String> info,
      @Nullable VcfWarnings warnings, boolean originatedPublicly) {
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
        throw new VcfFormatException("INFO value for key \"" + InternalUtils.truncateAndEscapeForMessage(key) +
            "\" is null");
      }
      if (key.isEmpty()) {
        if (warnings == null) {
          throw new VcfFormatException("INFO entry \"=" + InternalUtils.truncateAndEscapeForMessage(value) +
              "\" has an empty key");
        }
        warnings.warn("INFO entry \"{}\" has an empty key (VCF does not allow zero-length fields); dropping it",
            "=" + value);
        continue;
      }
      // Key: tab/LF/CR remain unconditional structural violations regardless of context, the same as the
      // parallel carve-out in the value check just below -- VcfWriter.addInfoOrDot (VcfWriter.java) writes this
      // key straight into the record column (sb.append(key)), where a tab would add a spurious column and an
      // LF/CR would end the record early, and nothing downstream catches either. Unreachable via a real parsed
      // file for the identical reason as that carve-out: the constructor's own checkNoLineTerminator/tab check
      // on the whole raw INFO text (see this class's constructor) already excludes them before info() ever
      // derives a key by splitting that text -- this can only fire via direct/programmatic construction with an
      // already-split ListMultimap.
      if (key.indexOf('\t') >= 0 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
        throw new VcfFormatException("INFO column entry \"" + InternalUtils.truncateAndEscapeForMessage(key) +
            "=" + InternalUtils.truncateAndEscapeForMessage(value) + "\" contains a tab or line terminator " +
            "in its key");
      }
      // Any OTHER whitespace in a key (a plain space, or VT/FF) does not corrupt output structure the way
      // tab/LF/CR do -- none of them is a delimiter or line terminator VcfWriter's default write path depends
      // on -- so unlike tab/LF/CR this gets the same isLenient()-gated warnOrThrow treatment as every other
      // recoverable INFO violation in this method, instead of an unconditional throw. VCFv4.2.tex line 186's
      // INFO grammar note ("String, no whitespace, semicolons, or equals-signs permitted") and VCFv4.3.tex line
      // 368's INFO key grammar (^([A-Za-z_][0-9A-Za-z_.]*|1000G)$, unchanged at VCFv4.4.tex line 384 and
      // VCFv4.5.tex line 433) both still forbid it -- this is leniency toward non-compliant input, not a
      // reinterpretation of the rule. Reproduces hts-specs test/vcf/4.2/failed/failed_body_info_028.vcf,
      // ##CauseOfFailure=INFO contains a whitespace (literal INFO="RS Q=123"). Matches IdMetadata's own
      // existing warnOrThrow treatment of a whitespace-containing ##INFO declaration ID under a lenient
      // context (see that class) -- a data-line key "A B" already correctly resolves against a declared
      // "##INFO=<ID=A B,...>" once both sides tolerate it (getId() only unquotes, never strips whitespace, and
      // VcfMetadata's INFO map is keyed by that exact value), so this closes an existing inconsistency between
      // the metadata and data-line layers rather than creating a new one. See VCF_SPEC_AMBIGUITIES.md's
      // "Malformed INFO shapes: '.' combined with a property, whitespace in a key, vertical tab/form feed in a
      // value" entry.
      if (InternalUtils.containsWhitespace(key)) {
        String message = "INFO column entry \"" + InternalUtils.truncateAndEscapeForMessage(key) + "=" +
            InternalUtils.truncateAndEscapeForMessage(value) + "\" contains whitespace";
        if (warnings == null) {
          throw new VcfFormatException(message);
        }
        warnings.warn(message + "; keeping the key as-is");
      }
      // Value: U+0020 SPACE only is permitted, at every version. VCFv4.2.tex line 186 (above) forbids all
      // whitespace in an INFO value, but VCFv4.3.tex line 366 states "Space characters are allowed in values"
      // (verbatim at VCFv4.4.tex line 382 and VCFv4.5.tex line 431), and the VCFv4.2->4.3 changelog corroborates
      // it at VCFv4.3.tex line 2151 ("Spaces are allowed in INFO field values"). This library applies the later, more
      // permissive reading unconditionally rather than gating it on the declared ##fileformat: accepting a space
      // in a VCFv4.2 file is pure leniency with no misinterpretation risk (a space is not a delimiter in any
      // version's INFO grammar, so no value's meaning changes), which is this project's established handling
      // per AGENTS.md. The relaxation is deliberately U+0020-only -- the spec says "space characters", not
      // whitespace, and no version widens it.
      //
      // Tab/LF/CR remain unconditional structural violations regardless of context, for the identical reason as
      // the key check just above: VcfWriter.addInfoOrDot writes this value straight into the record column
      // (sb.append(value)), where a tab adds a column and an LF/CR ends the record, and nothing downstream
      // catches it (the ';'/',' check below does not cover them, and InternalUtils.checkNoLineTerminator's own
      // whole-raw-text call in this class's constructor only catches \n/\r, not a tab -- see that call's own
      // comment). Unreachable via a real parsed file for the same reason as the key
      // check above -- only direct/programmatic construction can reach this.
      if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
        throw new VcfFormatException("INFO value \"" + InternalUtils.truncateAndEscapeForMessage(value) +
            "\" for key \"" + InternalUtils.truncateAndEscapeForMessage(key) + "\" contains a tab or line " +
            "terminator");
      }
      // Any other whitespace (VT/FF -- the only two characters containsWhitespaceOtherThanSpace can still match
      // once tab/LF/CR are excluded above) does not corrupt output structure the way tab/LF/CR do, so unlike
      // tab/LF/CR it gets the same isLenient()-gated warnOrThrow treatment as every other recoverable INFO
      // violation in this method. Confirmed empirically that the metadata layer's own attribute-value encoding
      // check (InternalUtils.findEncodingProblems) doesn't check for this at all -- a VT byte in a
      // ##INFO=<...,Description="...">  value already parses today with zero warning -- so this is leniency
      // toward non-compliant input already tolerated one layer up, not a new inconsistency. See
      // VCF_SPEC_AMBIGUITIES.md's "Malformed INFO shapes" entry.
      if (InternalUtils.containsWhitespaceOtherThanSpace(value)) {
        String message = "INFO value \"" + InternalUtils.truncateAndEscapeForMessage(value) + "\" for key \"" +
            InternalUtils.truncateAndEscapeForMessage(key) + "\" contains whitespace other than a space (only " +
            "U+0020 is permitted in an INFO value)";
        if (warnings == null) {
          throw new VcfFormatException(message);
        }
        warnings.warn(message + "; keeping the value as-is");
      }
      // a parsed value can never contain these (they were already split on to arrive at this value), but a value set
      // directly via getInfo().put(...) could; reject them so the writer's output remains re-parseable
      if (key.contains(";") || key.contains("=")) {
        throw new VcfFormatException("INFO key \"" + InternalUtils.truncateAndEscapeForMessage(key) +
            "\" contains ';' or '='");
      }
      // Unlike ';'/'=' just above (INFO's own key-delimiters, so a parsed key can never contain either), a
      // comma is INFO's VALUE-list delimiter, not the key's -- a real parsed file's key CAN legitimately
      // contain one (raw text "A,B=1" parses key "A,B" fine today). Confirmed live (bcftools 1.23.1): an
      // undeclared INFO key containing a comma makes bcftools synthesize a dummy ##INFO header line whose own
      // comma breaks its attribute tokenizer, failing the whole file (exit 8). Gated on originatedPublicly,
      // not warnings==null, so it applies regardless of a lenient or strict context -- see
      // VCF_SPEC_AMBIGUITIES.md's "Comma in a data-line FILTER code, INFO key, or FORMAT key" entry.
      if (originatedPublicly && key.contains(",")) {
        throw new VcfFormatException("INFO key \"" + InternalUtils.truncateAndEscapeForMessage(key) +
            "\" contains a comma");
      }
      if (value.contains(";") || value.contains(",")) {
        throw new VcfFormatException("INFO value \"" + InternalUtils.truncateAndEscapeForMessage(value) +
            "\" for key \"" + InternalUtils.truncateAndEscapeForMessage(key) + "\" contains ';' or ','");
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
          throw new VcfFormatException("INFO value \"" + InternalUtils.truncateAndEscapeForMessage(value) +
              "\" for key \"" + InternalUtils.truncateAndEscapeForMessage(key) + "\" contains '='");
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
        // isLenient()-gated, matching every other recoverable check in this method: throws under strict
        // (preserving fail-fast for direct/programmatic construction), warns and recovers under lenient --
        // VcfWriter.addInfoOrDot never re-emits a literal "."-combined-with-properties shape once the bare
        // sentinel is dropped here, so the output remains well-formed. See VCF_SPEC_AMBIGUITIES.md's
        // "Malformed INFO shapes" entry, and info()'s own identical check below, for the raw-text parsing path
        // this eager/already-split-multimap path doesn't share.
        String message = "INFO contains '.' (missing value) along with other properties";
        if (warnings == null) {
          throw new VcfFormatException(message);
        }
        warnings.warn(message + "; dropping '.' and keeping the other properties");
        // Removes only the bare-sentinel (empty-string) value(s) under key "." -- not the whole key -- so a
        // literal, non-empty value legitimately stored under the unusual key "." (e.g. from raw text ".=5",
        // which info()'s own raw-text parsing branch has always left alone; see this method's own class
        // Javadoc) survives. Guava's ListMultimap.get(key) returns a live view backed by the multimap, so
        // removing through it removes from the multimap itself, and the key disappears entirely once no values
        // remain under it.
        dotValues.removeIf(String::isEmpty);
      }
    }
    return result;
  }

  /**
   * For every entry in {@code info} whose key matches a {@link ReservedInfoProperty}, eagerly runs the same
   * conversion {@link #getInfo(ReservedInfoProperty)} would otherwise only run lazily on first read -- purely as
   * a validation probe, discarding the result -- so a caller building a position directly from an already-split
   * {@link ListMultimap} gets {@code VALIDATION_PHILOSOPHY.md}'s Principle 1 ("fail fast on direct, programmatic
   * input") for a reserved key's declared {@code Type}, not just {@link #checkInfoEntries}'s structural checks.
   * A cardinality mismatch still only warns under a lenient {@code context} (unchanged -- this deliberately does
   * NOT duplicate {@link #getInfo(ReservedInfoProperty)}'s own cardinality check and first-piece fallback, since
   * that requires this position's own ALT allele count, which isn't the point of this probe); a value that
   * cannot be parsed as the key's declared {@code Type} at all throws {@link VcfFormatException} unconditionally
   * regardless of context -- see {@code InternalUtils.convertElement}'s own entry in
   * {@code VALIDATION_REFERENCE.md} for why. Never reachable via {@code VcfParser}: the parser exclusively
   * attaches INFO through this constructor's {@code rawInfo} parameter (see
   * {@code VcfContext.newPositionTakingOwnership}/{@code newPositionWithTrustedFormat}), which stays lazy and
   * never reaches this already-split-multimap path at all.
   */
  private static void checkReservedInfoTypes(ListMultimap<String, String> info, VcfContext context) {
    for (ReservedInfoProperty prop : ReservedInfoProperty.values()) {
      List<String> values = info.get(prop.getId());
      if (!values.isEmpty()) {
        String value = prop.isList() ? sf_commaJoiner.join(values) : values.get(0);
        InternalUtils.convertProperty(prop, value, context);
      }
    }
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
   * @param originatedPublicly see {@link #checkFilters}'s identical parameter -- gates both the key-count
   * check and the comma check below the same way and for the same reason.
   */
  private static List<String> checkFormat(List<String> format, @Nullable VcfWarnings warnings,
      boolean originatedPublicly) {
    if (format.size() == 1 && ".".equals(format.get(0))) {
      return new ArrayList<>();
    }
    // htslib's own BCF binary representation caps the number of FORMAT keys at 255 (vcf.c's MAX_N_FMT,
    // "Limited by size of bcf1_t n_fmt field") -- not a VCF spec rule (vcf_validator 0.10.2 accepts 256
    // keys with no complaint at all, confirmed live) but a real bcftools interoperability floor: confirmed
    // live, bcftools 1.23.1 fails the whole file (exit 8, "[E::vcf_parse_format_dict2] FORMAT column ...
    // lists more identifiers than htslib can handle") at 256 keys, bisected exactly. Gated on
    // originatedPublicly for the same reason as the comma check below: a real parsed file's FORMAT column
    // could in principle already exceed 255 keys (this library imposes no count limit of its own), so this
    // cannot be unconditional regardless of origin. See VCF_SPEC_AMBIGUITIES.md's "More than 255 FORMAT
    // keys" entry.
    if (originatedPublicly && format.size() > 255) {
      throw new VcfFormatException("FORMAT lists " + format.size() + " keys, more than the 255 bcftools 1.23.1 " +
          "can handle");
    }
    // duplicate keys are explicitly disallowed by the VCF spec (VCFv4.3+ states this outright; only an
    // EMPTY key is excluded from this check, since it's already handled, and warned about, separately below
    // -- a grammar-invalid-but-non-empty key is NOT excluded: see the isDuplicateAt call inside the
    // grammar-invalid branch further down, added by the Phase 3 writer-compliance review's finding #4, for
    // why it hits this same unconditional floor too). isDuplicateAt (see checkIds() above and
    // DUPLICATE_SCAN_THRESHOLD's own comment) dispatches between an allocation-free scan and a
    // HashSet<String> depending on list size.
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
        // An empty key that is ALSO duplicated still hits the unconditional duplicate-key throw below -- the
        // "keep, don't drop" decision above is about preserving THIS key's own column position (dropping it
        // would misalign every later key's index), a distinct question from whether a SECOND empty key is a
        // "duplicate" of the first. It is, for exactly the reason finding #4's identical fix for a
        // grammar-invalid duplicate key gives: VcfSample.getProperty("") is structurally first-occurrence-only,
        // so VcfWriter.addSampleConditionally resolves both empty-keyed positions to the same first value on
        // write, silently dropping the second -- the identical loss shape, not a merely-theoretical one.
        // Confirmed live against installed bcftools 1.23.1: given FORMAT="GT:::DP" with sample value
        // "0/1:X:Y:5", bcftools reports "[W::vcf_parse_format_alloc4] Duplicate FORMAT tag ", collapses to 3
        // keys ("GT::DP"), and keeps only the FIRST empty key's value ("X"), silently dropping "Y" -- see
        // BcftoolsDependencyTest#testDuplicateEmptyFormatKeyBcftoolsMisalignsSampleValues and
        // BCFTOOLS_VERSION_DEPENDENCIES.md's matching entry. Found by the Phase 3 writer-compliance review's
        // Task 5 first re-verification round (finding B), user-approved fix (2026-08-25): unlike the
        // grammar-invalid case, VCFv4.3.tex:429's duplicate-key regex doesn't even match an empty string, so
        // "two empty keys aren't the same key" is a more defensible spec reading than the grammar-invalid
        // carve-out ever was -- but the actual defect (permanent, undiagnosed value loss on write) doesn't
        // depend on that identity question, only on whether the write path can tell the two positions apart,
        // and it can't.
        if (isDuplicateAt(format, i, seen)) {
          throw new VcfFormatException("Duplicate FORMAT key \"\"");
        }
        continue;
      }
      // Deliberately narrower than checkIds()/checkFilters()'s full containsWhitespace() throw: a tab or
      // line terminator embedded in a FORMAT key is structural -- VcfWriter.addFormatConditionally appends a
      // FORMAT key verbatim (VcfWriter.java:633), so an embedded tab adds a column (tab is the record's own
      // column delimiter) and an embedded LF/CR ends the record early on write; nothing downstream guards
      // against either (InternalUtils.checkNoLineTerminator is only ever called on the metadata and
      // sample-name/value paths, never here). Confirmed live (bcftools 1.23.1): a tab-corrupted FORMAT key
      // ("GT:AB<TAB>CD:DP", one intended key split into two physical columns by the embedded tab) makes
      // bcftools fail outright to read the file at all -- "[E::vcf_parse_format_fill5] Couldn't read GT
      // data: value not a number or '.'", exit code 8. A plain space, vertical tab, or form feed is NOT the
      // same kind of violation -- confirmed live that bcftools only warns on each ("[W::vcf_parse_format_dict2]
      // FORMAT '...' is not defined in the header"/"[W::bcf_hrec_check] Invalid tag name"), exit 0, no column
      // shift, no data loss -- so those three stay governed by the warn-or-throw FORMAT_PATTERN check below,
      // matching this method's own pre-existing, deliberate leniency for non-structural grammar violations
      // (findings37.md item 1). Unconditional (not gated on warnings == null), matching checkIds()/
      // checkFilters()'s treatment of their own delimiters: this check can only ever fire on content reaching
      // VcfPosition via public construction, for the same reason as the colon check immediately below.
      if (f.indexOf('\t') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0) {
        throw new VcfFormatException("FORMAT key \"" + InternalUtils.truncateAndEscapeForMessage(f) +
            "\" contains a tab or line terminator");
      }
      // VCFv4.3.tex:429 -- "First a FORMAT field is given specifying the data types and order
      // (colon-separated FORMAT keys matching the regular expression ^[A-Za-z_][0-9A-Za-z_.]*$, duplicate
      // keys are not allowed)." This is a separate, unconditional floor for the delimiter-corruption
      // question, distinct from the tag-name-grammar check immediately below: a colon embedded in one
      // intended key corrupts the key LIST's own structure on round-trip, the same shape checkFilters()/
      // checkIds() already guard their own delimiters against. Confirmed live (bcftools 1.23.1): a 3-key
      // list (GT, a colon-corrupted "GT:AA", DP), written joined with ':' between keys, produces the wire
      // text "GT:GT:AA:DP" -- bcftools reports "[W::vcf_parse_format_alloc4] Duplicate FORMAT tag GT",
      // collapses to 3 keys, and silently misassigns sample values (one value vanished entirely; another was
      // reassigned to the wrong key -- see
      // BcftoolsDependencyTest#testFormatColonCorruptedKeyBcftoolsMisalignsSampleValues, added in Task 2) --
      // worse than the tag-name-grammar case below, which never corrupts alignment this way. Unconditional
      // (not gated on warnings == null), unlike the tag-name-grammar check: this is a distinct question from
      // "is this a valid identifier" and gets its own floor. This check can only ever fire on content
      // reaching VcfPosition via public construction: VcfParser.toList (VcfParser.java:794-822) is a plain,
      // no-escaping split, so no FORMAT key produced by parsing a real file's FORMAT column can ever itself
      // contain the ':' it would have been split on.
      if (f.contains(":")) {
        throw new VcfFormatException("FORMAT key \"" + InternalUtils.truncateAndEscapeForMessage(f) +
            "\" contains a colon");
      }
      // Unlike ':' just above (FORMAT's own key-list delimiter, so a parsed key can never contain it), a
      // comma is not a FORMAT-column delimiter at all -- a real parsed file's FORMAT key can legitimately
      // contain one, and today it only fails the tag-name grammar below (warn-or-throw, isLenient()-gated).
      // Confirmed live (bcftools 1.23.1): an undeclared FORMAT key containing a comma makes bcftools
      // synthesize a dummy ##FORMAT header line whose own comma breaks its attribute tokenizer, failing the
      // whole file (exit 8). Gated on originatedPublicly, not warnings==null, and checked before the grammar
      // branch below so public-lenient construction throws here instead of merely warning -- see
      // VCF_SPEC_AMBIGUITIES.md's "Comma in a data-line FILTER code, INFO key, or FORMAT key" entry.
      if (originatedPublicly && f.contains(",")) {
        throw new VcfFormatException("FORMAT key \"" + InternalUtils.truncateAndEscapeForMessage(f) +
            "\" contains a comma");
      }
      // tab/LF/CR and ':' are already handled above as their own unconditional structural floors -- this
      // check's remaining job is every OTHER tag-name-grammar violation, including a plain space, vertical
      // tab, or form feed (non-structural, per the comment above) plus a leading digit or illegal punctuation.
      // Lenient, not a structural floor for those: matches FormatMetadata's identical warn-only treatment of
      // this same grammar rule for a ##FORMAT declaration ID (see FormatMetadata.init()) -- a data-line
      // FORMAT key failing it must not abort the whole parse (findings37.md item 1; confirmed against
      // bcftools 1.23.1, which only warns on this input).
      if (!InternalUtils.FORMAT_PATTERN.matcher(f).matches()) {
        if (warnings == null) {
          throw new VcfFormatException("FORMAT ID \"" + InternalUtils.truncateAndEscapeForMessage(f) +
              "\" does not match the required tag-name grammar (must start with a letter or underscore, " +
              "followed only by letters, digits, underscores, or periods)");
        }
        warnings.warn("FORMAT ID \"{}\" does not match the required tag-name grammar (must start with a letter " +
            "or underscore, followed only by letters, digits, underscores, or periods); keeping it as-is", f);
        // A grammar-invalid key that is ALSO duplicated still hits the unconditional duplicate-key throw below
        // -- it does not get a second, independent leniency exemption on top of the grammar one. Uses
        // isDuplicateAt/seen the same way the unconditional check below does (registering this occurrence
        // too). This carve-out used to warn-and-continue here instead of throwing, exempting a doubly-invalid
        // key from the duplicate floor every OTHER key (grammar-valid or not) is held to -- but that was never
        // this carve-out's actual purpose: it exists so one badly-named key doesn't abort an otherwise-parseable
        // record (VCFv4.3.tex:429's tag-name grammar is warn-and-recover, per this method's own comment above),
        // not to also waive VCFv4.3.tex:429's separate, unconditional "duplicate keys are not allowed" floor.
        // Left in place, the exemption caused real, silent data loss: VcfSample.getProperty(key) is
        // structurally first-occurrence-only in every internal representation (no positional-access
        // alternative exists anywhere in that class), and VcfWriter.addSampleConditionally looks up each
        // FORMAT position's value by key name, so a duplicated key's SECOND occurrence resolved to the same
        // first value on write, silently dropping it -- with no warning naming the loss itself (only the
        // grammar warning above and, if a caller happened to read this key later, VcfSample.properties()'s own
        // separate duplicate-key warning). Confirmed live against installed bcftools 1.23.1: given
        // FORMAT="GT:1XY:1XY:DP" with sample value "0/1:aa:bb:5", bcftools reports
        // "[W::vcf_parse_format_alloc4] Duplicate FORMAT tag 1XY", collapses to 3 keys, and keeps only the
        // FIRST occurrence's value ("aa"), silently dropping "bb" -- the identical loss shape, corroborating
        // that this is a genuine defect, not a legitimate leniency. Found by the Phase 3 writer-compliance
        // review's Path B kickoff -- see BcftoolsDependencyTest#testDuplicateGrammarInvalidFormatKeyBcftoolsMisalignsSampleValues
        // and BCFTOOLS_VERSION_DEPENDENCIES.md's matching entry.
        if (isDuplicateAt(format, i, seen)) {
          throw new VcfFormatException("Duplicate FORMAT key \"" + InternalUtils.truncateAndEscapeForMessage(f) +
              "\"");
        }
        continue;
      }
      if (isDuplicateAt(format, i, seen)) {
        throw new VcfFormatException("Duplicate FORMAT key \"" + InternalUtils.truncateAndEscapeForMessage(f) +
            "\"");
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
   * Javadoc) is already validated by the time either returns. <b>INFO is the one exception, and this
   * method remains genuinely load-bearing because of it</b>: {@link Builder#rawInfo}
   * (and the matching {@code rawInfo} parameter {@code VcfContext.newPositionTakingOwnership}/
   * {@code newPositionWithTrustedFormat} accept) attaches raw, not-yet-parsed text without validating it at
   * construction or build time at all -- that is deferred to first access ({@link #getInfo()}) or to an
   * explicit call to this method. A position built this way can therefore still hold invalid raw INFO text
   * after {@link Builder#build()} returns, and this method is the only way to surface that without also
   * triggering the ordinary lazy-access warning path. {@code VcfWriter}'s {@code validateBeforeWrite()} relies
   * on exactly this, calling it on every position before writing -- do not remove or bypass this method, and
   * do not assume every field it checks is already guaranteed valid the way ID/ALT/FILTER/FORMAT/INFO-multimap
   * are.
   * <p>
   * QUAL is deliberately NOT included here, even though {@link Builder#rawQuality} has the identical
   * raw-text-not-yet-validated shape INFO does: unlike {@link #getInfo()}'s checks (which warn under a
   * lenient context), {@link #getQuality()} throws {@link VcfFormatException} <b>unconditionally</b> for a
   * genuinely unparseable value, regardless of context -- see that method's own {@code @throws} Javadoc.
   * Calling it from here would make this method throw where {@code VcfWriter}'s own {@code writeLine()}
   * deliberately catches that exact throw and downgrades it to a warning instead (so a record with
   * unparseable-but-still-representable raw QUAL text can still be written, per {@code AGENTS.md}'s Writer
   * Validation Policy) -- {@code validateBeforeWrite()} would otherwise reject content the writer is
   * specifically designed to preserve. A position built with invalid raw QUAL text therefore still passes
   * this method cleanly; the same content is only ever surfaced by an explicit {@link #getQuality()} call
   * (or a caller-supplied catch of {@code VcfWriter}'s own downgraded warning).
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
      // m_warnings, not the null-means-strict checkWarnings convention: validate() always warns for a
      // recoverable condition rather than throwing, even under a strict context -- matching checkIds/
      // checkAltBases/checkFilters/checkFormat below, which are already passed m_warnings unconditionally.
      //
      // originatedPublicly=false: unlike every other check here, checkFilters/checkInfoEntries/checkFormat's
      // bcftools-floor checks (a comma in a key, too many FORMAT keys) must never newly fire on an already-
      // constructed position re-validated here, regardless of that position's own origin -- content that
      // reached this point via public construction/Builder#build() already passed (or would have thrown at)
      // that same check once; content that reached it via the parser's trusted path must stay exempt, exactly
      // as it is at construction. See the primary constructor's own originatedPublicly Javadoc.
      checkChromosome(m_chromosome, m_warnings);
      checkPosition(m_position);
      m_ids = checkIds(m_ids, m_warnings);
      checkRef(m_refBases);
      m_altBases = checkAltBases(m_altBases, m_refBases, m_warnings);
      m_filter = checkFilters(m_filter, m_warnings, false);
      normalizeFilters();
      m_info = checkInfoEntries(info(), m_warnings, false);
      m_format = checkFormat(m_format, m_warnings, false);
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
    // explicit bound check, not a bare delegation to m_altBases.get(index - 1): that would report the
    // shifted index in its own message (e.g. getAllele(-1) reporting "Index -2"), confusing for a caller who
    // never passed -2 -- reporting the caller's own index instead.
    if (index < 0 || index > m_altBases.size()) {
      throw new IndexOutOfBoundsException("Allele index " + index + " is out of bounds");
    }
    return m_altBases.get(index - 1);
  }


  /**
   * @throws VcfFormatException if the raw QUAL text is neither a normal decimal number nor one of VCFv4.3.tex
   * line 100's Special Float values (the line's own regex: {@code ^[-+]?(INF|INFINITY|NAN)$}, case
   * insensitively) -- unconditionally, regardless of context (this is a genuinely deferred check: {@code VcfParser}
   * itself stays lenient and finishes parsing the file regardless of what this throws later). Matches
   * {@link InternalUtils#convertElement}'s identical unconditional-throw-for-an-unparseable-value decision for
   * INFO/FORMAT reserved keys, for the same reason: there is no sane value to preserve or normalize to, and
   * nothing is lost by throwing, since {@link #getRawQuality()} still returns the original wire text either
   * way. Every call re-attempts the parse and re-throws, unlike the warn-and-recover branch below, which only
   * ever warns once.
   */
  public @Nullable VcfFloat getQuality() {
    if (!m_qualityResolved) {
      String raw = m_rawQuality;
      if (raw != null && !raw.isEmpty() && !raw.equals(".")) {
        try {
          // QUAL is VCF-spec-typed Float; VcfFloat.parse() also accepts NAN/INF/INFINITY (case-insensitive,
          // optionally signed) per VCFv4.3.tex line 100's own regex, ^[-+]?(INF|INFINITY|NAN)$ case
          // insensitively -- exactly like every other Float-typed reserved key (see
          // InternalUtils.convertElement's own identical VcfFloat.parse(value, warnings) call).
          m_quality = VcfFloat.parse(raw, m_warnings);
          m_qualityResolved = true;
        } catch (VcfFormatException e) {
          // deliberately not setting m_qualityResolved here -- see this method's own @throws Javadoc for why a
          // strict-equivalent throw (unconditional here, not gated on isLenient()) re-fires on every call
          // instead of being cached like the successful/missing outcomes above and below.
          // withLineNumber (not a bare throw) and passing e as the cause: this is the one remaining deferred
          // getter that threw without either, unlike its sibling info()/getInfo() overloads -- losing both the
          // line number and, e.g., VcfFloat's own more specific "too long to be a valid VCF Float" diagnosis,
          // flattened here to a generic "is not a number".
          throw withLineNumber(new VcfFormatException("QUAL '" + InternalUtils.truncateAndEscapeForMessage(raw) +
              "' is not a number -- see getRawQuality() for the original text", e));
        }
      } else {
        m_qualityResolved = true;
      }
    }
    return m_quality;
  }

  /**
   * @return the original QUAL column text, exactly as it appeared on the wire -- {@code null} only when this
   * position was constructed directly from an already-parsed {@link VcfFloat} (via {@link Builder#quality}),
   * so no wire text ever existed to reproduce. Unlike {@link #getQuality()}, never parses anything and never
   * throws; always safe to call, including after {@link #getQuality()} has thrown for an unparseable value --
   * this is exactly the fallback that makes that throw lossless. Mirrors {@link #getInfo(String)}'s equivalent
   * role for INFO's own reserved-key typed getters. {@code VcfWriter}'s default write path prefers this over
   * {@link #getQuality()} whenever it is non-null, matching how every other field is written back from its
   * originally parsed text rather than a reformatted typed value.
   */
  public @Nullable String getRawQuality() {
    return m_rawQuality;
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
        // "." (missing value: no INFO at all) cannot be combined with a real property, the same as FILTER's
        // exclusivity rule for its own "." sentinel (checkFilters below) -- NOT the same as ALT, which
        // deliberately allows "." as a valid per-entry token even combined with others (EMPTY_FIELD_HANDLING.md's
        // "position-independent list" section; e.g. "ALT=.,A" parses as a two-element list with no warning).
        // Unlike an empty prop (dropped above), this isn't a stray delimiter artifact, it directly contradicts
        // "." meaning "nothing here"
        if (props.contains(".") && props.size() > 1) {
          // isLenient()-gated, matching checkInfoEntries's own identical check below (which this raw-text
          // path never reaches for this shape, since the loop below skips a "." prop before it can ever reach
          // the built multimap -- see that loop's own comment): throws under strict, warns and drops every
          // stray "." prop under lenient, keeping the real properties. See VCF_SPEC_AMBIGUITIES.md's
          // "Malformed INFO shapes" entry.
          String message = "INFO contains '.' (missing value) along with other properties";
          if (!m_context.isLenient()) {
            throw withLineNumber(new VcfFormatException(message));
          }
          m_warnings.warn(message + "; dropping '.' and keeping the other properties");
          props.removeIf(prop -> prop.equals("."));
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
          // isLenient()-gated, matching every other check in this method (and checkInfoEntries's own
          // warnOrThrow checks it shares with the eager construction path): a position built under a strict
          // context via Builder.rawInfo() must still throw here, not merely warn, for every one of
          // checkInfoEntries's recoverable checks (key/value whitespace, an empty key, a bare '=' in a
          // value) -- passing m_warnings unconditionally regardless of context would silently downgrade all
          // of them to warnings for this lazy path only.
          //
          // originatedPublicly=false (a known, deliberate gap, not an oversight): m_rawInfo reaches here from
          // either the parser's own trusted rawInfo attachment or a public Builder.rawInfo() call, and
          // nothing tracks which -- unlike the eager checkInfoEntries call in the constructor/Builder#build(),
          // which knows its caller's origin directly. Threading that distinction through this lazy path would
          // need dirty-tracking infrastructure Builder.rawInfo() does not have today. A comma in an INFO key
          // supplied this way (Builder.rawInfo("A,B=1")) is therefore not caught by the new bcftools-floor
          // check -- narrower and more indirect than the eager-multimap construction path the finding
          // reproduced, and left out of scope here; see VCF_SPEC_AMBIGUITIES.md's "Comma in a data-line
          // FILTER code, INFO key, or FORMAT key" entry.
          info = checkInfoEntries(info, m_context.isLenient() ? m_warnings : null, false);
        } catch (VcfFormatException ex) {
          throw withLineNumber(ex);
        }
      }
      m_info = info;
      // m_rawInfo is deliberately NOT cleared here -- see getRawInfo() and this class's own field comment on
      // m_rawInfo, and m_rawQuality's identical permanent-retention pattern. Once this method has parsed (and
      // possibly warned about and recovered from) the raw text into m_info, the original wire text must remain
      // available via getRawInfo() regardless -- see VALIDATION_PHILOSOPHY.md's raw-fallback-always-exists
      // claim.
    }
    return info;
  }

  /**
   * @return {@code ex}, annotated with this position's line number if known ({@code m_lineNumber >= 0}) --
   * shared by {@link #info()} and both {@code getInfo} overloads below, which otherwise each independently
   * repeated this same annotate-then-throw snippet.
   */
  private VcfFormatException withLineNumber(VcfFormatException ex) {
    if (m_lineNumber >= 0) {
      ex.addMetadata(m_lineNumber, "data");
    }
    return ex;
  }

  /**
   * Gets all INFO fields for every key.
   * <p>
   * Values are raw wire text -- this library never percent-decodes an INFO value; see
   * {@link VcfPercentEncoding} if you need semantic text.
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
   * @return the original INFO column text, exactly as it appeared on the wire -- {@code null} only when this
   * position was constructed directly from an already-parsed {@link ListMultimap} (via {@link Builder#info}),
   * so no wire text ever existed to reproduce. Unlike {@link #getInfo()}, never parses anything and never
   * throws; always safe to call, including after {@link #getInfo()}/{@link #getInfoKeys()}/
   * {@link #hasInfo(String)} (or any other accessor that triggers {@link #info()}) has warned about or thrown
   * for a malformed shape --
   * this is exactly the fallback that makes that outcome lossless. Mirrors {@link #getRawQuality()}'s
   * equivalent role for QUAL.
   */
  public @Nullable String getRawInfo() {
    return m_rawInfo;
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
   * @throws VcfFormatException if the raw value's actual element count does not match {@code key}'s declared
   * {@code Number} and this position was built under a strict {@link VcfContext} (under a lenient one, this
   * instead warns and, for a scalar-declared key given more than one comma-piece, keeps only the first -- see
   * {@link InternalUtils#warnIfWrongCardinality}); or if the raw value cannot be parsed as {@code key}'s
   * declared {@code Type} at all -- unconditionally, regardless of context, since (unlike a cardinality
   * mismatch or an out-of-range-but-otherwise-valid number) there is no sane value to preserve or normalize to.
   * This parse is a genuinely deferred check -- {@code VcfParser} itself stays lenient and finishes parsing the
   * file regardless -- and nothing is lost even when it throws: the original wire text for {@code key} remains
   * available afterward via {@link #getInfo(String)}/{@link #getInfo()}, which read from the same backing
   * storage this conversion never mutates
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
    // isLenient()-aware, shared by both cardinality-check branches inside resolveInfoValue() and
    // convertProperty's own dispatch -- see InternalUtils.convertProperty(Class, String, boolean, VcfWarnings,
    // VcfWarnings)'s own Javadoc for the general pattern. A cardinality mismatch (scalar or list) is the same
    // kind of "bad input causing data loss on typed access" this library already throws for elsewhere under
    // strict, rather than warning and silently keeping only the first piece.
    VcfWarnings checkWarnings = m_context.isLenient() ? m_warnings : null;
    // getExpectedNumber() is always the literal "0" or "1" for a scalar (isList=false) key --
    // getExpectedCardinality() parses it back to the identical numeric value, so this produces the same
    // message text as this method's own previous hand-written warn call. For a list-typed key, this checks the
    // actual element count against what getExpectedNumber() implies for this record's own ALT allele count (the
    // INFO-side counterpart of VcfSample.getProperty(VcfPosition, VcfMetadata, String)'s identical FORMAT-side
    // check) -- e.g. AC/AF (Number=A) must have exactly one value per ALT allele, CIPOS/CIEND (Number=2) exactly
    // two. No reserved INFO key declares Number=G, so ploidy (irrelevant for INFO, which has no per-sample
    // concept) is always null here.
    String value = resolveInfoValue("INFO " + key.getId(), key.getExpectedNumber(), key.isList(), list,
        checkWarnings);
    try {
      return InternalUtils.convertProperty(key, value, m_context);
    } catch (VcfFormatException ex) {
      throw withLineNumber(ex);
    }
  }

  /**
   * Resolves {@code list}'s value for a Number-checked INFO read -- warning (via {@code checkWarnings},
   * isLenient()-gated) if the actual element count doesn't match {@code number}'s expected cardinality, then
   * either falling back to the first element (scalar) or comma-joining every element (list) -- shared by
   * {@link #getInfo(ReservedInfoProperty)} and {@link #getInfo(VcfMetadata, String)}, which otherwise each
   * independently repeated this same resolve-and-join block, differing only in whether {@code number}/
   * {@code isList} come from a {@link ReservedInfoProperty} or a resolved {@code ##INFO} declaration. Skipped
   * when {@code list} is the single-element {@code "."} sentinel: that represents this key's value being
   * entirely absent (e.g. an INFO string of {@code "AD="} normalizes to a single {@code "."} entry, per
   * {@code EMPTY_FIELD_HANDLING.md}), not a real value whose count could violate cardinality.
   *
   * @param label the {@code "INFO <key>"} label used in the cardinality-mismatch warning/exception text
   */
  private String resolveInfoValue(String label, String number, boolean isList, List<String> list,
      @Nullable VcfWarnings checkWarnings) {
    if (!isList && list.size() > 1) {
      InternalUtils.warnIfWrongCardinality(label, number, list.size(), getAltBases().size(), null, m_warnings,
          checkWarnings);
      return list.get(0);
    }
    if (isList && !(list.size() == 1 && list.get(0).equals("."))) {
      InternalUtils.warnIfWrongCardinality(label, number, list.size(), getAltBases().size(), null, m_warnings,
          checkWarnings);
    }
    return sf_commaJoiner.join(list);
  }

  /**
   * Returns the value for {@code id} converted to the type its {@link ReservedInfoProperty} declares, or --
   * for a non-reserved key -- the type its {@code ##INFO} declaration in {@code metadata} specifies. Unlike
   * {@link #getInfo(ReservedInfoProperty)}, this also resolves a non-reserved key's declared {@code Number}/
   * {@code Type} the way {@link VcfSample#getProperty(VcfPosition, VcfMetadata, String)} already does for
   * {@code FORMAT} -- mirroring that method's shape, minus its ploidy/{@code GT} logic, since INFO has no
   * per-sample concept ({@link #getInfo(ReservedInfoProperty)} already documents this: "no reserved INFO key
   * declares Number=G, so ploidy is always null here").
   *
   * @param <T> the type {@code id}'s declared {@code Type} specifies if it (or, for a non-reserved key, its
   * {@code ##INFO} declaration's {@code Number}) is not list-shaped; otherwise {@code List<V>} where {@code V}
   * is that type
   * @throws NullPointerException if {@code metadata} is {@code null} -- a required read-time collaborator,
   * not content being looked up (unlike {@code id}, which is null-tolerant, matching {@link #getInfo(String)}'s
   * "not present" contract); see {@code AGENTS.md}'s Public API Null-Safety Policy for the distinction
   * @throws VcfFormatException if {@code metadata} was built under a different {@link VcfContext} than this
   * position; if the raw value cannot be parsed as {@code id}'s declared {@code Type} at all -- unconditionally,
   * regardless of context, since there is no sane value to preserve or normalize to; this is a genuinely
   * deferred check ({@code VcfParser} itself stays lenient and finishes parsing the file regardless), and
   * nothing is lost even when it throws, since the original wire text for {@code id} remains available
   * afterward via {@link #getInfo(String)}/{@link #getInfo()}; if a declared
   * {@code Type=Integer} value is not representable as a 32-bit signed integer or collides with a BCF-reserved
   * sentinel value (see {@link InternalUtils#checkInteger32BitRange}); if the raw value's actual element count
   * does not match {@code id}'s declared/reserved {@code Number} and this position was built under a strict
   * {@link VcfContext} (under a lenient one, this instead warns and, for a scalar-declared key given more than
   * one comma-piece, keeps only the first -- see {@link InternalUtils#warnIfWrongCardinality}); or if the raw
   * value is an empty (but non-{@code "."}) entry and this position was built under a strict {@link VcfContext}
   * (under a lenient one, an empty value instead returns {@code null} -- see
   * {@link InternalUtils#convertProperty(Class, String, boolean, VcfWarnings, VcfWarnings)})
   */
  public @Nullable <T> T getInfo(VcfMetadata metadata, String id) {
    if (id == null) {
      return null;
    }
    Preconditions.checkNotNull(metadata, "metadata must not be null");
    if (metadata.getContext() != m_context) {
      throw new VcfFormatException("getInfo() was given metadata created under a different VcfContext " +
          "than this position; the read would have no unambiguous version or warnings destination");
    }
    List<String> list = info().get(id);
    if (list.isEmpty()) {
      return null;
    }
    // version-aware: AD/ADF/ADR have no VCFv4.2 declaration at all, so from there they take the non-reserved
    // branch below (validated against their own ##INFO declaration, or the standard "no metadata declaration"
    // warning when undeclared) instead of this library's reserved canonical Number/Type -- the INFO-side
    // mirror of VcfSample.getProperty(VcfPosition, VcfMetadata, String)'s identical GLE/version-gated dispatch
    ReservedInfoProperty reserved = ReservedInfoProperty.fromId(id, m_context.getVersion());
    InfoMetadata declared = null;
    String number;
    boolean isList;
    if (reserved != null) {
      number = reserved.getExpectedNumber();
      isList = reserved.isList();
    } else {
      declared = metadata.getInfo().get(id);
      if (declared == null) {
        m_warnings.warn("INFO {} has no metadata declaration; cannot determine its Number/Type", id);
        return null;
      }
      number = declared.getNumber();
      if (number == null) {
        number = ".";
      }
      isList = !number.equals("0") && !number.equals("1");
    }
    // isLenient()-aware, shared by both cardinality-check branches inside resolveInfoValue(), the range check
    // further down, and convertProperty's own empty-value substitution -- see
    // InternalUtils.convertProperty(Class, String, boolean, VcfWarnings, VcfWarnings)'s own Javadoc for the
    // general pattern.
    VcfWarnings checkWarnings = m_context.isLenient() ? m_warnings : null;
    // same shared helper as getInfo(ReservedInfoProperty) above, so both routes stay in sync rather than each
    // independently drifting
    String value = resolveInfoValue("INFO " + id, number, isList, list, checkWarnings);
    if (reserved != null) {
      try {
        return InternalUtils.convertProperty(reserved, value, m_context);
      } catch (VcfFormatException ex) {
        throw withLineNumber(ex);
      }
    }
    InfoType infoType = declared.getType();
    Class<?> type = infoType == null ? String.class : InternalUtils.toJavaType(infoType);
    try {
      if (type == Long.class) {
        InternalUtils.checkInteger32BitRange("INFO " + id, value, isList, checkWarnings);
      }
      return InternalUtils.convertProperty(type, value, isList, m_warnings, checkWarnings);
    } catch (VcfFormatException ex) {
      throw withLineNumber(ex);
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
    private boolean m_refDirty;
    private @Nullable List<String> m_altBases;
    private boolean m_altBasesDirty;
    private @Nullable VcfFloat m_quality;
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

    /** Marks {@code ref} dirty for {@link #build()} to re-run {@code checkAltBases} against, not just
     * {@code ref}'s own {@code checkRef} -- since {@code checkAltBases}'s ALT-identical-to-REF check
     * (added after this class's dirty-tracking scheme was designed) is a cross-field check depending on REF,
     * not just on ALT, changing REF alone must not leave a stale ALT considered "already known valid" against
     * the new REF. */
    public Builder ref(String ref) {
      // ref is typed non-@Nullable, but nothing enforces that at runtime; see chromosome(String)'s identical
      // reasoning above
      if (ref == null) {
        throw new VcfFormatException("REF is null");
      }
      seedRef(ref);
      m_refDirty = true;
      return this;
    }

    /** Seeds {@code ref} from an already-validated source without marking it dirty; see this class's own
     * Javadoc (the {@code ids}/{@code altBases}/... seeding paragraph, which this follows identically). Called
     * from {@link #toBuilder()} instead of the public {@link #ref(String)} setter, so copying a position's own
     * already-valid REF into a fresh builder does not spuriously mark ALT dirty for {@link #build()} to
     * re-check against it. */
    private void seedRef(String ref) {
      m_ref = ref;
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
    public Builder quality(@Nullable VcfFloat quality) {
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
     * re-validate. Values are raw wire text -- this library never percent-decodes or percent-encodes an INFO
     * value; see {@link VcfPercentEncoding} if you need semantic text. */
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
     * reasoning for why {@code VcfParser} does not use this method either. This text is never percent-decoded;
     * see {@link VcfPercentEncoding} if you need semantic text.
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
      // m_refDirty as well as m_altBasesDirty: checkAltBases's ALT-identical-to-REF check is a cross-field
      // check depending on REF too, so changing REF alone (via ref(String), which marks m_refDirty but not
      // m_altBasesDirty) must still re-run it against the new REF, not leave a stale, unchanged ALT list
      // treated as "already known valid" against a REF it was never actually checked against.
      if ((m_altBasesDirty || m_refDirty) && m_altBases != null) {
        m_altBases = checkAltBases(m_altBases, m_ref, checkWarnings);
      }
      // originatedPublicly=true on all three below: Builder is exclusively public API (VcfParser never
      // constructs or calls one internally), and each field is only re-checked here when its own dirty flag
      // marks it as freshly, publicly supplied via a setter -- exactly the "genuinely public construction"
      // case the bcftools-floor checks inside checkFilters/checkInfoEntries/checkFormat must catch. An
      // unchanged field already passed (or was exempt from) this same check when it was first seeded, so
      // this never re-throws on content this build() call didn't itself introduce.
      if (m_filterDirty && m_filter != null) {
        m_filter = checkFilters(m_filter, checkWarnings, true);
      }
      if (m_infoDirty && m_info != null) {
        m_info = checkInfoEntries(m_info, checkWarnings, true);
        checkReservedInfoTypes(m_info, m_context);
      }
      if (m_formatDirty && m_format != null) {
        m_format = checkFormat(m_format, checkWarnings, true);
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

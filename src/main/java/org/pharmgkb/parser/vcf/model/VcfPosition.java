package org.pharmgkb.parser.vcf.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.base.Joiner;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.pharmgkb.parser.vcf.VcfWarnings;


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
 * @author Mark Woon
 */
public class VcfPosition {

  private static final Joiner sf_commaJoiner = Joiner.on(",");
  private String m_chromosome;
  private long m_position;
  private List<String> m_ids = new ArrayList<>();
  private String m_refBases;
  private List<String> m_altBases = new ArrayList<>();
  // QUAL is stored either as a parsed BigDecimal (eager path) or as raw text parsed lazily on first getQuality()
  // (parser path via setRawQuality); many consumers never read QUAL.
  private @Nullable BigDecimal m_quality;
  private @Nullable String m_rawQuality;
  private List<String> m_filter = new ArrayList<>();
  // INFO is stored either as a parsed multimap (eager constructor path) or as raw text parsed lazily on first access
  // (parser path via setRawInfo); at most one is non-null at a time.
  private @Nullable ListMultimap<String, String> m_info;
  private @Nullable String m_rawInfo;
  private List<String> m_format = new ArrayList<>();
  // originating line number in the source VCF file, or -1 if this position wasn't produced by parsing a real
  // file (e.g. constructed directly by a caller/test) -- used to attach line-number context to any
  // VcfFormatException this instance's own validation throws, guarded so a lineNumber of -1 (not produced by
  // parsing) leaves exception behavior unchanged.
  private int m_lineNumber = -1;


  /**
   * Takes ownership of {@code ids}, {@code altBases}, {@code filter}, {@code format}, and {@code info}: this
   * constructor does not defensively copy any of them when no normalization is needed, to avoid an unnecessary
   * allocation on this library's hot parse path (mirroring {@link VcfSample}'s own no-copy ownership contract).
   * {@link #getIds()}, {@link #getAltBases()}, {@link #getFilters()}, {@link #getFormat()}, and
   * {@link #getInfo()} return these same objects (or an equivalent list/multimap produced by normalization); the
   * returned list/multimap may be immutable if one was passed to this constructor and did not need normalizing --
   * pass a mutable {@code List}/{@code ListMultimap} (not e.g. {@code List.of(...)}) if you intend to mutate a
   * position's IDs, ALT bases, filters, format, or INFO after construction.
   *
   * @param lineNumber the originating line number in the source VCF file, or {@code -1} if this position was not
   * produced by parsing a real file (e.g. constructed directly by a caller or test)
   */
  public VcfPosition(String chr, long pos,
      @Nullable List<String> ids,
      String ref,
      @Nullable List<String> altBases,
      @Nullable BigDecimal qual,
      @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info,
      @Nullable List<String> format,
      int lineNumber) {

    /*
      1. Check the arguments, in order
     */

    checkChromosome(chr);
    checkPosition(pos);
    if (ids != null) {
      ids = checkIds(ids);
    }
    checkRef(ref);
    if (altBases != null) {
      altBases = checkAltBases(altBases);
    }
    if (filter != null) {
      filter = checkFilters(filter);
    }
    if (info != null) {
      checkInfoEntries(info.entries());
    }
    if (format != null) {
      format = checkFormat(format);
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
  }

  /**
   * @param lineNumber the originating line number in the source VCF file, or {@code -1} if this position was not
   * produced by parsing a real file (e.g. constructed directly by a caller or test)
   */
  public VcfPosition(String chromosome, long position, String refBases, BigDecimal quality, int lineNumber) {
    checkChromosome(chromosome);
    checkPosition(position);
    checkRef(refBases);
    m_chromosome = chromosome;
    m_position = position;
    m_refBases = refBases;
    m_quality = quality;
    m_lineNumber = lineNumber; // required (-1 if not produced by parsing a real line)
  }

  private static void checkChromosome(String chr) {
    // the VCF spec forbids whitespace in CHROM (but not other characters, e.g. colons)
    if (chr.isEmpty() || VcfUtils.containsWhitespace(chr)) {
      throw new VcfFormatException("CHROM column \"" + chr + "\" is empty or contains whitespace");
    }
  }

  private static void checkPosition(long pos) {
    // POS 0 is reserved for telomeres, but a negative position is invalid
    if (pos < 0) {
      throw new VcfFormatException("POS " + pos + " is negative");
    }
  }

  private static void checkRef(String ref) {
    if (!VcfUtils.REF_BASE_PATTERN.matcher(ref).matches()) {
      throw new VcfFormatException("Invalid reference base '" + ref +
          "' (must match " + VcfUtils.REF_BASE_PATTERN + ")");
    }
  }

  /**
   * @return {@code ids} unchanged, or a new list with any empty entry dropped (with a warning); see
   * {@code EMPTY_FIELD_HANDLING.md}
   */
  private static List<String> checkIds(List<String> ids) {
    ids = VcfUtils.dropEmptyEntries("ID", ids);
    Set<String> seenIds = new HashSet<>();
    for (String id : ids) {
      if (VcfUtils.containsWhitespace(id) || id.contains(";")) {
        throw new VcfFormatException("ID \"" + id + "\" contains whitespace or semicolons");
      }
      if (!seenIds.add(id)) {
        throw new VcfFormatException("Duplicate ID \"" + id + "\"");
      }
    }
    return ids;
  }

  /**
   * @return {@code altBases} unchanged, or a new list derived from it with any empty entry substituted with
   * {@code "."} (with a warning); see {@code EMPTY_FIELD_HANDLING.md}
   */
  private static List<String> checkAltBases(List<String> altBases) {
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
      VcfUtils.fillEmptyEntriesWithDot("ALT", altBases);
    }
    for (String base : altBases) {
      if (base == null) {
        throw new VcfFormatException("ALT contains a null entry");
      }
      if (!VcfUtils.ALT_BASE_PATTERN.matcher(base).matches()) {
        throw new VcfFormatException("Invalid alternate base '" + base + "' (must match " + VcfUtils.ALT_BASE_PATTERN + ")");
      }
    }
    return altBases;
  }

  /**
   * Checks the given FILTER values for validity. This does not perform the construction-time normalization of a lone
   * {@code "PASS"} or {@code "."} value (see the constructor); those are legal single-element lists on their own.
   *
   * @return {@code filters} unchanged, or a new list with any empty entry dropped (with a warning); see
   * {@code EMPTY_FIELD_HANDLING.md}
   */
  private static List<String> checkFilters(List<String> filters) {
    filters = VcfUtils.dropEmptyEntries("FILTER", filters);
    for (String f : filters) {
      if (VcfUtils.containsWhitespace(f)) {
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
        VcfWarnings.warn("FILTER contains PASS along with other filters: {}", filters);
      }
      if (f.equals(".") && filters.size() > 1) {
        throw new VcfFormatException("FILTER contains '.' (missing value) along with other filters!");
      }
    }
    return filters;
  }

  private static void checkInfoEntries(Iterable<Map.Entry<String, String>> entries) {
    for (Map.Entry<String, String> entry : entries) {
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
      if (VcfUtils.containsWhitespace(key) || VcfUtils.containsWhitespace(value)) {
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
        VcfWarnings.warn("INFO value \"{}\" for key \"{}\" contains '='; VCFv4.2's grammar doesn't permit this, but " +
            "bcftools 1.23.1 accepts it silently, so it is preserved as-is", value, key);
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
   * silent, this is treated as valid here too.
   */
  private static List<String> checkFormat(List<String> format) {
    if (format.size() == 1 && ".".equals(format.get(0))) {
      return new ArrayList<>();
    }
    // duplicate keys are explicitly disallowed by the VCF spec (VCFv4.3+ states this outright; empty keys are
    // excluded from this check since they're already handled, and warned about, separately below)
    Set<String> seenKeys = new HashSet<>();
    for (String f : format) {
      if (f == null) {
        throw new VcfFormatException("FORMAT contains a null sub-field name");
      }
      // an empty sub-field name is kept as-is rather than dropped: every sample's colon-split values are matched to
      // FORMAT keys by index, and dropping this key would misalign every sample's values with the wrong key. See
      // EMPTY_FIELD_HANDLING.md.
      if (f.isEmpty()) {
        VcfWarnings.warn("FORMAT contains an empty sub-field name (VCF does not allow zero-length fields); keeping " +
            "it as-is, but it cannot be looked up in metadata");
        continue;
      }
      // f.contains(":") is not checked separately: FORMAT_PATTERN's character class ([0-9A-Za-z_.]) never
      // includes ':', so matches() alone already rejects any f containing one.
      if (!VcfUtils.FORMAT_PATTERN.matcher(f).matches()) {
        throw new VcfFormatException("FORMAT ID does not match VCF spec");
      }
      if (!seenKeys.add(f)) {
        throw new VcfFormatException("Duplicate FORMAT key \"" + f + "\"");
      }
    }
    if (format.indexOf("GT") > 0) {
      throw new VcfFormatException("FORMAT GT must be the first sub-field when present");
    }
    return format;
  }

  /**
   * Normalizes a lone {@code "PASS"} or {@code "."} in {@link #m_filter} into a fresh, independently-mutable list
   * -- {@code "."} becomes an empty list ({@link FilterStatus#NONE}); {@code "PASS"} is copied into a new
   * single-element list containing just {@code "PASS"} (still {@link FilterStatus#PASSED}), so a caller can mutate
   * it afterward via {@link #getFilters()} (e.g. {@code clear()}) even if the list originally passed to the
   * constructor was immutable. Neither case is a caller mistake to warn about -- {@code "PASS"} is itself the sole
   * representation of {@link FilterStatus#PASSED}. Assumes {@link #checkFilters} has already validated
   * {@link #m_filter}. Idempotent: a no-op once {@link #m_filter} no longer has exactly one such element.
   */
  private void normalizeFilters() {
    if (m_filter.size() == 1) {
      if (m_filter.get(0).equals(".")) {
        m_filter = new ArrayList<>();
      } else if (m_filter.get(0).equals("PASS")) {
        m_filter = new ArrayList<>(m_filter);
      }
    }
  }

  /**
   * Re-validates this position's current field values, throwing {@link VcfFormatException} if any are no longer valid.
   * As a side effect (matching the constructor), normalizes a lone {@code "."} FILTER value left by a mutation so
   * that {@link #getFilterStatus()} continues to report correctly.
   * <p>
   * The constructors validate their arguments, but {@link #setChromosome}, {@link #setRef}, {@link #setPosition}, and
   * mutations made through {@link #getIds}, {@link #getAltBases}, {@link #getFilters}, {@link #getFormat}, and
   * {@link #getInfo()} do <em>not</em> validate their input, to support transformation pipelines (e.g.
   * {@link org.pharmgkb.parser.vcf.VcfTransformation}) that mutate a position in place. Each of those accessors may
   * return an immutable list/multimap if one was passed to the primary constructor and did not need normalizing --
   * see its Javadoc. Call this method after such mutations if you need to confirm the position is still valid.
   * <p>
   * {@link #getFilterStatus()} is always derived directly from {@link #getFilters()}'s current contents (see its
   * Javadoc), so emptying the FILTER list by any means -- including {@code clear()} -- always produces
   * {@link FilterStatus#NONE}, regardless of what the list contained before. {@link FilterStatus#PASSED} is
   * always an explicit opt-in: set {@link #getFilters()} to the single-element list {@code ["PASS"]} and call this
   * method to reach it.
   * <p>
   * The whole method body is wrapped in a single guarded catch rather than annotating individual checks:
   * {@link #m_lineNumber} identifies which line this record came from, not which line a specific value came from,
   * so any validation failure on this record gets the same line-number attachment.
   */
  public void validate() {
    try {
      checkChromosome(m_chromosome);
      checkPosition(m_position);
      m_ids = checkIds(m_ids);
      checkRef(m_refBases);
      m_altBases = checkAltBases(m_altBases);
      m_filter = checkFilters(m_filter);
      normalizeFilters();
      checkInfoEntries(info().entries());
      m_format = checkFormat(m_format);
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

  public void setChromosome(String chromosome) {
    m_chromosome = chromosome;
  }

  public void setRef(String ref) {
    m_refBases = ref;
  }

  public long getPosition() {
    return m_position;
  }

  public void setPosition(long position) {
    m_position = position;
  }

  /**
   * Gets the list of unique identifiers for this position.
   */
  public List<String> getIds() {
    return m_ids;
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
    return m_altBases;
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
          m_quality = new BigDecimal(raw);
        } catch (NumberFormatException e) {
          // a non-numeric QUAL is a value-quality issue, not a structural one (the column itself is well-formed);
          // warn and recover as the missing value, consistent with how other non-structural content issues are
          // handled, rather than throwing
          VcfWarnings.warn("QUAL '{}' is not a number; treating it as the missing value", raw);
        }
      }
    }
    return m_quality;
  }

  /**
   * Sets the raw QUAL column text, to be parsed lazily on first {@link #getQuality()}. Used by the parser so positions
   * whose QUAL is never read do not pay to build a {@link BigDecimal}.
   */
  public void setRawQuality(@Nullable String rawQuality) {
    m_rawQuality = rawQuality;
    m_quality = null;
  }

  public void setQuality(@Nullable BigDecimal quality) {
    m_quality = quality;
    m_rawQuality = null;
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
    return m_filter;
  }

  /**
   * Sets the raw INFO column text, to be parsed lazily on first access. Used by the parser so positions whose INFO is
   * never read do not pay to build the INFO multimap.
   */
  public void setRawInfo(@Nullable String rawInfo) {
    m_rawInfo = rawInfo;
    m_info = null;
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
            VcfWarnings.warn("INFO contains an empty entry between ';'s (VCF does not allow zero-length fields); " +
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
            VcfWarnings.warn("INFO entry \"{}\" has an empty key (VCF does not allow zero-length fields); " +
                "dropping it", prop);
            continue;
          }
          if (!seenKeys.add(key)) {
            VcfWarnings.warn("INFO contains duplicate key \"{}\"; keeping only the first occurrence, matching " +
                "bcftools 1.23.1 behavior", key);
            continue;
          }
          if (idx == -1) {
            info.put(key, "");
          } else {
            for (String value : prop.substring(idx + 1).split(",", -1)) {
              if (value.isEmpty()) {
                VcfWarnings.warn("INFO value for key \"{}\" contains an empty entry (VCF does not allow zero-length " +
                    "fields); treating it as the missing value '.'", key);
                value = ".";
              }
              info.put(key, value);
            }
          }
        }
        try {
          checkInfoEntries(info.entries());
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
    return info();
  }

  /**
   * Get INFO metadata with the specified ID.
   *
   * @return list of values or null if there is no INFO metadata for the specified id
   */
  public @Nullable List<String> getInfo(String id) {
    ListMultimap<String, String> info = info();
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
    List<String> list = info().get(key.getId());
    if (list.isEmpty()) {
      return null;
    }
    try {
      return VcfUtils.convertProperty(key, sf_commaJoiner.join(list));
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
    return hasInfo(key.getId());
  }

  public List<String> getFormat() {
    return m_format;
  }

  public Set<String> getInfoKeys() {
    return info().keySet();
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
}

package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final Joiner sf_commaJoiner = Joiner.on(",");
  // matched with find(), not matches(): a wrapping ".*\s.*" pattern would fail to detect whitespace in a string
  // containing 2+ line-terminator characters, since "." does not match line terminators without DOTALL
  private static final Pattern sf_whitespace = Pattern.compile("\\s");
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
  // false only when FILTER was the missing value "." (filters not applied); distinguishes NONE from PASSED.
  private boolean m_filtersApplied = true;
  // INFO is stored either as a parsed multimap (eager constructor path) or as raw text parsed lazily on first access
  // (parser path via setRawInfo); at most one is non-null at a time.
  private @Nullable ListMultimap<String, String> m_info;
  private @Nullable String m_rawInfo;
  private List<String> m_format = new ArrayList<>();


  public VcfPosition(String chr, long pos,
      @Nullable List<String> ids,
      String ref,
      @Nullable List<String> altBases,
      @Nullable BigDecimal qual,
      @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info,
      @Nullable List<String> format) {

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
      checkFormat(format);
    }

    /*
      2. Set the fields, in order
     */

    // not resolving ID string
    m_chromosome = chr; // required
    m_position = pos; // required

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

  public VcfPosition(String chromosome, long position, String refBases, BigDecimal quality) {
    checkChromosome(chromosome);
    checkPosition(position);
    checkRef(refBases);
    m_chromosome = chromosome;
    m_position = position;
    m_refBases = refBases;
    m_quality = quality;
  }

  private static void checkChromosome(String chr) {
    // the VCF spec forbids whitespace in CHROM (but not other characters, e.g. colons)
    if (chr.isEmpty() || sf_whitespace.matcher(chr).find()) {
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
    ids = VcfUtils.dropEmptyEntries(sf_logger, "ID", ids);
    Set<String> seenIds = new HashSet<>();
    for (String id : ids) {
      if (sf_whitespace.matcher(id).find() || id.contains(";")) {
        throw new VcfFormatException("ID \"" + id + "\" contains whitespace or semicolons");
      }
      if (!seenIds.add(id)) {
        throw new VcfFormatException("Duplicate ID \"" + id + "\"");
      }
    }
    return ids;
  }

  /**
   * @return {@code altBases} unchanged, or a new list with any empty entry dropped (with a warning); see
   * {@code EMPTY_FIELD_HANDLING.md}
   */
  private static List<String> checkAltBases(List<String> altBases) {
    altBases = VcfUtils.dropEmptyEntries(sf_logger, "ALT", altBases);
    for (String base : altBases) {
      if (!VcfUtils.ALT_BASE_PATTERN.matcher(base).matches()) {
        throw new VcfFormatException("Invalid alternate base '" + base + "' (must match " + VcfUtils.ALT_BASE_PATTERN + ")");
      }
    }
    // "." is the missing value, representing "no alternate allele"; it cannot be combined with a real allele
    if (altBases.size() > 1 && altBases.contains(".")) {
      throw new VcfFormatException("ALT contains '.' (missing value) along with other alleles");
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
    filters = VcfUtils.dropEmptyEntries(sf_logger, "FILTER", filters);
    for (String f : filters) {
      if (sf_whitespace.matcher(f).find()) {
        throw new VcfFormatException("FILTER column entry \"" + f + "\" contains whitespace");
      }
      if (f.equals("0")) {
        throw new VcfFormatException("FILTER column entry should not be 0");
      }
      if (f.equals("PASS") && filters.size() > 1) {
        throw new VcfFormatException("FILTER contains PASS along with other filters!");
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
      if (sf_whitespace.matcher(key).find() || sf_whitespace.matcher(value).find()) {
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
    }
  }

  private static void checkFormat(List<String> format) {
    // duplicate keys are explicitly disallowed by the VCF spec (VCFv4.3+ states this outright; empty keys are
    // excluded from this check since they're already handled, and warned about, separately below)
    Set<String> seenKeys = new HashSet<>();
    for (String f : format) {
      // an empty sub-field name is kept as-is rather than dropped: every sample's colon-split values are matched to
      // FORMAT keys by index, and dropping this key would misalign every sample's values with the wrong key. See
      // EMPTY_FIELD_HANDLING.md.
      if (f.isEmpty()) {
        sf_logger.warn("FORMAT contains an empty sub-field name (VCF does not allow zero-length fields); keeping " +
            "it as-is, but it cannot be looked up in metadata");
        continue;
      }
      if (!VcfUtils.FORMAT_PATTERN.matcher(f).matches() || f.contains(":")) {
        throw new VcfFormatException("FORMAT ID does not match VCF spec");
      }
      if (!seenKeys.add(f)) {
        throw new VcfFormatException("Duplicate FORMAT key \"" + f + "\"");
      }
    }
    if (format.indexOf("GT") > 0) {
      throw new VcfFormatException("FORMAT GT must be the first sub-field when present");
    }
  }

  /**
   * Normalizes a lone {@code "PASS"} or {@code "."} in {@link #m_filter} to an empty list (setting
   * {@link #m_filtersApplied} for {@code "."}), exactly as the constructor does. Assumes {@link #checkFilters} has
   * already validated {@link #m_filter}. Idempotent: a no-op once {@link #m_filter} no longer has exactly one element.
   */
  private void normalizeFilters() {
    if (m_filter.size() == 1) {
      if (m_filter.get(0).equals("PASS")) { // a user is likely to pass "PASS" instead of an empty list or null
        sf_logger.warn("FILTER is PASS, but should have been passed as null. Converting to null");
        m_filter = new ArrayList<>();
      } else if (m_filter.get(0).equals(".")) { // "." is the missing value: filters were not applied (FilterStatus.NONE)
        m_filtersApplied = false;
        m_filter = new ArrayList<>();
      }
    }
  }

  /**
   * Re-validates this position's current field values, throwing {@link VcfFormatException} if any are no longer valid.
   * As a side effect (matching the constructor), normalizes a lone {@code "PASS"} or {@code "."} FILTER value left by a
   * mutation so that {@link #getFilterStatus()} continues to report correctly.
   * <p>
   * The constructors validate their arguments, but {@link #setChromosome}, {@link #setRef}, {@link #setPosition}, and
   * the mutable lists returned by {@link #getIds}, {@link #getAltBases}, {@link #getFilters}, {@link #getFormat}, and
   * {@link #getInfo()} do <em>not</em> validate their input, to support transformation pipelines (e.g.
   * {@link org.pharmgkb.parser.vcf.VcfTransformation}) that mutate a position in place. Call this method after such
   * mutations if you need to confirm the position is still valid.
   */
  public void validate() {
    checkChromosome(m_chromosome);
    checkPosition(m_position);
    checkRef(m_refBases);
    m_ids = checkIds(m_ids);
    m_altBases = checkAltBases(m_altBases);
    m_filter = checkFilters(m_filter);
    normalizeFilters();
    checkInfoEntries(info().entries());
    checkFormat(m_format);
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
          sf_logger.warn("QUAL '{}' is not a number; treating it as the missing value", raw);
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
   * Returns the FILTER status for this position:
   * <ul>
   *   <li>{@link FilterStatus#NONE} if filters were not applied (FILTER was the missing value "{@code .}")</li>
   *   <li>{@link FilterStatus#PASSED} if the position passed all filters (FILTER was "{@code PASS}", or no filters are
   *       set)</li>
   *   <li>{@link FilterStatus#FAILED} if the position failed one or more filters</li>
   * </ul>
   */
  public FilterStatus getFilterStatus() {
    if (!m_filter.isEmpty()) {
      return FilterStatus.FAILED;
    }
    return m_filtersApplied ? FilterStatus.PASSED : FilterStatus.NONE;
  }

  /**
   * Returns true if this position did not fail any filters. Note that this is true for both {@link FilterStatus#PASSED}
   * and {@link FilterStatus#NONE} (no filters applied is treated as passing); use {@link #getFilterStatus()} to
   * distinguish those two cases.
   */
  public boolean isPassingAllFilters() {
    return m_filter.isEmpty();
  }

  /**
   * Returns a list of filters this position failed, if any. This is empty for both {@link FilterStatus#PASSED} and
   * {@link FilterStatus#NONE}; use {@link #getFilterStatus()} to distinguish those cases.
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
      info = ArrayListMultimap.create();
      String raw = m_rawInfo;
      if (raw != null && !raw.isEmpty() && !raw.equals(".")) {
        // limit -1 preserves an empty prop/value (e.g. a trailing ';' or ','), handled below rather than silently
        // dropped; VCF does not allow zero-length fields. See EMPTY_FIELD_HANDLING.md.
        List<String> props = new ArrayList<>();
        for (String prop : raw.split(";", -1)) {
          if (prop.isEmpty()) {
            sf_logger.warn("INFO contains an empty entry between ';'s (VCF does not allow zero-length fields); " +
                "dropping it");
            continue;
          }
          props.add(prop);
        }
        // "." (missing value: no INFO at all) cannot be combined with a real property, the same as ALT/FILTER's
        // exclusivity rule for their own "." sentinel; unlike an empty prop (dropped above), this isn't a stray
        // delimiter artifact, it directly contradicts "." meaning "nothing here"
        if (props.contains(".") && props.size() > 1) {
          throw new VcfFormatException("INFO contains '.' (missing value) along with other properties");
        }
        for (String prop : props) {
          int idx = prop.indexOf('=');
          if (idx == -1) {
            info.put(prop, "");
          } else {
            String key = prop.substring(0, idx);
            if (key.isEmpty()) {
              sf_logger.warn("INFO entry \"{}\" has an empty key (VCF does not allow zero-length fields); " +
                  "dropping it", prop);
              continue;
            }
            for (String value : prop.substring(idx + 1).split(",", -1)) {
              if (value.isEmpty()) {
                sf_logger.warn("INFO value for key \"{}\" contains an empty entry (VCF does not allow zero-length " +
                    "fields); treating it as the missing value '.'", key);
                value = ".";
              }
              info.put(key, value);
            }
          }
        }
        checkInfoEntries(info.entries());
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
   *   BigDecimal bq = vcfPosition.getInfo(ReservedInfoProperty.BaseQuality);
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
    return VcfUtils.convertProperty(key, sf_commaJoiner.join(list));
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
    /** Filters were not applied (FILTER was the missing value "{@code .}"). */
    NONE,
    /** The position passed all filters (FILTER was "{@code PASS}"). */
    PASSED,
    /** The position failed one or more filters. */
    FAILED
  }
}

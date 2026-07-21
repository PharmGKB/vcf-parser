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
  private static final Pattern sf_whitespace = Pattern.compile(".*\\s.*");
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
      Set<String> seenIds = new HashSet<>();
      for (String id : ids) {
        if (sf_whitespace.matcher(id).matches() || id.contains(";")) {
          throw new VcfFormatException("ID \"" + id + "\" contains whitespace or semicolons");
        }
        if (!seenIds.add(id)) {
          throw new VcfFormatException("Duplicate ID \"" + id + "\"");
        }
      }
    }

    checkRef(ref);

    if (altBases != null) {
      for (String base : altBases) {
        if (!VcfUtils.ALT_BASE_PATTERN.matcher(base).matches()) {
          throw new VcfFormatException("Invalid alternate base '" + base + "' (must be [AaGgCcTtNn\\*]+ or <.+>)");
        }
      }
    }

    if (filter != null) {
      for (String f : filter) {
        if (sf_whitespace.matcher(f).matches()) {
          throw new VcfFormatException("FILTER column entry \"" + f + "\" contains whitespace");
        }
        if (f.equals("0")) {
          throw new VcfFormatException("FILTER column entry should not be 0");
        }
        if (f.equals("PASS")) {
          if (filter.size() == 1) { // a user is likely to pass "PASS" instead of an empty list or null
            sf_logger.warn("FILTER is PASS, but should have been passed as null. Converting to null");
            filter = null;
            break; // unnecessary, but gets rid of the warning
          } else { // but this is illegal per VCF spec
            throw new VcfFormatException("FILTER contains PASS along with other filters!");
          }
        }
        if (f.equals(".")) {
          if (filter.size() == 1) { // "." is the missing value: filters were not applied (FilterStatus.NONE)
            m_filtersApplied = false;
            filter = null;
            break;
          } else { // "." must not be combined with actual filter codes
            throw new VcfFormatException("FILTER contains '.' (missing value) along with other filters!");
          }
        }
      }
    }

    if (info != null) {
      for (Map.Entry<String, String> entry : info.entries()) {
        if (sf_whitespace.matcher(entry.getKey()).matches() || sf_whitespace.matcher(entry.getValue()).matches()) {
          throw new VcfFormatException("INFO column entry \"" + entry.getKey() + "=" + entry.getValue() +
              "\" contains whitespace");
        }
      }
    }

    if (format != null) {
      for (String f : format) {
        if (!VcfUtils.FORMAT_PATTERN.matcher(f).matches() || f.contains(":")) {
          throw new VcfFormatException("FORMAT ID does not match VCF spec");
        }
      }
      if (format.indexOf("GT") > 0) {
        throw new VcfFormatException("FORMAT GT must be the first sub-field when present");
      }
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
    if (chr.isEmpty() || sf_whitespace.matcher(chr).matches()) {
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
          throw new VcfFormatException("QUAL '" + raw + "' is not a number");
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
        for (String prop : raw.split(";")) {
          int idx = prop.indexOf('=');
          if (idx == -1) {
            info.put(prop, "");
          } else {
            String key = prop.substring(0, idx);
            for (String value : prop.substring(idx + 1).split(",")) {
              info.put(key, value);
            }
          }
        }
        for (Map.Entry<String, String> entry : info.entries()) {
          if (sf_whitespace.matcher(entry.getKey()).matches() || sf_whitespace.matcher(entry.getValue()).matches()) {
            throw new VcfFormatException("INFO column entry \"" + entry.getKey() + "=" + entry.getValue() +
                "\" contains whitespace");
          }
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
   *   BigDecimal bq = vcfPosition.getInfoConverted(ReservedInfoProperty.BaseQuality);
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

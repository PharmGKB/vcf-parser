package org.pharmgkb.parser.vcf.model;

import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;


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
  private List<String> m_alleles = new ArrayList<>();
  private BigDecimal m_quality;
  private List<String> m_filter = new ArrayList<>();
  private ListMultimap<String, String> m_info = ArrayListMultimap.create();
  private List<String> m_format = new ArrayList<>();


  public VcfPosition(@Nonnull String chr, long pos,
      @Nullable List<String> ids,
      @Nonnull String ref,
      @Nullable List<String> altBases,
      @Nullable BigDecimal qual,
      @Nullable List<String> filter,
      @Nullable ListMultimap<String, String> info,
      @Nullable List<String> format) {

    /*
      1. Check the arguments, in order
     */

    if (sf_whitespace.matcher(chr).matches()  || chr.contains(":")) {
      throw new IllegalArgumentException("CHROM column \"" + chr + "\" contains whitespace or colons");
    }

    // allow pos < 1 because that's reserved for telomers

    if (ids != null) {
      for (String id : ids) {
        if (sf_whitespace.matcher(id).matches() || id.contains(";")) {
          throw new IllegalArgumentException("ID \"" + id + "\" contains whitespace or semicolons");
        }
      }
    }

    if (!VcfUtils.REF_BASE_PATTERN.matcher(ref).matches()) {
      throw new IllegalArgumentException("Invalid reference base '" + ref +
          "' (must match " + VcfUtils.REF_BASE_PATTERN +")");
    }

    if (altBases != null) {
      for (String base : altBases) {
        if (!VcfUtils.ALT_BASE_PATTERN.matcher(base).matches()) {
          throw new IllegalArgumentException("Invalid alternate base '" + base + "' (must be [AaGgCcTtNn\\*]+ or <.+>)");
        }
      }
    }

    if (filter != null) {
      for (String f : filter) {
        if (sf_whitespace.matcher(f).matches()) {
          throw new IllegalArgumentException("FILTER column entry \"" + f + "\" contains whitespace");
        }
        if (f.equals("0")) {
          throw new IllegalArgumentException("FILTER column entry should not be 0");
        }
        if (f.equals("PASS")) {
          if (filter.size() == 1) { // a user is likely to pass "PASS" instead of an empty list or null
            sf_logger.warn("FILTER is PASS, but should have been passed as null. Converting to null");
            filter = null;
            break; // unnecessary, but gets rid of the warning
          } else { // but this is illegal per VCF spec
            throw new IllegalArgumentException("FILTER contains PASS along with other filters!");
          }
        }
      }
    }

    if (info != null) {
      for (Map.Entry<String, String> entry : info.entries()) {
        if (sf_whitespace.matcher(entry.getKey()).matches() || sf_whitespace.matcher(entry.getValue()).matches()) {
          throw new IllegalArgumentException("INFO column entry \"" + entry.getKey() + "=" + entry.getValue() +
              "\" contains whitespace");
        }
      }
    }

    if (format != null) {
      for (String f : format) {
        if (!VcfUtils.FORMAT_PATTERN.matcher(f).matches() || f.contains(":")) {
          throw new IllegalArgumentException("FORMAT column is not alphanumeric");
        }
      }
    }

    /*
      2. Set the fields, in order
     */

    // not resolving ID string
    m_chromosome = chr; // required
    m_position = pos; // required

    if (ids != null) {
      m_ids = new ArrayList<>(ids);
    }

    m_refBases = ref; // required
    m_alleles.add(m_refBases);

    if (altBases != null) {
      m_altBases = new ArrayList<>(altBases);
      m_alleles.addAll(altBases);
    }

    m_quality = qual; // required

    if (filter != null) {
      m_filter = new ArrayList<>(filter);
    }

    if (info != null) {
      m_info = ArrayListMultimap.create();
      m_info.putAll(info);
    }

    if (format != null) {
      m_format = new ArrayList<>(format);
    }
  }

  public VcfPosition(@Nonnull String chromosome, long position, @Nonnull String refBases, @Nonnull BigDecimal quality) {
    m_chromosome = chromosome;
    m_position = position;
    m_refBases = refBases;
    m_quality = quality;
  }

  /**
   * Gets an identifier from the reference genome or an angle-bracketed ID String ("<ID>") pointing to a contig in the
   * assembly file.
   */
  public @Nonnull String getChromosome() {
    return m_chromosome;
  }

  public void setChromosome(@Nonnull String chromosome) {
    m_chromosome = chromosome;
  }

  public void setRef(@Nonnull String ref) {
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
  public @Nonnull List<String> getIds() {
    return m_ids;
  }

  /**
   * Gets the reference base(s) for this position.  Each base must be an A, C, G, T, or N.
   */
  public @Nonnull String getRef() {
    return m_refBases;
  }

  /**
   * Gets the alternate base(s) for this position.  Each base must be an A, C, G, T, N or * unless it's an
   * angle-bracketed ID string ("<ID>").
   * <p>
   * ID strings should reference a specific ALT metadata (obtainable via {@link VcfMetadata#getAlt(java.lang.String)}).
   * </p>
   */
  public @Nonnull List<String> getAltBases() {
    return m_altBases;
  }

  /**
   * Gets the allele at the given index from a list of containing refBases + altBases.
   *
   * @throws IndexOutOfBoundsException if index is out of range
   */
  public @Nonnull String getAllele(int index) {
    return m_alleles.get(index);
  }


  public @Nullable BigDecimal getQuality() {
    return m_quality;
  }

  public void setQuality(@Nullable BigDecimal quality) {
    m_quality = quality;
  }

  public boolean isPassingAllFilters() {
    return m_filter.isEmpty() || m_filter.size() == 1 && m_filter.get(0).equals(".");
  }

  /**
   * Returns a list of filters this position failed, if any.
   */
  public @Nonnull List<String> getFilters() {
    // TODO this is definitely hacky, but since we always return the list and let it change, there's no easy way around
    // also, the definition of PASS as empty and "." by itself should probably be reversed
    // ensure that we only ever have "." (meaning no filters applied yet) by itself:
    if (m_filter.contains(".") && m_filter.size() > 1) {
      m_filter.remove(".");
    }
    return m_filter;
  }

  /**
   * Gets all INFO fields for every key.
   */
  public @Nonnull ListMultimap<String, String> getInfo() {
    return m_info;
  }

  /**
   * Get INFO metadata with the specified ID.
   *
   * @return list of values or null if there is no INFO metadata for the specified id
   */
  public @Nullable List<String> getInfo(@Nonnull String id) {
    if (hasInfo(id)) {
      return m_info.get(id);
    }
    return null;
  }

  /**
   * Returns the value for the reserved property as the type specified by both {@link ReservedInfoProperty#getType()}
   * and {@link ReservedInfoProperty#isList()}.
   * <em>Note that this method does NOT always return a list.</em>
   * For example:
   * <code>
   *   BigDecimal bq = vcfPosition.getInfoConverted(ReservedInfoProperty.BaseQuality);
   * </code>
   * @param <T> The type specified by {@code ReservedInfoProperty.getType()} if {@code ReservedInfoProperty.isList()}
   *           is false;
   *           otherwise {@code List<V>} where V is the type specified by {@code ReservedInfoProperty.getType()}.
   */
  public @Nullable <T> T getInfo(@Nonnull ReservedInfoProperty key) {
    if (!hasInfo(key.getId())) {
      return null;
    }
    List<String> list = m_info.get(key.getId());
    if (list.isEmpty()) {
      return null;
    }
    return VcfUtils.convertProperty(key, sf_commaJoiner.join(list));
  }

  /**
   * Checks if there is INFO metadata with the specified ID.
   */
  public boolean hasInfo(@Nonnull String id) {
    return m_info != null && m_info.containsKey(id);
  }

  /**
   * Checks if there is INFO metadata with the specified ID.
   */
  public boolean hasInfo(@Nonnull ReservedInfoProperty key) {
    return hasInfo(key.getId());
  }

  public @Nonnull List<String> getFormat() {
    return m_format;
  }

  @Nonnull
  public Set<String> getInfoKeys() {
    if (m_info == null) {
      return new HashSet<>(0);
    }
    return m_info.keySet();
  }

}

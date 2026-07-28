package org.pharmgkb.parser.vcf.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.base.Preconditions;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.pharmgkb.parser.vcf.VcfUtils;
import org.pharmgkb.parser.vcf.VcfWarnings;


/**
 * This class captures all the VCF metadata from a VCF file.
 *
 * @author Mark Woon
 */
public class VcfMetadata {

  private String m_fileFormat;
  private final Map<String, IdDescriptionMetadata> m_alt;
  private final Map<String, InfoMetadata> m_info;
  private final Map<String, IdDescriptionMetadata> m_filter;
  private final Map<String, FormatMetadata> m_format;
  private final List<String> m_columns;
  private final ListMultimap<String, String> m_properties;
  private final Map<String, ContigMetadata> m_contig;
  private final Map<String, IdDescriptionMetadata> m_sample;
  private final List<BaseMetadata> m_pedigree;


  private VcfMetadata(String fileFormat, @Nullable Map<String, IdDescriptionMetadata> alt,
      @Nullable Map<String, InfoMetadata> info, @Nullable Map<String, IdDescriptionMetadata> filter,
      @Nullable Map<String, FormatMetadata> format, @Nullable Map<String, ContigMetadata> contig,
      @Nullable Map<String, IdDescriptionMetadata> sample, @Nullable List<BaseMetadata> pedigree,
      List<String> columns, @Nullable ListMultimap<String, String> properties) {
    Preconditions.checkNotNull(fileFormat);
    Preconditions.checkNotNull(columns);
    m_fileFormat = fileFormat;
    // fresh, independently-mutable copies -- NOT List.copyOf/Map.copyOf. getAlts()/getInfo()/getFilters()/
    // getFormats()/getContigs()/getSamples() returning a live, mutable backing map is an existing, tested,
    // intentional contract (e.g. TransformingVcfLineParserTest.java's metadata.getInfo().put(...) usage inside a
    // VcfTransformation). Copying here only prevents a built VcfMetadata from sharing state with the Builder (or
    // with another VcfMetadata built from the same Builder) -- it must not stop a VcfMetadata from being mutated
    // on its own via its own getters, which is a separately supported, separately tested code path.
    m_alt        = alt==null?        new LinkedHashMap<>()      : new LinkedHashMap<>(alt);
    m_info       = info==null?       new LinkedHashMap<>()      : new LinkedHashMap<>(info);
    m_filter     = filter==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(filter);
    m_format     = format==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(format);
    m_contig     = contig==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(contig);
    m_sample     = sample==null?     new LinkedHashMap<>()      : new LinkedHashMap<>(sample);
    m_pedigree   = pedigree==null?   new ArrayList<>()          : new ArrayList<>(pedigree);
    m_properties = properties==null? LinkedListMultimap.create() : LinkedListMultimap.create(properties);
    // m_columns alone stays a true immutable copy: unlike the seven fields above, it has no supported
    // mutable-getter counterpart (no getColumns() exposing the live list, only derived read-only accessors like
    // getColumnIndex()) -- there is no mutation contract to preserve for it, so this asymmetry is intentional.
    m_columns    = List.copyOf(columns);
  }

  public String getFileFormat() {
    return m_fileFormat;
  }

  public void setFileFormat(String fileFormat) {
    if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(fileFormat).matches()) {
      throw new VcfFormatException("VCF format must look like ex: VCFv4.2; was " + fileFormat);
    }
    m_fileFormat = fileFormat;
  }

  public Map<String, IdDescriptionMetadata> getAlts() {
    return m_alt;
  }

  /**
   * Gets the ALT metadata for the given ID.
   *
   * @param id the ID to lookup, will unwrap ID's enclosed in angle brackets (e.g. &lt;CN1&gt; will get converted to CN1)
   */
  @Nullable
  public IdDescriptionMetadata getAlt(String id) {
    IdDescriptionMetadata md = m_alt.get(id);
    if (md == null && id.startsWith("<") && id.endsWith(">")) {
      md = m_alt.get(id.substring(1, id.length() - 1));
    }
    return md;
  }


  public Map<String, InfoMetadata> getInfo() {
    return m_info;
  }

  public Map<String, IdDescriptionMetadata> getFilters() {
    return m_filter;
  }

  public Map<String, FormatMetadata> getFormats() {
    return m_format;
  }

  public Map<String, ContigMetadata> getContigs() {
    return m_contig;
  }

  public List<BaseMetadata> getPedigrees() {
    return m_pedigree;
  }

  public Map<String, IdDescriptionMetadata> getSamples() {
    return m_sample;
  }

  /**
   * @return The URLs from the field(s) in the <em>assembly</em> metadata line(s)
   */
  public List<String> getAssemblies() {
    // spec says: ##assembly=url (without angle brackets)
    return m_properties.get("assembly");
  }

  /**
   * @return The URLs from the field(s) in the <em>pedigreeDB</em> metadata line(s)
   */
  public List<String> getPedigreeDatabases() {
    // spec says: ##pedigreeDB=url (no angle brackets; see addPedigreeDatabase's comment)
    return m_properties.get("pedigreeDB");
  }

  /**
   * Adds {@code value} to the map of ALT metadata, using its {@link IdDescriptionMetadata#getId() ID} as the key.
   * Warns and keeps the existing entry if {@code value}'s ID is already present -- call {@link #removeAlt} first
   * to replace an existing declaration.
   */
  public void addAlt(IdDescriptionMetadata value) {
    if (value.getId() == null || value.getId().trim().isEmpty()) {
      VcfWarnings.warn("ALT metadata declaration has no ID and cannot be stored; dropped: {}", value.getPropertiesRaw());
      return;
    }
    if (warnIfDuplicate(m_alt, value.getId(), "ALT")) {
      return;
    }
    validateAlt(value);
    m_alt.put(value.getId(), value);
  }

  /**
   * Warns (does not throw -- matching this project's warn-and-preserve policy for metadata declarations) if
   * {@code value}'s ID is not a valid ALT structural variant code, or if it declares a Number/Type extra property
   * with a syntactically invalid value.
   */
  public static void validateAlt(IdDescriptionMetadata value) {
    String id = value.getId();
    if (id != null) {
      // whitespace is already checked generically by IdMetadata.init() for every ID-based metadata type; angle
      // brackets are checked here since VCFv4.2.tex line 105's "or angle brackets" clause has only ever been
      // evidenced for ALT specifically, not extended to ##contig/##INFO/##FORMAT/##FILTER/##SAMPLE.
      if (id.chars().anyMatch(c -> c == '<' || c == '>')) {
        VcfWarnings.warn("ALT {} must not contain angle brackets", id);
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
          VcfWarnings.warn("ALT {} is not a valid structural variant code: {}", id, e.getMessage());
        }
      }
    }
    // ##ALT's only defined fields are ID/Description (VCFv4.2.tex line 103: ##ALT=<ID=type,Description="description">);
    // Number/Type are unrecognized extras, but test/vcf/4.2/failed/failed_meta_alt_001.vcf and
    // .../failed_meta_alt_002.vcf (##CauseOfFailure=Incorrect ALT Number/Type value) establish that, once
    // declared, they're still expected to be syntactically valid VCF Number/Type tokens.
    String number = value.getPropertyRaw(InfoMetadata.NUMBER);
    if (number != null && !VcfUtils.NUMBER_PATTERN.matcher(number).matches()) {
      VcfWarnings.warn("ALT {} has Number '{}' but it is not a valid VCF number", id, number);
    }
    String type = value.getPropertyRaw(InfoMetadata.TYPE);
    if (type != null) {
      try {
        InfoType.valueOf(type);
      } catch (IllegalArgumentException e) {
        VcfWarnings.warn("ALT {} has Type '{}' but it is not a recognized VCF type", id, type);
      }
    }
  }

  /**
   * @return Whether {@code value} has no meaningful content -- either fully empty, or an empty bracket pair
   * ({@code <>}).
   */
  public static boolean isEmptyMetadataValue(String value) {
    String trimmed = value.trim();
    // ##pedigreeDB=<> (test/vcf/{4.1,4.2}/failed/failed_meta_pedigreedb_001.vcf, ##CauseOfFailure=Meta-data entry
    // is not a key-value pair) has no meaningful content between the brackets -- effectively the same as empty.
    // Not pedigreeDB-specific: confirmed directly that bcftools 1.23.1 rejects "##assembly=<>" (and, by the same
    // htslib attribute-list-grammar collision, any other key) with "[E::bcf_hdr_parse_line] Could not parse the
    // header line", not just "##pedigreeDB=<>" -- so the check applies uniformly to every key.
    return trimmed.isEmpty() || trimmed.equals("<>");
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
   * FILTER/ALT/SAMPLE (contrast with duplicate #CHROM sample-column names, which remain a structural error --
   * see DUPLICATE_METADATA_ID_HANDLING.md for why). hts-specs' own test/vcf/{4.1,4.2}/passed/passed_meta_contig.vcf
   * fixture declares ##contig=&lt;ID=1&gt; twice and is expected to parse successfully. Confirmed valid by
   * vcf_validator 0.10.2 for all six declaration types tested; bcftools 1.23.1 (htslib 1.23.1) keeps the first
   * declaration and silently drops each duplicate, with no warning, for all six -- see
   * DUPLICATE_METADATA_ID_HANDLING.md.
   */
  private static boolean warnIfDuplicate(Map<String, ?> map, String id, String typeName) {
    if (map.containsKey(id)) {
      VcfWarnings.warn("Duplicate ID \"{}\" for {} metadata declaration ignored; keeping the first declaration",
          id, typeName);
      return true;
    }
    return false;
  }

  /**
   * Adds {@code value} to the map of INFO metadata, using its {@link InfoMetadata#getId() ID} as the key. Warns
   * and keeps the existing entry if {@code value}'s ID is already present -- call {@link #removeInfo} first to
   * replace an existing declaration.
   */
  public void addInfo(InfoMetadata value) {
    if (value.getId() == null || value.getId().trim().isEmpty()) {
      VcfWarnings.warn("INFO metadata declaration has no ID and cannot be stored; dropped: {}", value.getPropertiesRaw());
      return;
    }
    if (warnIfDuplicate(m_info, value.getId(), "INFO")) {
      return;
    }
    m_info.put(value.getId(), value);
  }

  /**
   * Adds {@code value} to the map of FORMAT metadata, using its {@link FormatMetadata#getId() ID} as the key.
   * Warns and keeps the existing entry if {@code value}'s ID is already present -- call {@link #removeFormat}
   * first to replace an existing declaration.
   */
  public void addFormat(FormatMetadata value) {
    if (value.getId() == null || value.getId().trim().isEmpty()) {
      VcfWarnings.warn("FORMAT metadata declaration has no ID and cannot be stored; dropped: {}", value.getPropertiesRaw());
      return;
    }
    if (warnIfDuplicate(m_format, value.getId(), "FORMAT")) {
      return;
    }
    m_format.put(value.getId(), value);
  }

  /**
   * Adds {@code value} to the map of CONTIG metadata, using its {@link ContigMetadata#getId() ID} as the key.
   * Warns and keeps the existing entry if {@code value}'s ID is already present -- call {@link #removeContig}
   * first to replace an existing declaration.
   */
  public void addContig(ContigMetadata value) {
    if (value.getId() == null || value.getId().trim().isEmpty()) {
      VcfWarnings.warn("CONTIG metadata declaration has no ID and cannot be stored; dropped: {}", value.getPropertiesRaw());
      return;
    }
    if (warnIfDuplicate(m_contig, value.getId(), "CONTIG")) {
      return;
    }
    m_contig.put(value.getId(), value);
  }

  /**
   * Adds {@code value} to the map of FILTER metadata, using its {@link IdDescriptionMetadata#getId() ID} as the
   * key. Warns and keeps the existing entry if {@code value}'s ID is already present -- call {@link #removeFilter}
   * first to replace an existing declaration.
   */
  public void addFilter(IdDescriptionMetadata value) {
    if (value.getId() == null || value.getId().trim().isEmpty()) {
      VcfWarnings.warn("FILTER metadata declaration has no ID and cannot be stored; dropped: {}", value.getPropertiesRaw());
      return;
    }
    if (warnIfDuplicate(m_filter, value.getId(), "FILTER")) {
      return;
    }
    m_filter.put(value.getId(), value);
  }

  /**
   * Adds {@code value} to the list of assembly metadata.
   * @param value Should not be wrapped in angle brackets
   */
  public void addAssembly(String value) {
    VcfUtils.checkNoLineTerminator("assembly", value);
    if (isEmptyMetadataValue(value)) {
      VcfWarnings.warn("Metadata property \"assembly\" has an empty value and was dropped: {}", value);
      return;
    }
    m_properties.put("assembly", value);
  }

  /**
   * Adds {@code value} to the list of pedigreeDB.
   * @param value Should not be wrapped in angle brackets
   */
  public void addPedigreeDatabase(String value) {
    // VCFv4.2.tex/4.3.tex/4.4.tex's Pedigree field format section all template this bracket-less:
    // "##pedigreeDB=URL". hts-specs' own passed/passed_meta_pedigreedb.vcf fixture (all versions) is bracket-less
    // and expected to parse; confirmed bcftools 1.23.1 accepts it. Brackets are not required or expected here
    // (unlike ##ALT/##INFO/etc, which use <ID=...> attribute-list syntax) -- bcftools hard-errors on
    // "##pedigreeDB=<>" specifically because it collides with that other grammar, not because brackets are this
    // field's own syntax.
    // Known, deliberately out-of-scope gap: a non-empty bracket-wrapped value (e.g. "<ftp://example.com/db>") is
    // still accepted here as a literal string today, even though bcftools would also reject it via that same
    // grammar collision -- not fixed in this pass.
    VcfUtils.checkNoLineTerminator("pedigreeDB", value);
    if (isEmptyMetadataValue(value)) {
      VcfWarnings.warn("Metadata property \"pedigreeDB\" has an empty value and was dropped: {}", value);
      return;
    }
    m_properties.put("pedigreeDB", value);
  }

  public void removeAlt(IdDescriptionMetadata value) {
    m_alt.remove(value.getId());
  }

  public void removeInfo(InfoMetadata value) {
    m_info.remove(value.getId());
  }

  public void removeFormat(FormatMetadata value) {
    m_format.remove(value.getId());
  }

  public void removeContig(ContigMetadata value) {
    m_contig.remove(value.getId());
  }

  public void removeFilter(IdDescriptionMetadata value) {
    m_filter.remove(value.getId());
  }

  public void removeAssembly(String value) {
    m_properties.remove("assembly", value);
  }

  /**
   * Removes {@code value} from the list of pedigreeDB.
   * @param value Should not be wrapped in angle brackets
   */
  public void removePedigreeDb(String value) {
    m_properties.remove("pedigreeDB", value);
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
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   * <p>
   * This is the backing, mutable multimap. Unlike {@link #addAssembly} and {@link #addPedigreeDatabase}, mutations
   * made directly through it do not run the line-terminator check or the empty-value check
   * ({@link org.pharmgkb.parser.vcf.VcfWriter}'s {@code validateBeforeWrite()} mode re-checks the latter at write
   * time).
   */
  public ListMultimap<String, String> getRawProperties() {
    return m_properties;
  }

  /**
   * Returns the values of a property, or an empty list if the property is not set (this delegates directly to
   * Guava's {@link ListMultimap#get}, which never returns {@code null}).
   * <strong>This method will return an empty list for a reserved property of the form
   * XX=&lt;ID=value,ID=value,...&gt;; {@code assembly} and {@code pedigreeDB} are still included.</strong>
   * <p>
   * Like {@link #getRawProperties()}, the returned list is a live view backed by the multimap: mutating it directly
   * bypasses the line-terminator and empty-value checks that {@link #addAssembly} and {@link #addPedigreeDatabase}
   * run at insertion time.
   */
  public List<String> getRawValuesOfProperty(String propertyKey) {
    return m_properties.get(propertyKey);
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
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   * <p>
   * Iterates in the order each key was first added, not alphabetically.
   */
  public Set<String> getRawPropertyKeys() {
    return new LinkedHashSet<>(m_properties.keySet());
  }

  public int getColumnIndex(String column) {
    return m_columns.indexOf(column);
  }

  /**
   * Sample numbering starts at 0.
   *
   * @return the sample's index, or {@code -1} if {@code sampleId} is not a sample column
   */
  public int getSampleIndex(String sampleId) {
    int idx = m_columns.indexOf(sampleId);
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
   * @throws ArrayIndexOutOfBoundsException If the sample doesn't exist
   */
  public String getSampleName(int idx) {
    return m_columns.get(9 + idx);
  }



  public static class Builder {
    private @Nullable String m_fileFormat;
    private final Map<String, IdDescriptionMetadata> m_alt = new LinkedHashMap<>();
    private final Map<String, InfoMetadata> m_info = new LinkedHashMap<>();
    private final Map<String, IdDescriptionMetadata> m_filter = new LinkedHashMap<>();
    private final Map<String, FormatMetadata> m_format = new LinkedHashMap<>();
    private final Map<String, ContigMetadata> m_contig = new LinkedHashMap<>();
    private final Map<String, IdDescriptionMetadata> m_sample = new LinkedHashMap<>();
    private final List<BaseMetadata> m_pedigree = new ArrayList<>();
    private List<String> m_columns = new ArrayList<>();
    private final ListMultimap<String, String> m_properties = LinkedListMultimap.create();

    /**
     * Sets the VCF version string.
     * @param fileFormat Ex: "VCFv4.2"
     */
    public Builder setFileFormat(String fileFormat) {
      m_fileFormat = fileFormat;
      if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(fileFormat).matches()) {
        throw new VcfFormatException("Not a VCF file: fileformat is " + m_fileFormat);
      }
      return this;
    }

    public Builder addAlt(IdDescriptionMetadata md) {
      if (md.getId() == null || md.getId().trim().isEmpty()) {
        VcfWarnings.warn("ALT metadata declaration has no ID and cannot be stored; dropped: {}", md.getPropertiesRaw());
        return this;
      }
      if (warnIfDuplicate(m_alt, md.getId(), "ALT")) {
        return this;
      }
      validateAlt(md);
      m_alt.put(md.getId(), md);
      return this;
    }

    public Builder addInfo(InfoMetadata md) {
      if (md.getId() == null || md.getId().trim().isEmpty()) {
        VcfWarnings.warn("INFO metadata declaration has no ID and cannot be stored; dropped: {}", md.getPropertiesRaw());
        return this;
      }
      if (warnIfDuplicate(m_info, md.getId(), "INFO")) {
        return this;
      }
      m_info.put(md.getId(), md);
      return this;
    }

    public Builder addFilter(IdDescriptionMetadata md) {
      if (md.getId() == null || md.getId().trim().isEmpty()) {
        VcfWarnings.warn("FILTER metadata declaration has no ID and cannot be stored; dropped: {}", md.getPropertiesRaw());
        return this;
      }
      if (warnIfDuplicate(m_filter, md.getId(), "FILTER")) {
        return this;
      }
      m_filter.put(md.getId(), md);
      return this;
    }

    public Builder addFormat(FormatMetadata md) {
      if (md.getId() == null || md.getId().trim().isEmpty()) {
        VcfWarnings.warn("FORMAT metadata declaration has no ID and cannot be stored; dropped: {}", md.getPropertiesRaw());
        return this;
      }
      if (warnIfDuplicate(m_format, md.getId(), "FORMAT")) {
        return this;
      }
      m_format.put(md.getId(), md);
      return this;
    }

    public Builder addContig(ContigMetadata md) {
      if (md.getId() == null || md.getId().trim().isEmpty()) {
        VcfWarnings.warn("CONTIG metadata declaration has no ID and cannot be stored; dropped: {}", md.getPropertiesRaw());
        return this;
      }
      if (warnIfDuplicate(m_contig, md.getId(), "CONTIG")) {
        return this;
      }
      m_contig.put(md.getId(), md);
      return this;
    }

    public Builder addSample(IdDescriptionMetadata md) {
      if (md.getId() == null || md.getId().trim().isEmpty()) {
        VcfWarnings.warn("SAMPLE metadata declaration has no ID and cannot be stored; dropped: {}", md.getPropertiesRaw());
        return this;
      }
      if (warnIfDuplicate(m_sample, md.getId(), "SAMPLE")) {
        return this;
      }
      m_sample.put(md.getId(), md);
      return this;
    }

    /**
     * Adds {@code md} to the list of PEDIGREE metadata. If {@code md} declares a literal {@code ID} property that
     * duplicates an earlier PEDIGREE entry's {@code ID}, warns and keeps the first entry instead of storing
     * {@code md} -- matching the six keyed types' warn-and-keep-first rule. A PEDIGREE entry with no {@code ID}
     * property (VCFv4.2.tex line 152's own grammar, e.g. {@code Name_0=G0-ID,...,Name_N=GN-ID}, has no mandatory
     * ID attribute) is always added: confirmed bcftools 1.23.1 keeps both of two ID-less duplicate PEDIGREE lines,
     * since there is no key to dedupe on -- see DUPLICATE_METADATA_ID_HANDLING.md.
     * <p>
     * Checks {@code m_pedigree} directly (rather than a separately-tracked set of seen IDs) so this stays correct
     * if an entry is ever removed from the list between calls -- the same "read live state" property the six
     * keyed types get for free from their backing {@code Map}.
     */
    public Builder addPedigree(BaseMetadata md) {
      String id = md.getPropertiesRaw().get("ID");
      if (id != null &&
          m_pedigree.stream().anyMatch(existing -> id.equals(existing.getPropertiesRaw().get("ID")))) {
        VcfWarnings.warn("Duplicate ID \"{}\" for PEDIGREE metadata declaration ignored; keeping the first " +
            "declaration", id);
        return this;
      }
      m_pedigree.add(md);
      return this;
    }

    public Builder addRawProperty(String name, String value) {
      VcfUtils.checkNoLineTerminator(name, value);
      if (VcfUtils.isReservedMetadataKey(name)) {
        // this cannot currently be reached through normal parsing -- VcfParser.parseMetadata's switch routes
        // every reserved name to the structured path, never to addRawProperty -- but it closes the hole for
        // direct/programmatic Builder use, matching the empty-value case below's warn-and-drop style
        VcfWarnings.warn("Metadata property name \"{}\" collides with a structured declaration keyword and was " +
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
        VcfWarnings.warn("Metadata property \"{}\" has an empty value and was dropped: {}", name, value);
        return this;
      }
      m_properties.put(name, value);
      return this;
    }

    public Builder setColumns(List<String> cols) {
      m_columns = cols;
      return this;
    }

    public VcfMetadata build() {
      if (m_fileFormat == null) {
        throw new VcfFormatException("Not a VCF file: no ##fileformat line");
      }
      return new VcfMetadata(m_fileFormat, m_alt, m_info, m_filter, m_format, m_contig, m_sample, m_pedigree,
          m_columns, m_properties);
    }
  }
}

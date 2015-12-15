package org.pharmgkb.parser.vcf.model;

import com.google.common.base.Functions;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.sun.xml.internal.rngom.parse.host.Base;
import org.pharmgkb.parser.vcf.VcfUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;


/**
 * This class captures all of the VCF metadata from a VCF file.
 *
 * @author Mark Woon
 */
public class VcfMetadata {

  private String m_fileFormat;
  private List<String> m_columns;

  private LinkedListMultimap<UniqueMetadataKey, IdMetadata> m_metadataWithId;

  private VcfMetadata(@Nonnull String fileFormat, @Nullable LinkedHashMap<String, IdDescriptionMetadata> alt,
      @Nullable LinkedHashMap<String, InfoMetadata> info, @Nullable LinkedHashMap<String, IdDescriptionMetadata> filter,
      @Nullable LinkedHashMap<String, FormatMetadata> format, @Nullable LinkedHashMap<String, ContigMetadata> contig,
      @Nullable LinkedHashMap<String, IdDescriptionMetadata> sample, @Nullable List<BaseMetadata> pedigree,
      @Nonnull List<String> columns, @Nullable LinkedListMultimap<String, String> properties) {

    Preconditions.checkNotNull(fileFormat);
    Preconditions.checkNotNull(columns);

    m_fileFormat = fileFormat;
    m_columns    = columns;

    if (alt != null) alt.entrySet().forEach(e -> m_metadataWithId.put(new UniqueMetadataKey(MetadataType.ALT, e.getKey()), e.getValue()));
    if (info != null) info.entrySet().forEach(e -> m_metadataWithId.put(new UniqueMetadataKey(MetadataType.INFO, e.getKey()), e.getValue()));
    if (filter != null) filter.entrySet().forEach(e -> m_metadataWithId.put(new UniqueMetadataKey(MetadataType.FILTER, e.getKey()), e.getValue()));
    if (format != null) format.entrySet().forEach(e -> m_metadataWithId.put(new UniqueMetadataKey(MetadataType.FORMAT, e.getKey()), e.getValue()));
    if (contig != null) contig.entrySet().forEach(e -> m_metadataWithId.put(new UniqueMetadataKey(MetadataType.CONTIG, e.getKey()), e.getValue()));
    if (info != null) info.entrySet().forEach(e -> m_metadataWithId.put(new UniqueMetadataKey(MetadataType.INFO, e.getKey()), e.getValue()));
    if (sample != null) sample.entrySet().forEach(e -> m_metadataWithId.put(new UniqueMetadataKey(MetadataType.SAMPLE, e.getKey()), e.getValue()));

  }

  public @Nonnull String getFileFormat() {
    return m_fileFormat;
  }

  public void setFileFormat(@Nonnull String fileFormat) {
    if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(fileFormat).matches()) {
      throw new IllegalArgumentException("VCF format must look like ex: VCFv4.2; was " + fileFormat);
    }
    m_fileFormat = fileFormat;
  }

  @SuppressWarnings("unchecked")
  private <T extends IdMetadata> LinkedListMultimap<String, T> getAllOfTypeRaw(@Nonnull MetadataType type) {
    LinkedListMultimap m = LinkedListMultimap.create();
    m_metadataWithId.entries().stream()
        .filter(e -> e.getKey().getType() == type)
        .forEach(e -> m.put(e.getKey(), e.getValue()));
    return m;
  }

  @SuppressWarnings("unchecked")
  private <T extends IdMetadata> LinkedHashMap<String, T> getAllOfType(@Nonnull MetadataType type) {
    LinkedHashMap<String, T> m = new LinkedHashMap<>();
    m_metadataWithId.entries().stream()
        .filter(e -> e.getKey().getType() == type)
        .forEach(e -> m.put(e.getKey().getKey(), (T)e.getValue()));
    return m;
  }

  @SuppressWarnings("unchecked")
  private <T extends IdMetadata> List<T> getOfType(@Nonnull MetadataType type, @Nonnull String id) {
    return (List<T>)m_metadataWithId.get(new UniqueMetadataKey(type, id));
  }

  private void putOfType(@Nonnull MetadataType type, @Nonnull String id, @Nonnull IdMetadata value) {
    if (type != MetadataType.UNDEFINED && !getOfType(type, id).isEmpty()) {
      throw new IllegalArgumentException("Can't add duplicate metadata ID=" + id + " of type " + type);
    }
    m_metadataWithId.put(new UniqueMetadataKey(type, id), value);
  }

  private void removeOfType(@Nonnull MetadataType type, @Nonnull String id, @Nonnull IdMetadata value) {
    m_metadataWithId.remove(new UniqueMetadataKey(type, id), value);
  }

  private void removeAllOfType(@Nonnull MetadataType type, @Nonnull String id) {
    m_metadataWithId.removeAll(new UniqueMetadataKey(type, id));
  }

  public @Nonnull Map<String, IdDescriptionMetadata> getAlts() {
    return getAllOfType(MetadataType.ALT);
  }

  /**
   * Gets the ALT metadata for the given ID.
   *
   * @param id the ID to lookup, will unwrap ID's enclosed in angle brackets (e.g. &lt;CN1&gt; will get converted to CN1)
   */
  @Nullable
  public IdDescriptionMetadata getAlt(@Nonnull String id) {
    return (IdDescriptionMetadata) getOfType(MetadataType.ALT, id).get(0);
  }

  public @Nonnull Map<String, InfoMetadata> getInfo() {
    return getAllOfType(MetadataType.INFO);
  }

  public @Nonnull Map<String, IdDescriptionMetadata> getFilters() {
    return getAllOfType(MetadataType.FILTER);
  }

  public @Nonnull Map<String, FormatMetadata> getFormats() {
    return getAllOfType(MetadataType.FORMAT);
  }

  public @Nonnull Map<String, ContigMetadata> getContigs() {
    return getAllOfType(MetadataType.CONTIG);
  }

  public @Nonnull Map<String, IdDescriptionMetadata> getSamples() {
    return getAllOfType(MetadataType.SAMPLE);
  }

  public @Nonnull List<BaseMetadata> getPedigrees() {
    List<BaseMetadata> m = new ArrayList<>();
    getAllOfTypeRaw(MetadataType.PEDIGREE).values().forEach(m::add); // because Java generics are invariant
    return m;
  }

  /**
   * @return The URLs from the field(s) in the <em>assembly</em> metadata line(s)
   */
  public @Nonnull List<String> getAssemblies() {
    // spec says: ##assembly=url (without angle brackets)
    List<String> m = new ArrayList<>();
    getAllOfTypeRaw(MetadataType.UNDEFINED).values().stream()
        .filter(e -> e.getId().equals("assembly"))
        .forEach(IdMetadata::getId); // because Java generics are invariant
    return m;
  }

  /**
   * @return The URLs from the field(s) in the <em>pedigreeDB</em> metadata line(s), including angle brackets if any
   */
  public @Nonnull List<String> getPedigreeDatabases() {
    // spec says: ##pedigreeDB=<url> (with angle brackets)
    List<String> m = new ArrayList<>();
    getAllOfTypeRaw(MetadataType.UNDEFINED).values().stream()
        .filter(e -> e.getId().equals("pedigreeDB"))
        .forEach(IdMetadata::getId); // because Java generics are invariant
    return m;
  }

  /**
   * Adds {@code value} to the map of ALT metadata, using its {@link IdDescriptionMetadata#getId() ID} as the key.
   */
  public void addAlt(@Nonnull IdDescriptionMetadata value) {
    putOfType(MetadataType.ALT, value.getId(), value);
  }

  /**
   * Adds {@code value} to the map of INFO metadata, using its {@link InfoMetadata#getId() ID} as the key.
   */
  public void addInfo(@Nonnull InfoMetadata value) {
    putOfType(MetadataType.INFO, value.getId(), value);
  }

  /**
   * Adds {@code value} to the map of FORMAT metadata, using its {@link FormatMetadata#getId() ID} as the key.
   */
  public void addFormat(@Nonnull FormatMetadata value) {
    putOfType(MetadataType.FORMAT, value.getId(), value);
  }

  /**
   * Adds {@code value} to the map of CONTIG metadata, using its {@link ContigMetadata#getId() ID} as the key.
   */
  public void addContig(@Nonnull ContigMetadata value) {
    putOfType(MetadataType.CONTIG, value.getId(), value);
  }

  /**
   * Adds {@code value} to the map of FILTER metadata, using its {@link IdDescriptionMetadata#getId() ID} as the key.
   */
  public void addFilter(@Nonnull IdDescriptionMetadata value) {
    putOfType(MetadataType.FILTER, value.getId(), value);
  }

  /**
   * Adds {@code value} to the list of assembly metadata.
   * @param value Should not be wrapped in angle brackets
   */
  public void addAssembly(@Nonnull String value) {
    putOfType(MetadataType.UNDEFINED, "assembly", new RawMetadata(value));
  }

  /**
   * Adds {@code value} to the list of pedigreeDB.
   * @param value Must be wrapped in angle brackets
   * @throws IllegalArgumentException If {@code value} is not wrapped in angle brackets
   */
  public void addPedigreeDatabase(@Nonnull String value) {
    if (value.startsWith("<") && value.endsWith(">")) {
      putOfType(MetadataType.UNDEFINED, "assembly", new RawMetadata(value));
    } else {
      throw new IllegalArgumentException("pedigreeDB string " + value + " should be enclosed in angle brackets according to spec");
    }
  }

  public void removeAlt(@Nonnull IdDescriptionMetadata value) {
    removeOfType(MetadataType.ALT, value.getId(), value);
  }

  public void removeInfo(@Nonnull InfoMetadata value) {
    removeOfType(MetadataType.INFO, value.getId(), value);
  }

  public void removeFormat(@Nonnull FormatMetadata value) {
    removeOfType(MetadataType.FORMAT, value.getId(), value);
  }

  public void removeContig(@Nonnull ContigMetadata value) {
    removeOfType(MetadataType.CONTIG, value.getId(), value);
  }

  public void removeFilter(@Nonnull IdDescriptionMetadata value) {
    removeOfType(MetadataType.FILTER, value.getId(), value);
  }

  public void removeAssembly(@Nonnull String value) {
    removeOfType(MetadataType.UNDEFINED, "assembly", new RawMetadata(value));
  }

  /**
   * Adds {@code value} to the list of pedigreeDB.
   * @param value Must be wrapped in angle brackets
   * @throws IllegalArgumentException If {@code value} is not wrapped in angle brackets
   */
  public void removePedigreeDb(@Nonnull String value) {
    if (value.startsWith("<") && value.endsWith(">")) {
      removeOfType(MetadataType.UNDEFINED, "pedigreeDB", new RawMetadata(value));
    } else { // be strict to avoid needing to delete both value and <value>
      throw new IllegalArgumentException("pedigreeDB string " + value + " should be enclosed in angle brackets according to spec");
    }
  }

  /**
   * Returns a map from every property key to each of its values.
   * Call {@link ListMultimap#asMap} to get a Map&lt;String, Collection&lt;String&gt;&gt;.
   * @return <em>Contains every property except those contained in:</em>
   * <ul>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getFilters}</li>
   *   <li>{@link #getFormats}</li>
   *   <li>{@link #getContigs}</li>
   *   <li>{@link #getPedigrees}</li>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getSamples}</li>
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   */
  public @Nonnull ListMultimap<String, String> getRawProperties() {
    LinkedListMultimap<String, String> m = LinkedListMultimap.create();
    m_metadataWithId.entries().stream()
        .filter(e -> e.getKey().getType() == MetadataType.UNDEFINED)
        .forEach(e -> m.put(e.getKey().getKey(), e.getValue().asVcfString("")));
    return m;
  }

  /**
   * Returns the value of a property, or null if the property is not set or has no value.
   * <strong>This method will return null for a reserved property of the form XX=&lt;ID=value,ID=value,...&gt;;
   * {@code assembly} and {@code pedigreeDB} are still included.</strong>
   */
  public @Nonnull List<String> getRawValuesOfProperty(@Nonnull String propertyKey) {
    return getRawProperties().get(propertyKey);
  }

  /**
   * Returns a list of the properties defined.
   * <strong>Reserved properties of the form XX=&lt;ID=value,ID=value,...&gt; are excluded, though {@code assembly}
   * and {@code pedigreeDB} are still included.</strong>
   * @return <em>Contains every property except those contained in:</em>
   * <ul>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getFilters}</li>
   *   <li>{@link #getFormats}</li>
   *   <li>{@link #getContigs}</li>
   *   <li>{@link #getPedigrees}</li>
   *   <li>{@link #getInfo}</li>
   *   <li>{@link #getSamples}</li>
   * </ul>
   * However, contains any in {@link #getAssemblies} and {@link #getPedigreeDatabases}.
   */
  public @Nonnull List<String> getRawPropertyKeys() {
    return m_metadataWithId.keySet().stream()
        .map(UniqueMetadataKey::getKey)
        .collect(Collectors.toList());
  }

  public List<Map.Entry<UniqueMetadataKey, IdMetadata>> getAllMetadataInOrder() {
    return m_metadataWithId.entries();
  }

  public int getColumnIndex(@Nonnull String column) {
    return m_columns.indexOf(column);
  }

  /**
   * Sample numbering starts at 0.
   */
  public int getSampleIndex(@Nonnull String sampleId) {
    return m_columns.indexOf(sampleId) - 9;
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
   * Gets the sample name (column name).
   *
   * @param idx sample index, first sample is at index 0
   *
   * @throws ArrayIndexOutOfBoundsException If the sample doesn't exist
   */
  public @Nonnull String getSampleName(int idx) {
    return m_columns.get(9 + idx);
  }



  public static class Builder {
    private String m_fileFormat;
    private LinkedHashMap<String, IdDescriptionMetadata> m_alt = new LinkedHashMap<>();
    private LinkedHashMap<String, InfoMetadata> m_info = new LinkedHashMap<>();
    private LinkedHashMap<String, IdDescriptionMetadata> m_filter = new LinkedHashMap<>();
    private LinkedHashMap<String, FormatMetadata> m_format = new LinkedHashMap<>();
    private LinkedHashMap<String, ContigMetadata> m_contig = new LinkedHashMap<>();
    private LinkedHashMap<String, IdDescriptionMetadata> m_sample = new LinkedHashMap<>();
    private List<BaseMetadata> m_pedigree = new ArrayList<>();
    private List<String> m_columns = new ArrayList<>();
    private LinkedListMultimap<String, String> m_properties = LinkedListMultimap.create();

    /**
     * Sets the VCF version string.
     * @param fileFormat Ex: "VCFv4.2"
     */
    public Builder setFileFormat(@Nonnull String fileFormat) {
      m_fileFormat = fileFormat;
      if (!VcfUtils.FILE_FORMAT_PATTERN.matcher(fileFormat).matches()) {
        throw new IllegalStateException("Not a VCF file: fileformat is " + m_fileFormat);
      }
      return this;
    }

    public Builder addAlt(@Nonnull IdDescriptionMetadata md) {
      if (m_alt.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for ALT");
      }
      m_alt.put(md.getId(), md);
      return this;
    }

    public Builder addInfo(@Nonnull InfoMetadata md) {
      if (m_info.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for INFO");
      }
      m_info.put(md.getId(), md);
      return this;
    }

    public Builder addFilter(@Nonnull IdDescriptionMetadata md) {
      if (m_filter.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for FILTER");
      }
      m_filter.put(md.getId(), md);
      return this;
    }

    public Builder addFormat(@Nonnull FormatMetadata md) {
      if (m_format.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for FORMAT");
      }
      m_format.put(md.getId(), md);
      return this;
    }

    public Builder addContig(@Nonnull ContigMetadata md) {
      if (m_contig.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for CONTIG");
      }
      m_contig.put(md.getId(), md);
      return this;
    }

    public Builder addSample(@Nonnull IdDescriptionMetadata md) {
      if (m_sample.containsKey(md.getId())) {
        throw new IllegalArgumentException("Duplicate ID " + md.getId() + " for SAMPLE");
      }
      m_sample.put(md.getId(), md);
      return this;
    }

    public Builder addPedigree(@Nonnull BaseMetadata md) {
      m_pedigree.add(md);
      return this;
    }

    public Builder addRawProperty(@Nonnull String name, @Nonnull String value) {
      m_properties.put(name, value);
      return this;
    }

    public Builder setColumns(@Nonnull List<String> cols) {
      m_columns = cols;
      return this;
    }

    @Nonnull
    public VcfMetadata build() {
      if (m_fileFormat == null) {
        throw new IllegalStateException("Not a VCF file: no ##fileformat line");
      }
      return new VcfMetadata(m_fileFormat, m_alt, m_info, m_filter, m_format, m_contig, m_sample, m_pedigree,
          m_columns, m_properties);
    }
  }

  /**
   * A key for metadata objects that's guaranteed to be unique.
   * If the metadata object is not a form of {@link IdMetadata}, the entire text of the metadata value is used.
   */
  static class UniqueMetadataKey {

    private final MetadataType m_type;
    private final String m_key;

    public UniqueMetadataKey(@Nonnull MetadataType type, @Nonnull String key) {
      m_type = type;
      m_key = key;
    }

    public @Nonnull MetadataType getType() {
      return m_type;
    }

    public @Nonnull String getKey() {
      return m_key;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("type", m_type).add("key", m_key).toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      UniqueMetadataKey that = (UniqueMetadataKey) o;
      return m_type == that.m_type && m_key.equals(that.m_key);
    }

    @Override
    public int hashCode() {
      return Objects.hash(m_type, m_key);
    }
  }

  private enum MetadataType {
    ALT, INFO, FILTER, FORMAT, CONTIG, SAMPLE, PEDIGREE, UNDEFINED
  }
}

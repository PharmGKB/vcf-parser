package org.pharmgkb.parser.vcf.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.pharmgkb.parser.vcf.VcfFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A VCF metadata element with an Id (called "ID").
 */
public class IdMetadata extends BaseMetadata {

  private final boolean m_isBaseType;

  public static final String ID = "ID";

  public IdMetadata(String id) {
    this(id, true);
  }

  public IdMetadata(Map<String, String> properties) {
    this(properties, true);
  }

  protected IdMetadata(String id, boolean isBaseType) {
    super(new LinkedHashMap<>());
    // VCFv4.3.tex line 121: "All structured lines require an ID..." -- VCFv4.2 doesn't use the word "required" but
    // its own templates for all six keyed types show ID unconditionally first; see DUPLICATE_METADATA_ID_HANDLING.md
    if (id == null || id.trim().isEmpty()) {
      throw new VcfFormatException("ID is required and must not be blank");
    }
    m_isBaseType = isBaseType;
    putPropertyRaw(ID, id);
    init(isBaseType);
  }

  protected IdMetadata(Map<String, String> properties, boolean isBaseType) {
    super(properties);
    m_isBaseType = isBaseType;
    init(isBaseType);
  }

  @Override
  public void validate() {
    super.validate();
    init(m_isBaseType);
  }

  private void init(boolean isBaseType) {
    Logger logger = LoggerFactory.getLogger(getClass());
    warnIfMissing(logger, ID, getPropertyRaw(ID));
    if (isBaseType) {
      ensureNoExtras(logger, ID);
    }
  }

  /**
   * @return Null only when incorrectly constructed without one
   */
  @Nullable
  public String getId() {
    return getPropertyRaw(ID);
  }

}

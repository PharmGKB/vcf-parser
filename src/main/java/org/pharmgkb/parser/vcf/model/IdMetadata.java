package org.pharmgkb.parser.vcf.model;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A VCF metadata element with an Id (called "ID").
 */
public class IdMetadata extends BaseMetadata {

  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final boolean m_isBaseType;

  public static final String ID = "ID";

  public IdMetadata(String id) {
    this(id, true);
  }

  public IdMetadata(Map<String, String> properties) {
    this(properties, true);
  }

  protected IdMetadata(String id, boolean isBaseType) {
    super(new HashMap<>());
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
    warnIfMissing(sf_logger, ID, getPropertyRaw(ID));
    if (isBaseType) {
      ensureNoExtras(ID);
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

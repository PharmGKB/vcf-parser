package org.pharmgkb.parser.vcf.model;

import com.google.common.base.Preconditions;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class contains sample data for a VCF position line.
 *
 * @author Mark Woon
 */
public class VcfSample {
  private Map<String, String> m_properties = new HashMap<>();

  public VcfSample(@Nullable List<String> keys, @Nullable List<String> values) {
    if (keys == null) {
      if (values == null || values.size() == 0) {
        return;
      }
      throw new IllegalArgumentException("keys is null but values is not");
    } else if (values == null) {
      throw new IllegalArgumentException("values is null but keys is not");
    }
    Preconditions.checkArgument(keys.size() == values.size(), "Number of keys does not match number of values");
    for (int x = 0; x < keys.size(); x++) {
      m_properties.put(keys.get(x), values.get(x));
    }
  }


  @Nullable
  public String getProperty(String key) {
    return m_properties.get(key);
  }
}

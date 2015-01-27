package org.pharmgkb.parser.vcf.model;

import com.google.common.base.Preconditions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;

/**
 * This class contains sample data for a VCF position line.
 *
 * @author Mark Woon
 */
public class VcfSample extends HashMap<String, String> {

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
      put(keys.get(x), values.get(x));
    }
  }

  /**
   * Returns the value for the reserved property as the type specified by both {@link ReservedFormatProperty#getType()}
   * and {@link ReservedFormatProperty#isList()}.
   * @param <T> The type specified by {@code ReservedInfoProperty.getType()} if {@code ReservedFormatProperty.isList()}
   *           is false;
   *           otherwise {@code List<V>} where V is the type specified by {@code ReservedFormatProperty.getType()}.
   */
  public @Nullable <T> T getReserved(@Nonnull ReservedFormatProperty key) {
    return PropertyUtils.convertProperty(key, get(key.getId()));
  }

  public boolean containsReserved(@Nonnull ReservedFormatProperty key) {
    return containsKey(key.getId());
  }


}

package org.pharmgkb.parser.vcf;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;


/**
 * A reserved value for the "Number" field in INFO and FORMAT metadata entries.
 * @author Douglas Myers-Turnbull
 */
public enum SpecialVcfNumber {

  ONE_PER_ALT("A"),
  ONE_PER_ALT_OR_REF("R"),
  ONE_PER_GENOTYPE("G"),
  UNKNOWN_OR_UNBOUNDED(".");

  private static final Map<String, SpecialVcfNumber> BY_ID = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(SpecialVcfNumber::getId, Function.identity()));

  @Nullable
  public static SpecialVcfNumber fromId(@Nullable String id) {
    return id == null ? null : BY_ID.get(id);
  }

  private final String m_id;

  SpecialVcfNumber(String id) {
    m_id = id;
  }

  public String getId() {
    return m_id;
  }
}

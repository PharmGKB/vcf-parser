package org.pharmgkb.parser.vcf;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;


/**
 * This exception indicates a VCF format error was found while parsing the VCF file.
 *
 * @author Mark Woon
 */
public class VcfFormatException extends RuntimeException {
  private int m_lineNumber = -1;
  private @Nullable String m_section;
  private final String m_baseMessage;


  public VcfFormatException(String msg) {
    m_baseMessage = Preconditions.checkNotNull(StringUtils.stripToNull(msg), "msg must not be null or blank");
  }

  public VcfFormatException(String msg, int lineNumber) {
    m_baseMessage = Preconditions.checkNotNull(StringUtils.stripToNull(msg), "msg must not be null or blank");
    m_lineNumber = lineNumber;
  }

  public VcfFormatException(String msg, Throwable ex) {
    super(ex);
    m_baseMessage = Preconditions.checkNotNull(StringUtils.stripToNull(msg), "msg must not be null or blank");
  }

  public VcfFormatException(int lineNumber, String section, Throwable ex) {
    // ex must be checked before use below (ex.getMessage()), but super(...) must be this constructor's first
    // statement -- so the check is embedded in super()'s own argument expression, the same pattern this
    // codebase already uses when a null guard can't precede a mandatory first call (e.g.
    // VcfSample.Genotype's this(...)-delegating constructor). Matches this class's own
    // established convention of a raw NullPointerException (via Preconditions.checkNotNull) for a required
    // constructor argument, the same treatment the other constructors already give a null/blank msg.
    super(Preconditions.checkNotNull(ex, "ex must not be null"));
    m_lineNumber = lineNumber;
    m_section = section;
    String derived = StringUtils.stripToNull(ex.getMessage());
    m_baseMessage = derived != null ? derived : ex.getClass().getName();
  }


  public void addMetadata(int lineNumber, String section) {
    m_lineNumber = lineNumber;
    m_section = section;
  }


  @Override
  public String getMessage() {
    StringBuilder sb = new StringBuilder();
    if (m_lineNumber >= 0) {
      sb.append("[Line #").append(m_lineNumber).append("] ");
    }
    if (m_section != null) {
      sb.append("Error parsing ").append(m_section).append(": ");
    }
    sb.append(m_baseMessage);
    return sb.toString();
  }

  /**
   * @return The line number this exception was attached to, or {@code -1} if none was ever attached (via
   * {@link #VcfFormatException(String, int)}, {@link #addMetadata(int, String)}, or the
   * {@link #VcfFormatException(int, String, Throwable)} constructor).
   */
  public int getLineNumber() {
    return m_lineNumber;
  }

  @Override
  public String toString() {
    return getMessage();
  }
}

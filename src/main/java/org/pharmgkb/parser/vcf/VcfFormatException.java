package org.pharmgkb.parser.vcf;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;


/**
 * This exception indicates a VCF format error was found while parsing the VCF file.
 *
 * @author Mark Woon
 */
public class VcfFormatException extends RuntimeException {
  private int m_lineNumber;
  private String m_section;
  private final String m_baseMessage;


  public VcfFormatException(String msg) {
    Preconditions.checkNotNull(msg);
    m_baseMessage = msg;
  }

  public VcfFormatException(String msg, int lineNumber) {
    Preconditions.checkNotNull(msg);
    m_baseMessage = msg;
    m_lineNumber = lineNumber;
  }

  public VcfFormatException(String msg, Throwable ex) {
    super(ex);
    m_baseMessage = StringUtils.stripToNull(msg);
  }

  public VcfFormatException(int lineNumber, String section, Throwable ex) {
    super(ex);
    m_lineNumber = lineNumber;
    m_section = section;
    m_baseMessage = StringUtils.stripToNull(ex.getMessage());
  }


  public void addMetadata(int lineNumber, String section) {
    m_lineNumber = lineNumber;
    m_section = section;
  }


  @Override
  public String getMessage() {
    if (m_section == null) {
      return m_baseMessage;
    }
    StringBuilder builder = new StringBuilder()
        .append("[Line #")
        .append(m_lineNumber)
        .append("] Error parsing ")
        .append(m_section);
    if (m_baseMessage != null) {
        builder.append(": ")
            .append(m_baseMessage);
    }
    return builder.toString();
  }

  public int getLineNumber() {
    return m_lineNumber;
  }

  @Override
  public String toString() {
    return getMessage();
  }
}

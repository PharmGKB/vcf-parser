package org.pharmgkb.parser.vcf.spec;

import java.io.IOException;
import java.io.Writer;

/**
 * Test-only {@link Writer} that can be armed to fail on every subsequent write/flush, for exercising
 * VcfWriter's PrintWriter-based error detection ({@code checkError()}) without needing a real
 * disk-full/broken-pipe condition.
 */
public class FailingWriter extends Writer {
  private boolean m_failing = false;
  private boolean m_closed = false;

  public void startFailing() {
    m_failing = true;
  }

  public boolean wasClosed() {
    return m_closed;
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    if (m_failing) {
      throw new IOException("simulated write failure");
    }
  }

  @Override
  public void flush() throws IOException {
    if (m_failing) {
      throw new IOException("simulated flush failure");
    }
  }

  @Override
  public void close() {
    m_closed = true;
  }
}

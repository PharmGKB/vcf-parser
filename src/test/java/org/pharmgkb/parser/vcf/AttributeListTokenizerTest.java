package org.pharmgkb.parser.vcf;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;


class AttributeListTokenizerTest {

  private StringWriter m_sink;

  private List<InternalUtils.Attribute> tokenize(String innerText) {
    m_sink = new StringWriter();
    return InternalUtils.tokenizeAttributeList(innerText, new VcfWarnings(m_sink));
  }

  private String warnings() {
    return m_sink.toString();
  }

  private static InternalUtils.Attribute attr(String name, String rawValue) {
    return new InternalUtils.Attribute(name, rawValue);
  }

  @Test
  void bracketValueProtectsItsCommas() {
    assertThat(tokenize("ID=c1,x=[a, b],length=999"),
        contains(attr("ID", "c1"), attr("x", "[a, b]"), attr("length", "999")));
    assertThat(warnings(), is(emptyString()));
  }

  @Test
  void onlyALeadingDelimiterQuotes() {
    // a non-leading quote is an ordinary byte and protects nothing
    assertThat(tokenize("ID=c1,x=a\"b,length=999"),
        contains(attr("ID", "c1"), attr("x", "a\"b"), attr("length", "999")));
    assertThat(tokenize("ID=c1,x=a\"b,y=\"cd\",length=999"),
        contains(attr("ID", "c1"), attr("x", "a\"b"), attr("y", "\"cd\""), attr("length", "999")));
    // ...but a leading one does
    assertThat(tokenize("ID=c1,x=\"a,b\",length=999"),
        contains(attr("ID", "c1"), attr("x", "\"a,b\""), attr("length", "999")));
  }

  @Test
  void junkAfterAClosingDelimiterIsDiscardedWithAWarning() {
    assertThat(tokenize("ID=c1,x=\"a\" junk,length=999"),
        contains(attr("ID", "c1"), attr("x", "\"a\""), attr("length", "999")));
    assertThat(warnings(), containsString("trailing content"));
    assertThat(tokenize("ID=c1,x=[a]b],length=999"),
        contains(attr("ID", "c1"), attr("x", "[a]"), attr("length", "999")));
    assertThat(warnings(), containsString("trailing content"));
  }

  @Test
  void anUnterminatedQuoteBecomesALiteralByte() {
    assertThat(tokenize("ID=c1,x=\"ab,length=999"),
        contains(attr("ID", "c1"), attr("x", "\"ab"), attr("length", "999")));
    assertThat(warnings(), containsString("unterminated quoted value"));
  }

  @Test
  void anUnterminatedBracketIsRequotedSoOutputStaysReadable() {
    assertThat(tokenize("ID=c1,x=[abc,length=999"),
        contains(attr("ID", "c1"), attr("x", "\"[abc\""), attr("length", "999")));
    assertThat(warnings(), containsString("unterminated bracket value"));
    // and its content is escaped, so the emitted line re-parses
    assertThat(tokenize("ID=c1,x=[a\"b,length=999"),
        contains(attr("ID", "c1"), attr("x", "\"[a\\\"b\""), attr("length", "999")));
  }

  @Test
  void aClosingDelimiterIsSearchedForAcrossTheWholeRemainder() {
    // NOT bounded by the next comma -- otherwise the spec's own ##META example would be "unterminated"
    assertThat(tokenize("ID=c1,x=[abc,length=999,y=[d]"),
        contains(attr("ID", "c1"), attr("x", "[abc,length=999,y=[d]")));
    assertThat(warnings(), is(emptyString()));
  }

  @Test
  void spacesOutsideDelimitersAreLayoutAndSpacesInsideAreContent() {
    assertThat(tokenize("ID=c1 ,length=999"), contains(attr("ID", "c1"), attr("length", "999")));
    assertThat(tokenize("ID=c1, x=v"), contains(attr("ID", "c1"), attr("x", "v")));
    assertThat(tokenize("ID=c1,x= abc"), contains(attr("ID", "c1"), attr("x", "abc")));
    assertThat(tokenize("ID=c1,x=  \"a \""), contains(attr("ID", "c1"), attr("x", "\"a \"")));
    assertThat(tokenize("ID=c1,x=[a ]"), contains(attr("ID", "c1"), attr("x", "[a ]")));
    assertThat(tokenize("ID=c1, length =999"), contains(attr("ID", "c1"), attr("length", "999")));
  }

  @Test
  void onlyAsciiSpaceIsLayout() {
    // String#trim() would strip this tab and silently rename the attribute to "length". Only 0x20 is
    // layout, so the tab stays part of the name and the construction allowlist rejects it later.
    assertThat(tokenize("ID=c1,\tlength=999"),
        contains(attr("ID", "c1"), attr("\tlength", "999")));
    assertThat(tokenize("ID=c1,x=\tabc"), contains(attr("ID", "c1"), attr("x", "\tabc")));
  }

  @Test
  void anEqualsSignInAValueIsOrdinary() {
    // c1=x is a spec-conformant contig name. VCFv4.3.tex:262 gives the contig grammar as
    // "[0-9A-Za-z!#$%&+./:;?@^_|~-][0-9A-Za-z!#$%&*+./:;=?@^_|~-]*" -- '=' is in the second class only.
    assertThat(tokenize("ID=c1=x,length=999"), contains(attr("ID", "c1=x"), attr("length", "999")));
  }

  @Test
  void angleBracketsInAValueAreOrdinaryBytesButWarn() {
    assertThat(tokenize("ID=c1,x=a>b,length=999"),
        contains(attr("ID", "c1"), attr("x", "a>b"), attr("length", "999")));
    assertThat(warnings(), containsString("structural"));
    assertThat(tokenize("ID=c1,x=a<b,length=999"),
        contains(attr("ID", "c1"), attr("x", "a<b"), attr("length", "999")));
    assertThat(warnings(), containsString("structural"));
  }

  @Test
  void degenerateAttributesAreDroppedIndividually() {
    assertThat(tokenize(""), emptyIterable());
    assertThat(tokenize("ID=c1,,length=999"), contains(attr("ID", "c1"), attr("length", "999")));
    assertThat(tokenize("ID=c1,junk,length=999"), contains(attr("ID", "c1"), attr("length", "999")));
    assertThat(warnings(), containsString("has no '='"));
    assertThat(tokenize("ID=c1,=v,length=999"), contains(attr("ID", "c1"), attr("length", "999")));
    assertThat(warnings(), containsString("empty name"));
    assertThat(tokenize("ID=c1,x=,length=999"),
        contains(attr("ID", "c1"), attr("x", ""), attr("length", "999")));
    assertThat(tokenize("ID=c1,length=999,"), contains(attr("ID", "c1"), attr("length", "999")));
    assertThat(warnings(), containsString("empty trailing attribute"));
    // A review flagged this shape as possibly producing a SPURIOUS trailing-attribute warning after an
    // unterminated-bracket recovery. It could not be reproduced: the loop only exits with i == n when the
    // final byte really was a consumed separator, so the warning is correct here. Pinned so that if the
    // recovery branch ever changes, this asserts the current, intended behaviour rather than drifting.
    assertThat(tokenize("ID=c1,x=[abc,"),
        contains(attr("ID", "c1"), attr("x", "\"[abc\"")));
    assertThat(warnings(), containsString("empty trailing attribute"));
  }

  @Test
  void escapedDelimitersDoNotClose() {
    assertThat(tokenize("Description=\"a\\\"b\",length=999"),
        contains(attr("Description", "\"a\\\"b\""), attr("length", "999")));
    assertThat(tokenize("ID=c1,x=[a\\]b],length=999"),
        contains(attr("ID", "c1"), attr("x", "[a\\]b]"), attr("length", "999")));
    // an EVEN run is an escaped backslash, so the ']' after it DOES close
    assertThat(tokenize("ID=c1,x=[a\\\\],length=999"),
        contains(attr("ID", "c1"), attr("x", "[a\\\\]"), attr("length", "999")));
    assertThat(tokenize("ID=c1,x=[],length=999"),
        contains(attr("ID", "c1"), attr("x", "[]"), attr("length", "999")));
  }

  @Test
  void terminatesOnEveryDegenerateInput() {
    // regression guard: an earlier draft of the algorithm looped forever on "ID=c1,=v"
    for (String s : new String[] {",", ",,", "=", "==", "a", "a=", "=a", "[", "\"", "a=[", "a=\"",
        "ID=c1,=v", "ID=c1,=,x=1", " ", "\\"}) {
      tokenize(s); // must return, not hang or throw
    }
  }
}

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

  /** Convenience overload for every test below that isn't about {@code ##META}'s {@code Values} specifically --
   * the kind is irrelevant to them, so a neutral, never-"META" placeholder keeps their diffs unrelated to this
   * file's bracket-scoping tests. */
  private List<InternalUtils.Attribute> tokenize(String innerText) {
    return tokenize(innerText, "contig");
  }

  private List<InternalUtils.Attribute> tokenize(String innerText, String kind) {
    m_sink = new StringWriter();
    return InternalUtils.tokenizeAttributeList(innerText, new VcfWarnings(m_sink), kind);
  }

  private String warnings() {
    return m_sink.toString();
  }

  private static InternalUtils.Attribute attr(String name, String rawValue) {
    return new InternalUtils.Attribute(name, rawValue);
  }

  @Test
  void bracketValueProtectsCommasOnlyForMetaValues() {
    // the ONE place any VCF version (4.3-4.5) evidences "[...]" as a comma-protecting form at all --
    // VCFv4.3.tex:273-276's worked examples -- see VCF_SPEC_AMBIGUITIES.md's "General bracket-list quoting
    // beyond ##META's Values" entry for why this is scoped this narrowly rather than generalized the way
    // htslib generalizes it.
    assertThat(tokenize("ID=c1,Values=[a, b],length=999", "META"),
        contains(attr("ID", "c1"), attr("Values", "[a, b]"), attr("length", "999")));
    assertThat(warnings(), is(emptyString()));
  }

  @Test
  void bracketValueDoesNotProtectCommasElsewhere() {
    // same shape, but neither the declaration type nor the attribute name is META's Values -- '[' is now an
    // ordinary byte, exactly like '<', and the comma still ends the attribute. The truncated value has no
    // closing ']' of its own (the comma split "[a, b]" apart from its ']'), so it is quoted for safety rather
    // than stored raw -- see bracketValueDoesNotProtectCommasElsewhereButStaysBcftoolsSafe below for why.
    assertThat(tokenize("ID=c1,x=[a, b],length=999"),
        contains(attr("ID", "c1"), attr("x", "\"[a\""), attr("length", "999")));
    assertThat(warnings(), containsString("closing it"));
    // same value, but on META itself under a DIFFERENT attribute name -- still not protected: the gate is on
    // the exact attribute name "Values", not merely the declaration type
    assertThat(tokenize("ID=c1,Extra=[a, b],length=999", "META"),
        contains(attr("ID", "c1"), attr("Extra", "\"[a\""), attr("length", "999")));
    assertThat(warnings(), containsString("closing it"));
  }

  @Test
  void bracketValueDoesNotProtectCommasElsewhereButStaysBcftoolsSafe() {
    // regression test for a real bug: writing the truncated value back out RAW ("[a") would manufacture a
    // value bcftools cannot read at all (exit 255) from an input that was perfectly well-formed -- not a case
    // of carrying forward already-malformed content, but of this library's own comma-splitting creating new
    // fatal content. Quoting the truncated value keeps it exactly as informative (still split at the comma,
    // per the design decision that '[' does not protect it here) while staying safe to re-emit.
    assertThat(tokenize("ID=c1,x=[a, b],length=999"),
        contains(attr("ID", "c1"), attr("x", "\"[a\""), attr("length", "999")));
    // a value that DOES still contain a ']' after truncation needs no such rescue -- it is not dangling
    assertThat(tokenize("ID=c1,x=[a],length=999"),
        contains(attr("ID", "c1"), attr("x", "[a]"), attr("length", "999")));
    assertThat(warnings(), containsString("structural"));
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
    // same shape for META's Values bracket form
    assertThat(tokenize("ID=c1,Values=[a]b],length=999", "META"),
        contains(attr("ID", "c1"), attr("Values", "[a]"), attr("length", "999")));
    assertThat(warnings(), containsString("trailing content"));
  }

  @Test
  void anUnterminatedQuoteBecomesALiteralByte() {
    assertThat(tokenize("ID=c1,x=\"ab,length=999"),
        contains(attr("ID", "c1"), attr("x", "\"ab"), attr("length", "999")));
    assertThat(warnings(), containsString("unterminated quoted value"));
  }

  @Test
  void anUnterminatedMetaValuesBracketIsPreservedAsIsNotRequoted() {
    // parse-only leniency, and deliberately NOT the same recovery an unterminated quote gets: this used to
    // re-quote defensively (back when '[' was a general-purpose delimiter reachable on any attribute, so an
    // unterminated one made bcftools exit 255 for ANY caller's file); now that it is scoped to this one
    // spec-evidenced attribute, construction rejects an unterminated bracket outright (findEncodingProblems),
    // so a value reaching this branch can only be carried-forward content from someone else's file -- emit it
    // verbatim and let a downstream tool decide what to do with it, per the explicit design decision.
    //
    // This is NOT the same fix the non-Values case needed (see AttributeListTokenizerTest's sibling suite for
    // that): Values's own closing-']' scan is unbounded by comma (aMetaValuesClosingBracketIsSearchedForAcrossTheWholeRemainder
    // below), so this library's own splitting can never manufacture a dangling '[' here the way it could
    // outside Values -- the only way this branch is ever reached is if the ORIGINAL file's Values value
    // already had no closing ']' anywhere in the rest of the line. bcftools would already fail fatally reading
    // that same original file, before this library was ever involved, so re-emitting it as-is introduces no
    // NEW failure this library caused. bcftools' fatal exit on an unterminated '[' at all is a filed defect,
    // https://github.com/samtools/htslib/issues/2065 -- see VCF_SPEC_AMBIGUITIES.md's "General bracket-list
    // quoting beyond ##META's Values" entry for the full reasoning.
    assertThat(tokenize("ID=c1,Values=[abc,length=999", "META"),
        contains(attr("ID", "c1"), attr("Values", "[abc"), attr("length", "999")));
    assertThat(warnings(), containsString("no closing ']'"));
    // the content is NOT escaped/re-encoded the way the quoted-value recovery would be
    assertThat(tokenize("ID=c1,Values=[a\"b,length=999", "META"),
        contains(attr("ID", "c1"), attr("Values", "[a\"b"), attr("length", "999")));
  }

  @Test
  void aMetaValuesClosingBracketIsSearchedForAcrossTheWholeRemainder() {
    // NOT bounded by the next comma -- otherwise the spec's own ##META example would be "unterminated"
    assertThat(tokenize("ID=c1,Values=[abc,length=999,y=[d]", "META"),
        contains(attr("ID", "c1"), attr("Values", "[abc,length=999,y=[d]")));
    assertThat(warnings(), is(emptyString()));
  }

  @Test
  void spacesOutsideDelimitersAreLayoutAndSpacesInsideAreContent() {
    assertThat(tokenize("ID=c1 ,length=999"), contains(attr("ID", "c1"), attr("length", "999")));
    assertThat(tokenize("ID=c1, x=v"), contains(attr("ID", "c1"), attr("x", "v")));
    assertThat(tokenize("ID=c1,x= abc"), contains(attr("ID", "c1"), attr("x", "abc")));
    assertThat(tokenize("ID=c1,x=  \"a \""), contains(attr("ID", "c1"), attr("x", "\"a \"")));
    assertThat(tokenize("ID=c1,Values=[a ]", "META"), contains(attr("ID", "c1"), attr("Values", "[a ]")));
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
  void leadingBracketOutsideMetaValuesIsOrdinaryButWarnsLikeAngleBrackets() {
    // the exact same treatment '<'/'>' get above -- see VCF_SPEC_AMBIGUITIES.md's "General bracket-list
    // quoting beyond ##META's Values" entry
    assertThat(tokenize("ID=c1,x=[a],length=999"),
        contains(attr("ID", "c1"), attr("x", "[a]"), attr("length", "999")));
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
    assertThat(tokenize("ID=c1,Values=[abc,", "META"),
        contains(attr("ID", "c1"), attr("Values", "[abc")));
    assertThat(warnings(), containsString("empty trailing attribute"));
  }

  @Test
  void escapedDelimitersDoNotClose() {
    assertThat(tokenize("Description=\"a\\\"b\",length=999"),
        contains(attr("Description", "\"a\\\"b\""), attr("length", "999")));
  }

  @Test
  void metaValuesBracketsHaveNoEscapeMechanism() {
    // the spec defines no way to escape '[' or ']' -- unlike the quoted-value form's backslash escaping,
    // the closing ']' is found by a plain, unescaped scan. A backslash before ']' is ordinary content, not
    // an escape: it does NOT protect that ']' from closing the value.
    assertThat(tokenize("ID=c1,Values=[a\\]b],length=999", "META"),
        contains(attr("ID", "c1"), attr("Values", "[a\\]"), attr("length", "999")));
    assertThat(warnings(), containsString("trailing content"));
    assertThat(tokenize("ID=c1,Values=[a\\\\],length=999", "META"),
        contains(attr("ID", "c1"), attr("Values", "[a\\\\]"), attr("length", "999")));
    assertThat(tokenize("ID=c1,Values=[],length=999", "META"),
        contains(attr("ID", "c1"), attr("Values", "[]"), attr("length", "999")));
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

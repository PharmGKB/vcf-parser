package org.pharmgkb.parser.vcf;

import java.util.Locale;
import org.jspecify.annotations.Nullable;


/**
 * Opt-in percent-encoding/decoding for the eight sequences VCFv4.3.tex's "Character encoding, non-printable
 * characters and characters with special meaning" section tables ({@code VCFv4.3.tex:83} states the rule --
 * "for any other meaning they must be represented with the capitalized percent encoding" -- and the table at
 * {@code VCFv4.3.tex:86-95} lists the eight sequences): {@code %3A %3B %3D %25 %2C %0D %0A %09} for
 * {@code : ; = % , CR LF TAB} respectively.
 * <p>
 * Per that same section (and {@code VCFv4.3.tex:365}'s INFO-value grammar note, and the "Changes between
 * VCFv4.2 and VCFv4.3" errata at {@code VCFv4.3.tex:2152}, "Characters with special meaning (such as `;' in
 * INFO, `:' in FORMAT, and `%' in both) can be encoded using percent encoding"), percent-encoding is only
 * meaningful within an INFO value, a sample (FORMAT) value, or a structured metadata declaration's attribute
 * value -- never within an ID, a FILTER code, a FORMAT/INFO key, a metadata declaration ID, or a symbolic ALT
 * allele, none of which permit a literal delimiter character in the first place (see
 * {@code VCF_SPEC_AMBIGUITIES.md}'s "Percent-encoding field
 * scope" entry for the full citation trail). This class is a pure {@code String} function with no way to know
 * which field it was handed, so it does not and cannot enforce that scope -- restricting its use to the fields
 * above is the caller's own responsibility.
 * <p>
 * <b>Nothing in this library's model layer (parsing, writing, or any {@code VcfPosition}/{@code VcfSample}/
 * {@code BaseMetadata} accessor) calls either method here.</b> Every value this library exposes is raw wire
 * text, exactly as it appeared in (or as it will appear in) a VCF file, and this class exists precisely so that
 * stays true: a decoded {@code String} cannot distinguish a parsed literal {@code %3A} (which must round-trip
 * back out as {@code %3A}) from a caller-supplied, already-decoded {@code ":"} (which would need to be written
 * back out as {@code %3A} to remain valid) -- one in-memory representation, two irreconcilable serializations.
 * Decoding and re-encoding a value is therefore an action a caller takes deliberately, on a value it knows the
 * provenance of, never something this library does on the caller's behalf.
 *
 * @author Mark Woon
 */
public final class VcfPercentEncoding {

  private VcfPercentEncoding() {}

  /**
   * Decodes the eight percent-encoded sequences ({@code %3A %3B %3D %25 %2C %0D %0A %09}) in {@code s} back to
   * their literal characters ({@code : ; = % , CR LF TAB}), leaving every other character -- including a
   * {@code '%'} that isn't followed by one of those eight two-character codes -- unchanged.
   * <p>
   * Hex digits are matched case-insensitively (so {@code %3a}/{@code %3A} both decode to {@code ':'}), but a
   * decoded output is always the literal character, never re-cased.
   * <p>
   * This is a single left-to-right scan: at each {@code '%'}, the next two characters are matched against the
   * table above and, on a match, all three characters are consumed and replaced by the single decoded
   * character; on no match, only the {@code '%'} itself is copied through and the scan resumes at the very next
   * character. Because the scan never revisits a character it has already emitted, a sequence that a decode
   * step itself produces (e.g. the literal {@code %3B} left behind after {@code %253B}'s leading {@code %25}
   * decodes to a literal {@code '%'}) is never itself decoded again -- {@code decode("%253B")} is
   * {@code "%3B"}, not {@code ";"}.
   *
   * @throws VcfFormatException if {@code s} is {@code null}
   */
  public static String decode(@Nullable String s) {
    if (s == null) {
      throw new VcfFormatException("Cannot percent-decode a null string");
    }
    StringBuilder sb = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '%' && i + 2 < s.length()) {
        Character decoded = decodeHex(s.substring(i + 1, i + 3).toUpperCase(Locale.ROOT));
        if (decoded != null) {
          sb.append(decoded.charValue());
          i += 3;
          continue;
        }
      }
      sb.append(c);
      i++;
    }
    return sb.toString();
  }

  /**
   * Encodes each of the eight delimiter-colliding characters ({@code : ; = % , CR LF TAB}) in {@code s} as
   * {@code VCFv4.3.tex:83}'s canonical uppercase percent-encoded spelling ({@code %3A %3B %3D %25 %2C %0D %0A
   * %09}); every other character is copied through unchanged.
   *
   * @throws VcfFormatException if {@code s} is {@code null}
   */
  public static String encode(@Nullable String s) {
    if (s == null) {
      throw new VcfFormatException("Cannot percent-encode a null string");
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      String encoded = encodeChar(c);
      if (encoded == null) {
        sb.append(c);
      } else {
        sb.append(encoded);
      }
    }
    return sb.toString();
  }

  /** @return the literal character for one of the eight tabled two-hex-digit codes, given an already
   * upper-cased two-character {@code hex}, or {@code null} if {@code hex} isn't one of them. */
  private static @Nullable Character decodeHex(String hex) {
    return switch (hex) {
      case "3A" -> ':';
      case "3B" -> ';';
      case "3D" -> '=';
      case "25" -> '%';
      case "2C" -> ',';
      case "0D" -> '\r';
      case "0A" -> '\n';
      case "09" -> '\t';
      default -> null;
    };
  }

  /** @return {@code c}'s canonical uppercase percent-encoded spelling, or {@code null} if {@code c} isn't one
   * of the eight tabled characters. */
  private static @Nullable String encodeChar(char c) {
    return switch (c) {
      case ':' -> "%3A";
      case ';' -> "%3B";
      case '=' -> "%3D";
      case '%' -> "%25";
      case ',' -> "%2C";
      case '\r' -> "%0D";
      case '\n' -> "%0A";
      case '\t' -> "%09";
      default -> null;
    };
  }
}

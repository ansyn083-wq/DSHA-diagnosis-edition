package com.deepseekharness.app.util;

import java.net.URI;

/** 只做无副作用的 Markdown 公式边界和链接检查。 */
public final class NativeChatMarkup {
  // 仅允许经过本地渲染用途检查的命令；不接受完整 TeX 的宏、资源与尺寸语言。
  private static final java.util.Set<String> MATH_COMMANDS =
      java.util.Set.of(
          ("frac dfrac tfrac cfrac binom dbinom tbinom sqrt left right middle big bigl bigr bigm "
                  + "sum prod coprod int iint iiint iiiint idotsint oint lim liminf limsup sup inf max min "
                  + "sin cos tan cot sec csc sinh cosh tanh coth arcsin arccos arctan log ln exp lg "
                  + "det dim gcd arg ker deg hom pr mod bmod pmod "
                  + "alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa lambda mu "
                  + "nu xi omicron pi varpi rho varrho sigma varsigma tau upsilon phi varphi chi psi omega "
                  + "partial nabla infty ell hbar imath jmath re im wp emptyset varnothing aleph "
                  + "times div cdot ast star circ bullet pm mp cup cap setminus uplus oplus otimes odot "
                  + "le leq ge geq neq ne approx sim simeq equiv propto ll gg prec succ preceq succeq "
                  + "in notin ni subset supset subseteq supseteq nsubseteq nsupseteq parallel perp mid "
                  + "forall exists neg land lor wedge vee top bot to mapsto gets leftarrow rightarrow "
                  + "leftrightarrow longleftarrow longrightarrow longleftrightarrow uparrow downarrow "
                  + "updownarrow implies iff lbrace rbrace langle rangle lvert rvert lfloor rfloor lceil rceil "
                  + "ldots cdots vdots ddots dots quad qquad thinspace medspace thickspace "
                  + "overline underline bar hat widehat tilde widetilde vec dot ddot check breve acute grave "
                  + "overset underset stackrel overbrace underbrace phantom hphantom vphantom "
                  + "text textrm textbf textit textsf texttt textnormal mathrm mathbf mathit mathsf mathtt "
                  + "mathnormal mathcal mathbb mathfrak boldsymbol operatorname displaystyle textstyle "
                  + "scriptstyle scriptscriptstyle limits nolimits begin end")
              .split(" "));
  private static final java.util.Set<String> MATH_ENVIRONMENTS =
      java.util.Set.of(
          "matrix",
          "pmatrix",
          "bmatrix",
          "Bmatrix",
          "vmatrix",
          "Vmatrix",
          "smallmatrix",
          "cases",
          "aligned",
          "gathered");

  private NativeChatMarkup() {}

  public static boolean safeLink(String value) {
    if (value == null || value.indexOf('\\') >= 0) return false;
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i)) || Character.isWhitespace(value.charAt(i)))
        return false;
    }
    try {
      URI uri = new URI(value);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null;
    } catch (Exception invalid) {
      return false;
    }
  }

  /** 不执行资源命令或改变共享宏环境，限制单公式长度及递归深度。 */
  public static boolean safeFormula(String source) {
    if (source == null || source.length() > 4096) return false;
    int depth = 0, commands = 0, environments = 0, separators = 0;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\') {
        if (++commands > 256 || i + 1 == source.length()) return false;
        int start = ++i;
        while (i < source.length() && Character.isLetter(source.charAt(i))) i++;
        if (i == start) {
          if ("\\,;:!{}%$#_&| /".indexOf(source.charAt(i)) < 0) return false;
          continue;
        }
        String command = source.substring(start, i);
        if (!MATH_COMMANDS.contains(command.toLowerCase(java.util.Locale.ROOT))) return false;
        if (command.equals("begin") || command.equals("end")) {
          int brace = i;
          while (brace < source.length() && Character.isWhitespace(source.charAt(brace))) brace++;
          int close = source.indexOf('}', brace);
          if (brace == source.length()
              || source.charAt(brace) != '{'
              || close < 0
              || !MATH_ENVIRONMENTS.contains(source.substring(brace + 1, close))) return false;
          if (command.equals("begin") && ++environments > 8) return false;
        }
        i--;
      } else if (c == '{') {
        if (++depth > 32) return false;
      } else if (c == '}') {
        if (--depth < 0) return false;
      } else if (c == '&' && ++separators > 128) {
        return false;
      }
    }
    return depth == 0;
  }

  /** 转换常见数学分隔符；保留不完整消息、货币、转义以及代码。 */
  public static String normalizeMath(String source) {
    if (source == null || source.isEmpty()) return "";
    StringBuilder out = new StringBuilder(source.length());
    char fence = 0;
    int fenceLength = 0;
    boolean display = false;
    String[] lines = source.split("\n", -1);
    for (int n = 0; n < lines.length; n++) {
      if (n > 0) out.append('\n');
      String line = lines[n];
      String trimmed = line.stripLeading();
      int indent = line.length() - trimmed.length();
      char first = trimmed.isEmpty() ? 0 : trimmed.charAt(0);
      int run = runLength(trimmed, 0, first);
      if (fence != 0) {
        out.append(line);
        if (indent <= 3
            && first == fence
            && run >= fenceLength
            && trimmed.substring(run).trim().isEmpty()) {
          fence = 0;
        }
        continue;
      }
      if (indent <= 3 && (first == '`' || first == '~') && run >= 3) {
        fence = first;
        fenceLength = run;
        out.append(line);
        continue;
      }
      if (indent >= 4 || line.startsWith("\t")) {
        out.append(line);
        continue;
      }
      String whole = line.trim();
      if (whole.equals("\\[") || whole.equals("\\]")) {
        out.append("$$");
        display = whole.equals("\\[");
      } else if (whole.startsWith("\\[") && whole.endsWith("\\]")) {
        out.append("$$\n").append(whole, 2, whole.length() - 2).append("\n$$");
      } else if (whole.matches("\\${2,}")) {
        display = !display;
        out.append(line);
      } else if (display) {
        out.append(line);
      } else {
        appendInline(out, line);
      }
    }
    return out.toString();
  }

  private static void appendInline(StringBuilder out, String line) {
    for (int i = 0; i < line.length(); ) {
      char c = line.charAt(i);
      if (c == '`') {
        int length = runLength(line, i, '`');
        int close = line.indexOf("`".repeat(length), i + length);
        int end = close < 0 ? line.length() : close + length;
        out.append(line, i, end);
        i = end;
      } else if (line.startsWith("\\(", i)) {
        int end = line.indexOf("\\)", i + 2);
        if (end < 0) {
          out.append(line.substring(i));
          return;
        }
        out.append("$$").append(line, i + 2, end).append("$$");
        i = end + 2;
      } else if (c == '\\' && i + 1 < line.length()) {
        out.append(line, i, i + 2);
        i += 2;
      } else if (c == '$') {
        int length = runLength(line, i, '$');
        if (length != 1) {
          int close = line.indexOf("$".repeat(length), i + length);
          int end = close < 0 ? line.length() : close + length;
          out.append(line, i, end);
          i = end;
          continue;
        }
        int close = closingDollar(line, i + 1);
        if (close > i + 1
            && !line.substring(i + 1, close).contains("\\$")
            && !Character.isWhitespace(line.charAt(i + 1))
            && !Character.isWhitespace(line.charAt(close - 1))
            && (close + 1 == line.length() || !Character.isDigit(line.charAt(close + 1)))) {
          out.append("$$").append(line, i + 1, close).append("$$");
          i = close + 1;
        } else {
          out.append(c);
          i++;
        }
      } else {
        out.append(c);
        i++;
      }
    }
  }

  private static int closingDollar(String line, int from) {
    for (int i = from; i < line.length(); i++) {
      if (line.charAt(i) == '\\') {
        i++;
        continue;
      }
      if (line.charAt(i) == '$') return i;
    }
    return -1;
  }

  private static int runLength(String text, int start, char symbol) {
    int end = start;
    while (end < text.length() && text.charAt(end) == symbol) end++;
    return end - start;
  }
}

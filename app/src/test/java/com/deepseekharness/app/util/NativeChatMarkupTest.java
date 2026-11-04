package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** 公式边界转换不得碰代码、转义美元或货币。 */
public class NativeChatMarkupTest {
  private String normalize(String source) {
    return NativeChatMarkup.normalizeMath(source);
  }

  @Test
  public void translatesInlineSingleDollarAndParentheses() throws Exception {
    assertEquals(
        "Use $$x^2$$ and $$\\frac{a}{b}$$.", normalize("Use $x^2$ and \\(\\frac{a}{b}\\)."));
  }

  @Test
  public void translatesDisplayBracketDelimiters() throws Exception {
    assertEquals("$$\n\\sum_{i=1}^n i\n$$", normalize("\\[\n\\sum_{i=1}^n i\n\\]"));
    assertEquals("$$\nx=2\n$$", normalize("\\[x=2\\]"));
  }

  @Test
  public void leavesFencedAndInlineCodeUntouched() throws Exception {
    String source = "`$x$` ``\\(x\\)``\n```latex\n$x$\n\\[x\\]\n```\n~~~\n$x$\n~~~";
    assertEquals(source, normalize(source));
  }

  @Test
  public void preservesExistingDoubleDollarsCurrencyEscapesAndIncompleteStreaming()
      throws Exception {
    String source = "$$\nx=1\n$$\nInline $$x$$ costs $5 and $10. \\$x$ unclosed $y";
    assertEquals(source, normalize(source));
  }

  @Test
  public void safeLinksRejectCredentialsNonWebSchemesAndControls() throws Exception {
    assertTrue(safe("https://example.com/docs?q=1"));
    assertTrue(safe("http://localhost:3080/"));
    for (String target :
        new String[] {
          "https://user:secret@example.com/",
          "javascript:alert(1)",
          "file:///etc/passwd",
          "intent://example.com",
          "https://example.com/\nattack",
          "//example.com/",
          "https://"
        }) {
      assertFalse(target, safe(target));
    }
  }

  @Test
  public void formulasCannotDefineGlobalMacrosOrLoadResources() throws Exception {
    assertTrue(formulaSafe("\\frac{a}{b} + x^2"));
    for (String source :
        new String[] {
          "\\newcommand{\\evil}{evil}",
          "\\includegraphics{https://example.com/x}",
          "\\input{/private/key}",
          "x".repeat(4097),
          "{".repeat(33) + "x" + "}".repeat(33)
        }) {
      assertFalse(source, formulaSafe(source));
    }
  }

  @Test
  public void formulasCannotMutateSharedFontSizeColorOrConverters() {
    for (String command :
        new String[] {
          "DeclareMathSizes",
          "magnification",
          "jlmExternalFont",
          "definecolor",
          "jlmDynamic",
          "jlmXML",
          "csname"
        }) {
      assertFalse(command, NativeChatMarkup.safeFormula("\\" + command + "{1}{2}{3}{4}"));
    }
  }

  @Test
  public void onlyBoundedCommonMathCommandsAndEnvironmentsRender() {
    assertTrue(NativeChatMarkup.safeFormula("\\sqrt{x} + \\sin \\alpha + \\mathbb{R}"));
    assertTrue(NativeChatMarkup.safeFormula("\\begin{bmatrix}1&2\\\\3&4\\end{bmatrix}"));
    assertTrue(NativeChatMarkup.safeFormula("\\left\\{x\\right\\}"));
    for (String formula :
        new String[] {
          "\\Roman{999999999}",
          "\\rule{999999em}{999999em}",
          "\\hspace{999999cm}",
          "\\resizebox{999999cm}{999999cm}{x}",
          "\\multicolumn{999999999}{c}{x}",
          "\\begin{array}{*{999999999}{c}}x\\end{array}",
          "\\unknownFutureMacro{x}",
          "\\begin{matrix}".repeat(9) + "x" + "\\end{matrix}".repeat(9)
        }) {
      assertFalse(formula, NativeChatMarkup.safeFormula(formula));
    }
  }

  private boolean formulaSafe(String source) {
    return NativeChatMarkup.safeFormula(source);
  }

  private boolean safe(String target) {
    return NativeChatMarkup.safeLink(target);
  }
}

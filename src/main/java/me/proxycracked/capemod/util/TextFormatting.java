package me.proxycracked.capemod.util;

public final class TextFormatting {
  public static final char COLOR_CHAR = '§';

  private TextFormatting() {}

  public static String translate(String text) {
    if (text == null) return "";
    char[] b = text.toCharArray();
    for (int i = 0; i < b.length - 1; i++) {
      if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(b[i + 1]) > -1) {
        b[i] = COLOR_CHAR;
        b[i + 1] = Character.toLowerCase(b[i + 1]);
      }
    }
    return new String(b);
  }
}

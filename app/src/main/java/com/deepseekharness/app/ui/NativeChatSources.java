package com.deepseekharness.app.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Extracts explicit links in reply prose for the native sources sheet. */
final class NativeChatSources {
  record Source(String title, String url) {}

  private static final Pattern LINK =
      Pattern.compile(
          "(?<![!\\\\])\\[([^\\[\\]\\r\\n]{1,200})\\]\\((https?://[^\\s()<>]{1,2048})\\)",
          Pattern.CASE_INSENSITIVE);

  private NativeChatSources() {}

  static List<Source> fromReply(String text) {
    var sources = new LinkedHashMap<String, Source>();
    boolean fenced = false;
    for (String line : text.split("\\R")) {
      if (line.stripLeading().startsWith("```")) {
        fenced = !fenced;
        continue;
      }
      if (fenced) continue;
      var links = LINK.matcher(line.replaceAll("`[^`]*`", ""));
      while (links.find()) {
        try {
          URI url = new URI(links.group(2));
          if (url.getHost() == null || url.getRawUserInfo() != null) continue;
          String address = url.toASCIIString();
          sources.putIfAbsent(address, new Source(links.group(1), address));
          if (sources.size() == 30) return List.copyOf(sources.values());
        } catch (URISyntaxException invalid) {
          // Ignore malformed targets.
        }
      }
    }
    return List.copyOf(sources.values());
  }
}

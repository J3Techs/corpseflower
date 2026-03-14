package org.corpseflower;

import java.util.Map;

public final class CorpseflowerPreferences {
  public static final String VERBOSE = "corpseflower.verbose";
  public static final String NO_DEOBFUSCATE = "corpseflower.no.deobfuscate";
  public static final String NO_QUALITY_GATE = "corpseflower.no.quality.gate";
  public static final String INPUT_PATH = "corpseflower.input.path";

  private CorpseflowerPreferences() {
  }

  public static void applyDefaults(Map<String, Object> options) {
    options.putIfAbsent(VERBOSE, "0");
    options.putIfAbsent(NO_DEOBFUSCATE, "0");
    options.putIfAbsent(NO_QUALITY_GATE, "0");
    options.putIfAbsent(INPUT_PATH, "");
  }
}

package org.corpseflower;

import org.jetbrains.java.decompiler.main.decompiler.OptionParser;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.util.JrtFinder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CorpseflowerOptions {
  private final Path input;
  private final Path output;
  private final boolean deobfuscate;
  private final boolean qualityGate;
  private final boolean verbose;
  private final boolean showHelp;
  private final Map<String, Object> fernflowerOptions;

  private CorpseflowerOptions(
    Path input,
    Path output,
    boolean deobfuscate,
    boolean qualityGate,
    boolean verbose,
    boolean showHelp,
    Map<String, Object> fernflowerOptions
  ) {
    this.input = input;
    this.output = output;
    this.deobfuscate = deobfuscate;
    this.qualityGate = qualityGate;
    this.verbose = verbose;
    this.showHelp = showHelp;
    this.fernflowerOptions = fernflowerOptions;
  }

  static CorpseflowerOptions parse(String[] args) {
    Map<String, Object> fernflowerOptions = new HashMap<>(IFernflowerPreferences.DEFAULTS);
    fernflowerOptions.put(IFernflowerPreferences.INCLUDE_JAVA_RUNTIME, JrtFinder.CURRENT);
    fernflowerOptions.put(IFernflowerPreferences.THREADS, "0");
    fernflowerOptions.put(IFernflowerPreferences.LOG_LEVEL, "warn");

    boolean deobfuscate = true;
    boolean qualityGate = true;
    boolean verbose = false;
    boolean showHelp = false;
    List<String> positionals = new ArrayList<>();

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "-h", "--help", "-help" -> showHelp = true;
        case "--deobfuscate" -> deobfuscate = true;
        case "--no-deobfuscate" -> deobfuscate = false;
        case "--quality-gate" -> qualityGate = true;
        case "--no-quality-gate" -> qualityGate = false;
        case "--verbose" -> {
          verbose = true;
          fernflowerOptions.put(IFernflowerPreferences.LOG_LEVEL, "info");
        }
        case "--threads" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for --threads");
          }
          fernflowerOptions.put(IFernflowerPreferences.THREADS, args[++i]);
        }
        default -> {
          if (arg.startsWith("-")) {
            OptionParser.parse(arg, fernflowerOptions);
          } else {
            positionals.add(arg);
          }
        }
      }
    }

    if (showHelp) {
      return new CorpseflowerOptions(null, null, deobfuscate, qualityGate, verbose, true, fernflowerOptions);
    }

    if (positionals.size() != 2) {
      throw new IllegalArgumentException("Expected exactly 2 positional arguments: <input> <output>");
    }

    Path input = Path.of(positionals.get(0)).toAbsolutePath().normalize();
    Path output = Path.of(positionals.get(1)).toAbsolutePath().normalize();
    if (!Files.exists(input)) {
      throw new IllegalArgumentException("Input path does not exist: " + input);
    }

    fernflowerOptions.put(CorpseflowerPreferences.VERBOSE, verbose ? "1" : "0");
    fernflowerOptions.put(CorpseflowerPreferences.NO_DEOBFUSCATE, deobfuscate ? "0" : "1");
    fernflowerOptions.put(CorpseflowerPreferences.NO_QUALITY_GATE, qualityGate ? "0" : "1");

    return new CorpseflowerOptions(input, output, deobfuscate, qualityGate, verbose, false, fernflowerOptions);
  }

  Path input() {
    return input;
  }

  Path output() {
    return output;
  }

  boolean deobfuscate() {
    return deobfuscate;
  }

  boolean qualityGate() {
    return qualityGate;
  }

  boolean verbose() {
    return verbose;
  }

  boolean showHelp() {
    return showHelp;
  }

  Map<String, Object> fernflowerOptions() {
    return new HashMap<>(fernflowerOptions);
  }
}

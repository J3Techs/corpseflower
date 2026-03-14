package org.corpseflower;

public final class CorpseflowerMain {
  private CorpseflowerMain() {
  }

  public static void main(String[] args) {
    try {
      CorpseflowerOptions options = CorpseflowerOptions.parse(args);
      if (options.showHelp()) {
        printUsage(null);
        return;
      }

      new CorpseflowerRunner(options).run();
    } catch (IllegalArgumentException e) {
      printUsage(e.getMessage());
    } catch (Exception e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  static void printUsage(String error) {
    if (error != null && !error.isBlank()) {
      System.err.println("error: " + error);
    }
    System.out.println("Usage: java -jar corpseflower.jar [options] <input.jar> <output_dir>");
    System.out.println("Options:");
    System.out.println("  --deobfuscate");
    System.out.println("  --no-deobfuscate");
    System.out.println("  --quality-gate");
    System.out.println("  --no-quality-gate");
    System.out.println("  --verbose");
    System.out.println("  --threads <count>");
    System.out.println("  -h, --help");
  }
}

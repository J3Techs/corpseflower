package org.corpseflower;

import org.corpseflower.deobfuscation.DeobfuscationStage;
import org.corpseflower.deobfuscation.DeobfuscationStage.Result;
import org.corpseflower.quality.CfrBridge;
import org.corpseflower.quality.OutputMerger;
import org.corpseflower.quality.OutputMerger.MergeResult;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class CorpseflowerRunner {
  private final CorpseflowerOptions options;

  CorpseflowerRunner(CorpseflowerOptions options) {
    this.options = options;
  }

  void run() throws Exception {
    if (Files.isDirectory(options.input())) {
      runBatch();
      return;
    }

    JarRunSummary summary = processJar(options.input(), options.output(), true);
    printFinalSummary(new Totals().add(summary));
  }

  private void runBatch() throws Exception {
    Files.createDirectories(options.output());

    List<Path> jars;
    try (Stream<Path> walk = Files.list(options.input())) {
      jars = walk
        .filter(path -> path.getFileName().toString().endsWith(".jar"))
        .sorted()
        .toList();
    }

    Totals totals = new Totals();
    for (Path jar : jars) {
      Path jarOutput = options.output().resolve(stripJarSuffix(jar.getFileName().toString()));
      try {
        if (options.verbose()) {
          System.out.println("[Corpseflower] Processing " + jar.getFileName());
        }
        totals.add(processJar(jar, jarOutput, options.verbose()));
      } catch (Exception e) {
        totals.failures++;
        System.err.println("[Corpseflower] ERROR processing " + jar.getFileName() + ": " + e.getMessage());
        if (options.verbose()) {
          e.printStackTrace(System.err);
        }
      }
    }

    printFinalSummary(totals);
  }

  private JarRunSummary processJar(Path inputJar, Path outputDir, boolean printPerJarSummary) throws Exception {
    Result deobfuscation = DeobfuscationStage.maybeRun(inputJar, options.deobfuscate(), options.verbose());
    Path vineflowerDir = options.qualityGate() ? Files.createTempDirectory("corpseflower-vf-") : outputDir;
    Path cfrDir = options.qualityGate() ? Files.createTempDirectory("corpseflower-cfr-") : null;
    MergeResult mergeResult = new MergeResult(0, 0, 0);
    try {
      prepareOutputDirectory(vineflowerDir);
      if (!vineflowerDir.equals(outputDir)) {
        prepareOutputDirectory(outputDir);
      }

      decompileJar(deobfuscation.outputJar(), vineflowerDir, options.fernflowerOptions());
      if (printPerJarSummary) {
        printPhaseBSummary(inputJar, outputDir, deobfuscation);
      }

      if (options.qualityGate()) {
        CfrBridge.Result cfrResult = new CfrBridge().decompile(deobfuscation.outputJar(), cfrDir, options.verbose());
        mergeResult = new OutputMerger().merge(vineflowerDir, cfrDir, outputDir);
        if (printPerJarSummary) {
          printPhaseDSummary(cfrResult, mergeResult);
        }
      } else if (!vineflowerDir.equals(outputDir)) {
        new OutputMerger().merge(vineflowerDir, vineflowerDir, outputDir);
      }

      return new JarRunSummary(
        inputJar,
        countJavaFiles(outputDir),
        deobfuscation.deobfuscated(),
        deobfuscation.stringsDecrypted(),
        deobfuscation.fakeTryCatchRemoved(),
        deobfuscation.opaquePredicatesSimplified(),
        deobfuscation.deadInstructionsRemoved(),
        deobfuscation.exceptionVerifyFixed(),
        deobfuscation.zkmClassesRemoved(),
        mergeResult.cfrSelections(),
        options.qualityGate() ? mergeResult.finalStubMarkers() : countStubMarkers(outputDir)
      );
    } finally {
      deleteRecursively(vineflowerDir, !vineflowerDir.equals(outputDir));
      deleteRecursively(cfrDir, cfrDir != null);
      deobfuscation.cleanup();
    }
  }

  private void decompileJar(Path jar, Path outputDir, Map<String, Object> fernflowerOptions) {
    PrintStreamLogger logger = new PrintStreamLogger(System.out);
    Fernflower fernflower = new Fernflower(new DirectoryResultSaver(outputDir.toFile()), fernflowerOptions, logger);
    try {
      fernflower.addSource(jar.toFile());
      fernflower.decompileContext();
    } finally {
      fernflower.clearContext();
    }
  }

  private void prepareOutputDirectory(Path outputDir) throws IOException {
    if (Files.exists(outputDir)) {
      deleteRecursively(outputDir, false);
    }
    Files.createDirectories(outputDir);
  }

  private void printPhaseBSummary(Path inputJar, Path outputDir, Result result) {
    System.out.println("[Corpseflower] === Phase B ===");
    System.out.println("[Corpseflower] Input: " + inputJar.getFileName());
    System.out.println("[Corpseflower] Output: " + outputDir);
    if (result.deobfuscated()) {
      System.out.println("[Corpseflower] Deobfuscated: yes");
      System.out.println("[Corpseflower] Strings decrypted: " + result.stringsDecrypted());
      System.out.println("[Corpseflower] Fake try-catch removed: " + result.fakeTryCatchRemoved());
      System.out.println("[Corpseflower] Opaque predicates simplified: " + result.opaquePredicatesSimplified());
      System.out.println("[Corpseflower] Dead instructions removed: " + result.deadInstructionsRemoved());
      System.out.println("[Corpseflower] Exception table verify fixes: " + result.exceptionVerifyFixed());
      System.out.println("[Corpseflower] ZKM classes removed: " + result.zkmClassesRemoved());
    } else {
      System.out.println("[Corpseflower] Deobfuscated: no");
    }
  }

  private void printPhaseDSummary(CfrBridge.Result cfrResult, MergeResult merge) {
    System.out.println("[Corpseflower] === Phase D ===");
    System.out.println("[Corpseflower] CFR exceptions: " + cfrResult.exceptionCount());
    System.out.println("[Corpseflower] CFR java files: " + cfrResult.javaFileCount());
    System.out.println("[Corpseflower] Java files compared: " + merge.comparedFiles());
    System.out.println("[Corpseflower] CFR-selected outputs: " + merge.cfrSelections());
    System.out.println("[Corpseflower] Final stub markers: " + merge.finalStubMarkers());
  }

  private void deleteRecursively(Path root, boolean deleteRoot) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }

    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        if (deleteRoot || !path.equals(root)) {
          try {
            Files.deleteIfExists(path);
          } catch (IOException e) {
            throw new RuntimeException("Failed to delete " + path, e);
          }
        }
      });
    }
  }

  private int countJavaFiles(Path outputDir) throws IOException {
    try (Stream<Path> walk = Files.walk(outputDir)) {
      return (int) walk
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith(".java"))
        .count();
    }
  }

  private int countStubMarkers(Path outputDir) throws IOException {
    int total = 0;
    try (Stream<Path> walk = Files.walk(outputDir)) {
      for (Path file : walk.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        total += count(source, "Exception decompiling");
        total += count(source, "$VF: Couldn't be decompiled");
        total += count(source, "$VF: Unable to decompile class");
      }
    }
    return total;
  }

  private int count(String haystack, String needle) {
    int count = 0;
    int index = 0;
    while ((index = haystack.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

  private String stripJarSuffix(String name) {
    return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
  }

  private void printFinalSummary(Totals totals) {
    System.out.println("[Corpseflower] === Results ===");
    System.out.println("[Corpseflower] Classes processed: " + totals.classesProcessed);
    System.out.println("[Corpseflower] Deobfuscated jars: " + totals.deobfuscatedJars +
      " (strings: " + totals.stringsDecrypted +
      ", predicates: " + totals.opaquePredicatesSimplified +
      ", try-catch: " + totals.fakeTryCatchRemoved + ")");
    System.out.println("[Corpseflower] Output Java files: " + totals.outputJavaFiles);
    System.out.println("[Corpseflower] Quality gate decisions: " + totals.cfrSelections + " classes used CFR output");
    System.out.println("[Corpseflower] Final stub markers: " + totals.finalStubMarkers);
    System.out.println("[Corpseflower] Failures: " + totals.failures);
  }

  private record JarRunSummary(
    Path inputJar,
    int outputJavaFiles,
    boolean deobfuscated,
    int stringsDecrypted,
    int fakeTryCatchRemoved,
    int opaquePredicatesSimplified,
    int deadInstructionsRemoved,
    int exceptionVerifyFixed,
    int zkmClassesRemoved,
    int cfrSelections,
    int finalStubMarkers
  ) {
  }

  private static final class Totals {
    int classesProcessed;
    int deobfuscatedJars;
    int stringsDecrypted;
    int fakeTryCatchRemoved;
    int opaquePredicatesSimplified;
    int outputJavaFiles;
    int cfrSelections;
    int finalStubMarkers;
    int failures;

    Totals add(JarRunSummary summary) {
      classesProcessed += summary.outputJavaFiles();
      outputJavaFiles += summary.outputJavaFiles();
      if (summary.deobfuscated()) {
        deobfuscatedJars++;
      }
      stringsDecrypted += summary.stringsDecrypted();
      fakeTryCatchRemoved += summary.fakeTryCatchRemoved();
      opaquePredicatesSimplified += summary.opaquePredicatesSimplified();
      cfrSelections += summary.cfrSelections();
      finalStubMarkers += summary.finalStubMarkers();
      return this;
    }
  }
}

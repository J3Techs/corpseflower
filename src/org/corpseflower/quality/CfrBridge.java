package org.corpseflower.quality;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.OutputSinkFactory.Sink;
import org.benf.cfr.reader.api.OutputSinkFactory.SinkClass;
import org.benf.cfr.reader.api.OutputSinkFactory.SinkType;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CfrBridge {
  private static final String IN_MEMORY_JAR = "corpseflower-input.jar";

  public Result decompile(Map<String, byte[]> classBytes, Path outputDir, boolean verbose) throws IOException {
    Files.createDirectories(outputDir);
    if (classBytes.isEmpty()) {
      System.err.println("[Corpseflower] WARN: No class bytes available for CFR benchmark");
      return new Result(0, 0);
    }

    List<String> exceptions = new ArrayList<>();

    Map<String, String> options = new HashMap<>();
    options.put("forcetopsort", "true");
    options.put("silent", verbose ? "false" : "true");

    Map<String, byte[]> classBytesByPath = new HashMap<>();
    for (Map.Entry<String, byte[]> entry : classBytes.entrySet()) {
      classBytesByPath.put(entry.getKey() + ".class", entry.getValue());
    }

    ClassFileSource source = new ClassFileSource() {
      @Override
      public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {
      }

      @Override
      public Collection<String> addJar(String jarPath) {
        return classBytesByPath.keySet();
      }

      @Override
      public String getPossiblyRenamedPath(String path) {
        return path;
      }

      @Override
      public Pair<byte[], String> getClassFileContent(String path) throws IOException {
        byte[] bytes = classBytesByPath.get(path);
        if (bytes == null) {
          throw new IOException("Missing in-memory class for " + path);
        }
        return Pair.make(bytes, path);
      }
    };

    OutputSinkFactory sinkFactory = new OutputSinkFactory() {
      @Override
      public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
        return switch (sinkType) {
          case JAVA -> available.contains(SinkClass.DECOMPILED)
            ? List.of(SinkClass.DECOMPILED)
            : List.of();
          case EXCEPTION -> available.contains(SinkClass.EXCEPTION_MESSAGE)
            ? List.of(SinkClass.EXCEPTION_MESSAGE)
            : List.of();
          case PROGRESS, SUMMARY -> available.contains(SinkClass.STRING)
            ? List.of(SinkClass.STRING)
            : List.of();
          default -> List.of();
        };
      }

      @Override
      @SuppressWarnings("unchecked")
      public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
        return sinkable -> {
          try {
            switch (sinkType) {
              case JAVA -> {
                SinkReturns.Decompiled decompiled = (SinkReturns.Decompiled) sinkable;
                writeJava(outputDir, decompiled.getPackageName(), decompiled.getClassName(), decompiled.getJava());
              }
              case EXCEPTION -> {
                SinkReturns.ExceptionMessage message = (SinkReturns.ExceptionMessage) sinkable;
                exceptions.add(message.getPath() + ": " + message.getMessage());
                if (verbose) {
                  System.out.println("[CFR] " + message.getPath() + ": " + message.getMessage());
                }
              }
              case PROGRESS, SUMMARY -> {
                if (verbose) {
                  System.out.println("[CFR] " + sinkable);
                }
              }
              default -> {
              }
            }
          } catch (IOException e) {
            throw new RuntimeException("Failed to write CFR output", e);
          }
        };
      }
    };

    CfrDriver driver = new CfrDriver.Builder()
      .withOptions(options)
      .withClassFileSource(source)
      .withOutputSink(sinkFactory)
      .build();
    driver.analyse(List.of(IN_MEMORY_JAR));

    int javaFileCount = countJavaFiles(outputDir);
    if (javaFileCount == 0) {
      System.err.println("[Corpseflower] WARN: CFR API produced no Java output for in-memory benchmark input");
    }

    return new Result(exceptions.size(), javaFileCount);
  }

  private void writeJava(Path outputDir, String packageName, String className, String javaSource) throws IOException {
    Path targetDir = outputDir;
    if (packageName != null && !packageName.isBlank()) {
      targetDir = outputDir.resolve(packageName.replace('.', '/'));
    }
    Files.createDirectories(targetDir);

    String fileName = className.endsWith(".java") ? className : className + ".java";
    Files.writeString(targetDir.resolve(fileName), javaSource);
  }

  private int countJavaFiles(Path outputDir) throws IOException {
    try (var walk = Files.walk(outputDir)) {
      return (int) walk
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith(".java"))
        .count();
    }
  }

  public record Result(int exceptionCount, int javaFileCount) {
  }
}

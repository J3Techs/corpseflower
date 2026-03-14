package org.corpseflower.quality;

import org.corpseflower.quality.QualityScorer.Score;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

public final class OutputMerger {
  private static final String CFR_HEADER = "// Decompiled by CFR 0.152 (Corpseflower quality gate)\n\n";

  private final QualityScorer scorer = new QualityScorer();

  public MergeResult merge(Path vineflowerDir, Path cfrDir, Path outputDir) throws IOException {
    copyTree(vineflowerDir, outputDir);

    Set<Path> compared = new HashSet<>();
    compared.addAll(listJavaFiles(vineflowerDir));
    compared.addAll(listJavaFiles(cfrDir));

    int cfrSelections = 0;
    int finalStubMarkers = 0;

    for (Path relative : compared) {
      Path vineflowerFile = vineflowerDir.resolve(relative);
      Path cfrFile = cfrDir.resolve(relative);
      String vineflowerSource = Files.exists(vineflowerFile) ? Files.readString(vineflowerFile) : null;
      String cfrSource = Files.exists(cfrFile) ? Files.readString(cfrFile) : null;

      boolean useCfr = shouldUseCfr(vineflowerSource, cfrSource);
      String chosen = useCfr ? withHeader(cfrSource) : vineflowerSource;
      if (chosen == null) {
        chosen = cfrSource;
      }

      if (chosen != null) {
        Path target = outputDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, chosen);
        finalStubMarkers += scorer.score(chosen).stubCount();
      }
      if (useCfr && cfrSource != null) {
        cfrSelections++;
      }
    }

    return new MergeResult(compared.size(), cfrSelections, finalStubMarkers);
  }

  private boolean shouldUseCfr(String vineflowerSource, String cfrSource) {
    if (vineflowerSource == null) {
      return cfrSource != null;
    }
    if (cfrSource == null) {
      return false;
    }

    Score vineflowerScore = scorer.score(vineflowerSource);
    Score cfrScore = scorer.score(cfrSource);

    if (vineflowerScore.stubCount() > 0 && cfrScore.stubCount() == 0) {
      return true;
    }
    if (vineflowerScore.stubCount() == 0 && cfrScore.stubCount() > 0) {
      return false;
    }
    if (cfrScore.stubCount() < vineflowerScore.stubCount()) {
      return true;
    }
    if (vineflowerScore.stubCount() == 0 && vineflowerScore.total() >= 60 && vineflowerScore.total() >= cfrScore.total()) {
      return false;
    }
    if (cfrScore.total() > vineflowerScore.total() + 5) {
      return true;
    }
    return false;
  }

  private String withHeader(String source) {
    if (source == null || source.startsWith(CFR_HEADER)) {
      return source;
    }
    return CFR_HEADER + source;
  }

  private void copyTree(Path source, Path target) throws IOException {
    if (!Files.exists(source)) {
      return;
    }

    try (Stream<Path> walk = Files.walk(source)) {
      walk.forEach(path -> {
        try {
          Path relative = source.relativize(path);
          Path destination = target.resolve(relative);
          if (Files.isDirectory(path)) {
            Files.createDirectories(destination);
          } else {
            Files.createDirectories(destination.getParent());
            Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          }
        } catch (IOException e) {
          throw new RuntimeException("Failed to copy " + path, e);
        }
      });
    }
  }

  private Set<Path> listJavaFiles(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return Set.of();
    }

    Set<Path> files = new HashSet<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith(".java"))
        .map(root::relativize)
        .forEach(files::add);
    }
    return files;
  }

  public record MergeResult(int comparedFiles, int cfrSelections, int finalStubMarkers) {
  }
}

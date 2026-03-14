package org.corpseflower.quality;

public final class QualityScorer {
  public Score score(String source) {
    if (source == null || source.isBlank()) {
      return new Score(0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE);
    }

    int stubCount = count(source, "Exception decompiling")
      + count(source, "$VF: Couldn't be decompiled")
      + count(source, "$VF: Unable to decompile class");
    int completeness = stubCount == 0 ? 100 : 0;
    int artifactCount = count(source, "while (true)")
      + count(source, "% 2")
      + count(source, "goto ")
      + count(source, "label");
    int maxDepth = nestingDepth(source);
    int statementCount = count(source, ";");
    int readabilityPenalty = maxDepth * 4 + (statementCount / 50);
    int total = completeness * 100 - artifactCount * 10 - readabilityPenalty - stubCount * 200;
    return new Score(completeness, artifactCount, readabilityPenalty, stubCount, total);
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

  private int nestingDepth(String source) {
    int maxDepth = 0;
    int depth = 0;
    for (int i = 0; i < source.length(); i++) {
      char ch = source.charAt(i);
      if (ch == '{') {
        depth++;
        maxDepth = Math.max(maxDepth, depth);
      } else if (ch == '}') {
        depth = Math.max(0, depth - 1);
      }
    }
    return maxDepth;
  }

  public record Score(int completeness, int artifacts, int readabilityPenalty, int stubCount, int total) {
  }
}

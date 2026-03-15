package org.corpseflower.quality;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QualityScorer {
  private static final Pattern RAW_LABEL_PATTERN = Pattern.compile("\\blbl\\d+:");
  private static final String[] FAILURE_MARKERS = {
    "// $VF: Couldn't be decompiled",
    "$VF: Unable to decompile class",
    "Exception decompiling",
    "/* Exception decompiling",
    "This method has failed to decompile"
  };

  public Score score(String source) {
    if (source == null || source.isBlank()) {
      return new Score(0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE);
    }

    int stubCount = 0;
    for (String marker : FAILURE_MARKERS) {
      stubCount += count(source, marker);
    }
    if (stubCount > 0) {
      return new Score(0, 0, 0, stubCount, 0);
    }

    int completeness = Math.max(0, 100 - emptyBodyCount(source) * 5 - rawDumpCount(source) * 20);
    int artifactCount = rawDumpCount(source);
    int maxDepth = nestingDepth(source);
    int statementCount = count(source, ";");
    int readabilityPenalty = maxDepth * 4 + (statementCount / 50);
    int total = completeness * 100 - artifactCount * 25 - readabilityPenalty;
    return new Score(completeness, artifactCount, readabilityPenalty, stubCount, total);
  }

  public boolean hasFailureMarkers(String source) {
    if (source == null || source.isBlank()) {
      return true;
    }

    for (String marker : FAILURE_MARKERS) {
      if (source.contains(marker)) {
        return true;
      }
    }

    return false;
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

  private int emptyBodyCount(String source) {
    return count(source, "{\n    }") + count(source, "{\r\n    }") + count(source, "{ }");
  }

  private int rawDumpCount(String source) {
    return countPattern(source, RAW_LABEL_PATTERN) + count(source, "bytecode offset") + count(source, "goto ");
  }

  private int countPattern(String source, Pattern pattern) {
    int count = 0;
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  public record Score(int completeness, int artifacts, int readabilityPenalty, int stubCount, int total) {
  }
}

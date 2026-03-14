package org.corpseflower.quality;

import org.corpseflower.quality.QualityScorer.Score;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class OutputMerger {
  private static final String CFR_HEADER = "// Decompiled by CFR 0.152 (Corpseflower quality gate)\n\n";
  private static final String CFR_METHOD_HEADER = "// Method decompiled by CFR 0.152 (Corpseflower quality gate)\n";

  private final QualityScorer scorer = new QualityScorer();

  public MergeResult merge(Path corpseflowerDir, Path cfrDir, Path outputDir) throws IOException {
    copyTree(corpseflowerDir, outputDir);

    Set<Path> compared = new HashSet<>();
    compared.addAll(listJavaFiles(corpseflowerDir));
    compared.addAll(listJavaFiles(cfrDir));

    int cfrSelections = 0;
    int finalStubMarkers = 0;

    for (Path relative : compared) {
      Path corpseflowerFile = corpseflowerDir.resolve(relative);
      Path cfrFile = cfrDir.resolve(relative);
      String corpseflowerSource = Files.exists(corpseflowerFile) ? Files.readString(corpseflowerFile) : null;
      String cfrSource = Files.exists(cfrFile) ? Files.readString(cfrFile) : null;

      MergeDecision decision = mergeSource(corpseflowerSource, cfrSource);

      if (decision.source() != null) {
        Path target = outputDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, decision.source());
        finalStubMarkers += scorer.score(decision.source()).stubCount();
      }

      if (decision.usedCfr() && cfrSource != null) {
        cfrSelections++;
      }
    }

    return new MergeResult(compared.size(), cfrSelections, finalStubMarkers);
  }

  private MergeDecision mergeSource(String corpseflowerSource, String cfrSource) {
    if (corpseflowerSource == null) {
      return new MergeDecision(withHeader(cfrSource), cfrSource != null);
    }
    if (cfrSource == null) {
      return new MergeDecision(corpseflowerSource, false);
    }

    Score corpseflowerScore = scorer.score(corpseflowerSource);
    Score cfrScore = scorer.score(cfrSource);
    boolean corpseflowerClean = isClean(corpseflowerScore);
    boolean cfrClean = isClean(cfrScore);

    if (corpseflowerClean) {
      if (!cfrClean) {
        return new MergeDecision(corpseflowerSource, false);
      }
      if (corpseflowerScore.artifacts() == 0 && corpseflowerScore.completeness() == 100) {
        return new MergeDecision(corpseflowerSource, false);
      }
      if (corpseflowerScore.total() >= cfrScore.total()) {
        return new MergeDecision(corpseflowerSource, false);
      }
    }

    String spliced = spliceMembers(corpseflowerSource, cfrSource);
    Score splicedScore = scorer.score(spliced);
    boolean splicedUsesCfr = spliced.contains(CFR_HEADER) || spliced.contains(CFR_METHOD_HEADER);

    if (!corpseflowerClean && isClean(splicedScore) &&
      (splicedScore.stubCount() < corpseflowerScore.stubCount() || splicedScore.total() >= cfrScore.total())) {
      return new MergeDecision(spliced, splicedUsesCfr);
    }

    if (corpseflowerClean && !spliced.equals(corpseflowerSource) && isClean(splicedScore) && splicedScore.total() > corpseflowerScore.total() + 25) {
      return new MergeDecision(spliced, splicedUsesCfr);
    }

    if (!corpseflowerClean && cfrClean) {
      return new MergeDecision(withHeader(cfrSource), true);
    }
    if (splicedScore.stubCount() < Math.min(corpseflowerScore.stubCount(), cfrScore.stubCount())) {
      return new MergeDecision(spliced, splicedUsesCfr);
    }
    if (corpseflowerScore.stubCount() < cfrScore.stubCount()) {
      return new MergeDecision(corpseflowerSource, false);
    }
    if (cfrScore.stubCount() < corpseflowerScore.stubCount()) {
      return new MergeDecision(withHeader(cfrSource), true);
    }
    if (corpseflowerScore.total() >= cfrScore.total()) {
      return new MergeDecision(corpseflowerSource, false);
    }
    return new MergeDecision(withHeader(cfrSource), true);
  }

  private String spliceMembers(String corpseflowerSource, String cfrSource) {
    SourceModel corpseflower = SourceModel.parse(corpseflowerSource);
    SourceModel cfr = SourceModel.parse(cfrSource);

    if (corpseflower == null || cfr == null) {
      return corpseflowerSource;
    }

    boolean corpseflowerHasFailures = scorer.hasFailureMarkers(corpseflowerSource);
    Map<String, Member> cfrMembers = new LinkedHashMap<>();
    for (Member member : cfr.members()) {
      cfrMembers.put(member.key(), member);
    }

    List<String> mergedMembers = new ArrayList<>();
    boolean usedCfr = false;
    for (Member member : corpseflower.members()) {
      Member cfrMember = cfrMembers.remove(member.key());
      if (cfrMember == null) {
        mergedMembers.add(member.text());
        continue;
      }

      String chosen = chooseMember(member, cfrMember);
      if (!chosen.equals(member.text())) {
        usedCfr = true;
      }
      mergedMembers.add(chosen);
    }

    if (corpseflowerHasFailures) {
      for (Member member : cfrMembers.values()) {
        if (scorer.hasFailureMarkers(member.text())) {
          continue;
        }
        mergedMembers.add(withMethodHeader(member.text()));
        usedCfr = true;
      }
    }

    if (!usedCfr) {
      return corpseflowerSource;
    }

    return corpseflower.header() + String.join("", mergedMembers) + corpseflower.footer();
  }

  private String chooseMember(Member corpseflowerMember, Member cfrMember) {
    Score corpseflowerScore = scorer.score(corpseflowerMember.text());
    Score cfrScore = scorer.score(cfrMember.text());

    if (corpseflowerScore.stubCount() == 0 && cfrScore.stubCount() > 0) {
      return corpseflowerMember.text();
    }
    if (cfrScore.stubCount() == 0 && corpseflowerScore.stubCount() > 0) {
      return withMethodHeader(cfrMember.text());
    }
    if (cfrScore.stubCount() < corpseflowerScore.stubCount()) {
      return withMethodHeader(cfrMember.text());
    }
    if (corpseflowerScore.stubCount() < cfrScore.stubCount()) {
      return corpseflowerMember.text();
    }
    if (cfrScore.total() > corpseflowerScore.total() + 5) {
      return withMethodHeader(cfrMember.text());
    }
    return corpseflowerMember.text();
  }

  private String withHeader(String source) {
    if (source == null || source.startsWith(CFR_HEADER)) {
      return source;
    }
    return CFR_HEADER + source;
  }

  private boolean isClean(Score score) {
    return score.stubCount() == 0;
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

  private String withMethodHeader(String source) {
    if (source == null || source.startsWith(CFR_METHOD_HEADER)) {
      return source;
    }
    return CFR_METHOD_HEADER + source;
  }

  private record MergeDecision(String source, boolean usedCfr) {
  }

  private record Member(String key, String text) {
  }

  private record SourceModel(String header, List<Member> members, String footer) {
    static SourceModel parse(String source) {
      if (source == null || source.isBlank()) {
        return null;
      }

      int bodyStart = findTypeBodyStart(source);
      if (bodyStart < 0) {
        return null;
      }

      int bodyEnd = findMatchingBrace(source, bodyStart);
      if (bodyEnd < 0) {
        return null;
      }

      List<Member> members = new ArrayList<>();
      int depth = 1;
      int memberStart = -1;
      ScanState state = new ScanState();
      int blockOrdinal = 0;

      for (int i = bodyStart + 1; i < bodyEnd; i++) {
        char ch = source.charAt(i);
        char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
        state.advance(ch, next);
        if (state.inTrivia()) {
          continue;
        }

        if (depth == 1 && memberStart < 0 && !Character.isWhitespace(ch)) {
          memberStart = i;
        }

        if (ch == '{' && state.parenDepth == 0) {
          depth++;
          state.parenDepth = Math.max(0, state.parenDepth);
          continue;
        }

        if (ch == '}' && state.parenDepth == 0) {
          depth--;
          if (depth == 1 && memberStart >= 0) {
            int end = consumeTrailingWhitespace(source, i + 1, bodyEnd);
            members.add(new Member(safeBuildKey(source.substring(memberStart, end), blockOrdinal++), source.substring(memberStart, end)));
            memberStart = -1;
          }
          continue;
        }

        if (depth == 1 && ch == ';' && memberStart >= 0) {
          int end = consumeTrailingWhitespace(source, i + 1, bodyEnd);
          members.add(new Member(safeBuildKey(source.substring(memberStart, end), blockOrdinal++), source.substring(memberStart, end)));
          memberStart = -1;
        }
      }

      String header = source.substring(0, bodyStart + 1);
      String footer = source.substring(bodyEnd);
      return new SourceModel(header, List.copyOf(members), footer);
    }

    private static int findTypeBodyStart(String source) {
      ScanState state = new ScanState();
      for (int i = 0; i < source.length(); i++) {
        char ch = source.charAt(i);
        char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
        state.advance(ch, next);
        if (!state.inTrivia() && ch == '{') {
          return i;
        }
      }
      return -1;
    }

    private static int findMatchingBrace(String source, int start) {
      ScanState state = new ScanState();
      int depth = 0;
      for (int i = start; i < source.length(); i++) {
        char ch = source.charAt(i);
        char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
        state.advance(ch, next);
        if (state.inTrivia()) {
          continue;
        }
        if (ch == '{' && state.parenDepth == 0) {
          depth++;
        } else if (ch == '}' && state.parenDepth == 0) {
          depth--;
          if (depth == 0) {
            return i;
          }
        }
      }
      return -1;
    }

    private static int consumeTrailingWhitespace(String source, int start, int limit) {
      int idx = start;
      while (idx < limit && Character.isWhitespace(source.charAt(idx))) {
        idx++;
      }
      return idx;
    }

    private static String safeBuildKey(String member, int blockOrdinal) {
      try {
        return buildKey(member, blockOrdinal);
      } catch (RuntimeException ex) {
        return fallbackKey(member, blockOrdinal);
      }
    }

    private static String buildKey(String member, int blockOrdinal) {
      String signature = stripLeadingAnnotations(stripComments(member).trim());
      if (signature.isBlank()) {
        return fallbackKey(member, blockOrdinal);
      }

      String prefix = prefixBeforeBody(signature);
      if (prefix.isBlank()) {
        return fallbackKey(member, blockOrdinal);
      }
      if (prefix.matches("(?s).*\\b(class|interface|enum|record|@interface)\\s+[A-Za-z_$][\\w$]*.*")) {
        return "type:" + prefix.replaceAll("(?s).*\\b(class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*).*", "$2");
      }
      if (prefix.startsWith("static {") || prefix.equals("static")) {
        return "block:static:" + blockOrdinal;
      }
      if (prefix.startsWith("{")) {
        return "block:init:" + blockOrdinal;
      }

      int paren = prefix.indexOf('(');
      if (paren >= 0) {
        String beforeParen = prefix.substring(0, paren).trim();
        String name = beforeParen.replaceAll("(?s).*?([A-Za-z_$][\\w$]*)\\s*$", "$1");
        int closeParen = findClosingParen(prefix, paren);
        if (closeParen <= paren) {
          return fallbackKey(member, blockOrdinal);
        }
        String params = normalizeParameters(prefix.substring(paren + 1, closeParen));
        if (name.equals(beforeParen) && !beforeParen.matches("(?s).*\\b[A-Za-z_$][\\w$]*\\s*$")) {
          return fallbackKey(member, blockOrdinal);
        }
        return "callable:" + name + "(" + params + ")";
      }

      String field = prefix;
      int eq = field.indexOf('=');
      if (eq >= 0) {
        field = field.substring(0, eq).trim();
      }
      field = field.replaceAll("(?s).*?([A-Za-z_$][\\w$]*)\\s*(\\[\\])?\\s*$", "$1$2");
      if (field.isBlank() || field.equals(prefix)) {
        return fallbackKey(member, blockOrdinal);
      }
      return "field:" + field + ":" + blockOrdinal;
    }

    private static String stripLeadingAnnotations(String signature) {
      String stripped = signature;
      while (stripped.startsWith("@")) {
        int newline = stripped.indexOf('\n');
        if (newline < 0) {
          return signature;
        }
        stripped = stripped.substring(newline + 1).trim();
      }
      return stripped;
    }

    private static int findClosingParen(String text, int openParen) {
      int depth = 0;
      for (int i = openParen; i < text.length(); i++) {
        char ch = text.charAt(i);
        if (ch == '(') {
          depth++;
        } else if (ch == ')') {
          depth--;
          if (depth == 0) {
            return i;
          }
        }
      }
      return -1;
    }

    private static String fallbackKey(String member, int blockOrdinal) {
      String normalized = normalizeSignature(prefixBeforeBody(stripComments(member)).trim());
      if (normalized.isBlank()) {
        normalized = normalizeSignature(stripComments(member).trim());
      }
      return "raw:" + blockOrdinal + ":" + Integer.toUnsignedString(normalized.hashCode());
    }

    private static String normalizeSignature(String signature) {
      return signature.replaceAll("\\s+", " ").trim();
    }

    private static String prefixBeforeBody(String member) {
      ScanState state = new ScanState();
      for (int i = 0; i < member.length(); i++) {
        char ch = member.charAt(i);
        char next = i + 1 < member.length() ? member.charAt(i + 1) : '\0';
        state.advance(ch, next);
        if (state.inTrivia()) {
          continue;
        }
        if ((ch == '{' || ch == ';') && state.parenDepth == 0) {
          return member.substring(0, i).trim();
        }
      }
      return member.trim();
    }

    private static String normalizeParameters(String parameters) {
      List<String> normalized = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      int genericDepth = 0;
      for (int i = 0; i < parameters.length(); i++) {
        char ch = parameters.charAt(i);
        if (ch == '<') genericDepth++;
        if (ch == '>') genericDepth = Math.max(0, genericDepth - 1);
        if (ch == ',' && genericDepth == 0) {
          addParameter(normalized, current.toString());
          current.setLength(0);
          continue;
        }
        current.append(ch);
      }
      addParameter(normalized, current.toString());
      return String.join(",", normalized);
    }

    private static void addParameter(List<String> normalized, String parameter) {
      String cleaned = stripComments(parameter)
        .replaceAll("(?m)^\\s*@[^\\n]+\\n?", "")
        .replace("final ", "")
        .trim();
      if (cleaned.isEmpty()) {
        return;
      }

      int nameEnd = cleaned.length() - 1;
      while (nameEnd >= 0 && Character.isWhitespace(cleaned.charAt(nameEnd))) {
        nameEnd--;
      }
      while (nameEnd >= 0 && Character.isJavaIdentifierPart(cleaned.charAt(nameEnd))) {
        nameEnd--;
      }
      cleaned = cleaned.substring(0, Math.max(0, nameEnd + 1)).trim();
      normalized.add(cleaned.replaceAll("\\s+", " ").replace(" ,", ","));
    }

    private static String stripComments(String text) {
      return text
        .replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//.*$", " ");
    }
  }

  private static final class ScanState {
    private boolean inLineComment;
    private boolean inBlockComment;
    private boolean inString;
    private boolean inChar;
    private boolean escaped;
    private int parenDepth;

    void advance(char ch, char next) {
      if (inLineComment) {
        if (ch == '\n') {
          inLineComment = false;
        }
        return;
      }
      if (inBlockComment) {
        if (ch == '*' && next == '/') {
          inBlockComment = false;
        }
        return;
      }
      if (inString) {
        if (ch == '"' && !escaped) {
          inString = false;
        }
        escaped = ch == '\\' && !escaped;
        return;
      }
      if (inChar) {
        if (ch == '\'' && !escaped) {
          inChar = false;
        }
        escaped = ch == '\\' && !escaped;
        return;
      }
      escaped = false;
      if (ch == '/' && next == '/') {
        inLineComment = true;
        return;
      }
      if (ch == '/' && next == '*') {
        inBlockComment = true;
        return;
      }
      if (ch == '"') {
        inString = true;
        return;
      }
      if (ch == '\'') {
        inChar = true;
        return;
      }
      if (ch == '(') {
        parenDepth++;
      } else if (ch == ')') {
        parenDepth = Math.max(0, parenDepth - 1);
      }
    }

    boolean inTrivia() {
      return inLineComment || inBlockComment || inString || inChar;
    }
  }

  public record MergeResult(int comparedFiles, int cfrSelections, int finalStubMarkers) {
  }
}

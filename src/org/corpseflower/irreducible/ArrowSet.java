package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ArrowSet {
  public record Arrow(int from, int to, BasicBlock fromBlock, BasicBlock toBlock, boolean exceptionEdge) {
    public boolean crosses(int position) {
      int lo = Math.min(from, to);
      int hi = Math.max(from, to);
      return lo < position && position < hi;
    }

    public boolean interleaves(Arrow other) {
      int thisLo = Math.min(from, to);
      int thisHi = Math.max(from, to);
      int otherLo = Math.min(other.from, other.to);
      int otherHi = Math.max(other.from, other.to);

      return (thisLo < otherLo && otherLo < thisHi && thisHi < otherHi) ||
             (otherLo < thisLo && thisLo < otherHi && otherHi < thisHi);
    }
  }

  private final List<Arrow> arrows;
  private final Map<Integer, BasicBlock> positionToBlock;
  private final Map<BasicBlock, Integer> blockToPosition;

  private ArrowSet(List<Arrow> arrows, Map<Integer, BasicBlock> positionToBlock, Map<BasicBlock, Integer> blockToPosition) {
    this.arrows = arrows;
    this.positionToBlock = positionToBlock;
    this.blockToPosition = blockToPosition;
  }

  public static ArrowSet fromCFG(ControlFlowGraph graph) {
    Map<Integer, BasicBlock> positionToBlock = new LinkedHashMap<>();
    Map<BasicBlock, Integer> blockToPosition = new LinkedHashMap<>();

    int index = 0;
    for (BasicBlock block : graph.getBlocks()) {
      positionToBlock.put(index, block);
      blockToPosition.put(block, index);
      index++;
    }

    List<Arrow> arrows = new ArrayList<>();
    for (BasicBlock block : graph.getBlocks()) {
      Integer from = blockToPosition.get(block);
      if (from == null) {
        continue;
      }

      for (BasicBlock succ : block.getSuccs()) {
        Integer to = blockToPosition.get(succ);
        if (to != null) {
          arrows.add(new Arrow(from, to, block, succ, false));
        }
      }

      for (BasicBlock succ : block.getSuccExceptions()) {
        Integer to = blockToPosition.get(succ);
        if (to != null) {
          arrows.add(new Arrow(from, to, block, succ, true));
        }
      }
    }

    return new ArrowSet(arrows, positionToBlock, blockToPosition);
  }

  public List<int[]> findCollisions() {
    List<int[]> collisions = new ArrayList<>();

    for (int i = 0; i < arrows.size(); i++) {
      Arrow left = arrows.get(i);
      if (left.exceptionEdge()) {
        continue;
      }

      for (int j = i + 1; j < arrows.size(); j++) {
        Arrow right = arrows.get(j);
        if (right.exceptionEdge()) {
          continue;
        }

        if (left.interleaves(right)) {
          collisions.add(new int[] {i, j});
        }
      }
    }

    return collisions;
  }

  public List<Integer> findSplitGaps(int left, int right) {
    List<Integer> gaps = new ArrayList<>();

    for (int pos = left + 1; pos < right; pos++) {
      boolean crossed = false;
      for (Arrow arrow : arrows) {
        if (arrow.crosses(pos)) {
          crossed = true;
          break;
        }
      }

      if (!crossed) {
        gaps.add(pos);
      }
    }

    return gaps;
  }

  public List<Arrow> getArrows() {
    return arrows;
  }

  public BasicBlock getBlock(int position) {
    return positionToBlock.get(position);
  }

  public int getPosition(BasicBlock block) {
    return blockToPosition.getOrDefault(block, -1);
  }
}

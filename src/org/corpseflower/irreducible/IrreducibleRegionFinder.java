package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.GenericDominatorEngine;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.IGraph;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.IGraphNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IrreducibleRegionFinder {
  public record IrreducibleRegion(Set<BasicBlock> blocks, List<BasicBlock> entryPoints, Set<BasicBlock> exitPoints) {
  }

  private IrreducibleRegionFinder() {
  }

  public static List<IrreducibleRegion> findRegions(ControlFlowGraph graph) {
    Map<String, IrreducibleRegion> regions = new HashMap<>();

    collectRegions(graph, findStronglyConnectedComponents(graph.getBlocks()), regions);
    collectDominatorRegions(graph, regions);
    collectArrowCollisionRegions(graph, regions);

    List<IrreducibleRegion> ordered = new ArrayList<>(regions.values());
    ordered.sort(
      Comparator.comparingInt((IrreducibleRegion region) -> region.blocks().size())
                .thenComparingInt(region -> region.entryPoints().size())
                .thenComparingInt(region -> lowestBlockId(region.blocks()))
    );
    return ordered;
  }

  private static void collectRegions(ControlFlowGraph graph,
                                     List<Set<BasicBlock>> components,
                                     Map<String, IrreducibleRegion> regions) {
    for (Set<BasicBlock> component : components) {
      if (!isLoopComponent(component)) {
        continue;
      }
      if (containsSyntheticScaffold(component)) {
        continue;
      }

      List<BasicBlock> entries = findEntryPoints(graph, component);
      if (entries.size() < 2) {
        continue;
      }

      IrreducibleRegion region = new IrreducibleRegion(component, entries, findExitPoints(component));
      regions.putIfAbsent(regionKey(component), region);
    }
  }

  private static void collectArrowCollisionRegions(ControlFlowGraph graph, Map<String, IrreducibleRegion> regions) {
    ArrowSet arrowSet = ArrowSet.fromCFG(graph);

    for (int[] collision : arrowSet.findCollisions()) {
      ArrowSet.Arrow first = arrowSet.getArrows().get(collision[0]);
      ArrowSet.Arrow second = arrowSet.getArrows().get(collision[1]);

      int left = Math.min(Math.min(first.from(), first.to()), Math.min(second.from(), second.to()));
      int right = Math.max(Math.max(first.from(), first.to()), Math.max(second.from(), second.to()));

      Set<BasicBlock> window = new LinkedHashSet<>();
      for (int position = left; position <= right; position++) {
        BasicBlock block = arrowSet.getBlock(position);
        if (block != null) {
          window.add(block);
        }
      }

      if (window.size() < 3) {
        continue;
      }
      if (containsSyntheticScaffold(window)) {
        continue;
      }

      List<BasicBlock> windowEntries = findEntryPoints(graph, window);
      if (windowEntries.size() >= 2) {
        IrreducibleRegion windowRegion = new IrreducibleRegion(window, windowEntries, findExitPoints(window));
        regions.putIfAbsent(regionKey(window), windowRegion);
      }

      collectRegions(graph, findStronglyConnectedComponents(window), regions);
    }
  }

  private static void collectDominatorRegions(ControlFlowGraph graph, Map<String, IrreducibleRegion> regions) {
    if (graph.getFirst() == null) {
      return;
    }

    GenericDominatorEngine dominators = new GenericDominatorEngine(new IGraph() {
      @Override
      public List<? extends IGraphNode> getReversePostOrderList() {
        return graph.getReversePostOrder();
      }

      @Override
      public Set<? extends IGraphNode> getRoots() {
        return Set.of(graph.getFirst());
      }
    });
    dominators.initialize();

    Map<BasicBlock, Integer> rpoIndex = new HashMap<>();
    List<BasicBlock> reversePostOrder = graph.getReversePostOrder();
    for (int i = 0; i < reversePostOrder.size(); i++) {
      rpoIndex.put(reversePostOrder.get(i), i);
    }

    ArrowSet arrowSet = ArrowSet.fromCFG(graph);
    for (BasicBlock source : graph.getBlocks()) {
      Integer sourceRpo = rpoIndex.get(source);
      if (sourceRpo == null) {
        continue;
      }

      for (BasicBlock destination : source.getSuccs()) {
        Integer destinationRpo = rpoIndex.get(destination);
        if (destinationRpo == null || destinationRpo > sourceRpo) {
          continue;
        }

        if (dominators.isDominator(source, destination)) {
          continue;
        }

        int left = Math.min(arrowSet.getPosition(source), arrowSet.getPosition(destination));
        int right = Math.max(arrowSet.getPosition(source), arrowSet.getPosition(destination));
        if (left < 0 || right < 0 || right - left < 2) {
          continue;
        }

        Set<BasicBlock> window = new LinkedHashSet<>();
        for (int position = left; position <= right; position++) {
          BasicBlock block = arrowSet.getBlock(position);
          if (block != null) {
            window.add(block);
          }
        }

        if (window.size() < 3) {
          continue;
        }

        collectRegions(graph, findStronglyConnectedComponents(window), regions);
      }
    }
  }

  private static List<Set<BasicBlock>> findStronglyConnectedComponents(Iterable<BasicBlock> blocks) {
    Map<BasicBlock, Integer> index = new HashMap<>();
    Map<BasicBlock, Integer> lowlink = new HashMap<>();
    Deque<BasicBlock> stack = new ArrayDeque<>();
    Set<BasicBlock> onStack = new LinkedHashSet<>();
    List<Set<BasicBlock>> components = new ArrayList<>();
    int[] counter = {0};
    Set<BasicBlock> scope = new LinkedHashSet<>();

    for (BasicBlock block : blocks) {
      scope.add(block);
    }

    for (BasicBlock block : scope) {
      if (!index.containsKey(block)) {
        strongConnect(block, scope, index, lowlink, stack, onStack, components, counter);
      }
    }

    return components;
  }

  private static void strongConnect(BasicBlock block,
                                    Set<BasicBlock> scope,
                                    Map<BasicBlock, Integer> index,
                                    Map<BasicBlock, Integer> lowlink,
                                    Deque<BasicBlock> stack,
                                    Set<BasicBlock> onStack,
                                    List<Set<BasicBlock>> components,
                                    int[] counter) {
    index.put(block, counter[0]);
    lowlink.put(block, counter[0]);
    counter[0]++;
    stack.push(block);
    onStack.add(block);

    for (BasicBlock succ : block.getSuccs()) {
      if (!scope.contains(succ)) {
        continue;
      }

      if (!index.containsKey(succ)) {
        strongConnect(succ, scope, index, lowlink, stack, onStack, components, counter);
        lowlink.put(block, Math.min(lowlink.get(block), lowlink.get(succ)));
      } else if (onStack.contains(succ)) {
        lowlink.put(block, Math.min(lowlink.get(block), index.get(succ)));
      }
    }

    if (lowlink.get(block).equals(index.get(block))) {
      Set<BasicBlock> component = new LinkedHashSet<>();
      BasicBlock current;
      do {
        current = stack.pop();
        onStack.remove(current);
        component.add(current);
      } while (current != block);
      components.add(component);
    }
  }

  private static boolean isLoopComponent(Set<BasicBlock> component) {
    if (component.size() > 1) {
      return true;
    }

    BasicBlock block = component.iterator().next();
    return block.getSuccs().contains(block);
  }

  private static List<BasicBlock> findEntryPoints(ControlFlowGraph graph, Set<BasicBlock> component) {
    List<BasicBlock> entries = new ArrayList<>();

    for (BasicBlock block : component) {
      if (block == graph.getFirst()) {
        entries.add(block);
        continue;
      }

      boolean hasExternalRegularPred = false;
      for (BasicBlock pred : block.getPreds()) {
        if (!component.contains(pred)) {
          hasExternalRegularPred = true;
          break;
        }
      }

      if (hasExternalRegularPred) {
        entries.add(block);
      }
    }

    entries.sort(Comparator.comparingInt(BasicBlock::getId));
    return entries;
  }

  private static Set<BasicBlock> findExitPoints(Set<BasicBlock> component) {
    Set<BasicBlock> exits = new LinkedHashSet<>();

    for (BasicBlock block : component) {
      for (BasicBlock succ : block.getSuccs()) {
        if (!component.contains(succ)) {
          exits.add(block);
          break;
        }
      }
    }

    return exits;
  }

  private static String regionKey(Set<BasicBlock> component) {
    StringBuilder builder = new StringBuilder();
    component.stream()
             .map(BasicBlock::getId)
             .sorted()
             .forEach(id -> builder.append(id).append(';'));
    return builder.toString();
  }

  private static int lowestBlockId(Set<BasicBlock> blocks) {
    int min = Integer.MAX_VALUE;
    for (BasicBlock block : blocks) {
      min = Math.min(min, block.id);
    }
    return min;
  }

  private static boolean containsSyntheticScaffold(Set<BasicBlock> blocks) {
    for (BasicBlock block : blocks) {
      if (DispatcherBlockBuilder.isSyntheticDispatcherScaffold(block)) {
        return true;
      }
    }
    return false;
  }
}

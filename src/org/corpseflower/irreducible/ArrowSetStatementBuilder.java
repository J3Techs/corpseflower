package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.code.cfg.ExceptionRangeCFG;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.stats.BasicBlockStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchAllStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DoStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DummyExitStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.GeneralStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.SequenceStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.SwitchStatement;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.util.collections.VBStyleCollection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ArrowSetStatementBuilder {
  private ArrowSetStatementBuilder() {
  }

  public static RootStatement buildFromCFG(ControlFlowGraph graph, StructMethod mt) {
    if (graph.getBlocks().isEmpty()) {
      return null;
    }

    DummyExitStatement dummyExit = new DummyExitStatement();
    VBStyleCollection<Statement, Integer> stats = new VBStyleCollection<>();

    for (BasicBlock block : graph.getBlocks()) {
      BasicBlockStatement stat = new BasicBlockStatement(block);
      stats.addWithKey(stat, block.id);
    }

    Statement first = stats.getWithKey(graph.getFirst().id);
    if (first == null) {
      return null;
    }

    if (stats.size() == 1 && !graph.getFirst().isSuccessor(graph.getFirst())) {
      RootStatement root = new RootStatement(first, dummyExit, mt);
      first.addSuccessor(new StatEdge(StatEdge.TYPE_BREAK, first, dummyExit, root));
      root.addComments(graph);
      return root;
    }

    ArrowSet arrowSet = ArrowSet.fromCFG(graph);
    GeneralStatement general = new GeneralStatement(first, stats, null);
    RootStatement root = new RootStatement(general, dummyExit, mt);

    wireGraphEdges(graph, mt, stats, dummyExit, general);
    general.setAllParent();
    general.buildContinueSet();
    general.buildMonitorFlags();

    collapseSimpleStatements(general, arrowSet, mt);
    Statement body = general.getStats().size() == 1 ? general.getFirst() : buildFallbackSequence(general, arrowSet);

    if (body == null) {
      return null;
    }

    if (body != general) {
      root.replaceStatement(general, body);
    }

    root.getFirst().buildContinueSet();
    root.getFirst().buildMonitorFlags();
    root.addComments(graph);
    return root;
  }

  private static void wireGraphEdges(ControlFlowGraph graph,
                                     StructMethod mt,
                                     VBStyleCollection<Statement, Integer> stats,
                                     DummyExitStatement dummyExit,
                                     Statement general) {
    Set<Integer> exitPredIds = new HashSet<>();
    for (BasicBlock exitPred : graph.getLast().getPreds()) {
      exitPredIds.add(exitPred.id);
    }

    for (BasicBlock block : graph.getBlocks()) {
      Statement stat = stats.getWithKey(block.id);
      if (stat == null) {
        continue;
      }

      boolean hasDummyExit = false;
      for (BasicBlock succ : block.getSuccs()) {
        Statement succStat = stats.getWithKey(succ.id);
        if (succ.id == graph.getFirst().id) {
          stat.addSuccessor(new StatEdge(StatEdge.TYPE_CONTINUE, stat, general, general));
        } else if (succStat != null) {
          stat.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, stat, succStat));
        } else {
          hasDummyExit = true;
          stat.addSuccessor(new StatEdge(exitEdgeType(graph, block), stat, dummyExit, general));
        }
      }

      if (!hasDummyExit && exitPredIds.contains(block.id)) {
        stat.addSuccessor(new StatEdge(exitEdgeType(graph, block), stat, dummyExit, general));
      }

      for (BasicBlock succEx : block.getSuccExceptions()) {
        Statement succStat = stats.getWithKey(succEx.id);
        if (succStat == null) {
          continue;
        }

        ExceptionRangeCFG range = graph.getExceptionRange(succEx, block);
        if (range == null) {
          DecompilerContext.getLogger().writeMessage(
            "Missing exception range for block " + block.id + " -> " + succEx.id + " in " + mt.getName() + mt.getDescriptor(),
            IFernflowerLogger.Severity.WARN
          );
          stat.addSuccessor(new StatEdge(stat, succStat, null));
          continue;
        }

        if (!range.isCircular()) {
          stat.addSuccessor(new StatEdge(stat, succStat, range.getExceptionTypes()));
        }
      }
    }
  }

  private static int exitEdgeType(ControlFlowGraph graph, BasicBlock block) {
    return graph.getFinallyExits().contains(block) ? StatEdge.TYPE_FINALLYEXIT : StatEdge.TYPE_BREAK;
  }

  private static void collapseSimpleStatements(GeneralStatement general, ArrowSet arrowSet, StructMethod mt) {
    boolean changed;

    do {
      changed = false;

      for (Statement candidate : orderForCollapse(general, arrowSet)) {
        if (candidate.getParent() != general) {
          continue;
        }

        Statement result;
        try {
          result = detectStatement(candidate);
        } catch (RuntimeException ex) {
          DecompilerContext.getLogger().writeMessage(
            "Arrow-set fallback skipped candidate " + candidate + " in " + mt.getName() + mt.getDescriptor() + ": " + ex.getMessage(),
            IFernflowerLogger.Severity.WARN
          );
          continue;
        }

        if (result == null) {
          continue;
        }

        general.collapseNodesToStatement(result);
        changed = true;
        break;
      }
    }
    while (changed);
  }

  private static Statement detectStatement(Statement head) {
    Statement result;

    if ((result = DoStatement.isHead(head)) != null) {
      return result;
    }

    if ((result = SwitchStatement.isHead(head)) != null) {
      return result;
    }

    if ((result = IfStatement.isHead(head)) != null) {
      return result;
    }

    if ((result = SequenceStatement.isHead2Block(head)) != null) {
      return result;
    }

    if ((result = CatchStatement.isHead(head)) != null) {
      return result;
    }

    if ((result = CatchAllStatement.isHead(head)) != null) {
      return result;
    }

    return null;
  }

  private static Statement buildFallbackSequence(GeneralStatement general, ArrowSet arrowSet) {
    List<Statement> ordered = new ArrayList<>(general.getStats());
    ordered.sort(Comparator
      .comparingInt((Statement stat) -> getRange(stat, arrowSet).start())
      .thenComparingInt(stat -> getRange(stat, arrowSet).end())
      .thenComparingInt(stat -> stat.id));

    if (ordered.isEmpty()) {
      return null;
    }

    if (ordered.size() == 1) {
      return ordered.get(0);
    }

    SequenceStatement sequence = new SequenceStatement(ordered);
    wireSequenceEdges(ordered);
    sequence.setAllParent();
    sequence.buildContinueSet();
    sequence.buildMonitorFlags();
    return sequence;
  }

  private static void wireSequenceEdges(List<Statement> orderedStats) {
    for (int i = 0; i < orderedStats.size() - 1; i++) {
      Statement current = orderedStats.get(i);
      Statement next = orderedStats.get(i + 1);

      boolean hasRegularEdge = false;
      for (StatEdge edge : current.getSuccessorEdges(StatEdge.TYPE_REGULAR)) {
        if (edge.getDestination() == next) {
          hasRegularEdge = true;
          break;
        }
      }

      if (!hasRegularEdge && current.hasBasicSuccEdge()) {
        current.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, current, next));
      }
    }
  }

  private static List<Statement> orderForCollapse(GeneralStatement general, ArrowSet arrowSet) {
    List<Statement> ordered = new ArrayList<>(general.getStats());
    ordered.sort(Comparator
      .comparingInt((Statement stat) -> getRange(stat, arrowSet).end())
      .thenComparingInt(stat -> getRange(stat, arrowSet).start())
      .thenComparingInt(stat -> stat.id)
      .reversed());
    return ordered;
  }

  private static PositionRange getRange(Statement stat, ArrowSet arrowSet) {
    int[] bounds = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE};
    collectRange(stat, arrowSet, bounds);

    if (bounds[0] == Integer.MAX_VALUE) {
      return new PositionRange(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    return new PositionRange(bounds[0], bounds[1]);
  }

  private static void collectRange(Statement stat, ArrowSet arrowSet, int[] bounds) {
    if (stat instanceof BasicBlockStatement basic) {
      int position = arrowSet.getPosition(basic.getBlock());
      if (position >= 0) {
        bounds[0] = Math.min(bounds[0], position);
        bounds[1] = Math.max(bounds[1], position);
      }
      return;
    }

    for (Statement child : stat.getStats()) {
      collectRange(child, arrowSet, bounds);
    }
  }

  private record PositionRange(int start, int end) {
  }
}

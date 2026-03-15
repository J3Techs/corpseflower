package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.struct.StructMethod;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class IrreducibleDispatcherInserter {
  private static final int MAX_DISPATCHERS_PER_METHOD = 6;

  private IrreducibleDispatcherInserter() {
  }

  public static int insertDispatchers(ControlFlowGraph graph, StructMethod mt) {
    List<IrreducibleRegionFinder.IrreducibleRegion> regions = IrreducibleRegionFinder.findRegions(graph);
    if (regions.isEmpty()) {
      return 0;
    }

    IFernflowerLogger logger = DecompilerContext.getLogger();
    int inserted = 0;
    Set<BasicBlock> claimedBlocks = new HashSet<>();

    for (IrreducibleRegionFinder.IrreducibleRegion region : regions) {
      if (region.entryPoints().size() < 2) {
        continue;
      }
      if (region.blocks().size() < 3) {
        continue;
      }
      if (inserted >= MAX_DISPATCHERS_PER_METHOD) {
        break;
      }

      boolean overlaps = false;
      for (BasicBlock block : region.blocks()) {
        if (claimedBlocks.contains(block)) {
          overlaps = true;
          break;
        }
      }
      if (overlaps) {
        continue;
      }

      int dispatchVar = DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.VAR_COUNTER);
      boolean success = DispatcherBlockBuilder.insertDispatcher(graph, mt, region, dispatchVar, logger);
      if (success) {
        inserted++;
        claimedBlocks.addAll(region.blocks());
      }
    }

    return inserted;
  }
}

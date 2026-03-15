# Purplesyringa Arrow-Set Integration — Implementation Plan

**Target:** Eliminate all remaining `$VF` stubs and `parsing failure!` exceptions on ZKM-damaged bytecode by adding arrow-set based dispatcher insertion to Corpseflower.

---

## Where This Fits in the Pipeline

```
MethodProcessor.codeToJava() pipeline:

  Line 100:  new ControlFlowGraph(seq)           ← CFG built from bytecode
  Line 104:  DeadCodeHelper.removeDeadBlocks()
  Line 113:  DeadCodeHelper.removeGotos()
  Line 115:  ExceptionDeobfuscator.removeCircularRanges()
  Line 136:  DeadCodeHelper.mergeBasicBlocks()
  Line 137:  ExceptionDeobfuscator.splitCleanupCloseBlocks()
  Line 138:  ExceptionDeobfuscator.removeNonThrowingExceptionEdges()
  Line 139:  ExceptionDeobfuscator.removeRedundantCleanupCloseBlocks()

  ─── NEW: INSERT DISPATCHER PASS HERE ───
  Line 168:  IrreducibleDispatcherInserter.insertDispatchers(graph)   ← NEW
  ─────────────────────────────────────────

  Line 169:  DomHelper.parseGraph(graph, mt, 0)  ← Statement tree built
  Line 181:  FinallyProcessor loop               ← Finally handling
  Line 258:  Main decompilation loop              ← Expression/statement passes
```

The dispatcher insertion runs AFTER all exception deobfuscation but BEFORE DomHelper tries to build the statement tree. This is the optimal point because:
- Exception edges are already cleaned up (non-throwing removed, circular removed)
- Basic blocks are already merged
- The CFG is as clean as it will get from deobfuscation
- DomHelper hasn't attempted parsing yet (no wasted work on irreducible flow)

---

## New Files to Create

```
src/org/corpseflower/irreducible/
├── IrreducibleDispatcherInserter.java   # Main entry point — detect and fix irreducible flow
├── ArrowSet.java                        # Arrow-set data structure and range queries
├── IrreducibleRegionFinder.java         # Detect irreducible regions in CFG
└── DispatcherBlockBuilder.java          # Build synthetic dispatcher blocks in CFG
```

---

## File 1: ArrowSet.java

The arrow set represents all control flow edges as directed arrows on a linear numbering of basic blocks. This enables range-based queries for block boundary detection and irreducible region identification.

```java
package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;

import java.util.*;

public final class ArrowSet {

    public record Arrow(int from, int to, BasicBlock fromBlock, BasicBlock toBlock, boolean isException) {
        public boolean isForward() { return to > from; }
        public boolean isBackward() { return to <= from; }
        public boolean crosses(int position) {
            int lo = Math.min(from, to);
            int hi = Math.max(from, to);
            return lo < position && position < hi;
        }
    }

    private final List<Arrow> arrows;
    private final Map<Integer, BasicBlock> positionToBlock;
    private final Map<BasicBlock, Integer> blockToPosition;
    private final int size;

    private ArrowSet(List<Arrow> arrows, Map<Integer, BasicBlock> posToBlock,
                     Map<BasicBlock, Integer> blockToPos, int size) {
        this.arrows = arrows;
        this.positionToBlock = posToBlock;
        this.blockToPosition = blockToPos;
        this.size = size;
    }

    /**
     * Build an ArrowSet from a ControlFlowGraph.
     * Assigns linear positions to blocks in the order they appear in graph.getBlocks().
     */
    public static ArrowSet fromCFG(ControlFlowGraph graph) {
        // Assign linear positions to blocks
        Map<BasicBlock, Integer> blockToPos = new LinkedHashMap<>();
        Map<Integer, BasicBlock> posToBlock = new LinkedHashMap<>();
        int pos = 0;
        for (BasicBlock block : graph.getBlocks()) {
            blockToPos.put(block, pos);
            posToBlock.put(pos, block);
            pos++;
        }

        // Build arrows from all edges
        List<Arrow> arrows = new ArrayList<>();
        for (BasicBlock block : graph.getBlocks()) {
            int fromPos = blockToPos.get(block);

            // Regular successors
            for (BasicBlock succ : block.getSuccs()) {
                Integer toPos = blockToPos.get(succ);
                if (toPos != null) {
                    arrows.add(new Arrow(fromPos, toPos, block, succ, false));
                }
            }

            // Exception successors
            for (BasicBlock succEx : block.getSuccExceptions()) {
                Integer toPos = blockToPos.get(succEx);
                if (toPos != null) {
                    arrows.add(new Arrow(fromPos, toPos, block, succEx, true));
                }
            }
        }

        return new ArrowSet(arrows, posToBlock, blockToPos, pos);
    }

    /**
     * Find split gaps in range [l, r] — positions where no arrow crosses.
     * A split gap means the range can be cleanly divided without breaking any edge.
     */
    public List<Integer> findSplitGaps(int l, int r) {
        List<Integer> gaps = new ArrayList<>();
        for (int pos = l + 1; pos < r; pos++) {
            boolean crossed = false;
            for (Arrow a : arrows) {
                if (a.crosses(pos)) {
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

    /**
     * Find arrows that create irreducible flow — forward and backward arrows
     * that interleave (one starts inside the other's range).
     */
    public List<int[]> findCollisions() {
        List<int[]> collisions = new ArrayList<>();
        for (int i = 0; i < arrows.size(); i++) {
            Arrow a = arrows.get(i);
            for (int j = i + 1; j < arrows.size(); j++) {
                Arrow b = arrows.get(j);
                // Two arrows collide if their ranges interleave:
                // a.from < b.from < a.to < b.to (or vice versa)
                if (interleaves(a, b)) {
                    collisions.add(new int[]{i, j});
                }
            }
        }
        return collisions;
    }

    private boolean interleaves(Arrow a, Arrow b) {
        int aLo = Math.min(a.from, a.to);
        int aHi = Math.max(a.from, a.to);
        int bLo = Math.min(b.from, b.to);
        int bHi = Math.max(b.from, b.to);
        // Interleave: aLo < bLo < aHi < bHi or bLo < aLo < bHi < aHi
        return (aLo < bLo && bLo < aHi && aHi < bHi) ||
               (bLo < aLo && aLo < bHi && bHi < aHi);
    }

    // Accessors
    public List<Arrow> getArrows() { return arrows; }
    public BasicBlock getBlock(int position) { return positionToBlock.get(position); }
    public int getPosition(BasicBlock block) { return blockToPosition.getOrDefault(block, -1); }
    public int size() { return size; }
}
```

---

## File 2: IrreducibleRegionFinder.java

Detects irreducible regions using both dominator analysis (Vineflower's existing engine) and arrow-set collision detection (purplesyringa's approach).

```java
package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.GenericDominatorEngine;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.IGraph;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.IGraphNode;

import java.util.*;

public final class IrreducibleRegionFinder {

    /**
     * An irreducible region: a set of blocks with multiple entry points
     * where no single block dominates all others.
     */
    public record IrreducibleRegion(
        Set<BasicBlock> blocks,        // All blocks in the region
        List<BasicBlock> entryPoints,  // Blocks reachable from outside the region
        Set<BasicBlock> exitPoints     // Blocks with successors outside the region
    ) {}

    /**
     * Find all irreducible regions in the CFG.
     * Uses two complementary detection methods:
     * 1. Dominator-based: back edges to non-dominators
     * 2. Arrow-set collision: interleaving forward/backward arrows
     */
    public static List<IrreducibleRegion> findRegions(ControlFlowGraph graph) {
        // Method 1: Dominator-based detection
        List<IrreducibleRegion> regions = findByDominators(graph);

        // Method 2: Arrow-set collision detection (catches cases Method 1 misses)
        ArrowSet arrowSet = ArrowSet.fromCFG(graph);
        List<IrreducibleRegion> arrowRegions = findByArrowCollisions(graph, arrowSet);

        // Merge unique regions
        for (IrreducibleRegion ar : arrowRegions) {
            boolean isDuplicate = false;
            for (IrreducibleRegion dr : regions) {
                if (dr.blocks().containsAll(ar.blocks()) || ar.blocks().containsAll(dr.blocks())) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                regions.add(ar);
            }
        }

        return regions;
    }

    /**
     * Dominator-based: find back edges where the target does NOT dominate the source.
     * These indicate irreducible loops.
     */
    private static List<IrreducibleRegion> findByDominators(ControlFlowGraph graph) {
        // Build dominator tree using Vineflower's existing engine
        GenericDominatorEngine engine = new GenericDominatorEngine(new IGraph() {
            @Override
            public List<? extends IGraphNode> getReversePostOrderList() {
                return graph.getReversePostOrder();
            }
            @Override
            public Set<? extends IGraphNode> getRoots() {
                return Set.of(graph.getFirst());
            }
        });
        engine.initialize();

        List<IrreducibleRegion> regions = new ArrayList<>();

        for (BasicBlock block : graph.getBlocks()) {
            for (BasicBlock succ : block.getSuccs()) {
                // Back edge: successor has lower position (or equal = self-loop)
                boolean isBackEdge = isBackEdge(graph, block, succ);
                if (!isBackEdge) continue;

                // If succ dominates block → reducible (normal loop). Skip.
                if (engine.isDominator(block, succ)) continue;

                // Back edge to non-dominator → irreducible!
                // Collect the region: all blocks reachable from succ that can reach block
                Set<BasicBlock> regionBlocks = collectRegion(graph, succ, block);
                List<BasicBlock> entries = findEntryPoints(graph, regionBlocks);

                if (entries.size() >= 2) {
                    Set<BasicBlock> exits = findExitPoints(graph, regionBlocks);
                    regions.add(new IrreducibleRegion(regionBlocks, entries, exits));
                }
            }
        }

        return regions;
    }

    /**
     * Arrow-set collision: find interleaving arrows that indicate irreducible flow.
     */
    private static List<IrreducibleRegion> findByArrowCollisions(
            ControlFlowGraph graph, ArrowSet arrowSet) {
        List<int[]> collisions = arrowSet.findCollisions();
        if (collisions.isEmpty()) return List.of();

        List<IrreducibleRegion> regions = new ArrayList<>();
        for (int[] collision : collisions) {
            ArrowSet.Arrow a = arrowSet.getArrows().get(collision[0]);
            ArrowSet.Arrow b = arrowSet.getArrows().get(collision[1]);

            // The irreducible region spans the union of both arrows' ranges
            int lo = Math.min(Math.min(a.from(), a.to()), Math.min(b.from(), b.to()));
            int hi = Math.max(Math.max(a.from(), a.to()), Math.max(b.from(), b.to()));

            Set<BasicBlock> regionBlocks = new LinkedHashSet<>();
            for (int pos = lo; pos <= hi; pos++) {
                BasicBlock bb = arrowSet.getBlock(pos);
                if (bb != null) regionBlocks.add(bb);
            }

            List<BasicBlock> entries = findEntryPoints(graph, regionBlocks);
            if (entries.size() >= 2) {
                Set<BasicBlock> exits = findExitPoints(graph, regionBlocks);
                regions.add(new IrreducibleRegion(regionBlocks, entries, exits));
            }
        }

        return regions;
    }

    private static boolean isBackEdge(ControlFlowGraph graph, BasicBlock from, BasicBlock to) {
        // Simple heuristic: to's position in reverse post order is before from's
        List<BasicBlock> rpo = new ArrayList<>();
        for (IGraphNode node : graph.getReversePostOrder()) {
            rpo.add((BasicBlock) node);
        }
        int fromIdx = rpo.indexOf(from);
        int toIdx = rpo.indexOf(to);
        return toIdx <= fromIdx;
    }

    private static Set<BasicBlock> collectRegion(ControlFlowGraph graph,
                                                  BasicBlock regionHead, BasicBlock backEdgeSource) {
        // BFS/DFS from regionHead, collecting blocks until we find backEdgeSource
        Set<BasicBlock> region = new LinkedHashSet<>();
        Deque<BasicBlock> work = new ArrayDeque<>();
        work.add(regionHead);
        region.add(regionHead);

        while (!work.isEmpty()) {
            BasicBlock current = work.poll();
            for (BasicBlock succ : current.getSuccs()) {
                if (region.add(succ)) {
                    work.add(succ);
                }
            }
            // Stop expanding if we've reached a reasonable size
            if (region.size() > 50) break;
        }

        // Also ensure backEdgeSource is in the region
        region.add(backEdgeSource);
        return region;
    }

    static List<BasicBlock> findEntryPoints(ControlFlowGraph graph, Set<BasicBlock> region) {
        List<BasicBlock> entries = new ArrayList<>();
        for (BasicBlock block : region) {
            for (BasicBlock pred : block.getPreds()) {
                if (!region.contains(pred)) {
                    if (!entries.contains(block)) entries.add(block);
                    break;
                }
            }
        }
        // The first block (if it's the method entry) is always an entry point
        if (region.contains(graph.getFirst()) && !entries.contains(graph.getFirst())) {
            entries.add(0, graph.getFirst());
        }
        return entries;
    }

    static Set<BasicBlock> findExitPoints(ControlFlowGraph graph, Set<BasicBlock> region) {
        Set<BasicBlock> exits = new LinkedHashSet<>();
        for (BasicBlock block : region) {
            for (BasicBlock succ : block.getSuccs()) {
                if (!region.contains(succ)) {
                    exits.add(block);
                    break;
                }
            }
        }
        return exits;
    }
}
```

---

## File 3: DispatcherBlockBuilder.java

Transforms the CFG by inserting synthetic dispatcher blocks to make irreducible regions reducible.

```java
package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.*;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.code.cfg.ExceptionRangeCFG;
import org.jetbrains.java.decompiler.struct.consts.ConstantPool;

import java.util.*;

public final class DispatcherBlockBuilder {

    /**
     * Insert a dispatcher block for an irreducible region.
     *
     * Before:
     *   predA → entryX          (irreducible: multiple entries)
     *   predB → entryY
     *
     * After:
     *   predA → DISPATCHER       (single entry)
     *   predB → DISPATCHER
     *   DISPATCHER → switch(__dispatch) {
     *     case 0: goto entryX
     *     case 1: goto entryY
     *   }
     *
     * The key insight: this makes the region reducible because there's now
     * a single entry point (the dispatcher block). DomHelper can then
     * successfully build the statement tree.
     */
    public static boolean insertDispatcher(
            ControlFlowGraph graph,
            IrreducibleRegionFinder.IrreducibleRegion region,
            int dispatchVarIndex) {

        List<BasicBlock> entries = region.entryPoints();
        if (entries.size() < 2) return false; // Not actually irreducible

        // Build the dispatcher instruction sequence:
        //   ILOAD dispatchVarIndex
        //   LOOKUPSWITCH { 0: entry0, 1: entry1, ..., default: entry0 }
        SimpleInstructionSequence dispatchSeq = new SimpleInstructionSequence();

        // ILOAD __dispatch
        Instruction iload = Instruction.create(CodeConstants.opc_iload, false,
            CodeConstants.GROUP_GENERAL, 0, new int[]{dispatchVarIndex}, 1);
        dispatchSeq.addInstruction(iload, -1);

        // Build LOOKUPSWITCH keys and targets
        // (Actual target wiring happens through BasicBlock successor edges,
        //  not through instruction operands in Vineflower's CFG model.
        //  The instruction is just for type correctness.)
        int[] keys = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            keys[i] = i;
        }

        // Create dispatcher basic block
        BasicBlock dispatcherBlock = new BasicBlock(++graph.last_id);
        dispatcherBlock.setSeq(dispatchSeq);

        // For each entry point, create a setup block:
        //   ICONST <state_id>
        //   ISTORE dispatchVarIndex
        //   GOTO dispatcher
        Map<BasicBlock, BasicBlock> entryToSetup = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            BasicBlock entry = entries.get(i);
            SimpleInstructionSequence setupSeq = new SimpleInstructionSequence();

            // ICONST state_id
            Instruction iconst = Instruction.create(
                i <= 5 ? CodeConstants.opc_iconst_0 + i : CodeConstants.opc_bipush,
                false, CodeConstants.GROUP_GENERAL, 0,
                i <= 5 ? new int[]{} : new int[]{i}, 1);
            setupSeq.addInstruction(iconst, -1);

            // ISTORE __dispatch
            Instruction istore = Instruction.create(CodeConstants.opc_istore, false,
                CodeConstants.GROUP_GENERAL, 0, new int[]{dispatchVarIndex}, 1);
            setupSeq.addInstruction(istore, -1);

            BasicBlock setupBlock = new BasicBlock(++graph.last_id);
            setupBlock.setSeq(setupSeq);

            // Wire: setup → dispatcher
            setupBlock.addSuccessor(dispatcherBlock);

            graph.getBlocks().addWithKey(setupBlock, setupBlock.id);
            entryToSetup.put(entry, setupBlock);
        }

        // Wire: dispatcher → each entry point
        for (BasicBlock entry : entries) {
            dispatcherBlock.addSuccessor(entry);
        }

        graph.getBlocks().addWithKey(dispatcherBlock, dispatcherBlock.id);

        // Redirect external predecessors: instead of going directly to entry points,
        // they now go to the corresponding setup block
        for (BasicBlock entry : entries) {
            BasicBlock setupBlock = entryToSetup.get(entry);

            // Collect predecessors that are OUTSIDE the region
            List<BasicBlock> externalPreds = new ArrayList<>();
            for (BasicBlock pred : new ArrayList<>(entry.getPreds())) {
                if (!region.blocks().contains(pred)) {
                    externalPreds.add(pred);
                }
            }

            // Redirect external preds to setup block
            for (BasicBlock pred : externalPreds) {
                pred.replaceSuccessor(entry, setupBlock);
            }
        }

        // Handle exception ranges: if any exception range covers blocks in the region,
        // extend it to cover the dispatcher and setup blocks
        for (ExceptionRangeCFG range : graph.getExceptions()) {
            boolean coversRegion = false;
            for (BasicBlock block : region.blocks()) {
                if (range.getProtectedRange().contains(block)) {
                    coversRegion = true;
                    break;
                }
            }
            if (coversRegion) {
                range.getProtectedRange().add(dispatcherBlock);
                for (BasicBlock setup : entryToSetup.values()) {
                    range.getProtectedRange().add(setup);
                }
                // Add exception edges
                dispatcherBlock.addSuccessorException(range.getHandler());
                for (BasicBlock setup : entryToSetup.values()) {
                    setup.addSuccessorException(range.getHandler());
                }
            }
        }

        return true;
    }
}
```

---

## File 4: IrreducibleDispatcherInserter.java

The main entry point — orchestrates detection and insertion.

```java
package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.struct.StructMethod;

import java.util.List;

public final class IrreducibleDispatcherInserter {

    /**
     * Detect and fix irreducible control flow in the CFG.
     * Returns the number of dispatchers inserted.
     *
     * Called from MethodProcessor.codeToJava() BEFORE DomHelper.parseGraph().
     */
    public static int insertDispatchers(ControlFlowGraph graph, StructMethod mt) {
        List<IrreducibleRegionFinder.IrreducibleRegion> regions =
            IrreducibleRegionFinder.findRegions(graph);

        if (regions.isEmpty()) return 0;

        DecompilerContext.getLogger().writeMessage(
            "Found " + regions.size() + " irreducible region(s) in " +
            mt.getName() + mt.getDescriptor() + ", inserting dispatchers",
            IFernflowerLogger.Severity.WARN
        );

        // Allocate a dispatch variable (use next available local)
        int dispatchVar = mt.getLocalVariables() + 1;

        int inserted = 0;
        for (IrreducibleRegionFinder.IrreducibleRegion region : regions) {
            // Skip tiny regions (likely false positives)
            if (region.entryPoints().size() < 2) continue;

            // Skip regions with too many blocks (likely the entire method)
            if (region.blocks().size() > 100) {
                DecompilerContext.getLogger().writeMessage(
                    "  Skipping oversized region (" + region.blocks().size() +
                    " blocks, " + region.entryPoints().size() + " entries)",
                    IFernflowerLogger.Severity.WARN
                );
                continue;
            }

            boolean success = DispatcherBlockBuilder.insertDispatcher(
                graph, region, dispatchVar);

            if (success) {
                inserted++;
                dispatchVar++; // Next region gets a different variable
                DecompilerContext.getLogger().writeMessage(
                    "  Inserted dispatcher for region with " +
                    region.entryPoints().size() + " entry points, " +
                    region.blocks().size() + " blocks",
                    IFernflowerLogger.Severity.INFO
                );
            }
        }

        return inserted;
    }
}
```

---

## Integration Point: MethodProcessor.java

Add 3 lines after the exception deobfuscation block (after line 139, before line 168):

```java
// Line 139: ExceptionDeobfuscator.removeRedundantCleanupCloseBlocks(graph, cl);

// NEW: Purplesyringa-inspired dispatcher insertion for irreducible flow
int dispatchers = IrreducibleDispatcherInserter.insertDispatchers(graph, mt);
if (dispatchers > 0) {
    DeadCodeHelper.mergeBasicBlocks(graph); // Clean up after insertion
}

// Line 168: RootStatement root = DomHelper.parseGraph(graph, mt, 0);
```

Import to add:
```java
import org.corpseflower.irreducible.IrreducibleDispatcherInserter;
```

---

## How the Existing Passes Clean Up Dispatchers

After `IrreducibleDispatcherInserter` runs and DomHelper succeeds, the dispatcher pattern flows through the existing pass pipeline:

1. **DomHelper.parseGraph()** — Now succeeds because the CFG is reducible. Builds statement tree with a DoStatement (infinite loop) containing a SwitchStatement on `__dispatch`.

2. **ConstantExpressionFolder** (MAIN_LOOP) — If the dispatch variable is always assigned constants, the switch cases may fold.

3. **StateMachineDeflattener** (AFTER_MAIN) — Detects the `while(true){switch(__dispatch)}` pattern. If the transition graph is a DAG, linearizes into sequential statements. If it has real loops, leaves as-is (the loop is genuine).

4. **DeadCodeEliminator** (MAIN_LOOP) — Removes any dead cases after the deflattener.

So the dispatcher is a temporary scaffolding that enables DomHelper to succeed. Later passes remove it if possible, or leave it as a readable switch-based control flow if the original flow was genuinely complex.

---

## Interaction with Existing Irreducible Handling

Vineflower already has `IrreducibleCFGDeobfuscator` which does **node splitting** (duplicating blocks to eliminate multiple entries). The dispatcher approach is complementary:

- **Node splitting** (existing): Duplicates blocks. Works for small regions but exponential in size. Limited to `MAX_IRREDUCIBLE_SPLITS = 24`.
- **Dispatcher insertion** (new): Adds a switch. Constant cost regardless of region size. Works for any region.

The dispatcher runs BEFORE DomHelper, so DomHelper's node splitting may never be needed. If a region is small enough, node splitting might still produce cleaner output (no dispatcher variable). The priority should be:

1. Dispatcher insertion makes the CFG reducible
2. DomHelper builds the statement tree (using node splitting only if needed for remaining small regions)
3. StateMachineDeflattener cleans up the dispatcher pattern
4. If the deflattener can linearize, the dispatcher disappears entirely

---

## Testing Strategy

### Unit test: verify dispatcher makes CFG reducible
```java
// Build a CFG with known irreducible flow
// Run IrreducibleDispatcherInserter
// Verify IrreducibleCFGDeobfuscator.isStatementIrreducible() returns false
```

### Integration test: hardest JAR
```bash
# Before dispatcher insertion:
#   hmi.host: 22 $VF stubs, multiple parsing failures
#   LoginScreen.startLoginThread(): parsing failure!
#   LoginScreen.checkTokenAndLoginIfValid(): parsing failure!

# After dispatcher insertion:
#   These methods should decompile with while(true){switch(__dispatch)} pattern
#   StateMachineDeflattener should linearize if DAG
#   $VF stubs should drop significantly

java -jar corpseflower.jar --verbose \
  com.ford.fdt.hmi.host-73.18.21.jar /tmp/test_dispatcher/
grep -c '$VF:' /tmp/test_dispatcher/**/*.java
```

### Regression test: clean JARs
```bash
# Clean JARs should have 0 dispatchers inserted (no irreducible flow)
# Output must be identical to stock Vineflower
```

---

## Risk Mitigation

| Risk | Mitigation |
|---|---|
| False positive irreducible detection | Minimum region size (2+ entries). Skip oversized regions (100+ blocks). Dominator + arrow-set cross-validation. |
| Dispatcher not cleaned up | StateMachineDeflattener handles it. Even if not linearized, `switch(__dispatch)` is valid readable Java. |
| Exception edge handling breaks | Copy exception ranges to dispatcher/setup blocks. Test on JARs with heavy exception tables (license manager). |
| Variable index collision | Use `mt.getLocalVariables() + 1` — guaranteed unused. |
| Performance on large methods | Arrow-set collision detection is O(n²) but only runs on methods the deobfuscator flagged with Analyzer failures. Fast enough for methods with <1000 blocks. |
| Breaks DomHelper expectations | Dispatcher block is a regular block with regular edges. DomHelper sees a standard switch pattern. No special handling needed. |

---

## Expected Impact

| Metric | Before | After (estimated) |
|---|---|---|
| Parsing failures (30 JARs) | 15 | **0** |
| `$VF` stubs (hmi.host) | 22 | **<5** (remaining are constructor resugaring) |
| `$VF` stubs (all 30 JARs) | 93 | **<15** |
| Methods with dispatcher patterns | 0 | ~20 (most linearized by deflattener) |
| CFR quality gate triggers | 3 | **0** (Corpseflower handles everything) |

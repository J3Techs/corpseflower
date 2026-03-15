# Corpseflower V5 Agent — Arrow-Set Statement Builder Fallback

You are adding a **fallback statement tree builder** to Corpseflower that uses the purplesyringa arrow-set algorithm to construct Statement trees directly, bypassing DomHelper entirely for methods that DomHelper cannot decompose.

**Repo:** `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\`
**Build:** `./gradlew shadowJar`

---

## THE PROBLEM

9 methods in the hardest JAR (`com.ford.fdt.hmi.host-73.18.21.jar`) fail DomHelper decomposition. Synthetic dispatchers make the CFG reducible and the switch IS recognized, but DomHelper's postdominator/exception analysis crashes on the dispatcher scaffold with "Statement cannot be decomposed although reducible!" or "computing post reverse post order failed!". Three attempts to fix DomHelper for these cases all regressed other methods. DomHelper is not going to work for these methods.

The current fallback path in `MethodProcessor.parseGraphWithDispatcherFallback()` (line 489-530) catches the `RuntimeException`, tries inserting more dispatchers, and eventually re-throws — producing a `$VF: Couldn't be decompiled` stub.

**Instead of re-throwing, build the Statement tree directly from the arrow-set.**

---

## THE SOLUTION

Add `ArrowSetStatementBuilder` — a new class that takes a `ControlFlowGraph` and produces a `RootStatement` using the purplesyringa `build(l,r)` recursive algorithm. It runs ONLY when DomHelper fails. DomHelper remains the primary path for the 274 methods it handles perfectly.

### Integration point: `MethodProcessor.parseGraphWithDispatcherFallback()`

Change line 508-509 from:
```java
if (round == MAX_DISPATCHER_RETRY_ROUNDS) {
    throw firstFailure;
}
```

To:
```java
if (round == MAX_DISPATCHER_RETRY_ROUNDS) {
    // DomHelper exhausted — try arrow-set direct construction
    try {
        RootStatement arrowSetRoot = ArrowSetStatementBuilder.buildFromCFG(graph, mt);
        if (arrowSetRoot != null) {
            DecompilerContext.getLogger().writeMessage(
                "Arrow-set fallback succeeded for " + mt.getName() + mt.getDescriptor(),
                IFernflowerLogger.Severity.WARN
            );
            return arrowSetRoot;
        }
    } catch (Exception arrowEx) {
        firstFailure.addSuppressed(arrowEx);
    }
    throw firstFailure;
}
```

Also add it as a final fallback after dispatchers are exhausted (line 513-514):
```java
if (dispatchers <= 0) {
    // No more dispatchers possible — try arrow-set
    try {
        RootStatement arrowSetRoot = ArrowSetStatementBuilder.buildFromCFG(graph, mt);
        if (arrowSetRoot != null) {
            return arrowSetRoot;
        }
    } catch (Exception arrowEx) {
        firstFailure.addSuppressed(arrowEx);
    }
    throw firstFailure;
}
```

---

## NEW FILE: `ArrowSetStatementBuilder.java`

**Location:** `src/org/corpseflower/irreducible/ArrowSetStatementBuilder.java`

### The Algorithm

The purplesyringa `build(l, r)` algorithm constructs block structure from a linear ordering of basic blocks:

```
build(l, r):
  if l == r-1:
    return BasicBlockStatement for block at position l

  gaps = arrowSet.findSplitGaps(l, r)
  if gaps is not empty:
    // Range can be cleanly divided — build a SequenceStatement
    segments = split [l,r] at gaps: [l,gap1], [gap1,gap2], ..., [gapN,r]
    return SequenceStatement(build(seg) for each seg)

  // No split gaps — this is a minimal block
  // Check for loops (backward arrows within [l,r])
  backArrows = arrows where from >= l && to >= l && from < r && to < r && to <= from
  if backArrows exist:
    // Loop detected
    loopHead = min(to) across backArrows  // earliest target = loop header
    loopTail = max(from) across backArrows  // latest source = loop back-edge
    body = build(loopHead, loopTail+1)
    return DoStatement(INFINITE, body)
    // The loop condition will be extracted by Vineflower's EliminateLoopsHelper later

  // Check for conditionals (forward branches within [l,r])
  firstBlock = block at position l
  forwardSuccessors = regular successors of firstBlock within [l,r]
  if forwardSuccessors.size() == 2:
    // if/else
    thenTarget = forwardSuccessors[0]
    elseTarget = forwardSuccessors[1]
    thenPos = position of thenTarget
    elsePos = position of elseTarget
    // Build if-body from [thenPos, elsePos] and else-body from [elsePos, r]
    // or [thenPos, r] if there's no else
    return IfStatement(head=firstBlock, ifBody=build(...), elseBody=build(...))

  if forwardSuccessors.size() > 2:
    // switch
    return SwitchStatement(head=firstBlock, cases=build each branch)

  // Single successor — sequence with the rest
  return SequenceStatement(BasicBlockStatement(firstBlock), build(l+1, r))
```

### Key Design Decisions

1. **Use existing BasicBlockStatements.** DomHelper's `graphToStatement()` already created `BasicBlockStatement` wrappers for each `BasicBlock`. The arrow-set builder should reuse these, not create new ones. Get them from the existing `GeneralStatement` or `RootStatement` that DomHelper partially built.

2. **Produce a valid Statement tree.** The result must have:
   - A `RootStatement` at the top
   - A `DummyExitStatement` as the last statement
   - Proper `StatEdge` connections (TYPE_REGULAR between sequential statements, TYPE_BREAK for exits)
   - Each Statement's `first` field pointing to its first child

3. **Don't try to be perfect.** The goal is readable Java, not beautiful Java. Emit `while(true)` for loops (Vineflower's loop passes will try to extract conditions later). Emit `if/else` for two-way branches. Emit `switch` for multi-way. For anything that doesn't fit, emit a flat `SequenceStatement`.

4. **Handle exception edges.** Exception arrows create try-catch structure. For the fallback, wrap the entire method body in a try-catch if exception handlers exist, rather than trying to precisely scope them.

5. **The expression parsing still uses Vineflower.** The arrow-set builder only constructs the **control flow skeleton**. Each `BasicBlockStatement` already contains its bytecode instructions. Vineflower's expression parser (`ExprProcessor`) runs on each `BasicBlockStatement` independently to convert stack operations into expression trees. This happens AFTER the Statement tree is built and doesn't depend on DomHelper.

### Handling the Existing Partial State

When `parseGraphWithDispatcherFallback` catches the DomHelper failure, some work has already been done:

- `graphToStatement()` created `BasicBlockStatement` wrappers (line 37 of DomHelper)
- `graphToStatement()` created a `GeneralStatement` containing all of them (line 48)
- `graphToStatement()` created a `RootStatement` (line 101)
- The statement edges were set up (lines 59-87 of DomHelper)
- Then `processStatement()` failed during decomposition

The arrow-set builder should work from the **original CFG** (which is still intact), not from the partially-built statement tree. Build fresh `BasicBlockStatement` wrappers and a new `RootStatement`.

### Implementation Sketch

```java
package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.code.cfg.ExceptionRangeCFG;
import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.struct.StructMethod;

import java.util.*;

public final class ArrowSetStatementBuilder {

    /**
     * Build a RootStatement directly from CFG using arrow-set block construction.
     * This bypasses DomHelper entirely — used only when DomHelper fails.
     */
    public static RootStatement buildFromCFG(ControlFlowGraph graph, StructMethod mt) {
        if (graph.getBlocks().isEmpty()) return null;

        ArrowSet arrowSet = ArrowSet.fromCFG(graph);
        int blockCount = graph.getBlocks().size();

        // Create BasicBlockStatements for each block (same as DomHelper.graphToStatement line 37)
        Map<Integer, BasicBlockStatement> blockStatements = new LinkedHashMap<>();
        for (BasicBlock block : graph.getBlocks()) {
            BasicBlockStatement bbs = new BasicBlockStatement(block);
            blockStatements.put(block.id, bbs);
        }

        // Build the statement tree using arrow-set recursive algorithm
        Statement body = buildRange(arrowSet, 0, blockCount, blockStatements);
        if (body == null) return null;

        // Create DummyExitStatement
        DummyExitStatement dummyExit = new DummyExitStatement();

        // Wrap in RootStatement
        RootStatement root = new RootStatement(body, dummyExit, mt);

        // Wire edges: body's exits → dummyExit
        // Find all statements that end with return/throw and add TYPE_BREAK to dummyExit
        wireExitEdges(body, dummyExit);

        // Handle exception ranges: wrap relevant sections in CatchStatement
        wrapExceptionRanges(root, graph, blockStatements);

        return root;
    }

    /**
     * Recursive block builder — the core purplesyringa algorithm.
     * Builds a Statement for the range [l, r) of blocks.
     */
    private static Statement buildRange(ArrowSet arrowSet, int l, int r,
                                         Map<Integer, BasicBlockStatement> blockStatements) {
        if (r - l <= 0) return null;

        // Base case: single block
        if (r - l == 1) {
            BasicBlock block = arrowSet.getBlock(l);
            return block == null ? null : blockStatements.get(block.id);
        }

        // Find split gaps — positions where no arrow crosses
        List<Integer> gaps = arrowSet.findSplitGaps(l, r);

        if (!gaps.isEmpty()) {
            // Build SequenceStatement from segments
            List<Statement> segments = new ArrayList<>();
            int segStart = l;
            for (int gap : gaps) {
                Statement seg = buildRange(arrowSet, segStart, gap, blockStatements);
                if (seg != null) segments.add(seg);
                segStart = gap;
            }
            Statement lastSeg = buildRange(arrowSet, segStart, r, blockStatements);
            if (lastSeg != null) segments.add(lastSeg);

            if (segments.size() == 1) return segments.get(0);
            return buildSequence(segments);
        }

        // No split gaps — minimal block
        // Check for backward arrows (loops)
        List<ArrowSet.Arrow> backArrows = new ArrayList<>();
        for (ArrowSet.Arrow arrow : arrowSet.getArrows()) {
            if (arrow.exceptionEdge()) continue;
            if (arrow.from() >= l && arrow.from() < r &&
                arrow.to() >= l && arrow.to() < r &&
                arrow.to() <= arrow.from()) {
                backArrows.add(arrow);
            }
        }

        if (!backArrows.isEmpty()) {
            // Loop — build as DoStatement(INFINITE, body)
            Statement loopBody = buildRange(arrowSet, l, r, blockStatements);
            // Wrap in infinite loop (Vineflower's loop passes will refine later)
            return buildInfiniteLoop(loopBody);
        }

        // Check forward branch count from first block
        BasicBlock firstBlock = arrowSet.getBlock(l);
        if (firstBlock != null) {
            List<BasicBlock> fwdSuccs = new ArrayList<>();
            for (BasicBlock succ : firstBlock.getSuccs()) {
                int pos = arrowSet.getPosition(succ);
                if (pos >= l && pos < r) {
                    fwdSuccs.add(succ);
                }
            }

            if (fwdSuccs.size() == 2) {
                // Two-way branch — build as if/else
                return buildIfElse(arrowSet, l, r, firstBlock, fwdSuccs, blockStatements);
            }

            if (fwdSuccs.size() > 2) {
                // Multi-way — build as switch
                return buildSwitch(arrowSet, l, r, firstBlock, fwdSuccs, blockStatements);
            }
        }

        // Fallback: flat sequence of all blocks in range
        List<Statement> flat = new ArrayList<>();
        for (int pos = l; pos < r; pos++) {
            BasicBlock block = arrowSet.getBlock(pos);
            if (block != null) {
                BasicBlockStatement bbs = blockStatements.get(block.id);
                if (bbs != null) flat.add(bbs);
            }
        }
        if (flat.size() == 1) return flat.get(0);
        return buildSequence(flat);
    }

    // ... helper methods for buildSequence, buildInfiniteLoop, buildIfElse,
    //     buildSwitch, wireExitEdges, wrapExceptionRanges
}
```

---

## CRITICAL: Statement Edge Wiring

The hardest part is getting the `StatEdge` connections right. Vineflower's Statement tree requires:

### Edge types (from `StatEdge.java`):
- `TYPE_REGULAR` — normal sequential flow (statement A → statement B)
- `TYPE_BREAK` — exit from a loop/switch (break/return)
- `TYPE_CONTINUE` — back-edge to loop head (continue)
- `TYPE_EXCEPTION` — exception handler entry

### What each Statement type needs:
- `SequenceStatement(A, B, C)`: A→B (REGULAR), B→C (REGULAR)
- `DoStatement(INFINITE, body)`: body's last → body's first (CONTINUE)
- `IfStatement(head, ifBody, elseBody)`: head→ifBody (REGULAR), head→elseBody (REGULAR)
- `SwitchStatement(head, cases)`: head→case0 (REGULAR), head→case1 (REGULAR), ...
- `RootStatement(body, exit)`: body→exit (BREAK) for return/throw exits

### How to wire:

```java
private static SequenceStatement buildSequence(List<Statement> segments) {
    SequenceStatement seq = new SequenceStatement(segments);
    // SequenceStatement constructor handles internal edge wiring
    return seq;
}

private static DoStatement buildInfiniteLoop(Statement body) {
    DoStatement loop = new DoStatement(DoStatement.Type.INFINITE, body);
    // The constructor handles the continue edge
    return loop;
}
```

**Look at how DomHelper constructs these** in `findSimpleStatements()` and `detectStatement()` for reference on exact edge wiring. The constructors of `SequenceStatement`, `DoStatement`, `IfStatement`, and `SwitchStatement` handle most of the internal wiring — you mainly need to set up the external edges (entries from predecessors, exits to successors).

### Simpler alternative: build everything as SequenceStatement

If the full recursive algorithm is too complex to get right in one pass, start with the simplest possible approach:

```java
public static RootStatement buildFromCFG(ControlFlowGraph graph, StructMethod mt) {
    // Flatten ALL blocks into a single SequenceStatement
    // This produces ugly but valid output with labels and breaks
    List<Statement> allBlocks = new ArrayList<>();
    for (BasicBlock block : graph.getBlocks()) {
        allBlocks.add(new BasicBlockStatement(block));
    }
    Statement body = new SequenceStatement(allBlocks);
    DummyExitStatement exit = new DummyExitStatement();
    return new RootStatement(body, exit, mt);
}
```

This is the absolute minimum — a flat sequence of all blocks. It produces Java like:
```java
void method() {
    // block 0
    int x = 5;
    if (condition) { /* block 1 */ }
    // block 2
    LOG.info("done");
    return;
}
```

Not pretty, but infinitely better than a `$VF` stub. Once the flat approach works, add structure (loops, if/else) incrementally.

---

## VALIDATION

### Test 1: Build
```bash
cd "C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower"
./gradlew shadowJar
```

### Test 2: Hard JAR
```bash
CF_JAR="build/libs/corpseflower-1.0.0-SNAPSHOT-all.jar"
cp "C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\OBFUSCATED\com.ford.fdt.hmi.host-73.18.21.jar" /c/tmp/cf_v5_input.jar
java -jar "$CF_JAR" --verbose /c/tmp/cf_v5_input.jar /c/tmp/cf_v5_output/ 2>&1 | tee /c/tmp/cf_v5.log

echo "Files: $(find /c/tmp/cf_v5_output -name '*.java' | wc -l)"
echo "VF stubs: $(grep -rl '\$VF: Couldn' /c/tmp/cf_v5_output --include='*.java' | wc -l)"
echo "Arrow-set fallbacks: $(grep -c 'Arrow-set fallback' /c/tmp/cf_v5.log)"
```

**Current baseline (beat this):**
- 283 files, 9 raw stubs (2 final after quality gate)

**Target:**
- 283 files, 0 raw stubs, 0 final stubs
- Arrow-set fallback fires on the 9 previously-failing methods
- Clean JARs: 0 stubs, arrow-set never fires (DomHelper handles everything)

### Test 3: Clean JAR regression
```bash
cp "C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\CLEAN\com.ford.fdrs.core.common-11.10.6.jar" /c/tmp/cf_v5_clean.jar
java -jar "$CF_JAR" /c/tmp/cf_v5_clean.jar /c/tmp/cf_v5_clean_output/
echo "Clean stubs: $(grep -rl '\$VF:' /c/tmp/cf_v5_clean_output --include='*.java' | wc -l)"
# Must be 0
```

---

## FILES TO READ

- `src/org/corpseflower/irreducible/ArrowSet.java` — existing, has `findSplitGaps()` already
- `src/org/corpseflower/irreducible/DispatcherBlockBuilder.java` — existing dispatcher builder
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/SequenceStatement.java` — constructor for sequences
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/DoStatement.java` — constructor for loops
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/IfStatement.java` — constructor for conditionals
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/SwitchStatement.java` — constructor for switches
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/BasicBlockStatement.java` — leaf nodes
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/RootStatement.java` — tree root
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/DummyExitStatement.java` — exit sentinel
- `src/org/jetbrains/java/decompiler/modules/decompiler/stats/Statement.java` — base class, edge wiring
- `src/org/jetbrains/java/decompiler/modules/decompiler/decompose/DomHelper.java` — reference for how `graphToStatement` and `processStatement` work
- `src/org/jetbrains/java/decompiler/main/rels/MethodProcessor.java` — integration point (line 489)

## PATH HANDLING
- Shell is bash (Git Bash/MSYS2) — use Unix syntax
- Java needs Windows paths — use `cygpath -w`
- Build: `./gradlew shadowJar`
- Java 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\`

---

## CRITICAL NOTES

1. **Start with the flat SequenceStatement approach.** Get it compiling, building, and producing output (even ugly output) for the 9 failing methods. Then add structure incrementally. A flat sequence that produces readable Java is infinitely better than a `$VF` stub with raw bytecode.

2. **Don't touch DomHelper.** The arrow-set builder is a parallel path, not a DomHelper modification. DomHelper stays the primary for 99% of methods.

3. **The expression parser is independent.** `ExprProcessor` runs on each `BasicBlockStatement` after the Statement tree is built. It doesn't care how the tree was constructed. So the arrow-set builder only needs to produce the control flow skeleton — expressions are handled automatically.

4. **Test after every change.** Build, run on `hmi.host`, check stub count. The arrow-set builder must never crash — if it fails, catch the exception and fall through to the `$VF` stub.

5. **Clean JAR regression is a hard stop.** The arrow-set builder should never fire on clean code (DomHelper handles it). If it does fire and produces worse output, something is wrong.

6. **Commit the current working state first** before starting the arrow-set builder. The current 9-stub / 2-final state is valuable and shouldn't be lost.

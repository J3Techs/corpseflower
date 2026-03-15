# Purplesyringa Arrow-Set Control Flow Recovery — Corpseflower Integration Plan

**Status:** Future work (after v2 fixes land)
**Source:** https://purplesyringa.moe/blog/recovering-control-flow-structures-without-cfgs/
**Philosophy:** Corpseflower's goal is the best possible output on every method. Not "pick one algorithm" — combine every viable technique and keep whichever produces the cleanest result per method. Vineflower's CFG-based approach stays as the primary engine. Purplesyringa's arrow-set approach augments it as a parallel recovery path. CFR stays as a regression benchmark. The final output for each method is whichever technique scored highest.

---

## The Problem This Solves

After FDRSDeobfuscator v2.9 cleans bytecode, ~22 methods in the hardest JAR (`hmi.host`) still fail Vineflower's decompilation. These failures share a common root cause:

1. ZKM exception-table fragmentation creates **irreducible control flow** — backward jumps inside protected exception ranges that produce paths with incompatible stack depths
2. Vineflower builds a **CFG** (control flow graph) and then tries to match it against structured Java constructs (if/else, while, for, try-catch) using **dominator tree analysis**
3. When the CFG has irreducible regions (multiple entry points into a loop, or crossing exception handler boundaries), Vineflower's structural analysis **rejects the method** and emits `$VF: Couldn't be decompiled`
4. CFR handles some of these because its control flow recovery is more permissive — it allows `goto`-like constructs and labels in its intermediate representation, which Vineflower does not

The purplesyringa algorithm handles irreducible control flow by design, not as an edge case.

---

## What the Algorithm Does

### Traditional approach (Vineflower/CFR)
```
Bytecode → Build CFG → Dominator tree → Match patterns → Structured Java
                              ↑
                    Fails here on irreducible flow
```

### Purplesyringa approach
```
Bytecode → Linearized statements → Arrow sets → Range queries → Block tree → Structured Java
                                                      ↑
                                        Handles irreducible flow via dispatchers
```

### Core concepts

**Arrows:** Every jump in the bytecode (goto, conditional, exception handler entry) is represented as an arrow from source to destination on a linear number line of statement positions.

**Blocks:** Contiguous ranges of statements `[l, r]` where all arrows either stay within the range or go entirely outside it. If an arrow crosses a block boundary, the block must be split or the arrow must be dispatched.

**Split gaps:** Positions between statements where no arrow crosses. These are natural block boundaries — the algorithm recursively splits at these gaps.

**Dispatcher mechanism:** When arrows create irreducible flow (e.g., two arrows both entering the same region from different directions), instead of giving up, the algorithm introduces a synthetic variable:
```java
int state = 0;
while (true) {
    switch (state) {
        case 0: /* original code path A */ state = 2; break;
        case 1: /* original code path B */ state = 2; break;
        case 2: /* merge point */ return result;
    }
}
```
This localizes the irreducible portion to a single switch block. The rest of the method remains cleanly structured.

**Key insight:** If the arrow set forms a tree (no crossings), it maps directly to nested blocks (if/else/while). If arrows cross (irreducible), only the crossing region gets a dispatcher — the rest stays clean.

---

## What We Can Integrate

### 1. Fallback Decompilation Pass (highest value, most feasible)

**What:** A new pass `ArrowSetRecovery` that fires ONLY on methods where Vineflower's standard decompilation fails.

**When it runs:** After Vineflower attempts decompilation and produces a `$VF` failure marker.

**How it works:**
1. Takes the method's bytecode (post-deobfuscation)
2. Builds the arrow set from jump instructions + exception handler entries
3. Identifies split gaps → recursive block construction
4. For irreducible regions, inserts dispatcher variables
5. Produces a Statement tree that Vineflower's code generator can emit

**Integration point:** Register as an `AT_END` pass in Vineflower. When processing a method that has `$VF` markers, re-analyze the bytecode with the arrow-set approach and replace the failed output.

**Expected impact:** Could recover 15-22 of the currently failing methods on `hmi.host`. The dispatcher patterns would then be cleaned up by the existing `StateMachineDeflattener` pass.

**Complexity:** Medium-high. Requires implementing the arrow-set data structures and block builder, but can reuse Vineflower's expression tree and code generator for the output phase.

### 2. Dispatcher Insertion at Bytecode Level (medium value, medium feasibility)

**What:** A new deobfuscator pass that detects irreducible control flow at the bytecode level and inserts dispatcher variables BEFORE Vineflower tries to build its IR.

**When it runs:** In Stage 1 (PRE_DECOMPILE), after all other deobfuscation passes.

**How it works:**
1. Run ASM Analyzer on each method
2. If analysis fails (irreducible flow detected), identify the problematic region
3. Insert a synthetic local variable (`int __dispatch`)
4. Replace the irreducible jumps with assignments to `__dispatch` + a LOOKUPSWITCH
5. The method now has reducible control flow that Vineflower can handle normally

**Integration point:** New method `insertDispatchers()` in the deobfuscation convergence loop, after `verifyAndFixExceptionTable()`.

**Expected impact:** Converts irreducible methods into reducible ones. Vineflower's standard decompilation then succeeds. The `StateMachineDeflattener` cleans up the dispatcher pattern in the AST.

**Complexity:** Medium. The hard part is correctly identifying the minimal irreducible region and choosing the right dispatch points. Too aggressive and you get unnecessary dispatchers; too conservative and methods still fail.

### 3. Arrow-Set Block Boundary Detection (lower value, easy)

**What:** Use arrow-set range queries to improve the deobfuscator's basic block detection, replacing the current linear scan.

**When it runs:** In `reorderBasicBlocksImpl()`.

**How it works:**
1. Build arrow set from all jumps in the method
2. Find split gaps (natural block boundaries)
3. Use these for block reordering instead of the current heuristic-based approach

**Integration point:** Replace the block boundary detection in `reorderBasicBlocksImpl()` (lines 4032-4237 of FDRSDeobfuscator).

**Expected impact:** Better block ordering → more constants adjacent to their consumers → more opaque predicates resolved in fewer rounds. Marginal but cumulative improvement.

**Complexity:** Low. The arrow-set construction is straightforward; the main work is building the segment tree for efficient range queries.

---

## Multi-Engine Architecture — Corpseflower's Differentiator

No single decompilation algorithm is best on all inputs. Corpseflower's approach is to run multiple recovery strategies per method and keep the best output. This is what makes it fundamentally different from Vineflower, CFR, or any single-algorithm decompiler.

### Per-Method Multi-Strategy Pipeline

```
For each method in each class:

  ┌─────────────────────────────────────────────────┐
  │ Strategy 1: VINEFLOWER (primary)                │
  │   CFG → dominator tree → structured statements  │
  │   Best on: clean code, modern Java features     │
  │   Fails on: irreducible flow, ZKM damage        │
  └──────────────┬──────────────────────────────────┘
                 │ output + quality score
                 ▼
  ┌─────────────────────────────────────────────────┐
  │ Strategy 2: ARROW-SET RECOVERY (purplesyringa)  │
  │   Linearized bytecode → arrow sets → blocks     │
  │   Best on: irreducible flow, goto-heavy code    │
  │   Handles what Vineflower rejects               │
  │   Only runs if Strategy 1 produced stubs        │
  └──────────────┬──────────────────────────────────┘
                 │ output + quality score
                 ▼
  ┌─────────────────────────────────────────────────┐
  │ Strategy 3: DISPATCHER SYNTHESIS                │
  │   Detect irreducible regions → insert synthetic │
  │   switch(state) → re-run Vineflower on the      │
  │   now-reducible bytecode                        │
  │   Only runs if Strategies 1-2 both have stubs   │
  └──────────────┬──────────────────────────────────┘
                 │ output + quality score
                 ▼
  ┌─────────────────────────────────────────────────┐
  │ Strategy 4: CFR BENCHMARK (regression check)    │
  │   Embedded CFR on same deobfuscated bytecode    │
  │   If CFR beats all above → that's a bug to fix  │
  │   Never used in production output (goal: 0)     │
  └──────────────┬──────────────────────────────────┘
                 │
                 ▼
  ┌─────────────────────────────────────────────────┐
  │ QUALITY GATE: Pick best output per method       │
  │   Score: completeness > readability > conciseness│
  │   Annotate with provenance                      │
  │   Flag CFR wins as Corpseflower bugs            │
  └─────────────────────────────────────────────────┘
```

### Why This Works

- **Strategy 1 (Vineflower)** handles ~95% of methods perfectly. It produces the cleanest, most readable output when it succeeds. No reason to run anything else on clean code.
- **Strategy 2 (arrow-set)** handles irreducible control flow that Vineflower rejects. Instead of giving up, it finds block structure through range queries on linearized code. The dispatcher mechanism localizes irreducible regions instead of propagating the failure.
- **Strategy 3 (dispatcher synthesis)** is a hybrid: modify the bytecode to make it reducible, then let Vineflower's superior expression/type/SSA analysis handle the rest. Leverages Vineflower's strengths while working around its weakness.
- **Strategy 4 (CFR)** exists only to catch regressions. If CFR ever scores higher than all three Corpseflower strategies on any method, that method gets logged as a defect for the next Corpseflower iteration.

### Quality Scoring Per Method

```java
int score(String methodSource) {
    // Hard failures — score 0
    if (contains "$VF: Couldn't be decompiled") return 0;
    if (contains "Exception decompiling") return 0;
    if (contains "This method has failed to decompile") return 0;

    int score = 1000;

    // Decompiler artifacts (penalty)
    score -= countDecompilerWhileTrue(methodSource) * 20;   // ZKM-induced, not legitimate
    score -= countOpaqueRemnants(methodSource) * 30;        // % 2 patterns in switch/if
    score -= countGotoLabels(methodSource) * 15;            // label/goto artifacts
    score -= countEmptyMethodBodies(methodSource) * 100;    // stub bodies

    // Readability (bonus for cleaner code)
    score -= maxNestingDepth(methodSource) * 5;             // deep nesting = harder to read
    score += hasProperVariableNames(methodSource) ? 50 : 0; // named vs var0/var1

    return score;
}
```

The key: `countDecompilerWhileTrue()` distinguishes ZKM-induced `while(true){switch}` from legitimate infinite loops (server loops, event loops) by checking if the loop body is a switch on a variable that gets assigned constants.

### Integration Points in Vineflower

Strategy 2 (arrow-set) needs to produce output that Vineflower's code generator can consume. Two options:

**Option A: Produce a Statement tree.** Arrow-set recovery outputs `SequenceStatement`, `IfStatement`, `DoStatement`, `SwitchStatement` objects. Vineflower's `ClassWriter` traverses these to generate Java source. This requires understanding Vineflower's statement/expression AST well but produces native-quality output.

**Option B: Produce Java source directly.** Arrow-set recovery generates Java source text independently. Simpler to implement but loses Vineflower's formatting, variable naming, and type inference.

**Recommendation:** Option A for full integration. The arrow-set algorithm's block tree maps naturally to Vineflower's Statement tree:
- Split gap → SequenceStatement boundary
- Forward arrow → IfStatement or break/continue
- Backward arrow → DoStatement (loop)
- Dispatcher block → SwitchStatement

The expression content within each block comes from Vineflower's existing expression parser — only the control flow structure is replaced.

---

## What We Cannot Take

### Full decompiler replacement
The algorithm covers ONLY control flow recovery. It has no:
- Exception handling (`try-catch-finally`)
- Expression reconstruction (stack → tree)
- SSA/SSAU variable analysis
- Type inference
- Lambda/inner class handling
- Java language feature support (generics, records, sealed classes, etc.)

Vineflower handles all of these well. Replacing Vineflower's core with purplesyringa's approach would require reimplementing 95% of a decompiler from scratch for a 5% improvement in control flow.

### The full O(n log n) segment tree implementation
The blog describes a segment tree + interval tree for logarithmic arrow queries. For our use case (methods with hundreds to low thousands of instructions), a simpler O(n²) brute-force approach is fast enough. The algorithmic optimization matters for methods with millions of instructions, which we don't have.

---

## Implementation Sequence

All three phases should be implemented. They are complementary, not alternatives. Each addresses a different part of the problem.

### Phase 1: Dispatcher Insertion at Bytecode Level (first — unblocks everything)

This is the foundation because:
- It works within the existing architecture (deobfuscator pass, not decompiler modification)
- It converts irreducible flow to reducible flow, which benefits ALL downstream processing
- The `StateMachineDeflattener` already handles the output pattern
- Testable independently: run deobfuscator, check if Analyzer now succeeds on previously-failing methods
- Even if Phases 2-3 are never implemented, Phase 1 alone eliminates many failures

```
New pass in deobfuscation convergence loop:

    verifyAndFixExceptionTable(cn, mn);
    insertDispatchers(cn, mn);          // NEW
    removeNops(mn);
```

Implementation sketch:
```java
static void insertDispatchers(ClassNode cn, MethodNode mn) {
    // Only run on methods that fail Analyzer verification
    if (analyzerSucceeds(cn, mn)) return;

    // Build arrow set: all jumps + exception handler entries
    List<Arrow> arrows = buildArrowSet(mn);

    // Find irreducible regions: arrows that cross each other
    // (arrow A goes forward over arrow B's start, arrow B goes backward over A's start)
    List<IrreducibleRegion> regions = findIrreducibleRegions(arrows);

    if (regions.isEmpty()) return; // Not irreducible, something else is wrong

    for (IrreducibleRegion region : regions) {
        // Allocate a new local variable for the dispatcher
        int dispatchVar = mn.maxLocals++;

        // At each entry point to the region, insert:
        //   ICONST <state_id>; ISTORE dispatchVar
        // Replace the entry jumps with jumps to a new dispatch block:
        //   ILOAD dispatchVar; LOOKUPSWITCH { 0: entry0, 1: entry1, ... }

        insertDispatchBlock(mn, region, dispatchVar);
    }

    // Re-verify — if still failing, the region identification was wrong
    if (!analyzerSucceeds(cn, mn)) {
        // Revert changes (or just leave them — the method was broken anyway)
    }
}
```

### Phase 2: Arrow-Set Fallback Decompilation Pass (second — catches what Phase 1 misses)

For methods where dispatcher insertion doesn't fully resolve the irreducibility (e.g., exception handler interactions, complex multi-entry loops):

- Register as an `AT_END` pass in Vineflower
- Scan all methods in the output for `$VF` failure markers
- For each failed method, re-analyze the bytecode with arrow-set block construction
- Produce a Statement tree (Option A from the multi-engine architecture above)
- Vineflower's code generator emits Java source from the replacement tree
- If arrow-set also fails, the method stays as-is (CFR benchmark catches it)

This is the most technically ambitious phase — it requires building a partial decompiler that produces Vineflower-compatible AST nodes. But it's also the phase with the most potential payoff, because it handles cases that NO existing decompiler gets right.

### Phase 3: Arrow-Set Block Boundary Detection (third — improves everything)

Replace the heuristic block detection in the deobfuscator with arrow-set range queries:

- Better block ordering → constants adjacent to consumers → fewer opaque predicate rounds
- Better split gap detection → cleaner basic block boundaries → less work for Vineflower
- The arrow-set data structures built in Phase 2 can be reused here

This isn't just an optimization — it feeds back into Phase 1 by producing cleaner bytecode for the dispatcher insertion pass. The three phases form a reinforcing loop:

```
Phase 3 (better blocks) → Phase 1 (better dispatchers) → Phase 2 (better fallback) → cleaner output
```

---

## Data Structures Needed

### Arrow
```java
record Arrow(int from, int to, ArrowType type) {
    enum ArrowType { GOTO, CONDITIONAL, EXCEPTION_HANDLER, SWITCH_CASE }
    boolean isForward() { return to > from; }
    boolean isBackward() { return to < from; }
    boolean crosses(int position) {
        return (from < position && position < to) || (to < position && position < from);
    }
}
```

### IrreducibleRegion
```java
record IrreducibleRegion(int start, int end, List<Integer> entryPoints) {}
```

### ArrowSet (simplified — no segment tree needed for our scale)
```java
class ArrowSet {
    List<Arrow> arrows;

    /** Find split gaps in range [l, r] — positions where no arrow crosses */
    List<Integer> findSplitGaps(int l, int r) {
        List<Integer> gaps = new ArrayList<>();
        for (int pos = l + 1; pos < r; pos++) {
            boolean crossed = false;
            for (Arrow a : arrows) {
                if (a.crosses(pos)) { crossed = true; break; }
            }
            if (!crossed) gaps.add(pos);
        }
        return gaps;
    }

    /** Find irreducible regions — where forward and backward arrows collide */
    List<IrreducibleRegion> findIrreducibleRegions() {
        // Two arrows are "colliding" if they cross each other:
        // Arrow A [a1→a2] and Arrow B [b1→b2] collide if
        // a1 < b1 < a2 < b2 (interleaving ranges)
        // This creates a region where neither arrow can be cleanly nested
        ...
    }
}
```

---

## Success Metrics

| Metric | Before | After Phase 1 | After Phase 2 |
|---|---|---|---|
| `$VF` failures (hmi.host) | 22 | <5 | 0 |
| `$VF` failures (all 30 JARs) | ~50 | <10 | 0 |
| CFR quality gate triggers | ~20 classes | <5 | 0 |
| Methods with dispatcher patterns | 0 | ~20 (inserted) | ~20 (then deflattened) |

---

## References

- Original blog post: https://purplesyringa.moe/blog/recovering-control-flow-structures-without-cfgs/
- Vineflower ARCHITECTURE.md (in repo): describes current CFG-based approach
- `StateMachineDeflattener.java`: existing pass that handles dispatcher patterns in the AST
- `verifyAndFixExceptionTable()`: existing pass that identifies methods with irreducible flow (Analyzer failure = likely irreducible)
- `reorderBasicBlocksImpl()`: existing block reordering that would benefit from arrow-set boundary detection

---

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Dispatcher insertion creates worse output than the original failure | Only insert on methods that already fail. Worse-than-nothing is impossible when the current output is a stub. |
| Incorrect irreducible region detection | Validate with ASM Analyzer after insertion. If still failing, revert. |
| Dispatcher patterns not cleaned up by StateMachineDeflattener | The deflattener already handles `while(true){switch(state)}`. May need to extend it to handle the specific shape dispatcher insertion creates. |
| Performance overhead on large JARs | Only runs on Analyzer-failing methods (tiny fraction of total). Arrow-set construction is O(n) per method. |
| Algorithm is unproven at scale | We're applying it to a narrow problem (ZKM-damaged methods, not general decompilation). The narrow scope reduces risk. |

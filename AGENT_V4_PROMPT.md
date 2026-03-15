# Corpseflower V4 Agent — Vineflower Decompiler Fixes

You are improving **Corpseflower**, a custom Vineflower fork at `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\`. The deobfuscation pipeline is working correctly. The remaining quality gap is in Vineflower's decompiler — specific control flow reconstruction failures on ZKM-damaged bytecode.

---

## CURRENT STATE

On the hardest JAR (`com.ford.fdt.hmi.host-73.18.21.jar`, 283 classes):
- 19 stub markers, 22 files containing `$VF:`
- 0 "Exception decompiling" stubs
- Deobfuscation metrics match standalone (strings, predicates, try-catch all correct)

On all 30 obfuscated JARs:
- 93 total `$VF` stubs
- 0 "Exception decompiling"
- 15 parsing failures

**The deobfuscation is NOT the problem.** Feeding standalone-deobfuscated bytecode to Corpseflower produces worse results (27 stubs) than Corpseflower's own deobfuscation (19 stubs). The bottleneck is Vineflower's control flow analysis.

---

## THREE FAILURE CATEGORIES TO FIX

### Category 1: FlattenStatementsHelper.setEdges() — "Could not find destination nodes"

**Symptom:** `IllegalStateException: Could not find destination nodes for stat id {If}:45 from source 45_case`

**Affected methods (examples):**
- `ApplicationHostHeader.concernMenuButtonClicked()`
- `ApplicationHostHeader.updateUser()`

**Root cause:** After ZKM deobfuscation, some statement edges point to nodes that were removed during earlier processing passes (dead code elimination, exception handler cleanup). The `setEdges()` method iterates flattened statement nodes and tries to resolve edge targets. When a target node doesn't exist in the flattened set, it throws.

**File:** `src/org/jetbrains/java/decompiler/modules/decompiler/FlattenStatementsHelper.java`

**Fix approach:** Instead of throwing when a destination node can't be found, handle it gracefully:
1. Find `setEdges()` — locate where it throws the `IllegalStateException`
2. When a destination is missing, check if the edge can be safely dropped (e.g., exception edges to removed handlers) or redirected to the nearest valid successor
3. Log a warning instead of throwing, so the method still decompiles (possibly with a minor control flow inaccuracy rather than complete failure)
4. Be conservative — only skip edges where the source is an exception edge or a break/continue to a removed loop. Don't silently drop regular control flow edges.

**Important:** This fix must not break clean code. The destination-not-found case should only occur on deobfuscated bytecode where dead code removal left dangling edges. Test on clean JARs after any change.

---

### Category 2: DomHelper.parseGraph() — "parsing failure!"

**Symptom:** `RuntimeException: parsing failure!`

**Affected methods (examples):**
- `LoginScreen.startLoginThread()`
- `LoginScreen.checkTokenAndLoginIfValid()`
- `CommsServicesReadPCM.readRPMDIDService()`
- `SuperModuleController.enterDiagnosticSession()`
- `DiagSessionSecurityManager.requestSecuritySFD()`
- `FilterConfiguration.requestSecuritySFD()`
- `HookupData.getHookupScreenForGenericToolBox()`

**Root cause:** DomHelper builds a dominator tree from the control flow graph and tries to match it against structured Java constructs (if/else, while, for, try-catch). When the CFG has irreducible regions (multiple entry points into a loop created by ZKM), the algorithm can't find a valid decomposition.

The irreducible split budget was already increased from 5 to 12 in `DomHelper.java` (`MAX_IRREDUCIBLE_SPLITS`). These 7+ methods exceed even the 12-split budget.

**File:** `src/org/jetbrains/java/decompiler/modules/decompiler/decompose/DomHelper.java`

**Fix approaches (try in order):**

**Approach A: Increase budget further (quick test)**
Try `MAX_IRREDUCIBLE_SPLITS = 24` or `32`. Some methods may just need more splits. This is brute force but safe — it only affects methods that already fail at the current budget.

**Approach B: Graceful degradation instead of throwing**
When the parsing fails after exhausting the split budget, instead of throwing `RuntimeException("parsing failure!")`, emit the method body as a structured comment with the bytecode listing:
```java
// $VF: Method could not be fully structured (irreducible control flow)
// Original bytecode available in class file
```
This way the class file still decompiles (other methods succeed) and only the irreducible method gets a stub. Currently the exception may propagate and kill the entire class.

**Approach C: Pre-decomposition dispatcher insertion**
Before DomHelper runs, detect irreducible regions in the CFG and insert synthetic dispatcher variables to make the flow reducible. This is the most impactful fix but also the most complex:

1. In `MethodProcessor.java`, before `DomHelper.parseGraph()` is called, analyze the CFG for irreducible regions
2. An irreducible region has multiple entry points — two or more basic blocks that are loop headers but neither dominates the other
3. For each irreducible region, introduce a synthetic `int __dispatch` variable
4. At each entry point, insert an assignment: `__dispatch = N`
5. Replace the entry edges with a single entry through a `switch(__dispatch)` block
6. The CFG is now reducible — DomHelper can handle it
7. Later passes (ConstantExpressionFolder, StateMachineDeflattener) clean up the dispatcher if possible

**Implementation sketch for Approach C:**
```java
// In MethodProcessor.java, after DeadCodeHelper.mergeBasicBlocks(graph):
if (hasIrreducibleFlow(graph)) {
    insertDispatchers(graph);
}
```

The irreducible flow detection can reuse Vineflower's existing `GenericDominatorEngine`:
```java
static boolean hasIrreducibleFlow(ControlFlowGraph graph) {
    // Build dominator tree
    // For each back edge (A→B where B dominates A), check if B has
    // multiple predecessors not dominated by B — that's irreducible
}
```

Read `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\PURPLESYRINGA_INTEGRATION.md` for the full theoretical background on dispatcher insertion and arrow-set control flow recovery. The relevant section is "Phase 1: Dispatcher Insertion at Bytecode Level".

---

### Category 3: Constructor Resugaring — "$VF: Unable to resugar constructor"

**Symptom:** `// $VF: Unable to resugar constructor` in decompiled output

**Affected methods (examples):**
- `ApplicationManagerView` constructor
- `SelectVcmiiDevicePopuputil.applyNewSettings()`
- `VehicleCommunicationSettingsPopup.applyNewSettings()`
- `VciStorageFileCheckPopUp` constructor

**Root cause:** Vineflower's `InvocationExprent.java` tries to collapse the pattern `new X(); x.<init>(args)` into `new X(args)`. After ZKM deobfuscation, the constructor call pattern may be non-standard — the `NEW` and `<init>` instructions might be separated by additional instructions (opaque predicate cleanup residue), or the variable assignments might be reordered.

**File:** `src/org/jetbrains/java/decompiler/modules/decompiler/exps/InvocationExprent.java`

**Fix approach:** This is the lowest priority of the three categories because the method body IS decompiled — only the constructor call isn't pretty. The `$VF` marker is cosmetic.

However, these false `$VF` markers inflate the stub count and trigger unnecessary CFR quality gate comparisons. To fix:

1. Find where `InvocationExprent` emits the `$VF: Unable to resugar constructor` comment
2. Check what pattern matching it does to find the `NEW` + `<init>` pair
3. Make the pattern matching more tolerant:
   - Allow NOPs or dead instructions between `NEW` and `DUP`
   - Allow the `NEW` result to be stored in a local variable before `<init>` is called (ZKM sometimes separates them)
   - Handle the case where the constructor arguments include expressions that were simplified by the deobfuscator (register field accesses → local variable reads)
4. If resugaring truly can't be done, emit the code WITHOUT the `$VF` marker — the raw `new X(); x.<init>(args)` form is valid Java-like pseudocode and doesn't need a failure comment

**Alternative quick fix:** Just remove the `$VF` comment emission for constructor resugaring failures. The code works, it's just ugly. Don't count it as a failure.

---

## PRIORITY ORDER

1. **Category 2 (DomHelper) — Approach A first** (increase MAX_IRREDUCIBLE_SPLITS). Takes 30 seconds. If it eliminates some failures, great. Then try Approach B (graceful degradation) for the rest.

2. **Category 1 (FlattenStatementsHelper)** — Graceful edge handling. This is a targeted fix that prevents cascading class-level failures.

3. **Category 3 (Constructor resugaring)** — Quick fix: suppress the `$VF` marker for constructor resugaring, or make the pattern matching more tolerant.

4. **Category 2 Approach C (Dispatcher insertion)** — Only if Approaches A+B don't eliminate enough failures. This is the heaviest lift but the most fundamentally correct solution.

---

## VALIDATION

### Build
```bash
cd "C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower"
./gradlew shadowJar
```

### Test on hardest JAR (quick validation after each change)
```bash
CF_JAR="build/libs/corpseflower-1.0.0-SNAPSHOT-all.jar"
cp "C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\OBFUSCATED\com.ford.fdt.hmi.host-73.18.21.jar" /c/tmp/cf_v4_input.jar

java -jar "$CF_JAR" --verbose /c/tmp/cf_v4_input.jar /c/tmp/cf_v4_output/ 2>&1 | tee /c/tmp/cf_v4.log

echo "Files: $(find /c/tmp/cf_v4_output -name '*.java' | wc -l)"
echo "VF stubs: $(grep -rl '\$VF:' /c/tmp/cf_v4_output --include='*.java' | wc -l)"
echo "Parsing failures: $(grep -c 'parsing failure' /c/tmp/cf_v4.log)"
echo "Destination node failures: $(grep -c 'Could not find destination' /c/tmp/cf_v4.log)"
echo "Resugar failures: $(grep -c 'Unable to resugar' /c/tmp/cf_v4.log)"
```

**Current baseline (beat this):**
- 283 files, 22 `$VF` stubs, multiple parsing failures

**Target:**
- 283 files, <5 `$VF` stubs (ideally 0), 0 parsing failures, 0 destination node failures

### Full 30-JAR batch (after hard JAR passes)
```bash
mkdir -p /c/tmp/cf_v4_batch_input /c/tmp/cf_v4_batch_output
cp "C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\OBFUSCATED\"*.jar /c/tmp/cf_v4_batch_input/
java -jar "$CF_JAR" --verbose /c/tmp/cf_v4_batch_input/ /c/tmp/cf_v4_batch_output/ 2>&1 | tee /c/tmp/cf_v4_batch.log

echo "Total VF stubs: $(grep -rl '\$VF:' /c/tmp/cf_v4_batch_output --include='*.java' | wc -l)"
echo "Total parsing failures: $(grep -c 'parsing failure' /c/tmp/cf_v4_batch.log)"
echo "Total Java files: $(find /c/tmp/cf_v4_batch_output -name '*.java' | wc -l)"
```

**Target for 30 JARs:**
- VF stubs: <15 (was 93)
- Parsing failures: 0 (was 15)
- Java files: 1700+

### Regression check on clean JARs
```bash
mkdir -p /c/tmp/cf_v4_clean
cp "C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\CLEAN\com.ford.fdrs.core.common-11.10.6.jar" /c/tmp/cf_v4_clean_input.jar
java -jar "$CF_JAR" /c/tmp/cf_v4_clean_input.jar /c/tmp/cf_v4_clean_output/
echo "Clean stubs: $(grep -rl '\$VF:' /c/tmp/cf_v4_clean_output --include='*.java' | wc -l)"
# Must be 0
```

---

## FILES AND DIRECTORIES

- **Corpseflower repo:** `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\`
- **Key source files:**
  - `src/org/jetbrains/java/decompiler/modules/decompiler/FlattenStatementsHelper.java` (Category 1)
  - `src/org/jetbrains/java/decompiler/modules/decompiler/decompose/DomHelper.java` (Category 2)
  - `src/org/jetbrains/java/decompiler/modules/decompiler/exps/InvocationExprent.java` (Category 3)
  - `src/org/jetbrains/java/decompiler/main/rels/MethodProcessor.java` (dispatcher insertion point)
  - `src/org/jetbrains/java/decompiler/modules/decompiler/deobfuscator/ExceptionDeobfuscator.java` (already has ZKM-specific fixes)
- **Purplesyringa design doc:** `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\PURPLESYRINGA_INTEGRATION.md`
- **Test JARs:** `C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\OBFUSCATED\`
- **Clean JARs:** `C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\CLEAN\`
- **Previous test logs:** `/c/tmp/cf_v3_single.log`, `/c/tmp/cf_v2_batch.log`
- **Old pipeline baseline:** `C:\tmp\obf_decompiled_v2\`

## PATH HANDLING
- Shell is bash (Git Bash/MSYS2) — use Unix syntax
- Java needs Windows paths — use `cygpath -w`
- Build: `./gradlew shadowJar`
- Java 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\`

---

## CRITICAL NOTES

1. **Test after EVERY change.** Build, run on `hmi.host`, check stub count. Don't accumulate changes.

2. **Clean JAR regression is a hard stop.** If any change introduces `$VF` stubs on clean (non-obfuscated) JARs, revert immediately.

3. **DomHelper Approach A is 30 seconds of work.** Change `MAX_IRREDUCIBLE_SPLITS = 12` to `24` and test. Do this first before anything else.

4. **Don't modify the deobfuscator.** `LegacyFdrsDeobfuscator.java` is working correctly. All fixes should be in Vineflower's decompiler code.

5. **The StateMachineDeflattener is a TODO.** It detects `while(true){switch(state)}` patterns but doesn't replace them yet. Don't try to fix it in this round — it's a separate effort.

6. **Some `$VF` markers are cosmetic.** Constructor resugaring failures have the method body intact. They look like failures but aren't. Distinguishing real failures (empty method body) from cosmetic ones (body present but `$VF` comment added) matters for accurate stub counting.

7. **CFR also fails on the hardest methods.** The quality gate shows CFR producing "Invalid stack depths" on the same methods Vineflower fails on. These are genuinely hard methods that need dispatcher insertion (future work), not just better decompiler heuristics. Don't spin on making Vineflower handle them if the CFG is irreducible — log them and move on to wins elsewhere.

# Corpseflower V3 Agent — Fix Deobfuscation Regression

You are fixing a critical regression in **Corpseflower**, a custom Vineflower fork at `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\`. The deobfuscation stage was recently moved from an external temp-JAR process to an in-memory pipeline. The in-memory path produces significantly worse deobfuscation results than the standalone tool it was ported from.

---

## THE PROBLEM

The in-memory deobfuscation decrypts only 69 of 180 strings (62% regression), simplifies 27% fewer opaque predicates, and removes 15% fewer fake try-catch blocks compared to the standalone `FDRSDeobfuscator v2.9` on the exact same 30 input JARs.

| Metric | Standalone v2.9 (target) | Corpseflower v2 (broken) | Delta |
|---|---|---|---|
| Strings decrypted | **180** | 69 | **-62%** |
| Predicates simplified | **~174,000** | 127,760 | **-27%** |
| Fake try-catch removed | **~230,000** | 195,961 | **-15%** |
| VF stubs in output | **4** | 93 | **+89** |
| Exception decompiling stubs | **152** (but CFR handles) | 0 | Better |
| Parsing failures | **0** | 15 | **+15** |

This regression causes 93 `$VF: Couldn't be decompiled` stubs and 15 parsing failures in the final output. **Fix the deobfuscation regression and these downstream problems largely disappear.**

---

## ROOT CAUSE

The standalone `FDRSDeobfuscator.processJar()` (lines 258-620 of `LegacyFdrsDeobfuscator.java`) does string decryption by:

1. Creating a `URLClassLoader` from the **input JAR file path** (line ~1487):
   ```java
   URL jarUrl = new File(inputPath).toURI().toURL();
   URLClassLoader cl = new URLClassLoader(new URL[]{jarUrl}, ...);
   ```
2. Loading ZKM decryptor classes from the JAR into the classloader
3. Invoking decryptor methods via reflection to recover encrypted strings
4. Building synthetic classes to resolve per-class opaque predicates

The in-memory deobfuscation path (`DeobfuscationStage.maybeRunInMemory()`) provides class bytes as a `Map<String, byte[]>` but the string decryption code still needs a **real JAR file on disk** to construct the `URLClassLoader`. If the JAR path is null, empty, or not passed through correctly, classloader-based string decryption silently fails — `decryptStrings()` catches exceptions and continues, producing 69 strings instead of 180.

The predicate regression (174K→128K) is a cascade: fewer decrypted strings → fewer resolved opaque predicate methods → fewer constants available → fewer predicates simplified → more damaged bytecode survives → more VF stubs.

---

## FILES TO EXAMINE AND FIX

### Primary files (the in-memory deobfuscation path):

1. **`src/org/corpseflower/deobfuscation/DeobfuscationStage.java`** — Contains `maybeRunInMemory()`. Check:
   - Is it passing the original `inputJar` Path to the deobfuscator?
   - Does the deobfuscator receive a valid JAR file path for classloader construction?
   - The original JAR is still on disk — the in-memory path should use it for classloading while operating on byte arrays for bytecode transforms.

2. **`src/org/corpseflower/deobfuscation/LegacyFdrsDeobfuscator.java`** — The ported deobfuscator. Check:
   - The `processClassBytes()` or equivalent in-memory entry point — does it call `decryptStrings()` with a valid JAR path?
   - `decryptStrings()` (around line ~1471) — trace how `inputPath` is used. It constructs a `URLClassLoader` from this path.
   - `populateCachesFromJar()` (around line ~724) — also uses the JAR path for reflection-based cache population.
   - `buildSyntheticClass()` — builds classes for per-class opaque predicate resolution. Needs the classloader.
   - Compare the in-memory entry point vs `processJar()` (the original JAR-based entry) — what steps does the in-memory path skip?

3. **`src/org/corpseflower/deobfuscation/CorpseflowerPreDecompilePass.java`** — The PRE_DECOMPILE pass that invokes deobfuscation. Check:
   - It has `ctx.getInputJar()` which should be the original JAR path.
   - Is this path being forwarded to `DeobfuscationStage.maybeRunInMemory()`?
   - Is it being forwarded further to the deobfuscator's string decryption?

4. **`src/org/corpseflower/deobfuscation/PreDecompileContext.java`** — Holds the `inputJar` path. Verify it's populated.

### Reference file (the working standalone version):

5. **`C:\Users\JResp\Desktop\New folder (24)\tools\deobfuscator\FDRSDeobfuscator.java`** — The original standalone v2.9 (4,341 lines). This is the gold standard. Every pass in this file must execute identically in the Corpseflower in-memory path.

### Supporting Vineflower files (for context):

6. **`src/org/jetbrains/java/decompiler/struct/StructContext.java`** — `getOwnClassBytesSnapshot()` provides the byte arrays. `replaceOwnClasses()` installs deobfuscated bytes. Check that the snapshot includes ALL classes in the JAR.

7. **`src/org/jetbrains/java/decompiler/main/Fernflower.java`** — The PRE_DECOMPILE hook point. Check that `CorpseflowerPreferences.INPUT_PATH` is set correctly before the hook fires.

---

## THE FIX

The fix should ensure the in-memory deobfuscation path has access to the original JAR file for classloader-based string decryption. Specifically:

### Option A: Pass the JAR path through (simplest)

Ensure `DeobfuscationStage.maybeRunInMemory()` receives the original `inputJar` path and passes it to the deobfuscator. The deobfuscator uses the JAR path ONLY for `URLClassLoader` construction in `decryptStrings()`. All bytecode transforms operate on the in-memory byte arrays.

The call chain should be:
```
CorpseflowerPreDecompilePass.run(ctx)
  → ctx.getInputJar()  // original JAR path on disk
  → DeobfuscationStage.maybeRunInMemory(inputJar, classBytesMap, ...)
    → LegacyFdrsDeobfuscator.processInMemory(classBytesMap, inputJarPath, ...)
      → decryptStrings(classes, rawClassBytes, inputJarPath, verbose)
        → new URLClassLoader(new URL[]{inputJarPath.toUri().toURL()}, ...)
```

### Option B: Write a temp JAR for classloading (if Option A doesn't work)

If the in-memory class bytes differ from the on-disk JAR (because Vineflower's loading process transforms them), write a temporary JAR from the in-memory bytes for classloader use:
```java
Path tempJar = Files.createTempFile("corpseflower-cl-", ".jar");
try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar.toFile()))) {
    for (Map.Entry<String, byte[]> entry : classBytesMap.entrySet()) {
        jos.putNextEntry(new JarEntry(entry.getKey() + ".class"));
        jos.write(entry.getValue());
        jos.closeEntry();
    }
}
// Use tempJar for URLClassLoader, then delete
```

### Option C: Build a ByteArrayClassLoader (cleanest but most work)

Create a custom classloader that loads classes directly from the in-memory byte arrays:
```java
ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classBytesMap.get(name.replace('.', '/'));
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        throw new ClassNotFoundException(name);
    }
};
```
This eliminates the need for any JAR file but requires modifying `decryptStrings()` to accept a `ClassLoader` instead of constructing its own `URLClassLoader`.

---

## SECONDARY ISSUE: Detection Scan

The batch log shows `Deobfuscated jars: 15` but all 30 JARs report `Deobfuscated: yes`. This means `needsDeobfuscation()` is triggering on all 30 (correct), but only 15 JARs are running the full deobfuscation pipeline (wrong — should be 30, matching standalone behavior).

Check: does `maybeRunInMemory()` have a different detection threshold than `maybeRun()`? The standalone path processes all 30 JARs that `needsDeobfuscation()` flags. The in-memory path should do the same.

Also check: the standalone deobfuscator's `--batch` mode auto-detects which JARs need processing and copies the rest unchanged. In Corpseflower, ALL 30 test JARs are obfuscated (they come from the OBFUSCATED folder), so all 30 should be processed. If only 15 are running full deobfuscation, the other 15 get raw obfuscated bytecode → guaranteed decompilation failures.

---

## VALIDATION

### Step 1: Build
```bash
cd "C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower"
./gradlew shadowJar
```

### Step 2: Run on all 30 obfuscated JARs
```bash
mkdir -p /c/tmp/cf_v3_input /c/tmp/cf_v3_output
cp "C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\OBFUSCATED\"*.jar /c/tmp/cf_v3_input/

CF_JAR="build/libs/corpseflower-1.0.0-SNAPSHOT-all.jar"
java -jar "$CF_JAR" --verbose /c/tmp/cf_v3_input/ /c/tmp/cf_v3_output/ 2>&1 | tee /c/tmp/cf_v3.log
```

### Step 3: Check deobfuscation metrics (MUST match standalone)
```bash
grep -E "Strings|Predicates|try-catch" /c/tmp/cf_v3.log
# MUST see:
#   Strings: 180 (not 69)
#   Predicates: ~174000 (not 127760)
#   Try-catch: ~230000 (not 195961)
```

### Step 4: Check output quality
```bash
echo "VF stubs: $(grep -rl '\$VF:' /c/tmp/cf_v3_output --include='*.java' | wc -l)"
echo "Exception decompiling: $(grep -rl 'Exception decompiling' /c/tmp/cf_v3_output --include='*.java' | wc -l)"
echo "CFR selected: $(grep -rl 'Decompiled by CFR' /c/tmp/cf_v3_output --include='*.java' | wc -l)"
echo "Java files: $(find /c/tmp/cf_v3_output -name '*.java' | wc -l)"

# Targets:
#   VF stubs: <10 (was 93, standalone has 4)
#   Exception decompiling: 0
#   Parsing failures: 0
#   Java files: ~1700+ (was 1532)
```

### Step 5: Regression check on clean JARs
```bash
mkdir -p /c/tmp/cf_v3_clean_input /c/tmp/cf_v3_clean_output
cp "C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\CLEAN\com.ford.fdrs.core.common-11.10.6.jar" /c/tmp/cf_v3_clean_input/
java -jar "$CF_JAR" /c/tmp/cf_v3_clean_input/ /c/tmp/cf_v3_clean_output/
echo "Clean JAR stubs: $(grep -rl '\$VF:' /c/tmp/cf_v3_clean_output --include='*.java' | wc -l)"
# Must be 0
```

---

## WORKING DIRECTORIES

- **Corpseflower repo:** `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\`
- **Original standalone deobfuscator (reference):** `C:\Users\JResp\Desktop\New folder (24)\tools\deobfuscator\FDRSDeobfuscator.java`
- **Test JARs:** `C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\OBFUSCATED\` (30 JARs)
- **Clean JARs:** `C:\Users\JResp\Desktop\New folder (24)\COMPLETE FDRS 47\ONLY_JAR\FORD\CLEAN\`
- **Old pipeline baseline:** `C:\tmp\obf_decompiled_v2\` (0 failures, 4 VF stubs — the target to match)
- **Previous test output:** `C:\tmp\cf_v2_batch_output\` and `C:\tmp\cf_v2_batch.log` (the broken run)
- **Recommendations doc:** `C:\Users\JResp\Desktop\New folder (24)\tools\corpseflower\AGENT_V3_RECOMMENDATIONS.md`

## PATH HANDLING
- Shell is bash (Git Bash/MSYS2) — use Unix syntax
- Java tools need Windows-native paths — use `cygpath -w`
- Classpath separator is `;` escaped as `\;` in bash
- Build: `cd tools/corpseflower && ./gradlew shadowJar`
- Java 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\`

---

## CRITICAL NOTES

1. **Do NOT restructure or refactor the deobfuscator.** The `LegacyFdrsDeobfuscator.java` is a faithful port of v2.9. The logic is correct — the problem is how the in-memory path invokes it, not the deobfuscator itself.

2. **The original JAR file is still on disk.** The in-memory path was designed to avoid writing a temp JAR for deobfuscated output. But the classloader-based string decryption still needs to read the ORIGINAL (pre-deobfuscation) JAR to load ZKM classes. This is not a contradiction — reading the original JAR for classloading is fine.

3. **`decryptStrings()` silently fails.** It catches all exceptions during string decryption and increments `stringsFailedDynamic` / `stringsFailedAnalyzer` counters. Check the verbose output for these counters to see what's failing.

4. **Test after every change.** Run on the hardest JAR first (`com.ford.fdt.hmi.host-73.18.21.jar`) — if strings decrypted goes from 0 to ~40 for that JAR, you're on the right track.

5. **The deobfuscation convergence loop order matters.** 18 sub-passes in a specific order, up to 8 rounds. Don't change the order.

6. **180 strings is across all 30 JARs.** Only 5 of the 30 JARs have ZKM string encryption. The other 25 have per-class opaque predicates but no encrypted strings. So string decryption affects a small number of JARs but those are the highest-value ones (license manager, security, auth, crypto).

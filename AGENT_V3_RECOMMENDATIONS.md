# Corpseflower V3 — Fix Recommendations

## CRITICAL: Deobfuscation Regression

The in-memory deobfuscation path produces significantly worse results than the standalone FDRSDeobfuscator v2.9:

| Metric | Standalone v2.9 | Corpseflower v2 | Delta |
|---|---|---|---|
| Strings decrypted | 180 | 69 | -62% |
| Predicates simplified | 174,000 | 127,760 | -27% |
| Fake try-catch removed | 230,031 | 195,961 | -15% |
| VF stubs in output | 4 | 93 | +89 |

This regression is the #1 cause of all remaining quality issues. Fix this and most other problems disappear.

### Root Cause: String Decryption Failure

The `decryptStrings()` method in `LegacyFdrsDeobfuscator.java` creates a `URLClassLoader` from the **JAR file path** to load ZKM decryptor classes and invoke them via reflection. When running in-memory through `DeobfuscationStage.maybeRunInMemory()`, the classloader needs either:

1. The original JAR file path (still on disk) — verify that `inputJar` path is being passed correctly to `LegacyFdrsDeobfuscator.processClassBytes()` or equivalent
2. A synthetic classloader built from the in-memory byte arrays — if the in-memory path doesn't have a JAR file to point at

**Check `DeobfuscationStage.maybeRunInMemory()`** — does it pass the original JAR path to the deobfuscator for classloader construction? The deobfuscator's `decryptStrings()` at line ~1471 does:
```java
URL jarUrl = new File(inputPath).toURI().toURL();
URLClassLoader cl = new URLClassLoader(new URL[]{jarUrl}, ...);
```

If `inputPath` is null or wrong in the in-memory path, string decryption silently fails (catches exceptions, logs failures, continues). That explains 69 instead of 180 strings — only the strings that DON'T require classloader invocation succeed.

**Fix:** Ensure the in-memory deobfuscation path passes the original `inputJar` path to the deobfuscator so classloader-based string decryption works. The original JAR is still on disk — the in-memory path should use it for classloading while operating on the byte arrays for everything else.

### Verification

After fixing, run on all 30 JARs and check:
- Strings decrypted: must be **180** (matching standalone)
- Predicates simplified: must be **~174,000**
- Fake try-catch removed: must be **~230,000**

If these numbers match, VF stubs should drop from 93 to near the standalone pipeline's 4.

## SECONDARY: Quality Gate Not Catching Stubs

The quality gate only selected 3 CFR outputs out of 93 VF stubs. This is because CFR ALSO fails on the same methods — both decompilers get bad bytecode due to the deobfuscation regression above. Fix the deobfuscation regression first, then re-evaluate.

If stubs remain after the deobfuscation fix, check the quality gate's `shouldUseCfr()` logic — any class with a `$VF:` stub where CFR produces clean output should trigger CFR selection.

## SECONDARY: 43 Methods with "couldn't be decompiled"

43 unique methods fail across the 30 JARs. After fixing the deobfuscation regression, many of these will succeed (better bytecode = Vineflower can handle it). For any that remain:

1. Check if they have `<clinit>` failures — these are often enum switch maps that the deobfuscator's `<clinit>` simulation handles. If simulation fails, the switch map isn't resolved and Vineflower can't match the enum.

2. The `parsing failure!` exceptions (15 of the 43) are Vineflower's `DomHelper` failing to build structured statements from the CFG. The DomHelper irreducible split budget was already increased from 5 to 12 — these are methods that need even more splits, or have genuinely irreducible flow that dispatchers (purplesyringa Phase 1) would fix.

## TERTIARY: Deobfuscation Only Ran on 15 of 30 JARs

The log shows `Deobfuscated jars: 15` but all 30 report `Deobfuscated: yes`. This means the `needsDeobfuscation()` detection is working differently in the in-memory path vs standalone. Check if the detection scan has access to all class bytes when running in-memory.

## Test Protocol

```bash
# After fix, verify deobfuscation metrics match standalone:
CF_JAR="build/libs/corpseflower-1.0.0-SNAPSHOT-all.jar"
java -jar "$CF_JAR" --verbose /c/tmp/cf_v2_batch_input/ /c/tmp/cf_v3_test/ 2>&1 | grep -E "Strings|Predicates|try-catch|stubs|Failures"

# Expected:
# Strings: 180 (was 69)
# Predicates: ~174000 (was 127760)
# Try-catch: ~230000 (was 195961)
# VF stubs: <10 (was 93)
# Failures: 0 (was 15)
```

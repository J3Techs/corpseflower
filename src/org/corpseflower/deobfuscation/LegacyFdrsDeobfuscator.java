package org.corpseflower.deobfuscation;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * FDRS ZKM Deobfuscator v2.9.
 *
 * v2 additions over v1:
 *   - Opaque predicate FIELD resolution (GETSTATIC static int fields → constants)
 *   - Dead store removal (PUTSTATIC to opaque fields → POP)
 *   - Per-class ZKM detection without requiring a ZKM package
 *   - Batch mode: --batch <inputDir> <outputDir> auto-detects and processes all affected JARs
 *   - Dead expression removal (const; const; arith; cast; POP chains)
 *   - Multi-round Phase 2 convergence loop
 *   - Enhanced fake try-catch detection
 *
 * v2.7 additions:
 *   - Exception handler cleanup (non-throwing ranges, dead handler locals, exact duplicates)
 *   - Pass E_CHAIN: multi-instruction constant chain folding
 *   - Pass E_ALG2: cross-block algebraic identity via Analyzer (n*(n+k)%2 == 0)
 *
 * v2.8 additions:
 *   - Pass GOTO_DEAD: remove dead GOTOs (target is next label) that block E/E_CHAIN
 *   - E_CHAIN enhanced: skips dead GOTOs in backward walk
 *   - E3 Analyzer: diagnostic logging on failure (--verbose)
 *   - Verbose diagnostics for residual constant chain blockers
 *
 * v2.9 additions:
 *   - Removed 500-insn scan limit in cleanupExceptionHandlers (was preserving dead entries in large methods)
 *   - Added GETSTATIC/PUTSTATIC/IINC to non-throwing instruction list
 *   - New pass: verifyAndFixExceptionTable() — runs ASM Analyzer post-cleanup;
 *     iteratively removes exception entries causing stack inconsistencies
 *   - Added chainsFolded/crossBlockAlgFolded/deadGotosRemoved to convergence metric
 *   - Handles handler-is-ATHROW (catch-and-rethrow) pattern more aggressively
 */
public final class LegacyFdrsDeobfuscator {

    // ── Counters ──
    static int stringsDecrypted = 0;
    static int stringsFailedDynamic = 0;
    static int stringsFailedAnalyzer = 0;
    static int flowBlocksCleaned = 0;
    static int deadInstructionsRemoved = 0;
    static int fakeTryCatchRemoved = 0;
    static int nopsRemoved = 0;
    static int opaquePredicatesSimplified = 0;
    static int opaqueMethodsRemoved = 0;
    static int opaqueFieldsRemoved = 0;
    static int zkmClassesRemoved = 0;
    static int deadExpressionsRemoved = 0;
    static int unreachableInsnsRemoved = 0;
    static int exnTableEntriesCleaned = 0;
    static int exnHandlersCleaned = 0;
    static int chainsFolded = 0;
    static int crossBlockAlgFolded = 0;
    static int analyzerFailures = 0;
    static int deadGotosRemoved = 0;
    static int exnVerifyFixed = 0;

    // ── Verbose mode (set from processJar) ──
    static boolean verboseMode = false;

    // ── Detected ZKM infrastructure ──
    static String decryptorClassName = null;
    static String zkmPackageName = null;
    static Set<String> opaquePredicateClasses = new LinkedHashSet<>();
    static Set<String> zkm2ArgMethods = new LinkedHashSet<>();
    static Set<String> zkm3ArgMethods = new LinkedHashSet<>();

    // ── Caches ──
    static Map<String, Integer> globalMethodCache = new HashMap<>();
    static Map<String, Integer> globalFieldCache = new HashMap<>();
    static Map<String, Integer> registerFieldInitialValues = new HashMap<>();
    static Set<String> uncertainSyntheticClasses = new HashSet<>();
    static Map<String, ClassNode> allClasses = null;

    static void resetState() {
        stringsDecrypted = 0;
        stringsFailedDynamic = 0;
        stringsFailedAnalyzer = 0;
        flowBlocksCleaned = 0;
        deadInstructionsRemoved = 0;
        fakeTryCatchRemoved = 0;
        nopsRemoved = 0;
        opaquePredicatesSimplified = 0;
        opaqueMethodsRemoved = 0;
        opaqueFieldsRemoved = 0;
        zkmClassesRemoved = 0;
        deadExpressionsRemoved = 0;
        unreachableInsnsRemoved = 0;
        exnTableEntriesCleaned = 0;
        exnHandlersCleaned = 0;
        chainsFolded = 0;
        crossBlockAlgFolded = 0;
        analyzerFailures = 0;
        deadGotosRemoved = 0;
        exnVerifyFixed = 0;
        verboseMode = false;
        decryptorClassName = null;
        zkmPackageName = null;
        opaquePredicateClasses = new LinkedHashSet<>();
        zkm2ArgMethods = new LinkedHashSet<>();
        zkm3ArgMethods = new LinkedHashSet<>();
        globalMethodCache = new HashMap<>();
        globalFieldCache = new HashMap<>();
        registerFieldInitialValues = new HashMap<>();
        uncertainSyntheticClasses = new HashSet<>();
        allClasses = null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("FDRS ZKM Deobfuscator v2.8");
            System.out.println("Usage: FDRSDeobfuscator <input.jar> <output.jar> [--no-strings] [--no-flow] [--verbose]");
            System.out.println("       FDRSDeobfuscator --batch <inputDir> <outputDir> [--verbose]");
            System.out.println();
            System.out.println("Passes:");
            System.out.println("  1. ZKM string decryption (dataflow analysis + classloader invoke)");
            System.out.println("  2. ZKM flow deobfuscation (fake try-catch, dead code, opaque predicates, dead expressions)");
            System.out.println("  3. Opaque predicate field/method/class removal");
            return;
        }

        // ── Batch mode ──
        if ("--batch".equals(args[0])) {
            if (args.length < 3) {
                System.err.println("Usage: FDRSDeobfuscator --batch <inputDir> <outputDir> [--verbose]");
                return;
            }
            boolean verbose = Arrays.asList(args).contains("--verbose");
            batchProcess(args[1], args[2], verbose);
            return;
        }

        // ── Single JAR mode ──
        String inputPath = args[0];
        String outputPath = args[1];
        boolean doStrings = true, doFlow = true, verbose = false;
        for (int i = 2; i < args.length; i++) {
            if ("--no-strings".equals(args[i])) doStrings = false;
            if ("--no-flow".equals(args[i])) doFlow = false;
            if ("--verbose".equals(args[i])) verbose = true;
        }

        processJar(inputPath, outputPath, doStrings, doFlow, verbose);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch Processing
    // ═══════════════════════════════════════════════════════════════════

    static void batchProcess(String inputDir, String outputDir, boolean verbose) throws Exception {
        File inDir = new File(inputDir);
        File outDir = new File(outputDir);
        if (!outDir.exists()) outDir.mkdirs();

        File[] jars = inDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            System.out.println("[FDRS] No JARs found in " + inputDir);
            return;
        }
        Arrays.sort(jars);

        int totalProcessed = 0, totalCopied = 0;
        int totalStrings = 0, totalPredicates = 0, totalFakeTry = 0, totalDeadInsns = 0;
        int totalDeadExprs = 0, totalMethods = 0, totalFields = 0, totalZkmClasses = 0;

        for (File jar : jars) {
            File outFile = new File(outDir, jar.getName());
            if (needsDeobfuscation(jar)) {
                System.out.println("\n[FDRS] ========== Processing: " + jar.getName() + " ==========");
                long jarStart = System.currentTimeMillis();
                resetState();
                processJar(jar.getAbsolutePath(), outFile.getAbsolutePath(), true, true, verbose);
                System.out.println("[FDRS] " + jar.getName() + " took " + (System.currentTimeMillis() - jarStart) + "ms");
                totalProcessed++;
                totalStrings += stringsDecrypted;
                totalPredicates += opaquePredicatesSimplified;
                totalFakeTry += fakeTryCatchRemoved;
                totalDeadInsns += deadInstructionsRemoved;
                totalDeadExprs += deadExpressionsRemoved;
                totalMethods += opaqueMethodsRemoved;
                totalFields += opaqueFieldsRemoved;
                totalZkmClasses += zkmClassesRemoved;
            } else {
                Files.copy(jar.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                totalCopied++;
            }
        }

        System.out.println("\n[FDRS] ══════════════════════════════════════");
        System.out.println("[FDRS] ═══ Batch Summary ═══");
        System.out.println("[FDRS] JARs processed:          " + totalProcessed);
        System.out.println("[FDRS] JARs copied unchanged:   " + totalCopied);
        System.out.println("[FDRS] Total JARs:              " + (totalProcessed + totalCopied));
        System.out.println("[FDRS] Total strings decrypted: " + totalStrings);
        System.out.println("[FDRS] Total predicates simplified: " + totalPredicates);
        System.out.println("[FDRS] Total fake try-catch:    " + totalFakeTry);
        System.out.println("[FDRS] Total dead insns:        " + totalDeadInsns);
        System.out.println("[FDRS] Total dead expressions:  " + totalDeadExprs);
        System.out.println("[FDRS] Total opaque methods:    " + totalMethods);
        System.out.println("[FDRS] Total opaque fields:     " + totalFields);
        System.out.println("[FDRS] Total ZKM classes:       " + totalZkmClasses);
    }

    /**
     * Quick scan: does this JAR contain any ZKM obfuscation indicators?
     * Checks field/method naming patterns without loading full bytecode.
     */
    static boolean needsDeobfuscation(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                byte[] data;
                try (InputStream is = jar.getInputStream(entry)) { data = is.readAllBytes(); }
                try {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, ClassReader.SKIP_CODE);

                    // Check for ZKM-style static int fields (per-class opaque predicates)
                    for (FieldNode fn : cn.fields) {
                        if ((fn.access & Opcodes.ACC_STATIC) != 0 && fn.desc.equals("I") &&
                            fn.name.matches(".*00[4-7][0-9a-fA-F].*")) return true;
                    }
                    // Check for ZKM-style static ()I methods (per-class opaque predicates)
                    for (MethodNode mn : cn.methods) {
                        if ((mn.access & Opcodes.ACC_STATIC) != 0 && mn.desc.equals("()I") &&
                            mn.name.matches(".*00[4-7][0-9a-fA-F].*")) return true;
                    }
                    // Check for ZKM predicate holder class (many static long fields)
                    int staticLongs = 0;
                    for (FieldNode fn : cn.fields) {
                        if ((fn.access & Opcodes.ACC_STATIC) != 0 && fn.desc.equals("J")) staticLongs++;
                    }
                    if (staticLongs >= 10) return true;
                    // Check for ZKM string decryptor signature
                    for (MethodNode mn : cn.methods) {
                        if ((mn.access & Opcodes.ACC_STATIC) != 0 && (mn.access & Opcodes.ACC_PUBLIC) != 0 &&
                            mn.desc.equals("(Ljava/lang/String;CC)Ljava/lang/String;")) return true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Single JAR Processing
    // ═══════════════════════════════════════════════════════════════════

    static void processJar(String inputPath, String outputPath,
                           boolean doStrings, boolean doFlow, boolean verbose) throws Exception {
        verboseMode = verbose;
        System.out.println("[FDRS] Loading " + inputPath);

        // ── Load all classes and resources ──
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        Map<String, byte[]> rawClassBytes = new LinkedHashMap<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();

        try (JarFile jar = new JarFile(inputPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                byte[] data;
                try (InputStream is = jar.getInputStream(entry)) {
                    data = is.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    try {
                        ClassNode cn = new ClassNode();
                        new ClassReader(data).accept(cn, 0);
                        classes.put(cn.name, cn);
                        rawClassBytes.put(cn.name, data);
                    } catch (Exception e) {
                        // v2.9: Rename encrypted/corrupt classes so decompilers don't crash on them
                        String safeName = entry.getName() + ".encrypted";
                        System.err.println("[FDRS] WARN: Encrypted class (not CAFEBABE): " + entry.getName() + " → " + safeName);
                        resources.put(safeName, data);
                    }
                } else {
                    resources.put(entry.getName(), data);
                }
            }
        }
        System.out.println("[FDRS] Loaded " + classes.size() + " classes, " + resources.size() + " resources");

        // ── Phase 0: Detect ZKM infrastructure ──
        long phaseStart = System.currentTimeMillis();
        detectZkmInfrastructure(classes);
        System.out.println("[FDRS] Phase 0 (detect): " + (System.currentTimeMillis() - phaseStart) + "ms");

        if (decryptorClassName == null) {
            System.out.println("[FDRS] No ZKM string decryptor detected. Skipping string decryption.");
            doStrings = false;
        } else {
            System.out.println("[FDRS] Detected ZKM decryptor: " + decryptorClassName.replace('/', '.'));
            System.out.println("[FDRS] Opaque predicate classes: " + opaquePredicateClasses.size());
            System.out.println("[FDRS] 2-arg decrypt methods: " + zkm2ArgMethods);
            System.out.println("[FDRS] 3-arg decrypt methods: " + zkm3ArgMethods);
        }

        // Set allClasses early so extractFieldCache/analyzeClinitFields can check for register fields
        allClasses = classes;

        // ── Phase 1: String decryption via dataflow analysis + classloader invoke ──
        if (doStrings) {
            System.out.println("\n[FDRS] === Phase 1: String Decryption ===");
            phaseStart = System.currentTimeMillis();
            decryptStrings(classes, rawClassBytes, inputPath, verbose);
            System.out.println("[FDRS] Phase 1 (strings): " + (System.currentTimeMillis() - phaseStart) + "ms");
            System.out.println("[FDRS] Decrypted " + stringsDecrypted + " strings (" +
                               stringsFailedAnalyzer + " analyzer fallbacks, " +
                               stringsFailedDynamic + " failures)");
        }

        // ── Populate opaque predicate cache if Phase 1 was skipped ──
        if (!doStrings && globalMethodCache.isEmpty()) {
            phaseStart = System.currentTimeMillis();
            populateCachesFromJar(classes, inputPath, verbose);
            System.out.println("[FDRS] Cache population: " + (System.currentTimeMillis() - phaseStart) + "ms");
        }

        // ── Phase 2: Flow deobfuscation (multi-round convergence) ──
        if (doFlow) {
            System.out.println("\n[FDRS] === Phase 2: Flow Deobfuscation ===");

            int round = 0;
            int prevTotal;
            do {
                round++;
                long roundStart = System.currentTimeMillis();
                long tFakeTry = 0, tDeadCode = 0, tOpaque = 0, tDeadExpr = 0, tUnreachable = 0, tConsolidate = 0, tCleanupExn = 0, tNops = 0, tGoto = 0, tGotoInLoop = 0, tReorder = 0, tLocalize = 0;
                prevTotal = fakeTryCatchRemoved + deadInstructionsRemoved +
                           opaquePredicatesSimplified + deadExpressionsRemoved + nopsRemoved +
                           unreachableInsnsRemoved + exnTableEntriesCleaned + exnHandlersCleaned +
                           chainsFolded + crossBlockAlgFolded + deadGotosRemoved + exnVerifyFixed;
                for (ClassNode cn : classes.values()) {
                    for (MethodNode mn : cn.methods) {
                        long t0 = System.currentTimeMillis();
                        removeFakeTryCatch(cn, mn);
                        long t1 = System.currentTimeMillis();
                        removeDeadCode(cn, mn);
                        long t2 = System.currentTimeMillis();
                        // Block reordering + goto shortening BEFORE opaque pred simplification
                        // so constants become adjacent to their consuming arithmetic ops
                        shortenGotoChains(mn);
                        long t2b = System.currentTimeMillis();
                        reorderBasicBlocksImpl(cn, mn);
                        long t2c = System.currentTimeMillis();
                        localizeRegisterFields(cn, mn);
                        long t2d = System.currentTimeMillis();
                        // v2.8: Eliminate unreachable code + ensure method termination BEFORE
                        // opaque predicates so the E3 Analyzer doesn't see dead code.
                        // Note: exception table cleanup stays AFTER simplifyOpaquePredicates
                        // to avoid removing handlers needed for opaque predicate resolution.
                        eliminateUnreachableCode(cn, mn);
                        ensureMethodTermination(mn);
                        long t2e = System.currentTimeMillis();
                        simplifyOpaquePredicates(cn, mn);
                        long t3 = System.currentTimeMillis();
                        removeDeadExpressions(cn, mn);
                        long t4 = System.currentTimeMillis();
                        eliminateUnreachableCode(cn, mn);
                        long t5 = System.currentTimeMillis();
                        consolidateExceptionTable(mn);
                        long t5b = System.currentTimeMillis();
                        cleanupExceptionHandlers(cn, mn);
                        verifyAndFixExceptionTable(cn, mn);
                        long t5c = System.currentTimeMillis();
                        removeNops(mn);
                        long t6 = System.currentTimeMillis();
                        shortenGotoChains(mn);
                        long t7 = System.currentTimeMillis();
                        tFakeTry += t1 - t0;
                        tDeadCode += t2 - t1;
                        tGotoInLoop += t2b - t2;
                        tReorder += t2c - t2b;
                        tLocalize += t2d - t2c;
                        tOpaque += t3 - t2d;
                        tDeadExpr += t4 - t3;
                        tUnreachable += t5 - t4;
                        tConsolidate += t5b - t5;
                        tCleanupExn += t5c - t5b;
                        tNops += t6 - t5c;
                        tGoto += t7 - t6;
                    }
                }
                int newTotal = fakeTryCatchRemoved + deadInstructionsRemoved +
                              opaquePredicatesSimplified + deadExpressionsRemoved + nopsRemoved +
                              unreachableInsnsRemoved + exnTableEntriesCleaned + exnHandlersCleaned +
                              chainsFolded + crossBlockAlgFolded + deadGotosRemoved + exnVerifyFixed;
                long roundMs = System.currentTimeMillis() - roundStart;
                System.out.println("[FDRS] Round " + round + ": " + (newTotal - prevTotal) + " changes (" + roundMs + "ms)");
                System.out.println("[FDRS]   fakeTry=" + tFakeTry + "ms deadCode=" + tDeadCode + "ms gotoInLoop=" + tGotoInLoop + "ms reorder=" + tReorder +
                    "ms localize=" + tLocalize + "ms opaque=" + tOpaque + "ms deadExpr=" + tDeadExpr + "ms unreachable=" + tUnreachable + "ms consolidate=" + tConsolidate + "ms cleanupExn=" + tCleanupExn + "ms nops=" + tNops + "ms goto=" + tGoto + "ms");
                if (newTotal == prevTotal) break;
            } while (round < 8);

            System.out.println("[FDRS] Removed " + fakeTryCatchRemoved + " fake try-catch blocks");
            System.out.println("[FDRS] Removed " + deadInstructionsRemoved + " dead instructions");
            System.out.println("[FDRS] Simplified " + opaquePredicatesSimplified + " opaque predicates");
            System.out.println("[FDRS] Removed " + deadExpressionsRemoved + " dead expressions");
            System.out.println("[FDRS] Removed " + unreachableInsnsRemoved + " unreachable instructions");
            System.out.println("[FDRS] Cleaned " + exnTableEntriesCleaned + " exception table entries");
            System.out.println("[FDRS] Cleaned " + exnHandlersCleaned + " exception handlers (non-throwing/dead/dup)");
            System.out.println("[FDRS] Folded " + chainsFolded + " constant chains");
            System.out.println("[FDRS] Folded " + crossBlockAlgFolded + " cross-block algebraic identities");
            System.out.println("[FDRS] Removed " + deadGotosRemoved + " dead GOTOs");
            System.out.println("[FDRS] Exception table verify fixes: " + exnVerifyFixed);
            System.out.println("[FDRS] Analyzer failures: " + analyzerFailures);
            System.out.println("[FDRS] Removed " + nopsRemoved + " NOPs");

            // Post-convergence: reorder basic blocks for all methods
            long reorderStart = System.currentTimeMillis();
            int reorderedMethods = 0;
            for (ClassNode cn : classes.values()) {
                for (MethodNode mn : cn.methods) {
                    int sizeBefore = mn.instructions.size();
                    reorderBasicBlocksImpl(cn, mn);
                    if (mn.instructions.size() != sizeBefore || sizeBefore > 0) reorderedMethods++;
                }
            }
            System.out.println("[FDRS] Block reordering: " + (System.currentTimeMillis() - reorderStart) + "ms");
        }

        // ── Phase 3: Cleanup — remove ZKM infrastructure ──
        boolean canCleanup = (doStrings && stringsFailedDynamic == 0) || decryptorClassName == null;
        if (canCleanup && zkmPackageName != null) {
            System.out.println("\n[FDRS] === Phase 3: ZKM Infrastructure Removal ===");
            phaseStart = System.currentTimeMillis();

            Set<String> zkmClasses = new LinkedHashSet<>();
            for (String name : classes.keySet()) {
                if (name.startsWith(zkmPackageName + "/")) {
                    zkmClasses.add(name);
                }
            }

            Set<String> referencedZkm = new LinkedHashSet<>();
            for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
                if (entry.getKey().startsWith(zkmPackageName + "/")) continue;
                ClassNode cn = entry.getValue();
                for (MethodNode mn : cn.methods) {
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof MethodInsnNode mi && zkmClasses.contains(mi.owner)) {
                            referencedZkm.add(mi.owner + "." + mi.name);
                        }
                        if (insn instanceof FieldInsnNode fi && zkmClasses.contains(fi.owner)) {
                            referencedZkm.add(fi.owner + "." + fi.name);
                        }
                    }
                }
            }

            if (!referencedZkm.isEmpty() && verbose) {
                System.out.println("[FDRS] WARNING: " + referencedZkm.size() + " remaining references to ZKM classes — cleaning anyway");
                for (String ref : referencedZkm) {
                    System.out.println("[FDRS]   ref: " + ref);
                }
            }

            Iterator<Map.Entry<String, ClassNode>> it = classes.entrySet().iterator();
            while (it.hasNext()) {
                String name = it.next().getKey();
                if (name.startsWith(zkmPackageName + "/")) {
                    it.remove();
                    rawClassBytes.remove(name);
                    zkmClassesRemoved++;
                }
            }

            for (ClassNode cn : classes.values()) {
                opaqueFieldsRemoved += removeOpaquePredicateFields(cn);
                removeOpaquePredicateMethods(cn);
            }
            System.out.println("[FDRS] Removed " + zkmClassesRemoved + " ZKM infrastructure classes");
            System.out.println("[FDRS] Removed " + opaqueMethodsRemoved + " opaque predicate methods from app classes");
            System.out.println("[FDRS] Removed " + opaqueFieldsRemoved + " opaque predicate fields from app classes");
            System.out.println("[FDRS] ZKM package purged: " + zkmPackageName);
            System.out.println("[FDRS] Phase 3 (cleanup): " + (System.currentTimeMillis() - phaseStart) + "ms");
        }

        // Also remove per-class opaque fields/methods for packageless bundles
        if (zkmPackageName == null) {
            int methodsBefore = opaqueMethodsRemoved;
            int fieldsBefore = opaqueFieldsRemoved;
            for (ClassNode cn : classes.values()) {
                opaqueFieldsRemoved += removeOpaquePredicateFields(cn);
                removeOpaquePredicateMethods(cn);
            }
            if (opaqueMethodsRemoved > methodsBefore || opaqueFieldsRemoved > fieldsBefore) {
                System.out.println("[FDRS] Removed " + (opaqueMethodsRemoved - methodsBefore) +
                                   " per-class opaque predicate methods, " +
                                   (opaqueFieldsRemoved - fieldsBefore) + " fields (no ZKM package)");
            }
        }

        // Clean obfuscated variable names in all classes
        int renamedVars = 0;
        for (ClassNode cn : classes.values()) {
            renamedVars += cleanObfuscatedVariableNames(cn);
        }
        if (renamedVars > 0) {
            System.out.println("[FDRS] Renamed " + renamedVars + " obfuscated local variable names");
        }

        // ── Pre-write sanitization: fix structural issues that cause ClassWriter failures ──
        int sanitized = 0;
        for (ClassNode cn : classes.values()) {
            for (MethodNode mn : cn.methods) {
                if (sanitizeMethodStructure(mn)) sanitized++;
            }
        }
        if (sanitized > 0) {
            System.out.println("[FDRS] Sanitized " + sanitized + " methods (fixed label/exception table references)");
        }

        // ── Write output ──
        phaseStart = System.currentTimeMillis();
        System.out.println("\n[FDRS] Writing " + outputPath);
        int fallbackCount = 0;
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputPath))) {
            for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
                ClassNode cn = entry.getValue();
                byte[] classBytes = null;

                // Strategy 1: COMPUTE_FRAMES with safe superclass resolution
                if (classBytes == null) {
                    try {
                        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                            @Override protected String getCommonSuperClass(String type1, String type2) {
                                try {
                                    return super.getCommonSuperClass(type1, type2);
                                } catch (Exception e) {
                                    return "java/lang/Object";
                                }
                            }
                        };
                        cn.accept(cw);
                        classBytes = cw.toByteArray();
                    } catch (Throwable ignored) {}
                }

                // Strategy 2: COMPUTE_MAXS only
                if (classBytes == null) {
                    try {
                        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        cn.accept(cw);
                        classBytes = cw.toByteArray();
                    } catch (Throwable ignored) {}
                }

                // Strategy 3: No computation at all
                if (classBytes == null) {
                    try {
                        ClassWriter cw = new ClassWriter(0);
                        cn.accept(cw);
                        classBytes = cw.toByteArray();
                    } catch (Throwable t3) {
                        System.err.println("[FDRS] Strategy 3 failed for " + cn.name + ": " + t3.getClass().getSimpleName() + ": " + t3.getMessage());
                        t3.printStackTrace(System.err);
                    }
                }

                // Strategy 4: Last resort — original bytes
                if (classBytes == null) {
                    classBytes = rawClassBytes.get(cn.name);
                    if (classBytes != null) {
                        System.err.println("[FDRS] WARN: Fell back to original bytes for " + cn.name);
                        fallbackCount++;
                    }
                }

                if (classBytes != null) {
                    jos.putNextEntry(new ZipEntry(cn.name + ".class"));
                    jos.write(classBytes);
                    jos.closeEntry();
                }
            }
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                jos.putNextEntry(new ZipEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }

        System.out.println("[FDRS] Write phase: " + (System.currentTimeMillis() - phaseStart) + "ms");
        System.out.println("\n[FDRS] === Summary ===");
        System.out.println("[FDRS] Strings decrypted:       " + stringsDecrypted);
        System.out.println("[FDRS] Analyzer fallbacks:      " + stringsFailedAnalyzer);
        System.out.println("[FDRS] String failures:         " + stringsFailedDynamic);
        System.out.println("[FDRS] Fake try-catch removed:  " + fakeTryCatchRemoved);
        System.out.println("[FDRS] Dead insns removed:      " + deadInstructionsRemoved);
        System.out.println("[FDRS] Opaque preds simplified: " + opaquePredicatesSimplified);
        System.out.println("[FDRS] Dead expressions removed:" + deadExpressionsRemoved);
        System.out.println("[FDRS] Unreachable insns:       " + unreachableInsnsRemoved);
        System.out.println("[FDRS] Exn table cleaned:       " + exnTableEntriesCleaned);
        System.out.println("[FDRS] Opaque methods removed:  " + opaqueMethodsRemoved);
        System.out.println("[FDRS] Opaque fields removed:   " + opaqueFieldsRemoved);
        System.out.println("[FDRS] ZKM classes removed:     " + zkmClassesRemoved);
        System.out.println("[FDRS] NOPs removed:            " + nopsRemoved);
        if (fallbackCount > 0) {
            System.out.println("[FDRS] ClassWriter fallbacks:   " + fallbackCount);
        }
        System.out.println("[FDRS] Done.");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 0: Detect ZKM infrastructure
    // ═══════════════════════════════════════════════════════════════════

    static void detectZkmInfrastructure(Map<String, ClassNode> classes) {
        // Pass 1: find decryptor class and opaque predicate holders
        for (ClassNode cn : classes.values()) {
            boolean hasDecryptMethod = false;

            for (MethodNode mn : cn.methods) {
                if ((mn.access & Opcodes.ACC_STATIC) != 0 &&
                    mn.desc.equals("(Ljava/lang/String;CC)Ljava/lang/String;") &&
                    (mn.access & Opcodes.ACC_PUBLIC) != 0) {
                    hasDecryptMethod = true;
                }
            }

            // Check for opaque predicate pattern: class with many static long fields
            int staticLongFields = 0;
            for (FieldNode fn : cn.fields) {
                if ((fn.access & Opcodes.ACC_STATIC) != 0 && fn.desc.equals("J")) {
                    staticLongFields++;
                }
            }

            if (staticLongFields >= 10) {
                opaquePredicateClasses.add(cn.name);
            }

            if (hasDecryptMethod) {
                decryptorClassName = cn.name;
                for (MethodNode mn : cn.methods) {
                    if ((mn.access & Opcodes.ACC_STATIC) == 0) continue;
                    if (!mn.desc.contains("Ljava/lang/String;") || !mn.desc.endsWith("Ljava/lang/String;")) continue;
                    if (mn.desc.equals("(Ljava/lang/String;CC)Ljava/lang/String;") &&
                        (mn.access & Opcodes.ACC_PUBLIC) != 0) {
                        zkm2ArgMethods.add(mn.name);
                    }
                    if (mn.desc.equals("(Ljava/lang/String;CCC)Ljava/lang/String;") &&
                        (mn.access & Opcodes.ACC_PUBLIC) != 0) {
                        zkm3ArgMethods.add(mn.name);
                    }
                }
            }
        }

        // Pass 2: identify the ZKM package
        if (decryptorClassName != null && decryptorClassName.contains("/")) {
            zkmPackageName = decryptorClassName.substring(0, decryptorClassName.lastIndexOf('/'));
        } else if (!opaquePredicateClasses.isEmpty()) {
            String first = opaquePredicateClasses.iterator().next();
            if (first.contains("/")) {
                zkmPackageName = first.substring(0, first.lastIndexOf('/'));
            }
        }

        // Pass 3: if we identified a ZKM package, mark ALL classes in it for removal
        if (zkmPackageName != null) {
            for (String name : classes.keySet()) {
                if (name.startsWith(zkmPackageName + "/")) {
                    opaquePredicateClasses.add(name);
                }
            }
        }

        // Pass 4: also detect opaque predicate classes by checking decryptor bytecode references
        if (decryptorClassName != null) {
            ClassNode decCn = classes.get(decryptorClassName);
            if (decCn != null) {
                for (MethodNode mn : decCn.methods) {
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof MethodInsnNode mi) {
                            if (mi.getOpcode() == Opcodes.INVOKESTATIC &&
                                !mi.owner.equals(decryptorClassName) &&
                                classes.containsKey(mi.owner)) {
                                opaquePredicateClasses.add(mi.owner);
                            }
                        }
                    }
                }
            }
        }

        // Pass 5: count per-class opaque predicates (for bundles without ZKM package)
        int perClassCount = 0;
        for (ClassNode cn : classes.values()) {
            if (opaquePredicateClasses.contains(cn.name)) continue;
            for (FieldNode fn : cn.fields) {
                if ((fn.access & Opcodes.ACC_STATIC) != 0 && fn.desc.equals("I") &&
                    fn.name.matches(".*00[4-7][0-9a-fA-F].*")) {
                    perClassCount++;
                    break;
                }
            }
        }
        if (perClassCount > 0) {
            System.out.println("[FDRS] Detected " + perClassCount + " classes with per-class opaque predicates");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cache Population (for bundles without string decryption)
    // ═══════════════════════════════════════════════════════════════════

    static void populateCachesFromJar(Map<String, ClassNode> classes, String jarPath, boolean verbose) {
        System.out.println("\n[FDRS] === Populating opaque predicate caches ===");
        try {
            URL jarUrl = new File(jarPath).toURI().toURL();
            URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, ClassLoader.getPlatformClassLoader());

            // Load ZKM classes (if any)
            for (String name : opaquePredicateClasses) {
                try {
                    Class<?> cls = loader.loadClass(name.replace('/', '.'));
                    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) &&
                            m.getReturnType() == int.class && m.getParameterCount() == 0) {
                            try {
                                m.setAccessible(true);
                                int val = (int) m.invoke(null);
                                globalMethodCache.put(name + "." + m.getName(), val);
                            } catch (Throwable ignored) {}
                        }
                    }
                    // Build register field set for this ZKM class
                    Set<String> zkmRegFields = new HashSet<>();
                    if (allClasses != null) {
                        ClassNode zkmCn = allClasses.get(name);
                        if (zkmCn != null) {
                            for (MethodNode m : zkmCn.methods) {
                                if (m.name.equals("<clinit>")) continue;
                                for (AbstractInsnNode insn : m.instructions) {
                                    if (insn.getOpcode() == Opcodes.PUTSTATIC && insn instanceof FieldInsnNode fi) {
                                        if (fi.owner.equals(zkmCn.name) && fi.desc.equals("I")) {
                                            zkmRegFields.add(fi.name);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                            try {
                                f.setAccessible(true);
                                if (zkmRegFields.contains(f.getName())) {
                                    registerFieldInitialValues.put(name + "." + f.getName(), f.getInt(null));
                                } else {
                                    globalFieldCache.put(name + "." + f.getName(), f.getInt(null));
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // Build synthetic classes for per-class opaque predicates in app classes
            List<ClassNode> appPredicateClasses = new ArrayList<>();
            for (ClassNode cn : classes.values()) {
                if (opaquePredicateClasses.contains(cn.name)) continue;
                if (!hasOpaquePredicateSignatures(cn)) continue;
                appPredicateClasses.add(cn);
                try {
                    Class<?> sc = buildSyntheticClass(cn, loader);
                    if (sc != null) {
                        extractMethodCache(sc, cn.name);
                        extractFieldCache(sc, cn.name);
                    }
                } catch (Throwable ignored) {}
            }

            // v2.1: Always run simulation on ALL app classes with opaque predicates.
            // The classloader approach may get wrong values when external refs are stripped,
            // but the simulation follows the actual control flow correctly.
            int simCount = 0;
            for (ClassNode cn : appPredicateClasses) {
                int before = globalFieldCache.size() + globalMethodCache.size();
                analyzeClinitFields(cn);
                if (globalFieldCache.size() + globalMethodCache.size() > before) simCount++;
            }

            loader.close();

            // v2.6: Cache validation — cross-check ConstantValue vs simulator values
            int cacheDisagreements = validateFieldCache(classes);
            if (cacheDisagreements > 0) {
                System.out.println("[FDRS] [WARN] Removed " + cacheDisagreements + " disagreeing field cache entries");
            }

            System.out.println("[FDRS] Populated " + globalMethodCache.size() + " method values, " +
                               globalFieldCache.size() + " field values" +
                               (simCount == 0 ? "" : " (" + simCount + " via static analysis)"));
        } catch (Exception e) {
            System.err.println("[FDRS] WARNING: Failed to populate caches: " + e.getMessage());
        }
    }

    /** Check if a class has any ZKM opaque predicate field or method signatures. */
    static boolean hasOpaquePredicateSignatures(ClassNode cn) {
        for (FieldNode fn : cn.fields) {
            if ((fn.access & Opcodes.ACC_STATIC) != 0 && fn.desc.equals("I") &&
                fn.name.matches(".*00[4-7][0-9a-fA-F].*")) return true;
        }
        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_STATIC) != 0 && mn.desc.equals("()I") &&
                mn.name.matches(".*00[4-7][0-9a-fA-F].*")) return true;
        }
        return false;
    }

    /** Extract static int method return values from a loaded class into globalMethodCache. */
    static void extractMethodCache(Class<?> cls, String ownerName) {
        try {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType() == int.class && m.getParameterCount() == 0) {
                    try {
                        m.setAccessible(true);
                        int val = (int) m.invoke(null);
                        globalMethodCache.put(ownerName + "." + m.getName(), val);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            // getDeclaredMethods() can throw NoClassDefFoundError. Fall back to individual method lookup.
            if (allClasses != null) {
                ClassNode cn = allClasses.get(ownerName);
                if (cn != null) {
                    for (MethodNode mn : cn.methods) {
                        if ((mn.access & Opcodes.ACC_STATIC) != 0 && mn.desc.equals("()I")) {
                            try {
                                java.lang.reflect.Method m = cls.getDeclaredMethod(mn.name);
                                m.setAccessible(true);
                                globalMethodCache.put(ownerName + "." + mn.name, (int) m.invoke(null));
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
    }

    /** Extract static int field values from a loaded class into globalFieldCache.
     *  Excludes "register" fields that are set by PUTSTATIC in non-&lt;clinit&gt; methods —
     *  these are per-method-call values, not class-level constants.
     *  v2.6: Skips caching for uncertain synthetic classes (where external refs were stripped). */
    static void extractFieldCache(Class<?> cls, String ownerName) {
        // v2.6: If this class had external refs stripped in its synthetic <clinit>,
        // field values from classloader may be wrong. Skip — let simulator handle them.
        if (uncertainSyntheticClasses.contains(ownerName)) return;
        // Build set of register fields (set via PUTSTATIC in method bodies)
        Set<String> registerFields = new HashSet<>();
        if (allClasses != null) {
            ClassNode cn = allClasses.get(ownerName);
            if (cn != null) {
                for (MethodNode mn : cn.methods) {
                    if (mn.name.equals("<clinit>")) continue;
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn.getOpcode() == Opcodes.PUTSTATIC && insn instanceof FieldInsnNode fi) {
                            if (fi.owner.equals(cn.name) && fi.desc.equals("I")) {
                                registerFields.add(fi.name);
                            }
                        }
                    }
                }
            }
        }
        try {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                    if (registerFields.contains(f.getName())) continue;
                    try {
                        f.setAccessible(true);
                        globalFieldCache.put(ownerName + "." + f.getName(), f.getInt(null));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            if (allClasses != null) {
                ClassNode cn = allClasses.get(ownerName);
                if (cn != null) {
                    for (FieldNode fn : cn.fields) {
                        if ((fn.access & Opcodes.ACC_STATIC) != 0 && fn.desc.equals("I")) {
                            if (registerFields.contains(fn.name)) continue;
                            try {
                                java.lang.reflect.Field f = cls.getDeclaredField(fn.name);
                                f.setAccessible(true);
                                globalFieldCache.put(ownerName + "." + fn.name, f.getInt(null));
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
    }

    /**
     * v2.1: Static bytecode analysis fallback for extracting opaque predicate field values
     * when classloader approach fails. Analyzes <clinit> and static ()I methods using ConstInterp.
     */
    static void analyzeClinitFields(ClassNode cn) {
        // Step 0: Extract ConstantValue attributes from static int fields.
        // ZKM sets ConstantValue on opaque predicate fields (e.g., modulus=2, offset=1)
        // even though the fields aren't static final. The JVM initializes these before <clinit>.
        //
        // IMPORTANT: ZKM also has "register" fields that are set via PUTSTATIC at the start
        // of each method body. For these, the ConstantValue is just an initial value that gets
        // overwritten per-method-call. Caching the ConstantValue would be WRONG — it would
        // cause the opaque predicate to resolve to the dead branch, destroying useful code.
        // We must exclude any field that has a PUTSTATIC in a non-<clinit> method.
        Set<String> registerFields = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<clinit>")) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == Opcodes.PUTSTATIC && insn instanceof FieldInsnNode fi) {
                    if (fi.owner.equals(cn.name) && fi.desc.equals("I")) {
                        registerFields.add(fi.name);
                    }
                }
            }
        }
        for (FieldNode fn : cn.fields) {
            if ((fn.access & Opcodes.ACC_STATIC) != 0 && fn.desc.equals("I") && fn.value instanceof Integer) {
                if (registerFields.contains(fn.name)) {
                    // Store initial value for localization, NOT in globalFieldCache
                    String key = cn.name + "." + fn.name;
                    if (!registerFieldInitialValues.containsKey(key)) {
                        registerFieldInitialValues.put(key, (Integer) fn.value);
                    }
                    continue;
                }
                String key = cn.name + "." + fn.name;
                if (!globalFieldCache.containsKey(key)) {
                    globalFieldCache.put(key, (Integer) fn.value);
                }
            }
        }

        // Step 1: Resolve static ()I methods using bytecode analysis
        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_STATIC) == 0) continue;
            if (!mn.desc.equals("()I")) continue;
            if (!mn.name.matches(".*00[4-7][0-9a-fA-F].*")) continue;
            String key = cn.name + "." + mn.name;
            if (globalMethodCache.containsKey(key)) continue;
            try {
                mn.maxStack = Math.max(mn.maxStack, 16);
                mn.maxLocals = Math.max(mn.maxLocals, 16);
                Analyzer<CVal> analyzer = new Analyzer<>(new ConstInterp(null, globalMethodCache));
                Frame<CVal>[] frames = analyzer.analyze(cn.name, mn);
                for (int i = 0; i < mn.instructions.size(); i++) {
                    AbstractInsnNode insn = mn.instructions.get(i);
                    if (insn.getOpcode() == Opcodes.IRETURN && frames[i] != null) {
                        CVal top = frames[i].getStack(frames[i].getStackSize() - 1);
                        if (top != null && top.isInt()) {
                            globalMethodCache.put(key, top.intVal());
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Step 2: Simulate <clinit> execution to extract opaque field values
        MethodNode clinit = null;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<clinit>")) { clinit = mn; break; }
        }
        if (clinit == null) return;
        simulateClinitExecution(cn, clinit, registerFields);
    }

    /**
     * Lightweight bytecode interpreter that simulates <clinit> execution.
     * Maintains a local int stack and field state map, follows GOTOs and resolves switches.
     * This handles the self-referential field patterns that the ASM Analyzer can't resolve.
     */
    static void simulateClinitExecution(ClassNode cn, MethodNode clinit, Set<String> registerFields) {
        try {
            Map<String, Integer> localFields = new HashMap<>();
            Deque<Integer> stack = new ArrayDeque<>();
            Map<LabelNode, Integer> labelIndices = new HashMap<>();
            AbstractInsnNode[] insns = clinit.instructions.toArray();

            // Pre-index labels
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof LabelNode ln) labelIndices.put(ln, i);
            }

            int pc = 0;
            int steps = 0;
            int maxSteps = insns.length * 20; // prevent infinite loops

            while (pc < insns.length && steps++ < maxSteps) {
                AbstractInsnNode insn = insns[pc];
                int op = insn.getOpcode();
                switch (op) {
                    case -1: // LabelNode, LineNumberNode, FrameNode
                        pc++; continue;
                    case Opcodes.ICONST_M1: stack.push(-1); pc++; continue;
                    case Opcodes.ICONST_0: stack.push(0); pc++; continue;
                    case Opcodes.ICONST_1: stack.push(1); pc++; continue;
                    case Opcodes.ICONST_2: stack.push(2); pc++; continue;
                    case Opcodes.ICONST_3: stack.push(3); pc++; continue;
                    case Opcodes.ICONST_4: stack.push(4); pc++; continue;
                    case Opcodes.ICONST_5: stack.push(5); pc++; continue;
                    case Opcodes.BIPUSH: case Opcodes.SIPUSH:
                        stack.push(((IntInsnNode) insn).operand); pc++; continue;
                    case Opcodes.LDC: {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if (ldc.cst instanceof Integer iv) stack.push(iv);
                        else stack.push(null); // non-int LDC: UNKNOWN placeholder
                        pc++; continue;
                    }
                    case Opcodes.DUP:
                        if (!stack.isEmpty()) stack.push(stack.peek());
                        pc++; continue;
                    case Opcodes.POP:
                        if (!stack.isEmpty()) stack.pop();
                        pc++; continue;
                    case Opcodes.IADD: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a+b:null); } pc++; continue; }
                    case Opcodes.ISUB: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a-b:null); } pc++; continue; }
                    case Opcodes.IMUL: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a*b:null); } pc++; continue; }
                    case Opcodes.IREM: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null&&b!=0?a%b:null); } pc++; continue; }
                    case Opcodes.IXOR: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a^b:null); } pc++; continue; }
                    case Opcodes.IAND: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a&b:null); } pc++; continue; }
                    case Opcodes.IOR:  { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a|b:null); } pc++; continue; }
                    case Opcodes.ISHL: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a<<b:null); } pc++; continue; }
                    case Opcodes.ISHR: { if (stack.size()>=2) { Integer b=stack.pop(),a=stack.pop(); stack.push(a!=null&&b!=null?a>>b:null); } pc++; continue; }
                    case Opcodes.INEG: { if (!stack.isEmpty()) { Integer v=stack.pop(); stack.push(v!=null?-v:null); } pc++; continue; }
                    case Opcodes.I2C: { if (!stack.isEmpty()) { Integer v=stack.pop(); stack.push(v!=null?v&0xFFFF:null); } pc++; continue; }
                    case Opcodes.I2B: { if (!stack.isEmpty()) { Integer v=stack.pop(); stack.push(v!=null?(int)(byte)(int)v:null); } pc++; continue; }
                    case Opcodes.I2S: { if (!stack.isEmpty()) { Integer v=stack.pop(); stack.push(v!=null?(int)(short)(int)v:null); } pc++; continue; }
                    case Opcodes.GETSTATIC: {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        if (fi.desc.equals("I")) {
                            Integer v = null;
                            if (fi.owner.equals(cn.name)) {
                                v = localFields.get(fi.name);
                                if (v == null) v = globalFieldCache.get(cn.name + "." + fi.name);
                            } else {
                                v = globalFieldCache.get(fi.owner + "." + fi.name);
                            }
                            stack.push(v); // null = UNKNOWN, maintains stack alignment
                        } else {
                            stack.push(null); // non-int: UNKNOWN placeholder
                        }
                        pc++; continue;
                    }
                    case Opcodes.PUTSTATIC: {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        if (!stack.isEmpty()) {
                            Integer val = stack.pop();
                            if (fi.desc.equals("I") && val != null &&
                                fi.owner.equals(cn.name) && fi.name.matches(".*00[4-7][0-9a-fA-F].*")) {
                                localFields.put(fi.name, val);
                            }
                        }
                        pc++; continue;
                    }
                    case Opcodes.INVOKESTATIC: {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        // Pop arguments to maintain stack alignment
                        org.objectweb.asm.Type[] simArgTypes = org.objectweb.asm.Type.getArgumentTypes(mi.desc);
                        for (org.objectweb.asm.Type at : simArgTypes) {
                            if (!stack.isEmpty()) stack.pop();
                        }
                        // Push return value (null = UNKNOWN)
                        org.objectweb.asm.Type simRetType = org.objectweb.asm.Type.getReturnType(mi.desc);
                        if (simRetType.getSort() != org.objectweb.asm.Type.VOID) {
                            Integer val = null;
                            if (mi.desc.equals("()I")) {
                                String key = mi.owner + "." + mi.name;
                                if (mi.owner.equals(cn.name)) key = cn.name + "." + mi.name;
                                val = globalMethodCache.get(key);
                            }
                            stack.push(val);
                        }
                        pc++; continue;
                    }
                    case Opcodes.GOTO: {
                        JumpInsnNode ji = (JumpInsnNode) insn;
                        Integer target = labelIndices.get(ji.label);
                        if (target != null) { pc = target; continue; }
                        pc++; continue;
                    }
                    case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE:
                    case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE:
                    case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE: {
                        JumpInsnNode ji = (JumpInsnNode) insn;
                        if (stack.size() >= 2) {
                            Integer b = stack.pop(), a = stack.pop();
                            if (a != null && b != null) {
                                boolean branch = switch (op) {
                                    case Opcodes.IF_ICMPEQ -> (int)a == (int)b;
                                    case Opcodes.IF_ICMPNE -> (int)a != (int)b;
                                    case Opcodes.IF_ICMPLT -> a < b;
                                    case Opcodes.IF_ICMPGE -> a >= b;
                                    case Opcodes.IF_ICMPGT -> a > b;
                                    case Opcodes.IF_ICMPLE -> a <= b;
                                    default -> false;
                                };
                                if (branch) {
                                    Integer target = labelIndices.get(ji.label);
                                    if (target != null) { pc = target; continue; }
                                }
                            }
                            // else: UNKNOWN operands, fall through (safe default)
                        }
                        pc++; continue;
                    }
                    case Opcodes.IFEQ: case Opcodes.IFNE:
                    case Opcodes.IFLT: case Opcodes.IFGE:
                    case Opcodes.IFGT: case Opcodes.IFLE: {
                        JumpInsnNode ji = (JumpInsnNode) insn;
                        if (!stack.isEmpty()) {
                            Integer a = stack.pop();
                            if (a != null) {
                                boolean branch = switch (op) {
                                    case Opcodes.IFEQ -> a == 0;
                                    case Opcodes.IFNE -> a != 0;
                                    case Opcodes.IFLT -> a < 0;
                                    case Opcodes.IFGE -> a >= 0;
                                    case Opcodes.IFGT -> a > 0;
                                    case Opcodes.IFLE -> a <= 0;
                                    default -> false;
                                };
                                if (branch) {
                                    Integer target = labelIndices.get(ji.label);
                                    if (target != null) { pc = target; continue; }
                                }
                            }
                        }
                        pc++; continue;
                    }
                    case Opcodes.TABLESWITCH: {
                        TableSwitchInsnNode ts = (TableSwitchInsnNode) insn;
                        if (!stack.isEmpty()) {
                            Integer keyVal = stack.pop();
                            if (keyVal == null) { pc++; continue; }
                            int key = keyVal;
                            LabelNode target;
                            if (key >= ts.min && key <= ts.max) {
                                target = ts.labels.get(key - ts.min);
                            } else {
                                target = ts.dflt;
                            }
                            Integer idx = labelIndices.get(target);
                            if (idx != null) { pc = idx; continue; }
                        }
                        pc++; continue;
                    }
                    case Opcodes.LOOKUPSWITCH: {
                        LookupSwitchInsnNode ls = (LookupSwitchInsnNode) insn;
                        if (!stack.isEmpty()) {
                            Integer keyVal2 = stack.pop();
                            if (keyVal2 == null) { pc++; continue; }
                            int key = keyVal2;
                            LabelNode target = ls.dflt;
                            for (int j = 0; j < ls.keys.size(); j++) {
                                if (ls.keys.get(j) == key) { target = ls.labels.get(j); break; }
                            }
                            Integer idx = labelIndices.get(target);
                            if (idx != null) { pc = idx; continue; }
                        }
                        pc++; continue;
                    }
                    case Opcodes.RETURN: case Opcodes.ATHROW:
                        pc = insns.length; continue; // end execution
                    default:
                        // Unknown instruction — skip, may desync stack
                        pc++; continue;
                }
            }

            // Write resolved fields to appropriate cache
            for (Map.Entry<String, Integer> e : localFields.entrySet()) {
                String key = cn.name + "." + e.getKey();
                if (registerFields != null && registerFields.contains(e.getKey())) {
                    // Register fields go to registerFieldInitialValues, NOT globalFieldCache
                    registerFieldInitialValues.put(key, e.getValue());
                } else {
                    globalFieldCache.put(key, e.getValue());
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * v2.6: Cross-validate cached field values against ConstantValue attributes.
     * If a field has a ConstantValue and the cached value disagrees, the cache is wrong
     * (typically from stripExternalReferences producing ICONST_0 for external fields).
     * Also re-runs the simulator independently and compares — if simulator disagrees
     * with cache, remove the entry (safe: unresolved = cosmetic only).
     * Returns the number of entries removed.
     */
    static int validateFieldCache(Map<String, ClassNode> classes) {
        int removed = 0;
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : globalFieldCache.entrySet()) {
            String key = entry.getKey(); // "owner.fieldName"
            int cachedVal = entry.getValue();
            int dotIdx = key.lastIndexOf('.');
            if (dotIdx < 0) continue;
            String ownerName = key.substring(0, dotIdx);
            String fieldName = key.substring(dotIdx + 1);

            ClassNode cn = (allClasses != null) ? allClasses.get(ownerName) : classes.get(ownerName);
            if (cn == null) continue;

            // Check ConstantValue attribute
            for (FieldNode fn : cn.fields) {
                if (fn.name.equals(fieldName) && fn.desc.equals("I") &&
                    (fn.access & Opcodes.ACC_STATIC) != 0 && fn.value instanceof Integer cv) {
                    // ConstantValue exists — check if it agrees with cached value
                    // But only if the field is NOT a register field (register fields get overwritten)
                    boolean isRegister = registerFieldInitialValues.containsKey(key);
                    if (!isRegister && cv != cachedVal) {
                        // ConstantValue disagrees with cache — remove from cache
                        System.out.println("[FDRS] [WARN] Field " + key + ": ConstantValue=" + cv +
                                         " vs cached=" + cachedVal + " — removing from cache");
                        toRemove.add(key);
                    }
                    break;
                }
            }
        }

        for (String key : toRemove) {
            globalFieldCache.remove(key);
            removed++;
        }
        return removed;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 1: String decryption via dataflow analysis + classloader invoke
    // ═══════════════════════════════════════════════════════════════════

    // Constant-tracking value for the dataflow analysis
    static class CVal implements Value {
        static final CVal UNKNOWN1 = new CVal(null, 1, -1);
        static final CVal UNKNOWN2 = new CVal(null, 2, -1);
        final Object cst;
        final int sz;
        final int sourceVar; // ILOAD local variable index, or -1 if unknown/not from ILOAD
        CVal(Object cst, int sz, int sourceVar) { this.cst = cst; this.sz = sz; this.sourceVar = sourceVar; }
        @Override public int getSize() { return sz; }
        boolean isKnown() { return cst != null; }
        boolean isInt() { return cst instanceof Integer; }
        boolean isStr() { return cst instanceof String; }
        int intVal() { return (Integer) cst; }
        int sourceVar() { return sourceVar; }
        static CVal ofInt(int v) { return new CVal(v, 1, -1); }
        static CVal ofStr(String s) { return new CVal(s, 1, -1); }
        static CVal unk(int sz) { return sz == 2 ? UNKNOWN2 : UNKNOWN1; }
        static CVal unkWithSource(int sz, int sourceVar) { return new CVal(null, sz, sourceVar); }
    }

    // Interpreter that propagates integer and string constants through the JVM stack
    static class ConstInterp extends Interpreter<CVal> {
        final ClassLoader loader;
        final Map<String, Integer> methodCache;

        ConstInterp(ClassLoader loader, Map<String, Integer> methodCache) {
            super(Opcodes.ASM9);
            this.loader = loader;
            this.methodCache = methodCache;
        }

        @Override public CVal newValue(org.objectweb.asm.Type type) {
            if (type == null) return CVal.UNKNOWN1;
            if (type == org.objectweb.asm.Type.VOID_TYPE) return null;
            return CVal.unk(type.getSize());
        }

        @Override public CVal newOperation(AbstractInsnNode insn) {
            switch (insn.getOpcode()) {
                case Opcodes.ICONST_M1: return CVal.ofInt(-1);
                case Opcodes.ICONST_0: return CVal.ofInt(0);
                case Opcodes.ICONST_1: return CVal.ofInt(1);
                case Opcodes.ICONST_2: return CVal.ofInt(2);
                case Opcodes.ICONST_3: return CVal.ofInt(3);
                case Opcodes.ICONST_4: return CVal.ofInt(4);
                case Opcodes.ICONST_5: return CVal.ofInt(5);
                case Opcodes.BIPUSH: case Opcodes.SIPUSH:
                    return CVal.ofInt(((IntInsnNode) insn).operand);
                case Opcodes.LDC: {
                    Object c = ((LdcInsnNode) insn).cst;
                    if (c instanceof Integer i) return CVal.ofInt(i);
                    if (c instanceof String s) return CVal.ofStr(s);
                    if (c instanceof Long || c instanceof Double) return CVal.UNKNOWN2;
                    return CVal.UNKNOWN1;
                }
                case Opcodes.LCONST_0: case Opcodes.LCONST_1:
                case Opcodes.DCONST_0: case Opcodes.DCONST_1:
                    return CVal.UNKNOWN2;
                case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2:
                case Opcodes.ACONST_NULL:
                    return CVal.UNKNOWN1;
                case Opcodes.GETSTATIC: {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    // v2: Resolve opaque predicate fields to constants
                    if (fi.desc.equals("I")) {
                        String key = fi.owner + "." + fi.name;
                        Integer cached = globalFieldCache.get(key);
                        if (cached != null) return CVal.ofInt(cached);
                    }
                    return CVal.unk(org.objectweb.asm.Type.getType(fi.desc).getSize());
                }
                case Opcodes.NEW:
                    return CVal.UNKNOWN1;
                default:
                    return CVal.UNKNOWN1;
            }
        }

        @Override public CVal copyOperation(AbstractInsnNode insn, CVal value) {
            // Track source variable for ILOAD — enables cross-block identity matching
            if (insn.getOpcode() == Opcodes.ILOAD && insn instanceof VarInsnNode vi) {
                if (value.isInt()) return value; // already known constant, preserve it
                // Return same object if sourceVar already matches to ensure convergence
                if (value.sourceVar == vi.var && value.sz == 1) return value;
                return CVal.unkWithSource(1, vi.var);
            }
            return value;
        }

        @Override public CVal unaryOperation(AbstractInsnNode insn, CVal v) {
            switch (insn.getOpcode()) {
                case Opcodes.I2C: return v.isInt() ? CVal.ofInt((char) v.intVal()) : CVal.UNKNOWN1;
                case Opcodes.I2B: return v.isInt() ? CVal.ofInt((byte) v.intVal()) : CVal.UNKNOWN1;
                case Opcodes.I2S: return v.isInt() ? CVal.ofInt((short) v.intVal()) : CVal.UNKNOWN1;
                case Opcodes.INEG: return v.isInt() ? CVal.ofInt(-v.intVal()) : CVal.UNKNOWN1;
                case Opcodes.I2L: case Opcodes.I2D: case Opcodes.F2L: case Opcodes.F2D:
                case Opcodes.L2D: case Opcodes.D2L:
                    return CVal.UNKNOWN2;
                case Opcodes.I2F: case Opcodes.L2I: case Opcodes.L2F:
                case Opcodes.D2I: case Opcodes.D2F: case Opcodes.F2I:
                case Opcodes.FNEG:
                    return CVal.UNKNOWN1;
                case Opcodes.LNEG: case Opcodes.DNEG:
                    return CVal.UNKNOWN2;
                case Opcodes.ARRAYLENGTH: case Opcodes.INSTANCEOF:
                    return CVal.UNKNOWN1;
                case Opcodes.CHECKCAST:
                    return v;
                case Opcodes.GETFIELD: {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    return CVal.unk(org.objectweb.asm.Type.getType(fi.desc).getSize());
                }
                case Opcodes.NEWARRAY: case Opcodes.ANEWARRAY:
                    return CVal.UNKNOWN1;
                case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT:
                case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
                case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                case Opcodes.TABLESWITCH: case Opcodes.LOOKUPSWITCH:
                case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN:
                case Opcodes.DRETURN: case Opcodes.ARETURN:
                case Opcodes.PUTSTATIC: case Opcodes.ATHROW:
                case Opcodes.MONITORENTER: case Opcodes.MONITOREXIT:
                    return null;
                default:
                    return CVal.UNKNOWN1;
            }
        }

        @Override public CVal binaryOperation(AbstractInsnNode insn, CVal a, CVal b) {
            int op = insn.getOpcode();
            if (a.isInt() && b.isInt()) {
                int v1 = a.intVal(), v2 = b.intVal();
                switch (op) {
                    case Opcodes.IADD: return CVal.ofInt(v1 + v2);
                    case Opcodes.ISUB: return CVal.ofInt(v1 - v2);
                    case Opcodes.IMUL: return CVal.ofInt(v1 * v2);
                    case Opcodes.IDIV: return v2 != 0 ? CVal.ofInt(v1 / v2) : CVal.UNKNOWN1;
                    case Opcodes.IREM: return v2 != 0 ? CVal.ofInt(v1 % v2) : CVal.UNKNOWN1;
                    case Opcodes.IAND: return CVal.ofInt(v1 & v2);
                    case Opcodes.IOR:  return CVal.ofInt(v1 | v2);
                    case Opcodes.IXOR: return CVal.ofInt(v1 ^ v2);
                    case Opcodes.ISHL: return CVal.ofInt(v1 << v2);
                    case Opcodes.ISHR: return CVal.ofInt(v1 >> v2);
                    case Opcodes.IUSHR: return CVal.ofInt(v1 >>> v2);
                }
            }
            if (op >= Opcodes.IADD && op <= Opcodes.IUSHR) return CVal.UNKNOWN1;
            if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE) return null;
            if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) return null;
            if (op == Opcodes.PUTFIELD) return null;
            if (op == Opcodes.LCMP || op == Opcodes.FCMPL || op == Opcodes.FCMPG ||
                op == Opcodes.DCMPL || op == Opcodes.DCMPG) return CVal.UNKNOWN1;
            if (op >= Opcodes.LADD && op <= Opcodes.LXOR) return CVal.UNKNOWN2;
            if (op >= Opcodes.DADD && op <= Opcodes.DREM) return CVal.UNKNOWN2;
            if (op >= Opcodes.FADD && op <= Opcodes.FREM) return CVal.UNKNOWN1;
            return CVal.UNKNOWN1;
        }

        @Override public CVal ternaryOperation(AbstractInsnNode insn, CVal v1, CVal v2, CVal v3) {
            return null;
        }

        @Override public CVal naryOperation(AbstractInsnNode insn, List<? extends CVal> values) {
            if (insn instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == Opcodes.INVOKESTATIC && mi.desc.equals("()I")) {
                    String key = mi.owner + "." + mi.name;
                    Integer cached = methodCache.get(key);
                    if (cached != null) return CVal.ofInt(cached);
                    // Also check globalMethodCache
                    cached = globalMethodCache.get(key);
                    if (cached != null) return CVal.ofInt(cached);
                    try {
                        Class<?> cls = loader.loadClass(mi.owner.replace('/', '.'));
                        java.lang.reflect.Method m = cls.getDeclaredMethod(mi.name);
                        m.setAccessible(true);
                        int result = ((Number) m.invoke(null)).intValue();
                        methodCache.put(key, result);
                        return CVal.ofInt(result);
                    } catch (Throwable ignored) {}
                }
                org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(mi.desc);
                if (ret.equals(org.objectweb.asm.Type.VOID_TYPE)) return null;
                return CVal.unk(ret.getSize());
            }
            if (insn instanceof InvokeDynamicInsnNode idi) {
                org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(idi.desc);
                if (ret.equals(org.objectweb.asm.Type.VOID_TYPE)) return null;
                return CVal.unk(ret.getSize());
            }
            return CVal.UNKNOWN1;
        }

        @Override public void returnOperation(AbstractInsnNode insn, CVal value, CVal expected) {}

        @Override public CVal merge(CVal a, CVal b) {
            if (a == b) return a;
            if (a.cst != null && b.cst != null && a.cst.equals(b.cst) && a.sz == b.sz) return a;
            int sz = Math.max(a.sz, b.sz);
            // Preserve sourceVar if both paths agree on the same source variable
            int mergedSource = (a.sourceVar >= 0 && a.sourceVar == b.sourceVar) ? a.sourceVar : -1;
            // CRITICAL: return an existing input if it matches the merged result,
            // to ensure reference equality convergence in the Analyzer's fixed-point loop.
            if (a.cst == null && a.sz == sz && a.sourceVar == mergedSource) return a;
            if (b.cst == null && b.sz == sz && b.sourceVar == mergedSource) return b;
            if (mergedSource >= 0) return CVal.unkWithSource(sz, mergedSource);
            return CVal.unk(sz);
        }
    }

    // ── Orchestrator ──

    static void decryptStrings(Map<String, ClassNode> classes, Map<String, byte[]> rawClassBytes,
                                String jarPath, boolean verbose) {
        if (decryptorClassName == null) return;

        URLClassLoader loader;
        try {
            URL jarUrl = new File(jarPath).toURI().toURL();
            loader = new URLClassLoader(new URL[]{jarUrl}, ClassLoader.getPlatformClassLoader());
        } catch (Exception e) {
            System.err.println("[FDRS] ERROR: Cannot create classloader: " + e);
            return;
        }

        Class<?> decryptorClass;
        try {
            decryptorClass = loader.loadClass(decryptorClassName.replace('/', '.'));
        } catch (Exception e) {
            System.err.println("[FDRS] ERROR: Cannot load decryptor class: " + e);
            return;
        }

        Map<String, java.lang.reflect.Method> decryptMethods = new HashMap<>();
        for (java.lang.reflect.Method m : decryptorClass.getDeclaredMethods()) {
            m.setAccessible(true);
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class) {
                String key = m.getName() + org.objectweb.asm.Type.getMethodDescriptor(m);
                decryptMethods.put(key, m);
            }
        }
        System.out.println("[FDRS] Loaded " + decryptMethods.size() + " decrypt methods");

        // Pre-build synthetic classes for per-class opaque predicate resolution
        long tSynth = System.currentTimeMillis();
        Map<String, Class<?>> syntheticClasses = new HashMap<>();
        for (ClassNode cn : classes.values()) {
            if (cn.name.equals(decryptorClassName) || cn.name.startsWith(decryptorClassName + "$") ||
                opaquePredicateClasses.contains(cn.name)) continue;
            if (!hasDecryptCalls(cn) && !hasOpaquePredicateSignatures(cn)) continue;
            try {
                long t0 = System.currentTimeMillis();
                Class<?> sc = buildSyntheticClass(cn, loader);
                long dt = System.currentTimeMillis() - t0;
                if (sc != null) {
                    syntheticClasses.put(cn.name, sc);
                    if (dt > 500) System.out.println("[FDRS]   SLOW synthetic class: " + cn.name + " (" + dt + "ms)");
                    else if (verbose) System.out.println("[FDRS] Built synthetic class for " + cn.name);
                }
            } catch (Throwable e) {
                if (verbose) System.err.println("[FDRS] WARN: Synthetic class failed for " + cn.name + ": " + e.getMessage());
            }
        }
        System.out.println("[FDRS]   Synthetic classes: " + syntheticClasses.size() + " in " + (System.currentTimeMillis() - tSynth) + "ms");

        // Populate globalMethodCache
        // 1. Load ALL ZKM classes to trigger <clinit>
        long tLoad = System.currentTimeMillis();
        Map<String, Class<?>> zkmLoadedClasses = new LinkedHashMap<>();
        for (String name : opaquePredicateClasses) {
            try {
                long t0 = System.currentTimeMillis();
                Class<?> cls = loader.loadClass(name.replace('/', '.'));
                long dt = System.currentTimeMillis() - t0;
                zkmLoadedClasses.put(name, cls);
                if (dt > 500) System.out.println("[FDRS]   SLOW class load: " + name + " (" + dt + "ms)");
            } catch (Throwable e) {
                if (verbose) System.err.println("[FDRS]   Failed to load ZKM class " + name + ": " + e);
            }
        }
        System.out.println("[FDRS]   ZKM class loading: " + zkmLoadedClasses.size() + " classes in " + (System.currentTimeMillis() - tLoad) + "ms");

        // 2. Extract method values from ZKM classes
        long tExtract = System.currentTimeMillis();
        List<String> failedMethods = new ArrayList<>();
        for (var zkmEntry : zkmLoadedClasses.entrySet()) {
            String name = zkmEntry.getKey();
            Class<?> cls = zkmEntry.getValue();
            extractMethodCache(cls, name);
            extractFieldCache(cls, name); // v2: also extract field values
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType() == int.class && m.getParameterCount() == 0) {
                    if (!globalMethodCache.containsKey(name + "." + m.getName())) {
                        failedMethods.add(name + "." + m.getName());
                    }
                }
            }
        }

        // 3. Retry failed methods
        if (!failedMethods.isEmpty()) {
            int retryResolved = 0;
            for (String key : failedMethods) {
                if (globalMethodCache.containsKey(key)) continue;
                String className = key.substring(0, key.lastIndexOf('.'));
                String methodName = key.substring(key.lastIndexOf('.') + 1);
                Class<?> cls = zkmLoadedClasses.get(className);
                if (cls == null) continue;
                try {
                    java.lang.reflect.Method m = cls.getDeclaredMethod(methodName);
                    m.setAccessible(true);
                    int val = (int) m.invoke(null);
                    globalMethodCache.put(key, val);
                    retryResolved++;
                } catch (Throwable ignored) {}
            }
            if (verbose && retryResolved > 0) {
                System.out.println("[FDRS] Retry resolved " + retryResolved + " additional opaque predicate values");
            }
        }

        // 4. From synthetic classes (per-class predicates)
        for (var entry : syntheticClasses.entrySet()) {
            String className = entry.getKey();
            Class<?> sc = entry.getValue();
            extractMethodCache(sc, className);
            extractFieldCache(sc, className); // v2: also extract field values
        }

        // 5. v2.3: Run analyzeClinitFields on ALL app classes with opaque predicates.
        // The synthetic class approach may fail (external deps, abstract classes, complex <clinit>),
        // but the bytecode simulation follows actual control flow correctly.
        // Previously this only ran in populateCachesFromJar (Phase 1.5) — JARs with string
        // encryption went through decryptStrings (Phase 1) and missed this step entirely.
        // Run in convergence loop: newly resolved fields may help resolve others.
        int simCount = 0;
        List<ClassNode> appPredicateClasses2 = new ArrayList<>();
        for (ClassNode cn : classes.values()) {
            if (cn.name.equals(decryptorClassName) || cn.name.startsWith(decryptorClassName + "$") ||
                opaquePredicateClasses.contains(cn.name)) continue;
            if (!hasOpaquePredicateSignatures(cn)) continue;
            appPredicateClasses2.add(cn);
        }
        for (int simRound = 0; simRound < 5; simRound++) {
            int prevSize = globalFieldCache.size() + globalMethodCache.size();
            for (ClassNode cn : appPredicateClasses2) {
                int before = globalFieldCache.size() + globalMethodCache.size();
                analyzeClinitFields(cn);
                if (globalFieldCache.size() + globalMethodCache.size() > before) simCount++;
            }
            if (globalFieldCache.size() + globalMethodCache.size() == prevSize) break;
        }

        System.out.println("[FDRS]   Extract + retry: " + (System.currentTimeMillis() - tExtract) + "ms");

        // v2.6: Cache validation — cross-check ConstantValue vs simulator values
        int cacheDisagreements = validateFieldCache(classes);
        if (cacheDisagreements > 0) {
            System.out.println("[FDRS] [WARN] Removed " + cacheDisagreements + " disagreeing field cache entries");
        }

        System.out.println("[FDRS] Populated " + globalMethodCache.size() + " method values, " +
                           globalFieldCache.size() + " field values" +
                          (failedMethods.isEmpty() ? "" : " (" + failedMethods.size() + " methods failed)") +
                          (simCount == 0 ? "" : " (" + simCount + " via static analysis)"));

        // Decrypt strings in each class
        long tDecrypt = System.currentTimeMillis();
        for (ClassNode cn : classes.values()) {
            if (cn.name.equals(decryptorClassName) || cn.name.startsWith(decryptorClassName + "$") ||
                opaquePredicateClasses.contains(cn.name)) continue;
            for (MethodNode mn : cn.methods) {
                decryptStringsInMethod(cn, mn, decryptorClass, decryptMethods,
                                       syntheticClasses.get(cn.name), classes, loader, verbose);
            }
        }
        System.out.println("[FDRS]   String invocations: " + (System.currentTimeMillis() - tDecrypt) + "ms");

        try { loader.close(); } catch (Exception ignored) {}
    }

    static boolean hasDecryptCalls(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode mi &&
                    mi.getOpcode() == Opcodes.INVOKESTATIC &&
                    mi.owner.equals(decryptorClassName)) return true;
            }
        }
        return false;
    }

    /**
     * Build a synthetic class containing only the static fields, static methods,
     * and &lt;clinit&gt; from the original.
     */
    static Class<?> buildSyntheticClass(ClassNode original, ClassLoader parent) throws Exception {
        String synthName = "fdrs_synth/" + original.name.replace('/', '_');

        ClassNode synth = new ClassNode();
        synth.version = original.version;
        synth.access = Opcodes.ACC_PUBLIC;
        synth.name = synthName;
        synth.superName = "java/lang/Object";

        for (FieldNode fn : original.fields) {
            if ((fn.access & Opcodes.ACC_STATIC) != 0) {
                synth.fields.add(new FieldNode(fn.access, fn.name, fn.desc, null, fn.value));
            }
        }

        for (MethodNode mn : original.methods) {
            if ((mn.access & Opcodes.ACC_STATIC) == 0) continue;
            MethodNode copy = new MethodNode(mn.access, mn.name, mn.desc, null,
                mn.exceptions != null ? mn.exceptions.toArray(new String[0]) : null);
            mn.accept(copy);
            for (AbstractInsnNode insn : copy.instructions) {
                if (insn instanceof FieldInsnNode fi && fi.owner.equals(original.name)) {
                    fi.owner = synthName;
                } else if (insn instanceof MethodInsnNode mi && mi.owner.equals(original.name)) {
                    mi.owner = synthName;
                }
            }
            // v2.1: Strip external references from <clinit> to prevent ClassNotFoundErrors
            if (mn.name.equals("<clinit>")) {
                if (stripExternalReferences(copy, synthName)) {
                    uncertainSyntheticClasses.add(original.name);
                }
            }
            synth.methods.add(copy);
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        synth.accept(cw);
        byte[] bytes = cw.toByteArray();

        String synthDotName = synthName.replace('/', '.');
        ClassLoader synthLoader = new ClassLoader(parent) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(synthDotName)) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        try {
            return Class.forName(synthDotName, true, synthLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Strip instructions from a method that reference external (non-java, non-self) classes.
     * Replaces INVOKE/GETSTATIC/NEW with appropriate POP/push-default sequences so the
     * method can execute without loading external dependencies.
     */
    /** Returns true if any external references were stripped (values may be wrong). */
    static boolean stripExternalReferences(MethodNode mn, String selfName) {
        List<AbstractInsnNode> toProcess = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            boolean isExternal = false;
            if (insn instanceof MethodInsnNode mi) {
                isExternal = !mi.owner.equals(selfName) && !mi.owner.startsWith("java/");
            } else if (insn instanceof FieldInsnNode fi) {
                isExternal = !fi.owner.equals(selfName) && !fi.owner.startsWith("java/");
            } else if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) {
                isExternal = !ti.desc.equals(selfName) && !ti.desc.startsWith("java/");
            }
            if (isExternal) toProcess.add(insn);
        }
        if (toProcess.isEmpty()) return false;
        for (AbstractInsnNode insn : toProcess) {
            if (insn instanceof MethodInsnNode mi) {
                // Pop all arguments + receiver (for non-static), push default return
                org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(mi.desc);
                org.objectweb.asm.Type retType = org.objectweb.asm.Type.getReturnType(mi.desc);
                InsnList replacement = new InsnList();
                // Pop args in reverse order
                for (int i = argTypes.length - 1; i >= 0; i--) {
                    replacement.add(new InsnNode(argTypes[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                }
                // Pop receiver for non-static calls
                if (mi.getOpcode() != Opcodes.INVOKESTATIC) {
                    replacement.add(new InsnNode(Opcodes.POP));
                }
                // Push default return value
                switch (retType.getSort()) {
                    case org.objectweb.asm.Type.VOID: break;
                    case org.objectweb.asm.Type.INT: case org.objectweb.asm.Type.BYTE:
                    case org.objectweb.asm.Type.CHAR: case org.objectweb.asm.Type.SHORT:
                    case org.objectweb.asm.Type.BOOLEAN:
                        replacement.add(new InsnNode(Opcodes.ICONST_0)); break;
                    case org.objectweb.asm.Type.LONG:
                        replacement.add(new InsnNode(Opcodes.LCONST_0)); break;
                    case org.objectweb.asm.Type.FLOAT:
                        replacement.add(new InsnNode(Opcodes.FCONST_0)); break;
                    case org.objectweb.asm.Type.DOUBLE:
                        replacement.add(new InsnNode(Opcodes.DCONST_0)); break;
                    default:
                        replacement.add(new InsnNode(Opcodes.ACONST_NULL)); break;
                }
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
            } else if (insn instanceof FieldInsnNode fi) {
                int sz = org.objectweb.asm.Type.getType(fi.desc).getSize();
                if (fi.getOpcode() == Opcodes.GETSTATIC) {
                    // Replace with push default
                    InsnList replacement = new InsnList();
                    if (fi.desc.equals("I") || fi.desc.equals("Z") || fi.desc.equals("B") ||
                        fi.desc.equals("C") || fi.desc.equals("S")) {
                        replacement.add(new InsnNode(Opcodes.ICONST_0));
                    } else if (fi.desc.equals("J")) {
                        replacement.add(new InsnNode(Opcodes.LCONST_0));
                    } else if (fi.desc.equals("F")) {
                        replacement.add(new InsnNode(Opcodes.FCONST_0));
                    } else if (fi.desc.equals("D")) {
                        replacement.add(new InsnNode(Opcodes.DCONST_0));
                    } else {
                        replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                    }
                    mn.instructions.insertBefore(insn, replacement);
                    mn.instructions.remove(insn);
                } else if (fi.getOpcode() == Opcodes.PUTSTATIC) {
                    // Replace with POP
                    mn.instructions.insertBefore(insn, new InsnNode(sz == 2 ? Opcodes.POP2 : Opcodes.POP));
                    mn.instructions.remove(insn);
                } else if (fi.getOpcode() == Opcodes.GETFIELD) {
                    InsnList replacement = new InsnList();
                    replacement.add(new InsnNode(Opcodes.POP)); // pop receiver
                    if (sz == 2) replacement.add(new InsnNode(Opcodes.LCONST_0));
                    else if (fi.desc.equals("I") || fi.desc.equals("Z") || fi.desc.equals("B") ||
                             fi.desc.equals("C") || fi.desc.equals("S"))
                        replacement.add(new InsnNode(Opcodes.ICONST_0));
                    else replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                    mn.instructions.insertBefore(insn, replacement);
                    mn.instructions.remove(insn);
                } else if (fi.getOpcode() == Opcodes.PUTFIELD) {
                    InsnList replacement = new InsnList();
                    replacement.add(new InsnNode(sz == 2 ? Opcodes.POP2 : Opcodes.POP)); // pop value
                    replacement.add(new InsnNode(Opcodes.POP)); // pop receiver
                    mn.instructions.insertBefore(insn, replacement);
                    mn.instructions.remove(insn);
                }
            } else if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) {
                // Replace NEW with ACONST_NULL
                mn.instructions.insertBefore(insn, new InsnNode(Opcodes.ACONST_NULL));
                mn.instructions.remove(insn);
            }
        }
        // Also strip exception table entries referencing external types
        if (mn.tryCatchBlocks != null) {
            mn.tryCatchBlocks.removeIf(tc ->
                tc.type != null && !tc.type.equals(selfName) && !tc.type.startsWith("java/"));
        }
        return true; // external refs were stripped
    }

    // ── Per-method string decryption ──

    static void decryptStringsInMethod(ClassNode cn, MethodNode mn,
                                        Class<?> decryptorClass,
                                        Map<String, java.lang.reflect.Method> decryptMethods,
                                        Class<?> synthClass,
                                        Map<String, ClassNode> classes,
                                        ClassLoader loader, boolean verbose) {
        List<MethodInsnNode> decryptCalls = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode mi &&
                mi.getOpcode() == Opcodes.INVOKESTATIC &&
                mi.owner.equals(decryptorClassName)) {
                decryptCalls.add(mi);
            }
        }
        if (decryptCalls.isEmpty()) return;

        Map<AbstractInsnNode, Integer> insnIndex = new IdentityHashMap<>();
        for (int i = 0; i < mn.instructions.size(); i++) {
            insnIndex.put(mn.instructions.get(i), i);
        }

        Map<String, Integer> methodCache = new HashMap<>();

        if (synthClass != null) {
            for (java.lang.reflect.Method m : synthClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType() == int.class && m.getParameterCount() == 0) {
                    try {
                        m.setAccessible(true);
                        int val = (int) m.invoke(null);
                        methodCache.put(cn.name + "." + m.getName(), val);
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Phase A: Try backward walk for all decrypt calls (fast, no mutation)
        Map<MethodInsnNode, String> resolved = new IdentityHashMap<>();
        List<MethodInsnNode> unresolved = new ArrayList<>();
        for (MethodInsnNode mi : decryptCalls) {
            try {
                String decrypted = tryBackwardWalkDecrypt(cn, mn, mi, decryptorClass,
                                                          decryptMethods, classes, loader);
                if (decrypted != null) {
                    resolved.put(mi, decrypted);
                } else {
                    unresolved.add(mi);
                }
            } catch (Exception e) {
                unresolved.add(mi);
            }
        }

        // Phase B: Only run expensive Analyzer if backward walk left unresolved calls
        Frame<CVal>[] frames = null;
        if (!unresolved.isEmpty()) {
            stringsFailedAnalyzer += unresolved.size();

            {
                int savedMaxStack = mn.maxStack;
                int savedMaxLocals = mn.maxLocals;
                // Inflate moderately — original values may be too low for obfuscated code
                // but instructions.size() was way too high, causing massive Frame arrays
                mn.maxStack = Math.max(mn.maxStack, 256);
                mn.maxLocals = Math.max(mn.maxLocals, 256);

                try {
                    Analyzer<CVal> analyzer = new Analyzer<>(new ConstInterp(loader, methodCache));
                    frames = analyzer.analyze(cn.name, mn);
                } catch (Exception e) {
                    if (verbose) System.err.println("[FDRS]   Analyzer failed for " + cn.name + "." + mn.name + ": " + e.getMessage());
                }

                mn.maxStack = savedMaxStack;
                mn.maxLocals = savedMaxLocals;
            }

            for (MethodInsnNode mi : unresolved) {
                try {
                    String decrypted = null;
                    if (frames != null) {
                        Integer idx = insnIndex.get(mi);
                        if (idx != null && frames[idx] != null) {
                            decrypted = tryDecryptFromFrame(frames[idx], mi, decryptorClass, decryptMethods);
                        }
                    }
                    if (decrypted != null) {
                        resolved.put(mi, decrypted);
                    } else {
                        stringsFailedDynamic++;
                        if (verbose) {
                            System.err.println("[FDRS]   FAIL " + cn.name + "." + mn.name + ": all strategies exhausted for " + mi.name + mi.desc);
                        }
                    }
                } catch (Exception e) {
                    stringsFailedDynamic++;
                    if (verbose) {
                        System.err.println("[FDRS]   FAIL " + cn.name + "." + mn.name + ": " + e.getMessage());
                    }
                }
            }
        }

        // Phase C: Apply all resolved decryptions
        for (var entry : resolved.entrySet()) {
            replaceWithDecryptedString(mn, entry.getKey(), entry.getValue());
            stringsDecrypted++;
            if (verbose) {
                System.out.println("[FDRS]   " + cn.name + "." + mn.name + ": \"" + truncate(entry.getValue(), 60) + "\"");
            }
        }
    }

    static String tryDecryptFromFrame(Frame<CVal> frame, MethodInsnNode mi,
                                       Class<?> decryptorClass,
                                       Map<String, java.lang.reflect.Method> decryptMethods) throws Exception {
        org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(mi.desc);
        int argCount = argTypes.length;
        int stackSize = frame.getStackSize();
        if (stackSize < argCount) return null;

        Object[] args = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            CVal v = frame.getStack(stackSize - argCount + i);
            if (!v.isKnown()) return null;
            args[i] = v.cst;
        }

        String methodKey = mi.name + mi.desc;
        java.lang.reflect.Method m = decryptMethods.get(methodKey);
        if (m == null) {
            for (var entry : decryptMethods.entrySet()) {
                if (entry.getKey().startsWith(mi.name + "(")) {
                    m = entry.getValue();
                    break;
                }
            }
        }
        if (m == null) return null;

        Class<?>[] paramTypes = m.getParameterTypes();
        Object[] invokeArgs = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            if (paramTypes[i] == String.class) {
                if (!(args[i] instanceof String)) return null;
                invokeArgs[i] = args[i];
            } else if (paramTypes[i] == char.class) {
                if (args[i] instanceof Integer intVal) {
                    invokeArgs[i] = (char) intVal.intValue();
                } else return null;
            } else {
                return null;
            }
        }

        m.setAccessible(true);
        return (String) m.invoke(null, invokeArgs);
    }

    static String tryBackwardWalkDecrypt(ClassNode cn, MethodNode mn, MethodInsnNode callInsn,
                                          Class<?> decryptorClass,
                                          Map<String, java.lang.reflect.Method> decryptMethods,
                                          Map<String, ClassNode> classes,
                                          ClassLoader loader) {
        try {
            org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(callInsn.desc);
            int argCount = argTypes.length;
            LinkedList<Object> argStack = new LinkedList<>();
            AbstractInsnNode current = callInsn.getPrevious();
            int collected = 0;

            while (current != null && collected < argCount) {
                if (current instanceof LabelNode || current instanceof LineNumberNode ||
                    current instanceof FrameNode || current.getOpcode() == Opcodes.NOP) {
                    current = current.getPrevious();
                    continue;
                }

                if (current instanceof LdcInsnNode ldc) {
                    argStack.addFirst(ldc.cst);
                    collected++;
                    current = current.getPrevious();
                } else if (current.getOpcode() == Opcodes.IXOR) {
                    AbstractInsnNode op2 = skipNonInsn(current.getPrevious(), true);
                    AbstractInsnNode op1 = op2 != null ? skipNonInsn(op2.getPrevious(), true) : null;

                    int mask, predValue;
                    if (op2 instanceof LdcInsnNode ldc2 && op1 instanceof MethodInsnNode mi1) {
                        mask = ((Number) ldc2.cst).intValue();
                        predValue = invokeStaticIntMethod(mi1, loader);
                        current = op1.getPrevious();
                    } else if (op1 instanceof LdcInsnNode ldc1 && op2 instanceof MethodInsnNode mi2) {
                        mask = ((Number) ldc1.cst).intValue();
                        predValue = invokeStaticIntMethod(mi2, loader);
                        current = op1.getPrevious();
                    } else {
                        return null;
                    }
                    argStack.addFirst((char)(predValue ^ mask));
                    collected++;
                } else if (current.getOpcode() == Opcodes.I2C) {
                    current = current.getPrevious();
                } else if (current.getOpcode() == Opcodes.SIPUSH || current.getOpcode() == Opcodes.BIPUSH) {
                    argStack.addFirst((char) ((IntInsnNode) current).operand);
                    collected++;
                    current = current.getPrevious();
                } else if (current.getOpcode() >= Opcodes.ICONST_M1 && current.getOpcode() <= Opcodes.ICONST_5) {
                    int val = current.getOpcode() == Opcodes.ICONST_M1 ? -1 : current.getOpcode() - Opcodes.ICONST_0;
                    argStack.addFirst((char) val);
                    collected++;
                    current = current.getPrevious();
                } else {
                    return null;
                }
            }

            if (collected < argCount) return null;

            String methodKey = callInsn.name + callInsn.desc;
            java.lang.reflect.Method m = decryptMethods.get(methodKey);
            if (m == null) {
                for (var entry : decryptMethods.entrySet()) {
                    if (entry.getKey().startsWith(callInsn.name + "(")) { m = entry.getValue(); break; }
                }
            }
            if (m == null) return null;

            Class<?>[] paramTypes = m.getParameterTypes();
            Object[] invokeArgs = new Object[argCount];
            for (int i = 0; i < argCount; i++) {
                Object arg = argStack.get(i);
                if (paramTypes[i] == String.class) {
                    invokeArgs[i] = (String) arg;
                } else if (paramTypes[i] == char.class) {
                    invokeArgs[i] = (arg instanceof Character c) ? c : (char) ((Number) arg).intValue();
                } else return null;
            }

            m.setAccessible(true);
            return (String) m.invoke(null, invokeArgs);
        } catch (Exception e) {
            return null;
        }
    }

    static int invokeStaticIntMethod(MethodInsnNode mi, ClassLoader loader) throws Exception {
        Class<?> cls = loader.loadClass(mi.owner.replace('/', '.'));
        java.lang.reflect.Method m = cls.getDeclaredMethod(mi.name);
        m.setAccessible(true);
        return ((Number) m.invoke(null)).intValue();
    }

    static void replaceWithDecryptedString(MethodNode mn, MethodInsnNode callInsn, String decrypted) {
        org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(callInsn.desc);
        int argCount = argTypes.length;

        InsnList patch = new InsnList();
        for (int i = 0; i < argCount; i++) {
            patch.add(new InsnNode(Opcodes.POP));
        }
        LdcInsnNode ldcNode = new LdcInsnNode(decrypted);
        patch.add(ldcNode);

        AbstractInsnNode afterCall = callInsn.getNext();
        mn.instructions.insertBefore(callInsn, patch);
        mn.instructions.remove(callInsn);

        AbstractInsnNode next = afterCall;
        while (next instanceof LabelNode || next instanceof LineNumberNode || next instanceof FrameNode) {
            next = next.getNext();
        }
        if (next instanceof MethodInsnNode internCall &&
            internCall.name.equals("intern") &&
            internCall.owner.equals("java/lang/String") &&
            internCall.getOpcode() == Opcodes.INVOKEVIRTUAL) {
            mn.instructions.remove(internCall);
        }
    }

    static AbstractInsnNode skipNonInsn(AbstractInsnNode node, boolean backwards) {
        while (node != null && (node instanceof LabelNode || node instanceof LineNumberNode ||
                                node instanceof FrameNode)) {
            node = backwards ? node.getPrevious() : node.getNext();
        }
        return node;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 2: Flow deobfuscation
    // ═══════════════════════════════════════════════════════════════════

    static void removeFakeTryCatch(ClassNode cn, MethodNode mn) {
        if (mn.tryCatchBlocks == null) return;
        Iterator<TryCatchBlockNode> it = mn.tryCatchBlocks.iterator();
        while (it.hasNext()) {
            TryCatchBlockNode tcb = it.next();
            if (tcb.handler != null) {
                AbstractInsnNode handlerInsn = skipNonInsn(tcb.handler.getNext(), false);

                // Pattern 1: handler immediately throws (catch-and-rethrow)
                if (handlerInsn != null && handlerInsn.getOpcode() == Opcodes.ATHROW) {
                    it.remove();
                    fakeTryCatchRemoved++;
                    continue;
                }

                // Pattern 2: degenerate try block (start == handler or end == handler)
                if (tcb.start == tcb.handler || tcb.end == tcb.handler) {
                    it.remove();
                    fakeTryCatchRemoved++;
                    continue;
                }

                // v2 Pattern 3: handler POPs exception and falls through (RuntimeException/Throwable)
                if (handlerInsn != null && handlerInsn.getOpcode() == Opcodes.POP) {
                    if (tcb.type != null && (tcb.type.equals("java/lang/RuntimeException") ||
                        tcb.type.equals("java/lang/Throwable"))) {
                        it.remove();
                        fakeTryCatchRemoved++;
                        continue;
                    }
                }

                // v2 Pattern 4: handler stores exception and immediately GOTOs (swallowed exception)
                if (handlerInsn != null && handlerInsn.getOpcode() == Opcodes.ASTORE) {
                    AbstractInsnNode afterStore = skipNonInsn(handlerInsn.getNext(), false);
                    if (afterStore != null && afterStore.getOpcode() == Opcodes.GOTO) {
                        if (tcb.type != null && (tcb.type.equals("java/lang/RuntimeException") ||
                            tcb.type.equals("java/lang/Throwable"))) {
                            it.remove();
                            fakeTryCatchRemoved++;
                            continue;
                        }
                    }
                }
            }
        }
    }

    static void removeDeadCode(ClassNode cn, MethodNode mn) {
        Set<LabelNode> branchTargets = new HashSet<>();
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof JumpInsnNode ji) {
                branchTargets.add(ji.label);
            } else if (insn instanceof TableSwitchInsnNode ts) {
                branchTargets.add(ts.dflt);
                branchTargets.addAll(ts.labels);
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                branchTargets.add(ls.dflt);
                branchTargets.addAll(ls.labels);
            }
        }
        if (mn.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                branchTargets.add(tcb.handler);
                branchTargets.add(tcb.start);
                branchTargets.add(tcb.end);
            }
        }

        List<AbstractInsnNode> toRemove = new ArrayList<>();
        boolean afterTerminator = false;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof LabelNode ln) {
                afterTerminator = !branchTargets.contains(ln) && afterTerminator;
                continue;
            }
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
            if (afterTerminator) {
                toRemove.add(insn);
                continue;
            }
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW ||
                opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN ||
                opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN ||
                opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN) {
                afterTerminator = true;
            }
        }
        for (AbstractInsnNode insn : toRemove) {
            mn.instructions.remove(insn);
            deadInstructionsRemoved++;
        }
    }

    static void simplifyOpaquePredicates(ClassNode cn, MethodNode mn) {
        // v2: No longer require zkmPackageName. Check for per-class opaque predicates too.
        // v3: runOpaqueResolution guards only Passes A/F/G (which need ZKM predicate sources).
        //     Passes B/C/D/E (constant folding, branch folding) always run — any class can
        //     CONSUME opaque predicates via cross-class INVOKESTATIC without defining them.
        boolean runOpaqueResolution = true;
        if (zkmPackageName == null) {
            boolean hasPerClassPredicates = false;
            for (MethodNode m : cn.methods) {
                if ((m.access & Opcodes.ACC_STATIC) != 0 && m.desc.equals("()I") &&
                    m.name.matches(".*00[4-7][0-9a-fA-F].*")) {
                    hasPerClassPredicates = true;
                    break;
                }
            }
            if (!hasPerClassPredicates) {
                for (FieldNode f : cn.fields) {
                    if ((f.access & Opcodes.ACC_STATIC) != 0 && f.desc.equals("I") &&
                        f.name.matches(".*00[4-7][0-9a-fA-F].*")) {
                        hasPerClassPredicates = true;
                        break;
                    }
                }
            }
            runOpaqueResolution = hasPerClassPredicates;
        }

        boolean changed = true;
        int iterations = 0;
        while (changed && iterations < 10) {
            changed = false;
            iterations++;

            // Pass A: Replace INVOKESTATIC to ZKM/per-class ()I methods with constants
            if (runOpaqueResolution) {
                List<MethodInsnNode> toReplace = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode mi)) continue;
                    if (mi.getOpcode() != Opcodes.INVOKESTATIC) continue;
                    if (!mi.desc.equals("()I")) continue;
                    if (zkmPackageName != null && mi.owner.startsWith(zkmPackageName + "/")) {
                        toReplace.add(mi);
                    } else if (isOpaquePredicateMethod(cn, mi)) {
                        toReplace.add(mi);
                    }
                }
                for (MethodInsnNode mi : toReplace) {
                    AbstractInsnNode replacement = resolveToConstant(mi);
                    if (replacement != null) {
                        mn.instructions.insertBefore(mi, replacement);
                        mn.instructions.remove(mi);
                        opaquePredicatesSimplified++;
                        changed = true;
                    }
                }
            }

            // v2 Pass F: Replace GETSTATIC of opaque predicate fields with constants
            if (runOpaqueResolution) {
                List<FieldInsnNode> fieldGets = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof FieldInsnNode fi)) continue;
                    if (fi.getOpcode() != Opcodes.GETSTATIC) continue;
                    if (!fi.desc.equals("I")) continue;
                    String key = fi.owner + "." + fi.name;
                    if (globalFieldCache.containsKey(key)) {
                        fieldGets.add(fi);
                    }
                }
                for (FieldInsnNode fi : fieldGets) {
                    String key = fi.owner + "." + fi.name;
                    int val = globalFieldCache.get(key);
                    mn.instructions.insertBefore(fi, makeConstantPush(val));
                    mn.instructions.remove(fi);
                    opaquePredicatesSimplified++;
                    changed = true;
                }
            }

            // v2 Pass G: Replace PUTSTATIC of opaque predicate fields with POP (dead stores)
            if (runOpaqueResolution) {
                List<FieldInsnNode> fieldPuts = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof FieldInsnNode fi)) continue;
                    if (fi.getOpcode() != Opcodes.PUTSTATIC) continue;
                    if (!fi.desc.equals("I")) continue;
                    String key = fi.owner + "." + fi.name;
                    if (globalFieldCache.containsKey(key)) {
                        fieldPuts.add(fi);
                    }
                }
                for (FieldInsnNode fi : fieldPuts) {
                    mn.instructions.insertBefore(fi, new InsnNode(Opcodes.POP));
                    mn.instructions.remove(fi);
                    opaquePredicatesSimplified++;
                    changed = true;
                }
            }

            // v2.8 Pass GOTO_DEAD: Remove dead GOTOs whose target is the immediately
            // following label. These are left behind by branch folding (B/C/D), goto
            // shortening, and fake try-catch removal. They block Pass E/E_CHAIN from
            // seeing adjacent constant instructions.
            {
                List<JumpInsnNode> deadGotos = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.GOTO && insn instanceof JumpInsnNode ji) {
                        AbstractInsnNode after = insn.getNext();
                        while (after != null && (after instanceof LabelNode ||
                               after instanceof LineNumberNode || after instanceof FrameNode)) {
                            if (after == ji.label) break;
                            after = after.getNext();
                        }
                        if (after == ji.label) {
                            deadGotos.add(ji);
                        }
                    }
                }
                for (JumpInsnNode ji : deadGotos) {
                    mn.instructions.remove(ji);
                    deadGotosRemoved++;
                    changed = true;
                }
            }

            // Pass E0: Expand DUP of constant push into duplicate constant push
            // Handles patterns like ICONST_3; DUP; DUP; IMUL where Pass E can't fold
            // because it sees DUP (opcode 89) as predecessor, not a constant push.
            {
                List<AbstractInsnNode> dups = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.DUP) dups.add(insn);
                }
                for (AbstractInsnNode dup : dups) {
                    AbstractInsnNode prev = skipNonInsn(dup.getPrevious(), true);
                    if (prev != null && isConstantPush(prev)) {
                        mn.instructions.insertBefore(dup, makeConstantPush(getConstantValue(prev)));
                        mn.instructions.remove(dup);
                        opaquePredicatesSimplified++;
                        changed = true;
                    }
                }
            }

            // Pass E: Fold constant arithmetic (IXOR, IADD, IMUL, etc.) on two constants
            // Runs before branch folding (B/C/D) so arithmetic produces constants that branches consume
            List<AbstractInsnNode> arithInsns = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (op == Opcodes.IADD || op == Opcodes.ISUB || op == Opcodes.IMUL ||
                    op == Opcodes.IDIV || op == Opcodes.IREM || op == Opcodes.IXOR ||
                    op == Opcodes.IAND || op == Opcodes.IOR || op == Opcodes.ISHL ||
                    op == Opcodes.ISHR || op == Opcodes.IUSHR) {
                    arithInsns.add(insn);
                }
            }
            for (AbstractInsnNode insn : arithInsns) {
                AbstractInsnNode val2 = skipNonInsn(insn.getPrevious(), true);
                if (val2 == null || !isConstantPush(val2)) continue;
                AbstractInsnNode val1 = skipNonInsn(val2.getPrevious(), true);
                if (val1 == null || !isConstantPush(val1)) continue;
                int a = getConstantValue(val1), b = getConstantValue(val2);
                int result;
                try {
                    result = switch (insn.getOpcode()) {
                        case Opcodes.IADD -> a + b;   case Opcodes.ISUB -> a - b;
                        case Opcodes.IMUL -> a * b;    case Opcodes.IDIV -> a / b;
                        case Opcodes.IREM -> a % b;    case Opcodes.IXOR -> a ^ b;
                        case Opcodes.IAND -> a & b;    case Opcodes.IOR -> a | b;
                        case Opcodes.ISHL -> a << b;   case Opcodes.ISHR -> a >> b;
                        case Opcodes.IUSHR -> a >>> b;
                        default -> throw new ArithmeticException();
                    };
                } catch (ArithmeticException e) { continue; }
                mn.instructions.insertBefore(val1, makeConstantPush(result));
                mn.instructions.remove(val1);
                mn.instructions.remove(val2);
                mn.instructions.remove(insn);
                opaquePredicatesSimplified++;
                changed = true;
            }

            // Pass E2: Fold unary operations on constant (INEG, I2B, I2C, I2S)
            List<AbstractInsnNode> unaryInsns = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (op == Opcodes.INEG || op == Opcodes.I2B || op == Opcodes.I2C || op == Opcodes.I2S) {
                    unaryInsns.add(insn);
                }
            }
            for (AbstractInsnNode insn : unaryInsns) {
                AbstractInsnNode prev = skipNonInsn(insn.getPrevious(), true);
                if (prev == null || !isConstantPush(prev)) continue;
                int val = getConstantValue(prev);
                int result = switch (insn.getOpcode()) {
                    case Opcodes.INEG -> -val;
                    case Opcodes.I2B -> (byte) val;
                    case Opcodes.I2C -> (char) val;
                    case Opcodes.I2S -> (short) val;
                    default -> val;
                };
                mn.instructions.insertBefore(prev, makeConstantPush(result));
                mn.instructions.remove(prev);
                mn.instructions.remove(insn);
                opaquePredicatesSimplified++;
                changed = true;
            }

            // v2.7 Pass E_CHAIN: Multi-instruction constant chain folding.
            // Walks backwards from each arithmetic op collecting a chain of constant pushes +
            // arithmetic. If the entire chain is constant-only, evaluates on a simulated stack
            // and replaces with a single constant. Catches all-constant expressions that span
            // more than 2 instructions (e.g. 0*(0+0)%2, 4*5%2, (0+1)*0%2).
            {
                List<AbstractInsnNode> chainTargets = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    int op = insn.getOpcode();
                    if (op == Opcodes.IREM || op == Opcodes.IMUL || op == Opcodes.IADD ||
                        op == Opcodes.ISUB || op == Opcodes.IXOR || op == Opcodes.IAND ||
                        op == Opcodes.IOR) {
                        chainTargets.add(insn);
                    }
                }
                for (AbstractInsnNode target : chainTargets) {
                    // Walk backwards collecting the expression tree as a linear chain
                    List<AbstractInsnNode> chain = new ArrayList<>();
                    chain.add(target);
                    int needed = 2; // binary ops need 2 operands
                    AbstractInsnNode cur = skipNonInsn(target.getPrevious(), true);
                    boolean allConst = true;
                    int maxChain = 30; // safety limit
                    while (cur != null && needed > 0 && allConst && maxChain-- > 0) {
                        if (isConstantPush(cur)) {
                            chain.add(0, cur);
                            needed--;
                        } else if (isBinaryIntArith(cur.getOpcode())) {
                            chain.add(0, cur);
                            needed++; // consumes 2, produces 1 → net need +1
                        } else if (cur.getOpcode() == Opcodes.INEG || cur.getOpcode() == Opcodes.I2B ||
                                   cur.getOpcode() == Opcodes.I2C || cur.getOpcode() == Opcodes.I2S) {
                            chain.add(0, cur);
                            // consumes 1, produces 1 → net 0
                        } else if (cur.getOpcode() == Opcodes.DUP) {
                            chain.add(0, cur);
                            needed--; // produces 1 extra value
                        } else if (cur.getOpcode() == Opcodes.GOTO && cur instanceof JumpInsnNode gotoJi) {
                            // v2.8: Check if this GOTO jumps to the next label (dead GOTO)
                            AbstractInsnNode afterGoto = cur.getNext();
                            boolean isDead = false;
                            while (afterGoto != null && (afterGoto instanceof LabelNode ||
                                   afterGoto instanceof LineNumberNode || afterGoto instanceof FrameNode)) {
                                if (afterGoto == gotoJi.label) { isDead = true; break; }
                                afterGoto = afterGoto.getNext();
                            }
                            if (!isDead) {
                                allConst = false;
                                break;
                            }
                            // Dead GOTO — skip it without adding to chain
                        } else {
                            allConst = false;
                            break;
                        }
                        cur = skipNonInsn(cur.getPrevious(), true);
                    }
                    if (!allConst || needed != 0) {
                        if (verboseMode && !allConst && target.getOpcode() == Opcodes.IREM && cur != null) {
                            System.out.println("[FDRS]   E_CHAIN: IREM blocked by opcode " +
                                cur.getOpcode() + " in " + cn.name + "." + mn.name);
                        }
                        continue;
                    }
                    if (chain.size() < 4) continue; // Pass E handles ≤3-instruction chains

                    // Evaluate the chain on a simulated stack
                    Deque<Integer> stack = new ArrayDeque<>();
                    boolean evalOk = true;
                    for (AbstractInsnNode ci : chain) {
                        if (isConstantPush(ci)) {
                            stack.push(getConstantValue(ci));
                        } else if (ci.getOpcode() == Opcodes.DUP) {
                            if (stack.isEmpty()) { evalOk = false; break; }
                            stack.push(stack.peek());
                        } else if (isBinaryIntArith(ci.getOpcode())) {
                            if (stack.size() < 2) { evalOk = false; break; }
                            int b = stack.pop(), a = stack.pop();
                            try {
                                int r = switch (ci.getOpcode()) {
                                    case Opcodes.IADD -> a + b; case Opcodes.ISUB -> a - b;
                                    case Opcodes.IMUL -> a * b; case Opcodes.IDIV -> a / b;
                                    case Opcodes.IREM -> a % b; case Opcodes.IXOR -> a ^ b;
                                    case Opcodes.IAND -> a & b; case Opcodes.IOR  -> a | b;
                                    case Opcodes.ISHL -> a << b; case Opcodes.ISHR -> a >> b;
                                    case Opcodes.IUSHR -> a >>> b;
                                    default -> throw new ArithmeticException();
                                };
                                stack.push(r);
                            } catch (ArithmeticException e) { evalOk = false; break; }
                        } else if (ci.getOpcode() == Opcodes.INEG) {
                            if (stack.isEmpty()) { evalOk = false; break; }
                            stack.push(-stack.pop());
                        } else if (ci.getOpcode() == Opcodes.I2B) {
                            if (stack.isEmpty()) { evalOk = false; break; }
                            stack.push((int)(byte)(int)stack.pop());
                        } else if (ci.getOpcode() == Opcodes.I2C) {
                            if (stack.isEmpty()) { evalOk = false; break; }
                            stack.push((int)(char)(int)stack.pop());
                        } else if (ci.getOpcode() == Opcodes.I2S) {
                            if (stack.isEmpty()) { evalOk = false; break; }
                            stack.push((int)(short)(int)stack.pop());
                        }
                    }
                    if (!evalOk || stack.size() != 1) continue;
                    int result = stack.pop();

                    // Replace entire chain with single constant
                    mn.instructions.insertBefore(chain.get(0), makeConstantPush(result));
                    for (AbstractInsnNode ci : chain) {
                        mn.instructions.remove(ci);
                    }
                    opaquePredicatesSimplified++;
                    chainsFolded++;
                    changed = true;
                }
            }

            // v2.6 Pass E_ALG: Algebraic identity folding — n*(n+k)%2 == 0 when k is odd
            // ZKM generates patterns like ILOAD n; ILOAD n; ICONST k; IADD; IMUL; ICONST 2; IREM
            // which always evaluate to 0 (product of consecutive-parity integers mod 2).
            // Also handles GETSTATIC variants and (n+k)*n%2 (commutative multiplication).
            // v2.8: Also handles ILOAD k when localIdx was initialized with an odd constant
            //       (from register field localization: ICONST odd; ISTORE idx at method entry).
            {
                // Build map of local variable init values from method entry
                // (localizeRegisterFields inserts ICONST val; ISTORE idx at the start)
                Map<Integer, Integer> localInitValues = new HashMap<>();
                for (AbstractInsnNode mi = mn.instructions.getFirst(); mi != null; mi = mi.getNext()) {
                    if (mi instanceof LabelNode || mi instanceof LineNumberNode || mi instanceof FrameNode)
                        continue;
                    if (isConstantPush(mi)) {
                        AbstractInsnNode store = skipNonInsn(mi.getNext(), false);
                        if (store != null && store.getOpcode() == Opcodes.ISTORE && store instanceof VarInsnNode vi) {
                            localInitValues.put(vi.var, getConstantValue(mi));
                            mi = store; // skip past the pair
                            continue;
                        }
                    }
                    break; // Stop at first non-init instruction
                }
                List<AbstractInsnNode> iremInsns = new ArrayList<>();
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.IREM) iremInsns.add(insn);
                }
                for (AbstractInsnNode irem : iremInsns) {
                    // Pattern: ... IMUL; ICONST_2; IREM → check if IMUL operands are n and n+k (k odd)
                    AbstractInsnNode two = skipNonInsn(irem.getPrevious(), true);
                    if (two == null || !isConstantPush(two) || getConstantValue(two) != 2) continue;
                    AbstractInsnNode imul = skipNonInsn(two.getPrevious(), true);
                    if (imul == null || imul.getOpcode() != Opcodes.IMUL) continue;

                    // Find the two operands of IMUL
                    // Operand 2 (top of stack before IMUL)
                    AbstractInsnNode op2end = skipNonInsn(imul.getPrevious(), true);
                    if (op2end == null) continue;

                    // Try pattern: op1; op1; ICONST k; IADD; IMUL; ICONST 2; IREM
                    // where op2end is the IADD
                    if (op2end.getOpcode() == Opcodes.IADD) {
                        AbstractInsnNode kNode = skipNonInsn(op2end.getPrevious(), true);
                        if (kNode == null) continue;
                        int k;
                        if (isConstantPush(kNode)) {
                            k = getConstantValue(kNode);
                        } else if (kNode.getOpcode() == Opcodes.ILOAD && kNode instanceof VarInsnNode kVar) {
                            // v2.8: Check if this ILOAD is a localized register field with known odd init
                            Integer initVal = localInitValues.get(kVar.var);
                            if (initVal == null) continue;
                            k = initVal;
                        } else {
                            continue;
                        }
                        if ((k & 1) == 0) continue; // k must be odd for identity to hold
                        AbstractInsnNode n2 = skipNonInsn(kNode.getPrevious(), true);
                        if (n2 == null) continue;

                        // v2.6: Handle DUP pattern: n; DUP; ICONST k; IADD; IMUL; 2; IREM
                        // DUP means both IMUL operands are the same value — identity always holds
                        if (n2.getOpcode() == Opcodes.DUP) {
                            AbstractInsnNode nOrig = skipNonInsn(n2.getPrevious(), true);
                            if (nOrig != null) {
                                // n; DUP → n is consumed by IMUL left, DUP'd copy by IADD left
                                mn.instructions.insertBefore(nOrig, new InsnNode(Opcodes.ICONST_0));
                                mn.instructions.remove(nOrig);
                                mn.instructions.remove(n2); // DUP
                                mn.instructions.remove(kNode);
                                mn.instructions.remove(op2end); // IADD
                                mn.instructions.remove(imul);
                                mn.instructions.remove(two);
                                mn.instructions.remove(irem);
                                opaquePredicatesSimplified++;
                                changed = true;
                                continue;
                            }
                        }

                        AbstractInsnNode n1 = skipNonInsn(n2.getPrevious(), true);
                        if (n1 == null) continue;

                        // Check if n1 and n2 refer to the same value
                        boolean sameValue = false;
                        if (n1.getOpcode() == n2.getOpcode()) {
                            if (n1 instanceof VarInsnNode v1 && n2 instanceof VarInsnNode v2 && v1.var == v2.var)
                                sameValue = true;
                            else if (isConstantPush(n1) && isConstantPush(n2) && getConstantValue(n1) == getConstantValue(n2))
                                sameValue = true;
                            else if (n1 instanceof FieldInsnNode f1 && n2 instanceof FieldInsnNode f2 &&
                                     f1.getOpcode() == Opcodes.GETSTATIC && f2.getOpcode() == Opcodes.GETSTATIC &&
                                     f1.owner.equals(f2.owner) && f1.name.equals(f2.name))
                                sameValue = true;
                        }

                        if (sameValue) {
                            // n*(n+k)%2 == 0: replace n1; n2; k; IADD; IMUL; 2; IREM with ICONST_0
                            mn.instructions.insertBefore(n1, new InsnNode(Opcodes.ICONST_0));
                            mn.instructions.remove(n1);
                            mn.instructions.remove(n2);
                            mn.instructions.remove(kNode);
                            mn.instructions.remove(op2end); // IADD
                            mn.instructions.remove(imul);
                            mn.instructions.remove(two);
                            mn.instructions.remove(irem);
                            opaquePredicatesSimplified++;
                            changed = true;
                            continue;
                        }
                    }

                    // Try reverse pattern: op1; ICONST k; IADD; op1; IMUL; ICONST 2; IREM
                    // where (n+k)*n form — op2end is a load (the second n)
                    // Also: op1; DUP; ICONST k; IADD; SWAP; IMUL — but SWAP changes stack layout
                    if (op2end instanceof VarInsnNode || (op2end instanceof FieldInsnNode fi2 && fi2.getOpcode() == Opcodes.GETSTATIC) ||
                        isConstantPush(op2end)) {
                        AbstractInsnNode iadd = skipNonInsn(op2end.getPrevious(), true);
                        if (iadd != null && iadd.getOpcode() == Opcodes.IADD) {
                            AbstractInsnNode kNode = skipNonInsn(iadd.getPrevious(), true);
                            int k = Integer.MIN_VALUE;
                            if (kNode != null && isConstantPush(kNode)) {
                                k = getConstantValue(kNode);
                            } else if (kNode != null && kNode.getOpcode() == Opcodes.ILOAD && kNode instanceof VarInsnNode kVar2) {
                                // v2.8: ILOAD from localized register field with known odd init
                                Integer initVal2 = localInitValues.get(kVar2.var);
                                if (initVal2 != null) k = initVal2;
                            }
                            if (k != Integer.MIN_VALUE) {
                                if ((k & 1) != 0) { // k must be odd
                                    AbstractInsnNode n1 = skipNonInsn(kNode.getPrevious(), true);
                                    if (n1 != null) {
                                        boolean sameValue = false;
                                        if (n1.getOpcode() == op2end.getOpcode()) {
                                            if (n1 instanceof VarInsnNode v1 && op2end instanceof VarInsnNode v2 && v1.var == v2.var)
                                                sameValue = true;
                                            else if (isConstantPush(n1) && isConstantPush(op2end) && getConstantValue(n1) == getConstantValue(op2end))
                                                sameValue = true;
                                            else if (n1 instanceof FieldInsnNode f1 && op2end instanceof FieldInsnNode f2 &&
                                                     f1.getOpcode() == Opcodes.GETSTATIC && f2.getOpcode() == Opcodes.GETSTATIC &&
                                                     f1.owner.equals(f2.owner) && f1.name.equals(f2.name))
                                                sameValue = true;
                                        }
                                        if (sameValue) {
                                            // (n+k)*n%2 == 0
                                            mn.instructions.insertBefore(n1, new InsnNode(Opcodes.ICONST_0));
                                            mn.instructions.remove(n1);
                                            mn.instructions.remove(kNode);
                                            mn.instructions.remove(iadd);
                                            mn.instructions.remove(op2end);
                                            mn.instructions.remove(imul);
                                            mn.instructions.remove(two);
                                            mn.instructions.remove(irem);
                                            opaquePredicatesSimplified++;
                                            changed = true;
                                            continue;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Pass E3: Analyzer-based constant folding (handles join-point blocks)
            // Runs the full ASM dataflow Analyzer with ConstInterp, which propagates constants
            // across GOTOs and through join points — unlike Passes E0/E/E2 which only look at
            // instruction-list predecessors. Replaces branches/switches/arithmetic where the
            // Analyzer proves operands are constant, using POP to consume cross-block values.
            try {
                int savedMaxStack = mn.maxStack, savedMaxLocals = mn.maxLocals;
                mn.maxStack = Math.max(mn.maxStack, 16);
                mn.maxLocals = Math.max(mn.maxLocals, 16);
                Analyzer<CVal> analyzer = new Analyzer<>(new ConstInterp(null, globalMethodCache));
                Frame<CVal>[] frames = analyzer.analyze(cn.name, mn);
                mn.maxStack = savedMaxStack; mn.maxLocals = savedMaxLocals;

                // Pre-build instruction → index map for O(1) lookups (only when needed)
                Map<AbstractInsnNode, Integer> insnIndex = null;
                if (runOpaqueResolution) {
                    insnIndex = new IdentityHashMap<>();
                    for (int ii = 0; ii < mn.instructions.size(); ii++) {
                        insnIndex.put(mn.instructions.get(ii), ii);
                    }
                }

                // Collect replacements (don't modify during iteration)
                List<AbstractInsnNode> e3targets = new ArrayList<>();
                for (int i = 0; i < mn.instructions.size(); i++) {
                    AbstractInsnNode insn = mn.instructions.get(i);
                    if (frames[i] == null) continue;
                    int op = insn.getOpcode();
                    Frame<CVal> f = frames[i];

                    // Switches with known constant key
                    if ((insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode)
                        && f.getStackSize() >= 1) {
                        CVal top = f.getStack(f.getStackSize() - 1);
                        if (top != null && top.isInt()) e3targets.add(insn);
                    }
                    // IF_ICMPxx with two known constant operands
                    else if (insn instanceof JumpInsnNode && op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE
                             && f.getStackSize() >= 2) {
                        CVal b = f.getStack(f.getStackSize() - 1);
                        CVal a = f.getStack(f.getStackSize() - 2);
                        if (a != null && a.isInt() && b != null && b.isInt()) e3targets.add(insn);
                    }
                    // IFxx with known constant operand
                    else if (insn instanceof JumpInsnNode && op >= Opcodes.IFEQ && op <= Opcodes.IFLE
                             && f.getStackSize() >= 1) {
                        CVal top = f.getStack(f.getStackSize() - 1);
                        if (top != null && top.isInt()) e3targets.add(insn);
                    }
                    // Binary int arithmetic with two known constants
                    else if (isBinaryIntArith(op) && f.getStackSize() >= 2) {
                        CVal b = f.getStack(f.getStackSize() - 1);
                        CVal a = f.getStack(f.getStackSize() - 2);
                        if (a != null && a.isInt() && b != null && b.isInt()) {
                            e3targets.add(insn);
                        }
                    }
                    // Unary int ops with known constant
                    else if ((op == Opcodes.INEG || op == Opcodes.I2B || op == Opcodes.I2C || op == Opcodes.I2S)
                             && f.getStackSize() >= 1) {
                        CVal top = f.getStack(f.getStackSize() - 1);
                        if (top != null && top.isInt()) {
                            e3targets.add(insn);
                        }
                    }
                }

                for (AbstractInsnNode insn : e3targets) {
                    // Re-lookup frame index (instruction may have moved)
                    int idx = -1;
                    int ci = 0;
                    for (AbstractInsnNode cur = mn.instructions.getFirst(); cur != null; cur = cur.getNext(), ci++) {
                        if (cur == insn) { idx = ci; break; }
                    }
                    if (idx < 0 || idx >= frames.length || frames[idx] == null) continue;
                    Frame<CVal> f = frames[idx];
                    int op = insn.getOpcode();

                    if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                        CVal top = f.getStack(f.getStackSize() - 1);
                        if (top == null || !top.isInt()) continue;
                        int val = top.intVal();
                        LabelNode target;
                        if (insn instanceof TableSwitchInsnNode ts) {
                            target = (val >= ts.min && val <= ts.max) ? ts.labels.get(val - ts.min) : ts.dflt;
                        } else {
                            LookupSwitchInsnNode ls = (LookupSwitchInsnNode) insn;
                            target = ls.dflt;
                            for (int j = 0; j < ls.keys.size(); j++) {
                                if (ls.keys.get(j) == val) { target = ls.labels.get(j); break; }
                            }
                        }
                        // POP (consume cross-block value) + GOTO target
                        mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                        mn.instructions.insertBefore(insn, new JumpInsnNode(Opcodes.GOTO, target));
                        mn.instructions.remove(insn);
                        opaquePredicatesSimplified++;
                        changed = true;
                    }
                    else if (insn instanceof JumpInsnNode ji && op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE) {
                        CVal b = f.getStack(f.getStackSize() - 1);
                        CVal a = f.getStack(f.getStackSize() - 2);
                        if (a == null || !a.isInt() || b == null || !b.isInt()) continue;
                        boolean branchTaken = switch (op) {
                            case Opcodes.IF_ICMPEQ -> a.intVal() == b.intVal();
                            case Opcodes.IF_ICMPNE -> a.intVal() != b.intVal();
                            case Opcodes.IF_ICMPLT -> a.intVal() < b.intVal();
                            case Opcodes.IF_ICMPGE -> a.intVal() >= b.intVal();
                            case Opcodes.IF_ICMPGT -> a.intVal() > b.intVal();
                            case Opcodes.IF_ICMPLE -> a.intVal() <= b.intVal();
                            default -> false;
                        };
                        // POP; POP (consume 2 cross-block values) + GOTO or NOP
                        mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                        mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                        if (branchTaken) {
                            mn.instructions.insertBefore(insn, new JumpInsnNode(Opcodes.GOTO, ji.label));
                        }
                        mn.instructions.remove(insn);
                        opaquePredicatesSimplified++;
                        changed = true;
                    }
                    else if (insn instanceof JumpInsnNode ji && op >= Opcodes.IFEQ && op <= Opcodes.IFLE) {
                        CVal top = f.getStack(f.getStackSize() - 1);
                        if (top == null || !top.isInt()) continue;
                        boolean branchTaken = switch (op) {
                            case Opcodes.IFEQ -> top.intVal() == 0;
                            case Opcodes.IFNE -> top.intVal() != 0;
                            case Opcodes.IFLT -> top.intVal() < 0;
                            case Opcodes.IFGE -> top.intVal() >= 0;
                            case Opcodes.IFGT -> top.intVal() > 0;
                            case Opcodes.IFLE -> top.intVal() <= 0;
                            default -> false;
                        };
                        mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                        if (branchTaken) {
                            mn.instructions.insertBefore(insn, new JumpInsnNode(Opcodes.GOTO, ji.label));
                        }
                        mn.instructions.remove(insn);
                        opaquePredicatesSimplified++;
                        changed = true;
                    }
                    else if (isBinaryIntArith(op)) {
                        CVal b = f.getStack(f.getStackSize() - 1);
                        CVal a = f.getStack(f.getStackSize() - 2);
                        if (a == null || !a.isInt() || b == null || !b.isInt()) continue;
                        int result;
                        try {
                            result = switch (op) {
                                case Opcodes.IADD -> a.intVal() + b.intVal();
                                case Opcodes.ISUB -> a.intVal() - b.intVal();
                                case Opcodes.IMUL -> a.intVal() * b.intVal();
                                case Opcodes.IDIV -> a.intVal() / b.intVal();
                                case Opcodes.IREM -> a.intVal() % b.intVal();
                                case Opcodes.IXOR -> a.intVal() ^ b.intVal();
                                case Opcodes.IAND -> a.intVal() & b.intVal();
                                case Opcodes.IOR  -> a.intVal() | b.intVal();
                                case Opcodes.ISHL -> a.intVal() << b.intVal();
                                case Opcodes.ISHR -> a.intVal() >> b.intVal();
                                case Opcodes.IUSHR -> a.intVal() >>> b.intVal();
                                default -> throw new ArithmeticException();
                            };
                        } catch (ArithmeticException e) { continue; }
                        // If both operands are adjacent constant pushes, remove them directly.
                        // Otherwise use POP; POP to consume cross-block operands.
                        AbstractInsnNode p2 = skipNonInsn(insn.getPrevious(), true);
                        AbstractInsnNode p1 = (p2 != null) ? skipNonInsn(p2.getPrevious(), true) : null;
                        if (p2 != null && isConstantPush(p2) && p1 != null && isConstantPush(p1)) {
                            mn.instructions.insertBefore(p1, makeConstantPush(result));
                            mn.instructions.remove(p1);
                            mn.instructions.remove(p2);
                        } else {
                            mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                            mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                            mn.instructions.insertBefore(insn, makeConstantPush(result));
                        }
                        mn.instructions.remove(insn);
                        opaquePredicatesSimplified++;
                        changed = true;
                    }
                    else if (op == Opcodes.INEG || op == Opcodes.I2B || op == Opcodes.I2C || op == Opcodes.I2S) {
                        CVal top = f.getStack(f.getStackSize() - 1);
                        if (top == null || !top.isInt()) continue;
                        int result = switch (op) {
                            case Opcodes.INEG -> -top.intVal();
                            case Opcodes.I2B -> (byte) top.intVal();
                            case Opcodes.I2C -> (char) top.intVal();
                            case Opcodes.I2S -> (short) top.intVal();
                            default -> top.intVal();
                        };
                        AbstractInsnNode prev = skipNonInsn(insn.getPrevious(), true);
                        if (prev != null && isConstantPush(prev)) {
                            mn.instructions.insertBefore(prev, makeConstantPush(result));
                            mn.instructions.remove(prev);
                        } else {
                            mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                            mn.instructions.insertBefore(insn, makeConstantPush(result));
                        }
                        mn.instructions.remove(insn);
                        opaquePredicatesSimplified++;
                        changed = true;
                    }
                }

                // v2.7 Pass E_ALG2: Cross-block algebraic identity via Analyzer.
                // When IREM has divisor=2 and unknown dividend, walk backwards to find IMUL,
                // then IADD. Uses Analyzer frames + CVal.sourceVar to match operands cross-block.
                // Folds n*(n+k)%2 → 0 when k is odd (Families C/D/E).
                // Only run on methods with opaque predicates to avoid overhead on clean code.
                if (runOpaqueResolution) for (int i = 0; i < mn.instructions.size(); i++) {
                    AbstractInsnNode insn = mn.instructions.get(i);
                    if (insn.getOpcode() != Opcodes.IREM) continue;
                    if (i >= frames.length || frames[i] == null) continue;
                    Frame<CVal> f = frames[i];
                    if (f.getStackSize() < 2) continue;
                    CVal divisor = f.getStack(f.getStackSize() - 1);
                    CVal dividend = f.getStack(f.getStackSize() - 2);
                    if (divisor == null || !divisor.isInt() || divisor.intVal() != 2) continue;
                    if (dividend != null && dividend.isInt()) continue; // constant — already handled by E3

                    if (isProvablyEvenProduct(mn, insn, frames, insnIndex)) {
                        // Replace: POP dividend, POP divisor(2), push 0
                        // But both are already on the stack, so: POP; POP; ICONST_0
                        mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                        mn.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                        mn.instructions.insertBefore(insn, new InsnNode(Opcodes.ICONST_0));
                        mn.instructions.remove(insn);
                        opaquePredicatesSimplified++;
                        crossBlockAlgFolded++;
                        changed = true;
                    }
                }
            } catch (Throwable e3err) {
                // Analyzer can fail on complex/malformed bytecode — fall through to Passes B/C/D
                analyzerFailures++;
                if (verboseMode) {
                    System.err.println("[FDRS]   E3 Analyzer failed: " + cn.name + "." +
                        mn.name + mn.desc + ": " + e3err.getClass().getSimpleName() +
                        ": " + e3err.getMessage());
                }
            }

            // Pass B: Fold TABLESWITCH/LOOKUPSWITCH on constant operands to single GOTO
            List<AbstractInsnNode> switches = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                    switches.add(insn);
                }
            }
            for (AbstractInsnNode insn : switches) {
                AbstractInsnNode prev = skipNonInsn(insn.getPrevious(), true);
                if (prev == null || !isConstantPush(prev)) continue;
                int val = getConstantValue(prev);
                LabelNode target;
                if (insn instanceof TableSwitchInsnNode ts) {
                    target = (val >= ts.min && val <= ts.max) ? ts.labels.get(val - ts.min) : ts.dflt;
                } else {
                    LookupSwitchInsnNode ls = (LookupSwitchInsnNode) insn;
                    target = ls.dflt;
                    for (int i = 0; i < ls.keys.size(); i++) {
                        if (ls.keys.get(i) == val) { target = ls.labels.get(i); break; }
                    }
                }
                JumpInsnNode jump = new JumpInsnNode(Opcodes.GOTO, target);
                mn.instructions.insertBefore(prev, jump);
                mn.instructions.remove(prev);
                mn.instructions.remove(insn);
                opaquePredicatesSimplified++;
                changed = true;
            }

            // Pass C: Fold IF_ICMPxx on two constant operands to GOTO or fall-through
            List<JumpInsnNode> cmpJumps = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof JumpInsnNode ji && ji.getOpcode() >= Opcodes.IF_ICMPEQ && ji.getOpcode() <= Opcodes.IF_ICMPLE) {
                    cmpJumps.add(ji);
                }
            }
            for (JumpInsnNode ji : cmpJumps) {
                AbstractInsnNode val2 = skipNonInsn(ji.getPrevious(), true);
                if (val2 == null || !isConstantPush(val2)) continue;
                AbstractInsnNode val1 = skipNonInsn(val2.getPrevious(), true);
                if (val1 == null || !isConstantPush(val1)) continue;
                int a = getConstantValue(val1), b = getConstantValue(val2);
                boolean branchTaken = switch (ji.getOpcode()) {
                    case Opcodes.IF_ICMPEQ -> a == b;  case Opcodes.IF_ICMPNE -> a != b;
                    case Opcodes.IF_ICMPLT -> a < b;    case Opcodes.IF_ICMPGE -> a >= b;
                    case Opcodes.IF_ICMPGT -> a > b;    case Opcodes.IF_ICMPLE -> a <= b;
                    default -> false;
                };
                if (branchTaken) {
                    mn.instructions.insertBefore(val1, new JumpInsnNode(Opcodes.GOTO, ji.label));
                } else {
                    mn.instructions.insertBefore(val1, new InsnNode(Opcodes.NOP));
                }
                mn.instructions.remove(val1);
                mn.instructions.remove(val2);
                mn.instructions.remove(ji);
                opaquePredicatesSimplified++;
                changed = true;
            }

            // Pass D: Fold IFEQ/IFNE/IFLT/IFGE/IFGT/IFLE on single constant
            List<JumpInsnNode> unaryJumps = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof JumpInsnNode ji) {
                    int op = ji.getOpcode();
                    if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE) {
                        unaryJumps.add(ji);
                    }
                }
            }
            for (JumpInsnNode ji : unaryJumps) {
                AbstractInsnNode prev = skipNonInsn(ji.getPrevious(), true);
                if (prev == null || !isConstantPush(prev)) continue;
                int val = getConstantValue(prev);
                boolean branchTaken = switch (ji.getOpcode()) {
                    case Opcodes.IFEQ -> val == 0;  case Opcodes.IFNE -> val != 0;
                    case Opcodes.IFLT -> val < 0;    case Opcodes.IFGE -> val >= 0;
                    case Opcodes.IFGT -> val > 0;    case Opcodes.IFLE -> val <= 0;
                    default -> false;
                };
                if (branchTaken) {
                    mn.instructions.insertBefore(prev, new JumpInsnNode(Opcodes.GOTO, ji.label));
                } else {
                    mn.instructions.insertBefore(prev, new InsnNode(Opcodes.NOP));
                }
                mn.instructions.remove(prev);
                mn.instructions.remove(ji);
                opaquePredicatesSimplified++;
                changed = true;
            }
        }

    }

    /**
     * Localize register fields: convert PUTSTATIC/GETSTATIC for register fields to ISTORE/ILOAD.
     * This lets the ASM Analyzer's copyOperation propagate values through the dataflow,
     * resolving predicates at merge points (same value → resolves; different → UNKNOWN = safe).
     * Skips <clinit> methods. Idempotent: returns 0 on subsequent rounds.
     */
    static int localizeRegisterFields(ClassNode cn, MethodNode mn) {
        if (mn.name.equals("<clinit>")) return 0;

        // Find register fields used in this method
        Map<String, Integer> fieldsUsed = new LinkedHashMap<>(); // fieldKey → initVal
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fi &&
                (fi.getOpcode() == Opcodes.GETSTATIC || fi.getOpcode() == Opcodes.PUTSTATIC) &&
                fi.desc.equals("I") && fi.owner.equals(cn.name)) {
                String key = cn.name + "." + fi.name;
                if (registerFieldInitialValues.containsKey(key) && !fieldsUsed.containsKey(key)) {
                    fieldsUsed.put(key, registerFieldInitialValues.get(key));
                }
            }
        }
        if (fieldsUsed.isEmpty()) return 0;

        // Allocate a local variable for each register field
        Map<String, Integer> fieldToLocal = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : fieldsUsed.entrySet()) {
            int localIdx = mn.maxLocals++;
            fieldToLocal.put(entry.getKey(), localIdx);
        }

        // Insert initialization at method entry: push initVal; ISTORE localN
        AbstractInsnNode insertPoint = mn.instructions.getFirst();
        for (Map.Entry<String, Integer> entry : fieldsUsed.entrySet()) {
            int initVal = entry.getValue();
            int localIdx = fieldToLocal.get(entry.getKey());
            mn.instructions.insertBefore(insertPoint, makeConstantPush(initVal));
            mn.instructions.insertBefore(insertPoint, new VarInsnNode(Opcodes.ISTORE, localIdx));
        }

        // Replace all PUTSTATIC→ISTORE, GETSTATIC→ILOAD for these fields
        int replaced = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof FieldInsnNode fi && fi.desc.equals("I") && fi.owner.equals(cn.name)) {
                String key = cn.name + "." + fi.name;
                Integer localIdx = fieldToLocal.get(key);
                if (localIdx != null) {
                    if (fi.getOpcode() == Opcodes.GETSTATIC) {
                        mn.instructions.insertBefore(fi, new VarInsnNode(Opcodes.ILOAD, localIdx));
                        mn.instructions.remove(fi);
                        replaced++;
                    } else if (fi.getOpcode() == Opcodes.PUTSTATIC) {
                        mn.instructions.insertBefore(fi, new VarInsnNode(Opcodes.ISTORE, localIdx));
                        mn.instructions.remove(fi);
                        replaced++;
                    }
                }
            }
            insn = next;
        }
        return replaced;
    }

    // v2: Dead expression removal
    static void removeDeadExpressions(ClassNode cn, MethodNode mn) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn.getOpcode() != Opcodes.POP) { insn = next; continue; }

                AbstractInsnNode prev = skipNonInsn(insn.getPrevious(), true);
                if (prev == null) { insn = next; continue; }

                // Pattern: optional I2C/I2B/I2S before POP
                AbstractInsnNode castNode = null;
                AbstractInsnNode exprNode = prev;
                if (prev.getOpcode() == Opcodes.I2C || prev.getOpcode() == Opcodes.I2B ||
                    prev.getOpcode() == Opcodes.I2S) {
                    castNode = prev;
                    exprNode = skipNonInsn(prev.getPrevious(), true);
                    if (exprNode == null) { insn = next; continue; }
                }

                List<AbstractInsnNode> toRemove = new ArrayList<>();

                // Pattern A: single constant → [cast] → POP
                if (isConstantPush(exprNode)) {
                    toRemove.add(exprNode);
                    if (castNode != null) toRemove.add(castNode);
                    toRemove.add(insn);
                }
                // Pattern B: const, const, binary-arith → [cast] → POP
                else if (isBinaryIntArith(exprNode.getOpcode())) {
                    AbstractInsnNode op2 = skipNonInsn(exprNode.getPrevious(), true);
                    if (op2 != null && isConstantPush(op2)) {
                        AbstractInsnNode op1 = skipNonInsn(op2.getPrevious(), true);
                        if (op1 != null && isConstantPush(op1)) {
                            toRemove.add(op1);
                            toRemove.add(op2);
                            toRemove.add(exprNode);
                            if (castNode != null) toRemove.add(castNode);
                            toRemove.add(insn);
                        }
                    }
                }
                // Pattern C: GETSTATIC (opaque field) → [cast] → POP
                else if (exprNode instanceof FieldInsnNode fi &&
                         fi.getOpcode() == Opcodes.GETSTATIC && fi.desc.equals("I") &&
                         globalFieldCache.containsKey(fi.owner + "." + fi.name)) {
                    toRemove.add(exprNode);
                    if (castNode != null) toRemove.add(castNode);
                    toRemove.add(insn);
                }

                if (!toRemove.isEmpty()) {
                    for (AbstractInsnNode n : toRemove) {
                        mn.instructions.remove(n);
                    }
                    deadExpressionsRemoved++;
                    changed = true;
                    // restart scan since we modified the list
                    break;
                }

                insn = next;
            }
        }
    }

    static boolean isBinaryIntArith(int opcode) {
        return opcode == Opcodes.IADD || opcode == Opcodes.ISUB || opcode == Opcodes.IMUL ||
               opcode == Opcodes.IDIV || opcode == Opcodes.IREM || opcode == Opcodes.IXOR ||
               opcode == Opcodes.IAND || opcode == Opcodes.IOR || opcode == Opcodes.ISHL ||
               opcode == Opcodes.ISHR || opcode == Opcodes.IUSHR;
    }

    /**
     * v2.7: Check if the dividend of an IREM %2 is provably even because it's
     * a product n*(n+k) where k is odd. Uses Analyzer frames and CVal.sourceVar
     * to match operands across basic block boundaries.
     *
     * Handles patterns (in instruction list order):
     *   Case 1: ... IADD; IMUL; ICONST_2; IREM  (IADD is top IMUL operand)
     *   Case 2: ... IMUL preceded by IADD further back (IADD is bottom IMUL operand)
     */
    static boolean isProvablyEvenProduct(MethodNode mn, AbstractInsnNode irem,
                                           Frame<CVal>[] frames, Map<AbstractInsnNode, Integer> insnIndex) {
        // Find IMUL by scanning backwards from IREM, skipping the ICONST_2
        AbstractInsnNode two = skipNonInsn(irem.getPrevious(), true);
        if (two == null) return false;

        // Find IMUL — it may not be directly adjacent (cross-block: value came via stack)
        AbstractInsnNode imul = null;
        if (isConstantPush(two) && getConstantValue(two) == 2) {
            AbstractInsnNode candidate = skipNonInsn(two.getPrevious(), true);
            if (candidate != null && candidate.getOpcode() == Opcodes.IMUL) {
                imul = candidate;
            }
        }
        // If IMUL not adjacent, scan backwards (max 20 instructions)
        if (imul == null) {
            AbstractInsnNode scan = irem.getPrevious();
            for (int steps = 0; steps < 20 && scan != null; steps++) {
                if (scan.getOpcode() == Opcodes.IMUL) { imul = scan; break; }
                int sop = scan.getOpcode();
                if (sop == Opcodes.GOTO || sop == Opcodes.ATHROW ||
                    (sop >= Opcodes.IRETURN && sop <= Opcodes.RETURN) ||
                    scan instanceof MethodInsnNode || scan instanceof InvokeDynamicInsnNode) break;
                scan = scan.getPrevious();
            }
        }
        if (imul == null) return false;

        // O(1) index lookup via pre-built map
        Integer imulIdx = insnIndex.get(imul);
        if (imulIdx == null || imulIdx >= frames.length || frames[imulIdx] == null) return false;
        Frame<CVal> imulFrame = frames[imulIdx];
        if (imulFrame.getStackSize() < 2) return false;

        CVal mulA = imulFrame.getStack(imulFrame.getStackSize() - 2);
        CVal mulB = imulFrame.getStack(imulFrame.getStackSize() - 1);

        // Case 1: IADD right before IMUL (top operand = n+k, bottom = n)
        AbstractInsnNode beforeImul = skipNonInsn(imul.getPrevious(), true);
        if (beforeImul != null && beforeImul.getOpcode() == Opcodes.IADD) {
            if (checkIaddMulIdentity(beforeImul, mulA, mulB, frames, insnIndex)) return true;
        }

        // Case 2: IADD further back
        if (beforeImul != null && beforeImul.getOpcode() != Opcodes.IADD) {
            AbstractInsnNode scan = beforeImul;
            for (int steps = 0; steps < 15 && scan != null; steps++) {
                if (scan.getOpcode() == Opcodes.IADD) {
                    if (checkIaddMulIdentity(scan, mulA, mulB, frames, insnIndex)) return true;
                    break;
                }
                int sop = scan.getOpcode();
                if (sop == Opcodes.GOTO || sop == Opcodes.ATHROW ||
                    (sop >= Opcodes.IRETURN && sop <= Opcodes.RETURN)) break;
                scan = skipNonInsn(scan.getPrevious(), true);
            }
        }

        return false;
    }

    /**
     * Check if IADD at the given instruction produces n+k (k odd) where n matches
     * the other IMUL operand, proving the product is even.
     */
    static boolean checkIaddMulIdentity(AbstractInsnNode iadd,
                                         CVal mulA, CVal mulB, Frame<CVal>[] frames,
                                         Map<AbstractInsnNode, Integer> insnIndex) {
        Integer iaddIdx = insnIndex.get(iadd);
        if (iaddIdx == null || iaddIdx >= frames.length || frames[iaddIdx] == null) return false;
        Frame<CVal> iaddFrame = frames[iaddIdx];
        if (iaddFrame.getStackSize() < 2) return false;

        CVal addA = iaddFrame.getStack(iaddFrame.getStackSize() - 2); // first IADD operand
        CVal addB = iaddFrame.getStack(iaddFrame.getStackSize() - 1); // second IADD operand

        // Check both orderings: addB=odd_const with addA=n, or addA=odd_const with addB=n
        if (addB != null && addB.isInt() && (addB.intVal() & 1) != 0) {
            // addA is "n" in n+k — does it match the other IMUL operand (mulA)?
            if (sameSource(addA, mulA)) return true;
            if (sameSource(addA, mulB)) return true; // commutative IMUL
        }
        if (addA != null && addA.isInt() && (addA.intVal() & 1) != 0) {
            // addB is "n" in k+n — does it match the other IMUL operand?
            if (sameSource(addB, mulA)) return true;
            if (sameSource(addB, mulB)) return true;
        }

        return false;
    }

    /**
     * Check if two CVal values provably came from the same source.
     * Uses CVal.sourceVar (tracked from ILOAD) and constant equality.
     */
    static boolean sameSource(CVal a, CVal b) {
        if (a == null || b == null) return false;
        // Both are the same known constant
        if (a.isInt() && b.isInt() && a.intVal() == b.intVal()) return true;
        // Both come from ILOAD of the same local variable
        if (a.sourceVar() >= 0 && a.sourceVar() == b.sourceVar()) return true;
        // Identity: same CVal object (shared reference through Analyzer)
        if (a == b && a != CVal.UNKNOWN1 && a != CVal.UNKNOWN2) return true;
        return false;
    }

    // Helper: check if a MethodInsnNode calls a per-class opaque predicate method
    static boolean isOpaquePredicateMethod(ClassNode cn, MethodInsnNode mi) {
        if (!mi.desc.equals("()I")) return false;
        if (!mi.name.matches(".*00[4-7][0-9a-fA-F].*")) return false;

        // Self-call: predicate method in same class
        if (mi.owner.equals(cn.name)) return true;

        // Cached in globalMethodCache (resolved during Phase 1 or cache population)
        if (globalMethodCache.containsKey(mi.owner + "." + mi.name)) return true;

        // Cross-class: check if target class has matching static ()I method
        if (allClasses != null && allClasses.containsKey(mi.owner)) {
            ClassNode targetCn = allClasses.get(mi.owner);
            for (MethodNode m : targetCn.methods) {
                if (m.name.equals(mi.name) && m.desc.equals("()I") &&
                    (m.access & Opcodes.ACC_STATIC) != 0) return true;
            }
        }

        return false;
    }

    // Helper: try to resolve an INVOKESTATIC ()I to a constant
    static AbstractInsnNode resolveToConstant(MethodInsnNode mi) {
        String key = mi.owner + "." + mi.name;
        Integer cached = globalMethodCache.get(key);
        if (cached != null) {
            return makeConstantPush(cached);
        }

        // Fallback: bytecode-level analysis of the target method
        if (allClasses != null) {
            Integer val = analyzeMethodReturnValue(mi.owner, mi.name);
            if (val != null) {
                globalMethodCache.put(key, val);
                return makeConstantPush(val);
            }
        }

        return null;
    }

    static Integer analyzeMethodReturnValue(String owner, String name) {
        if (allClasses == null) return null;
        ClassNode cn = allClasses.get(owner);
        if (cn == null) return null;

        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name) && mn.desc.equals("()I") &&
                (mn.access & Opcodes.ACC_STATIC) != 0) {
                target = mn;
                break;
            }
        }
        if (target == null) return null;

        List<AbstractInsnNode> insns = new ArrayList<>();
        for (AbstractInsnNode insn : target.instructions) {
            if (insn instanceof LabelNode || insn instanceof LineNumberNode ||
                insn instanceof FrameNode || insn.getOpcode() == Opcodes.NOP) continue;
            insns.add(insn);
        }

        // Pattern: simple constant return
        if (insns.size() == 2 && isConstantPush(insns.get(0)) &&
            insns.get(1).getOpcode() == Opcodes.IRETURN) {
            return getConstantValue(insns.get(0));
        }

        // Complex: run ConstInterp analyzer
        try {
            Map<String, Integer> tempCache = new HashMap<>();
            int savedMaxStack = target.maxStack;
            int savedMaxLocals = target.maxLocals;
            target.maxStack = Math.max(target.maxStack, target.instructions.size());
            target.maxLocals = Math.max(target.maxLocals, target.instructions.size());

            Analyzer<CVal> analyzer = new Analyzer<>(new ConstInterp(ClassLoader.getPlatformClassLoader(), tempCache));
            Frame<CVal>[] frames = analyzer.analyze(owner, target);

            target.maxStack = savedMaxStack;
            target.maxLocals = savedMaxLocals;

            for (int i = 0; i < target.instructions.size(); i++) {
                AbstractInsnNode insn = target.instructions.get(i);
                if (insn.getOpcode() == Opcodes.IRETURN && frames[i] != null) {
                    int stackSize = frames[i].getStackSize();
                    if (stackSize > 0) {
                        CVal top = frames[i].getStack(stackSize - 1);
                        if (top.isInt()) return top.intVal();
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    static boolean isConstantPush(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return true;
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return true;
        if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer) return true;
        return false;
    }

    static int getConstantValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return ((IntInsnNode) insn).operand;
        if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) return i;
        throw new RuntimeException("Not a constant push: " + op);
    }

    static AbstractInsnNode makeConstantPush(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, value);
        return new LdcInsnNode(value);
    }

    static void removeNops(MethodNode mn) {
        List<AbstractInsnNode> toRemove = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == Opcodes.NOP) {
                toRemove.add(insn);
            }
        }
        for (AbstractInsnNode insn : toRemove) {
            mn.instructions.remove(insn);
            nopsRemoved++;
        }
    }

    /**
     * v2.8: Ensure a method doesn't fall off the end of its bytecode.
     * After dead code and fake try-catch removal, some methods lose their
     * terminal instruction. The ASM Analyzer rejects such methods with
     * "Execution can fall off the end of the code". Fix by appending a
     * RETURN (void) or ACONST_NULL; ARETURN (object) as appropriate.
     */
    static void ensureMethodTermination(MethodNode mn) {
        if (mn.instructions.size() == 0) return;

        // Find last real instruction
        AbstractInsnNode last = mn.instructions.getLast();
        while (last != null && (last instanceof LabelNode || last instanceof LineNumberNode ||
               last instanceof FrameNode)) {
            last = last.getPrevious();
        }
        if (last == null) return;

        int op = last.getOpcode();
        // Already terminates properly
        if (op == Opcodes.RETURN || op == Opcodes.ARETURN || op == Opcodes.IRETURN ||
            op == Opcodes.LRETURN || op == Opcodes.FRETURN || op == Opcodes.DRETURN ||
            op == Opcodes.ATHROW || op == Opcodes.GOTO) {
            return;
        }

        // Determine appropriate return type from method descriptor
        org.objectweb.asm.Type retType = org.objectweb.asm.Type.getReturnType(mn.desc);
        if (retType == org.objectweb.asm.Type.VOID_TYPE) {
            mn.instructions.add(new InsnNode(Opcodes.RETURN));
        } else if (retType.getSort() == org.objectweb.asm.Type.OBJECT ||
                   retType.getSort() == org.objectweb.asm.Type.ARRAY) {
            mn.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        } else if (retType == org.objectweb.asm.Type.LONG_TYPE) {
            mn.instructions.add(new InsnNode(Opcodes.LCONST_0));
            mn.instructions.add(new InsnNode(Opcodes.LRETURN));
        } else if (retType == org.objectweb.asm.Type.FLOAT_TYPE) {
            mn.instructions.add(new InsnNode(Opcodes.FCONST_0));
            mn.instructions.add(new InsnNode(Opcodes.FRETURN));
        } else if (retType == org.objectweb.asm.Type.DOUBLE_TYPE) {
            mn.instructions.add(new InsnNode(Opcodes.DCONST_0));
            mn.instructions.add(new InsnNode(Opcodes.DRETURN));
        } else {
            // int, boolean, byte, char, short
            mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
            mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        }
    }

    /**
     * CFG-based unreachable code elimination.
     * Walks the control flow graph from method entry + exception handlers,
     * marks all reachable instructions, and removes everything else.
     * Also normalizes the exception table (removes entries with empty/invalid ranges).
     */
    static void eliminateUnreachableCode(ClassNode cn, MethodNode mn) {
        if (mn.instructions.size() == 0) return;

        // Build instruction index map
        Map<AbstractInsnNode, Integer> insnIndex = new IdentityHashMap<>();
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            insnIndex.put(insns[i], i);
        }

        // BFS to find all reachable instructions
        boolean[] reachable = new boolean[insns.length];
        Deque<Integer> worklist = new ArrayDeque<>();

        // Seed: method entry
        worklist.add(0);

        // Seed: exception handler entries
        if (mn.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                Integer hi = insnIndex.get(tcb.handler);
                if (hi != null) worklist.add(hi);
            }
        }

        while (!worklist.isEmpty()) {
            int idx = worklist.poll();
            if (idx < 0 || idx >= insns.length || reachable[idx]) continue;
            reachable[idx] = true;

            AbstractInsnNode insn = insns[idx];
            int op = insn.getOpcode();

            // Pseudo-instructions (labels, frames, line numbers) always fall through
            if (op == -1) {
                worklist.add(idx + 1);
                continue;
            }

            // Unconditional terminators
            if (op == Opcodes.RETURN || op == Opcodes.ARETURN || op == Opcodes.IRETURN ||
                op == Opcodes.LRETURN || op == Opcodes.FRETURN || op == Opcodes.DRETURN ||
                op == Opcodes.ATHROW) {
                continue; // no successors
            }

            // GOTO: only branch target, no fallthrough
            if (op == Opcodes.GOTO) {
                JumpInsnNode ji = (JumpInsnNode) insn;
                Integer target = insnIndex.get(ji.label);
                if (target != null) worklist.add(target);
                continue;
            }

            // Conditional jumps: branch target + fallthrough
            if (insn instanceof JumpInsnNode ji) {
                Integer target = insnIndex.get(ji.label);
                if (target != null) worklist.add(target);
                worklist.add(idx + 1);
                continue;
            }

            // Table switch
            if (insn instanceof TableSwitchInsnNode ts) {
                Integer dflt = insnIndex.get(ts.dflt);
                if (dflt != null) worklist.add(dflt);
                for (LabelNode lbl : ts.labels) {
                    Integer t = insnIndex.get(lbl);
                    if (t != null) worklist.add(t);
                }
                continue;
            }

            // Lookup switch
            if (insn instanceof LookupSwitchInsnNode ls) {
                Integer dflt = insnIndex.get(ls.dflt);
                if (dflt != null) worklist.add(dflt);
                for (LabelNode lbl : ls.labels) {
                    Integer t = insnIndex.get(lbl);
                    if (t != null) worklist.add(t);
                }
                continue;
            }

            // All other instructions: fall through
            worklist.add(idx + 1);
        }

        // Don't remove labels (they may be referenced by exception table or local var table)
        // Remove unreachable real instructions
        List<AbstractInsnNode> toRemove = new ArrayList<>();
        for (int i = 0; i < insns.length; i++) {
            if (!reachable[i] && !(insns[i] instanceof LabelNode) &&
                !(insns[i] instanceof LineNumberNode) && !(insns[i] instanceof FrameNode)) {
                toRemove.add(insns[i]);
            }
        }
        for (AbstractInsnNode insn : toRemove) {
            mn.instructions.remove(insn);
            unreachableInsnsRemoved++;
        }

        // Normalize exception table: remove entries where the protected range is empty
        // or where handler/start/end labels were removed
        if (mn.tryCatchBlocks != null) {
            Set<LabelNode> presentLabels = new HashSet<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode ln) presentLabels.add(ln);
            }

            Iterator<TryCatchBlockNode> tcIt = mn.tryCatchBlocks.iterator();
            while (tcIt.hasNext()) {
                TryCatchBlockNode tcb = tcIt.next();
                // Remove if labels are missing
                if (!presentLabels.contains(tcb.start) || !presentLabels.contains(tcb.end) ||
                    !presentLabels.contains(tcb.handler)) {
                    tcIt.remove();
                    exnTableEntriesCleaned++;
                    continue;
                }
                // Remove if start == end (empty range)
                if (tcb.start == tcb.end) {
                    tcIt.remove();
                    exnTableEntriesCleaned++;
                    continue;
                }
                // Check if the range contains any real instructions
                boolean hasRealInsn = false;
                for (AbstractInsnNode cur = tcb.start; cur != null && cur != tcb.end; cur = cur.getNext()) {
                    if (cur.getOpcode() != -1) { hasRealInsn = true; break; }
                }
                if (!hasRealInsn) {
                    tcIt.remove();
                    exnTableEntriesCleaned++;
                }
            }
        }
    }

    /**
     * Consolidate fragmented exception table entries.
     * ZKM splits one try-catch into many tiny entries with the same handler+type.
     * This creates backward jumps that confuse CFR. We merge fragments sharing
     * the same (handler, type) into a single entry covering all the code between
     * the first fragment's start and the last fragment's end.
     */
    static void consolidateExceptionTable(MethodNode mn) {
        if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.size() < 2) return;

        // Build label position map
        Map<LabelNode, Integer> labelPos = new IdentityHashMap<>();
        int pos = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode ln) labelPos.put(ln, pos);
            pos++;
        }

        // Group entries by (handler label identity, type) using reference equality
        record ExnGroupKey(LabelNode handler, String type) {}
        Map<ExnGroupKey, List<TryCatchBlockNode>> groups = new LinkedHashMap<>();
        for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
            ExnGroupKey key = new ExnGroupKey(tcb.handler, tcb.type);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tcb);
        }

        boolean changed = false;
        for (var entry : groups.entrySet()) {
            List<TryCatchBlockNode> group = entry.getValue();
            if (group.size() < 2) continue;

            // Find the earliest start and latest end in instruction order
            LabelNode earliestStart = null;
            LabelNode latestEnd = null;
            int minPos = Integer.MAX_VALUE;
            int maxPos = Integer.MIN_VALUE;

            for (TryCatchBlockNode tcb : group) {
                Integer sp = labelPos.get(tcb.start);
                Integer ep = labelPos.get(tcb.end);
                if (sp == null || ep == null) continue;
                if (sp < minPos) { minPos = sp; earliestStart = tcb.start; }
                if (ep > maxPos) { maxPos = ep; latestEnd = tcb.end; }
            }

            if (earliestStart == null || latestEnd == null || minPos >= maxPos) continue;

            // Keep the first entry, update its range, remove the rest
            TryCatchBlockNode keeper = group.get(0);
            keeper.start = earliestStart;
            keeper.end = latestEnd;
            for (int i = 1; i < group.size(); i++) {
                mn.tryCatchBlocks.remove(group.get(i));
                exnTableEntriesCleaned++;
            }
            changed = true;
        }
    }

    /**
     * v2.7: Deep exception handler cleanup.
     * Removes TryCatchBlockNodes where:
     *   (a) protected range has no instructions that can actually throw
     *   (b) handler is ASTORE+GOTO with never-read exception local
     *   (c) exact duplicate entries (same start/end/handler/type)
     */
    static void cleanupExceptionHandlers(ClassNode cn, MethodNode mn) {
        if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty()) return;

        // (c) Remove exact duplicates
        Set<String> seen = new HashSet<>();
        Iterator<TryCatchBlockNode> it = mn.tryCatchBlocks.iterator();
        while (it.hasNext()) {
            TryCatchBlockNode tcb = it.next();
            String key = System.identityHashCode(tcb.start) + ":" +
                         System.identityHashCode(tcb.end) + ":" +
                         System.identityHashCode(tcb.handler) + ":" + tcb.type;
            if (!seen.add(key)) {
                it.remove();
                exnHandlersCleaned++;
            }
        }

        // Pre-build set of all ALOAD variable indices (for check b, O(n) once)
        Set<Integer> aloadVars = new HashSet<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.ALOAD && insn instanceof VarInsnNode vi) {
                aloadVars.add(vi.var);
            }
        }

        // (a) Remove entries where protected range has no throwing instructions
        it = mn.tryCatchBlocks.iterator();
        while (it.hasNext()) {
            TryCatchBlockNode tcb = it.next();
            boolean hasThrowingInsn = false;
            for (AbstractInsnNode cur = tcb.start; cur != null && cur != tcb.end; cur = cur.getNext()) {
                if (canInstructionThrow(cur)) {
                    hasThrowingInsn = true;
                    break;
                }
            }
            if (!hasThrowingInsn) {
                it.remove();
                exnHandlersCleaned++;
                continue;
            }

            // (b) Handler is ASTORE+GOTO with never-read exception local
            AbstractInsnNode handlerInsn = skipNonInsn(tcb.handler.getNext(), false);
            if (handlerInsn != null && handlerInsn.getOpcode() == Opcodes.ASTORE) {
                AbstractInsnNode afterStore = skipNonInsn(handlerInsn.getNext(), false);
                if (afterStore != null && afterStore.getOpcode() == Opcodes.GOTO) {
                    int localVar = ((VarInsnNode) handlerInsn).var;
                    if (!aloadVars.contains(localVar)) {
                        it.remove();
                        exnHandlersCleaned++;
                    }
                }
            }
        }
    }

    /**
     * v2.9: Verify exception table consistency using ASM Analyzer.
     * If the Analyzer fails (stack depth inconsistency from ZKM damage),
     * iteratively remove exception entries until the method verifies clean.
     *
     * Strategy: try removing exception entries one at a time, starting with
     * entries whose handler is a simple rethrow (ASTORE+ALOAD+ATHROW or just ATHROW),
     * then entries for RuntimeException/Throwable (ZKM favorites), then any entry.
     */
    static void verifyAndFixExceptionTable(ClassNode cn, MethodNode mn) {
        if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty()) return;
        if (mn.instructions.size() == 0) return;

        // First check: does the method verify cleanly?
        if (analyzerSucceeds(cn, mn)) return;

        // Method fails verification. Try removing exception entries to fix it.
        boolean changed = true;
        int maxIterations = mn.tryCatchBlocks.size(); // safety limit
        while (changed && maxIterations-- > 0) {
            changed = false;

            // Priority 1: Remove entries where handler is ATHROW (catch-and-rethrow)
            Iterator<TryCatchBlockNode> it = mn.tryCatchBlocks.iterator();
            while (it.hasNext()) {
                TryCatchBlockNode tcb = it.next();
                AbstractInsnNode h = skipNonInsn(tcb.handler.getNext(), false);
                // Pattern: handler → ATHROW (immediate rethrow)
                boolean isRethrow = (h != null && h.getOpcode() == Opcodes.ATHROW);
                // Pattern: handler → ASTORE x → ALOAD x → ATHROW
                if (!isRethrow && h != null && h.getOpcode() == Opcodes.ASTORE) {
                    AbstractInsnNode h2 = skipNonInsn(h.getNext(), false);
                    if (h2 != null && h2.getOpcode() == Opcodes.ALOAD &&
                        ((VarInsnNode) h2).var == ((VarInsnNode) h).var) {
                        AbstractInsnNode h3 = skipNonInsn(h2.getNext(), false);
                        isRethrow = (h3 != null && h3.getOpcode() == Opcodes.ATHROW);
                    }
                }
                if (isRethrow) {
                    it.remove();
                    exnVerifyFixed++;
                    changed = true;
                }
            }
            if (changed && analyzerSucceeds(cn, mn)) return;

            // Priority 2: Remove RuntimeException/Throwable/null-type entries one at a time
            changed = false;
            for (int i = mn.tryCatchBlocks.size() - 1; i >= 0; i--) {
                TryCatchBlockNode tcb = mn.tryCatchBlocks.get(i);
                String type = tcb.type;
                if (type == null || type.equals("java/lang/RuntimeException") ||
                    type.equals("java/lang/Throwable") || type.equals("java/lang/Exception")) {
                    TryCatchBlockNode removed = mn.tryCatchBlocks.remove(i);
                    if (analyzerSucceeds(cn, mn)) {
                        exnVerifyFixed++;
                        changed = true;
                        break; // restart the outer loop
                    }
                    // Didn't help — put it back
                    mn.tryCatchBlocks.add(i, removed);
                }
            }
            if (changed && analyzerSucceeds(cn, mn)) return;

            // Priority 3: Try removing ANY entry one at a time (last resort)
            changed = false;
            for (int i = mn.tryCatchBlocks.size() - 1; i >= 0; i--) {
                TryCatchBlockNode removed = mn.tryCatchBlocks.remove(i);
                if (analyzerSucceeds(cn, mn)) {
                    exnVerifyFixed++;
                    changed = true;
                    break;
                }
                mn.tryCatchBlocks.add(i, removed);
            }
        }
    }

    /**
     * Try running ASM Analyzer on a method. Returns true if analysis succeeds.
     */
    static boolean analyzerSucceeds(ClassNode cn, MethodNode mn) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier());
            analyzer.analyze(cn.name, mn);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if an instruction can potentially throw an exception.
     * Returns false only for opcodes that provably cannot throw.
     */
    static boolean canInstructionThrow(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == -1) return false; // labels, frames, line numbers
        return switch (op) {
            case Opcodes.NOP, Opcodes.ACONST_NULL,
                 Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1,
                 Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
                 Opcodes.LCONST_0, Opcodes.LCONST_1,
                 Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
                 Opcodes.DCONST_0, Opcodes.DCONST_1,
                 Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC,
                 Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD,
                 Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE,
                 Opcodes.POP, Opcodes.POP2,
                 Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2,
                 Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2,
                 Opcodes.SWAP,
                 Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD,
                 Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
                 Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL,
                 Opcodes.FDIV, Opcodes.DDIV, Opcodes.FREM, Opcodes.DREM,
                 Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG,
                 Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
                 Opcodes.IUSHR, Opcodes.LUSHR,
                 Opcodes.IAND, Opcodes.LAND, Opcodes.IOR, Opcodes.LOR,
                 Opcodes.IXOR, Opcodes.LXOR,
                 Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
                 Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                 Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
                 Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                 Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                 Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG,
                 Opcodes.DCMPL, Opcodes.DCMPG,
                 Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT,
                 Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                 Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT,
                 Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                 Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                 Opcodes.GOTO,
                 Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
                 Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
                 Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN,
                 Opcodes.INSTANCEOF,
                 Opcodes.IFNULL, Opcodes.IFNONNULL,
                 Opcodes.GETSTATIC, Opcodes.PUTSTATIC, // class init errors are pre-verification
                 Opcodes.IINC -> false;
            default -> true; // INVOKE*, xALOAD, xASTORE, IDIV, IREM, ATHROW, NEW, etc.
        };
    }

    /**
     * Reorder basic blocks to linearize control flow.
     * ZKM scrambles block order so every block is a GOTO-chain.
     * This pass follows execution order (greedy: follow GOTO targets,
     * then fallthrough for conditionals) and places blocks sequentially,
     * turning most GOTOs into fallthroughs and eliminating backward jumps.
     */
    /**
     * GOTO chain shortcutting + redundant GOTO removal.
     * Doesn't reorder blocks, so exception tables stay valid.
     */
    /**
     * Pre-write sanitization: ensure all label references in jumps and exception table
     * entries point to labels that actually exist in the instruction list.
     * Fixes structural issues that cause ClassWriter to throw exceptions.
     */
    static boolean sanitizeMethodStructure(MethodNode mn) {
        if (mn.instructions.size() == 0) return false;
        boolean fixed = false;

        // Collect all labels actually present in the instruction list
        Set<LabelNode> presentLabels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode ln) presentLabels.add(ln);
        }

        // Fix jump instructions targeting missing labels → replace with NOP
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode ji) {
                if (!presentLabels.contains(ji.label)) {
                    mn.instructions.insertBefore(insn, new InsnNode(Opcodes.NOP));
                    mn.instructions.remove(insn);
                    insn = mn.instructions.getFirst(); // restart
                    fixed = true;
                }
            }
        }

        // Remove exception table entries with missing labels
        if (mn.tryCatchBlocks != null) {
            Iterator<TryCatchBlockNode> it = mn.tryCatchBlocks.iterator();
            while (it.hasNext()) {
                TryCatchBlockNode tcb = it.next();
                if (!presentLabels.contains(tcb.start) || !presentLabels.contains(tcb.end) ||
                    !presentLabels.contains(tcb.handler)) {
                    it.remove();
                    fixed = true;
                }
            }
        }

        // Remove LocalVariableNodes with missing labels
        if (mn.localVariables != null) {
            mn.localVariables.removeIf(lv ->
                !presentLabels.contains(lv.start) || !presentLabels.contains(lv.end));
        }

        // Strip stale FrameNode entries — they become invalid after bytecode rewriting.
        // COMPUTE_FRAMES will regenerate correct frames during ClassWriter.
        List<AbstractInsnNode> frames = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode) frames.add(insn);
        }
        for (AbstractInsnNode f : frames) {
            mn.instructions.remove(f);
        }
        if (!frames.isEmpty()) fixed = true;

        return fixed;
    }

    static void shortenGotoChains(MethodNode mn) {
        if (mn.instructions.size() < 4) return;
        // Build label → first-real-instruction map
        Map<LabelNode, AbstractInsnNode> labelTarget = new IdentityHashMap<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode ln) {
                AbstractInsnNode next = ln.getNext();
                while (next != null && next.getOpcode() == -1) next = next.getNext();
                if (next != null) labelTarget.put(ln, next);
            }
        }
        // Shortcut GOTO chains (max 20 hops)
        boolean changed = true;
        for (int pass = 0; pass < 5 && changed; pass++) {
            changed = false;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof JumpInsnNode ji) {
                    LabelNode target = ji.label;
                    int hops = 0;
                    while (hops++ < 20) {
                        AbstractInsnNode dest = labelTarget.get(target);
                        if (dest instanceof JumpInsnNode destJi && dest.getOpcode() == Opcodes.GOTO)
                            target = destJi.label;
                        else break;
                    }
                    if (target != ji.label) { ji.label = target; changed = true; }
                }
                if (insn instanceof TableSwitchInsnNode ts) {
                    for (int i = 0; i < ts.labels.size(); i++) {
                        AbstractInsnNode dest = labelTarget.get(ts.labels.get(i));
                        if (dest instanceof JumpInsnNode dj && dest.getOpcode() == Opcodes.GOTO)
                            { ts.labels.set(i, dj.label); changed = true; }
                    }
                    AbstractInsnNode dd = labelTarget.get(ts.dflt);
                    if (dd instanceof JumpInsnNode dj && dd.getOpcode() == Opcodes.GOTO)
                        { ts.dflt = dj.label; changed = true; }
                }
                if (insn instanceof LookupSwitchInsnNode ls) {
                    for (int i = 0; i < ls.labels.size(); i++) {
                        AbstractInsnNode dest = labelTarget.get(ls.labels.get(i));
                        if (dest instanceof JumpInsnNode dj && dest.getOpcode() == Opcodes.GOTO)
                            { ls.labels.set(i, dj.label); changed = true; }
                    }
                    AbstractInsnNode dd = labelTarget.get(ls.dflt);
                    if (dd instanceof JumpInsnNode dj && dd.getOpcode() == Opcodes.GOTO)
                        { ls.dflt = dj.label; changed = true; }
                }
            }
            if (changed) {
                labelTarget.clear();
                for (AbstractInsnNode i2 = mn.instructions.getFirst(); i2 != null; i2 = i2.getNext()) {
                    if (i2 instanceof LabelNode ln) {
                        AbstractInsnNode next = ln.getNext();
                        while (next != null && next.getOpcode() == -1) next = next.getNext();
                        if (next != null) labelTarget.put(ln, next);
                    }
                }
            }
        }
        // Remove redundant GOTOs
        List<AbstractInsnNode> toRemove = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GOTO) {
                JumpInsnNode ji = (JumpInsnNode) insn;
                AbstractInsnNode nxt = ji.getNext();
                while (nxt != null && !(nxt instanceof LabelNode) && nxt.getOpcode() == -1) nxt = nxt.getNext();
                if (nxt == ji.label) toRemove.add(insn);
            }
        }
        for (AbstractInsnNode insn : toRemove) mn.instructions.remove(insn);
    }

    /**
     * Block reordering: linearize control flow by following execution order.
     * Exception table reconstruction at the end handles methods with try-catch.
     */
    static void reorderBasicBlocksImpl(ClassNode cn, MethodNode mn) {
        if (mn.instructions.size() < 10 || mn.instructions.size() > 8000) return;
        AbstractInsnNode[] insns = mn.instructions.toArray();
        int n = insns.length;

        // 1. Mark block-start indices (target labels, post-terminators)
        Set<LabelNode> targetLabels = new HashSet<>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof JumpInsnNode ji) targetLabels.add(ji.label);
            else if (insn instanceof TableSwitchInsnNode ts) {
                targetLabels.add(ts.dflt); targetLabels.addAll(ts.labels);
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                targetLabels.add(ls.dflt); targetLabels.addAll(ls.labels);
            }
        }
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                targetLabels.add(tcb.start); targetLabels.add(tcb.end); targetLabels.add(tcb.handler);
            }

        // 2. Compute block boundaries as [blockStart[i], blockStart[i+1])
        boolean[] isBlockStart = new boolean[n];
        isBlockStart[0] = true;
        for (int i = 0; i < n; i++) {
            if (insns[i] instanceof LabelNode ln && targetLabels.contains(ln)) isBlockStart[i] = true;
            int op = insns[i].getOpcode();
            if (op == Opcodes.GOTO || op == Opcodes.ATHROW ||
                (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) ||
                insns[i] instanceof TableSwitchInsnNode || insns[i] instanceof LookupSwitchInsnNode) {
                if (i + 1 < n) isBlockStart[i + 1] = true;
            }
        }

        // Count blocks & build start array
        int numBlocks = 0;
        for (boolean b : isBlockStart) if (b) numBlocks++;
        if (numBlocks < 3) return;
        int[] blockStart = new int[numBlocks + 1]; // blockStart[numBlocks] = n (sentinel)
        int bi = 0;
        for (int i = 0; i < n; i++) if (isBlockStart[i]) blockStart[bi++] = i;
        blockStart[numBlocks] = n;

        // 3. Map labels → block index, find terminators per block
        Map<LabelNode, Integer> labelToBlock = new HashMap<>();
        int[] termIdx = new int[numBlocks]; // index into insns[] of last real instruction
        Arrays.fill(termIdx, -1);
        for (int b2 = 0; b2 < numBlocks; b2++) {
            for (int i = blockStart[b2]; i < blockStart[b2 + 1]; i++) {
                if (insns[i] instanceof LabelNode ln) labelToBlock.put(ln, b2);
                if (insns[i].getOpcode() != -1) termIdx[b2] = i;
            }
        }

        // 4. Greedy linearization
        boolean[] placed = new boolean[numBlocks];
        int[] order = new int[numBlocks];
        int orderLen = 0;
        Deque<Integer> worklist = new ArrayDeque<>();
        worklist.add(0);
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                Integer hi = labelToBlock.get(tcb.handler);
                if (hi != null) worklist.add(hi);
            }

        while (!worklist.isEmpty()) {
            int idx = worklist.poll();
            if (idx < 0 || idx >= numBlocks || placed[idx]) continue;
            int current = idx;
            while (current >= 0 && current < numBlocks && !placed[current]) {
                placed[current] = true;
                order[orderLen++] = current;
                int ti = termIdx[current];
                int next = -1;
                if (ti >= 0) {
                    AbstractInsnNode term = insns[ti];
                    int op = term.getOpcode();
                    if (op == Opcodes.GOTO) {
                        Integer tgt = labelToBlock.get(((JumpInsnNode)term).label);
                        if (tgt != null && !placed[tgt]) next = tgt;
                    } else if (term instanceof JumpInsnNode ji) {
                        Integer tgt = labelToBlock.get(ji.label);
                        if (tgt != null && !placed[tgt]) worklist.addFirst(tgt);
                        if (current+1 < numBlocks && !placed[current+1]) next = current+1;
                    } else if (term instanceof TableSwitchInsnNode ts) {
                        for (LabelNode lbl : ts.labels) { Integer t=labelToBlock.get(lbl); if(t!=null&&!placed[t]) worklist.addFirst(t); }
                        Integer d=labelToBlock.get(ts.dflt); if(d!=null&&!placed[d]) worklist.addFirst(d);
                    } else if (term instanceof LookupSwitchInsnNode ls) {
                        for (LabelNode lbl : ls.labels) { Integer t=labelToBlock.get(lbl); if(t!=null&&!placed[t]) worklist.addFirst(t); }
                        Integer d=labelToBlock.get(ls.dflt); if(d!=null&&!placed[d]) worklist.addFirst(d);
                    } else if (op != Opcodes.ATHROW && !(op >= Opcodes.IRETURN && op <= Opcodes.RETURN)) {
                        if (current+1 < numBlocks && !placed[current+1]) next = current+1;
                    }
                } else {
                    if (current+1 < numBlocks && !placed[current+1]) next = current+1;
                }
                current = next;
            }
        }
        for (int i = 0; i < numBlocks; i++) if (!placed[i]) order[orderLen++] = i;

        // 5. Check if order changed
        boolean changed = false;
        for (int i = 0; i < numBlocks; i++) if (order[i] != i) { changed = true; break; }
        if (!changed) return;

        // 6. Rebuild InsnList.
        // IMPORTANT: InsnList.clear() does NOT null out prev/next pointers on nodes.
        // We must remove() each node individually to properly detach it, otherwise
        // stale next-pointers create infinite cycles during iteration.
        while (mn.instructions.size() > 0) {
            mn.instructions.remove(mn.instructions.getFirst());
        }
        for (int i = 0; i < orderLen; i++) {
            int b2 = order[i];
            for (int j = blockStart[b2]; j < blockStart[b2 + 1]; j++) {
                mn.instructions.add(insns[j]);
            }
        }

        // 7. Rebuild exception table for new block order.
        // Group entries by (handler, type), find first/last covered blocks in new order,
        // and create merged entries that wrap those blocks.
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            // Map labels to their position in the new instruction list
            Map<LabelNode, Integer> labelPos = new IdentityHashMap<>();
            int pos2 = 0;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode ln) labelPos.put(ln, pos2);
                pos2++;
            }

            // Group entries by (handler, type)
            record ExnKey(LabelNode handler, String type) {}
            Map<ExnKey, List<TryCatchBlockNode>> groups = new LinkedHashMap<>();
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                groups.computeIfAbsent(new ExnKey(tcb.handler, tcb.type), k -> new ArrayList<>()).add(tcb);
            }

            List<TryCatchBlockNode> newTcbs = new ArrayList<>();
            for (var entry : groups.entrySet()) {
                // Find all label ranges in new order, merge into contiguous spans
                List<int[]> ranges = new ArrayList<>(); // [start_pos, end_pos]
                for (TryCatchBlockNode tcb : entry.getValue()) {
                    Integer sp = labelPos.get(tcb.start);
                    Integer ep = labelPos.get(tcb.end);
                    if (sp == null || ep == null) continue;
                    int lo = Math.min(sp, ep), hi = Math.max(sp, ep);
                    ranges.add(new int[]{lo, hi});
                }
                if (ranges.isEmpty()) continue;
                // Sort and merge overlapping/adjacent ranges
                ranges.sort(Comparator.comparingInt(a -> a[0]));
                List<int[]> merged = new ArrayList<>();
                int[] cur2 = ranges.get(0);
                for (int i2 = 1; i2 < ranges.size(); i2++) {
                    int[] r = ranges.get(i2);
                    if (r[0] <= cur2[1] + 5) { // merge if within 5 insns (allow small gaps)
                        cur2[1] = Math.max(cur2[1], r[1]);
                    } else {
                        merged.add(cur2);
                        cur2 = r;
                    }
                }
                merged.add(cur2);

                // Create new exception entries for each merged range
                // Find the label at or just before each range boundary
                LabelNode handler = entry.getKey().handler;
                String type = entry.getKey().type;
                for (int[] range : merged) {
                    LabelNode startLabel = null, endLabel = null;
                    int startPos = Integer.MAX_VALUE, endPos = -1;
                    for (var le : labelPos.entrySet()) {
                        int lp = le.getValue();
                        if (lp >= range[0] && lp <= range[1]) {
                            if (lp < startPos) { startPos = lp; startLabel = le.getKey(); }
                            if (lp > endPos) { endPos = lp; endLabel = le.getKey(); }
                        }
                    }
                    if (startLabel != null && endLabel != null && startLabel != endLabel) {
                        newTcbs.add(new TryCatchBlockNode(startLabel, endLabel, handler, type));
                    }
                }
            }
            mn.tryCatchBlocks.clear();
            mn.tryCatchBlocks.addAll(newTcbs);
        }

        // 8. Remove redundant GOTOs (GOTO L where L is the next label)
        List<AbstractInsnNode> gotoToRemove = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GOTO) {
                JumpInsnNode ji = (JumpInsnNode) insn;
                AbstractInsnNode nxt = ji.getNext();
                while (nxt != null && !(nxt instanceof LabelNode) && nxt.getOpcode() == -1) nxt = nxt.getNext();
                if (nxt == ji.label) gotoToRemove.add(insn);
            }
        }
        for (AbstractInsnNode insn : gotoToRemove) mn.instructions.remove(insn);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 3: Cleanup
    // ═══════════════════════════════════════════════════════════════════

    static int removeOpaquePredicateFields(ClassNode cn) {
        // Collect names of fields being removed
        Set<String> removedNames = new HashSet<>();
        int[] removed = {0};
        cn.fields.removeIf(fn -> {
            if ((fn.access & Opcodes.ACC_STATIC) == 0) return false;
            if (!fn.desc.equals("I")) return false;
            if (fn.name.matches(".*00[4-7][0-9a-fA-F].*")) {
                removedNames.add(fn.name);
                removed[0]++;
                return true;
            }
            return false;
        });

        // Clean dangling PUTSTATIC/GETSTATIC references to removed fields
        // (handles <clinit> which is not localized, and any edge cases)
        if (!removedNames.isEmpty()) {
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
                    AbstractInsnNode next = insn.getNext();
                    if (insn instanceof FieldInsnNode fi && fi.owner.equals(cn.name) &&
                        fi.desc.equals("I") && removedNames.contains(fi.name)) {
                        if (fi.getOpcode() == Opcodes.PUTSTATIC) {
                            // PUTSTATIC consumes one stack value → replace with POP
                            mn.instructions.insertBefore(fi, new InsnNode(Opcodes.POP));
                            mn.instructions.remove(fi);
                        } else if (fi.getOpcode() == Opcodes.GETSTATIC) {
                            // GETSTATIC produces one stack value → replace with ICONST_0
                            mn.instructions.insertBefore(fi, new InsnNode(Opcodes.ICONST_0));
                            mn.instructions.remove(fi);
                        }
                    }
                    insn = next;
                }
            }
        }
        return removed[0];
    }

    static void removeOpaquePredicateMethods(ClassNode cn) {
        Iterator<MethodNode> it = cn.methods.iterator();
        while (it.hasNext()) {
            MethodNode mn = it.next();
            if ((mn.access & Opcodes.ACC_STATIC) == 0) continue;
            if (!mn.desc.equals("()I")) continue;
            if (mn.name.matches(".*00[4-7][0-9a-fA-F].*")) {
                boolean calledInternally = false;
                for (MethodNode other : cn.methods) {
                    if (other == mn) continue;
                    for (AbstractInsnNode insn : other.instructions) {
                        if (insn instanceof MethodInsnNode mi &&
                            mi.getOpcode() == Opcodes.INVOKESTATIC &&
                            mi.owner.equals(cn.name) && mi.name.equals(mn.name) &&
                            mi.desc.equals("()I")) {
                            calledInternally = true;
                            break;
                        }
                    }
                    if (calledInternally) break;
                }
                if (!calledInternally) {
                    it.remove();
                    opaqueMethodsRemoved++;
                }
            }
        }
    }

    /**
     * Rename obfuscated local variable names matching ZKM patterns
     * (e.g. "p0070pppp0070", "aaaaa0061a") to clean "var{index}" names.
     */
    static int cleanObfuscatedVariableNames(ClassNode cn) {
        int renamed = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.localVariables == null) continue;
            for (LocalVariableNode lvn : mn.localVariables) {
                if (lvn.name.matches(".*00[4-7][0-9a-fA-F].*")) {
                    lvn.name = "var" + lvn.index;
                    renamed++;
                }
            }
        }
        return renamed;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    static char toChar(Object obj) {
        if (obj instanceof Character c) return c;
        if (obj instanceof Number n) return (char) n.intValue();
        throw new RuntimeException("Cannot convert " + obj.getClass() + " to char");
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        s = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}

package org.corpseflower.passes;

import org.jetbrains.java.decompiler.api.java.JavaPassLocation;
import org.jetbrains.java.decompiler.api.java.JavaPassRegistrar;
import org.jetbrains.java.decompiler.api.plugin.Plugin;
import org.jetbrains.java.decompiler.api.plugin.pass.NamedPass;

/**
 * Vineflower plugin that cleans up residual ZKM obfuscation artifacts
 * after bytecode-level deobfuscation by FDRSDeobfuscator.
 *
 * Handles patterns that survive into the decompiled statement/expression tree:
 * - Constant arithmetic expressions (e.g., 0*(0+0)%2)
 * - Dead conditionals (if(false), while(false))
 * - Control flow flattened state machines (while(true){switch(state)})
 */
public class FDRSCleanupPlugin implements Plugin {

    @Override
    public String id() {
        return "fdrs-cleanup";
    }

    @Override
    public String description() {
        return "Cleans up residual ZKM obfuscation artifacts in decompiled FDRS code.";
    }

    @Override
    public void registerJavaPasses(JavaPassRegistrar registrar) {
        // MAIN_LOOP: returning true restarts the loop, enabling cascading simplifications
        registrar.register(JavaPassLocation.MAIN_LOOP,
            NamedPass.of("FDRSConstFold", new ConstantExpressionFolder()));
        registrar.register(JavaPassLocation.MAIN_LOOP,
            NamedPass.of("FDRSDeadCode", new DeadCodeEliminator()));
        // AT_END: runs once after all other passes, for complex structural changes
        registrar.register(JavaPassLocation.AT_END,
            NamedPass.of("FDRSDeflatten", new StateMachineDeflattener()));
    }
}

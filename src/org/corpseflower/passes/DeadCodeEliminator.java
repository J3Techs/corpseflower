package org.corpseflower.passes;

import org.jetbrains.java.decompiler.api.plugin.pass.Pass;
import org.jetbrains.java.decompiler.api.plugin.pass.PassContext;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes dead code resulting from constant folding:
 * - if(false) { ... } → removed (or replaced with else branch)
 * - if(true) { ... } else { ... } → replaced with if-body
 * - switch(constVal) { case N: ... } → replaced with matching case body
 * - while(false) { ... } → removed
 *
 * Runs at MAIN_LOOP after ConstantExpressionFolder.
 */
public class DeadCodeEliminator implements Pass {

    private boolean modified;

    @Override
    public boolean run(PassContext ctx) {
        modified = false;
        processStatement(ctx.getRoot());
        return modified;
    }

    private void processStatement(Statement stat) {
        if (stat == null) return;

        // Process children first (bottom-up), on a copy to avoid concurrent modification
        List<Statement> children = new ArrayList<>(stat.getStats());
        for (Statement child : children) {
            processStatement(child);
        }

        // Check for dead if-statements
        if (stat instanceof IfStatement ifStat) {
            processIfStatement(ifStat);
        }

        // Check for dead switch-statements with constant head
        if (stat instanceof SwitchStatement sw) {
            processSwitchStatement(sw);
        }

        // Check for dead loops with constant false condition
        if (stat instanceof DoStatement doStat) {
            processDoStatement(doStat);
        }
    }

    private void processIfStatement(IfStatement ifStat) {
        IfExprent head = ifStat.getHeadexprent();
        if (head == null) return;
        Exprent cond = head.getCondition();
        if (!(cond instanceof ConstExprent constCond)) return;
        Object val = constCond.getValue();
        if (!(val instanceof Integer intVal)) return;

        Statement parent = ifStat.getParent();
        if (parent == null) return;

        if (intVal == 0) {
            // Condition is false → if-body is dead
            Statement elseBody = ifStat.getElsestat();
            if (elseBody != null) {
                // Keep else body, replace the if statement with it
                try {
                    parent.replaceStatement(ifStat, elseBody);
                    modified = true;
                } catch (Exception ignored) {
                    // Statement tree manipulation can fail — skip silently
                }
            } else {
                // No else branch — entire if is dead
                // Replace with empty basic block to avoid leaving holes
                try {
                    parent.getStats().removeWithKey(ifStat.id);
                    modified = true;
                } catch (Exception ignored) {
                }
            }
        } else {
            // Condition is true → else-body is dead
            Statement ifBody = ifStat.getIfstat();
            if (ifBody != null) {
                try {
                    parent.replaceStatement(ifStat, ifBody);
                    modified = true;
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void processSwitchStatement(SwitchStatement sw) {
        Exprent head = sw.getHeadexprent();
        if (!(head instanceof ConstExprent constHead)) return;
        Object val = constHead.getValue();
        if (!(val instanceof Integer switchVal)) return;

        // Find the matching case
        List<List<Exprent>> caseValues = sw.getCaseValues();
        List<Statement> caseStatements = sw.getCaseStatements();
        Statement matchingCase = null;
        Statement defaultCase = null;

        for (int i = 0; i < caseValues.size(); i++) {
            List<Exprent> values = caseValues.get(i);
            if (i >= caseStatements.size()) break;

            for (Exprent caseVal : values) {
                if (caseVal == null) {
                    // null case value means default
                    defaultCase = caseStatements.get(i);
                } else if (caseVal instanceof ConstExprent cv &&
                           cv.getValue() instanceof Integer ci &&
                           ci.intValue() == switchVal) {
                    matchingCase = caseStatements.get(i);
                }
            }
        }

        if (matchingCase == null) matchingCase = defaultCase;
        if (matchingCase == null) return;

        Statement parent = sw.getParent();
        if (parent == null) return;

        try {
            parent.replaceStatement(sw, matchingCase);
            modified = true;
        } catch (Exception ignored) {
            // Statement tree manipulation can fail — skip silently
        }
    }

    private void processDoStatement(DoStatement doStat) {
        // Only handle WHILE loops with constant false condition
        if (doStat.getLooptype() != DoStatement.Type.WHILE) return;

        Exprent cond = doStat.getConditionExprent();
        if (!(cond instanceof ConstExprent constCond)) return;
        Object val = constCond.getValue();
        if (!(val instanceof Integer intVal)) return;

        if (intVal == 0) {
            // while(false) — entire loop is dead
            Statement parent = doStat.getParent();
            if (parent == null) return;
            try {
                parent.getStats().removeWithKey(doStat.id);
                modified = true;
            } catch (Exception ignored) {
            }
        }
    }
}

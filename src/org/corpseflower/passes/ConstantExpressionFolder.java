package org.corpseflower.passes;

import org.jetbrains.java.decompiler.api.plugin.pass.Pass;
import org.jetbrains.java.decompiler.api.plugin.pass.PassContext;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.exps.FunctionExprent.FunctionType;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds constant arithmetic expressions in the decompiled statement tree.
 *
 * Catches patterns like:
 *   0 * (0 + 0) % 2  →  0
 *   (3 + 4) * 5 % 2  →  1
 *   0 == 0            →  true (1)
 *
 * Runs at MAIN_LOOP — returning true restarts the decompilation loop,
 * enabling cascading simplifications (e.g., fold arithmetic → fold switch).
 */
public class ConstantExpressionFolder implements Pass {

    private boolean modified;
    // Map of variable index → init value for variables initialized with byte constants.
    // Used to extend algebraic identity recognition for register field values.
    private Map<Integer, Integer> varInitValues;

    @Override
    public boolean run(PassContext ctx) {
        modified = false;
        varInitValues = new HashMap<>();
        collectInitValues(ctx.getRoot());
        processStatement(ctx.getRoot());
        return modified;
    }

    /**
     * Scan the statement tree for simple variable initializations: `byte/int varX = <const>;`
     * These correspond to ZKM register field values after localization.
     */
    private void collectInitValues(Statement root) {
        collectInitValuesFromStatement(root);
    }

    private void collectInitValuesFromStatement(Statement stat) {
        List<Exprent> exprents = stat.getExprents();
        if (exprents != null) {
            for (Exprent expr : exprents) {
                if (expr instanceof AssignmentExprent assign) {
                    if (assign.getLeft() instanceof VarExprent var &&
                        assign.getRight() instanceof ConstExprent cv &&
                        cv.getValue() instanceof Integer iv) {
                        // Only track if not already seen (first assignment wins)
                        varInitValues.putIfAbsent(var.getIndex(), iv);
                    }
                }
            }
        }
        for (Statement child : stat.getStats()) {
            collectInitValuesFromStatement(child);
        }
    }

    private void processStatement(Statement stat) {
        if (stat == null) return;

        // Recurse into child statements first (bottom-up)
        for (Statement child : stat.getStats()) {
            processStatement(child);
        }

        // Process expressions in this statement's exprents list
        List<Exprent> exprents = stat.getExprents();
        if (exprents != null) {
            for (int i = 0; i < exprents.size(); i++) {
                Exprent folded = foldExpression(exprents.get(i));
                if (folded != exprents.get(i)) {
                    exprents.set(i, folded);
                    modified = true;
                }
            }
        }

        // Process head exprents of control structures
        if (stat instanceof IfStatement ifStat) {
            IfExprent head = ifStat.getHeadexprent();
            if (head != null) {
                Exprent cond = head.getCondition();
                Exprent folded = foldExpression(cond);
                if (folded != cond) {
                    head.setCondition(folded);
                    modified = true;
                }
            }
        } else if (stat instanceof SwitchStatement sw) {
            Exprent head = sw.getHeadexprent();
            if (head != null) {
                Exprent folded = foldExpression(head);
                if (folded != head) {
                    sw.replaceExprent(head, folded);
                    modified = true;
                }
            }
        } else if (stat instanceof DoStatement doStat) {
            Exprent cond = doStat.getConditionExprent();
            if (cond != null) {
                Exprent folded = foldExpression(cond);
                if (folded != cond) {
                    doStat.setConditionExprent(folded);
                    modified = true;
                }
            }
        }
    }

    /**
     * Recursively fold constant sub-expressions bottom-up.
     * Returns the original expression if no folding is possible,
     * or a new ConstExprent if the entire expression evaluates to a constant.
     */
    private Exprent foldExpression(Exprent expr) {
        if (expr instanceof FunctionExprent func) {
            // Recursively fold operands first
            List<Exprent> ops = func.getLstOperands();
            boolean anyChanged = false;
            for (int i = 0; i < ops.size(); i++) {
                Exprent folded = foldExpression(ops.get(i));
                if (folded != ops.get(i)) {
                    ops.set(i, folded);
                    anyChanged = true;
                }
            }

            // Try to evaluate this function with its (possibly folded) operands
            Integer result = tryEvaluate(func);
            if (result != null) {
                modified = true;
                return makeIntConst(result, func);
            }

            // Try algebraic identities: n*(n+k)%2 → 0 when k is odd
            result = tryAlgebraicFold(func);
            if (result != null) {
                modified = true;
                return makeIntConst(result, func);
            }

            if (anyChanged) modified = true;
            return func;
        }

        // Fold sub-expressions inside assignments (right-hand side)
        if (expr instanceof AssignmentExprent assign) {
            Exprent right = assign.getRight();
            Exprent folded = foldExpression(right);
            if (folded != right) {
                assign.setRight(folded);
                modified = true;
            }
            return expr;
        }

        // Fold condition inside IfExprent (when encountered as expression)
        if (expr instanceof IfExprent ifExpr) {
            Exprent cond = ifExpr.getCondition();
            Exprent folded = foldExpression(cond);
            if (folded != cond) {
                ifExpr.setCondition(folded);
                modified = true;
            }
            return expr;
        }

        // Generic: process sub-expressions of any other Exprent type
        // (SwitchHeadExprent, ExitExprent, InvocationExprent, etc.)
        for (Exprent sub : expr.getAllExprents()) {
            Exprent folded = foldExpression(sub);
            if (folded != sub) {
                expr.replaceExprent(sub, folded);
                modified = true;
            }
        }
        return expr;
    }

    /**
     * Try to fold algebraic identities that don't require all-constant operands.
     *
     * Identity: n * (n + k) % 2 → 0 when k is an odd constant.
     * Proof: if n is even, n*x is even; if n is odd, (n+odd) is even, so n*even is even.
     * Covers both orderings: n*(n+k), (n+k)*n, and commutative ADD: n*(k+n), (k+n)*n.
     */
    private Integer tryAlgebraicFold(FunctionExprent func) {
        if (func.getFuncType() != FunctionType.REM) return null;
        List<Exprent> ops = func.getLstOperands();
        if (ops.size() < 2) return null;

        // Check: ... % 2
        if (!(ops.get(1) instanceof ConstExprent divisor)) return null;
        if (!(divisor.getValue() instanceof Integer di) || di != 2) return null;

        // Dividend must be MUL(a, b)
        Exprent dividend = ops.get(0);
        if (!(dividend instanceof FunctionExprent mul) || mul.getFuncType() != FunctionType.MUL) return null;

        List<Exprent> mulOps = mul.getLstOperands();
        if (mulOps.size() < 2) return null;

        // Try both orderings: n * (n+k) and (n+k) * n
        Integer result = checkNTimesNPlusK(mulOps.get(0), mulOps.get(1));
        if (result != null) return result;
        return checkNTimesNPlusK(mulOps.get(1), mulOps.get(0));
    }

    /**
     * Check if a=n and b=(n+k) where k is an odd constant.
     * Returns 0 if the pattern matches (n*(n+k)%2 is always 0), null otherwise.
     */
    private Integer checkNTimesNPlusK(Exprent n, Exprent nPlusK) {
        if (!(nPlusK instanceof FunctionExprent add) || add.getFuncType() != FunctionType.ADD) return null;
        List<Exprent> addOps = add.getLstOperands();
        if (addOps.size() < 2) return null;

        // b = n + k or b = k + n (ADD is commutative)
        if (sameVar(n, addOps.get(0)) && isOddConstant(addOps.get(1))) return 0;
        if (sameVar(n, addOps.get(1)) && isOddConstant(addOps.get(0))) return 0;
        return null;
    }

    private boolean sameVar(Exprent a, Exprent b) {
        if (a instanceof VarExprent va && b instanceof VarExprent vb) {
            return va.getIndex() == vb.getIndex();
        }
        return false;
    }

    private boolean isOddConstant(Exprent expr) {
        if (expr instanceof ConstExprent ce && ce.getValue() instanceof Integer iv) {
            return (iv & 1) == 1;
        }
        // Check if this is a variable with a known odd init value (register field)
        if (expr instanceof VarExprent var && varInitValues != null) {
            Integer initVal = varInitValues.get(var.getIndex());
            if (initVal != null && (initVal & 1) == 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Try to evaluate a FunctionExprent to a constant integer.
     * Returns null if the function cannot be evaluated at compile time.
     */
    private Integer tryEvaluate(FunctionExprent func) {
        FunctionType ft = func.getFuncType();
        List<Exprent> ops = func.getLstOperands();

        // Binary operations
        if (ft.arity == 2 && ops.size() >= 2) {
            if (!(ops.get(0) instanceof ConstExprent a) || !(ops.get(1) instanceof ConstExprent b))
                return null;
            Object aVal = a.getValue(), bVal = b.getValue();
            if (!(aVal instanceof Integer ai) || !(bVal instanceof Integer bi))
                return null;

            return switch (ft) {
                case ADD -> ai + bi;
                case SUB -> ai - bi;
                case MUL -> ai * bi;
                case DIV -> bi != 0 ? ai / bi : null;
                case REM -> bi != 0 ? ai % bi : null;
                case AND -> ai & bi;
                case OR  -> ai | bi;
                case XOR -> ai ^ bi;
                case SHL -> ai << bi;
                case SHR -> ai >> bi;
                case USHR -> ai >>> bi;
                case EQ -> (ai.intValue() == bi.intValue()) ? 1 : 0;
                case NE -> (ai.intValue() != bi.intValue()) ? 1 : 0;
                case LT -> (ai < bi) ? 1 : 0;
                case GE -> (ai >= bi) ? 1 : 0;
                case GT -> (ai > bi) ? 1 : 0;
                case LE -> (ai <= bi) ? 1 : 0;
                default -> null;
            };
        }

        // Unary operations
        if (ft.arity == 1 && ops.size() >= 1) {
            if (!(ops.get(0) instanceof ConstExprent a)) return null;
            Object aVal = a.getValue();
            if (!(aVal instanceof Integer ai)) return null;

            return switch (ft) {
                case NEG -> -ai;
                case BIT_NOT -> ~ai;
                case BOOL_NOT -> ai == 0 ? 1 : 0;
                case I2B -> (int)(byte)(int) ai;
                case I2C -> (int)(char)(int) ai;
                case I2S -> (int)(short)(int) ai;
                default -> null;
            };
        }

        // Ternary: condition ? trueVal : falseVal — all constant
        if (ft == FunctionType.TERNARY && ops.size() >= 3) {
            if (!(ops.get(0) instanceof ConstExprent cond)) return null;
            Object cv = cond.getValue();
            if (!(cv instanceof Integer ci)) return null;

            // Pick the branch
            Exprent branch = (ci != 0) ? ops.get(1) : ops.get(2);
            if (branch instanceof ConstExprent brConst && brConst.getValue() instanceof Integer bi) {
                return bi;
            }
        }

        return null;
    }

    /**
     * Create an integer ConstExprent preserving the source bytecode offsets.
     */
    private ConstExprent makeIntConst(int value, Exprent source) {
        return new ConstExprent(VarType.VARTYPE_INT, value, source.bytecode);
    }
}

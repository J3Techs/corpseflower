// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.deobfuscator;

import org.jetbrains.java.decompiler.code.*;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.code.cfg.ExceptionRangeCFG;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.decompiler.ValidationHelper;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.GenericDominatorEngine;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.IGraph;
import org.jetbrains.java.decompiler.modules.decompiler.decompose.IGraphNode;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.consts.LinkConstant;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.util.*;
import java.util.Map.Entry;

public final class ExceptionDeobfuscator {

  private static final class Range {
    private final BasicBlock handler;
    private final String uniqueStr;
    private final Set<BasicBlock> protectedRange;
    private final ExceptionRangeCFG rangeCFG;

    private Range(BasicBlock handler, String uniqueStr, Set<BasicBlock> protectedRange, ExceptionRangeCFG rangeCFG) {
      this.handler = handler;
      this.uniqueStr = uniqueStr;
      this.protectedRange = protectedRange;
      this.rangeCFG = rangeCFG;
    }
  }

  public static void restorePopRanges(ControlFlowGraph graph) {

    List<Range> lstRanges = new ArrayList<>();

    // aggregate ranges
    for (ExceptionRangeCFG range : graph.getExceptions()) {
      boolean found = false;
      for (Range arr : lstRanges) {
        if (arr.handler == range.getHandler() && InterpreterUtil.equalObjects(range.getUniqueExceptionsString(), arr.uniqueStr)) {
          arr.protectedRange.addAll(range.getProtectedRange());
          found = true;
          break;
        }
      }

      if (!found) {
        // doesn't matter, which range chosen
        lstRanges.add(new Range(range.getHandler(), range.getUniqueExceptionsString(), new HashSet<>(range.getProtectedRange()), range));
      }
    }

    // process aggregated ranges
    for (Range range : lstRanges) {

      if (range.uniqueStr != null) {

        BasicBlock handler = range.handler;
        InstructionSequence seq = handler.getSeq();

        Instruction firstinstr;
        if (seq.length() > 0) {
          firstinstr = seq.getInstr(0);

          if (firstinstr.opcode == CodeConstants.opc_pop ||
              firstinstr.opcode == CodeConstants.opc_astore) {
            Set<BasicBlock> setrange = new HashSet<>(range.protectedRange);

            for (Range range_super : lstRanges) { // finally or strict superset

              if (range != range_super) {

                Set<BasicBlock> setrange_super = new HashSet<>(range_super.protectedRange);

                if (!setrange.contains(range_super.handler) && !setrange_super.contains(handler)
                    && (range_super.uniqueStr == null || setrange_super.containsAll(setrange))) {

                  if (range_super.uniqueStr == null) {
                    setrange_super.retainAll(setrange);
                  }
                  else {
                    setrange_super.removeAll(setrange);
                  }

                  if (!setrange_super.isEmpty()) {

                    BasicBlock newblock = handler;

                    // split the handler
                    if (seq.length() > 1) {
                      newblock = new BasicBlock(++graph.last_id);
                      InstructionSequence newseq = new SimpleInstructionSequence();
                      newseq.addInstruction(firstinstr.clone(), -1);

                      newblock.setSeq(newseq);
                      graph.getBlocks().addWithKey(newblock, newblock.id);


                      List<BasicBlock> lstTemp = new ArrayList<>();
                      lstTemp.addAll(handler.getPreds());
                      lstTemp.addAll(handler.getPredExceptions());

                      // replace predecessors
                      for (BasicBlock pred : lstTemp) {
                        pred.replaceSuccessor(handler, newblock);
                      }

                      // replace handler
                      for (ExceptionRangeCFG range_ext : graph.getExceptions()) {
                        if (range_ext.getHandler() == handler) {
                          range_ext.setHandler(newblock);
                        }
                        else if (range_ext.getProtectedRange().contains(handler)) {
                          newblock.addSuccessorException(range_ext.getHandler());
                          range_ext.getProtectedRange().add(newblock);
                        }
                      }

                      newblock.addSuccessor(handler);
                      if (graph.getFirst() == handler) {
                        graph.setFirst(newblock);
                      }

                      // remove the first pop in the handler
                      seq.removeInstruction(0);
                    }

                    newblock.addSuccessorException(range_super.handler);
                    range_super.rangeCFG.getProtectedRange().add(newblock);

                    handler = range.rangeCFG.getHandler();
                    seq = handler.getSeq();
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  public static void insertEmptyExceptionHandlerBlocks(ControlFlowGraph graph) {

    Set<BasicBlock> setVisited = new HashSet<>();

    for (ExceptionRangeCFG range : graph.getExceptions()) {
      BasicBlock handler = range.getHandler();

      if (setVisited.contains(handler)) {
        continue;
      }
      setVisited.add(handler);

      BasicBlock emptyblock = new BasicBlock(++graph.last_id);
      graph.getBlocks().addWithKey(emptyblock, emptyblock.id);

      // only exception predecessors considered
      List<BasicBlock> lstTemp = new ArrayList<>(handler.getPredExceptions());

      // replace predecessors
      for (BasicBlock pred : lstTemp) {
        pred.replaceSuccessor(handler, emptyblock);
      }

      // replace handler
      for (ExceptionRangeCFG range_ext : graph.getExceptions()) {
        if (range_ext.getHandler() == handler) {
          range_ext.setHandler(emptyblock);
        }
        else if (range_ext.getProtectedRange().contains(handler)) {
          emptyblock.addSuccessorException(range_ext.getHandler());
          range_ext.getProtectedRange().add(emptyblock);
        }
      }

      emptyblock.addSuccessor(handler);
      if (graph.getFirst() == handler) {
        graph.setFirst(emptyblock);
      }
    }
  }

  public static void removeEmptyRanges(ControlFlowGraph graph) {

    List<ExceptionRangeCFG> lstRanges = graph.getExceptions();
    for (int i = lstRanges.size() - 1; i >= 0; i--) {
      ExceptionRangeCFG range = lstRanges.get(i);

      boolean isEmpty = true;
      for (BasicBlock block : range.getProtectedRange()) {
        if (!block.getSeq().isEmpty()) {
          isEmpty = false;
          break;
        }
      }

      if (isEmpty) {
        for (BasicBlock block : range.getProtectedRange()) {
          block.removeSuccessorException(range.getHandler());
        }

        lstRanges.remove(i);
        graph.addComment("$VF: Removed empty exception range");
      }
    }
  }

  public static void splitCleanupCloseBlocks(ControlFlowGraph graph, StructClass cl) {
    List<BasicBlock> snapshot = new ArrayList<>();
    for (BasicBlock block : graph.getBlocks()) {
      snapshot.add(block);
    }

    for (BasicBlock block : snapshot) {
      int cleanupStart = getCleanupCloseSuffixStart(block, cl);
      if (cleanupStart <= 0) {
        continue;
      }

      splitBlockTail(graph, block, cleanupStart);
    }
  }

  public static void removeNonThrowingExceptionEdges(ControlFlowGraph graph, StructClass cl) {
    List<ExceptionRangeCFG> ranges = graph.getExceptions();
    for (int i = ranges.size() - 1; i >= 0; i--) {
      ExceptionRangeCFG range = ranges.get(i);
      List<BasicBlock> snapshot = new ArrayList<>(range.getProtectedRange());
      for (BasicBlock block : snapshot) {
        if (canBlockThrow(block, cl)) {
          continue;
        }

        range.getProtectedRange().remove(block);
        block.removeSuccessorException(range.getHandler());
      }

      if (range.getProtectedRange().isEmpty()) {
        ranges.remove(i);
      }
    }
  }

  public static void removeRedundantCleanupCloseBlocks(ControlFlowGraph graph, StructClass cl) {
    List<BasicBlock> snapshot = new ArrayList<>();
    for (BasicBlock block : graph.getBlocks()) {
      snapshot.add(block);
    }

    for (BasicBlock block : snapshot) {
      if (!graph.getBlocks().containsKey(block.id) || block.getSuccs().size() != 1 || !block.getSuccExceptions().isEmpty()) {
        continue;
      }

      Integer varIndex = getCleanupCloseVarIndex(block, cl);
      if (varIndex == null || !hasDuplicateCleanupClose(block.getSuccs().get(0), varIndex, cl)) {
        continue;
      }

      BasicBlock successor = block.getSuccs().get(0);
      for (BasicBlock predecessor : new ArrayList<>(block.getPreds())) {
        predecessor.replaceSuccessor(block, successor);
      }

      graph.removeBlock(block);
    }
  }

  private static boolean canBlockThrow(BasicBlock block, StructClass cl) {
    InstructionSequence seq = block.getSeq();
    if (seq.isEmpty()) {
      // Keep synthetic empty blocks conservative. They are often structural artifacts.
      return true;
    }

    if (isSyntheticCleanupCloseBlock(block, cl)) {
      return false;
    }

    for (Instruction instruction : seq) {
      if (canInstructionThrow(instruction)) {
        return true;
      }
    }

    return false;
  }

  private static int getCleanupCloseSuffixStart(BasicBlock block, StructClass cl) {
    if (block.getSuccExceptions().isEmpty()) {
      return -1;
    }

    InstructionSequence seq = block.getSeq();
    int length = seq.length();
    if (length < 2) {
      return -1;
    }

    if (isReturnInstruction(seq.getLastInstr())
        && length >= 3
        && seq.getInstr(length - 3).opcode == CodeConstants.opc_aload
        && isCleanupCloseInvocation(seq.getInstr(length - 2), cl)) {
      return length - 3;
    }

    if (seq.getInstr(length - 2).opcode == CodeConstants.opc_aload
        && isCleanupCloseInvocation(seq.getInstr(length - 1), cl)) {
      return length - 2;
    }

    return -1;
  }

  private static Integer getCleanupCloseVarIndex(BasicBlock block, StructClass cl) {
    InstructionSequence seq = block.getSeq();
    if (seq.length() < 2 || seq.length() > 3) {
      return null;
    }

    int invokeIndex = seq.length() == 3 ? 1 : seq.length() - 1;
    int loadIndex = invokeIndex - 1;
    if (loadIndex < 0 || seq.getInstr(loadIndex).opcode != CodeConstants.opc_aload) {
      return null;
    }

    if (seq.length() == 3 && !isReturnInstruction(seq.getLastInstr())) {
      return null;
    }

    if (!isCleanupCloseInvocation(seq.getInstr(invokeIndex), cl)) {
      return null;
    }

    return seq.getInstr(loadIndex).operand(0);
  }

  private static boolean isSyntheticCleanupCloseBlock(BasicBlock block, StructClass cl) {
    Integer varIndex = getCleanupCloseVarIndex(block, cl);
    if (varIndex == null) {
      return false;
    }

    InstructionSequence seq = block.getSeq();
    return hasNullGuardPredecessor(block) || isReturnInstruction(seq.getLastInstr()) || hasCleanupSuccessor(block);
  }

  private static boolean hasDuplicateCleanupClose(BasicBlock start, int varIndex, StructClass cl) {
    Deque<BasicBlock> work = new ArrayDeque<>();
    Set<BasicBlock> seen = new HashSet<>();
    work.add(start);

    while (!work.isEmpty()) {
      BasicBlock block = work.removeFirst();
      if (!seen.add(block)) {
        continue;
      }

      Integer loadedVar = getCleanupCloseVarIndex(block, cl);
      if (loadedVar != null) {
        if (loadedVar == varIndex) {
          return true;
        }

        work.addAll(block.getSuccs());
        continue;
      }

      if (isNullGuardBlock(block)) {
        work.addAll(block.getSuccs());
        continue;
      }

      if (block.getSeq().isEmpty() || isReturnInstruction(block.getSeq().getLastInstr())) {
        continue;
      }

      return false;
    }

    return false;
  }

  private static boolean hasNullGuardPredecessor(BasicBlock block) {
    for (BasicBlock predecessor : block.getPreds()) {
      InstructionSequence predecessorSeq = predecessor.getSeq();
      if (predecessorSeq.isEmpty()) {
        continue;
      }

      int opcode = predecessorSeq.getLastInstr().opcode;
      if (opcode == CodeConstants.opc_ifnull || opcode == CodeConstants.opc_ifnonnull) {
        return true;
      }
    }

    return false;
  }

  private static boolean hasCleanupSuccessor(BasicBlock block) {
    if (block.getSuccs().size() != 1) {
      return false;
    }

    InstructionSequence successorSeq = block.getSuccs().get(0).getSeq();
    if (successorSeq.isEmpty()) {
      return true;
    }

    Instruction last = successorSeq.getLastInstr();
    if (last.opcode == CodeConstants.opc_ifnull || last.opcode == CodeConstants.opc_ifnonnull) {
      return true;
    }

    return isReturnInstruction(last);
  }

  private static boolean isNullGuardBlock(BasicBlock block) {
    InstructionSequence seq = block.getSeq();
    if (seq.length() != 2 || seq.getInstr(0).opcode != CodeConstants.opc_aload) {
      return false;
    }

    int opcode = seq.getLastInstr().opcode;
    return opcode == CodeConstants.opc_ifnull || opcode == CodeConstants.opc_ifnonnull;
  }

  private static boolean isCleanupCloseInvocation(Instruction instruction, StructClass cl) {
    if (instruction.opcode != CodeConstants.opc_invokevirtual && instruction.opcode != CodeConstants.opc_invokeinterface) {
      return false;
    }

    LinkConstant link = cl.getPool().getLinkConstant(instruction.operand(0));
    if (link == null || !"close".equals(link.elementname) || !"()V".equals(link.descriptor)) {
      return false;
    }

    if (link.classname != null && link.classname.startsWith("java/io/")) {
      return true;
    }

    try {
      return DecompilerContext.getStructContext().instanceOf(link.classname, "java/lang/AutoCloseable");
    }
    catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean isReturnInstruction(Instruction instruction) {
    if (instruction == null) {
      return false;
    }

    int opcode = instruction.opcode;
    return opcode >= CodeConstants.opc_ireturn && opcode <= CodeConstants.opc_return;
  }

  private static void splitBlockTail(ControlFlowGraph graph, BasicBlock block, int splitIndex) {
    InstructionSequence source = block.getSeq();
    SimpleInstructionSequence tail = new SimpleInstructionSequence();
    LinkedList<Integer> tailOffsets = new LinkedList<>();
    List<Integer> sourceOffsets = block.getInstrOldOffsets();

    for (int i = source.length() - 1; i >= splitIndex; i--) {
      tail.addInstruction(0, source.getInstr(i), -1);
      tailOffsets.addFirst(block.getOldOffset(i));
      source.removeInstruction(i);
      if (i < sourceOffsets.size()) {
        sourceOffsets.remove(i);
      }
    }

    BasicBlock tailBlock = new BasicBlock(++graph.last_id);
    tailBlock.setSeq(tail);
    tailBlock.getInstrOldOffsets().addAll(tailOffsets);

    List<BasicBlock> successors = new ArrayList<>(block.getSuccs());
    for (BasicBlock successor : successors) {
      block.removeSuccessor(successor);
      tailBlock.addSuccessor(successor);
    }

    block.addSuccessor(tailBlock);
    graph.getBlocks().addWithKey(tailBlock, tailBlock.id);

    if (graph.getFinallyExits().remove(block)) {
      graph.getFinallyExits().add(tailBlock);
    }

    List<BasicBlock> exceptionSuccessors = new ArrayList<>(block.getSuccExceptions());
    for (BasicBlock handler : exceptionSuccessors) {
      tailBlock.addSuccessorException(handler);

      ExceptionRangeCFG range = graph.getExceptionRange(handler, block);
      if (range != null && !range.getProtectedRange().contains(tailBlock)) {
        range.getProtectedRange().add(tailBlock);
      }
    }
  }

  private static boolean canInstructionThrow(Instruction instruction) {
    int op = instruction.opcode;
    return switch (op) {
      case CodeConstants.opc_nop, CodeConstants.opc_aconst_null,
           CodeConstants.opc_iconst_m1, CodeConstants.opc_iconst_0, CodeConstants.opc_iconst_1,
           CodeConstants.opc_iconst_2, CodeConstants.opc_iconst_3, CodeConstants.opc_iconst_4, CodeConstants.opc_iconst_5,
           CodeConstants.opc_lconst_0, CodeConstants.opc_lconst_1,
           CodeConstants.opc_fconst_0, CodeConstants.opc_fconst_1, CodeConstants.opc_fconst_2,
           CodeConstants.opc_dconst_0, CodeConstants.opc_dconst_1,
           CodeConstants.opc_bipush, CodeConstants.opc_sipush, CodeConstants.opc_ldc, CodeConstants.opc_ldc_w, CodeConstants.opc_ldc2_w,
           CodeConstants.opc_iload, CodeConstants.opc_lload, CodeConstants.opc_fload, CodeConstants.opc_dload, CodeConstants.opc_aload,
           CodeConstants.opc_iload_0, CodeConstants.opc_iload_1, CodeConstants.opc_iload_2, CodeConstants.opc_iload_3,
           CodeConstants.opc_lload_0, CodeConstants.opc_lload_1, CodeConstants.opc_lload_2, CodeConstants.opc_lload_3,
           CodeConstants.opc_fload_0, CodeConstants.opc_fload_1, CodeConstants.opc_fload_2, CodeConstants.opc_fload_3,
           CodeConstants.opc_dload_0, CodeConstants.opc_dload_1, CodeConstants.opc_dload_2, CodeConstants.opc_dload_3,
           CodeConstants.opc_aload_0, CodeConstants.opc_aload_1, CodeConstants.opc_aload_2, CodeConstants.opc_aload_3,
           CodeConstants.opc_istore, CodeConstants.opc_lstore, CodeConstants.opc_fstore, CodeConstants.opc_dstore, CodeConstants.opc_astore,
           CodeConstants.opc_istore_0, CodeConstants.opc_istore_1, CodeConstants.opc_istore_2, CodeConstants.opc_istore_3,
           CodeConstants.opc_lstore_0, CodeConstants.opc_lstore_1, CodeConstants.opc_lstore_2, CodeConstants.opc_lstore_3,
           CodeConstants.opc_fstore_0, CodeConstants.opc_fstore_1, CodeConstants.opc_fstore_2, CodeConstants.opc_fstore_3,
           CodeConstants.opc_dstore_0, CodeConstants.opc_dstore_1, CodeConstants.opc_dstore_2, CodeConstants.opc_dstore_3,
           CodeConstants.opc_astore_0, CodeConstants.opc_astore_1, CodeConstants.opc_astore_2, CodeConstants.opc_astore_3,
           CodeConstants.opc_pop, CodeConstants.opc_pop2,
           CodeConstants.opc_dup, CodeConstants.opc_dup_x1, CodeConstants.opc_dup_x2,
           CodeConstants.opc_dup2, CodeConstants.opc_dup2_x1, CodeConstants.opc_dup2_x2,
           CodeConstants.opc_swap,
           CodeConstants.opc_iadd, CodeConstants.opc_ladd, CodeConstants.opc_fadd, CodeConstants.opc_dadd,
           CodeConstants.opc_isub, CodeConstants.opc_lsub, CodeConstants.opc_fsub, CodeConstants.opc_dsub,
           CodeConstants.opc_imul, CodeConstants.opc_lmul, CodeConstants.opc_fmul, CodeConstants.opc_dmul,
           CodeConstants.opc_fdiv, CodeConstants.opc_ddiv, CodeConstants.opc_frem, CodeConstants.opc_drem,
           CodeConstants.opc_ineg, CodeConstants.opc_lneg, CodeConstants.opc_fneg, CodeConstants.opc_dneg,
           CodeConstants.opc_ishl, CodeConstants.opc_lshl, CodeConstants.opc_ishr, CodeConstants.opc_lshr,
           CodeConstants.opc_iushr, CodeConstants.opc_lushr,
           CodeConstants.opc_iand, CodeConstants.opc_land, CodeConstants.opc_ior, CodeConstants.opc_lor,
           CodeConstants.opc_ixor, CodeConstants.opc_lxor,
           CodeConstants.opc_iinc,
           CodeConstants.opc_i2l, CodeConstants.opc_i2f, CodeConstants.opc_i2d,
           CodeConstants.opc_l2i, CodeConstants.opc_l2f, CodeConstants.opc_l2d,
           CodeConstants.opc_f2i, CodeConstants.opc_f2l, CodeConstants.opc_f2d,
           CodeConstants.opc_d2i, CodeConstants.opc_d2l, CodeConstants.opc_d2f,
           CodeConstants.opc_i2b, CodeConstants.opc_i2c, CodeConstants.opc_i2s,
           CodeConstants.opc_lcmp, CodeConstants.opc_fcmpl, CodeConstants.opc_fcmpg,
           CodeConstants.opc_dcmpl, CodeConstants.opc_dcmpg,
           CodeConstants.opc_ifeq, CodeConstants.opc_ifne, CodeConstants.opc_iflt,
           CodeConstants.opc_ifge, CodeConstants.opc_ifgt, CodeConstants.opc_ifle,
           CodeConstants.opc_if_icmpeq, CodeConstants.opc_if_icmpne, CodeConstants.opc_if_icmplt,
           CodeConstants.opc_if_icmpge, CodeConstants.opc_if_icmpgt, CodeConstants.opc_if_icmple,
           CodeConstants.opc_if_acmpeq, CodeConstants.opc_if_acmpne,
           CodeConstants.opc_goto, CodeConstants.opc_goto_w,
           CodeConstants.opc_jsr, CodeConstants.opc_jsr_w, CodeConstants.opc_ret,
           CodeConstants.opc_tableswitch, CodeConstants.opc_lookupswitch,
           CodeConstants.opc_ireturn, CodeConstants.opc_lreturn, CodeConstants.opc_freturn,
           CodeConstants.opc_dreturn, CodeConstants.opc_areturn, CodeConstants.opc_return,
           CodeConstants.opc_instanceof,
           CodeConstants.opc_ifnull, CodeConstants.opc_ifnonnull,
           CodeConstants.opc_getstatic, CodeConstants.opc_putstatic -> false;
      default -> true;
    };
  }

  public static void removeCircularRanges(final ControlFlowGraph graph) {

    GenericDominatorEngine engine = new GenericDominatorEngine(new IGraph() {
      @Override
      public List<? extends IGraphNode> getReversePostOrderList() {
        return graph.getReversePostOrder();
      }

      @Override
      public Set<? extends IGraphNode> getRoots() {
        return new HashSet<>(Collections.singletonList(graph.getFirst()));
      }
    });

    engine.initialize();

    List<ExceptionRangeCFG> lstRanges = graph.getExceptions();
    for (int i = lstRanges.size() - 1; i >= 0; i--) {
      ExceptionRangeCFG range = lstRanges.get(i);

      BasicBlock handler = range.getHandler();
      List<BasicBlock> rangeList = range.getProtectedRange();

      if (rangeList.contains(handler)) {  // TODO: better removing strategy

        List<BasicBlock> lstRemBlocks = getReachableBlocksRestricted(range.getHandler(), range, engine);

        if (lstRemBlocks.size() < rangeList.size() || rangeList.size() == 1) {
          for (BasicBlock block : lstRemBlocks) {
            block.removeSuccessorException(handler);
            rangeList.remove(block);
          }
        }

        if (rangeList.isEmpty()) {
          lstRanges.remove(i);
        }
      }
    }
  }

  private static List<BasicBlock> getReachableBlocksRestricted(BasicBlock start, ExceptionRangeCFG range, GenericDominatorEngine engine) {

    List<BasicBlock> lstRes = new ArrayList<>();

    LinkedList<BasicBlock> stack = new LinkedList<>();
    Set<BasicBlock> setVisited = new HashSet<>();

    stack.addFirst(start);

    while (!stack.isEmpty()) {
      BasicBlock block = stack.removeFirst();

      setVisited.add(block);

      if (range.getProtectedRange().contains(block) && engine.isDominator(block, start)) {
        lstRes.add(block);

        List<BasicBlock> lstSuccs = new ArrayList<>(block.getSuccs());
        lstSuccs.addAll(block.getSuccExceptions());

        for (BasicBlock succ : lstSuccs) {
          if (!setVisited.contains(succ)) {
            stack.add(succ);
          }
        }
      }
    }

    return lstRes;
  }

  public static boolean hasObfuscatedExceptions(ControlFlowGraph graph) {
    Map<BasicBlock, Set<BasicBlock>> mapRanges = new HashMap<>();
    for (ExceptionRangeCFG range : graph.getExceptions()) {
      mapRanges.computeIfAbsent(range.getHandler(), k -> new HashSet<>()).addAll(range.getProtectedRange());
    }

    for (Entry<BasicBlock, Set<BasicBlock>> ent : mapRanges.entrySet()) {
      Set<BasicBlock> setEntries = new HashSet<>();

      for (BasicBlock block : ent.getValue()) {
        Set<BasicBlock> setTemp = new HashSet<>(block.getPreds());
        setTemp.removeAll(ent.getValue());

        if (!setTemp.isEmpty()) {
          setEntries.add(block);
        }
      }

      if (ent.getValue().contains(graph.getFirst())) {
        setEntries.add(graph.getFirst());
      }

      if (!setEntries.isEmpty()) {
        if (setEntries.size() > 1 /*|| ent.getValue().contains(first)*/) {
          return true;
        }
      }
    }

    return false;
  }

  public static boolean handleMultipleEntryExceptionRanges(ControlFlowGraph graph) {
    GenericDominatorEngine engine = new GenericDominatorEngine(new IGraph() {
      @Override
      public List<? extends IGraphNode> getReversePostOrderList() {
        return graph.getReversePostOrder();
      }

      @Override
      public Set<? extends IGraphNode> getRoots() {
        return new HashSet<>(Collections.singletonList(graph.getFirst()));
      }
    });

    engine.initialize();

    boolean found;

    while (true) {
      found = false;
      boolean splitted = false;

      for (ExceptionRangeCFG range : graph.getExceptions()) {
        Set<BasicBlock> setEntries = getRangeEntries(range);

        if (setEntries.size() > 1) { // multiple-entry protected range
          found = true;

          if (splitExceptionRange(range, setEntries, graph, engine)) {
            splitted = true;
            graph.addComment("$VF: Handled exception range with multiple entry points by splitting it");
            break;
          }
        }
      }

      if (!splitted) {
        break;
      }
    }

    return !found;
  }

  private static Set<BasicBlock> getRangeEntries(ExceptionRangeCFG range) {
    Set<BasicBlock> setEntries = new HashSet<>();
    Set<BasicBlock> setRange = new HashSet<>(range.getProtectedRange());

    for (BasicBlock block : range.getProtectedRange()) {
      Set<BasicBlock> setPreds = new HashSet<>(block.getPreds());
      setPreds.removeAll(setRange);

      if (!setPreds.isEmpty()) {
        setEntries.add(block);
      }
    }

    return setEntries;
  }

  private static boolean splitExceptionRange(ExceptionRangeCFG range,
                                             Set<BasicBlock> setEntries,
                                             ControlFlowGraph graph,
                                             GenericDominatorEngine engine) {
    for (BasicBlock entry : setEntries) {
      List<BasicBlock> lstSubrangeBlocks = getReachableBlocksRestricted(entry, range, engine);
      if (!lstSubrangeBlocks.isEmpty() && lstSubrangeBlocks.size() < range.getProtectedRange().size()) {
        // add new range
        ExceptionRangeCFG subRange = new ExceptionRangeCFG(lstSubrangeBlocks, range.getHandler(), range.getExceptionTypes());
        graph.getExceptions().add(subRange);
        // shrink the original range
        range.getProtectedRange().removeAll(lstSubrangeBlocks);
        return true;
      }
      else {
        // should not happen
        DecompilerContext.getLogger().writeMessage("Inconsistency found while splitting protected range", IFernflowerLogger.Severity.WARN);
      }
    }

    return false;
  }

  public static void insertDummyExceptionHandlerBlocks(ControlFlowGraph graph, BytecodeVersion bytecode_version) {
    Map<BasicBlock, List<ExceptionRangeCFG>> mapRanges = new HashMap<>();
    for (ExceptionRangeCFG range : graph.getExceptions()) {
      mapRanges.computeIfAbsent(range.getHandler(), k -> new ArrayList<>()).add(range);
    }

    for (Entry<BasicBlock, List<ExceptionRangeCFG>> ent : mapRanges.entrySet()) {
      BasicBlock handler = ent.getKey();
      List<ExceptionRangeCFG> ranges = ent.getValue();

      if (ranges.size() == 1) {
        continue;
      }

      if (!DecompilerContext.getOption(IFernflowerPreferences.OLD_TRY_DEDUP)) {
        for (int i = 1; i < ranges.size(); i++) {
          ExceptionRangeCFG range = ranges.get(i);

          // Duplicate block now
          BasicBlock newBlock = new BasicBlock(++graph.last_id);
          newBlock.setSeq(handler.getSeq().clone());

          graph.getBlocks().addWithKey(newBlock, newBlock.id);

          // only exception predecessors from this range considered
          List<BasicBlock> lstPredExceptions = new ArrayList<>(handler.getPredExceptions());
          lstPredExceptions.retainAll(range.getProtectedRange());

          // replace predecessors
          for (BasicBlock pred : lstPredExceptions) {
            pred.replaceSuccessor(handler, newBlock);
          }
          range.setHandler(newBlock);

          // Add successors

          for (BasicBlock succ : handler.getSuccs()) {
            newBlock.addSuccessor(succ);
          }

          for (BasicBlock succ : handler.getSuccExceptions()) {
            newBlock.addSuccessorException(succ);
          }
        }

        if (!isMatchException(handler)) {
          graph.addComment("$VF: Duplicated exception handlers to handle obfuscated exceptions");
        }

      } else {
        for (ExceptionRangeCFG range : ranges) {

          // add some dummy instructions to prevent optimizing away the empty block
          SimpleInstructionSequence seq = new SimpleInstructionSequence();
          seq.addInstruction(Instruction.create(CodeConstants.opc_bipush, false, CodeConstants.GROUP_GENERAL, bytecode_version, new int[]{0}, 1), -1);
          seq.addInstruction(Instruction.create(CodeConstants.opc_pop, false, CodeConstants.GROUP_GENERAL, bytecode_version, null, 1), -1);

          BasicBlock dummyBlock = new BasicBlock(++graph.last_id);
          dummyBlock.setSeq(seq);

          graph.getBlocks().addWithKey(dummyBlock, dummyBlock.id);

          // only exception predecessors from this range considered
          List<BasicBlock> lstPredExceptions = new ArrayList<>(handler.getPredExceptions());
          lstPredExceptions.retainAll(range.getProtectedRange());

          // replace predecessors
          for (BasicBlock pred : lstPredExceptions) {
            pred.replaceSuccessor(handler, dummyBlock);
          }

          // replace handler
          range.setHandler(dummyBlock);
          // add common exception edges
          Set<BasicBlock> commonHandlers = new HashSet<>(handler.getSuccExceptions());
          for (BasicBlock pred : lstPredExceptions) {
            commonHandlers.retainAll(pred.getSuccExceptions());
          }
          // TODO: more sanity checks?
          for (BasicBlock commonHandler : commonHandlers) {
            ExceptionRangeCFG commonRange = graph.getExceptionRange(commonHandler, handler);

            dummyBlock.addSuccessorException(commonHandler);
            commonRange.getProtectedRange().add(dummyBlock);
          }

          dummyBlock.addSuccessor(handler);

          graph.addComment("$VF: Inserted dummy exception handlers to handle obfuscated exceptions");
        }
      }
    }
  }

  private static boolean isMatchException(BasicBlock block) {
    StructClass cl = DecompilerContext.getContextProperty(DecompilerContext.CURRENT_CLASS);

    // Check if block has any "new MatchException;"
    for (Instruction instr : block.getSeq()) {
      if (instr.opcode == CodeConstants.opc_new) {
        if ("java/lang/MatchException".equals(cl.getPool().getPrimitiveConstant(instr.operand(0)).getString())) {
          return true;

        }
      }
    }

    return false;
  }
}

package org.corpseflower.irreducible;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.Instruction;
import org.jetbrains.java.decompiler.code.SimpleInstructionSequence;
import org.jetbrains.java.decompiler.code.BytecodeVersion;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.code.cfg.ExceptionRangeCFG;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.struct.StructMethod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DispatcherBlockBuilder {
  private DispatcherBlockBuilder() {
  }

  public static boolean isSyntheticDispatcherScaffold(BasicBlock block) {
    if (block == null || block.size() < 2) {
      return false;
    }

    if (block.size() == 2) {
      int first = block.getInstruction(0).opcode;
      int second = block.getInstruction(1).opcode;
      return (first >= CodeConstants.opc_iconst_m1 && first <= CodeConstants.opc_sipush) &&
             second == CodeConstants.opc_istore;
    }

    if (block.getLastInstruction().opcode != CodeConstants.opc_lookupswitch) {
      return false;
    }

    if (block.getInstruction(block.size() - 2).opcode != CodeConstants.opc_iload) {
      return false;
    }

    for (int i = 0; i < block.size() - 2; i++) {
      if (block.getInstruction(i).opcode != CodeConstants.opc_nop) {
        return false;
      }
    }

    return true;
  }

  public static boolean insertDispatcher(ControlFlowGraph graph,
                                         StructMethod mt,
                                         IrreducibleRegionFinder.IrreducibleRegion region,
                                         int dispatchVar,
                                         IFernflowerLogger logger) {
    List<BasicBlock> entries = region.entryPoints();
    if (entries.size() < 2 || entries.size() > 8) {
      return false;
    }

    if (!isSafeRegion(graph, region)) {
      return false;
    }

    BytecodeVersion version = mt.getBytecodeVersion();
    BasicBlock dispatcherBlock = createDispatcherBlock(graph, version, dispatchVar, entries.size());
    BasicBlock defaultBlock = createDefaultBlock(graph, version);
    Map<BasicBlock, BasicBlock> setupBlocks = new LinkedHashMap<>();

    graph.getBlocks().addWithKey(dispatcherBlock, dispatcherBlock.id);
    graph.getBlocks().addWithKey(defaultBlock, defaultBlock.id);
    for (int i = 0; i < entries.size(); i++) {
      BasicBlock entry = entries.get(i);
      BasicBlock setup = createSetupBlock(graph, version, dispatchVar, i);
      setup.addSuccessor(dispatcherBlock);
      graph.getBlocks().addWithKey(setup, setup.id);
      setupBlocks.put(entry, setup);
    }

    // Synthetic LOOKUPSWITCH still has a default branch. Give it a dedicated shim block so
    // switch reconstruction sees a real default arm instead of conflating it with case 0.
    defaultBlock.addSuccessor(entries.get(0));
    dispatcherBlock.addSuccessor(defaultBlock);
    for (BasicBlock entry : entries) {
      dispatcherBlock.addSuccessor(entry);
    }

    List<BasicBlock> syntheticBlocks = new ArrayList<>(setupBlocks.values());
    syntheticBlocks.add(defaultBlock);
    propagateSharedExceptionRanges(graph, region, dispatcherBlock, syntheticBlocks);

    for (BasicBlock entry : entries) {
      BasicBlock setup = setupBlocks.get(entry);

      if (graph.getFirst() == entry) {
        graph.setFirst(setup);
      }

      List<BasicBlock> redirectPreds = new ArrayList<>();
      for (BasicBlock pred : new ArrayList<>(entry.getPreds())) {
        if (pred == setup || pred == dispatcherBlock || DispatcherBlockBuilder.isSyntheticDispatcherScaffold(pred)) {
          continue;
        }
        redirectPreds.add(pred);
      }

      for (BasicBlock pred : redirectPreds) {
        redirectRegularEdge(pred, entry, setup);
      }
    }

    if (logger != null) {
      logger.writeMessage("Inserted dispatcher for " + mt.getName() + mt.getDescriptor() +
                          " with " + entries.size() + " entry points across " + region.blocks().size() + " blocks",
                          IFernflowerLogger.Severity.INFO);
    }

    return true;
  }

  private static boolean isSafeRegion(ControlFlowGraph graph, IrreducibleRegionFinder.IrreducibleRegion region) {
    if (region.blocks().size() > 48) {
      return false;
    }

    List<BasicBlock> externalEntrySources = new ArrayList<>();
    Set<ExceptionRangeCFG> sharedExternalRanges = null;
    boolean methodEntry = false;
    for (BasicBlock entry : region.entryPoints()) {
      if (entry == graph.getFirst()) {
        methodEntry = true;
      }

      for (BasicBlock pred : entry.getPredExceptions()) {
        if (!region.blocks().contains(pred)) {
          return false;
        }
      }

      for (BasicBlock pred : entry.getPreds()) {
        if (!region.blocks().contains(pred) && !externalEntrySources.contains(pred)) {
          externalEntrySources.add(pred);
        }
      }

      Set<ExceptionRangeCFG> externalRanges = getExternalExceptionRanges(graph, region, entry);
      if (sharedExternalRanges == null) {
        sharedExternalRanges = externalRanges;
      } else if (!sharedExternalRanges.equals(externalRanges)) {
        return false;
      }
    }

    if (externalEntrySources.size() + (methodEntry ? 1 : 0) < 2) {
      return false;
    }

    return graph.getLast() == null || !region.blocks().contains(graph.getLast());
  }

  private static Set<ExceptionRangeCFG> getExternalExceptionRanges(ControlFlowGraph graph,
                                                                   IrreducibleRegionFinder.IrreducibleRegion region,
                                                                   BasicBlock entry) {
    Set<ExceptionRangeCFG> ranges = new LinkedHashSet<>();
    for (BasicBlock handler : entry.getSuccExceptions()) {
      if (region.blocks().contains(handler)) {
        continue;
      }

      ExceptionRangeCFG range = graph.getExceptionRange(handler, entry);
      if (range != null) {
        ranges.add(range);
      }
    }
    return ranges;
  }

  private static void propagateSharedExceptionRanges(ControlFlowGraph graph,
                                                     IrreducibleRegionFinder.IrreducibleRegion region,
                                                     BasicBlock dispatcherBlock,
                                                     Iterable<BasicBlock> setupBlocks) {
    Set<ExceptionRangeCFG> sharedRanges = null;
    for (BasicBlock entry : region.entryPoints()) {
      Set<ExceptionRangeCFG> entryRanges = getExternalExceptionRanges(graph, region, entry);
      if (sharedRanges == null) {
        sharedRanges = new LinkedHashSet<>(entryRanges);
      } else {
        sharedRanges.retainAll(entryRanges);
      }
    }

    if (sharedRanges == null || sharedRanges.isEmpty()) {
      return;
    }

    for (ExceptionRangeCFG range : sharedRanges) {
      if (!range.getProtectedRange().contains(dispatcherBlock)) {
        range.getProtectedRange().add(dispatcherBlock);
      }
      dispatcherBlock.addSuccessorException(range.getHandler());

      for (BasicBlock setup : setupBlocks) {
        if (!range.getProtectedRange().contains(setup)) {
          range.getProtectedRange().add(setup);
        }
        setup.addSuccessorException(range.getHandler());
      }
    }
  }

  private static BasicBlock createSetupBlock(ControlFlowGraph graph, BytecodeVersion version, int dispatchVar, int stateId) {
    BasicBlock block = new BasicBlock(++graph.last_id);
    SimpleInstructionSequence seq = new SimpleInstructionSequence();

    seq.addInstruction(createConstInstruction(version, stateId), 0);
    seq.addInstruction(Instruction.create(CodeConstants.opc_istore, false, CodeConstants.GROUP_GENERAL, version, new int[] {dispatchVar}, 1), 1);

    block.setSeq(seq);
    block.getInstrOldOffsets().add(-1);
    block.getInstrOldOffsets().add(-1);
    return block;
  }

  private static BasicBlock createDispatcherBlock(ControlFlowGraph graph, BytecodeVersion version, int dispatchVar, int entryCount) {
    BasicBlock block = new BasicBlock(++graph.last_id);
    SimpleInstructionSequence seq = new SimpleInstructionSequence();

    int switchOffset = entryCount + 2;
    int[] targetOffsets = new int[entryCount + 1];
    for (int i = 0; i < targetOffsets.length; i++) {
      targetOffsets[i] = i;
      seq.addInstruction(Instruction.create(CodeConstants.opc_nop, false, CodeConstants.GROUP_GENERAL, version, null, 1), i);
    }

    seq.addInstruction(Instruction.create(CodeConstants.opc_iload, false, CodeConstants.GROUP_GENERAL, version, new int[] {dispatchVar}, 1), entryCount + 1);

    int[] switchOperands = buildLookupSwitchOperands(targetOffsets, switchOffset);
    Instruction switchInstruction = Instruction.create(CodeConstants.opc_lookupswitch, false, CodeConstants.GROUP_SWITCH, version, switchOperands, 1);
    seq.addInstruction(switchInstruction, switchOffset);
    seq.setPointer(seq.length() - 1);
    switchInstruction.initInstruction(seq);

    block.setSeq(seq);
    for (int i = 0; i < seq.length(); i++) {
      block.getInstrOldOffsets().add(-1);
    }
    return block;
  }

  private static BasicBlock createDefaultBlock(ControlFlowGraph graph, BytecodeVersion version) {
    BasicBlock block = new BasicBlock(++graph.last_id);
    SimpleInstructionSequence seq = new SimpleInstructionSequence();
    seq.addInstruction(Instruction.create(CodeConstants.opc_nop, false, CodeConstants.GROUP_GENERAL, version, null, 1), 0);
    block.setSeq(seq);
    block.getInstrOldOffsets().add(-1);
    return block;
  }

  private static Instruction createConstInstruction(BytecodeVersion version, int value) {
    if (value >= 0 && value <= 5) {
      return Instruction.create(CodeConstants.opc_iconst_0 + value, false, CodeConstants.GROUP_GENERAL, version, null, 1);
    }

    if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      return Instruction.create(CodeConstants.opc_bipush, false, CodeConstants.GROUP_GENERAL, version, new int[] {value}, 1);
    }

    return Instruction.create(CodeConstants.opc_sipush, false, CodeConstants.GROUP_GENERAL, version, new int[] {value}, 1);
  }

  private static int[] buildLookupSwitchOperands(int[] targetOffsets, int switchOffset) {
    int entryCount = targetOffsets.length - 1;
    int[] operands = new int[2 + entryCount * 2];
    operands[0] = targetOffsets[0] - switchOffset;
    operands[1] = entryCount;
    for (int i = 0; i < entryCount; i++) {
      operands[2 + i * 2] = i;
      operands[3 + i * 2] = targetOffsets[i + 1] - switchOffset;
    }
    return operands;
  }

  private static void redirectRegularEdge(BasicBlock pred, BasicBlock oldSucc, BasicBlock newSucc) {
    List<BasicBlock> succs = pred.getSuccs();
    for (int i = 0; i < succs.size(); i++) {
      if (succs.get(i) == oldSucc) {
        succs.set(i, newSucc);
        oldSucc.getPreds().remove(pred);
        newSucc.getPreds().add(pred);
      }
    }
  }
}

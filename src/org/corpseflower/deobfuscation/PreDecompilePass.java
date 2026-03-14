package org.corpseflower.deobfuscation;

@FunctionalInterface
public interface PreDecompilePass {
  void run(PreDecompileContext ctx) throws Exception;
}

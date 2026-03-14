package org.corpseflower.deobfuscation;

import org.corpseflower.CorpseflowerPreferences;
import org.jetbrains.java.decompiler.main.DecompilerContext;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CorpseflowerPreDecompilePass implements PreDecompilePass {
  @Override
  public void run(PreDecompileContext ctx) throws Exception {
    Path inputJar = ctx.getInputJar();
    if (inputJar == null || !Files.isRegularFile(inputJar)) {
      return;
    }

    Object disabled = DecompilerContext.getProperty(CorpseflowerPreferences.NO_DEOBFUSCATE);
    if ("1".equals(String.valueOf(disabled))) {
      ctx.setResult(DeobfuscationStage.passthrough(inputJar));
      return;
    }

    boolean verbose = "1".equals(String.valueOf(DecompilerContext.getProperty(CorpseflowerPreferences.VERBOSE)));
    DeobfuscationStage.AppliedResult applied = DeobfuscationStage.maybeRunInMemory(
      inputJar,
      ctx.getStructContext().getOwnClassBytesSnapshot(),
      true,
      verbose
    );
    ctx.setResult(applied.result());

    if (applied.result().deobfuscated()) {
      ctx.getStructContext().replaceOwnClasses(applied.classBytes());
    }
  }
}

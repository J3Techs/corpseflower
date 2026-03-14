package org.corpseflower.deobfuscation;

import org.jetbrains.java.decompiler.struct.StructContext;

import java.nio.file.Path;

public final class PreDecompileContext {
  private final StructContext structContext;
  private final Path inputJar;
  private DeobfuscationStage.Result result;

  public PreDecompileContext(StructContext structContext, Path inputJar) {
    this.structContext = structContext;
    this.inputJar = inputJar;
    this.result = DeobfuscationStage.passthrough(inputJar);
  }

  public StructContext getStructContext() {
    return structContext;
  }

  public Path getInputJar() {
    return inputJar;
  }

  public DeobfuscationStage.Result getResult() {
    return result;
  }

  public void setResult(DeobfuscationStage.Result result) {
    this.result = result;
  }
}

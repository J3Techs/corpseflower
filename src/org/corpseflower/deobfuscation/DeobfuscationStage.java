package org.corpseflower.deobfuscation;

import java.nio.file.Path;
import java.util.Map;

public final class DeobfuscationStage {
  private DeobfuscationStage() {
  }

  public static Result passthrough(Path inputJar) {
    return new Result(inputJar, false, 0, 0, 0, 0, 0, 0);
  }

  public static AppliedResult maybeRunInMemory(Path inputJar,
                                               Map<String, byte[]> classBytes,
                                               boolean enabled,
                                               boolean verbose) throws Exception {
    if (!enabled || !LegacyFdrsDeobfuscator.needsDeobfuscation(inputJar.toFile())) {
      return new AppliedResult(classBytes, passthrough(inputJar));
    }

    LegacyFdrsDeobfuscator.resetState();
    LegacyFdrsDeobfuscator.InMemoryResult deobfuscated = LegacyFdrsDeobfuscator.processClassesInMemory(classBytes, inputJar.toString(), true, true, verbose);
    return new AppliedResult(
      deobfuscated.classBytes(),
      new Result(
      inputJar,
      true,
      LegacyFdrsDeobfuscator.stringsDecrypted,
      LegacyFdrsDeobfuscator.fakeTryCatchRemoved,
      LegacyFdrsDeobfuscator.opaquePredicatesSimplified,
      LegacyFdrsDeobfuscator.deadInstructionsRemoved,
      LegacyFdrsDeobfuscator.exnVerifyFixed,
      LegacyFdrsDeobfuscator.zkmClassesRemoved
      )
    );
  }

  public record AppliedResult(Map<String, byte[]> classBytes, Result result) {
  }

  public record Result(
    Path inputJar,
    boolean deobfuscated,
    int stringsDecrypted,
    int fakeTryCatchRemoved,
    int opaquePredicatesSimplified,
    int deadInstructionsRemoved,
    int exceptionVerifyFixed,
    int zkmClassesRemoved
  ) {
  }
}

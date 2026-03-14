package org.corpseflower.deobfuscation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DeobfuscationStage {
  private DeobfuscationStage() {
  }

  public static Result maybeRun(Path inputJar, boolean enabled, boolean verbose) throws Exception {
    if (!enabled || !LegacyFdrsDeobfuscator.needsDeobfuscation(inputJar.toFile())) {
      return new Result(inputJar, null, false, 0, 0, 0, 0, 0, 0);
    }

    Path tempJar = Files.createTempFile("corpseflower-deobf-", ".jar");
    LegacyFdrsDeobfuscator.resetState();
    LegacyFdrsDeobfuscator.processJar(inputJar.toString(), tempJar.toString(), true, true, verbose);
    return new Result(
      tempJar,
      tempJar,
      true,
      LegacyFdrsDeobfuscator.stringsDecrypted,
      LegacyFdrsDeobfuscator.fakeTryCatchRemoved,
      LegacyFdrsDeobfuscator.opaquePredicatesSimplified,
      LegacyFdrsDeobfuscator.deadInstructionsRemoved,
      LegacyFdrsDeobfuscator.exnVerifyFixed,
      LegacyFdrsDeobfuscator.zkmClassesRemoved
    );
  }

  public record Result(
    Path workingJar,
    Path tempJar,
    boolean deobfuscated,
    int stringsDecrypted,
    int fakeTryCatchRemoved,
    int opaquePredicatesSimplified,
    int deadInstructionsRemoved,
    int exceptionVerifyFixed,
    int zkmClassesRemoved
  ) {
    public Path outputJar() {
      return workingJar;
    }

    public void cleanup() throws IOException {
      if (tempJar != null) {
        Files.deleteIfExists(tempJar);
      }
    }
  }
}

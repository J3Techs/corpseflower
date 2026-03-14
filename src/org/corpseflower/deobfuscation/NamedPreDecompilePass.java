package org.corpseflower.deobfuscation;

public final class NamedPreDecompilePass implements PreDecompilePass {
  private final String name;
  private final PreDecompilePass pass;

  public NamedPreDecompilePass(String name, PreDecompilePass pass) {
    this.name = name;
    this.pass = pass;
  }

  public static NamedPreDecompilePass of(String name, PreDecompilePass pass) {
    return new NamedPreDecompilePass(name, pass);
  }

  @Override
  public void run(PreDecompileContext ctx) throws Exception {
    this.pass.run(ctx);
  }

  public String getName() {
    return name;
  }
}

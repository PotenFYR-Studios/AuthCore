package net.ded3ec.entrypoint;

import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * Fabric entrypoint - listed in fabric.mod.json ("server" entrypoint).
 * Delegates straight to the loader-neutral {@link net.ded3ec.AuthCoreServer#start()}.
 */
public class FabricEntry implements DedicatedServerModInitializer {

  @Override
  public void onInitializeServer() {
    net.ded3ec.AuthCoreServer.start();
  }
}

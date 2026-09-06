package in.potenfyr.authcore.entrypoint;

import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * Fabric entrypoint - listed in fabric.mod.json ("server" entrypoint).
 * Delegates straight to the loader-neutral {@link in.potenfyr.authcore.AuthCoreServer#start()}.
 */
public class FabricEntry implements DedicatedServerModInitializer {

  @Override
  public void onInitializeServer() {
    in.potenfyr.authcore.AuthCoreServer.start();
  }
}

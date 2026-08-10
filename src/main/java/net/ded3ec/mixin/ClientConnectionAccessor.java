package net.ded3ec.mixin;

import java.net.SocketAddress;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the private connection address - used by proxy IP forwarding. */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(Connection.class)
public interface ClientConnectionAccessor {

  @Accessor("address")
  @Mutable
  void authCore$setAddress(SocketAddress address);
}

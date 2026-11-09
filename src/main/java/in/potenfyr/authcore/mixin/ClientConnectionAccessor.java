package in.potenfyr.authcore.mixin;

import java.net.SocketAddress;
/*? if fabric {*/
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
/*?}*/
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessors for {@code Connection}: the private address (proxy IP forwarding) and the netty
 * channel (raw plugin-message payloads on 1.20.2+, see Compat.sendCustomPayload). Accessor names
 * are remapped by the toolchain - {@code Connection.channel()} itself is gone on 26.x.
 */
/*? if fabric {*/
  @Environment(EnvType.SERVER)
  /*?}*/
@Mixin(Connection.class)
public interface ClientConnectionAccessor {

  @Accessor("address")
  @Mutable
  void authCore$setAddress(SocketAddress address);

  @Accessor("channel")
  io.netty.channel.Channel authCore$getChannel();
}

package net.ded3ec;

import java.nio.file.Path;

import net.ded3ec.models.Config;
import net.ded3ec.models.Messages;
import net.ded3ec.utils.Logger;
import net.ded3ec.utils.Registry;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class AuthCoreServer implements DedicatedServerModInitializer {
  public static final String MOD_ID = "authcore";
  public static final Logger LOGGER = new Logger(MOD_ID);
  public static final Path configPath =
      FabricLoader.getInstance().getConfigDir().resolve("authcore");

  public static Config config;
  public static Messages messages;

  @Override
  public void onInitializeServer() {
    Registry.register();

    LOGGER.info(null,"-------------------------------------------------");
    LOGGER.info(
        null,
            """
                    
                    $$$$$$\\              $$\\     $$\\        $$$$$$\\                               \s
                    $$  __$$\\             $$ |    $$ |      $$  __$$\\                              \s
                    $$ /  $$ |$$\\   $$\\ $$$$$$\\   $$$$$$$\\  $$ /  \\__| $$$$$$\\   $$$$$$\\   $$$$$$\\ \s
                    $$$$$$$$ |$$ |  $$ |\\_$$  _|  $$  __$$\\ $$ |      $$  __$$\\ $$  __$$\\ $$  __$$\\\s
                    $$  __$$ |$$ |  $$ |  $$ |    $$ |  $$ |$$ |      $$ /  $$ |$$ |  \\__|$$$$$$$$ |
                    $$ |  $$ |$$ |  $$ |  $$ |$$\\ $$ |  $$ |$$ |  $$\\ $$ |  $$ |$$ |      $$   ____|
                    $$ |  $$ |\\$$$$$$  |  \\$$$$  |$$ |  $$ |\\$$$$$$  |\\$$$$$$  |$$ |      \\$$$$$$$\\\s
                    \\__|  \\__| \\______/    \\____/ \\__|  \\__| \\______/  \\______/ \\__|       \\_______|
                                                                                                   \s
                                                                                                   \s
                                                                                                   \s""");
    LOGGER.info(null,"");
    LOGGER.info(null," Version      : {}", "1.0.0-alpha.2");
    LOGGER.info(null," Minecraft    : 1.21.11");
    LOGGER.info(null," Server Mode : {}", config.session.serverMode);
    LOGGER.info(null,"-------------------------------------------------");
  }
}

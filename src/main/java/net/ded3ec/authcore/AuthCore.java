package net.ded3ec.authcore;

import java.nio.file.Path;

import net.ded3ec.authcore.models.Config;
import net.ded3ec.authcore.models.Messages;
import net.ded3ec.authcore.utils.Hooks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthCore implements ModInitializer {
    public static final String MOD_ID = "authcore";
    public static final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("authcore");
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Config config;
    public static Messages messages;

    @Override
    public void onInitialize() {

        Hooks.register();
    }
}

package com.example.mc308593fix;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mc308593Mod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mc308593fix");

    @Override
    public void onInitializeClient() {
        LOGGER.info("MC-308593 workaround loaded");
    }
}

package com.villagertradepredicator;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillagerTradePredicator implements ClientModInitializer {
    public static final String MOD_ID = "villagertradepredicator";
    public static final Logger LOGGER = LoggerFactory.getLogger("VillagerTradePredicator");

    @Override
    public void onInitializeClient() {
        LOGGER.info("VillagerTradePredicator initialized");
    }
}

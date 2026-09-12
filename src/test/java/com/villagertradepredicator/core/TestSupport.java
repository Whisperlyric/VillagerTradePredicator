package com.villagertradepredicator.core;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Bootstraps the vanilla static registries so tests can resolve items. */
public final class TestSupport {
    private static boolean bootstrapped;

    public static synchronized void bootstrap() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private TestSupport() {}
}

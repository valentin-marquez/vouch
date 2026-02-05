package com.nozz.vouch.fabric;

import com.nozz.vouch.VouchMod;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric entrypoint for Vouch (server-side only)
 */
public final class VouchFabric implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        // Initialize the common mod
        VouchMod.init(FabricLoader.getInstance().getConfigDir());
    }
}

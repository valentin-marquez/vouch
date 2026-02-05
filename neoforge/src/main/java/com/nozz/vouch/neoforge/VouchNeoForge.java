package com.nozz.vouch.neoforge;

import com.nozz.vouch.VouchMod;
import com.nozz.vouch.util.PermissionHelper;
import com.nozz.vouch.util.neoforge.PermissionHelperImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * NeoForge entrypoint for Vouch (server-side only)
 */
@Mod(VouchMod.MOD_ID)
public final class VouchNeoForge {

    public VouchNeoForge(IEventBus modEventBus) {
        // Register server setup event
        modEventBus.addListener(this::onServerSetup);

        // Register shutdown hook and permission nodes on game event bus
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterPermissionNodes);
    }

    private void onServerSetup(FMLDedicatedServerSetupEvent event) {
        // Initialize the common mod on the server thread
        event.enqueueWork(() -> VouchMod.init(FMLPaths.CONFIGDIR.get()));
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Cleanup resources
        VouchMod.getInstance().shutdown();
    }

    /**
     * Register all Vouch permission nodes with NeoForge's PermissionAPI.
     */
    private void onRegisterPermissionNodes(PermissionGatherEvent.Nodes event) {
        // User commands - default to all players (true)
        registerNode(event, PermissionHelper.Nodes.REGISTER, true);
        registerNode(event, PermissionHelper.Nodes.LOGIN, true);
        registerNode(event, PermissionHelper.Nodes.LOGOUT, true);
        
        // 2FA commands - default to all authenticated players (true)
        registerNode(event, PermissionHelper.Nodes.TWO_FA_SETUP, true);
        registerNode(event, PermissionHelper.Nodes.TWO_FA_VERIFY, true);
        registerNode(event, PermissionHelper.Nodes.TWO_FA_DISABLE, true);
        registerNode(event, PermissionHelper.Nodes.TWO_FA_STATUS, true);
        
        // Admin commands - default to OPs only (false for non-OPs)
        registerNodeOpOnly(event, PermissionHelper.Nodes.ADMIN_RELOAD);
        registerNodeOpOnly(event, PermissionHelper.Nodes.ADMIN_UNREGISTER);
        registerNodeOpOnly(event, PermissionHelper.Nodes.ADMIN_EXPORT_LANG);
        
        // Special permissions - default to OPs only
        registerNodeOpOnly(event, PermissionHelper.Nodes.BYPASS_AUTH);
    }

    /**
     * Register a permission node with a default value.
     */
    private void registerNode(PermissionGatherEvent.Nodes event, String nodeName, boolean defaultValue) {
        PermissionNode<Boolean> node = new PermissionNode<>(
                VouchMod.MOD_ID,
                nodeName.replace("vouch.", ""),
                PermissionTypes.BOOLEAN,
                (player, uuid, context) -> defaultValue
        );
        event.addNodes(node);
        PermissionHelperImpl.registerNode(node);
    }

    /**
     * Register a permission node that defaults to true only for OPs.
     */
    private void registerNodeOpOnly(PermissionGatherEvent.Nodes event, String nodeName) {
        PermissionNode<Boolean> node = new PermissionNode<>(
                VouchMod.MOD_ID,
                nodeName.replace("vouch.", ""),
                PermissionTypes.BOOLEAN,
                (player, uuid, context) -> {
                    // Default resolver: only allow for OPs
                    if (player != null && player.getServer() != null) {
                        return player.getServer().getPlayerManager().isOperator(player.getGameProfile());
                    }
                    return false;
                }
        );
        event.addNodes(node);
        PermissionHelperImpl.registerNode(node);
    }
}

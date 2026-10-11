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
        // Walk the single node table instead of hand-listing them here. Two
        // nodes used to be missing from this method, so LuckPerms grants for
        // them were honoured on Fabric and silently ignored on NeoForge.
        PermissionHelper.Nodes.all().forEach(node -> registerNode(event, node));
    }

    /**
     * Register one permission node, falling back to its declared OP level when
     * no permission provider is installed.
     *
     * A node declared EXPLICIT_GRANT_ONLY sits above every vanilla OP level, so
     * this resolver can never satisfy it — which is the point for
     * {@code vouch.bypass.auth}: being an operator must not skip authentication.
     */
    private void registerNode(PermissionGatherEvent.Nodes event, PermissionHelper.Node vouchNode) {
        PermissionNode<Boolean> node = new PermissionNode<>(
                VouchMod.MOD_ID,
                vouchNode.id().replace("vouch.", ""),
                PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null
                        && PermissionHelper.permissionSetHasLevel(player.permissions(), vouchNode.defaultOpLevel())
        );
        event.addNodes(node);
        PermissionHelperImpl.registerNode(node);
    }
}

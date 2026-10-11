package com.nozz.vouch.util.neoforge;

import com.nozz.vouch.util.PermissionHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge implementation of PermissionHelper.
 * 
 * Uses NeoForge's built-in PermissionAPI which is compatible with:
 * - LuckPerms (NeoForge version)
 * - FTB Ranks (NeoForge version)
 * - Other NeoForge permission providers
 * 
 * Falls back to vanilla OP levels when no permission mod is installed.
 */
public final class PermissionHelperImpl {

    // Cache of permission nodes for quick lookup
    private static final Map<String, PermissionNode<Boolean>> PERMISSION_NODES = new ConcurrentHashMap<>();

    private PermissionHelperImpl() {
    }

    /**
     * Register a permission node for later use.
     * Called during mod initialization via RegisterPermissionNodesEvent.
     */
    public static void registerNode(PermissionNode<Boolean> node) {
        PERMISSION_NODES.put(node.getNodeName(), node);
    }

    /**
     * Check permission using NeoForge PermissionAPI.
     * Falls back to OP level check if permission node is not registered or no permission mod is present.
     * 
     * @param source         The command source to check
     * @param permission     The permission node to check
     * @param defaultOpLevel The OP level to use as fallback
     * @return true if the source has the permission
     */
    public static boolean hasPermission(CommandSourceStack source, String permission, int defaultOpLevel) {
        // For non-player sources (console, command blocks), use OP level check
        if (!source.isPlayer()) {
            return PermissionHelper.permissionSetHasLevel(source.permissions(), defaultOpLevel);
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return PermissionHelper.permissionSetHasLevel(source.permissions(), defaultOpLevel);
        }

        // Try to get the registered permission node
        PermissionNode<Boolean> node = PERMISSION_NODES.get(permission);
        if (node != null) {
            try {
                Boolean result = PermissionAPI.getPermission(player, node);
                return result;
            } catch (Exception e) {
                // Fallback to OP level on any error
                return PermissionHelper.permissionSetHasLevel(source.permissions(), defaultOpLevel);
            }
        }

        // Node not registered, fall back to OP level
        return PermissionHelper.permissionSetHasLevel(source.permissions(), defaultOpLevel);
    }
}

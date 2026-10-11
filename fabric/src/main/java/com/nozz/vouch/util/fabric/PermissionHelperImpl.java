package com.nozz.vouch.util.fabric;

import com.nozz.vouch.util.PermissionHelper;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;

/**
 * Fabric implementation of PermissionHelper.
 * 
 * Uses Fabric Permissions API which is compatible with:
 * - LuckPerms
 * - FTB Ranks
 * - fabric-permissions-api implementations
 * 
 * Falls back to vanilla OP levels when no permission mod is installed.
 */
public final class PermissionHelperImpl {

    private PermissionHelperImpl() {
    }

    /**
     * Check permission using Fabric Permissions API.
     * Falls back to OP level check if no permission mod is present.
     * 
     * @param source         The command source to check
     * @param permission     The permission node to check
     * @param defaultOpLevel The OP level to use as fallback
     * @return true if the source has the permission
     */
    public static boolean hasPermission(CommandSourceStack source, String permission, int defaultOpLevel) {
        // fabric-permissions-api clamps the level into 0..4 before comparing, so an
        // explicit-grant-only node (Integer.MAX_VALUE) would become "operator level 4" and be
        // satisfied by every operator. Such nodes must fall back to "denied" instead.
        if (PermissionHelper.unreachableByOpLevel(defaultOpLevel)) {
            return Permissions.check(source, permission, false);
        }
        return Permissions.check(source, permission, defaultOpLevel);
    }
}

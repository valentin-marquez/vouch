package com.nozz.vouch.util;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Platform-agnostic permission checking utility.
 * 
 * Fabric: Uses Fabric Permissions API (compatible with LuckPerms, FTB Ranks, etc.)
 * NeoForge: Uses NeoForge PermissionAPI
 * 
 * Falls back to vanilla OP levels when no permission mod is installed.
 */
public final class PermissionHelper {

    private PermissionHelper() {
    }

    /**
     * Permission nodes for Vouch commands.
     */
    public static final class Nodes {
        // User commands (default: all players)
        public static final String REGISTER = "vouch.command.register";
        public static final String LOGIN = "vouch.command.login";
        public static final String LOGOUT = "vouch.command.logout";
        
        // 2FA commands (default: all authenticated players)
        public static final String TWO_FA_SETUP = "vouch.command.2fa.setup";
        public static final String TWO_FA_VERIFY = "vouch.command.2fa.verify";
        public static final String TWO_FA_DISABLE = "vouch.command.2fa.disable";
        public static final String TWO_FA_STATUS = "vouch.command.2fa.status";
        
        // Admin commands (default: OP level 4)
        public static final String ADMIN_RELOAD = "vouch.admin.reload";
        public static final String ADMIN_UNREGISTER = "vouch.admin.unregister";
        public static final String ADMIN_EXPORT_LANG = "vouch.admin.export-lang";
        
        // Special permissions
        public static final String BYPASS_AUTH = "vouch.bypass.auth";
        
        private Nodes() {
        }
    }

    /**
     * Default OP levels for permission nodes.
     */
    public static final class Defaults {
        public static final int USER_COMMAND = 0;      // All players
        public static final int ADMIN_COMMAND = 4;     // Server operators only
        public static final int BYPASS = 4;            // Server operators only
        
        private Defaults() {
        }
    }

    /**
     * Check if a command source has a specific permission.
     * 
     * @param source         The command source to check
     * @param permission     The permission node to check
     * @param defaultOpLevel The OP level to use as fallback when no permission mod is installed
     * @return true if the source has the permission
     */
    @ExpectPlatform
    public static boolean hasPermission(ServerCommandSource source, String permission, int defaultOpLevel) {
        throw new AssertionError("Platform implementation not loaded");
    }

    /**
     * Check if a command source has admin permission (OP level 4 default).
     */
    public static boolean hasAdminPermission(ServerCommandSource source, String permission) {
        return hasPermission(source, permission, Defaults.ADMIN_COMMAND);
    }

    /**
     * Check if a command source has user-level permission (available to all by default).
     */
    public static boolean hasUserPermission(ServerCommandSource source, String permission) {
        return hasPermission(source, permission, Defaults.USER_COMMAND);
    }

    /**
     * Check if a command source can bypass authentication.
     */
    public static boolean canBypassAuth(ServerCommandSource source) {
        return hasPermission(source, Nodes.BYPASS_AUTH, Defaults.BYPASS);
    }
}

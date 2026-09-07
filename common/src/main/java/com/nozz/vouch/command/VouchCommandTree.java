package com.nozz.vouch.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.nozz.vouch.auth.AuthMode;

import java.util.function.Predicate;

/**
 * The one description of Vouch's command surface.
 *
 * <h2>Why it is built here and not in the command classes</h2>
 * {@code /vouch} and {@code /auth} used to be two hand-typed twenty-four line
 * trees, and they had already drifted: a subcommand added to one was missing from
 * the other. The tree is now assembled once and registered twice, with
 * {@code /auth} as a Brigadier {@code redirect} rather than a copy — so it cannot
 * drift, and an admin script that already says {@code /auth} keeps working.
 *
 * <h2>Why it is generic in the source type</h2>
 * Nothing here touches a Minecraft class. Every builder is plain Brigadier
 * ({@code CommandManager.literal} is exactly {@code LiteralArgumentBuilder.literal}),
 * so the whole shape — which paths parse, what an alias resolves to, what a node's
 * predicate says — is testable with a {@code CommandDispatcher} and a value
 * standing in for a player. The server supplies the Minecraft-flavoured
 * {@link Hooks}; the tests supply their own.
 *
 * <h2>The surface</h2>
 * <pre>
 * /login &lt;password&gt;                 + alias /l
 * /register &lt;password&gt; &lt;password&gt;   + alias /reg
 * /logout
 *
 * /vouch                            + alias /auth (redirect)
 *   2fa                             + alias /2fa (redirect)
 *     setup | verify &lt;code&gt; | disable &lt;code&gt; | status
 *   password &lt;old&gt; &lt;new&gt;
 *   premium                         + legacy alias markAsOnline
 *   admin
 *     reload
 *     export-lang
 *     user &lt;player&gt;    info | unregister | resetpw &lt;new&gt;
 *     premium &lt;player&gt; force-offline | force-online
 *     unregister &lt;player&gt;                     (legacy)
 *     force-offline &lt;player&gt; | force-online &lt;player&gt;  (legacy)
 * </pre>
 *
 * <p>The entry-flow verbs stay at the root because they are universal convention:
 * they are on server signage and in tutorials, and moving them would break every
 * one of those. Everything else consolidates under {@code /vouch}.
 *
 * <h2>Visibility</h2>
 * Every node carries a {@code requires} predicate derived from {@link CommandGate}.
 * Brigadier sends a client only the nodes whose predicate accepts it and evaluates
 * the same predicate again before executing, so one rule both hides a command and
 * refuses it. Branch literals ({@code 2fa}, {@code admin}, {@code vouch} itself)
 * require <em>any</em> of their children, so an empty branch disappears rather than
 * showing as a dead end.
 */
public final class VouchCommandTree {

    // Argument names are part of the contract with the executors, which read them
    // back by name out of the CommandContext.
    public static final String ARG_PASSWORD = "password";
    public static final String ARG_CONFIRM_PASSWORD = "confirmPassword";
    public static final String ARG_OLD_PASSWORD = "oldPassword";
    public static final String ARG_NEW_PASSWORD = "newPassword";
    public static final String ARG_CODE = "code";
    public static final String ARG_PLAYER = "player";

    /** Every executable endpoint. One leaf may be reachable by several paths. */
    public enum Leaf {
        LOGIN,
        LOGIN_2FA_ONLY,
        REGISTER,
        REGISTER_2FA_ONLY,
        LOGOUT,

        TWO_FACTOR_SETUP,
        TWO_FACTOR_VERIFY,
        TWO_FACTOR_DISABLE,
        TWO_FACTOR_STATUS,

        PASSWORD,
        PREMIUM,

        ADMIN_RELOAD,
        ADMIN_EXPORT_LANG,
        ADMIN_USER_INFO,
        ADMIN_USER_UNREGISTER,
        ADMIN_USER_RESETPW,
        ADMIN_PREMIUM_FORCE_OFFLINE,
        ADMIN_PREMIUM_FORCE_ONLINE
    }

    /**
     * Everything the tree needs from its host.
     *
     * <p>Implementations must return the <em>same</em> {@link Command} instance for a
     * given leaf every time, because a leaf reachable by several paths (a legacy alias,
     * or the {@code /auth} redirect) has to be one behaviour, not several.
     */
    public interface Hooks<S> {
        /** The predicate deciding whether this command exists for a given caller. */
        Predicate<S> requirement(VouchCommand command);

        /** The executor for a leaf. Must be stable per leaf. */
        Command<S> action(Leaf leaf);

        /** Completion for any argument naming a registered player. */
        SuggestionProvider<S> registeredPlayerNames();
    }

    private VouchCommandTree() {
    }

    /**
     * Build the surface and register it, aliases and redirects included.
     *
     * @param mode decides only the shape of the two entry commands: 2FA-only servers
     *             take a code where a password would go, and register no password.
     */
    public static <S> void register(CommandDispatcher<S> dispatcher, AuthMode mode, Hooks<S> hooks) {
        LiteralCommandNode<S> vouch = dispatcher.register(vouchBranch("vouch", hooks));
        // /auth is a redirect, not a second tree. Deleting it would break admin
        // scripts that already exist; copying it is what let the two drift apart.
        dispatcher.register(LiteralArgumentBuilder.<S>literal("auth")
                .requires(vouch.getRequirement())
                .redirect(vouch));

        // /2fa is no longer a root of its own, but the name is free to keep.
        CommandNode<S> twoFactor = vouch.getChild("2fa");
        dispatcher.register(LiteralArgumentBuilder.<S>literal("2fa")
                .requires(twoFactor.getRequirement())
                .redirect(twoFactor));

        LiteralCommandNode<S> login = dispatcher.register(loginBranch(mode, hooks));
        dispatcher.register(LiteralArgumentBuilder.<S>literal("l")
                .requires(login.getRequirement())
                .redirect(login));

        LiteralCommandNode<S> register = dispatcher.register(registerBranch(mode, hooks));
        dispatcher.register(LiteralArgumentBuilder.<S>literal("reg")
                .requires(register.getRequirement())
                .redirect(register));

        dispatcher.register(LiteralArgumentBuilder.<S>literal("logout")
                .requires(hooks.requirement(VouchCommand.LOGOUT))
                .executes(hooks.action(Leaf.LOGOUT)));
    }

    // ---- entry flow ----------------------------------------------------------

    private static <S> LiteralArgumentBuilder<S> loginBranch(AuthMode mode, Hooks<S> hooks) {
        LiteralArgumentBuilder<S> login = LiteralArgumentBuilder.<S>literal("login")
                .requires(hooks.requirement(VouchCommand.LOGIN));

        if (mode.is2FARequired()) {
            return login.then(RequiredArgumentBuilder.<S, String>argument(ARG_CODE, StringArgumentType.greedyString())
                    .executes(hooks.action(Leaf.LOGIN_2FA_ONLY)));
        }
        return login.then(RequiredArgumentBuilder.<S, String>argument(ARG_PASSWORD, StringArgumentType.string())
                .executes(hooks.action(Leaf.LOGIN)));
    }

    private static <S> LiteralArgumentBuilder<S> registerBranch(AuthMode mode, Hooks<S> hooks) {
        LiteralArgumentBuilder<S> register = LiteralArgumentBuilder.<S>literal("register")
                .requires(hooks.requirement(VouchCommand.REGISTER));

        if (mode.is2FARequired()) {
            // No password exists in this mode; /register goes straight to the QR code.
            return register.executes(hooks.action(Leaf.REGISTER_2FA_ONLY));
        }
        return register.then(RequiredArgumentBuilder.<S, String>argument(ARG_PASSWORD, StringArgumentType.string())
                .then(RequiredArgumentBuilder.<S, String>argument(ARG_CONFIRM_PASSWORD, StringArgumentType.string())
                        .executes(hooks.action(Leaf.REGISTER))));
    }

    // ---- /vouch --------------------------------------------------------------

    private static <S> LiteralArgumentBuilder<S> vouchBranch(String name, Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal(name)
                .requires(anyOf(hooks,
                        VouchCommand.TWO_FACTOR_SETUP, VouchCommand.TWO_FACTOR_VERIFY,
                        VouchCommand.TWO_FACTOR_DISABLE, VouchCommand.TWO_FACTOR_STATUS,
                        VouchCommand.PASSWORD, VouchCommand.PREMIUM,
                        VouchCommand.ADMIN_RELOAD, VouchCommand.ADMIN_USER,
                        VouchCommand.ADMIN_PREMIUM, VouchCommand.ADMIN_EXPORT_LANG))
                .then(twoFactorBranch(hooks))
                .then(passwordBranch(hooks))
                .then(premiumBranch("premium", hooks))
                // The old name, kept because keeping it costs one line.
                .then(premiumBranch("markAsOnline", hooks))
                .then(adminBranch(hooks));
    }

    private static <S> LiteralArgumentBuilder<S> twoFactorBranch(Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal("2fa")
                .requires(anyOf(hooks,
                        VouchCommand.TWO_FACTOR_SETUP, VouchCommand.TWO_FACTOR_VERIFY,
                        VouchCommand.TWO_FACTOR_DISABLE, VouchCommand.TWO_FACTOR_STATUS))
                .then(LiteralArgumentBuilder.<S>literal("setup")
                        .requires(hooks.requirement(VouchCommand.TWO_FACTOR_SETUP))
                        .executes(hooks.action(Leaf.TWO_FACTOR_SETUP)))
                .then(LiteralArgumentBuilder.<S>literal("verify")
                        .requires(hooks.requirement(VouchCommand.TWO_FACTOR_VERIFY))
                        .then(RequiredArgumentBuilder.<S, String>argument(ARG_CODE, StringArgumentType.greedyString())
                                .executes(hooks.action(Leaf.TWO_FACTOR_VERIFY))))
                .then(LiteralArgumentBuilder.<S>literal("disable")
                        .requires(hooks.requirement(VouchCommand.TWO_FACTOR_DISABLE))
                        .then(RequiredArgumentBuilder.<S, String>argument(ARG_CODE, StringArgumentType.greedyString())
                                .executes(hooks.action(Leaf.TWO_FACTOR_DISABLE))))
                .then(LiteralArgumentBuilder.<S>literal("status")
                        .requires(hooks.requirement(VouchCommand.TWO_FACTOR_STATUS))
                        .executes(hooks.action(Leaf.TWO_FACTOR_STATUS)));
    }

    private static <S> LiteralArgumentBuilder<S> passwordBranch(Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal("password")
                .requires(hooks.requirement(VouchCommand.PASSWORD))
                .then(RequiredArgumentBuilder.<S, String>argument(ARG_OLD_PASSWORD, StringArgumentType.string())
                        .then(RequiredArgumentBuilder.<S, String>argument(ARG_NEW_PASSWORD, StringArgumentType.string())
                                .executes(hooks.action(Leaf.PASSWORD))));
    }

    private static <S> LiteralArgumentBuilder<S> premiumBranch(String name, Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal(name)
                .requires(hooks.requirement(VouchCommand.PREMIUM))
                .executes(hooks.action(Leaf.PREMIUM));
    }

    private static <S> LiteralArgumentBuilder<S> adminBranch(Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal("admin")
                .requires(anyOf(hooks,
                        VouchCommand.ADMIN_RELOAD, VouchCommand.ADMIN_USER,
                        VouchCommand.ADMIN_PREMIUM, VouchCommand.ADMIN_EXPORT_LANG))
                .then(LiteralArgumentBuilder.<S>literal("reload")
                        .requires(hooks.requirement(VouchCommand.ADMIN_RELOAD))
                        .executes(hooks.action(Leaf.ADMIN_RELOAD)))
                .then(LiteralArgumentBuilder.<S>literal("export-lang")
                        .requires(hooks.requirement(VouchCommand.ADMIN_EXPORT_LANG))
                        .executes(hooks.action(Leaf.ADMIN_EXPORT_LANG)))
                .then(LiteralArgumentBuilder.<S>literal("user")
                        .requires(hooks.requirement(VouchCommand.ADMIN_USER))
                        .then(player(hooks)
                                .then(LiteralArgumentBuilder.<S>literal("info")
                                        .executes(hooks.action(Leaf.ADMIN_USER_INFO)))
                                .then(LiteralArgumentBuilder.<S>literal("unregister")
                                        .executes(hooks.action(Leaf.ADMIN_USER_UNREGISTER)))
                                .then(LiteralArgumentBuilder.<S>literal("resetpw")
                                        .then(RequiredArgumentBuilder.<S, String>argument(
                                                        ARG_NEW_PASSWORD, StringArgumentType.string())
                                                .executes(hooks.action(Leaf.ADMIN_USER_RESETPW))))))
                .then(LiteralArgumentBuilder.<S>literal("premium")
                        .requires(hooks.requirement(VouchCommand.ADMIN_PREMIUM))
                        .then(player(hooks)
                                .then(LiteralArgumentBuilder.<S>literal("force-offline")
                                        .executes(hooks.action(Leaf.ADMIN_PREMIUM_FORCE_OFFLINE)))
                                .then(LiteralArgumentBuilder.<S>literal("force-online")
                                        .executes(hooks.action(Leaf.ADMIN_PREMIUM_FORCE_ONLINE)))))
                // The pre-restructure flat verbs. Same executors, so they cannot drift.
                .then(legacyPlayerVerb("unregister", VouchCommand.ADMIN_USER,
                        Leaf.ADMIN_USER_UNREGISTER, hooks))
                .then(legacyPlayerVerb("force-offline", VouchCommand.ADMIN_PREMIUM,
                        Leaf.ADMIN_PREMIUM_FORCE_OFFLINE, hooks))
                .then(legacyPlayerVerb("force-online", VouchCommand.ADMIN_PREMIUM,
                        Leaf.ADMIN_PREMIUM_FORCE_ONLINE, hooks));
    }

    private static <S> LiteralArgumentBuilder<S> legacyPlayerVerb(
            String name, VouchCommand gate, Leaf leaf, Hooks<S> hooks) {
        return LiteralArgumentBuilder.<S>literal(name)
                .requires(hooks.requirement(gate))
                .then(player(hooks).executes(hooks.action(leaf)));
    }

    /** A player-name argument that completes against the registered-name cache. */
    private static <S> RequiredArgumentBuilder<S, String> player(Hooks<S> hooks) {
        return RequiredArgumentBuilder.<S, String>argument(ARG_PLAYER, StringArgumentType.word())
                .suggests(hooks.registeredPlayerNames());
    }

    /**
     * A branch literal exists when at least one thing under it does, so a player is
     * never shown a {@code /vouch admin} that leads nowhere.
     */
    private static <S> Predicate<S> anyOf(Hooks<S> hooks, VouchCommand... commands) {
        Predicate<S> combined = source -> false;
        for (VouchCommand command : commands) {
            combined = combined.or(hooks.requirement(command));
        }
        return combined;
    }
}

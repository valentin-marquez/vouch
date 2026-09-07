package com.nozz.vouch.util;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which commands an unauthenticated ("jailed") player is allowed to run.
 *
 * <p>Since Minecraft 1.19 commands no longer travel on the chat packet, so blocking
 * chat is not enough: {@code CommandExecutionC2SPacket} and
 * {@code ChatCommandSignedC2SPacket} are separate entry points. The mixins that gate
 * those packets delegate every decision to this class so the decision itself stays
 * unit-testable — a mixin is not.
 *
 * <h2>Matching semantics</h2>
 * Only the <em>root literal</em> of the command matters — the first whitespace
 * delimited token, after an optional single leading {@code /} is removed and leading
 * whitespace is trimmed. It is compared against the allowlist:
 *
 * <ul>
 *   <li><b>Exact match, case-insensitive</b> ({@link Locale#ROOT}). {@code /LOGIN pw}
 *       is allowed; {@code /loginx} is not. Case-insensitivity is safe here because an
 *       allowlist can only ever admit a command that is already named in it.</li>
 *   <li><b>Namespaces are never stripped.</b> {@code minecraft:me} is a root literal in
 *       its own right, so it does not match {@code me}, and — more importantly —
 *       {@code evilmod:login} does not match {@code login}. A matcher that reduced
 *       {@code ns:name} to {@code name} would hand any mod a way to smuggle a command
 *       through the jail under an allowlisted name.</li>
 *   <li><b>Deny by default.</b> Null, empty, blank, {@code "/"}, {@code "/ login"} and
 *       {@code "//login"} all produce a root literal that is not on the list.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method here is pure (bar the {@link Corrector} seam) and safe to call from the
 * Netty I/O thread, which is where the packet handlers' {@code HEAD} actually runs —
 * vanilla only hops to the main thread later, inside {@code validateMessage}.
 */
public final class PreAuthCommandGate {

    /**
     * Bootstrap allowlist.
     *
     * <p>Hardcoded on purpose: the {@code auth.allowed_commands} config key does not
     * exist yet. When it lands, override {@link #allowedCommands()} to read it (running
     * the configured values through {@link #normalizeAllowlist(Collection)} first) and
     * nothing else in this class or in the mixins has to change.
     */
    public static final Set<String> DEFAULT_ALLOWED_COMMANDS =
            Set.of("login", "l", "register", "reg");

    private static final Corrector NO_CORRECTION = (rootLiteral, allowed) -> Optional.empty();

    private static volatile Corrector corrector = NO_CORRECTION;

    private PreAuthCommandGate() {
    }

    /**
     * Extension point for a later typo-correction pass ("did you mean /login?").
     *
     * <p>Consulted only for commands that were <em>already blocked</em>; a corrector can
     * never turn a block into an allow, so a buggy or hostile implementation cannot open
     * the jail.
     */
    @FunctionalInterface
    public interface Corrector {
        Optional<String> suggest(String rootLiteral, Set<String> allowed);
    }

    /**
     * The outcome of gating one command.
     *
     * @param allowed     whether the packet handler should proceed
     * @param rootLiteral the normalized root literal, for logging and feedback
     * @param suggestion  an optional "did you mean …" replacement, only ever set when
     *                    {@code allowed} is {@code false}
     */
    public record Decision(boolean allowed, String rootLiteral, Optional<String> suggestion) {
    }

    /**
     * Install the typo corrector. Passing {@code null} restores the no-op corrector.
     */
    public static void setCorrector(Corrector newCorrector) {
        corrector = newCorrector == null ? NO_CORRECTION : newCorrector;
    }

    /**
     * The allowlist currently in force.
     *
     * <p>Single seam for the future {@code auth.allowed_commands} config key.
     */
    public static Set<String> allowedCommands() {
        return DEFAULT_ALLOWED_COMMANDS;
    }

    /**
     * Gate a raw command string against {@link #allowedCommands()}.
     *
     * @param rawCommand the command as received from the client, with or without a
     *                   leading slash (vanilla strips it before it reaches the packet)
     */
    public static Decision evaluate(String rawCommand) {
        return evaluate(rawCommand, allowedCommands());
    }

    /**
     * Gate a raw command string against an explicit allowlist.
     */
    public static Decision evaluate(String rawCommand, Set<String> allowed) {
        String root = rootLiteral(rawCommand);
        boolean permitted = matches(root, allowed);
        if (permitted) {
            return new Decision(true, root, Optional.empty());
        }
        return new Decision(false, root, suggestFor(root, allowed));
    }

    /**
     * @return {@code true} when the command's root literal is on the allowlist
     */
    public static boolean isAllowed(String rawCommand, Collection<String> allowed) {
        return matches(rootLiteral(rawCommand), allowed);
    }

    /**
     * Extract the normalized root literal of a command.
     *
     * <p>Strips leading whitespace, then at most one leading {@code /}, then keeps
     * everything up to the first whitespace character, lowercased with
     * {@link Locale#ROOT}. Returns {@code ""} when there is no usable literal.
     */
    public static String rootLiteral(String rawCommand) {
        if (rawCommand == null) {
            return "";
        }

        int index = 0;
        int length = rawCommand.length();
        while (index < length && Character.isWhitespace(rawCommand.charAt(index))) {
            index++;
        }
        // Exactly one slash: "//login" keeps a literal "/login", which is not a command.
        if (index < length && rawCommand.charAt(index) == '/') {
            index++;
        }

        int start = index;
        while (index < length && !Character.isWhitespace(rawCommand.charAt(index))) {
            index++;
        }
        if (start == index) {
            return "";
        }
        return rawCommand.substring(start, index).toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize allowlist entries so hand-written config values behave predictably:
     * {@code "/Login"}, {@code "  REG  "} and {@code "login <password>"} all collapse to
     * their root literal. Unusable entries are dropped.
     */
    public static Set<String> normalizeAllowlist(Collection<String> entries) {
        if (entries == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String entry : entries) {
            String root = rootLiteral(entry);
            if (!root.isEmpty()) {
                normalized.add(root);
            }
        }
        return Set.copyOf(normalized);
    }

    private static boolean matches(String rootLiteral, Collection<String> allowed) {
        if (rootLiteral.isEmpty() || allowed == null || allowed.isEmpty()) {
            return false;
        }
        for (String entry : allowed) {
            if (entry != null && rootLiteral.equals(entry.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> suggestFor(String rootLiteral, Set<String> allowed) {
        if (rootLiteral.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<String> suggestion = corrector.suggest(rootLiteral, allowed == null ? Set.of() : allowed);
            return suggestion == null ? Optional.empty() : suggestion;
        } catch (RuntimeException e) {
            // A broken corrector must never affect whether the command is blocked.
            return Optional.empty();
        }
    }
}

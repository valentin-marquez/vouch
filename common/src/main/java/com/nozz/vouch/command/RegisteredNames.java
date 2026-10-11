package com.nozz.vouch.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The usernames this server has accounts for, kept in memory so tab-completion
 * can answer instantly.
 *
 * <h2>Why this exists</h2>
 * {@code /vouch admin user <TAB>} has to offer registered players. Brigadier asks
 * for suggestions on every keystroke, so the answer cannot come from the database;
 * it has to be sitting in memory already.
 *
 * <h2>How it stays true</h2>
 * Every write happens at a moment where the database has just answered the same
 * question, and nowhere else:
 *
 * <ul>
 *   <li>seeded once at server start from {@code listRegisteredUsernames()};</li>
 *   <li>{@link #setPresent(String, boolean)} on every {@code isRegistered} lookup —
 *       notably when a player joins, which re-establishes the truth for exactly the
 *       player about to need it;</li>
 *   <li>{@link #add(String)} after a successful registration (password, 2FA-only, or
 *       premium auto-registration) and after an admin password reset;</li>
 *   <li>{@link #remove(String)} after a successful unregistration.</li>
 * </ul>
 *
 * <p>It is a suggestion cache and a visibility hint, never an authority: every
 * executor still resolves the player against the database. A second server sharing
 * one database can therefore leave an entry stale here without any security
 * consequence — the worst case is a name offered that no longer resolves.
 *
 * <h2>Threading</h2>
 * Written from database callbacks and read from the command thread, so the backing
 * map is concurrent. Names are keyed by their lower-cased form because Minecraft
 * treats usernames case-insensitively, while the value keeps the spelling to show.
 */
public final class RegisteredNames {

    private static final RegisteredNames INSTANCE = new RegisteredNames();

    /** Lower-cased name to the spelling worth showing. Sorted, so suggestions are stable. */
    private final Map<String, String> byLowerCase = new ConcurrentSkipListMap<>();

    RegisteredNames() {
    }

    /** The set every command and suggestion provider consults. */
    public static RegisteredNames getInstance() {
        return INSTANCE;
    }

    /** Replace the whole set, e.g. from the startup query. A null collection empties it. */
    public void seed(Collection<String> usernames) {
        Map<String, String> replacement = new ConcurrentHashMap<>();
        if (usernames != null) {
            for (String username : usernames) {
                String normalized = normalize(username);
                if (normalized != null) {
                    replacement.put(normalized.toLowerCase(Locale.ROOT), normalized);
                }
            }
        }
        byLowerCase.keySet().retainAll(replacement.keySet());
        byLowerCase.putAll(replacement);
    }

    public void add(String username) {
        String normalized = normalize(username);
        if (normalized != null) {
            byLowerCase.put(normalized.toLowerCase(Locale.ROOT), normalized);
        }
    }

    public void remove(String username) {
        String normalized = normalize(username);
        if (normalized != null) {
            byLowerCase.remove(normalized.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Record what the database just said about this username.
     *
     * <p>The only writer that can both add and remove, and the reason a stale entry
     * heals the moment the player it concerns is looked up again.
     */
    public void setPresent(String username, boolean registered) {
        if (registered) {
            add(username);
        } else {
            remove(username);
        }
    }

    public boolean contains(String username) {
        String normalized = normalize(username);
        return normalized != null && byLowerCase.containsKey(normalized.toLowerCase(Locale.ROOT));
    }

    /** An immutable, alphabetically ordered snapshot for tab-completion. */
    public List<String> names() {
        return List.copyOf(byLowerCase.values());
    }

    public int size() {
        return byLowerCase.size();
    }

    private static String normalize(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

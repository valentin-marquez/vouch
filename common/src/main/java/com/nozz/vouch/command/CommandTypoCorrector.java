package com.nozz.vouch.command;

import com.nozz.vouch.util.PreAuthCommandGate;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a mistyped command into "did you mean …", for players still in the
 * pre-auth jail.
 *
 * <h2>The visibility rule</h2>
 * <b>Only ever suggests a command the player can already see.</b> The candidates
 * are exactly the allowlist {@link PreAuthCommandGate} passes in — the same set
 * that decides what an unauthenticated player may run. Searching the full
 * dispatcher instead would leak the command surface that the rest of this mod's
 * gating is there to hide: a player could enumerate a server's plugins by typing
 * near-misses at the login screen. There is no code path here that consults
 * anything but the given candidates.
 *
 * <p>The gate consults this only for commands it has <em>already blocked</em> and
 * ignores anything thrown, so a suggestion can never widen access.
 *
 * <h2>Thresholds</h2>
 * Plain Levenshtein distance, with a budget that grows with the length of what
 * was typed: {@code (length - 1) / 2}, capped at 2. Short input therefore gets
 * almost no latitude, which is the case that matters — with a generous budget
 * every stray keypress would come back as "did you mean /l?", and a two-letter
 * command like {@code /tp} would be "corrected" into something the player never
 * meant. Candidates shorter than two characters are never offered for the same
 * reason. When nothing is within budget, nothing is suggested.
 */
public final class CommandTypoCorrector implements PreAuthCommandGate.Corrector {

    /** Beyond this, a "correction" is a guess rather than a fixed typo. */
    private static final int MAX_DISTANCE = 2;

    /** A one-character candidate is within one edit of most of the keyboard. */
    private static final int MIN_CANDIDATE_LENGTH = 2;

    @Override
    public Optional<String> suggest(String rootLiteral, Set<String> allowed) {
        if (rootLiteral == null || allowed == null) {
            return Optional.empty();
        }
        String typed = rootLiteral.trim().toLowerCase(Locale.ROOT);
        if (typed.isEmpty()) {
            return Optional.empty();
        }

        int budget = budgetFor(typed.length());
        if (budget <= 0) {
            return Optional.empty();
        }

        String best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String candidate : allowed) {
            if (candidate == null) {
                continue;
            }
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (normalized.length() < MIN_CANDIDATE_LENGTH) {
                continue;
            }
            if (normalized.equals(typed)) {
                // The caller only asks about blocked commands; an exact match means the
                // allowlist and the caller disagree. Echoing it back would be nonsense.
                return Optional.empty();
            }
            if (Math.abs(normalized.length() - typed.length()) > budget) {
                continue;
            }

            int distance = distance(typed, normalized);
            if (distance > budget) {
                continue;
            }
            // Alphabetical order breaks ties, so the same typo always yields the same
            // suggestion whatever order the allowlist happens to iterate in.
            if (distance < bestDistance || (distance == bestDistance && normalized.compareTo(best) < 0)) {
                best = normalized;
                bestDistance = distance;
            }
        }

        return Optional.ofNullable(best);
    }

    /** How many edits may separate what was typed from something worth offering. */
    static int budgetFor(int typedLength) {
        return Math.min(MAX_DISTANCE, (typedLength - 1) / 2);
    }

    /**
     * Levenshtein distance: the number of single-character insertions, deletions and
     * substitutions separating two strings. Two rows rather than a full matrix, since
     * these are command literals and this runs on the Netty thread.
     */
    static int distance(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) {
            return b.length();
        }
        if (b.isEmpty()) {
            return a.length();
        }

        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                current[j] = Math.min(substitution, Math.min(deletion, insertion));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[b.length()];
    }
}

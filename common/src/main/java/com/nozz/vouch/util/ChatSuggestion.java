package com.nozz.vouch.util;

/**
 * The prompts that carry a clickable next step, and what that step is.
 *
 * <h2>Why this is a table and not six string literals</h2>
 * Each entry pairs a translation key with a command. Both halves rot: a lang key
 * gets renamed, or — as in this restructure — a command moves and every prompt
 * still points at where it used to be. Keeping the pairs in one enum is what lets
 * a test walk them, check each key exists in the source language, and parse each
 * command against the real dispatcher to prove it still resolves to something.
 *
 * <h2>What translators see</h2>
 * Nothing. The lang files hold plain strings; the command text lives here, and the
 * hover wording is its own ordinary key ({@code vouch.ui.click_to_fill}). A
 * translator can rewrite every word of a prompt without being able to change or
 * break what the click does — and a language that has not been updated still gets
 * a working, clickable message through the usual fallback.
 *
 * <h2>Suggest, never run</h2>
 * These commands take passwords and one-time codes. The click fills the chat box
 * and stops there; a click that executed would submit an empty credential, in
 * public. That is why the trailing space matters on the entries that take an
 * argument — the caret lands where the player types.
 */
public enum ChatSuggestion {

    /** "You are not registered" → offer the registration command. */
    NOT_REGISTERED("vouch.auth.not_registered", "/register "),

    /** "Log in first" → offer the login command. */
    MUST_LOGIN_FIRST("vouch.2fa.must_login_first", "/login "),

    /** "Second factor required" → offer the verify step. */
    TWO_FACTOR_REQUIRED("vouch.2fa.required", "/2fa verify "),

    /** The QR-code instructions → offer the verify step that completes them. */
    TWO_FACTOR_SETUP_INSTRUCTIONS("vouch.2fa.setup.instructions", "/2fa verify "),

    /** "2FA is off" → offer to turn it on. */
    TWO_FACTOR_STATUS_DISABLED("vouch.2fa.status.disabled", "/vouch 2fa setup"),

    /** An operator asked to configure 2FA → offer the command that does it. */
    TWO_FACTOR_REQUIRED_FOR_OPS("vouch.2fa.required_for_ops", "/vouch 2fa setup");

    /** The hover text shown over any suggested command. One key, all prompts. */
    public static final String HOVER_KEY = "vouch.ui.click_to_fill";

    private final String langKey;
    private final String command;

    ChatSuggestion(String langKey, String command) {
        this.langKey = langKey;
        this.command = command;
    }

    /** The translation key of the message itself. */
    public String langKey() {
        return langKey;
    }

    /** What is filled into the player's chat box, leading slash included. */
    public String command() {
        return command;
    }

    /**
     * The command without its trailing space or arguments — the path a dispatcher
     * should be able to resolve.
     */
    public String commandPath() {
        return command.startsWith("/") ? command.substring(1).trim() : command.trim();
    }
}

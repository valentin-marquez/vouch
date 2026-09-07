package com.nozz.vouch.auth;

/**
 * Everything {@link LoginPolicy} needs to judge a login attempt, gathered by
 * the caller before the decision is made.
 *
 * Deliberately holds no Minecraft types and performs no lookups of its own —
 * that is what makes the decision testable.
 */
public record LoginFacts(
        boolean registered,
        boolean passwordHashPresent,
        boolean passwordValid,
        boolean twoFactorEnabled,
        boolean operator,
        boolean require2faForOps
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean registered;
        private boolean passwordHashPresent;
        private boolean passwordValid;
        private boolean twoFactorEnabled;
        private boolean operator;
        private boolean require2faForOps;

        private Builder() {
        }

        public Builder registered(boolean value) {
            this.registered = value;
            return this;
        }

        public Builder passwordHashPresent(boolean value) {
            this.passwordHashPresent = value;
            return this;
        }

        public Builder passwordValid(boolean value) {
            this.passwordValid = value;
            return this;
        }

        public Builder twoFactorEnabled(boolean value) {
            this.twoFactorEnabled = value;
            return this;
        }

        public Builder operator(boolean value) {
            this.operator = value;
            return this;
        }

        public Builder require2faForOps(boolean value) {
            this.require2faForOps = value;
            return this;
        }

        public LoginFacts build() {
            return new LoginFacts(registered, passwordHashPresent, passwordValid,
                    twoFactorEnabled, operator, require2faForOps);
        }
    }
}

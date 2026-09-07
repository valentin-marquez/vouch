package com.nozz.vouch.auth;

/** Everything {@link SessionRestorePolicy} needs, gathered before deciding. */
public record SessionRestoreFacts(
        boolean persistenceEnabled,
        boolean addressKnown,
        boolean liveSessionForThisAddress,
        boolean premiumVerifiedThisLogin,
        boolean twoFactorEnabled,
        boolean require2faOnRestore
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean persistenceEnabled;
        private boolean addressKnown;
        private boolean liveSessionForThisAddress;
        private boolean premiumVerifiedThisLogin;
        private boolean twoFactorEnabled;
        private boolean require2faOnRestore = true;

        private Builder() {
        }

        public Builder persistenceEnabled(boolean v) { this.persistenceEnabled = v; return this; }
        public Builder addressKnown(boolean v) { this.addressKnown = v; return this; }
        public Builder liveSessionForThisAddress(boolean v) { this.liveSessionForThisAddress = v; return this; }
        public Builder premiumVerifiedThisLogin(boolean v) { this.premiumVerifiedThisLogin = v; return this; }
        public Builder twoFactorEnabled(boolean v) { this.twoFactorEnabled = v; return this; }
        public Builder require2faOnRestore(boolean v) { this.require2faOnRestore = v; return this; }

        public SessionRestoreFacts build() {
            return new SessionRestoreFacts(persistenceEnabled, addressKnown, liveSessionForThisAddress,
                    premiumVerifiedThisLogin, twoFactorEnabled, require2faOnRestore);
        }
    }
}

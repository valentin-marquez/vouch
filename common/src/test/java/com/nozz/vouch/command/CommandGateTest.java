package com.nozz.vouch.command;

import org.junit.jupiter.api.Test;

import static com.nozz.vouch.command.PlayerAuthState.AUTHENTICATED;
import static com.nozz.vouch.command.PlayerAuthState.AWAITING_2FA;
import static com.nozz.vouch.command.PlayerAuthState.UNAUTHENTICATED_REGISTERED;
import static com.nozz.vouch.command.PlayerAuthState.UNAUTHENTICATED_UNREGISTERED;
import static com.nozz.vouch.command.VouchCommand.LOGIN;
import static com.nozz.vouch.command.VouchCommand.LOGOUT;
import static com.nozz.vouch.command.VouchCommand.PASSWORD;
import static com.nozz.vouch.command.VouchCommand.PREMIUM;
import static com.nozz.vouch.command.VouchCommand.REGISTER;
import static com.nozz.vouch.command.VouchCommand.TWO_FACTOR_DISABLE;
import static com.nozz.vouch.command.VouchCommand.TWO_FACTOR_SETUP;
import static com.nozz.vouch.command.VouchCommand.TWO_FACTOR_STATUS;
import static com.nozz.vouch.command.VouchCommand.TWO_FACTOR_VERIFY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which commands exist, from the player's point of view, in each state.
 *
 * Brigadier only sends a client the nodes whose predicate accepts them, so this
 * matrix decides both what tab-completion offers and — for this mod's own
 * commands — what the server will accept. A command that stays visible while
 * unauthenticated is not merely untidy: it is reachable.
 */
class CommandGateTest {

    private static boolean visible(VouchCommand command, PlayerAuthState state) {
        return CommandGate.isAvailable(command, state, false);
    }

    private static boolean visibleWith2FA(VouchCommand command, PlayerAuthState state) {
        return CommandGate.isAvailable(command, state, true);
    }

    @Test
    void anUnregisteredPlayerIsOfferedOnlyRegister() {
        assertTrue(visible(REGISTER, UNAUTHENTICATED_UNREGISTERED));
        assertFalse(visible(LOGIN, UNAUTHENTICATED_UNREGISTERED));
        assertFalse(visible(LOGOUT, UNAUTHENTICATED_UNREGISTERED));
        assertFalse(visible(PASSWORD, UNAUTHENTICATED_UNREGISTERED));
        assertFalse(visible(PREMIUM, UNAUTHENTICATED_UNREGISTERED));
    }

    @Test
    void aRegisteredPlayerIsOfferedOnlyLogin() {
        assertTrue(visible(LOGIN, UNAUTHENTICATED_REGISTERED));
        assertFalse(visible(REGISTER, UNAUTHENTICATED_REGISTERED));
        assertFalse(visible(LOGOUT, UNAUTHENTICATED_REGISTERED));
    }

    @Test
    void awaiting2FAOffersOnlyTheVerifyStep() {
        assertTrue(visible(TWO_FACTOR_VERIFY, AWAITING_2FA));
        assertFalse(visible(LOGIN, AWAITING_2FA));
        assertFalse(visible(TWO_FACTOR_SETUP, AWAITING_2FA));
        assertFalse(visible(TWO_FACTOR_DISABLE, AWAITING_2FA));
        assertFalse(visible(LOGOUT, AWAITING_2FA));
    }

    @Test
    void authenticatedPlayersGetTheOrdinarySurface() {
        assertTrue(visible(LOGOUT, AUTHENTICATED));
        assertTrue(visible(PASSWORD, AUTHENTICATED));
        assertTrue(visible(PREMIUM, AUTHENTICATED));
        assertTrue(visible(TWO_FACTOR_STATUS, AUTHENTICATED));
        assertFalse(visible(LOGIN, AUTHENTICATED));
        assertFalse(visible(REGISTER, AUTHENTICATED));
    }

    @Test
    void twoFactorSetupIsOfferedOnlyWhenItIsNotAlreadyOn() {
        assertTrue(visible(TWO_FACTOR_SETUP, AUTHENTICATED));
        assertFalse(visibleWith2FA(TWO_FACTOR_SETUP, AUTHENTICATED));
    }

    @Test
    void twoFactorDisableIsOfferedOnlyWhenItIsOn() {
        assertFalse(visible(TWO_FACTOR_DISABLE, AUTHENTICATED));
        assertTrue(visibleWith2FA(TWO_FACTOR_DISABLE, AUTHENTICATED));
    }

    @Test
    void noCommandIsAvailableInEveryState() {
        // A command reachable from every state — including unauthenticated —
        // is almost certainly a gate someone forgot to set.
        for (VouchCommand command : VouchCommand.values()) {
            long states = 0;
            for (PlayerAuthState state : PlayerAuthState.values()) {
                if (CommandGate.isAvailable(command, state, false)
                        || CommandGate.isAvailable(command, state, true)) {
                    states++;
                }
            }
            assertTrue(states < PlayerAuthState.values().length,
                    command + " is available in every auth state");
        }
    }

    @Test
    void everyCommandIsReachableFromSomeState() {
        // The mirror of the previous check: a command no state can reach is
        // dead weight in the tree.
        for (VouchCommand command : VouchCommand.values()) {
            boolean reachable = false;
            for (PlayerAuthState state : PlayerAuthState.values()) {
                reachable |= CommandGate.isAvailable(command, state, false)
                        || CommandGate.isAvailable(command, state, true);
            }
            assertTrue(reachable, command + " is unreachable from every auth state");
        }
    }
}

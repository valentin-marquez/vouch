# Quick Start

This guide walks you through the minimal setup to get Vouch working on your server.

## 1. Install the Mod

Follow the [Installation Guide](./installation) for your platform (Fabric or NeoForge).

## 2. Start the Server

Start the server once to generate the default configuration:

```bash
java -jar server.jar
```

Vouch will create `config/vouch/vouch.toml` with sensible defaults.

## 3. Review the Config

The default configuration works out of the box with:

- **Auth mode**: `password_optional_2fa` — password required, optional 2FA
- **Database**: H2 (embedded, zero config)
- **Login timeout**: 60 seconds
- **Session persistence**: enabled (1 hour)

For most servers, the defaults are sufficient. See [Configuration](./configuration/) if you want to customize.

## 4. Test the Auth Flow

### Register a New Player

1. Join the server as a new player.
2. You will see a title: **"Welcome!"** with subtitle **"Use /register \<password\> \<password\>"**.
3. A boss bar shows a countdown timer.
4. Run:

```
/register MySecurePassword123 MySecurePassword123
```

5. You should hear a success sound and see a confirmation title.

### Log In

1. Disconnect and reconnect to the server.
2. You will see the login prompt.
3. Run:

```
/login MySecurePassword123
```

4. Session is now active — on your next reconnect within 1 hour, you'll be authenticated automatically.

### Set Up 2FA (Optional)

1. While authenticated, run:

```
/2fa setup
```

2. A QR code will appear rendered on a map in your hand.
3. Scan it with your authenticator app (Google Authenticator, Authy, Aegis).
4. Verify with:

```
/2fa verify 123456
```

5. 2FA is now enabled. On the next login, you'll need to enter a TOTP code after your password.

## 5. Admin Commands

As an operator (OP level 4), you have access to admin commands:

```
/vouch admin reload           # Reload config and language files
/vouch admin unregister Steve # Remove a player's registration
/vouch admin export-lang      # Export language file to config dir
```

## Next Steps

- [Configuration Reference](./configuration/) — Customize every aspect of Vouch
- [Commands](./commands) — Full command reference
- [Auth Modes](./auth-modes) — Choose the right auth mode for your server
- [Security](./security) — Understand the security model

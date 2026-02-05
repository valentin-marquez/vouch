Vouch

[![Modrinth](https://img.shields.io/modrinth/dt/vouch?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/mod/vouch)
[![CurseForge](https://img.shields.io/curseforge/dt/vouch?logo=curseforge&label=CurseForge&color=F16436)](https://www.curseforge.com/minecraft/mc-mods/vouch)
[![Fabric](https://img.shields.io/badge/Fabric-1.21.1-DBD0B4?logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAOCAYAAAAfSC3RAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAABOSURBVDhPY/hPAWBkYGD4j4cNBDCKEwMYGBgYmBgoBKMaKQSjGikEFGv8T6ZGkBomRkoByBgmShVDjSMTMDIwMPxHE2OiVDHUOBIBAOjWE8cjMFcGAAAAAElFTkSuQmCC)](https://modrinth.com/mod/vouch)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-f38120?logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAOCAYAAAAfSC3RAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAABYSURBVDhPY/hPAWBkYGD4j4cNBDCKEwOwMDAwMPz//58ozcgamCgFIGOYKFXMwMDAQK5GkBoGBgaGJ0+e/CfFVpBGJkoV////n+x4BKlhYqAUjGqkEAAAWrcVx7sFjEEAAAAASUVORK5CYII=)](https://modrinth.com/mod/vouch)

Server-side authentication mod for Minecraft 1.21.1. Works on Fabric and NeoForge.

Players register with a password, log in when they join, and optionally enable TOTP two-factor authentication. The server handles everything. No client mod needed.

Passwords are hashed with Argon2id asynchronously so the main thread is never blocked. TOTP secrets are encrypted at rest. Sessions persist across reconnects so players don't have to re-authenticate every time they rejoin.

Supports H2 (embedded, default), MySQL, and PostgreSQL. Switch between them in the config file.

The mod integrates with LuckPerms and other permission systems through the Fabric Permissions API on Fabric and NeoForge's built-in permission system.

Built with Architectury for cross-platform support from a single codebase.

License: All Rights Reserved - Source Available (see LICENSE file)
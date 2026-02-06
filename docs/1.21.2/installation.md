# Installation

## Download

Vouch is available on multiple platforms:

[![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg)](https://modrinth.com/mod/vouch)
[![CurseForge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg)](https://legacy.curseforge.com/minecraft/mc-mods/vouch)
[![GitHub Releases](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg)](https://github.com/valentin-marquez/vouch/releases)

| Platform | Link |
|----------|------|
| **Modrinth** | [modrinth.com/mod/vouch](https://modrinth.com/mod/vouch) |
| **CurseForge** | [curseforge.com/minecraft/mc-mods/vouch](https://legacy.curseforge.com/minecraft/mc-mods/vouch) |
| **GitHub Releases** | [github.com/valentin-marquez/vouch/releases](https://github.com/valentin-marquez/vouch/releases) |

---

## Fabric

### Dependencies

| Mod | Required | Link |
|-----|----------|------|
| **Fabric Loader** | ≥ 0.16.9 | [fabricmc.net](https://fabricmc.net/use/) |
| **Fabric API** | Any version for 1.21.2 | [Modrinth](https://modrinth.com/mod/fabric-api) |
| **Architectury API** | 14.0.x | [Modrinth](https://modrinth.com/mod/architectury-api) |

### Steps

1. Download and install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.2 or 1.21.3.
2. Download the required dependencies listed above and place them in your `mods/` folder.
3. Download `vouch-fabric-0.1.0+1.21.2.jar` and place it in your `mods/` folder.
4. Start the server.

```
server/
├── mods/
│   ├── fabric-api-x.x.x+1.21.2.jar
│   ├── architectury-14.0.x-fabric.jar
│   └── vouch-fabric-0.1.0+1.21.2.jar
├── server.jar
└── ...
```

---

## NeoForge

### Dependencies

| Mod | Required | Link |
|-----|----------|------|
| **NeoForge** | ≥ 21.2 | [neoforged.net](https://neoforged.net/) |
| **Architectury API** | 14.0.x | [Modrinth](https://modrinth.com/mod/architectury-api) |

### Steps

1. Download and install [NeoForge](https://neoforged.net/) for Minecraft 1.21.2 or 1.21.3.
2. Download Architectury API and place it in your `mods/` folder.
3. Download `vouch-neoforge-0.1.0+1.21.2.jar` and place it in your `mods/` folder.
4. Start the server.

```
server/
├── mods/
│   ├── architectury-14.0.x-neoforge.jar
│   └── vouch-neoforge-0.1.0+1.21.2.jar
├── server.jar
└── ...
```

::: tip No Fabric API on NeoForge
NeoForge does **not** require Fabric API. Only Architectury API is needed.
:::

---

## First Launch

On the first server start with Vouch installed, the following files and directories are automatically created:

```
config/
└── vouch/
    ├── vouch.toml          # Main configuration file
    └── lang/
        ├── en_us.json      # English language file
        └── es_mx.json      # Spanish (Mexico) language file

vouch/
└── vouch.mv.db             # H2 database (default)
```

### Verifying Installation

1. Check the server console for the Vouch initialization message.
2. Confirm the `config/vouch/vouch.toml` file was generated.
3. Join the server — you should see the registration prompt (title, action bar, boss bar).

::: warning Accept the EULA
Make sure your `eula.txt` is set to `eula=true` before the first launch.
:::

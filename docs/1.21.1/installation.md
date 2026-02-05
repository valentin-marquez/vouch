# Installation

## Fabric

### Dependencies

| Mod | Required | Link |
|-----|----------|------|
| **Fabric Loader** | ≥ 0.15.11 | [fabricmc.net](https://fabricmc.net/use/) |
| **Fabric API** | Any version for 1.21.1 | [Modrinth](https://modrinth.com/mod/fabric-api) |
| **Architectury API** | 13.0.x | [Modrinth](https://modrinth.com/mod/architectury-api) |

### Steps

1. Download and install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.1.
2. Download the required dependencies listed above and place them in your `mods/` folder.
3. Download `vouch-fabric-0.1.0+1.21.1.jar` and place it in your `mods/` folder.
4. Start the server.

```
server/
├── mods/
│   ├── fabric-api-x.x.x+1.21.1.jar
│   ├── architectury-13.0.x-fabric.jar
│   └── vouch-fabric-0.1.0+1.21.1.jar
├── server.jar
└── ...
```

---

## NeoForge

### Dependencies

| Mod | Required | Link |
|-----|----------|------|
| **NeoForge** | ≥ 21.1 | [neoforged.net](https://neoforged.net/) |
| **Architectury API** | 13.0.x | [Modrinth](https://modrinth.com/mod/architectury-api) |

### Steps

1. Download and install [NeoForge](https://neoforged.net/) for Minecraft 1.21.1.
2. Download Architectury API and place it in your `mods/` folder.
3. Download `vouch-neoforge-0.1.0+1.21.1.jar` and place it in your `mods/` folder.
4. Start the server.

```
server/
├── mods/
│   ├── architectury-13.0.x-neoforge.jar
│   └── vouch-neoforge-0.1.0+1.21.1.jar
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

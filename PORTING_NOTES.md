# Vouch — Multi-Version Porting Notes

This document tracks version compatibility groups, breaking changes, and porting status
for Vouch across Minecraft 1.21.x versions.

## Version Compatibility Map

| Group | MC Versions      | Branch     | Architectury | NeoForge  | Fabric API          | Yarn Mappings       | Status      |
|-------|------------------|------------|--------------|-----------|---------------------|---------------------|-------------|
| A     | 1.21.1           | mc/1.21.1  | 13.0.8       | 21.1.215  | 0.116.8+1.21.1      | 1.21.1+build.1      | ✅ Released  |
| B     | 1.21.2, 1.21.3   | mc/1.21.2  | 14.x         | 21.2.x    | 0.x+1.21.2          | 1.21.2+build.x      | ⬜ Pending   |
| C     | 1.21.4           | mc/1.21.4  | 15.0.3       | 21.4.130  | 0.119.4+1.21.4      | 1.21.4+build.1      | ✅ Ported    |
| D     | 1.21.5           | mc/1.21.5  | 16.x         | 21.5.x    | 0.x+1.21.5          | 1.21.5+build.x      | ⬜ Pending   |
| E     | 1.21.6–1.21.8    | mc/1.21.6  | 17.x         | 21.6.x    | 0.x+1.21.6          | 1.21.6+build.x      | ⬜ Pending   |
| F     | 1.21.9, 1.21.10  | mc/1.21.9  | 18.x         | 21.9.x    | 0.x+1.21.9          | 1.21.9+build.x      | ⬜ Pending   |
| G     | 1.21.11          | mc/1.21.11 | 19.x         | 21.11.x   | 0.x+1.21.11         | 1.21.11+build.x     | ⬜ Pending   |

## Porting Priority

1. **1.21.4** (Group C) — The Garden Awakens, widely used
2. **1.21.2–1.21.3** (Group B) — Bundles of Bravery
3. **1.21.5** (Group D) — Spring to Life
4. **1.21.6–1.21.8** (Group E) — Chase the Skies
5. **1.21.9–1.21.10** (Group F) — The Copper Age
6. **1.21.11** (Group G) — Mounts of Mayhem

## Breaking Changes Per Boundary

### A → B: 1.21.1 → 1.21.2 | Impact: MODERATE

- **Protocol**: 767 → 768
- **`ActionResult` refactoring**: MC 1.21.2 restructured `ActionResult` return types. Directly affects
  `PlayerInteractionMixin` which returns `ActionResult.FAIL` from `interactItem()` and `interactBlock()`.
  Verify return type still exists in Yarn mappings.
- **Action items**:
  1. Update `gradle.properties`: Yarn, Fabric API, NeoForge, Architectury → v14
  2. Verify `ActionResult.FAIL` return type on `interactItem`/`interactBlock`
  3. Re-verify all `@Inject` method descriptors against 1.21.2 Yarn mappings

### B → C: 1.21.2/3 → 1.21.4 | Impact: LOW → MODERATE (ACTUAL)

- **Protocol**: 768 → 769
- **Damage immunity removed**: 3-second post-spawn damage immunity eliminated.
  `EntityDamageMixin` becomes more critical (no built-in immunity window).
- **Architectury API v15 breaking change** (CONFIRMED):
  - `InteractionEvent.RIGHT_CLICK_BLOCK` now expects `ActionResult` return type (was `EventResult`)
  - `InteractionEvent.LEFT_CLICK_BLOCK` now expects `ActionResult` return type (was `EventResult`)
  - `InteractionEvent.INTERACT_ENTITY` still uses `EventResult` (unchanged)
  - Fix: Change `EventResult.interruptFalse()` → `ActionResult.FAIL`, `EventResult.pass()` → `ActionResult.PASS`
- **Action items**:
  1. Update dependency versions (Architectury v14 → v15)
  2. Import `net.minecraft.util.ActionResult`
  3. Update block interaction event handlers to return `ActionResult` instead of `EventResult`
  4. Test damage blocking immediately on join

### C → D: 1.21.4 → 1.21.5 | Impact: MODERATE

- **Protocol**: 769 → 770
- **Text component format overhaul**: Serialization format changed. Java API should remain stable,
  but any raw JSON text construction would break.
- **Chat packet changes**: `player_chat` packet now has `index` field, checksum byte added.
  Verify `onChatMessage()` handler in `ServerPlayNetworkHandlerMixin`.
- **Action items**:
  1. Update dependency versions (Architectury v15 → v16)
  2. Verify `ChatMessageC2SPacket` class structure
  3. Verify all text construction uses programmatic API (not raw JSON)

### D → E: 1.21.5 → 1.21.6 | Impact: LOW

- **Protocol**: 770 → 771
- **Strict JSON parsing**: All JSON now parsed in strict mode (no trailing commas, no comments).
- **Dialog system**: New `/dialog` command — potential future auth UI (not blocking).
- **Action items**:
  1. Update dependency versions (Architectury v16 → v17)
  2. Audit JSON files for strict-mode compliance

### E → F: 1.21.6/7/8 → 1.21.9 | Impact: HIGH

- **Protocol**: 772 → 773
- **Spawn chunks removed**: Dimensions now "active" based on player/forceload activity.
- **`pack.mcmeta` format overhauled**: `pack_format` → `min_format`/`max_format`.
- **`server.properties` → game rules**: `pvp`, `allow-nether`, etc moved to game rules.
- **Action items**:
  1. Update dependency versions (Architectury v17 → v18)
  2. Update `pack.mcmeta` to new format
  3. Verify mixin targets against 1.21.9 Yarn mappings
  4. Test auth flow without spawn chunks

### F → G: 1.21.9/10 → 1.21.11 | Impact: HIGH

- **Protocol**: 773 → 774
- **ALL game rule names renamed**: `camelCase` → `snake_case` with `minecraft:` namespace.
- **Unobfuscated release**: First version with unobfuscated option.
- **Last version using `1.x.y` format**: Next version is `26.1`.
- **Action items**:
  1. Update dependency versions (Architectury v18 → v19)
  2. Audit all game rule references and update names
  3. Update `pack.mcmeta` format (94.1)
  4. Verify mixin targets against 1.21.11 Yarn mappings

## Mixin Target Risk Assessment

| Mixin File                         | Target Method                   | Risk    | Notes                                        |
|------------------------------------|---------------------------------|---------|----------------------------------------------|
| `ServerPlayerEntityMixin`          | `tick()` HEAD/TAIL              | LOW     | Fundamental entity tick                      |
| `ServerPlayerEntityMixin`          | `playerTick()` HEAD             | LOW-MED | Yarn name could change                       |
| `ServerPlayNetworkHandlerMixin`    | `onChatMessage(ChatMessageC2SPacket)` | MEDIUM | Chat packet changed in 1.21.5         |
| `PlayerInteractionMixin`           | `tryBreakBlock(BlockPos)`       | LOW     | Stable block interaction                     |
| `PlayerInteractionMixin`           | `interactItem(...)` → `ActionResult` | HIGH | ActionResult refactored in 1.21.2      |
| `PlayerInteractionMixin`           | `interactBlock(...)` → `ActionResult` | HIGH | Same ActionResult concern              |
| `EntityDamageMixin`                | `damage(DamageSource, float)`   | MEDIUM  | Verify DamageSource hasn't moved             |

## Backporting / Forward-Porting Workflow

1. **Develop new features on `main`** (always the newest supported version)
2. **Cherry-pick** relevant commits to older `mc/*` branches
3. **Version-specific fixes** go directly to the affected branch
4. **Tags**: `v{mod_version}-mc{minecraft_version}` (e.g., `v0.1.1-mc1.21.4`)

## Support Policy

| Tier             | Branches                         | Scope                           |
|------------------|----------------------------------|---------------------------------|
| **Active**       | `main` + `mc/1.21.1` + `mc/1.21.4` | Features + bugfixes + security |
| **Maintenance**  | All other `mc/*` branches        | Bugfixes + security only       |
| **Archive**      | (none yet)                       | No updates                     |

Evaluate archival after 6 months of inactivity.

## Verification Checklist (per group)

- [ ] `gradlew clean build` succeeds (Fabric + NeoForge)
- [ ] `gradlew :fabric:runServer` — mod loads, no crashes
- [ ] `gradlew :neoforge:runServer` — mod loads, no crashes
- [ ] Connect to server → pre-auth jail works (movement frozen)
- [ ] `/register` + `/login` flow works
- [ ] Chat blocked for unauthenticated players
- [ ] Block break/place/item use blocked
- [ ] Damage invulnerability works
- [ ] 2FA QR code renders on map
- [ ] Session persistence works across reconnect
- [ ] Database operations work (H2 default)

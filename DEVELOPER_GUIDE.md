# Boxy — Developer Guide

> A complete reference for anyone touching this codebase for the first time.
> Read the **Overview** and **The Core Problem** first; everything else is detail you can jump to.

---

## 1. Overview

**Boxy loads [Voxy](https://modrinth.com/mod/voxy) — a Fabric Level-of-Detail (LOD) rendering mod — on Forge 1.20.1, without modifying Voxy's jar.** On top of that it adds a port of **[Voxy Server Side](https://modrinth.com/mod/voxy-server-side) (VSS)**: a client+server protocol that streams distant LOD terrain from a server to clients (see §9). It also adds **distant entity & player rendering** (§11): real players and configured mobs, shown out toward Voxy's LOD range by extending vanilla entity tracking. And when **[Oculus](https://modrinth.com/mod/oculus)** (the Forge fork of Iris) is installed, Voxy's built-in shader integration engages so LODs render through the active shaderpack — Boxy just feeds Oculus to the remap and bridges the `iris` mod id (§12).

You drop the *unmodified, Fabric-built* Voxy jar into your `mods/` folder next to Boxy, and Boxy does everything needed at launch to make Forge load and run it: it remaps Voxy's bytecode, translates its Fabric metadata into Forge metadata, bridges the Fabric APIs Voxy calls, and patches a handful of incompatibilities. The user never sees any of this — Voxy "just works," and shows up in the Forge mods list.

Boxy is the spiritual sibling of **Foxy**, which does the same thing on **NeoForge 26.1.2**. The crucial difference — and the reason Boxy is dramatically more complex than Foxy — is mappings (see below).

| Project | Loader | MC version | Role |
|---|---|---|---|
| **Voxy** | Fabric | 1.20.1 | The LOD mod we want to run. We never modify it. |
| **Foxy** | NeoForge | 26.1.2 | Reference implementation. Loads Voxy with *no bytecode remap*. |
| **Boxy** | **Forge** | **1.20.1** | This project. Loads Voxy *with* a full bytecode remap, **and** adds the VSS server-streaming port. |

---

## 2. The Core Problem: three mapping namespaces

Minecraft's classes/methods/fields have different names depending on who compiled the code. There are **three** namespaces in play, and Voxy's jar is in the "wrong" one for Forge:

| Namespace | Looks like | Used by |
|---|---|---|
| **Fabric intermediary** | `net/minecraft/class_310`, `method_1551`, `field_1724` | **Voxy's published jar** |
| **SRG** | `net/minecraft/client/Minecraft` (official class names) + `m_91087_`, `f_91074_` (obfuscated members) | **Forge 1.20.1 runtime** |
| **Mojmap (official)** | `net/minecraft/client/Minecraft`, `getInstance`, `player` | **NeoForge runtime** + Boxy's own dev environment |

This single table explains the entire project:

- **Foxy is easy** because NeoForge runs in **Mojmap**, and the *NeoForge build of Voxy* it loads is already Mojmap-named. No translation needed — NeoForge can load Voxy's bytecode as-is.
- **Boxy is hard** because Forge runs in **SRG**, but Voxy's jar is in **intermediary**. Every Minecraft reference in Voxy's bytecode — every method call, field access, mixin target, and access widener — must be rewritten `intermediary → SRG` *at load time, in memory*, before Forge sees it.

So Boxy is essentially a tiny, Voxy-specific [Sinytra Connector](https://github.com/Sinytra/Connector): a load-time Fabric-on-Forge shim, scoped to exactly one mod.

> **Rule of thumb when reading the code:** any string like `class_310`/`method_1551`/`field_1724` is **intermediary** (Voxy's world). Any `m_…`/`f_…` is **SRG** (the runtime). Any normal name like `getInstance` is **official/Mojmap** (Boxy's own source code and the dev environment).

---

## 3. Architecture at a glance

Boxy has parts in **two different Forge classloader layers**, and at scan time the locator hands Forge **two** jars: the remapped Voxy jar *and* Boxy's own standalone game-layer mod.

```
                      ┌─────────────────────────────────────────────────────────┐
   mods/              │  SERVICE LAYER (boots first; JPMS module "boxyloader")   │
   ├── Boxy.jar ──────►  • BoxyTransformationService  (ITransformationService)   │
   │   (this project) │  • BoxyModLocator             (IModLocator)              │
   │                  │     │                                                    │
   │                  │     │  scanCandidates() returns TWO paths:               │
   │                  │     ▼                                                    │
   │                  │   1) VoxyRemapper.remap(voxy.jar) ─► .boxy/cache/        │
   │                  │        intermediary → SRG, +metadata, +patches          │
   │                  │   2) extract boxy-mod.libzip ─────► .boxy/cache/         │
   │                  │        (Boxy's own reobf'd game-layer mod = "boxy")      │
   │                  └───────────────│─────────────────────────────────────────┘
   │                                  │ both returned into Forge's discovery
   │                                  ▼
   ├── voxy.jar ───────►  ┌─────────────────────────────────────────────────────┐
   │   (unmodified,    │  │  GAME LAYER (normal mods)                            │
   │    Fabric)        │  │  • voxy.jar     → mod "voxy"  (+ injected @Mod shim) │
   │                   │  │  • boxy-mod.jar → mod "boxy"  (BoxyVss: VSS + ingest)│
   ├── Embeddium.jar ──►  │  • Voxy's mixins + Boxy's boxy.mixins.json apply     │
   └── ...             │  │  • Voxy renders LODs; Boxy streams server LODs (§9)  │
                          └─────────────────────────────────────────────────────┘
```

### Why two layers?

Forge's **service layer** (a.k.a. plugin layer) boots *before* the game and mod classes exist. It's where you're allowed to discover and transform jars. Boxy must run here because it needs to produce Voxy's jar *before* Forge tries to load mods.

The catch: service-layer code can see the `fmlloader` module (`FMLLoader`, `FMLPaths`) but **not** the game-layer `fmlcore` module (`ModList`, the mod registry, etc.). This constraint drives several design decisions — most notably `BoxyFabricLoaderImpl` having to answer "is mod X loaded?" without access to the real mod list, and the fact that all of Boxy's `@Mod`/game-layer code must be delivered as a *separate* game-layer jar (§8), not from Boxy's own service-layer jar (Forge never scans the service layer for `@Mod`).

---

## 4. What happens at launch (the load-time pipeline)

1. **Forge boots the service layer.** It reads `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` and `…forgespi.locating.IModLocator` from Boxy's jar and instantiates `BoxyTransformationService` + `BoxyModLocator`.
2. **`BoxyModLocator.scanCandidates()` runs.** This is the entry point for everything. It:
   - Finds the Voxy jar in `mods/` (a jar with a `fabric.mod.json` whose `id` is `voxy`, and *no* `mods.toml`).
   - Finds Embeddium (the jar containing `assets/sodium/shaders/include/fog.glsl`).
   - Locates Forge's runtime **SRG** Minecraft jar (the client/joined jar on a client, or the dedicated-server jar — see §12/quirk 21) and builds an **intermediary-named** copy of it (cached) for use as remap classpath.
   - Calls **`VoxyRemapper.remap(...)`**, which produces (and caches) the Forge-ready Voxy jar.
   - Extracts Boxy's **standalone game-layer mod** (`prepareBoxyMod()` → `.boxy/cache/boxy-mod.jar`; see §8).
   - Registers Voxy's metadata with `BoxyFabricLoaderImpl` so the Fabric facade can answer queries about it.
   - **Returns BOTH paths** — the remapped Voxy jar and the standalone Boxy mod — into Forge's discovery stream (`Stream.of(remapped, boxyMod)`).
3. **Forge loads both jars as normal mods.** Voxy's synthesized `mods.toml` declares mod `voxy`; Boxy's standalone jar declares mod `boxy`. Forge applies the mixin configs named in each manifest (`MixinConfigs`), applies the access transformer, and constructs the `@Mod` classes (`VoxyBridgeMod`/`@Mod("voxy")` inside Voxy's jar, `BoxyVss`/`@Mod("boxy")` inside the standalone jar).
4. **Voxy runs.** Its mixins are applied against Embeddium/Minecraft, its renderer initializes, LODs render. Boxy's VSS code wires up its network channel and server/client services (§9).

The expensive remap (step 2) is **cached** under `<gamedir>/.boxy/cache/`, keyed by Voxy's content hash + `REMAP_VERSION`. On subsequent launches it's a no-op unless Voxy or Boxy changed. The standalone `boxy-mod.jar` is re-extracted fresh every launch (it's tiny and tracks the installed Boxy version).

---

## 5. The remap pipeline (`VoxyRemapper`)

This is the heart of the Voxy-loading half of Boxy. `remap()` produces `voxy-srg-<REMAP_VERSION>-<hash>.jar` in two passes:

### Pass 1 — `runTinyRemapper()` (bytecode)
Runs **tiny-remapper 0.10.3** with the `intermediary → SRG` mapping (from `BoxyMappings`) and the **`MixinExtension`**. The MixinExtension is essential: Voxy's mixins were *statically* remapped to intermediary by Fabric Loom at build time, so their `@At`/`@Inject` selector strings contain intermediary names that must be remapped too. **The intermediary Minecraft jar is on the classpath** so tiny-remapper can resolve inherited members and mixin targets — without it, MC references (and mixin `@Shadow`s) are left intermediary and blow up at runtime (this is the failure mode behind quirks 21 & 22).

tiny-remapper is **shaded** (relocated to `com.golem.boxy.libs.tinyremapper`) into Boxy's jar so the service layer can use it at runtime without colliding with any other mod that bundles it.

### Pass 2 — `assemble()` (everything else)
Re-zips the remapped jar with these transformations:

1. **Copy entries**, except those we replace/drop. Two entries get special handling mid-copy:
   - **Mixin config JSONs** → `rewriteMixinConfig()` (strip incompatible mixins, add Boxy's chunky mixins).
   - **Mixin class files** → `MixinSelectorRemapper.fix()` (fix leftover intermediary names in selector strings).
2. **Rewrite the manifest** — add the `MixinConfigs` attribute (this is how Forge 1.20.1 actually discovers mixin configs), keep `Multi-Release`, drop Fabric keys.
3. **Synthesize `META-INF/mods.toml`** from `fabric.mod.json` (`VoxyModsToml`) — declares **only** mod `voxy` now (the `boxy` entry moved to the standalone mod, §8).
4. **Convert `voxy.accesswidener` → `META-INF/accesstransformer.cfg`** (`AccessWidenerToAt`).
   - **4b.** Synthesize a `pack.mcmeta` so Forge registers Voxy's assets (shaders/lang/textures) as a resource pack.
5. **Flatten Voxy's bundled libraries** (`META-INF/jars/*.jar`: rocksdb, lwjgl-lmdb/zstd, jedis, lz4, xz, commons-pool2) into the jar root — Forge doesn't read Fabric's nested-jar format.
6. **Copy Sodium shader assets** (`assets/sodium/**`) from Embeddium into Voxy's jar (see *Module isolation* in §12).
7. **Merge Boxy's command-mod classes** (the `cmd` bundle, already SRG-reobfuscated) so the `@Mod("voxy")` shim + chunky mixins live on the game layer **inside Voxy's jar** (§8).

> Note: Boxy's *own* game-layer mod (BoxyVss + the entire VSS port) is **not** part of this pipeline — Voxy's jar is left as pristine as possible. That code is a separate, self-contained jar the locator returns alongside (§8).

---

## 6. The mapping composition (`BoxyMappings`)

Boxy bundles three mapping files in `src/main/resources/boxy/mappings/` and composes them at runtime:

| File | Provides | Source |
|---|---|---|
| `intermediary.tiny` | `intermediary ↔ obf` | Fabric's intermediary mappings for 1.20.1 |
| `obf-srg.tsrg` | `obf ↔ SRG` | Forge's `extractSrg` output |
| `official-srg.tsrg` | `official ↔ SRG` | Forge's `createMcpToSrg` output |

The main mapping, **`intermediary → SRG`**, is built by joining `intermediary↔obf` with `obf↔srg` on the shared **obf** pivot.

### The functional-interface (SAM) fallback — important and non-obvious
Fabric intermediary **does not rename** the single-abstract-method of certain functional interfaces; it leaves them with their **official** names. The canonical example is `BlockColor.getColor`, which Voxy calls but which intermediary never gave a `method_NNN` id. After `intermediary→SRG` via the obf pivot, such a call stays `getColor` and crashes at runtime with `NoSuchMethodError`.

The fix lives in `BoxyMappings.emit()`: for every method in `official-srg.tsrg`, if intermediary did **not** rename it (tracked by the global `tinySrgNames` set), add an extra `official-name → SRG` entry. The `tinySrgNames` filter is critical — without it, the fallback also rewrites methods intermediary *did* rename (and inherited methods), producing "unfixable conflicts" and mis-targeted mixins (see the quirks catalog, #11).

`BoxyMappings` also exposes:
- `classMap()` — `intermediary class → SRG class` (for the selector remapper).
- `memberNameMap()` — `intermediary member name → SRG member name`. Member ids (`method_NNN`/`field_NNN`) are **globally unique**, so a name-only map is safe and is what lets the selector remapper fix third-party mixin selectors.

---

## 7. Mixins — the separate problems

Mixins caused more trouble than anything else. There are several distinct issues, each with its own fix:

1. **Discovery.** Forge 1.20.1 does **not** reliably honor mixin configs declared in `mods.toml` `[[mixins]]`. It *does* honor the **`MixinConfigs` manifest attribute** (this is how Embeddium and other Forge mods register theirs). So `buildManifest()` writes that attribute on Voxy's jar; **Boxy's own standalone mod jar likewise sets `MixinConfigs: boxy.mixins.json`** in its manifest. `VoxyModsToml` deliberately does **not** emit `[[mixins]]`.

2. **Statically-baked selectors.** Loom baked intermediary names into Voxy's mixin annotation strings at build time. tiny-remapper's `MixinExtension` remaps these *only for mixins targeting mapped (Minecraft) classes*. Voxy's `sodium.*` mixins target **Embeddium** classes, and its MixinExtras `@WrapMethod`/`@WrapOperation` selectors aren't covered either — so intermediary tokens survive. **`MixinSelectorRemapper`** is an ASM pass that walks every mixin class's annotations and rewrites leftover `net/minecraft/class_NNN`, `method_NNN`, and `field_NNN` tokens using `classMap()` + `memberNameMap()`.

3. **Incompatible mixins.** Voxy mixins that can't apply are stripped from the configs by `rewriteMixinConfig()` (see `INCOMPATIBLE_MIXINS`):
   - `chunky.MixinFabricWorld` — targets the Fabric-only `FabricWorld`; Boxy ships a Forge equivalent instead. (This one is genuinely Forge-incompatible and stays stripped.)
   - `minecraft.MixinWindow` — **was** stripped (it `@Inject @At("INVOKE")`s into a **constructor**, which Forge's stock **Mixin 0.8.5** rejects), but is **re-enabled as of v36**: the bundled Mixin upgrade (§12 shaders / quirk 35) accepts constructor INVOKE injection, the same fix that makes the `iris.*` mixins work. It only sets render-thread priority, so this is parity, not a fix for a visible bug.

4. **Boxy's own mixins live in two places.**
   - **Inside Voxy's jar** (added to Voxy's `common.voxy.mixins.json` by `rewriteMixinConfig()`): `chunky.MixinForgeWorld` and `chunky.MixinVoxelIngestStarlight` (§10). These are Voxy-coupled, so they ride along in Voxy's jar.
   - **In Boxy's own `boxy.mixins.json`** (shipped in the standalone mod, §8): the VSS server hooks `AccessorChunkMap` + `ChunkMapSaveHook` + the edit-gated `MixinServerLevelLiveDirty` (§9); the distant-entity server hook `MixinChunkMapTrackedEntity`; and the client-side distant-entity mixins `AccessorClientLevel`, `MixinClientPacketListenerServerPos`, `MixinEntityRenderDistance`, `MixinLevelRendererDistantEntities`, the ReplayMod-compatibility guard `MixinEntityForeignTickGuard`, plus the optional texture-mipmap trio `MixinAbstractTexture` / `MixinSimpleTexture` / `MixinHttpTexture` (all §11); and the Voxy render-pipeline compat mixins `MixinVoxyDepthStateRestore` (Oculus depth-state re-sync, §12) and `MixinVoxyStencilMaskFix` (restores the GL stencil write mask so a mod like ModularUI can't no-op Voxy's LOD-mask stencil clear, quirk 41); and the Voxy performance mixins `MixinVoxyModelBakeryWake` + `MixinVoxyMapperBiomeCache` (§12 *Voxy performance mixins*). This config is Boxy's own and never touches Voxy's.

5. **Hand-written SRG + `remap = false`.** Boxy's injected mixins are reobfuscated official→SRG at build time, but **mixin annotation strings are not** rewritten by that reobf. So any `@At`/`method`/`@Accessor`/`@Invoker` target string must be written in **SRG by hand** (e.g. `m_140049_`, `f_140133_`) with `remap = false` on the `@Mixin`. Find SRG names in `boxy/mappings/official-srg.tsrg`.

6. **Don't `@Invoker` an inherited method on a subclass mixin.** A required `@Invoker` whose target method is declared on a *superclass* (not the `@Mixin` target) can fail "method not found in target," aborting the whole class transform — fatal if it's a class loaded during world creation. The VSS disk reader hit this and now uses reflection instead (quirk 23).

---

## 8. Game-layer injection: the `cmd` bundle **and** the standalone Boxy mod

Voxy has **no `@Mod` class of its own** (it's a Fabric mod), and Boxy's own jar is **service-layer** (Forge won't scan it for `@Mod`). So all of Boxy's game-layer `@Mod`/Forge-event/mixin code must be delivered as game-layer class files some other way. Boxy uses **two** mechanisms, for two different reasons:

### (a) The `cmd` bundle — *merged into Voxy's jar*
For code that must be **the `voxy` mod** or live in **Voxy's mixin config**. A Forge `@Mod` class must live in the ModFile that declares that mod; Voxy's mixin config classes must live in Voxy's jar. So:

| Class | Lives in | Purpose |
|---|---|---|
| `VoxyBridgeMod` | Voxy's jar | `@Mod("voxy")` — gives Voxy the `@Mod` class it lacks (quirk 1); registers the `/voxy` client command. |
| `BoxyCommands` | Voxy's jar | The actual `/voxy` command tree. |
| `MixinForgeWorld`, `MixinVoxelIngestStarlight` | Voxy's jar | Chunky + Starlight integration, added to Voxy's `common.voxy.mixins.json` (§10). |

### (b) The standalone Boxy mod — *returned as its own ModFile*
For **everything Boxy owns**: the VSS port, the server-side spawn ingest, and Boxy's own mixin config. This is a **complete, self-contained Forge mod** (its own `mods.toml` declaring mod `boxy`, its own `boxy.mixins.json`, its own `MixinConfigs` manifest) that `BoxyModLocator` hands to Forge as a second ModFile (§4). It depends on Voxy (`ordering = AFTER`, `mandatory = true`) and calls into it at runtime; **Voxy's jar is left pristine.** This is also what makes "Boxy" appear in the Forge mods list (the old `BoxyInfoMod` is gone).

| Class | In standalone mod | Purpose |
|---|---|---|
| `BoxyVss` | `@Mod("boxy")` | Entry point: registers the network channel + server/client services (§9). |
| `BoxyServerIngest` | (event subscriber) | Server-side spawn-area ingest (§10). |
| the `vss.**` packages | — | The whole Voxy Server Side port (§9). |
| `AccessorChunkMap`, `ChunkMapSaveHook`, `MixinServerLevelLiveDirty` | (mixins) | VSS server hooks, in `boxy.mixins.json` (§9). |

### The build-time chicken-and-egg (applies to both)
These classes call Minecraft, so they need to be **SRG** at runtime — yet Boxy writes its source in **official/Mojmap** names, and a runtime tiny-remapper pass can't do `official→SRG` for them (no official Minecraft on the classpath then). Both bundles are therefore reobfuscated at **build time** by ForgeGradle (which has the full MC classpath, so it resolves inherited members correctly), then staged as **non-`.jar`** binaries inside Boxy's jar:

```
sourceSets.main ─► cmdJar / boxyModJar (Jar)   cmdJar:     com/golem/boxy/cmd/**  + chunky mixins
   (compiled in        │                        boxyModJar: com/golem/boxy/vss/** + src/boxymod/resources
    official names)    ▼                                    (its own mods.toml + boxy.mixins.json + MixinConfigs manifest)
              reobf{cmdJar}/reobf{boxyModJar}   ForgeGradle reobf: official → SRG
                       │
                       ▼
              stageCmdJar / stageBoxyModJar     → boxy/voxy-cmd.libzip   (merged into Voxy's jar at runtime)
                       │ (Copy)                 → boxy/boxy-mod.libzip    (returned as its own ModFile at runtime)
                       ▼
              processResources                  bundled into Boxy.jar as verbatim binaries
```

At runtime, `VoxyRemapper.prepareCommandMod()` extracts `voxy-cmd.libzip` and `mergeClasses()` drops its classes into the remapped Voxy jar; `BoxyModLocator.prepareBoxyMod()` extracts `boxy-mod.libzip` to `.boxy/cache/boxy-mod.jar` and returns it whole.

The `.libzip` extension is load-bearing: the Shadow plugin merges anything ending in `.jar` from a resource directory, which would corrupt the nested jar. `shadowJar` also **excludes** `com/golem/boxy/cmd/**`, the chunky mixin package, **and `com/golem/boxy/vss/**`** from Boxy's own module so those classes exist *only* inside the bundled `.libzip`s.

### The JPMS module-name trap (quirk 20)
Because the standalone mod's id is `boxy`, Forge names its JPMS module `boxy` (mod module names come from the mod id). Boxy's **service-layer jar** would otherwise also be module `boxy` (derived from its filename) → two modules named `boxy` → `module threadly reads more than one module named boxy` at launch. Fix: the service/shadow jar manifest sets **`Automatic-Module-Name: boxyloader`** (internal plumbing; the mod id / displayed name stays `boxy`). **This only holds while `META-INF/MANIFEST.MF` stays the first jar entry** — securejarhandler ignores `Automatic-Module-Name` if the manifest isn't early and reverts to the filename `boxy`. The `embedMixinUpgrade` step (§12) must therefore preserve manifest ordering (quirk 37).

---

## 9. Voxy Server Side (the server-streaming port)

A from-scratch reimplementation of the **Voxy Server Side** addon (originally Fabric, MC 1.21.11) for Forge 1.20.1. It lets a **server** stream serialized distant-terrain LOD columns to **clients**, which feed them into Voxy — so players see far terrain a server generated/holds, even terrain they never walked through. It lives entirely in the standalone Boxy mod (`com.golem.boxy.vss.**`); **Voxy's jar is untouched.** Confirmed working on a dedicated server; server↔client and singleplayer-loopback both supported.

### Layout
```
com/golem/boxy/vss/
  BoxyVss              @Mod("boxy"): registers the channel + server/client services
  BoxyServerIngest     server-side spawn-area ingest burst (§10)
  net/VssChannels      the Forge SimpleChannel + 8 message types + send helpers
  payloads/            8 FriendlyByteBuf POJOs (Handshake/BatchChunkRequest/Cancel/BandwidthUpdate C2S;
                       SessionConfig/BatchResponse/DirtyColumns/VoxelColumn S2C) + VssPayload
  server/              RequestProcessingService, ChunkDiskReader, ChunkGenerationService,
                       ForgeOffThreadProcessor, SectionSerializer, NbtSectionSerializer,
                       DirtyColumnBroadcaster, PlayerRequestState, ServerNetworking
                       (+ FrozenChunkLoader — distant-entity frozen force-load, §11)
  client/              LodRequestManager, SpiralScanner, ClientNetworking, ClientColumnProcessor,
                       ColumnCacheStore, InFlightTracker, RequestQueue, RequestMetrics,
                       VoxyClientBridge, VoxelColumnData, BoxyConfigPage, BoxyOptionStorage (client GUI, §11)
                       (+ ClientEntitySync, DistantEntityTicker, DistantEntityServerPos, BoxyMipmappable
                        — distant entities, §11)
  common/              platform-agnostic engine, ported ~verbatim: processing/* (IncomingRequestRouter,
                       OffThreadProcessor, DedupTracker, Abstract{PlayerRequestState,ChunkDiskReader},
                       RateLimiterSet, ConcurrencyLimiter, SendActionBatcher, …), voxel/ColumnTimestampCache,
                       tracking/DirtyColumnTracker, VSSConstants, VSSLogger, PositionUtil, VssMath,
                       TrackedEntityTypes (§11)
  config/              JsonConfig + VSSServerConfig + VSSClientConfig (Gson, in FMLPaths.CONFIGDIR)
  mixin/               VSS: AccessorChunkMap (@Accessor level), ChunkMapSaveHook (@Inject save),
                       MixinServerLevelLiveDirty (@Inject sendBlockUpdated — edit-gated live dirty sync).
                       Distant entities (§11): MixinChunkMapTrackedEntity (server); AccessorClientLevel,
                       MixinClientPacketListenerServerPos, MixinEntityRenderDistance,
                       MixinLevelRendererDistantEntities, MixinEntityForeignTickGuard (ReplayMod compat)
                       + texture-mipmap trio
                       MixinAbstractTexture/MixinSimpleTexture/MixinHttpTexture (client).
                       Voxy render-pipeline compat (§12): MixinVoxyDepthStateRestore (Oculus depth re-sync),
                       MixinVoxyStencilMaskFix (restores stencil write mask so ModularUI etc. don't cull all LODs, quirk 41).
                       Client settings GUI (§11): MixinEmbeddiumOptionsGUI (adds Boxy's page to Embeddium's SodiumOptionsGUI)
```

### Porting strategy (the three rules that made it tractable)
1. **The `common` engine is pure Java** (no MC imports) → ported verbatim, just package-renamed. Java-21-isms were rewritten for Java 17 (`Math.clamp` → `VssMath.clamp`; a type-pattern switch unrolled).
2. **The platform layer is written in Mojmap with direct calls** — reobf maps MC *and Voxy* names at build time, so it calls `VoxelIngestService.rawIngest(...)`, `level.getChunkSource()`, etc. directly (no reflection, no hand-SRG), exactly like `BoxyServerIngest`. The only hand-SRG is in mixin annotation strings (§7.5) and one reflection call (the disk read, quirk 23).
3. **The Voxy hook points match 1.20.1 exactly**, so the client bridge is near-verbatim: `WorldIdentifier.of(Level)`, `VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection, x, y, z, DataLayer, DataLayer)`, `VoxyConfig.CONFIG.sectionRenderDistance`.

### Networking (`VssChannels`)
One Forge `SimpleChannel` named `boxy:vss` with 8 indexed message types. It is built with `NetworkRegistry.acceptMissingOr(...)` so a Boxy server never rejects a vanilla/non-Boxy client (and vice-versa) — like a Fabric optional channel. Capability negotiation is done at the app layer: on client login the client sends `HandshakeC2SPayload`; if the server has VSS it replies `SessionConfigS2CPayload` (enabled flag + limits). Payloads carry the *same* `FriendlyByteBuf` byte layout as the original VSS; only the registration/dispatch is Forge-native (the 1.20.5+ `CustomPacketPayload`/`StreamCodec` API doesn't exist on 1.20.1). Handlers are delivered on the main thread (`consumerMainThread`) and routed to the installed `ServerHandler`/`ClientHandler`.

### Server side (no Voxy instance required)
The server half needs only world/chunk access, so it runs even on a dedicated server where Voxy's renderer never initializes.
- **`ServerNetworking`** bootstraps on `ServerStartedEvent` (covers dedicated **and** integrated servers — VSS's LAN-defer was dropped) and ticks on `ServerTickEvent` END; per-player sessions activate only when a client handshakes.
- **`RequestProcessingService`** (the orchestrator) each tick: polls finished generations, probes already-loaded chunks (`getChunkNow` → `SectionSerializer`), posts a snapshot to the off-thread processor, drains responses, and flushes per-player send queues under a bandwidth budget.
- **`ChunkDiskReader`** serves already-generated terrain straight off the region files **without loading it into the live world**, on a small thread pool. The protected `ChunkStorage.read(ChunkPos)` is reached by **reflection** (SRG `m_223454_`, graceful if C2ME's reworked I/O changes it — quirk 23). `NbtSectionSerializer` rebuilds the block-state/biome `PalettedContainer`s inline the way vanilla `ChunkSerializer.read` does (the 1.21 codec-factory class doesn't exist on 1.20.1).
- **`ChunkGenerationService`** generates not-yet-generated chunks on demand: a `TicketType` + `addRegionTicket` forces generation to `FULL`, then it polls `getChunkNow` each tick and releases the ticket (concurrency-limited per player + globally).
- **Dirty-column sync** keeps streamed LODs fresh. Two hooks mark a column dirty (both `require = 0` so they degrade instead of crashing): `MixinServerLevelLiveDirty` (`@Inject` on `ServerLevel.sendBlockUpdated` / `m_7260_`) marks it the **instant a block changes** (edit-gated — fires for player edits, redstone, pistons, fluids, growth, but not on chunk load), and `ChunkMapSaveHook` (`@Inject` on `ChunkMap.save` / `m_140258_`) marks it on save as a backstop. `DirtyColumnBroadcaster` then pushes `DirtyColumnsS2CPayload` to in-range players every `dirtyBroadcastIntervalSeconds` (default 2s); the client re-requests and the in-memory probe serves the fresh column. So a change to a **loaded** distant chunk shows up within the interval + the re-request round-trip (~3–4s), not at save granularity. `liveDirtyUpdates` (server config, default on) toggles the edit-gated path. Only loaded chunks can change, so unloaded distant terrain still needs a player / forceload / Chunky to be observable; `/save-all` still forces a refresh.

### Client side (needs Voxy)
- **`ClientNetworking`** (Dist.CLIENT only) **always** sends the handshake on `ClientPlayerNetworkEvent.LoggingIn` — so the server's session config (including its distant-entity list, §11) syncs even when this client has LOD terrain streaming off — drives `LodRequestManager.tick()` on `ClientTickEvent`, and cleans up on logout. `receiveServerLods` now gates only the LOD-terrain request manager (created in `handleSessionConfig`), **not** the handshake.
- **`LodRequestManager` + `SpiralScanner`** scan outward ring-by-ring from the player (out to the effective LOD distance = `min(server, client, Voxy's sectionRenderDistance×32)`, default cap 256 chunks), skipping vanilla-rendered chunks, and batch-request the columns they lack (or that went dirty), with in-flight tracking, rate/concurrency limits, backpressure, and a persistent per-(server,dimension) **timestamp cache** (`ColumnCacheStore`) so unchanged columns aren't re-downloaded across sessions.
- **`ClientColumnProcessor`** deserializes received columns (off-thread by default) — `new LevelChunkSection(biomeRegistry)` + `read(buf)` + `DataLayer`s — and hands them to **`VoxyClientBridge`**, which calls `VoxelIngestService.rawIngest(WorldIdentifier.of(level), section, …)` per section. This is the one place VSS touches Voxy. On an **update** (a re-sync of a column the client already had) it also ingests empty air sections for the sub-chunks the payload omitted, so a sub-chunk that emptied since — the server drops all-air sections — doesn't keep showing stale LOD (quirk 32).

### Scope notes
- **Boxy↔Boxy only.** The wire format encodes 1.20.1 block-state palette IDs; it is *not* compatible with the real 1.21 VSS. Explicit non-goal.
- **Deferred** (not yet ported): the `/vss` command tree and the benchmark/diagnostics tooling. The JSON server/client config **is** ported (`config/vss-*.json`), and the **client** config is now editable in-game via a Boxy page in Embeddium's Video Settings (§11 *Client settings GUI*). A server-config / ModMenu GUI is still deferred.

---

## 10. Boxy's gameplay patches (the injected mixins & ingest)

These give Boxy feature-parity (and a bit more) with Voxy's Fabric integrations. They are the most "gameplay-facing" code Boxy ships outside the VSS port.

### `MixinForgeWorld` — Chunky auto-ingest  *(in Voxy's jar)*
Voxy's Fabric build auto-ingests chunks generated by [Chunky](https://modrinth.com/plugin/chunky) via a mixin on Chunky's `FabricWorld`. Boxy ships the Forge equivalent targeting `org.popcraft.chunky.platform.ForgeWorld`. It wraps the same call Voxy's Fabric mixin wraps — `ChunkHolder.getOrScheduleFuture(ChunkStatus.FULL, …)` (SRG `m_140049_`) — and ingests the fully-lit chunk **inline** from the future result.
- **Inline, not deferred:** an earlier version dispatched the ingest to the next server tick, but by then chunks outside view distance had unloaded and dropped their light. Ingesting inline (while Chunky still holds the chunk ticket) fixes far-chunk ingestion.

### `MixinVoxelIngestStarlight` — Starlight compatibility  *(in Voxy's jar)*
Voxy gates ingest on `lightEngine.getDebugSectionType(...) == LIGHT_AND_DATA`. The **Forge Starlight port** computes light in its own storage and never updates that vanilla debug state, so the check reads "no light" for fully-lit chunks (`isLightCorrect() == true`) and Voxy rejects them. This mixin wraps `getDebugSectionType` (SRG `m_284493_`) inside Voxy's `enqueueIngest` and, when the vanilla query says "no data," falls back to whether the layer actually has a light array (`getLayerListener(layer).getDataLayerData(pos) != null`). It's a **no-op without Starlight**.

### `BoxyServerIngest` — vanilla spawn-area ingest  *(in the standalone Boxy mod)*
None of Voxy's hooks ingest chunks the **server** generated but the player never rendered — most importantly vanilla's spawn-area pre-generation, which runs during world load (before Voxy's client instance even exists) over a radius larger than render distance. A generation-time hook can't catch it. So on `PlayerLoggedInEvent`, Boxy runs a **short, self-terminating burst**: roughly once a second it walks the loaded full chunks (`ChunkMap.getChunks()` via reflection, since it's `protected`) and feeds unseen ones to Voxy's ingest, stopping as soon as two consecutive sweeps find nothing new (hard cap ~15s). It re-arms only on the next world join. It moved from the `cmd` bundle into the standalone mod (its `@Mod.EventBusSubscriber(modid = "boxy")` now matches the mod it lives in). Needs a Voxy client instance, so it no-ops on a dedicated server (unlike the VSS server side).

---

## 11. Distant entity & player rendering

A Boxy-specific feature, independent of the VSS terrain stream (§9) though it reuses VSS's network channel and config plumbing: render **players and configured entity types far beyond vanilla's entity range** — out toward Voxy's LOD distance — by **extending vanilla entity tracking** rather than faking entities. The server sends the *real* entity; the vanilla client renders it with full fidelity (skin layers, armor, held items, sneaking, nameplate). It lives entirely in the standalone Boxy mod (`com.golem.boxy.vss.**`); Voxy's jar is untouched, and the only thing taken from Voxy is the LOD render range.

### Why "extend tracking," not "fake entities"
An earlier approach streamed lightweight fake entities and drew them with the vanilla renderer. They came out hollow — no armor / held items / second skin layer, no sneaking, janky movement. The fix was to stop faking: the server already has the real entity; it merely stops *sending* it once it's past the receiver's view distance. Lift that server-side cap and the client's own culling/render gates, and vanilla does the rest, perfectly. That is the entire feature — four small gates, no synthetic entities.

### The pipeline (four gates, server → client)
Vanilla drops a distant entity at four independent points; the feature lifts each, every one **type-gated** to the configured list so nothing else pays the cost:

| # | Gate | Vanilla behavior | Boxy's lift |
|---|---|---|---|
| 1 | **Server: tracking range** | `ChunkMap$TrackedEntity.updatePlayer` (`m_140497_`) sends an entity only within `min(getEffectiveRange, viewDistance×16)` | `MixinChunkMapTrackedEntity` `@Redirect`s that `Math.min` → `entityTrackingDistanceChunks×16` for configured types |
| 2 | **Client: distance cull** | `Entity.shouldRenderAtSqrDistance` (`m_6783_`) culls past a short range | `MixinEntityRenderDistance` returns "render" for configured types within the effective distance |
| 3 | **Client: chunk-compiled gate** | `LevelRenderer.renderLevel` (`m_109599_`) draws an entity only if `isOutsideBuildHeight ‖ isChunkCompiled(pos)` (`m_202430_`) — distant entities sit in LOD-only (un-compiled) chunks | `MixinLevelRendererDistantEntities` `@WrapOperation`s that `isChunkCompiled` → true for distant entities while the feature is on |
| 4 | **Client: ticking** | only entities in `ClientLevel.tickingEntities` are ticked; a distant entity is *rendered* but never *ticked*, so it freezes mid-move | `DistantEntityTicker` ticks configured types the normal loop isn't (`level.tickNonPassenger`), so movement interpolates |

Gate 3 was the subtle one — the entity was tracked, in the store, and past the distance cull, yet still invisible because its chunk was never compiled (only Voxy LOD lives out there). Gate 4's "already ticked?" discriminator is membership in `tickingEntities` (via `AccessorClientLevel`), **not** `hasChunkAt` — Voxy populates the client chunk cache in the LOD region, so `hasChunkAt` reports present where the entity is in fact not ticking. The ticker skips the local player and passengers (their vehicle ticks them). One wrinkle ticking introduces: a **non-living** entity (a vehicle — e.g. the jet mod) simulates gravity in its client tick, but the collision terrain isn't loaded out here (only Voxy LOD) — so it would free-fall through the world and snap back on each (infrequent) server update. So after ticking such an entity the ticker **pins its Y *and* rotation to the server-authoritative values** — captured from the move/teleport packets by `MixinClientPacketListenerServerPos` into `DistantEntityServerPos` — and zeroes its vertical velocity. (Rotation matters because the vehicle derives its pitch from the bogus vertical motion, so it nose-dives between updates then snaps level.) The *old* rotation fields (`yRotO`/`xRotO`, which the model interpolates **from**) are reset to the previous pinned values too — otherwise the renderer swings between the physics-corrupted old value and the pinned new one every frame (a violent spin). Living entities are lerp-only client-side (no gravity) and need no pin (quirk 30).

### Mod compatibility: foreign re-ticking (`MixinEntityForeignTickGuard`)
The pinning above assumes `DistantEntityTicker` is the **only** thing ticking a distant entity. Some mods
break that: **ReForgedPlay (ReplayMod)** ships `Mixin_FixEntityNotTracking`, which on every entity
move/teleport packet re-ticks (up to 100×) any entity not in `tickingEntities` — the exact set Boxy owns. The
burst runs a non-living entity's gravity with no collision terrain loaded, flinging it; Boxy's pin then
fights it → violent positional thrashing past view distance (quirk 33). `MixinEntityForeignTickGuard`
(client) cancels `Entity.tick`/`rideTick` for a Boxy-managed distant entity unless Boxy's own ticker is the
caller (a static `DistantEntityTicker.isBoxyTicking()` flag set around its `tickNonPassenger` call) — so any
*foreign* re-tick no-ops while Boxy's pinned tick still runs. It targets vanilla `Entity`, so it works
regardless of the other mod's internals.

### Which entities — type list & wildcards (`TrackedEntityTypes`)
The server's `trackedEntityTypes` and the client's `renderedEntityTypes` are lists of entity-type ids (default `["minecraft:player"]`). `TrackedEntityTypes` resolves them to memoized `EntityType` sets, **lazily** (on first query) so modded types registered *after* the config statics load (mod construction) still resolve — eager resolution would silently drop them. An id of the form `modid:*` is a wildcard whitelisting every registered type in that namespace (`minecraft:*`, `create:*`, …). Membership on the hot path is then an O(1) `Set.contains`. Unknown/malformed ids are logged once and skipped.

### Server-synced, never writes the client file (`ClientEntitySync`)
So a player needn't hand-match the server, the server pushes its effective config on the VSS handshake. The client **always** sends that handshake on login — independent of the `receiveServerLods` (LOD-terrain) opt-out — so the server's entity list **always** loads while connected to a Boxy server. The reply `SessionConfigS2CPayload` carries three added fields — `entityRenderEnabled`, `entityRenderDistanceChunks`, `entityTypes` — applied **in memory only**; `vss-client-config.json` is never rewritten. While a sync is active the server's list/distance/enable win (so the client automatically matches the server's whitelist); on logout (`clear()`) the client reverts to its local config. The local `extendEntityRenderDistance` is always a client-side **opt-out** (off locally ⇒ the feature is off regardless of the server — though the list is still synced in memory). The client mixins read `ClientEntitySync.enabled()` / `distanceChunks()`; the synced type set itself lives in `TrackedEntityTypes`.

### Optional frozen force-load (`FrozenChunkLoader`) — off by default
Gates 1–4 only help where the entity's chunk is actually loaded server-side (player vicinity, spawn chunks, Chunky/forceload). To surface entities in otherwise-unloaded terrain, `forceLoadTrackedEntities` keeps a sliding window of **load-only, non-ticking** chunks around each player: a custom in-memory `TicketType` via `addRegionTicket(type, pos, 0, pos)` — radius 0 ⇒ chunk level 33 (`FullChunkStatus.FULL`), so entities load and become `Visibility.TRACKED` (sendable) but do **not** tick (no AI, movement, or mob spawning). Player-owned ticking tickets are never downgraded, so only the ring beyond view distance is frozen. **Cost:** it loads (and *generates* if ungenerated) the whole `forceLoadRadiusChunks` square per player — memory + disk I/O + one-time worldgen scaling radius²×players — and the entities in that ring are static. Hence default-off, radius default 2, hard-capped 64. (Server-side single-entity ticking inside those frozen chunks was explicitly shelved.)

### Distant-texture mipmapping (`MixinAbstractTexture` + Simple/Http) — off by default
At the distances Boxy now renders entities, vanilla's un-mipmapped entity textures (loaded with 0 mip levels; entity RenderTypes filter `NEAREST`/no-mipmap via `TextureStateShard(tex, false, false)`) alias into shimmering garble under heavy minification. `mipmapEntityTextures` (client, **off by default**, restart to apply) rebuilds entity/skin textures with an alpha-aware mip chain (`MipmapGenerator`) and `NEAREST_MIPMAP_LINEAR` filtering — pixel-crisp up close, clean far. `MixinSimpleTexture` (scoped to `textures/entity/**`, so GUI/other textures are untouched) and `MixinHttpTexture` (downloaded skins) route uploads through `MixinAbstractTexture.boxy$uploadMipmapped`, which falls back to a plain upload on non-power-of-two sizes or any error so a texture never breaks; `boxy$keepMipmap` (a `TAIL` inject on `setFilter`, re-applying the mipmap min filter via GL) restores it after the entity RenderType's `TextureStateShard` resets the filter on every bind. It's off because it changes texture handling globally for *all* entities and costs a little VRAM. It addresses texture *aliasing* only — **not** the depth-precision z-fighting that detailed models show at extreme range, which is a separate, unfixable-here limit (§17). The `BoxyMipmappable` interface it relies on must live **outside** the mixin package (quirk 28).

### Client settings GUI (`BoxyConfigPage`) — a page in Embeddium's Video Settings
Mirroring Voxy's own config page, the client mixin `MixinEmbeddiumOptionsGUI` appends a **Boxy** `OptionPage` to Embeddium's `SodiumOptionsGUI` at construction (`<init>` TAIL inject, `remap = false`, `require = 0` — the same hook Voxy uses; both injectors coexist). `BoxyConfigPage` builds the page with Embeddium's `OptionImpl`/`OptionGroup`/`SliderControl`/`TickBoxControl` API, bound to `VSSClientConfig.CONFIG` through `BoxyOptionStorage` (an Embeddium `OptionStorage<VSSClientConfig>` whose `getData()` returns the config and `save()` calls `VSSClientConfig.saveClamped()` = clamp + write JSON). It surfaces every client knob below **except** `renderedEntityTypes` — Embeddium has no list control, and the server's synced list is authoritative anyway, so that one stays JSON-only. Knobs are read live except `mipmapEntityTextures` (flagged `REQUIRES_GAME_RESTART`). Everything Embeddium-facing is **client-only** (the mixin lives in `boxy.mixins.json`'s `client` list; `me.jellysquid.*` is absent on a dedicated server, so these classes never load there) and compiles against Embeddium from Maven (`maven.modrinth:embeddium`, compile-only; §15).

### Config knobs
**Server** (`vss-server-config.json`) — authoritative; synced to clients:

| Key | Default | Meaning |
|---|---|---|
| `extendEntityTracking` | `true` | Master switch for gate 1 + the sync push. |
| `trackedEntityTypes` | `["minecraft:player"]` | Id list; `modid:*` wildcards allowed. |
| `entityTrackingDistanceChunks` | `32` | Tracking range for configured types. |
| `forceLoadTrackedEntities` | `false` | Enable the frozen force-load. |
| `forceLoadRadiusChunks` | `2` (max 64) | Its per-player radius. |

**Client** (`vss-client-config.json`) — local fallback / opt-out; editable in Embeddium's Video Settings → **Boxy** (except `renderedEntityTypes`):

| Key | Default | Meaning |
|---|---|---|
| `extendEntityRenderDistance` | `true` | Local opt-out for gates 2–4 (off ⇒ feature off here). |
| `renderedEntityTypes` | `["minecraft:player"]` | Used only when not synced by a Boxy server. **JSON-only** (no Embeddium list control). |
| `entityRenderDistanceChunks` | `32` | Likewise — local fallback distance. |
| `mipmapEntityTextures` | `false` | Distant-texture mipmapping (restart to apply). |

All distant-entity mixins use hand-SRG targets with `remap = false` and `require = 0` (degrade, don't crash), and each fires a **one-time diagnostic** through `TrackedEntityTypes.diag*` so a server/client log confirms which gates applied.

---

## 12. Other quirks worth knowing

- **Module isolation / shaders.** Under Forge's module system, Voxy's classloader `getResourceAsStream` only sees Voxy's *own* jar. Voxy `#import`s Sodium shaders (`#import <sodium:...>`), so those assets must physically live inside Voxy's jar — hence Pass-2 step 6 copies Embeddium's `assets/sodium/**` in.
- **Shader packs (Oculus).** Voxy ships a full Iris integration — its `iris.*` mixins plus `me.cortex.voxy.client.iris.*` — that renders LODs *through* the active shaderpack. On Forge the Iris fork is **[Oculus](https://modrinth.com/mod/oculus)**, which keeps Iris's `net.irisshaders.iris.*` package and API **verbatim**, so it is to the `iris.*` mixins exactly what Embeddium is to the `sodium.*` mixins (Oculus 1.8.0 ≈ Iris 1.7.x). `BoxyModLocator.findOculusJar()` locates it (marker `net/irisshaders/iris/Iris.class`) and adds it to the remap classpath so tiny-remapper resolves those mixin targets and their inheritance; the remap cache key gains an `-iris` tag so adding/removing Oculus forces a fresh remap. **No asset copy is needed** (unlike Sodium) — Voxy patches the user's shaderpack in place rather than `#import`ing shipped Iris shaders. Two non-obvious points: **(a)** Voxy gates *everything* on `FabricLoader.isModLoaded("iris")`, but Forge's `ModList.isLoaded` checks only real mod ids (`indexedMods`), **not** Oculus's `provides=["iris"]` — so `BoxyFabricLoaderImpl` maps `iris`→`oculus` explicitly, otherwise shaders silently stay off (quirk 34). **(b)** Three iris mixins `@Inject @At("INVOKE")` into a **constructor** (`MixinProgramSet` + `MixinIrisRenderingPipeline` ×2 — `voxy$injectPatchMaker`, `voxy$injectPatchDataStore`, `voxy$injectPipeline`). Forge's runtime Mixin (`0.8.5+Jenkins-b310`) rejects *any* constructor `@Inject` at a non-`RETURN`/`TAIL` point (`InvalidInjectionException: Found @Inject targetting a constructor`) — the same limit that got `minecraft.MixinWindow` stripped (quirk 6). Boxy solves this **the way Sinytra Connector does: by upgrading the runtime Mixin.** It **bundles Mixin Transmogrifier + Fabric Mixin `0.12.11`** inside its own service-layer jar (`fabric-mixin.jar` at the jar root + the Transmogrifier classes — **shaded to `com.golem.boxy.libs.mixintransmog`**, see quirk 39 — listed as a second `ITransformationService`), which swaps Forge's stock Mixin for the Fabric fork at boot. That fork accepts constructor INVOKE injection (after the super-delegate), so Voxy's three callbacks apply **at their original positions** — byte-for-byte the Fabric/Sinytra path. Packaging splits across the two build stages: `fabric-mixin.jar` is injected *after* `reobfShadowJar` (by `embedMixinUpgrade`) so it stays a verbatim nested jar Shadow won't unpack and reobf won't touch; the Transmogrifier **classes** instead go through `shadowJar` so its `relocate` can shade them (quirk 39). **`embedMixinUpgrade` must use the Ant `jar` task, not `ant.zip`:** the zip task reorders entries and displaces `META-INF/MANIFEST.MF`, which silently drops `Automatic-Module-Name: boxyloader` and reintroduces the duplicate-`boxy`-module crash (quirk 37). **Why shade Transmog + wrap it:** two mods bundling Transmog's package in automatic modules JPMS-split-package-crash at the module layer (quirk 39 → shade it), and even past that, modlauncher rejects two services with the same `name()` (`"mixin-transmogrifier"`) — and the upgrade itself (`Unsafe`-swapping the Mixin module's jar source) is **not** idempotent. So Boxy registers a wrapper `BoxyTransmogService` (unique name) that drives the shaded Transmogrifier **only when Connector is absent**, standing down to Connector's when present (quirk 40). Transmog's own `DummyMixinTransformationService`/`serviceLookup` dedup is service-level and runs after `buildLayer`, so it can't help with either. *History:* Boxy originally avoided the Mixin upgrade with an ASM pass (`MixinCtorInjectFix`, quirk 35) that rewrote those `<init>` injects to `@At("TAIL")`. That made them legal but **reordered** Voxy's pipeline build to the end of `IrisRenderingPipeline.<init>` — past `customUniforms.optimise()` (which then pruned Voxy's LOD-only uniforms like `vxModelView` → unshaded LODs, "fixed" by a second workaround `MixinIrisCustomUniforms` cancelling `optimise()`, quirk 36) **and** past the setup-compute dispatch + full fog-color clear. The residual reorder degraded shader lighting and produced a **black band** under shaderpacks. Bundling the upgraded Mixin removes the reorder entirely, so **both `MixinCtorInjectFix` and `MixinIrisCustomUniforms` were deleted** (quirks 35/36 resolved at the source). Absent Oculus, the `iris.*` mixins target classes that aren't present and Mixin drops them — shaders just stay off, exactly as before.
- **The Fabric API facade.** Voxy calls `net.fabricmc.*` APIs that don't exist on Forge. Boxy ships **stub** classes (`net/fabricmc/api/*`, `net/fabricmc/loader/api/*`, `net/fabricmc/fabric/api/...`). `BoxyFabricLoaderImpl` backs `FabricLoader`: it answers `getModContainer("voxy")` **synthetically** (it can't reach the game-layer `ModList` from the service layer), `isModLoaded` reflectively, and `getEnvironmentType`/`getConfigDir` via `FMLLoader`/`FMLPaths`. **These stubs are written under `net.fabricmc.*` in source but are *shaded* at build into `com.golem.boxy.fabricapi.*`** (so the runtime service jar never actually ships `net.fabricmc.*` — see quirk 38 / the Sinytra Connector coexistence note in §13). `shadowJar` relocates Boxy's own classes; `VoxyRemapper.fabricStubRelocation()` relocates Voxy's references to match, so Voxy links against the shaded stubs. Keep those two relocation lists in sync with the stub set.
- **The `commit` custom value.** Voxy's `VoxyCommon` static initializer reads a `commit` string from its mod metadata and throws if it's missing. `VoxyModsToml` emits `[modproperties.voxy] commit = "..."`, and `BoxyFabricLoaderImpl` surfaces it through the facade.
- **`displayTest = "IGNORE_ALL_VERSION"`.** On both the synthesized Voxy `mods.toml` and the standalone Boxy mod — stops Forge's server-version handshake from rejecting them.
- **Finding the SRG MC jar (`findMinecraftJar`).** It must locate Forge's runtime SRG jar to use as the remap classpath. It **strongly prefers the client/joined jar** (`net/minecraft/client/Minecraft.class`, under `…/net/minecraft/client/`) and only falls back to a **dedicated-server jar** (`net/minecraft/server/MinecraftServer.class`, under `…/net/minecraft/server/`) when no client jar exists anywhere. Getting this preference wrong under-remaps mixins on the side that lost its classpath (quirks 21 & 22). It is also **MC-version-aware** (`MC_VERSION = "1.20.1"`, `pickForMcVersion`): some launchers (PrismLauncher) share one `libraries/` dir across multiple Minecraft versions, so the finder filters to the matching-version jar — picking a `1.18.2-srg.jar` there yields a `MixinTransformerError` at connect (quirk 24).
- **JPMS module name.** The service/shadow jar sets `Automatic-Module-Name: boxyloader` so its module doesn't collide with the standalone mod's `boxy` module (quirk 20).
- **Voxy performance mixins.** Two Boxy mixins (in `boxy.mixins.json`, client list) optimize Voxy itself from the outside — both string-target Voxy classes with `remap = false` / `require = 0`, so they degrade to Voxy's original behavior if its internals change, and neither touches the remap pipeline (no `REMAP_VERSION` bump needed). **`MixinVoxyModelBakeryWake`**: Voxy's "Model factory processor" thread polls its bake queue with `Thread.sleep(10)` (Voxy's own `//TODO: replace with LockSupport.park()`); the mixin redirects the sleep to `LockSupport.parkNanos(10ms)` (same worst case) and `unpark`s from `requestBlockBake`/`addBiome`/`shutdown`, so block-model bakes — which gate section meshing via `IdNotYetComputedException` retries — start immediately instead of on the next 10 ms poll tick (faster LOD fill-in). The run-loop lambda is the synthetic `lambda$new$0()V` (verified via javap; re-verify on a Voxy update). **`MixinVoxyMapperBiomeCache`**: `Mapper.getIdForBiome` builds a `namespace:path` string (`unwrapKey().get().location().toString()`) on every call and `WorldConversionFactory.convert` calls it 64×/section during ingest; the mixin fronts it with a per-Mapper identity-keyed `Holder<Biome>` → id cache (holders are canonical per session; a Mapper only appends mappings, so entries can't go stale).
- **Boxy hot-path performance rules.** Boxy's own hooks sit on some of the hottest paths in the game, and the code encodes a few invariants worth preserving. **(a)** The one-time `diag*` methods in `TrackedEntityTypes` are called from per-entity-per-tick/frame handlers, so each `compareAndSet` is guarded by a plain `get()` — a failed CAS still executes a hardware CAS; keep the guard when adding diagnostics. **(b)** `MixinEntityForeignTickGuard` (every entity tick) checks `ClientEntitySync.enabled()` first — its guards are order-independent early-returns, so the feature gate must stay the cheapest, earliest exit. **(c)** The VSS client spiral (`SpiralScanner`) has two rescan strategies, chosen by the client config `incrementalSpiralRescan` (**default off** = the original behaviour, editable live in the Embeddium *Boxy* page). When **off**, chunk moves and dirty broadcasts both `resetScanCounter()` (full restart from ring 0). When **on** (opt-in optimization), chunk moves `recenter(moved)` (confirmed rings stay closed, shifted by the distance moved) and dirty broadcasts re-open only the innermost dirty column's ring (`lowerConfirmedRing`). **Independent of the mode**, request timeouts re-open their ring (`InFlightTracker.timeoutSweep` callback → `onRequestTimedOut`, unconditional) — a safety net that also closes a latent gap the original had (an in-flight column advances the confirmed ring past itself, so a stationary player's timed-out request would otherwise never re-request). If you add more "this column needs another look" state, in incremental mode it must lower the confirmed ring itself (the full restarts that mask this only run in the default mode). Prune passes over the timestamp/dirty/retry/validated collections run only after ≥8 chunks of movement (`PRUNE_HYSTERESIS_CHUNKS`; safe under `getPruneDistance`'s 32-chunk margin) in **both** modes. **(d)** `DirtyColumnTracker.markDirty` (every block update server-wide via `MixinServerLevelLiveDirty`) memoizes the last-marked column and skips the monitor for repeat marks; the memo is invalidated in `drainDirty`, so keep that pairing if the drain path changes.
- **`REMAP_VERSION`.** A string constant in `VoxyRemapper` (currently **`v34`**). It's part of every cache filename. **Bump it whenever you change any remap/patch/classpath logic**, or stale cached jars will be reused.

---

## 13. Quirks & patches catalog (the war stories)

Every problem hit during bring-up, its root cause, and the fix. This is the most useful section when something breaks.

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | `constructed 0 mods [voxy]` | Voxy has no `@Mod` class | `lowcodefml` initially; switched to `javafml` once Boxy injected its own `@Mod` |
| 2 | "Minecraft jar not found" during remap | SRG MC jar isn't on a simple classpath in a real launcher | `findMinecraftJar()` also searches `-DlibraryDirectory` for `*-srg.jar` |
| 3 | "failed to load valid ResourcePackInfo" | Forge needs `pack.mcmeta` to register mod assets | Synthesize one (Pass-2 step 4b) |
| 4 | Voxy mixins silently never apply | Forge 1.20.1 ignores `mods.toml [[mixins]]` | Use the `MixinConfigs` **manifest** attribute instead |
| 5 | `NoClassDefFoundError: ModList` | `BoxyFabricLoaderImpl` (service layer) can't see the game-layer `ModList` | Make `getModContainer` synthetic, `isModLoaded` reflective |
| 6 | `MixinWindow` apply failure | Mixin 0.8.5 rejects `@Inject @At("INVOKE")` into a constructor | Originally stripped `minecraft.MixinWindow` (harmless — thread priority only). **Re-enabled in v36** once the bundled Mixin upgrade (quirk 35) lifted that limit — the upgrade fixes this the same way it fixes the iris mixins, so the strip was reverted (only `chunky.MixinFabricWorld` is still stripped, for an unrelated reason) |
| 7 | Crash: shader `…/fog.glsl` not found | Module isolation: Voxy's classloader only sees its own jar | Copy Embeddium's `assets/sodium/**` into Voxy's jar |
| 8 | `MixinRenderSectionManager` `@Inject <init>(class_638,…)` not found | tiny-remapper doesn't remap selectors of mixins targeting third-party (Embeddium) classes | `MixinSelectorRemapper` (class tokens) |
| 9 | `NoSuchMethodError: BlockColor.getColor` | Fabric intermediary leaves functional-interface SAMs **official**-named | `official→SRG` fallback in `BoxyMappings.emit()` |
| 10 | #9 still crashes after a clean run | Intermediary-MC cache was keyed only by MC hash → stale | Add `REMAP_VERSION` to the cache key |
| 11 | "Unfixable conflicts" / `MixinMinecraft method_18099` not found | The reverse fallback emitted official names for **all** methods, incl. renamed/inherited | Global `tinySrgNames` filter — fallback applies only to truly-unrenamed methods |
| 12 | No config page in video settings | `SodiumOptionsGUI` mixins were stripped unnecessarily | Un-strip — Embeddium keeps that class with a matching constructor |
| 13 | No `/voxy` command | Boxy is service-layer only; its `@Mod` never loads | Inject a reobf'd `@Mod("voxy")` into Voxy's jar |
| 14 | `@WrapMethod method_32796` not found | MixinExtras selectors aren't remapped by MixinExtension | Extend `MixinSelectorRemapper` with the global member-name map |
| 15 | Shadow corrupts the injected cmd jar | Shadow unzips/merges anything ending in `.jar` | Stage it as `voxy-cmd.libzip` + add as a resources srcDir |
| 16 | Can't remap cmd classes `official→SRG` at runtime | No official MC on the classpath at that stage | Reobf the cmd jar at **build time** with ForgeGradle |
| 17 | Chunky: `loaded=true ingested=false` | Several layered causes — see below | See 17a–17d |
| 17a | Fetched chunk was unlit | `getChunkNow` returned a chunk before lighting | Wrap `getOrScheduleFuture(FULL)` and take the lit chunk from the future |
| 17b | Ingest ran on a C2ME worker thread | C2ME completes the chunk future off the main thread | (interim) hop to server thread; later replaced by inline ingest (17d) |
| 17c | Still `ingested=false`, `lit=0`, `lightCorrect=true` | **Forge Starlight** doesn't populate vanilla's light debug state | `MixinVoxelIngestStarlight` trusts `getDataLayerData` instead |
| 17d | Far (out-of-view-distance) chunks not ingested | Deferring ingest to the next tick let far chunks unload first | Ingest **inline** in the future callback (mirrors Voxy) |
| 18 | "Boxy" missing from the mods list | Service-layer jars are excluded from Forge's mod scan | Originally an empty `@Mod("boxy")` in Voxy's jar; now the standalone Boxy mod *is* the `boxy` mod (§8) |
| 19 | Vanilla spawn area never becomes LODs | No hook ingests server-generated chunks the player never rendered | `BoxyServerIngest`: bounded sweep of loaded chunks on world join |
| 20 | `module threadly reads more than one module named boxy` | Standalone mod's module = mod id `boxy`, colliding with the service jar's filename-derived `boxy` module | `Automatic-Module-Name: boxyloader` on the service/shadow jar (§8) |
| 21 | Dedicated server: `NoSuchFieldError: field_41241` in `WorldIdentifier.<clinit>` | `findMinecraftJar()` only matched the **client** jar; a dedicated server has none → empty remap classpath → MC refs (e.g. `Registries.field_41241`) left intermediary | Also locate the dedicated-server SRG jar (`MinecraftServer.class`) (§12) |
| 22 | Client regression: `@Shadow field_11574` not found in `StairBlock` (MixinStairBlock) | Over-broad jar finder picked a **server** jar on a client → Voxy's client-side mixin shadows under-remapped | Strictly **prefer the client/joined jar**; server jar only as a fallback (§12) |
| 23 | World load aborts when a `@Mixin(ChunkMap)` applies | A required `@Invoker` for `ChunkStorage.read` (an *inherited* method) failed "not found in target," failing the class transform | Read via **reflection** (SRG `m_223454_`, graceful under C2ME) instead of an `@Invoker`; `require = 0` on the save hook |
| 24 | `MixinTransformerError` at connect for one player (identical mods) | `findMinecraftJar()` matched a **different-MC-version** SRG jar — a shared launcher `libraries/` held `1.18.2-srg.jar` beside 1.20.1's → Voxy remapped against the wrong MC | Make the finder **MC-version-aware** (`pickForMcVersion`); bump `REMAP_VERSION` to drop the poisoned cache (§12) |
| 25 | Distant tracked player "unloads" past view distance (server tracking confirmed) | `LevelRenderer.renderLevel` only draws an entity whose chunk is **compiled**; distant entities sit in LOD-only chunks | `MixinLevelRendererDistantEntities` wraps `isChunkCompiled` → true for distant entities (§11, gate 3) |
| 26 | Distant entity freezes at the LOD border while moving (stationary syncs fine) | Rendered but not in `tickingEntities`, so never ticked/interpolated; `hasChunkAt` can't discriminate — Voxy fills the client chunk cache there | `DistantEntityTicker` ticks configured types absent from `tickingEntities` (via `AccessorClientLevel`), **not** `hasChunkAt` (§11, gate 4) |
| 27 | New config keys don't appear in an existing config file | `JsonConfig.load` skipped re-saving when the file already parsed, so added fields were never written back | Always `validate()` + `save()` after load, so new defaults merge in |
| 28 | `IllegalClassLoadError: BoxyMipmappable … is in a defined mixin package` at launch | A plain (unregistered) interface lived in `com.golem.boxy.vss.mixin`, which Mixin treats as mixins-only | Move shared interfaces/helpers to `…vss.client` (or `.common`); mixins import them across packages fine |
| 29 | Distant entity textures garble/shimmer when zoomed | Entity textures load with 0 mip levels and entity RenderTypes filter NEAREST/no-mipmap → aliasing under heavy minification | Optional `mipmapEntityTextures`: alpha-aware mip chain + `NEAREST_MIPMAP_LINEAR` (§11; off by default) |
| 30 | Distant vehicles (jet mod) fall through terrain / nose-dive, then snap back | A non-living entity simulates gravity in its **client** tick (no collision terrain out there) and derives its pitch from that bogus motion; the server only corrects every few ticks | After ticking, `DistantEntityTicker` pins non-living entities' Y **and rotation** (incl. the `yRotO`/`xRotO` *old*-rotation render-interp fields, else the model spins violently) to the server values captured by `MixinClientPacketListenerServerPos`; living entities are lerp-only and unaffected (§11, gate 4) |
| 31 | Singleplayer client disconnects ("invalid packet" / "count out of range ~6649") after a long pause → unpause | LOD requests kept being sent while the paused SP server couldn't drain them; on unpause the backlog batched >4096 (`MAX_BATCH_RESPONSES`) responses into one `BatchResponseS2CPayload`, which the decoder rejects | Split the send into ≤`MAX_BATCH_RESPONSES` packets (`RequestProcessingService`); also skip client LOD requests while `Minecraft.isPaused()` so the backlog never builds |
| 32 | Distant-edit desync: a sub-chunk that empties (e.g. breaking a pillar whose top section holds only the pillar) keeps showing stale LOD while the section below it updates correctly | `SectionSerializer` omits all-air sections (a stream-size optimization), so on a dirty re-sync the client is never told the now-empty section should be cleared and its old LOD lingers; the section below still has ground, so it *is* sent and clears | On an **update** (a re-sync of a column the client already had — `LodRequestManager.onColumnReceived` returns `wasCached`), `VoxyClientBridge.ingest` also ingests an empty air section for every sub-chunk in the world height the payload omitted, clearing the stale LOD. Fresh ingests skip this, so the initial stream keeps the size optimization |
| 33 | Distant non-living entities (the jet mod) render "all over the place," switching position extremely fast past view distance — **only with ReForgedPlay (ReplayMod) installed** | ReplayMod's `Mixin_FixEntityNotTracking` re-ticks (up to 100×/packet) every entity not in `ClientLevel.tickingEntities` — the same set `DistantEntityTicker` owns. The burst runs gravity with no collision terrain loaded → the jet free-falls/flies far; Boxy pins it back → thrashing. Living entities have no gravity → unaffected | `MixinEntityForeignTickGuard` cancels `Entity.tick`/`rideTick` (`m_8119_`/`m_6083_`) for a Boxy-managed distant entity unless Boxy's own ticker is the caller (`DistantEntityTicker.isBoxyTicking()` flag); the foreign re-tick loop then sees no movement and stops, while Boxy's pinned tick still runs (§11) |
| 34 | Shaders don't engage even with Oculus installed (no crash, just no shader-integrated LODs) | Voxy gates its Iris integration on `FabricLoader.isModLoaded("iris")`; Forge's `ModList.isLoaded` checks only real mod ids, **not** Oculus's `provides=["iris"]`, so the facade returned `false` | `BoxyFabricLoaderImpl` maps `iris`→`oculus` explicitly; `BoxyModLocator` also puts Oculus on the remap classpath (like Embeddium) so Voxy's `iris.*` mixins resolve and remap (§12) |
| 35 | Crash the instant a shaderpack loads: `InvalidInjectionException: Found @Inject targetting a constructor` on `iris.MixinProgramSet` / `MixinIrisRenderingPipeline` | Forge's runtime Mixin (`0.8.5+Jenkins-b310`) rejects `@Inject @At("INVOKE")` into a constructor outright (the same limit behind quirk 6) — these three iris mixins do exactly that. | **Resolved at the source:** Boxy bundles **Mixin Transmogrifier + Fabric Mixin 0.12.11** (like Connector) to upgrade the runtime Mixin, which accepts constructor INVOKE injection — so the injects apply at their original points, no rewrite needed. *(Superseded the old `MixinCtorInjectFix` `@At("TAIL")` rewrite, which was deleted — see quirk 36 and §12.)* |
| 36 | LODs render **unshaded** under a shaderpack (`'vxModelView' : undeclared identifier`), or — after the v33 `MixinCtorInjectFix` `@At("TAIL")` workaround — shaded but with **inaccurate lighting + a black band** | Both are fallout of running Voxy's pipeline build at the *wrong* point in `IrisRenderingPipeline.<init>`. The `TAIL` rewrite (quirk 35's old fix) reordered it past `customUniforms.optimise()` (which pruned Voxy's LOD-only uniforms → unshaded) **and** past the setup-compute dispatch + full clear (→ the black band). An interim patch `MixinIrisCustomUniforms` cancelled `optimise()` to restore shading, but the deeper reorder remained | **Resolved at the source by quirk 35's fix:** the upgraded Mixin runs Voxy's injects at their original positions (before `optimise()` and the dispatch), exactly as on Fabric/Sinytra — no reorder, correct lighting, no band. Both `MixinCtorInjectFix` *and* `MixinIrisCustomUniforms` were deleted (§12) |
| 37 | After bundling the Mixin upgrade (quirk 35), launch crashes again with `module <X> reads more than one module named boxy` (quirk 20 reappears) — `<X>` is whatever mod the JPMS resolver names first (e.g. `xaerolib`) | `embedMixinUpgrade` used `ant.zip(update:true)` to add `fabric-mixin.jar` + the Transmogrifier classes; Ant's **zip** task writes the added filesets at the **front** and reorders entries, displacing `META-INF/MANIFEST.MF` from index 0/1 to ~index 13. securejarhandler reads `Automatic-Module-Name` only when the manifest is early (JAR spec: manifest first), so the service jar fell back to its **filename**-derived module name `boxy`, colliding with the standalone mod's `boxy` module. (`JarFile`/`.NET` read the manifest via the central directory, so it *looks* present — the position is the trap.) | `embedMixinUpgrade` now uses the Ant **jar** task (always writes `MANIFEST.MF` first), fed the jar's own extracted manifest verbatim via `manifest:` so no attribute is lost. Verify post-build: `META-INF/MANIFEST.MF` at index ≤ 1 (matches Connector) and `Automatic-Module-Name: boxyloader` intact. The production filename `boxy-*.jar` *also* derives to `boxy`, so the manifest is the **only** thing preventing the collision — never let a post-processing step move it |
| 38 | With **Sinytra Connector also installed**, launch crashes: `Module org.sinytra.connector contains package net.fabricmc.loader.api.metadata, module boxyloader exports package net.fabricmc.loader.api.metadata to org.sinytra.connector` (JPMS split package, in `discoverServices → buildLayer`) | Boxy's service jar shipped its **stub Fabric API** in the real `net.fabricmc.*` packages (§12). Connector ships the *same* packages (the real Fabric loader). Both are strict-JPMS **service-layer** modules, so the shared package is supplied twice → resolution fails. It happens while the service layer is *built*, **before any Boxy code runs**, so Boxy can't detect Connector and step aside at runtime — and the stub names can't move (Voxy links against `net.fabricmc.*` by name) | **Shade the stubs into a Boxy-private namespace** `com.golem.boxy.fabricapi.*` so Boxy never claims `net.fabricmc.*` — exactly how tiny-remapper is shaded. `shadowJar` relocates Boxy's own stub classes; `VoxyRemapper.fabricStubRelocation()` relocates Voxy's references to match (`REMAP_VERSION` → v35). Connector already **defers Voxy loading to Boxy** (`ConnectorLocator` is an `IDependencyLocator`, runs after Boxy's `IModLocator`, and skips any mod id already loaded), so Boxy keeps full ownership of Voxy and all features. Verify post-build: **no** `net/fabricmc/*` entries in `boxy-*.jar`; the stubs live under `com/golem/boxy/fabricapi/*` |
| 39 | After quirk 38, with Connector installed the **same** split-package crash on a different package: `… org.sinytra.connector contains package io.github.steelwoolmc.mixintransmog … boxyloader exports … to org.sinytra.connector` | Boxy and Connector **both bundle Mixin Transmogrifier** (§12 shaders) — byte-identically: loose `io.github.steelwoolmc.mixintransmog.*` classes in an automatic module. Two service-layer modules export the same package → split package. | **Shade Transmog too**, into `com.golem.boxy.libs.mixintransmog`. Moved its classes from the post-reobf `embedMixinUpgrade` step into `shadowJar` so Shadow's `relocate` rewrites them. Safe because Transmog drives the upgrade purely by reflection on external modlauncher/Mixin classes (its own package name is irrelevant); `fabric-mixin.jar` still rides at the jar root for `InstrumentationHack`. (Don't register the shaded service directly — see quirk 40.) Verify post-build: **no** `io/github/steelwoolmc/*`; classes under `com/golem/boxy/libs/mixintransmog/` |
| 40 | After quirk 39, with Connector installed: `java.lang.IllegalStateException: Duplicate key mixin-transmogrifier` in `TransformationServicesHandler.discoverServices` | Past the module split, modlauncher collects transformation services into a map keyed by `name()`. Boxy's (shaded) Transmogrifier and Connector's both report `"mixin-transmogrifier"` → duplicate key. And running both anyway is unsafe: the upgrade (`InstrumentationHack.inject()`, in Transmog's static initializer) `Unsafe`-swaps the Mixin module's jar source — **not** idempotent | Don't register the Transmogrifier service directly. Register a thin wrapper **`BoxyTransmogService`** (`name() = "boxy-mixin-transmogrifier"`, unique) that loads + drives the shaded Transmogrifier **only when Connector is absent** (`ModuleLayer.findModule("org.sinytra.connector")` — no false positives, so a standalone Boxy always upgrades). With Connector present it stands down and Connector's Transmogrifier does the upgrade. Verify post-build: the `ITransformationService` service file lists `BoxyTransmogService` (not the Transmogrifier); `com/golem/boxy/libs/mixintransmog/MixinTransformationService.class` is present (the wrapper `Class.forName`s it) |
| 41 | With **ModularUI** installed (or any mod that zeroes the GL stencil write mask), **Voxy LODs never render** — no crash, no GL error; the pipeline runs, terrain streams, the hierarchy traverses (`renderSections` > 0), but **0 sections survive the occlusion cull** (`visibleSections == 0`) | Voxy masks LODs to the beyond-vanilla region via the stencil buffer. `AbstractRenderPipeline.initDepthStencil` clears its framebuffer stencil to 1 (`glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, 1.0f, 1)`) **before** it sets `glStencilMask(0xFF)` a few lines later. That clear **honours the stencil write mask**, and ModularUI's per-frame `Stencil.reset()` leaves the write mask at `0x00`; the state persists into Voxy's pipeline → the clear no-ops → the LOD-region mask never gets its `1`s → the rasterized occlusion cull (`cull/raster.frag`, `early_fragment_tests` + stencil `EQUAL 1`) rejects every AABB. **Independent of `RenderTarget.enableStencil()`** — it's the *write mask*, not the attachment or the test state — which is why detaching the stencil attachment, neutralising `GL_STENCIL_TEST`, and even skipping `enableStencil` entirely all failed to fix it. (Bisected via `RenderStatistics` counters: `traversalCount`/`renderSections` healthy, `visibleSections`/`quadCount` = 0; a manual stencil-clear-to-1 before the cull restored them.) | **`MixinVoxyStencilMaskFix`** (`@Inject` HEAD of `initDepthStencil`, `remap = false`, `require = 0`) forces `glStencilMask(0xFF)` before Voxy's clear, so the clear lands and Voxy's own mask-build runs correctly. A strict no-op when the mask is already `0xFF` (i.e. without a mask-zeroing mod), so Voxy is unchanged in the common case |
| 42 | With **Radium** (the Forge Lithium port) installed, a non-stop error storm: every `Ingest service` job throws `IllegalStateException: Unknown palette type: …LithiumHashPalette@…` (`WorldConversionFactory.setupLocalPalette`), so **no terrain ever ingests** into Voxy | Radium replaces vanilla chunk palettes with `me.jellysquid.mods.lithium.common.world.chunk.LithiumHashPalette`. Voxy has a fast-path for it, but gates it behind `LITHIUM_INSTALLED = FabricLoader.isModLoaded("lithium")`; Radium's mod id is `radium` and Forge's `ModList.isLoaded` ignores its `provides=["lithium"]` alias (same root cause as quirk 34), so the gate is `false` → the palette falls through to the `Unknown palette type` throw on every job | `BoxyFabricLoaderImpl` maps `lithium`→`radium` explicitly (mirrors the `iris`→`oculus` alias). No remap/classpath change needed — Radium ships the `LithiumHashPalette` class Voxy already links against, so once the gate opens Voxy's own fast-path handles it; this is a pure runtime-facade fix, `REMAP_VERSION` unchanged |

---

## 14. File-by-file reference

### `com.golem.boxy.loader` (service layer)
| File | Responsibility |
|---|---|
| `BoxyTransformationService` | `ITransformationService` SPI entry; promotes Boxy to the service layer. Does no transforming itself. |
| `BoxyModLocator` | `IModLocator`. Finds Voxy + Embeddium + **Oculus** + the SRG MC jar, drives the remap, **and returns Boxy's standalone game-layer mod** as a second ModFile. The orchestrator. |
| `VoxyRemapper` | The two-pass remap + assemble pipeline. Holds `REMAP_VERSION` and `INCOMPATIBLE_MIXINS`. |
| `BoxyMappings` | Parses + composes the three mapping files; the `intermediary→SRG` map, the SAM fallback, `classMap`, `memberNameMap`. |
| `MixinSelectorRemapper` | ASM pass that fixes leftover intermediary tokens in mixin annotation strings. |
| `VoxyModsToml` | Synthesizes Voxy's `mods.toml` (mod `voxy` only) from `fabric.mod.json`. |
| `AccessWidenerToAt` | Converts `voxy.accesswidener` → `accesstransformer.cfg` (remapping members to SRG). |
| `BoxyFabricLoaderImpl` | Backs the Fabric `FabricLoader` facade (synthetic mod container, reflective `isModLoaded` — with the `iris`→`oculus` alias for shader support and the `lithium`→`radium` alias for the Lithium palette fast-path, env/config dirs). |

### `com.golem.boxy.cmd` (merged into Voxy's jar, SRG-reobf'd)
| File | Responsibility |
|---|---|
| `VoxyBridgeMod` | `@Mod("voxy")` — gives Voxy a `@Mod` class; registers the `/voxy` command. |
| `BoxyCommands` | The actual `/voxy` command tree. |

### `com.golem.boxy.vss.**` (the standalone Boxy mod, SRG-reobf'd) — see §9 (terrain) & §11 (entities)
| Package / file | Responsibility |
|---|---|
| `BoxyVss` | `@Mod("boxy")` entry point — registers the channel + server/client services. |
| `BoxyServerIngest` | Server-side spawn-area ingest burst (§10). |
| `net.VssChannels` | The Forge `SimpleChannel` (`boxy:vss`), 8 message types, send/handler plumbing. |
| `payloads.*` | The 8 `FriendlyByteBuf` payload POJOs + `VssPayload` (`SessionConfig` also carries the distant-entity sync, §11). |
| `server.*` | Server orchestrator, disk reader, generation, off-thread processor, serializers, dirty broadcaster, networking bootstrap; `FrozenChunkLoader` (distant-entity frozen force-load, §11). |
| `client.*` | Spiral request manager + queues + metrics, client networking, column processor, disk cache, `VoxyClientBridge` (the Voxy ingest call); `ClientEntitySync`, `DistantEntityTicker`, `DistantEntityServerPos`, `BoxyMipmappable` (distant entities, §11); `BoxyConfigPage` + `BoxyOptionStorage` (the Embeddium client-settings page, §11). |
| `common.*` | Platform-agnostic engine (`processing/`, `voxel/`, `tracking/`), constants, logger, `VssMath`; `TrackedEntityTypes` (entity-id list/wildcard resolver, §11). |
| `config.*` | `JsonConfig` + `VSSServerConfig` + `VSSClientConfig` (the latter two also hold the distant-entity knobs, §11). |
| `mixin.*` (Boxy's own `boxy.mixins.json`) | VSS server hooks `AccessorChunkMap` + `ChunkMapSaveHook` + `MixinServerLevelLiveDirty` (§9). Distant entities (§11): `MixinChunkMapTrackedEntity` (server); `AccessorClientLevel`, `MixinClientPacketListenerServerPos`, `MixinEntityRenderDistance`, `MixinLevelRendererDistantEntities`, `MixinEntityForeignTickGuard`, + texture-mipmap `MixinAbstractTexture`/`MixinSimpleTexture`/`MixinHttpTexture` (client). Shaders (§12): `MixinVoxyDepthStateRestore` (string-targets Voxy's `AbstractRenderPipeline.runPipeline`, re-syncs GL depth-test state after Voxy's pipeline so Oculus's cloud/weather passes don't punch through terrain). Mod compat: `MixinVoxyStencilMaskFix` (string-targets `AbstractRenderPipeline.initDepthStencil`, forces `glStencilMask(0xFF)` before Voxy's LOD-mask stencil clear so a mod that left the write mask at `0x00` — e.g. ModularUI — doesn't silently no-op it and cull all LODs, quirk 41). Client settings GUI (§11): `MixinEmbeddiumOptionsGUI` (appends Boxy's page to Embeddium's `SodiumOptionsGUI`). *(The old `MixinIrisCustomUniforms` was deleted — the bundled Mixin upgrade made it unnecessary, §12/quirk 36.)* |

### `me.cortex.voxy.commonImpl.mixin.chunky` (injected into Voxy's jar)
| File | Responsibility |
|---|---|
| `MixinForgeWorld` | Chunky auto-ingest on Forge. |
| `MixinVoxelIngestStarlight` | Starlight light-gate compatibility. |

### `net.fabricmc.*` (Fabric API stubs)
Minimal stand-ins for the Fabric APIs Voxy references: `api/{ModInitializer, ClientModInitializer, EnvType}`, `loader/api/{FabricLoader, ModContainer, Version, metadata/CustomValue, metadata/ModMetadata}`, `fabric/api/client/command/v2/FabricClientCommandSource`. (Note: VSS networking uses Forge's `SimpleChannel` directly — no Fabric networking stubs are needed.)

### `src/main/resources`
- `META-INF/services/*` — the SPI registrations that put Boxy on the service layer. `…ITransformationService` lists **two** services: `BoxyTransformationService` **and** `io.github.steelwoolmc.mixintransmog.MixinTransformationService` (the bundled Mixin Transmogrifier — see §12 shaders / below).
- `META-INF/mods.toml` — Boxy's *own* (service-layer) metadata. **Vestigial** — Forge doesn't scan the service jar as a mod; the visible "Boxy" entry comes from the standalone mod's `mods.toml` instead.
- `pack.mcmeta` — Boxy's own pack metadata.
- `boxy/mappings/{intermediary.tiny, obf-srg.tsrg, official-srg.tsrg}` — the three mapping files.
- (build-generated) `boxy/voxy-cmd.libzip` — the reobf'd command bundle.
- (build-generated) `boxy/boxy-mod.libzip` — the reobf'd standalone Boxy mod (§8).

### `libs/` (vendored build inputs — see the [README](README.md#dependencies-libs))
Only artifacts **not available on public Maven** live here; everything else (Embeddium, the Fabric-Mixin fork) is a normal Gradle dependency (§15).
- `mixintransmog/` — the **Mixin Transmogrifier** classes (`io.github.steelwoolmc.mixintransmog.*`), **committed**. `shadowJar` shades them into `com.golem.boxy.libs.mixintransmog` so they don't split-package with Connector (quirk 39). *(Same artifacts Sinytra Connector ships — see `Embeddium-Oculus/Connector-1.20.1/gradle.properties`.)*
- `voxy-<ver>-dev.jar` — Voxy's Mojmap dev jar (compile-only, never bundled; §15). **Not committed** (git-ignored) — it isn't on any public Maven, so it's built from [m3t4f1v3/voxy](https://github.com/m3t4f1v3/voxy) and dropped in here; see the [README](README.md#dependencies-libs). The `fabric-mixin.jar` embedded at the production jar's root now comes from Maven (`org.sinytra:sponge-mixin`), not this folder.

### `src/boxymod/resources` (bundled into the standalone mod jar, **not** Boxy's own jar)
- `META-INF/mods.toml` — the **real** "Boxy" mod entry (id `boxy`, depends on `voxy`).
- `boxy.mixins.json` — Boxy's own mixin config: the VSS server hooks (`AccessorChunkMap`, `ChunkMapSaveHook`, `MixinServerLevelLiveDirty`), the distant-entity server/client mixins (§11), the client-settings-page mixin `MixinEmbeddiumOptionsGUI` (§11), and the Voxy render-pipeline compat mixins `MixinVoxyDepthStateRestore` / `MixinVoxyStencilMaskFix` (§12, quirk 41).
- `pack.mcmeta` — the standalone mod's pack metadata.

---

## 15. Building, installing, testing

### Prerequisites
- **JDK 17** is required. ForgeGradle 6 for 1.20.1 is **not** compatible with newer JDKs (25, etc.). Point `JAVA_HOME` at a JDK 17 before building:
  ```bash
  export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17"
  export PATH="$JAVA_HOME/bin:$PATH"
  ```
- Voxy's **Mojmap dev jar** must be at `libs/voxy-<version>-dev.jar` (compile-only; never bundled). It isn't on any public Maven, so build it from [m3t4f1v3/voxy](https://github.com/m3t4f1v3/voxy) and drop it in `libs/` (step-by-step in the [README](README.md#dependencies-libs)). Boxy's `vss.client` code calls into it directly (`VoxelIngestService`, `WorldIdentifier`, `VoxyConfig`).
- **Embeddium** and the **Fabric-Mixin fork** are resolved from Maven automatically — no manual setup. Embeddium (`maven.modrinth:embeddium`, compile-only) is what Boxy's client-settings page (`BoxyConfigPage` + `MixinEmbeddiumOptionsGUI`) compiles against (`me.jellysquid.mods.sodium.client.gui.*`); it isn't remapped (only Embeddium's own API with official MC class types is called), so the production jar works directly and the user's installed Embeddium provides it at runtime. The Fabric-Mixin fork (`org.sinytra:sponge-mixin`, from Sinytra's maven) is injected into the production jar at build time (§12).

### Build
```bash
./gradlew shadowJar
# output: build/libs/boxy-<version>.jar   (the shaded + reobf'd production jar)
```
`shadowJar` bundles the relocated tiny-remapper, the shaded Transmogrifier classes, and **both** staged binaries (`voxy-cmd.libzip` + `boxy-mod.libzip`); `reobfShadowJar` reobfuscates Boxy's own MC-facing service-layer classes to SRG; finally `embedMixinUpgrade` injects the Maven-resolved Fabric-Mixin fork jar at the production jar's root as `fabric-mixin.jar` (§12). The two game-layer bundles are reobf'd separately by `reobf{cmdJar}` / `reobf{boxyModJar}`. The thin `jar` is an intermediate `-slim` artifact that Shadow consumes — don't ship it.

> **Post-build sanity check (guards quirk 37):** confirm `META-INF/MANIFEST.MF` is still the first jar entry and carries `Automatic-Module-Name: boxyloader`. Quick check:
> ```bash
> unzip -l build/libs/boxy-<version>.jar | head            # MANIFEST.MF must be at/near the top
> unzip -p build/libs/boxy-<version>.jar META-INF/MANIFEST.MF | grep Automatic-Module-Name
> ```
> If the manifest has drifted down the archive, the service jar silently becomes module `boxy` and launch dies with `reads more than one module named boxy`.

### Install (manual test against a real instance)
Drop these into the instance's `mods/`: **Boxy**, the **unmodified Voxy** Fabric jar, and **Embeddium**. Optionally MixinExtras, Chunky, Starlight, C2ME, and **Oculus** (for shader-pack support, §12). Then:
```bash
# force a fresh remap and stage the new build
rm -f "<instance>/.boxy/cache/"*.jar
cp build/libs/boxy-<version>.jar "<instance>/mods/"
```
For VSS server testing, repeat on a **dedicated Forge 1.20.1 server** instance (Boxy + Voxy; Embeddium not required server-side).

### Things to verify
- Voxy renders LODs in-world; `/voxy` command exists; Voxy's options appear in Embeddium's video settings.
- **Boxy's own settings page** appears in Embeddium's Video Settings (a **Boxy** tab beside Voxy's); toggling a live knob (e.g. entity render distance) applies without a restart and persists to `config/vss-client-config.json`.
- **Boxy** and **Voxy** both appear in the Forge mods list.
- With Chunky: a generated region becomes LODs (works with C2ME and Starlight).
- Fresh world: spawn-area terrain beyond render distance fills in within ~10s of joining.
- **VSS (server):** a dedicated server boots cleanly (watch for `Boxy: using intermediary Minecraft classpath from server-…-srg.jar` and the VSS service "started" line); a connected client sees distant pre-generated terrain fill in as LODs beyond render distance, then un-generated terrain via on-demand generation.
- **VSS (live dirty sync):** modify a far (server-loaded) chunk → the client's LOD refreshes within a few seconds, edit-gated (no `/save-all` needed; `liveDirtyUpdates` + `dirtyBroadcastIntervalSeconds` tune it). Also break a structure spanning two sub-chunks where the upper one holds only that structure (e.g. a tall pillar) → the upper sub-chunk's LOD clears too, not just the lower (quirk 32).
- **Non-Boxy interop:** a vanilla client can still join the Boxy server, and Boxy can still join a vanilla server (the channel is optional; the handshake just goes unanswered).
- **Distant entities (§11):** with two clients on a server, move one player out past the other's view distance — the remote player still renders with full fidelity (skin layers, armor, held items, sneaking) **and moves smoothly**, not frozen at the border. Add a mob id (or `modid:*`) to `trackedEntityTypes`, restart, and the same holds for that type. Server log: `Boxy tracking redirect ACTIVE`; client log: the render-cull / chunk-bypass / distant-tick one-time diagnostics.
- **Entity config sync:** a client joining a server whose `trackedEntityTypes` / distance differ adopts the server's values — and its own `vss-client-config.json` is **not** rewritten. This now holds **even with `receiveServerLods` off** on the client (the handshake always fires, so the entity list always syncs); the local `extendEntityRenderDistance` opt-out still turns rendering off.
- **Mipmap (opt-in):** with `mipmapEntityTextures: true` (client) + restart, a distant entity's texture is smooth (not garbled) when zoomed; the default `false` leaves vanilla texture handling untouched.
- **Shaders (Oculus, §12):** at boot the log shows **Mixin Transmogrifier** upgrading Mixin to the Fabric `0.12.11` fork, and the client reaches the title screen **without** the `module … reads more than one module named boxy` crash (quirk 37 — if it crashes there, re-run the post-build manifest check above). With **Oculus** installed the client log shows `Boxy: found Oculus (Iris) at … — enabling shader-pack support` and the cached jar is `voxy-srg-v34-iris-<hash>.jar`. Enable a **voxy-aware** shaderpack (e.g. Complementary Reimagined/Unbound, which ship `shaders/world*/voxy*.glsl`) in Oculus's settings → it loads **without** the `InvalidInjectionException` on `iris.MixinProgramSet`/`MixinIrisRenderingPipeline` (quirk 35), the Voxy LOD section shaders compile (**no** `'vxModelView' : undeclared identifier` / `uniforms could not be found` spam — quirk 36), and LODs render lit/shadowed under the pack **with accurate lighting and no black band** (Voxy logs `Creating voxy iris render pipeline`). **Confirmed:** with the manifest fix in place this matches a Sinytra Connector + Embeddium + Oculus instance (the ground-truth reference). Without Oculus, LODs render unshaded exactly as before. Note a non-voxy-aware pack simply renders LODs unshaded (no `voxy.json` → Voxy uses its normal pipeline).

---

## 16. How to update Voxy or change remap logic

- **New Voxy version:** drop the new Fabric jar in `mods/`. The cache is keyed by content hash, so it re-remaps automatically. Replace `libs/voxy-*-dev.jar` if Boxy's source references changed APIs (the `build.gradle` `compileOnly` is a `voxy-*-dev.jar` glob, so no build edit is needed — just swap the file; see the [README](README.md#dependencies-libs)). Check whether Voxy added/removed mixins (re-evaluate `INCOMPATIBLE_MIXINS`) or new Fabric API calls (extend the stubs/facade), and whether the **VSS Voxy hook points** still match (§9: `WorldIdentifier.of`, `VoxelIngestService.rawIngest`, `VoxyConfig.CONFIG.sectionRenderDistance`). If Voxy bumped its **Iris** dependency, confirm the `iris.*` mixin targets still line up with the installed **Oculus** version's `net.irisshaders.iris.*` API (§12) — Boxy puts Oculus on the classpath but does not itself remap Iris names, so a divergent Oculus throws `NoSuchMethod`/`NoSuchField` at shaderpack load.
- **Any change to remap/patch/classpath logic:** **bump `REMAP_VERSION`** in `VoxyRemapper`. This invalidates both the `voxy-srg-*` and `mc-intermediary-*` caches.
- **New SRG `@At`/`@Accessor`/`@Invoker` target in a Boxy mixin:** Boxy's reobf maps method *bodies* but **not** annotation strings, so the target must be written in **SRG by hand** (e.g. `m_140049_`, `f_140133_`). Find the SRG name in `boxy/mappings/official-srg.tsrg`. Keep `remap = false`. Avoid `@Invoker` on inherited methods (quirk 23) — reflect or target the declaring class.
- **Add classes to a game-layer bundle:** put non-mixin classes under `com/golem/boxy/vss/**` (already covered by `boxyModJar`) or `com/golem/boxy/cmd/**`; put new mixins under `com/golem/boxy/vss/mixin/**` and list them in `src/boxymod/resources/boxy.mixins.json`. Don't forget the matching `shadowJar` `exclude`. **Only registered mixins may live in `…vss.mixin`** — a plain class/interface there (even one a mixin implements, e.g. `BoxyMipmappable`) throws `IllegalClassLoadError` at launch; put shared helpers in `…vss.client` / `.common` (quirk 28).
- **Different MC version:** a large undertaking — every bundled mapping, every hardcoded SRG name (injected mixins, `BoxyServerIngest` reflection, the VSS disk-read reflection), and the Mixin/MixinExtras assumptions would need revisiting. Boxy is firmly pinned to **1.20.1**.

---

## 17. Known limitations

- **The renderer is client-only.** Voxy's renderer + `BoxyServerIngest`'s spawn ingest only run where a Voxy client instance exists (singleplayer/integrated, or a connected client). On a dedicated server they no-op.
- **The VSS *server* side does run headless.** It needs only world/chunk access, so a dedicated Forge server streams LODs to clients without a Voxy instance (§9). This is the one place Boxy is *not* purely a client-side concern.
- **VSS dirty-sync is edit-gated (for loaded chunks).** A change to a chunk that is **loaded server-side** propagates within `dirtyBroadcastIntervalSeconds` (default 2s) + the client's re-request round-trip (~3–4s total), via `MixinServerLevelLiveDirty` on `sendBlockUpdated`; `ChunkMapSaveHook` (save-time) is a backstop. The hard limit is that **a chunk must be loaded server-side to change at all** — unloaded distant terrain is inert, so making far edits observable still requires a player nearby, a forceload/Chunky, or spawn chunks. Tunables: `dirtyBroadcastIntervalSeconds`, `liveDirtyUpdates` (toggles the edit-gated path), and `/save-all` (forces a refresh).
- **`BoxyServerIngest` only sees loaded chunks.** Vanilla keeps the spawn-chunk radius loaded, which it covers; terrain vanilla generated and then *unloaded* (beyond that radius) isn't in memory to sweep — use Chunky for those (or, with a client connected to a VSS server, the disk reader serves them).
- **Distant entities need their chunk loaded server-side.** The four render gates (§11) only surface an entity where its chunk is loaded (player vicinity, spawn chunks, Chunky/forceload). To cover empty terrain, enable `forceLoadTrackedEntities` — but that **frozen force-load is costly**: it loads and *generates* a `forceLoadRadiusChunks²` square per player (memory + disk + one-time worldgen), and the entities in it are static (non-ticking). Default off, radius 2.
- **Distant-entity movement is client-side interpolation.** `DistantEntityTicker` advances an entity's position from the server's tracking updates so it moves smoothly; it is not a full server-authoritative simulation of that entity at distance.
- **Entity-texture mipmapping is global and opt-in.** `mipmapEntityTextures` changes texture handling for *all* entities (not just distant ones), costs a little VRAM, and needs a restart; it's off by default.
- **Distant entities z-fight at extreme range.** Vanilla entities share Minecraft's standard depth buffer (Voxy renders its LOD with `glDepthFunc(GL_ALWAYS)` + its own HiZ occlusion, then restores `GL_LEQUAL` — so there's no reversed-Z to join), whose precision degrades with distance². A detailed model (many overlapping faces) more than a few hundred blocks out fights for depth and shimmers as the camera angle changes — roughly clean to ~8–12 chunks, bad by 32. This is a depth-precision limit, **not** a texture one (mipmapping doesn't touch it); the only real cure is a reversed-Z / log depth buffer, which can't be retrofitted under Minecraft + Voxy + Embeddium. The practical lever is to keep `entityRenderDistanceChunks` / `entityTrackingDistanceChunks` in the clean zone.
- **Hardcoded SRG names** in the injected mixins and the `BoxyServerIngest`/disk-read reflection tie Boxy to 1.20.1's SRG.
- **C2ME interaction.** The injected gameplay code reads the light engine off-thread under C2ME; this is safe with Starlight (concurrent-read storage) and matches Voxy's inline-ingest behavior. C2ME also reworks chunk I/O, so the VSS disk-read path is the least-exercised piece there (it fails soft — quirk 23 — falling back to loaded-chunk probing + generation).
- **VSS is Boxy↔Boxy and not wire-compatible with the real (1.21) Voxy Server Side.**

---

## 18. Glossary

- **SRG** — Forge's runtime naming: official class names, obfuscated (`m_`/`f_`) members.
- **Intermediary** — Fabric's stable runtime naming (`class_`/`method_`/`field_`). Voxy's jar uses this.
- **Mojmap / official** — Mojang's deobfuscated names. NeoForge's runtime; Boxy's dev environment.
- **Service layer / plugin layer** — Forge's early classloader layer where jar discovery/transformation happens, before game classes load.
- **Game layer** — the normal mod classloader layer, a child of the service layer. Boxy's standalone mod and the remapped Voxy jar live here.
- **ModFile** — Forge's unit of a discovered mod jar. `BoxyModLocator` returns two: the remapped Voxy jar and Boxy's standalone mod.
- **Access Widener (AW)** — Fabric's mechanism to loosen access modifiers. **Access Transformer (AT)** — Forge's equivalent.
- **Reobf** — reobfuscation; here, ForgeGradle rewriting Boxy's official-named bytecode to SRG at build time.
- **MixinExtension** — tiny-remapper add-on that also remaps mixin annotation selectors during the bytecode pass.
- **SAM** — Single Abstract Method (functional interface). The source of the `getColor` fallback quirk.
- **VSS** — Voxy Server Side: the server→client LOD-streaming protocol Boxy ports (§9).
- **Column** — one chunk's worth of stacked LOD sections; the unit VSS requests, serializes, and streams.

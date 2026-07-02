# Boxy

**Boxy runs [Voxy](https://modrinth.com/mod/voxy) — a Fabric Level-of-Detail (LOD) terrain renderer —
on Minecraft Forge 1.20.1, without modifying Voxy's jar.** Drop the unmodified, Fabric-built Voxy jar
into `mods/` next to Boxy, and Boxy does everything needed at launch to make Forge load and run it: it
remaps Voxy's bytecode from Fabric's naming to Forge's, translates its metadata, bridges the Fabric
APIs it calls, and patches a handful of incompatibilities. Voxy just shows up in the Forge mods list
and works.

On top of that, Boxy adds features of its own (below). It's the Forge sibling of **Foxy**, which does
the same on NeoForge.

> **Working on the code?** Read the [**Developer Guide**](DEVELOPER_GUIDE.md) — it covers the whole
> architecture, the load-time remap pipeline, the mixin handling, and every quirk hit during
> development.

## Features

- **Loads unmodified Voxy on Forge 1.20.1** — a load-time Fabric→Forge shim scoped to a single mod
  (essentially a tiny, Voxy-specific [Sinytra Connector](https://github.com/Sinytra/Connector)).
- **Voxy Server Side (VSS)** — a from-scratch port of the server→client LOD-streaming protocol: a
  Forge server streams distant terrain LODs to clients, so players see far terrain the server holds —
  even terrain they never walked through. Works on dedicated servers and in singleplayer.
- **Distant entity & player rendering** — renders real players and configured mob types far past
  vanilla's entity range (out toward Voxy's LOD distance), at full fidelity, by extending vanilla
  entity tracking rather than faking entities.
- **Shaderpack support** — with [Oculus](https://modrinth.com/mod/oculus) installed, Voxy's LODs
  render through the active shaderpack.
- **Integrations** — Chunky auto-ingest, Starlight, and C2ME.

## Using Boxy (players)

Drop these into your instance's `mods/` folder:

- **Boxy** (this mod)
- the **unmodified Voxy** Fabric jar
- **Embeddium** (the Sodium fork for Forge)

Optional: **Oculus** (shaderpacks), **MixinExtras**, **Chunky**, **Starlight**, **C2ME**. For VSS
server streaming, install Boxy + Voxy on a dedicated Forge 1.20.1 server too (Embeddium isn't needed
server-side).

## Building (developers)

### Prerequisites

- **JDK 17.** ForgeGradle 6 for 1.20.1 is not compatible with newer JDKs (21, 25, …). Point
  `JAVA_HOME` at a JDK 17 before building:
  ```bash
  export JAVA_HOME="/path/to/jdk-17"
  ```
- **The Voxy dev jar in `libs/`** — see [Dependencies](#dependencies-libs) below. Everything else
  resolves from Maven automatically.

### Build

```bash
./gradlew shadowJar
# output: build/libs/boxy-<version>.jar   (the shaded, reobfuscated production jar)
```

After building, sanity-check that `META-INF/MANIFEST.MF` is still the first jar entry and carries
`Automatic-Module-Name: boxyloader` (the Developer Guide §15 explains why this matters):

```bash
unzip -l build/libs/boxy-<version>.jar | head
unzip -p build/libs/boxy-<version>.jar META-INF/MANIFEST.MF | grep Automatic-Module-Name
```

## Dependencies (`libs/`)

Most build inputs resolve from Maven automatically (declared in [`build.gradle`](build.gradle)):

| Dependency | Source |
|---|---|
| Minecraft Forge 1.20.1 | ForgeGradle |
| Embeddium (compile-only) | Modrinth maven — `maven.modrinth:embeddium` |
| Fabric-Mixin fork | Sinytra maven — `org.sinytra:sponge-mixin` (injected into the jar at build) |
| tiny-remapper, ASM, MixinExtras | Maven Central / Fabric maven |

Two artifacts are **not on any public Maven** and live in `libs/`:

### `voxy-*-dev.jar` — Voxy's Mojmap dev jar (you must provide this)

Boxy compiles against Voxy's public API and needs Voxy's **named / Mojmap "dev" jar** — the one whose
method signatures use official Minecraft names (`Level`, `LevelChunkSection`, …). The Modrinth download
is the *intermediary*-mapped production jar (`class_1937`, `class_2826`, …) and **will not compile**
against Boxy's official-mapped dev environment.

That dev jar isn't published to Maven, so build it from source:

```bash
git clone -b mc_1201 https://github.com/m3t4f1v3/voxy.git
cd voxy
./gradlew build
# The named/dev jar is the "-dev" artifact, under build/devlibs/ (or build/libs/ on older Loom):
cp build/devlibs/voxy-*-dev.jar  <path-to-boxy>/libs/
```

`build.gradle` picks it up with `fileTree(dir: 'libs', include: ['voxy-*-dev.jar'])`, so the exact
version suffix in the filename doesn't matter — but the **API version must be 0.2.14-alpha**, which
Boxy's source targets. This jar is git-ignored, so the repo does not redistribute Voxy's build
artifacts.

### `mixintransmog/` — Mixin Transmogrifier classes (committed)

Loose class files of **Mixin Transmogrifier**, a compatibility tool that lets Forge use Fabric's Mixin
fork so Voxy's constructor-injecting shader mixins apply under Oculus (Developer Guide §12). Boxy shades
them into `com.golem.boxy.libs.mixintransmog` at build time. They're committed as loose classes because
they must be shaded into a Boxy-private package — this is the byte-identical set Sinytra Connector
bundles, kept in lock-step.

**Credit / license:** Mixin Transmogrifier is MIT-licensed, maintained by
[Sinytra](https://github.com/Sinytra/MixinTransmogrifier), originally created by
[SteelwoolMC](https://github.com/SteelwoolMC/MixinTransmogrifier). To refresh these classes, extract
`io/github/steelwoolmc/mixintransmog/**` from a Transmogrifier build into `libs/mixintransmog/`.

## How it works

Voxy's jar is compiled in Fabric's *intermediary* naming (`class_310`, `method_1551`); Forge 1.20.1
runs in *SRG*. Boxy's core job is to rewrite every Minecraft reference in Voxy's bytecode from
intermediary to SRG **at load time, in memory**, before Forge sees it — plus translate Fabric metadata
to Forge, bridge the Fabric APIs Voxy calls, and apply a few compatibility patches. Boxy's own features
(VSS, distant entities) ship as a separate game-layer mod that the same locator hands to Forge, so
Voxy's jar stays pristine.

The full story — the two-classloader-layer architecture, the mapping composition, the mixin handling,
and the catalog of every quirk — is in the [**Developer Guide**](DEVELOPER_GUIDE.md).

## Credits

Boxy builds on Voxy, Voxy Server Side, Sinytra Connector / Mixin Transmogrifier, Embeddium, Oculus, and
Minecraft Forge. See [CREDITS.txt](CREDITS.txt) for full attribution.

## License

GPL-3.0-only — see [LICENSE.txt](LICENSE.txt). Copyright © 2026 golem. Bundled or referenced
third-party components retain their own licenses (see [CREDITS.txt](CREDITS.txt)).

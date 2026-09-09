# Boxy

Boxy is a **native NeoForge 1.21.1 addon for Voxy**. It streams distant terrain from servers and renders configured players/entities beyond vanilla tracking distance.

Boxy and Voxy remain separate mods. Boxy does not remap, rewrite, embed, or redistribute Voxy. The Forge 1.20.1 loader bridge is not part of this version.

## Installation

Client baseline:

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 |
| NeoForge | 21.1.248 or newer |
| Voxy | 0.2.15-beta, NeoForge build from the selected multiversion source |
| Sodium | 0.8.12-beta.2 for NeoForge 1.21.1 |
| Iris, optional | 1.8.14-beta.1 for NeoForge 1.21.1 |

Install the complete native distribution jars in `mods/`, not the Sodium API/development jars. Install the matching `boxy-2.0.0-alpha.1.jar`; it is a single Jar-in-Jar distribution containing Boxy's NeoForge mod and its early Voxy bootstrap. The same jar is safe on a dedicated server because the bootstrap skips server launch targets. **Do not install the old Fabric Voxy jar, Embeddium, Oculus, the old Boxy loader, or an older combined Boxy jar.**

A dedicated server needs only **Boxy + NeoForge**. Its terrain streaming does not require Voxy, Sodium, or Iris. Clients without Boxy may connect; Boxy only sends its custom payloads over negotiated channels.

The Voxy reference's original NeoForge 21.1.230/Iris 1.8.12 pins were not compatible with the selected Sodium beta's current runtime dependencies. See [MIGRATION.md](MIGRATION.md) for the audited changes and remaining verification work.

## Features

- Server terrain streaming from loaded chunks, region-file reads, and optional on-demand generation.
- Live dirty-column synchronization, bounded work queues, bandwidth/concurrency limits, and persistent validation timestamps.
- Distant real players and configured entity types, server-synced whitelists, namespace wildcards, interpolation, and client opt-out.
- Optional non-ticking chunk loading for distant entities, default off.
- Distant-entity depth modes: Off, Basic, and Precise. Precise falls back to Basic under active shaderpacks.
- Native Sodium settings page for Boxy's client options.
- Integrated-server spawn ingestion and retained, configurable Voxy lighting/occlusion fixes that are absent upstream.

Configuration files remain `config/vss-client-config.json` and `config/vss-server-config.json`. Server entity settings apply in memory, never overwrite the client file. The entity-type list remains JSON-only. Reconnect after changing the terrain-streaming master switch.

This is a clean protocol break from Forge Boxy and original Voxy Server Side. Old worlds and caches are **not deleted or converted**. Back up worlds before any Minecraft version upgrade; use separate Voxy storage when moving from 1.20.1.

### Voxy Dependency Bootstrap

On a client launch, the outer `boxy` jar runs a ModLauncher early service before NeoForge validates the mod list. If no valid Voxy jar is already present in the instance `mods` folder, it downloads the official Voxy release into that folder using an atomic temporary file. The Boxy NeoForge mod is embedded as a separate Jar-in-Jar path and submitted by a Sodium-style early mod locator, so NeoForge can discover it even though ModLauncher claims the outer path. A small `Setting up dependencies` window shows progress when a graphical desktop is available; headless/server launches log progress and do not download the client-only dependency. The launcher then discovers Voxy during the same startup.

The download source is the official release URL:

`https://github.com/Warfactory-Official/voxy/releases/download/latest/1.21.1-neoforge-voxy-0.2.15-beta+1.21.1-neoforge.jar`

If the download fails or the result is not a valid Voxy NeoForge jar, startup stops before Boxy can enter a broken state. Users may also install Voxy manually; an existing valid Voxy jar is reused.

## Build

Use JDK 21. Supply the native Voxy jar in `libs/voxy-0.2.15-beta+1.21.1-neoforge.jar`, or point `-Pvoxy_jar` to it:

```powershell
.\gradlew.bat build "-Pvoxy_jar=C:\path\to\voxy-0.2.15-beta+1.21.1-neoforge.jar"
```

Output: `build/libs/boxy-2.0.0-alpha.1.jar`. It is the single production distribution containing the isolated bootstrap and nested core mod; no `shadowJar`, reobfuscation, extracted game-layer jar, or bundled Mixin upgrade is used.

To build the supplied Voxy source without changing the original directory:

```powershell
.\gradlew.bat stageVoxySource "-Pvoxy_source=C:\path\to\voxy-multiversion"
.\build\voxy-source\gradlew.bat -p build/voxy-source :1.21.1-neoforge:jar --configure-on-demand
.\gradlew.bat stageVoxyDependency "-Pvoxy_jar=build/voxy-source/versions/1.21.1-neoforge/build/libs/voxy-0.2.15-beta+1.21.1-neoforge.jar"
.\gradlew.bat build
```

Voxy's license says **All rights reserved / Do not redistribute**. Its jar is a private build input, excluded from Git and Boxy's artifact.

## Development Checks

```powershell
.\gradlew.bat test verifyProductionJar verifyClientLaunch
.\gradlew.bat runSmokeServer -Psmoke
.\gradlew.bat runSmokeClient -Psmoke
.\gradlew.bat runSmokeClient -Psmoke -Pwith_iris
.\gradlew.bat runSmokeClient -Psmoke -Pdimension_smoke
.\gradlew.bat runSmokeClient -Psmoke -Pocclusion_smoke -Pwith_iris
.\gradlew.bat runSmokeClient -Psmoke -Pocclusion_smoke -Pocclusion_distance=832
.\gradlew.bat runSmokeClient -Psmoke -Pocclusion_smoke -Pocclusion_distance=832 -Pocclusion_depth_mode=OFF
.\gradlew.bat runSmokeClient -Psmoke -Pstability_smoke "-Pstability_world_source=C:\path\to\closed-test-world"
```

Server smoke testing requires the operator to accept Minecraft's EULA in `run/smoke-server/eula.txt`. No build task accepts it automatically. A loopback-only server configuration is generated if absent. The server smoke mod starts `boxy-migration-smoke`, checks common hooks and serialization, and stops the server. Client smoke testing copies that world to `run/smoke-client/saves/boxy-migration-smoke`, tests integrated streaming and a pig 160 blocks away, then exits. Do not use these smoke runs with valuable worlds.

`runClient` copies complete native dependencies and an internal bootstrap-only jar into `run/client/mods` for normal loader discovery while the core mod is loaded from the development source set. It does not delete existing mods; remove old staged versions, including any combined Boxy jar or older bootstrap companion, when changing dependencies. Omitting `-Pwith_iris` does not remove an Iris jar already staged by an earlier run.

**Normal and smoke launches are separate.** `runClient`/`runServer` never load the smoke mod or apply its memory overrides, even with `-Psmoke`. `runSmokeClient`/`runSmokeServer` have independent generated JVM argument files and game directories. Normal launches use Voxy's native geometry-memory policy; smoke clients default to 1 GiB. Do not use a 256 MiB geometry cap: the selected Voxy starts evicting when fewer than 256,000,000 bytes remain free, leaving only about 12 MiB before continual remeshing. `build` regenerates and checks the normal client argument file to prevent this regression.

Stability testing uses `run/stability` and can copy a closed world's LOD data without modifying the source. It sweeps the camera, then requires identical stationary LOD geometry counts and no GL errors. It is not a general visual certification.

The smoke source set is opt-in and is never packaged in Boxy. `-Pdimension_smoke` additionally verifies overworld/nether/overworld travel and resumed streaming. A smoke failure fails the Gradle task even if Minecraft exits with code zero. Automated smoke checks are **not** a substitute for visual shaderpack/occlusion and multiplayer testing.

## CI

The native build workflow accepts a Voxy jar URL and its SHA-256, supplied through workflow inputs or repository variables `VOXY_JAR_URL` / `VOXY_JAR_SHA256`. Configure both before enabling automatic builds. The selected local source has no authoritative Git commit, so CI does not silently substitute the old `mc_1201` branch or an unverified remote baseline. Only Boxy's jar is uploaded; there is no automatic release publishing during migration.

See [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) for architecture and [MIGRATION.md](MIGRATION.md) for the patch audit.

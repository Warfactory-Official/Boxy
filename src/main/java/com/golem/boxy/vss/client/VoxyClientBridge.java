package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.OptionalInt;

/**
 * The one place Boxy's VSS port touches Voxy: it feeds received LOD sections straight into Voxy's ingest
 * service. Unlike the original VSS (which used reflective MethodHandles to stay decoupled), Boxy's mod jar
 * compiles against Voxy, so these are direct, type-safe calls (the same approach as {@code BoxyServerIngest}
 * on the client thread). Client-only.
 */
public final class VoxyClientBridge {
    private VoxyClientBridge() {}

    /** True once Voxy's instance is up (its renderer/world engine exists). */
    public static boolean isAvailable() {
        return VoxyCommon.getInstance() != null;
    }

    /**
     * Ingest a deserialized column into Voxy (one rawIngest per section). Client thread only, serialized with session shutdown.
     *
     * <p>When {@code clearMissingSections} is set (a re-sync of a column the client already had), every
     * sub-chunk in the world height that the payload did <b>not</b> include is ingested as an empty (air)
     * section. The server omits all-air sections to keep the stream cheap, so on an update the client would
     * otherwise never hear that a now-empty sub-chunk (e.g. a broken pillar's top section, which held only
     * the pillar) should be cleared, and the stale LOD would linger. Fresh ingests pass {@code false}, so the
     * initial stream is untouched.
     */
    public static boolean ingest(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData,
                               boolean clearMissingSections) {
        if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Voxy ingest must run on the client thread");
        if (net.minecraft.client.Minecraft.getInstance().level != level || !level.dimension().equals(dimension) || !isAvailable()) return false;
        try {
            WorldIdentifier worldId = WorldIdentifier.of(level);
            if (worldId == null) {
                return false;
            }
            VoxelColumnData.SectionData[] sections = columnData.sections();
            for (VoxelColumnData.SectionData s : sections) {
                if (!VoxelIngestService.rawIngest(worldId, s.section(), chunkX, s.sectionY(), chunkZ, s.blockLight(), s.skyLight())) return false;
            }
            if (clearMissingSections) {
                int minSection = level.getMinSection();
                int maxSection = level.getMaxSection();
                boolean[] present = new boolean[Math.max(0, maxSection - minSection)];
                for (VoxelColumnData.SectionData s : sections) {
                    int idx = s.sectionY() - minSection;
                    if (idx >= 0 && idx < present.length) {
                        present[idx] = true;
                    }
                }
                Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
                for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                    if (!present[sectionY - minSection]) {
                        if (!VoxelIngestService.rawIngest(worldId, new LevelChunkSection(biomeRegistry), chunkX, sectionY, chunkZ, null, null)) return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            if (t instanceof Error && !(t instanceof LinkageError) && !(t instanceof AssertionError)) {
                throw (Error) t;
            }
            VSSLogger.error("Voxy raw ingest failed", t);
            return false;
        }
    }

    /** Voxy's configured LOD render distance in chunks (sectionRenderDistance * 32), if available. */
    public static OptionalInt getViewDistanceChunks() {
        try {
            float sectionDist = VoxyConfig.CONFIG.sectionRenderDistance;
            return OptionalInt.of(Math.round(sectionDist * 32.0F));
        } catch (Throwable t) {
            return OptionalInt.empty();
        }
    }
}

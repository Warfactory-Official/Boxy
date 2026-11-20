package com.golem.boxy.vss.client;

import com.golem.boxy.seedlod.fill.RealColumns;
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
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.OptionalInt;
import net.minecraft.client.Minecraft;

/**
 * The one place Boxy's VSS port touches Voxy: it feeds received LOD sections straight into Voxy's ingest
 * service. Unlike the original VSS (which used reflective MethodHandles to stay decoupled), Boxy's mod jar
 * compiles against Voxy, so these are direct, type-safe calls (the same approach as {@code BoxyServerIngest}
 * on the client thread). Client-only.
 */
public final class VoxyClientBridge {
    private VoxyClientBridge() {}

    private static final int SKY_SECTIONS_ABOVE_TOP = 2;

    /**
     * The lowest section of the column that is open sky: one above the highest section holding any block, as
     * vanilla's sky light reads an unallocated layer there as full light. Voxy stores air ingested without a sky
     * layer as light 0, so those sections must be given full sky explicitly, or water and terrain whose top lies
     * on a section's top layer at some LOD level are meshed black. {@code Integer.MAX_VALUE} without sky light.
     */
    static int openSkyFrom(ClientLevel level, VoxelColumnData.SectionData[] sections) {
        if (!level.dimensionType().hasSkyLight()) {
            return Integer.MAX_VALUE;
        }
        int top = level.getMinSection();
        for (VoxelColumnData.SectionData s : sections) {
            if (!s.section().hasOnlyAir()) {
                top = Math.max(top, s.sectionY() + 1);
            }
        }
        return top;
    }

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
     * the pillar) should be cleared, and the stale LOD would linger. Fresh ingests pass {@code false} and store
     * only the open sky just above the terrain. Air above the column's top block is ingested with full sky light
     * ({@link #openSkyFrom}).
     */
    public static boolean ingest(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData,
                               boolean clearMissingSections) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Voxy ingest must run on the client thread");
        if (Minecraft.getInstance().level != level || !level.dimension().equals(dimension) || !isAvailable()) return false;
        try {
            WorldIdentifier worldId = WorldIdentifier.of(level);
            if (worldId == null) {
                return false;
            }
            // Mark before ingesting: a seed fill racing this column must see it as real (see RealColumns).
            RealColumns.mark(dimension, chunkX, chunkZ);
            VoxelColumnData.SectionData[] sections = columnData.sections();
            int top = openSkyFrom(level, sections);
            for (VoxelColumnData.SectionData s : sections) {
                DataLayer sky = s.skyLight() == null && s.sectionY() >= top ? new DataLayer(15) : s.skyLight();
                if (!VoxelIngestService.rawIngest(worldId, s.section(), chunkX, s.sectionY(), chunkZ, s.blockLight(), sky)) return false;
            }
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
                if (present[sectionY - minSection]) {
                    continue;
                }
                boolean openSky = sectionY >= top;
                // Fresh columns still store the open air just above the terrain: Voxy lights a surface from the voxel
                // above it, and at its coarsest level (16-block voxels) that voxel ends at most 31 blocks over the top
                // block, i.e. inside the two sections above the top one. Unstored, it would read as unlit air.
                if (!clearMissingSections && !(openSky && sectionY < top + SKY_SECTIONS_ABOVE_TOP)) {
                    continue;
                }
                if (!VoxelIngestService.rawIngest(worldId, new LevelChunkSection(biomeRegistry), chunkX, sectionY, chunkZ,
                        null, openSky ? new DataLayer(15) : null)) return false;
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

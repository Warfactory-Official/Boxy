package com.golem.boxy.smoke;

import com.golem.boxy.vss.server.ServerNetworking;
import com.golem.boxy.vss.server.ColumnSnapshotter;
import com.golem.boxy.vss.server.SectionSerializer;

import com.golem.boxy.vss.mixin.AccessorChunkMap;
import com.golem.boxy.vss.mixin.AccessorLevelChunkSection;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Real dedicated-server smoke checks; enabled explicitly with -Psmoke -Pserver_only. */
@Mod("boxy_smoke")
public final class BoxySmoke {
    public BoxySmoke() {
        var config = com.golem.boxy.vss.config.VSSServerConfig.CONFIG;
        config.trackedEntityTypes = java.util.List.of("minecraft:player", "minecraft:pig", "minecraft:ghast");
        config.enableChunkGeneration = false;
        config.lodDistanceChunks = 32;
        if (Boolean.getBoolean("boxy.smokeOcclusion")) {
            config.lodDistanceChunks = Math.max(32, OcclusionSmoke.ENTITY_X / 16 + 4);
            config.entityTrackingDistanceChunks = Math.max(32, OcclusionSmoke.ENTITY_X / 16 + 4);
        }
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, BoxySmoke::checkServer);
        NeoForge.EVENT_BUS.addListener(BoxySmoke::placeDistantPig);
    }

    private static void placeDistantPig(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (Boolean.getBoolean("boxy.smokeStability")) {
            player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            return;
        }
        if (Boolean.getBoolean("boxy.smokeOcclusion")) {
            OcclusionSmoke.place(player);
            return;
        }
        var level = player.serverLevel();
        var pos = new net.minecraft.world.level.ChunkPos(10, 0);
        level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, pos, 0, pos);
        level.getChunk(10, 0);
        level.getEntitiesOfClass(net.minecraft.world.entity.animal.Pig.class,
                new net.minecraft.world.phys.AABB(159, 119, -1, 162, 122, 2)).forEach(net.minecraft.world.entity.Entity::discard);
        var pig = EntityType.PIG.create(level);
        if (pig == null) throw new AssertionError("Distant pig creation failed");
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.setPersistenceRequired();
        pig.moveTo(160.5, 120, 0.5);
        level.addFreshEntity(pig);
        player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        player.teleportTo(level, 0.5, 120, 0.5, -90, 0);
    }

    private static void checkServer(ServerStartedEvent event) {
        var server = event.getServer();
        if (!server.isDedicatedServer()) return;
        var level = server.overworld();
        if (ServerNetworking.getRequestService() == null) throw new AssertionError("VSS service did not start");
        var accessor = (AccessorChunkMap) level.getChunkSource().chunkMap;
        if (accessor.boxy$getLevel() != level) throw new AssertionError("ChunkMap accessor mismatch");
        accessor.boxy$getChunks().iterator();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var section = new LevelChunkSection(biomes);
        section.setBlockState(2, 3, 4, Blocks.GLOWSTONE.defaultBlockState());
        if (((AccessorLevelChunkSection) (Object) section).boxy$getNonEmptyBlockCount() != 1) {
            throw new AssertionError("Section accessor mismatch");
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            section.write(buf);
            var decoded = new LevelChunkSection(biomes);
            decoded.read(buf);
            if (!decoded.getBlockState(2, 3, 4).is(Blocks.GLOWSTONE) || buf.isReadable()) {
                throw new AssertionError("Section wire round trip failed");
            }
        } finally {
            buf.release();
        }
        var uniform = new DataLayer(15);
        try {
            var lightBytes = SectionSerializer.class.getDeclaredMethod("lightBytes", DataLayer.class);
            lightBytes.setAccessible(true);
            if (((byte[]) lightBytes.invoke(null, uniform))[0] != (byte) 0xff || !uniform.isDefinitelyHomogenous()) {
                throw new AssertionError("Uniform lighting serialization mutates source");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        var chunk = level.getChunk(0, 0);
        var snapshot = ColumnSnapshotter.snapshot(server, level, chunk, 0, 0);
        ColumnSnapshotter.serialize(snapshot);
        var emptySnapshot = new com.golem.boxy.vss.server.ColumnSnapshot(0, 0,
                new com.golem.boxy.vss.server.ColumnSnapshot.SectionSnapshot[0]);
        var emptyBytes = ColumnSnapshotter.serialize(emptySnapshot);
        if (emptyBytes == null || emptyBytes.length != 0) throw new AssertionError("Empty column lost its update");
        var emptyPayload = new com.golem.boxy.vss.payloads.VoxelColumnS2CPayload(1, 0, 0,
                net.minecraft.world.level.Level.OVERWORLD, 123, emptyBytes);
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            emptyPayload.encode(wire);
            var decoded = com.golem.boxy.vss.payloads.VoxelColumnS2CPayload.decode(wire);
            if (decoded.decompressedSections().length != 0 || wire.isReadable()) throw new AssertionError("Empty column wire round trip failed");
        } finally {
            wire.release();
        }
        BulkTerrainSmoke.check(level);
        var pig = EntityType.PIG.create(level);
        if (pig == null) throw new AssertionError("Pig creation failed");
        pig.moveTo(8, level.getSeaLevel() + 3, 8);
        if (!level.addFreshEntity(pig)) throw new AssertionError("Entity registration failed");
        pig.discard(); // Loads/applies ChunkMap.TrackedEntity, including both required tracking hooks.
        com.mojang.logging.LogUtils.getLogger().info("BOXY_SMOKE_OK: standalone server, lifecycle, common mixins, palettes, snapshots, uniform lighting, entity tracking");
        EntityLoadingSmoke.start(server);
    }
}

package com.golem.boxy.smoke;

import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.server.ServerNetworking;
import com.golem.boxy.vss.server.ColumnSnapshotter;
import com.golem.boxy.vss.server.SectionSerializer;

import com.golem.boxy.vss.mixin.AccessorChunkMap;
import com.golem.boxy.vss.mixin.AccessorLevelChunkSection;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import com.golem.boxy.vss.server.ColumnSnapshot;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Real dedicated-server smoke checks; enabled explicitly with -Psmoke -Pserver_only. */
@Mod("boxy_smoke")
public final class BoxySmoke {
    public BoxySmoke() {
        var config = VSSServerConfig.CONFIG;
        config.trackedEntityTypes = List.of("minecraft:player", "minecraft:pig", "minecraft:ghast");
        config.enableChunkGeneration = false;
        config.lodDistanceChunks = 32;
        if (Boolean.getBoolean("boxy.smokeOcclusion")) {
            config.lodDistanceChunks = Math.max(32, OcclusionSmoke.ENTITY_X / 16 + 4);
            config.entityTrackingDistanceChunks = Math.max(32, OcclusionSmoke.ENTITY_X / 16 + 4);
        }
        if (Boolean.getBoolean("boxy.perf")) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, PerfServerSampler::beforeTick);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PerfServerSampler::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, PerfServerSampler::onServerStopping);
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BoxySmoke::checkServer);
        NeoForge.EVENT_BUS.addListener(BoxySmoke::placeDistantPig);
    }

    private static void placeDistantPig(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (Boolean.getBoolean("boxy.smokeStability")) {
            player.setGameMode(GameType.SPECTATOR);
            if (Boolean.getBoolean("boxy.perf")) {
                var level = player.serverLevel();
                player.teleportTo(level, -74.5, 63.0, -57.5, -90.0F, 25.0F);
                level.setDayTime(6000L);
                level.setWeatherParameters(0, 6000, false, false);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT)
                        .set(false, level.getServer());
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE)
                        .set(false, level.getServer());
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING)
                        .set(false, level.getServer());
            }
            return;
        }
        if (Boolean.getBoolean("boxy.smokeOcclusion")) {
            OcclusionSmoke.place(player);
            return;
        }
        if (Boolean.getBoolean("boxy.smokeWater")) {
            WaterSmoke.place(player);
            return;
        }
        var level = player.serverLevel();
        var pos = new ChunkPos(10, 0);
        level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 0, pos);
        level.getChunk(10, 0);
        level.getEntitiesOfClass(Pig.class,
                new AABB(159, 119, -1, 162, 122, 2)).forEach(Entity::discard);
        var pig = EntityType.PIG.create(level);
        if (pig == null) throw new AssertionError("Distant pig creation failed");
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.setPersistenceRequired();
        pig.moveTo(160.5, 120, 0.5);
        level.addFreshEntity(pig);
        player.setGameMode(GameType.SPECTATOR);
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
        var emptySnapshot = new ColumnSnapshot(0, 0,
                new ColumnSnapshot.SectionSnapshot[0]);
        var emptyBytes = ColumnSnapshotter.serialize(emptySnapshot);
        if (emptyBytes == null || emptyBytes.length != 0) throw new AssertionError("Empty column lost its update");
        var emptyPayload = new VoxelColumnS2CPayload(1, 0, 0,
                Level.OVERWORLD, 123, emptyBytes);
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            emptyPayload.encode(wire);
            var decoded = VoxelColumnS2CPayload.decode(wire);
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
        LogUtils.getLogger().info("BOXY_SMOKE_OK: standalone server, lifecycle, common mixins, palettes, snapshots, uniform lighting, entity tracking");
        EntityLoadingSmoke.start(server);
    }
}

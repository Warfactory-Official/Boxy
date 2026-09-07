package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.TrackedEntityTypes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/** Last-known locations, not entity snapshots. Minecraft remains responsible for saving entities. */
public final class DiscoveredEntities extends SavedData {
    private static final Factory<DiscoveredEntities> FACTORY =
            new Factory<>(DiscoveredEntities::new, DiscoveredEntities::load, null);
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<Long, Set<UUID>> regions = new HashMap<>();
    private long revision;

    record Entry(ResourceLocation type, long chunk) {}

    public static DiscoveredEntities get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "boxy-discovered-entities-v1");
    }

    public static DiscoveredEntities load(CompoundTag tag, HolderLookup.Provider registries) {
        DiscoveredEntities data = new DiscoveredEntities();
        ListTag list = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation type = ResourceLocation.tryParse(entry.getString("type"));
            if (entry.hasUUID("uuid") && type != null) {
                data.remember(entry.getUUID("uuid"), type, entry.getLong("chunk"));
            }
        }
        data.setDirty(false);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        entries.forEach((uuid, entry) -> {
            CompoundTag item = new CompoundTag();
            item.putUUID("uuid", uuid);
            item.putString("type", entry.type.toString());
            item.putLong("chunk", entry.chunk);
            list.add(item);
        });
        tag.put("entities", list);
        return tag;
    }

    void observe(Entity entity) {
        // Players live in playerdata, not chunk entity storage, and cannot be restored with tickets.
        if (!(entity instanceof Player) && !entity.isRemoved()
                && TrackedEntityTypes.serverContains(entity.getType())) {
            remember(entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                    entity.chunkPosition().toLong());
        }
    }

    public void remember(UUID uuid, ResourceLocation type, long chunk) {
        Entry entry = new Entry(type, chunk);
        Entry previous = entries.get(uuid);
        if (entry.equals(previous)) return;
        if (previous != null) removeRegion(uuid, previous.chunk);
        entries.put(uuid, entry);
        regions.computeIfAbsent(region(chunk), key -> new HashSet<>()).add(uuid);
        changed();
    }

    public void forget(UUID uuid) {
        Entry previous = entries.remove(uuid);
        if (previous != null) {
            removeRegion(uuid, previous.chunk);
            changed();
        }
    }

    private static long region(long chunk) {
        return ChunkPos.asLong(ChunkPos.getX(chunk) >> 5, ChunkPos.getZ(chunk) >> 5);
    }

    private void removeRegion(UUID uuid, long chunk) {
        long key = region(chunk);
        Set<UUID> ids = regions.get(key);
        ids.remove(uuid);
        if (ids.isEmpty()) regions.remove(key);
    }

    private void changed() {
        revision++;
        setDirty();
    }

    public long revision() {
        return revision;
    }

    void refresh(ServerLevel level) {
        // Rotate a bounded slice so old discoveries cannot turn every tick into a world-wide scan.
        var retained = new ArrayList<Map.Entry<UUID, Entry>>();
        var iterator = entries.entrySet().iterator();
        for (int count = 0; count < 64 && iterator.hasNext(); count++) {
            var cached = iterator.next();
            ChunkPos chunk = new ChunkPos(cached.getValue().chunk);
            // FULL terrain alone is insufficient: entity storage loads asynchronously afterwards.
            if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null
                    && level.areEntitiesLoaded(chunk.toLong())
                    && level.getEntity(cached.getKey()) == null) {
                removeRegion(cached.getKey(), cached.getValue().chunk);
                changed();
            } else {
                retained.add(Map.entry(cached.getKey(), cached.getValue()));
            }
            iterator.remove();
        }
        retained.forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
    }

    public LongSet desiredChunks(double playerX, double playerZ, int distance, int radius) {
        LongSet desired = new LongOpenHashSet();
        int blocks = distance * 16;
        for (int x = (int) Math.floor((playerX - blocks - 16) / 512); x <= (int) Math.floor((playerX + blocks) / 512); x++) {
            for (int z = (int) Math.floor((playerZ - blocks - 16) / 512); z <= (int) Math.floor((playerZ + blocks) / 512); z++) {
                Set<UUID> ids = regions.get(ChunkPos.asLong(x, z));
                if (ids == null) continue;
                for (UUID uuid : ids) {
                    Entry entry = entries.get(uuid);
                    var type = BuiltInRegistries.ENTITY_TYPE.getOptional(entry.type);
                    if (type.isEmpty() || !TrackedEntityTypes.serverContains(type.get())) continue;
                    addWindow(desired, playerX, playerZ, entry.chunk, distance, radius);
                }
            }
        }
        return desired;
    }

    public static void addWindow(LongSet desired, double playerX, double playerZ, long entityChunk,
                          int distance, int radius) {
        ChunkPos center = new ChunkPos(entityChunk);
        double dx = Math.max(Math.max(center.getMinBlockX() - playerX, playerX - (center.getMinBlockX() + 16)), 0);
        double dz = Math.max(Math.max(center.getMinBlockZ() - playerZ, playerZ - (center.getMinBlockZ() + 16)), 0);
        if (dx * dx + dz * dz > (double) distance * distance * 256) return;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                desired.add(ChunkPos.asLong(center.x + x, center.z + z));
            }
        }
    }
}

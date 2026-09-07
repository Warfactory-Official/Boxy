package com.golem.boxy.vss.common.processing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class DedupTracker {
    private final Map<String, Long2ObjectOpenHashMap<Group>> pending = new HashMap<>();

    boolean tryAttachOrCreate(long packed, String dimension, UUID player, int requestId, long order) {
        var slice = pending.computeIfAbsent(dimension, key -> new Long2ObjectOpenHashMap<>());
        Group existing = slice.get(packed);
        if (existing != null) {
            existing.attached().add(new Attachment(player, requestId, order));
            return true;
        }
        slice.put(packed, new Group(player, requestId, dimension, new ArrayList<>(2)));
        return false;
    }

    Group removeGroup(long packed, String dimension, UUID player, int requestId) {
        var slice = pending.get(dimension);
        if (slice == null) return null;
        Group group = slice.get(packed);
        // A cancelled/old request must not consume a newer group's ownership of these coordinates.
        if (group == null || !group.primaryPlayer().equals(player) || group.requestId() != requestId) return null;
        return slice.remove(packed);
    }

    List<RemovedGroup> removePlayer(UUID player) {
        var removed = new ArrayList<RemovedGroup>();
        for (var slice : pending.values()) {
            var iterator = slice.long2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Group group = entry.getValue();
                if (group.primaryPlayer().equals(player)) {
                    removed.add(new RemovedGroup(entry.getLongKey(), group));
                    iterator.remove();
                } else {
                    group.attached().removeIf(attachment -> attachment.playerUuid().equals(player));
                }
            }
        }
        return removed;
    }

    record Attachment(UUID playerUuid, int requestId, long submissionOrder) {}
    record Group(UUID primaryPlayer, int requestId, String dimension, ArrayList<Attachment> attached) {}
    record RemovedGroup(long packed, Group group) {}
}

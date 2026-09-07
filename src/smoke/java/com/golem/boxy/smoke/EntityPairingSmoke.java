package com.golem.boxy.smoke;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Pig;

/** Forces the pending-chunk tracking gate without changing the player's world or camera. */
final class EntityPairingSmoke {
    private static Entity clientEntity;
    private static volatile boolean complete;
    private static volatile Throwable failure;
    private static int completionTicks;

    static boolean check(Minecraft mc) {
        if (clientEntity == null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Pig && Math.abs(entity.getX() - 160.5) < 1 && entity.getY() > 119) {
                    clientEntity = entity;
                    break;
                }
            }
            if (clientEntity == null) throw new AssertionError("No distant pairing fixture");
            int id = clientEntity.getId();
            var playerId = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(playerId);
                    var level = player.serverLevel();
                    var entity = level.getEntity(id);
                    var map = level.getChunkSource().chunkMap;
                    var entitiesField = map.getClass().getDeclaredField("entityMap");
                    entitiesField.setAccessible(true);
                    var entities = (it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) entitiesField.get(map);
                    Object tracker = entities.get(id);
                    var type = tracker.getClass();
                    var seenField = type.getDeclaredField("seenBy");
                    seenField.setAccessible(true);
                    var seen = (java.util.Set<?>) seenField.get(tracker);
                    var update = type.getDeclaredMethod("updatePlayer", net.minecraft.server.level.ServerPlayer.class);
                    update.setAccessible(true);
                    if (!seen.contains(player.connection)) throw new AssertionError("Fixture not paired initially");
                    var sender = player.connection.chunkSender;
                    var pendingField = sender.getClass().getDeclaredField("pendingChunks");
                    pendingField.setAccessible(true);
                    var pending = (it.unimi.dsi.fastutil.longs.LongSet) pendingField.get(sender);
                    long chunk = entity.chunkPosition().toLong();
                    boolean added = pending.add(chunk);
                    try {
                        update.invoke(tracker, player);
                        if (!seen.contains(player.connection)) throw new AssertionError("Pending chunk removed existing distant pairing");
                        var constructor = type.getDeclaredConstructor(net.minecraft.server.level.ChunkMap.class,
                                Entity.class, int.class, int.class, boolean.class);
                        constructor.setAccessible(true);
                        Object unseen = constructor.newInstance(map, entity, 64, 3, false);
                        update.invoke(unseen, player);
                        if (!((java.util.Set<?>) seenField.get(unseen)).isEmpty()) {
                            throw new AssertionError("Unseen entity paired before pending chunk delivery");
                        }
                    } finally {
                        if (added) pending.remove(chunk);
                    }
                    update.invoke(tracker, player);
                    if (!seen.contains(player.connection)) throw new AssertionError("Pairing lost after delivery");
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    complete = true;
                }
            });
            return false;
        }
        if (!complete) return false;
        if (failure != null) throw new AssertionError("Entity pairing handoff failed", failure);
        if (++completionTicks < 10) return false;
        if (mc.level.getEntity(clientEntity.getId()) != clientEntity) {
            throw new AssertionError("Client entity identity changed during pending chunk handoff");
        }
        com.mojang.logging.LogUtils.getLogger().info("BOXY_ENTITY_PAIRING_SMOKE_OK: existing pairing retained, unseen delivery gated, client identity retained");
        return true;
    }
}

package com.golem.boxy.vss.common;

import com.golem.boxy.vss.config.VSSClientConfig;
import com.golem.boxy.vss.config.VSSServerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves the configured entity-type id lists (server {@code trackedEntityTypes} / client
 * {@code renderedEntityTypes}) into memoized {@link EntityType} sets used by the distant-entity mixins.
 *
 * <p>Resolution is <b>lazy</b> — the first query builds the set — so it runs after the entity registry is
 * populated. The config statics load much earlier (mod construction), when modded entity types may not be
 * registered yet, so resolving eagerly would silently drop them. Unknown/malformed ids are logged once
 * and skipped. Membership is then an O(1) {@code Set.contains(entity.getType())}.
 *
 * <p>An id of the form {@code "modid:*"} is a wildcard that whitelists every registered entity type in
 * that namespace (e.g. {@code "minecraft:*"} or {@code "create:*"}).
 */
public final class TrackedEntityTypes {
    private static volatile Set<EntityType<?>> serverSet;
    private static List<String> serverIds;
    private static volatile Set<EntityType<?>> clientSet;
    private static volatile Set<EntityType<?>> syncedSet; // server-pushed; overrides clientSet while connected

    private static final AtomicBoolean DIAG_FIRED = new AtomicBoolean();
    private static final AtomicBoolean DIAG_EXTENDED = new AtomicBoolean();

    private TrackedEntityTypes() {}

    /**
     * One-time diagnostics from the tracking mixin: confirms it applied, the shadow bound, and that it extends.
     *
     * <p>This sits on {@code ChunkMap$TrackedEntity.updatePlayer} — per tracked entity, per player, per tick —
     * so after the messages have fired it must cost as close to nothing as possible. A failed
     * {@code compareAndSet} still executes a hardware CAS, so each is guarded by a plain volatile
     * {@code get()} first; and since EXTENDED can only fire after FIRED, a single read of EXTENDED
     * short-circuits the fully-fired steady state.
     */
    public static void diagRedirect(EntityType<?> type, boolean extend, int distChunks) {
        if (DIAG_EXTENDED.get()) {
            return; // both messages fired — steady state, one volatile read
        }
        if (!DIAG_FIRED.get() && DIAG_FIRED.compareAndSet(false, true)) {
            VSSLogger.info("Boxy tracking redirect ACTIVE (mixin applied). first entity="
                    + (type == null ? "<null: shadow did not bind>" : EntityType.getKey(type)) + ", extendThis=" + extend);
        }
        if (extend && type != null && DIAG_EXTENDED.compareAndSet(false, true)) {
            VSSLogger.info("Boxy extending tracking range to " + distChunks + " chunks (first type "
                    + EntityType.getKey(type) + ")");
        }
    }

    private static final AtomicBoolean DIAG_CLIENT_FIRED = new AtomicBoolean();
    private static final AtomicBoolean DIAG_CHUNK_BYPASS = new AtomicBoolean();

    /** One-time diagnostic from the client cull mixin: confirms it applied and reaches a configured type.
     *  get()-guarded so the per-entity-per-frame caller pays a volatile read, not a CAS, once fired. */
    public static void diagClientCull(EntityType<?> type) {
        if (!DIAG_CLIENT_FIRED.get() && DIAG_CLIENT_FIRED.compareAndSet(false, true)) {
            VSSLogger.info("Boxy client render-cull override ACTIVE for type "
                    + (type == null ? "<null>" : EntityType.getKey(type)));
        }
    }

    /** One-time diagnostic from the chunk-compiled wrap: confirms a distant entity got past the chunk gate.
     *  get()-guarded so the per-entity-per-frame caller pays a volatile read, not a CAS, once fired. */
    public static void diagChunkBypass() {
        if (!DIAG_CHUNK_BYPASS.get() && DIAG_CHUNK_BYPASS.compareAndSet(false, true)) {
            VSSLogger.info("Boxy chunk-compiled gate bypassed — distant entity in an un-compiled chunk is now renderable");
        }
    }

    private static final AtomicBoolean DIAG_FOREIGN_CULL = new AtomicBoolean();

    /** One-time diagnostic from the foreign-culler override: another mod vetoed a distant tracked entity and
     *  Boxy re-allowed it. get()-guarded so the per-entity-per-frame caller pays a volatile read once fired. */
    public static void diagForeignCullOverride(EntityType<?> type) {
        if (!DIAG_FOREIGN_CULL.get() && DIAG_FOREIGN_CULL.compareAndSet(false, true)) {
            VSSLogger.info("Boxy re-allowed a distant tracked entity vetoed by another mod's entity culling — first type "
                    + (type == null ? "<null>" : EntityType.getKey(type)));
        }
    }

    private static final AtomicBoolean DIAG_SHARP_DEPTH = new AtomicBoolean();

    /** One-time diagnostic from the depth re-band flush: confirms distant entities render with re-banded depth.
     *  get()-guarded so the per-distant-entity-per-frame caller pays a volatile read, not a CAS, once fired. */
    public static void diagSharpDepth(EntityType<?> type) {
        if (!DIAG_SHARP_DEPTH.get() && DIAG_SHARP_DEPTH.compareAndSet(false, true)) {
            VSSLogger.info("Boxy distant-entity depth re-band ACTIVE (z-fighting fix) — first type "
                    + (type == null ? "<null>" : EntityType.getKey(type)));
        }
    }

    private static final AtomicBoolean DIAG_TICK = new AtomicBoolean();

    /** One-time diagnostic from the distant-entity ticker: confirms moving entities now get interpolated.
     *  get()-guarded so the per-distant-entity-per-tick caller pays a volatile read, not a CAS, once fired. */
    public static void diagDistantTick(EntityType<?> type) {
        if (!DIAG_TICK.get() && DIAG_TICK.compareAndSet(false, true)) {
            VSSLogger.info("Boxy ticking distant entities client-side (movement now syncs) — first type "
                    + (type == null ? "<null>" : EntityType.getKey(type)));
        }
    }

    /** True if the server should extend tracking for this entity type. */
    public static boolean serverContains(EntityType<?> type) {
        Set<EntityType<?>> set = serverSet;
        if (set == null) {
            refreshServerTypes();
            set = serverSet;
        }
        return set.contains(type);
    }

    /** Called once per server tick, not on the per-entity tracking hot path. */
    public static boolean refreshServerTypes() {
        List<String> ids = VSSServerConfig.CONFIG.trackedEntityTypes;
        if (serverSet != null && java.util.Objects.equals(serverIds, ids)) return false;
        serverIds = ids == null ? null : new java.util.ArrayList<>(ids);
        serverSet = resolve(ids, "server trackedEntityTypes");
        return true;
    }

    /** True if the client should render this entity type beyond the vanilla cull distance. */
    public static boolean clientContains(EntityType<?> type) {
        Set<EntityType<?>> synced = syncedSet;
        if (synced != null) {
            return synced.contains(type); // server-synced list wins while connected
        }
        Set<EntityType<?>> set = clientSet;
        if (set == null) {
            set = resolve(VSSClientConfig.CONFIG.renderedEntityTypes, "client renderedEntityTypes");
            clientSet = set;
        }
        return set.contains(type);
    }

    /** Apply the server-pushed entity list (in memory only; the client config file is untouched). */
    public static void setSyncedTypes(List<String> ids) {
        syncedSet = resolve(ids, "server-synced entity types");
    }

    /** Drop the server-synced list, reverting to the local client config. */
    public static void clearSynced() {
        syncedSet = null;
    }

    private static Set<EntityType<?>> resolve(List<String> ids, String which) {
        Set<EntityType<?>> set = new HashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null) {
                    continue;
                }
                // Wildcard: "modid:*" whitelists every registered entity type in that namespace.
                if (id.endsWith(":*")) {
                    String namespace = id.substring(0, id.length() - 2);
                    int before = set.size();
                    for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                        if (key != null && key.getNamespace().equals(namespace)) {
                            set.add(type);
                        }
                    }
                    int added = set.size() - before;
                    if (added == 0) {
                        VSSLogger.warn("Boxy: wildcard '" + id + "' matched no entity types in " + which
                                + " (mod not loaded, or it has no entities)");
                    } else {
                        VSSLogger.info("Boxy: wildcard '" + id + "' added " + added + " entity type(s) in " + which);
                    }
                    continue;
                }
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl == null) {
                    VSSLogger.warn("Boxy: ignoring malformed entity id '" + id + "' in " + which);
                    continue;
                }
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
                if (type == null) {
                    VSSLogger.warn("Boxy: unknown entity type '" + id + "' in " + which);
                    continue;
                }
                set.add(type);
            }
        }
        VSSLogger.info("Boxy: " + which + " resolved to " + set.size() + " entity type(s)");
        return set;
    }
}

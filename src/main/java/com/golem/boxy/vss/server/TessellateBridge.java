package com.golem.boxy.vss.server;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Tessellate region dispatch by reflection; no compile dependency. Absent => {@link #bind} null. */
final class TessellateBridge {
    static final String API = "org.texboobcat.tessellate.api.TessellateApi";
    static final String CONFIG = "org.texboobcat.tessellate.Config";

    private TessellateBridge() {}

    @Nullable
    static LiveSnapshots.Host bind() {
        return bind(API, CONFIG, TessellateBridge.class.getClassLoader());
    }

    /**
     * Host runs inline when {@code asyncRegionLoops} is off, else queues on the region owning the chunk
     * ({@code executeOnRegion}: returns immediately, never leases/blocks).
     */
    @Nullable
    static LiveSnapshots.Host bind(String api, String config, ClassLoader loader) {
        Class<?> type;
        try {
            type = Class.forName(api, true, loader);
        } catch (ClassNotFoundException absent) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> levelType = Class.forName("net.minecraft.server.level.ServerLevel", false, loader);
            Class<?> posType = Class.forName("net.minecraft.core.BlockPos", false, loader);
            MethodHandle execute = MethodHandles.collectArguments(
                    lookup.findStatic(type, "executeOnRegion", MethodType.methodType(void.class, levelType, posType, Runnable.class)),
                    1, lookup.findConstructor(posType, MethodType.methodType(void.class, int.class, int.class, int.class)))
                    .asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class, Runnable.class));
            MethodHandle async = lookup.findStaticGetter(Class.forName(config, true, loader), "asyncRegionLoops", boolean.class);
            return (level, cx, cz, work) -> {
                try {
                    if ((boolean) async.invokeExact()) {
                        execute.invokeExact(level, cx << 4, 0, cz << 4, work);
                    } else {
                        work.run();
                    }
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            };
        } catch (ReflectiveOperationException mismatch) {
            throw new IllegalStateException("Tessellate region API mismatch", mismatch);
        }
    }
}

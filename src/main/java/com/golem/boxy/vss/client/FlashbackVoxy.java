package com.golem.boxy.vss.client;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flashback replays and Voxy on NeoForge. Fabric Voxy stamps its storage path into a recording's metadata as
 * {@code voxy_storage_path} and opens that database, read-only, during playback; the NeoForge Voxy build ships those
 * mixins disabled and its {@code getReplayStoragePath} returns null, so playback opened an empty database in
 * Flashback's temp world and drew no LODs. Boxy's flashback mixins restore it with the same key, so replays carry
 * across loaders. Flashback runs through Sinytra Connector and is not a build dependency, hence the reflection.
 */
public final class FlashbackVoxy {
    private static final Logger LOGGER = LoggerFactory.getLogger("boxy");

    /** Implemented on Flashback's {@code FlashbackMeta} by {@code MixinFlashbackMeta}. */
    public interface Meta {
        File boxy$getVoxyPath();

        void boxy$setVoxyPath(File path);
    }

    private static final Method IS_IN_REPLAY;
    private static final Method GET_REPLAY_SERVER;

    static {
        Method inReplay = null, replayServer = null;
        try {
            Class<?> flashback = Class.forName("com.moulberry.flashback.Flashback");
            inReplay = flashback.getMethod("isInReplay");
            replayServer = flashback.getMethod("getReplayServer");
        } catch (ReflectiveOperationException | LinkageError absent) {
            // no Flashback
        }
        IS_IN_REPLAY = inReplay;
        GET_REPLAY_SERVER = replayServer;
    }

    private FlashbackVoxy() {
    }

    public static boolean inReplay() {
        try {
            return IS_IN_REPLAY != null && (boolean) IS_IN_REPLAY.invoke(null);
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    /** The Voxy database the replay was recorded against, if its metadata names one that still exists. */
    public static Path replayStoragePath() {
        if (GET_REPLAY_SERVER == null) {
            return null;
        }
        try {
            Object server = GET_REPLAY_SERVER.invoke(null);
            if (server == null) {
                return null;
            }
            Object meta = server.getClass().getMethod("getMetadata").invoke(server);
            File path = meta instanceof Meta holder ? holder.boxy$getVoxyPath() : null;
            if (path == null) {
                LOGGER.info("Flashback replay has no voxy_storage_path; no distant LODs in this replay");
                return null;
            }
            if (!path.isDirectory()) {
                LOGGER.warn("Flashback replay's Voxy storage {} no longer exists; no distant LODs in this replay", path);
                return null;
            }
            LOGGER.info("Flashback replay: Voxy LODs from {} (read-only)", path);
            return path.toPath();
        } catch (ReflectiveOperationException failure) {
            LOGGER.warn("Could not read the Flashback replay's Voxy storage path", failure);
            return null;
        }
    }
}

package com.golem.boxy.vss.common.tracking;

/** LOD content edit state on {@code ChunkAccess} (MixinChunkLiveDirty). */
public interface LiveDirtyChunk {
    /** {@code setUnsaved} from a source that changes no LOD content (light, attachments, block-entity data). */
    void boxy$setUnsavedQuietly(boolean unsaved);

    void boxy$markContentEdited();

    /** Content edited since the last take. */
    boolean boxy$takeContentEdited();
}

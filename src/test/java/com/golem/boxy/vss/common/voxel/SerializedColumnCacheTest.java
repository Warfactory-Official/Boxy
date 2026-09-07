package com.golem.boxy.vss.common.voxel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SerializedColumnCacheTest {
    @Test
    void emptyUpdateInvalidatesOldBytesWithoutUnboundedZeroCostEntries() {
        var cache = new SerializedColumnCache(1024);
        cache.put("minecraft:overworld", 42, new byte[]{1, 2, 3});
        assertNotNull(cache.get("minecraft:overworld", 42));
        cache.put("minecraft:overworld", 42, new byte[0]);
        assertNull(cache.get("minecraft:overworld", 42));
    }
}

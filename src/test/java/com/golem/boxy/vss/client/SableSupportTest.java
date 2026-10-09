package com.golem.boxy.vss.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableSupportTest {
    @Test
    void absentInTestEnvironmentAndResetIsSafe() {
        SableSupport.clearCacheForTests();
        assertFalse(SableSupport.isSableLoaded());
        assertDoesNotThrow(SableSupport::resetActiveTextureUnitAfterVoxy);
    }
}

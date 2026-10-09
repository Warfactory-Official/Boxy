package com.golem.boxy.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableVoxyOverrideTest {
    @Test
    void emptyContentAddsDottedOverride() {
        String updated = SableVoxyOverride.ensureOverrideInText("");
        assertEquals("dependencyOverrides.voxy = [\"-sable\"]\n", updated);
    }

    @Test
    void existingSableOverrideIsNoChange() {
        assertNull(SableVoxyOverride.ensureOverrideInText("dependencyOverrides.voxy = [\"-sable\"]\n"));
        assertNull(SableVoxyOverride.ensureOverrideInText("dependencyOverrides.voxy = [\"+other\", \"-sable\"]\n"));
        assertNull(SableVoxyOverride.ensureOverrideInText("[dependencyOverrides]\nvoxy = [\"-sable\"]\n"));
        assertNull(SableVoxyOverride.ensureOverrideInText("dependencyOverrides = { voxy = [\"-sable\"] }\n"));
    }

    @Test
    void dottedArrayMergesWithoutDuplicating() {
        String updated = SableVoxyOverride.ensureOverrideInText("dependencyOverrides.voxy = [\"-other\"]\n");
        assertNotNull(updated);
        assertTrue(updated.contains("\"-other\""));
        assertTrue(updated.contains("\"-sable\""));
        // Second pass is stable.
        assertNull(SableVoxyOverride.ensureOverrideInText(updated));
    }

    @Test
    void dottedSingleStringConvertsToArray() {
        String updated = SableVoxyOverride.ensureOverrideInText("dependencyOverrides.voxy = \"+other\"\n");
        assertNotNull(updated);
        assertTrue(updated.contains("\"+other\""));
        assertTrue(updated.contains("\"-sable\""));
        assertNull(SableVoxyOverride.ensureOverrideInText(updated));
    }

    @Test
    void tableSectionWithoutVoxyInsertsEntry() {
        String input = "[dependencyOverrides]\nother = [\"-foo\"]\n";
        String updated = SableVoxyOverride.ensureOverrideInText(input);
        assertNotNull(updated);
        assertTrue(updated.contains("[dependencyOverrides]"));
        assertTrue(updated.contains("voxy = [\"-sable\"]"));
        assertTrue(updated.contains("other = [\"-foo\"]"));
        assertNull(SableVoxyOverride.ensureOverrideInText(updated));
    }

    @Test
    void tableSectionWithVoxyMerges() {
        String input = "[dependencyOverrides]\nvoxy = [\"-other\"]\n";
        String updated = SableVoxyOverride.ensureOverrideInText(input);
        assertNotNull(updated);
        assertTrue(updated.contains("\"-sable\""));
        assertNull(SableVoxyOverride.ensureOverrideInText(updated));
    }

    @Test
    void inlineEmptyTableRewrites() {
        String updated = SableVoxyOverride.ensureOverrideInText("dependencyOverrides = {}\n");
        assertNotNull(updated);
        assertTrue(updated.contains("voxy = [\"-sable\"]"));
        assertNull(SableVoxyOverride.ensureOverrideInText(updated));
    }

    @Test
    void otherOverridesArePreserved() {
        String input = "earlyWindowControl = true\ndependencyOverrides.other = [\"-foo\"]\n";
        String updated = SableVoxyOverride.ensureOverrideInText(input);
        assertNotNull(updated);
        assertTrue(updated.contains("dependencyOverrides.other = [\"-foo\"]"));
        assertTrue(updated.contains("dependencyOverrides.voxy = [\"-sable\"]"));
        assertNull(SableVoxyOverride.ensureOverrideInText(updated));
    }
}

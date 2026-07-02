package com.golem.boxy.vss.common.processing;

public record LoadedColumnData(int cx, int cz, byte[] serializedSections, int estimatedBytes) {
}

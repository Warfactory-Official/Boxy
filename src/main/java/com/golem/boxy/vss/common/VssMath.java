package com.golem.boxy.vss.common;

/**
 * Small numeric helpers. {@code Math.clamp} is a Java 21 API; Boxy targets Java 17, so the VSS port
 * uses these overloads (matching {@code Math.clamp}'s signatures/return types) instead.
 */
public final class VssMath {
    private VssMath() {}

    public static int clamp(long value, int min, int max) {
        return (int) Math.max((long) min, Math.min((long) max, value));
    }

    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.golem.boxy.vss.client;

/** Bridges a brief loss of light data, never a valid change in environmental lighting. */
final class EntityLightHistory {
    private Object dimension;
    private long sampledAt;
    private int previous;
    private boolean valid;
    private boolean wasPresent;
    private boolean waitingForLight;
    private boolean displayed;
    private int lastDisplayed;
    private long lastTick;
    private Object lightSource;

    void lightSource(Object source) {
        if (lightSource != source) wasPresent = false;
        lightSource = source;
    }

    int sample(Object dimension, long tick, int light, boolean present, boolean ready) {
        if (this.dimension != dimension || tick < lastTick) {
            valid = false;
            displayed = false;
            wasPresent = false;
            waitingForLight = false;
        }
        this.dimension = dimension;
        lastTick = tick;
        if (present && !wasPresent) {
            // A long distant stay may outlive the outgoing cache. Bridge from what was last displayed.
            previous = lastDisplayed;
            valid = displayed;
            sampledAt = tick;
            waitingForLight = true;
        }
        wasPresent = present;
        if (ready) waitingForLight = false;
        if (present && !waitingForLight) {
            previous = light;
            sampledAt = tick;
            valid = true;
        } else if (valid && tick - sampledAt <= 40) {
            light = previous;
        }
        lastDisplayed = light;
        displayed = true;
        return light;
    }
}

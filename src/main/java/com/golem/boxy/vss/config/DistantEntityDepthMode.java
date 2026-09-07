package com.golem.boxy.vss.config;

/**
 * How aggressively the distant-entity depth re-band ({@code DistantEntityDepthFix}) works.
 *
 * <ul>
 *   <li>{@link #OFF} — vanilla depth handling; distant entities z-fight at range.</li>
 *   <li>{@link #BASIC} — the re-banded depth range is exactly the window-depth band the entity truly
 *       occupies: compositing against terrain stays exact, but the band only holds as many depth steps as
 *       the 24-bit buffer physically gives that thin slice (~30 at 32 chunks), so mid-size gaps within a
 *       model can still flicker at long range.</li>
 *   <li>{@link #PRECISE} — multi-pass render (visibility mask → stencil-gated depth clear → fine pass →
 *       depth restore) that keeps compositing exact <b>and</b> spreads the model across the full 24 bits of
 *       depth: essentially perfect internal ordering, at the cost of drawing each distant entity a few
 *       times. Under an active Iris shaderpack it behaves like {@link #BASIC} (the multi-pass needs a
 *       stencil buffer the shader pipeline does not provide).</li>
 * </ul>
 */
public enum DistantEntityDepthMode {
    OFF,
    BASIC,
    PRECISE
}

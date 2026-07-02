package com.golem.boxy.vss.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoizes Voxy's biome→id resolution by holder identity, removing per-call string building from the
 * voxel-ingest hot path.
 *
 * <p><b>The inefficiency.</b> {@code Mapper.getIdForBiome(Holder&lt;Biome&gt;)} resolves the id via
 * {@code biome.unwrapKey().get().location().toString()} — an Optional plus a fresh
 * {@code "namespace:path"} String (StringBuilder + char[] + String) on <em>every</em> call — followed
 * by a string-keyed ConcurrentHashMap lookup. {@code WorldConversionFactory.convert} calls it
 * <b>64&nbsp;times per ingested section</b>, so bulk ingest (world load, Chunky, VSS streaming, the DH
 * importer) churns through thousands of throwaway strings per second. Voxy memoizes the equivalent
 * block-state→id lookups in a thread-local identity cache but never gave biomes the same treatment
 * (the sibling {@code getBaseId} even carries Voxy's {@code //TODO:FIXME: IS VERY SLOW}).
 *
 * <p><b>The fix.</b> An identity-keyed cache in front: registry {@code Holder.Reference} instances are
 * canonical for a world session, and a {@code Mapper} only ever appends id mappings, so holder→id can
 * never go stale for a given Mapper. The cache is a per-Mapper instance field (it dies with the world
 * engine), bounded by the biome count (a few hundred entries), and racing misses both resolve to the
 * same id ({@code registerNewBiome} is lock-guarded and idempotent) so {@code putIfAbsent} is safe.
 * Cache hits skip the whole allocation chain for one identity-hash map read.
 *
 * <p>Targeted by string so no Voxy compile dependency is needed; {@code remap = false} (Voxy class);
 * {@code require = 0} so it degrades to Voxy's original (correct, just slower) path if the signature
 * ever changes.
 */
@Mixin(targets = "me.cortex.voxy.common.world.other.Mapper", remap = false)
public class MixinVoxyMapperBiomeCache {

    @Unique
    private final ConcurrentHashMap<Holder<Biome>, Integer> boxy$biomeIdCache = new ConcurrentHashMap<>();

    @Inject(method = "getIdForBiome", at = @At("HEAD"), cancellable = true, require = 0)
    private void boxy$cachedBiomeId(Holder<Biome> biome, CallbackInfoReturnable<Integer> cir) {
        Integer cached = this.boxy$biomeIdCache.get(biome);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getIdForBiome", at = @At("RETURN"), require = 0)
    private void boxy$storeBiomeId(Holder<Biome> biome, CallbackInfoReturnable<Integer> cir) {
        // Only reached on a cache miss: injection points are resolved against the original method
        // before any are applied, so the HEAD callback's synthesized return is not instrumented here.
        this.boxy$biomeIdCache.putIfAbsent(biome, cir.getReturnValueI());
    }
}

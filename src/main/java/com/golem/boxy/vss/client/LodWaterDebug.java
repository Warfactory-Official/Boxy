package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * {@code /boxylod probe | audit [radius] | sweep [steps] [pitch] [settleTicks]}, client side (pitch: positive looks down): measures dark LOD water.
 * Probe and audit read Voxy's stored data ({@link LodWaterData}); sweep measures the screen ({@link LodWaterSweep}).
 */
public final class LodWaterDebug {
    private LodWaterDebug() {
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("boxylod")
                        .then(Commands.literal("probe").executes(LodWaterDebug::probe))
                        .then(Commands.literal("audit").executes(context -> audit(context, 256))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(32, 4096))
                                        .executes(context -> audit(context, IntegerArgumentType.getInteger(context, "radius")))))
                        .then(Commands.literal("sweep").executes(context -> sweep(context, 8, 35f, 60))
                                .then(Commands.argument("steps", IntegerArgumentType.integer(1, 72))
                                        .executes(context -> sweep(context, IntegerArgumentType.getInteger(context, "steps"), 35f, 60))
                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90, 90))
                                                .executes(context -> sweep(context, IntegerArgumentType.getInteger(context, "steps"),
                                                        FloatArgumentType.getFloat(context, "pitch"), 60))
                                                .then(Commands.argument("settleTicks", IntegerArgumentType.integer(5, 1200))
                                                        .executes(context -> sweep(context, IntegerArgumentType.getInteger(context, "steps"),
                                                                FloatArgumentType.getFloat(context, "pitch"),
                                                                IntegerArgumentType.getInteger(context, "settleTicks")))))))));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> LodWaterSweep.tick(Minecraft.getInstance()));
    }

    private static int probe(CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 look = mc.player.getViewVector(1f);
        return withEngine(context, engine -> LodWaterData.probe(engine, eye, look, 8192));
    }

    private static int audit(CommandContext<CommandSourceStack> context, int radius) {
        Minecraft mc = Minecraft.getInstance();
        int x = mc.player.getBlockX(), z = mc.player.getBlockZ();
        int minY = mc.level.getMinBuildHeight(), maxY = mc.level.getMaxBuildHeight();
        say(context, "Auditing LOD water within " + radius + " blocks...");
        return withEngine(context, engine -> LodWaterData.audit(engine, x, z, radius, minY, maxY).stream().map(String::valueOf).toList());
    }

    private static int sweep(CommandContext<CommandSourceStack> context, int steps, float pitch, int settle) {
        say(context, LodWaterSweep.start(Minecraft.getInstance(), steps, pitch, settle));
        return 1;
    }

    /** Runs a read of Voxy's database off the render thread, holding a reference on the world engine meanwhile. */
    private static int withEngine(CommandContext<CommandSourceStack> context, Function<WorldEngine, List<String>> read) {
        Minecraft mc = Minecraft.getInstance();
        VoxyInstance voxy = VoxyCommon.getInstance();
        WorldIdentifier id = mc.level == null ? null : WorldIdentifier.of(mc.level);
        WorldEngine engine = voxy == null || !voxy.isRunning() || id == null ? null : voxy.getOrCreate(id, true);
        if (engine == null) {
            say(context, "Voxy has no world for this level.");
            return 0;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return read.apply(engine);
            } finally {
                engine.releaseRef();
            }
        }).whenComplete((lines, failure) -> mc.execute(() -> {
            if (failure != null) {
                VSSLogger.error("LOD water debug read failed", failure);
                say(context, "Failed: " + failure);
                return;
            }
            for (String line : lines) {
                VSSLogger.info("[boxylod] " + line);
                say(context, line);
            }
        }));
        return 1;
    }

    private static void say(CommandContext<CommandSourceStack> context, String text) {
        context.getSource().sendSuccess(() -> Component.literal(text), false);
    }
}

package com.golem.boxy.seedlod;

import com.golem.boxy.seedlod.fill.SeedFiller;
import com.golem.boxy.seedlod.share.SharingClient;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /boxyseed status | on | off | serverseed <seed>}, client side. */
final class SeedLodsCommand {
   private SeedLodsCommand() {
   }

   static LiteralArgumentBuilder<CommandSourceStack> tree() {
      return Commands.literal("boxyseed")
         .then(Commands.literal("status").executes(context -> reply(context, SeedFiller.status())))
         .then(Commands.literal("on").executes(context -> toggle(context, true)))
         .then(Commands.literal("off").executes(context -> toggle(context, false)))
         .then(Commands.literal("serverseed").then(Commands.argument("seed", LongArgumentType.longArg())
            .executes(context -> reply(context, SharingClient.useTypedSeed(LongArgumentType.getLong(context, "seed"))))));
   }

   private static int toggle(CommandContext<CommandSourceStack> context, boolean on) {
      SeedLodsConfig config = SeedLodsConfig.get();
      config.enabled = on;
      config.saveClamped();
      if (!on) {
         SeedFiller.stop();
      }
      return reply(context, on ? "Seed LODs on." : "Seed LODs off.");
   }

   private static int reply(CommandContext<CommandSourceStack> context, String text) {
      context.getSource().sendSuccess(() -> Component.literal(text), false);
      return 1;
   }
}

package net.fabricmc.fabric.api.client.command.v2;

import net.minecraft.commands.SharedSuggestionProvider;

// Stub so Voxy's own VoxyCommands class links if it is ever loaded. Boxy uses its own
// BoxyCommands against CommandSourceStack, so this is only a linkage safety net.
public interface FabricClientCommandSource extends SharedSuggestionProvider {
}

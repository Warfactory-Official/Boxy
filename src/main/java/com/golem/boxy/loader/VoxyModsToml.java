package com.golem.boxy.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Synthesizes a Forge {@code META-INF/mods.toml} from Voxy's {@code fabric.mod.json}. This is the
 * Forge 1.20.1 analogue of Foxy's {@code FoxyFabricModReader.buildModsToml} (which targeted
 * {@code neoforge.mods.toml}). The most important detail is emitting
 * {@code [modproperties.voxy] commit = "..."}: Voxy's {@code VoxyCommon} static initializer reads
 * that custom value through {@link BoxyFabricLoaderImpl}, and throws if it is missing.
 */
final class VoxyModsToml {
    private VoxyModsToml() {}

    static String build(String fabricModJson) {
        JsonObject fmj = JsonParser.parseString(fabricModJson).getAsJsonObject();

        String modId = string(fmj, "id", "voxy");
        String version = string(fmj, "version", "0.0.0");
        String name = string(fmj, "name", modId);
        String description = string(fmj, "description", "");
        String icon = string(fmj, "icon", null);
        String authors = joinAuthors(fmj);
        String commit = readCommit(fmj);

        StringBuilder sb = new StringBuilder();
        // Voxy has no @Mod class of its own, but Boxy injects one (com.golem.boxy.cmd.
        // VoxyBridgeMod, annotated @Mod("voxy")) to register the /voxy command, so we use "javafml".
        // That bridge mod is defensive — if it ever fails, it cannot stop Voxy loading/rendering.
        sb.append("modLoader = \"javafml\"\n");
        sb.append("loaderVersion = \"[47,)\"\n");
        sb.append("license = \"").append(esc(string(fmj, "license", "All-Rights-Reserved"))).append("\"\n");
        sb.append("\n[[mods]]\n");
        sb.append("modId = \"").append(esc(modId)).append("\"\n");
        sb.append("version = \"").append(esc(version)).append("\"\n");
        sb.append("displayName = \"").append(esc(name)).append("\"\n");
        if (icon != null) {
            sb.append("logoFile = \"").append(esc(icon)).append("\"\n");
        }
        if (!authors.isEmpty()) {
            sb.append("authors = \"").append(esc(authors)).append("\"\n");
        }
        // Voxy is a client renderer; never trip the server-version handshake.
        sb.append("displayTest = \"IGNORE_ALL_VERSION\"\n");
        if (!description.isEmpty()) {
            sb.append("description = '''").append(description).append("'''\n");
        }

        // NOTE: "Boxy" used to be a second [[mods]] entry here, backed by an empty @Mod("boxy") smuggled
        // into Voxy's jar. It is now its own standalone game-layer mod jar (returned separately by
        // BoxyModLocator), so this synthesized Voxy mods.toml declares only "voxy".

        // Custom values Voxy reads back through FabricLoader.getModContainer("voxy").
        sb.append("\n[modproperties.").append(modId).append("]\n");
        sb.append("commit = \"").append(esc(commit)).append("\"\n");

        // Dependencies (Forge syntax differs from NeoForge: mandatory=true, versionRange).
        appendDependency(sb, modId, "forge", "[47,)", "NONE", "BOTH");
        appendDependency(sb, modId, "minecraft", "[1.20.1,1.21)", "NONE", "BOTH");
        // Boxy loads before embeddium so Voxy's sodium mixins see embeddium's classes.
        appendDependency(sb, modId, "embeddium", "[0.3,)", "AFTER", "CLIENT");

        // NOTE: Voxy's mixin configs are NOT registered here. Forge 1.20.1 does not reliably apply
        // mixin configs declared via mods.toml [[mixins]] — working Forge mods (e.g. Embeddium) use the
        // jar manifest's "MixinConfigs" attribute instead, which is what VoxyRemapper writes.
        return sb.toString();
    }

    /** The mixin config file names Voxy declares in its fabric.mod.json, for the manifest attribute. */
    static List<String> mixinConfigs(String fabricModJson) {
        return readMixins(JsonParser.parseString(fabricModJson).getAsJsonObject());
    }

    private static void appendDependency(StringBuilder sb, String modId, String depId, String range, String ordering, String side) {
        sb.append("\n[[dependencies.").append(modId).append("]]\n");
        sb.append("modId = \"").append(depId).append("\"\n");
        sb.append("mandatory = true\n");
        sb.append("versionRange = \"").append(range).append("\"\n");
        sb.append("ordering = \"").append(ordering).append("\"\n");
        sb.append("side = \"").append(side).append("\"\n");
    }

    private static List<String> readMixins(JsonObject fmj) {
        List<String> result = new ArrayList<>();
        JsonElement mixins = fmj.get("mixins");
        if (mixins != null && mixins.isJsonArray()) {
            for (JsonElement el : mixins.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    result.add(el.getAsString());
                } else if (el.isJsonObject() && el.getAsJsonObject().has("config")) {
                    result.add(el.getAsJsonObject().get("config").getAsString());
                }
            }
        }
        return result;
    }

    private static String readCommit(JsonObject fmj) {
        JsonElement custom = fmj.get("custom");
        if (custom != null && custom.isJsonObject()) {
            JsonElement commit = custom.getAsJsonObject().get("commit");
            if (commit != null && commit.isJsonPrimitive()) {
                String c = commit.getAsString();
                if (!c.startsWith("$")) return c;
            }
        }
        return "boxycompat0000000000000000000000000000000";
    }

    private static String joinAuthors(JsonObject fmj) {
        JsonElement authors = fmj.get("authors");
        if (authors == null || !authors.isJsonArray()) return "";
        StringJoiner sj = new StringJoiner(", ");
        for (JsonElement el : authors.getAsJsonArray()) {
            if (el.isJsonPrimitive()) {
                sj.add(el.getAsString());
            } else if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                sj.add(el.getAsJsonObject().get("name").getAsString());
            }
        }
        return sj.toString();
    }

    private static String string(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        String s = el.getAsString();
        return s.startsWith("$") ? fallback : s;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

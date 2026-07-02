package com.golem.boxy.loader;

import net.fabricmc.tinyremapper.IMappingProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@code intermediary -> srg} mapping that turns Voxy's published (Fabric intermediary)
 * bytecode into Forge 1.20.1 runtime names (official class names + SRG {@code f_/m_} members).
 *
 * <p>It composes two bundled inputs that both pivot on obfuscated (notch) names:
 * <ul>
 *   <li>{@code boxy/mappings/intermediary.tiny} — Fabric intermediary (tiny v2: {@code official=obf} ↔ {@code intermediary}).</li>
 *   <li>{@code boxy/mappings/obf-srg.tsrg} — Forge's {@code extractSrg} output (tsrg2: {@code obf} → {@code srg};
 *       the srg <em>class</em> name is the official name, the srg <em>member</em> name is {@code f_/m_}).</li>
 * </ul>
 * Joining them on obf yields exactly Forge's runtime namespace. The result is exposed as a
 * tiny-remapper {@link IMappingProvider} keyed in the intermediary (source) namespace.
 */
public final class BoxyMappings {
    private BoxyMappings() {}

    private static final String INTERMEDIARY = "/boxy/mappings/intermediary.tiny";
    private static final String OBF_SRG = "/boxy/mappings/obf-srg.tsrg";
    private static final String OFFICIAL_SRG = "/boxy/mappings/official-srg.tsrg";

    // ---- parsed intermediary (tiny v2) ----
    private final Map<String, String> obfToIntClass = new HashMap<>();
    private final List<MemberLine> intFields = new ArrayList<>();
    private final List<MemberLine> intMethods = new ArrayList<>();

    // ---- parsed obf->srg (tsrg2) ----
    private final Map<String, String> obfToSrgClass = new HashMap<>();
    // obfOwner -> (obfFieldName -> srgName)
    private final Map<String, Map<String, String>> srgFields = new HashMap<>();
    // obfOwner -> (obfMethodName+obfDesc -> srgName)
    private final Map<String, Map<String, String>> srgMethods = new HashMap<>();

    // ---- parsed official->srg (createMcpToSrg tsrg2), for methods intermediary leaves official-named ----
    private final List<McpMethod> mcpMethods = new ArrayList<>();
    private final List<McpField> mcpFields = new ArrayList<>();

    private record MemberLine(String obfOwner, String obfName, String obfDesc, String intName) {}
    private record McpMethod(String officialOwner, String name, String officialDesc, String srg) {}
    private record McpField(String officialOwner, String name, String srg) {}

    /** Composes the bundled mappings and returns the intermediary->srg provider (for remapping Voxy). */
    public static IMappingProvider intermediaryToSrg() {
        return load().emit(false);
    }

    /**
     * Returns the reverse srg->intermediary provider. Used to convert Forge's runtime (SRG) Minecraft
     * jar into an intermediary-named classpath, which tiny-remapper needs to resolve Voxy's inherited
     * member calls and mixin targets during {@link #intermediaryToSrg()} remapping.
     */
    public static IMappingProvider srgToIntermediary() {
        return load().emit(true);
    }

    /** Intermediary internal class name -> srg (official) internal class name, for selector fixups. */
    public static Map<String, String> classMap() {
        BoxyMappings m = load();
        Map<String, String> map = new HashMap<>();
        for (var e : m.obfToIntClass.entrySet()) {
            String srg = m.obfToSrgClass.get(e.getKey());
            if (srg != null) map.put(e.getValue(), srg);
        }
        return map;
    }

    /**
     * Intermediary member name ({@code method_NNN}/{@code field_NNN}) -> srg name ({@code m_/f_}).
     * Intermediary member ids are globally unique, so this needs no owner context — used to fix
     * member names left in mixin selectors that tiny-remapper's MixinExtension doesn't touch (e.g.
     * MixinExtras {@code @WrapMethod}/{@code @WrapOperation} {@code method=} values).
     */
    public static Map<String, String> memberNameMap() {
        BoxyMappings m = load();
        Map<String, String> map = new HashMap<>();
        for (MemberLine f : m.intFields) {
            Map<String, String> byName = m.srgFields.get(f.obfOwner());
            String srg = byName == null ? null : byName.get(f.obfName());
            if (srg != null) map.put(f.intName(), srg);
        }
        for (MemberLine mth : m.intMethods) {
            Map<String, String> byKey = m.srgMethods.get(mth.obfOwner());
            String srg = byKey == null ? null : byKey.get(mth.obfName() + mth.obfDesc());
            if (srg != null) map.put(mth.intName(), srg);
        }
        return map;
    }

    /**
     * Returns an official->srg provider (methods + fields) for remapping Boxy's own dev-compiled command
     * classes, which are injected into Voxy's jar. MC class names are identical in both namespaces, so
     * only members need mapping; use with {@code ignoreFieldDesc(true)} since field descriptors are absent.
     */
    public static IMappingProvider officialToSrg() {
        BoxyMappings m = load();
        return out -> {
            for (McpMethod mm : m.mcpMethods) {
                out.acceptMethod(new IMappingProvider.Member(mm.officialOwner(), mm.name(), mm.officialDesc()), mm.srg());
            }
            for (McpField mf : m.mcpFields) {
                out.acceptField(new IMappingProvider.Member(mf.officialOwner(), mf.name(), null), mf.srg());
            }
        };
    }

    private static BoxyMappings load() {
        BoxyMappings m = new BoxyMappings();
        try {
            m.parseIntermediary();
            m.parseObfSrg();
            m.parseOfficialSrg();
        } catch (IOException e) {
            throw new RuntimeException("Boxy: failed to load bundled mappings", e);
        }
        return m;
    }

    private IMappingProvider emit(boolean reverse) {
        return out -> {
            // Classes.
            for (var e : obfToIntClass.entrySet()) {
                String srg = obfToSrgClass.get(e.getKey());
                if (srg == null) continue;
                if (reverse) out.acceptClass(srg, e.getValue());
                else out.acceptClass(e.getValue(), srg);
            }
            // Fields.
            for (MemberLine f : intFields) {
                Map<String, String> byName = srgFields.get(f.obfOwner());
                String srg = byName == null ? null : byName.get(f.obfName());
                String owner = reverse ? obfToSrgClass.get(f.obfOwner()) : obfToIntClass.get(f.obfOwner());
                if (srg == null || owner == null) continue;
                if (reverse) {
                    out.acceptField(new IMappingProvider.Member(owner, srg, remapDesc(f.obfDesc(), true)), f.intName());
                } else {
                    out.acceptField(new IMappingProvider.Member(owner, f.intName(), remapDesc(f.obfDesc(), false)), srg);
                }
            }
            // Methods. Collect every srg method name the tiny renames (anywhere): the official-name
            // fallback below must only touch methods intermediary genuinely leaves official-named, or it
            // would conflict with intermediary's inherited names (which propagate across the hierarchy).
            Set<String> tinySrgNames = new HashSet<>();
            for (MemberLine mth : intMethods) {
                Map<String, String> byKey = srgMethods.get(mth.obfOwner());
                String srg = byKey == null ? null : byKey.get(mth.obfName() + mth.obfDesc());
                String owner = reverse ? obfToSrgClass.get(mth.obfOwner()) : obfToIntClass.get(mth.obfOwner());
                if (srg == null || owner == null) continue;
                tinySrgNames.add(srg);
                if (reverse) {
                    out.acceptMethod(new IMappingProvider.Member(owner, srg, remapDesc(mth.obfDesc(), true)), mth.intName());
                } else {
                    out.acceptMethod(new IMappingProvider.Member(owner, mth.intName(), remapDesc(mth.obfDesc(), false)), srg);
                }
            }
            // Fallback for methods Fabric intermediary leaves official-named (e.g. functional-interface
            // SAMs like BlockColor.getColor -> m_92566_): map (intermediary owner, OFFICIAL name,
            // intermediary desc) -> srg, sourced from createMcpToSrg. Skip any srg the tiny already names.
            Map<String, String> officialToInt = new HashMap<>();
            for (var e : obfToIntClass.entrySet()) {
                String srg = obfToSrgClass.get(e.getKey());
                if (srg != null) officialToInt.put(srg, e.getValue());
            }
            for (McpMethod mm : mcpMethods) {
                if (tinySrgNames.contains(mm.srg())) continue; // renamed by intermediary -> not a fallback case
                String intOwner = officialToInt.get(mm.officialOwner());
                if (intOwner == null) continue;
                if (reverse) {
                    out.acceptMethod(new IMappingProvider.Member(mm.officialOwner(), mm.srg(), mm.officialDesc()), mm.name());
                } else {
                    out.acceptMethod(new IMappingProvider.Member(intOwner, mm.name(), remapDescVia(mm.officialDesc(), officialToInt)), mm.srg());
                }
            }
        };
    }

    /** Remaps a descriptor's class types via the given class map (others passed through). */
    private String remapDescVia(String desc, Map<String, String> classMap) {
        StringBuilder sb = new StringBuilder(desc.length());
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                String type = desc.substring(i + 1, end);
                sb.append('L').append(classMap.getOrDefault(type, type)).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Remaps a descriptor's class types from obf to the source namespace (intermediary, or srg if reverse). */
    private String remapDesc(String obfDesc, boolean toSrg) {
        Map<String, String> classMap = toSrg ? obfToSrgClass : obfToIntClass;
        StringBuilder sb = new StringBuilder(obfDesc.length());
        int i = 0;
        while (i < obfDesc.length()) {
            char c = obfDesc.charAt(i);
            if (c == 'L') {
                int end = obfDesc.indexOf(';', i);
                String obfType = obfDesc.substring(i + 1, end);
                sb.append('L').append(classMap.getOrDefault(obfType, obfType)).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private void parseIntermediary() throws IOException {
        try (BufferedReader r = open(INTERMEDIARY)) {
            String line;
            String curObf = null;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; } // header
                String[] t = line.split("\t", -1);
                if (t.length >= 3 && t[0].equals("c")) {
                    curObf = t[1];
                    obfToIntClass.put(t[1], t[2]);
                } else if (curObf != null && t.length >= 5 && t[0].isEmpty()) {
                    // "\t f|m \t <obfDesc> \t <obfName> \t <intName>"
                    String kind = t[1];
                    if (kind.equals("f")) {
                        intFields.add(new MemberLine(curObf, t[3], t[2], t[4]));
                    } else if (kind.equals("m")) {
                        intMethods.add(new MemberLine(curObf, t[3], t[2], t[4]));
                    }
                }
            }
        }
    }

    private void parseObfSrg() throws IOException {
        try (BufferedReader r = open(OBF_SRG)) {
            String line;
            String curObf = null;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; } // "tsrg2 left right"
                int depth = 0;
                while (depth < line.length() && line.charAt(depth) == '\t') depth++;
                String body = line.substring(depth);
                String[] t = body.split(" ");
                if (depth == 0) {
                    if (t.length >= 2) {
                        curObf = t[0];
                        obfToSrgClass.put(t[0], t[1]);
                    }
                } else if (depth == 1 && curObf != null) {
                    if (t.length == 2) { // field: obfName srgName
                        srgFields.computeIfAbsent(curObf, k -> new HashMap<>()).put(t[0], t[1]);
                    } else if (t.length == 3) { // method: obfName obfDesc srgName
                        srgMethods.computeIfAbsent(curObf, k -> new HashMap<>()).put(t[0] + t[1], t[2]);
                    }
                }
                // depth >= 2 (params / static) ignored
            }
        }
    }

    private void parseOfficialSrg() throws IOException {
        try (BufferedReader r = open(OFFICIAL_SRG)) {
            String line;
            String curOfficial = null;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; } // "tsrg2 left right"
                int depth = 0;
                while (depth < line.length() && line.charAt(depth) == '\t') depth++;
                String[] t = line.substring(depth).split(" ");
                if (depth == 0) {
                    if (t.length >= 1) curOfficial = t[0]; // official class (left column)
                } else if (depth == 1 && curOfficial != null) {
                    if (t.length == 3) {        // method: officialName officialDesc srgName
                        mcpMethods.add(new McpMethod(curOfficial, t[0], t[1], t[2]));
                    } else if (t.length == 2) { // field: officialName srgName
                        mcpFields.add(new McpField(curOfficial, t[0], t[1]));
                    }
                }
            }
        }
    }

    private static BufferedReader open(String resource) throws IOException {
        InputStream is = BoxyMappings.class.getResourceAsStream(resource);
        if (is == null) {
            throw new IOException("Bundled mapping resource missing: " + resource);
        }
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }
}

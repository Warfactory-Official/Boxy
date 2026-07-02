package com.golem.boxy.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processes Voxy's mixin classes to remap intermediary Minecraft class names left inside mixin
 * annotation selector strings (e.g. {@code @Inject(method = "<init>(Lnet/minecraft/class_638;...)V")}).
 *
 * <p>tiny-remapper's MixinExtension remaps such selectors only for mixins that target a mapped (MC)
 * class. Voxy's {@code sodium.*} mixins target third-party classes (e.g. {@code RenderSectionManager}),
 * so the MC types baked into their selectors by Loom's static remap stay in intermediary and the
 * injection fails to match at runtime. This pass rewrites those {@code net/minecraft/class_NNN} tokens
 * to official names so the selectors resolve against Embeddium's (officially-named) classes.
 */
final class MixinSelectorRemapper {
    // Matches intermediary class internal names and bare member ids inside selector strings.
    private static final Pattern TOKEN = Pattern.compile("net/minecraft/class_[0-9]+(\\$class_[0-9]+)*|method_[0-9]+|field_[0-9]+");

    private final Map<String, String> classMap;
    private final Map<String, String> memberMap;

    MixinSelectorRemapper(Map<String, String> classMap, Map<String, String> memberMap) {
        this.classMap = classMap;
        this.memberMap = memberMap;
    }

    byte[] fix(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean[] changed = {false};

        fixAnnotations(cn.visibleAnnotations, changed);
        fixAnnotations(cn.invisibleAnnotations, changed);
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                fixAnnotations(mn.visibleAnnotations, changed);
                fixAnnotations(mn.invisibleAnnotations, changed);
            }
        }
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                fixAnnotations(fn.visibleAnnotations, changed);
                fixAnnotations(fn.invisibleAnnotations, changed);
            }
        }
        if (!changed[0]) {
            return bytes;
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private void fixAnnotations(List<AnnotationNode> anns, boolean[] changed) {
        if (anns == null) return;
        for (AnnotationNode an : anns) {
            fixAnnotation(an, changed);
        }
    }

    private void fixAnnotation(AnnotationNode an, boolean[] changed) {
        if (an == null || an.values == null) return;
        for (int i = 1; i < an.values.size(); i += 2) {
            an.values.set(i, fixValue(an.values.get(i), changed));
        }
    }

    private Object fixValue(Object v, boolean[] changed) {
        if (v instanceof String s) {
            String r = remapTokens(s);
            if (!r.equals(s)) changed[0] = true;
            return r;
        } else if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(fixValue(o, changed));
            }
            return out;
        } else if (v instanceof AnnotationNode an) {
            fixAnnotation(an, changed);
        }
        return v;
    }

    private String remapTokens(String s) {
        if (!s.contains("class_") && !s.contains("method_") && !s.contains("field_")) return s;
        Matcher m = TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tok = m.group();
            String repl = tok.startsWith("net/minecraft/class_")
                    ? classMap.getOrDefault(tok, tok)
                    : memberMap.getOrDefault(tok, tok);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

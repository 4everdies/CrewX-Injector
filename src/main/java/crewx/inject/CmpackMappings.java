package crewx.inject;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External CMPack 1.8.8 profile used by the JVMTI remapping route.
 * The JSON can be replaced without rebuilding the loader or the payload.
 */
final class CmpackMappings {
    static final String PROFILE_PROPERTY = "crewx.cmpack.profile";
    static final String FORMAT = "crewx-cmpack-profile/1";
    static final String HOST = "CMPack 1.8.8 2.8.11-beta";

    private static volatile CmpackMappings instance;

    final Map<String, String> namedToObf = new HashMap<String, String>();
    final Map<String, String> obfToNamed = new HashMap<String, String>();
    final Map<String, String> namedMethods = new HashMap<String, String>();
    final Map<String, String> obfMethods = new HashMap<String, String>();
    final Map<String, String> namedFields = new HashMap<String, String>();
    final Map<String, String> obfFields = new HashMap<String, String>();
    final Map<String, List<String>> namedParents = new HashMap<String, List<String>>();
    final Map<String, List<String>> obfParents = new HashMap<String, List<String>>();
    final String path;

    private CmpackMappings(String path) {
        this.path = path;
    }

    static CmpackMappings get() {
        CmpackMappings current = instance;
        if (current == null) {
            synchronized (CmpackMappings.class) {
                current = instance;
                if (current == null) {
                    File explicitProfile = explicitProfile();
                    JsonObject remoteProfile = explicitProfile == null
                            ? MappingRepository.profile("cmpack") : null;
                    if (explicitProfile != null) {
                        try {
                            current = load(explicitProfile);
                        } catch (IOException error) {
                            throw new IllegalStateException("Cannot read CMPack profile "
                                    + explicitProfile + ": " + error.getMessage(), error);
                        }
                    } else if (remoteProfile != null) {
                        current = load(remoteProfile, MappingRepository.DOCUMENT_URL + "#/profiles/cmpack");
                    } else {
                        File file = resolveProfile();
                        if (file == null) {
                            throw new IllegalStateException("CMPack profile not found in the remote "
                                    + "mappings.json or locally. Put cmpack-mappings.json next to "
                                    + "CrewXInjector.exe or in .minecraft/CrewX/. Set "
                                    + PROFILE_PROPERTY + " to override.");
                        }
                        try {
                            current = load(file);
                        } catch (IOException error) {
                            throw new IllegalStateException("Cannot read CMPack profile " + file
                                    + ": " + error.getMessage(), error);
                        }
                    }
                    instance = current;
                }
            }
        }
        return current;
    }

    String className(String named) {
        String internal = named.replace('.', '/');
        String mapped = namedToObf.get(internal);
        return (mapped == null ? internal : mapped).replace('/', '.');
    }

    String methodName(String owner, String name, String descriptor) {
        String key = owner.replace('.', '/') + "/" + name + " " + descriptor;
        String mapped = namedMethods.get(key);
        if (mapped == null) mapped = inherited(namedMethods, namedParents,
                owner.replace('.', '/'), name + " " + descriptor);
        return mapped == null ? name : mapped;
    }

    String fieldName(String owner, String name) {
        String normalized = owner.replace('.', '/');
        String mapped = namedFields.get(normalized + "/" + name);
        if (mapped == null) mapped = inherited(namedFields, namedParents, normalized, name);
        return mapped == null ? name : mapped;
    }

    boolean matchesMethod(String namedName, Method candidate) {
        String owner = candidate.getDeclaringClass().getName().replace('.', '/');
        String suffix = candidate.getName() + " " + Type.getMethodDescriptor(candidate);
        String mapped = obfMethods.get(owner + "/" + suffix);
        if (mapped == null) mapped = inherited(obfMethods, obfParents, owner, suffix);
        return namedName.equals(mapped);
    }

    byte[] toNamed(byte[] bytes) {
        final Remapper remapper = new Remapper() {
            @Override
            public String map(String name) {
                return obfToNamed.containsKey(name) ? obfToNamed.get(name) : name;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (name.startsWith("<")) return name;
                String mapped = obfMethods.get(owner + "/" + name + " " + descriptor);
                if (mapped == null) mapped = inherited(obfMethods, obfParents, owner,
                        name + " " + descriptor);
                return mapped == null ? name : mapped;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String mapped = obfFields.get(owner + "/" + name);
                if (mapped == null) mapped = inherited(obfFields, obfParents, owner, name);
                return mapped == null ? name : mapped;
            }
        };
        return remap(bytes, remapper);
    }

    byte[] toNotch(byte[] bytes) {
        final Remapper remapper = new Remapper() {
            @Override
            public String map(String name) {
                return namedToObf.containsKey(name) ? namedToObf.get(name) : name;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (name.startsWith("<")) return name;
                String mapped = namedMethods.get(owner + "/" + name + " " + descriptor);
                if (mapped == null) mapped = inherited(namedMethods, namedParents, owner,
                        name + " " + descriptor);
                return mapped == null ? name : mapped;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String mapped = namedFields.get(owner + "/" + name);
                if (mapped == null) mapped = inherited(namedFields, namedParents, owner, name);
                return mapped == null ? name : mapped;
            }
        };
        return remap(bytes, remapper);
    }

    private static byte[] remap(byte[] bytes, Remapper remapper) {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        reader.accept(new org.objectweb.asm.commons.ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static File resolveProfile() {
        String configured = System.getProperty(PROFILE_PROPERTY);
        if (configured != null) {
            File file = new File(configured);
            if (file.isFile()) return file.getAbsoluteFile();
        }
        List<File> candidates = new ArrayList<File>();
        candidates.add(new File("cmpack-mappings.json"));
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            File minecraft = new File(appData, ".minecraft");
            candidates.add(new File(minecraft, "CrewX/cmpack-mappings.json"));
            candidates.add(new File(minecraft, "versions/cmpack_1.8.8/cmpack-mappings.json"));
        }
        candidates.add(new File(System.getProperty("java.io.tmpdir"), "CrewX21/cmpack-mappings.json"));
        candidates.add(new File(System.getProperty("java.io.tmpdir"), "CrewX/cmpack-mappings.json"));
        for (File candidate : candidates) {
            if (candidate.isFile()) return candidate.getAbsoluteFile();
        }
        return null;
    }

    private static File explicitProfile() {
        String configured = System.getProperty(PROFILE_PROPERTY);
        if (configured == null || configured.length() == 0) return null;
        File file = new File(configured);
        return file.isFile() ? file.getAbsoluteFile() : null;
    }

    private static CmpackMappings load(File file) throws IOException {
        Reader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("CMPack profile root must be a JSON object");
            }
            return load(parsed.getAsJsonObject(), file.getAbsolutePath());
        } finally {
            reader.close();
        }
    }

    private static CmpackMappings load(JsonObject root, String path) {
        CmpackMappings result = new CmpackMappings(path);
        String format = string(root, "format");
        if (!FORMAT.equals(format)) {
            throw new IllegalStateException("CMPack profile format is '" + format
                    + "', expected '" + FORMAT + "'");
        }
        String host = string(root, "host");
        if (!HOST.equals(host)) {
            throw new IllegalStateException("CMPack profile host is '" + host
                    + "', expected '" + HOST + "'");
        }
        copyObject(root, "classes", result.namedToObf);
        copyObject(root, "methods", result.namedMethods);
        copyObject(root, "fields", result.namedFields);
        JsonElement parents = root.get("parents");
        if (parents != null && parents.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : parents.getAsJsonObject().entrySet()) {
                List<String> list = new ArrayList<String>();
                if (entry.getValue().isJsonArray()) {
                    for (JsonElement parent : entry.getValue().getAsJsonArray()) {
                        if (parent.isJsonPrimitive()) list.add(parent.getAsString());
                    }
                }
                result.namedParents.put(entry.getKey(), list);
            }
        }
        if (result.namedToObf.size() < 1500 || result.namedMethods.size() < 9000
                || result.namedFields.size() < 4000) {
            throw new IllegalStateException("CMPack profile is truncated (classes="
                    + result.namedToObf.size() + " methods=" + result.namedMethods.size()
                    + " fields=" + result.namedFields.size() + ")");
        }
        if (!"ir".equals(result.namedToObf.get("net/minecraft/client/Minecraft"))) {
            throw new IllegalStateException("CMPack profile does not map Minecraft to ir");
        }
        if (!result.namedMethods.containsKey(
                "net/minecraft/client/Minecraft/getMinecraft ()Lnet/minecraft/client/Minecraft;")) {
            throw new IllegalStateException("CMPack profile lacks Minecraft.getMinecraft");
        }
        result.buildInverse();
        return result;
    }

    private void buildInverse() {
        for (Map.Entry<String, String> entry : namedToObf.entrySet()) {
            obfToNamed.put(entry.getValue(), entry.getKey());
        }
        for (Map.Entry<String, List<String>> entry : namedParents.entrySet()) {
            String obfOwner = namedToObf.get(entry.getKey());
            if (obfOwner == null) continue;
            List<String> translated = new ArrayList<String>();
            for (String parent : entry.getValue()) {
                String obfParent = namedToObf.get(parent);
                translated.add(obfParent == null ? parent : obfParent);
            }
            obfParents.put(obfOwner, translated);
        }
        for (Map.Entry<String, String> entry : namedMethods.entrySet()) {
            int space = entry.getKey().indexOf(' ');
            if (space < 0) continue;
            int slash = entry.getKey().lastIndexOf('/', space);
            if (slash < 0) continue;
            String owner = entry.getKey().substring(0, slash);
            String name = entry.getKey().substring(slash + 1, space);
            String descriptor = entry.getKey().substring(space + 1);
            String obfOwner = namedToObf.get(owner);
            if (obfOwner == null) continue;
            obfMethods.put(obfOwner + "/" + entry.getValue() + " " + mapDescriptor(descriptor),
                    name);
        }
        for (Map.Entry<String, String> entry : namedFields.entrySet()) {
            int slash = entry.getKey().lastIndexOf('/');
            if (slash < 0) continue;
            String owner = entry.getKey().substring(0, slash);
            String name = entry.getKey().substring(slash + 1);
            String obfOwner = namedToObf.get(owner);
            if (obfOwner == null) continue;
            obfFields.put(obfOwner + "/" + entry.getValue(), name);
        }
    }

    private String mapDescriptor(String descriptor) {
        StringBuilder text = new StringBuilder(descriptor.length());
        for (int i = 0; i < descriptor.length(); i++) {
            char c = descriptor.charAt(i);
            if (c != 'L') {
                text.append(c);
                continue;
            }
            int end = descriptor.indexOf(';', i);
            if (end < 0) {
                text.append(descriptor.substring(i));
                break;
            }
            String type = descriptor.substring(i + 1, end);
            String mapped = namedToObf.get(type);
            text.append('L').append(mapped == null ? type : mapped).append(';');
            i = end;
        }
        return text.toString();
    }

    private static String inherited(Map<String, String> members,
            Map<String, List<String>> parents, String owner, String suffix) {
        Set<String> seen = new HashSet<String>();
        ArrayDeque<String> queue = new ArrayDeque<String>();
        List<String> direct = parents.get(owner);
        if (direct != null) queue.addAll(direct);
        while (!queue.isEmpty()) {
            String parent = queue.removeFirst();
            if (!seen.add(parent)) continue;
            String found = members.get(parent + "/" + suffix);
            if (found != null) return found;
            List<String> next = parents.get(parent);
            if (next != null) queue.addAll(next);
        }
        return null;
    }

    private static String string(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
                ? null : element.getAsString();
    }

    private static void copyObject(JsonObject root, String key, Map<String, String> target) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException("CMPack profile section '" + key + "' is missing");
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                target.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }
}

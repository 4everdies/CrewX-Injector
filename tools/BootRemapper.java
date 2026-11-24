package crewx.inject;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Java 8 bootstrap that validates the external CMPack profile and produces a
 * CMPack-mapped copy of the named CrewX payload before the DLL adds it to the
 * game class loader. This class must not reference Minecraft classes.
 */
public final class BootRemapper {
    public static final String FORMAT = "crewx-cmpack-profile/1";
    public static final String HOST = "CMPack 1.8.8 2.8.11-beta";
    private static final String MAPPINGS_URL = "https://raw.githubusercontent.com/4everdies/CrewX-Injector/main/src/main/resources/crewx/inject/mappings.json";
    public static final String EXPECTED_SHA256 =
            "464347BEE470967072E3B7A2536D95E1F87645C1A02D2F8B0F120FCC9EBC4A80";
    public static final String PROFILE_PROPERTY = "crewx.cmpack.profile";

    private static final String[] REQUIRED_CLASSES = {
            "net/minecraft/client/Minecraft",
            "net/minecraft/client/gui/GuiIngame",
            "net/minecraft/client/gui/GuiScreen",
            "net/minecraft/client/gui/GuiTextField",
            "net/minecraft/client/gui/GuiNewChat",
            "net/minecraft/client/network/NetHandlerPlayClient",
            "net/minecraft/client/renderer/WorldRenderer",
            "net/minecraft/client/settings/KeyBinding",
            "net/minecraft/item/ItemStack",
            "net/minecraft/client/renderer/BlockModelRenderer",
            "net/minecraft/client/renderer/entity/RenderManager",
            "net/minecraft/client/gui/GuiButton",
            "net/minecraft/client/renderer/EntityRenderer",
            "net/minecraft/client/renderer/ItemRenderer",
            "net/minecraft/client/gui/FontRenderer",
            "net/minecraft/client/entity/AbstractClientPlayer",
            "net/minecraft/client/entity/EntityPlayerSP",
            "net/minecraft/entity/player/EntityPlayer",
            "net/minecraft/entity/EntityLivingBase",
            "net/minecraft/client/multiplayer/PlayerControllerMP",
            "net/minecraft/network/NetworkManager",
            "net/minecraft/entity/Entity",
            "net/minecraft/client/renderer/entity/RendererLivingEntity",
            "net/minecraft/world/World",
            "net/minecraft/block/Block",
            "net/minecraft/client/renderer/BlockRendererDispatcher"
    };

    private static final String[] REQUIRED_METHODS = {
            "net/minecraft/client/Minecraft/getMinecraft ()Lnet/minecraft/client/Minecraft;",
            "net/minecraft/client/Minecraft/getNetHandler ()Lnet/minecraft/client/network/NetHandlerPlayClient;",
            "net/minecraft/client/Minecraft/addScheduledTask (Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;",
            "net/minecraft/client/gui/GuiScreen/drawScreen (IIF)V",
            "net/minecraft/client/gui/GuiScreen/initGui ()V",
            "net/minecraft/entity/Entity/getEntityId ()I",
            "net/minecraft/network/NetworkManager/channelRead0 (Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V",
            "net/minecraft/world/World/getBlockState (Lnet/minecraft/util/BlockPos;)Lnet/minecraft/block/state/IBlockState;",
            "net/minecraft/client/gui/GuiIngame/renderGameOverlay (F)V",
            "net/minecraft/client/renderer/EntityRenderer/updateCameraAndRender (FJ)V"
    };

    private BootRemapper() {
    }

    /** Returns the absolute path of the remapped payload JAR. */
    public static String remap(String payloadPath, String profilePath) throws Exception {
        boolean remoteProfile = profilePath == null || profilePath.length() == 0;
        File profileFile = remoteProfile
                ? new File(System.getProperty("java.io.tmpdir"), "mappings.json")
                : new File(profilePath).getAbsoluteFile();
        File payloadFile = new File(payloadPath).getAbsoluteFile();
        File outputFile = new File(payloadFile.getParentFile(),
                "crewx-cmpack-" + Long.toHexString(System.nanoTime()) + ".jar");
        PrintWriter log = openLog(profileFile);
        try {
            log.println("profile=" + profileFile);
            log.println("payload=" + payloadFile);
            Profile profile = remoteProfile ? Profile.loadRemote() : Profile.load(profileFile);
            validate(profile, profileFile, log);
            if (!remoteProfile) {
                System.setProperty(PROFILE_PROPERTY, profileFile.getAbsolutePath());
            }
            Mapping mapping;
            int remapped = 0;
            int copied = 0;
            int skipped = 0;
            JarFile input = new JarFile(payloadFile);
            try {
                Payload payload = Payload.scan(input);
                payload.resolveOverrides(profile);
                mapping = new Mapping(profile, payload);
                log.println("payloadHierarchy=" + payload.classes.size()
                        + " overrideMethods=" + payload.methodOverrides.size());
                JarOutputStream output = new JarOutputStream(new FileOutputStream(outputFile));
                try {
                    Enumeration<JarEntry> entries = input.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory()) continue;
                        if (name.startsWith("META-INF/") && (name.endsWith(".SF")
                                || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                            skipped++;
                            continue;
                        }
                        if ("META-INF/MANIFEST.MF".equals(name)
                                || name.startsWith("org/spongepowered/")
                                || name.startsWith("net/montoyo/mcef/coremod/")
                                || name.startsWith("net/montoyo/mcef/example/")
                                        && !name.equals("net/montoyo/mcef/example/ExampleMod.class")
                                || name.equals("net/montoyo/mcef/utilities/ForgeProgressListener.class")
                                || name.startsWith("mixins.crewx")
                                || name.startsWith("META-INF/services/org.spongepowered")) {
                            skipped++;
                            continue;
                        }
                        byte[] bytes = readAll(input.getInputStream(entry));
                        if (name.endsWith(".class")) {
                            if (name.equals("net/montoyo/mcef/example/ExampleMod.class")) {
                                bytes = stubExampleMod();
                            } else if (name.startsWith("net/montoyo/")) {
                                bytes = sanitizeMcef(name, bytes);
                            }
                            ClassReader reader = new ClassReader(bytes);
                            ClassWriter writer = new ClassWriter(0);
                            reader.accept(new ClassRemapper(writer, mapping), 0);
                            bytes = writer.toByteArray();
                            remapped++;
                        } else {
                            copied++;
                        }
                        JarEntry out = new JarEntry(name);
                        output.putNextEntry(out);
                        output.write(bytes);
                        output.closeEntry();
                    }
                } finally {
                    output.close();
                }
            } finally {
                input.close();
            }
            List<String> missing = new ArrayList<String>();
            missing.addAll(mapping.missingClasses);
            missing.addAll(mapping.missingMethods);
            missing.addAll(mapping.missingFields);
            missing.remove("net/minecraft/client/gui/GuiTextField/mouseClicked (III)Z");
            for (int i = missing.size() - 1; i >= 0; i--) {
                String link = missing.get(i);
                if (link.startsWith("net/minecraftforge/")
                        || link.startsWith("cpw/mods/")
                        || link.startsWith("net/minecraft/launchwrapper/")) {
                    missing.remove(i);
                }
            }
            log.println("missingLinks=" + missing.size());
            for (String link : missing) log.println("  missing " + link);
            if (!missing.isEmpty()) {
                outputFile.delete();
                StringBuilder message = new StringBuilder("CMPack profile is incomplete for the CrewX payload: ")
                        .append(missing.size()).append(" missing link(s): ")
                        .append(join(missing, 12));
                log.println(message);
                throw new IllegalStateException(message.toString());
            }
            log.println("remappedClasses=" + remapped + " copied=" + copied + " skipped=" + skipped);
            log.println("output=" + outputFile.getAbsolutePath() + " (" + outputFile.length() + " bytes)");
            log.flush();
            return outputFile.getAbsolutePath();
        } catch (Throwable error) {
            log.println("FAILED: " + error);
            error.printStackTrace(log);
            if (outputFile.exists()) outputFile.delete();
            if (error instanceof Exception) throw (Exception) error;
            throw new IllegalStateException(error);
        } finally {
            log.close();
        }
    }

    /**
     * MCEF is a Forge mod. CMPack has no Forge, so its Forge-only methods are
     * removed and the two hard Forge calls in ClientProxy.onInit are rewritten.
     * The web browser itself (org.cef) and the Minecraft-facing code stay intact.
     */
    private static byte[] sanitizeMcef(String name, byte[] original) {
        if (name.endsWith(".class")) name = name.substring(0, name.length() - 6);
        boolean main = "net/montoyo/mcef/MCEF".equals(name);
        boolean proxy = "net/montoyo/mcef/client/ClientProxy".equals(name);
        boolean api = "net/montoyo/mcef/api/MCEFApi".equals(name);
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        for (int i = node.methods.size() - 1; i >= 0; i--) {
            MethodNode method = node.methods.get(i);
            if (main && method.desc.contains("net/minecraftforge/")) {
                node.methods.remove(i);
                continue;
            }
            if (proxy && (("onPreInit".equals(method.name) && "()V".equals(method.desc))
                    || method.desc.contains("net/minecraftforge/"))) {
                node.methods.remove(i);
                continue;
            }
            if (proxy && "onInit".equals(method.name) && "()V".equals(method.desc)) {
                patchClientProxyInit(method);
                continue;
            }
            if (api && "isMCEFLoaded".equals(method.name) && "()Z".equals(method.desc)) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new InsnNode(Opcodes.ICONST_1));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                method.maxStack = 1;
                if (method.maxLocals < 1) method.maxLocals = 1;
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void patchClientProxyInit(MethodNode method) {
        for (AbstractInsnNode current = method.instructions.getFirst(); current != null; ) {
            AbstractInsnNode next = current.getNext();
            if (current instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) current;
                if (field.getOpcode() == Opcodes.GETSTATIC
                        && "net/minecraftforge/common/MinecraftForge".equals(field.owner)
                        && "EVENT_BUS".equals(field.name)) {
                    AbstractInsnNode load = field.getNext();
                    AbstractInsnNode call = load == null ? null : load.getNext();
                    if (load instanceof VarInsnNode && load.getOpcode() == Opcodes.ALOAD
                            && ((VarInsnNode) load).var == 0
                            && call instanceof MethodInsnNode
                            && "net/minecraftforge/fml/common/eventhandler/EventBus"
                                    .equals(((MethodInsnNode) call).owner)
                            && "register".equals(((MethodInsnNode) call).name)) {
                        method.instructions.remove(field);
                        method.instructions.remove(load);
                        method.instructions.remove(call);
                        current = next;
                        continue;
                    }
                }
            }
            if (current instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) current;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && "net/montoyo/mcef/coremod/ShutdownPatcher".equals(call.owner)
                        && "didPatchSucceed".equals(call.name)) {
                    method.instructions.set(current, new InsnNode(Opcodes.ICONST_1));
                } else if ("net/montoyo/mcef/utilities/ForgeProgressListener".equals(call.owner)) {
                    call.owner = "net/montoyo/mcef/client/UpdateFrame";
                }
            } else if (current instanceof TypeInsnNode) {
                TypeInsnNode type = (TypeInsnNode) current;
                if ("net/montoyo/mcef/utilities/ForgeProgressListener".equals(type.desc)) {
                    type.desc = "net/montoyo/mcef/client/UpdateFrame";
                }
            }
            current = next;
        }
    }

    private static byte[] stubExampleMod() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC,
                "net/montoyo/mcef/example/ExampleMod", null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        for (String methodName : new String[]{"onPreInit", "onInit"}) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void validate(Profile profile, File profileFile, PrintWriter log)
            throws Exception {
        if (!FORMAT.equals(profile.format)) {
            throw new IllegalStateException("CMPack profile format is '" + profile.format
                    + "', expected '" + FORMAT + "'");
        }
        if (!HOST.equals(profile.host)) {
            throw new IllegalStateException("CMPack profile host is '" + profile.host
                    + "', expected '" + HOST + "'");
        }
        if (profile.jarSha256 == null || !profile.jarSha256.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalStateException("CMPack profile jarSha256 is missing or invalid");
        }
        if (profile.classes.size() < 1500 || profile.methods.size() < 9000
                || profile.fields.size() < 4000) {
            throw new IllegalStateException("CMPack profile is truncated (classes="
                    + profile.classes.size() + " methods=" + profile.methods.size()
                    + " fields=" + profile.fields.size() + ")");
        }
        Map<String, String> targetToNamed = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : profile.classes.entrySet()) {
            String previous = targetToNamed.put(entry.getValue(), entry.getKey());
            if (previous != null) {
                throw new IllegalStateException("CMPack profile maps two named classes to '"
                        + entry.getValue() + "': " + previous + " and " + entry.getKey());
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String required : REQUIRED_CLASSES) {
            if (!profile.classes.containsKey(required)) missing.add("class " + required);
        }
        for (String required : REQUIRED_METHODS) {
            if (!profile.methods.containsKey(required)) missing.add("method " + required);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("CMPack profile lacks required entries: " + join(missing, 20));
        }
        File hostJar = findHostJar(profileFile);
        if (hostJar != null) {
            String actual = sha256(hostJar);
            if (!profile.jarSha256.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("CMPack host JAR does not match the profile: "
                        + hostJar + " sha256=" + actual + " expected=" + profile.jarSha256);
            }
            log.println("hostJar=" + hostJar + " sha256 OK");
        } else if (!classResourcePresent("ir.class") || !classResourcePresent("zI.class")) {
            throw new IllegalStateException("CMPack host was not found and the loaded client "
                    + "does not expose the expected CMPack classes (ir/zI)");
        } else {
            log.println("hostJar not found; CMPack identity confirmed by loaded class fingerprints");
        }
    }

    private static File findHostJar(File profileFile) {
        List<File> candidates = new ArrayList<File>();
        addCandidate(candidates, System.getProperty("crewx.cmpack.jar"));
        addCandidate(candidates, System.getenv("CREWX_CMPACK_JAR"));
        addCandidate(candidates, new File(profileFile.getParentFile(), "CMPack_app.jar"));
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            File minecraft = new File(appData, ".minecraft");
            addCandidate(candidates, new File(minecraft, "CrewX/CMPack_app.jar"));
            addCandidate(candidates, new File(minecraft, "mods/CMPack_app.jar"));
            File version = new File(minecraft, "versions/cmpack_1.8.8");
            addCandidate(candidates, new File(version, "CMPack_app.jar"));
            File[] jars = version.listFiles();
            if (jars != null) {
                for (File jar : jars) {
                    if (jar.isFile() && jar.getName().toLowerCase().endsWith(".jar")
                            && jar.getName().toLowerCase().contains("cmpack")) {
                        addCandidate(candidates, jar);
                    }
                }
            }
        }
        addCandidate(candidates, new File("CMPack_app.jar"));
        for (File candidate : candidates) {
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static void addCandidate(List<File> candidates, String path) {
        if (path != null && path.length() != 0) candidates.add(new File(path));
    }

    private static void addCandidate(List<File> candidates, File file) {
        if (file != null) candidates.add(file);
    }

    private static boolean classResourcePresent(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = BootRemapper.class.getClassLoader();
        InputStream input = loader == null ? null : loader.getResourceAsStream(resource);
        if (input == null) return false;
        try {
            input.close();
        } catch (IOException ignored) {
        }
        return true;
    }

    private static PrintWriter openLog(File profileFile) throws IOException {
        File logFile = new File(profileFile.getParentFile(), "cmpack-remap.log");
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
        writer.println("=== " + new java.util.Date() + " ===");
        return writer;
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        byte[] bytes = digest.digest();
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02X", value & 0xff));
        return text.toString();
    }

    static String join(List<String> values, int limit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.size() && i < limit; i++) {
            if (i != 0) text.append(", ");
            text.append(values.get(i));
        }
        if (values.size() > limit) text.append(", ...");
        return text.toString();
    }

    static byte[] readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                if (count > 0) buffer.write(chunk, 0, count);
            }
            return buffer.toByteArray();
        } finally {
            input.close();
        }
    }

    static final class Profile {
        String format;
        String host;
        String jarSha256;
        final Map<String, String> classes = new HashMap<String, String>();
        final Map<String, String> methods = new HashMap<String, String>();
        final Map<String, String> fields = new HashMap<String, String>();
        final Map<String, List<String>> parents = new HashMap<String, List<String>>();

        static Profile load(File file) throws IOException {
            Reader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                JsonElement parsed = new JsonParser().parse(reader);
                if (!parsed.isJsonObject()) {
                    throw new IOException("CMPack profile root must be a JSON object");
                }
                JsonObject root = parsed.getAsJsonObject();
                JsonElement profiles = root.get("profiles");
                if (profiles != null && profiles.isJsonObject()) {
                    JsonElement cmpack = profiles.getAsJsonObject().get("cmpack");
                    if (cmpack != null && cmpack.isJsonObject()) root = cmpack.getAsJsonObject();
                }
                return fromJson(root);
            } finally {
                reader.close();
            }
        }
        static Profile loadRemote() throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(MAPPINGS_URL).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(7000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "CrewX-Injector-Mappings/1");
            try {
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Mappings server returned HTTP " + connection.getResponseCode());
                }
                File snapshot = File.createTempFile("crewx-mappings-", ".json");
                snapshot.deleteOnExit();
                try (InputStream input = connection.getInputStream();
                        FileOutputStream output = new FileOutputStream(snapshot)) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 32 * 1024 * 1024) throw new IOException("Mappings JSON is too large");
                        output.write(buffer, 0, count);
                    }
                }
                System.setProperty("crewx.mappings.document", snapshot.getAbsolutePath());
                return load(snapshot);
            } finally {
                connection.disconnect();
            }
        }
        private static Profile fromJson(JsonObject root) throws IOException {
                Profile profile = new Profile();
                profile.format = string(root, "format");
                profile.host = string(root, "host");
                profile.jarSha256 = string(root, "jarSha256");
                copyObject(root, "classes", profile.classes);
                copyObject(root, "methods", profile.methods);
                copyObject(root, "fields", profile.fields);
                JsonElement parentElement = root.get("parents");
                if (parentElement != null && parentElement.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry
                            : parentElement.getAsJsonObject().entrySet()) {
                        List<String> list = new ArrayList<String>();
                        if (entry.getValue().isJsonArray()) {
                            for (JsonElement parent : entry.getValue().getAsJsonArray()) {
                                if (parent.isJsonPrimitive()) list.add(parent.getAsString());
                            }
                        }
                        profile.parents.put(entry.getKey(), list);
                    }
                }
                return profile;
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

    /**
     * Class hierarchy and declared members of the payload being remapped. The
     * profile only knows the game classes, but a payload subclass declares
     * methods that override game methods (drawScreen, initGui, ...) and reads
     * fields inherited from game classes. Both the declaration and every
     * reference must use the obfuscated name, otherwise the game calls the
     * base GuiScreen method and the payload GUI never paints.
     */
    static final class Payload {
        final Set<String> classes = new HashSet<String>();
        final Map<String, List<String>> parents = new HashMap<String, List<String>>();
        final Map<String, Set<String>> fields = new HashMap<String, Set<String>>();
        final Map<String, Set<String>> methods = new HashMap<String, Set<String>>();
        final Map<String, Integer> methodAccess = new HashMap<String, Integer>();
        final Map<String, String> methodOverrides = new HashMap<String, String>();
        private Profile profile;

        static Payload scan(JarFile jar) throws IOException {
            Payload payload = new Payload();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] bytes = readAll(jar.getInputStream(entry));
                final ClassReader reader;
                try {
                    reader = new ClassReader(bytes);
                } catch (RuntimeException invalidClass) {
                    continue;
                }
                final String className = reader.getClassName();
                if (className == null || "module-info".equals(className)) continue;
                final Set<String> fieldNames = new HashSet<String>();
                final Set<String> methodNames = new HashSet<String>();
                List<String> parentNames = new ArrayList<String>();
                String superName = reader.getSuperName();
                if (superName != null) parentNames.add(superName);
                String[] interfaces = reader.getInterfaces();
                if (interfaces != null) {
                    for (String iface : interfaces) {
                        if (iface != null) parentNames.add(iface);
                    }
                }
                payload.classes.add(className);
                payload.parents.put(className, parentNames);
                payload.fields.put(className, fieldNames);
                payload.methods.put(className, methodNames);
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String fieldName,
                            String descriptor, String signature, Object value) {
                        fieldNames.add(fieldName);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String methodName,
                            String descriptor, String signature, String[] exceptions) {
                        String suffix = methodName + " " + descriptor;
                        methodNames.add(suffix);
                        payload.methodAccess.put(className + "/" + suffix, Integer.valueOf(access));
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            return payload;
        }

        void resolveOverrides(Profile profile) {
            this.profile = profile;
            for (Map.Entry<String, Set<String>> entry : methods.entrySet()) {
                String owner = entry.getKey();
                for (String suffix : entry.getValue()) {
                    if (suffix.startsWith("<")) continue;
                    Integer access = methodAccess.get(owner + "/" + suffix);
                    if (access == null) continue;
                    if ((access.intValue() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) {
                        continue;
                    }
                    String mapped = gameMethodInAncestors(owner, suffix, new HashSet<String>());
                    if (mapped != null) {
                        methodOverrides.put(owner + "/" + suffix, mapped);
                    }
                }
            }
        }

        private String gameMethodInAncestors(String owner, String suffix, Set<String> seen) {
            List<String> direct = parents.get(owner);
            if (direct == null) return null;
            for (String parent : direct) {
                if (parent == null) continue;
                if (classes.contains(parent)) {
                    if (!seen.add(parent)) continue;
                    String mapped = gameMethodInAncestors(parent, suffix, seen);
                    if (mapped != null) return mapped;
                } else {
                    String mapped = Mapping.inherited(profile, profile.methods, parent, suffix);
                    if (mapped != null) return mapped;
                }
            }
            return null;
        }

        /** Returns null when the member is not declared in the payload hierarchy. */
        String lookupMethod(String owner, String suffix) {
            if (!classes.contains(owner)) return null;
            Set<String> declared = methods.get(owner);
            if (declared != null && declared.contains(suffix)) {
                String mapped = methodOverrides.get(owner + "/" + suffix);
                return mapped == null ? methodName(suffix) : mapped;
            }
            List<String> direct = parents.get(owner);
            if (direct == null) return null;
            for (String parent : direct) {
                if (parent == null) continue;
                String resolved = classes.contains(parent)
                        ? lookupMethod(parent, suffix)
                        : Mapping.inherited(profile, profile.methods, parent, suffix);
                if (resolved != null) return resolved;
            }
            return null;
        }

        String lookupField(String owner, String name) {
            if (!classes.contains(owner)) return null;
            Set<String> declared = fields.get(owner);
            if (declared != null && declared.contains(name)) return name;
            List<String> direct = parents.get(owner);
            if (direct == null) return null;
            for (String parent : direct) {
                if (parent == null) continue;
                String resolved = classes.contains(parent)
                        ? lookupField(parent, name)
                        : Mapping.inherited(profile, profile.fields, parent, name);
                if (resolved != null) return resolved;
            }
            return null;
        }

        private static String methodName(String suffix) {
            int space = suffix.indexOf(' ');
            return space < 0 ? suffix : suffix.substring(0, space);
        }
    }

    static final class Mapping extends Remapper {
        final Profile profile;
        final Payload payload;
        final Set<String> missingClasses = new LinkedHashSet<String>();
        final Set<String> missingMethods = new LinkedHashSet<String>();
        final Set<String> missingFields = new LinkedHashSet<String>();

        Mapping(Profile profile, Payload payload) {
            this.profile = profile;
            this.payload = payload;
        }

        static String inherited(Profile profile, Map<String, String> members,
                String owner, String suffix) {
            Set<String> seen = new HashSet<String>();
            ArrayDeque<String> queue = new ArrayDeque<String>();
            String directHit = members.get(owner + "/" + suffix);
            if (directHit != null) return directHit;
            List<String> direct = profile.parents.get(owner);
            if (direct != null) queue.addAll(direct);
            while (!queue.isEmpty()) {
                String parent = queue.removeFirst();
                if (!seen.add(parent)) continue;
                String found = members.get(parent + "/" + suffix);
                if (found != null) return found;
                List<String> next = profile.parents.get(parent);
                if (next != null) queue.addAll(next);
            }
            return null;
        }

        @Override
        public String map(String internalName) {
            // MCEF was compiled against 1.8.9, where the vertex buffer is called
            // BufferBuilder. CMPack 1.8.8 exposes the same class as WorldRenderer.
            if ("net/minecraft/client/renderer/BufferBuilder".equals(internalName)
                    && !profile.classes.containsKey(internalName)) {
                internalName = "net/minecraft/client/renderer/WorldRenderer";
            }
            String mapped = profile.classes.get(internalName);
            if (mapped == null && internalName.startsWith("net/minecraft/")) {
                missingClasses.add(internalName);
            }
            return mapped == null ? internalName : mapped;
        }

        /**
         * True when the class inherits from a non-Minecraft type such as
         * java.lang.Thread or a library collection. A method absent from the
         * profile may legitimately come from that ancestor, so it must not be
         * reported as an incomplete Minecraft mapping.
         */
        boolean hasExternalAncestor(String owner) {
            Set<String> seen = new HashSet<String>();
            ArrayDeque<String> queue = new ArrayDeque<String>();
            List<String> direct = profile.parents.get(owner);
            if (direct != null) queue.addAll(direct);
            while (!queue.isEmpty()) {
                String parent = queue.removeFirst();
                if (!seen.add(parent)) continue;
                if (!parent.startsWith("net/minecraft/")
                        && !"java/lang/Object".equals(parent)) {
                    return true;
                }
                List<String> next = profile.parents.get(parent);
                if (next != null) queue.addAll(next);
            }
            return false;
        }

        @Override
        public String mapMethodName(String owner, String name, String desc) {
            if (name.startsWith("<")) return name;
            if ("net/minecraft/client/renderer/BufferBuilder".equals(owner)) {
                owner = "net/minecraft/client/renderer/WorldRenderer";
                desc = desc.replace("Lnet/minecraft/client/renderer/BufferBuilder;",
                        "Lnet/minecraft/client/renderer/WorldRenderer;");
            }
            if ("net/minecraft/client/renderer/Tessellator".equals(owner)
                    && "getBuffer".equals(name)
                    && "()Lnet/minecraft/client/renderer/BufferBuilder;".equals(desc)) {
                name = "getWorldRenderer";
                desc = "()Lnet/minecraft/client/renderer/WorldRenderer;";
            }
            String suffix = name + " " + desc;
            String mapped = profile.methods.get(owner + "/" + suffix);
            if (mapped == null) mapped = inherited(profile, profile.methods, owner, suffix);
            if (mapped == null && payload != null) mapped = payload.lookupMethod(owner, suffix);
            if (mapped == null && owner.startsWith("net/minecraft/")
                    && !"ordinal".equals(name) && !"equals".equals(name)
                    && !"toString".equals(name) && !hasExternalAncestor(owner)) {
                missingMethods.add(owner + "/" + suffix);
            }
            return mapped == null ? name : mapped;
        }

        @Override
        public String mapFieldName(String owner, String name, String desc) {
            if ("net/minecraft/client/renderer/BufferBuilder".equals(owner)) {
                owner = "net/minecraft/client/renderer/WorldRenderer";
            }
            String mapped = profile.fields.get(owner + "/" + name);
            if (mapped == null) mapped = inherited(profile, profile.fields, owner, name);
            if (mapped == null && payload != null) mapped = payload.lookupField(owner, name);
            if (mapped == null && owner.startsWith("net/minecraft/")
                    && !hasExternalAncestor(owner)) {
                missingFields.add(owner + "/" + name);
            }
            return mapped == null ? name : mapped;
        }
    }
}

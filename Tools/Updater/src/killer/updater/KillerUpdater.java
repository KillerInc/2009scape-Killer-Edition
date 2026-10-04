package killer.updater;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class KillerUpdater {
    private KillerUpdater() {}

    public static void main(String[] args) {
        try {
            Map<String,String> a = parseArgs(args);
            Path root = Paths.get(required(a, "root")).toAbsolutePath().normalize();
            Path stage = Paths.get(required(a, "stage")).toAbsolutePath().normalize();
            Path manifest = Paths.get(required(a, "manifest")).toAbsolutePath().normalize();
            String version = required(a, "version");
            long waitPid = Long.parseLong(a.getOrDefault("wait-pid", "0"));

            if (waitPid > 0) {
                ProcessHandle.of(waitPid).ifPresent(ph -> {
                    try { ph.onExit().get(120, TimeUnit.SECONDS); }
                    catch (Exception ignored) {}
                });
            }

            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                p.load(r);
            }

            int count = Integer.parseInt(p.getProperty("file.count", "0"));
            Path backup = root.resolve(".update-backup").resolve(version + "-" + System.currentTimeMillis());
            Files.createDirectories(backup);

            for (int i = 0; i < count; i++) {
                String rel = required(p, "file." + i + ".path");
                String expected = required(p, "file." + i + ".sha256").toLowerCase(Locale.ROOT);

                Path target = safeResolve(root, rel);
                Path source = safeResolve(stage, rel);
                if (!Files.isRegularFile(source)) throw new IOException("Missing staged file: " + rel);

                String actual = sha256(source);
                if (!actual.equals(expected)) {
                    throw new IOException("SHA-256 mismatch for " + rel + ": expected " + expected + ", got " + actual);
                }

                if (Files.exists(target)) {
                    Path old = safeResolve(backup, rel);
                    if (old.getParent() != null) Files.createDirectories(old.getParent());
                    Files.copy(target, old, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }

                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Path temp = target.resolveSibling(target.getFileName().toString() + ".update-new");
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            Files.write(root.resolve(".update-backup").resolve("last-successful.txt"),
                    Arrays.asList("version=" + version, "backup=" + backup.toString()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("Killer Edition update " + version + " applied successfully.");
            relaunch(root);
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                Path log = Paths.get(System.getProperty("java.io.tmpdir"), "killer-updater-error.log");
                try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                    t.printStackTrace(out);
                }
            } catch (Exception ignored) {}
            System.exit(1);
        }
    }

    private static void relaunch(Path root) throws IOException {
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path javaExe = root.resolve(".runtime").resolve("jdk-11").resolve("bin").resolve(exe);
        if (!Files.isRegularFile(javaExe)) {
            javaExe = Paths.get(System.getProperty("java.home"), "bin", exe);
        }
        Path jar = root.resolve("Server").resolve("server.jar");
        new ProcessBuilder(javaExe.toString(), "-jar", jar.toString(), "--gui")
                .directory(root.resolve("Server").toFile())
                .start();
    }

    private static Path safeResolve(Path base, String relative) throws IOException {
        Path p = base.resolve(relative.replace('/', File.separatorChar)).normalize();
        if (!p.startsWith(base)) throw new IOException("Unsafe manifest path: " + relative);
        return p;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static Map<String,String> parseArgs(String[] args) {
        Map<String,String> out = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            String key = args[i];
            if (key.startsWith("--")) key = key.substring(2);
            out.put(key, args[i + 1]);
        }
        return out;
    }

    private static String required(Map<String,String> map, String key) {
        String v = map.get(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing --" + key);
        return v;
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing manifest property " + key);
        return v;
    }
}

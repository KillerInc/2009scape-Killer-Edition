package killer.updater;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class KillerUpdater {
    private final JFrame frame = new JFrame("2009Scape Killer Edition - Updater");
    private final JLabel versionLabel = new JLabel("Version: checking...");
    private final JLabel statusLabel = new JLabel("Preparing update...");
    private final JLabel fileLabel = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);

    private Path root;
    private Path stage;
    private Path manifest;
    private String targetVersion;
    private long waitPid;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                KillerUpdater updater = new KillerUpdater();
                updater.parseArguments(args);
                updater.buildUi();
                updater.frame.setVisible(true);
                updater.startUpdate();
            } catch (Throwable t) {
                JOptionPane.showMessageDialog(null, t.toString(), "Killer Edition Updater", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    private void parseArguments(String[] args) {
        Map<String,String> a = parseArgs(args);
        root = Paths.get(required(a, "root")).toAbsolutePath().normalize();
        stage = Paths.get(required(a, "stage")).toAbsolutePath().normalize();
        manifest = Paths.get(required(a, "manifest")).toAbsolutePath().normalize();
        targetVersion = required(a, "version");
        waitPid = Long.parseLong(a.getOrDefault("wait-pid", "0"));
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);
        frame.setSize(560, 220);
        frame.setLocationRelativeTo(null);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("2009Scape Killer Edition Updater");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        versionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        progress.setStringPainted(true);
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        panel.add(title);
        panel.add(Box.createVerticalStrut(10));
        panel.add(versionLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(fileLabel);
        panel.add(Box.createVerticalStrut(12));
        panel.add(progress);

        frame.setContentPane(panel);
    }

    private void startUpdate() {
        Thread worker = new Thread(() -> {
            try {
                runUpdate();
            } catch (Throwable t) {
                logError(t);
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Update failed.");
                    fileLabel.setText(t.getMessage() == null ? t.toString() : t.getMessage());
                    progress.setIndeterminate(false);
                    progress.setValue(0);
                    progress.setString("Failed");
                    JOptionPane.showMessageDialog(frame,
                            "The update could not be completed.\n\n" + t.getMessage(),
                            "Update Failed", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "killer-updater-worker");
        worker.setDaemon(false);
        worker.start();
    }

    private void runUpdate() throws Exception {
        String currentVersion = readLocalVersion(root);
        SwingUtilities.invokeLater(() ->
                versionLabel.setText("Installed: " + currentVersion + "    Target: " + targetVersion));

        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            p.load(r);
        }

        int componentCount = Integer.parseInt(p.getProperty("component.count", "0"));
        if (componentCount <= 0) throw new IOException("Update manifest contains no components.");

        java.util.List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < componentCount; i++) {
            String archiveRel = required(p, "component." + i + ".file");
            Path archive = safeResolve(stage, archiveRel);
            if (Files.isRegularFile(archive)) changed.add(i);
        }

        if (changed.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("No patch required.");
                fileLabel.setText("Killer Edition " + currentVersion + " is already up to date.");
                progress.setValue(100);
                progress.setString("Up to date");
            });
            Thread.sleep(1800);
            relaunch(root);
            SwingUtilities.invokeLater(frame::dispose);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Waiting for Server Control to close...");
            progress.setIndeterminate(true);
            progress.setString("Waiting");
        });

        if (waitPid > 0) {
            ProcessHandle.of(waitPid).ifPresent(ph -> {
                try { ph.onExit().get(120, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            });
        }

        Path backup = root.resolve(".update-backup")
                .resolve(targetVersion + "-" + System.currentTimeMillis());
        Files.createDirectories(backup);

        int done = 0;
        for (int index : changed) {
            String name = required(p, "component." + index + ".name");
            String archiveRel = required(p, "component." + index + ".file");
            String expected = required(p, "component." + index + ".sha256").toLowerCase(Locale.ROOT);
            String cleanPath = p.getProperty("component." + index + ".clean", "").trim();
            boolean doBackup = Boolean.parseBoolean(p.getProperty("component." + index + ".backup", "true"));

            Path archive = safeResolve(stage, archiveRel);
            if (!sha256(archive).equals(expected)) {
                throw new IOException("SHA-256 mismatch for " + name + " archive.");
            }

            int currentDone = done;
            SwingUtilities.invokeLater(() -> {
                progress.setIndeterminate(false);
                statusLabel.setText("Patching " + name + "...");
                fileLabel.setText(archiveRel);
                int pct = (currentDone * 100) / changed.size();
                progress.setValue(pct);
                progress.setString(pct + "%");
            });

            if (!cleanPath.isEmpty()) {
                Path targetDir = safeResolve(root, cleanPath);
                if (Files.exists(targetDir)) {
                    if (doBackup) {
                        Path backupDir = safeResolve(backup, cleanPath);
                        copyTree(targetDir, backupDir);
                    }
                    deleteTree(targetDir);
                }
            } else if (doBackup) {
                backupArchiveTargets(archive, root, backup);
            }

            extractZip(archive, root);
            done++;

            int pct = (done * 100) / changed.size();
            SwingUtilities.invokeLater(() -> {
                progress.setValue(pct);
                progress.setString(pct + "%");
            });
        }

        Files.copy(manifest, root.resolve(".killer-components.properties"),
                StandardCopyOption.REPLACE_EXISTING);

        Files.write(root.resolve(".update-backup").resolve("last-successful.txt"),
                Arrays.asList("version=" + targetVersion, "backup=" + backup.toString()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        SwingUtilities.invokeLater(() -> {
            versionLabel.setText("Installed: " + targetVersion + "    Target: " + targetVersion);
            statusLabel.setText("Update complete.");
            fileLabel.setText("Restarting Killer Edition...");
            progress.setValue(100);
            progress.setString("Complete");
        });

        Thread.sleep(1200);
        relaunch(root);
        SwingUtilities.invokeLater(frame::dispose);
    }

    private static void backupArchiveTargets(Path archive, Path root, Path backup) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path target = safeResolve(root, entry.getName());
                if (Files.isRegularFile(target)) {
                    Path dest = safeResolve(backup, entry.getName());
                    if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                    Files.copy(target, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = safeResolve(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Path temp = out.resolveSibling(out.getFileName().toString() + ".update-new");
                    try (OutputStream output = Files.newOutputStream(temp,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[1024 * 1024];
                        int n;
                        while ((n = zin.read(buffer)) > 0) output.write(buffer, 0, n);
                    }
                    try {
                        Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Iterator<Path> it = paths.iterator(); it.hasNext();) {
                Path p = it.next();
                Path rel = source.relativize(p);
                Path dest = destination.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private static String readLocalVersion(Path root) {
        Path versionFile = root.resolve("VERSION");
        try {
            if (Files.isRegularFile(versionFile)) {
                return new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {}
        return "unknown";
    }

    private static void relaunch(Path root) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "javaw.exe" : "java";
        Path javaExe = root.resolve(".runtime").resolve("jdk-11").resolve("bin").resolve(exe);
        if (!Files.isRegularFile(javaExe)) {
            javaExe = Paths.get(System.getProperty("java.home"), "bin", exe);
        }
        Path jar = root.resolve("Server").resolve("server.jar");
        new ProcessBuilder(javaExe.toString(), "-jar", jar.toString(), "--gui")
                .directory(root.resolve("Server").toFile()).start();
    }

    private static Path safeResolve(Path base, String relative) throws IOException {
        Path p = base.resolve(relative.replace('/', File.separatorChar)).normalize();
        if (!p.startsWith(base)) throw new IOException("Unsafe path: " + relative);
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

    private static void logError(Throwable t) {
        t.printStackTrace();
        try {
            Path log = Paths.get(System.getProperty("java.io.tmpdir"), "killer-updater-error.log");
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                t.printStackTrace(out);
            }
        } catch (Exception ignored) {}
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
        return v.trim();
    }
}

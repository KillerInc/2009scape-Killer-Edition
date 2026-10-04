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
                JOptionPane.showMessageDialog(
                        null,
                        t.toString(),
                        "Killer Edition Updater",
                        JOptionPane.ERROR_MESSAGE
                );
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
        frame.setSize(520, 210);
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
        progress.setValue(0);
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
                    progress.setValue(0);
                    progress.setString("Failed");
                    JOptionPane.showMessageDialog(
                            frame,
                            "The update could not be completed.\n\n" + t.getMessage(),
                            "Update Failed",
                            JOptionPane.ERROR_MESSAGE
                    );
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

        if (targetVersion.equals(currentVersion)) {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("No patch required.");
                fileLabel.setText("Killer Edition " + currentVersion + " is already up to date.");
                progress.setValue(100);
                progress.setString("Up to date");
            });
            Thread.sleep(1800);
            relaunch(root);
            SwingUtilities.invokeLater(() -> frame.dispose());
            return;
        }

        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Waiting for Server Control to close...");
            progress.setIndeterminate(true);
            progress.setString("Waiting");
        });

        if (waitPid > 0) {
            ProcessHandle.of(waitPid).ifPresent(ph -> {
                try {
                    ph.onExit().get(120, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            });
        }

        SwingUtilities.invokeLater(() -> {
            progress.setIndeterminate(false);
            progress.setValue(0);
            progress.setString("0%");
            statusLabel.setText("Verifying update package...");
        });

        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            p.load(r);
        }

        int count = Integer.parseInt(p.getProperty("file.count", "0"));
        if (count <= 0) throw new IOException("Update manifest contains no files.");

        Path backup = root.resolve(".update-backup")
                .resolve(targetVersion + "-" + System.currentTimeMillis());
        Files.createDirectories(backup);

        for (int i = 0; i < count; i++) {
            String rel = required(p, "file." + i + ".path");
            String expected = required(p, "file." + i + ".sha256").toLowerCase(Locale.ROOT);

            int index = i;
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Patching files...");
                fileLabel.setText(rel);
                int pct = Math.max(1, (index * 100) / count);
                progress.setValue(pct);
                progress.setString(pct + "%");
            });

            Path target = safeResolve(root, rel);
            Path source = safeResolve(stage, rel);

            if (!Files.isRegularFile(source)) {
                throw new IOException("Missing staged file: " + rel);
            }

            String actual = sha256(source);
            if (!actual.equals(expected)) {
                throw new IOException("SHA-256 mismatch for " + rel);
            }

            if (Files.exists(target)) {
                Path old = safeResolve(backup, rel);
                if (old.getParent() != null) Files.createDirectories(old.getParent());
                Files.copy(
                        target,
                        old,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
            }

            if (target.getParent() != null) Files.createDirectories(target.getParent());

            Path temp = target.resolveSibling(target.getFileName().toString() + ".update-new");
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);

            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            int pct = ((i + 1) * 100) / count;
            SwingUtilities.invokeLater(() -> {
                progress.setValue(pct);
                progress.setString(pct + "%");
            });
        }

        Files.write(
                root.resolve(".update-backup").resolve("last-successful.txt"),
                Arrays.asList(
                        "version=" + targetVersion,
                        "backup=" + backup.toString()
                ),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        SwingUtilities.invokeLater(() -> {
            versionLabel.setText("Installed: " + targetVersion + "    Target: " + targetVersion);
            statusLabel.setText("Update complete.");
            fileLabel.setText("Restarting Killer Edition...");
            progress.setValue(100);
            progress.setString("Complete");
        });

        Thread.sleep(1200);
        relaunch(root);
        SwingUtilities.invokeLater(() -> frame.dispose());
    }

    private static String readLocalVersion(Path root) {
        Path versionFile = root.resolve("VERSION");
        try {
            if (Files.isRegularFile(versionFile)) {
                return new String(
                        Files.readAllBytes(versionFile),
                        StandardCharsets.UTF_8
                ).trim();
            }
        } catch (IOException ignored) {
        }
        return "unknown";
    }

    private static void relaunch(Path root) throws IOException {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");

        String exe = windows ? "javaw.exe" : "java";
        Path javaExe = root.resolve(".runtime")
                .resolve("jdk-11")
                .resolve("bin")
                .resolve(exe);

        if (!Files.isRegularFile(javaExe)) {
            javaExe = Paths.get(System.getProperty("java.home"), "bin", exe);
        }

        Path jar = root.resolve("Server").resolve("server.jar");

        new ProcessBuilder(
                javaExe.toString(),
                "-jar",
                jar.toString(),
                "--gui"
        )
                .directory(root.resolve("Server").toFile())
                .start();
    }

    private static Path safeResolve(Path base, String relative) throws IOException {
        Path p = base.resolve(relative.replace('/', File.separatorChar)).normalize();
        if (!p.startsWith(base)) {
            throw new IOException("Unsafe manifest path: " + relative);
        }
        return p;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void logError(Throwable t) {
        t.printStackTrace();
        try {
            Path log = Paths.get(
                    System.getProperty("java.io.tmpdir"),
                    "killer-updater-error.log"
            );
            try (PrintWriter out = new PrintWriter(
                    Files.newBufferedWriter(
                            log,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                    ))) {
                t.printStackTrace(out);
            }
        } catch (Exception ignored) {
        }
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
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return v;
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing manifest property " + key);
        }
        return v;
    }
}

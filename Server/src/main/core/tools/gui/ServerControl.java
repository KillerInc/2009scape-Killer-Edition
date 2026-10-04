package core.tools.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ServerControl {
    private static final String UPDATE_MANIFEST_URL =
            "https://github.com/KillerInc/2009scape-Killer-Edition/releases/latest/download/update-manifest.properties";

    private final JFrame frame = new JFrame("2009Scape Killer Edition - Server Control");
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Offline");
    private final JLabel uptimeLabel = new JLabel("00:00:00");
    private final JLabel playersLabel = new JLabel("0");
    private final JLabel botsLabel = new JLabel("0");
    private final JLabel versionLabel = new JLabel("?");
    private final JLabel updateLabel = new JLabel("Not checked");

    private final JSpinner minutesSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 1440, 1));
    private final JSpinner secondsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));

    private final JButton startButton = new JButton("Start Server");
    private final JButton restartButton = new JButton("Restart Server");
    private final JButton shutdownButton = new JButton("Safe Shutdown");
    private final JButton forceStopButton = new JButton("Force Stop");
    private final JButton cancelCountdownButton = new JButton("Cancel Countdown");
    private final JButton updateButton = new JButton("Update");
    private final JButton checkVersionButton = new JButton("Check Version");

    private final JTextField commandField = new JTextField();
    private final JButton sendButton = new JButton("Send");

    private final Path serverDir;
    private final Path serverJar;
    private final Path javaExe;
    private final Path installRoot;

    private volatile Process process;
    private volatile BufferedWriter serverInput;
    private volatile Instant startedAt;

    private volatile Properties latestManifest;
    private volatile String latestVersion;
    private volatile Path stagedUpdateDir;
    private volatile boolean installAfterServerExit;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new ServerControl().show();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, ex.toString(), "Server Control", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private ServerControl() throws URISyntaxException {
        Path jarLocation = Paths.get(ServerControl.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();

        if (Files.isDirectory(jarLocation)) {
            serverDir = Paths.get("").toAbsolutePath().normalize();
            serverJar = serverDir.resolve("server.jar");
        } else {
            serverJar = jarLocation;
            serverDir = jarLocation.getParent();
        }

        installRoot = serverDir.getParent() == null ? serverDir : serverDir.getParent();

        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        javaExe = Paths.get(System.getProperty("java.home"), "bin", exe);

        buildUi();
        updateButtons();
        startTimers();
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 560));
        frame.setSize(1120, 740);
        frame.setLocationRelativeTo(null);

        JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
        rootPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel statusInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        statusInfoPanel.add(new JLabel("Status:"));
        statusInfoPanel.add(statusLabel);
        statusInfoPanel.add(new JLabel("Uptime:"));
        statusInfoPanel.add(uptimeLabel);
        statusInfoPanel.add(new JLabel("Players:"));
        statusInfoPanel.add(playersLabel);
        statusInfoPanel.add(new JLabel("Bots:"));
        statusInfoPanel.add(botsLabel);
        statusInfoPanel.add(new JLabel("Version:"));
        statusInfoPanel.add(versionLabel);
        statusInfoPanel.add(new JLabel("Update:"));
        statusInfoPanel.add(updateLabel);

        JPanel statusPanel = new JPanel(new BorderLayout(8, 0));
        statusPanel.add(statusInfoPanel, BorderLayout.CENTER);
        JPanel updatePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        checkVersionButton.setPreferredSize(new Dimension(120, 28));
        updateButton.setPreferredSize(new Dimension(150, 28));
        updatePanel.add(checkVersionButton);
        updatePanel.add(updateButton);
        statusPanel.add(updatePanel, BorderLayout.EAST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(startButton);
        buttons.add(restartButton);
        buttons.add(shutdownButton);
        buttons.add(forceStopButton);
        buttons.add(cancelCountdownButton);
        buttons.add(new JLabel("Delay:"));
        buttons.add(minutesSpinner);
        buttons.add(new JLabel("min"));
        buttons.add(secondsSpinner);
        buttons.add(new JLabel("sec"));

        JButton configButton = new JButton("Open Config");
        JButton logsButton = new JButton("Open Logs");
        buttons.add(configButton);
        buttons.add(logsButton);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(statusPanel, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(logArea);

        JPanel commandPanel = new JPanel(new BorderLayout(6, 0));
        commandPanel.add(new JLabel("Console command:"), BorderLayout.WEST);
        commandPanel.add(commandField, BorderLayout.CENTER);
        commandPanel.add(sendButton, BorderLayout.EAST);

        rootPanel.add(top, BorderLayout.NORTH);
        rootPanel.add(scroll, BorderLayout.CENTER);
        rootPanel.add(commandPanel, BorderLayout.SOUTH);
        frame.setContentPane(rootPanel);

        startButton.addActionListener(e -> startServer());
        restartButton.addActionListener(e -> restartServer());
        shutdownButton.addActionListener(e -> safeShutdown());
        forceStopButton.addActionListener(e -> forceStop());

        cancelCountdownButton.addActionListener(e -> {
            if (isRunning()) {
                append("[GUI] Cancelling scheduled shutdown/restart...");
                sendCommand("cancelshutdown");
                status("Running");
            }
        });

        checkVersionButton.addActionListener(e -> checkForUpdates(true));
        updateButton.addActionListener(e -> handleUpdateButton());

        sendButton.addActionListener(e -> sendTypedCommand());
        commandField.addActionListener(e -> sendTypedCommand());

        configButton.addActionListener(e -> openPath(serverDir.resolve("worldprops")));
        logsButton.addActionListener(e -> openPath(serverDir.resolve("data").resolve("logs")));

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (isRunning()) {
                    int result = JOptionPane.showConfirmDialog(
                            frame,
                            "The server is still running.\n\nUse Safe Shutdown before closing the controller?",
                            "Server still running",
                            JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE
                    );

                    if (result == JOptionPane.YES_OPTION) {
                        safeShutdown();
                    } else if (result == JOptionPane.NO_OPTION) {
                        closeController();
                    }
                } else {
                    closeController();
                }
            }
        });
    }

    private void show() {
        append("[GUI] Killer Edition Server Control ready.");
        append("[GUI] Server JAR: " + serverJar);
        append("[GUI] Java: " + javaExe);
        versionLabel.setText(readLocalVersion());
        frame.setVisible(true);
        checkForUpdates(false);
    }

    private synchronized void startServer() {
        if (isRunning()) return;

        if (!Files.isRegularFile(javaExe)) {
            error("Java runtime could not be found:\n" + javaExe);
            return;
        }
        if (!Files.isRegularFile(serverJar)) {
            error("server.jar could not be found:\n" + serverJar);
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), "-jar", serverJar.getFileName().toString());
            pb.directory(serverDir.toFile());
            pb.redirectErrorStream(true);

            append("");
            append("[GUI] Starting server...");
            process = pb.start();
            serverInput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            startedAt = Instant.now();
            status("Starting");
            updateButtons();

            Process current = process;
            Thread reader = new Thread(() -> readOutput(current), "server-output");
            reader.setDaemon(true);
            reader.start();

            current.onExit().thenRun(() -> SwingUtilities.invokeLater(this::serverExited));
        } catch (Exception ex) {
            append("[GUI] Failed to start server: " + ex);
            process = null;
            serverInput = null;
            startedAt = null;
            status("Offline");
            updateButtons();
        }
    }

    private void readOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[GUI_STATUS] ")) {
                    parseGuiStatus(line);
                    continue;
                }

                append(line);

                if (line.contains(" started in ") && line.contains(" milliseconds.")) {
                    SwingUtilities.invokeLater(() -> status("Running"));
                } else if (line.contains("Initializing termination sequence")) {
                    SwingUtilities.invokeLater(() -> status("Stopping"));
                } else if (line.contains("Server successfully terminated!")) {
                    SwingUtilities.invokeLater(() -> status("Stopped safely"));
                }
            }
        } catch (IOException ex) {
            if (p.isAlive()) append("[GUI] Console read error: " + ex.getMessage());
        }
    }

    private synchronized void safeShutdown() {
        if (!isRunning()) return;
        int seconds = selectedDelaySeconds();
        status("Shutdown scheduled");
        append("[GUI] Safe shutdown scheduled in " + seconds + " seconds.");
        sendCommand("shutdown " + seconds);
    }

    private synchronized void restartServer() {
        if (!isRunning()) {
            startServer();
            return;
        }

        int seconds = selectedDelaySeconds();
        status("Restart scheduled");
        append("[GUI] Safe restart scheduled in " + seconds + " seconds.");
        sendCommand("restart " + seconds);
    }

    private int selectedDelaySeconds() {
        int minutes = ((Number) minutesSpinner.getValue()).intValue();
        int seconds = ((Number) secondsSpinner.getValue()).intValue();
        int total = minutes * 60 + seconds;

        if (total < 15) {
            total = 15;
            minutesSpinner.setValue(0);
            secondsSpinner.setValue(15);
        }
        return total;
    }

    private synchronized void forceStop() {
        if (!isRunning()) return;

        int result = JOptionPane.showConfirmDialog(
                frame,
                "Force Stop may interrupt saves.\nUse only if Safe Shutdown is not working.\n\nForce stop now?",
                "Force Stop",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            append("[GUI] FORCE STOP requested.");
            process.destroyForcibly();
        }
    }

    private synchronized void serverExited() {
        int code = -1;
        try {
            if (process != null) code = process.exitValue();
        } catch (IllegalThreadStateException ignored) {
        }

        append("[GUI] Server process exited with code " + code + ".");
        process = null;
        closeInput();
        startedAt = null;

        status("Offline");
        playersLabel.setText("0");
        botsLabel.setText("0");
        updateButtons();

        if (installAfterServerExit && stagedUpdateDir != null) {
            installAfterServerExit = false;
            launchUpdater();
            return;
        }

        if (code == 23) {
            append("[GUI] Server requested restart. Starting again...");
            Timer timer = new Timer(1000, e -> startServer());
            timer.setRepeats(false);
            timer.start();
        }
    }

    private void sendTypedCommand() {
        String command = commandField.getText().trim();
        if (command.isEmpty()) return;

        commandField.setText("");
        append("> " + command);
        sendCommand(command);
    }

    private synchronized void sendCommand(String command) {
        if (!isRunning() || serverInput == null) {
            append("[GUI] Server is not running.");
            return;
        }

        try {
            serverInput.write(command);
            serverInput.newLine();
            serverInput.flush();
        } catch (IOException ex) {
            append("[GUI] Failed to send command: " + ex.getMessage());
        }
    }

    private boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    private void updateButtons() {
        boolean running = isRunning();
        startButton.setEnabled(!running);
        restartButton.setEnabled(running);
        shutdownButton.setEnabled(running);
        forceStopButton.setEnabled(running);
        cancelCountdownButton.setEnabled(running);
        minutesSpinner.setEnabled(running);
        secondsSpinner.setEnabled(running);
        commandField.setEnabled(running);
        sendButton.setEnabled(running);

        checkVersionButton.setEnabled(true);
        updateButton.setEnabled(true);
        boolean patchRequired = false;
        if (latestManifest != null && latestVersion != null) {
            try {
                patchRequired = hasPendingComponentChanges(latestManifest);
            } catch (Exception ignored) {
            }
        }
        updateButton.setText(patchRequired ? "Update to v" + latestVersion : "Update");
    }

    private void status(String value) {
        statusLabel.setText(value);
    }

    private void startTimers() {
        Timer uptimeTimer = new Timer(1000, e -> {
            Instant start = startedAt;
            if (start == null || !isRunning()) {
                uptimeLabel.setText("00:00:00");
                return;
            }

            Duration d = Duration.between(start, Instant.now());
            long seconds = d.getSeconds();
            uptimeLabel.setText(String.format("%02d:%02d:%02d",
                    seconds / 3600,
                    (seconds % 3600) / 60,
                    seconds % 60));
        });
        uptimeTimer.start();

        Timer statusTimer = new Timer(5000, e -> {
            if (isRunning()) sendCommand("guistatus");
        });
        statusTimer.setInitialDelay(5000);
        statusTimer.start();

        Timer updateCheckTimer = new Timer(30 * 60 * 1000, e -> checkForUpdates(false));
        updateCheckTimer.setInitialDelay(30 * 60 * 1000);
        updateCheckTimer.start();
    }

    private void parseGuiStatus(String line) {
        try {
            String payload = line.substring("[GUI_STATUS] ".length());
            String[] parts = payload.split(" ");
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                if ("players".equals(kv[0])) playersLabel.setText(kv[1]);
                if ("bots".equals(kv[0])) botsLabel.setText(kv[1]);
            }
        } catch (Exception ignored) {
        }
    }

    private String readLocalVersion() {
        Path versionFile = installRoot.resolve("VERSION");
        try {
            if (Files.isRegularFile(versionFile)) {
                return new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
        }
        return "unknown";
    }

    private void checkForUpdates(boolean interactive) {
        checkVersionButton.setEnabled(false);
        updateButton.setEnabled(false);
        updateLabel.setText("Checking...");

        Thread t = new Thread(() -> {
            try {
                Properties manifest = new Properties();
                try (InputStream in = openUrl(new URL(UPDATE_MANIFEST_URL))) {
                    manifest.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }

                String version = requiredManifest(manifest, "version");
                latestManifest = manifest;
                latestVersion = version;

                String local = readLocalVersion();
                boolean patchRequired = hasPendingComponentChanges(manifest);

                SwingUtilities.invokeLater(() -> {
                    versionLabel.setText(local);

                    if (!patchRequired) {
                        updateLabel.setText("Up to date");
                        updateButton.setText("Update");
                        if (interactive) {
                            JOptionPane.showMessageDialog(frame,
                                    "No patch required.\nKiller Edition " + local + " is already up to date.",
                                    "Updates",
                                    JOptionPane.INFORMATION_MESSAGE);
                        }
                    } else {
                        updateLabel.setText("v" + version + " available");
                        updateButton.setText("Update to v" + version);
                        if (interactive) {
                            JOptionPane.showMessageDialog(frame,
                                    "Killer Edition " + version + " is available.",
                                    "Update Available",
                                    JOptionPane.INFORMATION_MESSAGE);
                        }
                    }

                    checkVersionButton.setEnabled(true);
                    updateButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    updateLabel.setText("Check failed");
                    checkVersionButton.setEnabled(true);
                    updateButton.setEnabled(true);
                    updateButton.setText("Update");
                    if (interactive) error("Update check failed:\n" + ex.getMessage());
                });
            }
        }, "update-check");

        t.setDaemon(true);
        t.start();
    }

    private void handleUpdateButton() {
        if (latestManifest != null) {
            try {
                if (hasPendingComponentChanges(latestManifest)) {
                    downloadAndInstallUpdate();
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        checkForUpdates(true);
    }

    private boolean hasPendingComponentChanges(Properties manifest) throws IOException {
        Properties installed = loadInstalledComponents();
        int count = Integer.parseInt(manifest.getProperty("component.count", "0"));
        if (count <= 0) {
            return !requiredManifest(manifest, "version").equals(readLocalVersion());
        }

        for (int i = 0; i < count; i++) {
            String name = requiredManifest(manifest, "component." + i + ".name");
            String remoteHash = requiredManifest(manifest, "component." + i + ".sha256");
            String installedHash = componentHash(installed, name);
            if (!remoteHash.equalsIgnoreCase(installedHash)) return true;
        }
        return false;
    }

    private Properties loadInstalledComponents() {
        Properties installed = new Properties();
        Path localManifest = installRoot.resolve(".killer-components.properties");
        if (!Files.isRegularFile(localManifest)) return installed;
        try (Reader r = Files.newBufferedReader(localManifest, StandardCharsets.UTF_8)) {
            installed.load(r);
        } catch (IOException ignored) {
        }
        return installed;
    }

    private static String componentHash(Properties p, String componentName) {
        int count;
        try {
            count = Integer.parseInt(p.getProperty("component.count", "0"));
        } catch (NumberFormatException ex) {
            return "";
        }

        for (int i = 0; i < count; i++) {
            if (componentName.equalsIgnoreCase(p.getProperty("component." + i + ".name", ""))) {
                return p.getProperty("component." + i + ".sha256", "");
            }
        }
        return "";
    }

    private void downloadAndInstallUpdate() {
        Properties manifest = latestManifest;
        String version = latestVersion;

        if (manifest == null || version == null) {
            checkForUpdates(true);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                frame,
                "Download and install Killer Edition " + version + "?\n\n"
                        + "Only changed runtime component archives will be downloaded.\n"
                        + "Player saves, worldprops, logs, and .runtime are never replaced.",
                "Install Update",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (confirm != JOptionPane.YES_OPTION) return;

        updateButton.setEnabled(false);
        updateLabel.setText("Downloading...");

        Thread t = new Thread(() -> {
            try {
                Path updateRoot = installRoot.resolve(".update-stage").resolve(version);
                if (Files.exists(updateRoot)) deleteTree(updateRoot);
                Path packageDir = updateRoot.resolve("packages");
                Files.createDirectories(packageDir);

                Properties installed = loadInstalledComponents();
                int count = Integer.parseInt(manifest.getProperty("component.count", "0"));
                if (count <= 0) throw new IOException("Update manifest contains no components.");

                int downloads = 0;
                for (int i = 0; i < count; i++) {
                    String name = requiredManifest(manifest, "component." + i + ".name");
                    String rel = requiredManifest(manifest, "component." + i + ".file");
                    String expected = requiredManifest(manifest, "component." + i + ".sha256").toLowerCase(Locale.ROOT);
                    String url = requiredManifest(manifest, "component." + i + ".url");

                    String currentHash = componentHash(installed, name);
                    if (expected.equalsIgnoreCase(currentHash)) {
                        append("[UPDATE] " + name + " unchanged - skipping.");
                        continue;
                    }

                    Path destination = safeResolve(updateRoot, rel);
                    if (destination.getParent() != null) Files.createDirectories(destination.getParent());

                    append("[UPDATE] Downloading " + name + "...");
                    download(new URL(url), destination);

                    if (!sha256(destination).equals(expected)) {
                        throw new IOException("SHA-256 mismatch for " + name + ".");
                    }
                    downloads++;
                }

                if (downloads == 0) {
                    SwingUtilities.invokeLater(() -> {
                        updateLabel.setText("Up to date");
                        updateButton.setText("Update");
                        updateButton.setEnabled(true);
                        JOptionPane.showMessageDialog(frame,
                                "No patch required.\nThis installation already matches the latest component hashes.",
                                "Updates",
                                JOptionPane.INFORMATION_MESSAGE);
                    });
                    return;
                }

                Path manifestCopy = updateRoot.resolve("update-manifest.properties");
                try (Writer w = Files.newBufferedWriter(manifestCopy, StandardCharsets.UTF_8)) {
                    manifest.store(w, "Killer Edition component manifest");
                }

                Path installedHelper = installRoot.resolve("Tools").resolve("Updater").resolve("KillerUpdater.jar");
                if (!Files.isRegularFile(installedHelper)) {
                    throw new IOException("Installed updater helper is missing: " + installedHelper);
                }

                Path runningHelper = updateRoot.resolve("KillerUpdater-running.jar");
                Files.copy(installedHelper, runningHelper, StandardCopyOption.REPLACE_EXISTING);

                stagedUpdateDir = updateRoot;

                SwingUtilities.invokeLater(() -> {
                    updateLabel.setText("Ready to install");

                    if (isRunning()) {
                        int delay = selectedDelaySeconds();
                        installAfterServerExit = true;
                        append("[UPDATE] Update ready. Scheduling safe shutdown in " + delay + " seconds.");
                        status("Update shutdown scheduled");
                        sendCommand("shutdown " + delay);
                    } else {
                        launchUpdater();
                    }
                });
            } catch (Exception ex) {
                append("[UPDATE] Failed: " + ex);
                SwingUtilities.invokeLater(() -> {
                    updateLabel.setText("Download failed");
                    updateButton.setEnabled(true);
                    error("Update download failed:\n" + ex.getMessage());
                });
            }
        }, "update-download");

        t.setDaemon(true);
        t.start();
    }

    private void launchUpdater() {
        try {
            if (stagedUpdateDir == null || latestVersion == null) {
                throw new IOException("No staged update.");
            }

            Path helper = stagedUpdateDir.resolve("KillerUpdater-running.jar");
            Path manifest = stagedUpdateDir.resolve("update-manifest.properties");
            long pid = ProcessHandle.current().pid();

            ProcessBuilder pb = new ProcessBuilder(
                    javaExe.toString(),
                    "-jar", helper.toString(),
                    "--root", installRoot.toString(),
                    "--stage", stagedUpdateDir.toString(),
                    "--manifest", manifest.toString(),
                    "--version", latestVersion,
                    "--wait-pid", Long.toString(pid)
            );
            pb.directory(installRoot.toFile());
            pb.start();

            append("[UPDATE] Updater launched. Closing GUI so files can be replaced.");
            closeController();
        } catch (Exception ex) {
            error("Could not launch updater:\n" + ex.getMessage());
        }
    }

    private static InputStream openUrl(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "2009Scape-Killer-Edition-Updater");
        int code = conn.getResponseCode();

        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        return conn.getInputStream();
    }

    private static void download(URL url, Path destination) throws IOException {
        Path temp = destination.resolveSibling(destination.getFileName().toString() + ".part");
        try (InputStream in = openUrl(url);
             OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extractZip(Path zipFile, Path destination) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = destination.resolve(entry.getName()).normalize();
                if (!out.startsWith(destination)) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    try (OutputStream output = Files.newOutputStream(out,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[1024 * 1024];
                        int n;
                        while ((n = zin.read(buffer)) > 0) output.write(buffer, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
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

    private static String requiredManifest(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) throw new IOException("Manifest missing " + key);
        return v.trim();
    }

    private static Path safeResolve(Path base, String relative) throws IOException {
        Path p = base.resolve(relative.replace('/', File.separatorChar)).normalize();
        if (!p.startsWith(base)) throw new IOException("Unsafe update path: " + relative);
        return p;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(text + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void openPath(Path path) {
        try {
            Files.createDirectories(path);
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception ex) {
            error("Could not open:\n" + path + "\n\n" + ex.getMessage());
        }
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(frame, message, "Server Control", JOptionPane.ERROR_MESSAGE);
    }

    private synchronized void closeInput() {
        try {
            if (serverInput != null) serverInput.close();
        } catch (IOException ignored) {
        }
        serverInput = null;
    }

    private void closeController() {
        frame.dispose();
        System.exit(0);
    }
}

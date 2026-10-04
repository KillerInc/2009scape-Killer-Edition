package core.tools.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;

public final class ServerControl {
    private final JFrame frame = new JFrame("2009Scape Killer Edition - Server Control");
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Offline");
    private final JLabel uptimeLabel = new JLabel("00:00:00");
    private final JLabel playersLabel = new JLabel("0");
    private final JLabel botsLabel = new JLabel("0");
    private final JSpinner minutesSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 1440, 1));
    private final JSpinner secondsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
    private final JButton startButton = new JButton("Start Server");
    private final JButton restartButton = new JButton("Restart Server");
    private final JButton shutdownButton = new JButton("Safe Shutdown");
    private final JButton forceStopButton = new JButton("Force Stop");
    private final JButton cancelCountdownButton = new JButton("Cancel Countdown");
    private final JTextField commandField = new JTextField();
    private final JButton sendButton = new JButton("Send");

    private final Path serverDir;
    private final Path serverJar;
    private final Path javaExe;

    private volatile Process process;
    private volatile BufferedWriter serverInput;
    private volatile Instant startedAt;

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
        Path jarLocation = Paths.get(ServerControl.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();

        if (Files.isDirectory(jarLocation)) {
            serverDir = Paths.get("").toAbsolutePath().normalize();
            serverJar = serverDir.resolve("server.jar");
        } else {
            serverJar = jarLocation;
            serverDir = jarLocation.getParent();
        }

        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        javaExe = Paths.get(System.getProperty("java.home"), "bin", exe);

        buildUi();
        updateButtons();
        startUptimeTimer();
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(780, 520));
        frame.setSize(1000, 700);
        frame.setLocationRelativeTo(null);

        JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
        rootPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel);
        statusPanel.add(new JLabel("Uptime:"));
        statusPanel.add(uptimeLabel);
        statusPanel.add(new JLabel("Players:"));
        statusPanel.add(playersLabel);
        statusPanel.add(new JLabel("Bots:"));
        statusPanel.add(botsLabel);

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
                        frame.dispose();
                    }
                } else {
                    frame.dispose();
                }
            }
        });
    }

    private void show() {
        append("[GUI] Killer Edition Server Control ready.");
        append("[GUI] Server JAR: " + serverJar);
        append("[GUI] Java: " + javaExe);
        frame.setVisible(true);
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

        boolean doRestart = code == 23;
        status("Offline");
        playersLabel.setText("0");
        botsLabel.setText("0");
        updateButtons();

        if (doRestart) {
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
    }

    private void status(String value) {
        statusLabel.setText(value);
    }

    private void startUptimeTimer() {
        Timer timer = new Timer(1000, e -> {
            Instant start = startedAt;
            if (start == null || !isRunning()) {
                uptimeLabel.setText("00:00:00");
                return;
            }

            Duration d = Duration.between(start, Instant.now());
            long seconds = d.getSeconds();
            uptimeLabel.setText(String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60));
        });
        timer.start();

        Timer statusTimer = new Timer(5000, e -> {
            if (isRunning()) sendCommand("guistatus");
        });
        statusTimer.setInitialDelay(5000);
        statusTimer.start();
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
}

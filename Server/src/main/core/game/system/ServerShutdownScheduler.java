package core.game.system;

import core.game.node.entity.player.Player;
import core.game.world.repository.Repository;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class ServerShutdownScheduler {
    public enum Action {
        SHUTDOWN,
        RESTART
    }

    public static final int MINIMUM_SECONDS = 15;

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "server-shutdown-countdown");
            t.setDaemon(true);
            return t;
        }
    });

    private static ScheduledFuture<?> task;
    private static Action action;
    private static int secondsRemaining;

    private ServerShutdownScheduler() {}

    public static synchronized boolean schedule(Action requestedAction, int requestedSeconds) {
        int seconds = Math.max(MINIMUM_SECONDS, requestedSeconds);
        cancelInternal(false);

        action = requestedAction;
        secondsRemaining = seconds;

        broadcastWarning(secondsRemaining);
        System.out.println("[ServerControl] " + actionName() + " scheduled in " + formatDuration(secondsRemaining) + ".");

        task = EXECUTOR.scheduleAtFixedRate(() -> tick(), 1, 1, TimeUnit.SECONDS);
        return true;
    }

    public static synchronized boolean cancel() {
        if (task == null) {
            return false;
        }
        cancelInternal(true);
        return true;
    }

    private static synchronized void tick() {
        if (task == null) {
            return;
        }

        secondsRemaining--;

        if (secondsRemaining <= 0) {
            final Action finishingAction = action;
            cancelInternal(false);
            broadcast("<col=FF0000>Server " + (finishingAction == Action.RESTART ? "restarting" : "shutting down") + " now.");
            System.out.println("[ServerControl] Countdown complete. Beginning safe " + finishingAction.name().toLowerCase(Locale.ROOT) + ".");
            SystemManager.flag(SystemState.TERMINATED);
            System.exit(finishingAction == Action.RESTART ? 23 : 0);
            return;
        }

        if (secondsRemaining <= 15 || secondsRemaining % 60 == 0) {
            broadcastWarning(secondsRemaining);
        }
    }

    private static void broadcastWarning(int seconds) {
        String verb = action == Action.RESTART ? "restart" : "shutdown";
        broadcast("<col=FF0000>Server " + verb + " in " + formatDuration(seconds) + ".");
    }

    public static void broadcast(String message) {
        for (Player player : Repository.getPlayers()) {
            if (player == null || player.isArtificial()) {
                continue;
            }
            try {
                player.getPacketDispatch().sendMessage(message);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static synchronized void cancelInternal(boolean announce) {
        if (task != null) {
            task.cancel(false);
            task = null;
        }

        if (announce && action != null) {
            broadcast("<col=00FF00>Scheduled server " + actionName() + " cancelled.");
            System.out.println("[ServerControl] Scheduled " + actionName() + " cancelled.");
        }

        action = null;
        secondsRemaining = 0;
    }

    public static synchronized boolean isScheduled() {
        return task != null;
    }

    public static synchronized int getSecondsRemaining() {
        return secondsRemaining;
    }

    public static synchronized Action getAction() {
        return action;
    }

    private static String actionName() {
        return action == Action.RESTART ? "restart" : "shutdown";
    }

    public static String formatDuration(int totalSeconds) {
        if (totalSeconds >= 60 && totalSeconds % 60 == 0) {
            int minutes = totalSeconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        if (totalSeconds >= 60) {
            int minutes = totalSeconds / 60;
            int seconds = totalSeconds % 60;
            return minutes + "m " + seconds + "s";
        }
        return totalSeconds + (totalSeconds == 1 ? " second" : " seconds");
    }
}

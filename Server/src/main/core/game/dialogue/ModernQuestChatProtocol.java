package core.game.dialogue;

import core.game.node.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Optional semantic Quest Chat transport for Killer Modern UI clients.
 *
 * This deliberately rides on the existing RUN_CS2 packet with a reserved
 * script id. Stock/vanilla clients never receive this packet because support
 * is opt-in per player session.
 */
public final class ModernQuestChatProtocol {
    public static final String CAPABILITY_ATTRIBUTE =
        "killer:modern-quest-chat";
    public static final int RUNSCRIPT_ID = 65001;
    public static final int VERSION = 1;

    private ModernQuestChatProtocol() {
    }

    public static boolean isEnabled(Player player) {
        return player != null
            && player.getAttribute(
                CAPABILITY_ATTRIBUTE,
                false
            );
    }

    public static void enable(Player player) {
        if (player != null) {
            player.setAttribute(
                CAPABILITY_ATTRIBUTE,
                true
            );
        }
    }

    public static void sendDialogue(
        Player player,
        int speakerType,
        int speakerId,
        String speakerName,
        int expression,
        boolean canContinue,
        String... lines
    ) {
        if (!isEnabled(player)) {
            return;
        }

        StringBuilder payload =
            new StringBuilder(128);
        payload.append(VERSION)
            .append("|OPEN|")
            .append(speakerType)
            .append('|')
            .append(speakerId)
            .append('|')
            .append(expression)
            .append('|')
            .append(canContinue ? 1 : 0)
            .append('|')
            .append(encode(speakerName))
            .append('|')
            .append(lines == null ? 0 : lines.length);

        if (lines != null) {
            for (String line : lines) {
                payload.append('|')
                    .append(encode(line));
            }
        }

        payload.append("|0");
        send(player, payload.toString());
    }

    public static void sendOptions(
        Player player,
        String title,
        String... options
    ) {
        if (!isEnabled(player)) {
            return;
        }

        StringBuilder payload =
            new StringBuilder(128);
        payload.append(VERSION)
            .append("|OPEN|0|-1|-1|0|")
            .append(encode(title))
            .append("|0|")
            .append(options == null ? 0 : options.length);

        if (options != null) {
            for (String option : options) {
                payload.append('|')
                    .append(encode(option));
            }
        }

        send(player, payload.toString());
    }

    public static void close(Player player) {
        if (isEnabled(player)) {
            send(
                player,
                VERSION + "|CLOSE"
            );
        }
    }

    private static void send(
        Player player,
        String payload
    ) {
        player.getPacketDispatch().sendRunScript(
            RUNSCRIPT_ID,
            payload
        );
    }

    private static String encode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return Base64.getEncoder().encodeToString(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }
}

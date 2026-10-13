package core.game.dialogue;

import content.region.misc.tutisland.handlers.TutorialStage;
import core.game.component.Component;
import core.game.node.entity.player.Player;

/**
 * Optional Modern Tutorial UI state transport.
 *
 * Uses the existing RUN_CS2 packet with a reserved Modern-only script id.
 * Vanilla clients never receive this payload because support is opt-in.
 */
public final class ModernTutorialUiProtocol {
    public static final String CAPABILITY_ATTRIBUTE =
        "killer:modern-tutorial-ui";
    public static final int RUNSCRIPT_ID = 65002;
    public static final int VERSION = 1;
    public static final int MODERN_TAB_COUNT = 14;

    private ModernTutorialUiProtocol() {
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

    public static void sendState(
        Player player,
        int stage,
        int highlightedTab
    ) {
        if (!isEnabled(player)) {
            return;
        }

        int mask = 0;
        Component[] tabs =
            player.getInterfaceManager().getTabs();
        if (tabs != null) {
            int count =
                Math.min(
                    MODERN_TAB_COUNT,
                    tabs.length
                );
            for (int i = 0; i < count; i++) {
                if (tabs[i] != null) {
                    mask |= 1 << i;
                }
            }
        }

        boolean complete =
            player.getAttribute(
                "tutorial:complete",
                false
            );

        if (complete) {
            mask = (1 << MODERN_TAB_COUNT) - 1;
            highlightedTab = 0;
        }

        String payload =
            VERSION
                + "|STATE|"
                + stage
                + "|"
                + mask
                + "|"
                + highlightedTab
                + "|"
                + (complete ? 1 : 0);

        player.getPacketDispatch().sendRunScript(
            RUNSCRIPT_ID,
            new Object[] { payload }
        );
    }

    public static void sendCurrentState(Player player) {
        if (player == null) {
            return;
        }

        int stage =
            player.getAttribute(
                "tutorial:stage",
                0
            );
        int highlighted =
            highlightedTabForStage(stage);
        sendState(
            player,
            stage,
            highlighted
        );
    }

    public static boolean selectTab(
        Player player,
        int slot
    ) {
        if (!isEnabled(player)
            || player == null
            || slot < 0
            || slot >= MODERN_TAB_COUNT) {
            return false;
        }

        Component[] tabs =
            player.getInterfaceManager().getTabs();
        if (tabs == null
            || slot >= tabs.length
            || tabs[slot] == null) {
            return false;
        }

        player.getInterfaceManager().setViewedTab(slot);

        int stage =
            player.getAttribute(
                "tutorial:stage",
                0
            );
        int nextStage = nextStageForTabClick(
            stage,
            slot
        );
        if (nextStage != stage) {
            player.setAttribute(
                "tutorial:stage",
                nextStage
            );
            TutorialStage.load(
                player,
                nextStage
            );
        } else {
            sendCurrentState(player);
        }

        return true;
    }

    private static int nextStageForTabClick(
        int stage,
        int slot
    ) {
        switch (stage) {
            case 1:
                return slot == 11 ? 2 : stage;
            case 5:
                return slot == 3 ? 6 : stage;
            case 10:
                return slot == 1 ? 11 : stage;
            case 21:
                return slot == 13 ? 22 : stage;
            case 23:
                return slot == 12 ? 24 : stage;
            case 27:
                return slot == 2 ? 28 : stage;
            case 45:
            case 46:
                return slot == 4 ? 46 : stage;
            case 49:
                return slot == 0 ? 50 : stage;
            case 61:
                return slot == 5 ? 62 : stage;
            case 63:
                return slot == 8 ? 64 : stage;
            case 64:
                return slot == 9 ? 65 : stage;
            case 68:
                return slot == 6 ? 69 : stage;
            default:
                return stage;
        }
    }

    public static int highlightedTabForStage(
        int stage
    ) {
        switch (stage) {
            case 1:
                return 12; // Options
            case 5:
            case 6:
                return 4;  // Inventory
            case 10:
            case 11:
                return 2;  // Stats
            case 21:
                return 14; // Music
            case 23:
                return 13; // Emotes
            case 45:
                return 5;  // Equipment
            case 49:
                return 1;  // Combat
            case 61:
                return 6;  // Prayer
            case 63:
                return 9;  // Friends
            case 64:
                return 10; // Ignore
            case 68:
                return 7;  // Magic
            default:
                return 0;
        }
    }
}

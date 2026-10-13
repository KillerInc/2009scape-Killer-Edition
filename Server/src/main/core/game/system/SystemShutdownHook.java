package core.game.system;

import core.tools.Log;
import core.tools.SystemLogger;

import static core.api.ContentAPIKt.log;

/**
 * Handles the shutdown hook.
 * @author Emperor
 */
public final class SystemShutdownHook implements Runnable {

	@Override
	public void run() {
        core.game.node.entity.player.info.login.PlayerSaveWorker.flush();
		if (SystemManager.isTerminated()) {
			return;
		}
		log(this.getClass(), Log.INFO, "Terminating...");
		SystemManager.flag(SystemState.TERMINATED);
	}
}
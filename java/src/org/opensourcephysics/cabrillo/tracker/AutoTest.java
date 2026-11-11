package org.opensourcephysics.cabrillo.tracker;

/**
 * Patched Tracker: unattended AutoTracker benchmark.
 *
 * Start with main class org.opensourcephysics.cabrillo.tracker.AutoTest and the
 * environment variable TRACKER_AUTOTEST = a .trk/.trz saved with Autotracker key
 * frames (environment variables reach Java in Unicode, unlike command-line paths on
 * a non-Thai Windows code page). Tracker opens the file, the key frames are
 * restored, every step except the key frames is cleared, Search runs in Fast mode
 * to the end, the timing log is flushed and Tracker exits.
 */
public final class AutoTest {

	static final String FILE = System.getenv("TRACKER_AUTOTEST");

	private AutoTest() {
	}

	static boolean active() {
		return FILE != null && !FILE.isEmpty();
	}

	public static void main(String[] args) {
		if (!active()) {
			System.err.println("set TRACKER_AUTOTEST to a .trk/.trz file"); //$NON-NLS-1$
			System.exit(2);
		}
		System.out.println("AutoTest: opening " + FILE); //$NON-NLS-1$
		// safety net: never leave a test Tracker running
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(Long.getLong("tracker.autotest.timeout", 300_000L));
			} catch (InterruptedException e) {
				return;
			}
			System.out.println("AutoTest: timeout"); //$NON-NLS-1$
			Runtime.getRuntime().halt(3);
		}, "AutoTest watchdog"); //$NON-NLS-1$
		watchdog.setDaemon(true);
		watchdog.start();
		Tracker.main(new String[] { FILE });
	}

	/** Called by AutoTracker right before it clicks Search. */
	static void searchStarted() {
		started = System.nanoTime();
	}

	/** TRACKER_AUTOTEST_FRAMES: stop after this many stepped frames (0 = to the end) */
	private static final int LIMIT = parseLimit();
	private static int stepped;

	private static int parseLimit() {
		try {
			return Integer.parseInt(System.getenv("TRACKER_AUTOTEST_FRAMES")); //$NON-NLS-1$
		} catch (Exception e) {
			return 0;
		}
	}

	/** Called for every frame the AutoTracker steps; true when the frame limit is reached. */
	static boolean limitReached() {
		return active() && started != 0 && LIMIT > 0 && ++stepped >= LIMIT;
	}

	private static long started;
	private static boolean finished;

	/** Called from AutoTracker.stop: report and exit. */
	static void stopped(TTrack track) {
		if (finished || started == 0)
			return;
		finished = true;
		double s = (System.nanoTime() - started) / 1e9;
		int marked = 0;
		if (track != null)
			for (Step step : track.getSteps())
				if (step != null)
					marked++;
		final String line = String.format("AutoTest: %d steps marked in %.2f s%n", marked, s); //$NON-NLS-1$
		System.out.print(line);
		org.opensourcephysics.media.core.NativeMatcher.perfFlush(line);
		javax.swing.Timer t = new javax.swing.Timer(500, e -> Runtime.getRuntime().halt(0));
		t.setRepeats(false);
		t.start();
	}
}

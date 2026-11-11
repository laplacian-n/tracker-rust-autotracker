package org.opensourcephysics.cabrillo.tracker;

import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Patched Tracker: while the AutoTracker steps in Fast mode, Swing repaint
 * requests are collected and handed to Swing at most once per
 * {@link #INTERVAL_MS} instead of once per video frame, so the event thread
 * spends its time tracking. Repaints go straight through again
 * {@link #HOLD_MS} after the last tracked frame, and anything collected is
 * always handed over.
 *
 * -Dtracker.fastpaint=false turns this off.
 */
public final class FastPaint {

	static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tracker.fastpaint"));
	static final int INTERVAL_MS = 40;
	static final long HOLD_MS = 300;

	private static volatile long activeUntil;
	private static long lastInfo;
	private static boolean installed;

	private FastPaint() {
	}

	/** Called on the event thread for every frame the AutoTracker steps in Fast mode. */
	static void tick() {
		if (!ENABLED)
			return;
		if (!installed) {
			installed = true;
			RepaintManager.setCurrentManager(new Manager());
			if (PROFILE)
				java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().push(new TimingQueue());
		}
		activeUntil = System.currentTimeMillis() + HOLD_MS;
		if (PROFILE && ++profFrames >= 300)
			dumpProfile();
	}

	// ---------------------------------------------------------------- profiling

	/** -Dtracker.fastpaint.profile=false turns the per-frame breakdown off. */
	static final boolean PROFILE = !"false".equalsIgnoreCase(System.getProperty("tracker.fastpaint.profile"));
	private static final Map<String, long[]> prof = new java.util.HashMap<>();
	private static int profFrames;

	/** Adds the time since startNanos to a named bucket while Fast tracking. */
	public static void time(String key, long startNanos) {
		if (!PROFILE || !active())
			return;
		long[] v = prof.computeIfAbsent(key, k -> new long[2]);
		v[0] += System.nanoTime() - startNanos;
		v[1]++;
	}

	private static void dumpProfile() {
		StringBuilder sb = new StringBuilder(String.format("%tT  breakdown over %d frames (ms/frame, calls/frame):%n",
				System.currentTimeMillis(), profFrames));
		prof.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(25)
				.forEach(e -> sb.append(String.format("    %7.3f  %5.2f  %s%n", e.getValue()[0] / 1e6 / profFrames,
						(double) e.getValue()[1] / profFrames, e.getKey())));
		try (java.io.FileWriter w = new java.io.FileWriter(
				new java.io.File(System.getProperty("java.io.tmpdir"), "tracker-native-perf.log"), true)) {
			w.write(sb.toString());
		} catch (java.io.IOException e) {
		}
		prof.clear();
		profFrames = 0;
	}

	/** Times every event the event thread dispatches, by kind. */
	private static final class TimingQueue extends java.awt.EventQueue {
		@Override
		protected void dispatchEvent(java.awt.AWTEvent e) {
			if (!active()) {
				super.dispatchEvent(e);
				return;
			}
			long t = System.nanoTime();
			try {
				super.dispatchEvent(e);
			} finally {
				time("event " + key(e), t);
			}
		}

		private static String key(java.awt.AWTEvent e) {
			if (e instanceof java.awt.event.InvocationEvent) {
				String s = e.paramString();
				int i = s.indexOf("runnable=");
				if (i >= 0) {
					s = s.substring(i + 9);
					int j = s.indexOf(',');
					if (j >= 0)
						s = s.substring(0, j);
					j = s.indexOf("$$Lambda");
					if (j < 0)
						j = s.indexOf('@');
					if (j >= 0)
						s = s.substring(0, j);
					return "invoke " + s;
				}
			}
			Object src = e.getSource();
			return e.getClass().getSimpleName() + ":" + e.getID() + " "
					+ (src == null ? "" : src.getClass().getSimpleName());
		}
	}

	static boolean active() {
		return System.currentTimeMillis() < activeUntil;
	}

	private static long lastGui;

	/** True when the whole wizard GUI should be refreshed now (every 250 ms while Fast tracking). */
	static boolean guiDue() {
		long now = System.currentTimeMillis();
		if (!ENABLED || !active() || now - lastGui >= 250) {
			lastGui = now;
			return true;
		}
		return false;
	}

	/** True when per-frame text such as the wizard info should be refreshed now. */
	static boolean infoDue() {
		long now = System.currentTimeMillis();
		if (!ENABLED || !active() || now - lastInfo >= 3 * INTERVAL_MS) {
			lastInfo = now;
			return true;
		}
		return false;
	}

	private static final class Manager extends RepaintManager {
		/** repaint requests held back, per component */
		private final Map<JComponent, Rectangle> held = new LinkedHashMap<>();
		private final Timer flush = new Timer(INTERVAL_MS, e -> flush());

		Manager() {
			flush.setRepeats(true);
		}

		@Override
		public void validateInvalidComponents() {
			long t = System.nanoTime();
			super.validateInvalidComponents();
			time("RM.validate (layout)", t);
		}

		@Override
		public void paintDirtyRegions() {
			long t = System.nanoTime();
			super.paintDirtyRegions();
			time("RM.paintDirtyRegions", t);
		}

		@Override
		public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
			if (w <= 0 || h <= 0 || c == null)
				return;
			if (!SwingUtilities.isEventDispatchThread() || !active()) {
				if (!held.isEmpty() && SwingUtilities.isEventDispatchThread())
					flush();
				super.addDirtyRegion(c, x, y, w, h);
				return;
			}
			Rectangle r = held.get(c);
			if (r == null)
				held.put(c, new Rectangle(x, y, w, h));
			else
				r.add(new Rectangle(x, y, w, h));
			if (!flush.isRunning())
				flush.start();
		}

		private void flush() {
			if (held.isEmpty()) {
				if (!active())
					flush.stop();
				return;
			}
			Map<JComponent, Rectangle> copy = new LinkedHashMap<>(held);
			held.clear();
			for (Map.Entry<JComponent, Rectangle> e : copy.entrySet()) {
				Rectangle r = e.getValue();
				super.addDirtyRegion(e.getKey(), r.x, r.y, r.width, r.height);
			}
		}
	}
}

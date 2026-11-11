package org.opensourcephysics.media.core;

/**
 * Bridge to the Rust template-matching kernel (tmnative.dll).
 * Mode comes from -Dtracker.native.mode (default 7 = auto: SIMD, threads for large searches);
 * -1 or a missing library falls back to the original Java loop.
 */
public class NativeMatcher {

	public static final int JAVA = -1;
	public static volatile int mode = JAVA;

	/**
	 * Sub-pixel refinement after the parabola fit (-Dtracker.native.refine,
	 * default true): Gauss-Newton on a bicubic-interpolated target, more accurate
	 * than the 3-point parabola. Only used when the native library is loaded.
	 */
	public static volatile boolean refine = !"false".equalsIgnoreCase(System.getProperty("tracker.native.refine"));

	static {
		int m = Integer.getInteger("tracker.native.mode", 7);
		if (m >= 0) {
			try {
				System.loadLibrary("tmnative");
				mode = m;
			} catch (Throwable t) {
				// Tracker relaunches itself without java.library.path, so also
				// look next to the jar this class came from
				try {
					java.io.File jar = new java.io.File(
							NativeMatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
					java.io.File dir = jar.isDirectory() ? jar : jar.getParentFile();
					System.load(loadablePath(new java.io.File(dir, System.mapLibraryName("tmnative"))));
					mode = m;
				} catch (Throwable t2) {
					System.err.println("tmnative not loaded, using Java matcher: " + t2);
				}
			}
		}
	}

	/**
	 * System.load passes the path to Windows in the ANSI code page, so a folder
	 * name it cannot represent (e.g. Thai "Documents" on an English system) turns
	 * into "?" and the load fails. In that case load a copy from an ASCII folder.
	 */
	private static String loadablePath(java.io.File lib) throws java.io.IOException {
		String path = lib.getAbsolutePath();
		if (encodable(path) || !lib.isFile())
			return path;
		String[] dirs = { System.getProperty("java.io.tmpdir"), System.getenv("ProgramData"), System.getenv("PUBLIC"),
				"C:\\Users\\Public" };
		String name = "tmnative-" + lib.length() + "-" + lib.lastModified() + ".dll";
		for (String d : dirs) {
			if (d == null)
				continue;
			java.io.File copy = new java.io.File(new java.io.File(d, "tracker-native"), name);
			if (!encodable(copy.getAbsolutePath()))
				continue;
			try {
				if (!copy.isFile() || copy.length() != lib.length()) {
					copy.getParentFile().mkdirs();
					// drop copies of older builds (fails harmlessly if another Tracker has one loaded)
					java.io.File[] old = copy.getParentFile().listFiles((f, n) -> n.startsWith("tmnative-") && n.endsWith(".dll"));
					if (old != null)
						for (java.io.File f : old)
							f.delete();
					java.nio.file.Files.copy(lib.toPath(), copy.toPath(),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				return copy.getAbsolutePath();
			} catch (java.io.IOException e) {
				// try the next folder
			}
		}
		return path;
	}

	private static boolean encodable(String s) {
		try {
			String enc = System.getProperty("sun.jnu.encoding");
			return enc == null || java.nio.charset.Charset.forName(enc).newEncoder().canEncode(s);
		} catch (Exception e) {
			return true;
		}
	}

	/**
	 * Searches for the template; out gets {matchIndex, minDiffSq, sumDiffSq, n}.
	 * pts is null for a full rectangle search, else flat {x0, y0, x1, y1, ...}.
	 */
	static native void search(int[] target, int wTest, int[] tR, int[] tG, int[] tB,
			boolean[] transparent, int wTemplate, int hTemplate, int[] pts, int sw, int sh,
			int mode, double[] out);

	/**
	 * Refines the match at test point (xMatch, yMatch) to sub-pixel accuracy,
	 * starting from (dx, dy). (ux, uy) is a unit direction to stay on, or (0, 0)
	 * for a 2D search. On success out gets {dx, dy} and true is returned.
	 */
	static native boolean refine(int[] target, int wTest, int[] tR, int[] tG, int[] tB,
			boolean[] transparent, int wTemplate, int hTemplate, int xMatch, int yMatch,
			double dx, double dy, double ux, double uy, double[] out);

	public static native int threads();

	// ---------------------------------------------------------------- per-frame timing

	/** -Dtracker.native.perf=false turns the timing log off. */
	private static final boolean PERF = !"false".equalsIgnoreCase(System.getProperty("tracker.native.perf"));
	private static long perfLast, perfSumGap, perfSumIn;
	private static int perfN;
	private static String perfSize = "";

	/**
	 * Records one getMatchLocation call. Calls less than a second apart count as
	 * one run; every 200 calls, and at the end of a run, a line with the mean
	 * time per frame (start to start) and the mean time inside the matcher goes
	 * to %TEMP%\tracker-native-perf.log.
	 */
	static synchronized void perf(long start, long end, int wt, int ht, int sw, int sh) {
		if (!PERF)
			return;
		long gap = start - perfLast;
		perfLast = start;
		if (gap > 1_000_000_000L) {
			writePerf();
			return;
		}
		perfSize = wt + "x" + ht + " search " + sw + "x" + sh;
		perfSumGap += gap;
		perfSumIn += end - start;
		if (++perfN >= 200)
			writePerf();
	}

	/** Writes the run so far plus a line of text to the timing log (AutoTest). */
	public static synchronized void perfFlush(String text) {
		writePerf();
		try (java.io.FileWriter w = new java.io.FileWriter(
				new java.io.File(System.getProperty("java.io.tmpdir"), "tracker-native-perf.log"), true)) {
			w.write(text);
		} catch (java.io.IOException e) {
		}
	}

	private static void writePerf() {
		if (perfN >= 10) {
			double frame = perfSumGap / 1e6 / perfN, in = perfSumIn / 1e6 / perfN;
			String line = String.format(
					"%tT  frames=%d  %.2f ms/frame (%.0f fps)  matcher %.2f ms  ahead %d/%d  template %s%n",
					System.currentTimeMillis(), perfN, frame, 1000 / frame, in, TemplateMatcher.specHits,
					TemplateMatcher.specTries, perfSize);
			TemplateMatcher.specHits = TemplateMatcher.specTries = 0;
			try (java.io.FileWriter w = new java.io.FileWriter(
					new java.io.File(System.getProperty("java.io.tmpdir"), "tracker-native-perf.log"), true)) {
				w.write(line);
			} catch (java.io.IOException e) {
				// timing only
			}
		}
		perfN = 0;
		perfSumGap = perfSumIn = 0;
	}
}

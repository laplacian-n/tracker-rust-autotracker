package javajs.async;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;

import org.opensourcephysics.media.core.NativeMatcher;

/**
 * patched: shows a JFileChooser's open/save request in the Windows file dialog
 * (the File Explorer style picker) through tmnative.dll, and puts the result back
 * into the chooser as if the Swing dialog had been used. The chooser's filters
 * become the dialog's "files of type" list: the extensions each filter accepts
 * are found by asking the filter itself.
 *
 * Off with -Dtracker.native.dialog=false; also off when the library is not
 * loaded or not on Windows (the Swing dialog is used then).
 */
final class NativeChooser {

	static final int UNAVAILABLE = Integer.MIN_VALUE;

	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tracker.native.dialog"))
			&& System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

	/** Extensions tried against each filter, besides those named by the filters. */
	private static final String[] COMMON = { "trk", "trz", "zip", "txt", "csv", "tsv", "xml", "jar", "ejs", "html",
			"pdf", "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "mp4", "m4v", "mov", "avi", "wmv", "flv",
			"mpg", "mpeg", "mkv", "webm", "ogg", "ogv", "dv", "mts", "m2ts", "mod", "3gp", "asf", "vob", "ts" };

	private static final Pattern EXT_IN_TEXT = Pattern.compile("(?:^|[\\s(,;*])\\.([A-Za-z0-9]{1,8})\\b");

	private NativeChooser() {
	}

	static int show(JFileChooser fc, String okLabel) {
		if (!ENABLED || GraphicsEnvironment.isHeadless() || !NativeMatcher.libraryLoaded)
			return UNAVAILABLE;
		try {
			return run(fc, okLabel);
		} catch (Throwable t) {
			System.err.println("native file dialog failed, using Swing: " + t);
			return UNAVAILABLE;
		}
	}

	private static int run(JFileChooser fc, String okLabel) throws Exception {
		boolean save = fc.getDialogType() == JFileChooser.SAVE_DIALOG;
		boolean folders = fc.getFileSelectionMode() == JFileChooser.DIRECTORIES_ONLY;
		boolean multi = fc.isMultiSelectionEnabled() && !save;
		int flags = (save ? 1 : 0) | (multi ? 2 : 0) | (folders ? 4 : 0);

		// filters: the chooser's own list (the "all files" filter last, as in Windows)
		List<FileFilter> filters = new ArrayList<>();
		FileFilter all = fc.getAcceptAllFileFilter();
		for (FileFilter f : fc.getChoosableFileFilters())
			if (f != all)
				filters.add(f);
		if (fc.isAcceptAllFileFilterUsed() || filters.isEmpty())
			filters.add(all);
		Set<String> candidates = candidates(fc, filters);
		List<String> names = new ArrayList<>(), specs = new ArrayList<>();
		for (FileFilter f : filters) {
			names.add(f == all ? "All Files" : f.getDescription());
			specs.add(f == all ? "*.*" : spec(f, candidates));
		}
		int index = Math.max(0, filters.indexOf(fc.getFileFilter()));
		if (Boolean.getBoolean("tracker.native.dialog.debug"))
			for (int i = 0; i < names.size(); i++)
				System.out.println("file type " + i + (i == index ? "*" : "") + ": " + names.get(i) + " = " + specs.get(i));

		File dir = fc.getCurrentDirectory();
		File sel = fc.getSelectedFile();
		String name = sel == null ? null : sel.getName();
		if (sel != null && sel.getParentFile() != null && sel.getParentFile().isDirectory())
			dir = sel.getParentFile();
		if (folders && sel != null && sel.isDirectory()) {
			dir = sel.getParentFile() != null ? sel.getParentFile() : sel;
			name = null;
		}
		if (!save)
			name = null;
		String title = fc.getDialogTitle();
		String ok = okLabel != null ? okLabel : fc.getApproveButtonText();

		String[] res = call(flags, title, ok, dir == null ? null : dir.getAbsolutePath(), name,
				names.toArray(new String[0]), specs.toArray(new String[0]), index);
		if (res != null && res.length == 0)
			return UNAVAILABLE;
		if (res == null || res.length < 2) {
			fc.setSelectedFile(null);
			return JFileChooser.CANCEL_OPTION;
		}

		File[] files = new File[res.length - 1];
		for (int i = 1; i < res.length; i++)
			files[i - 1] = new File(res[i]);
		int chosen = Integer.parseInt(res[0]);
		// set the filter first: changing it afterwards can drop the selection
		if (chosen >= 0 && chosen < filters.size() && filters.get(chosen) != fc.getFileFilter())
			fc.setFileFilter(filters.get(chosen));
		File parent = files[0].getParentFile();
		if (parent != null)
			fc.setCurrentDirectory(parent);
		if (multi)
			fc.setSelectedFiles(files);
		fc.setSelectedFile(files[0]);
		return JFileChooser.APPROVE_OPTION;
	}

	/**
	 * Runs the dialog on a thread of its own. On the event thread a secondary loop
	 * keeps Tracker's windows painting while the (modal) dialog is open.
	 */
	private static String[] call(int flags, String title, String ok, String dir, String name, String[] names,
			String[] specs, int index) throws Exception {
		String[][] out = new String[1][];
		Throwable[] err = new Throwable[1];
		Runnable job = () -> {
			try {
				out[0] = NativeMatcher.fileDialog(flags, title, ok, dir, name, names, specs, index);
			} catch (Throwable t) {
				err[0] = t;
			}
		};
		Thread t;
		if (EventQueue.isDispatchThread()) {
			SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
			t = new Thread(() -> {
				job.run();
				// exit() before enter() is ignored: retry until the loop has been entered
				while (!loop.exit()) {
					try {
						Thread.sleep(5);
					} catch (InterruptedException e) {
						return;
					}
				}
			}, "native file dialog");
			t.setDaemon(true);
			t.start();
			loop.enter();
		} else {
			t = new Thread(job, "native file dialog");
			t.setDaemon(true);
			t.start();
		}
		t.join();
		if (err[0] instanceof Exception)
			throw (Exception) err[0];
		if (err[0] != null)
			throw new RuntimeException(err[0]);
		return out[0];
	}

	/** "*.a;*.b" for the candidate extensions the filter accepts. */
	private static String spec(FileFilter f, Set<String> candidates) {
		File dir = new File(System.getProperty("java.io.tmpdir"));
		StringBuilder sb = new StringBuilder();
		for (String ext : candidates) {
			boolean ok;
			try {
				ok = f.accept(new File(dir, "tracker-file-dialog-probe." + ext));
			} catch (Throwable t) {
				ok = false;
			}
			if (ok)
				sb.append(sb.length() == 0 ? "" : ";").append("*.").append(ext);
		}
		return sb.length() == 0 ? "*.*" : sb.toString();
	}

	private static Set<String> candidates(JFileChooser fc, List<FileFilter> filters) {
		Set<String> set = new LinkedHashSet<>();
		for (FileFilter f : filters) {
			if (f == fc.getAcceptAllFileFilter())
				continue;
			Matcher m = EXT_IN_TEXT.matcher(String.valueOf(f.getDescription()));
			while (m.find())
				set.add(m.group(1).toLowerCase(Locale.ROOT));
			fieldExtensions(f, set);
		}
		try {
			for (String e : org.opensourcephysics.media.core.VideoIO.getVideoExtensions())
				set.add(e.toLowerCase(Locale.ROOT));
		} catch (Throwable t) {
		}
		try {
			for (String e : org.opensourcephysics.media.core.VideoIO.KNOWN_VIDEO_EXTENSIONS)
				set.add(e.toLowerCase(Locale.ROOT));
		} catch (Throwable t) {
		}
		for (String e : COMMON)
			set.add(e);
		set.remove("");
		return set;
	}

	/** Extension fields of the OSP filter classes ("ext", "extensions"). */
	private static void fieldExtensions(FileFilter f, Set<String> set) {
		for (Class<?> c = f.getClass(); c != null && c != FileFilter.class; c = c.getSuperclass()) {
			for (Field fd : c.getDeclaredFields()) {
				String n = fd.getName().toLowerCase(Locale.ROOT);
				if (!n.startsWith("ext"))
					continue;
				try {
					fd.setAccessible(true);
					add(fd.get(f), set);
				} catch (Throwable t) {
				}
			}
		}
	}

	private static void add(Object v, Set<String> set) {
		if (v instanceof String)
			set.add(((String) v).replace("*", "").replace(".", "").trim().toLowerCase(Locale.ROOT));
		else if (v instanceof String[])
			for (String s : (String[]) v)
				add(s, set);
		else if (v instanceof Collection)
			for (Object o : (Collection<?>) v)
				add(o, set);
	}
}

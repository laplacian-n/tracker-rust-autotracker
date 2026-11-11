import java.io.File;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import javajs.async.AsyncFileChooser;

/**
 * Opens Tracker's file chooser the way TrackerIO does ("open", "save tabset")
 * with Tracker's own filters, for driving the Windows file dialog from a script:
 *
 *   java -Dtracker.native.dialog.debug=true -cp ... DialogCheck open|save|cancel
 *
 * Prints "result: <option> <file>" and exits.
 */
public class DialogCheck {
	public static void main(String[] args) throws Exception {
		String what = args.length > 0 ? args[0] : "open";
		org.opensourcephysics.media.core.NativeMatcher.mode += 0; // load tmnative.dll
		SwingUtilities.invokeAndWait(() -> {
			JFrame f = new JFrame("DialogCheck owner");
			f.setSize(300, 200);
			f.setLocationRelativeTo(null);
			f.setVisible(true);
			f.toFront();
		});
		Thread.sleep(500);
		SwingUtilities.invokeAndWait(() -> {
			AsyncFileChooser fc = new AsyncFileChooser();
			// the filters are VideoIO fields set up by TrackerIO's static initializer
			try {
				Class.forName("org.opensourcephysics.cabrillo.tracker.TrackerIO");
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			}
			fc.setAcceptAllFileFilterUsed(true);
			if (what.equals("save")) {
				fc.addChoosableFileFilter(org.opensourcephysics.cabrillo.tracker.TrackerIO.trkFileFilter);
				fc.setDialogTitle("DialogCheck Save");
				fc.setSelectedFile(new File(System.getProperty("java.io.tmpdir"), "dialogcheck.trk"));
				fc.showSaveDialog(null, () -> {
				}, () -> {
				});
			} else {
				fc.addChoosableFileFilter(org.opensourcephysics.cabrillo.tracker.TrackerIO.videoAndTrkFileFilter);
				fc.setFileFilter(org.opensourcephysics.cabrillo.tracker.TrackerIO.videoAndTrkFileFilter);
				fc.setDialogTitle("DialogCheck Open");
				fc.showOpenDialog(null, () -> {
				}, () -> {
				});
			}
			System.out.println("result: " + (fc.getSelectedOption() == JFileChooser.APPROVE_OPTION ? "APPROVE" : "CANCEL")
					+ " " + fc.getSelectedFile() + " filter=" + (fc.getFileFilter() == null ? null : fc.getFileFilter().getDescription()));
		});
		System.exit(0);
	}
}

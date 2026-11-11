package org.opensourcephysics.cabrillo.tracker;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import org.opensourcephysics.controls.XMLControl;
import org.opensourcephysics.controls.XMLControlElement;
import org.opensourcephysics.media.core.ImageCoordSystem;
import org.opensourcephysics.media.core.NativeMatcher;
import org.opensourcephysics.media.core.PatternGeometry;
import org.opensourcephysics.media.core.PerspectiveFilter;
import org.opensourcephysics.media.core.Video;

/**
 * patched (new): Coordinate System > Calibrate from Pattern...
 *
 * Finds a printed checkerboard in the current frame and sets the scale, origin
 * and axes from all its corners. Reports how far the board is tilted from
 * square-on to the camera, the true scale at its centre and how much the scale
 * changes across it, and offers to correct the perspective with a Perspective
 * filter so that one scale holds everywhere in the board's plane.
 */
final class PatternCalibration {

	private static String lastSize = "20";
	private static String lastUnit = "mm";
	private static final String[] UNITS = { "mm", "cm", "m", "in" };

	private PatternCalibration() {
	}

	static void run(TrackerPanel panel) {
		TFrame frame = panel.getTFrame();
		Video video = panel.getVideo();
		ImageCoordSystem coords = panel.getCoords();
		if (video == null) {
			JOptionPane.showMessageDialog(frame, "Open a video first.", "Calibrate from Pattern", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (coords.isLocked() || coords instanceof ReferenceFrame) {
			JOptionPane.showMessageDialog(frame,
					"The coordinate system is locked or is a reference frame.\nUnlock it / choose the default reference frame first.",
					"Calibrate from Pattern", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (!NativeMatcher.libraryLoaded) {
			JOptionPane.showMessageDialog(frame, "The native library (tmnative.dll) is not loaded.", "Calibrate from Pattern",
					JOptionPane.ERROR_MESSAGE);
			return;
		}

		// 1. square size
		JTextField size = new JTextField(lastSize, 6);
		JComboBox<String> unit = new JComboBox<>(UNITS);
		unit.setSelectedItem(lastUnit);
		JPanel ask = new JPanel(new BorderLayout(0, 8));
		ask.add(new JLabel("<html>Show a printed checkerboard lying in the plane of the motion, on this frame,<br>"
				+ "fully visible (the white margin around it too). Then give the size of one square:</html>"),
				BorderLayout.NORTH);
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		row.add(new JLabel("Square size"));
		row.add(size);
		row.add(unit);
		ask.add(row, BorderLayout.CENTER);
		if (JOptionPane.showConfirmDialog(frame, ask, "Calibrate from Pattern", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
			return;
		double square;
		try {
			square = Double.parseDouble(size.getText().trim().replace(',', '.'));
			if (!(square > 0))
				throw new NumberFormatException();
		} catch (NumberFormatException e) {
			JOptionPane.showMessageDialog(frame, "The square size must be a positive number.", "Calibrate from Pattern",
					JOptionPane.ERROR_MESSAGE);
			return;
		}
		lastSize = size.getText().trim();
		lastUnit = (String) unit.getSelectedItem();
		String u = lastUnit;

		// 2. find the board
		BufferedImage img = video.getImage();
		if (img == null)
			return;
		int w = img.getWidth(), h = img.getHeight();
		int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
		double[] out = new double[4 * 4000];
		frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		int n;
		try {
			n = NativeMatcher.findCheckerboard(rgb, w, h, out);
		} finally {
			frame.setCursor(Cursor.getDefaultCursor());
		}
		PatternGeometry g = PatternGeometry.fromCorners(out, n, w, h, square);
		if (g == null || g.cols < 3 || g.rows < 3) {
			JOptionPane.showMessageDialog(frame,
					"<html>No checkerboard found on this frame.<br><br>"
							+ "Check that the whole board and some white margin are in view, in focus,<br>"
							+ "without glare, and at least about 8 pixels per square.</html>",
					"Calibrate from Pattern", JOptionPane.WARNING_MESSAGE);
			return;
		}

		// 3. report and choose
		double boardSize = Math.hypot((g.cols - 1) * square, (g.rows - 1) * square);
		boolean recommendCorrection = g.simMaxWorld > 0.0025 * boardSize || g.tiltDeg > 4;
		StringBuilder sb = new StringBuilder("<html><body style='width:330px'>");
		sb.append(String.format(Locale.US, "<b>Found %d &times; %d inner corners</b> (%d points), squares %s %s.<br><br>",
				g.cols, g.rows, g.count, fmt(square), u));
		sb.append(String.format(Locale.US, "<b>Board tilt from square-on: %.1f&deg;</b>", g.tiltDeg));
		if (g.tiltDeg >= 0.5)
			sb.append(" &mdash; the ").append(g.fartherSide).append(" side is farther from the camera");
		sb.append(".<br>");
		sb.append(g.focalMeasured
				? String.format(Locale.US, "<small>(camera focal length measured from the board: %.0f px; distance to the board &asymp; %s %s)</small><br>",
						g.focal, fmt(g.distance), u)
				: "<small>(too little perspective to measure the focal length; tilt computed with a typical one)</small><br>");
		sb.append(String.format(Locale.US, "<br><b>True scale at the board centre:</b> %.4f px/%s along x, %.4f px/%s along y.<br>",
				g.scaleCentreX, u, g.scaleCentreY, u));
		sb.append(String.format(Locale.US, "The scale changes by <b>%.1f%%</b> over the board (%.4f &ndash; %.4f px/%s).<br><br>",
				100 * (g.scaleMax - g.scaleMin) / g.scaleCentre, g.scaleMin, g.scaleMax, u));
		sb.append(String.format(Locale.US, "One scale for the whole image (%.4f px/%s): error up to <b>%s %s</b> on the board.<br>",
				g.simScale, u, fmt(g.simMaxWorld), u));
		sb.append(String.format(Locale.US, "With perspective correction: the board's corners fit a flat grid to %.2f px RMS.<br><br>",
				g.homographyRms));
		sb.append("<small>Valid for motion in the plane of the board. Objects nearer to or farther from the camera "
				+ "than the board get a different scale.</small></body></html>");

		JRadioButton simple = new JRadioButton("Scale, origin and axes only");
		JRadioButton persp = new JRadioButton("Also correct the perspective (adds a Perspective filter)");
		ButtonGroup group = new ButtonGroup();
		group.add(simple);
		group.add(persp);
		(recommendCorrection ? persp : simple).setSelected(true);
		JLabel rec = new JLabel(recommendCorrection ? "Recommended: correct the perspective (the tilt is not negligible)."
				: "Recommended: scale only (the board is close to square-on).");
		rec.setForeground(recommendCorrection ? new Color(170, 80, 0) : new Color(0, 120, 60));

		JDialog dialog = new JDialog(frame, "Calibrate from Pattern", true);
		JPanel content = new JPanel(new GridBagLayout());
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(0, 0, 0, 12);
		c.gridx = 0;
		c.gridy = 0;
		c.gridheight = 4;
		c.anchor = GridBagConstraints.NORTHWEST;
		content.add(new JLabel(new ImageIcon(preview(img, g))), c);
		c.gridx = 1;
		c.gridheight = 1;
		c.insets = new Insets(0, 0, 6, 0);
		content.add(new JLabel(sb.toString()), c);
		c.gridy++;
		content.add(rec, c);
		c.gridy++;
		JPanel radios = new JPanel(new GridBagLayout());
		GridBagConstraints rc = new GridBagConstraints();
		rc.gridx = 0;
		rc.anchor = GridBagConstraints.WEST;
		radios.add(simple, rc);
		radios.add(persp, rc);
		content.add(radios, c);
		c.gridy++;
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton apply = new JButton("Apply");
		JButton cancel = new JButton("Cancel");
		buttons.add(apply);
		buttons.add(cancel);
		c.anchor = GridBagConstraints.SOUTHEAST;
		content.add(buttons, c);
		boolean[] ok = { false };
		apply.addActionListener((e) -> {
			ok[0] = true;
			dialog.dispose();
		});
		cancel.addActionListener((e) -> dialog.dispose());
		dialog.getRootPane().setDefaultButton(apply);
		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(frame);
		dialog.setVisible(true);
		if (!ok[0])
			return;

		// 4. apply
		apply(panel, g, persp.isSelected(), u);
	}

	/** Sets the coordinate system (and with perspective, adds the Perspective filter). */
	static void apply(TrackerPanel panel, PatternGeometry g, boolean perspective, String u) {
		Video video = panel.getVideo();
		ImageCoordSystem coords = panel.getCoords();
		XMLControl previous = new XMLControlElement(coords);
		double scale, ox, oy, angle;
		if (perspective) {
			XMLControl fc = new XMLControlElement(PerspectiveFilter.class);
			fc.setValue("fixed_in", true);
			fc.setValue("fixed_out", true);
			fc.setValue("in_corners", new double[][][] { g.inQuad });
			fc.setValue("out_corners", new double[][][] { g.outQuad });
			fc.setValue("disabled", false);
			PerspectiveFilter filter = (PerspectiveFilter) fc.loadObject(null);
			video.getFilterStack().addFilter(filter);
			filter.setVideoPanel(panel);
			scale = g.rectScale;
			ox = g.rectOriginX;
			oy = g.rectOriginY;
			angle = 0;
		} else {
			scale = g.simScale;
			ox = g.simOriginX;
			oy = g.simOriginY;
			angle = g.simAngle;
		}
		coords.setFixedOrigin(true);
		coords.setFixedAngle(true);
		coords.setFixedScale(true);
		coords.setAllScalesXY(scale, scale);
		coords.setAllOriginsXY(ox, oy);
		coords.setAllCosineSines(Math.cos(angle), Math.sin(angle));
		Undo.postCoordsEdit(panel, previous);
		panel.setLengthUnit(u, true);
		if (panel.getAxes() != null)
			panel.getAxes().setVisible(true);
		panel.repaint();
	}

	private static String fmt(double v) {
		double a = Math.abs(v);
		return String.format(Locale.US, a >= 100 ? "%.0f" : a >= 10 ? "%.1f" : a >= 1 ? "%.2f" : "%.3f", v);
	}

	/** the board region with the corners found, the origin and the axes drawn on it */
	private static BufferedImage preview(BufferedImage img, PatternGeometry g) {
		double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
		for (int k = 0; k < g.count; k++) {
			x0 = Math.min(x0, g.ix[k]);
			x1 = Math.max(x1, g.ix[k]);
			y0 = Math.min(y0, g.iy[k]);
			y1 = Math.max(y1, g.iy[k]);
		}
		double m = 0.25 * Math.max(x1 - x0, y1 - y0) + 10;
		int cx0 = (int) Math.max(0, x0 - m), cy0 = (int) Math.max(0, y0 - m);
		int cx1 = (int) Math.min(img.getWidth(), x1 + m), cy1 = (int) Math.min(img.getHeight(), y1 + m);
		double s = Math.min(1.0, 380.0 / Math.max(cx1 - cx0, cy1 - cy0));
		s = Math.max(s, Math.min(4.0, 380.0 / Math.max(cx1 - cx0, cy1 - cy0)));
		int pw = Math.max(1, (int) ((cx1 - cx0) * s)), ph = Math.max(1, (int) ((cy1 - cy0) * s));
		BufferedImage p = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = p.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.drawImage(img, 0, 0, pw, ph, cx0, cy0, cx1, cy1, null);
		g2.setStroke(new BasicStroke(1.5f));
		g2.setColor(new Color(0, 200, 90));
		for (int k = 0; k < g.count; k++) {
			double px = (g.ix[k] + 0.5 - cx0) * s, py = (g.iy[k] + 0.5 - cy0) * s;
			g2.draw(new Ellipse2D.Double(px - 3, py - 3, 6, 6));
		}
		// axes from the origin, one board width / height long
		double[] o = PatternGeometry.apply(g.H, 0, 0);
		double[] ax = PatternGeometry.apply(g.H, (g.cols - 1) * g.square, 0);
		double[] ay = PatternGeometry.apply(g.H, 0, (g.rows - 1) * g.square);
		g2.setStroke(new BasicStroke(2.5f));
		g2.setColor(new Color(230, 40, 40));
		g2.draw(new Line2D.Double((o[0] + 0.5 - cx0) * s, (o[1] + 0.5 - cy0) * s, (ax[0] + 0.5 - cx0) * s, (ax[1] + 0.5 - cy0) * s));
		g2.setColor(new Color(40, 90, 230));
		g2.draw(new Line2D.Double((o[0] + 0.5 - cx0) * s, (o[1] + 0.5 - cy0) * s, (ay[0] + 0.5 - cx0) * s, (ay[1] + 0.5 - cy0) * s));
		g2.setColor(Color.WHITE);
		g2.drawString("x", (float) ((ax[0] + 0.5 - cx0) * s + 4), (float) ((ax[1] + 0.5 - cy0) * s - 4));
		g2.drawString("y", (float) ((ay[0] + 0.5 - cx0) * s + 4), (float) ((ay[1] + 0.5 - cy0) * s - 4));
		g2.dispose();
		return p;
	}
}

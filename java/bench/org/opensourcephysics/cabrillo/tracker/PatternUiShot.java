package org.opensourcephysics.cabrillo.tracker;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.opensourcephysics.media.core.NativeMatcher;
import org.opensourcephysics.media.core.PatternGeometry;

/**
 * Screenshots of the Calibrate from Pattern dialogs, as Tracker shows them, on a
 * rendered frame (board tilted by the given angles):
 *
 *   java ... PatternUiShot outDir [tilt yaw]
 */
public class PatternUiShot {

	public static void main(String[] args) throws Exception {
		File dir = new File(args[0]);
		double tilt = args.length > 1 ? Double.parseDouble(args[1]) : 18, yaw = args.length > 2 ? Double.parseDouble(args[2]) : 12;
		UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		NativeMatcher.mode += 0;
		Class<?> pc = Class.forName("PatternCheck");
		Method camera = pc.getDeclaredMethod("camera", double.class, double.class, double.class, double.class);
		Method render = pc.getDeclaredMethod("render", double[][].class, java.util.Random.class);
		camera.setAccessible(true);
		render.setAccessible(true);
		int w = 1080, h = 1920;
		int[] rgb = (int[]) render.invoke(null, camera.invoke(null, tilt, yaw, 6.0, 22.0), new java.util.Random(5));
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, w, h, rgb, 0, w);
		double[] out = new double[4 * 4000];
		int n = NativeMatcher.findCheckerboard(rgb, w, h, out);
		PatternGeometry g = PatternGeometry.fromCorners(out, n, w, h, 20);

		shoot(dir, "calibrate_ask.png", () -> {
			JTextField size = new JTextField("20", 6);
			JComboBox<String> unit = new JComboBox<>(new String[] { "mm", "cm", "m", "in" });
			JOptionPane pane = new JOptionPane(PatternCalibration.askPanel(size, unit), JOptionPane.PLAIN_MESSAGE,
					JOptionPane.OK_CANCEL_OPTION);
			return pane.createDialog(null, "Calibrate from Pattern");
		});
		shoot(dir, "calibrate_result.png", () -> {
			JRadioButton simple = new JRadioButton("Scale, origin and axes only");
			JRadioButton persp = new JRadioButton("Also correct the perspective (adds a Perspective filter)");
			JDialog d = new JDialog((java.awt.Frame) null, "Calibrate from Pattern", false);
			d.setContentPane(PatternCalibration.resultPanel(img, g, 20, "mm", simple, persp, new JButton("Apply"),
					new JButton("Cancel")));
			d.pack();
			return d;
		});
		System.exit(0);
	}

	interface Maker {
		JDialog make();
	}

	static void shoot(File dir, String name, Maker maker) throws Exception {
		JDialog[] d = new JDialog[1];
		SwingUtilities.invokeAndWait(() -> {
			d[0] = maker.make();
			d[0].setModal(false);
			d[0].setLocation(80, 80);
			d[0].setAlwaysOnTop(true);
			d[0].setVisible(true);
			d[0].toFront();
		});
		Thread.sleep(1200);
		Rectangle r = d[0].getBounds();
		BufferedImage shot = new Robot().createScreenCapture(r);
		ImageIO.write(shot, "png", new File(dir, name));
		SwingUtilities.invokeAndWait(() -> d[0].dispose());
		System.out.println(name + " " + r.width + "x" + r.height);
	}
}

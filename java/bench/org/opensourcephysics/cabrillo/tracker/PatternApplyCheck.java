package org.opensourcephysics.cabrillo.tracker;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

import org.opensourcephysics.media.core.ImageCoordSystem;
import org.opensourcephysics.media.core.ImageVideo;
import org.opensourcephysics.media.core.NativeMatcher;
import org.opensourcephysics.media.core.PatternGeometry;

/**
 * End-to-end check of Calibrate from Pattern inside a real TrackerPanel: a board
 * rendered through a tilted camera (PatternCheck) becomes the video, the
 * calibration is applied (scale only, then with perspective correction), and the
 * board's corners - found again in the image Tracker shows, through any filter -
 * are turned into world coordinates by Tracker's coordinate system. Prints the
 * largest distance from the true square grid in mm.
 */
public class PatternApplyCheck {

	public static void main(String[] args) throws Exception {
		NativeMatcher.mode += 0;
		Class<?> pc = Class.forName("PatternCheck");
		Method camera = pc.getDeclaredMethod("camera", double.class, double.class, double.class, double.class);
		Method render = pc.getDeclaredMethod("render", double[][].class, java.util.Random.class);
		camera.setAccessible(true);
		render.setAccessible(true);
		int w = 1080, h = 1920;
		double sq = 20;
		System.out.printf("%5s %5s | %-12s | %10s %10s %8s%n", "tilt", "yaw", "mode", "px/mm", "max err mm", "angle");
		for (double[] c : new double[][] { { 3, 0 }, { 15, 10 }, { 35, -20 } }) {
			double[][] cam = (double[][]) camera.invoke(null, c[0], c[1], 9.0, 28.0);
			int[] rgb = (int[]) render.invoke(null, cam, new java.util.Random(3));
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			img.setRGB(0, 0, w, h, rgb, 0, w);
			for (boolean persp : new boolean[] { false, true }) {
				TrackerPanel panel = new TrackerPanel();
				panel.setVideo(new ImageVideo(img));
				BufferedImage shown = panel.getVideo().getImage();
				double[] out = new double[4 * 4000];
				int n = NativeMatcher.findCheckerboard(shown.getRGB(0, 0, w, h, null, 0, w), w, h, out);
				PatternGeometry g = PatternGeometry.fromCorners(out, n, w, h, sq);
				PatternCalibration.apply(panel, g, persp, "mm");
				// what Tracker now shows, and its coordinates
				BufferedImage now = panel.getVideo().getImage();
				int m = NativeMatcher.findCheckerboard(now.getRGB(0, 0, w, h, null, 0, w), w, h, out);
				PatternGeometry g2 = PatternGeometry.fromCorners(out, m, w, h, sq);
				ImageCoordSystem coords = panel.getCoords();
				int f = panel.getFrameNumber();
				// world positions of the corners; compare with the square grid after removing the offset
				double ox = 0, oy = 0;
				double[] xs = new double[g2.count], ys = new double[g2.count];
				for (int k = 0; k < g2.count; k++) {
					xs[k] = coords.imageToWorldX(f, g2.ix[k] + 0.5, g2.iy[k] + 0.5);
					ys[k] = coords.imageToWorldY(f, g2.ix[k] + 0.5, g2.iy[k] + 0.5);
					ox += xs[k] - g2.wx[k];
					oy += ys[k] - g2.wy[k];
				}
				ox /= g2.count;
				oy /= g2.count;
				double max = 0;
				for (int k = 0; k < g2.count; k++)
					max = Math.max(max, Math.hypot(xs[k] - g2.wx[k] - ox, ys[k] - g2.wy[k] - oy));
				System.out.printf("%5.0f %5.0f | %-12s | %10.4f %10.3f %8.2f  (origin offset %.2f, %.2f mm; %d corners)%n", c[0],
						c[1], persp ? "perspective" : "scale only", coords.getScaleX(f), max,
						Math.toDegrees(coords.getAngle(f)), ox, oy, g2.count);
			}
		}
		System.exit(0);
	}
}

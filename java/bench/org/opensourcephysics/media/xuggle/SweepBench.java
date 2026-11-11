package org.opensourcephysics.media.xuggle;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

import org.opensourcephysics.media.core.Patch;
import org.opensourcephysics.media.core.TemplateMatcher;

/**
 * Time per getMatchLocation call, stock Tracker code (Patch.ON = false: Java loop +
 * parabola) vs this build (Patch.ON = true: native auto mode + refine), on a real
 * video frame, for a sweep of search sizes and template sizes. Prints CSV:
 *   sweep,template,search,mode,ms_median,reps
 *
 * Usage: java SweepBench <video> <frame> <cx> <cy>
 */
public class SweepBench {
	public static void main(String[] a) throws Exception {
		XuggleVideo v = new XuggleVideo(a[0], null);
		Method get = XuggleVideo.class.getDeclaredMethod("getImage", int.class);
		get.setAccessible(true);
		BufferedImage raw = (BufferedImage) get.invoke(v, Integer.parseInt(a[1]));
		// the frame as Tracker matches it: VideoAdapter's INT_RGB buffer
		BufferedImage f = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = f.createGraphics();
		g.drawImage(raw, 0, 0, null);
		g.dispose();
		int cx = Integer.parseInt(a[2]), cy = Integer.parseInt(a[3]);
		System.out.println("sweep,template,search,mode,ms_median,reps");
		for (int t : new int[] { 41, 161 })
			for (int s : new int[] { 21, 41, 81, 121, 181, 241, 321, 401 })
				run("search", f, cx, cy, t, s);
		for (int s : new int[] { 181 })
			for (int t : new int[] { 11, 21, 41, 61, 81, 121, 161, 201 })
				run("template", f, cx, cy, t, s);
		v.dispose();
		System.exit(0);
	}

	static void run(String sweep, BufferedImage f, int cx, int cy, int t, int s) {
		for (boolean on : new boolean[] { false, true }) {
			Patch.ON = on;
			Rectangle r = new Rectangle(cx - t / 2, cy - t / 2, t, t);
			Shape mask = AffineTransform.getTranslateInstance(-r.x, -r.y)
					.createTransformedShape(new Ellipse2D.Double(r.x, r.y, t, t));
			TemplateMatcher m = new TemplateMatcher(crop(f, r), mask);
			Rectangle search = new Rectangle(cx - s / 2, cy - s / 2, s, s);
			m.getMatchLocation(f, new Rectangle(search), null, 0, 0, 0); // warm-up
			double budget = on ? 0.6e9 : 2.5e9;
			java.util.List<Long> times = new java.util.ArrayList<>();
			long total = 0;
			while ((total < budget && times.size() < 200) || times.size() < 3 && total < 20e9) {
				long t0 = System.nanoTime();
				m.getMatchLocation(f, new Rectangle(search), null, 0, 0, 0);
				long dt = System.nanoTime() - t0;
				times.add(dt);
				total += dt;
			}
			java.util.Collections.sort(times);
			double med = times.get(times.size() / 2) / 1e6;
			System.out.printf("%s,%d,%d,%s,%.4f,%d%n", sweep, t, s, on ? "new" : "stock", med, times.size());
			System.out.flush();
		}
	}

	static BufferedImage crop(BufferedImage src, Rectangle r) {
		BufferedImage b = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = b.createGraphics();
		g.drawImage(src, -r.x, -r.y, null);
		g.dispose();
		return b;
	}
}

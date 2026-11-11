import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.opensourcephysics.media.core.TPoint;
import org.opensourcephysics.media.core.TemplateMatcher;

/**
 * Times TemplateMatcher.getMatchLocation on synthetic 1280x720 frames with a
 * textured ball and a circular template mask, the way AutoTracker calls it.
 *
 * Usage: java Bench <label> [modes...]   modes: -1 = Java loop, 0..11 = Rust (see lib.rs)
 * With only tracker.jar on the classpath (no NativeMatcher) it times the
 * original installed class.
 */
public class Bench {

	static final int W = 1280, H = 720, FRAMES = 8;

	/** {template size, search size, linear search (0/1)} */
	static final int[][] SCENES = { { 21, 41, 0 }, { 41, 101, 0 }, { 61, 201, 0 }, { 81, 401, 0 }, { 41, 401, 1 } };

	public static void main(String[] args) throws Exception {
		String label = args.length > 0 ? args[0] : "run";
		Field modeField = null;
		try {
			Class<?> nm = Class.forName("org.opensourcephysics.media.core.NativeMatcher");
			modeField = nm.getField("mode");
			System.out.println("# native library loaded, rayon threads = " + nm.getMethod("threads").invoke(null));
		} catch (Throwable t) {
			System.out.println("# original TemplateMatcher (no NativeMatcher)");
		}
		List<Integer> modes = new ArrayList<>();
		for (int i = 1; i < args.length; i++)
			modes.add(Integer.parseInt(args[i]));
		if (modes.isEmpty() || modeField == null)
			modes = Arrays.asList(-1);

		BufferedImage background = background(new Random(1));
		for (int[] sc : SCENES) {
			int tpl = sc[0], search = sc[1];
			boolean linear = sc[2] == 1;
			BufferedImage[] frames = new BufferedImage[FRAMES];
			double[][] truth = new double[FRAMES][2];
			for (int f = 0; f < FRAMES; f++) {
				truth[f][0] = 400 + 3.37 * f;
				truth[f][1] = 300 + 1.91 * f;
				frames[f] = frame(background, truth[f][0], truth[f][1], tpl * 0.42);
			}
			// template: square crop around the ball in frame 0, circular mask
			int tx = (int) Math.round(truth[0][0]) - tpl / 2, ty = (int) Math.round(truth[0][1]) - tpl / 2;
			BufferedImage templ = new BufferedImage(tpl, tpl, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = templ.createGraphics();
			g.drawImage(frames[0].getSubimage(tx, ty, tpl, tpl), 0, 0, null);
			g.dispose();
			Ellipse2D mask = new Ellipse2D.Double(0, 0, tpl, tpl);

			for (int mode : modes) {
				if (modeField != null)
					modeField.setInt(null, mode);
				TemplateMatcher tm = new TemplateMatcher(templ, mask);
				StringBuilder sig = new StringBuilder();
				// warm up the JIT, then time
				for (int i = 0; i < 40; i++)
					match(tm, frames[i % FRAMES], truth[i % FRAMES], search, linear);
				long budget = 1_500_000_000L, start = System.nanoTime();
				List<Long> times = new ArrayList<>();
				int i = 0;
				while (System.nanoTime() - start < budget || times.size() < 20) {
					int f = i++ % FRAMES;
					long t0 = System.nanoTime();
					TPoint p = match(tm, frames[f], truth[f], search, linear);
					times.add(System.nanoTime() - t0);
					if (i <= FRAMES) {
						double[] wh = tm.getMatchWidthAndHeight();
						sig.append(String.format("%.6f,%.6f,%.6g,%.6g;", p.x, p.y, wh[0], wh[1]));
					}
				}
				times.sort(null);
				double med = times.get(times.size() / 2) / 1000.0;
				System.out.printf("%s\ttpl=%d\tsearch=%d%s\tmode=%d\tmedian_us=%.1f\tcalls=%d\tsig=%08x\tfirst=%s%n", label, tpl, search,
						linear ? "(line)" : "", mode, med, times.size(), sig.toString().hashCode(), sig.substring(0, sig.indexOf(";")));
			}
		}
	}

	static TPoint match(TemplateMatcher tm, BufferedImage frame, double[] truth, int search, boolean linear) {
		// AutoTracker centres the search on the predicted template corner
		int x = (int) truth[0] - search / 2, y = (int) truth[1] - search / 2;
		Rectangle rect = new Rectangle(x, y, search, search);
		int[][] pts = null;
		double theta = 0.5;
		if (linear)
			pts = tm.getSearchPoints(new Rectangle(rect), search / 2.0, search / 2.0, theta, 2);
		return tm.getMatchLocation(frame, rect, pts, search / 2.0, search / 2.0, theta);
	}

	/** Smooth coloured noise, like a real scene. */
	static BufferedImage background(Random r) {
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(120, 130, 125));
		g.fillRect(0, 0, W, H);
		for (int i = 0; i < 4000; i++) {
			g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256), 60));
			int s = 4 + r.nextInt(40);
			g.fillOval(r.nextInt(W), r.nextInt(H), s, s);
		}
		g.dispose();
		return img;
	}

	/** Background plus a striped ball at a sub-pixel position. */
	static BufferedImage frame(BufferedImage bg, double cx, double cy, double rad) {
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.drawImage(bg, 0, 0, null);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(230, 60, 40));
		g.fill(new Ellipse2D.Double(cx - rad, cy - rad, 2 * rad, 2 * rad));
		g.setColor(new Color(250, 230, 60));
		g.fill(new Ellipse2D.Double(cx - rad * 0.5, cy - rad * 0.8, rad * 0.6, rad * 0.6));
		g.setColor(new Color(30, 30, 160));
		g.fill(new Ellipse2D.Double(cx + rad * 0.1, cy + rad * 0.1, rad * 0.5, rad * 0.5));
		g.dispose();
		return img;
	}
}

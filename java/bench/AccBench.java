import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.util.Random;

import org.opensourcephysics.media.core.TPoint;
import org.opensourcephysics.media.core.TemplateMatcher;

/**
 * Accuracy and frame-type benchmark for TemplateMatcher.
 *
 * Frames are rendered analytically (blurred textured ball on smooth background
 * plus camera noise) so the true sub-pixel position is known exactly; Java2D
 * anti-aliasing is not used because it quantises edges to 1/8 pixel.
 *
 * Usage: java AccBench <label>
 * With NativeMatcher on the classpath it compares Java / Rust+parabola /
 * Rust+refine; with only tracker.jar it measures the original class.
 */
public class AccBench {

	static final int W = 480, H = 360, FRAMES = 200;
	static Field modeField, refineField;

	public static void main(String[] args) throws Exception {
		String label = args.length > 0 ? args[0] : "run";
		try {
			Class<?> nm = Class.forName("org.opensourcephysics.media.core.NativeMatcher");
			modeField = nm.getField("mode");
			refineField = nm.getField("refine");
		} catch (Throwable t) {
			System.out.println("# original TemplateMatcher");
		}
		String[][] configs = modeField == null ? new String[][] { { "java", "-1", "0" } }
				: new String[][] { { "java", "-1", "0" }, { "rust+parabola", "7", "0" }, { "rust+refine", "7", "1" } };
		for (int tpl : new int[] { 15, 21, 41 }) {
			for (double noise : new double[] { 0, 3 }) {
				for (String[] c : configs)
					accuracy(label, c, tpl, noise, false);
			}
		}
		for (String[] c : configs)
			accuracy(label, c, 21, 3, true);
		frameTypes(label);
	}

	static void set(String[] c) throws Exception {
		if (modeField != null) {
			modeField.setInt(null, Integer.parseInt(c[1]));
			refineField.setBoolean(null, c[2].equals("1"));
		}
	}

	/** RMS / max position error over FRAMES random sub-pixel positions. */
	static void accuracy(String label, String[] c, int tpl, double noise, boolean linear) throws Exception {
		set(c);
		Random r = new Random(42);
		Scene sc = new Scene(new Random(7), tpl * 0.4);
		double theta = 0.5, ux = Math.cos(theta), uy = -Math.sin(theta);
		double cx0 = W / 2 + 0.3, cy0 = H / 2 + 0.2;
		int[] f0 = sc.render(cx0, cy0, noise, r);
		BufferedImage img0 = toImage(f0);
		int tx = (int) Math.round(cx0) - tpl / 2, ty = (int) Math.round(cy0) - tpl / 2;
		BufferedImage templ = new BufferedImage(tpl, tpl, BufferedImage.TYPE_INT_ARGB);
		templ.getGraphics().drawImage(img0.getSubimage(tx, ty, tpl, tpl), 0, 0, null);
		TemplateMatcher tm = new TemplateMatcher(templ, new Ellipse2D.Double(0, 0, tpl, tpl));
		int search = 2 * tpl + 21;
		TPoint p0 = match(tm, img0, cx0, cy0, search, linear, cx0, cy0, theta);
		double se = 0, max = 0;
		int n = 0, fails = 0;
		for (int f = 0; f < FRAMES; f++) {
			double ex, ey, dxT, dyT;
			if (linear) {
				double t = (r.nextDouble() - 0.5) * 16;
				dxT = t * ux;
				dyT = t * uy;
			} else {
				dxT = (r.nextDouble() - 0.5) * 16;
				dyT = (r.nextDouble() - 0.5) * 16;
			}
			BufferedImage img = toImage(sc.render(cx0 + dxT, cy0 + dyT, noise, r));
			TPoint p = match(tm, img, cx0 + dxT, cy0 + dyT, search, linear, cx0, cy0, theta);
			if (p == null) {
				fails++;
				continue;
			}
			ex = p.x - p0.x - dxT;
			ey = p.y - p0.y - dyT;
			// along the line only: the search is 1D
			double e = linear ? Math.abs(ex * ux + ey * uy) : Math.hypot(ex, ey);
			se += e * e;
			max = Math.max(max, e);
			n++;
		}
		System.out.printf("%s\t%-14s tpl=%d noise=%.0f%s\trms_px=%.4f\tmax_px=%.4f\tfails=%d%n", label, c[0], tpl, noise,
				linear ? " line" : "     ", Math.sqrt(se / n), max, fails);
	}

	static TPoint match(TemplateMatcher tm, BufferedImage img, double cx, double cy, int search, boolean linear,
			double lx, double ly, double theta) {
		// search centred on the predicted position (slightly off, like a real prediction)
		Rectangle rect = new Rectangle((int) cx - search / 2 + 2, (int) cy - search / 2 - 1, search, search);
		int[][] pts = linear ? tm.getSearchPoints(new Rectangle(rect), lx, ly, theta, 2) : null;
		return tm.getMatchLocation(img, rect, pts, lx, ly, theta);
	}

	/** Time per match for INT_RGB vs 3BYTE_BGR frames at 1920x1080. */
	static void frameTypes(String label) throws Exception {
		int w = 1920, h = 1080;
		Scene sc = new Scene(new Random(3), 10);
		Random r = new Random(1);
		BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		rgb.getGraphics().drawImage(toImage(sc.render(W / 2, H / 2, 2, r)), 0, 0, w, h, null);
		BufferedImage bgr = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
		bgr.getGraphics().drawImage(rgb, 0, 0, null);
		BufferedImage templ = new BufferedImage(31, 31, BufferedImage.TYPE_INT_ARGB);
		templ.getGraphics().drawImage(rgb.getSubimage(w / 2 - 15, h / 2 - 15, 31, 31), 0, 0, null);
		String[] c = modeField == null ? new String[] { "java", "-1", "0" } : new String[] { "rust+refine", "7", "1" };
		set(c);
		for (BufferedImage img : new BufferedImage[] { rgb, bgr }) {
			TemplateMatcher tm = new TemplateMatcher(templ, new Ellipse2D.Double(0, 0, 31, 31));
			Rectangle rect = new Rectangle(w / 2 - 40, h / 2 - 40, 81, 81);
			for (int i = 0; i < 30; i++)
				tm.getMatchLocation(img, new Rectangle(rect), null, 0, 0, 0);
			int calls = 0;
			long t0 = System.nanoTime();
			while (System.nanoTime() - t0 < 1_500_000_000L) {
				tm.getMatchLocation(img, new Rectangle(rect), null, 0, 0, 0);
				calls++;
			}
			System.out.printf("%s\t%-14s 1920x1080 %s\tus_per_match=%.1f%n", label, c[0],
					img.getType() == BufferedImage.TYPE_INT_RGB ? "INT_RGB  " : "3BYTE_BGR", (System.nanoTime() - t0) / 1000.0 / calls);
		}
	}

	static BufferedImage toImage(int[] px) {
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		System.arraycopy(px, 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, px.length);
		return img;
	}

	/** Smooth background plus a blurred three-colour ball, sampled at pixel centres. */
	static class Scene {
		final double[][] bg = new double[3][W * H];
		final double rad;

		Scene(Random r, double rad) {
			this.rad = rad;
			for (int c = 0; c < 3; c++)
				java.util.Arrays.fill(bg[c], 110 + 10 * c);
			for (int k = 0; k < 250; k++) {
				double bx = r.nextDouble() * W, by = r.nextDouble() * H, s = 3 + r.nextDouble() * 12;
				double[] col = { r.nextDouble() * 120 - 60, r.nextDouble() * 120 - 60, r.nextDouble() * 120 - 60 };
				int x0 = Math.max(0, (int) (bx - 3 * s)), x1 = Math.min(W, (int) (bx + 3 * s));
				int y0 = Math.max(0, (int) (by - 3 * s)), y1 = Math.min(H, (int) (by + 3 * s));
				for (int y = y0; y < y1; y++)
					for (int x = x0; x < x1; x++) {
						double g = Math.exp(-((x - bx) * (x - bx) + (y - by) * (y - by)) / (2 * s * s));
						for (int c = 0; c < 3; c++)
							bg[c][y * W + x] += col[c] * g;
					}
			}
		}

		/** Coverage of a disc with a Gaussian-blurred edge (sigma 0.8 px). */
		static double disc(double dx, double dy, double rr) {
			double d = Math.sqrt(dx * dx + dy * dy) - rr;
			return 0.5 * erfc(d / (0.8 * Math.sqrt(2)));
		}

		int[] render(double cx, double cy, double noise, Random r) {
			int[] out = new int[W * H];
			double[][] cols = { { 230, 60, 40 }, { 250, 230, 60 }, { 30, 30, 160 } };
			for (int y = 0; y < H; y++)
				for (int x = 0; x < W; x++) {
					int i = y * W + x;
					double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
					double[] v = { bg[0][i], bg[1][i], bg[2][i] };
					if (dx * dx + dy * dy < (rad + 4) * (rad + 4)) {
						double a = disc(dx, dy, rad);
						double b = disc(dx + rad * 0.2, dy + rad * 0.5, rad * 0.3);
						double c = disc(dx - rad * 0.35, dy - rad * 0.35, rad * 0.25);
						for (int k = 0; k < 3; k++) {
							v[k] = v[k] * (1 - a) + cols[0][k] * a;
							v[k] = v[k] * (1 - b) + cols[1][k] * b;
							v[k] = v[k] * (1 - c) + cols[2][k] * c;
						}
					}
					int p = 0;
					for (int k = 0; k < 3; k++) {
						int q = (int) Math.round(v[k] + noise * r.nextGaussian());
						p = (p << 8) | Math.max(0, Math.min(255, q));
					}
					out[i] = p;
				}
			return out;
		}

		/** erfc, Numerical Recipes erfcc (fractional error < 1.2e-7). */
		static double erfc(double x) {
			double z = Math.abs(x), t = 1 / (1 + 0.5 * z);
			double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
					+ t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
							+ t * (-0.82215223 + t * 0.17087277)))))))));
			return x >= 0 ? ans : 2 - ans;
		}
	}
}

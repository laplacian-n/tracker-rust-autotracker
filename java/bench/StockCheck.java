import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Patched TemplateMatcher with Patch.ON = false against the stock class from the
 * original tracker.jar (own class loader), on synthetic frames with an evolving
 * template: every match location, peak height/width and match image must be equal.
 *
 * Usage: java -cp patched;lib\tracker.jar;bench\out StockCheck lib\tracker.jar [frames]
 */
public class StockCheck {
	public static void main(String[] a) throws Exception {
		int frames = a.length > 1 ? Integer.parseInt(a[1]) : 40;
		URLClassLoader stockLoader = new URLClassLoader(new URL[] { new File(a[0]).toURI().toURL() },
				ClassLoader.getPlatformClassLoader());
		Class<?> stockTM = stockLoader.loadClass("org.opensourcephysics.media.core.TemplateMatcher");
		Class<?> ourTM = Class.forName("org.opensourcephysics.media.core.TemplateMatcher");
		Class.forName("org.opensourcephysics.media.core.Patch").getField("ON").setBoolean(null, false);
		int w = 640, h = 480, tw = 61, th = 57;
		java.util.Random rnd = new java.util.Random(7);
		BufferedImage bg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				bg.setRGB(x, y, ((x * 3) & 0xff) << 16 | ((y * 5) & 0xff) << 8 | ((x + y) & 0xff));
		Object S = null, O = null;
		double px = 200, py = 150;
		int same = 0;
		for (int n = 0; n < frames; n++) {
			// a moving textured disc on a gradient, 3BYTE_BGR like decoded video
			double cx = 230 + 80 * Math.sin(n * 0.21), cy = 180 + 60 * Math.cos(n * 0.17);
			BufferedImage f = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
			Graphics2D g = f.createGraphics();
			g.drawImage(bg, 0, 0, null);
			g.setColor(new java.awt.Color(200, 40, 180));
			g.fill(new Ellipse2D.Double(cx - 25, cy - 25, 50, 50));
			g.setColor(new java.awt.Color(250, 200, 250));
			g.fill(new Ellipse2D.Double(cx - 10, cy - 18, 12, 9));
			g.dispose();
			for (int k = 0; k < 300; k++)
				f.setRGB(rnd.nextInt(w), rnd.nextInt(h), rnd.nextInt(0xffffff));
			if (n == 0) {
				Rectangle r = new Rectangle((int) cx - tw / 2, (int) cy - th / 2, tw, th);
				Shape mask = AffineTransform.getTranslateInstance(-r.x, -r.y)
						.createTransformedShape(new Ellipse2D.Double(r.x, r.y, tw, th));
				BufferedImage t = crop(f, r);
				S = stockTM.getConstructor(BufferedImage.class, Shape.class).newInstance(t, mask);
				O = ourTM.getConstructor(BufferedImage.class, Shape.class).newInstance(crop(f, r), mask);
				px = r.x;
				py = r.y;
				continue;
			}
			Rectangle rect = new Rectangle((int) px - 30, (int) py - 30, 60, 60);
			Object ps = call(stockTM, S, "getMatchLocation", f, new Rectangle(rect));
			Object po = call(ourTM, O, "getMatchLocation", f, new Rectangle(rect));
			double[] ws = (double[]) stockTM.getMethod("getMatchWidthAndHeight").invoke(S);
			double[] wo = (double[]) ourTM.getMethod("getMatchWidthAndHeight").invoke(O);
			String ss = ps == null ? "null" : ((java.awt.geom.Point2D) ps).getX() + "," + ((java.awt.geom.Point2D) ps).getY();
			String so = po == null ? "null" : ((java.awt.geom.Point2D) po).getX() + "," + ((java.awt.geom.Point2D) po).getY();
			boolean eq = ss.equals(so) && java.util.Arrays.equals(ws, wo) && java.util.Arrays.equals(
					px((BufferedImage) stockTM.getMethod("getMatchImage").invoke(S)),
					px((BufferedImage) ourTM.getMethod("getMatchImage").invoke(O)));
			if (eq)
				same++;
			else
				System.out.println("DIFF frame " + n + " stock=" + ss + " " + ws[1] + " ours=" + so + " " + wo[1]);
			if (ps == null)
				continue;
			java.awt.geom.Point2D p = (java.awt.geom.Point2D) ps;
			px = p.getX();
			py = p.getY();
			Rectangle r = new Rectangle((int) Math.round(px), (int) Math.round(py), tw, th);
			stockTM.getMethod("buildTemplate", BufferedImage.class, int.class, int.class).invoke(S, crop(f, r), 51, 13);
			ourTM.getMethod("buildTemplate", BufferedImage.class, int.class, int.class).invoke(O, crop(f, r), 51, 13);
			stockTM.getMethod("setTemplate", BufferedImage.class).invoke(S,
					stockTM.getMethod("getTemplate").invoke(S));
			ourTM.getMethod("setTemplate", BufferedImage.class).invoke(O, ourTM.getMethod("getTemplate").invoke(O));
		}
		System.out.println("frames=" + (frames - 1) + " identical to stock=" + same);
		System.exit(same == frames - 1 ? 0 : 1);
	}

	static Object call(Class<?> c, Object o, String m, BufferedImage f, Rectangle r) throws Exception {
		Method mm = c.getMethod(m, BufferedImage.class, Rectangle.class, int[][].class, double.class, double.class,
				double.class);
		return mm.invoke(o, f, r, null, 0.0, 0.0, 0.0);
	}

	static int[] px(BufferedImage b) {
		return b == null ? null : b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
	}

	static BufferedImage crop(BufferedImage src, Rectangle r) {
		BufferedImage b = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = b.createGraphics();
		g.drawImage(src, -r.x, -r.y, null);
		g.dispose();
		return b;
	}
}

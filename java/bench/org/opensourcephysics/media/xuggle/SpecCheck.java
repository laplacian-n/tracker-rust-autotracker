package org.opensourcephysics.media.xuggle;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

import org.opensourcephysics.media.core.TPoint;
import org.opensourcephysics.media.core.TemplateMatcher;

/**
 * Runs two TemplateMatchers in lockstep over a video with an evolving template
 * (like AutoTracker): A matches on the spot, B gets each frame speculated ahead
 * from the read-ahead decoder. Every result must be identical.
 *
 * Usage: java SpecCheck <video> <frames> <x> <y> <w> <h>   (template rectangle in frame 0)
 */
public class SpecCheck {
	public static void main(String[] a) throws Exception {
		XuggleVideo v = new XuggleVideo(a[0], null);
		int frames = Math.min(Integer.parseInt(a[1]), v.getFrameCount());
		Rectangle t = new Rectangle(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]),
				Integer.parseInt(a[5]));
		Method get = XuggleVideo.class.getDeclaredMethod("getImage", int.class);
		get.setAccessible(true);
		BufferedImage f0 = (BufferedImage) get.invoke(v, 0);
		Ellipse2D mask = new Ellipse2D.Double(t.x, t.y, t.width, t.height);
		Rectangle mb = mask.getBounds();
		java.awt.Shape local = AffineTransform.getTranslateInstance(-mb.x, -mb.y).createTransformedShape(mask);
		TemplateMatcher A = new TemplateMatcher(crop(f0, mb), local);
		TemplateMatcher B = new TemplateMatcher(crop(f0, mb), local);
		double px = t.x, py = t.y; // last match corner (template origin)
		int same = 0, diff = 0, nulls = 0;
		long tA = 0, tB = 0;
		for (int n = 1; n < frames; n++) {
			int half = Integer.getInteger("spec.half", 40);
			Rectangle rect = new Rectangle((int) Math.round(px) - half, (int) Math.round(py) - half, 2 * half, 2 * half);
			// B: speculate on the coming frame before "stepping" to it
			BufferedImage ahead = v.peekPrefetched(n);
			if (ahead != null)
				B.speculate(ahead, new Rectangle(rect));
			BufferedImage img = (BufferedImage) get.invoke(v, n);
			// identical template state for both: set from each one's own current template
			A.setTemplate(A.getTemplate());
			B.setTemplate(B.getTemplate());
			long s = System.nanoTime();
			TPoint pA = A.getMatchLocation(img, new Rectangle(rect), null, 0, 0, 0);
			long m = System.nanoTime();
			TPoint pB = B.getMatchLocation(img, new Rectangle(rect), null, 0, 0, 0);
			tB += System.nanoTime() - m;
			tA += m - s;
			double[] wa = A.getMatchWidthAndHeight(), wb = B.getMatchWidthAndHeight();
			boolean eq = (pA == null ? pB == null
					: pB != null && pA.x == pB.x && pA.y == pB.y) && Double.compare(wa[0], wb[0]) == 0
					&& Double.compare(wa[1], wb[1]) == 0
					&& java.util.Arrays.equals(px(A.getMatchImage()), px(B.getMatchImage()));
			if (eq)
				same++;
			else {
				diff++;
				System.out.println("DIFF frame " + n + " A=" + pA + " " + wa[1] + " B=" + pB + " " + wb[1]);
			}
			if (pA == null) {
				nulls++;
				continue;
			}
			px = pA.x;
			py = pA.y;
			// evolve both templates the same way AutoTracker does (20% new, 5% tether)
			Rectangle r = new Rectangle((int) Math.round(px), (int) Math.round(py), mb.width, mb.height);
			A.buildTemplate(crop(img, r), 51, 13);
			B.buildTemplate(crop(img, r), 51, 13);
		}
		System.out.println("frames=" + (frames - 1) + " identical=" + same + " different=" + diff + " noMatch=" + nulls
				+ String.format("  on-the-spot %.2f ms, with speculation %.2f ms per match", tA / 1e6 / (frames - 1),
						tB / 1e6 / (frames - 1))
				+ "  last=" + px + "," + py);
		for (String f : new String[] { "statSearch", "statRefine", "statCore" }) {
			java.lang.reflect.Field fl = TemplateMatcher.class.getDeclaredField(f);
			fl.setAccessible(true);
			System.out.printf("%s %.2f ms/match (both matchers, incl. speculation)%n", f, fl.getLong(null) / 1e6 / (2 * (frames - 1)));
		}
		v.dispose();
		System.exit(diff == 0 ? 0 : 1);
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

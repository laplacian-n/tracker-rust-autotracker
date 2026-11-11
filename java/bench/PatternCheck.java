import org.opensourcephysics.media.core.NativeMatcher;
import org.opensourcephysics.media.core.PatternGeometry;

/**
 * Renders a 9x6 (inner corners) board with 20 mm squares through a known camera
 * (1080x1920, f = 1500 px) and checks what PatternGeometry makes of the corners
 * the native detector finds: tilt, focal length, true scale at the centre, and
 * that the Perspective-filter corners map the board onto an exact square grid.
 *
 *   java -cp bench\out;..\tracker-native\tracker-native.jar PatternCheck
 */
public class PatternCheck {

	static final int W = 1080, H = 1920;
	static final double F = 1500, SQ = 20;

	public static void main(String[] args) {
		NativeMatcher.mode += 0;
		System.out.printf("%5s %5s %5s | %6s %6s %7s %7s | %8s %8s %6s | %8s %8s %8s%n", "tilt", "yaw", "sq px", "found",
				"tilt", "f", "f true", "px/mm", "true", "vary%", "1-scale", "rect mm", "fit px");
		double[][] cases = { { 0, 0, 30 }, { 5, 0, 30 }, { 10, 20, 25 }, { 20, 0, 25 }, { 30, 15, 30 }, { 45, 0, 20 },
				{ 15, -30, 15 } };
		java.util.Random rnd = new java.util.Random(7);
		for (double[] c : cases) {
			double[][] cam = camera(c[0], c[1], 9.0, c[2]);
			int[] rgb = render(cam, rnd);
			double[] out = new double[4 * 4000];
			int n = NativeMatcher.findCheckerboard(rgb, W, H, out);
			PatternGeometry g = PatternGeometry.fromCorners(out, n, W, H, SQ);
			if (g == null) {
				System.out.printf("%5.0f %5.0f %5.0f | none%n", c[0], c[1], c[2]);
				continue;
			}
			// true local scale at the board centre (sqrt |det J|) by finite differences
			double[] p0 = project(cam, 5 * SQ, 3.5 * SQ), px = project(cam, 5 * SQ + 0.01, 3.5 * SQ),
					py = project(cam, 5 * SQ, 3.5 * SQ + 0.01);
			double j00 = (px[0] - p0[0]) / 0.01, j10 = (px[1] - p0[1]) / 0.01, j01 = (py[0] - p0[0]) / 0.01,
					j11 = (py[1] - p0[1]) / 0.01;
			double trueScale = Math.sqrt(Math.abs(j00 * j11 - j01 * j10));
			// perspective correction check: board corners through in->out quad mapping land on a square grid
			double[] q = quadToQuad(g.inQuad, g.outQuad);
			double maxErr = 0;
			for (int k = 0; k < g.count; k++) {
				double[] o = PatternGeometry.apply(q, g.ix[k], g.iy[k]);
				double ex = (o[0] + 0.5 - g.rectOriginX) / g.rectScale - g.wx[k];
				double ey = -(o[1] + 0.5 - g.rectOriginY) / g.rectScale - g.wy[k];
				maxErr = Math.max(maxErr, Math.hypot(ex, ey));
			}
			System.out.printf("%5.0f %5.0f %5.0f | %3d %dx%d %5.1f %7.0f%s %7.0f | %8.4f %8.4f %6.1f | %8.2f %8.3f %8.3f%n", c[0],
					c[1], c[2], g.count, g.cols, g.rows, g.tiltDeg, g.focal, g.focalMeasured ? "" : "?", F,
					g.scaleCentre, trueScale, 100 * (g.scaleMax - g.scaleMin) / g.scaleCentre, g.simMaxWorld, maxErr,
					g.homographyRms);
		}
	}

	/** {rotation rows..., t} for a board tilted about its x axis, then turned (yaw) and rolled */
	static double[][] camera(double tiltDeg, double yawDeg, double rollDeg, double sqPx) {
		double t = Math.toRadians(tiltDeg), y = Math.toRadians(yawDeg), r = Math.toRadians(rollDeg);
		double[][] rz = { { Math.cos(r), -Math.sin(r), 0 }, { Math.sin(r), Math.cos(r), 0 }, { 0, 0, 1 } };
		double[][] rx = { { 1, 0, 0 }, { 0, Math.cos(t), -Math.sin(t) }, { 0, Math.sin(t), Math.cos(t) } };
		double[][] ry = { { Math.cos(y), 0, Math.sin(y) }, { 0, 1, 0 }, { -Math.sin(y), 0, Math.cos(y) } };
		double[][] rot = mul(ry, mul(rx, rz));
		double z = F * SQ / sqPx;
		double bx = 5 * SQ, by = 3.5 * SQ; // board centre (10 x 7 squares) on the optical axis
		double[] tv = { -(rot[0][0] * bx + rot[0][1] * by), -(rot[1][0] * bx + rot[1][1] * by),
				z - (rot[2][0] * bx + rot[2][1] * by) };
		return new double[][] { rot[0], rot[1], rot[2], tv };
	}

	/** board point (mm, board corner at 0,0) to pixel-centre image coordinates */
	static double[] project(double[][] cam, double u, double v) {
		double x = cam[0][0] * u + cam[0][1] * v + cam[3][0];
		double y = cam[1][0] * u + cam[1][1] * v + cam[3][1];
		double z = cam[2][0] * u + cam[2][1] * v + cam[3][2];
		return new double[] { F * x / z + (W - 1) / 2.0, F * y / z + (H - 1) / 2.0 };
	}

	/** inner-corner board coordinates are offset by one square from the board corner */
	static int[] render(double[][] cam, java.util.Random rnd) {
		// inverse homography image -> board via 3x3 of [r1 r2 t] with K
		double[] h = { F * cam[0][0] + (W - 1) / 2.0 * cam[2][0], F * cam[0][1] + (W - 1) / 2.0 * cam[2][1],
				F * cam[3][0] + (W - 1) / 2.0 * cam[3][2], F * cam[1][0] + (H - 1) / 2.0 * cam[2][0],
				F * cam[1][1] + (H - 1) / 2.0 * cam[2][1], F * cam[3][1] + (H - 1) / 2.0 * cam[3][2], cam[2][0],
				cam[2][1], cam[3][2] };
		double[] hi = inv(h);
		int[] rgb = new int[W * H];
		for (int y = 0; y < H; y++)
			for (int x = 0; x < W; x++) {
				double acc = 0;
				for (int s = 0; s < 9; s++) {
					double sx = x + ((s % 3) + 0.5) / 3 - 0.5, sy = y + ((s / 3) + 0.5) / 3 - 0.5;
					double[] b = PatternGeometry.apply(hi, sx, sy);
					double i = Math.floor(b[0] / SQ), j = Math.floor(b[1] / SQ);
					double val;
					if (i >= 0 && i < 10 && j >= 0 && j < 7)
						val = ((int) (i + j)) % 2 == 0 ? 30 : 220;
					else if (i >= -1 && i < 11 && j >= -1 && j < 8)
						val = 230;
					else
						val = 100 + 50 * Math.sin(sx / 37.0) * Math.cos(sy / 53.0);
					acc += val;
				}
				int v = (int) Math.max(0, Math.min(255, Math.round(acc / 9 + 3 * rnd.nextGaussian())));
				rgb[y * W + x] = (v << 16) | (v << 8) | v;
			}
		return rgb;
	}

	static double[] quadToQuad(double[][] in, double[][] out) {
		return mul9(squareToQuad(out), inv(squareToQuad(in)));
	}

	static double[] squareToQuad(double[][] q) {
		double x0 = q[0][0], y0 = q[0][1], x1 = q[1][0], y1 = q[1][1], x2 = q[2][0], y2 = q[2][1], x3 = q[3][0],
				y3 = q[3][1];
		double dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
		double dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
		double det = dx1 * dy2 - dx2 * dy1;
		double g = (dx3 * dy2 - dx2 * dy3) / det, h = (dx1 * dy3 - dx3 * dy1) / det;
		return new double[] { x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h, 1 };
	}

	static double[][] mul(double[][] a, double[][] b) {
		double[][] r = new double[3][3];
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				for (int k = 0; k < 3; k++)
					r[i][j] += a[i][k] * b[k][j];
		return r;
	}

	static double[] mul9(double[] a, double[] b) {
		double[] r = new double[9];
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				for (int k = 0; k < 3; k++)
					r[3 * i + j] += a[3 * i + k] * b[3 * k + j];
		return r;
	}

	static double[] inv(double[] m) {
		double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
		double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
		return new double[] { (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, (f * g - d * i) / det,
				(a * i - c * g) / det, (c * d - a * f) / det, (d * h - e * g) / det, (b * g - a * h) / det,
				(a * e - b * d) / det };
	}
}

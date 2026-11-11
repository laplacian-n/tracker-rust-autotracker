package org.opensourcephysics.media.core;

/**
 * patched (new): geometry of a printed checkerboard found in a video frame.
 *
 * From the board's inner corners (grid index and image position) it works out
 * <ul>
 * <li>the plane-to-image homography H (world units on the board -> pixels),</li>
 * <li>the camera focal length (from H, principal point at the image centre) and
 * so how far the board is tilted from square-on to the camera,</li>
 * <li>the true scale (pixels per unit) at the board centre along each board axis
 * and how much it changes across the board,</li>
 * <li>the best single scale/angle/origin (Tracker's coordinate system without
 * perspective) and the largest error that leaves on the board,</li>
 * <li>input/output corners for Tracker's Perspective filter that turn the board
 * square-on, and the exact scale/origin in that corrected image.</li>
 * </ul>
 * Image positions are pixel centres at integer coordinates (as the detector and
 * the Perspective filter use them); Tracker image coordinates are +0.5.
 */
public final class PatternGeometry {

	/** corners found; board size in inner corners along world x and y */
	public int count, cols, rows;
	public double square;
	public int imageW, imageH;

	/** world (board) and image positions of each corner */
	public double[] wx, wy, ix, iy;

	/** homography world -> image, row-major 3x3, and its RMS residual (px) */
	public double[] H = new double[9];
	public double homographyRms;

	/** focal length (px) and whether it was measured (false: assumed) */
	public double focal;
	public boolean focalMeasured;
	/** tilt of the board from square-on (degrees), and which board side is farther */
	public double tiltDeg;
	public String fartherSide = "";
	/** camera to board-centre distance in world units (meaningful when focalMeasured) */
	public double distance;

	/** true scale at the board centre (px per unit) along board x and y, and its area mean */
	public double scaleCentreX, scaleCentreY, scaleCentre;
	/** smallest and largest true scale (area mean) over the board's corners */
	public double scaleMin, scaleMax;

	/** best single scale (px/unit), angle (rad, Tracker's sense) and origin (Tracker image coordinates) */
	public double simScale, simAngle, simOriginX, simOriginY;
	/** residuals of that fit: RMS and max (px), and max in world units */
	public double simRmsPx, simMaxPx, simMaxWorld;

	/** perspective correction: in/out corners (TL, TR, BR, BL), resulting scale and origin */
	public double[][] inQuad = new double[4][2], outQuad = new double[4][2];
	public double rectScale, rectOriginX, rectOriginY;

	private PatternGeometry() {
	}

	/**
	 * @param det    {i, j, x, y} per corner, as NativeMatcher.findCheckerboard writes it
	 * @param n      number of corners
	 * @param square side of one square in world units
	 */
	public static PatternGeometry fromCorners(double[] det, int n, int imageW, int imageH, double square) {
		if (n < 9)
			return null;
		PatternGeometry g = new PatternGeometry();
		g.count = n;
		g.square = square;
		g.imageW = imageW;
		g.imageH = imageH;
		g.orient(det, n);
		g.fitHomography();
		g.cameraPose();
		g.localScales();
		g.fitSimilarity();
		g.rectification();
		return g;
	}

	// ---------------------------------------------------------------- orientation

	/** World x runs to the right in the image, world y up; origin at the bottom-left corner. */
	private void orient(double[] det, int n) {
		int imin = Integer.MAX_VALUE, jmin = Integer.MAX_VALUE, imax = Integer.MIN_VALUE, jmax = Integer.MIN_VALUE;
		for (int k = 0; k < n; k++) {
			imin = Math.min(imin, (int) det[4 * k]);
			imax = Math.max(imax, (int) det[4 * k]);
			jmin = Math.min(jmin, (int) det[4 * k + 1]);
			jmax = Math.max(jmax, (int) det[4 * k + 1]);
		}
		int ni = imax - imin + 1, nj = jmax - jmin + 1;
		double[][] px = new double[ni][nj], py = new double[ni][nj];
		boolean[][] has = new boolean[ni][nj];
		for (int k = 0; k < n; k++) {
			int i = (int) det[4 * k] - imin, j = (int) det[4 * k + 1] - jmin;
			px[i][j] = det[4 * k + 2];
			py[i][j] = det[4 * k + 3];
			has[i][j] = true;
		}
		// mean image step along i and along j (y flipped: up is positive)
		double eix = 0, eiy = 0, ejx = 0, ejy = 0;
		for (int i = 0; i < ni; i++)
			for (int j = 0; j < nj; j++) {
				if (!has[i][j])
					continue;
				if (i + 1 < ni && has[i + 1][j]) {
					eix += px[i + 1][j] - px[i][j];
					eiy -= py[i + 1][j] - py[i][j];
				}
				if (j + 1 < nj && has[i][j + 1]) {
					ejx += px[i][j + 1] - px[i][j];
					ejy -= py[i][j + 1] - py[i][j];
				}
			}
		// world x: the grid direction (with sign) pointing most to the right
		boolean xAlongI = Math.abs(eix) / Math.hypot(eix, eiy) >= Math.abs(ejx) / Math.hypot(ejx, ejy);
		int sx = xAlongI ? (eix >= 0 ? 1 : -1) : (ejx >= 0 ? 1 : -1);
		double xx = xAlongI ? sx * eix : sx * ejx, xy = xAlongI ? sx * eiy : sx * ejy;
		double yx = xAlongI ? ejx : eix, yy = xAlongI ? ejy : eiy;
		// world y: the other direction, signed so that x, y turn counter-clockwise (y up)
		int sy = xx * yy - xy * yx >= 0 ? 1 : -1;
		cols = xAlongI ? ni : nj;
		rows = xAlongI ? nj : ni;
		wx = new double[n];
		wy = new double[n];
		ix = new double[n];
		iy = new double[n];
		int k = 0;
		for (int i = 0; i < ni; i++)
			for (int j = 0; j < nj; j++) {
				if (!has[i][j])
					continue;
				int a = xAlongI ? i : j, b = xAlongI ? j : i;
				if (sx < 0)
					a = cols - 1 - a;
				if (sy < 0)
					b = rows - 1 - b;
				wx[k] = a * square;
				wy[k] = b * square;
				ix[k] = px[i][j];
				iy[k] = py[i][j];
				k++;
			}
		count = k;
	}

	// ---------------------------------------------------------------- homography

	private void fitHomography() {
		int n = count;
		// Hartley normalisation
		double[] tw = normalisation(wx, wy), ti = normalisation(ix, iy);
		double[][] ata = new double[9][9];
		double[] r = new double[9];
		for (int k = 0; k < n; k++) {
			double u = (wx[k] - tw[0]) * tw[2], v = (wy[k] - tw[1]) * tw[2];
			double x = (ix[k] - ti[0]) * ti[2], y = (iy[k] - ti[1]) * ti[2];
			double[][] rows2 = { { u, v, 1, 0, 0, 0, -x * u, -x * v, -x }, { 0, 0, 0, u, v, 1, -y * u, -y * v, -y } };
			for (double[] row : rows2)
				for (int a = 0; a < 9; a++)
					for (int b = 0; b < 9; b++)
						ata[a][b] += row[a] * row[b];
		}
		double[] h = smallestEigenvector(ata);
		// undo normalisation: H = Ti^-1 * Hn * Tw
		double[] tW = { tw[2], 0, -tw[2] * tw[0], 0, tw[2], -tw[2] * tw[1], 0, 0, 1 };
		double[] tIinv = { 1 / ti[2], 0, ti[0], 0, 1 / ti[2], ti[1], 0, 0, 1 };
		double[] m = mul(tIinv, mul(h, tW));
		double s = m[8];
		for (int a = 0; a < 9; a++)
			H[a] = m[a] / s;
		double se = 0;
		for (int k = 0; k < n; k++) {
			double[] p = apply(H, wx[k], wy[k]);
			se += sq(p[0] - ix[k]) + sq(p[1] - iy[k]);
		}
		homographyRms = Math.sqrt(se / n);
	}

	private static double[] normalisation(double[] x, double[] y) {
		double mx = 0, my = 0;
		for (int k = 0; k < x.length; k++) {
			mx += x[k];
			my += y[k];
		}
		mx /= x.length;
		my /= x.length;
		double d = 0;
		for (int k = 0; k < x.length; k++)
			d += Math.hypot(x[k] - mx, y[k] - my);
		d /= x.length;
		return new double[] { mx, my, d > 0 ? Math.sqrt(2) / d : 1 };
	}

	// ---------------------------------------------------------------- camera

	private void cameraPose() {
		double cx = (imageW - 1) / 2.0, cy = (imageH - 1) / 2.0;
		// homography with the principal point at the origin
		double[] hc = { H[0] - cx * H[6], H[1] - cx * H[7], H[2] - cx * H[8], H[3] - cy * H[6], H[4] - cy * H[7],
				H[5] - cy * H[8], H[6], H[7], H[8] };
		double a1 = hc[0], a2 = hc[1], b1 = hc[3], b2 = hc[4], c1 = hc[6], c2 = hc[7];
		// r1.r2 = 0 and |r1| = |r2| with K = diag(f, f, 1): two equations in x = 1/f^2
		double p1 = a1 * a2 + b1 * b2, q1 = c1 * c2;
		double p2 = a1 * a1 + b1 * b1 - a2 * a2 - b2 * b2, q2 = c1 * c1 - c2 * c2;
		double x = -(p1 * q1 + p2 * q2) / (p1 * p1 + p2 * p2);
		double big = Math.max(imageW, imageH);
		double f = x > 0 ? 1 / Math.sqrt(x) : Double.NaN;
		focalMeasured = f > 0.3 * big && f < 5 * big;
		focal = focalMeasured ? f : 0.8 * big; // typical phone / camcorder field of view
		f = focal;
		double[] r1 = { a1 / f, b1 / f, c1 }, r2 = { a2 / f, b2 / f, c2 }, t = { hc[2] / f, hc[5] / f, hc[8] };
		double lambda = 2 / (norm(r1) + norm(r2));
		if (t[2] * lambda < 0)
			lambda = -lambda;
		for (int a = 0; a < 3; a++) {
			r1[a] *= lambda;
			r2[a] *= lambda;
			t[a] *= lambda;
		}
		double[] r3 = cross(r1, r2);
		double n3 = norm(r3);
		tiltDeg = Math.toDegrees(Math.acos(Math.min(1, Math.abs(r3[2] / n3))));
		// depth of the board's four sides
		double w = (cols - 1) * square, hgt = (rows - 1) * square;
		double[][] sides = { { 0, hgt / 2 }, { w, hgt / 2 }, { w / 2, 0 }, { w / 2, hgt } };
		double[] z = new double[4];
		for (int s = 0; s < 4; s++)
			z[s] = r1[2] * sides[s][0] + r2[2] * sides[s][1] + t[2];
		int far = 0;
		double best = 0;
		for (int s = 0; s < 4; s += 2) {
			double dz = z[s + 1] - z[s];
			if (Math.abs(dz) > Math.abs(best)) {
				best = dz;
				far = dz > 0 ? s + 1 : s;
			}
		}
		// name the side by where it lies in the image
		double[] pc = apply(H, sides[far][0], sides[far][1]);
		double[] mid = apply(H, w / 2, hgt / 2);
		double dx = pc[0] - mid[0], dy = pc[1] - mid[1];
		fartherSide = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "bottom" : "top");
		double[] c = { r1[0] * w / 2 + r2[0] * hgt / 2 + t[0], r1[1] * w / 2 + r2[1] * hgt / 2 + t[1],
				r1[2] * w / 2 + r2[2] * hgt / 2 + t[2] };
		distance = norm(c);
	}

	// ---------------------------------------------------------------- scale

	/** local scale (px per unit) of H at a board point: {along x, along y, sqrt|det|} */
	private double[] localScale(double u, double v) {
		double w = H[6] * u + H[7] * v + H[8];
		double x = (H[0] * u + H[1] * v + H[2]) / w, y = (H[3] * u + H[4] * v + H[5]) / w;
		double j00 = (H[0] - x * H[6]) / w, j01 = (H[1] - x * H[7]) / w;
		double j10 = (H[3] - y * H[6]) / w, j11 = (H[4] - y * H[7]) / w;
		return new double[] { Math.hypot(j00, j10), Math.hypot(j01, j11), Math.sqrt(Math.abs(j00 * j11 - j01 * j10)) };
	}

	private void localScales() {
		double w = (cols - 1) * square, h = (rows - 1) * square;
		double[] c = localScale(w / 2, h / 2);
		scaleCentreX = c[0];
		scaleCentreY = c[1];
		scaleCentre = c[2];
		scaleMin = Double.MAX_VALUE;
		scaleMax = 0;
		for (double[] p : new double[][] { { 0, 0 }, { w, 0 }, { 0, h }, { w, h } }) {
			double s = localScale(p[0], p[1])[2];
			scaleMin = Math.min(scaleMin, s);
			scaleMax = Math.max(scaleMax, s);
		}
	}

	// ---------------------------------------------------------------- Tracker coordinates without perspective

	/**
	 * Tracker maps world to image as img = origin + s [cos -sin; -sin -cos] w, i.e.
	 * with y flipped, a rotation by the angle and a uniform scale.
	 */
	private void fitSimilarity() {
		int n = count;
		double mwx = 0, mwy = 0, mqx = 0, mqy = 0;
		for (int k = 0; k < n; k++) {
			mwx += wx[k];
			mwy += wy[k];
			mqx += ix[k];
			mqy -= iy[k];
		}
		mwx /= n;
		mwy /= n;
		mqx /= n;
		mqy /= n;
		double a = 0, b = 0, ww = 0;
		for (int k = 0; k < n; k++) {
			double u = wx[k] - mwx, v = wy[k] - mwy, qx = ix[k] - mqx, qy = -iy[k] - mqy;
			a += u * qx + v * qy;
			b += u * qy - v * qx;
			ww += u * u + v * v;
		}
		simAngle = Math.atan2(b, a);
		simScale = Math.hypot(a, b) / ww;
		double cos = Math.cos(simAngle), sin = Math.sin(simAngle);
		double tx = mqx - simScale * (cos * mwx - sin * mwy);
		double ty = mqy - simScale * (sin * mwx + cos * mwy);
		simOriginX = tx + 0.5;
		simOriginY = -ty + 0.5;
		double se = 0, mx = 0;
		for (int k = 0; k < n; k++) {
			double px = tx + simScale * (cos * wx[k] - sin * wy[k]);
			double py = -(ty + simScale * (sin * wx[k] + cos * wy[k]));
			double d = Math.hypot(px - ix[k], py - iy[k]);
			se += d * d;
			mx = Math.max(mx, d);
		}
		simRmsPx = Math.sqrt(se / n);
		simMaxPx = mx;
		simMaxWorld = mx / simScale;
	}

	// ---------------------------------------------------------------- perspective correction

	private void rectification() {
		double w = (cols - 1) * square, h = (rows - 1) * square;
		double[][] world = { { 0, h }, { w, h }, { w, 0 }, { 0, 0 } }; // TL, TR, BR, BL in the output
		double[] centre = apply(H, w / 2, h / 2);
		rectScale = scaleCentre;
		for (int k = 0; k < 4; k++) {
			double[] p = apply(H, world[k][0], world[k][1]);
			inQuad[k][0] = p[0];
			inQuad[k][1] = p[1];
			outQuad[k][0] = centre[0] + rectScale * (world[k][0] - w / 2);
			outQuad[k][1] = centre[1] - rectScale * (world[k][1] - h / 2);
		}
		rectOriginX = outQuad[3][0] + 0.5;
		rectOriginY = outQuad[3][1] + 0.5;
	}

	// ---------------------------------------------------------------- small linear algebra

	public static double[] apply(double[] m, double u, double v) {
		double w = m[6] * u + m[7] * v + m[8];
		return new double[] { (m[0] * u + m[1] * v + m[2]) / w, (m[3] * u + m[4] * v + m[5]) / w };
	}

	private static double[] mul(double[] a, double[] b) {
		double[] r = new double[9];
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				for (int k = 0; k < 3; k++)
					r[3 * i + j] += a[3 * i + k] * b[3 * k + j];
		return r;
	}

	private static double sq(double x) {
		return x * x;
	}

	private static double norm(double[] v) {
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
	}

	/** eigenvector of the smallest eigenvalue of a symmetric matrix (Jacobi rotations) */
	private static double[] smallestEigenvector(double[][] m) {
		int n = m.length;
		double[][] a = new double[n][];
		for (int i = 0; i < n; i++)
			a[i] = m[i].clone();
		double[][] v = new double[n][n];
		for (int i = 0; i < n; i++)
			v[i][i] = 1;
		for (int sweep = 0; sweep < 100; sweep++) {
			double off = 0;
			for (int p = 0; p < n; p++)
				for (int q = p + 1; q < n; q++)
					off += a[p][q] * a[p][q];
			if (off < 1e-30)
				break;
			for (int p = 0; p < n; p++)
				for (int q = p + 1; q < n; q++) {
					if (Math.abs(a[p][q]) < 1e-300)
						continue;
					double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
					double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
					if (theta == 0)
						t = 1;
					double c = 1 / Math.sqrt(t * t + 1), s = t * c;
					for (int k = 0; k < n; k++) {
						double akp = a[k][p], akq = a[k][q];
						a[k][p] = c * akp - s * akq;
						a[k][q] = s * akp + c * akq;
					}
					for (int k = 0; k < n; k++) {
						double apk = a[p][k], aqk = a[q][k];
						a[p][k] = c * apk - s * aqk;
						a[q][k] = s * apk + c * aqk;
					}
					for (int k = 0; k < n; k++) {
						double vkp = v[k][p], vkq = v[k][q];
						v[k][p] = c * vkp - s * vkq;
						v[k][q] = s * vkp + c * vkq;
					}
				}
		}
		int best = 0;
		for (int i = 1; i < n; i++)
			if (a[i][i] < a[best][best])
				best = i;
		double[] r = new double[n];
		for (int i = 0; i < n; i++)
			r[i] = v[i][best];
		return r;
	}
}

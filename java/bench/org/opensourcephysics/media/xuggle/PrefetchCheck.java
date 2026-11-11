package org.opensourcephysics.media.xuggle;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.zip.CRC32;

/**
 * Prints a CRC per requested frame of a video read through the patched
 * XuggleVideo, plus timing. Run once with -Dtracker.xuggle.prefetch=false and
 * once without; the CRC lines must be identical.
 *
 * Usage: java PrefetchCheck <video> [frames]
 * Access pattern: sequential 0..n-1, then a jump back, steps of 2, a backward
 * walk, and a second sequential run from the middle.
 */
public class PrefetchCheck {
	public static void main(String[] a) throws Exception {
		XuggleVideo v = new XuggleVideo(a[0], null);
		int n = Math.min(a.length > 1 ? Integer.parseInt(a[1]) : 300, v.getFrameCount());
		Method get = XuggleVideo.class.getDeclaredMethod("getImage", int.class);
		get.setAccessible(true);
		java.util.List<Integer> order = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++)
			order.add(i);
		for (int i = n / 3; i < n; i += 2)
			order.add(i);
		for (int i = n / 2; i > n / 2 - 20 && i >= 0; i--)
			order.add(i);
		for (int i = n / 4; i < n / 4 + 60 && i < n; i++)
			order.add(i);
		order.add(v.getFrameCount() - 1);
		order.add(v.getFrameCount() - 2);
		long t0 = System.nanoTime(), tSeq = 0;
		for (int k = 0; k < order.size(); k++) {
			int f = order.get(k);
			// exactly what Tracker uses: set the frame, then VideoAdapter.getImage()
			v.setFrameNumber(f);
			BufferedImage bi = v.getImage();
			if (k == n - 1)
				tSeq = System.nanoTime() - t0;
			CRC32 crc = new CRC32();
			if (bi != null) {
				int[] px = ((java.awt.image.DataBufferInt) bi.getRaster().getDataBuffer()).getData();
				java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(px.length * 4);
				bb.asIntBuffer().put(px);
				crc.update(bb.array());
			}
			System.out.println("frame " + f + " " + (bi == null ? "null" : Long.toHexString(crc.getValue())));
		}
		System.err.printf("frames=%d sequential: %.2f ms/frame, all %d requests: %.1f ms%n", n, tSeq / 1e6 / n,
				order.size(), (System.nanoTime() - t0) / 1e6);
		v.dispose();
		System.exit(0);
	}
}

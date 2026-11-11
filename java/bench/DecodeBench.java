import com.xuggle.xuggler.*;
import com.xuggle.xuggler.video.*;
import java.awt.image.BufferedImage;

/**
 * Times sequential Xuggle decoding of a video the way XuggleVideo does it
 * (decode, resample to BGR24, convert to BufferedImage).
 * Usage: java DecodeBench <video> <threads|-> [frames]
 *   threads "-" leaves the decoder as Tracker opens it.
 */
public class DecodeBench {
	public static void main(String[] a) throws Exception {
		String path = a[0];
		int max = a.length > 2 ? Integer.parseInt(a[2]) : 400;
		IContainer c = IContainer.make();
		if (c.open(path, IContainer.Type.READ, null) < 0) throw new RuntimeException("open " + path);
		int si = -1;
		IStreamCoder dec = null;
		for (int i = 0; i < c.getNumStreams(); i++) {
			IStreamCoder sc = c.getStream(i).getStreamCoder();
			if (sc.getCodecType() == ICodec.Type.CODEC_TYPE_VIDEO) { si = i; dec = sc; break; }
		}
		if (!a[1].equals("-")) {
			System.out.println("set threads=" + a[1] + " -> " + dec.setProperty("threads", a[1]));
		}
		if (System.getProperty("tt") != null) System.out.println("thread_type -> " + dec.setProperty("thread_type", System.getProperty("tt")));
		if (dec.open() < 0) throw new RuntimeException("decoder open");
		System.out.println("codec=" + dec.getCodec().getName() + " " + dec.getWidth() + "x" + dec.getHeight()
				+ " fps=" + dec.getFrameRate() + " threads=" + dec.getPropertyAsString("threads")
				+ " thread_type=" + dec.getPropertyAsString("thread_type"));
		IVideoPicture pic = IVideoPicture.make(dec.getPixelType(), dec.getWidth(), dec.getHeight());
		IVideoResampler rs = IVideoResampler.make(dec.getWidth(), dec.getHeight(), IPixelFormat.Type.BGR24,
				dec.getWidth(), dec.getHeight(), dec.getPixelType());
		IVideoPicture out = IVideoPicture.make(IPixelFormat.Type.BGR24, dec.getWidth(), dec.getHeight());
		IConverter conv = null;
		IPacket pk = IPacket.make();
		long tDec = 0, tRs = 0, tImg = 0, t0 = System.nanoTime();
		int frames = 0;
		long sink = 0;
		while (frames < max && c.readNextPacket(pk) >= 0) {
			if (pk.getStreamIndex() != si) continue;
			int off = 0;
			while (off < pk.getSize()) {
				long s = System.nanoTime();
				int n = dec.decodeVideo(pic, pk, off);
				tDec += System.nanoTime() - s;
				if (n < 0) break;
				off += n;
				if (pic.isComplete()) {
					s = System.nanoTime();
					rs.resample(out, pic);
					tRs += System.nanoTime() - s;
					s = System.nanoTime();
					if (conv == null) conv = ConverterFactory.createConverter(
							ConverterFactory.findRegisteredConverter("XUGGLER-BGR-24").getDescriptor(), out);
					BufferedImage bi = conv.toImage(out);
					sink += bi.getRGB(10, 10);
					tImg += System.nanoTime() - s;
					frames++;
				}
			}
		}
		double ms = (System.nanoTime() - t0) / 1e6;
		System.out.printf("frames=%d total=%.1f ms  per frame: all=%.2f decode=%.2f resample=%.2f toImage=%.2f ms (%d)%n",
				frames, ms, ms / frames, tDec / 1e6 / frames, tRs / 1e6 / frames, tImg / 1e6 / frames, sink & 1);
	}
}

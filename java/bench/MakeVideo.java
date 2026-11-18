import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.xuggle.mediatool.IMediaWriter;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.xuggler.ICodec;

/**
 * Writes a short test video with Xuggle (textured background, a dark disk moving
 * on a circle) for decoder checks without anyone's own footage:
 *
 *   java -cp ...xuggle... MakeVideo out.mp4 [frames]
 */
public class MakeVideo {
	public static void main(String[] args) {
		String path = args[0];
		int frames = args.length > 1 ? Integer.parseInt(args[1]) : 120;
		int w = 640, h = 480;
		BufferedImage bg = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
		Random rnd = new Random(1);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int v = 150 + (int) (40 * Math.sin(x / 23.0) * Math.cos(y / 31.0)) + rnd.nextInt(20);
				bg.setRGB(x, y, (v << 16) | ((v - 10) << 8) | (v - 30));
			}
		IMediaWriter writer = ToolFactory.makeWriter(path);
		writer.addVideoStream(0, 0, ICodec.ID.AV_CODEC_ID_MPEG4, w, h);
		for (int i = 0; i < frames; i++) {
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
			Graphics2D g = img.createGraphics();
			g.drawImage(bg, 0, 0, null);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			double a = 2 * Math.PI * i / frames;
			int cx = (int) (w / 2 + 180 * Math.cos(a)), cy = (int) (h / 2 + 140 * Math.sin(a));
			g.setColor(new Color(30, 40, 120));
			g.fillOval(cx - 22, cy - 22, 44, 44);
			g.setColor(Color.WHITE);
			g.fillOval(cx - 6, cy - 6, 12, 12);
			g.dispose();
			writer.encodeVideo(0, img, i * 1000L / 30, TimeUnit.MILLISECONDS);
		}
		writer.close();
		System.out.println("wrote " + path + " (" + frames + " frames)");
	}
}

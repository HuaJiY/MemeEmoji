import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.util.Iterator;

public class CheckDim {
    public static void main(String[] args) throws Exception {
        // Register WebP
        Class<?> readerSpi = Class.forName("com.luciad.imageio.webp.WebPImageReaderSpi");
        var reader = (ImageReader) readerSpi.getDeclaredConstructor().newInstance();
        javax.imageio.spi.IIORegistry.getDefaultInstance().registerServiceProvider(reader, javax.imageio.spi.ImageReaderSpi.class);

        File dir = new File("run/config/memeemoji/emoji");
        for (File f : dir.listFiles((d, n) -> n.endsWith(".webp"))) {
            try (ImageInputStream in = ImageIO.createImageInputStream(f)) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (readers.hasNext()) {
                    ImageReader r = readers.next();
                    r.setInput(in, true, true);
                    System.out.println(f.getName() + ": " + r.getWidth(0) + "x" + r.getHeight(0));
                    r.dispose();
                }
            }
        }
    }
}

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Decode the original JPEG before cropping, preserving ImageIO's JPEG artifacts. */
class CropGiratina {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java scripts/CropGiratina.java <source.jpg>");
        BufferedImage image = ImageIO.read(new File(args[0]));
        if (image == null || image.getWidth() != 540 || image.getHeight() != 1170)
            throw new IllegalArgumentException("Expected the 540x1170 source JPEG");
        // Bar labels and all three tracks only; no caught date, location or other metadata.
        ImageIO.write(image.getSubimage(50, 875, 215, 150), "png", System.out);
    }
}

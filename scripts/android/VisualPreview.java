import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/** Full-resolution fixture previews for the authenticated Actions log fallback. */
class VisualPreview {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--validate")) {
            Path input = Path.of(args[1]);
            if (Files.isRegularFile(input)) validate(input);
            else try (var files = Files.walk(input)) {
                for (Path source : files.filter(path -> path.toString().endsWith(".png")).toList()) validate(source);
            }
            return;
        }
        Path destination = Files.createDirectories(Path.of(args[1]));
        int count = 0;
        Path input = Path.of(args[0]);
        try (var files = Files.walk(input)) {
            for (Path source : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = input.relativize(source).toString().replace('/', '_').replace('\\', '_');
                if (name.endsWith(".png")) {
                    BufferedImage original = ImageIO.read(source.toFile());
                    if (original == null) throw new IllegalStateException("Unreadable capture: " + name);
                    BufferedImage rgb = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
                    var graphics = rgb.createGraphics();
                    graphics.drawImage(original, 0, 0, null);
                    graphics.dispose();
                    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                    try (var output = ImageIO.createImageOutputStream(destination.resolve(name.replace(".png", ".jpg")).toFile())) {
                        writer.setOutput(output);
                        var parameters = writer.getDefaultWriteParam();
                        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        parameters.setCompressionQuality(.9f);
                        writer.write(null, new IIOImage(rgb, null, null), parameters);
                    } finally {
                        writer.dispose();
                    }
                    count++;
                } else if (name.endsWith(".xml") || name.endsWith(".mp4") || name.equals("capture-context.txt")) {
                    Files.copy(source, destination.resolve(name));
                }
            }
        }
        if (count == 0) throw new IllegalStateException("No visual captures to preserve");
        System.out.println("Preserved " + count + " full-resolution visual previews");
    }

    private static void validate(Path source) throws Exception {
        BufferedImage bitmap = ImageIO.read(source.toFile());
        if (bitmap == null) throw new IllegalStateException("Unreadable capture: " + source);
        var colors = new java.util.HashSet<Integer>();
        // Inspect app content, excluding system bars. A file size check alone accepts black ATD frames.
        for (int y = bitmap.getHeight() / 10; y < bitmap.getHeight() * 9 / 10; y += 5) {
            for (int x = bitmap.getWidth() / 10; x < bitmap.getWidth() * 9 / 10; x += 5) {
                colors.add(bitmap.getRGB(x, y) & 0x00ffffff);
            }
        }
        if (colors.size() < 32) throw new IllegalStateException("Blank or unrendered screenshot: " + source);
    }
}

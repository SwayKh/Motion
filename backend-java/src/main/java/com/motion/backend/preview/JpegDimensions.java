package com.motion.backend.preview;

import com.motion.backend.processing.ProcessingException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/** Reads generated sprite dimensions without decoding the full image. */
public final class JpegDimensions {

    private JpegDimensions() {
    }

    public static Size read(Path path) {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                throw new ProcessingException("could not read generated thumbnails");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ProcessingException("could not read generated thumbnails");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new ProcessingException("could not read generated thumbnails");
                }
                return new Size(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new ProcessingException("could not read generated thumbnails", exception);
        }
    }

    public record Size(int width, int height) {
    }
}

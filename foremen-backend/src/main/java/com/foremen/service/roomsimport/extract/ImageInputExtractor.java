package com.foremen.service.roomsimport.extract;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

/**
 * Image decode, EXIF orientation and conversion. JPEG/PNG without rotation are forwarded
 * unchanged; rotated JPEGs and GIF/BMP/TIFF become PNG. HEIC/HEIF/WEBP that {@link ImageIO}
 * cannot decode are forwarded as is with {@code croppable=false}.
 */
public class ImageInputExtractor implements InputExtractor {

    private static final Set<String> FORWARD_AS_IS = Set.of("image/heic", "image/heif", "image/webp");
    private static final Set<String> GEMINI_NATIVE = Set.of("image/jpeg", "image/png", "image/webp");

    @Override
    public boolean supports(InputKind kind) {
        return kind == InputKind.IMAGE;
    }

    @Override
    public ExtractedInput extract(String fileName, byte[] c, DetectedType type) throws Exception {
        String mime = type.mimeType();
        // HEIC/HEIF have no ImageIO reader; WEBP only when a plugin is present
        BufferedImage img = mime.startsWith("image/hei") ? null : ImageIO.read(new ByteArrayInputStream(c));
        if (img == null) {
            if (FORWARD_AS_IS.contains(mime)) {
                return single(fileName, new PageImage(c, mime, 0, 0, false, fileName, null));
            }
            throw new IllegalArgumentException("undecodable image");
        }
        int orientation = "image/jpeg".equals(mime) ? ExifOrientation.read(c) : 1;
        if (GEMINI_NATIVE.contains(mime) && orientation == 1) {
            return single(fileName, new PageImage(c, mime, img.getWidth(), img.getHeight(), true, fileName, null));
        }
        BufferedImage upright = ExifOrientation.apply(img, orientation);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(upright, "png", out)) {
            throw new IllegalStateException("png encode failed");
        }
        return single(fileName, new PageImage(out.toByteArray(), "image/png", upright.getWidth(),
                upright.getHeight(), true, fileName, null));
    }

    private static ExtractedInput single(String fileName, PageImage page) {
        return new ExtractedInput(fileName, InputKind.IMAGE, null, List.of(page), null, null, null);
    }
}

package com.foremen.service.roomsimport.crop;

import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Crops a recognised room out of a source image (Requirement 9.1/9.2).
 *
 * <p>The bounding box is the model's normalised {@code [ymin, xmin, ymax, xmax]} in the
 * {@code 0..1000} range. A margin (fraction of the box size on each side) is added and the
 * result is clamped to the image. Output is always PNG. Never throws: an undecodable image
 * (e.g. HEIC), an invalid box or an encoding failure yields a result without crop bytes and
 * with the {@code CROP_UNAVAILABLE} warning, so the caller can attach the whole source image.
 */
public final class RoomCropper {

    public static final double DEFAULT_MARGIN = 0.04;
    static final double SCALE = 1000.0;

    /**
     * @param png     cropped PNG bytes; null when {@code warning} is present
     * @param warning {@code CROP_UNAVAILABLE} when the crop could not be produced
     */
    public record CropResult(byte[] png, Optional<Warning> warning) {
        public boolean cropped() {
            return png != null;
        }
    }

    private final double margin;

    public RoomCropper() {
        this(DEFAULT_MARGIN);
    }

    public RoomCropper(double margin) {
        this.margin = Double.isNaN(margin) || margin < 0 ? 0 : margin;
    }

    /**
     * @param source     encoded source image bytes
     * @param bbox       {@code [ymin, xmin, ymax, xmax]}, 0..1000
     * @param roomTempId owning draft room, for the warning
     */
    public CropResult crop(byte[] source, double[] bbox, String roomTempId) {
        try {
            if (source == null || source.length == 0 || !validBox(bbox)) {
                return unavailable(roomTempId, "invalid_input");
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return unavailable(roomTempId, "undecodable");
            }
            int w = image.getWidth();
            int h = image.getHeight();

            double boxH = bbox[2] - bbox[0];
            double boxW = bbox[3] - bbox[1];
            double ymin = clamp(bbox[0] - boxH * margin);
            double xmin = clamp(bbox[1] - boxW * margin);
            double ymax = clamp(bbox[2] + boxH * margin);
            double xmax = clamp(bbox[3] + boxW * margin);

            int x0 = Math.min(w - 1, (int) Math.floor(xmin / SCALE * w));
            int y0 = Math.min(h - 1, (int) Math.floor(ymin / SCALE * h));
            int x1 = Math.min(w, Math.max(x0 + 1, (int) Math.ceil(xmax / SCALE * w)));
            int y1 = Math.min(h, Math.max(y0 + 1, (int) Math.ceil(ymax / SCALE * h)));

            BufferedImage sub = image.getSubimage(x0, y0, x1 - x0, y1 - y0);
            // copy so the PNG writer does not depend on the parent raster layout
            BufferedImage copy = new BufferedImage(sub.getWidth(), sub.getHeight(),
                    sub.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            copy.getGraphics().drawImage(sub, 0, 0, null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(copy, "png", out)) {
                return unavailable(roomTempId, "encode_failed");
            }
            return new CropResult(out.toByteArray(), Optional.empty());
        } catch (Exception | OutOfMemoryError e) {
            return unavailable(roomTempId, "error");
        }
    }

    private static boolean validBox(double[] b) {
        if (b == null || b.length != 4) {
            return false;
        }
        for (double v : b) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return false;
            }
        }
        return b[2] > b[0] && b[3] > b[1];
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(SCALE, v));
    }

    private static CropResult unavailable(String roomTempId, String reason) {
        return new CropResult(null, Optional.of(new Warning(
                WarningCodes.CROP_UNAVAILABLE, Severity.INFO, roomTempId, null,
                Map.of("reason", reason), null)));
    }
}

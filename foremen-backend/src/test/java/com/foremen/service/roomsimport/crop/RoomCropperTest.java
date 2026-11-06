package com.foremen.service.roomsimport.crop;

import com.foremen.service.roomsimport.crop.RoomCropper.CropResult;
import com.foremen.service.roomsimport.model.WarningCodes;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RoomCropperTest {

    private static byte[] image(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void cropsBboxWithoutMarginToPng() throws Exception {
        // 1000x500 image, box covers x 100..500 (400px), y 200..600 => 100..300 (200px)
        CropResult r = new RoomCropper(0).crop(image(1000, 500, "png"), new double[]{200, 100, 600, 500}, "r1");
        assertThat(r.cropped()).isTrue();
        assertThat(r.warning()).isEmpty();
        BufferedImage out = decode(r.png());
        assertThat(out.getWidth()).isEqualTo(400);
        assertThat(out.getHeight()).isEqualTo(200);
        // PNG signature
        assertThat(r.png()[1]).isEqualTo((byte) 'P');
    }

    @Test
    void defaultMarginExpandsBoxByFourPercentPerSide() throws Exception {
        // box width 400/1000 => margin 16 each side => 432 of 1000px; height 400 => 432 of 1000px
        CropResult r = new RoomCropper().crop(image(1000, 1000, "jpg"), new double[]{300, 300, 700, 700}, "r1");
        BufferedImage out = decode(r.png());
        assertThat(out.getWidth()).isBetween(431, 433);
        assertThat(out.getHeight()).isBetween(431, 433);
    }

    @Test
    void marginIsClampedToImageBounds() throws Exception {
        CropResult r = new RoomCropper().crop(image(200, 100, "png"), new double[]{0, 0, 1000, 1000}, "r1");
        BufferedImage out = decode(r.png());
        assertThat(out.getWidth()).isEqualTo(200);
        assertThat(out.getHeight()).isEqualTo(100);
    }

    @Test
    void outOfRangeBoxIsClamped() throws Exception {
        CropResult r = new RoomCropper(0).crop(image(100, 100, "png"), new double[]{-50, 900, 500, 2000}, "r1");
        BufferedImage out = decode(r.png());
        assertThat(out.getWidth()).isEqualTo(10);
        assertThat(out.getHeight()).isEqualTo(50);
    }

    @Test
    void degenerateBoxStillYieldsAtLeastOnePixel() throws Exception {
        CropResult r = new RoomCropper(0).crop(image(100, 100, "png"), new double[]{500, 500, 500.1, 500.1}, "r1");
        BufferedImage out = decode(r.png());
        assertThat(out.getWidth()).isGreaterThanOrEqualTo(1);
        assertThat(out.getHeight()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void undecodableBytesFallBackToCropUnavailable() {
        byte[] heicLike = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
        CropResult r = new RoomCropper().crop(heicLike, new double[]{0, 0, 500, 500}, "r7");
        assertThat(r.cropped()).isFalse();
        assertThat(r.warning()).isPresent();
        assertThat(r.warning().get().code()).isEqualTo(WarningCodes.CROP_UNAVAILABLE);
        assertThat(r.warning().get().roomTempId()).isEqualTo("r7");
    }

    @Test
    void invalidInputsFallBackWithoutThrowing() throws Exception {
        byte[] png = image(10, 10, "png");
        RoomCropper c = new RoomCropper();
        for (CropResult r : new CropResult[]{
                c.crop(null, new double[]{0, 0, 10, 10}, "r1"),
                c.crop(new byte[0], new double[]{0, 0, 10, 10}, "r1"),
                c.crop(png, null, "r1"),
                c.crop(png, new double[]{0, 0, 10}, "r1"),
                c.crop(png, new double[]{500, 500, 100, 100}, "r1"),
                c.crop(png, new double[]{Double.NaN, 0, 10, 10}, "r1")}) {
            assertThat(r.cropped()).isFalse();
            assertThat(r.warning().get().code()).isEqualTo(WarningCodes.CROP_UNAVAILABLE);
        }
    }
}

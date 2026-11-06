package com.foremen.service.roomsimport.extract;

import java.awt.image.BufferedImage;

/** Minimal JPEG EXIF orientation reader and pixel transform (orientation values 1..8). */
final class ExifOrientation {

    private ExifOrientation() {}

    /** @return EXIF orientation 1..8, or 1 when absent/unparseable or not a JPEG. */
    static int read(byte[] b) {
        try {
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
                return 1;
            }
            int pos = 2;
            while (pos + 4 < b.length) {
                if ((b[pos] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = b[pos + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    return 1;
                }
                int len = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
                if (marker == 0xE1 && len >= 16 && b[pos + 4] == 'E' && b[pos + 5] == 'x'
                        && b[pos + 6] == 'i' && b[pos + 7] == 'f') {
                    return tiffOrientation(b, pos + 10, pos + 2 + len);
                }
                pos += 2 + len;
            }
        } catch (RuntimeException ignored) {
            // malformed EXIF => treat as upright
        }
        return 1;
    }

    private static int tiffOrientation(byte[] b, int tiff, int end) {
        boolean le = b[tiff] == 'I';
        int ifd = tiff + (int) u32(b, tiff + 4, le);
        int n = u16(b, ifd, le);
        for (int i = 0; i < n; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > end) {
                break;
            }
            if (u16(b, e, le) == 0x0112) {
                int v = u16(b, e + 8, le);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int o, boolean le) {
        return le ? (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) : ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static long u32(byte[] b, int o, boolean le) {
        return le ? (u16(b, o, true) & 0xFFFFL) | ((long) u16(b, o + 2, true) << 16)
                : ((long) u16(b, o, false) << 16) | (u16(b, o + 2, false) & 0xFFFFL);
    }

    /** Applies the orientation so the returned image is upright. */
    static BufferedImage apply(BufferedImage src, int o) {
        if (o <= 1) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = o >= 5;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx;
                int ny;
                switch (o) {
                    case 2 -> { nx = w - 1 - x; ny = y; }
                    case 3 -> { nx = w - 1 - x; ny = h - 1 - y; }
                    case 4 -> { nx = x; ny = h - 1 - y; }
                    case 5 -> { nx = y; ny = x; }
                    case 6 -> { nx = h - 1 - y; ny = x; }
                    case 7 -> { nx = h - 1 - y; ny = w - 1 - x; }
                    default -> { nx = y; ny = w - 1 - x; }
                }
                dst.setRGB(nx, ny, src.getRGB(x, y));
            }
        }
        return dst;
    }
}

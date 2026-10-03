package com.mineastr;

import java.util.Arrays;

/** Recognizes a complete first image without trying to decode partial LZW data. */
final class MineAstrGifPreview {
    static byte[] firstFrame(byte[] bytes, int length) {
        if (length < 13 || !MineAstrGif.matches(bytes)) return null;
        int position = 13;
        if ((bytes[10] & 128) != 0) position += 3 * (1 << ((bytes[10] & 7) + 1));
        while (position < length) {
            int marker = bytes[position++] & 255;
            if (marker == 0x21) {
                if (position >= length) return null;
                position++; // Extension label, followed by length-prefixed sub-blocks.
                position = skipBlocks(bytes, length, position);
                if (position < 0) return null;
            } else if (marker == 0x2c) {
                if (position + 9 > length) return null;
                int packed = bytes[position + 8] & 255;
                position += 9;
                if ((packed & 128) != 0) position += 3 * (1 << ((packed & 7) + 1));
                if (position >= length) return null;
                position++; // LZW minimum code size.
                position = skipBlocks(bytes, length, position);
                if (position < 0) return null;
                byte[] preview = Arrays.copyOf(bytes, position + 1);
                preview[position] = 0x3b;
                return preview;
            } else return null;
        }
        return null;
    }

    private static int skipBlocks(byte[] bytes, int length, int position) {
        while (position < length) {
            int count = bytes[position++] & 255;
            if (count == 0) return position;
            if (count > length - position) return -1;
            position += count;
        }
        return -1;
    }
}

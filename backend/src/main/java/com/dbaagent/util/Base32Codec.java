package com.dbaagent.util;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

public final class Base32Codec {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32Codec() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = data[0] & 0xff;
        int next = 1;
        int bitsLeft = 8;
        while (bitsLeft > 0 || next < data.length) {
            if (bitsLeft < 5) {
                if (next < data.length) {
                    buffer <<= 8;
                    buffer |= data[next++] & 0xff;
                    bitsLeft += 8;
                } else {
                    int pad = 5 - bitsLeft;
                    buffer <<= pad;
                    bitsLeft += pad;
                }
            }
            int index = 0x1f & (buffer >> (bitsLeft - 5));
            bitsLeft -= 5;
            result.append(ALPHABET[index]);
        }
        return result.toString();
    }

    public static byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        String normalized = value.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : normalized.toCharArray()) {
            int val = alphabetIndex(c);
            if (val < 0) {
                throw new IllegalArgumentException("Invalid base32 character");
            }
            buffer <<= 5;
            buffer |= val & 0x1f;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static int alphabetIndex(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '2' && c <= '7') {
            return 26 + (c - '2');
        }
        return -1;
    }
}

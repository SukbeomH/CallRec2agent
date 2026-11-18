package dev.local.voicejson;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class TranscriptFingerprint {
    static String hash(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static boolean same(String sourceHash, String actualRemoteHash) {
        return sourceHash != null && sourceHash.equals(actualRemoteHash);
    }
}

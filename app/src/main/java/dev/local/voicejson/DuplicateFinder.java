package dev.local.voicejson;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class DuplicateFinder {
    record File(String id, String name) {}
    record Match(File file, int bytes, String sha256) {}
    interface Reader { byte[] read(String id) throws Exception; }
    static Match find(List<File> files, String name, String sourceHash, String preferredId, Reader reader) throws Exception {
        List<File> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparingInt(file -> file.id().equals(preferredId) ? 0 : file.name().equals(name) ? 1 : 2));
        for (File file : ordered) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!file.name().toLowerCase(Locale.ROOT).endsWith(".txt") || "llm.txt".equalsIgnoreCase(file.name())) continue;
            byte[] bytes = reader.read(file.id());
            String actual = TranscriptFingerprint.hash(bytes);
            if (TranscriptFingerprint.same(sourceHash, actual)) return new Match(file, bytes.length, actual);
        }
        return null;
    }
}

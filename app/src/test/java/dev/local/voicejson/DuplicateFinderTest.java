package dev.local.voicejson;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class DuplicateFinderTest {
    private static byte[] txt(String body) { return ("\ufeff제목\n\n나  (00:01)\n" + body).getBytes(StandardCharsets.UTF_16BE); }
    private static DuplicateFinder.Match find(Map<String, byte[]> content, List<DuplicateFinder.File> files, byte[] source, String preferred) throws Exception {
        return DuplicateFinder.find(files, "회의_original.txt", TranscriptFingerprint.hash(source), preferred, id -> content.get(id));
    }
    @Test public void adoptsExistingUploadWithoutAnyLocalHistory() throws Exception {
        byte[] original = txt("기존 발화");
        var result = find(Map.of("remote", original), List.of(new DuplicateFinder.File("remote", "회의_original.txt")), original, null);
        assertNotNull(result); assertEquals("remote", result.file().id());
    }
    @Test public void sameNameWithChangedTranscriptNeedsUpload() throws Exception {
        assertNull(find(Map.of("remote", txt("수정 전")), List.of(new DuplicateFinder.File("remote", "회의_original.txt")), txt("수정 후"), "remote"));
    }
    @Test public void renamedRemoteStillPreventsDuplicate() throws Exception {
        byte[] original = txt("같은 발화");
        assertNotNull(find(Map.of("remote", original), List.of(new DuplicateFinder.File("remote", "사용자가 바꾼 이름.txt")), original, null));
    }
    @Test public void deletedRemoteIsNotSkippedBecauseOfOldHistory() throws Exception {
        assertNull(find(Map.of(), List.of(), txt("기존 발화"), "deleted"));
    }
    @Test public void remoteEditIsReadAgainInsteadOfTrustingHistory() throws Exception {
        assertNull(find(Map.of("remote", txt("Drive에서 변경한 본문")), List.of(new DuplicateFinder.File("remote", "회의_original.txt")), txt("폰 원본"), "remote"));
    }
    @Test public void multipleSameNamedFilesUseActualMatchingContent() throws Exception {
        byte[] wanted = txt("둘째 버전");
        var files = List.of(new DuplicateFinder.File("first", "회의_original.txt"), new DuplicateFinder.File("second", "회의_original.txt"));
        var match = find(Map.of("first", txt("첫째 버전"), "second", wanted), files, wanted, "first");
        assertEquals("second", match.file().id());
    }
    @Test public void incompleteOrUnreadableInventoryCannotAuthorizeUpload() {
        assertThrows(java.io.IOException.class, () -> DuplicateFinder.find(List.of(new DuplicateFinder.File("remote", "회의_original.txt")), "회의_original.txt", TranscriptFingerprint.hash(txt("본문")), null,
            id -> { throw new java.io.IOException("offline"); }));
    }
    @Test public void newVersionIsSkippedAfterItsVerifiedFileAppears() throws Exception {
        byte[] edited = txt("수정된 발화");
        var original = new DuplicateFinder.File("old", "회의_original.txt");
        assertNull(find(Map.of("old", txt("수정 전")), List.of(original), edited, "old"));
        assertNotNull(find(Map.of("old", txt("수정 전"), "new", edited), List.of(original, new DuplicateFinder.File("new", "회의_original.txt")), edited, "new"));
    }
    @Test public void guidesAndLegacyJsonDoNotStandInForActualTxt() throws Exception {
        byte[] source = txt("본문");
        assertNull(find(Map.of("guide", source, "json", source), List.of(new DuplicateFinder.File("guide", "llm.txt"), new DuplicateFinder.File("json", "old.json")), source, null));
    }
}

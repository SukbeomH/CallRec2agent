package dev.local.voicejson;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DirectShareTest {
    @Test public void readsOriginalNameFromDriveMultipleShareLabel() {
        assertEquals("회의_original.txt", DirectShare.uploadName("회의_original.txt, 파일 이름 목록"));
        assertEquals("회의_original.txt", DirectShare.uploadName("회의_original.txt"));
        assertEquals("call", DirectShare.folderName("call, 위치"));
        assertEquals("call", DirectShare.folderName("call"));
    }
    @Test public void detectsWrongRecording() {
        assertTrue(DirectShare.matchesRecording("회의_original.txt", "회의"));
        assertTrue(DirectShare.matchesRecording("회의.txt", "회의"));
        assertFalse(DirectShare.matchesRecording("다른 회의_original.txt", "회의"));
    }
    @Test public void recognizesUtf16SamsungTranscriptWithoutChangingBytes() {
        String source = "\ufeffAI로 생성된 콘텐츠입니다\n\n회의\n\n나 & 발화자 1  (01:23)\n첫 줄\n둘째 줄\n";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_16BE), copy = bytes.clone();
        assertTrue(DirectShare.isTranscript(bytes));
        assertArrayEquals(copy, bytes);
        assertTrue(DirectShare.isTranscript(source.getBytes(StandardCharsets.UTF_16LE)));
        assertTrue(DirectShare.isTranscript(source.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void rejectsEmptyOrUnrelatedFiles() {
        assertFalse(DirectShare.isTranscript(new byte[0]));
        assertFalse(DirectShare.isTranscript("일반 메모입니다".getBytes(StandardCharsets.UTF_8)));
        assertFalse(DirectShare.isTranscript("나  (01:99)\n본문".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void automaticSaveRequiresConfirmedAccountAndMatchingFolder() {
        assertTrue(DirectShare.maySave("call", "a@example.com", "call", "a@example.com"));
        assertFalse(DirectShare.maySave("call", "a@example.com", "call", null));
        assertFalse(DirectShare.maySave("other", "a@example.com", "call", "a@example.com"));
        assertFalse(DirectShare.maySave("call", "b@example.com", "call", "a@example.com"));
    }
    @Test public void oldSameNamedFileDoesNotProveNewSave() {
        var before = java.util.Set.of("uri:existing");
        assertFalse(DirectShare.newlySaved("회의.txt", "회의.txt", "uri:existing", before));
        assertFalse(DirectShare.newlySaved("다른.txt", "회의.txt", "uri:new", before));
        assertTrue(DirectShare.newlySaved("회의.txt", "회의.txt", "uri:new", before));
    }
    @Test public void folderNamesCannotEscapeSelectedParent() {
        assertTrue(DirectShare.validFolderName("CallRec2agent"));
        for (String name : new String[]{"", " ", ".", "..", "a/b", "a\\b", "a\nb", "a".repeat(101)}) assertFalse(name, DirectShare.validFolderName(name));
    }
    @Test public void choosesAppLabelInsteadOfDirectShareBadge() {
        assertTrue(DirectShare.isAppLabel("Drive", "android:id/text1", "Drive"));
        assertTrue(DirectShare.isAppLabel("전사 Drive 확인", "android:id/text1", "전사 Drive 확인"));
        assertFalse(DirectShare.isAppLabel("Drive", "android:id/text1", "전사 Drive 확인"));
        assertFalse(DirectShare.isAppLabel("", "com.android.intentresolver:id/sem_target_badge", "Drive"));
        assertFalse(DirectShare.isAppLabel("call", "android:id/text1", "Drive"));
        assertFalse(DirectShare.isAppLabel("Drive", "com.android.intentresolver:id/sem_target_badge", "Drive"));
    }
}

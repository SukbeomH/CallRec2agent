package dev.local.voicejson;

import org.junit.Test;
import static org.junit.Assert.*;

public class AgentPromptTest {
    @Test public void includesVerifiedAccountLocationLinkAndReadingInstructions() {
        String prompt = AgentPrompt.build("person@example.com", "call / CallRec2agent", "https://drive.google.com/drive/folders/example", "회의_original.txt");
        for (String expected : new String[]{"person@example.com", "call / CallRec2agent", "https://drive.google.com/drive/folders/example", "회의_original.txt", "llm.txt", "index.json", "UTF-16", "Wi-Fi"}) assertTrue(expected, prompt.contains(expected));
    }
    @Test public void unverifiedSetupDoesNotInventAccountOrLink() {
        String prompt = AgentPrompt.build(null, "call", null, null);
        assertTrue(prompt.contains("확인되지 않았습니다"));
        assertTrue(prompt.contains("폴더 링크는 미등록"));
    }
    @Test public void onlyAcceptsGoogleDriveFolderLinks() {
        assertTrue(AgentPrompt.validFolderLink("https://drive.google.com/drive/folders/example?usp=sharing"));
        assertTrue(AgentPrompt.validFolderLink("https://drive.google.com/drive/u/0/folders/example"));
        assertFalse(AgentPrompt.validFolderLink("https://drive.google.com.evil.example/drive/folders/example"));
        assertFalse(AgentPrompt.validFolderLink("https://drive.google.com/file/d/example/view"));
    }
}

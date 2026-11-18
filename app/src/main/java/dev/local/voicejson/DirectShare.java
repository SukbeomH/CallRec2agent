package dev.local.voicejson;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

final class DirectShare {
    private static final Pattern SPEECH = Pattern.compile("(?m)^.+?\\s{2,}\\((?:\\d{1,3}:)?[0-5]?[0-9]:[0-5][0-9]\\)\\s*$");
    static boolean isDriveAppLabel(String text, String resourceId) {
        return "Drive".equals(text) && "android:id/text1".equals(resourceId);
    }
    static String uploadName(String displayed) {
        return displayed.endsWith(", 파일 이름 목록") ? displayed.substring(0, displayed.length() - ", 파일 이름 목록".length()) : displayed;
    }
    static String folderName(String displayed) {
        return displayed.endsWith(", 위치") ? displayed.substring(0, displayed.length() - ", 위치".length()) : displayed;
    }
    static boolean validFolderName(String name) {
        return !name.isBlank() && name.length() <= 100 && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.chars().noneMatch(Character::isISOControl);
    }
    static boolean newlySaved(String actualName, String wantedName, String uri, java.util.Set<String> before) {
        return wantedName.equals(actualName) && !before.contains(uri);
    }
    static boolean matchesRecording(String original, String title) {
        return title == null || original.equals(title + "_original.txt") || original.equals(title + ".txt");
    }
    static boolean isTranscript(byte[] bytes) {
        if (bytes.length < 4) return false;
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) { charset = StandardCharsets.UTF_16BE; offset = 2; }
        else if (bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) { charset = StandardCharsets.UTF_16LE; offset = 2; }
        String text = new String(bytes, offset, bytes.length - offset, charset);
        return SPEECH.matcher(text).find();
    }
    static boolean maySave(String folder, String account, String expectedFolder, String confirmedAccount) {
        return expectedFolder != null && expectedFolder.equals(folder) && account != null
            && confirmedAccount != null && confirmedAccount.equals(account);
    }
}

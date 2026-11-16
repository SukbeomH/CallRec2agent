package dev.local.voicejson;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class TranscriptStore {
    record Receipt(String sourceName, String hash, String uri, int segments, boolean duplicate) {}
    private final Context context;
    TranscriptStore(Context context) { this.context = context.getApplicationContext(); }

    synchronized Receipt receive(Uri uri, String expectedTitle) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException("공유된 content URI가 필요합니다.");
        String name = "transcript.txt";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        }
        if (expectedTitle != null && !name.equals(expectedTitle + "_original.txt") && !name.equals(expectedTitle + ".txt")) {
            throw new IllegalArgumentException("대기 중인 녹음과 공유 파일 이름이 다릅니다. 자동화를 중지합니다.");
        }
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("공유 파일을 열 수 없습니다.");
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > 8 * 1024 * 1024) throw new IllegalArgumentException("공유 TXT가 8MB를 넘습니다.");
                output.write(buffer, 0, n);
            }
            bytes = output.toByteArray();
        }
        SamsungTxtParser.Transcript parsed = SamsungTxtParser.parse(bytes);
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format(Locale.ROOT, "%02x", b & 255));
        String hash = digest.toString();
        File folder = new File(context.getFilesDir(), "transcripts");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("저장 폴더를 만들 수 없습니다.");
        File jsonFile = new File(folder, hash + ".json");
        boolean duplicate = jsonFile.exists();
        if (!duplicate) {
            JSONObject document = new JSONObject();
            document.put("schemaVersion", 1).put("sourceFileName", name).put("sourceSha256", hash).put("sourceEncoding", parsed.encoding());
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            document.put("importedAt", iso.format(new Date())).put("title", parsed.title()).put("timeResolutionMs", 1000);
            document.put("source", "Samsung Voice Recorder TXT share");
            JSONArray segments = new JSONArray();
            for (SamsungTxtParser.Segment segment : parsed.segments()) {
                JSONObject item = new JSONObject();
                item.put("speaker", segment.speaker()).put("speakers", new JSONArray(segment.speakers()));
                item.put("startMs", segment.startMs()).put("text", segment.text()); segments.put(item);
            }
            document.put("segments", segments);
            atomic(new File(folder, hash + ".txt"), bytes);
            atomic(jsonFile, document.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        String publicUri = publish(jsonFile, hash, name);
        context.getSharedPreferences("status", 0).edit().putString("last", name + " · " + parsed.segments().size() + "개 발화 · " + (duplicate ? "기존 파일 확인" : "저장 완료")).apply();
        return new Receipt(name, hash, publicUri, parsed.segments().size(), duplicate);
    }

    private String publish(File file, String hash, String originalName) throws Exception {
        var prefs = context.getSharedPreferences("published", 0);
        String existing = prefs.getString(hash, null);
        if (existing != null) {
            try (Cursor c = context.getContentResolver().query(Uri.parse(existing), new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
                if (c != null && c.moveToFirst()) return existing;
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, jsonFileName(originalName, hash));
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VoiceJson");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri output = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (output == null) throw new IllegalStateException("다운로드 폴더에 저장할 수 없습니다.");
        try {
            try (InputStream input = new java.io.FileInputStream(file); OutputStream stream = context.getContentResolver().openOutputStream(output)) {
                if (stream == null) throw new IllegalStateException("저장 파일을 열 수 없습니다.");
                byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) != -1) stream.write(buffer, 0, n);
            }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(output, values, null, null);
            prefs.edit().putString(hash, output.toString()).apply(); return output.toString();
        } catch (Exception failure) {
            context.getContentResolver().delete(output, null, null); throw failure;
        }
    }

    static String jsonFileName(String originalName, String hash) {
        String stem = originalName.replaceFirst("(?i)\\.txt$", "").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (stem.length() > 70) stem = stem.substring(0, 70);
        return stem + "_" + hash.substring(0, 10) + ".json";
    }

    private static void atomic(File file, byte[] bytes) throws Exception {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream stream = atomic.startWrite();
        try { stream.write(bytes); atomic.finishWrite(stream); }
        catch (Exception failure) { atomic.failWrite(stream); throw failure; }
    }
}

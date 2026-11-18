package dev.local.voicejson;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

final class DriveFolder {
    record Document(Uri uri, String name) {}
    record Receipt(String name, String uri, int bytes, String sha256) {}
    private final Context context;
    private final Uri tree;
    private final String folderId;
    DriveFolder(Context context) {
        this.context = context.getApplicationContext();
        String selected = context.getSharedPreferences("drive", 0).getString("tree", null);
        if (selected == null) throw new IllegalStateException("먼저 Drive 폴더를 선택하세요.");
        tree = Uri.parse(selected);
        folderId = context.getSharedPreferences("drive", 0).getString("folder_id", DocumentsContract.getTreeDocumentId(tree));
    }
    private List<Document> children() throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId);
        try (Cursor cursor = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Drive 폴더를 읽을 수 없습니다. 폴더 권한을 다시 선택하세요.");
            String error = cursor.getExtras().getString(DocumentsContract.EXTRA_ERROR);
            if (error != null) throw new IllegalStateException(error);
            if (cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) return null;
            List<Document> files = new ArrayList<>();
            while (cursor.moveToNext()) files.add(new Document(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(1)));
            return files;
        }
    }
    private byte[] read(Uri document) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(document); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Drive 파일을 다시 읽을 수 없습니다.");
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (out.size() + count > 16 * 1024 * 1024) throw new IllegalStateException("확인할 TXT가 16MB를 넘습니다.");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    java.util.Set<String> snapshot() throws Exception {
        List<Document> files = children();
        if (files == null) throw new IllegalStateException("Drive 폴더 목록이 로딩 중입니다. 잠시 후 다시 시작하세요.");
        java.util.Set<String> result = new java.util.HashSet<>();
        for (Document file : files) result.add(file.uri().toString());
        return result;
    }
    static String ensureChild(Context context, Uri tree, String parentId, String name) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        for (int attempt = 0; attempt < 40; attempt++) {
            try (Cursor cursor = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IllegalStateException("상위 Drive 폴더를 읽을 수 없습니다.");
                String error = cursor.getExtras().getString(DocumentsContract.EXTRA_ERROR);
                if (error != null) throw new IllegalStateException(error);
                if (cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) { Thread.sleep(500); continue; }
                String found = null;
                while (cursor.moveToNext()) if (name.equals(cursor.getString(1))) {
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)) || found != null) throw new IllegalStateException("같은 이름의 파일이나 여러 폴더가 있습니다. 다른 이름을 사용하세요.");
                    found = cursor.getString(0);
                }
                if (found != null) return found;
                Uri created = DocumentsContract.createDocument(context.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, parentId), DocumentsContract.Document.MIME_TYPE_DIR, name);
                if (created == null) throw new IllegalStateException("Drive 폴더를 생성할 수 없습니다.");
                return DocumentsContract.getDocumentId(created);
            }
        }
        throw new IllegalStateException("상위 Drive 폴더 목록 로딩 시간이 초과됐습니다.");
    }
    Receipt verify(String name, java.util.Set<String> before) throws Exception {
        List<Document> files = children();
        if (files == null) return null;
        List<Document> matches = files.stream().filter(file -> DirectShare.newlySaved(file.name(), name, file.uri().toString(), before)).collect(java.util.stream.Collectors.toList());
        if (matches.isEmpty()) return null;
        if (matches.size() != 1) throw new IllegalStateException("같은 이름의 파일이 여러 개여서 저장을 확인할 수 없습니다.");
        Document document = matches.get(0);
        byte[] bytes = read(document.uri());
        if (bytes.length == 0) return null;
        if (!DirectShare.isTranscript(bytes)) throw new IllegalStateException("Drive의 파일에서 삼성 전사 TXT 형식을 확인하지 못했습니다.");
        return new Receipt(name, document.uri().toString(), bytes.length, hash(bytes));
    }
    void prepareGuide() throws Exception {
        byte[] wanted;
        try (InputStream input = context.getAssets().open("llm.txt")) { wanted = input.readAllBytes(); }
        List<Document> files = null;
        for (int attempt = 0; attempt < 40 && files == null; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            files = children(); if (files == null) Thread.sleep(500);
        }
        if (files == null) throw new IllegalStateException("Drive 폴더 목록 로딩 시간이 초과됐습니다.");
        List<Document> matches = files.stream().filter(file -> "llm.txt".equals(file.name())).collect(java.util.stream.Collectors.toList());
        if (matches.size() > 1) throw new IllegalStateException("llm.txt가 여러 개입니다. 전용 폴더를 선택하세요.");
        var history = context.getSharedPreferences("drive-written", 0);
        String key = hash((tree + (folderId.equals(DocumentsContract.getTreeDocumentId(tree)) ? "" : "\n" + folderId) + "\nllm.txt").getBytes(StandardCharsets.UTF_8));
        Uri document = matches.isEmpty() ? null : matches.get(0).uri();
        if (document != null) {
            String current = hash(read(document));
            if (current.equals(hash(wanted))) return;
            boolean owned = false;
            for (var entry : history.getAll().entrySet()) if (entry.getKey().endsWith("_uri") && entry.getValue() instanceof String previousUri) {
                try {
                    String previousKey = entry.getKey().substring(0, entry.getKey().length() - 4);
                    if (DocumentsContract.getDocumentId(Uri.parse(previousUri)).equals(DocumentsContract.getDocumentId(document))
                            && current.equals(history.getString(previousKey + "_hash", null))) { owned = true; break; }
                } catch (IllegalArgumentException ignored) { }
            }
            if (!owned)
                throw new IllegalStateException("사용자가 수정했거나 이 앱이 쓰지 않은 llm.txt는 덮어쓰지 않습니다. 다른 전용 폴더를 선택하세요.");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (document == null) document = DocumentsContract.createDocument(context.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, folderId), "text/plain", "llm.txt");
        if (document == null) throw new IllegalStateException("llm.txt를 만들 수 없습니다.");
        try (var output = context.getContentResolver().openOutputStream(document, "wt")) {
            if (output == null) throw new IllegalStateException("llm.txt를 쓸 수 없습니다.");
            output.write(wanted);
        }
        if (!hash(wanted).equals(hash(read(document)))) throw new IllegalStateException("llm.txt를 다시 읽은 내용이 다릅니다.");
        history.edit().putString(key + "_uri", document.toString()).putString(key + "_hash", hash(wanted)).commit();
    }
    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
}

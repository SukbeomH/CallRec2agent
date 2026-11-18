package dev.local.voicejson;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.nio.charset.StandardCharsets;

final class TransferHistory {
    private final SharedPreferences entries;
    private final String folderId;
    TransferHistory(Context context) {
        entries = context.getSharedPreferences("transfers", 0);
        var drive = context.getSharedPreferences("drive", 0);
        folderId = drive.getString("folder_id", DocumentsContract.getTreeDocumentId(Uri.parse(drive.getString("tree", ""))));
    }
    private String key(String name) { return TranscriptFingerprint.hash((folderId + "\n" + name).getBytes(StandardCharsets.UTF_8)); }
    String preferredDocument(String name) { return entries.getString(key(name) + "_document", null); }
    void remember(String sourceName, String sourceHash, DriveFolder.Receipt receipt) {
        String key = key(sourceName);
        entries.edit().putString(key + "_name", sourceName).putString(key + "_source_hash", sourceHash)
            .putString(key + "_document", DocumentsContract.getDocumentId(Uri.parse(receipt.uri())))
            .putLong(key + "_checked_at", System.currentTimeMillis()).apply();
    }
}

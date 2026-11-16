package dev.local.voicejson;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

public class ShareActivity extends Activity {
    private static final java.util.concurrent.ExecutorService IMPORTS = java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this); status.setTextSize(20); status.setPadding(32, 60, 32, 32); status.setText("공유된 전사를 JSON으로 저장하는 중…"); setContentView(status);
        ExportService service = ExportService.instance;
        long token = service == null ? -1 : service.receiptToken();
        String expected = service == null ? null : service.expectedTitle();
        Intent intent = getIntent();
        Uri stream;
        if (Intent.ACTION_SEND.equals(intent.getAction())) stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            java.util.ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (streams == null || streams.size() != 1) {
                String message = "녹음별 TXT 한 개씩 공유해 주세요.";
                if (service != null && token >= 0) service.failed(token, message);
                status.setText(message); return;
            }
            stream = streams.get(0);
        } else { status.setText("삼성 음성녹음에서 TXT를 공유해 주세요."); return; }
        Uri uri = stream;
        IMPORTS.execute(() -> {
            try {
                TranscriptStore.Receipt receipt = new TranscriptStore(this).receive(uri, expected);
                runOnUiThread(() -> {
                    if (service != null && token >= 0) service.received(token, receipt);
                    if (token >= 0) finish();
                    else { status.setText(receipt.sourceName() + "\n\n" + receipt.segments() + "개 발화 저장 완료\n\n내 파일 → 내장 저장공간 → Download → VoiceJson"); }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> { if (service != null && token >= 0) service.failed(token, failure.getMessage()); status.setText("저장 실패\n" + failure.getMessage()); });
            }
        });
    }
}

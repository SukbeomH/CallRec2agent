package dev.local.voicejson;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

public class ShareActivity extends Activity {
    private long token = -1;
    private boolean resolved;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView message = new TextView(this); message.setTextSize(20); message.setPadding(40, 90, 40, 40);
        message.setText("TXT 내용과 기존 Drive 파일을 비교하고 있어요.\n\n같은 내용이면 건너뛰고, 새 내용만 Drive로 보냅니다."); setContentView(message);
        ExportService service = ExportService.instance;
        token = service == null ? -1 : service.shareToken();
        if (token < 0) { message.setText("전사 Drive 앱에서 녹음을 준비하고 ‘전송 시작’을 눌러 주세요."); return; }
        try {
            Intent incoming = getIntent();
            Uri stream;
            if (Intent.ACTION_SEND.equals(incoming.getAction())) stream = incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            else if (Intent.ACTION_SEND_MULTIPLE.equals(incoming.getAction())) {
                var streams = incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
                if (streams == null || streams.size() != 1) throw new IllegalArgumentException("녹음별 TXT 하나씩 공유해 주세요.");
                stream = streams.get(0);
            } else throw new IllegalArgumentException("TXT 파일 공유만 지원합니다.");
            if (stream == null || !"content".equals(stream.getScheme())) throw new IllegalArgumentException("공유된 TXT 읽기 권한을 확인할 수 없습니다.");
            Uri source = stream;
            service.reviewShare(token, source, send -> {
                if (isDestroyed() || isFinishing()) { service.shareFailed(token, "공유 검사 화면이 닫혔습니다."); return; }
                try {
                    if (send) {
                        Intent forward = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/txt").setPackage(ExportService.DRIVE)
                            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new java.util.ArrayList<>(java.util.List.of(source))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        forward.setClipData(ClipData.newRawUri("전사 TXT", source));
                        var target = getPackageManager().resolveActivity(forward, 0);
                        if (target == null || !ExportService.DRIVE.equals(target.activityInfo.packageName)) throw new IllegalStateException("Drive의 TXT 공유 화면을 열 수 없습니다.");
                        forward.setComponent(new ComponentName(target.activityInfo.packageName, target.activityInfo.name));
                        startActivity(forward);
                    }
                    resolved = true; finish();
                } catch (Exception failure) { resolved = true; service.shareFailed(token, failure.getMessage()); message.setText("공유 중지 · " + failure.getMessage()); }
            });
        } catch (Exception failure) { resolved = true; service.shareFailed(token, failure.getMessage()); message.setText(failure.getMessage()); }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
            resolved = true; service.shareFailed(token, "사용자가 공유 검사를 중지했습니다."); finish();
        });
    }
    @Override protected void onDestroy() {
        if (!resolved && isFinishing() && ExportService.instance != null) ExportService.instance.shareFailed(token, "공유 검사 화면이 닫혔습니다.");
        super.onDestroy();
    }
}

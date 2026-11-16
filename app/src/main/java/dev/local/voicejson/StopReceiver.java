package dev.local.voicejson;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (ExportService.instance != null) ExportService.instance.stop("사용자가 중지했습니다.");
    }
}

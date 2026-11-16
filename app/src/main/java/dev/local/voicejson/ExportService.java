package dev.local.voicejson;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Path;
import android.accessibilityservice.GestureDescription;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ExportService extends AccessibilityService {
    static final String RECORDER = "com.sec.android.app.voicenote";
    static ExportService instance;
    enum Outcome { RUNNING, COMPLETED, STOPPED }
    record Snapshot(String id, Outcome outcome, int saved, int skipped, String message) {}
    private String runId;
    private Outcome outcome;
    private enum Stage { IDLE, SELECT, OPEN, MENU, FORMAT, CHOOSER, RECEIVING, RETURN }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Stage stage = Stage.IDLE;
    private boolean batch;
    private int limit, saved, skipped, scrolls;
    private long deadline, nextAction, openedAt, outsideSince;
    private String pendingTitle, pendingKey, lastCursor = "";
    private String lastScrollDiagnostic = "none";
    private final Set<String> visited = new HashSet<>();
    private long generation;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (stage == Stage.IDLE) return;
            try { step(); } catch (Exception e) { stop("중지: " + e.getMessage()); }
            if (stage != Stage.IDLE) handler.postDelayed(this, 450);
        }
    };

    @Override protected void onServiceConnected() {
        instance = this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("export", "전사 추출 상태", NotificationManager.IMPORTANCE_LOW));
        Snapshot previous = readSnapshot(this);
        if (previous != null && previous.outcome() == Outcome.RUNNING) {
            getSharedPreferences("status", 0).edit().putString("run_outcome", Outcome.STOPPED.name())
                .putString("run_message", "도우미가 다시 연결돼 이전 실행을 종료했습니다. 저장된 파일은 그대로 확인할 수 있습니다.").apply();
        }
        status("접근성 연결됨 · 시작 버튼을 눌러 실행하세요.");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { stop("접근성 서비스가 중단됐습니다."); }
    @Override public void onDestroy() { stop("접근성 연결이 해제됐습니다."); instance = null; super.onDestroy(); }

    String start(boolean all, int maximum) {
        if (stage != Stage.IDLE) stop("이전 실행을 중지했습니다.");
        generation++; batch = all; limit = maximum; saved = skipped = scrolls = 0;
        visited.clear(); pendingTitle = pendingKey = null; lastCursor = ""; outsideSince = 0;
        Intent launch = getPackageManager().getLaunchIntentForPackage(RECORDER);
        if (launch == null) { status("삼성 음성녹음 앱이 설치돼 있지 않습니다."); return null; }
        runId = java.util.UUID.randomUUID().toString(); outcome = Outcome.RUNNING;
        stage = all ? Stage.SELECT : Stage.OPEN; openedAt = SystemClock.elapsedRealtime();
        deadline = openedAt + 60000; nextAction = openedAt + 1800;
        status("실행 중 · " + (all ? "현재 목록에서 최대 " + maximum + "개" : "현재 열려 있는 녹음"));
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch);
        handler.removeCallbacks(ticker); handler.postDelayed(ticker, 500);
        return runId;
    }

    void stop(String message) {
        if (!running()) return;
        terminate(message, Outcome.STOPPED);
    }
    private void complete(String message) {
        terminate(message, Outcome.COMPLETED);
        Intent result = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(result);
    }
    private void terminate(String message, Outcome result) {
        outcome = result;
        generation++; stage = Stage.IDLE; pendingTitle = pendingKey = null;
        handler.removeCallbacks(ticker); status(message);
    }
    static Snapshot readSnapshot(Context context) {
        var prefs = context.getSharedPreferences("status", 0);
        String id = prefs.getString("run_id", null);
        if (id == null) return null;
        Outcome state;
        try { state = Outcome.valueOf(prefs.getString("run_outcome", Outcome.STOPPED.name())); }
        catch (IllegalArgumentException invalid) { state = Outcome.STOPPED; }
        return new Snapshot(id, state, prefs.getInt("run_saved", 0), prefs.getInt("run_skipped", 0), prefs.getString("run_message", ""));
    }
    boolean running() { return stage != Stage.IDLE; }
    long receiptToken() { return stage == Stage.RECEIVING ? generation : -1; }
    String expectedTitle() { return stage == Stage.RECEIVING ? pendingTitle : null; }
    void received(long token, TranscriptStore.Receipt receipt) {
        if (token != generation || stage != Stage.RECEIVING) return;
        saved++;
        if (pendingKey != null) visited.add(pendingKey);
        status("저장 " + saved + "개 · " + receipt.segments() + "개 발화" + (receipt.duplicate() ? " (중복 파일 재사용)" : ""));
        if (!batch || visited.size() >= limit) { complete("완료 · 처리 " + saved + "개, 전사 없음 " + skipped + "개"); return; }
        transition(Stage.RETURN); deadline = SystemClock.elapsedRealtime() + 60000;
        Intent launch = getPackageManager().getLaunchIntentForPackage(RECORDER);
        if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); }
    }
    void failed(long token, String message) { if (token == generation && stage != Stage.IDLE) stop("수신 실패 · " + message); }

    private void step() {
        long now = SystemClock.elapsedRealtime();
        if (now > deadline) { stop("시간 초과 · 화면에서 공유를 완료하지 못했습니다. 저장 " + saved + "개"); return; }
        if (now < nextAction || stage == Stage.RECEIVING) return;
        if (getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) { stop("잠금 화면에서 중지했습니다."); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        String pkg = root.getPackageName().toString();
        boolean recorder = RECORDER.equals(pkg), chooser = pkg.equals("com.android.intentresolver") || pkg.equals("android");
        if (!recorder && !chooser && !pkg.equals(getPackageName())) {
            if (outsideSince == 0) outsideSince = now;
            if (now - outsideSince > 3000) stop("다른 앱으로 이동해 중지했습니다. 저장 " + saved + "개");
            return;
        }
        outsideSince = 0;
        if (chooser && (stage == Stage.SELECT || stage == Stage.OPEN || stage == Stage.RETURN)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            nextAction = now + 1100;
            return;
        }
        switch (stage) {
            case SELECT -> {
                if (!recorder) return;
                List<AccessibilityNodeInfo> rows = ids(root, "listRow_title");
                if (rows.isEmpty() || ids(root, "listRow_date").isEmpty()) { performGlobalAction(GLOBAL_ACTION_BACK); nextAction = now + 1200; return; }
                StringBuilder cursor = new StringBuilder();
                for (AccessibilityNodeInfo row : rows) {
                    String key = rowKey(row);
                    if (key == null) continue;
                    cursor.append(key).append('|');
                    if (!visited.contains(key)) {
                        pendingTitle = text(row); pendingKey = key;
                        transition(Stage.OPEN); openedAt = now; deadline = now + 60000;
                        if (!click(row)) stop("녹음 목록 항목을 열 수 없습니다.");
                        return;
                    }
                }
                if (scrolls >= 100) { stop("목록 스크롤 한도에 도달했습니다. 처리 " + saved + "개"); return; }
                if (lastCursor.equals(cursor.toString())) { complete("목록 끝 · 처리 " + saved + "개, 전사 없음 " + skipped + "개"); return; }
                lastCursor = cursor.toString(); scrolls++;
                List<AccessibilityNodeInfo> lists = ids(root, "list");
                AccessibilityNodeInfo scroll = lists.isEmpty() ? null : lists.get(0);
                boolean moved = scroll != null && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (!moved && scroll != null) {
                    Rect bounds = new Rect(); scroll.getBoundsInScreen(bounds);
                    if (bounds.width() > 100 && bounds.height() > 200) {
                        Path swipe = new Path();
                        float x = bounds.left + bounds.width() * 0.55f;
                        swipe.moveTo(x, bounds.top + bounds.height() * 0.78f);
                        swipe.lineTo(x, bounds.top + bounds.height() * 0.25f);
                        long token = generation;
                        moved = dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(swipe, 0, 400)).build(), new GestureResultCallback() {
                            @Override public void onCancelled(GestureDescription gesture) { if (token == generation && running()) stop("목록 스크롤이 취소돼 중지했습니다."); }
                        }, handler);
                    }
                }
                lastScrollDiagnostic = "listCount=" + lists.size() + " dispatched=" + moved;
                if (!moved) stop("스크롤 동작을 실행하지 못했습니다. 저장 " + saved + "개");
                nextAction = now + 1500;
            }
            case OPEN -> {
                if (!recorder) return;
                if (!ids(root, "stt_content").isEmpty() || !ids(root, "transcript_title").isEmpty()) {
                    List<AccessibilityNodeInfo> more = ids(root, "more_options");
                    AccessibilityNodeInfo rightmost = null; int right = -1;
                    for (AccessibilityNodeInfo node : more) { Rect rect = new Rect(); node.getBoundsInScreen(rect); if (rect.right > right) { right = rect.right; rightmost = node; } }
                    if (rightmost != null) { transition(Stage.MENU); if (!click(rightmost)) stop("녹음 메뉴를 열 수 없습니다."); }
                } else if (now - openedAt > 5000) {
                    if (!batch) { stop("열린 화면에 기존 전사가 없습니다. 전사가 있는 녹음을 먼저 여세요."); return; }
                    visited.add(pendingKey); skipped++;
                    if (visited.size() >= limit) complete("완료 · 처리 " + saved + "개, 전사 없음 " + skipped + "개");
                    else transition(Stage.RETURN);
                }
            }
            case MENU -> {
                if (!recorder) return;
                AccessibilityNodeInfo share = exact(root, "공유");
                if (share != null) { transition(Stage.FORMAT); if (!click(share)) stop("공유 메뉴를 누를 수 없습니다."); }
            }
            case FORMAT -> {
                if (!recorder) return;
                AccessibilityNodeInfo txt = exact(root, "텍스트 파일");
                if (txt != null) { transition(Stage.CHOOSER); if (!click(txt)) stop("TXT 공유를 선택할 수 없습니다."); }
            }
            case CHOOSER -> {
                if (!chooser) return;
                AccessibilityNodeInfo target = exact(root, "전사 JSON 저장");
                if (target != null) { transition(Stage.RECEIVING); if (!click(target)) stop("이 앱으로 공유할 수 없습니다."); return; }
                AccessibilityNodeInfo more = exact(root, "더보기");
                if (more != null) { click(more); nextAction = now + 1000; return; }
                for (AccessibilityNodeInfo node : descendants(root)) if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { nextAction = now + 1000; return; }
            }
            case RETURN -> {
                if (!recorder) return;
                if (!ids(root, "listRow_title").isEmpty() && !ids(root, "listRow_date").isEmpty()) { transition(Stage.SELECT); }
                else { performGlobalAction(GLOBAL_ACTION_BACK); nextAction = now + 1100; }
            }
            default -> { }
        }
    }

    private void transition(Stage next) {
        stage = next; nextAction = SystemClock.elapsedRealtime() + 850;
        String label = switch(next) {
            case SELECT -> "다음 녹음 선택";
            case OPEN -> "기존 전사 확인";
            case MENU -> "공유 메뉴 확인";
            case FORMAT -> "TXT 공유 선택";
            case CHOOSER -> "이 앱으로 받기";
            case RECEIVING -> "JSON 저장 대기";
            case RETURN -> "목록으로 돌아가기";
            default -> "대기";
        };
        status("진행 중 · " + label + " · 저장 " + saved + "개");
    }
    private void status(String message) {
        var edit = getSharedPreferences("status", 0).edit().putString("automation", message);
        if (runId != null) edit.putString("run_id", runId).putString("run_outcome", outcome.name())
            .putInt("run_saved", saved).putInt("run_skipped", skipped).putString("run_message", message);
        edit.apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder notification = new Notification.Builder(this, "export").setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("전사 JSON").setContentText(message).setContentIntent(open).setOngoing(running());
        if (running()) {
            PendingIntent stop = PendingIntent.getBroadcast(this, 1, new Intent(this, StopReceiver.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notification.addAction(new Notification.Action.Builder(null, "중지", stop).build());
        }
        try { manager.notify(1, notification.build()); } catch (SecurityException ignored) { }
    }
    private static String text(AccessibilityNodeInfo node) { return node.getText() == null ? "" : node.getText().toString().trim(); }
    private static List<AccessibilityNodeInfo> ids(AccessibilityNodeInfo root, String suffix) {
        List<AccessibilityNodeInfo> visible = new ArrayList<>();
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(RECORDER + ":id/" + suffix)) if (node.isVisibleToUser()) visible.add(node);
        return visible;
    }
    private static AccessibilityNodeInfo exact(AccessibilityNodeInfo root, String label) {
        for (AccessibilityNodeInfo node : descendants(root)) if (node.isVisibleToUser() && (label.equals(text(node)) || (node.getContentDescription() != null && label.equals(node.getContentDescription().toString().trim())))) return node;
        return null;
    }
    private static List<AccessibilityNodeInfo> descendants(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>(); nodes.add(root);
        for (int i = 0; i < nodes.size() && i < 2500; i++) for (int j = 0; j < nodes.get(i).getChildCount(); j++) { AccessibilityNodeInfo child = nodes.get(i).getChild(j); if (child != null) nodes.add(child); }
        return nodes;
    }
    private static boolean click(AccessibilityNodeInfo node) {
        for (int i = 0; node != null && i < 6; i++, node = node.getParent()) if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return false;
    }
    private static String rowKey(AccessibilityNodeInfo row) {
        AccessibilityNodeInfo parent = row;
        for (int i = 0; i < 4 && parent != null; i++, parent = parent.getParent()) {
            List<AccessibilityNodeInfo> dates = ids(parent, "listRow_date");
            if (dates.size() == 1) {
                List<AccessibilityNodeInfo> durations = ids(parent, "listRow_duration");
                return text(row) + "|" + text(dates.get(0)) + "|" + (durations.isEmpty() ? "" : text(durations.get(0)));
            }
        }
        return null;
    }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        writer.println("stage=" + stage + " saved=" + saved + " skipped=" + skipped + " visited=" + visited.size());
        writer.println("lastScroll=" + lastScrollDiagnostic);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) writer.println("package=" + root.getPackageName() + " rowTitles=" + ids(root,"listRow_title").size() + " rowDates=" + ids(root,"listRow_date").size());
        if (root != null) for (AccessibilityNodeInfo list : ids(root, "list")) writer.println("listScrollable=" + list.isScrollable() + " listActions=" + list.getActionList());
    }
}

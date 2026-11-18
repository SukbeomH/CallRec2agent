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
    static final String DRIVE = "com.google.android.apps.docs";
    static final String SHARE_LABEL = "전사 Drive 확인";
    static final String RECORDER = "com.sec.android.app.voicenote";
    static ExportService instance;
    enum Outcome { RUNNING, COMPLETED, STOPPED }
    record Snapshot(String id, Outcome outcome, int saved, int skipped, int unchanged, String message) {}
    private String runId;
    private Outcome outcome;
    private enum Stage { IDLE, PREPARING, SELECT, OPEN, MENU, FORMAT, CHOOSER, RECEIVING, DRIVE, SAVE, VERIFY, RETURN }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Stage stage = Stage.IDLE;
    private boolean batch;
    private int limit, saved, skipped, unchanged, scrolls, chooserAttempts;
    private long deadline, nextAction, openedAt, outsideSince;
    private String pendingTitle, pendingKey, lastCursor = "";
    private String lastScrollDiagnostic = "none";
    private final Set<String> visited = new HashSet<>();
    private long generation;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> task;
    private boolean checking, saveScreenLeft;
    private String shareName, sourceName, sourceHash, driveAccount;
    private Set<String> beforeSave = Set.of();
    private int absentFrames;

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
                .putString("run_message", "도우미가 다시 연결돼 이전 실행을 종료했습니다. 이미 저장된 파일은 유지됩니다. 확인되지 않은 마지막 TXT는 Drive에서 확인하세요.").apply();
        }
        status("접근성 연결됨 · 시작 버튼을 눌러 실행하세요.");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { stop("접근성 서비스가 중단됐습니다."); }
    @Override public void onDestroy() { stop("접근성 연결이 해제됐습니다."); instance = null; worker.shutdownNow(); super.onDestroy(); }

    String start(boolean all, int maximum) {
        if (stage != Stage.IDLE) stop("이전 실행을 중지했습니다.");
        generation++; batch = all; limit = maximum; saved = skipped = unchanged = scrolls = 0;
        visited.clear(); pendingTitle = pendingKey = null; lastCursor = ""; outsideSince = 0;
        Intent launch = getPackageManager().getLaunchIntentForPackage(RECORDER);
        if (launch == null) { status("삼성 음성녹음 앱이 설치돼 있지 않습니다."); return null; }
        runId = java.util.UUID.randomUUID().toString(); outcome = Outcome.RUNNING;
        stage = Stage.PREPARING; openedAt = SystemClock.elapsedRealtime();
        deadline = openedAt + 60000; nextAction = openedAt + 1800;
        status("실행 중 · " + (all ? "현재 목록에서 최대 " + maximum + "개" : "현재 열려 있는 녹음"));
        checking = true;
        long token = generation;
        task = worker.submit(() -> {
            try {
                new DriveFolder(this).prepareGuide();
                handler.post(() -> {
                    if (token != generation || !running()) return;
                    checking = false;
                    transition(batch ? Stage.SELECT : Stage.OPEN);
                    openedAt = SystemClock.elapsedRealtime(); deadline = openedAt + 60000;
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch);
                });
            } catch (Exception failure) { handler.post(() -> { if (token == generation) stop("Drive 준비 실패 · " + failure.getMessage()); }); }
        });
        status("Drive 안내문 준비 및 읽기 확인 중…");
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
        generation++; if (task != null) task.cancel(true); checking = false; stage = Stage.IDLE; pendingTitle = pendingKey = null;
        handler.removeCallbacks(ticker); status(message);
    }
    static Snapshot readSnapshot(Context context) {
        var prefs = context.getSharedPreferences("status", 0);
        String id = prefs.getString("run_id", null);
        if (id == null) return null;
        Outcome state;
        try { state = Outcome.valueOf(prefs.getString("run_outcome", Outcome.STOPPED.name())); }
        catch (IllegalArgumentException invalid) { state = Outcome.STOPPED; }
        return new Snapshot(id, state, prefs.getInt("run_saved", 0), prefs.getInt("run_skipped", 0), prefs.getInt("run_unchanged", 0), prefs.getString("run_message", ""));
    }
    boolean running() { return stage != Stage.IDLE; }
    long shareToken() { return stage == Stage.RECEIVING ? generation : -1; }
    void shareFailed(long token, String message) { if (token == generation && running()) stop("TXT 확인·전송 중지 · " + message); }
    void reviewShare(long token, android.net.Uri uri, java.util.function.Consumer<Boolean> done) {
        if (token != generation || stage != Stage.RECEIVING || checking) { done.accept(false); return; }
        checking = true; deadline = SystemClock.elapsedRealtime() + 300000;
        String expected = pendingTitle;
        task = worker.submit(() -> {
            try {
                DriveFolder folder = new DriveFolder(this);
                DriveFolder.Source source = folder.inspect(uri);
                if (!DirectShare.matchesRecording(source.name(), expected)) throw new IllegalArgumentException("공유된 TXT가 선택한 녹음과 다릅니다.");
                DriveFolder.Receipt existing = folder.findExisting(source.name(), source.sha256(), new TransferHistory(this).preferredDocument(source.name()));
                handler.post(() -> {
                    if (generation != token || stage != Stage.RECEIVING) { done.accept(false); return; }
                    checking = false; sourceName = source.name(); sourceHash = source.sha256();
                    if (existing != null) { done.accept(false); unchanged(existing); }
                    else { transition(Stage.DRIVE); deadline = SystemClock.elapsedRealtime() + 300000; done.accept(true); }
                });
            } catch (Exception failure) { handler.post(() -> { if (token == generation) shareFailed(token, failure.getMessage()); done.accept(false); }); }
        });
    }
    private String counts() { return "새로 저장 " + saved + "개 · 변경 없음 " + unchanged + "개 · 전사 없음 " + skipped + "개"; }
    private void unchanged(DriveFolder.Receipt receipt) {
        new TransferHistory(this).remember(sourceName, sourceHash, receipt);
        unchanged++; status("같은 내용의 Drive TXT 확인 · " + counts()); advance();
    }
    private void received(DriveFolder.Receipt receipt) {
        new TransferHistory(this).remember(sourceName, sourceHash, receipt);
        saved++;
        getSharedPreferences("drive", 0).edit().putString("confirmed_account", driveAccount)
            .putString("last_file", receipt.name()).putString("last_uri", receipt.uri())
            .putString("last_sha256", receipt.sha256()).putInt("last_bytes", receipt.bytes()).apply();
        advance();
    }
    private void advance() {
        if (pendingKey != null) visited.add(pendingKey);
        if (!batch || visited.size() >= limit) { complete("완료 · " + counts()); return; }
        transition(Stage.RETURN); deadline = SystemClock.elapsedRealtime() + 60000;
        Intent launch = getPackageManager().getLaunchIntentForPackage(RECORDER);
        if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); }
    }
    private record SaveScreen(String name, AccessibilityNodeInfo save, String account, String folder) {}
    private SaveScreen driveScreen(AccessibilityNodeInfo root) {
        if (root == null || !DRIVE.equals(String.valueOf(root.getPackageName()))) return null;
        AccessibilityNodeInfo name = driveId(root, "multiline_title"), save = driveId(root, "save_button");
        AccessibilityNodeInfo account = driveId(root, "upload_account_autocomplete"), folder = driveId(root, "upload_folder_autocomplete");
        if (name == null || save == null || account == null || folder == null || !("업로드".equals(text(save)) || "저장".equals(text(save)))) return null;
        String fileName = DirectShare.uploadName(text(name));
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) return null;
        java.util.regex.Matcher email = java.util.regex.Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}").matcher(text(account));
        if (!email.find()) return null;
        return new SaveScreen(fileName, save, email.group(), DirectShare.folderName(text(folder)));
    }
    private static AccessibilityNodeInfo driveId(AccessibilityNodeInfo root, String id) {
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(DRIVE + ":id/" + id)) if (node.isVisibleToUser()) return node;
        return null;
    }
    private boolean folderMatches(SaveScreen screen) {
        return screen != null && screen.folder().equals(getSharedPreferences("drive", 0).getString("folder_name", null));
    }
    private void verify() {
        if (checking || !saveScreenLeft) return;
        checking = true; long token = generation; String name = shareName;
        nextAction = SystemClock.elapsedRealtime() + 1200;
        task = worker.submit(() -> {
            try {
                DriveFolder.Receipt receipt = new DriveFolder(this).verify(name, beforeSave, sourceHash);
                handler.post(() -> {
                    if (generation != token || stage != Stage.VERIFY) return;
                    checking = false;
                    if (receipt != null) received(receipt);
                });
            } catch (Exception failure) { handler.post(() -> { if (generation == token) stop("저장 확인 실패 · " + failure.getMessage()); }); }
        });
    }

    private void step() {
        long now = SystemClock.elapsedRealtime();
        if (now > deadline) { stop("시간 초과 · 마지막 TXT의 저장을 확인하지 못했습니다. 재시도 전에 Drive를 확인하세요. 확인된 파일 " + saved + "개"); return; }
        if (now < nextAction || stage == Stage.PREPARING) return;
        if (getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) { stop("잠금 화면에서 중지했습니다."); return; }
        if (stage == Stage.RECEIVING) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        String pkg = root.getPackageName().toString();
        boolean recorder = RECORDER.equals(pkg), chooser = pkg.equals("com.android.intentresolver") || pkg.equals("android");
        if (pkg.equals("com.android.systemui") && (stage == Stage.DRIVE || stage == Stage.SAVE || stage == Stage.VERIFY)) { outsideSince = 0; return; }
        if (!recorder && !chooser && !pkg.equals(getPackageName()) && !DRIVE.equals(pkg)) {
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
                if (lastCursor.equals(cursor.toString())) { complete("목록 끝 · " + counts()); return; }
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
                    skipped++; advance();
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
                if (DRIVE.equals(pkg)) { stop("중복 확인을 위해 공유 대상 ‘" + SHARE_LABEL + "’를 선택해야 합니다."); return; }
                if (!chooser) return;
                AccessibilityNodeInfo target = null;
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(SHARE_LABEL))
                    if (node.isVisibleToUser() && DirectShare.isAppLabel(text(node), node.getViewIdResourceName(), SHARE_LABEL)) { target = node; break; }
                if (target != null) {
                    if (++chooserAttempts > 3) { stop("TXT 검사 화면으로 이동하지 못했습니다."); return; }
                    shareName = sourceName = sourceHash = driveAccount = null; saveScreenLeft = false; absentFrames = 0;
                    transition(Stage.RECEIVING); deadline = now + 300000;
                    if (!click(target)) stop("TXT 확인 항목을 누를 수 없습니다.");
                    nextAction = now + 1800; return;
                }
                AccessibilityNodeInfo more = exact(root, "더보기");
                if (more != null) { click(more); nextAction = now + 1000; return; }
                for (AccessibilityNodeInfo node : descendants(root)) if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { nextAction = now + 1000; return; }
            }
            case DRIVE -> {
                SaveScreen screen = driveScreen(root);
                if (screen == null || checking) return;
                if (!folderMatches(screen)) { status("Drive 저장 위치를 선택한 폴더로 바꿔 주세요. 폴더가 일치하면 계속합니다."); return; }
                if (!screen.name().equals(sourceName)) { stop("공유된 TXT 이름이 선택한 녹음과 다릅니다."); return; }
                shareName = screen.name(); driveAccount = screen.account(); checking = true; long token = generation;
                task = worker.submit(() -> {
                    try {
                        DriveFolder folder = new DriveFolder(this);
                        DriveFolder.Receipt existing = folder.findExisting(sourceName, sourceHash, new TransferHistory(this).preferredDocument(sourceName));
                        Set<String> before = existing == null ? folder.snapshot() : Set.of();
                        handler.post(() -> {
                            if (token != generation || stage != Stage.DRIVE) return;
                            checking = false;
                            if (existing != null) {
                                AccessibilityNodeInfo currentRoot = getRootInActiveWindow();
                                SaveScreen current = driveScreen(currentRoot);
                                if (current == null || !folderMatches(current) || !sourceName.equals(current.name())) { stop("중복 확인 중 Drive 화면이 바뀌었습니다."); return; }
                                AccessibilityNodeInfo cancel = exact(currentRoot, "취소");
                                if (cancel == null || !click(cancel)) { stop("같은 TXT가 발견됐습니다. Drive 업로드 화면을 취소한 뒤 다시 시작하세요."); return; }
                                unchanged(existing);
                            } else { beforeSave = before; transition(Stage.SAVE); }
                        });
                    } catch (Exception failure) { handler.post(() -> { if (token == generation) stop("Drive 저장 전 확인 실패 · " + failure.getMessage()); }); }
                });
            }
            case SAVE -> {
                SaveScreen screen = driveScreen(root);
                if (screen == null) {
                    if (++absentFrames >= 3 && shareName != null) { saveScreenLeft = true; transition(Stage.VERIFY); deadline = now + 90000; }
                    return;
                }
                absentFrames = 0;
                if (!shareName.equals(screen.name()) || !folderMatches(screen) || !driveAccount.equals(screen.account())) {
                    stop("Drive의 파일 이름·계정·폴더가 바뀌어 중지했습니다. 다시 시작하세요."); return;
                }
                String confirmed = getSharedPreferences("drive", 0).getString("confirmed_account", null);
                if (!DirectShare.maySave(screen.folder(), screen.account(), getSharedPreferences("drive", 0).getString("folder_name", null), confirmed)) {
                    status("처음 한 번: Drive 계정·폴더를 확인하고 ‘업로드’를 눌러 주세요. 다음부터 자동 저장합니다."); return;
                }
                transition(Stage.VERIFY); deadline = now + 90000;
                if (!click(screen.save())) stop("Drive 업로드 버튼을 누를 수 없습니다.");
            }
            case VERIFY -> {
                if (driveScreen(root) == null) { if (++absentFrames >= 3) saveScreenLeft = true; }
                else { absentFrames = 0; }
                verify();
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
        stage = next; if (next == Stage.CHOOSER) chooserAttempts = 0; nextAction = SystemClock.elapsedRealtime() + (next == Stage.CHOOSER ? 2800 : 850);
        String label = switch(next) {
            case SELECT -> "다음 녹음 선택";
            case OPEN -> "기존 전사 확인";
            case MENU -> "공유 메뉴 확인";
            case FORMAT -> "TXT 공유 선택";
            case CHOOSER -> "TXT 확인 도우미로 공유";
            case RECEIVING -> "기존 Drive TXT와 내용 비교";
            case DRIVE -> "Drive 저장 위치 확인";
            case SAVE -> "Drive 저장 확인";
            case VERIFY -> "선택한 Drive 폴더에서 TXT 다시 읽기";
            case PREPARING -> "llm.txt 준비";
            case RETURN -> "목록으로 돌아가기";
            default -> "대기";
        };
        status("진행 중 · " + label + " · " + counts());
    }
    private void status(String message) {
        var edit = getSharedPreferences("status", 0).edit().putString("automation", message);
        if (runId != null) edit.putString("run_id", runId).putString("run_outcome", outcome.name())
            .putInt("run_saved", saved).putInt("run_skipped", skipped).putInt("run_unchanged", unchanged).putString("run_message", message);
        edit.apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder notification = new Notification.Builder(this, "export").setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("전사 Drive").setContentText(message).setContentIntent(open).setOngoing(running());
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
        writer.println("stage=" + stage + " saved=" + saved + " skipped=" + skipped + " unchanged=" + unchanged + " visited=" + visited.size());
        writer.println("lastScroll=" + lastScrollDiagnostic);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) writer.println("package=" + root.getPackageName() + " rowTitles=" + ids(root,"listRow_title").size() + " rowDates=" + ids(root,"listRow_date").size());
        if (root != null && DRIVE.equals(String.valueOf(root.getPackageName()))) {
            SaveScreen screen = driveScreen(root);
            writer.println("driveSaveScreen=" + (screen != null) + " folderMatches=" + folderMatches(screen) + " checking=" + checking + " saveScreenLeft=" + saveScreenLeft + " beforeCount=" + beforeSave.size());
        }
        if (root != null && args != null && java.util.Arrays.asList(args).contains("ui") && (DRIVE.equals(String.valueOf(root.getPackageName())) || "com.android.intentresolver".equals(String.valueOf(root.getPackageName())))) {
            for (AccessibilityNodeInfo node : descendants(root)) if (node.isVisibleToUser() && (!text(node).isEmpty() || node.getContentDescription() != null)) {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                writer.println("node " + node.getViewIdResourceName() + " text=" + text(node) + " desc=" + node.getContentDescription() + " bounds=" + bounds.toShortString() + " clickable=" + node.isClickable());
            }
        }
        if (root != null) for (AccessibilityNodeInfo list : ids(root, "list")) writer.println("listScrollable=" + list.isScrollable() + " listActions=" + list.getActionList());
    }
}

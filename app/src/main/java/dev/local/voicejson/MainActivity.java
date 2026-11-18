package dev.local.voicejson;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private enum Step { PREPARE, CHOOSE, READY, RUNNING, RESULTS }
    private static final int INK = 0xff172235, MUTED = 0xff63718a, BLUE = 0xff315dcd, BACKGROUND = 0xfff5f7fb;
    private Step step = Step.PREPARE, afterPrepare = Step.CHOOSE;
    private boolean batch, recorderOpened;
    private int maximum = 10;
    private String watchedRun;
    private SharedPreferences preferences;
    private LinearLayout body, footer;
    private EditText countInput;
    private TextView liveStatus;
    private static final int PICK_DRIVE = 204;
    private boolean settingFolder;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { sync(); handler.postDelayed(this, 600); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("wizard", MODE_PRIVATE);
        try { step = Step.valueOf(preferences.getString("step", Step.PREPARE.name())); }
        catch (IllegalArgumentException invalid) { step = Step.PREPARE; }
        try { afterPrepare = Step.valueOf(preferences.getString("after_prepare", Step.CHOOSE.name())); }
        catch (IllegalArgumentException invalid) { afterPrepare = Step.CHOOSE; }
        batch = preferences.getBoolean("batch", false);
        maximum = preferences.getInt("maximum", 10);
        recorderOpened = preferences.getBoolean("recorder_opened", false);
        watchedRun = preferences.getString("watched_run", null);
        if (step == Step.PREPARE && ready()) step = Step.CHOOSE;
        if (!preferences.getBoolean("direct_txt_v1", false)) { watchedRun = null; recorderOpened = false; step = Step.PREPARE; preferences.edit().putBoolean("direct_txt_v1", true).apply(); }
        reconcileRun();
        render();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        reconcileRun(); render();
    }
    @Override protected void onResume() {
        super.onResume(); reconcileRun();
        if (step == Step.PREPARE && ready()) step = afterPrepare;
        render(); handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); persist(); super.onPause(); }
    private void persist() {
        if (countInput != null && step == Step.CHOOSE) {
            try { int n = Integer.parseInt(countInput.getText().toString()); if (n >= 1 && n <= 500) maximum = n; }
            catch (NumberFormatException ignored) { }
        }
        preferences.edit().putString("step", step.name()).putString("after_prepare", afterPrepare.name()).putBoolean("batch", batch).putInt("maximum", maximum)
            .putBoolean("recorder_opened", recorderOpened).putString("watched_run", watchedRun).apply();
    }
    private boolean ready() { return ExportService.instance != null
        && getPackageManager().getLaunchIntentForPackage(ExportService.RECORDER) != null
        && getPackageManager().getLaunchIntentForPackage(ExportService.DRIVE) != null
        && getSharedPreferences("drive", 0).getString("tree", null) != null; }
    private void reconcileRun() {
        ExportService.Snapshot session = ExportService.readSnapshot(this);
        if (watchedRun == null || session == null || !watchedRun.equals(session.id())) return;
        step = session.outcome() == ExportService.Outcome.RUNNING ? Step.RUNNING : Step.RESULTS;
    }
    private void sync() {
        if (step == Step.PREPARE && ready()) { go(afterPrepare); return; }
        if (step == Step.RUNNING) {
            Step before = step; reconcileRun();
            if (before != step) { render(); return; }
            ExportService.Snapshot session = ExportService.readSnapshot(this);
            if (session != null && liveStatus != null) liveStatus.setText(session.message());
        }
    }
    private void go(Step next) { hideKeyboard(); step = next; persist(); render(); }
    private void back() {
        if (step == Step.RUNNING) {
            new AlertDialog.Builder(this).setTitle("전송을 중지할까요?").setMessage("Drive에 저장한 TXT는 남습니다. 확인 중이던 마지막 파일은 Drive에서 확인하세요.")
                .setPositiveButton("중지", (d,w) -> stop()).setNegativeButton("계속 전송", null).show();
        } else if (step == Step.READY) go(Step.CHOOSE);
        else if (step == Step.RESULTS) reset();
        else finish();
    }
    private void reset() { watchedRun = null; recorderOpened = false; go(ready() ? Step.CHOOSE : Step.PREPARE); }

    private void render() {
        countInput = null; liveStatus = null;
        LinearLayout root = column(); root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            var keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(12) + Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("전사 Drive", 18, true, INK); brand.setGravity(Gravity.CENTER_VERTICAL); header.addView(brand, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button help = button("사용법", false, this::manual); help.setTextSize(14); header.addView(help, new LinearLayout.LayoutParams(dp(90), dp(46)));
        root.addView(header); space(root, 12);
        LinearLayout progress = new LinearLayout(this); String[] labels = {"준비", "선택", "전송", "결과"};
        int active = switch(step) { case PREPARE -> 0; case CHOOSE -> 1; case READY, RUNNING -> 2; case RESULTS -> 3; };
        for (int i = 0; i < 4; i++) {
            TextView item = text((i < active ? "✓ " : (i + 1) + " ") + labels[i], 13, i == active, i <= active ? BLUE : MUTED);
            item.setGravity(Gravity.CENTER); item.setBackground(shape(i == active ? 0xffe7edff : Color.TRANSPARENT, Color.TRANSPARENT, 12));
            item.setContentDescription((i + 1) + "단계 " + labels[i] + (i == active ? ", 현재 단계" : ""));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1); p.setMargins(dp(2),0,dp(2),0); progress.addView(item,p);
        }
        root.addView(progress); space(root, 16);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        body = column(); body.setPadding(0, dp(4), 0, dp(12)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        footer = column(); footer.setPadding(0,dp(10),0,0); root.addView(footer);
        switch(step) {
            case PREPARE -> prepare();
            case CHOOSE -> choose();
            case READY -> readyScreen();
            case RUNNING -> runningScreen();
            case RESULTS -> results();
        }
        setContentView(root); root.requestApplyInsets();
        getWindow().getInsetsController().setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        persist();
    }

    private void prepare() {
        heading("처음 한 번만 준비해요", "공유 도우미를 켜면, 이후에는 PC 연결 없이 폰에서 사용할 수 있어요.");
        boolean installed = getPackageManager().getLaunchIntentForPackage(ExportService.RECORDER) != null;
        LinearLayout recorder = card(); recorder.addView(text(installed ? "✓ 삼성 음성녹음 확인됨" : "삼성 음성녹음이 필요해요", 17,true,INK));
        if (!installed) paragraph(recorder,"삼성 음성녹음 앱을 설치한 뒤 다시 열어 주세요.");
        LinearLayout access = card(); access.addView(text("공유 도우미 켜기",19,true,INK));
        paragraph(access,"설정에서 ‘전사 Drive 공유 도우미’를 켜세요. 사용자가 시작한 동안 화면의 공유 버튼을 누르고 녹음 목록을 스크롤합니다.");
        paragraph(access,"접근성 권한과 Drive 폴더를 준비하면 다음 단계로 이동합니다. ‘제한된 설정’이 표시되면 앱 정보에서 ‘제한된 설정 허용’을 직접 선택하세요.");
        action(access,"접근성 설정 열기",true,() -> { persist(); startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); });
        driveCard();
        note("기존 전사 TXT를 삼성 앱에서 바로 Drive로 보냅니다. Drive 앱과 인터넷 연결이 필요합니다. llm.txt는 시작할 때 같은 폴더에 자동 준비됩니다.");
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            action(body,"진행·중지 알림 허용",false,() -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1));
        action(footer,"준비 상태 다시 확인",true,() -> { if (ready()) go(afterPrepare); else new AlertDialog.Builder(this).setMessage("접근성 설정에서 ‘전사 Drive 공유 도우미’가 켜졌는지 확인해 주세요.").setPositiveButton("확인",null).show(); });
    }
    private void choose() {
        heading("어떤 TXT를 보낼까요?", "삼성 음성녹음의 기존 전사를 Drive로 바로 공유합니다.");
        modeCard("한 건만 저장", "열어 둔 녹음 하나의 전사 TXT를 Drive에 저장합니다.", false);
        modeCard("여러 건 순서대로 저장", "현재 녹음 목록 위치부터 아래로 진행합니다.", true);
        if (batch) {
            LinearLayout card = card(); card.addView(text("최대 몇 건을 처리할까요?",17,true,INK));
            countInput = new EditText(this); countInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); countInput.setSingleLine(true);
            countInput.setText(String.valueOf(maximum)); countInput.setSelectAllOnFocus(true); countInput.setTextColor(INK); countInput.setTextSize(24);
            countInput.setContentDescription("최대 녹음 수, 1부터 500까지"); card.addView(countInput,new LinearLayout.LayoutParams(-1,dp(58)));
            paragraph(card,"1~500건 · 전사가 없는 녹음은 건너뜁니다.");
        }
        driveCard();
        if (ExportService.readSnapshot(this) != null) action(body, "최근 결과 보기", false, () -> {
            watchedRun = ExportService.readSnapshot(this).id(); go(Step.RESULTS);
        });
        action(footer,"다음 · 녹음 준비",true,() -> {
            if (batch) {
                try { int n=Integer.parseInt(countInput.getText().toString()); if(n<1||n>500) throw new NumberFormatException(); maximum=n; }
                catch(NumberFormatException invalid) { countInput.setError("1~500 사이의 수를 입력하세요."); countInput.requestFocus(); return; }
            }
            recorderOpened=false; go(Step.READY);
        });
    }
    private void modeCard(String title,String description,boolean value) {
        LinearLayout card=card(); boolean selected=batch==value;
        card.setBackground(shape(selected?0xffedf2ff:Color.WHITE,selected?BLUE:0xffe0e6ef,18));
        card.addView(text((selected?"● ":"○ ")+title,18,true,selected?BLUE:INK)); paragraph(card,description);
        card.setFocusable(true); card.setClickable(true); card.setContentDescription(title+(selected?", 선택됨":""));
        card.setOnClickListener(v -> { persist(); batch=value; recorderOpened=false; render(); });
    }
    private void readyScreen() {
        heading(recorderOpened?"이제 전송을 시작해요":"삼성 앱에서 녹음을 준비해요", batch?"목록에서 최대 "+maximum+"건을 처리합니다.":"열어 둔 녹음 한 건을 처리합니다.");
        LinearLayout instructions=card();
        instruction(instructions,"1",batch?"시작할 목록 위치로 이동":"전사가 있는 녹음 열기",batch?
            "삼성 음성녹음에서 전송을 시작할 위치로 이동하세요. 처음부터 하려면 목록 맨 위로 올립니다.":
            "삼성 음성녹음에서 저장할 녹음을 누르고, 발화자와 전사 본문이 보이는 화면을 열어 두세요.");
        instruction(instructions,"2","이 앱으로 돌아오기","휴대폰의 최근 앱 화면에서 ‘전사 Drive’를 선택하세요. 돌아오는 것만으로 전송이 시작되지는 않습니다.");
        action(instructions,recorderOpened?"삼성 음성녹음 다시 열기":"삼성 음성녹음 열기",false,this::openRecorder);
        note("Drive 최초 연결: 저장 화면에서 선택한 것과 같은 계정·폴더를 고르고 ‘업로드’를 누릅니다. 저장 확인 후에는 이 계정과 폴더에서 자동으로 저장합니다.");
        if(recorderOpened) note("준비한 화면이 맞으면 아래 ‘전송 시작’을 누르세요. 시작하면 도우미가 삼성 앱으로 이동합니다.");
        else note("먼저 위 버튼으로 삼성 앱에서 녹음을 준비하세요. 돌아오면 시작 버튼이 활성화됩니다.");
        LinearLayout conditions=card(); conditions.addView(text("전송하는 동안",17,true,INK));
        paragraph(conditions,"화면 잠금을 풀어 두고 다른 앱을 사용하지 마세요. 중지하려면 알림의 ‘중지’ 또는 이 앱의 ‘전송 중지’를 누릅니다.");
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            action(conditions,"중지 버튼이 있는 알림 허용",false,()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1));
        Button start=action(footer,"전송 시작",true,this::startExport); start.setEnabled(recorderOpened); start.setAlpha(recorderOpened?1f:0.45f);
        action(footer,"이전 · 전송 방법 변경",false,()->go(Step.CHOOSE));
    }
    private void openRecorder() {
        Intent launch=getPackageManager().getLaunchIntentForPackage(ExportService.RECORDER);
        if(launch==null) { afterPrepare=Step.READY; go(Step.PREPARE); return; }
        recorderOpened=true; persist(); startActivity(launch);
    }
    private void startExport() {
        if(!ready()) { afterPrepare=Step.READY; go(Step.PREPARE); return; }
        watchedRun=ExportService.instance.start(batch,batch?maximum:1);
        if(watchedRun!=null) { step=Step.RUNNING; persist(); render(); }
    }
    private void runningScreen() {
        heading("전사를 저장하고 있어요", "삼성 앱의 공유 화면을 도우미가 조작합니다.");
        LinearLayout progress=card(); liveStatus=text("전송 상태 확인 중…",20,true,BLUE); progress.addView(liveStatus);
        ExportService.Snapshot session=ExportService.readSnapshot(this); if(session!=null)liveStatus.setText(session.message());
        paragraph(progress,"완료하면 이 화면으로 돌아와 결과를 보여 드립니다. 선택한 Drive 폴더에서 새 TXT를 다시 읽어 확인한 뒤 다음 녹음으로 넘어갑니다. 처음 한 건은 Drive 계정·폴더를 확인하고 업로드를 한 번 눌러 주세요.");
        note("화면 잠금을 풀어 두세요. 중지해도 지금까지 저장한 파일은 남아 있습니다.");
        action(body,"삼성 앱의 진행 화면 보기",false,()->{ Intent launch=getPackageManager().getLaunchIntentForPackage(ExportService.RECORDER); if(launch!=null)startActivity(launch); });
        action(footer,"전송 중지",true,this::stop);
    }
    private void stop() { if(ExportService.instance!=null)ExportService.instance.stop("사용자가 중지했습니다."); reconcileRun(); render(); }
    private void results() {
        ExportService.Snapshot session = ExportService.readSnapshot(this);
        boolean matched = session != null && watchedRun != null && watchedRun.equals(session.id());
        boolean complete = matched && session.outcome() == ExportService.Outcome.COMPLETED;
        heading(complete ? "Drive TXT 저장을 확인했어요" : "최근 실행 결과", "선택한 폴더에서 TXT를 다시 읽은 파일만 집계합니다.");
        if (matched) {
            LinearLayout summary = card(); summary.addView(text("저장 확인 " + session.saved() + "건", 24, true, BLUE));
            paragraph(summary, "전사 없음 " + session.skipped() + "건"); paragraph(summary, session.message());
        }
        var drive = getSharedPreferences("drive", 0);
        LinearLayout last = card(); last.addView(text("마지막으로 확인한 TXT", 17, true, INK));
        paragraph(last, drive.getString("last_file", "아직 확인한 TXT가 없습니다."));
        if (drive.contains("last_file")) paragraph(last, drive.getInt("last_bytes", 0) + "바이트 · SHA-256 읽기 확인");
        note("이 확인은 Drive 앱이 제공하는 파일을 읽은 결과입니다. Wi-Fi 대기 등으로 클라우드 업로드가 남아 있을 수 있습니다. 에이전트에서 안 보이면 Drive 앱의 업로드 상태를 확인하세요.");
        driveCard();
        action(body, "Drive 폴더 열기", false, this::openFolder);
        action(footer, "다른 녹음 보내기", true, this::reset);
    }
    private void driveCard() {
        LinearLayout drive = card(); drive.addView(text("Drive 저장 위치", 19, true, INK));
        var prefs = getSharedPreferences("drive", 0);
        paragraph(drive, prefs.getString("tree", null) == null ? "저장 확인에 사용할 Drive 폴더를 선택하세요." : "선택한 폴더: " + prefs.getString("folder_name", ""));
        String account = prefs.getString("confirmed_account", null);
        paragraph(drive, account == null ? "첫 한 건은 Drive에서 계정·폴더를 확인하고 직접 저장합니다. 이후 같은 대상으로 자동 저장합니다." : "자동 저장 확인 계정: " + account);
        paragraph(drive, "에이전트의 Drive 연결에서도 접근할 수 있는 전용 폴더를 선택하세요. llm.txt부터 읽고 관련 TXT를 열도록 요청하면 됩니다.");
        action(drive, "Drive 폴더 선택·변경", false, this::pickDrive);
        action(drive, "에이전트 프롬프트 복사", true, this::copyAgentPrompt);
        action(drive, "Drive 폴더 링크 설정", false, this::setFolderLink);
        action(drive, "llm.txt 안내문 보기", false, () -> {
            try (var input = getAssets().open("llm.txt")) { preview(new String(input.readAllBytes(), StandardCharsets.UTF_8), "llm.txt 안내문"); }
            catch (Exception failure) { message("안내문을 열 수 없습니다.", failure.getMessage()); }
        });
    }
    private void pickDrive() {
        if (settingFolder) return;
        new AlertDialog.Builder(this).setTitle("Drive 저장 폴더")
            .setItems(new String[]{"기존 폴더 선택", "하위 폴더 생성·선택"}, (dialog, which) -> {
                if (which == 0) { preferences.edit().remove("new_folder_name").apply(); openDrivePicker(); }
                else {
                    EditText name = new EditText(this); name.setSingleLine(true); name.setText("CallRec2agent"); name.setSelectAllOnFocus(true);
                    AlertDialog input = new AlertDialog.Builder(this).setTitle("새 폴더 이름")
                        .setMessage("다음 화면에서 상위 Drive 폴더를 고릅니다. 이미 같은 이름의 폴더를 선택하면 그대로 사용하고, 상위 위치에 같은 이름의 하위 폴더가 없으면 만듭니다.")
                        .setView(name).setPositiveButton("상위 폴더 선택", null).setNegativeButton("취소", null).create();
                    input.setOnShowListener(d -> input.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        String value = name.getText().toString().trim();
                        if (!DirectShare.validFolderName(value)) {
                            name.setError("100자 이내의 폴더 이름을 입력하세요. 슬래시와 제어 문자는 사용할 수 없습니다."); return;
                        }
                        preferences.edit().putString("new_folder_name", value).apply(); input.dismiss(); openDrivePicker();
                    })); input.show();
                }
            }).setNegativeButton("취소", null).show();
    }
    private void openDrivePicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        var drive = getSharedPreferences("drive", 0);
        String previous = drive.getString("tree", null);
        if (previous != null) {
            Uri tree = Uri.parse(previous);
            pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(tree, drive.getString("folder_id", DocumentsContract.getTreeDocumentId(tree))));
        }
        startActivityForResult(pick, PICK_DRIVE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_DRIVE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        var provider = getPackageManager().resolveContentProvider(tree.getAuthority(), 0);
        if (!DocumentsContract.isTreeUri(tree) || provider == null || !ExportService.DRIVE.equals(provider.packageName)) {
            message("Drive 폴더를 선택해 주세요.", "왼쪽 메뉴에서 Drive 계정을 사용하세요."); return;
        }
        int permission = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if ((data.getFlags() & permission) != permission) { message("읽기·쓰기 권한이 필요해요.", "Drive 폴더를 다시 선택하세요."); return; }
        String child = preferences.getString("new_folder_name", null);
        preferences.edit().remove("new_folder_name").apply();
        try { getContentResolver().takePersistableUriPermission(tree, permission); }
        catch (SecurityException failure) { message("폴더 권한을 보관하지 못했어요.", failure.getMessage()); return; }
        settingFolder = true;
        TextView progress = text("Drive 폴더 확인·준비 중…", 20, true, BLUE); body.addView(progress);
        new Thread(() -> {
            try {
                String folderId = DocumentsContract.getTreeDocumentId(tree), name = child;
                String parentName;
                try (var cursor = getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, folderId), new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("선택한 폴더 이름을 읽을 수 없습니다.");
                    parentName = cursor.getString(0);
                }
                boolean selectedTarget = child == null || child.equals(parentName);
                if (!selectedTarget) folderId = DriveFolder.ensureChild(this, tree, folderId, child);
                else name = parentName;
                String selectedId = folderId, selectedName = name, location = selectedTarget ? parentName : parentName + " / " + child;
                runOnUiThread(() -> { settingFolder = false; applyFolder(tree, selectedId, selectedName, location); });
            } catch (Exception failure) { runOnUiThread(() -> { settingFolder = false; message("폴더를 준비하지 못했어요.", failure.getMessage()); render(); }); }
        }, "drive-folder-setup").start();
    }
    private void applyFolder(Uri tree, String folderId, String name, String location) {
        var prefs = getSharedPreferences("drive", 0);
        String old = prefs.getString("tree", null);
        String oldId = prefs.getString("folder_id", old == null ? null : DocumentsContract.getTreeDocumentId(Uri.parse(old)));
        if (folderId.equals(oldId) && location.equals(name)) location = prefs.getString("folder_location", location);
        var edit = prefs.edit().putString("tree", tree.toString()).putString("folder_id", folderId).putString("folder_name", name).putString("folder_location", location).remove("last_result");
        if (!folderId.equals(oldId)) edit.remove("folder_link").remove("confirmed_account").remove("last_file").remove("last_uri").remove("last_bytes").remove("last_sha256");
        edit.apply();
        if (old != null && !old.equals(tree.toString())) try {
            for (var grant : getContentResolver().getPersistedUriPermissions()) if (grant.getUri().toString().equals(old))
                getContentResolver().releasePersistableUriPermission(grant.getUri(), (grant.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0) | (grant.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
        } catch (SecurityException ignored) { }
        watchedRun = null; recorderOpened = false; step = ready() ? Step.CHOOSE : Step.PREPARE; render();
    }
    private void copyAgentPrompt() {
        var prefs = getSharedPreferences("drive", 0);
        if (prefs.getString("tree", null) == null) { message("먼저 Drive 폴더를 선택하세요.", "저장 위치를 지정하면 해당 폴더용 프롬프트를 만들 수 있습니다."); return; }
        String account = prefs.getString("confirmed_account", null);
        String location = prefs.getString("folder_location", prefs.getString("folder_name", ""));
        String prompt = AgentPrompt.build(account, location, prefs.getString("folder_link", null), prefs.getString("last_file", null));
        new AlertDialog.Builder(this).setTitle("에이전트에게 전달할 프롬프트")
            .setMessage(prompt).setPositiveButton("복사", (dialog, which) -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("CallRec2agent Drive 안내", prompt));
                android.widget.Toast.makeText(this, "프롬프트를 복사했습니다.", android.widget.Toast.LENGTH_SHORT).show();
            }).setNegativeButton("닫기", null).show();
    }
    private void setFolderLink() {
        var prefs = getSharedPreferences("drive", 0);
        EditText input = new EditText(this); input.setSingleLine(true); input.setSelectAllOnFocus(true); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs.getString("folder_link", "")); input.setHint("https://drive.google.com/drive/folders/…");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Drive 폴더 링크")
            .setMessage("Drive 앱에서 선택한 폴더의 링크를 복사해 넣으세요. 프롬프트에 포함되며 이 설정이 공유 권한을 바꾸지는 않습니다. 비워 두면 폴더 이름으로 안내합니다.")
            .setView(input).setPositiveButton("저장", null).setNegativeButton("취소", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String link = input.getText().toString().trim();
            if (!link.isEmpty() && !AgentPrompt.validFolderLink(link)) { input.setError("Google Drive 폴더 링크를 입력하세요."); return; }
            prefs.edit().putString("folder_link", link).apply(); dialog.dismiss();
        })); dialog.show();
    }
    private void message(String title,String detail) { new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("확인",null).show(); }
    private void openFolder() {
        String tree = getSharedPreferences("drive", 0).getString("tree", null);
        if (tree == null) { pickDrive(); return; }
        Uri uri = Uri.parse(tree);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildDocumentUriUsingTree(uri, getSharedPreferences("drive", 0).getString("folder_id", DocumentsContract.getTreeDocumentId(uri))), DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(view); }
        catch (android.content.ActivityNotFoundException | SecurityException failure) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(ExportService.DRIVE);
            if (launch != null) startActivity(launch);
        }
    }
    private void preview(String json,String title) {
        TextView text=text(json,14,false,INK); text.setTextIsSelectable(true); text.setPadding(dp(20),dp(20),dp(20),dp(20));
        ScrollView scroll=new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("닫기",null).show();
    }
    private void manual() {
        String guide = "1. 처음 준비\n접근성 설정에서 ‘전사 Drive 공유 도우미’를 켜고 Drive 저장 폴더를 선택합니다. 폴더가 없으면 ‘하위 폴더 생성·선택’에서 이름을 입력하고 상위 Drive 위치를 고릅니다. 같은 이름의 폴더는 재사용합니다. 에이전트도 접근할 수 있는 전용 폴더를 사용하세요.\n\n2. 녹음 고르기\n한 건 또는 여러 건을 고릅니다. 삼성 앱을 열어 전사가 보이는 녹음이나 시작할 목록 위치를 준비한 뒤 최근 앱에서 돌아옵니다.\n\n3. 전송 시작\n시작을 누르면 TXT → Drive 공유를 자동 처리합니다. 첫 한 건은 Drive에서 같은 계정·폴더를 고르고 업로드를 한 번 눌러 주세요. 선택한 폴더에서 저장을 확인한 뒤 그 계정을 기억합니다. 이후 같은 대상으로는 저장도 자동으로 누릅니다.\n\n4. 저장 확인\nDrive 폴더에서 이번 실행의 새 TXT를 다시 읽은 뒤 다음 녹음으로 넘어갑니다. 삼성 원본 TXT 이름을 유지합니다. 같은 녹음을 다시 실행하면 사본이 생길 수 있습니다. JSON 변환과 별도 전사 업로드 단계는 없습니다.\n\n5. 에이전트 활용\nllm.txt가 시작할 때 같은 폴더에 자동 준비됩니다. ‘에이전트 프롬프트 복사’로 계정·위치·읽기 안내를 복사할 수 있습니다. Drive 폴더 링크를 설정하면 프롬프트에도 포함됩니다. 에이전트에게 이 폴더의 llm.txt부터 읽고 관련 TXT를 근거로 답하도록 요청하세요. 기존 JSON과 index.json은 삭제하지 않지만 새 TXT의 색인으로 사용하지 않습니다.\n\n실행 중에는 잠금을 풀고 다른 앱을 사용하지 마세요. 앱·알림의 중지로 멈출 수 있습니다. 확인이 끝나지 않은 마지막 파일은 Drive에서 확인한 뒤 재시도하세요. 파일 재읽기는 Drive 서버 동기화 완료를 보증하지 않습니다. Wi-Fi 대기 시 Drive 앱에서 업로드를 완료해야 에이전트가 볼 수 있습니다.";
        preview(guide, "폰에서 TXT를 Drive로 보내기");
    }
    private void heading(String title,String description) { body.addView(text(title,28,true,INK)); space(body,10); paragraph(body,description); space(body,14); }
    private void note(String value) { TextView note=text(value,14,false,MUTED); note.setPadding(dp(2),dp(10),dp(2),dp(10)); body.addView(note); }
    private void paragraph(LinearLayout parent,String value) { TextView paragraph=text(value,15,false,MUTED); paragraph.setPadding(0,dp(10),0,dp(2)); paragraph.setLineSpacing(dp(3),1); parent.addView(paragraph); }
    private void instruction(LinearLayout parent,String number,String title,String detail) { parent.addView(text(number+". "+title,17,true,INK)); paragraph(parent,detail); space(parent,16); }
    private LinearLayout card() {
        LinearLayout card=column(); card.setPadding(dp(18),dp(18),dp(18),dp(18)); card.setBackground(shape(Color.WHITE,0xffe0e6ef,18));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(14)); body.addView(card,p); return card;
    }
    private Button action(LinearLayout parent,String label,boolean primary,Runnable run) {
        Button button=button(label,primary,run); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54)); p.setMargins(0,dp(8),0,0); parent.addView(button,p); return button;
    }
    private Button button(String label,boolean primary,Runnable run) {
        Button button=new Button(this); button.setText(label); button.setTextSize(16); button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setTextColor(primary?Color.WHITE:BLUE); button.setBackground(shape(primary?BLUE:0xffeaf0ff,Color.TRANSPARENT,14));
        button.setOnClickListener(v->run.run()); return button;
    }
    private TextView text(String value,int size,boolean bold,int color) { TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return view; }
    private LinearLayout column() { LinearLayout result=new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private GradientDrawable shape(int fill,int stroke,int radius) { GradientDrawable drawable=new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); if(stroke!=Color.TRANSPARENT)drawable.setStroke(dp(1),stroke); return drawable; }
    private void space(LinearLayout parent,int height) { View view=new View(this); parent.addView(view,new LinearLayout.LayoutParams(1,dp(height))); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void hideKeyboard() { View focus=getCurrentFocus(); if(focus!=null)getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(),0); }
}

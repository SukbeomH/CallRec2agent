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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class MainActivity extends Activity {
    private enum Step { PREPARE, CHOOSE, READY, RUNNING, RESULTS }
    private static final int INK = 0xff172235, MUTED = 0xff63718a, BLUE = 0xff315dcd, BACKGROUND = 0xfff5f7fb;
    private static final String FOLDER = "내 파일 → 내장 저장공간 → Download → VoiceJson";
    private Step step = Step.PREPARE, afterPrepare = Step.CHOOSE;
    private boolean batch, recorderOpened, showAll;
    private int maximum = 10;
    private String watchedRun;
    private SharedPreferences preferences;
    private LinearLayout body, footer;
    private EditText countInput;
    private TextView liveStatus;
    private TextView driveProgress;
    private boolean lastDriveRunning;
    private static final int PICK_DRIVE = 204;
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
    private boolean ready() { return ExportService.instance != null && getPackageManager().getLaunchIntentForPackage(ExportService.RECORDER) != null; }
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
        if(step == Step.RESULTS) {
            DriveUpload.Status upload=DriveUpload.status();
            boolean active=upload!=null && upload.running();
            if(active!=lastDriveRunning) { render(); return; }
            if(upload!=null && driveProgress!=null)driveProgress.setText(upload.message());
        }
    }
    private void go(Step next) { hideKeyboard(); step = next; persist(); render(); }
    private void back() {
        if(step==Step.RESULTS && lastDriveRunning) {
            new AlertDialog.Builder(this).setTitle("업로드를 중지할까요?").setMessage("현재 파일을 처리한 뒤 멈춥니다. 다시 업로드하면 같은 내용은 재사용합니다.")
                .setPositiveButton("중지 요청",(d,w)->DriveUpload.cancel()).setNegativeButton("계속 업로드",null).show();
            return;
        }
        if (step == Step.RUNNING) {
            new AlertDialog.Builder(this).setTitle("추출을 중지할까요?").setMessage("지금까지 저장한 JSON은 남아 있습니다.")
                .setPositiveButton("중지", (d,w) -> stop()).setNegativeButton("계속 추출", null).show();
        } else if (step == Step.READY) go(Step.CHOOSE);
        else if (step == Step.RESULTS) reset();
        else finish();
    }
    private void reset() { watchedRun = null; recorderOpened = false; showAll = false; go(ready() ? Step.CHOOSE : Step.PREPARE); }

    private void render() {
        countInput = null; liveStatus = null; driveProgress = null;
        LinearLayout root = column(); root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            var keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(12) + Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("전사 JSON", 18, true, INK); brand.setGravity(Gravity.CENTER_VERTICAL); header.addView(brand, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button help = button("사용법", false, this::manual); help.setTextSize(14); header.addView(help, new LinearLayout.LayoutParams(dp(90), dp(46)));
        root.addView(header); space(root, 12);
        LinearLayout progress = new LinearLayout(this); String[] labels = {"준비", "선택", "추출", "결과"};
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
        paragraph(access,"설정에서 ‘전사 JSON 공유 도우미’를 켜세요. 사용자가 시작한 동안 화면의 공유 버튼을 누르고 녹음 목록을 스크롤합니다.");
        paragraph(access,"설정에서 돌아오면 다음 단계로 이동합니다. ‘제한된 설정’이 표시되면 앱 정보에서 ‘제한된 설정 허용’을 직접 선택하세요.");
        action(access,"접근성 설정 열기",true,() -> { persist(); startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); });
        note("인터넷 전송과 재전사는 하지 않습니다. 이미 있는 전사 TXT를 받아 JSON으로 저장합니다.");
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            action(body,"진행·중지 알림 허용",false,() -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1));
        action(footer,"준비 상태 다시 확인",true,() -> { if (ready()) go(afterPrepare); else new AlertDialog.Builder(this).setMessage("접근성 설정에서 ‘전사 JSON 공유 도우미’가 켜졌는지 확인해 주세요.").setPositiveButton("확인",null).show(); });
    }
    private void choose() {
        heading("무엇을 저장할까요?", "삼성 음성녹음에서 이미 전사된 내용을 가져옵니다.");
        modeCard("한 건만 저장", "열어 둔 녹음 하나의 발화자·시간·본문을 저장합니다.", false);
        modeCard("여러 건 순서대로 저장", "현재 녹음 목록 위치부터 아래로 진행합니다.", true);
        if (batch) {
            LinearLayout card = card(); card.addView(text("최대 몇 건을 처리할까요?",17,true,INK));
            countInput = new EditText(this); countInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); countInput.setSingleLine(true);
            countInput.setText(String.valueOf(maximum)); countInput.setSelectAllOnFocus(true); countInput.setTextColor(INK); countInput.setTextSize(24);
            countInput.setContentDescription("최대 녹음 수, 1부터 500까지"); card.addView(countInput,new LinearLayout.LayoutParams(-1,dp(58)));
            paragraph(card,"1~500건 · 전사가 없는 녹음은 건너뜁니다.");
        }
        int count = savedFiles().length;
        if(count > 0) action(body,"저장한 JSON " + count + "개 보기",false,() -> { watchedRun=null; showAll=false; go(Step.RESULTS); });
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
        heading(recorderOpened?"이제 추출을 시작해요":"삼성 앱에서 녹음을 준비해요", batch?"목록에서 최대 "+maximum+"건을 처리합니다.":"열어 둔 녹음 한 건을 처리합니다.");
        LinearLayout instructions=card();
        instruction(instructions,"1",batch?"시작할 목록 위치로 이동":"전사가 있는 녹음 열기",batch?
            "삼성 음성녹음에서 추출을 시작할 위치로 이동하세요. 처음부터 하려면 목록 맨 위로 올립니다.":
            "삼성 음성녹음에서 저장할 녹음을 누르고, 발화자와 전사 본문이 보이는 화면을 열어 두세요.");
        instruction(instructions,"2","이 앱으로 돌아오기","휴대폰의 최근 앱 화면에서 ‘전사 JSON’을 선택하세요. 돌아오는 것만으로 추출이 시작되지는 않습니다.");
        action(instructions,recorderOpened?"삼성 음성녹음 다시 열기":"삼성 음성녹음 열기",false,this::openRecorder);
        if(recorderOpened) note("준비한 화면이 맞으면 아래 ‘추출 시작’을 누르세요. 시작하면 도우미가 삼성 앱으로 이동합니다.");
        else note("먼저 위 버튼으로 삼성 앱에서 녹음을 준비하세요. 돌아오면 시작 버튼이 활성화됩니다.");
        LinearLayout conditions=card(); conditions.addView(text("추출하는 동안",17,true,INK));
        paragraph(conditions,"화면 잠금을 풀어 두고 다른 앱을 사용하지 마세요. 중지하려면 알림의 ‘중지’ 또는 이 앱의 ‘추출 중지’를 누릅니다.");
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            action(conditions,"중지 버튼이 있는 알림 허용",false,()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1));
        Button start=action(footer,"추출 시작",true,this::startExport); start.setEnabled(recorderOpened); start.setAlpha(recorderOpened?1f:0.45f);
        action(footer,"이전 · 추출 방법 변경",false,()->go(Step.CHOOSE));
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
        LinearLayout progress=card(); liveStatus=text("추출 상태 확인 중…",20,true,BLUE); progress.addView(liveStatus);
        ExportService.Snapshot session=ExportService.readSnapshot(this); if(session!=null)liveStatus.setText(session.message());
        paragraph(progress,"완료하면 이 화면으로 돌아와 결과를 보여 드립니다. 이미 저장한 동일한 TXT는 기존 JSON을 재사용합니다.");
        note("화면 잠금을 풀어 두세요. 중지해도 지금까지 저장한 파일은 남아 있습니다.");
        action(body,"삼성 앱의 진행 화면 보기",false,()->{ Intent launch=getPackageManager().getLaunchIntentForPackage(ExportService.RECORDER); if(launch!=null)startActivity(launch); });
        action(footer,"추출 중지",true,this::stop);
    }
    private void stop() { if(ExportService.instance!=null)ExportService.instance.stop("사용자가 중지했습니다."); reconcileRun(); render(); }
    private void results() {
        ExportService.Snapshot session=ExportService.readSnapshot(this);
        if(session!=null && watchedRun!=null && watchedRun.equals(session.id())) {
            boolean complete=session.outcome()==ExportService.Outcome.COMPLETED;
            heading(complete?"JSON 저장을 마쳤어요":"추출을 멈췄어요",complete?"폰 안에서 바로 결과를 확인할 수 있습니다.":"멈추기 전까지 저장한 파일은 그대로 있습니다.");
            LinearLayout summary=card(); summary.addView(text("처리한 녹음 "+session.saved()+"건",24,true,BLUE));
            paragraph(summary,"전사 없음 "+session.skipped()+"건 · 같은 TXT는 기존 파일을 재사용합니다.");
            if(!complete) paragraph(summary,session.message());
        } else heading("저장한 전사", "파일을 눌러 JSON을 확인하세요.");
        LinearLayout location=card(); location.addView(text("저장 위치",17,true,INK)); paragraph(location,FOLDER);
        action(location,"저장 폴더 열기",false,this::openFolder);
        File[] files=savedFiles();
        driveCard(files.length);
        body.addView(text("저장된 JSON "+files.length+"개",17,true,INK)); space(body,8);
        int visible=showAll?files.length:Math.min(files.length,3);
        for(int i=0;i<visible;i++) {
            File file=files[i];
            try {
                String json=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8); org.json.JSONObject document=new org.json.JSONObject(json);
                LinearLayout row=card(); TextView name=text(document.getString("sourceFileName").replaceFirst("_original\\.txt$",""),16,true,INK);
                name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(name);
                paragraph(row,document.getJSONArray("segments").length()+"개 발화 · 눌러서 JSON 보기"); row.setClickable(true); row.setFocusable(true);
                row.setOnClickListener(v->preview(json));
            } catch(Exception failure) { note("읽을 수 없는 저장 파일: "+file.getName()); }
        }
        if(files.length>visible)action(body,"전체 "+files.length+"개 보기",false,()->{ showAll=true; render(); });
        if(files.length==0)note("아직 저장한 전사가 없습니다. 녹음을 선택해 처음 추출해 보세요.");
        Button again=action(footer,"다른 녹음 추출하기",true,this::reset); again.setEnabled(!lastDriveRunning); again.setAlpha(lastDriveRunning?0.45f:1f);
    }
    private void driveCard(int fileCount) {
        LinearLayout drive=card(); drive.addView(text("Drive에서 에이전트와 활용",19,true,INK));
        paragraph(drive,"이 앱이 저장한 전사 JSON "+fileCount+"개와 에이전트 안내문 llm.txt, 파일 목록 index.json을 함께 보냅니다. 음성 파일과 원본 TXT는 보내지 않습니다.");
        var prefs=getSharedPreferences("drive",0);
        String tree=prefs.getString("tree",null), name=prefs.getString("folder_name",null);
        paragraph(drive,tree==null?"먼저 저장할 Drive 폴더를 선택하세요.":"대상 폴더: "+name);
        paragraph(drive,"에이전트의 Drive 연결 계정에서도 접근할 수 있는 폴더를 고르세요. 업로드하는 동안 이 앱을 열어 두세요.");
        DriveUpload.Status upload=DriveUpload.status(); lastDriveRunning=upload!=null && upload.running();
        Button choose=action(drive,tree==null?"Drive 폴더 선택":"Drive 폴더 변경",false,this::pickDrive); choose.setEnabled(!lastDriveRunning);
        Button send=action(drive,"선택한 폴더에 업로드",true,()->{
            String target=getSharedPreferences("drive",0).getString("tree",null);
            if(target==null) { pickDrive(); return; }
            if(DriveUpload.start(this,Uri.parse(target)))render();
        }); send.setEnabled(tree!=null && fileCount>0 && !lastDriveRunning); send.setAlpha(send.isEnabled()?1f:0.45f);
        if(lastDriveRunning)action(drive,"현재 파일 처리 후 중지",false,DriveUpload::cancel);
        driveProgress=text(upload==null?prefs.getString("last_result", "아직 업로드하지 않았습니다."):upload.message(),14,false,MUTED);
        driveProgress.setPadding(0,dp(12),0,0);drive.addView(driveProgress);
        action(drive,"에이전트 안내문 미리보기",false,()->{
            try(var input=getAssets().open("llm.txt")) { preview(new String(input.readAllBytes(),StandardCharsets.UTF_8),"llm.txt 안내문"); }
            catch(Exception failure) { message("안내문을 열 수 없습니다.",failure.getMessage()); }
        });
    }
    private void pickDrive() {
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        String previous=getSharedPreferences("drive",0).getString("tree",null);
        if(previous!=null)pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(previous));
        new AlertDialog.Builder(this).setTitle("Drive 폴더 선택")
            .setMessage("파일 선택기의 왼쪽 메뉴에서 Drive 계정을 선택하고, 전사를 저장할 폴더로 이동한 뒤 ‘이 폴더 사용’을 누르세요. 폴더 선택만으로 업로드가 시작되지는 않습니다.")
            .setPositiveButton("폴더 선택 열기",(d,w)->startActivityForResult(pick,PICK_DRIVE)).setNegativeButton("취소",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_DRIVE || result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri tree=data.getData();
        var provider=getPackageManager().resolveContentProvider(tree.getAuthority(),0);
        if(!DocumentsContract.isTreeUri(tree) || provider==null || !"com.google.android.apps.docs".equals(provider.packageName)) {
            message("Drive 폴더를 선택해 주세요.","왼쪽 메뉴의 Drive 계정을 사용하세요. 폰 내장 저장공간은 업로드 대상이 아닙니다.");return;
        }
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if((flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            message("읽기·쓰기 권한이 필요해요.","Drive에서 저장하고 확인할 수 있는 폴더를 다시 선택하세요.");return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            String name="선택한 Drive 폴더";
            Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            try(var cursor=getContentResolver().query(document,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) { if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0); }
            String old=getSharedPreferences("drive",0).getString("tree",null);
            getSharedPreferences("drive",0).edit().putString("tree",tree.toString()).putString("folder_name",name).remove("last_result").apply();
            DriveUpload.clearResult();
            if(old!=null && !old.equals(tree.toString()))try {
                for(var grant:getContentResolver().getPersistedUriPermissions())if(grant.getUri().toString().equals(old)) {
                    int oldFlags=(grant.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)|(grant.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0);
                    getContentResolver().releasePersistableUriPermission(grant.getUri(),oldFlags);break;
                }
            } catch(SecurityException ignored) { }
            step=Step.RESULTS; render();
        } catch(Exception failure) { message("폴더를 저장하지 못했어요.",failure.getMessage()); }
    }
    private void message(String title,String detail) { new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton("확인",null).show(); }
    private File[] savedFiles() {
        File[] files=new File(getFilesDir(),"transcripts").listFiles((dir,name)->name.endsWith(".json"));
        if(files==null)return new File[0]; Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified())); return files;
    }
    private void openFolder() {
        Intent view=new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/VoiceJson"),DocumentsContract.Document.MIME_TYPE_DIR);
        try { startActivity(view); }
        catch(android.content.ActivityNotFoundException missing) { new AlertDialog.Builder(this).setTitle("내 파일에서 확인하기").setMessage(FOLDER).setPositiveButton("확인",null).show(); }
        catch(SecurityException denied) { new AlertDialog.Builder(this).setTitle("내 파일에서 확인하기").setMessage(FOLDER).setPositiveButton("확인",null).show(); }
    }
    private void preview(String json) {
        preview(json,"JSON 미리보기");
    }
    private void preview(String json,String title) {
        TextView text=text(json,14,false,INK); text.setTextIsSelectable(true); text.setPadding(dp(20),dp(20),dp(20),dp(20));
        ScrollView scroll=new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("닫기",null).show();
    }
    private void manual() {
        String guide="PC 연결 없이 폰에서 사용합니다.\n\n1. 처음 한 번 준비\n접근성 설정에서 ‘전사 JSON 공유 도우미’를 켭니다. 이미 켜져 있으면 준비 단계를 건너뜁니다.\n\n2. 추출 방법 선택\n‘한 건만 저장’ 또는 ‘여러 건 순서대로 저장’을 고릅니다. 여러 건은 최대 녹음 수를 지정합니다.\n\n3. 삼성 앱에서 준비\n한 건은 전사가 보이는 녹음을 엽니다. 여러 건은 추출을 시작할 목록 위치로 이동합니다. 처음부터 추출하려면 목록 맨 위로 올리세요.\n\n4. 이 앱으로 돌아와 시작\n최근 앱 화면에서 ‘전사 JSON’으로 돌아와 ‘추출 시작’을 누릅니다.\n\n5. 결과 확인\n내 파일 → 내장 저장공간 → Download → VoiceJson에서 JSON을 확인합니다. 결과 화면에서 파일을 눌러 미리 볼 수도 있습니다.\n\n실행 중에는 화면 잠금을 풀고 다른 앱을 사용하지 마세요. 알림의 ‘중지’ 또는 앱의 ‘추출 중지’로 멈춥니다. 멈추기 전 저장한 파일은 남아 있습니다.\n\n발화자·시작 시간·본문을 저장합니다. TXT의 시간은 초 단위이며 종료 시간은 추정하지 않습니다. 같은 원본 TXT는 중복 저장하지 않습니다.\n\n접근성 연결이 끊기면 설정에서 도우미를 다시 켭니다. 인식하지 못하는 화면에서는 최대 60초 후 중지합니다.\n\n직접 공유하려면 삼성 앱에서 공유 → 텍스트 파일 → 전사 JSON 저장을 선택하세요.";
        guide+="\n\n6. Drive로 보내기\n결과 화면의 ‘Drive 폴더 선택’을 누릅니다. 파일 선택기의 왼쪽 메뉴에서 Drive 계정과 폴더를 고르고 ‘이 폴더 사용’을 누릅니다. 돌아와 대상 폴더를 확인한 뒤 ‘선택한 폴더에 업로드’를 누르세요.\n\n이 앱이 저장한 JSON과 llm.txt, index.json을 함께 보냅니다. 에이전트에는 같은 Drive 폴더를 알려주고 두 안내 파일부터 읽도록 요청하세요. 계정이 다르면 에이전트의 Drive 계정에도 폴더 접근 권한이 있어야 합니다.\n\n업로드 중에는 이 앱을 열어 두세요. 폴더 저장 후 클라우드 동기화 여부는 Drive 앱에서 확인합니다. 같은 내용은 재사용하고 같은 이름의 다른 파일은 덮어쓰지 않습니다.";
        TextView content=text(guide,16,false,INK); content.setPadding(dp(24),dp(8),dp(24),dp(16));
        ScrollView scroll=new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("폰에서 전사 JSON 저장하기").setView(scroll).setPositiveButton("닫기",null).show();
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

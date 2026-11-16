package dev.local.voicejson;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class DriveUpload {
    record Status(boolean running, boolean success, int done, int total, int created, int reused, String message) {}
    private static volatile Status status;
    private static volatile boolean cancelled;
    static Status status() { return status; }
    static synchronized void clearResult() { if(status==null || !status.running())status=null; }
    static void cancel() {
        cancelled=true;
        Status current=status;
        if(current!=null && current.running())status=new Status(true,false,current.done(),current.total(),current.created(),current.reused(),"중지 요청됨 · 현재 파일 처리가 끝나면 멈춥니다.");
    }
    static synchronized boolean start(Context owner, Uri tree) {
        if(status!=null && status.running())return false;
        Context context=owner.getApplicationContext(); cancelled=false;
        status=new Status(true,false,0,0,0,0,"업로드 준비 중…");
        new Thread(()->upload(context,tree),"voicejson-drive-upload").start();
        return true;
    }
    private static void upload(Context context,Uri tree) {
        int done=0,total=0,created=0,reused=0;
        try {
            var items=DriveBundle.prepare(context); total=items.size();
            java.util.Set<String> expected=new java.util.HashSet<>(); for(var item:items)expected.add(item.name());
            Map<String,Uri> existing=children(context,tree,expected);
            var history=context.getSharedPreferences("drive-written",0);
            for(DriveBundle.Item item:items) {
                if(cancelled)throw new IllegalStateException("사용자가 업로드를 중지했습니다. 다음 실행에서 같은 파일은 재사용합니다.");
                status=new Status(true,false,done,total,created,reused,"저장 확인 "+done+" / "+total+" · "+item.name());
                Uri document=existing.get(item.name());
                String key=DriveBundle.hash((tree+"\n"+item.name()).getBytes(StandardCharsets.UTF_8));
                String remoteHash=document==null?null:DriveBundle.hash(read(context,document));
                String ownedHash=document!=null && document.toString().equals(history.getString(key+"_uri",null))?history.getString(key+"_hash",null):null;
                SyncDecision.Action action=SyncDecision.choose(item.sha256(),remoteHash,ownedHash);
                if(action==SyncDecision.Action.CONFLICT)throw new IllegalStateException("같은 이름의 다른 파일을 보존하기 위해 중지했습니다: "+item.name()+". 전용 빈 폴더를 선택하거나 기존 파일을 확인하세요.");
                if(action==SyncDecision.Action.KEEP) { reused++; done++; continue; }
                boolean fresh=document==null;
                if(fresh) {
                    Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                    document=DocumentsContract.createDocument(context.getContentResolver(),parent,item.mime(),item.name());
                    if(document==null)throw new IllegalStateException("Drive 파일을 만들 수 없습니다: "+item.name());
                }
                try {
                    try(OutputStream output=context.getContentResolver().openOutputStream(document,"wt")) {
                        if(output==null)throw new IllegalStateException("Drive 파일을 열 수 없습니다.");
                        output.write(item.bytes());
                    }
                    if(!item.sha256().equals(DriveBundle.hash(read(context,document))))throw new IllegalStateException("Drive에서 다시 읽은 내용이 일치하지 않습니다: "+item.name());
                    history.edit().putString(key+"_uri",document.toString()).putString(key+"_hash",item.sha256()).commit();
                    existing.put(item.name(),document); created++; done++;
                } catch(Exception failure) {
                    if(fresh)try { DocumentsContract.deleteDocument(context.getContentResolver(),document); } catch(Exception ignored) { }
                    throw failure;
                }
            }
            String message="Drive 폴더에 "+done+"개 저장 확인 · 새로 저장/갱신 "+created+"개 · 같은 내용 "+reused+"개 재사용\n클라우드 동기화 완료 여부는 Drive 앱에서 확인하세요.";
            context.getSharedPreferences("drive",0).edit().putString("last_result",message).apply();
            status=new Status(false,true,done,total,created,reused,message);
        } catch(Exception failure) {
            String message="업로드 중지 · "+done+" / "+total+"개 처리\n"+failure.getMessage();
            context.getSharedPreferences("drive",0).edit().putString("last_result",message).apply();
            status=new Status(false,false,done,total,created,reused,message);
        }
    }
    private static Map<String,Uri> children(Context context,Uri tree,java.util.Set<String> expected)throws Exception {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        for(int attempt=0;attempt<40;attempt++) {
            if(cancelled)throw new IllegalStateException("사용자가 업로드를 중지했습니다.");
            try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
                if(cursor==null)throw new IllegalStateException("Drive 폴더를 읽을 수 없습니다. 폴더를 다시 선택하세요.");
                String error=cursor.getExtras().getString(DocumentsContract.EXTRA_ERROR);
                if(error!=null)throw new IllegalStateException(error);
                if(!cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING,false)) {
                    Map<String,Uri> result=new HashMap<>();
                    while(cursor.moveToNext()) {
                        String name=cursor.getString(1);
                        if(!expected.contains(name))continue;
                        Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
                        if(result.put(name,document)!=null)throw new IllegalStateException("폴더에 같은 이름의 파일이 여러 개 있습니다: "+name);
                    }
                    return result;
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Drive 폴더 목록이 아직 로딩 중입니다. 연결을 확인한 뒤 다시 업로드하세요.");
    }
    private static byte[] read(Context context,Uri document)throws Exception {
        try(InputStream input=context.getContentResolver().openInputStream(document);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            if(input==null)throw new IllegalStateException("Drive 내용을 다시 읽을 수 없습니다.");
            byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1) { if(output.size()+n>16*1024*1024)throw new IllegalStateException("같은 이름의 원격 파일이 16MB를 넘습니다. 덮어쓰지 않습니다."); output.write(buffer,0,n); }
            return output.toByteArray();
        }
    }
}

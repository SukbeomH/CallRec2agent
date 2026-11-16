package dev.local.voicejson;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class DriveBundle {
    record Item(String name, String mime, byte[] bytes, String sha256) {}
    static List<Item> prepare(Context context) throws Exception {
        File[] files=new File(context.getFilesDir(),"transcripts").listFiles((dir,name)->name.endsWith(".json"));
        if(files==null || files.length==0)throw new IllegalStateException("먼저 전사를 JSON으로 저장하세요.");
        Arrays.sort(files,Comparator.comparing(File::getName));
        List<Item> items=new ArrayList<>(); JSONArray records=new JSONArray();
        for(File file:files) {
            byte[] bytes=Files.readAllBytes(file.toPath()); JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            String name=TranscriptStore.jsonFileName(json.getString("sourceFileName"),json.getString("sourceSha256"));
            items.add(item(name,"application/json",bytes));
            JSONObject entry=new JSONObject();
            entry.put("file",name).put("title",json.getString("title")).put("sourceFileName",json.getString("sourceFileName"))
                .put("sourceSha256",json.getString("sourceSha256")).put("importedAt",json.getString("importedAt"))
                .put("segmentCount",json.getJSONArray("segments").length());
            records.put(entry);
        }
        byte[] guide;
        try(var input=context.getAssets().open("llm.txt")) { guide=input.readAllBytes(); }
        items.add(item("llm.txt","text/plain",guide));
        JSONObject index=new JSONObject().put("kind","voicejson-index").put("schemaVersion",1).put("recordCount",records.length())
            .put("guide","llm.txt").put("records",records);
        items.add(item("index.json","application/json",index.toString(2).getBytes(StandardCharsets.UTF_8)));
        File folder=new File(context.getFilesDir(),"drive-export");
        if(!folder.isDirectory() && !folder.mkdirs())throw new IllegalStateException("업로드 준비 폴더를 만들 수 없습니다.");
        for(Item item:items)Files.write(new File(folder,item.name()).toPath(),item.bytes());
        return List.copyOf(items);
    }
    private static Item item(String name,String mime,byte[] bytes)throws Exception { return new Item(name,mime,bytes,hash(bytes)); }
    static String hash(byte[] bytes)throws Exception {
        StringBuilder value=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))value.append(String.format(Locale.ROOT,"%02x",b&255)); return value.toString();
    }
}

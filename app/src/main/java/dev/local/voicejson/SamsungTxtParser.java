package dev.local.voicejson;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SamsungTxtParser {
    private static final Pattern HEADER = Pattern.compile("^(.+?)\\s{2,}\\((\\d{1,3}:\\d{2}(?::\\d{2})?)\\)\\s*$");
    public record Segment(String speaker, List<String> speakers, long startMs, String text) {}
    public record Transcript(String title, String encoding, List<Segment> segments, String originalText) {}

    public static Transcript parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("TXT 파일 크기는 1바이트~8MB여야 합니다.");
        Charset encoding = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) { encoding = StandardCharsets.UTF_16LE; offset = 2; }
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) { encoding = StandardCharsets.UTF_16BE; offset = 2; }
        else if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) offset = 3;
        String text;
        try { text = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("지원하는 TXT 인코딩은 UTF-8 또는 BOM이 있는 UTF-16입니다.", e); }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("텍스트가 아닌 파일입니다.");
        List<Segment> segments = new ArrayList<>();
        String speaker = null;
        long start = 0;
        StringBuilder body = new StringBuilder(), preamble = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            Matcher match = HEADER.matcher(line);
            if (match.matches()) {
                if (speaker != null) segments.add(segment(speaker, start, body.toString()));
                speaker = match.group(1).trim(); start = millis(match.group(2)); body.setLength(0);
            } else if (speaker == null) preamble.append(line).append('\n');
            else body.append(line).append('\n');
        }
        if (speaker != null) segments.add(segment(speaker, start, body.toString()));
        if (segments.isEmpty()) throw new IllegalArgumentException("발화자와 시간이 있는 삼성 전사 TXT를 찾지 못했습니다.");
        String title = preamble.toString().replace("AI로 생성된 콘텐츠입니다", "").replace("AI-generated content", "").trim();
        return new Transcript(title, encoding.name(), List.copyOf(segments), text);
    }

    private static Segment segment(String speaker, long start, String body) {
        if (body.trim().isEmpty()) throw new IllegalArgumentException("본문이 비어 있는 발화 구간이 있습니다. 원본 TXT를 확인하세요.");
        return new Segment(speaker, List.of(speaker.split("\\s+&\\s+")), start, body.trim());
    }

    private static long millis(String timestamp) {
        String[] parts = timestamp.split(":");
        int last = Integer.parseInt(parts[parts.length - 1]);
        int middle = Integer.parseInt(parts[parts.length - 2]);
        if (last >= 60 || (parts.length == 3 && middle >= 60)) throw new IllegalArgumentException("올바르지 않은 시간: " + timestamp);
        return (last + middle * 60L + (parts.length == 3 ? Integer.parseInt(parts[0]) * 3600L : 0L)) * 1000L;
    }
}

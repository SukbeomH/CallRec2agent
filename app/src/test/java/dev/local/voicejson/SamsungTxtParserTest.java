package dev.local.voicejson;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class SamsungTxtParserTest {
    @Test public void preservesSpeakersMultilineAndOverlappingTimes() {
        String source="AI로 생성된 콘텐츠입니다\r\n\r\n회의 제목\r\n\r\n나  (00:01)\r\n첫 줄\r\n둘째 줄\r\n\r\n민수 & 나  (00:01)\r\n겹친 발화\r\n\r\n민수  (01:02:03)\r\n끝\r\n";
        var result=SamsungTxtParser.parse(source.getBytes(StandardCharsets.UTF_16));
        assertEquals("회의 제목",result.title());
        assertEquals(3,result.segments().size());
        assertEquals("첫 줄\n둘째 줄",result.segments().get(0).text());
        assertEquals(1000,result.segments().get(0).startMs());
        assertEquals(1000,result.segments().get(1).startMs());
        assertEquals(java.util.List.of("민수","나"),result.segments().get(1).speakers());
        assertEquals("민수 & 나",result.segments().get(1).speaker());
        assertEquals(3723000,result.segments().get(2).startMs());
    }
    @Test public void acceptsBomUtf16LittleEndian() {
        byte[] body="발화자  (20:34)\n본문\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes=new byte[body.length+2]; bytes[0]=(byte)0xff; bytes[1]=(byte)0xfe; System.arraycopy(body,0,bytes,2,body.length);
        assertEquals(1234000, SamsungTxtParser.parse(bytes).segments().get(0).startMs());
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsOrdinaryText() { SamsungTxtParser.parse("시간 정보 없는 메모".getBytes(StandardCharsets.UTF_8)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptySpeech() { SamsungTxtParser.parse("나  (00:01)\n\n민수  (00:02)\n본문".getBytes(StandardCharsets.UTF_8)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidTime() { SamsungTxtParser.parse("나  (00:99)\n본문".getBytes(StandardCharsets.UTF_8)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsMalformedUtf8() { SamsungTxtParser.parse(new byte[]{(byte)0xc3,0x28}); }
}

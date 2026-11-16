# JSON 형식

```json
{
	"schemaVersion": 1,
	"sourceFileName": "예시_original.txt",
	"sourceSha256": "원본 바이트의 SHA-256",
	"sourceEncoding": "UTF-16BE",
	"importedAt": "2026-11-16T00:00:00Z",
	"title": "회의 제목",
	"timeResolutionMs": 1000,
	"source": "Samsung Voice Recorder TXT share",
	"segments": [
		{
			"speaker": "발화자 1 & 나",
			"speakers": ["발화자 1", "나"],
			"startMs": 1000,
			"text": "예시 발화입니다."
		}
	]
}
```

`speaker`는 원래 발화자 표기를 보존합니다. `speakers`는 공백으로 둘러싸인 `&`를 기준으로 나눈 이름 목록입니다. 겹친 발화의 본문을 개별 발화자에게 나누지 않습니다.

`startMs`는 TXT의 시작 시간을 밀리초로 환산한 값입니다. 원본 TXT가 초 단위이므로 소수점 이하의 시간과 단어별 시간은 복원하지 않습니다. 종료 시간도 추정하지 않습니다.

지원하는 입력은 BOM이 있는 UTF-16LE, UTF-16BE, UTF-8 TXT입니다. 최대 크기는 8MB입니다. 본문이 없는 발화나 올바르지 않은 시간, 전사 형식이 아닌 파일은 오류로 처리합니다.

자동화 선택자는 한국어 삼성 음성녹음 21.5.91.33의 화면에서 확인한 리소스 ID와 정확한 메뉴 문구를 사용합니다. 다른 언어 또는 앱 버전에서는 자동화 수정이 필요할 수 있습니다. TXT를 직접 공유하는 수신 경로는 자동화와 별도로 사용할 수 있습니다.

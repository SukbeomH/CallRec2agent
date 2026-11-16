"""Compare every saved JSON with its original shared TXT on the debug device."""
import hashlib
import json
import re
import subprocess
import shlex


def private(*args):
    return subprocess.check_output(["adb", "exec-out", "run-as", "dev.local.voicejson", *args])


paths = private("find", "files/transcripts", "-name", "*.json").decode().splitlines()
assert paths, "No device exports found"
header = re.compile(r"^(.+?)\s{2,}\((\d{1,3}:\d{2}(?::\d{2})?)\)\s*$", re.M)
total = 0
for path in paths:
    document = json.loads(private("cat", path))
    raw = private("cat", "files/transcripts/" + document["sourceSha256"] + ".txt")
    assert hashlib.sha256(raw).hexdigest() == document["sourceSha256"]
    text = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    matches = list(header.finditer(text))
    assert len(matches) == len(document["segments"])
    for index, match in enumerate(matches):
        segment = document["segments"][index]
        parts = list(map(int, match[2].split(":")))
        seconds = parts[-1] + 60 * parts[-2] + (3600 * parts[0] if len(parts) == 3 else 0)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        assert segment["speaker"] == match[1].strip()
        assert segment["startMs"] == seconds * 1000
        assert segment["text"] == text[match.end():end].strip()
        assert segment["speakers"] == re.split(r"\s+&\s+", match[1].strip())
    total += len(matches)
public_paths = subprocess.check_output(["adb", "exec-out", "find /sdcard/Download/VoiceJson -type f -name '*.json'"]).decode().splitlines()
private_documents = {json.loads(private("cat", p))["sourceSha256"]: json.loads(private("cat", p)) for p in paths}
for path in public_paths:
    public = json.loads(subprocess.check_output(["adb", "exec-out", "cat " + shlex.quote(path)]))
    assert public == private_documents[public["sourceSha256"]]
assert len(public_paths) == len(paths), "Public and private document counts differ"
print(f"PASS: {len(paths)} JSON documents, {total} segments; every speaker, time and body matches its original TXT; public Download copies match")

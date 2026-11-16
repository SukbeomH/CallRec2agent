# APK 빌드하기

JDK 21, Android SDK 36, Android SDK Build Tools를 설치합니다. `local.properties`에 SDK 위치를 설정합니다.

소스와 APK 릴리스는 별도로 관리합니다. 설치만 하려면 [GitHub Releases](https://github.com/SukbeomH/CallRec2agent/releases)에서 APK를 받습니다.

```properties
sdk.dir=/절대/경로/Android/sdk
```

프로젝트 폴더에서 실행합니다.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 위치는 `app/build/outputs/apk/debug/app-debug.apk`입니다. 개인 기기 검증용 디버그 서명 빌드입니다.

디버그 키는 각 개발 환경에서 생성됩니다. 다른 컴퓨터에서 빌드한 APK는 배포 APK와 서명이 다를 수 있습니다. 키 파일과 `local.properties`는 저장소에 포함하지 않습니다.

연결된 기기에 설치합니다.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.local.voicejson/.MainActivity
```

접근성 권한은 사용자가 휴대폰 설정에서 직접 켭니다. 이 앱은 접근성 설정을 자동으로 변경하지 않습니다.

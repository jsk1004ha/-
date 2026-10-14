# APK 추출기

Android에 설치된 앱을 `.apk` 또는 `.apks`로 추출하는 간단한 오프라인 앱입니다.

## 기능

- 설치된 일반 앱 / 시스템 앱 목록 (검색 및 필터)
- 앱을 누르면 단일 `.apk` 추출, 분할 설치 앱은 `.apks` (ZIP) 추출
- 앱을 길게 누르면 다중 선택 후 폴더에 일괄 추출
- APK 데이터는 기기 안에서만 처리 (네트워크·저장소 권한 요청 없음)
- `.apks`는 `base.apk`, split APK, `meta.sai_v1.json`, `meta.sai_v2.json`, `icon.png` 포함
- Android 8.0 (API 26) 이상 지원

## 빌드

Android SDK (API 35), Java 17, Gradle 8.11.1이 필요합니다.

```sh
gradle :app:assembleDebug
```

`app/build/outputs/apk/debug/app-debug.apk`를 기기에 설치하면 됩니다. GitHub Actions에서는 설치 가능한 서명된 릴리스 APK를 만들어 아티팩트로 제공합니다.

## 참고

- 사용자 데이터(앱 로그인, 설정, 앱 내부 DB)는 백업하지 않습니다. 설치 패키지만 복사합니다.
- 앱의 원본이 해당 기기에서 접근할 수 없거나 회사 정책으로 제한되어 있으면 추출에 실패할 수 있습니다.
- 분할 앱은 `base.apk`만 설치하면 실행되지 않을 수 있습니다. `.apks`를 SAI 등 분할 APK 설치기로 설치하세요.
- 다른 기기에서는 ABI / 화면 밀도 / 로케일 분할 구성 차이로 설치가 안 될 수 있습니다.
- `QUERY_ALL_PACKAGES` 권한은 기능에 꼭 필요하며, Google Play에 배포하려면 별도 심사/정책 준수가 필요할 수 있습니다.

## 개인정보

앱은 인터넷 연결 권한을 사용하지 않습니다. APK 파일은 사용자 지정 저장 위치로 직접 복사되며 서버로 업로드하지 않습니다.
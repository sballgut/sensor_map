# Sensor A·E — Android 수집 앱과 팀 서버

센서 공용 API v4와 A/E 역할 분담을 기준으로 만든 **실행 가능한 개발 뼈대**입니다. 기존 Team 4 전용 앱과 다른 앱 ID(`com.example.sensorae`)를 사용합니다. Android 8.0 이상을 대상으로 하며, 특정 Team 4 MAC 주소로 수집을 제한하지 않습니다.

## 먼저 이해할 데이터 구분

| 데이터 | 받는 곳 | 앱에서 하는 일 |
|---|---|---|
| 전체 센서의 서버 최신값·설치 좌표 | 공용 `GET /sensor/api/map` | 34개 표준 ID 목록 표시, direct/mobile 통합, 캐시 |
| 휴대폰 주변의 원시 패킷·RSSI | BLE 서비스 데이터 UUID `0x181A` | 실제 관측 저장, B의 위치 추정 입력 |
| 과거 서버 기록 | 공용 `GET /sensor/api/history` | 별도 Python 도구로 조회·파일 저장; 팀 API 키 필요 |
| 휴대폰이 수집한 기록·후보·배포 파일 | 이 프로젝트의 E 서버 | 배치 ACK, 중복 방지, 검토, 버전 배포 |

인터넷 API로 먼 곳의 센서 원시 BLE 패킷이나 현재 휴대폰 RSSI를 받을 수는 없습니다. API에 제공되지 않는 센서는 `API 미제공`으로 표시합니다. 작성 중 실제 map 응답은 36행이었고 별칭/모드를 합쳐 27개 표준 센서가 확인됐습니다. 이 수는 서버 상태에 따라 달라집니다.

## 포함된 기능

- LAB 8바이트 / OPEN_SOURCE 13바이트 계열 파서. 손상·미식별 패킷의 raw/RSSI/해석 상태 보존.
- `opensrc1~5`를 `sensor 07/15/31/32/33`으로 통합. 이전 `Opensrc_team4` 같은 별도 이름은 자동 매핑하지 않으며 미식별 원본으로 남김.
- SQLite 원본 보관, 세션·가명 기기 ID, UTC/단조 시각, CSV 내보내기, 마지막 세션 재생.
- 안내/수집/평가 모드. 안내는 GPS 요청 0 경로와 GPS null 저장 검사, 나머지는 획득 가능한 GPS 라벨 기록.
- 북쪽 기준 방향·정확도·고정 자북 보정, B/D용 `TeamBridge`.
- 앱 실행 중 약 30초마다 또는 수동 배치 전송. ACK된 record_id만 완료 처리, 실패 항목은 DB에 유지.
- 후보 기록 동의, 세션 근거·B의 추정 위치/불확실성 제출, 관리자 검토·철회 이력.
- 지도/그래프/목적지/모델 4개 자산의 SHA-256 검증, 임시 다운로드 후 활성 버전 교체, 이전 버전 복구, 안내 중 버전 고정.
- 팀 서버와 과거 데이터 조회 도구, 자동 테스트, 팀원용 연동 문서.

## 바로 실행

### 1. 앱

함께 제공한 `SensorAE-debug.apk`는 직접 설치해 볼 수 있는 **시험용 서명 APK**입니다. 기존 앱과 별도로 설치됩니다. 실기기에서 실행·BLE 수신까지 확인한 APK는 아닙니다.

1. `공용 API · 전체 센서 최신값 새로고침`으로 서버 목록을 조회합니다. E 서버 없이도 됩니다.
2. 수집 목적에 맞게 모드를 선택합니다. GPS 라벨 데이터셋을 모으려면 **수집** 모드를 선택합니다.
3. 필요하면 기준 지점 ID와 고정 보정각을 입력합니다. 0도는 보정값 미적용이며 진북 정확도를 보장하지 않습니다.
4. `BLE 수집 시작`을 누르고 주변 기기/정확한 위치 권한을 허용합니다. 수신 건수와 센서별 건수를 확인합니다.
5. 종료 후 `관측 CSV 내보내기`로 저장합니다. CSV는 지금까지 저장한 모든 세션을 포함합니다.

BLE 신호로 위치를 추정하는 용도이므로 안내 모드에서도 Android 위치 권한이 필요합니다. **권한 허용과 GPS 좌표 읽기는 다릅니다.** 안내 경로는 GPS·네트워크 위치 provider를 호출하지 않습니다. 일부 기종에서는 BLE 수신을 위해 시스템 위치 설정도 켜야 합니다.

앱 화면을 벗어나거나 잠그면 수집을 중지합니다. 활성 수집 중에는 화면을 켜 둡니다. 백그라운드 수집 서비스는 이 뼈대에 포함하지 않았습니다. 기존에 겪은 5분 후 수신 저하가 이 버전에서 해결됐다는 실기기 결과는 아직 없습니다. 현장 시험에서는 20분 이상 센서별 수신 간격을 CSV로 확인하세요.

### 2. E 서버

Python 3.10 이상으로 `server` 폴더에서 실행합니다. 외부 Python 패키지는 필요 없습니다.

```powershell
$env:APP_TOKEN = '직접-정한-긴-앱-토큰'
$env:ADMIN_TOKEN = '앱-토큰과-다른-긴-관리자-토큰'
python api.py --host 0.0.0.0 --port 8080 --data data
```

휴대폰과 PC를 같은 Wi-Fi에 연결하고 앱에 `http://PC의LAN주소:8080`과 APP_TOKEN을 입력한 뒤 설정을 저장하세요. 휴대폰에서 `127.0.0.1`은 서버 PC를 가리키지 않습니다. Android 17 이상에서는 첫 배치 전송 때 로컬 네트워크 권한을 허용한 뒤 버튼을 다시 누릅니다. 관리자는 PC의 `http://127.0.0.1:8080/admin`에 접속해 ADMIN_TOKEN을 입력합니다.

서버는 로컬/LAN 통합 시험용 표준 라이브러리 HTTP 서버입니다. 팀의 운영 프레임워크 최종 선정·외부 배포는 별도입니다. 공용 API 서버를 E 서버 주소로 넣으면 배치 저장이 되지 않습니다. 자세한 API·검토·발행 방법은 [server/README.md](server/README.md)를 보세요.

### 3. 후보와 업데이트

1. 후보 기록 동의 체크 후 새 세션을 수집합니다. B를 연결하지 않았으면 추정 위치는 생성되지 않습니다.
2. 관측 배치를 먼저 전송하고 후보 설명을 입력한 뒤 후보를 제출합니다. 세션 근거/추정값은 각각 최대 5,000건입니다.
3. 관리자가 검토를 시작하고 승인합니다. 승인만으로 지도에 길이 생성되지는 않습니다.
4. B/C가 준비한 실제 `map.json`, `graph.json`, `destinations.json`, `model.json`을 서버에서 새 버전으로 발행합니다.
5. 앱에서 수집/안내를 중지한 뒤 업데이트 버튼을 누릅니다. 안내 중에는 버전을 교체하지 않습니다.

최초에는 배포 자산이 없으므로 업데이트가 404를 반환하는 것이 정상입니다. 실제 지도·모델 대신 가짜 자산을 앱에 넣어 두지 않았습니다. 사진은 서버의 참조 필드까지만 지원하며 앱 사진 선택·바이너리 업로드 UI는 없습니다.

## Android Studio에서 수정·빌드

ZIP을 `C:\dev`처럼 짧은 경로에 풀고 **SensorAE/android** 폴더를 Android Studio로 여세요. ZIP에는 `.gradle`, `build`, 개인 SDK 경로, API 키, 서버 DB를 포함하지 않았습니다.

- AGP 9.3.2, Gradle 9.6.0, compile/target SDK 37, min SDK 26
- Gradle JDK: 설치한 최신 Android Studio의 내장 JDK 사용 권장; 작성 환경은 JDK 25
- Java 소스 호환 17, Gson 2.11.0, JUnit 4.13.2
- SDK Manager에 Android API 37 설치 후 Gradle Sync
- `gradlew.bat testDebugUnitTest assembleDebug`

제공 APK는 설치된 SDK 도구로 Java 컴파일 → D8 → AAPT2 → 정렬·서명하여 만들었습니다. 이번 환경에서 Gradle 전체 빌드나 실기기 설치까지 통과했다고 주장하지 않습니다. APK의 시험 서명과 사용자의 Android Studio debug 서명이 달라 이후 덮어쓰기 설치가 거부될 수 있습니다. 삭제 재설치하면 앱 DB가 지워지므로 먼저 CSV를 내보내세요.

## 팀원에게 넘길 문서

- [docs/INTEGRATION.md](docs/INTEGRATION.md): B/C/D 인터페이스, 원시 스키마, A/E 요구사항 구현 범위
- [docs/updates.md](docs/updates.md): manifest·버전 교체 계약
- [docs/VERIFICATION.md](docs/VERIFICATION.md): 실제 수행한 검증과 남은 실기기 시험
- [tools/README.md](tools/README.md): 전체 센서 과거 데이터 조회

**현재 완성 범위는 A/E 개발 기반입니다.** B의 위치 추정 모델, C의 실제 보행 그래프/경로 탐색, D의 지도 안내 화면, 운영 서버 배포는 포함하지 않았습니다. 최종 수업 요구사항 전체를 완료한 앱으로 제출하기 전에는 팀 연동과 현장 시험이 필요합니다.

## 패킷 문서 해석

역할 메모의 LAB 필드 순서와 API v4 PDF의 바이트 정의가 달라 **API v4의 CO₂ → 온도 → timestamp 순서**를 적용했습니다. 실제 LAB 장치 원시값과 한 번 대조해야 합니다. 13바이트 뒤의 추가 바이트는 보존하지만 검증 규칙이 없어 검증 성공으로 표시하지 않습니다.

참조: 제공된 `센서_공용_API_v4.pdf`, A~E 역할 분담 텍스트. 플랫폼 권한 근거: [Bluetooth 권한](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [로컬 네트워크 권한](https://developer.android.com/privacy-and-security/local-network-permission), [북쪽 기준 방향 센서](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position).

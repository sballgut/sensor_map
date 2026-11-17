# 검증 기록

## 받은 공유 경로 탭

- 하단 탭, 빈 목록, 파일 선택기 JSON 가져오기, 목록/상세 지도, 공유 경로 안내, 삭제 확인을 추가했다.
- assembleDebug / testDebugUnitTest / lintDebug 성공. JVM 64개 통과(공유 경로 검증 8개 추가), 실패/오류 0개.
- API 37 에뮬레이터에서 시험 JSON을 시스템 파일 선택기로 가져오기, 상세 표시, 시작점 근처에서 공유받은 경로 안내 시작, 앱 재설치/재시작 후 목록 유지, 삭제 후 빈 목록을 확인했다. 시험 자료는 검증 후 삭제했다. AndroidRuntime 오류 없음.
- screenshots/shared-routes-list.png 및 shared-route-detail.png는 실제 타인 기록이 아닌 시험 자료 화면이다. 실제 서버·링크 수신 및 현장 보행 검증은 아니다.


## 공유 경로 색상 변경

- RouteOrigin.SHARED는 2D/3D에서 초록색, LOCAL은 파란색으로 표시한다. 공유 수신 기능 자체는 미연결이다.
- assembleDebug / testDebugUnitTest / lintDebug 성공. 기존 56개 테스트 통과. 공유 수신 화면의 기기 검증은 수행하지 않았다.

## 2026-11-17 D-1/D-2 실제 위치 안내 통합

- d1-ui 0590c14의 Java 검색/상세창을 가져와 D-2 Compose/OpenGL 지도와 연결했다. 원본 검색 테스트 12개 유지.
- assembleDebug / testDebugUnitTest / lintDebug --offline 성공. JVM 56개 통과: 기존 34개 + D-1 12개 + 실제 위치 경로/도착 10개. 실패/오류 0개. Lint 오류 0개, 경고 8개(버전 알림 6, 가져온 Java View의 생성자/문자열 경고 2).
- API 37 에뮬레이터에서 검색 목록→123학군단 선택→현재 위치 출발 3D 안내 확인. 약 601m 경로에 46개 시험 위치를 순서대로 주입해 남은 거리/회전 안내 갱신과 목적지 인근 도착 메시지를 확인했다. 이 시험은 실제 현장 GPS 측정이 아니다.
- 최종 APK 재설치 후 동일 출발 위치로 3D 안내 재실행, 위치 서비스 끄기→정확한 위치 수신 대기, 다시 켜기, 2D 지도 전환을 확인했다. 설정은 복원했다. AndroidRuntime 오류 로그 없음.
- 화면: screenshots/d1-d2-home.png, screenshots/d1-d2-route-map.png.
- 실제 단말 방향 센서 정확도·GPS 보행 품질·전체 접근성/작은 화면·프로세스 복원은 추가 검증이 필요하다. A/B BLE 융합·기록 공유·서버 연동 완료를 의미하지 않는다.



## 2026-11-17 D-2 현재 위치 표시

- CampusMapPanel에 위치 상태를 입력받도록 분리하고, 독립 앱에 포그라운드 위치 권한/Android LocationManager 어댑터를 연결했다. 기존 A/E 앱과 TeamBridge는 변경하지 않았다.
- 최종 assembleDebug, testDebugUnitTest, lintDebug --offline 성공. JVM 34개 통과(위치 상태/좌표 변환 10개 추가), 실패 0개. Lint 오류 0개·경고 6개.
- API 37 에뮬레이터에서 권한 전 현재 위치 없음, Android 위치 권한 승인, 주입한 개신캠퍼스 좌표 수신 후 점/정확도 반경 표시를 확인했다. 이는 에뮬레이터 시험 좌표이며 실제 휴대폰 GPS 현장 검증이 아니다.
- 오래된 last-known 위치에 신호 공백 표시, 위치 서비스 끄기에 실시간 위치 아님 안내를 확인했다. 검증 후 위치 서비스를 다시 켜고 최종 APK를 설치했다.
- 최신 화면: `screenshots/d2-location.png`. 위치는 메모리에서만 사용하고 저장/전송하지 않는다.
- 실제 단말의 GPS 품질, 대략적 권한/영구 거부 전체 시나리오, 백그라운드 복귀 및 D-1/A/B 통합은 추가 검증이 필요하다. 길찾기 이동은 기존 시뮬레이션이다.

아래는 이전 변경의 검증 기록이다.


## 최신 수정: 확대 클리핑·3D 둘러보기 (2026-11-17)

- 지도 컨테이너와 Canvas에 clipToBounds를 적용했다. 최대 확대에서도 검색창/하단 패널 침범이 없음을 Pixel 10 API 37 에뮬레이터에서 캡처로 확인했다.
- 실제 OpenGL `3D 둘러보기`를 추가했다. 확대 버튼과 3D→경로 안내 전환을 실행하고 렌더링을 확인했다.
- 건물 방향별 명암·바닥 투영 그림자·일반적인 층 구분선을 추가했다. 실측 창문/외관 모델은 아니다.
- 먼 거리 도로 깊이 충돌을 발견해 24비트 깊이 버퍼, 카메라별 near/far 및 평면 레이어 순서로 수정했다. 수정 후 캡처에서 도로 연속 표시를 확인했다.
- 확대 도로의 마디 모양 테두리는 전체 외곽/내부를 각각 한 레이어로 그려 수정했다.
- JVM 24개 통과. 최종 assembleDebug/lintDebug --offline 성공, 오류 0개, 버전 알림 경고 6개.
- APK를 테스트 에뮬레이터에 업데이트 설치했다. 앱 시작/3D 둘러보기/3D 경로 안내 확인, AndroidRuntime 오류 로그 없음.
- 캡처: `docs/screenshots/zoom-clipping.png`, `overview-3d.png`, `navigation-3d.png`. 확대 캡처는 도로 테두리 추가 수정 전이며 클리핑 결과를 기록한 것이다.
- 실제 휴대폰 GPU/성능, 모든 화면 크기 및 전체 경로 완주는 미검증이다.

아래는 이전 변경의 검증 기록이다.


## 최신 변경: 개신캠퍼스 실제 좌표·무채색 지도

2026-11-17: OSM 원본과 SMASH-CBNU의 ODbL 건물 윤곽을 연결했다. 기본 지도는 gaesin_campus.json으로 변경했다. 2D·3D를 밝은 무채색으로 통일하고 경로/위치만 파란색으로 유지했다.

- 건물 157개, 노드 1,572개, 간선 1,745개, 목적지 98개.
- Python 데이터/경계 테스트 4개 통과. 경계를 벗어나는 간선, 가짜 목적지 연결을 검사했다.
- JVM 테스트 24개 통과: A* 11개, 경로 재생 9개, 오목 건물 윤곽 삼각분할 4개.
- 최종 assembleDebug / testDebugUnitTest / lintDebug --offline: BUILD SUCCESSFUL (4초).
- Lint 오류 0개, 버전 업데이트 알림 6개.
- 목적지는 실제 출입구가 아닌 기존 경로의 건물 인근 지점이다. GPS/BLE는 여전히 미연결이다.
- 기기 화면 렌더링·터치·실제 보행 통행 가능 여부는 미검증이다.
- 추가 파일: tools/import_osm.py, tools/test_import_osm.py, data/osm/*, data/smash-cbnu/*, assets/gaesin_campus.json, assets/MAP_LICENSES.txt, rendering/FootprintMesh.kt, rendering/FootprintMeshTest.kt(테스트 소스).

아래는 초기 가상 지도 MVP의 이전 검증 기록이다.


일자: 2026-11-17 (Asia/Seoul)

## 환경

- 초기 작업 폴더는 비어 있었음. 기존 프로젝트/사용자 코드를 덮어쓰지 않음.
- macOS arm64, Android Studio JBR 25.0.2
- Android SDK Platform 37.0, Build Tools 36.0.0
- Gradle Wrapper 9.5.0, AGP 9.3.2, Kotlin/Compose compiler 2.2.10

## 실제 실행 결과

1. `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL.
2. 회전 지점 표시 및 아이콘/백업 정책 수정 후 같은 작업을 `--offline`으로 실행: BUILD SUCCESSFUL.
3. 구형 Android 백업 설정 추가 후 `:app:assembleDebug :app:lintDebug --offline`: BUILD SUCCESSFUL (3초).
4. JVM 테스트: AStarTest 11개 + RoutePlaybackTest 9개 = **20개 통과**, 실패/오류/건너뜀 0개.
5. 최종 lint: **오류 0개, 경고 6개**. 모두 Gradle/라이브러리 새 버전 알림이며, 현재 빌드한 고정 버전을 유지함.
6. APK 생성: `app/build/outputs/apk/debug/app-debug.apk` (약 10MB), application ID `kr.ac.campusroute`, version 0.1.0, minSdk 26, targetSdk 37.
7. `aapt dump badging`으로 패키지와 런처 Activity(`kr.ac.campusroute.MainActivity`) 확인. 위치/BLE/카메라/인터넷 권한 없음. AndroidX 내부 receiver 보호용 권한만 포함.

## 단위 테스트 범위

- A*: 정상/예상 최단 경로, 단절, 출발=목적, 간선 비용, 직선거리보다 저렴한 간선의 최적성, 통행 금지, 일방향, 역방향, 미등록 ID, 음수 비용 거부, 비용 갱신.
- 재생/안내: 위치 보간, 회전 지점 진행, 도착 범위 제한, 1개 노드, 음수 진행 거리, 359→1도 각도 래핑, 각도 평활화, 직진 중간 노드 생략, 길이 0인 구간 처리.
- KNN/융합/QR 테스트는 해당 기능이 이번 1단계 범위에 없으므로 작성하지 않음.

## 수행하지 못한 검증

ADB 조회 결과 연결 기기 없음. 설치된 AVD 및 시스템 이미지 없음.
따라서 앱 설치/실행, 2D 터치, 실제 GPU의 3D 화면, 화면 회전/백그라운드 복귀, 도착 화면 전환, 비행기 모드에서의 실행은 **수동 검증 전**이다.
오프라인 Gradle 빌드 성공은 앱의 기기 오프라인 통합 테스트와 다르다.
README의 수동 검증 절차로 실제 기기에서 확인해야 한다.

## 생성 파일

- 루트: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), `README.md`, `AGENTS.md`
- 로컬 환경: `local.properties` (버전 관리 제외)
- 앱 설정: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- 리소스: `res/values/styles.xml`, `res/drawable/ic_campus_route.xml`, `res/xml/data_extraction_rules.xml`
- 지도: `app/src/main/assets/sample_campus.json`
- 앱 진입: `MainActivity.kt`
- 모델/저장소: `domain/model/Campus.kt`, `data/repository/CampusRepository.kt`
- 알고리즘: `routing/AStar.kt`, `routing/RoutePlayback.kt`
- UI: `ui/CampusViewModel.kt`, `ui/map/HomeScreen.kt`, `ui/navigation/NavigationScreen.kt`
- 렌더러: `rendering/CampusRenderer.kt`
- 테스트: `app/src/test/java/kr/ac/campusroute/routing/AStarTest.kt`, `RoutePlaybackTest.kt`
- 검증 문서: `docs/VALIDATION.md`

Kotlin 파일의 기준 디렉터리는 `app/src/main/java/kr/ac/campusroute/`이며 리소스는 `app/src/main/` 아래에 있다.

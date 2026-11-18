# Campus Route — 1단계 Android MVP

충북대학교 개신캠퍼스를 대상으로 하는 오픈소스개발프로젝트를 위한 **오프라인 보행 내비게이션 개발 앱**입니다.
기본 지도는 **개신캠퍼스의 OSM 실좌표 기반 지도**입니다. 건물 윤곽은 SMASH-CBNU의 ODbL 지도 데이터와 OSM 원본을 대조하여 사용합니다. 실제 출입구와 통행 가능 여부는 아직 현장 확인 전이며, 경로 안내의 이동은 데모 재생입니다.
지도에서 `내 위치 표시 허용`을 누르면 승인 후 기기의 실제 위치를 표시합니다. `경로 찾기` 후 3D 보행은 여전히 정문 출발 시뮬레이션입니다. BLE가 위치 좌표를 전송한다고 가정하지 않습니다.

## 지도 대상 범위

서비스 범위는 **충북대학교 개신캠퍼스 내부 보행 안내에 한정**합니다. 다른 캠퍼스 및 캠퍼스 외부 경로는 대상에 포함하지 않습니다. 건물·목적지·보행로 그래프는 개신캠퍼스 외곽 범위로 제한합니다. 현재 OSM 대학 relation 6705106의 외곽을 사용하며, 경계 밖으로 나가는 경로는 제외합니다. OSM 경계는 법적 지적 경계와 다를 수 있습니다.

## 구현 범위

- 단일 `app` 모듈, Kotlin, Jetpack Compose / Material 3, MVVM, StateFlow, Coroutines
- 로컬 JSON 지도·건물·보행로 로더와 목적지 검색/목록/지도 마커 선택
- 2D 지도 드래그, 핀치 줌, 확대/축소, 내 위치, 전체 보기
- 실제 A* 경로 탐색 후 **전체 화면 2D → 전체 화면 3D** 전환 (화면 분할 없음)
- 실제 OpenGL ES 2.0 삼각형 메시, OSM 건물 윤곽을 높이로 세운 입체 건물, 보행로, 3D 사용자 마커, 방향 화살표
- 지도 영역 클리핑으로 확대/드래그 시 헤더·하단 UI 침범 방지
- 홈의 `3D 둘러보기`에서 실제 OpenGL 원근 지도·확대·이동, `2D 지도`로 복귀
- 3D 건물의 방향별 명암·바닥 투영 그림자·일반적인 층 구분선 (실측 외관 아님)
- 밝은 무채색 지도: 흰 건물·회색 측면·옅은 회색 바탕과 도로, 선택 경로/현재 위치만 파란색
- 경로 보간 재생, 진행한 경로/남은 경로 구분, 남은 거리·다음 회전 안내
- 카메라 위치 및 방향 평활화, 일시 정지/재생, 보행 속도(1.4m/s)/빠른 재생(8m/s)
- 2D 경로 보기 및 3D 복귀, 안내 종료, 도착 2.5초 후 2D 복귀
- 앱 백그라운드 전환 시 시뮬레이션 정지, GL 생명주기 처리, 화면 회전 시 ViewModel 유지
- A*, 경로 보간, 회전 안내 및 각도 평활화 JVM 단위 테스트

이 버전은 1단계만 구현합니다. GPS/NETWORK 기반 지도 위치 표시는 구현했으며 BLE, Room, KNN/융합, QR, 체크포인트, 서버 전송은 **아직 구현하지 않았습니다**. 동작하지 않는 버튼이나 빈 구현으로 완료 표시하지 않습니다. 지도 표시용 포그라운드 위치 권한만 요청합니다. 위치는 메모리에서 표시하며 저장/전송하지 않습니다. 인터넷·Bluetooth·카메라·백그라운드 위치 권한은 없습니다.

## 실행

Android Studio에서 이 폴더를 Open하고 Gradle Sync 후 `app`을 실행합니다.

- Android Studio: AGP 9.3을 지원하는 버전
- Gradle 9.5.0 (Wrapper 포함), AGP 9.3.2, 내장 Kotlin 2.2.10 / Compose 플러그인 2.2.10
- compile/target SDK 37, Build Tools 36.0.0, min SDK 26, OpenGL ES 2.0 기기
- Gradle JDK: Android Studio에 포함된 JBR 권장. 이 환경은 JBR 25로 검증합니다.
- `local.properties`의 `sdk.dir`은 각 개발자 SDK 경로로 설정합니다. 해당 파일은 버전 관리에서 제외합니다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS에서 시스템 Java가 11인 경우:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
테스트: `app/build/reports/tests/testDebugUnitTest/index.html`
Lint: `app/build/reports/lint-results-debug.html`

최초 Gradle 의존성 다운로드에는 네트워크가 필요합니다. 설치 후 개신캠퍼스 지도·탐색·3D 재생은 네트워크 없이 동작하도록 구성되어 있습니다. 의존성이 캐시되면 `./gradlew --offline ...`으로 빌드할 수 있습니다.

## 사용자 흐름

1. 앱 실행 → 2D 개신캠퍼스 지도. `내 위치 표시 허용` → 위치 권한 승인 후 수신한 기기 위치와 정확도 반경을 표시합니다.
2. 필요하면 `3D 둘러보기`로 건물 높이를 확인합니다. 3D 목적지 선택은 목록에서 합니다.
3. 검색창, 가로 목적지 목록 또는 지도 노드를 눌러 목적지를 선택합니다.
4. `경로 찾기 · 이동 시뮬레이션` → 정문 인근 보행로에서 선택 목적지까지 A* 계산.
5. 경로가 없으면 2D 화면에 오류, 성공하면 전체 화면 3D 안내를 표시합니다.
6. 경로를 따라 자동 보행합니다. `빠르게`로 데모 시간을 줄일 수 있습니다.
7. `2D 지도`에서 같은 경로와 기기 위치를 확인하거나 `안내 종료`로 복귀합니다.
8. 도착하면 안내를 표시한 뒤 자동으로 2D 지도로 돌아갑니다.

출발지는 현재 OSM 정문 POI에서 약 13.3m 떨어진 기존 보행로 노드입니다. 지도에는 권한 승인 후 수신한 기기 위치를 표시하며 새 경로 찾기는 정문 인근 노드에서 다시 시작합니다. 프로세스 종료 후에는 초기 상태로 돌아갑니다.

## 구조와 협업 계약

| 파일/패키지 | 책임 / 입출력 | 연결 담당 |
|---|---|---|
| `domain/model/Campus.kt` | `Point`(미터), `Node`, `Edge`, `Building`, `Campus`, `Route` | 공통 |
| `data/repository/CampusRepository.kt` | `CampusRepository.load(): Campus`; assets JSON 구현 | 문정현 |
| `routing/AStar.kt` | `RoutePlanner.find(Campus, startId, destinationId): Route?` | 문정현 |
| `routing/RoutePlayback.kt` | `routePose(Route, travelledMetres): RoutePose`; Android 비의존 | 홍태경 / 장연우 |
| `ui/CampusViewModel.kt` | StateFlow 상태·목적지·탐색·재생 수명 관리 | 이동호 |
| `ui/map/HomeScreen.kt` | 검색·목적지 선택·전체 화면 조립 | D-1 |
| `ui/map/CampusMapPanel.kt` | 2D/3D 지도, 경로·현재 위치·정확도 표시 | D-2 (우리 담당) |
| `ui/navigation/NavigationScreen.kt` | 안내 UI 및 AndroidView/GL 생명주기 연결 | 장연우 |
| `rendering/CampusRenderer.kt` | 같은 Campus/Route + 불변 RoutePose → 3D 프레임 | 장연우 |
| 향후 측위 계층 | GPS/BLE 수집 → 로컬 미터 좌표·시각·정확도·신뢰도 | 박성현 |
| 향후 QR 계층 | 지도 버전 및 노드 ID 검증 → 기존 Route | 홍태경 |

지도 저장소·경로 탐색과 D-2 지도 입력/위치 공급 계약을 분리했습니다. 이후 측위기는 GPS/BLE 후보·융합 결과를 별도로 보존하고, `RoutePose`를 만들 때 경로 진행 추정과 map matching을 수행해야 합니다. 지금의 경로 재생은 실제 측위나 map matching이 아닙니다.

## A*와 거리

인접 리스트 + 최소 우선순위 큐로 `g + h`를 탐색합니다. 지나간 비용이 개선되면 다시 큐에 넣고, 오래된 큐 항목을 무시합니다. 양방향/일방향, 통행 불가 간선을 처리하며 잘못된 노드 ID나 단절 구간은 null을 반환합니다.

기본 휴리스틱은 로컬 평면 직선거리입니다. 가져온 간선 비용이 실제 기하 거리보다 작아도 최적성이 깨지지 않도록 최소 `간선 비용 / 직선거리` 비율(최대 1)을 휴리스틱에 적용합니다. 경로의 `totalDistance`는 간선 비용 합이고, 데모 보행 거리·진행률은 폴리라인 기하 거리입니다. 실제 좌표 그래프에서는 반올림 오차 범위 내에서 둘이 같습니다. 이후 실제 지도에서도 거리 비용을 미터로 저장해야 합니다.

## 지도 데이터와 SMASH 활용

- 기본 파일: `app/src/main/assets/gaesin_campus.json` (ODbL 1.0)
- 건물 157개, 경로 노드 1,572개, 간선 1,745개, 건물 인근 목적지 98개
- OSM 원본: `data/osm/gaesin.osm`, 출처·범위·정책: `data/osm/README.md`
- SMASH 데이터: `data/smash-cbnu/osm_building_outlines.json`, 출처: `data/smash-cbnu/README.md`
- 생성: `python3 tools/import_osm.py`
- 데이터 검증: `python3 -m unittest discover -s tools -p 'test_*.py'`

[SMASH-CBNU](https://github.com/Hong-Iron/SMASH-CBNU)는 개신캠퍼스 JSON을 3D로 그리는 게임입니다. 완성된 GLB 파일을 넣은 것이 아닙니다. ODbL로 명시된 건물 데이터 중 OSM ID 건물의 외곽만 사용하고, 게임 코드·이미지·텍스처·지형 고도·브이월드 추가 건물은 가져오지 않았습니다. 우리 앱의 OpenGL 메시 생성 코드는 직접 구현했습니다.

SMASH 좌표(x 동쪽/z 남쪽)를 위경도로 역변환한 뒤 기준점 (36.6285, 127.4565)의 미터 좌표로 맞춥니다. OSM 원본과 1m 이상 어긋난 윤곽은 원본으로 대체합니다. 건물 높이는 OSM height, levels×3.2m, 기본 10m 순으로 사용하며 추정 근거를 JSON에 저장합니다. 지형은 평면이고 외관/높이는 정밀 실측 모델이 아닙니다.

OSM 공유 노드 ID로만 경로를 연결합니다. 가까운 두 길을 임의로 연결하지 않습니다. 출입 제한·보행 금지·조건부 제한을 제외하고, campus 외곽 밖을 통과하는 간선을 제외합니다. service 도로도 포함되므로 모든 간선이 보행 전용은 아닙니다. 검증되지 않은 건물 출입구로 연결선을 만들지 않고, 건물 외곽에서 최대 60m 이내 기존 경로 노드를 **건물 인근** 목적지로 사용합니다. 일부 목적지는 시작점과 연결되지 않아 탐색 실패 메시지가 표시될 수 있습니다.

학교 공식 안내도와 현장 조사로 건물명·출입구·통행 제한을 검증한 후 실제 안내에 사용해야 합니다. 네이버 지도 첨부 이미지는 무채색 표현 방향의 참고이며 이미지나 지도 데이터를 복사하지 않았습니다. 기존 `sample_campus.json`은 참고용으로 남아 있고 앱은 읽지 않습니다.

지도 양 화면에 © OpenStreetMap contributors / ODbL 링크를 표시하고 `MAP_LICENSES.txt`를 APK에 포함합니다. 파생 JSON과 원본 데이터는 ODbL로 배포합니다. OSM 공개 타일 서버에서 이미지 타일을 일괄 다운로드하지 않습니다.

## 3D 렌더러 선택과 카메라

2026-11-17 공식 문서와 설치된 SDK를 확인했습니다. Android가 제공하는 OpenGL ES 2.0 `GLSurfaceView.Renderer`를 선택했습니다. 단순 건물·보행로 MVP에는 별도 엔진/모델 다운로드 없이 메시와 카메라를 직접 관리할 수 있고, API 26 이상과 Compose `AndroidView`에서 사용할 수 있습니다. Filament의 PBR·복잡한 모델 기능은 현재 범위에서 필요하지 않아 추가 의존성을 도입하지 않았습니다. OpenGL이 최신 그래픽 API라는 의미는 아니며, 향후 복잡한 모델·조명에는 렌더러 교체를 검토할 수 있습니다.

- 월드 좌표: `(localX, height, -localY)`, 실제 건물 윤곽의 지붕 삼각분할과 측면 메시·무채색 명암
- 깊이 버퍼, 원근 투영(53도), look-at 카메라
- 카메라: 사용자 뒤 19m/위 15m, 전방 13m 지점을 바라봄
- 사용자/카메라 위치를 각각 지수 보간, 카메라 방향은 ±π 최단 각도 차로 보간
- 위치·방향 목표는 불변 `RoutePose`를 volatile 참조로 GL 스레드에 전달
- 진행 구간은 회색, 남은 구간은 파란색, 목적지는 검정 깃발로 표시

공식 자료:
- [OpenGL ES 지원 및 Renderer](https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl)
- [GLSurfaceView 생명주기/API](https://developer.android.com/reference/android/opengl/GLSurfaceView)
- [Compose AndroidView 통합](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)
- [AGP 9.3 호환성](https://developer.android.com/build/releases/agp-9-3-0-release-notes)

## 수동 통합 검증 절차

- 비행기 모드에서 앱을 실행하고 개신캠퍼스 지도를 이동/확대합니다.
- 공학관/체육관 등 회전이 필요한 목적지를 선택 → 경로 찾기 → 전체 화면 3D 전환.
- 보행자, 방향 화살표, 건물 입체감, 파란 경로, 카메라 회전, 남은 거리를 확인합니다.
- 정지/재생, 빠른 재생, 2D 보기/3D 복귀, 시스템 뒤로 가기, 안내 종료를 확인합니다.
- 도착 후 2D 복귀, 출발지=목적지, 검색 결과 없음, 기기 회전, 백그라운드/복귀를 확인합니다.
- 단절 경로는 단위 테스트로 검증합니다. 실제 OSM 그래프는 일부 단절 구간을 포함하며 임의로 연결하지 않습니다.

실제 기기/에뮬레이터의 프레임 렌더링·터치·오프라인 실행 여부는 아래 빌드 결과와 구분해야 합니다. 자세한 실행 결과는 `docs/VALIDATION.md`에 기록합니다.

## 다음 단계

2단계: Android Location API와 BLE Scanner, 권한 거부/서비스 비활성화 처리, 별도 디버그 화면, 명시적 수집 동의와 Room 기록.
3단계: 실제 광고 식별자 파서, CSV/JSON 라디오맵, RSSI 필터링·KNN, 미터 좌표 기반 신뢰도 융합, 이탈/점프 감지와 map matching.
4단계: QR 인코딩/스캔, 지도 버전·연결 간선 검증, 복원 Route 안내.
5단계: 선택적 BLE 체크포인트, 미전송 큐, 고유 ID 및 서버 저장 ACK, 재시도.
6단계: 충북대학교 개신캠퍼스 OSM 경로·건물 출입구 대조 및 실제 보행로·센서 실측, GPS 단독 대비 융합 오차 비교와 오프라인 통합 테스트.

## D-2 담당과 위치 표시

지도/제어는 `CampusMapPanel`, 위치 계약은 `MapLocationState`/`MapFix`, 독립 앱의 권한/측위 연결은 `DeviceLocationBinding`/`AndroidLocationFeed`로 분리했습니다. D-1/A/B 연동 방법과 제한은 [D-2 연동 문서](docs/D2_INTEGRATION.md)를 참고하세요.

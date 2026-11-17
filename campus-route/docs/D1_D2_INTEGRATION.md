# D-1 / D-2 실제 위치 안내 통합

## 가져온 코드

- 저장소: https://github.com/sballgut/sensor_map
- D-1 브랜치 `d1-ui`, 커밋 `0590c14da569eab29b6f6d31f7ee13283bbba555`.
- `com.example.sensorae.ui.d1`의 Destination, DestinationSearchController, DestinationSelectionTarget, DestinationHomeView, DemoDestinations와 검색 테스트 12개를 가져왔다.
- DestinationHomeView의 attachMap / setDestinations / selectDestination 계약을 그대로 사용한다. 길찾기 콜백, 활성 상태, 위치 상태 문구를 추가했다. 기존 예시 목적지 대신 개신캠퍼스 OSM 목록을 주입한다.
- D-1 원본은 저장소의 `android/`, 실행 가능한 통합 앱은 `campus-route/`에 있다. 기존 A/E MainActivity, 수집·저장·서버 코드는 변경하지 않았다. 두 앱 전체를 합친 것은 아니다.
- 방향 센서는 기존 A/E HeadingTracker 구현을 독립 패키지에 재사용한다. 지도 어댑터에서 GeomagneticField로 자북을 진북에 보정하며 원본 A/E 코드는 수정하지 않는다.

## 동작

1. 위치 권한 허용 → GPS/NETWORK 위치 표시. 방향 센서가 유효하면 휴대폰 방향 화살표 표시.
2. D-1 건물 검색·선택·취소 ↔ D-2 선택 표시. 검색 정규화와 별칭은 원본 컨트롤러를 사용한다.
3. 현재 위치에서 길찾기 → 30m 이내 가장 가까운 통행 가능 간선 위를 출발점으로 A* 계산. 일방향 및 간선 비용을 보존한다. 사용자와 보행로 사이 임의의 연결선을 생성하지 않는다.
4. 3D 안내 또는 2D 지도에서 실제 측위에 따라 진행한다. 타이머 자동 보행은 없다.
5. 경로 이탈 시 안내하고 사용자가 현재 위치에서 다시 찾기를 실행한다.
6. 끝점 15m 이내 및 남은 경로 20m 이하, 정확도 20m 이하인 서로 다른 위치 두 번으로 도착 판단. 건물 출입구가 아닌 인근 보행로 도착이다.
7. 위치 신호가 오래되거나 정확도가 부족하면 진행·도착 판단을 멈춘다. 앱이 화면에서 사라지면 위치/방향 수집을 중단한다.

## 임시 정책과 한계

- 경로 시작/진행: 정확도 30m 이하, 대략적 위치 제외, 30초 이내 측위. 지도 표시는 별도의 100m 임계값이다.
- 경로 이탈: 경로와의 거리 > max(20m, 제공 정확도). 큰 위치 점프는 한 번 보류하고 다음 측위를 기다린다.
- 건물 입구·횡단보도·통행 제한은 OSM 기반이며 현장 검증 전이다. 최근접 간선만 사용하므로 분리된 보행로에서는 경로가 없을 수 있다. 평행 보행로/반복 경로의 정밀 map matching은 B/C 통합 때 개선해야 한다.
- 방향 정보가 없으면 사용자 방향 화살표를 숨긴다. 3D 카메라는 경로 방향을 참고하며 휴대폰 방향이라고 가장하지 않는다.
- 위치 원시 기록, 이력 공유, BLE 융합, QR, 서버 ACK/전송은 이번 통합 앱에 없다. 이 작업은 오프라인 GPS 길찾기 실행 버전이며 전체 팀 시스템의 현장 완료 판정은 아니다.
- 화면 회전 시 ViewModel은 안내 상태를 유지한다. 앱 프로세스 종료 시 안내 세션은 복원하지 않으며 다시 시작해야 한다. 위치를 디스크에 보관하지 않는다.

## 재현

Android Studio에서 `campus-route/`(독립 작업 폴더에서는 opensrc_map)를 연다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

휴대폰에서 위치 승인 → 개신캠퍼스 실외에서 위치 수신 → 목적지 검색 → 현재 위치에서 길찾기. 권한 거부/대략적 위치, 서비스 꺼짐, 지도 전환, 백그라운드 복귀, 보행 중 이탈·도착을 확인한다. 에뮬레이터 좌표 주입은 실측이 아니다.

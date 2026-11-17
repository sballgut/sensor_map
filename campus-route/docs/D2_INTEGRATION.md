# D-2 지도·위치 표시 연결 (2026-11-17)

> 아래는 최초 위치 표시 단계의 계약 기록입니다. 현재 실행 앱은 [D-1/D-2 통합 문서](D1_D2_INTEGRATION.md)를 따르며 실제 GPS 경로 안내, 선택 해제, 방향 센서 표시를 구현했습니다.

사용자 확인: 우리 담당은 D-2(지도·위치·방향 표시)다. 전달받은 D-1/D-2 합의안은 제안 문서이며, 기존 프로젝트에 모든 계약이 이미 구현되었다고 간주하지 않는다.

## 이번 변경

- D-2 지도와 제어 버튼을 `ui/map/CampusMapPanel.kt`로 분리했다.
- 기존 HomeScreen은 독립 실행용 호스트로 지도 컴포넌트를 배치한다. 검색·목적지 목록·길찾기 흐름은 유지한다. MainActivity와 A/E 코드는 변경하지 않았다.
- 위치 허용 → Android 위치 API 수신 → WGS84에서 지도 origin 기준 미터 좌표 변환 → 2D/3D 현재 위치 표시.
- 권한 없을 때 정문이나 데모 좌표로 사용자 점을 만들지 않는다. 실제 위치와 정문 출발 시뮬레이션은 별개다.
- 현재 기기 위치의 정확도 반경을 표시한다. 정보가 없으면 임의 정확도를 만들지 않는다.
- 유효한 위치는 따라가되 지도를 손으로 이동하거나 전체 보기를 누르면 따라가기를 멈춘다. 내 위치를 누르면 다시 따라간다.
- 대략적 위치, 정확도 미제공/100m 초과, 마지막 수신 30초 이상은 불확실/오래됨으로 구분한다. 회색 점으로 마지막 위치를 유지하고 자동 따라가기를 중지한다. 30초/100m는 임시 화면 정책으로 B/C와 합의해야 한다.
- 위치 서비스 꺼짐, 권한 거부/철회, 제공자 없음, 수신 대기를 구분한다. 권한을 철회하면 위치 점을 지운다.
- 위치가 캠퍼스 밖이면 경계로 강제 이동시키지 않는다. 실제 위치로 이동하되 주변 지도 미제공 문구를 표시한다.
- 지도 표시만을 위한 포그라운드 위치 요청이다. 위치 저장·로그 출력·서버 전송·백그라운드 권한은 없다.

## D-1이 사용할 컴포넌트

```kotlin
CampusMapPanel(
    campus = campus,                       // 기존 C 지도/목적지 자료
    route = selectedRoute,                 // 기존 Route? 표시 모델
    selectedDestinationId = destinationId,
    location = mapLocationState,           // 공통 연결부가 A/B 상태를 변환해서 전달
    modifier = Modifier.fillMaxSize(),
    onDestinationSelected = { id -> /* D-1 상세 열기 */ },
    onRequestLocation = { /* A의 기존 권한 요청에 위임 */ },
    onOpenLocationSettings = { /* 권한/서비스 설정 */ }
)
```

`CampusMapPanel`은 위치 수신 API를 직접 호출하지 않는다. `rememberDeviceLocation()` + `AndroidLocationFeed`는 현재 독립 앱에서만 쓰는 호스트 어댑터다. 통합 앱에서는 A의 기존 권한·리스너를 건드리지 않고 A/B 상태를 `MapLocationState`로 변환해 전달한다. TeamBridge 리스너나 PositionSink를 교체하지 않는다.

`MapFix`는 latitude/longitude(명명 필드), accuracyMetres(nullable), measuredAtUtcMs, elapsedRealtimeMs(기기 부팅 기준), bearingDegrees(nullable), approximate, source, isMock를 전달한다. JSON [경도, 위도] 배열은 공통 연결부에서 이름 있는 필드로 변환한다. 투영 기준점은 gaesin_campus.json의 origin에서 읽는다.

현재 Android 어댑터의 위치는 GPS/NETWORK 후보이며 B의 BLE 융합 결과가 아니다. A/E의 GPS 미사용 안내 정책과 합쳐진 구현도 아니다. 통합 시 측위 정책은 공통 연결부에서 결정한다. 현재 3D 경로 안내는 정문 출발 데모로 유지되고 실제 기기 위치로 경로를 재계산하지 않는다.

## 아직 연결하지 않은 합의안 항목

선택 초기화(null destination), request_id/route_id 변환, 비동기 C 요청 취소, 구간별 trace 표시, 지도 회전, B 진행방향/불확실성 계약, 실제 위치 기반 경로 안내는 별도 통합 작업이다. 화면용 애니메이션 결과를 A/E 원시 기록으로 저장하지 않는다.

## 실행 테스트

1. 앱 지도에서 `내 위치 표시 허용` → 시스템 권한의 `앱 사용 중에만 허용`.
2. 실외 GNSS 수신을 기다리면 파란 점과 제공된 정확도 반경이 나온다. 실내·오프라인 최초 수신은 시간이 걸릴 수 있다.
3. 에뮬레이터에서는 Extended Controls → Location에서 개신캠퍼스 부근 시험 좌표를 지정하거나 `adb emu geo fix 127.4566 36.6286`을 사용한다. 이는 실제 휴대폰 GPS 검증이 아니다.
4. 지도 이동 후 내 위치, 전체 보기, 3D 둘러보기, 앱 백그라운드/복귀를 시험한다.
5. 위치 서비스를 끄면 회색 마지막 위치·서비스 꺼짐 상태, 권한을 철회하면 위치 점이 사라지는지 확인한다.
6. 대략적 위치만 허용하면 정밀 위치로 가장하지 않고 불확실 상태로 표시되는지 확인한다.

참고: Android 공식 위치 권한과 LocationManager 문서
- https://developer.android.com/develop/sensors-and-location/location/permissions
- https://developer.android.com/reference/android/location/LocationManager

# Campus Route · 충북대학교 개신캠퍼스

D-1의 검색·상세창과 D-2의 무채색 2D/3D 지도를 연결한 **실제 위치 기반 오프라인 길찾기 앱**입니다. 사용자가 걷는 위치에 따라 진행하며 자동 이동 시뮬레이션은 사용하지 않습니다.

## 실행

Android Studio에서 이 폴더를 열고 `app`을 실행합니다. JDK는 Android Studio JBR, Android SDK 37을 사용합니다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

1. 지도에서 `내 위치 표시 허용` → 앱 사용 중 정확한 위치 허용.
2. 개신캠퍼스 안에서 위치가 수신되면 건물 검색·선택.
3. `현재 위치에서 길찾기` → 실외/실내 출발 선택. 실내에서는 현재 건물 선택 후 출구 밖으로 나와 새 위치를 확인하고 안내를 시작합니다. 2D 지도 전환도 가능합니다.
4. 실제 이동에 따라 남은 거리·회전 안내를 갱신합니다. 이탈하면 현재 위치에서 다시 찾기를 누릅니다.
5. 목적지 인근 보행로 도착을 확인한 뒤 안내를 종료합니다.

## 포함한 기능

- d1-ui 검색·별칭 정규화·선택/취소·하단 상세창과 실제 지도 연결
- GPS/NETWORK 위치, 정확도 반경, 방향 센서 화살표, 지도 따라가기/해제
- 마커·3D·경로 진행에 동일한 검증 위치 사용, 위치 끊김/튐 시 함께 정지
- Galaxy S26+ 화면 비율 및 큰 글씨 대응: 안내 정보·지도·버튼 영역 분리
- 지도 확대·이동, 2D/3D 전환, 건물 윤곽을 이용한 OpenGL 입체 지도
- 둥근 손·머리를 가진 흰색 사람 모형(기존 대비 1.4배)과 조금 높은 내비게이션 시점
- 받은 공유 경로 탭: JSON 파일 가져오기·목록·초록색 미리보기·안내 시작·삭제
- 경로 색상: 직접 찾기는 파란색, 공유받은 경로는 초록색
- 현재 위치 인근 보행로 출발 A*, 경로 진행·이탈·도착 판단
- 권한 없음·대략적 위치·신호 공백·서비스 꺼짐 구분
- 화면 사용 중에만 위치/방향 수신. 위치 저장·서버 전송·백그라운드 위치 권한 없음

## 협업 및 제한

우리 담당은 D-2입니다. 사용자의 요청으로 D-1 브랜치의 화면을 가져와 독립 앱 호스트에서 연결했습니다. 기존 A/E 앱의 수집·설정 화면은 원본 `android/`에 보존되어 있으며 이 앱에 통합하지 않았습니다.

| 구성 | 역할 |
|---|---|
| `com.example.sensorae.ui.d1` | D-1 검색·선택·상세창 |
| `ui/map/CampusMapPanel.kt`, `rendering/` | D-2 지도·위치·방향·경로 표시 |
| `ui/CampusViewModel.kt`, `routing/LiveRouting.kt` | 독립 앱의 실제 위치 경로 연결 |
| `location/`, `DeviceLocationBinding` | A/B 완성 전 GPS·방향 센서 어댑터 |

A/B BLE 융합, 기록·공유, QR, 서버 전송은 미연결입니다. OSM 목적지는 검증된 출입구가 아닌 인근 보행로이며 실외 보행 현장 검증이 필요합니다. GPS 경로 진행 임계값은 B/C와 합의 전 임시 정책입니다.

- [건물 안/밖 출발 및 제한](docs/INDOOR_DEPARTURE.md)
- [공유 경로 사용법·파일 형식](docs/SHARED_ROUTES.md)
- [D-1/D-2 통합 계약·출처·정책](docs/D1_D2_INTEGRATION.md)
- [D-2 지도 입력 계약](docs/D2_INTEGRATION.md)
- [검증 기록](docs/VALIDATION.md)
- [OSM 데이터 출처](data/osm/README.md), [SMASH-CBNU ODbL 건물 윤곽 출처](data/smash-cbnu/README.md)

지도 범위는 개신캠퍼스입니다. OSM/SMASH-CBNU ODbL 고지를 유지합니다. 네이버 지도 이미지는 디자인 참고로만 사용했으며 지도 이미지·게임 코드를 복제하지 않았습니다. 이전 데모 구현 설명은 [이전 README](docs/README_PRE_LIVE.md)에 보관했습니다.

### 지도 정보 접기·펼치기
지도 왼쪽 아래의 지도 정보 버튼으로 위치 상태·방향·오프라인 지도 설명을 펼치거나 접습니다. 처음에는 펼쳐져 있으며 접으면 OpenStreetMap 출처도 함께 숨깁니다. 화면 회전 후에도 선택을 유지합니다.


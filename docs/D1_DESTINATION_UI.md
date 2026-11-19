# D-1 첫 구현 — 목적지 검색·선택·하단 상세창

## 기준 코드와 범위

- 저장소: sballgut/sensor_map
- 기준 브랜치: feature/ae-base
- 기준 커밋: e74c3ee8bb608d74f570d076b095e880143a3579
- 기존 A/E Android 앱의 첫 화면으로 D-1 UI를 추가했다.
- D-2 지도, B 위치 추정, C 실제 목적지·경로 계산은 아직 연결하지 않았다.
- 다른 브랜치 feature/campus-route-3d의 campus-route/는 Kotlin/Compose로 만든 별도 앱이다. 이번 수정이 그 앱과 자동 통합되는 것은 아니다. 공통 앱을 정한 뒤 화면 호스트/어댑터를 연결해야 한다.
- 로컬 작업 복사본에서 구현했으며 D-1 공유 브랜치는 d1-ui로 분리한다. 이 문서는 기본 브랜치 병합 완료를 의미하지 않는다.

## 되는 기능

1. 도서관·학생회관·대학본부·정문·공학관·체육관 등 시험용 목록 표시.
2. 건물 이름·별칭 검색. 공백, 영문 대소문자와 전각 영문을 정규화한다.
3. 검색 결과 없음 표시.
4. 목적지 선택 → 하단 이름·분류·설명·준비 상태 표시.
5. 선택 취소 → 검색 결과 목록으로 복귀.
6. 검색어를 바꾸면 이전 선택을 해제해 상세창과 지도 쪽 선택이 어긋나지 않게 한다.
7. 화면 회전/Activity 재생성 시 검색어·유효한 선택·수집 화면 여부 복원.
8. 수집·설정 버튼으로 A/E 화면 전환, 돌아가기 버튼으로 D-1 화면 복귀.

길찾기 버튼은 ‘경로 기능 준비 중’으로 비활성화한다. 허구의 좌표·거리·도착 시간·위치 정확도를 표시하지 않는다. 시험용 목록의 좌표는 모두 null이다. 실제 충북대 시설 목록이 검증됐다는 뜻이 아니다.

화면 전환은 같은 Activity 안에서 한다. 수집은 CollectionService가 담당하므로 목적지 화면 전환, 앱 밖 이동, Activity 재생성으로 중지되지 않는다. 실제 화면 꺼짐 상태의 BLE 수신 지속은 폰에서 확인해야 한다.

## D-2 연결 방법 — UI 스레드에서 사용

```java
destinationHome.attachMap(yourMapView, new DestinationSelectionTarget() {
    @Override public void onDestinationSelected(Destination destination) {
        // destination.id로 등록된 건물을 강조한다.
        // hasCoordinates()==false면 지도 이동 좌표를 임의로 만들지 않는다.
    }
    @Override public void onSelectionCleared() {
        // 선택한 건물 강조와 목적지 핀을 해제한다.
    }
});
```

- yourMapView는 다른 부모에 붙어 있지 않은 Android View여야 한다.
- 연결할 때 현재 선택을 즉시 다시 전달하므로 지도가 늦게 준비돼도 상세창과 동기화된다.
- 지도에서 건물을 누르면 `destinationHome.selectDestination(destinationId)`를 호출한다. 등록되지 않은 ID는 false를 반환하고 기존 선택을 유지한다.
- D-2는 TeamBridge 리스너·PositionSink를 교체하지 않는다. 기존 저장 처리와 위치 갱신의 통합은 별도 작업이다.
- 이번에는 경로·안내 이벤트를 구현한 척하는 빈 동작을 추가하지 않았다. C의 경로 계약이 정해진 뒤 추가한다.

## C 목적지 목록 연결

```java
destinationHome.setDestinations(verifiedDestinations);
```

Destination의 좌표는 latitude, longitude라는 이름 있는 필드로 제공한다. GeoJSON에서 변환할 때만 `[longitude, latitude]` 순서를 사용한다. 두 값 모두 없거나, 둘 다 유효한 WGS84 값이어야 한다. 새 목록에서 선택 ID가 사라지면 선택도 해제한다. 새 목록에서 같은 ID의 정보가 갱신되면 그 최신 객체를 지도에 전달한다.

시험용 DemoDestinations와 실제 목적지를 구분한다. 실제 데이터 연결 후 isDemo=false로 표시하되, 좌표·시설 정보가 검증되었는지 C와 확인한다.

## 파일 분담

| 파일 | 역할 |
|---|---|
| ui/d1/Destination.java | 목적지 데이터 |
| ui/d1/DemoDestinations.java | 좌표 없는 시험 자료 |
| ui/d1/DestinationSearchController.java | 검색·선택·복원 로직, Android 의존성 없음 |
| ui/d1/DestinationSelectionTarget.java | D-2로 전달하는 선택·취소 이벤트 |
| ui/d1/DestinationHomeView.java | D-1 검색·상세 화면과 지도 호스트 |
| MainActivity.java | 첫 화면·수집 화면 배치와 상태 복원 |

## 확인 결과

- Android API 37 기준 main Java 전체 컴파일 통과.
- JVM 테스트 36개 통과: 기존 24개 + D-1 12개.
- D-1 테스트: 한글/별칭/정규화 검색, 빈 검색·결과 없음, 선택/취소·지도 전달, 검색 변경 시 선택 해제, 잘못된 ID, 늦은 지도 연결, 선택 복원, 자료 교체, 중복 ID, 잘못된 좌표 거부.
- SDK 도구로 APK 생성, 서명·정렬 검사 통과.
- Gradle 전체 빌드·lint·실기기 UI/키보드/터치/회전·실제 BLE 수신은 이번 검사에서 완료하지 않았다. 컴파일·JVM 결과와 구분한다.
- Android Studio에서 AGP 9.4.1 호환 오류가 확인되어 로컬 프로젝트의 플러그인을 기존 실행 프로젝트와 같은 AGP 9.3.2로 맞췄다. Gradle 9.6.0과 JVM toolchain 설정은 유지했다. 이번 APK는 수동 SDK 빌드 결과이며, 이 설정 변경 후 Studio 동기화는 사용자 환경에서 다시 확인한다.

## 폰 확인 순서

1. 첫 화면 상단의 ‘시험 모드 · 목적지 예시’를 확인한다.
2. 검색창에 ‘도서’, ‘학 생 회 관’, 없는 건물 이름을 차례로 입력한다.
3. 도서관 선택 → 하단 상세창과 지도 연결 전 영역의 이름을 확인한다.
4. 선택 취소·지우기를 확인한다.
5. 건물 선택 후 화면을 회전하고 선택 복원을 확인한다.
6. 수집·설정 화면으로 갔다가 돌아와 선택이 유지되는지 확인한다.
7. 작은 화면·큰 글꼴·키보드 표시 상태에서 하단창을 스크롤할 수 있는지 확인한다.

## 팀 브랜치에 반영할 때

패치는 위 기준 커밋의 A/E 앱을 대상으로 한다. 현재 main은 README만 있으므로 main에 바로 적용하지 않는다. 작업 폴더의 수정 내용을 먼저 확인한 뒤 feature/ae-base를 기준으로 D-1 브랜치를 만든다. 기존 브랜치/파일이 있으면 덮어쓰지 말고 변경을 비교한다. 별도 제공한 패치는 먼저 `git apply --check`로 검사한 후 적용한다.

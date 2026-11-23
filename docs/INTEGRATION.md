# B·C·D 연동 및 A·E 구현 범위

이 프로젝트는 A의 수집·저장·전송 경로와 E의 수신·후보 검토·버전 배포 경로를 연결할 수 있게 만든 실행용 뼈대입니다. B의 위치 추정 모델, C의 실제 보행 그래프·경로 탐색, D의 완성된 길 안내 화면은 포함하지 않았습니다. 화면의 `guidance`는 GPS를 요청하지 않는 실행 모드이며, 현재 앱이 길 안내를 완성했다는 뜻이 아닙니다.

## 1. 서로 다른 세 종류의 데이터

| 데이터 | 가져오는 곳 | 사용 목적 | 섞으면 안 되는 값 |
| --- | --- | --- | --- |
| 현재 휴대폰 BLE 관측 | 주변 광고의 서비스 데이터 `0x181A` | RSSI 창, 원본 보관, B 추정 입력 | 서버 최신값을 현재 BLE 수신처럼 생성하지 않음 |
| 공용 최신값·설치 좌표 | `GET /sensor/api/map` | 센서 레지스트리, 설치 위치 캐시, 환경값 표시 | 설치 좌표를 휴대폰 `gps_*`에 대입하지 않음 |
| 공용 과거 데이터 | `tools/fetch_history.py`의 `GET /sensor/api/history` | 별도 분석 자료 | 과거 HTTP 결과를 현재 휴대폰 관측 대기열에 넣지 않음 |

앱은 `sensor 01`~`sensor 34`를 인식하고 전체 목록에 표시합니다. 공용 API가 모든 센서의 최신값을 제공하는 것은 아니므로 누락된 센서는 `API 미제공`으로 표시합니다. BLE에서는 휴대폰 주변에서 실제 수신된 센서만 관측됩니다. HTTP API로 캠퍼스 전체 센서의 임의 원시 BLE 패킷·휴대폰별 RSSI를 가져오는 기능은 제공되지 않습니다.

공용 `/sensor/sensing/`은 GET 형식이어도 서버에 데이터를 **저장하는** API입니다. 조회·테스트 목적으로 호출하지 않으며 현재 앱은 여기에 쓰지 않습니다. 휴대폰 관측의 배치 ACK는 별도 E 서버가 담당합니다.

## 2. 센서 ID와 패킷 해석

`SensorIdentity.normalize()`의 표준 출력은 공백을 포함한 `sensor 07` 형태입니다. `sensor7`, `sensor 07`, `sensor #7` 같은 이름을 같은 ID로 바꿉니다. `opensrc1`~`opensrc5`는 각각 `sensor 07`, `sensor 15`, `sensor 31`, `sensor 32`, `sensor 33`으로 매핑합니다. 이름의 대소문자는 무시합니다.

ID만 같다고 패킷 종류까지 같지는 않습니다. `PacketParser`는 **실제 광고 이름**으로 LAB/OPEN_SOURCE를 구분합니다. 예를 들어 `opensrc1`은 13바이트 계열이고 `sensor 07`은 LAB 8바이트 계열입니다. 파싱 전에 광고 이름을 표준 ID로 덮어쓰지 마세요. 예전 앱의 `Opensrc_team4`와 같은 별도 이름은 현재 API v4 별칭 목록에 없으므로 자동으로 특정 센서에 대응시키지 않습니다.

| 종류 | 길이 | Little-endian 필드 순서 |
| --- | --- | --- |
| LAB | 정확히 8바이트 | CO₂ uint16 → 온도 int16 / 100 → 센서 Unix 시각 uint32 |
| OPEN_SOURCE | 13바이트 이상 | 온도 int16 / 100 → 습도 uint16 / 100 → AQI uint8 → TVOC uint16 → eCO₂ uint16 → 센서 Unix 시각 uint32 |

OPEN_SOURCE의 eCO₂도 공통 필드 `co2`에 저장되므로 B·D의 환경값 표시는 센서 종류를 함께 고려해야 합니다. TVOC는 ppb, CO₂/eCO₂는 ppm, 온도는 °C, 습도는 %입니다. 뒤에 붙은 검증 바이트는 원문에 보존하지만 인증 성공으로 해석하지 않습니다.

`parse_status`는 `parsed`, `parsed_trailing_unverified`, `unknown_name`, `missing_service_data`, `length_mismatch` 중 하나입니다. 이름·길이를 해석할 수 없으면 환경값은 null이고 RSSI·원본 hex는 남습니다. 현재 스캐너는 `0x181A` 서비스 데이터를 가진 광고를 대상으로 하며, 서비스 데이터가 전혀 없는 임의의 BLE 기기까지 저장하는 범용 스캐너는 아닙니다.

## 3. Observation 계약

정의 파일은 `android/app/src/main/java/com/example/sensorae/Observation.java`입니다. JSON 필드명과 CSV 열 이름을 연동 기준으로 사용하세요.

| 필드 | 단위·형태 | 의미 |
| --- | --- | --- |
| `record_id` | UUID 문자열 | 관측별 불변 ID. 재전송 시 유지 |
| `session_id` | UUID 문자열 | 수집 시작마다 새로 생성 |
| `device_id` | 설치별 UUID 문자열 | 앱 설정에 저장된 가명 ID. 앱 데이터 삭제 시 변경 가능 |
| `sensor_id` | `sensor 01`~`sensor 34` 또는 null | 통합 센서 ID |
| `advertised_name`, `mac` | 문자열 | 실제 광고 이름과 BLE 주소. MAC을 표준 센서 ID로 대신 쓰지 않음 |
| `rssi`, `raw_hex` | dBm 정수, hex 문자열 | 각 수신의 RSSI와 서비스 데이터 원문 |
| `utc_ms` | Unix epoch 밀리초 | 휴대폰이 수신한 벽시계 시각 |
| `elapsed_ns` | 부팅 후 단조 시각 나노초 | 같은 부팅 내 간격·창 계산 기준 |
| `sensor_timestamp` | Unix epoch 초 또는 null | 센서 payload 시각. 수신 시각과 별개 |
| `mode`, `parse_status` | 문자열 | 실행 모드 및 파싱 결과 |
| `temperature`, `humidity`, `aqi`, `tvoc`, `co2` | nullable 숫자 | 해석 가능한 환경값만 제공 |
| `gps_lat`, `gps_lon`, `gps_accuracy`, `gps_age_ms` | 도, 도, m, ms 또는 null | 수집·평가용 휴대폰 위치 라벨 |
| `heading_deg`, `heading_accuracy`, `heading_source`, `fixed_declination_deg` | nullable 값 | 휴대폰 방향과 방향 품질·보정 정보 |
| `candidate_consent` | boolean | 해당 관측 당시 세션의 후보 기록 동의 |
| `reference_point_id` | 선택 문자열 | 사용자가 입력한 기준 지점 ID |

UTC 값은 휴대폰 시간 설정에 영향을 받습니다. `elapsed_ns`는 재부팅 전후를 직접 이어 붙일 수 없으며 서로 다른 폰의 절대 시각 비교에 쓰면 안 됩니다. B의 창·실험 분석은 세션을 구분하고 센서 시각, 휴대폰 시각, 서버 수신 시각을 따로 취급하세요.

현재 코드는 반복 광고도 개별 RSSI 관측으로 보관합니다. 동일 센서의 같은 payload가 여러 번 들어와도 각각 새로운 `record_id`가 생깁니다. B의 집계·중복 측정 처리 기준은 별도로 정하되 원시 관측을 임의 삭제하지 마세요.

`ObservationStore`는 SQLite에 원본 JSON과 ACK 상태를 저장합니다. 같은 ID·같은 내용의 삽입은 중복 저장하지 않고, 같은 ID에 다른 내용을 덮어쓰려고 하면 거부합니다. `pending()`, `acknowledge()`, `recent()`, `session()`, `exportCsv()`가 제공됩니다. 전송 완료 후에도 원본 관측은 남습니다.

## 4. 모드, 위치 권한, 방향

| 모드 | GPS/network 위치 provider 요청 | 관측 `gps_*` | 주 용도 |
| --- | --- | --- | --- |
| `guidance` | 없음 | 전부 null | GPS 없이 B의 위치 추정·D 연동 |
| `collection` | 가능 | 유효한 최신 위치가 있을 때만 기록 | 학습·현장 수집 라벨 |
| `evaluation` | 가능 | 유효한 최신 위치가 있을 때만 기록 | 기준 위치와 추정 결과 평가 |

BLE를 실제 위치 추정에 사용하므로 앱은 안내 모드에서도 위치 권한을 요청합니다. **위치 권한을 허용하는 것과 GPS provider를 호출하는 것은 다른 동작**입니다. Android 12 이상에서는 주변 기기 권한도 요청합니다. 안내 모드는 `requestLocationUpdates()`를 호출하지 않으며 로컬 저장소·E 서버도 안내 관측의 non-null `gps_*`를 거부합니다. B·D도 안내 경로에서 별도로 GPS를 호출하지 않아야 합니다.

위치 라벨은 현재 코드에서 수신 시점 기준 나이 30초 이내, 정확도 100m 이내만 사용합니다. 이것은 정확한 정답 위치를 보장하는 기준이 아닙니다. B의 학습·E의 평가 기준에 맞춰 더 엄격한 필터와 기준 지점 절차를 합의해야 합니다.

`HeadingTracker`는 `TYPE_ROTATION_VECTOR`를 우선 사용하고 자력계+가속도계로 대체합니다. 게임 회전 벡터는 사용하지 않습니다. 화면 회전을 반영하며, 보정각은 사용자 고정값이고 GPS로 자동 계산하지 않습니다. 기본값 0을 보정된 진북이라고 표시하지 마세요. `degrees == null`은 미지원·부정확·거의 수직 자세 등으로 방향을 제공할 수 없다는 뜻입니다.

방향은 **휴대폰이 향한 방향**이며 보행 방향과 같다고 가정하지 않습니다. D는 북쪽 기준/경로 기준 회전, 신뢰도, 값의 최신성, 휴대폰을 손에 든 자세를 고려해야 합니다. 관측에는 최대 2초 이내 방향 상태만 연결됩니다.

## 5. B·D 연결: TeamBridge

`MainActivity.bridge`가 공개된 연결 지점입니다.

```java
public interface Listener {
    void onObservationWindow(List<Observation> observations);
    void onHeading(HeadingTracker.HeadingState state);
}
```

`bridge.setListener(...)`로 **하나의** 리스너를 등록합니다. B와 D가 각자 덮어쓰지 말고 공용 어댑터에서 관측은 B로, 방향은 D로 분배하세요. 콜백은 현재 앱의 추론 executor에서 들어오므로 화면 변경은 main thread로 전달해야 합니다. TeamBridge 내부 동기화 구간에서 콜백을 호출하므로 오래 걸리는 모델 추론은 별도 작업으로 넘기고 빠르게 반환하세요.

관측 창은 최근 수신의 `elapsed_ns` 기준 10초, 최대 3,000건입니다. **새 관측이 들어올 때** 최대 초당 1회 전달합니다. 수신이 끊겼을 때 빈 창을 주기적으로 보내는 타이머는 없습니다. B·D는 별도의 시각 기준으로 수신 공백을 판단하고 오래된 위치를 현재 위치처럼 표시하지 않아야 합니다. `snapshot()`도 새 수신이 없으면 이전 창을 반환할 수 있습니다. 목록은 수정 불가이며 요소는 원본과 분리된 복사본입니다.

B의 결과는 다음 형태로 돌려줍니다.

```java
TeamBridge.PositionEstimate p = new TeamBridge.PositionEstimate();
p.session_id = observations.get(observations.size() - 1).session_id;
p.utc_ms = /* 결과를 낸 실제 UTC 밀리초 */;
p.elapsed_ns = /* 해당 결과의 단조 시각 나노초 */;
p.status = /* B와 D가 합의한 상태 코드 */;
p.model_version = /* 실제 사용한 모델 버전 */;
p.latitude = /* 실제 추정 위도 또는 null */;
p.longitude = /* 실제 추정 경도 또는 null */;
p.uncertainty_m = /* B가 산출한 불확실성 또는 null */;
activity.bridge.submitPosition(p);
```

위 코드는 필드 연결 예시이며 주석을 실제 값으로 바꿔야 합니다. 상태 코드와 불확실성의 통계적 의미는 아직 정의되지 않았으므로 B·D가 합의해야 합니다. 예측값은 유한한 숫자만 사용하세요. A가 이미 설치한 `PositionSink`를 교체하지 마세요. 현재 sink는 실행 중이고 후보 동의가 있으며 같은 세션인 결과만 `candidate_positions.jsonl`에 기록합니다. 실제 위치 추정이 없으면 이 파일에 가짜 좌표를 만들지 않습니다.

마지막 세션 재생은 저장된 관측을 같은 `TeamBridge`로 보내는 읽기 전용 기능입니다. 원본을 재저장하거나 전송하지 않습니다. 긴 관측 간격은 최대 2초로 줄여 재생하지만 관측에 든 원래 시각은 유지합니다. B는 재생 시 현재 부팅 시각을 원본과 직접 비교하지 말고 관측 시각을 기준으로 계산해야 합니다. 재생을 성능·실시간 지연 시험과 혼동하지 마세요.

## 6. A → E 전송 계약

앱 설정의 E 서버 URL과 `APP_TOKEN`을 사용합니다. 공용 map/history API 주소·키와 별개입니다. 관리용 `ADMIN_TOKEN`을 앱에 넣지 않습니다.

```json
POST /v1/observations/batch
Authorization: Bearer APP_TOKEN

{"batch_id":"배치-UUID","records":[{"record_id":"관측-UUID","...":"Observation 필드"}]}
```

```json
{"batch_id":"동일한-배치-UUID","results":[
  {"record_id":"관측-UUID","status":"stored"},
  {"record_id":"다른-관측-UUID","status":"duplicate"},
  {"record_id":"오류-관측-UUID","status":"error","error":"검증 오류 내용"}
]}
```

앱은 한 번에 최대 100건, 서버는 배치당 최대 200건을 허용합니다. 응답의 batch_id가 맞고 이번 요청에 실제 포함된 record_id가 `stored`/`duplicate`로 명시된 경우만 완료 처리합니다. 누락·오류·알 수 없는 ID·HTTP 실패는 ACK로 간주하지 않습니다. 하나의 ID에 모순된 결과가 반복되면 그 ID도 완료 처리하지 않습니다.

현재 앱은 서버 설정이 있으면 약 30초 간격 및 수동 버튼으로 전송합니다. 실패한 관측은 SQLite에 남으며 앱을 다시 열고 서버 설정을 유지하면 재전송 대상이 됩니다. 재시도 때 batch_id가 새로 생겨도 record_id가 같아 서버 중복 저장은 방지됩니다. 영구 오류 레코드는 그대로 대기하며 뒤의 레코드가 계속 시도될 수 있도록 마지막 시도 시각으로 순서를 회전합니다. 오류 레코드를 자동 수정해 같은 ID로 덮어쓰지는 않습니다.

서버 실행, 정확한 필수 필드, 오류 코드와 인증은 [`../server/README.md`](../server/README.md)를 기준으로 확인하세요.

## 7. 후보의 동의·근거·검토

후보 동의는 세션 시작 전에 받으며 수집 중에는 바꾸지 못합니다. 모든 관측은 일반 실험 데이터로 로컬 저장되지만, 후보 동의가 없는 관측은 후보 근거로 사용할 수 없습니다. 동의 취소는 이후 세션의 동작에 적용되며 이미 제출한 서버 후보가 자동 삭제되지는 않습니다.

현재 제출 버튼은 해당 실행에서 생성한 동의 세션을 대상으로 다음 내용을 `POST /v1/candidates`에 보냅니다.

- `consent: true`, `session_id`, 사용자가 쓴 `description`
- `evidence_record_ids`: 해당 세션의 앞쪽 최대 5,000건 중 동의한 관측 ID
- `position_estimates`: 같은 세션으로 기록된 B의 결과 최대 5,000건

**관측 배치를 먼저 전송해야 합니다.** 앱은 중지한 세션의 관측에 모두 ACK를 받은 뒤 저장한 UUID와 본문으로 후보를 제출합니다. 응답 유실 후에는 ‘저장한 후보 · 상태 확인 및 재전송’에서 같은 후보를 선택합니다. 같은 서버에 같은 내용으로 다시 보내면 기존 후보 ID를 받습니다. 제출 대기 상태는 앱 화면이 열려 있을 때 약 30초마다 다시 확인합니다.

수집 앱에서는 관측 근거만 있는 실험 후보도 제출할 수 있습니다. 통합 지도 앱의 경로 제출에는 B의 실제 위치 추정과 D의 동의 기록 화면을 연결해야 합니다. 수집·비교 모드의 수신 창은 별도 CSV로 내보낼 수 있으며 업로드 규격은 [서버 API 계약](../server/README.md)에 있습니다.

관리자 웹 화면과 API가 `submitted → under_review → approved/rejected`를 관리합니다. `published`는 승인 후보를 연결한 패키지를 발행할 때만 부여합니다. 철회는 검토 이력을 남기며 발행된 후보의 철회만으로 이미 배포한 그래프가 수정되지 않습니다. C가 수정 패키지를 만들거나 E가 이전 배포로 복구해야 합니다. 서버는 후보가 실제 통행 가능한 길인지 자동 판단하지 않습니다.

## 8. B·C·D 배포 파일 계약

C/B가 서버 PC의 한 폴더에 다음 파일을 준비합니다. 현재 실제 지도·그래프·모델 파일은 포함하지 않았습니다.

| 서버 입력 파일 | manifest의 name | 담당·합의 사항 |
| --- | --- | --- |
| `map.json` | `map` | C/D: 표시 지도 데이터 형식·좌표계·이용 범위 |
| `graph.json` | `graph` | C: 보행 가능 노드/간선, 비용, 경로 제한 |
| `destinations.json` | `destinations` | C/D: 목적지 ID·표시명·그래프 연결 |
| `model.json` | `model` | B: 모델·전처리·입력 센서 순서·출력 좌표계 |

현재 서버는 네 JSON 파일의 존재와 JSON 문법을 검사합니다. 파일 내부 의미의 호환성이나 실제 경로·모델 정확성까지 검증하지 않습니다. TFLite 등 바이너리 모델이 필요하면 `model.json`으로 이름만 바꾸지 말고 서버 패키징과 앱 로더 계약을 함께 확장해야 합니다.

E가 `POST /v1/admin/releases`로 발행하면 `GET /v1/releases/current`에서 다음 구조를 반환합니다.

```json
{
  "version": "team-release-001",
  "compatibility": 1,
  "assets": [
    {"name":"map", "url":"/v1/assets/team-release-001/map.json", "size":123, "sha256":"실제-64자리-hex"},
    {"name":"graph", "url":"/v1/assets/team-release-001/graph.json", "size":456, "sha256":"실제-64자리-hex"},
    {"name":"destinations", "url":"/v1/assets/team-release-001/destinations.json", "size":78, "sha256":"실제-64자리-hex"},
    {"name":"model", "url":"/v1/assets/team-release-001/model.json", "size":90, "sha256":"실제-64자리-hex"}
  ]
}
```

위 길이·해시는 구조 예시입니다. 서버가 실제 파일에서 생성합니다. 현재 `compatibility: 1`과 네 항목 전체가 필요합니다. 자산별 독립 버전은 없으며 한 묶음의 `version`으로 네 파일의 조합을 고정합니다. 내부 스키마를 바꾸면 B/C/D 로더와 compatibility 정책도 함께 바꿔야 합니다.

앱 `ReleaseInstaller`는 임시 다운로드 → 전체 크기/SHA-256 검증 → 버전 폴더 확정 → AtomicFile의 활성 포인터 교체 순서로 설치합니다. 같은 버전을 다른 내용으로 덮어쓰지 않습니다. 파일당 20MiB 제한이며 설정 서버와 동일 origin만 허용합니다. 실패 시 기존 활성 버전을 유지합니다.

B/C/D는 `activeDirectory()` 아래 **확장자 없는** 파일 `map`, `graph`, `destinations`, `model`과 `manifest.json`을 읽습니다. 이 디렉터리는 읽기 전용으로 취급하세요. 서버 입력 파일명과 앱 내부 저장 파일명은 서로 다릅니다. 최초 실행에는 활성 디렉터리가 없을 수 있으므로 null/미설치 상태를 정상 처리해야 합니다.

안내 시작 때 `pinForGuidance()`로 현재 버전을 고정하고 안내 종료 때 `unpin()`합니다. 고정 중 설치 활성화·롤백은 거부됩니다. D가 독립된 안내 lifecycle을 추가할 때도 이 순서를 유지하고, B/C는 한 안내 중 서로 다른 버전의 파일을 섞어 읽지 않아야 합니다. 설치/롤백은 작업 스레드에서 호출합니다. 상세 제약은 [`updates.md`](updates.md)에 있습니다.

## 9. 요구사항별 현재 상태

| A·E 항목 | 현재 구현 | 아직 필요한 확인·연동 |
| --- | --- | --- |
| 두 종류 BLE 파싱, 실패 시 RSSI 보관 | 구현 | 실제 각 기종 광고 형식·이름·서비스 UUID 확인 |
| 34개 ID, 별칭, direct/mobile 최신값 통합 | 구현 | 실제 미제공 센서는 임의 보충하지 않음 |
| 로컬 원본·시각·CSV·재생 | 구현 | Android SQLite 재시작/저장공간·대규모 세션 시험 |
| 안내 GPS 미사용, 수집·평가 라벨 | 구현 | 권한 조합별 실기기 GPS 요청 0 확인 |
| 방향·정확도·고정 보정 | 구현 | 기종별 센서, 회전/자기장/자세 확인; 보행 방향 추정은 별도 |
| B/D 관측 창·방향 인터페이스 | 구현 | B 모델과 D 화면 연결, 상태·불확실성 계약 |
| 대기열, 레코드 ACK, 재전송 | 구현 | 실제 폰↔서버 단절·ACK 유실·재실행 시험 |
| 후보 동의·BLE 근거·추정 결과 저장 | 기본 경로 구현 | 실제 B 결과, 사진, 긴 세션/이전 세션 UI |
| 후보 관리자 검토·상태·철회·이력 | 구현 | C의 현장 검증과 운영 검토 절차 |
| 버전 manifest·해시·교체·복구 | 구현 | 실제 B/C 자산, 파일 의미 검증, 중단 복구 실기기 시험 |
| 실제 위치 추정·경로 안내·그래프 생성 | 미포함 | B/C/D 담당 구현 |
| 화면 꺼짐·백그라운드 장시간 수집 | 구현, 실기기 확인 필요 | CollectionService + 알림, 앱/알림에서 명시적 중지 |

Activity의 `onStop()`은 화면 갱신/재생만 중지합니다. BLE·방향·GPS 라벨·저장은 Application context만 보유하는 CollectionEngine, 실행 수명과 알림은 CollectionService가 담당합니다. `MainActivity.bridge`는 같은 프로세스의 공유 TeamBridge를 가리킵니다. B/D 리스너에 Activity/View를 장기간 보관하지 마세요. 안내 중 ReleaseInstaller의 버전 고정도 같은 공유 인스턴스를 사용합니다. 원래 5분 이후 수신 간격 문제가 실기기에서 해결됐다는 의미는 아닙니다. 상세 동작/시험은 BACKGROUND_COLLECTION.md를 참고하세요.

## 10. 함께 수행할 통합 시험

1. LAB 8바이트와 OPEN_SOURCE 13바이트의 실제 센서로 온도·CO₂ 순서, 별칭, timestamp 단위를 확인합니다. 일부러 길이가 잘못된 테스트 입력도 RSSI와 상태가 남는지 확인합니다.
2. 두 폰에서 동일 센서를 15~30분 이상 수집합니다. 벽시계·센서시각·단조 간격을 구분해 수신 공백을 비교하고 서버가 못 받은 것과 BLE부터 못 받은 것을 나눠 봅니다.
3. 안내에서 GPS 요청 0과 CSV/서버의 모든 `gps_* == null`을 확인합니다. 수집·평가에서만 라벨을 얻고 모드 전환 시 이전 위치가 남지 않는지 확인합니다.
4. 통신을 끊고 관측을 쌓은 뒤 재연결·앱 재실행합니다. 서버 저장 직후 응답이 사라지는 경우도 재전송 후 원본 1건만 남고 앱이 정확한 ID만 완료 처리하는지 확인합니다.
5. B를 연결해 창에 오래된 관측이 있을 때의 상태, null 환경값, 미식별 ID, 세션 전환, 원본 시각 기반 재생을 확인합니다. D는 미지원·불확실 방향과 위치를 정상 표시해야 합니다.
6. 동의 없는 관측 후보 제출은 거부되는지, 동의한 관측과 실제 B 결과가 같은 세션으로 연결되는지 확인합니다. 관리자 승인만으로 곧바로 안내 그래프가 바뀌지 않는지도 확인합니다.
7. 실제 네 파일을 발행하고 앱에서 설치합니다. 다운로드 중 네트워크 끊김·해시 불일치·저장 공간 부족·프로세스 종료·안내 시작 경합 시 기존 버전을 유지하는지 확인합니다. 이전 버전 복구 후 B/C/D가 같은 묶음을 읽는지 확인합니다.

단위 테스트와 로컬 코드 검사는 이 시험을 대신하지 않습니다. 실행·테스트 명령은 각 `server/README.md`, `tools/README.md`와 프로젝트 안내를 따르세요.

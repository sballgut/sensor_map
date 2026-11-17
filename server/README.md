# E 역할: 팀 서버 뼈대

Python 3.10 이상 표준 라이브러리(`ThreadingHTTPServer`, `sqlite3`)만 사용하는 실행 가능한 참고 서버입니다. 설치할 외부 패키지가 없습니다. 이 서버가 저장하는 것은 앱이 실제로 수집한 관측이며, 공용 센서 서버와 별도입니다. 공용 `/sensor/sensing/`을 호출하지 않습니다.

## 실행

서버 폴더에서 PowerShell로 실행합니다. 두 토큰은 서로 다른 임의의 긴 값으로 지정하세요.

```powershell
$env:APP_TOKEN = '여기에-앱-전용-토큰'
$env:ADMIN_TOKEN = '여기에-별도의-관리자-토큰'
python api.py --host 127.0.0.1 --port 8080 --data data
```

같은 Wi-Fi의 실제 휴대폰으로 연결할 때는 `--host 0.0.0.0`으로 실행하고 앱에 `http://서버PC의LAN주소:8080` 및 APP_TOKEN을 입력합니다. 필요하면 PC 방화벽에서 해당 포트의 사설 네트워크 접근을 허용합니다. Android 에뮬레이터에서는 일반적으로 호스트 주소 `10.0.2.2`를 사용합니다.

`http://127.0.0.1:8080/admin`에서 관리자 토큰을 입력하면 후보 목록·상세 근거·검토 이력을 보고 검토/승인/거절/철회를 할 수 있습니다. 토큰은 브라우저 저장소에 보관하지 않습니다. 관리자 토큰은 앱에 넣지 마세요.

이 코드는 로컬/LAN 통합 시험용입니다. 인터넷에 운영 배포할 때는 TLS, 계정별 권한, 토큰 회전, 요청 제한, DB 백업, 운영 로그 및 서비스 실행 관리가 추가로 필요합니다. 실제 배포는 수행하지 않았습니다.

## API 계약

### 관측 배치

`POST /v1/observations/batch`, 헤더 `Authorization: Bearer APP_TOKEN`

```json
{
  "batch_id": "고유한-배치-UUID",
  "records": [{
    "record_id": "고유한-관측-UUID",
    "session_id": "수집-세션-UUID",
    "device_id": "설치별-가명-UUID",
    "sensor_id": "sensor 07",
    "rssi": -63,
    "raw_hex": "001122",
    "utc_ms": 1790000000000,
    "elapsed_ns": 5000000000,
    "mode": "guidance",
    "parse_status": "length_mismatch",
    "gps_lat": null,
    "gps_lon": null
  }]
}
```

위 예시는 계약 설명용이며 실제 센서 데이터가 아닙니다. 한 배치는 1~200건입니다. `sensor_id`는 식별 실패 시 null을 허용합니다. `raw_hex`는 서비스 데이터가 없으면 빈 문자열을 허용합니다. 손상 패킷도 RSSI와 parse_status를 보존합니다. mode는 guidance/collection/evaluation이며 대소문자를 구분하지 않고 검증합니다. guidance의 GPS는 반드시 null이어야 합니다. collection/evaluation도 아직 위치를 얻지 못한 관측은 GPS null을 허용합니다.

추가 필드(광고 이름, MAC, 환경값, 방향 상태, GPS 정확도, 기준 지점 등)는 원본 JSON에 함께 저장됩니다. 숫자의 NaN/Infinity는 허용하지 않습니다. UTC epoch는 ms, 단조 시각은 ns이며 sensor timestamp 단위와 구분하세요.

```json
{
  "batch_id": "고유한-배치-UUID",
  "results": [
    {"record_id": "고유한-관측-UUID", "status": "stored"}
  ]
}
```

- `stored`: 새 레코드 저장 완료.
- `duplicate`: 같은 record_id의 내용이 완전히 동일해 기존 1건을 유지.
- `error`: 유효성 오류 또는 같은 record_id의 내용 충돌. 오류 문자열도 반환.
- ACK를 잃으면 같은 배치 ID와 같은 내용을 재전송해도 기존 응답을 반환합니다. 다른 배치 ID로 같은 관측을 재전송해도 중복 저장하지 않습니다.
- 같은 batch_id의 내용을 바꾸면 HTTP 409입니다. 오류를 수정해 다시 보낼 때는 새 배치 ID를 사용하세요. 이미 저장된 record_id의 측정 내용은 바꿀 수 없습니다.
- 클라이언트는 자기 배치에 속한 record_id 중 stored/duplicate로 명시된 것만 완료 처리해야 합니다.

### 후보 제출

`POST /v1/candidates`, APP_TOKEN 필요.

```json
{
  "consent": true,
  "session_id": "수집-세션-UUID",
  "description": "새 보행로로 추정되는 구간과 현장 설명",
  "evidence_record_ids": ["서버에-저장된-관측-UUID"],
  "position_estimates": [{"utc_ms": 1790000000000, "latitude": 36.62, "longitude": 127.45, "uncertainty_m": 20, "model_version": "B팀원-모델버전"}],
  "photo_refs": ["팀이 관리하는 사진의 참조 경로"]
}
```

응답은 `{candidate_id,status:"submitted"}`입니다. 명시적 동의가 필요하며 모든 근거 레코드는 서버에 존재하고 같은 session_id이며 관측 당시 `candidate_consent:true`여야 합니다. 제출 시에만 동의했다고 과거 비동의 기록을 근거로 사용할 수 없습니다. position_estimates는 최대 5,000개 객체, photo_refs는 최대 50개 문자열 참조(각 2,048자)를 허용합니다. NaN/Infinity는 허용하지 않습니다. 사진 바이너리 업로드·보관 및 지도상 미리보기는 이 뼈대에 포함하지 않았습니다. 위치 추정·불확실성은 B가 실제 생성한 값을 연결해야 하며 서버가 임의로 만들지 않습니다.

### 후보 검토

아래 API는 `Authorization: Bearer ADMIN_TOKEN`이 필요합니다.

- `GET /v1/admin/candidates`: 후보 요약 목록.
- `GET /v1/admin/candidates/{id}`: 제출 근거·사진 참조·전체 검토 이력.
- `POST /v1/admin/candidates/{id}`: `{status,reason}`. 사유 필수.
- 정상 흐름: submitted → under_review → approved 또는 rejected.
- rejected → under_review 재검토 가능.
- published는 패키지 발행 때만 전환 가능. UI 상태 변경만으로 발행할 수 없음.
- submitted/under_review/approved/rejected/published → withdrawn 철회 가능. 이력은 남음.
- 이미 발행한 후보를 철회하면 `deployment_action_required:true`를 반환합니다. 실제 경로에서 없애려면 수정 패키지 발행 또는 이전 버전 복구를 별도로 수행해야 합니다.

후보는 자동 승인하거나 서로 합치지 않습니다. 실제 길인지, 건물을 관통하는지, 출입 제한이 있는지는 C/E의 검토가 필요합니다.

### 배포와 롤백

`POST /v1/admin/releases`, ADMIN_TOKEN 필요:

```json
{
  "version": "2026-11-24-v1",
  "compatibility": 1,
  "source_dir": "C:/SensorPackages/approved-v1",
  "candidate_ids": ["승인된-후보-ID"]
}
```

source_dir는 **서버 PC**의 폴더이며 다음 네 파일이 필요합니다.

- map.json: 오프라인 지도/표시 자산의 합의된 JSON 표현
- graph.json: C가 검토한 보행 그래프
- destinations.json: C가 만든 목적지
- model.json: B가 제공한 모델 및 전처리 정보의 합의된 JSON 표현

이 뼈대는 JSON 문법·파일 존재·파일당 20MiB 제한·compatibility를 검사합니다. 지도 이용 허가, 보행 가능성, 추론 정확성, B/C 파일 내부 스키마의 의미적 호환성 검사는 팀이 추가해야 합니다. 실제 자산은 포함하지 않았습니다. 테스트용 자산은 테스트 중에만 임시 생성합니다.

최초 패키지는 candidate_ids를 빈 배열로 제출할 수 있습니다. 후보를 연결한다면 모두 approved여야 합니다. 승인된 후보를 파일에 실제 반영하는 작업은 C의 책임이며 서버는 그래프를 자동 생성하지 않습니다.

발행은 임시 폴더에 복사 → SHA-256 생성 → 불변 버전 폴더로 이동 → DB의 현재 버전 갱신 순서입니다. 동일 버전 덮어쓰기는 거부합니다. 복사 또는 검사 실패 시 이전 current는 유지합니다. 비정상 종료로 고아 버전 폴더가 생겨도 기존 current를 변경하지 않으며 해당 버전 이름을 재사용하지 마세요.

`GET /v1/releases/current` 응답 예시:

```json
{
  "version": "2026-11-24-v1",
  "compatibility": 1,
  "assets": [{"name":"graph","url":"/v1/assets/2026-11-24-v1/graph.json","sha256":"실제64자리해시","size":1234}]
}
```

실제 응답에는 map/graph/destinations/model 네 항목이 있습니다. name에는 확장자가 없으며 URL은 동일 서버의 상대 경로입니다. manifest 조회와 `/v1/assets/{version}/{file}` 다운로드는 읽기 전용이고 토큰 없이 가능합니다. 초기에는 발행 자산이 없어 404를 반환합니다.

`POST /v1/admin/releases/rollback`에 `{version,reason}`을 보내면 기존 불변 버전의 해시를 재검사하고 current를 바꿉니다. 손상된 버전으로는 롤백하지 않습니다. rollback은 배포 포인터만 바꾸며 과거 후보의 published 검토 이력을 지우지 않습니다. 클라이언트의 안내 중 버전 고정은 Android ReleaseInstaller 쪽에서 담당합니다.

## 테스트

```powershell
python -m unittest -v
```

17개 테스트가 포함되어 있고 모두 통과했습니다. 실제 localhost 연결 시험도 네트워크 권한 허용 후 통과했습니다. 레코드/배치 멱등성, 내용 충돌, 부분 ACK, 파싱 실패 RSSI 보존, 안내 모드 GPS 좌표·메타데이터 차단, DB 재시작, 관측 당시 동의와 근거 검사, 추정값 유효성, 관리자 권한, 상태 전이, 패키지 해시/발행/실패/롤백/철회 이력을 검사합니다.

API 로직 시험은 실제 임시 SQLite/파일을 사용합니다. HTTP 처리기는 메모리 스트림을 통해 실제 HTTP 요청 프레임을 파싱해 health, admin HTML, 인증 거부, 배치 저장/중복 ACK, 후보 제출·상세 응답을 확인했습니다. 별도의 루프백 시험은 127.0.0.1의 임시 포트로 서버를 띄워 같은 흐름을 검증하며, 실행 환경이 소켓 접근을 명시적으로 거부할 때만 skip합니다. 실제 휴대폰 HTTP 연결, 운영 서버 배포, 브라우저 화면 시각 검증은 별도 수행해야 합니다.

## 데이터 파일

`--data` 폴더 아래 sensor.db와 releases가 생성됩니다. observations, batches, candidates, reviews, releases, settings, deployment_history 테이블을 사용합니다. 원시 관측은 가명 기기 ID로 저장하지만 GPS·MAC·후보 설명 등은 여전히 민감할 수 있으므로 팀의 수집 동의와 보관 정책에 맞게 운영하세요. 앱 키/관리자 키 자체를 DB 레코드에 넣지 않습니다.

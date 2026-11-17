# SMASH-CBNU에서 가져온 지도 데이터

원본 프로젝트: https://github.com/Hong-Iron/SMASH-CBNU
원본 데이터: https://github.com/Hong-Iron/SMASH-CBNU/blob/main/data/campus.json
사용 조건: https://github.com/Hong-Iron/SMASH-CBNU/blob/main/LICENSE
확인일: 2026-11-17

이 폴더의 `osm_building_outlines.json`은 data/campus.json 중 `meta` 기준점과 `way/` ID 건물 157개의 ID·외곽만 추출한 것이다. 원본은 개신캠퍼스의 3D 게임을 위한 좌표 데이터이며 GLB/OBJ 모델이 아니다.

© OpenStreetMap contributors. Open Database License 1.0.
https://www.openstreetmap.org/copyright
https://opendatacommons.org/licenses/odbl/1-0/

데이터 정리 출처: Hong-Iron / SMASH-CBNU. 원본 LICENSE는 건물·도로 JSON에 ODbL 적용을 명시하고, 게임 코드·그림·화면 디자인에는 별도 복제/배포 제한을 명시한다. 게임 소스, 이미지, 일반 재질, config의 외관 설정은 복사하지 않았다. 68개의 `vw/` 건물, 지형 고도, 수목, 색상은 이번 데이터에 포함하지 않았다.

좌표는 원본 meta 기준 x=동쪽, z=남쪽이며 위경도로 역변환 후 앱의 로컬 미터 x=동쪽/y=북쪽으로 다시 투영한다. OSM 원본 윤곽과 1m 이상 어긋나거나 캠퍼스 경계 밖인 윤곽은 사용하지 않는다. 높이는 별도로 받은 OSM height를 우선 사용하고, 없으면 levels×3.2m 또는 기본 10m로 추정한다. 실제 외관·높이·경사 지형을 재현한 정밀 모델은 아니다.

다운로드 시 원본 `data/campus.json` Git blob SHA: `332e2dd5578cd3aa77870bf2872051dd1b9ccb98`.

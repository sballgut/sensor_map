# OpenStreetMap 원본

- 원본: `gaesin.osm` (OSM API XML, 2026-11-17 다운로드)
- 요청: https://api.openstreetmap.org/api/0.6/map?bbox=127.446,36.619,127.463,36.635
- 개신캠퍼스 경계: https://www.openstreetmap.org/relation/6705106
- © OpenStreetMap contributors, Open Database License 1.0
- 저작권/기여자: https://www.openstreetmap.org/copyright
- 라이선스 전문: https://opendatacommons.org/licenses/odbl/1-0/

원본 범위에는 주변 시가지도 포함되나 앱 데이터는 대학 relation의 연결된 outer 경계 내부로 제한한다. relation의 inner에는 대학 건물·주차장·연못 등이 들어 있으므로 대학 부지의 외곽을 서비스 경계로 사용한다. 이 OSM 외곽은 법적 지적 경계를 보증하지 않는다.

앱용 파생 데이터: `app/src/main/assets/gaesin_campus.json` (ODbL 1.0).
재현: 저장소 루트에서 `python3 tools/import_osm.py`.
검증: `python3 -m unittest discover -s tools -p 'test_*.py'`.

변환 내용: 위경도를 로컬 미터 좌표로 변환, 외곽 내부 건물·경로 선택, 미등록 연결을 추가하지 않고 OSM 공유 node ID로 그래프 구성. foot/access/conditional 제한을 고려한다. 일반 차량 oneway를 보행자에게 적용하지 않고 oneway:foot을 사용한다. 경계를 가로질러 밖으로 나갔다 들어오는 간선도 제외한다.

모든 길은 OSM 등록 정보에 근거한 미실측 상태다. service 길은 보행 전용로를 뜻하지 않는다. 지도에 없는 담장, 임시 통제, 건물 출입구, 계단 접근성은 현장 조사해야 한다.

# 공용 과거 데이터 조회

`fetch_history.py`는 API v4의 읽기 전용 `/sensor/api/history`만 사용합니다. 공용 서버에 데이터를 저장하는 `/sensor/sensing/`은 호출하지 않습니다. Python 3.10 이상, 표준 라이브러리만 사용합니다.

PowerShell 예:

```powershell
$env:SENSOR_API_KEY = '배정받은 팀 키'
python tools/fetch_history.py --sensor 7 --start 2026-11-08 --end 2026-11-15 --mode mobile --output history_mobile
```

34개 센서를 순차 조회하려면 `--sensor 7` 대신 `--all`을 사용합니다. `--mode`를 생략하면 direct/mobile을 함께 조회합니다. 한 요청 기간은 최대 7일이며, 종료 시각은 포함하지 않습니다. 시간대 없는 날짜/시각은 KST로 해석합니다. 미래 종료 시각은 허용하지 않습니다. 7일 이상은 기간을 나눠 실행하세요.

실제 네트워크 요청 간격은 최소 2.1초입니다. **같은 API 키를 다른 도구에서 동시에 쓰면 합산 제한 30회/분을 넘을 수 있습니다.** 429/503은 Retry-After를 기다린 뒤 기본 최대 4회 재시도합니다. `next_cursor`로 마지막 페이지까지 가져옵니다.

완료된 페이지는 `pages/`에 원자적으로 캐시합니다. 중간에 중단되면 같은 명령으로 재실행하여 완료 페이지를 재사용할 수 있습니다. 최종 `history_records.jsonl`도 완료 시에만 교체합니다. 키는 캐시하지 않습니다. 장기간 같은 키로 수집 중인 시간대의 서버 데이터가 추가될 수 있다면 재조회가 필요할 때 새로운 출력 폴더를 사용하세요.

출력의 `source`는 `public_history`입니다. 이것은 과거 서버 기록이며 현재 휴대폰 BLE 관측으로 넣으면 안 됩니다. 설치 좌표 캐시와 현재 휴대폰 GPS 라벨도 서로 구분해야 합니다.

네트워크 없는 테스트:

```powershell
python -m unittest discover -s tools -p 'test_history.py' -v
```

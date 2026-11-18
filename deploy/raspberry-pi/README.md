# Raspberry Pi 서버 설치와 관리

Python 3.10 이상, systemd, `team04` 사용자와 그룹, 비어 있는 TCP 8080 포트가 필요합니다. 저장소의 `server/` 코드를 설치하며 기존 환경센서 수집 프로그램과 CSV는 수정하지 않습니다.

## 최초 설치

저장소 전체를 Pi에 clone하거나 복사한 뒤 저장소 루트에서 실행합니다.

```sh
sudo python3 deploy/raspberry-pi/install.py
```

API 테스트 후 `/opt/sensorae-server`에 코드를 설치하고 `sensorae-server` 서비스를 부팅 시 자동 실행합니다. DB와 배포 데이터는 `/var/lib/sensorae-server`에 보관됩니다. 앱/관리자 토큰은 서로 다른 난수로 생성하여 `/etc/sensorae-server.env`에 root 전용 권한으로 저장합니다. 재부팅해도 토큰은 유지됩니다.

기존 설치 경로가 있으면 덮어쓰지 않고 중단합니다. 이미 설치한 Pi에서 재실행할 필요는 없습니다. 설치 실패 시 남은 파일을 무작정 삭제하지 말고 출력과 서비스 로그를 먼저 확인하세요. 이 스크립트는 업데이트용이 아닙니다.

## 앱과 관리 화면 연결

- 서버 기본 주소: `http://<Pi의 IP>:8080`
- 연결 확인: `http://<Pi의 IP>:8080/health` → `{"status":"ok"}`
- 관리 화면: `http://<Pi의 IP>:8080/admin`
- 앱 인증에는 `APP_TOKEN`, 관리 화면에는 `ADMIN_TOKEN` 사용

토큰은 Pi에서 필요한 항목만 확인합니다. 실제 값을 GitHub, 로그 캡처 또는 공개 채팅에 올리지 마세요.

```sh
sudo grep '^APP_TOKEN=' /etc/sensorae-server.env
sudo grep '^ADMIN_TOKEN=' /etc/sensorae-server.env
```

앱에는 `APP_TOKEN=` 뒤의 값만 입력합니다. 주소는 `/8080`이 아닌 `:8080`이며 기본 주소에 `/health`나 `/admin`을 붙이지 않습니다. Pi의 DHCP 주소가 바뀌면 앱 주소도 변경해야 합니다.

현재는 HTTP 기반 내부망 시험 서버입니다. 같은 공유기의 유선 LAN과 Wi-Fi에서도 기기 간 통신이 허용되면 접속할 수 있습니다. 학교 Wi-Fi는 기기 간 통신을 차단할 수 있습니다. 외부 접속에는 VPN 등 별도 구성이 필요하며 설치기는 라우터나 방화벽을 변경하지 않습니다.

## 운영

```sh
systemctl status sensorae-server --no-pager
sudo journalctl -u sensorae-server -n 40 --no-pager
sudo systemctl restart sensorae-server
```

백업은 서버를 중지한 뒤 `/var/lib/sensorae-server` 전체를 보관하고 다시 시작합니다. 지도/모델 배포 원본은 `ProtectHome` 제한 때문에 홈 대신 `/var/lib/sensorae-server/packages`에 두고 team04 사용자가 읽을 수 있게 설정합니다.

기존 환경센서 수집기를 별도로 중지하고 부팅 자동 실행도 해제하려면:

```sh
sudo systemctl disable --now opensrc-team4
```

이는 기존 CSV를 삭제하지 않으며 새 API 서버에도 영향을 주지 않습니다.

## 확인된 설치 결과

2026-11-18 제공된 Pi 실행 화면에서 Debian 13 / Python 3.13.5, API 테스트 17개 통과, 서버 `active`, 로컬 health 정상 응답을 확인했습니다. 사용자는 다른 기기에서도 health 정상 응답을 확인했습니다. Git 저장소 버전은 설치 파일의 위치만 저장소 구조에 맞게 조정했습니다.

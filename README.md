# GravityGun
Gravity gun plugin that can even catch players

플레이어를 잡을 수 있는 Gravity Gun 플러그인

명령어 목록: /gg 플레이어에게 주기: 중력총 아이템 받기 /gg 재로드: 구성 파일 다시 로드

중력총 사용법: 타겟을 보고 오른쪽 클릭: 타겟 집어 올리기 타겟을 잡고 오른쪽 클릭: 타겟 버리기 타겟을 잡고 왼쪽 클릭: 타겟 던지기 타겟을 잡고 Shift+오른쪽 클릭: 타겟 강제로 놓기

config.yml \plugins\GravityGun

messages:
  prefix: "&b[GG]&r "
item:
  material: BLAZE_ROD
  name: "&bGravity Gun"
mechanics:
  range: 16.0
  hold_distance: 3.0
  launch_power: 2.6
  tick_smooth: true
limits:
  allow_grab_players: true
  blacklist:
    - ENDER_DRAGON
    - WITHER
    - WARDEN
cooldown:
  grab_ms: 300


지원 버전: 1.21x 테스트 버전: 1.21.7

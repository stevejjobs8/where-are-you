# Android 작은 지도(PiP) — 실기기 테스트

## 목적
지도 앱을 화면 전체로 열지 않고 유튜브 PiP처럼 **다른 앱 위에 작은 디오라마**를 남기는 기능입니다. Android 8.0(API 26) 이상, PiP 지원 기기에서만 작동합니다.

## 새 테스트 APK
- APK: https://github.com/stevejjobs8/where-are-you/releases/download/app-pip-preview/where-are-you-pip-preview.apk
- 패키지: `io.github.stevejjobs8.whereareyou.pippreview`
- 버전: 0.4.6-map-pip-preview (versionCode 10)
- Android 기존 정식 앱 및 디자인 미리보기와 **별도 설치**
- 처음 실행 시 실제 위치를 전송하지 않는 1인 시연으로 시작
- 지도 제공 페이지: `app-pip-preview.html` + `map-design-preview.html` (GitHub Pages)

## 한 대로 확인하는 방법
1. 테스트 APK 설치 후 3D 디오라마가 표시될 때까지 기다립니다.
2. 화면 오른쪽 위 메뉴 버튼 바로 아래의 **겹친 사각형(작은 지도)** 버튼을 누릅니다.
3. 작은 지도가 홈 화면 위에 나타나는지 확인합니다.
4. 유튜브·메시지 등 다른 앱을 열어 지도 창이 계속 표시되는지 확인합니다.
5. PiP 창을 끌어서 위치를 바꾸고, 창을 터치하여 전체 화면으로 복귀합니다.
6. 전체 화면으로 돌아온 뒤 메뉴, 지도 회전, 이동수단 버튼이 복원되는지 확인합니다.
7. Android 12 이상에서는 지도 표시 중 홈으로 이동할 때 자동 PiP가 시작되는지도 확인합니다.
8. 시연 지도에는 **시연 · 실제 위치 아님** 표기가 유지돼야 합니다.

## 실제 짝꿍 모드
- **연결 시험**에서 실제 계정·짝꿍 연결을 한 후에만 실시간 상대 위치가 나타납니다.
- 짝꿍이 없는 상태에서는 PiP 버튼을 표시하지 않습니다.
- 공유 중단/위치 오래됨/연결 해제 시에는 기존 개인정보 보호 규칙이 유지돼야 합니다.
- PiP는 지도를 *보는* 방식이며, 내 위치를 전송하는 기능을 자동으로 켜지 않습니다.
- 내 위치 송신이 켜져 있으면 기존 Android Foreground Location Service가 담당합니다.

## 기기별 문제 해결
- 버튼이 보이지 않으면 Android 설정 → 앱 → 특별한 접근 → PIP(화면 속 화면)에서 테스트 앱이 허용되었는지 확인합니다.
- 기기에서 PiP 자체를 지원하지 않을 수 있습니다.
- PiP 상태에서 GPU/배터리·백그라운드 렌더링이 단말에 따라 달라질 수 있으므로 삼성 갤럭시 실기기에서 검증해야 합니다.
- 기기 OS에 따라 다른 앱으로 전환하는 제스처와 PiP 확대 방법이 다를 수 있습니다.

## 기술 구현
- `android:supportsPictureInPicture=true`, Activity `configChanges` 처리
- Android 12+ `PictureInPictureParams.Builder.setAutoEnterEnabled`; 이전 버전은 `onUserLeaveHint`
- PiP 진입/복원은 네이티브 Activity 콜백 → 웹 앱 `WY.onPipModeChanged`
- PiP에서는 지도 이외의 카드·공유 조작판을 숨기고 카메라 줌 조정
- 실시간 상태가 없는 경우 지도는 기존 좌표 숨김 정책을 따름
- 자동 진입 대상은 짝꿍 지도 또는 명시적으로 구분된 시연 지도만 허용
- Android PiP 지원 여부를 확인하며 실패 시 안내

## 미검증 항목
- 실제 삼성 갤럭시 PiP 전환 성공 및 WebGL 연속 렌더링
- PiP 창 이동·크기 조절·전체 화면 복귀 후 포커스/터치
- 장시간 배터리 사용량·발열
- 실제 짝꿍 송수신의 PiP 동시 운영
- Google Play 정책 검토 (PiP는 일반적으로 영상·통화·내비게이션 중심)

이 문서의 기능은 Android 전용입니다. iOS 브랜치는 변경하지 않았습니다.

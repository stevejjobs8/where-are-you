# 지금 너는 어디쯤 — iPhone 개발 브랜치

현재 단계: SwiftUI + WKWebView 기반 iPhone 앱과 Core Location 전송 초안. 실기기 검증 전이며, iOS Simulator 빌드 성공 여부는 GitHub Actions에서 별도 확인한다.

## 구조

- 기존 앱 메인 UI와 3D 디오라마: app-design-preview.html, map-design-preview.html
- ios/Sources/WhereAreYouApp.swift — SwiftUI 앱 + WKWebView
- ios/Sources/AppBridge.swift — JavaScript ↔ 네이티브 메시지, 키체인, 전환 제어
- ios/Sources/KeychainCredentials.swift — 기기 전용 Keychain
- ios/Sources/LocationSharing.swift — Core Location 위치 권한, 전송·중지 및 백그라운드 업데이트
- ios/Sources/SupabaseUploader.swift — 기존 push_location RPC, 직렬 처리
- ios/Sources/LocationFixPolicy.swift — GPS 유효 시간·정확도, 이동 분류
- ios/Tests — GPS 최신성 단위 테스트

## 실행

macOS에 Xcode 설치 후:

    brew install xcodegen
    cd ios
    xcodegen generate
    open WhereAreYou.xcodeproj

Xcode에서 WhereAreYou 스킴을 선택해 시뮬레이터로 실행한다.

터미널에서 컴파일:

    xcodebuild -project ios/WhereAreYou.xcodeproj -scheme WhereAreYou -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build

GitHub Actions가 생성하는 시뮬레이터 앱 ZIP은 실제 아이폰에 직접 설치할 수 있는 IPA가 아니다. 아이폰 실기기 설치 및 TestFlight는 Apple 개발자 서명·프로비저닝을 요구한다.

## 기능 및 개인정보

처음 실행은 혼자 체험하기로 시작하며, 가상의 움직임과 날씨를 볼 수 있다. **시연 중 실제 GPS 전송은 시작되지 않는다.**

연결 시험을 누르면 실사용 계정 연결 화면으로 바뀐다. 사용자가 공유를 직접 켜면 iOS 권한을 요청한 후 Core Location이 공급한 위치를 Supabase에 업로드한다. 공유 중지/혼자 체험 복귀 시 서버에 sharing=false, 좌표 null 요청을 보낸다. 오래된 GPS를 새 위치인 것처럼 재전송하지 않으며, 60초 이상 새로운 위치가 없을 때 좌표를 비운다. OS에 의해 앱이 일시 중단되거나 강제 종료된 경우의 동작은 아직 검증되지 않았다.

When In Use 권한만 있고 Always 권한이 없다면 백그라운드에서 공유가 제한될 수 있다. 사용자가 위치를 공유한 뒤에만 Always 권한 업그레이드를 요청한다. 시뮬레이터는 GPS·배터리·백그라운드 실기기 결과를 대체하지 못한다.

키체인 인증키는 URL에 넣지 않으며, 네이티브 메시지를 HTTPS + 원래 호스트 + 최상위 프레임에서만 수용한다. 기존 웹 iframe과 호환하기 위해 같은 출처의 프레임에 인증 조회 기능을 주입하므로 정식 배포 전 별도 보안 검토가 필요하다.

현재 iOS 이동 상태는 GPS 속도에 따른 단순 분류이고 실제 지하철 경로 예측은 미구현이다.

## 남은 검증

1. iOS Simulator 컴파일 오류 해결 및 기본 UI 렌더링 확인
2. 시연/실제 연결 전환, 두 기기 연결, 서버 송수신 통합 테스트
3. 아이폰 실기기 권한, 잠금 화면, 지하철 수신 불량, 배터리·발열 검증
4. 계정 삭제 시 키체인 초기화, 연결 해제와 보안 점검
5. Apple 개발자 계정으로 서명한 배포 아카이브 및 TestFlight 배포

브랜치: feature/ios-wkwebview. 정식 Android 코드 및 배포 앱은 변경하지 않았다.

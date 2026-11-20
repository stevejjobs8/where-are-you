import Foundation
import CoreLocation

/// Serial upload queue: a sharing-off request is always sent after earlier queued fixes.
final class SupabaseUploader {
    private let queue = DispatchQueue(label: "io.github.stevejjobs8.whereareyou.location-upload", qos: .utility)
    private let base = URL(string: "https://vjfzfjjktszxgqhwxqko.supabase.co/rest/v1/rpc/push_location")!
    // Publishable client key from the existing project, NOT a service-role secret.
    private let publishableKey = "sb_publishable_HvlTYMKZ4AQzELwmWqcS9g_SW29EBwB"

    func send(credentials: ShareCredentials,
              location: CLLocation?,
              sharing: Bool,
              completion: @escaping (String) -> Void) {
        guard credentials.isUsable else {
            DispatchQueue.main.async { completion("위치 공유 계정이 준비되지 않았어요") }
            return
        }

        // Copy primitive fields before entering the background queue.
        let latitude = location?.coordinate.latitude
        let longitude = location?.coordinate.longitude
        let accuracy = location?.horizontalAccuracy
        let speed = location?.speed
        let heading = location?.course
        let activity = sharing ? LocationFixPolicy.activity(speedMetersPerSecond: speed ?? -1) : ""
        queue.async { [self] in
            // A queued location must never be uploaded after a user has switched sharing off.
            if sharing && !UserDefaults.standard.bool(forKey: "iosSharingEnabled") { return }
            func number(_ n: Double?) -> Any { n.map { NSNumber(value: $0) } ?? NSNull() }

            let body: [String: Any] = [
                "p_view_code": credentials.view,
                "p_write_key": credentials.write,
                "p_lat": number(sharing ? latitude : nil),
                "p_lng": number(sharing ? longitude : nil),
                "p_accuracy": number(sharing && (accuracy ?? -1) >= 0 ? accuracy : nil),
                "p_speed": number(sharing && (speed ?? -1) >= 0 ? speed : nil),
                "p_heading": number(sharing && (heading ?? -1) >= 0 ? heading : nil),
                "p_activity": sharing && location != nil ? activity : NSNull(),
                "p_sharing": sharing
            ]
            var request = URLRequest(url: self.base)
            request.httpMethod = "POST"
            request.timeoutInterval = 12
            request.setValue(self.publishableKey, forHTTPHeaderField: "apikey")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            do { request.httpBody = try JSONSerialization.data(withJSONObject: body) }
            catch {
                DispatchQueue.main.async { completion("위치 데이터 형식 오류") }
                return
            }

            let semaphore = DispatchSemaphore(value: 0)
            var result = "서버 응답을 기다리는 중이에요"
            let task = URLSession.shared.dataTask(with: request) { _, response, error in
                if let error = error {
                    result = "위치 전송 실패: " + error.localizedDescription
                } else if let code = (response as? HTTPURLResponse)?.statusCode, !(200..<300).contains(code) {
                    result = "위치 서버 응답 오류 (\(code))"
                } else {
                    result = !sharing ? "위치 공유를 껐어요"
                        : location == nil ? "새로운 GPS 위치를 기다리는 중이에요" : "최근 위치를 전송했어요"
                }
                semaphore.signal()
            }
            task.resume()
            if semaphore.wait(timeout: .now() + 13) == .timedOut {
                task.cancel()
                result = "위치 전송 시간 초과"
            }
            DispatchQueue.main.async { completion(result) }
        }
    }
}

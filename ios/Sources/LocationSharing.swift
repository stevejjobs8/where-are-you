import Foundation
import CoreLocation

/// Explicit opt-in Core Location sharing. Simulation mode never starts this manager.
final class LocationSharing: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let sender = SupabaseUploader()
    private var freshnessTimer: Timer?
    private var pendingStart = false
    private var lastPublished: Date?
    private var lastReceived: Date?
    private var clearedMissing = false

    private(set) var isSharing = UserDefaults.standard.bool(forKey: "iosSharingEnabled")
    private(set) var status = "위치 공유 꺼짐"
    var onUpdate: ((Bool, String) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestPermissions() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
    }

    func start() {
        guard let creds = KeychainCredentials.read(), creds.isUsable else {
            notify("공유 계정이 준비되지 않았어요")
            onUpdate?(false, status)
            return
        }
        switch manager.authorizationStatus {
        case .notDetermined:
            pendingStart = true
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            begin()
        case .denied, .restricted:
            notify("아이폰 설정에서 위치 권한을 허용해 주세요")
            onUpdate?(false, status)
        @unknown default:
            onUpdate?(false, "위치 권한을 확인할 수 없어요")
        }
    }

    func resumeIfNeeded() {
        if isSharing {
            if manager.authorizationStatus == .authorizedAlways
                || manager.authorizationStatus == .authorizedWhenInUse { begin() }
            else { onUpdate?(false, "위치 권한이 변경됐어요. 설정을 확인해 주세요") }
        }
    }

    private func begin() {
        guard !isSharing || freshnessTimer == nil else { return }
        isSharing = true
        UserDefaults.standard.set(true, forKey: "iosSharingEnabled")
        lastPublished = nil
        lastReceived = nil
        clearedMissing = true
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        freshnessTimer?.invalidate()
        freshnessTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            self?.checkFreshness()
        }
        notify("GPS 위치를 찾고 있어요")
        onUpdate?(true, status)
        // Remove a previously cached fix until a fresh GPS event actually arrives.
        send(nil, sharing: true)
        if manager.authorizationStatus == .authorizedWhenInUse {
            // The OS may stage or decline the Always upgrade. Tracking still works while active.
            manager.requestAlwaysAuthorization()
        }
    }

    func stop() {
        pendingStart = false
        isSharing = false
        UserDefaults.standard.set(false, forKey: "iosSharingEnabled")
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        freshnessTimer?.invalidate()
        freshnessTimer = nil
        lastPublished = nil
        lastReceived = nil
        clearedMissing = true
        notify("위치 공유를 껐어요")
        onUpdate?(false, status)
        // One final authenticated request clears server coordinates and sharing permission.
        send(nil, sharing: false)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if pendingStart {
            pendingStart = false
            if manager.authorizationStatus == .authorizedAlways
                || manager.authorizationStatus == .authorizedWhenInUse { begin() }
            else if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
                onUpdate?(false, "위치 접근이 거부됐어요")
            }
        } else if isSharing
                    && (manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted) {
            stop()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard isSharing else { return }
        let now = Date()
        for location in locations {
            guard LocationFixPolicy.isEligible(location, now: now, lastPublished: lastPublished) else { continue }
            lastPublished = location.timestamp
            lastReceived = now
            clearedMissing = false
            send(location, sharing: true)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if isSharing { notify("GPS 신호 확인 중: " + error.localizedDescription) }
    }

    private func checkFreshness() {
        guard isSharing, LocationFixPolicy.needsClear(lastReceived: lastReceived,
                                                       now: Date(), cleared: clearedMissing) else { return }
        clearedMissing = true
        send(nil, sharing: true)
    }

    private func send(_ location: CLLocation?, sharing: Bool) {
        guard let creds = KeychainCredentials.read() else { return }
        sender.send(credentials: creds, location: location, sharing: sharing) { [weak self] result in
            guard let self = self, self.isSharing == sharing else { return }
            self.notify(result)
        }
    }

    private func notify(_ message: String) {
        status = message
        onUpdate?(isSharing, message)
    }
}

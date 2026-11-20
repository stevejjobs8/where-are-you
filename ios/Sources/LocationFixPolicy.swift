import Foundation
import CoreLocation

enum LocationFixPolicy {
    static let maximumAge: TimeInterval = 60
    static let maximumAccuracy: CLLocationAccuracy = 200

    static func isEligible(_ location: CLLocation, now: Date, lastPublished: Date?) -> Bool {
        let age = now.timeIntervalSince(location.timestamp)
        guard age >= -3, age <= maximumAge,
              location.horizontalAccuracy >= 0,
              location.horizontalAccuracy <= maximumAccuracy else { return false }
        if let lastPublished = lastPublished, location.timestamp <= lastPublished { return false }
        return true
    }

    static func needsClear(lastReceived: Date?, now: Date, cleared: Bool) -> Bool {
        guard !cleared, let last = lastReceived else { return !cleared }
        return now.timeIntervalSince(last) > maximumAge
    }

    static func activity(speedMetersPerSecond speed: CLLocationSpeed) -> String {
        guard speed >= 0 else { return "unknown" }
        if speed < 0.5 { return "still" }
        if speed < 2.3 { return "walk" }
        if speed < 3.8 { return "run" }
        if speed < 7.0 { return "bike" }
        return "vehicle"
    }
}

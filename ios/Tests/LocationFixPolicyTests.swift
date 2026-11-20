import XCTest
import CoreLocation
@testable import WhereAreYou

final class LocationFixPolicyTests: XCTestCase {
    private func fix(age: TimeInterval, accuracy: CLLocationAccuracy = 12) -> CLLocation {
        let now = Date()
        return CLLocation(coordinate: CLLocationCoordinate2D(latitude: 37.55, longitude: 126.92),
                          altitude: 0, horizontalAccuracy: accuracy, verticalAccuracy: 10,
                          timestamp: now.addingTimeInterval(-age))
    }

    func testFreshLocationAccepted() {
        let item = fix(age: 10)
        XCTAssertTrue(LocationFixPolicy.isEligible(item, now: Date(), lastPublished: nil))
    }

    func testExpiredAndInaccurateLocationRejected() {
        XCTAssertFalse(LocationFixPolicy.isEligible(fix(age: 65), now: Date(), lastPublished: nil))
        XCTAssertFalse(LocationFixPolicy.isEligible(fix(age: 3, accuracy: 300), now: Date(), lastPublished: nil))
    }

    func testDuplicateFixRejected() {
        let item = fix(age: 1)
        XCTAssertFalse(LocationFixPolicy.isEligible(item, now: Date(), lastPublished: item.timestamp))
    }

    func testClearStaleLocation() {
        let now = Date()
        XCTAssertTrue(LocationFixPolicy.needsClear(lastReceived: now.addingTimeInterval(-61), now: now, cleared: false))
        XCTAssertFalse(LocationFixPolicy.needsClear(lastReceived: now.addingTimeInterval(-2), now: now, cleared: false))
        XCTAssertFalse(LocationFixPolicy.needsClear(lastReceived: nil, now: now, cleared: true))
    }

    func testActivityHeuristicsDoNotInventSubway() {
        XCTAssertEqual(LocationFixPolicy.activity(speedMetersPerSecond: 0.1), "still")
        XCTAssertEqual(LocationFixPolicy.activity(speedMetersPerSecond: 1.2), "walk")
        XCTAssertEqual(LocationFixPolicy.activity(speedMetersPerSecond: 8.0), "vehicle")
    }
}

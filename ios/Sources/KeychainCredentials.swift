import Foundation
import Security

struct ShareCredentials: Codable {
    let view: String
    let write: String

    var isUsable: Bool { !view.isEmpty && !write.isEmpty }
}

enum KeychainCredentials {
    private static let service = "io.github.stevejjobs8.whereareyou.iospreview"
    private static let account = "location-sharing-credentials"

    static func read() -> ShareCredentials? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(ShareCredentials.self, from: data)
    }

    @discardableResult
    static func save(_ credentials: ShareCredentials) -> Bool {
        guard credentials.isUsable,
              let data = try? JSONEncoder().encode(credentials) else { return false }
        erase()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data
        ]
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    static func erase() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}

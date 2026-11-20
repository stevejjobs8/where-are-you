import Foundation
import WebKit
import UIKit
import SwiftUI

/// WKWebView native API, restricted to the first-party, top-level application.
final class AppBridge: NSObject, ObservableObject, WKScriptMessageHandler, WKNavigationDelegate {
    private static let origin = "https://stevejjobs8.github.io"
    private static let path = "/where-are-you/"
    private weak var webView: WKWebView?
    private let locations = LocationSharing()

    @Published private(set) var soloMode: Bool

    override init() {
        soloMode = (UserDefaults.standard.object(forKey: "iosSoloMode") as? Bool) ?? true
        super.init()
        locations.onUpdate = { [weak self] on, status in
            self?.notifyWeb(sharing: on, status: status)
        }
        if soloMode && locations.isSharing { locations.stop() }
    }

    private var pageURL: URL {
        let suffix = soloMode
            ? "app-design-preview.html?preview=main&solo=1"
            : "app-design-preview.html"
        return URL(string: Self.origin + Self.path + suffix)!
    }

    func attach(_ webView: WKWebView) {
        self.webView = webView
        webView.load(URLRequest(url: pageURL))
    }

    func didBecomeActive() {
        if !soloMode { locations.resumeIfNeeded() }
        if !soloMode { webView?.evaluateJavaScript("window.WY && WY.onResume && WY.onResume()", completionHandler: nil) }
    }

    func userScript() -> WKUserScript {
        let auth = KeychainCredentials.read()
        let credentials: [String: String] = [
            "view": auth?.view ?? "", "write": auth?.write ?? ""
        ]
        let data = (try? JSONSerialization.data(withJSONObject: credentials)) ?? Data("{}".utf8)
        let encodedAuth = String(data: data, encoding: .utf8) ?? "{}"
        let sharing = locations.isSharing && !soloMode ? "true" : "false"
        let initialStatus = Self.jsQuote(locations.status)
        // All frames: the nested first-party 3D map also needs read-only auth().
        // Any external-origin frame is explicitly excluded.
        let script = """
        (function () {
          if (location.origin !== '\(Self.origin)') return;
          let credentials = \(encodedAuth);
          let sharing = \(sharing);
          let status = \(initialStatus);
          function send(method, args) {
            window.webkit.messageHandlers.whereAreYou.postMessage({method: method, args: args || {}});
          }
          window.AndroidBridge = {
            auth: () => JSON.stringify(credentials),
            setCreds: (view, write) => {
              credentials = {view: String(view), write: String(write)};
              send('setCreds', credentials);
            },
            clearCreds: () => {credentials = {view:'',write:''}; send('clearCreds');},
            isSharing: () => sharing,
            shareStatus: () => status,
            startShare: () => send('startShare'),
            stopShare: () => {sharing = false; send('stopShare');},
            requestPermissions: () => send('requestPermissions'),
            copy: (value) => send('copy', {text: String(value)}),
            shareText: (value) => send('shareText', {text: String(value)}),
            openBattery: () => send('openBattery'),
            openUrl: (url) => send('openUrl', {url: String(url)}),
            enterConnectionMode: () => send('enterConnectionMode'),
            enterSoloMode: () => send('enterSoloMode')
          };
          window.__wyNativeState = (on, message) => {
            const changed = sharing !== Boolean(on);
            sharing = Boolean(on);
            status = message || status;
            if (window.WY) {
              if (changed && WY.onShare) WY.onShare(sharing);
              if (WY.onStatus) WY.onStatus(status);
            }
          };
        })();
        """
        return WKUserScript(source: script, injectionTime: .atDocumentStart, forMainFrameOnly: false)
    }

    /// Re-install the initial state for any subsequently loaded map iframe.
    private func refreshUserScript() {
        guard let controller = webView?.configuration.userContentController else { return }
        controller.removeAllUserScripts()
        controller.addUserScript(userScript())
    }

    private static func jsQuote(_ value: String) -> String {
        if let data = try? JSONSerialization.data(withJSONObject: [value]),
           let wrapped = String(data: data, encoding: .utf8) {
            return String(wrapped.dropFirst().dropLast())
        }
        return "\"\""
    }

    private func notifyWeb(sharing: Bool, status: String) {
        let js = "window.__wyNativeState && __wyNativeState(\(sharing ? "true" : "false"), \(Self.jsQuote(status)))"
        webView?.evaluateJavaScript(js, completionHandler: nil)
        // A permission denial must also clear a pending UI toggle.
        if !sharing && status.contains("거부") {
            webView?.evaluateJavaScript("window.WY && WY.onShareDenied && WY.onShareDenied()", completionHandler: nil)
        }
    }

    private func loadPage() {
        refreshUserScript()
        webView?.load(URLRequest(url: pageURL))
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "whereAreYou",
              message.frameInfo.isMainFrame,
              message.frameInfo.securityOrigin.protocol == "https",
              message.frameInfo.securityOrigin.host == "stevejjobs8.github.io",
              let body = message.body as? [String: Any],
              let method = body["method"] as? String else { return }
        let args = body["args"] as? [String: Any] ?? [:]
        switch method {
        case "setCreds":
            guard let view = args["view"] as? String, let write = args["write"] as? String,
                  !view.isEmpty, !write.isEmpty, view.count <= 256, write.count <= 256 else { return }
            if KeychainCredentials.save(ShareCredentials(view: view, write: write)) { refreshUserScript() }
        case "clearCreds":
            locations.stop()
            KeychainCredentials.erase()
            refreshUserScript()
        case "startShare":
            if !soloMode { locations.start() }
        case "stopShare":
            locations.stop()
        case "requestPermissions":
            if !soloMode { locations.requestPermissions() }
        case "enterConnectionMode":
            soloMode = false
            UserDefaults.standard.set(false, forKey: "iosSoloMode")
            loadPage()
        case "enterSoloMode":
            locations.stop()
            soloMode = true
            UserDefaults.standard.set(true, forKey: "iosSoloMode")
            loadPage()
        case "copy":
            if let value = args["text"] as? String { UIPasteboard.general.string = value }
        case "shareText":
            if let value = args["text"] as? String { share(value) }
        case "openBattery":
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        case "openUrl":
            if let text = args["url"] as? String, let url = URL(string: text),
               url.scheme == "https" || url.scheme == "http" { UIApplication.shared.open(url) }
        default:
            break
        }
    }

    private func share(_ text: String) {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow }),
              let presenter = window.rootViewController else { return }
        let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY,
                                        width: 1, height: 1)
        }
        presenter.present(sheet, animated: true)
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard action.targetFrame?.isMainFrame != false else {
            decisionHandler(.allow)
            return
        }
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if url.scheme == "https", url.host == "stevejjobs8.github.io",
           url.path.hasPrefix(Self.path) {
            decisionHandler(.allow)
        } else if ["https", "http"].contains(url.scheme ?? "") {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        } else {
            decisionHandler(.cancel)
        }
    }
}

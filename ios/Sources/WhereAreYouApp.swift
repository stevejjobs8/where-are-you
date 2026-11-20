import SwiftUI
import WebKit

@main
struct WhereAreYouApp: App {
    @StateObject private var app = AppBridge()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            MapWebView(bridge: app)
                .ignoresSafeArea()
                .background(Color(red: 227 / 255, green: 235 / 255, blue: 246 / 255))
                .onChange(of: scenePhase) { phase in
                    if phase == .active { app.didBecomeActive() }
                }
        }
    }
}

struct MapWebView: UIViewRepresentable {
    @ObservedObject var bridge: AppBridge

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.userContentController.add(bridge, name: "whereAreYou")
        config.userContentController.addUserScript(bridge.userScript())

        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = false
        view.backgroundColor = UIColor(red: 227 / 255, green: 235 / 255, blue: 246 / 255, alpha: 1)
        view.scrollView.backgroundColor = view.backgroundColor
        view.scrollView.isScrollEnabled = false
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.navigationDelegate = bridge
        bridge.attach(view)
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

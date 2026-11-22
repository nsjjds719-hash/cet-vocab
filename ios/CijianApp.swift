import AVFoundation
import SwiftUI
import UIKit
import WebKit

@main
struct CijianApp: App {
    var body: some Scene {
        WindowGroup {
            StudyBrowser()
                .background(Color(red: 0.973, green: 0.965, blue: 0.937).ignoresSafeArea())
                .preferredColorScheme(.light)
        }
    }
}

struct StudyBrowser: UIViewRepresentable {
    func makeCoordinator() -> StudyHost {
        StudyHost()
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(context.coordinator, name: "cijian")

        let browser = WKWebView(frame: .zero, configuration: configuration)
        browser.navigationDelegate = context.coordinator
        browser.isOpaque = false
        browser.backgroundColor = UIColor(red: 0.973, green: 0.965, blue: 0.937, alpha: 1)
        browser.scrollView.backgroundColor = browser.backgroundColor
        context.coordinator.browser = browser
        if let page = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "web") {
            context.coordinator.page = page.standardizedFileURL
            browser.loadFileURL(page, allowingReadAccessTo: page.deletingLastPathComponent())
        }
        return browser
    }

    func updateUIView(_ browser: WKWebView, context: Context) {}

    static func dismantleUIView(_ browser: WKWebView, coordinator: StudyHost) {
        browser.configuration.userContentController.removeScriptMessageHandler(forName: "cijian")
        coordinator.stopSpeech()
    }
}

final class StudyHost: NSObject, WKScriptMessageHandler, WKNavigationDelegate, AVSpeechSynthesizerDelegate {
    weak var browser: WKWebView?
    var page: URL?
    private let synthesizer = AVSpeechSynthesizer()
    private var activeUtterance: AVSpeechUtterance?
    private var speechRequest: Int?
    private var backgroundObserver: NSObjectProtocol?

    override init() {
        super.init()
        synthesizer.delegate = self
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.stopSpeech() }
    }

    deinit {
        if let backgroundObserver = backgroundObserver {
            NotificationCenter.default.removeObserver(backgroundObserver)
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let destination = navigationAction.request.url?.standardizedFileURL
        decisionHandler(destination == page && navigationAction.targetFrame?.isMainFrame == true
                        ? .allow : .cancel)
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame,
              message.frameInfo.request.url?.standardizedFileURL == page,
              let request = message.body as? [String: Any],
              let id = request["id"] as? Int,
              let operation = request["operation"] as? String else { return }
        do {
            switch operation {
            case "load":
                let url = try progressFile()
                if FileManager.default.fileExists(atPath: url.path) {
                    reply(id, value: try String(contentsOf: url, encoding: .utf8))
                } else {
                    reply(id)
                }
            case "save":
                guard let value = request["value"] as? [String: Any],
                      JSONSerialization.isValidJSONObject(value) else {
                    reply(id, error: "无法保存学习记录。")
                    return
                }
                let data = try JSONSerialization.data(withJSONObject: value)
                try data.write(to: progressFile(), options: .atomic)
                reply(id, value: true)
            case "speak":
                speak(id, request["value"] as? String ?? "")
            case "stop":
                stopSpeech()
                reply(id, value: true)
            default:
                reply(id, error: "无法完成这项操作。")
            }
        } catch {
            reply(id, error: "无法读取或保存本机学习记录。")
        }
    }

    private func progressFile() throws -> URL {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        return directory.appendingPathComponent("progress.json", isDirectory: false)
    }

    private func speak(_ id: Int, _ word: String) {
        stopSpeech()
        guard !word.isEmpty,
              let voice = AVSpeechSynthesisVoice(language: "en-US") ??
                          AVSpeechSynthesisVoice(language: "en-GB") else {
            reply(id, error: "请检查 iPhone 的英语语音设置。")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            let utterance = AVSpeechUtterance(string: word)
            utterance.voice = voice
            speechRequest = id
            activeUtterance = utterance
            synthesizer.speak(utterance)
        } catch {
            reply(id, error: "英语语音暂不可用，请重试。")
        }
    }

    func stopSpeech() {
        guard let id = speechRequest else { return }
        speechRequest = nil
        activeUtterance = nil
        synthesizer.stopSpeaking(at: .immediate)
        deactivateAudio()
        reply(id, error: "播放已停止。")
    }

    private func deactivateAudio() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func finish(_ utterance: AVSpeechUtterance, cancelled: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.activeUtterance === utterance, let id = self.speechRequest else { return }
            self.activeUtterance = nil
            self.speechRequest = nil
            self.deactivateAudio()
            self.reply(id, value: !cancelled, error: cancelled ? "播放已停止。" : nil)
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        finish(utterance, cancelled: false)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        finish(utterance, cancelled: true)
    }

    private func reply(_ id: Int, value: Any? = nil, error: String? = nil) {
        let payload: [String: Any] = [
            "id": id, "value": value ?? NSNull(), "error": error.map { $0 as Any } ?? NSNull()
        ]
        browser?.callAsyncJavaScript("window.cijianResponse(reply)", arguments: ["reply": payload],
                                     in: nil, contentWorld: .page, completionHandler: nil)
    }
}

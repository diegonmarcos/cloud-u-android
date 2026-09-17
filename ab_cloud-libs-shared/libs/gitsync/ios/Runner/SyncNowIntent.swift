import AppIntents
import Foundation

@available(iOS 17, *)
struct SyncNowIntent: AppIntent {
    static var title: LocalizedStringResource = "Sync Now"
    static var description: IntentDescription? = IntentDescription(
        "Synchronize the currently selected repository")
    static var openAppWhenRun: Bool = false
    static var isDiscoverable: Bool = true

    func perform() async throws -> some IntentResult {
        let repoIndex = UserDefaults(suiteName: "group.ForceSyncWidget")?.integer(forKey: "flutter.repoman_shortcutSyncIndex") ?? -1
        let urlString = repoIndex >= 0
            ? "gitsync://sync-now?homeWidget&index=\(repoIndex)"
            : "gitsync://sync-now?homeWidget"

        let backgroundIntent = BackgroundIntent(
            url: URL(string: urlString),
            appGroup: "group.ForceSyncWidget"
        )
        try await backgroundIntent.perform()

        return .result()
    }
}

@available(iOS 17, *)
@available(iOSApplicationExtension, unavailable)
extension SyncNowIntent: ForegroundContinuableIntent {}

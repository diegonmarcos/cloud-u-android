import Foundation

final class AppConfigManager {
  struct AppConfig: Decodable {
    let cloudNotesVersion: String
  }

  static var cloudNotesVersion: String?

  static func getCloudNotesVersion() -> String {
    if cloudNotesVersion == nil {
      let file = Bundle(for: AppConfigManager.self).url(forResource: "capacitor.config", withExtension: "json")!
      let data = try! Data(contentsOf: file)
      let config = try! JSONDecoder().decode(AppConfig.self, from: data)
      cloudNotesVersion = config.cloudNotesVersion
    }

    return cloudNotesVersion!
  }
}

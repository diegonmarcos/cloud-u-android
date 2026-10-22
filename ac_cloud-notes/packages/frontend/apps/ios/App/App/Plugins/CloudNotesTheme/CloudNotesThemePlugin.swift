import Capacitor
import UIKit

protocol CloudNotesThemeConfigurable: AnyObject {
  var appThemeUserInterfaceStyle: UIUserInterfaceStyle { get set }
}

enum CloudNotesThemeStorage {
  static let modeKey = "cloud-notes.theme.mode"
}

private enum CloudNotesThemeMode: String {
  case dark
  case light
  case system

  var userInterfaceStyle: UIUserInterfaceStyle {
    switch self {
    case .dark:
      .dark
    case .light:
      .light
    case .system:
      .unspecified
    }
  }
}

@objc(CloudNotesThemePlugin)
public final class CloudNotesThemePlugin: CAPPlugin, CAPBridgedPlugin {
  init(associatedController: UIViewController?) {
    controller = associatedController
    super.init()

    let cachedMode = CloudNotesThemeMode(rawValue: UserDefaults.standard.string(forKey: CloudNotesThemeStorage.modeKey) ?? "") ?? .system
    (associatedController as? CloudNotesThemeConfigurable)?.appThemeUserInterfaceStyle = cachedMode.userInterfaceStyle
  }

  weak var controller: UIViewController?

  public let identifier = "CloudNotesThemePlugin"
  public let jsName = "CloudNotesTheme"
  public let pluginMethods: [CAPPluginMethod] = [
    CAPPluginMethod(name: "onThemeChanged", returnType: CAPPluginReturnPromise),
  ]

  @objc func onThemeChanged(_ call: CAPPluginCall) {
    DispatchQueue.main.async {
      let themeMode = CloudNotesThemeMode(rawValue: call.getString("themeMode") ?? "") ?? .system
      UserDefaults.standard.set(themeMode.rawValue, forKey: CloudNotesThemeStorage.modeKey)
      (self.controller as? CloudNotesThemeConfigurable)?.appThemeUserInterfaceStyle = themeMode.userInterfaceStyle
      call.resolve()
    }
  }
}

// The Swift Programming Language
// https://docs.swift.org/swift-book

import SwiftUI
import UIKit

public enum CloudNotesColors: String, CaseIterable {
  case buttonPrimary = "cloud-notes.button.primary"
  case iconActivated = "cloud-notes.icon.activated"
  case iconPrimary = "cloud-notes.icon.primary"
  case layerBackgroundPrimary = "cloud-notes.layer.background.primary"
  case layerBackgroundSecondary = "cloud-notes.layer.background.secondary"
  case layerBorder = "cloud-notes.layer.border"
  case layerPureWhite = "cloud-notes.layer.pureWhite"
  case textEmphasis = "cloud-notes.text.emphasis"
  case textLink = "cloud-notes.text.link"
  case textListDotAndNumber = "cloud-notes.text.listDotAndNumber"
  case textPlaceholder = "cloud-notes.text.placeholder"
  case textPrimary = "cloud-notes.text.primary"
  case textPureWhite = "cloud-notes.text.pureWhite"
  case textSecondary = "cloud-notes.text.secondary"
  case textTertiary = "cloud-notes.text.tertiary"

  @available(iOS 13.0, *)
  public var color: Color {
    Color(rawValue, bundle: .module)
  }

  public var uiColor: UIColor {
    UIColor(named: rawValue, in: .module, compatibleWith: nil) ?? .clear
  }
}

public enum CloudNotesIcons: String, CaseIterable {
  case arrowDown = "ArrowDown"
  case arrowUpBig = "ArrowUpBig"
  case box = "Box"
  case broom = "Broom"
  case bubble = "Bubble"
  case calendar = "Calendar"
  case camera = "Camera"
  case checkCircle = "CheckCircle"
  case close = "Close"
  case image = "Image"
  case more = "More"
  case page = "Page"
  case plus = "Plus"
  case settings = "Settings"
  case think = "Think"
  case tools = "Tools"
  case upload = "Upload"
  case web = "Web"

  @available(iOS 13.0, *)
  public var image: Image {
    Image(rawValue, bundle: .module)
  }

  @available(iOS 13.0, *)
  public var uiImage: UIImage {
    UIImage(named: rawValue, in: .module, with: .none) ?? UIImage()
  }
}

//
//  ShareInboxConstants.swift
//  Shared between Cloud Notes and ShareExtension
//

import Foundation

enum ShareInboxConstants {
  static let appGroupId = "group.app.cloudnotes.pro"
  static let inboxDirectoryName = "ShareInbox"
  static let attachmentsDirectoryName = "Attachments"
  static let invalidDirectoryName = "Invalid"
  static let workspaceModeFileName = "ShareWorkspaceMode.json"
  static let officialLinkPreviewURL = URL(
    string: "https://notes.diegonmarcos.com/api/worker/link-preview"
  )!
  static let openInboxURL = URL(string: "cloud-notes://share-inbox")!
}

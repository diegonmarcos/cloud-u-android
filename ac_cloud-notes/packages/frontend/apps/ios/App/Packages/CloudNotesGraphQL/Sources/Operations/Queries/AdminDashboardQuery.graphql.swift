// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class AdminDashboardQuery: GraphQLQuery {
  public static let operationName: String = "adminDashboard"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query adminDashboard($input: AdminDashboardInput) { adminDashboard(input: $input) { __typename syncActiveUsers syncActiveUsersTimeline { __typename minute activeUsers } syncWindow { __typename from to timezone bucket requestedSize effectiveSize } copilotConversations copilotWindow { __typename from to timezone bucket requestedSize effectiveSize } workspaceStorageBytes blobStorageBytes workspaceStorageHistory { __typename date value } blobStorageHistory { __typename date value } storageWindow { __typename from to timezone bucket requestedSize effectiveSize } topSharedLinks { __typename workspaceId docId title shareUrl publishedAt views uniqueViews guestViews lastAccessedAt } topSharedLinksWindow { __typename from to timezone bucket requestedSize effectiveSize } generatedAt } }"#
    ))

  public var input: GraphQLNullable<AdminDashboardInput>

  public init(input: GraphQLNullable<AdminDashboardInput>) {
    self.input = input
  }

  public var __variables: Variables? { ["input": input] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("adminDashboard", AdminDashboard.self, arguments: ["input": .variable("input")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      AdminDashboardQuery.Data.self
    ] }

    /// Get aggregated dashboard metrics for admin panel
    public var adminDashboard: AdminDashboard { __data["adminDashboard"] }

    /// AdminDashboard
    ///
    /// Parent Type: `AdminDashboard`
    public struct AdminDashboard: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminDashboard }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("syncActiveUsers", Int.self),
        .field("syncActiveUsersTimeline", [SyncActiveUsersTimeline].self),
        .field("syncWindow", SyncWindow.self),
        .field("copilotConversations", CloudNotesGraphQL.SafeInt.self),
        .field("copilotWindow", CopilotWindow.self),
        .field("workspaceStorageBytes", CloudNotesGraphQL.SafeInt.self),
        .field("blobStorageBytes", CloudNotesGraphQL.SafeInt.self),
        .field("workspaceStorageHistory", [WorkspaceStorageHistory].self),
        .field("blobStorageHistory", [BlobStorageHistory].self),
        .field("storageWindow", StorageWindow.self),
        .field("topSharedLinks", [TopSharedLink].self),
        .field("topSharedLinksWindow", TopSharedLinksWindow.self),
        .field("generatedAt", CloudNotesGraphQL.DateTime.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        AdminDashboardQuery.Data.AdminDashboard.self
      ] }

      public var syncActiveUsers: Int { __data["syncActiveUsers"] }
      public var syncActiveUsersTimeline: [SyncActiveUsersTimeline] { __data["syncActiveUsersTimeline"] }
      public var syncWindow: SyncWindow { __data["syncWindow"] }
      public var copilotConversations: CloudNotesGraphQL.SafeInt { __data["copilotConversations"] }
      public var copilotWindow: CopilotWindow { __data["copilotWindow"] }
      public var workspaceStorageBytes: CloudNotesGraphQL.SafeInt { __data["workspaceStorageBytes"] }
      public var blobStorageBytes: CloudNotesGraphQL.SafeInt { __data["blobStorageBytes"] }
      public var workspaceStorageHistory: [WorkspaceStorageHistory] { __data["workspaceStorageHistory"] }
      public var blobStorageHistory: [BlobStorageHistory] { __data["blobStorageHistory"] }
      public var storageWindow: StorageWindow { __data["storageWindow"] }
      public var topSharedLinks: [TopSharedLink] { __data["topSharedLinks"] }
      public var topSharedLinksWindow: TopSharedLinksWindow { __data["topSharedLinksWindow"] }
      public var generatedAt: CloudNotesGraphQL.DateTime { __data["generatedAt"] }

      /// AdminDashboard.SyncActiveUsersTimeline
      ///
      /// Parent Type: `AdminDashboardMinutePoint`
      public struct SyncActiveUsersTimeline: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminDashboardMinutePoint }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("minute", CloudNotesGraphQL.DateTime.self),
          .field("activeUsers", Int.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.SyncActiveUsersTimeline.self
        ] }

        public var minute: CloudNotesGraphQL.DateTime { __data["minute"] }
        public var activeUsers: Int { __data["activeUsers"] }
      }

      /// AdminDashboard.SyncWindow
      ///
      /// Parent Type: `TimeWindow`
      public struct SyncWindow: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.TimeWindow }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("from", CloudNotesGraphQL.DateTime.self),
          .field("to", CloudNotesGraphQL.DateTime.self),
          .field("timezone", String.self),
          .field("bucket", GraphQLEnum<CloudNotesGraphQL.TimeBucket>.self),
          .field("requestedSize", Int.self),
          .field("effectiveSize", Int.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.SyncWindow.self
        ] }

        public var from: CloudNotesGraphQL.DateTime { __data["from"] }
        public var to: CloudNotesGraphQL.DateTime { __data["to"] }
        public var timezone: String { __data["timezone"] }
        public var bucket: GraphQLEnum<CloudNotesGraphQL.TimeBucket> { __data["bucket"] }
        public var requestedSize: Int { __data["requestedSize"] }
        public var effectiveSize: Int { __data["effectiveSize"] }
      }

      /// AdminDashboard.CopilotWindow
      ///
      /// Parent Type: `TimeWindow`
      public struct CopilotWindow: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.TimeWindow }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("from", CloudNotesGraphQL.DateTime.self),
          .field("to", CloudNotesGraphQL.DateTime.self),
          .field("timezone", String.self),
          .field("bucket", GraphQLEnum<CloudNotesGraphQL.TimeBucket>.self),
          .field("requestedSize", Int.self),
          .field("effectiveSize", Int.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.CopilotWindow.self
        ] }

        public var from: CloudNotesGraphQL.DateTime { __data["from"] }
        public var to: CloudNotesGraphQL.DateTime { __data["to"] }
        public var timezone: String { __data["timezone"] }
        public var bucket: GraphQLEnum<CloudNotesGraphQL.TimeBucket> { __data["bucket"] }
        public var requestedSize: Int { __data["requestedSize"] }
        public var effectiveSize: Int { __data["effectiveSize"] }
      }

      /// AdminDashboard.WorkspaceStorageHistory
      ///
      /// Parent Type: `AdminDashboardValueDayPoint`
      public struct WorkspaceStorageHistory: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminDashboardValueDayPoint }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("date", CloudNotesGraphQL.DateTime.self),
          .field("value", CloudNotesGraphQL.SafeInt.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.WorkspaceStorageHistory.self
        ] }

        public var date: CloudNotesGraphQL.DateTime { __data["date"] }
        public var value: CloudNotesGraphQL.SafeInt { __data["value"] }
      }

      /// AdminDashboard.BlobStorageHistory
      ///
      /// Parent Type: `AdminDashboardValueDayPoint`
      public struct BlobStorageHistory: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminDashboardValueDayPoint }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("date", CloudNotesGraphQL.DateTime.self),
          .field("value", CloudNotesGraphQL.SafeInt.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.BlobStorageHistory.self
        ] }

        public var date: CloudNotesGraphQL.DateTime { __data["date"] }
        public var value: CloudNotesGraphQL.SafeInt { __data["value"] }
      }

      /// AdminDashboard.StorageWindow
      ///
      /// Parent Type: `TimeWindow`
      public struct StorageWindow: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.TimeWindow }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("from", CloudNotesGraphQL.DateTime.self),
          .field("to", CloudNotesGraphQL.DateTime.self),
          .field("timezone", String.self),
          .field("bucket", GraphQLEnum<CloudNotesGraphQL.TimeBucket>.self),
          .field("requestedSize", Int.self),
          .field("effectiveSize", Int.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.StorageWindow.self
        ] }

        public var from: CloudNotesGraphQL.DateTime { __data["from"] }
        public var to: CloudNotesGraphQL.DateTime { __data["to"] }
        public var timezone: String { __data["timezone"] }
        public var bucket: GraphQLEnum<CloudNotesGraphQL.TimeBucket> { __data["bucket"] }
        public var requestedSize: Int { __data["requestedSize"] }
        public var effectiveSize: Int { __data["effectiveSize"] }
      }

      /// AdminDashboard.TopSharedLink
      ///
      /// Parent Type: `AdminSharedLinkTopItem`
      public struct TopSharedLink: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminSharedLinkTopItem }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("workspaceId", String.self),
          .field("docId", String.self),
          .field("title", String?.self),
          .field("shareUrl", String.self),
          .field("publishedAt", CloudNotesGraphQL.DateTime?.self),
          .field("views", CloudNotesGraphQL.SafeInt.self),
          .field("uniqueViews", CloudNotesGraphQL.SafeInt.self),
          .field("guestViews", CloudNotesGraphQL.SafeInt.self),
          .field("lastAccessedAt", CloudNotesGraphQL.DateTime?.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.TopSharedLink.self
        ] }

        public var workspaceId: String { __data["workspaceId"] }
        public var docId: String { __data["docId"] }
        public var title: String? { __data["title"] }
        public var shareUrl: String { __data["shareUrl"] }
        public var publishedAt: CloudNotesGraphQL.DateTime? { __data["publishedAt"] }
        public var views: CloudNotesGraphQL.SafeInt { __data["views"] }
        public var uniqueViews: CloudNotesGraphQL.SafeInt { __data["uniqueViews"] }
        public var guestViews: CloudNotesGraphQL.SafeInt { __data["guestViews"] }
        public var lastAccessedAt: CloudNotesGraphQL.DateTime? { __data["lastAccessedAt"] }
      }

      /// AdminDashboard.TopSharedLinksWindow
      ///
      /// Parent Type: `TimeWindow`
      public struct TopSharedLinksWindow: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.TimeWindow }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("from", CloudNotesGraphQL.DateTime.self),
          .field("to", CloudNotesGraphQL.DateTime.self),
          .field("timezone", String.self),
          .field("bucket", GraphQLEnum<CloudNotesGraphQL.TimeBucket>.self),
          .field("requestedSize", Int.self),
          .field("effectiveSize", Int.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminDashboardQuery.Data.AdminDashboard.TopSharedLinksWindow.self
        ] }

        public var from: CloudNotesGraphQL.DateTime { __data["from"] }
        public var to: CloudNotesGraphQL.DateTime { __data["to"] }
        public var timezone: String { __data["timezone"] }
        public var bucket: GraphQLEnum<CloudNotesGraphQL.TimeBucket> { __data["bucket"] }
        public var requestedSize: Int { __data["requestedSize"] }
        public var effectiveSize: Int { __data["effectiveSize"] }
      }
    }
  }
}

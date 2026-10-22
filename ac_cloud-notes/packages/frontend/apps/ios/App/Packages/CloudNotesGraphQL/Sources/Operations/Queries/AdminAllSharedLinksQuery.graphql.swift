// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class AdminAllSharedLinksQuery: GraphQLQuery {
  public static let operationName: String = "adminAllSharedLinks"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query adminAllSharedLinks($pagination: PaginationInput!, $filter: AdminAllSharedLinksFilterInput) { adminAllSharedLinks(pagination: $pagination, filter: $filter) { __typename totalCount analyticsWindow { __typename from to timezone bucket requestedSize effectiveSize } pageInfo { __typename hasNextPage hasPreviousPage startCursor endCursor } edges { __typename cursor node { __typename workspaceId docId title publishedAt docUpdatedAt workspaceOwnerId lastUpdaterId shareUrl views uniqueViews guestViews lastAccessedAt } } } }"#
    ))

  public var pagination: PaginationInput
  public var filter: GraphQLNullable<AdminAllSharedLinksFilterInput>

  public init(
    pagination: PaginationInput,
    filter: GraphQLNullable<AdminAllSharedLinksFilterInput>
  ) {
    self.pagination = pagination
    self.filter = filter
  }

  public var __variables: Variables? { [
    "pagination": pagination,
    "filter": filter
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("adminAllSharedLinks", AdminAllSharedLinks.self, arguments: [
        "pagination": .variable("pagination"),
        "filter": .variable("filter")
      ]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      AdminAllSharedLinksQuery.Data.self
    ] }

    /// List all shared links across workspaces for admin panel
    public var adminAllSharedLinks: AdminAllSharedLinks { __data["adminAllSharedLinks"] }

    /// AdminAllSharedLinks
    ///
    /// Parent Type: `PaginatedAdminAllSharedLink`
    public struct AdminAllSharedLinks: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.PaginatedAdminAllSharedLink }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("totalCount", Int?.self),
        .field("analyticsWindow", AnalyticsWindow.self),
        .field("pageInfo", PageInfo.self),
        .field("edges", [Edge].self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        AdminAllSharedLinksQuery.Data.AdminAllSharedLinks.self
      ] }

      public var totalCount: Int? { __data["totalCount"] }
      public var analyticsWindow: AnalyticsWindow { __data["analyticsWindow"] }
      public var pageInfo: PageInfo { __data["pageInfo"] }
      public var edges: [Edge] { __data["edges"] }

      /// AdminAllSharedLinks.AnalyticsWindow
      ///
      /// Parent Type: `TimeWindow`
      public struct AnalyticsWindow: CloudNotesGraphQL.SelectionSet {
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
          AdminAllSharedLinksQuery.Data.AdminAllSharedLinks.AnalyticsWindow.self
        ] }

        public var from: CloudNotesGraphQL.DateTime { __data["from"] }
        public var to: CloudNotesGraphQL.DateTime { __data["to"] }
        public var timezone: String { __data["timezone"] }
        public var bucket: GraphQLEnum<CloudNotesGraphQL.TimeBucket> { __data["bucket"] }
        public var requestedSize: Int { __data["requestedSize"] }
        public var effectiveSize: Int { __data["effectiveSize"] }
      }

      /// AdminAllSharedLinks.PageInfo
      ///
      /// Parent Type: `PageInfo`
      public struct PageInfo: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.PageInfo }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("hasNextPage", Bool.self),
          .field("hasPreviousPage", Bool.self),
          .field("startCursor", String?.self),
          .field("endCursor", String?.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminAllSharedLinksQuery.Data.AdminAllSharedLinks.PageInfo.self
        ] }

        public var hasNextPage: Bool { __data["hasNextPage"] }
        public var hasPreviousPage: Bool { __data["hasPreviousPage"] }
        public var startCursor: String? { __data["startCursor"] }
        public var endCursor: String? { __data["endCursor"] }
      }

      /// AdminAllSharedLinks.Edge
      ///
      /// Parent Type: `AdminAllSharedLinkEdge`
      public struct Edge: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminAllSharedLinkEdge }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("cursor", String.self),
          .field("node", Node.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminAllSharedLinksQuery.Data.AdminAllSharedLinks.Edge.self
        ] }

        public var cursor: String { __data["cursor"] }
        public var node: Node { __data["node"] }

        /// AdminAllSharedLinks.Edge.Node
        ///
        /// Parent Type: `AdminAllSharedLink`
        public struct Node: CloudNotesGraphQL.SelectionSet {
          public let __data: DataDict
          public init(_dataDict: DataDict) { __data = _dataDict }

          public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminAllSharedLink }
          public static var __selections: [ApolloAPI.Selection] { [
            .field("__typename", String.self),
            .field("workspaceId", String.self),
            .field("docId", String.self),
            .field("title", String?.self),
            .field("publishedAt", CloudNotesGraphQL.DateTime?.self),
            .field("docUpdatedAt", CloudNotesGraphQL.DateTime?.self),
            .field("workspaceOwnerId", String?.self),
            .field("lastUpdaterId", String?.self),
            .field("shareUrl", String.self),
            .field("views", CloudNotesGraphQL.SafeInt?.self),
            .field("uniqueViews", CloudNotesGraphQL.SafeInt?.self),
            .field("guestViews", CloudNotesGraphQL.SafeInt?.self),
            .field("lastAccessedAt", CloudNotesGraphQL.DateTime?.self),
          ] }
          public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
            AdminAllSharedLinksQuery.Data.AdminAllSharedLinks.Edge.Node.self
          ] }

          public var workspaceId: String { __data["workspaceId"] }
          public var docId: String { __data["docId"] }
          public var title: String? { __data["title"] }
          public var publishedAt: CloudNotesGraphQL.DateTime? { __data["publishedAt"] }
          public var docUpdatedAt: CloudNotesGraphQL.DateTime? { __data["docUpdatedAt"] }
          public var workspaceOwnerId: String? { __data["workspaceOwnerId"] }
          public var lastUpdaterId: String? { __data["lastUpdaterId"] }
          public var shareUrl: String { __data["shareUrl"] }
          public var views: CloudNotesGraphQL.SafeInt? { __data["views"] }
          public var uniqueViews: CloudNotesGraphQL.SafeInt? { __data["uniqueViews"] }
          public var guestViews: CloudNotesGraphQL.SafeInt? { __data["guestViews"] }
          public var lastAccessedAt: CloudNotesGraphQL.DateTime? { __data["lastAccessedAt"] }
        }
      }
    }
  }
}

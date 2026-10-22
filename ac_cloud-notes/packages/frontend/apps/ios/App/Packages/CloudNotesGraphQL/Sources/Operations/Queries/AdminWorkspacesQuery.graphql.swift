// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class AdminWorkspacesQuery: GraphQLQuery {
  public static let operationName: String = "adminWorkspaces"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query adminWorkspaces($filter: ListWorkspaceInput!) { adminWorkspaces(filter: $filter) { __typename id public createdAt name avatarKey enableAi enableSharing enableUrlPreview enableDocEmbedding owner { __typename id name email avatarUrl } memberCount publicPageCount snapshotCount snapshotSize blobCount blobSize } }"#
    ))

  public var filter: ListWorkspaceInput

  public init(filter: ListWorkspaceInput) {
    self.filter = filter
  }

  public var __variables: Variables? { ["filter": filter] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("adminWorkspaces", [AdminWorkspace].self, arguments: ["filter": .variable("filter")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      AdminWorkspacesQuery.Data.self
    ] }

    /// List workspaces for admin
    public var adminWorkspaces: [AdminWorkspace] { __data["adminWorkspaces"] }

    /// AdminWorkspace
    ///
    /// Parent Type: `AdminWorkspace`
    public struct AdminWorkspace: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminWorkspace }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", String.self),
        .field("public", Bool.self),
        .field("createdAt", CloudNotesGraphQL.DateTime.self),
        .field("name", String?.self),
        .field("avatarKey", String?.self),
        .field("enableAi", Bool.self),
        .field("enableSharing", Bool.self),
        .field("enableUrlPreview", Bool.self),
        .field("enableDocEmbedding", Bool.self),
        .field("owner", Owner?.self),
        .field("memberCount", Int.self),
        .field("publicPageCount", Int.self),
        .field("snapshotCount", Int.self),
        .field("snapshotSize", CloudNotesGraphQL.SafeInt.self),
        .field("blobCount", Int.self),
        .field("blobSize", CloudNotesGraphQL.SafeInt.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        AdminWorkspacesQuery.Data.AdminWorkspace.self
      ] }

      public var id: String { __data["id"] }
      public var `public`: Bool { __data["public"] }
      public var createdAt: CloudNotesGraphQL.DateTime { __data["createdAt"] }
      public var name: String? { __data["name"] }
      public var avatarKey: String? { __data["avatarKey"] }
      public var enableAi: Bool { __data["enableAi"] }
      public var enableSharing: Bool { __data["enableSharing"] }
      public var enableUrlPreview: Bool { __data["enableUrlPreview"] }
      public var enableDocEmbedding: Bool { __data["enableDocEmbedding"] }
      public var owner: Owner? { __data["owner"] }
      public var memberCount: Int { __data["memberCount"] }
      public var publicPageCount: Int { __data["publicPageCount"] }
      public var snapshotCount: Int { __data["snapshotCount"] }
      public var snapshotSize: CloudNotesGraphQL.SafeInt { __data["snapshotSize"] }
      public var blobCount: Int { __data["blobCount"] }
      public var blobSize: CloudNotesGraphQL.SafeInt { __data["blobSize"] }

      /// AdminWorkspace.Owner
      ///
      /// Parent Type: `WorkspaceUserType`
      public struct Owner: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceUserType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("id", String.self),
          .field("name", String.self),
          .field("email", String.self),
          .field("avatarUrl", String?.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminWorkspacesQuery.Data.AdminWorkspace.Owner.self
        ] }

        public var id: String { __data["id"] }
        public var name: String { __data["name"] }
        public var email: String { __data["email"] }
        public var avatarUrl: String? { __data["avatarUrl"] }
      }
    }
  }
}

// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class CreateWorkspaceMutation: GraphQLMutation {
  public static let operationName: String = "createWorkspace"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation createWorkspace { createWorkspace { __typename id public createdAt } }"#
    ))

  public init() {}

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("createWorkspace", CreateWorkspace.self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CreateWorkspaceMutation.Data.self
    ] }

    /// Create a new workspace
    public var createWorkspace: CreateWorkspace { __data["createWorkspace"] }

    /// CreateWorkspace
    ///
    /// Parent Type: `WorkspaceType`
    public struct CreateWorkspace: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", CloudNotesGraphQL.ID.self),
        .field("public", Bool.self),
        .field("createdAt", CloudNotesGraphQL.DateTime.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        CreateWorkspaceMutation.Data.CreateWorkspace.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
      /// is Public workspace
      public var `public`: Bool { __data["public"] }
      /// Workspace created date
      public var createdAt: CloudNotesGraphQL.DateTime { __data["createdAt"] }
    }
  }
}

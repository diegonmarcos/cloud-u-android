// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class SetEnableSharingMutation: GraphQLMutation {
  public static let operationName: String = "setEnableSharing"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation setEnableSharing($id: ID!, $enableSharing: Boolean!) { updateWorkspace(input: { id: $id, enableSharing: $enableSharing }) { __typename id } }"#
    ))

  public var id: ID
  public var enableSharing: Bool

  public init(
    id: ID,
    enableSharing: Bool
  ) {
    self.id = id
    self.enableSharing = enableSharing
  }

  public var __variables: Variables? { [
    "id": id,
    "enableSharing": enableSharing
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("updateWorkspace", UpdateWorkspace.self, arguments: ["input": [
        "id": .variable("id"),
        "enableSharing": .variable("enableSharing")
      ]]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      SetEnableSharingMutation.Data.self
    ] }

    /// Update workspace
    public var updateWorkspace: UpdateWorkspace { __data["updateWorkspace"] }

    /// UpdateWorkspace
    ///
    /// Parent Type: `WorkspaceType`
    public struct UpdateWorkspace: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", CloudNotesGraphQL.ID.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        SetEnableSharingMutation.Data.UpdateWorkspace.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
    }
  }
}

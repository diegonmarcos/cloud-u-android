// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class SetEnableUrlPreviewMutation: GraphQLMutation {
  public static let operationName: String = "setEnableUrlPreview"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation setEnableUrlPreview($id: ID!, $enableUrlPreview: Boolean!) { updateWorkspace(input: { id: $id, enableUrlPreview: $enableUrlPreview }) { __typename id } }"#
    ))

  public var id: ID
  public var enableUrlPreview: Bool

  public init(
    id: ID,
    enableUrlPreview: Bool
  ) {
    self.id = id
    self.enableUrlPreview = enableUrlPreview
  }

  public var __variables: Variables? { [
    "id": id,
    "enableUrlPreview": enableUrlPreview
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("updateWorkspace", UpdateWorkspace.self, arguments: ["input": [
        "id": .variable("id"),
        "enableUrlPreview": .variable("enableUrlPreview")
      ]]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      SetEnableUrlPreviewMutation.Data.self
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
        SetEnableUrlPreviewMutation.Data.UpdateWorkspace.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
    }
  }
}

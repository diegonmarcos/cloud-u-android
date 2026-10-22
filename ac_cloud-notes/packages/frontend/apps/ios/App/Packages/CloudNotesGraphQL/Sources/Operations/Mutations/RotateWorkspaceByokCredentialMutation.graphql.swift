// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class RotateWorkspaceByokCredentialMutation: GraphQLMutation {
  public static let operationName: String = "rotateWorkspaceByokCredential"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation rotateWorkspaceByokCredential($input: RotateWorkspaceByokCredentialInput!) { rotateWorkspaceByokCredential(input: $input) { __typename profileId } }"#
    ))

  public var input: RotateWorkspaceByokCredentialInput

  public init(input: RotateWorkspaceByokCredentialInput) {
    self.input = input
  }

  public var __variables: Variables? { ["input": input] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("rotateWorkspaceByokCredential", RotateWorkspaceByokCredential.self, arguments: ["input": .variable("input")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      RotateWorkspaceByokCredentialMutation.Data.self
    ] }

    public var rotateWorkspaceByokCredential: RotateWorkspaceByokCredential { __data["rotateWorkspaceByokCredential"] }

    /// RotateWorkspaceByokCredential
    ///
    /// Parent Type: `WorkspaceByokProfileType`
    public struct RotateWorkspaceByokCredential: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokProfileType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("profileId", CloudNotesGraphQL.ID.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        RotateWorkspaceByokCredentialMutation.Data.RotateWorkspaceByokCredential.self
      ] }

      public var profileId: CloudNotesGraphQL.ID { __data["profileId"] }
    }
  }
}

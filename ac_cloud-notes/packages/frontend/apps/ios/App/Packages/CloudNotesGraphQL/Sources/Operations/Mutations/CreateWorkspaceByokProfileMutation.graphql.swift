// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class CreateWorkspaceByokProfileMutation: GraphQLMutation {
  public static let operationName: String = "createWorkspaceByokProfile"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation createWorkspaceByokProfile($input: CreateWorkspaceByokProfileInput!) { createWorkspaceByokProfile(input: $input) { __typename profileId } }"#
    ))

  public var input: CreateWorkspaceByokProfileInput

  public init(input: CreateWorkspaceByokProfileInput) {
    self.input = input
  }

  public var __variables: Variables? { ["input": input] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("createWorkspaceByokProfile", CreateWorkspaceByokProfile.self, arguments: ["input": .variable("input")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CreateWorkspaceByokProfileMutation.Data.self
    ] }

    public var createWorkspaceByokProfile: CreateWorkspaceByokProfile { __data["createWorkspaceByokProfile"] }

    /// CreateWorkspaceByokProfile
    ///
    /// Parent Type: `WorkspaceByokProfileType`
    public struct CreateWorkspaceByokProfile: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokProfileType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("profileId", CloudNotesGraphQL.ID.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        CreateWorkspaceByokProfileMutation.Data.CreateWorkspaceByokProfile.self
      ] }

      public var profileId: CloudNotesGraphQL.ID { __data["profileId"] }
    }
  }
}

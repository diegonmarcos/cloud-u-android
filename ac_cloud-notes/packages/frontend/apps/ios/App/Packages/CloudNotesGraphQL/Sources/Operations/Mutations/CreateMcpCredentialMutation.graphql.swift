// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class CreateMcpCredentialMutation: GraphQLMutation {
  public static let operationName: String = "createMcpCredential"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation createMcpCredential($input: CreateMcpCredentialInput!) { createMcpCredential(input: $input) { __typename credential { __typename id name workspaceId accessMode fingerprint createdAt expiresAt lastUsedAt revokedAt graceEndsAt status } token } }"#
    ))

  public var input: CreateMcpCredentialInput

  public init(input: CreateMcpCredentialInput) {
    self.input = input
  }

  public var __variables: Variables? { ["input": input] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("createMcpCredential", CreateMcpCredential.self, arguments: ["input": .variable("input")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CreateMcpCredentialMutation.Data.self
    ] }

    public var createMcpCredential: CreateMcpCredential { __data["createMcpCredential"] }

    /// CreateMcpCredential
    ///
    /// Parent Type: `RevealedMcpCredentialType`
    public struct CreateMcpCredential: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.RevealedMcpCredentialType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("credential", Credential.self),
        .field("token", String.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        CreateMcpCredentialMutation.Data.CreateMcpCredential.self
      ] }

      public var credential: Credential { __data["credential"] }
      public var token: String { __data["token"] }

      /// CreateMcpCredential.Credential
      ///
      /// Parent Type: `McpCredentialType`
      public struct Credential: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.McpCredentialType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("id", CloudNotesGraphQL.ID.self),
          .field("name", String.self),
          .field("workspaceId", String.self),
          .field("accessMode", GraphQLEnum<CloudNotesGraphQL.McpAccessMode>.self),
          .field("fingerprint", String.self),
          .field("createdAt", CloudNotesGraphQL.DateTime.self),
          .field("expiresAt", CloudNotesGraphQL.DateTime.self),
          .field("lastUsedAt", CloudNotesGraphQL.DateTime?.self),
          .field("revokedAt", CloudNotesGraphQL.DateTime?.self),
          .field("graceEndsAt", CloudNotesGraphQL.DateTime?.self),
          .field("status", GraphQLEnum<CloudNotesGraphQL.McpCredentialStatus>.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          CreateMcpCredentialMutation.Data.CreateMcpCredential.Credential.self
        ] }

        public var id: CloudNotesGraphQL.ID { __data["id"] }
        public var name: String { __data["name"] }
        public var workspaceId: String { __data["workspaceId"] }
        public var accessMode: GraphQLEnum<CloudNotesGraphQL.McpAccessMode> { __data["accessMode"] }
        public var fingerprint: String { __data["fingerprint"] }
        public var createdAt: CloudNotesGraphQL.DateTime { __data["createdAt"] }
        public var expiresAt: CloudNotesGraphQL.DateTime { __data["expiresAt"] }
        public var lastUsedAt: CloudNotesGraphQL.DateTime? { __data["lastUsedAt"] }
        public var revokedAt: CloudNotesGraphQL.DateTime? { __data["revokedAt"] }
        public var graceEndsAt: CloudNotesGraphQL.DateTime? { __data["graceEndsAt"] }
        public var status: GraphQLEnum<CloudNotesGraphQL.McpCredentialStatus> { __data["status"] }
      }
    }
  }
}

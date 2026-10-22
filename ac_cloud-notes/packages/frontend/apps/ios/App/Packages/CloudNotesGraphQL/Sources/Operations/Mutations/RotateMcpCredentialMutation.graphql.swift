// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class RotateMcpCredentialMutation: GraphQLMutation {
  public static let operationName: String = "rotateMcpCredential"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation rotateMcpCredential($id: ID!, $workspaceId: String!, $expirationDays: Int!) { rotateMcpCredential( id: $id workspaceId: $workspaceId expirationDays: $expirationDays ) { __typename credential { __typename id name workspaceId accessMode fingerprint createdAt expiresAt lastUsedAt revokedAt graceEndsAt status } token } }"#
    ))

  public var id: ID
  public var workspaceId: String
  public var expirationDays: Int

  public init(
    id: ID,
    workspaceId: String,
    expirationDays: Int
  ) {
    self.id = id
    self.workspaceId = workspaceId
    self.expirationDays = expirationDays
  }

  public var __variables: Variables? { [
    "id": id,
    "workspaceId": workspaceId,
    "expirationDays": expirationDays
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("rotateMcpCredential", RotateMcpCredential.self, arguments: [
        "id": .variable("id"),
        "workspaceId": .variable("workspaceId"),
        "expirationDays": .variable("expirationDays")
      ]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      RotateMcpCredentialMutation.Data.self
    ] }

    public var rotateMcpCredential: RotateMcpCredential { __data["rotateMcpCredential"] }

    /// RotateMcpCredential
    ///
    /// Parent Type: `RevealedMcpCredentialType`
    public struct RotateMcpCredential: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.RevealedMcpCredentialType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("credential", Credential.self),
        .field("token", String.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        RotateMcpCredentialMutation.Data.RotateMcpCredential.self
      ] }

      public var credential: Credential { __data["credential"] }
      public var token: String { __data["token"] }

      /// RotateMcpCredential.Credential
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
          RotateMcpCredentialMutation.Data.RotateMcpCredential.Credential.self
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

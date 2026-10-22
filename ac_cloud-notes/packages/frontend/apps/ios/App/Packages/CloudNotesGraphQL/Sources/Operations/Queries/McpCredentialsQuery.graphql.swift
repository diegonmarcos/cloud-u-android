// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class McpCredentialsQuery: GraphQLQuery {
  public static let operationName: String = "mcpCredentials"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query mcpCredentials($workspaceId: String!) { mcpCredentialReadWriteAvailable mcpCredentials(workspaceId: $workspaceId) { __typename id name workspaceId accessMode fingerprint createdAt expiresAt lastUsedAt revokedAt graceEndsAt status } }"#
    ))

  public var workspaceId: String

  public init(workspaceId: String) {
    self.workspaceId = workspaceId
  }

  public var __variables: Variables? { ["workspaceId": workspaceId] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("mcpCredentialReadWriteAvailable", Bool.self),
      .field("mcpCredentials", [McpCredential].self, arguments: ["workspaceId": .variable("workspaceId")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      McpCredentialsQuery.Data.self
    ] }

    public var mcpCredentialReadWriteAvailable: Bool { __data["mcpCredentialReadWriteAvailable"] }
    public var mcpCredentials: [McpCredential] { __data["mcpCredentials"] }

    /// McpCredential
    ///
    /// Parent Type: `McpCredentialType`
    public struct McpCredential: CloudNotesGraphQL.SelectionSet {
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
        McpCredentialsQuery.Data.McpCredential.self
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

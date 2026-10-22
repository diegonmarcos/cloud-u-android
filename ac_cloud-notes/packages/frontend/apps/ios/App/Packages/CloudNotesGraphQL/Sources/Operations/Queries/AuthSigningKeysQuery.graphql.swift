// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class AuthSigningKeysQuery: GraphQLQuery {
  public static let operationName: String = "authSigningKeys"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query authSigningKeys { authSigningKeys { __typename id status source createdAt retiredAt verifyUntil canDelete } }"#
    ))

  public init() {}

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("authSigningKeys", [AuthSigningKey].self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      AuthSigningKeysQuery.Data.self
    ] }

    public var authSigningKeys: [AuthSigningKey] { __data["authSigningKeys"] }

    /// AuthSigningKey
    ///
    /// Parent Type: `AuthSigningKeyType`
    public struct AuthSigningKey: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AuthSigningKeyType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", String.self),
        .field("status", String.self),
        .field("source", String.self),
        .field("createdAt", CloudNotesGraphQL.DateTime?.self),
        .field("retiredAt", CloudNotesGraphQL.DateTime?.self),
        .field("verifyUntil", CloudNotesGraphQL.DateTime?.self),
        .field("canDelete", Bool.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        AuthSigningKeysQuery.Data.AuthSigningKey.self
      ] }

      public var id: String { __data["id"] }
      public var status: String { __data["status"] }
      public var source: String { __data["source"] }
      public var createdAt: CloudNotesGraphQL.DateTime? { __data["createdAt"] }
      public var retiredAt: CloudNotesGraphQL.DateTime? { __data["retiredAt"] }
      public var verifyUntil: CloudNotesGraphQL.DateTime? { __data["verifyUntil"] }
      public var canDelete: Bool { __data["canDelete"] }
    }
  }
}

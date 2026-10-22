// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class RotateAuthSigningKeyMutation: GraphQLMutation {
  public static let operationName: String = "rotateAuthSigningKey"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation rotateAuthSigningKey($expectedActiveKeyId: String!) { rotateAuthSigningKey(expectedActiveKeyId: $expectedActiveKeyId) { __typename id status source createdAt retiredAt verifyUntil canDelete } }"#
    ))

  public var expectedActiveKeyId: String

  public init(expectedActiveKeyId: String) {
    self.expectedActiveKeyId = expectedActiveKeyId
  }

  public var __variables: Variables? { ["expectedActiveKeyId": expectedActiveKeyId] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("rotateAuthSigningKey", [RotateAuthSigningKey].self, arguments: ["expectedActiveKeyId": .variable("expectedActiveKeyId")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      RotateAuthSigningKeyMutation.Data.self
    ] }

    public var rotateAuthSigningKey: [RotateAuthSigningKey] { __data["rotateAuthSigningKey"] }

    /// RotateAuthSigningKey
    ///
    /// Parent Type: `AuthSigningKeyType`
    public struct RotateAuthSigningKey: CloudNotesGraphQL.SelectionSet {
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
        RotateAuthSigningKeyMutation.Data.RotateAuthSigningKey.self
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

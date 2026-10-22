// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class DeleteAccountMutation: GraphQLMutation {
  public static let operationName: String = "deleteAccount"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation deleteAccount { deleteAccount { __typename success } }"#
    ))

  public init() {}

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("deleteAccount", DeleteAccount.self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      DeleteAccountMutation.Data.self
    ] }

    public var deleteAccount: DeleteAccount { __data["deleteAccount"] }

    /// DeleteAccount
    ///
    /// Parent Type: `DeleteAccount`
    public struct DeleteAccount: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.DeleteAccount }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("success", Bool.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        DeleteAccountMutation.Data.DeleteAccount.self
      ] }

      public var success: Bool { __data["success"] }
    }
  }
}

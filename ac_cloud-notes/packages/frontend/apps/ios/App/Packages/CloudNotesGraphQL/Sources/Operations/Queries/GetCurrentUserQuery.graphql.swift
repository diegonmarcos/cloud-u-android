// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class GetCurrentUserQuery: GraphQLQuery {
  public static let operationName: String = "getCurrentUser"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query getCurrentUser { currentUser { __typename id name email emailVerified avatarUrl hasPassword features } }"#
    ))

  public init() {}

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("currentUser", CurrentUser?.self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      GetCurrentUserQuery.Data.self
    ] }

    /// Get current user
    public var currentUser: CurrentUser? { __data["currentUser"] }

    /// CurrentUser
    ///
    /// Parent Type: `UserType`
    public struct CurrentUser: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.UserType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", CloudNotesGraphQL.ID.self),
        .field("name", String.self),
        .field("email", String.self),
        .field("emailVerified", Bool.self),
        .field("avatarUrl", String?.self),
        .field("hasPassword", Bool?.self),
        .field("features", [GraphQLEnum<CloudNotesGraphQL.FeatureType>].self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        GetCurrentUserQuery.Data.CurrentUser.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
      /// User name
      public var name: String { __data["name"] }
      /// User email
      public var email: String { __data["email"] }
      /// User email verified
      public var emailVerified: Bool { __data["emailVerified"] }
      /// User avatar url
      public var avatarUrl: String? { __data["avatarUrl"] }
      /// User password has been set
      public var hasPassword: Bool? { __data["hasPassword"] }
      /// Enabled features of a user
      public var features: [GraphQLEnum<CloudNotesGraphQL.FeatureType>] { __data["features"] }
    }
  }
}

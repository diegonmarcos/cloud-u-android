// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class GetUserFeaturesQuery: GraphQLQuery {
  public static let operationName: String = "getUserFeatures"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query getUserFeatures { currentUser { __typename id features } }"#
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
      GetUserFeaturesQuery.Data.self
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
        .field("features", [GraphQLEnum<CloudNotesGraphQL.FeatureType>].self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        GetUserFeaturesQuery.Data.CurrentUser.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
      /// Enabled features of a user
      public var features: [GraphQLEnum<CloudNotesGraphQL.FeatureType>] { __data["features"] }
    }
  }
}

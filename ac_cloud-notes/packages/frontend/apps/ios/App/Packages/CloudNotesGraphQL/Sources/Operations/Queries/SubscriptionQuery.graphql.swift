// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class SubscriptionQuery: GraphQLQuery {
  public static let operationName: String = "subscription"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query subscription { currentUser { __typename id subscriptions { __typename id status plan recurring start end nextBillAt canceledAt variant } } }"#
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
      SubscriptionQuery.Data.self
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
        .field("subscriptions", [Subscription].self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        SubscriptionQuery.Data.CurrentUser.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
      public var subscriptions: [Subscription] { __data["subscriptions"] }

      /// CurrentUser.Subscription
      ///
      /// Parent Type: `SubscriptionType`
      public struct Subscription: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.SubscriptionType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("id", String?.self),
          .field("status", GraphQLEnum<CloudNotesGraphQL.SubscriptionStatus>.self),
          .field("plan", GraphQLEnum<CloudNotesGraphQL.SubscriptionPlan>.self),
          .field("recurring", GraphQLEnum<CloudNotesGraphQL.SubscriptionRecurring>.self),
          .field("start", CloudNotesGraphQL.DateTime.self),
          .field("end", CloudNotesGraphQL.DateTime?.self),
          .field("nextBillAt", CloudNotesGraphQL.DateTime?.self),
          .field("canceledAt", CloudNotesGraphQL.DateTime?.self),
          .field("variant", GraphQLEnum<CloudNotesGraphQL.SubscriptionVariant>?.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          SubscriptionQuery.Data.CurrentUser.Subscription.self
        ] }

        @available(*, deprecated, message: "removed")
        public var id: String? { __data["id"] }
        public var status: GraphQLEnum<CloudNotesGraphQL.SubscriptionStatus> { __data["status"] }
        /// The 'Free' plan just exists to be a placeholder and for the type convenience of frontend.
        /// There won't actually be a subscription with plan 'Free'
        public var plan: GraphQLEnum<CloudNotesGraphQL.SubscriptionPlan> { __data["plan"] }
        public var recurring: GraphQLEnum<CloudNotesGraphQL.SubscriptionRecurring> { __data["recurring"] }
        public var start: CloudNotesGraphQL.DateTime { __data["start"] }
        public var end: CloudNotesGraphQL.DateTime? { __data["end"] }
        public var nextBillAt: CloudNotesGraphQL.DateTime? { __data["nextBillAt"] }
        public var canceledAt: CloudNotesGraphQL.DateTime? { __data["canceledAt"] }
        public var variant: GraphQLEnum<CloudNotesGraphQL.SubscriptionVariant>? { __data["variant"] }
      }
    }
  }
}

// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class GetWorkspaceSubscriptionQuery: GraphQLQuery {
  public static let operationName: String = "getWorkspaceSubscription"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query getWorkspaceSubscription($workspaceId: String!) { workspace(id: $workspaceId) { __typename subscription { __typename id status plan recurring start end nextBillAt canceledAt variant } } }"#
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
      .field("workspace", Workspace.self, arguments: ["id": .variable("workspaceId")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      GetWorkspaceSubscriptionQuery.Data.self
    ] }

    /// Get workspace by id
    public var workspace: Workspace { __data["workspace"] }

    /// Workspace
    ///
    /// Parent Type: `WorkspaceType`
    public struct Workspace: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("subscription", Subscription?.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        GetWorkspaceSubscriptionQuery.Data.Workspace.self
      ] }

      /// The team subscription of the workspace, if exists.
      public var subscription: Subscription? { __data["subscription"] }

      /// Workspace.Subscription
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
          GetWorkspaceSubscriptionQuery.Data.Workspace.Subscription.self
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

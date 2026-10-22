// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class ResumeSubscriptionMutation: GraphQLMutation {
  public static let operationName: String = "resumeSubscription"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation resumeSubscription($plan: SubscriptionPlan = Pro, $workspaceId: String) { resumeSubscription(plan: $plan, workspaceId: $workspaceId) { __typename id status nextBillAt start end } }"#
    ))

  public var plan: GraphQLNullable<GraphQLEnum<SubscriptionPlan>>
  public var workspaceId: GraphQLNullable<String>

  public init(
    plan: GraphQLNullable<GraphQLEnum<SubscriptionPlan>> = .init(.pro),
    workspaceId: GraphQLNullable<String>
  ) {
    self.plan = plan
    self.workspaceId = workspaceId
  }

  public var __variables: Variables? { [
    "plan": plan,
    "workspaceId": workspaceId
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("resumeSubscription", ResumeSubscription.self, arguments: [
        "plan": .variable("plan"),
        "workspaceId": .variable("workspaceId")
      ]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      ResumeSubscriptionMutation.Data.self
    ] }

    public var resumeSubscription: ResumeSubscription { __data["resumeSubscription"] }

    /// ResumeSubscription
    ///
    /// Parent Type: `SubscriptionType`
    public struct ResumeSubscription: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.SubscriptionType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", String?.self),
        .field("status", GraphQLEnum<CloudNotesGraphQL.SubscriptionStatus>.self),
        .field("nextBillAt", CloudNotesGraphQL.DateTime?.self),
        .field("start", CloudNotesGraphQL.DateTime.self),
        .field("end", CloudNotesGraphQL.DateTime?.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        ResumeSubscriptionMutation.Data.ResumeSubscription.self
      ] }

      @available(*, deprecated, message: "removed")
      public var id: String? { __data["id"] }
      public var status: GraphQLEnum<CloudNotesGraphQL.SubscriptionStatus> { __data["status"] }
      public var nextBillAt: CloudNotesGraphQL.DateTime? { __data["nextBillAt"] }
      public var start: CloudNotesGraphQL.DateTime { __data["start"] }
      public var end: CloudNotesGraphQL.DateTime? { __data["end"] }
    }
  }
}

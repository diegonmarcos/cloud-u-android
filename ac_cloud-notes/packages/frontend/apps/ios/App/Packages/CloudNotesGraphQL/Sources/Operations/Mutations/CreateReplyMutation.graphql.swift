// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class CreateReplyMutation: GraphQLMutation {
  public static let operationName: String = "createReply"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation createReply($input: ReplyCreateInput!) { createReply(input: $input) { __typename commentId id content createdAt updatedAt user { __typename id name avatarUrl } } }"#
    ))

  public var input: ReplyCreateInput

  public init(input: ReplyCreateInput) {
    self.input = input
  }

  public var __variables: Variables? { ["input": input] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("createReply", CreateReply.self, arguments: ["input": .variable("input")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CreateReplyMutation.Data.self
    ] }

    public var createReply: CreateReply { __data["createReply"] }

    /// CreateReply
    ///
    /// Parent Type: `ReplyObjectType`
    public struct CreateReply: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.ReplyObjectType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("commentId", CloudNotesGraphQL.ID.self),
        .field("id", CloudNotesGraphQL.ID.self),
        .field("content", CloudNotesGraphQL.JSONObject.self),
        .field("createdAt", CloudNotesGraphQL.DateTime.self),
        .field("updatedAt", CloudNotesGraphQL.DateTime.self),
        .field("user", User.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        CreateReplyMutation.Data.CreateReply.self
      ] }

      public var commentId: CloudNotesGraphQL.ID { __data["commentId"] }
      public var id: CloudNotesGraphQL.ID { __data["id"] }
      /// The content of the reply
      public var content: CloudNotesGraphQL.JSONObject { __data["content"] }
      /// The created at time of the reply
      public var createdAt: CloudNotesGraphQL.DateTime { __data["createdAt"] }
      /// The updated at time of the reply
      public var updatedAt: CloudNotesGraphQL.DateTime { __data["updatedAt"] }
      /// The user who created the reply
      public var user: User { __data["user"] }

      /// CreateReply.User
      ///
      /// Parent Type: `PublicUserType`
      public struct User: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.PublicUserType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("id", String.self),
          .field("name", String.self),
          .field("avatarUrl", String?.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          CreateReplyMutation.Data.CreateReply.User.self
        ] }

        public var id: String { __data["id"] }
        public var name: String { __data["name"] }
        public var avatarUrl: String? { __data["avatarUrl"] }
      }
    }
  }
}

// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class CreateCommentMutation: GraphQLMutation {
  public static let operationName: String = "createComment"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation createComment($input: CommentCreateInput!) { createComment(input: $input) { __typename id content resolved createdAt updatedAt user { __typename id name avatarUrl } replies { __typename commentId id content createdAt updatedAt user { __typename id name avatarUrl } } } }"#
    ))

  public var input: CommentCreateInput

  public init(input: CommentCreateInput) {
    self.input = input
  }

  public var __variables: Variables? { ["input": input] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("createComment", CreateComment.self, arguments: ["input": .variable("input")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CreateCommentMutation.Data.self
    ] }

    public var createComment: CreateComment { __data["createComment"] }

    /// CreateComment
    ///
    /// Parent Type: `CommentObjectType`
    public struct CreateComment: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.CommentObjectType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", CloudNotesGraphQL.ID.self),
        .field("content", CloudNotesGraphQL.JSONObject.self),
        .field("resolved", Bool.self),
        .field("createdAt", CloudNotesGraphQL.DateTime.self),
        .field("updatedAt", CloudNotesGraphQL.DateTime.self),
        .field("user", User.self),
        .field("replies", [Reply].self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        CreateCommentMutation.Data.CreateComment.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
      /// The content of the comment
      public var content: CloudNotesGraphQL.JSONObject { __data["content"] }
      /// Whether the comment is resolved
      public var resolved: Bool { __data["resolved"] }
      /// The created at time of the comment
      public var createdAt: CloudNotesGraphQL.DateTime { __data["createdAt"] }
      /// The updated at time of the comment
      public var updatedAt: CloudNotesGraphQL.DateTime { __data["updatedAt"] }
      /// The user who created the comment
      public var user: User { __data["user"] }
      /// The replies of the comment
      public var replies: [Reply] { __data["replies"] }

      /// CreateComment.User
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
          CreateCommentMutation.Data.CreateComment.User.self
        ] }

        public var id: String { __data["id"] }
        public var name: String { __data["name"] }
        public var avatarUrl: String? { __data["avatarUrl"] }
      }

      /// CreateComment.Reply
      ///
      /// Parent Type: `ReplyObjectType`
      public struct Reply: CloudNotesGraphQL.SelectionSet {
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
          CreateCommentMutation.Data.CreateComment.Reply.self
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

        /// CreateComment.Reply.User
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
            CreateCommentMutation.Data.CreateComment.Reply.User.self
          ] }

          public var id: String { __data["id"] }
          public var name: String { __data["name"] }
          public var avatarUrl: String? { __data["avatarUrl"] }
        }
      }
    }
  }
}

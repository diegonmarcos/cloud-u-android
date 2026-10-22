// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class RetryTranscriptTaskMutation: GraphQLMutation {
  public static let operationName: String = "retryTranscriptTask"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation retryTranscriptTask($workspaceId: String!, $taskId: String!) { retryTranscriptTask(workspaceId: $workspaceId, taskId: $taskId) { __typename id status } }"#
    ))

  public var workspaceId: String
  public var taskId: String

  public init(
    workspaceId: String,
    taskId: String
  ) {
    self.workspaceId = workspaceId
    self.taskId = taskId
  }

  public var __variables: Variables? { [
    "workspaceId": workspaceId,
    "taskId": taskId
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("retryTranscriptTask", RetryTranscriptTask?.self, arguments: [
        "workspaceId": .variable("workspaceId"),
        "taskId": .variable("taskId")
      ]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      RetryTranscriptTaskMutation.Data.self
    ] }

    public var retryTranscriptTask: RetryTranscriptTask? { __data["retryTranscriptTask"] }

    /// RetryTranscriptTask
    ///
    /// Parent Type: `TranscriptionResultType`
    public struct RetryTranscriptTask: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.TranscriptionResultType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", CloudNotesGraphQL.ID.self),
        .field("status", GraphQLEnum<CloudNotesGraphQL.AiJobStatus>.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        RetryTranscriptTaskMutation.Data.RetryTranscriptTask.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
      public var status: GraphQLEnum<CloudNotesGraphQL.AiJobStatus> { __data["status"] }
    }
  }
}

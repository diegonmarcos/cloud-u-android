// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class RecoverDocMutation: GraphQLMutation {
  public static let operationName: String = "recoverDoc"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation recoverDoc($workspaceId: String!, $docId: String!, $timestamp: DateTime!) { recoverDoc(workspaceId: $workspaceId, guid: $docId, timestamp: $timestamp) }"#
    ))

  public var workspaceId: String
  public var docId: String
  public var timestamp: DateTime

  public init(
    workspaceId: String,
    docId: String,
    timestamp: DateTime
  ) {
    self.workspaceId = workspaceId
    self.docId = docId
    self.timestamp = timestamp
  }

  public var __variables: Variables? { [
    "workspaceId": workspaceId,
    "docId": docId,
    "timestamp": timestamp
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("recoverDoc", CloudNotesGraphQL.DateTime.self, arguments: [
        "workspaceId": .variable("workspaceId"),
        "guid": .variable("docId"),
        "timestamp": .variable("timestamp")
      ]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      RecoverDocMutation.Data.self
    ] }

    public var recoverDoc: CloudNotesGraphQL.DateTime { __data["recoverDoc"] }
  }
}

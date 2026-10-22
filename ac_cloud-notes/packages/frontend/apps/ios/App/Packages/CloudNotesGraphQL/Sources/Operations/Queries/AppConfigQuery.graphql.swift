// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class AppConfigQuery: GraphQLQuery {
  public static let operationName: String = "appConfig"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query appConfig { appConfig }"#
    ))

  public init() {}

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("appConfig", CloudNotesGraphQL.JSONObject.self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      AppConfigQuery.Data.self
    ] }

    /// get the whole app configuration
    public var appConfig: CloudNotesGraphQL.JSONObject { __data["appConfig"] }
  }
}

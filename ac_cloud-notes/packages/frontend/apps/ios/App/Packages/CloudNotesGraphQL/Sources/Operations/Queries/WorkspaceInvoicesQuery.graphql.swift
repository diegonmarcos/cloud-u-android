// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class WorkspaceInvoicesQuery: GraphQLQuery {
  public static let operationName: String = "workspaceInvoices"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query workspaceInvoices($take: Int!, $skip: Int!, $workspaceId: String!) { workspace(id: $workspaceId) { __typename invoiceCount invoices(take: $take, skip: $skip) { __typename status currency amount reason lastPaymentError link createdAt } } }"#
    ))

  public var take: Int
  public var skip: Int
  public var workspaceId: String

  public init(
    take: Int,
    skip: Int,
    workspaceId: String
  ) {
    self.take = take
    self.skip = skip
    self.workspaceId = workspaceId
  }

  public var __variables: Variables? { [
    "take": take,
    "skip": skip,
    "workspaceId": workspaceId
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("workspace", Workspace.self, arguments: ["id": .variable("workspaceId")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      WorkspaceInvoicesQuery.Data.self
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
        .field("invoiceCount", Int.self),
        .field("invoices", [Invoice].self, arguments: [
          "take": .variable("take"),
          "skip": .variable("skip")
        ]),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        WorkspaceInvoicesQuery.Data.Workspace.self
      ] }

      /// Get user invoice count
      public var invoiceCount: Int { __data["invoiceCount"] }
      public var invoices: [Invoice] { __data["invoices"] }

      /// Workspace.Invoice
      ///
      /// Parent Type: `InvoiceType`
      public struct Invoice: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.InvoiceType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("status", GraphQLEnum<CloudNotesGraphQL.InvoiceStatus>.self),
          .field("currency", String.self),
          .field("amount", Int.self),
          .field("reason", String.self),
          .field("lastPaymentError", String?.self),
          .field("link", String?.self),
          .field("createdAt", CloudNotesGraphQL.DateTime.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          WorkspaceInvoicesQuery.Data.Workspace.Invoice.self
        ] }

        public var status: GraphQLEnum<CloudNotesGraphQL.InvoiceStatus> { __data["status"] }
        public var currency: String { __data["currency"] }
        public var amount: Int { __data["amount"] }
        public var reason: String { __data["reason"] }
        public var lastPaymentError: String? { __data["lastPaymentError"] }
        public var link: String? { __data["link"] }
        public var createdAt: CloudNotesGraphQL.DateTime { __data["createdAt"] }
      }
    }
  }
}

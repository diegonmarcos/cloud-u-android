// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class PreviewLicenseMutation: GraphQLMutation {
  public static let operationName: String = "previewLicense"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation previewLicense($license: Upload!) { previewLicense(license: $license) { __typename id workspaceId plan recurring quantity issuedAt expiresAt endAt entity issuer valid } }"#
    ))

  public var license: Upload

  public init(license: Upload) {
    self.license = license
  }

  public var __variables: Variables? { ["license": license] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("previewLicense", PreviewLicense.self, arguments: ["license": .variable("license")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      PreviewLicenseMutation.Data.self
    ] }

    public var previewLicense: PreviewLicense { __data["previewLicense"] }

    /// PreviewLicense
    ///
    /// Parent Type: `AdminLicensePreview`
    public struct PreviewLicense: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.AdminLicensePreview }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("id", String.self),
        .field("workspaceId", String.self),
        .field("plan", GraphQLEnum<CloudNotesGraphQL.SubscriptionPlan>.self),
        .field("recurring", GraphQLEnum<CloudNotesGraphQL.SubscriptionRecurring>.self),
        .field("quantity", Int.self),
        .field("issuedAt", CloudNotesGraphQL.DateTime.self),
        .field("expiresAt", CloudNotesGraphQL.DateTime.self),
        .field("endAt", CloudNotesGraphQL.DateTime.self),
        .field("entity", String.self),
        .field("issuer", String.self),
        .field("valid", Bool.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        PreviewLicenseMutation.Data.PreviewLicense.self
      ] }

      public var id: String { __data["id"] }
      public var workspaceId: String { __data["workspaceId"] }
      public var plan: GraphQLEnum<CloudNotesGraphQL.SubscriptionPlan> { __data["plan"] }
      public var recurring: GraphQLEnum<CloudNotesGraphQL.SubscriptionRecurring> { __data["recurring"] }
      public var quantity: Int { __data["quantity"] }
      public var issuedAt: CloudNotesGraphQL.DateTime { __data["issuedAt"] }
      public var expiresAt: CloudNotesGraphQL.DateTime { __data["expiresAt"] }
      public var endAt: CloudNotesGraphQL.DateTime { __data["endAt"] }
      public var entity: String { __data["entity"] }
      public var issuer: String { __data["issuer"] }
      public var valid: Bool { __data["valid"] }
    }
  }
}

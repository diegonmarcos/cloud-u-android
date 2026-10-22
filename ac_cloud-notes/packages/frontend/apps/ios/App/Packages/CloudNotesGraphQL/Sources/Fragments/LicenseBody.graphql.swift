// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public struct LicenseBody: CloudNotesGraphQL.SelectionSet, Fragment {
  public static var fragmentDefinition: StaticString {
    #"fragment licenseBody on License { __typename expiredAt installedAt quantity recurring validatedAt variant }"#
  }

  public let __data: DataDict
  public init(_dataDict: DataDict) { __data = _dataDict }

  public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.License }
  public static var __selections: [ApolloAPI.Selection] { [
    .field("__typename", String.self),
    .field("expiredAt", CloudNotesGraphQL.DateTime?.self),
    .field("installedAt", CloudNotesGraphQL.DateTime.self),
    .field("quantity", Int.self),
    .field("recurring", GraphQLEnum<CloudNotesGraphQL.SubscriptionRecurring>.self),
    .field("validatedAt", CloudNotesGraphQL.DateTime.self),
    .field("variant", GraphQLEnum<CloudNotesGraphQL.SubscriptionVariant>?.self),
  ] }
  public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
    LicenseBody.self
  ] }

  public var expiredAt: CloudNotesGraphQL.DateTime? { __data["expiredAt"] }
  public var installedAt: CloudNotesGraphQL.DateTime { __data["installedAt"] }
  public var quantity: Int { __data["quantity"] }
  public var recurring: GraphQLEnum<CloudNotesGraphQL.SubscriptionRecurring> { __data["recurring"] }
  public var validatedAt: CloudNotesGraphQL.DateTime { __data["validatedAt"] }
  public var variant: GraphQLEnum<CloudNotesGraphQL.SubscriptionVariant>? { __data["variant"] }
}

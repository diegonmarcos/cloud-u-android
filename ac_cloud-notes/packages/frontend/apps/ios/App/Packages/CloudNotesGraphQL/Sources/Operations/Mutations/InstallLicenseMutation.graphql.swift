// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class InstallLicenseMutation: GraphQLMutation {
  public static let operationName: String = "installLicense"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation installLicense($workspaceId: String!, $license: Upload!) { installLicense(workspaceId: $workspaceId, license: $license) { __typename ...licenseBody } }"#,
      fragments: [LicenseBody.self]
    ))

  public var workspaceId: String
  public var license: Upload

  public init(
    workspaceId: String,
    license: Upload
  ) {
    self.workspaceId = workspaceId
    self.license = license
  }

  public var __variables: Variables? { [
    "workspaceId": workspaceId,
    "license": license
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("installLicense", InstallLicense.self, arguments: [
        "workspaceId": .variable("workspaceId"),
        "license": .variable("license")
      ]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      InstallLicenseMutation.Data.self
    ] }

    public var installLicense: InstallLicense { __data["installLicense"] }

    /// InstallLicense
    ///
    /// Parent Type: `License`
    public struct InstallLicense: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.License }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .fragment(LicenseBody.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        InstallLicenseMutation.Data.InstallLicense.self,
        LicenseBody.self
      ] }

      public var expiredAt: CloudNotesGraphQL.DateTime? { __data["expiredAt"] }
      public var installedAt: CloudNotesGraphQL.DateTime { __data["installedAt"] }
      public var quantity: Int { __data["quantity"] }
      public var recurring: GraphQLEnum<CloudNotesGraphQL.SubscriptionRecurring> { __data["recurring"] }
      public var validatedAt: CloudNotesGraphQL.DateTime { __data["validatedAt"] }
      public var variant: GraphQLEnum<CloudNotesGraphQL.SubscriptionVariant>? { __data["variant"] }

      public struct Fragments: FragmentContainer {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public var licenseBody: LicenseBody { _toFragment() }
      }
    }
  }
}

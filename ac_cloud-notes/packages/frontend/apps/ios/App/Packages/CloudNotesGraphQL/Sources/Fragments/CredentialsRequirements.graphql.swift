// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public struct CredentialsRequirements: CloudNotesGraphQL.SelectionSet, Fragment {
  public static var fragmentDefinition: StaticString {
    #"fragment CredentialsRequirements on CredentialsRequirementType { __typename password { __typename ...PasswordLimits } }"#
  }

  public let __data: DataDict
  public init(_dataDict: DataDict) { __data = _dataDict }

  public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.CredentialsRequirementType }
  public static var __selections: [ApolloAPI.Selection] { [
    .field("__typename", String.self),
    .field("password", Password.self),
  ] }
  public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
    CredentialsRequirements.self
  ] }

  public var password: Password { __data["password"] }

  /// Password
  ///
  /// Parent Type: `PasswordLimitsType`
  public struct Password: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.PasswordLimitsType }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("__typename", String.self),
      .fragment(PasswordLimits.self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CredentialsRequirements.Password.self,
      PasswordLimits.self
    ] }

    public var minLength: Int { __data["minLength"] }
    public var maxLength: Int { __data["maxLength"] }

    public struct Fragments: FragmentContainer {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public var passwordLimits: PasswordLimits { _toFragment() }
    }
  }
}

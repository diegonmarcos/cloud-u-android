// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class AdminServerConfigQuery: GraphQLQuery {
  public static let operationName: String = "adminServerConfig"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query adminServerConfig { serverConfig { __typename version baseUrl name features type initialized credentialsRequirement { __typename ...CredentialsRequirements } availableUpgrade { __typename changelog version publishedAt url } availableUserFeatures } }"#,
      fragments: [CredentialsRequirements.self, PasswordLimits.self]
    ))

  public init() {}

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("serverConfig", ServerConfig.self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      AdminServerConfigQuery.Data.self
    ] }

    /// server config
    public var serverConfig: ServerConfig { __data["serverConfig"] }

    /// ServerConfig
    ///
    /// Parent Type: `ServerConfigType`
    public struct ServerConfig: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.ServerConfigType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("version", String.self),
        .field("baseUrl", String.self),
        .field("name", String.self),
        .field("features", [GraphQLEnum<CloudNotesGraphQL.ServerFeature>].self),
        .field("type", GraphQLEnum<CloudNotesGraphQL.ServerDeploymentType>.self),
        .field("initialized", Bool.self),
        .field("credentialsRequirement", CredentialsRequirement.self),
        .field("availableUpgrade", AvailableUpgrade?.self),
        .field("availableUserFeatures", [GraphQLEnum<CloudNotesGraphQL.FeatureType>].self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        AdminServerConfigQuery.Data.ServerConfig.self
      ] }

      /// server version
      public var version: String { __data["version"] }
      /// server base url
      public var baseUrl: String { __data["baseUrl"] }
      /// server identical name could be shown as badge on user interface
      public var name: String { __data["name"] }
      /// enabled server features
      public var features: [GraphQLEnum<CloudNotesGraphQL.ServerFeature>] { __data["features"] }
      /// server type
      public var type: GraphQLEnum<CloudNotesGraphQL.ServerDeploymentType> { __data["type"] }
      /// whether server has been initialized
      public var initialized: Bool { __data["initialized"] }
      /// credentials requirement
      public var credentialsRequirement: CredentialsRequirement { __data["credentialsRequirement"] }
      /// fetch latest available upgradable release of server
      public var availableUpgrade: AvailableUpgrade? { __data["availableUpgrade"] }
      /// Features for user that can be configured
      public var availableUserFeatures: [GraphQLEnum<CloudNotesGraphQL.FeatureType>] { __data["availableUserFeatures"] }

      /// ServerConfig.CredentialsRequirement
      ///
      /// Parent Type: `CredentialsRequirementType`
      public struct CredentialsRequirement: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.CredentialsRequirementType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .fragment(CredentialsRequirements.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminServerConfigQuery.Data.ServerConfig.CredentialsRequirement.self,
          CredentialsRequirements.self
        ] }

        public var password: Password { __data["password"] }

        public struct Fragments: FragmentContainer {
          public let __data: DataDict
          public init(_dataDict: DataDict) { __data = _dataDict }

          public var credentialsRequirements: CredentialsRequirements { _toFragment() }
        }

        public typealias Password = CredentialsRequirements.Password
      }

      /// ServerConfig.AvailableUpgrade
      ///
      /// Parent Type: `ReleaseVersionType`
      public struct AvailableUpgrade: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.ReleaseVersionType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("changelog", String.self),
          .field("version", String.self),
          .field("publishedAt", CloudNotesGraphQL.DateTime.self),
          .field("url", String.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          AdminServerConfigQuery.Data.ServerConfig.AvailableUpgrade.self
        ] }

        public var changelog: String { __data["changelog"] }
        public var version: String { __data["version"] }
        public var publishedAt: CloudNotesGraphQL.DateTime { __data["publishedAt"] }
        public var url: String { __data["url"] }
      }
    }
  }
}

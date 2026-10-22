// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class WorkspaceByokSettingsQuery: GraphQLQuery {
  public static let operationName: String = "workspaceByokSettings"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query workspaceByokSettings($id: String!, $from: DateTime!, $to: DateTime!) { workspace(id: $id) { __typename id byokSettings { __typename workspaceId entitled serverEntitled localEntitled policy { __typename enabled allowedProviders customEndpointMode privateEndpointSupported } catalog { __typename version providers { __typename provider models { __typename modelId displayName recommended capabilities { __typename input output features attachmentKinds attachmentSources } } } } profiles { __typename profileId provider name description enabled sortOrder revision definition { __typename endpoint { __typename kind url dialect } models { __typename modelId enabled capabilities { __typename input output features attachmentKinds attachmentSources } } } validation { __typename definitionFingerprint credentialGeneration connection { __typename kind testedAt errorKind } models { __typename modelId checks { __typename operation status { __typename kind testedAt errorKind } } } } } } byokUsage(from: $from, to: $to) { __typename date featureKind totalTokens } } }"#
    ))

  public var id: String
  public var from: DateTime
  public var to: DateTime

  public init(
    id: String,
    from: DateTime,
    to: DateTime
  ) {
    self.id = id
    self.from = from
    self.to = to
  }

  public var __variables: Variables? { [
    "id": id,
    "from": from,
    "to": to
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("workspace", Workspace.self, arguments: ["id": .variable("id")]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      WorkspaceByokSettingsQuery.Data.self
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
        .field("id", CloudNotesGraphQL.ID.self),
        .field("byokSettings", ByokSettings.self),
        .field("byokUsage", [ByokUsage].self, arguments: [
          "from": .variable("from"),
          "to": .variable("to")
        ]),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        WorkspaceByokSettingsQuery.Data.Workspace.self
      ] }

      public var id: CloudNotesGraphQL.ID { __data["id"] }
      public var byokSettings: ByokSettings { __data["byokSettings"] }
      public var byokUsage: [ByokUsage] { __data["byokUsage"] }

      /// Workspace.ByokSettings
      ///
      /// Parent Type: `WorkspaceByokSettingsType`
      public struct ByokSettings: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokSettingsType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("workspaceId", String.self),
          .field("entitled", Bool.self),
          .field("serverEntitled", Bool.self),
          .field("localEntitled", Bool.self),
          .field("policy", Policy.self),
          .field("catalog", Catalog.self),
          .field("profiles", [Profile].self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.self
        ] }

        public var workspaceId: String { __data["workspaceId"] }
        public var entitled: Bool { __data["entitled"] }
        public var serverEntitled: Bool { __data["serverEntitled"] }
        public var localEntitled: Bool { __data["localEntitled"] }
        public var policy: Policy { __data["policy"] }
        public var catalog: Catalog { __data["catalog"] }
        public var profiles: [Profile] { __data["profiles"] }

        /// Workspace.ByokSettings.Policy
        ///
        /// Parent Type: `WorkspaceByokPolicyType`
        public struct Policy: CloudNotesGraphQL.SelectionSet {
          public let __data: DataDict
          public init(_dataDict: DataDict) { __data = _dataDict }

          public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokPolicyType }
          public static var __selections: [ApolloAPI.Selection] { [
            .field("__typename", String.self),
            .field("enabled", Bool.self),
            .field("allowedProviders", [GraphQLEnum<CloudNotesGraphQL.ByokProvider>].self),
            .field("customEndpointMode", GraphQLEnum<CloudNotesGraphQL.ByokCustomEndpointMode>.self),
            .field("privateEndpointSupported", Bool.self),
          ] }
          public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
            WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Policy.self
          ] }

          public var enabled: Bool { __data["enabled"] }
          public var allowedProviders: [GraphQLEnum<CloudNotesGraphQL.ByokProvider>] { __data["allowedProviders"] }
          public var customEndpointMode: GraphQLEnum<CloudNotesGraphQL.ByokCustomEndpointMode> { __data["customEndpointMode"] }
          public var privateEndpointSupported: Bool { __data["privateEndpointSupported"] }
        }

        /// Workspace.ByokSettings.Catalog
        ///
        /// Parent Type: `WorkspaceByokCatalogType`
        public struct Catalog: CloudNotesGraphQL.SelectionSet {
          public let __data: DataDict
          public init(_dataDict: DataDict) { __data = _dataDict }

          public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokCatalogType }
          public static var __selections: [ApolloAPI.Selection] { [
            .field("__typename", String.self),
            .field("version", String.self),
            .field("providers", [Provider].self),
          ] }
          public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
            WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Catalog.self
          ] }

          public var version: String { __data["version"] }
          public var providers: [Provider] { __data["providers"] }

          /// Workspace.ByokSettings.Catalog.Provider
          ///
          /// Parent Type: `WorkspaceByokCatalogProviderType`
          public struct Provider: CloudNotesGraphQL.SelectionSet {
            public let __data: DataDict
            public init(_dataDict: DataDict) { __data = _dataDict }

            public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokCatalogProviderType }
            public static var __selections: [ApolloAPI.Selection] { [
              .field("__typename", String.self),
              .field("provider", GraphQLEnum<CloudNotesGraphQL.ByokProvider>.self),
              .field("models", [Model].self),
            ] }
            public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
              WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Catalog.Provider.self
            ] }

            public var provider: GraphQLEnum<CloudNotesGraphQL.ByokProvider> { __data["provider"] }
            public var models: [Model] { __data["models"] }

            /// Workspace.ByokSettings.Catalog.Provider.Model
            ///
            /// Parent Type: `WorkspaceByokCatalogModelType`
            public struct Model: CloudNotesGraphQL.SelectionSet {
              public let __data: DataDict
              public init(_dataDict: DataDict) { __data = _dataDict }

              public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokCatalogModelType }
              public static var __selections: [ApolloAPI.Selection] { [
                .field("__typename", String.self),
                .field("modelId", String.self),
                .field("displayName", String.self),
                .field("recommended", Bool.self),
                .field("capabilities", [Capability].self),
              ] }
              public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Catalog.Provider.Model.self
              ] }

              public var modelId: String { __data["modelId"] }
              public var displayName: String { __data["displayName"] }
              public var recommended: Bool { __data["recommended"] }
              public var capabilities: [Capability] { __data["capabilities"] }

              /// Workspace.ByokSettings.Catalog.Provider.Model.Capability
              ///
              /// Parent Type: `WorkspaceByokCapabilityType`
              public struct Capability: CloudNotesGraphQL.SelectionSet {
                public let __data: DataDict
                public init(_dataDict: DataDict) { __data = _dataDict }

                public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokCapabilityType }
                public static var __selections: [ApolloAPI.Selection] { [
                  .field("__typename", String.self),
                  .field("input", [GraphQLEnum<CloudNotesGraphQL.ByokModelInput>].self),
                  .field("output", [GraphQLEnum<CloudNotesGraphQL.ByokModelOutput>].self),
                  .field("features", [GraphQLEnum<CloudNotesGraphQL.ByokModelFeature>].self),
                  .field("attachmentKinds", [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentKind>].self),
                  .field("attachmentSources", [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentSource>].self),
                ] }
                public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                  WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Catalog.Provider.Model.Capability.self
                ] }

                public var input: [GraphQLEnum<CloudNotesGraphQL.ByokModelInput>] { __data["input"] }
                public var output: [GraphQLEnum<CloudNotesGraphQL.ByokModelOutput>] { __data["output"] }
                public var features: [GraphQLEnum<CloudNotesGraphQL.ByokModelFeature>] { __data["features"] }
                public var attachmentKinds: [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentKind>] { __data["attachmentKinds"] }
                public var attachmentSources: [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentSource>] { __data["attachmentSources"] }
              }
            }
          }
        }

        /// Workspace.ByokSettings.Profile
        ///
        /// Parent Type: `WorkspaceByokProfileType`
        public struct Profile: CloudNotesGraphQL.SelectionSet {
          public let __data: DataDict
          public init(_dataDict: DataDict) { __data = _dataDict }

          public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokProfileType }
          public static var __selections: [ApolloAPI.Selection] { [
            .field("__typename", String.self),
            .field("profileId", CloudNotesGraphQL.ID.self),
            .field("provider", GraphQLEnum<CloudNotesGraphQL.ByokProvider>.self),
            .field("name", String.self),
            .field("description", String?.self),
            .field("enabled", Bool.self),
            .field("sortOrder", CloudNotesGraphQL.SafeInt.self),
            .field("revision", CloudNotesGraphQL.SafeInt.self),
            .field("definition", Definition.self),
            .field("validation", Validation?.self),
          ] }
          public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
            WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.self
          ] }

          public var profileId: CloudNotesGraphQL.ID { __data["profileId"] }
          public var provider: GraphQLEnum<CloudNotesGraphQL.ByokProvider> { __data["provider"] }
          public var name: String { __data["name"] }
          public var description: String? { __data["description"] }
          public var enabled: Bool { __data["enabled"] }
          public var sortOrder: CloudNotesGraphQL.SafeInt { __data["sortOrder"] }
          public var revision: CloudNotesGraphQL.SafeInt { __data["revision"] }
          public var definition: Definition { __data["definition"] }
          public var validation: Validation? { __data["validation"] }

          /// Workspace.ByokSettings.Profile.Definition
          ///
          /// Parent Type: `WorkspaceByokProfileDefinitionType`
          public struct Definition: CloudNotesGraphQL.SelectionSet {
            public let __data: DataDict
            public init(_dataDict: DataDict) { __data = _dataDict }

            public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokProfileDefinitionType }
            public static var __selections: [ApolloAPI.Selection] { [
              .field("__typename", String.self),
              .field("endpoint", Endpoint.self),
              .field("models", [Model].self),
            ] }
            public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
              WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Definition.self
            ] }

            public var endpoint: Endpoint { __data["endpoint"] }
            public var models: [Model] { __data["models"] }

            /// Workspace.ByokSettings.Profile.Definition.Endpoint
            ///
            /// Parent Type: `WorkspaceByokEndpointType`
            public struct Endpoint: CloudNotesGraphQL.SelectionSet {
              public let __data: DataDict
              public init(_dataDict: DataDict) { __data = _dataDict }

              public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokEndpointType }
              public static var __selections: [ApolloAPI.Selection] { [
                .field("__typename", String.self),
                .field("kind", GraphQLEnum<CloudNotesGraphQL.ByokEndpointKind>.self),
                .field("url", String?.self),
                .field("dialect", GraphQLEnum<CloudNotesGraphQL.ByokOpenAiDialect>?.self),
              ] }
              public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Definition.Endpoint.self
              ] }

              public var kind: GraphQLEnum<CloudNotesGraphQL.ByokEndpointKind> { __data["kind"] }
              public var url: String? { __data["url"] }
              public var dialect: GraphQLEnum<CloudNotesGraphQL.ByokOpenAiDialect>? { __data["dialect"] }
            }

            /// Workspace.ByokSettings.Profile.Definition.Model
            ///
            /// Parent Type: `WorkspaceByokModelDeclarationType`
            public struct Model: CloudNotesGraphQL.SelectionSet {
              public let __data: DataDict
              public init(_dataDict: DataDict) { __data = _dataDict }

              public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokModelDeclarationType }
              public static var __selections: [ApolloAPI.Selection] { [
                .field("__typename", String.self),
                .field("modelId", String.self),
                .field("enabled", Bool.self),
                .field("capabilities", [Capability].self),
              ] }
              public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Definition.Model.self
              ] }

              public var modelId: String { __data["modelId"] }
              public var enabled: Bool { __data["enabled"] }
              public var capabilities: [Capability] { __data["capabilities"] }

              /// Workspace.ByokSettings.Profile.Definition.Model.Capability
              ///
              /// Parent Type: `WorkspaceByokCapabilityType`
              public struct Capability: CloudNotesGraphQL.SelectionSet {
                public let __data: DataDict
                public init(_dataDict: DataDict) { __data = _dataDict }

                public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokCapabilityType }
                public static var __selections: [ApolloAPI.Selection] { [
                  .field("__typename", String.self),
                  .field("input", [GraphQLEnum<CloudNotesGraphQL.ByokModelInput>].self),
                  .field("output", [GraphQLEnum<CloudNotesGraphQL.ByokModelOutput>].self),
                  .field("features", [GraphQLEnum<CloudNotesGraphQL.ByokModelFeature>].self),
                  .field("attachmentKinds", [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentKind>].self),
                  .field("attachmentSources", [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentSource>].self),
                ] }
                public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                  WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Definition.Model.Capability.self
                ] }

                public var input: [GraphQLEnum<CloudNotesGraphQL.ByokModelInput>] { __data["input"] }
                public var output: [GraphQLEnum<CloudNotesGraphQL.ByokModelOutput>] { __data["output"] }
                public var features: [GraphQLEnum<CloudNotesGraphQL.ByokModelFeature>] { __data["features"] }
                public var attachmentKinds: [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentKind>] { __data["attachmentKinds"] }
                public var attachmentSources: [GraphQLEnum<CloudNotesGraphQL.ByokAttachmentSource>] { __data["attachmentSources"] }
              }
            }
          }

          /// Workspace.ByokSettings.Profile.Validation
          ///
          /// Parent Type: `WorkspaceByokValidationType`
          public struct Validation: CloudNotesGraphQL.SelectionSet {
            public let __data: DataDict
            public init(_dataDict: DataDict) { __data = _dataDict }

            public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokValidationType }
            public static var __selections: [ApolloAPI.Selection] { [
              .field("__typename", String.self),
              .field("definitionFingerprint", String.self),
              .field("credentialGeneration", CloudNotesGraphQL.SafeInt.self),
              .field("connection", Connection.self),
              .field("models", [Model].self),
            ] }
            public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
              WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Validation.self
            ] }

            public var definitionFingerprint: String { __data["definitionFingerprint"] }
            public var credentialGeneration: CloudNotesGraphQL.SafeInt { __data["credentialGeneration"] }
            public var connection: Connection { __data["connection"] }
            public var models: [Model] { __data["models"] }

            /// Workspace.ByokSettings.Profile.Validation.Connection
            ///
            /// Parent Type: `WorkspaceByokProbeStatusType`
            public struct Connection: CloudNotesGraphQL.SelectionSet {
              public let __data: DataDict
              public init(_dataDict: DataDict) { __data = _dataDict }

              public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokProbeStatusType }
              public static var __selections: [ApolloAPI.Selection] { [
                .field("__typename", String.self),
                .field("kind", GraphQLEnum<CloudNotesGraphQL.ByokProbeStatusKind>.self),
                .field("testedAt", CloudNotesGraphQL.DateTime?.self),
                .field("errorKind", String?.self),
              ] }
              public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Validation.Connection.self
              ] }

              public var kind: GraphQLEnum<CloudNotesGraphQL.ByokProbeStatusKind> { __data["kind"] }
              public var testedAt: CloudNotesGraphQL.DateTime? { __data["testedAt"] }
              public var errorKind: String? { __data["errorKind"] }
            }

            /// Workspace.ByokSettings.Profile.Validation.Model
            ///
            /// Parent Type: `WorkspaceByokModelProbeType`
            public struct Model: CloudNotesGraphQL.SelectionSet {
              public let __data: DataDict
              public init(_dataDict: DataDict) { __data = _dataDict }

              public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokModelProbeType }
              public static var __selections: [ApolloAPI.Selection] { [
                .field("__typename", String.self),
                .field("modelId", String.self),
                .field("checks", [Check].self),
              ] }
              public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Validation.Model.self
              ] }

              public var modelId: String { __data["modelId"] }
              public var checks: [Check] { __data["checks"] }

              /// Workspace.ByokSettings.Profile.Validation.Model.Check
              ///
              /// Parent Type: `WorkspaceByokModelProbeCheckType`
              public struct Check: CloudNotesGraphQL.SelectionSet {
                public let __data: DataDict
                public init(_dataDict: DataDict) { __data = _dataDict }

                public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokModelProbeCheckType }
                public static var __selections: [ApolloAPI.Selection] { [
                  .field("__typename", String.self),
                  .field("operation", GraphQLEnum<CloudNotesGraphQL.ByokProbeOperation>.self),
                  .field("status", Status.self),
                ] }
                public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                  WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Validation.Model.Check.self
                ] }

                public var operation: GraphQLEnum<CloudNotesGraphQL.ByokProbeOperation> { __data["operation"] }
                public var status: Status { __data["status"] }

                /// Workspace.ByokSettings.Profile.Validation.Model.Check.Status
                ///
                /// Parent Type: `WorkspaceByokProbeStatusType`
                public struct Status: CloudNotesGraphQL.SelectionSet {
                  public let __data: DataDict
                  public init(_dataDict: DataDict) { __data = _dataDict }

                  public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokProbeStatusType }
                  public static var __selections: [ApolloAPI.Selection] { [
                    .field("__typename", String.self),
                    .field("kind", GraphQLEnum<CloudNotesGraphQL.ByokProbeStatusKind>.self),
                    .field("testedAt", CloudNotesGraphQL.DateTime?.self),
                    .field("errorKind", String?.self),
                  ] }
                  public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
                    WorkspaceByokSettingsQuery.Data.Workspace.ByokSettings.Profile.Validation.Model.Check.Status.self
                  ] }

                  public var kind: GraphQLEnum<CloudNotesGraphQL.ByokProbeStatusKind> { __data["kind"] }
                  public var testedAt: CloudNotesGraphQL.DateTime? { __data["testedAt"] }
                  public var errorKind: String? { __data["errorKind"] }
                }
              }
            }
          }
        }
      }

      /// Workspace.ByokUsage
      ///
      /// Parent Type: `WorkspaceByokUsagePointType`
      public struct ByokUsage: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.WorkspaceByokUsagePointType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("date", CloudNotesGraphQL.DateTime.self),
          .field("featureKind", String.self),
          .field("totalTokens", CloudNotesGraphQL.SafeInt.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          WorkspaceByokSettingsQuery.Data.Workspace.ByokUsage.self
        ] }

        public var date: CloudNotesGraphQL.DateTime { __data["date"] }
        public var featureKind: String { __data["featureKind"] }
        public var totalTokens: CloudNotesGraphQL.SafeInt { __data["totalTokens"] }
      }
    }
  }
}

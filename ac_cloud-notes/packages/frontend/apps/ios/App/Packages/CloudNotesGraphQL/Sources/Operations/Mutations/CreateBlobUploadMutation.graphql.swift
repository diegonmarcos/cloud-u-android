// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class CreateBlobUploadMutation: GraphQLMutation {
  public static let operationName: String = "createBlobUpload"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"mutation createBlobUpload($workspaceId: String!, $key: String!, $size: Int!, $mime: String!) { createBlobUpload(workspaceId: $workspaceId, key: $key, size: $size, mime: $mime) { __typename method blobKey alreadyUploaded uploadUrl headers expiresAt uploadId partSize uploadedParts { __typename partNumber etag } } }"#
    ))

  public var workspaceId: String
  public var key: String
  public var size: Int
  public var mime: String

  public init(
    workspaceId: String,
    key: String,
    size: Int,
    mime: String
  ) {
    self.workspaceId = workspaceId
    self.key = key
    self.size = size
    self.mime = mime
  }

  public var __variables: Variables? { [
    "workspaceId": workspaceId,
    "key": key,
    "size": size,
    "mime": mime
  ] }

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Mutation }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("createBlobUpload", CreateBlobUpload.self, arguments: [
        "workspaceId": .variable("workspaceId"),
        "key": .variable("key"),
        "size": .variable("size"),
        "mime": .variable("mime")
      ]),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CreateBlobUploadMutation.Data.self
    ] }

    public var createBlobUpload: CreateBlobUpload { __data["createBlobUpload"] }

    /// CreateBlobUpload
    ///
    /// Parent Type: `BlobUploadInit`
    public struct CreateBlobUpload: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.BlobUploadInit }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("method", GraphQLEnum<CloudNotesGraphQL.BlobUploadMethod>.self),
        .field("blobKey", String.self),
        .field("alreadyUploaded", Bool?.self),
        .field("uploadUrl", String?.self),
        .field("headers", CloudNotesGraphQL.JSONObject?.self),
        .field("expiresAt", CloudNotesGraphQL.DateTime?.self),
        .field("uploadId", String?.self),
        .field("partSize", Int?.self),
        .field("uploadedParts", [UploadedPart]?.self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        CreateBlobUploadMutation.Data.CreateBlobUpload.self
      ] }

      public var method: GraphQLEnum<CloudNotesGraphQL.BlobUploadMethod> { __data["method"] }
      public var blobKey: String { __data["blobKey"] }
      public var alreadyUploaded: Bool? { __data["alreadyUploaded"] }
      public var uploadUrl: String? { __data["uploadUrl"] }
      public var headers: CloudNotesGraphQL.JSONObject? { __data["headers"] }
      public var expiresAt: CloudNotesGraphQL.DateTime? { __data["expiresAt"] }
      public var uploadId: String? { __data["uploadId"] }
      public var partSize: Int? { __data["partSize"] }
      public var uploadedParts: [UploadedPart]? { __data["uploadedParts"] }

      /// CreateBlobUpload.UploadedPart
      ///
      /// Parent Type: `BlobUploadedPart`
      public struct UploadedPart: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.BlobUploadedPart }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("partNumber", Int.self),
          .field("etag", String.self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          CreateBlobUploadMutation.Data.CreateBlobUpload.UploadedPart.self
        ] }

        public var partNumber: Int { __data["partNumber"] }
        public var etag: String { __data["etag"] }
      }
    }
  }
}

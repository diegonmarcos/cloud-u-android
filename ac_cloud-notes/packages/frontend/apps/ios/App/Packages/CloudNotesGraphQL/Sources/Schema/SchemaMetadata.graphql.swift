// @generated
// This file was automatically generated and should not be edited.

import ApolloAPI

public protocol SelectionSet: ApolloAPI.SelectionSet & ApolloAPI.RootSelectionSet
where Schema == CloudNotesGraphQL.SchemaMetadata {}

public protocol InlineFragment: ApolloAPI.SelectionSet & ApolloAPI.InlineFragment
where Schema == CloudNotesGraphQL.SchemaMetadata {}

public protocol MutableSelectionSet: ApolloAPI.MutableRootSelectionSet
where Schema == CloudNotesGraphQL.SchemaMetadata {}

public protocol MutableInlineFragment: ApolloAPI.MutableSelectionSet & ApolloAPI.InlineFragment
where Schema == CloudNotesGraphQL.SchemaMetadata {}

public enum SchemaMetadata: ApolloAPI.SchemaMetadata {
  public static let configuration: any ApolloAPI.SchemaConfiguration.Type = SchemaConfiguration.self

  private static let objectTypeMap: [String: ApolloAPI.Object] = [
    "AdminAllSharedLink": CloudNotesGraphQL.Objects.AdminAllSharedLink,
    "AdminAllSharedLinkEdge": CloudNotesGraphQL.Objects.AdminAllSharedLinkEdge,
    "AdminDashboard": CloudNotesGraphQL.Objects.AdminDashboard,
    "AdminDashboardMinutePoint": CloudNotesGraphQL.Objects.AdminDashboardMinutePoint,
    "AdminDashboardValueDayPoint": CloudNotesGraphQL.Objects.AdminDashboardValueDayPoint,
    "AdminLicensePreview": CloudNotesGraphQL.Objects.AdminLicensePreview,
    "AdminMailDeliveryAnalytics": CloudNotesGraphQL.Objects.AdminMailDeliveryAnalytics,
    "AdminMailDeliveryPoint": CloudNotesGraphQL.Objects.AdminMailDeliveryPoint,
    "AdminMailDeliverySeries": CloudNotesGraphQL.Objects.AdminMailDeliverySeries,
    "AdminMailDeliverySummary": CloudNotesGraphQL.Objects.AdminMailDeliverySummary,
    "AdminSharedLinkTopItem": CloudNotesGraphQL.Objects.AdminSharedLinkTopItem,
    "AdminWorkspace": CloudNotesGraphQL.Objects.AdminWorkspace,
    "AdminWorkspaceMember": CloudNotesGraphQL.Objects.AdminWorkspaceMember,
    "AdminWorkspaceSharedLink": CloudNotesGraphQL.Objects.AdminWorkspaceSharedLink,
    "AggregateBucketHitsObjectType": CloudNotesGraphQL.Objects.AggregateBucketHitsObjectType,
    "AggregateBucketObjectType": CloudNotesGraphQL.Objects.AggregateBucketObjectType,
    "AggregateResultObjectType": CloudNotesGraphQL.Objects.AggregateResultObjectType,
    "AppConfigValidateResult": CloudNotesGraphQL.Objects.AppConfigValidateResult,
    "AudioSliceManifestItemType": CloudNotesGraphQL.Objects.AudioSliceManifestItemType,
    "AuthSigningKeyType": CloudNotesGraphQL.Objects.AuthSigningKeyType,
    "BlobUploadInit": CloudNotesGraphQL.Objects.BlobUploadInit,
    "BlobUploadPart": CloudNotesGraphQL.Objects.BlobUploadPart,
    "BlobUploadedPart": CloudNotesGraphQL.Objects.BlobUploadedPart,
    "CalendarAccountObjectType": CloudNotesGraphQL.Objects.CalendarAccountObjectType,
    "CalendarCalDAVProviderPresetObjectType": CloudNotesGraphQL.Objects.CalendarCalDAVProviderPresetObjectType,
    "CalendarEventObjectType": CloudNotesGraphQL.Objects.CalendarEventObjectType,
    "CalendarSubscriptionObjectType": CloudNotesGraphQL.Objects.CalendarSubscriptionObjectType,
    "ChatMessage": CloudNotesGraphQL.Objects.ChatMessage,
    "CommentChangeObjectType": CloudNotesGraphQL.Objects.CommentChangeObjectType,
    "CommentChangeObjectTypeEdge": CloudNotesGraphQL.Objects.CommentChangeObjectTypeEdge,
    "CommentObjectType": CloudNotesGraphQL.Objects.CommentObjectType,
    "CommentObjectTypeEdge": CloudNotesGraphQL.Objects.CommentObjectTypeEdge,
    "Copilot": CloudNotesGraphQL.Objects.Copilot,
    "CopilotHistories": CloudNotesGraphQL.Objects.CopilotHistories,
    "CopilotHistoriesTypeEdge": CloudNotesGraphQL.Objects.CopilotHistoriesTypeEdge,
    "CopilotQuota": CloudNotesGraphQL.Objects.CopilotQuota,
    "CopilotRouteOptions": CloudNotesGraphQL.Objects.CopilotRouteOptions,
    "CopilotRouteTarget": CloudNotesGraphQL.Objects.CopilotRouteTarget,
    "CopilotWorkspaceArtifact": CloudNotesGraphQL.Objects.CopilotWorkspaceArtifact,
    "CopilotWorkspaceArtifactTypeEdge": CloudNotesGraphQL.Objects.CopilotWorkspaceArtifactTypeEdge,
    "CopilotWorkspaceConfig": CloudNotesGraphQL.Objects.CopilotWorkspaceConfig,
    "CopilotWorkspaceIgnoredDoc": CloudNotesGraphQL.Objects.CopilotWorkspaceIgnoredDoc,
    "CopilotWorkspaceIgnoredDocTypeEdge": CloudNotesGraphQL.Objects.CopilotWorkspaceIgnoredDocTypeEdge,
    "CreateWorkspaceByokLocalLeaseResultType": CloudNotesGraphQL.Objects.CreateWorkspaceByokLocalLeaseResultType,
    "CredentialsRequirementType": CloudNotesGraphQL.Objects.CredentialsRequirementType,
    "DeleteAccount": CloudNotesGraphQL.Objects.DeleteAccount,
    "DocHistoryType": CloudNotesGraphQL.Objects.DocHistoryType,
    "DocMemberLastAccess": CloudNotesGraphQL.Objects.DocMemberLastAccess,
    "DocMemberLastAccessEdge": CloudNotesGraphQL.Objects.DocMemberLastAccessEdge,
    "DocPageAnalytics": CloudNotesGraphQL.Objects.DocPageAnalytics,
    "DocPageAnalyticsPoint": CloudNotesGraphQL.Objects.DocPageAnalyticsPoint,
    "DocPageAnalyticsSummary": CloudNotesGraphQL.Objects.DocPageAnalyticsSummary,
    "DocPermissions": CloudNotesGraphQL.Objects.DocPermissions,
    "DocType": CloudNotesGraphQL.Objects.DocType,
    "DocTypeEdge": CloudNotesGraphQL.Objects.DocTypeEdge,
    "EditorType": CloudNotesGraphQL.Objects.EditorType,
    "InvitationType": CloudNotesGraphQL.Objects.InvitationType,
    "InvitationWorkspaceType": CloudNotesGraphQL.Objects.InvitationWorkspaceType,
    "InviteLink": CloudNotesGraphQL.Objects.InviteLink,
    "InviteResult": CloudNotesGraphQL.Objects.InviteResult,
    "InvoiceType": CloudNotesGraphQL.Objects.InvoiceType,
    "License": CloudNotesGraphQL.Objects.License,
    "LimitedUserType": CloudNotesGraphQL.Objects.LimitedUserType,
    "ListedBlob": CloudNotesGraphQL.Objects.ListedBlob,
    "McpCredentialType": CloudNotesGraphQL.Objects.McpCredentialType,
    "MeetingActionItemType": CloudNotesGraphQL.Objects.MeetingActionItemType,
    "MeetingSummaryV2Type": CloudNotesGraphQL.Objects.MeetingSummaryV2Type,
    "Mutation": CloudNotesGraphQL.Objects.Mutation,
    "NormalizedTranscriptSegmentType": CloudNotesGraphQL.Objects.NormalizedTranscriptSegmentType,
    "NotificationObjectType": CloudNotesGraphQL.Objects.NotificationObjectType,
    "NotificationObjectTypeEdge": CloudNotesGraphQL.Objects.NotificationObjectTypeEdge,
    "PageInfo": CloudNotesGraphQL.Objects.PageInfo,
    "PaginatedAdminAllSharedLink": CloudNotesGraphQL.Objects.PaginatedAdminAllSharedLink,
    "PaginatedCommentChangeObjectType": CloudNotesGraphQL.Objects.PaginatedCommentChangeObjectType,
    "PaginatedCommentObjectType": CloudNotesGraphQL.Objects.PaginatedCommentObjectType,
    "PaginatedCopilotHistoriesType": CloudNotesGraphQL.Objects.PaginatedCopilotHistoriesType,
    "PaginatedCopilotWorkspaceArtifactType": CloudNotesGraphQL.Objects.PaginatedCopilotWorkspaceArtifactType,
    "PaginatedDocMemberLastAccess": CloudNotesGraphQL.Objects.PaginatedDocMemberLastAccess,
    "PaginatedDocType": CloudNotesGraphQL.Objects.PaginatedDocType,
    "PaginatedIgnoredDocsType": CloudNotesGraphQL.Objects.PaginatedIgnoredDocsType,
    "PaginatedNotificationObjectType": CloudNotesGraphQL.Objects.PaginatedNotificationObjectType,
    "PasswordLimitsType": CloudNotesGraphQL.Objects.PasswordLimitsType,
    "PublicUserType": CloudNotesGraphQL.Objects.PublicUserType,
    "Query": CloudNotesGraphQL.Objects.Query,
    "ReleaseVersionType": CloudNotesGraphQL.Objects.ReleaseVersionType,
    "RemoveAvatar": CloudNotesGraphQL.Objects.RemoveAvatar,
    "ReplyObjectType": CloudNotesGraphQL.Objects.ReplyObjectType,
    "RevealedMcpCredentialType": CloudNotesGraphQL.Objects.RevealedMcpCredentialType,
    "SearchDocObjectType": CloudNotesGraphQL.Objects.SearchDocObjectType,
    "SearchNodeObjectType": CloudNotesGraphQL.Objects.SearchNodeObjectType,
    "SearchResultObjectType": CloudNotesGraphQL.Objects.SearchResultObjectType,
    "SearchResultPagination": CloudNotesGraphQL.Objects.SearchResultPagination,
    "ServerConfigType": CloudNotesGraphQL.Objects.ServerConfigType,
    "StreamObject": CloudNotesGraphQL.Objects.StreamObject,
    "SubscriptionPrice": CloudNotesGraphQL.Objects.SubscriptionPrice,
    "SubscriptionType": CloudNotesGraphQL.Objects.SubscriptionType,
    "TimeWindow": CloudNotesGraphQL.Objects.TimeWindow,
    "TranscriptionItemType": CloudNotesGraphQL.Objects.TranscriptionItemType,
    "TranscriptionQualityType": CloudNotesGraphQL.Objects.TranscriptionQualityType,
    "TranscriptionResultType": CloudNotesGraphQL.Objects.TranscriptionResultType,
    "TranscriptionSourceAudioType": CloudNotesGraphQL.Objects.TranscriptionSourceAudioType,
    "UserImportFailedType": CloudNotesGraphQL.Objects.UserImportFailedType,
    "UserQuotaHumanReadableType": CloudNotesGraphQL.Objects.UserQuotaHumanReadableType,
    "UserQuotaType": CloudNotesGraphQL.Objects.UserQuotaType,
    "UserQuotaUsageType": CloudNotesGraphQL.Objects.UserQuotaUsageType,
    "UserSettingsType": CloudNotesGraphQL.Objects.UserSettingsType,
    "UserType": CloudNotesGraphQL.Objects.UserType,
    "WorkspaceByokCapabilityType": CloudNotesGraphQL.Objects.WorkspaceByokCapabilityType,
    "WorkspaceByokCatalogModelType": CloudNotesGraphQL.Objects.WorkspaceByokCatalogModelType,
    "WorkspaceByokCatalogProviderType": CloudNotesGraphQL.Objects.WorkspaceByokCatalogProviderType,
    "WorkspaceByokCatalogType": CloudNotesGraphQL.Objects.WorkspaceByokCatalogType,
    "WorkspaceByokEndpointType": CloudNotesGraphQL.Objects.WorkspaceByokEndpointType,
    "WorkspaceByokModelDeclarationType": CloudNotesGraphQL.Objects.WorkspaceByokModelDeclarationType,
    "WorkspaceByokModelProbeCheckType": CloudNotesGraphQL.Objects.WorkspaceByokModelProbeCheckType,
    "WorkspaceByokModelProbeType": CloudNotesGraphQL.Objects.WorkspaceByokModelProbeType,
    "WorkspaceByokPolicyType": CloudNotesGraphQL.Objects.WorkspaceByokPolicyType,
    "WorkspaceByokProbeResultType": CloudNotesGraphQL.Objects.WorkspaceByokProbeResultType,
    "WorkspaceByokProbeStatusType": CloudNotesGraphQL.Objects.WorkspaceByokProbeStatusType,
    "WorkspaceByokProfileDefinitionType": CloudNotesGraphQL.Objects.WorkspaceByokProfileDefinitionType,
    "WorkspaceByokProfileType": CloudNotesGraphQL.Objects.WorkspaceByokProfileType,
    "WorkspaceByokSettingsType": CloudNotesGraphQL.Objects.WorkspaceByokSettingsType,
    "WorkspaceByokUsagePointType": CloudNotesGraphQL.Objects.WorkspaceByokUsagePointType,
    "WorkspaceByokValidationType": CloudNotesGraphQL.Objects.WorkspaceByokValidationType,
    "WorkspaceCalendarItemObjectType": CloudNotesGraphQL.Objects.WorkspaceCalendarItemObjectType,
    "WorkspaceCalendarObjectType": CloudNotesGraphQL.Objects.WorkspaceCalendarObjectType,
    "WorkspaceDocMeta": CloudNotesGraphQL.Objects.WorkspaceDocMeta,
    "WorkspacePermissions": CloudNotesGraphQL.Objects.WorkspacePermissions,
    "WorkspaceQuotaHumanReadableType": CloudNotesGraphQL.Objects.WorkspaceQuotaHumanReadableType,
    "WorkspaceQuotaType": CloudNotesGraphQL.Objects.WorkspaceQuotaType,
    "WorkspaceRolePermissions": CloudNotesGraphQL.Objects.WorkspaceRolePermissions,
    "WorkspaceType": CloudNotesGraphQL.Objects.WorkspaceType,
    "WorkspaceUserType": CloudNotesGraphQL.Objects.WorkspaceUserType
  ]

  public static func objectType(forTypename typename: String) -> ApolloAPI.Object? {
    objectTypeMap[typename]
  }
}

public enum Objects {}
public enum Interfaces {}
public enum Unions {}

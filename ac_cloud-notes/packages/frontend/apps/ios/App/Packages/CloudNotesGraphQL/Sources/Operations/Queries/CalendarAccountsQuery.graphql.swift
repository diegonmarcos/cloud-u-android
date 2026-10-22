// @generated
// This file was automatically generated and should not be edited.

@_exported import ApolloAPI

public class CalendarAccountsQuery: GraphQLQuery {
  public static let operationName: String = "calendarAccounts"
  public static let operationDocument: ApolloAPI.OperationDocument = .init(
    definition: .init(
      #"query calendarAccounts { currentUser { __typename calendarAccounts { __typename id provider providerAccountId displayName email status lastError refreshIntervalMinutes calendarsCount createdAt updatedAt calendars { __typename id accountId provider externalCalendarId displayName timezone color enabled lastSyncAt } } } }"#
    ))

  public init() {}

  public struct Data: CloudNotesGraphQL.SelectionSet {
    public let __data: DataDict
    public init(_dataDict: DataDict) { __data = _dataDict }

    public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.Query }
    public static var __selections: [ApolloAPI.Selection] { [
      .field("currentUser", CurrentUser?.self),
    ] }
    public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
      CalendarAccountsQuery.Data.self
    ] }

    /// Get current user
    public var currentUser: CurrentUser? { __data["currentUser"] }

    /// CurrentUser
    ///
    /// Parent Type: `UserType`
    public struct CurrentUser: CloudNotesGraphQL.SelectionSet {
      public let __data: DataDict
      public init(_dataDict: DataDict) { __data = _dataDict }

      public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.UserType }
      public static var __selections: [ApolloAPI.Selection] { [
        .field("__typename", String.self),
        .field("calendarAccounts", [CalendarAccount].self),
      ] }
      public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
        CalendarAccountsQuery.Data.CurrentUser.self
      ] }

      public var calendarAccounts: [CalendarAccount] { __data["calendarAccounts"] }

      /// CurrentUser.CalendarAccount
      ///
      /// Parent Type: `CalendarAccountObjectType`
      public struct CalendarAccount: CloudNotesGraphQL.SelectionSet {
        public let __data: DataDict
        public init(_dataDict: DataDict) { __data = _dataDict }

        public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.CalendarAccountObjectType }
        public static var __selections: [ApolloAPI.Selection] { [
          .field("__typename", String.self),
          .field("id", String.self),
          .field("provider", GraphQLEnum<CloudNotesGraphQL.CalendarProviderType>.self),
          .field("providerAccountId", String.self),
          .field("displayName", String?.self),
          .field("email", String?.self),
          .field("status", String.self),
          .field("lastError", String?.self),
          .field("refreshIntervalMinutes", Int.self),
          .field("calendarsCount", Int.self),
          .field("createdAt", CloudNotesGraphQL.DateTime.self),
          .field("updatedAt", CloudNotesGraphQL.DateTime.self),
          .field("calendars", [Calendar].self),
        ] }
        public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
          CalendarAccountsQuery.Data.CurrentUser.CalendarAccount.self
        ] }

        public var id: String { __data["id"] }
        public var provider: GraphQLEnum<CloudNotesGraphQL.CalendarProviderType> { __data["provider"] }
        public var providerAccountId: String { __data["providerAccountId"] }
        public var displayName: String? { __data["displayName"] }
        public var email: String? { __data["email"] }
        public var status: String { __data["status"] }
        public var lastError: String? { __data["lastError"] }
        public var refreshIntervalMinutes: Int { __data["refreshIntervalMinutes"] }
        public var calendarsCount: Int { __data["calendarsCount"] }
        public var createdAt: CloudNotesGraphQL.DateTime { __data["createdAt"] }
        public var updatedAt: CloudNotesGraphQL.DateTime { __data["updatedAt"] }
        public var calendars: [Calendar] { __data["calendars"] }

        /// CurrentUser.CalendarAccount.Calendar
        ///
        /// Parent Type: `CalendarSubscriptionObjectType`
        public struct Calendar: CloudNotesGraphQL.SelectionSet {
          public let __data: DataDict
          public init(_dataDict: DataDict) { __data = _dataDict }

          public static var __parentType: any ApolloAPI.ParentType { CloudNotesGraphQL.Objects.CalendarSubscriptionObjectType }
          public static var __selections: [ApolloAPI.Selection] { [
            .field("__typename", String.self),
            .field("id", String.self),
            .field("accountId", String.self),
            .field("provider", GraphQLEnum<CloudNotesGraphQL.CalendarProviderType>.self),
            .field("externalCalendarId", String.self),
            .field("displayName", String?.self),
            .field("timezone", String?.self),
            .field("color", String?.self),
            .field("enabled", Bool.self),
            .field("lastSyncAt", CloudNotesGraphQL.DateTime?.self),
          ] }
          public static var __fulfilledFragments: [any ApolloAPI.SelectionSet.Type] { [
            CalendarAccountsQuery.Data.CurrentUser.CalendarAccount.Calendar.self
          ] }

          public var id: String { __data["id"] }
          public var accountId: String { __data["accountId"] }
          public var provider: GraphQLEnum<CloudNotesGraphQL.CalendarProviderType> { __data["provider"] }
          public var externalCalendarId: String { __data["externalCalendarId"] }
          public var displayName: String? { __data["displayName"] }
          public var timezone: String? { __data["timezone"] }
          public var color: String? { __data["color"] }
          public var enabled: Bool { __data["enabled"] }
          public var lastSyncAt: CloudNotesGraphQL.DateTime? { __data["lastSyncAt"] }
        }
      }
    }
  }
}

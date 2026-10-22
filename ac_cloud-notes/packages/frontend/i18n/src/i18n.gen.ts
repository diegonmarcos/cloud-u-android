// @ts-nocheck
/* oxlint-disable */
import { createElement, useMemo, type ComponentType, type JSX } from "react";
import { useTranslation, Trans, type TransProps } from "react-i18next";
type TypedTransProps<Value, Components, Context extends string | undefined = undefined> = Omit<TransProps<string, never, never, Context>, "values" | "ns" | "i18nKey"> & ({} extends Value ? {} : {
    values: Value;
}) & {
    components: Components;
};
function createProxy(initValue: (key: string) => any) {
    function define(key: string) {
        const value = initValue(key);
        Object.defineProperty(container, key, { value, configurable: true });
        return value;
    }
    const container = {
        __proto__: new Proxy({ __proto__: null }, {
            get(_, key) {
                if (typeof key === "symbol")
                    return undefined;
                return define(key);
            },
        }),
    };
    return new Proxy(container, {
        getPrototypeOf: () => null,
        setPrototypeOf: (_, v) => v === null,
        getOwnPropertyDescriptor: (_, key) => {
            if (typeof key === "symbol")
                return undefined;
            if (!(key in container))
                define(key);
            return Object.getOwnPropertyDescriptor(container, key);
        },
    });
}
export function useCloudNotesI18N(): {
    /**
      * `Back to my Content`
      */
    ["404.back"](): string;
    /**
      * `Sorry, you do not have access or this content does not exist...`
      */
    ["404.hint"](): string;
    /**
      * `Sign in to another account`
      */
    ["404.signOut"](): string;
    /**
      * `Cloud Notes`
      */
    ["Cloud Notes"](): string;
    /**
      * `All docs`
      */
    ["All pages"](): string;
    /**
      * `App version`
      */
    ["App Version"](): string;
    /**
      * `Available offline`
      */
    ["Available Offline"](): string;
    /**
      * `Bold`
      */
    Bold(): string;
    /**
      * `Cancel`
      */
    Cancel(): string;
    /**
      * `Click to replace photo`
      */
    ["Click to replace photo"](): string;
    /**
      * `Collections`
      */
    Collections(): string;
    /**
      * `Complete`
      */
    Complete(): string;
    /**
      * `Confirm`
      */
    Confirm(): string;
    /**
      * `Continue`
      */
    Continue(): string;
    /**
      * `Convert to `
      */
    ["Convert to "](): string;
    /**
      * `Copied link to clipboard`
      */
    ["Copied link to clipboard"](): string;
    /**
      * `Copied to clipboard`
      */
    ["Copied to clipboard"](): string;
    /**
      * `Copy`
      */
    Copy(): string;
    /**
      * `Create`
      */
    Create(): string;
    /**
      * `Created`
      */
    Created(): string;
    /**
      * `Customise`
      */
    Customize(): string;
    /**
      * `Colors`
      */
    Colors(): string;
    /**
      * `Database file already loaded`
      */
    DB_FILE_ALREADY_LOADED(): string;
    /**
      * `Invalid database file`
      */
    DB_FILE_INVALID(): string;
    /**
      * `Database file migration failed`
      */
    DB_FILE_MIGRATION_FAILED(): string;
    /**
      * `Database file path invalid`
      */
    DB_FILE_PATH_INVALID(): string;
    /**
      * `Date`
      */
    Date(): string;
    /**
      * `Delete`
      */
    Delete(): string;
    /**
      * `Deleted`
      */
    Deleted(): string;
    /**
      * `Disable`
      */
    Disable(): string;
    /**
      * `Disable public sharing`
      */
    ["Disable Public Sharing"](): string;
    /**
      * `Disable snapshot`
      */
    ["Disable Snapshot"](): string;
    /**
      * `Divider`
      */
    Divider(): string;
    /**
      * `Edgeless`
      */
    Edgeless(): string;
    /**
      * `Edit`
      */
    Edit(): string;
    /**
      * `Editor version`
      */
    ["Editor Version"](): string;
    /**
      * `Enable`
      */
    Enable(): string;
    /**
      * `Enable Cloud Notes Sync`
      */
    ["Enable Cloud Notes"](): string;
    /**
      * `Your workspace will be synced and backed up with Cloud Notes Sync.`
      */
    ["Enable Cloud Notes Description"](): string;
    /**
      * `Some features need Cloud Notes Sync. Web workspaces are saved in this browser and may be removed automatically when disk space is low. Enable Cloud Notes Sync to keep this workspace safer and available across devices.`
      */
    ["Enable cloud hint"](): string;
    /**
      * `Full Backup`
      */
    ["Full Backup"](): string;
    /**
      * `Export a complete workspace backup`
      */
    ["Full Backup Description"](): string;
    /**
      * `Sync all cloud data and export a complete workspace backup`
      */
    ["Full Backup Hint"](): string;
    /**
      * `Quick Export`
      */
    ["Quick Export"](): string;
    /**
      * `Skip cloud synchronization and quickly export current data(some attachments or docs may be missing)`
      */
    ["Quick Export Description"](): string;
    /**
      * `Export failed`
      */
    ["Export failed"](): string;
    /**
      * `Export success`
      */
    ["Export success"](): string;
    /**
      * `Export to HTML`
      */
    ["Export to HTML"](): string;
    /**
      * `Export to Markdown`
      */
    ["Export to Markdown"](): string;
    /**
      * `Export to PNG`
      */
    ["Export to PNG"](): string;
    /**
      * `File already exists`
      */
    FILE_ALREADY_EXISTS(): string;
    /**
      * `Favourite`
      */
    Favorite(): string;
    /**
      * `Favourited`
      */
    Favorited(): string;
    /**
      * `Favourites`
      */
    Favorites(): string;
    /**
      * `Feedback`
      */
    Feedback(): string;
    /**
      * `Found 0 results`
      */
    ["Find 0 result"](): string;
    /**
      * `Go back`
      */
    ["Go Back"](): string;
    /**
      * `Go forward`
      */
    ["Go Forward"](): string;
    /**
      * `Got it`
      */
    ["Got it"](): string;
    /**
      * `Heading {{number}}`
      */
    Heading(options: {
        readonly number: string;
    }): string;
    /**
      * `Image`
      */
    Image(): string;
    /**
      * `Import`
      */
    Import(): string;
    /**
      * `Info`
      */
    Info(): string;
    /**
      * `Invitation sent`
      */
    ["Invitation sent"](): string;
    /**
      * `Invited members have been notified with email to join this Workspace.`
      */
    ["Invitation sent hint"](): string;
    /**
      * `Invite`
      */
    Invite(): string;
    /**
      * `Invite members`
      */
    ["Invite Members"](): string;
    /**
      * `Invited members will collaborate with you in current workspace`
      */
    ["Invite Members Message"](): string;
    /**
      * `Insufficient team seat`
      */
    ["insufficient-team-seat"](): string;
    /**
      * `Joined workspace`
      */
    ["Joined Workspace"](): string;
    /**
      * `Leave`
      */
    Leave(): string;
    /**
      * `Hyperlink (with selected text)`
      */
    Link(): string;
    /**
      * `Loading...`
      */
    Loading(): string;
    /**
      * `Local`
      */
    Local(): string;
    /**
      * `Member`
      */
    Member(): string;
    /**
      * `Members`
      */
    Members(): string;
    /**
      * `Manage members here, invite new member by email.`
      */
    ["Members hint"](): string;
    /**
      * `New doc`
      */
    ["New Page"](): string;
    /**
      * `Owner`
      */
    Owner(): string;
    /**
      * `Page`
      */
    Page(): string;
    /**
      * `Pen`
      */
    Pen(): string;
    /**
      * `Pending`
      */
    Pending(): string;
    /**
      * `Collaborator`
      */
    Collaborator(): string;
    /**
      * `Under Review`
      */
    ["Under-Review"](): string;
    /**
      * `Need More Seats`
      */
    ["Need-More-Seats"](): string;
    /**
      * `Allocating Seat`
      */
    ["Allocating Seat"](): string;
    /**
      * `Admin`
      */
    Admin(): string;
    /**
      * `Publish`
      */
    Publish(): string;
    /**
      * `Published to web`
      */
    ["Published to Web"](): string;
    /**
      * `Quick search`
      */
    ["Quick Search"](): string;
    /**
      * `Search`
      */
    ["Quick search"](): string;
    /**
      * `Recent`
      */
    Recent(): string;
    /**
      * `Remove from workspace`
      */
    ["Remove from workspace"](): string;
    /**
      * `Remove photo`
      */
    ["Remove photo"](): string;
    /**
      * `Remove special filter`
      */
    ["Remove special filter"](): string;
    /**
      * `Removed successfully`
      */
    ["Removed successfully"](): string;
    /**
      * `Rename`
      */
    Rename(): string;
    /**
      * `Retry`
      */
    Retry(): string;
    /**
      * `Save`
      */
    Save(): string;
    /**
      * `Select`
      */
    Select(): string;
    /**
      * `Sign in to Cloud Notes`
      */
    ["Sign in"](): string;
    /**
      * `Sign in and enable`
      */
    ["Sign in and Enable"](): string;
    /**
      * `Sign out of Cloud Notes`
      */
    ["Sign out"](): string;
    /**
      * `Snapshot`
      */
    Snapshot(): string;
    /**
      * `Storage`
      */
    Storage(): string;
    /**
      * `Storage and export`
      */
    ["Storage and Export"](): string;
    /**
      * `Successfully deleted`
      */
    ["Successfully deleted"](): string;
    /**
      * `Successfully joined!`
      */
    ["Successfully joined!"](): string;
    /**
      * `Switch`
      */
    Switch(): string;
    /**
      * `Switch view`
      */
    switchView(): string;
    /**
      * `Sync`
      */
    Sync(): string;
    /**
      * `Tags`
      */
    Tags(): string;
    /**
      * `Text`
      */
    Text(): string;
    /**
      * `Theme`
      */
    Theme(): string;
    /**
      * `Title`
      */
    Title(): string;
    /**
      * `Trash`
      */
    Trash(): string;
    /**
      * `Unknown error`
      */
    UNKNOWN_ERROR(): string;
    /**
      * `Undo`
      */
    Undo(): string;
    /**
      * `Unpin`
      */
    Unpin(): string;
    /**
      * `Untitled`
      */
    Untitled(): string;
    /**
      * `Update workspace name success`
      */
    ["Update workspace name success"](): string;
    /**
      * `Updated`
      */
    Updated(): string;
    /**
      * `Upload`
      */
    Upload(): string;
    /**
      * `Users`
      */
    Users(): string;
    /**
      * `Version`
      */
    Version(): string;
    /**
      * `Visit workspace`
      */
    ["Visit Workspace"](): string;
    /**
      * `Workspace name`
      */
    ["Workspace Name"](): string;
    /**
      * `Workspace Owner`
      */
    ["Workspace Owner"](): string;
    /**
      * `Workspace profile`
      */
    ["Workspace Profile"](): string;
    /**
      * `Workspace settings`
      */
    ["Workspace Settings"](): string;
    /**
      * `{{name}}'s settings`
      */
    ["Workspace Settings with name"](options: {
        readonly name: string;
    }): string;
    /**
      * `{{name}} is saved locally`
      */
    ["Workspace saved locally"](options: {
        readonly name: string;
    }): string;
    /**
      * `Zoom in`
      */
    ["Zoom in"](): string;
    /**
      * `Zoom out`
      */
    ["Zoom out"](): string;
    /**
      * `Unknown User`
      */
    ["Unknown User"](): string;
    /**
      * `Deleted User`
      */
    ["Deleted User"](): string;
    /**
      * `all`
      */
    all(): string;
    /**
      * `current`
      */
    current(): string;
    /**
      * `created at {{time}}`
      */
    ["created at"](options: {
        readonly time: string;
    }): string;
    /**
      * `last updated at {{time}}`
      */
    ["updated at"](options: {
        readonly time: string;
    }): string;
    /**
      * `Automatically check for new updates periodically.`
      */
    ["com.cloud-notes.aboutCloudNotes.autoCheckUpdate.description"](): string;
    /**
      * `Check for updates automatically`
      */
    ["com.cloud-notes.aboutCloudNotes.autoCheckUpdate.title"](): string;
    /**
      * `Automatically download updates (to this device).`
      */
    ["com.cloud-notes.aboutCloudNotes.autoDownloadUpdate.description"](): string;
    /**
      * `Download updates automatically`
      */
    ["com.cloud-notes.aboutCloudNotes.autoDownloadUpdate.title"](): string;
    /**
      * `View the Cloud Notes Changelog.`
      */
    ["com.cloud-notes.aboutCloudNotes.changelog.description"](): string;
    /**
      * `Discover what's new`
      */
    ["com.cloud-notes.aboutCloudNotes.changelog.title"](): string;
    /**
      * `Check for update`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.button.check"](): string;
    /**
      * `Download update`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.button.download"](): string;
    /**
      * `Restart to update`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.button.restart"](): string;
    /**
      * `Retry`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.button.retry"](): string;
    /**
      * `New version is ready`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.description"](): string;
    /**
      * `Manually check for updates.`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.subtitle.check"](): string;
    /**
      * `Checking for updates...`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.subtitle.checking"](): string;
    /**
      * `Downloading the latest version...`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.subtitle.downloading"](): string;
    /**
      * `Unable to connect to the update server.`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.subtitle.error"](): string;
    /**
      * `You've got the latest version of Cloud Notes.`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.subtitle.latest"](): string;
    /**
      * `Restart to apply update.`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.subtitle.restart"](): string;
    /**
      * `New update available ({{version}})`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.subtitle.update-available"](options: {
        readonly version: string;
    }): string;
    /**
      * `Check for updates`
      */
    ["com.cloud-notes.aboutCloudNotes.checkUpdate.title"](): string;
    /**
      * `Communities`
      */
    ["com.cloud-notes.aboutCloudNotes.community.title"](): string;
    /**
      * `Cloud Notes community`
      */
    ["com.cloud-notes.aboutCloudNotes.contact.community"](): string;
    /**
      * `Contact us`
      */
    ["com.cloud-notes.aboutCloudNotes.contact.title"](): string;
    /**
      * `Official website`
      */
    ["com.cloud-notes.aboutCloudNotes.contact.website"](): string;
    /**
      * `Privacy`
      */
    ["com.cloud-notes.aboutCloudNotes.legal.privacy"](): string;
    /**
      * `Legal Info`
      */
    ["com.cloud-notes.aboutCloudNotes.legal.title"](): string;
    /**
      * `Terms of use`
      */
    ["com.cloud-notes.aboutCloudNotes.legal.tos"](): string;
    /**
      * `Information about Cloud Notes`
      */
    ["com.cloud-notes.aboutCloudNotes.subtitle"](): string;
    /**
      * `About Cloud Notes`
      */
    ["com.cloud-notes.aboutCloudNotes.title"](): string;
    /**
      * `App version`
      */
    ["com.cloud-notes.aboutCloudNotes.version.app"](): string;
    /**
      * `Editor version`
      */
    ["com.cloud-notes.aboutCloudNotes.version.editor.title"](): string;
    /**
      * `Version`
      */
    ["com.cloud-notes.aboutCloudNotes.version.title"](): string;
    /**
      * `Get started`
      */
    ["com.cloud-notes.ai-onboarding.edgeless.get-started"](): string;
    /**
      * `Lets you think bigger, create faster, work smarter and save time for every project.`
      */
    ["com.cloud-notes.ai-onboarding.edgeless.message"](): string;
    /**
      * `Upgrade to unlimited usage`
      */
    ["com.cloud-notes.ai-onboarding.edgeless.purchase"](): string;
    /**
      * `Right-clicking to select content AI`
      */
    ["com.cloud-notes.ai-onboarding.edgeless.title"](): string;
    /**
      * `Lets you think bigger, create faster, work smarter and save time for every project.`
      */
    ["com.cloud-notes.ai-onboarding.general.1.description"](): string;
    /**
      * `Meet Cloud Notes AI`
      */
    ["com.cloud-notes.ai-onboarding.general.1.title"](): string;
    /**
      * `Answer questions, draft docs, visualize ideas - Cloud Notes AI can save you time at every possible step. Powered by GPT's most powerful model.`
      */
    ["com.cloud-notes.ai-onboarding.general.2.description"](): string;
    /**
      * `Chat with Cloud Notes AI`
      */
    ["com.cloud-notes.ai-onboarding.general.2.title"](): string;
    /**
      * `Get insightful answer to any question, instantly.`
      */
    ["com.cloud-notes.ai-onboarding.general.3.description"](): string;
    /**
      * `Edit inline with Cloud Notes AI`
      */
    ["com.cloud-notes.ai-onboarding.general.3.title"](): string;
    /**
      * `Expand thinking. Untangle complexity. Breakdown and visualise your content with crafted mindmap and presentable slides with one click.`
      */
    ["com.cloud-notes.ai-onboarding.general.4.description"](): string;
    /**
      * `Make mind-map and presents with AI`
      */
    ["com.cloud-notes.ai-onboarding.general.4.title"](): string;
    /**
      * `Cloud Notes AI is ready`
      */
    ["com.cloud-notes.ai-onboarding.general.5.title"](): string;
    /**
      * `Get started`
      */
    ["com.cloud-notes.ai-onboarding.general.get-started"](): string;
    /**
      * `Next`
      */
    ["com.cloud-notes.ai-onboarding.general.next"](): string;
    /**
      * `Back`
      */
    ["com.cloud-notes.ai-onboarding.general.prev"](): string;
    /**
      * `Get unlimited usage`
      */
    ["com.cloud-notes.ai-onboarding.general.purchase"](): string;
    /**
      * `Remind me later`
      */
    ["com.cloud-notes.ai-onboarding.general.skip"](): string;
    /**
      * `Try for free`
      */
    ["com.cloud-notes.ai-onboarding.general.try-for-free"](): string;
    /**
      * `Dismiss`
      */
    ["com.cloud-notes.ai-onboarding.local.action-dismiss"](): string;
    /**
      * `Get started`
      */
    ["com.cloud-notes.ai-onboarding.local.action-get-started"](): string;
    /**
      * `Learn more`
      */
    ["com.cloud-notes.ai-onboarding.local.action-learn-more"](): string;
    /**
      * `Lets you think bigger, create faster, work smarter and save time for every project.`
      */
    ["com.cloud-notes.ai-onboarding.local.message"](): string;
    /**
      * `Meet Cloud Notes AI`
      */
    ["com.cloud-notes.ai-onboarding.local.title"](): string;
    /**
      * `New`
      */
    ["com.cloud-notes.ai-scroll-tip.tag"](): string;
    /**
      * `Meet Cloud Notes AI`
      */
    ["com.cloud-notes.ai-scroll-tip.title"](): string;
    /**
      * `View`
      */
    ["com.cloud-notes.ai-scroll-tip.view"](): string;
    /**
      * `Please switch to edgeless mode`
      */
    ["com.cloud-notes.ai.action.edgeless-only.dialog-title"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.ai.login-required.dialog-cancel"](): string;
    /**
      * `Sign in`
      */
    ["com.cloud-notes.ai.login-required.dialog-confirm"](): string;
    /**
      * `To use Cloud Notes AI, please sign in to your Cloud Notes account.`
      */
    ["com.cloud-notes.ai.login-required.dialog-content"](): string;
    /**
      * `Sign in to continue`
      */
    ["com.cloud-notes.ai.login-required.dialog-title"](): string;
    /**
      * `Failed to insert template, please try again.`
      */
    ["com.cloud-notes.ai.template-insert.failed"](): string;
    /**
      * `Cloud Notes AI`
      */
    ["com.cloud-notes.ai.chat-panel.title"](): string;
    /**
      * `Cloud Notes AI is loading history...`
      */
    ["com.cloud-notes.ai.chat-panel.loading-history"](): string;
    /**
      * `Embedding {{done}}/{{total}}`
      */
    ["com.cloud-notes.ai.chat-panel.embedding-progress"](options: Readonly<{
        done: string;
        total: string;
    }>): string;
    /**
      * `Local workspaces don't support attachments or references.`
      */
    ["com.cloud-notes.ai.chat-panel.local-workspace-context-unavailable"](): string;
    /**
      * `Reading canvas`
      */
    ["com.cloud-notes.ai.chat-panel.tool.canvas.reading"](): string;
    /**
      * `Read canvas of "{{title}}"`
      */
    ["com.cloud-notes.ai.chat-panel.tool.canvas.read"](options: {
        readonly title: string;
    }): string;
    /**
      * `Read canvas`
      */
    ["com.cloud-notes.ai.chat-panel.tool.canvas.read-untitled"](): string;
    /**
      * `Canvas read failed`
      */
    ["com.cloud-notes.ai.chat-panel.tool.canvas.failed"](): string;
    /**
      * `Canvas content`
      */
    ["com.cloud-notes.ai.chat-panel.tool.canvas.content"](): string;
    /**
      * `Current document`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.current-document"](): string;
    /**
      * `Checking editor state`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.state-checking"](): string;
    /**
      * `Checked editor state`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.state-checked"](): string;
    /**
      * `Reading selection`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.selection-reading"](): string;
    /**
      * `Read selection`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.selection-read"](): string;
    /**
      * `Reading editor content`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.content-reading"](): string;
    /**
      * `Read editor content`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.content-read"](): string;
    /**
      * `Reading outline of "{{title}}"`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.outline-reading"](options: {
        readonly title: string;
    }): string;
    /**
      * `Read outline of "{{title}}"`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.outline-read"](options: {
        readonly title: string;
    }): string;
    /**
      * `Reading canvas of "{{title}}"`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.canvas-reading"](options: {
        readonly title: string;
    }): string;
    /**
      * `Read canvas of "{{title}}"`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.canvas-read"](options: {
        readonly title: string;
    }): string;
    /**
      * `This view is not available in the current editor mode`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.view-unavailable"](): string;
    /**
      * `Live editor read failed`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.failed"](): string;
    /**
      * `{{mode}} mode`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.mode"](options: {
        readonly mode: string;
    }): string;
    /**
      * `{{count}} blocks`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.blocks"](options: {
        readonly count: string;
    }): string;
    /**
      * `{{count}} elements`
      */
    ["com.cloud-notes.ai.chat-panel.tool.live.elements"](options: {
        readonly count: string;
    }): string;
    /**
      * `Document`
      */
    ["com.cloud-notes.ai.chat-panel.scope.document"](): string;
    /**
      * `Tag`
      */
    ["com.cloud-notes.ai.chat-panel.scope.tag"](): string;
    /**
      * `Collection`
      */
    ["com.cloud-notes.ai.chat-panel.scope.collection"](): string;
    /**
      * `Favorites`
      */
    ["com.cloud-notes.ai.chat-panel.scope.favorite"](): string;
    /**
      * `Attachment`
      */
    ["com.cloud-notes.ai.chat-panel.scope.artifact"](): string;
    /**
      * `{{count}} sources`
      */
    ["com.cloud-notes.ai.chat-panel.scope.sources"](options: {
        readonly count: string;
    }): string;
    /**
      * `Your selected sources are still processing. Try again shortly.`
      */
    ["com.cloud-notes.ai.error.selectedSourcesProcessing"](): string;
    /**
      * `Some selected sources could not be processed. Try again; if the problem continues, remove them and send a new message.`
      */
    ["com.cloud-notes.ai.error.selectedSourcesFailed"](): string;
    /**
      * `One or more selected sources are unavailable. Check the sources or workspace AI indexing settings, then retry.`
      */
    ["com.cloud-notes.ai.error.selectedSourcesUnavailable"](): string;
    /**
      * `Too many sources or too much content was selected. Select fewer sources and send a new message.`
      */
    ["com.cloud-notes.ai.error.selectedSourcesLimitExceeded"](): string;
    /**
      * `Retry`
      */
    ["com.cloud-notes.ai.error.retry"](): string;
    /**
      * `Working… · {{count}} actions`
      */
    ["com.cloud-notes.ai.chat-panel.tool-group.running"](options: {
        readonly count: string;
    }): string;
    /**
      * `Completed {{count}} actions`
      */
    ["com.cloud-notes.ai.chat-panel.tool-group.completed"](options: {
        readonly count: string;
    }): string;
    /**
      * `Completed {{count}} actions · {{failed}} failed`
      */
    ["com.cloud-notes.ai.chat-panel.tool-group.failed"](options: Readonly<{
        count: string;
        failed: string;
    }>): string;
    /**
      * `Delete this history?`
      */
    ["com.cloud-notes.ai.chat-panel.session.delete.confirm.title"](): string;
    /**
      * `Do you want to delete this AI conversation history? Once deleted, it cannot be recovered.`
      */
    ["com.cloud-notes.ai.chat-panel.session.delete.confirm.message"](): string;
    /**
      * `History deleted`
      */
    ["com.cloud-notes.ai.chat-panel.session.delete.toast.success"](): string;
    /**
      * `Failed to delete history`
      */
    ["com.cloud-notes.ai.chat-panel.session.delete.toast.failed"](): string;
    /**
      * `All docs`
      */
    ["com.cloud-notes.all-pages.header"](): string;
    /**
      * `Learn more`
      */
    ["com.cloud-notes.app-sidebar.learn-more"](): string;
    /**
      * `Star us`
      */
    ["com.cloud-notes.app-sidebar.star-us"](): string;
    /**
      * `Download update`
      */
    ["com.cloud-notes.appUpdater.downloadUpdate"](): string;
    /**
      * `Downloading`
      */
    ["com.cloud-notes.appUpdater.downloading"](): string;
    /**
      * `Restart to install update`
      */
    ["com.cloud-notes.appUpdater.installUpdate"](): string;
    /**
      * `Open download page`
      */
    ["com.cloud-notes.appUpdater.openDownloadPage"](): string;
    /**
      * `Update available`
      */
    ["com.cloud-notes.appUpdater.updateAvailable"](): string;
    /**
      * `Discover what's new!`
      */
    ["com.cloud-notes.appUpdater.whatsNew"](): string;
    /**
      * `Customise the appearance of the client.`
      */
    ["com.cloud-notes.appearanceSettings.clientBorder.description"](): string;
    /**
      * `Client border style`
      */
    ["com.cloud-notes.appearanceSettings.clientBorder.title"](): string;
    /**
      * `Choose your colour mode`
      */
    ["com.cloud-notes.appearanceSettings.color.description"](): string;
    /**
      * `Colour mode`
      */
    ["com.cloud-notes.appearanceSettings.color.title"](): string;
    /**
      * `Edit all Cloud Notes theme variables here`
      */
    ["com.cloud-notes.appearanceSettings.customize-theme.description"](): string;
    /**
      * `Customize Theme`
      */
    ["com.cloud-notes.appearanceSettings.customize-theme.title"](): string;
    /**
      * `Images`
      */
    ["com.cloud-notes.appearanceSettings.images.title"](): string;
    /**
      * `Smooth image rendering`
      */
    ["com.cloud-notes.appearanceSettings.images.antialiasing.title"](): string;
    /**
      * `When disabled, images are rendered using nearest-neighbor scaling for crisp pixels.`
      */
    ["com.cloud-notes.appearanceSettings.images.antialiasing.description"](): string;
    /**
      * `Reset all`
      */
    ["com.cloud-notes.appearanceSettings.customize-theme.reset"](): string;
    /**
      * `Open Theme Editor`
      */
    ["com.cloud-notes.appearanceSettings.customize-theme.open"](): string;
    /**
      * `Choose your font style`
      */
    ["com.cloud-notes.appearanceSettings.font.description"](): string;
    /**
      * `Font style`
      */
    ["com.cloud-notes.appearanceSettings.font.title"](): string;
    /**
      * `Mono`
      */
    ["com.cloud-notes.appearanceSettings.fontStyle.mono"](): string;
    /**
      * `Sans`
      */
    ["com.cloud-notes.appearanceSettings.fontStyle.sans"](): string;
    /**
      * `Serif`
      */
    ["com.cloud-notes.appearanceSettings.fontStyle.serif"](): string;
    /**
      * `Select the language for the interface.`
      */
    ["com.cloud-notes.appearanceSettings.language.description"](): string;
    /**
      * `Display language`
      */
    ["com.cloud-notes.appearanceSettings.language.title"](): string;
    /**
      * `Use background noise effect on the sidebar.`
      */
    ["com.cloud-notes.appearanceSettings.noisyBackground.description"](): string;
    /**
      * `Noise background on the sidebar`
      */
    ["com.cloud-notes.appearanceSettings.noisyBackground.title"](): string;
    /**
      * `Sidebar`
      */
    ["com.cloud-notes.appearanceSettings.sidebar.title"](): string;
    /**
      * `Customize your Cloud Notes appearance`
      */
    ["com.cloud-notes.appearanceSettings.subtitle"](): string;
    /**
      * `Menubar`
      */
    ["com.cloud-notes.appearanceSettings.menubar.title"](): string;
    /**
      * `Enable menubar app`
      */
    ["com.cloud-notes.appearanceSettings.menubar.toggle"](): string;
    /**
      * `Display the menubar app in the tray for quick access to Cloud Notes or meeting recordings.`
      */
    ["com.cloud-notes.appearanceSettings.menubar.description"](): string;
    /**
      * `Window behavior`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.title"](): string;
    /**
      * `Quick open from tray icon`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.openOnLeftClick.toggle"](): string;
    /**
      * `Open Cloud Notes when left‑clicking the tray icon.`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.openOnLeftClick.description"](): string;
    /**
      * `Minimize to tray`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.minimizeToTray.toggle"](): string;
    /**
      * `Minimize Cloud Notes to the system tray.`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.minimizeToTray.description"](): string;
    /**
      * `Close to tray`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.closeToTray.toggle"](): string;
    /**
      * `Close Cloud Notes to the system tray.`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.closeToTray.description"](): string;
    /**
      * `Start minimized`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.startMinimized.toggle"](): string;
    /**
      * `Start Cloud Notes minimized to the system tray.`
      */
    ["com.cloud-notes.appearanceSettings.menubar.windowBehavior.startMinimized.description"](): string;
    /**
      * `Theme`
      */
    ["com.cloud-notes.appearanceSettings.theme.title"](): string;
    /**
      * `Appearance settings`
      */
    ["com.cloud-notes.appearanceSettings.title"](): string;
    /**
      * `Use transparency effect on the sidebar.`
      */
    ["com.cloud-notes.appearanceSettings.translucentUI.description"](): string;
    /**
      * `Translucent UI on the sidebar`
      */
    ["com.cloud-notes.appearanceSettings.translucentUI.title"](): string;
    /**
      * `Show linked doc in sidebar`
      */
    ["com.cloud-notes.appearanceSettings.showLinkedDocInSidebar.title"](): string;
    /**
      * `Control whether to show the structure of linked docs in the sidebar.`
      */
    ["com.cloud-notes.appearanceSettings.showLinkedDocInSidebar.description"](): string;
    /**
      * `Your current email is {{email}}. We'll send a confirmation link there first so you can securely switch to a new email address.`
      */
    ["com.cloud-notes.auth.change.email.message"](options: {
        readonly email: string;
    }): string;
    /**
      * `Please enter your new email address below. We will send a verification link to this email address to complete the process.`
      */
    ["com.cloud-notes.auth.change.email.page.subtitle"](): string;
    /**
      * `Congratulations! You have successfully updated the email address associated with your Cloud Notes account.`
      */
    ["com.cloud-notes.auth.change.email.page.success.subtitle"](): string;
    /**
      * `Email address updated!`
      */
    ["com.cloud-notes.auth.change.email.page.success.title"](): string;
    /**
      * `Change email address`
      */
    ["com.cloud-notes.auth.change.email.page.title"](): string;
    /**
      * `Forgot password`
      */
    ["com.cloud-notes.auth.forget"](): string;
    /**
      * `Later`
      */
    ["com.cloud-notes.auth.later"](): string;
    /**
      * `Open Cloud Notes`
      */
    ["com.cloud-notes.auth.open.cloud-notes"](): string;
    /**
      * `Download app`
      */
    ["com.cloud-notes.auth.open.cloud-notes.download-app"](): string;
    /**
      * `Try again`
      */
    ["com.cloud-notes.auth.open.cloud-notes.try-again"](): string;
    /**
      * `Still have problems?`
      */
    ["com.cloud-notes.auth.open.cloud-notes.still-have-problems"](): string;
    /**
      * `Continue with Browser`
      */
    ["com.cloud-notes.auth.open.cloud-notes.continue-with-browser"](): string;
    /**
      * `Download Latest Client`
      */
    ["com.cloud-notes.auth.open.cloud-notes.download-latest-client"](): string;
    /**
      * `Open here instead`
      */
    ["com.cloud-notes.auth.open.cloud-notes.doc.open-here"](): string;
    /**
      * `Edit settings`
      */
    ["com.cloud-notes.auth.open.cloud-notes.doc.edit-settings"](): string;
    /**
      * `Requires Cloud Notes desktop app version 0.18 or later.`
      */
    ["com.cloud-notes.auth.open.cloud-notes.doc.footer-text"](): string;
    /**
      * `Please set a password of {{min}}-{{max}} characters with both letters and numbers to continue signing up with `
      */
    ["com.cloud-notes.auth.page.sent.email.subtitle"](options: Readonly<{
        min: string;
        max: string;
    }>): string;
    /**
      * `Welcome to Cloud Notes, you're almost there!`
      */
    ["com.cloud-notes.auth.page.sent.email.title"](): string;
    /**
      * `Password`
      */
    ["com.cloud-notes.auth.password"](): string;
    /**
      * `Invalid password`
      */
    ["com.cloud-notes.auth.password.error"](): string;
    /**
      * `Set password failed`
      */
    ["com.cloud-notes.auth.password.set-failed"](): string;
    /**
      * `Reset password`
      */
    ["com.cloud-notes.auth.reset.password"](): string;
    /**
      * `You will receive an email with a link to reset your password. Please check your inbox.`
      */
    ["com.cloud-notes.auth.reset.password.message"](): string;
    /**
      * `Password reset successful`
      */
    ["com.cloud-notes.auth.reset.password.page.success"](): string;
    /**
      * `Reset your Cloud Notes password`
      */
    ["com.cloud-notes.auth.reset.password.page.title"](): string;
    /**
      * `Send reset link`
      */
    ["com.cloud-notes.auth.send.reset.password.link"](): string;
    /**
      * `Send set link`
      */
    ["com.cloud-notes.auth.send.set.password.link"](): string;
    /**
      * `Send verification link`
      */
    ["com.cloud-notes.auth.send.verify.email.hint"](): string;
    /**
      * `Verification code`
      */
    ["com.cloud-notes.auth.sign.auth.code"](): string;
    /**
      * `Invalid verification code`
      */
    ["com.cloud-notes.auth.sign.auth.code.invalid"](): string;
    /**
      * `Continue with code`
      */
    ["com.cloud-notes.auth.sign.auth.code.continue"](): string;
    /**
      * `Resend code`
      */
    ["com.cloud-notes.auth.sign.auth.code.resend"](): string;
    /**
      * `Resend in {{second}}s`
      */
    ["com.cloud-notes.auth.sign.auth.code.resend.hint"](options: {
        readonly second: string;
    }): string;
    /**
      * `Sent`
      */
    ["com.cloud-notes.auth.sent"](): string;
    /**
      * `The verification link failed to be sent, please try again later.`
      */
    ["com.cloud-notes.auth.sent.change.email.fail"](): string;
    /**
      * `Verification link has been sent.`
      */
    ["com.cloud-notes.auth.sent.change.email.hint"](): string;
    /**
      * `Reset password link has been sent.`
      */
    ["com.cloud-notes.auth.sent.change.password.hint"](): string;
    /**
      * `Your password has been updated! You can sign in to Cloud Notes with the new password.`
      */
    ["com.cloud-notes.auth.sent.reset.password.success.message"](): string;
    /**
      * `Set password link has been sent.`
      */
    ["com.cloud-notes.auth.sent.set.password.hint"](): string;
    /**
      * `Your password has been saved! You can sign in to Cloud Notes with email and password.`
      */
    ["com.cloud-notes.auth.sent.set.password.success.message"](): string;
    /**
      * `Verification link has been sent.`
      */
    ["com.cloud-notes.auth.sent.verify.email.hint"](): string;
    /**
      * `Save Email`
      */
    ["com.cloud-notes.auth.set.email.save"](): string;
    /**
      * `Set password`
      */
    ["com.cloud-notes.auth.set.password"](): string;
    /**
      * `Please set a password of {{min}}-{{max}} characters with both letters and numbers to continue signing up with `
      */
    ["com.cloud-notes.auth.set.password.message"](options: Readonly<{
        min: string;
        max: string;
    }>): string;
    /**
      * `Maximum {{max}} characters`
      */
    ["com.cloud-notes.auth.set.password.message.maxlength"](options: {
        readonly max: string;
    }): string;
    /**
      * `Minimum {{min}} characters`
      */
    ["com.cloud-notes.auth.set.password.message.minlength"](options: {
        readonly min: string;
    }): string;
    /**
      * `Password set successful`
      */
    ["com.cloud-notes.auth.set.password.page.success"](): string;
    /**
      * `Set your Cloud Notes password`
      */
    ["com.cloud-notes.auth.set.password.page.title"](): string;
    /**
      * `Set a password at least {{min}} letters long`
      */
    ["com.cloud-notes.auth.set.password.placeholder"](options: {
        readonly min: string;
    }): string;
    /**
      * `Confirm password`
      */
    ["com.cloud-notes.auth.set.password.placeholder.confirm"](): string;
    /**
      * `Save password`
      */
    ["com.cloud-notes.auth.set.password.save"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.auth.sign-out.confirm-modal.cancel"](): string;
    /**
      * `Sign Out`
      */
    ["com.cloud-notes.auth.sign-out.confirm-modal.confirm"](): string;
    /**
      * `After signing out, synced workspaces associated with this account will be removed from this device. Signing in again will add them back.`
      */
    ["com.cloud-notes.auth.sign-out.confirm-modal.description"](): string;
    /**
      * `Sign out?`
      */
    ["com.cloud-notes.auth.sign-out.confirm-modal.title"](): string;
    /**
      * `If you haven't received the email, please check your spam folder.`
      */
    ["com.cloud-notes.auth.sign.auth.code.message"](): string;
    /**
      * `Sign in with magic link`
      */
    ["com.cloud-notes.auth.sign.auth.code.send-email.sign-in"](): string;
    /**
      * `Terms of conditions`
      */
    ["com.cloud-notes.auth.sign.condition"](): string;
    /**
      * `Continue with email`
      */
    ["com.cloud-notes.auth.sign.email.continue"](): string;
    /**
      * `Invalid email`
      */
    ["com.cloud-notes.auth.sign.email.error"](): string;
    /**
      * `Enter your email address`
      */
    ["com.cloud-notes.auth.sign.email.placeholder"](): string;
    /**
      * `Sign in to Cloud Notes`
      */
    ["com.cloud-notes.auth.sign.in"](): string;
    /**
      * `Confirm your email`
      */
    ["com.cloud-notes.auth.sign.in.sent.email.subtitle"](): string;
    /**
      * `Self-hosted Cloud Notes`
      */
    ["com.cloud-notes.auth.sign.add-selfhosted.title"](): string;
    /**
      * `Connect to your Cloud Notes server`
      */
    ["com.cloud-notes.auth.sign.add-selfhosted"](): string;
    /**
      * `Cloud Notes Server URL`
      */
    ["com.cloud-notes.auth.sign.add-selfhosted.baseurl"](): string;
    /**
      * `Connect`
      */
    ["com.cloud-notes.auth.sign.add-selfhosted.connect-button"](): string;
    /**
      * `Unable to connect to the server.`
      */
    ["com.cloud-notes.auth.sign.add-selfhosted.error"](): string;
    /**
      * `Privacy policy`
      */
    ["com.cloud-notes.auth.sign.policy"](): string;
    /**
      * `Sign up`
      */
    ["com.cloud-notes.auth.sign.up"](): string;
    /**
      * `Create your account`
      */
    ["com.cloud-notes.auth.sign.up.sent.email.subtitle"](): string;
    /**
      * `The app will automatically open or redirect to the web version. If you encounter any issues, you can also click the button below to manually open the Cloud Notes app.`
      */
    ["com.cloud-notes.auth.sign.up.success.subtitle"](): string;
    /**
      * `Your account has been created and you're now signed in!`
      */
    ["com.cloud-notes.auth.sign.up.success.title"](): string;
    /**
      * `You have successfully signed in. The app will automatically open or redirect to the web version. if you encounter any issues, you can also click the button below to  manually open the Cloud Notes app.`
      */
    ["com.cloud-notes.auth.signed.success.subtitle"](): string;
    /**
      * `You're almost there!`
      */
    ["com.cloud-notes.auth.signed.success.title"](): string;
    /**
      * `Server error, please try again later.`
      */
    ["com.cloud-notes.auth.toast.message.failed"](): string;
    /**
      * `You have signed in to Cloud Notes.`
      */
    ["com.cloud-notes.auth.toast.message.signed-in"](): string;
    /**
      * `Unable to sign in`
      */
    ["com.cloud-notes.auth.toast.title.failed"](): string;
    /**
      * `Signed in`
      */
    ["com.cloud-notes.auth.toast.title.signed-in"](): string;
    /**
      * `Your current email is {{email}}. We'll send a verification link to this email so you can confirm it belongs to you.`
      */
    ["com.cloud-notes.auth.verify.email.message"](options: {
        readonly email: string;
    }): string;
    /**
      * `Back`
      */
    ["com.cloud-notes.backButton"](): string;
    /**
      * `Your workspace is saved in this browser. When disk space is low, the browser may remove it automatically. Enable Cloud Notes Sync to keep it safer.`
      */
    ["com.cloud-notes.banner.local-warning"](): string;
    /**
      * `Cloud Notes`
      */
    ["com.cloud-notes.brand.cloudNotesCloud"](): string;
    /**
      * `Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec`
      */
    ["com.cloud-notes.calendar-date-picker.month-names"](): string;
    /**
      * `Today`
      */
    ["com.cloud-notes.calendar-date-picker.today"](): string;
    /**
      * `Su,Mo,Tu,We,Th,Fr,Sa`
      */
    ["com.cloud-notes.calendar-date-picker.week-days"](): string;
    /**
      * `Host by notes.diegonmarcos.com, Save, sync, and backup all your data.`
      */
    ["com.cloud-notes.cloud-scroll-tip.caption"](): string;
    /**
      * `Cloud Notes`
      */
    ["com.cloud-notes.cloud-scroll-tip.title"](): string;
    /**
      * `Collections`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.collections"](): string;
    /**
      * `Create`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.creation"](): string;
    /**
      * `Edgeless`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.edgeless"](): string;
    /**
      * `General`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.general"](): string;
    /**
      * `Help`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.help"](): string;
    /**
      * `Layout controls`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.layout"](): string;
    /**
      * `Navigation`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.navigation"](): string;
    /**
      * `Docs`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.pages"](): string;
    /**
      * `Recent`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.recent"](): string;
    /**
      * `Settings`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.settings"](): string;
    /**
      * `Tags`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.tags"](): string;
    /**
      * `Updates`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.updates"](): string;
    /**
      * `Edgeless commands`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.editor.edgeless"](): string;
    /**
      * `Insert object`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.editor.insert-object"](): string;
    /**
      * `Doc Commands`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.editor.page"](): string;
    /**
      * `Results`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.results"](): string;
    /**
      * `Change client border style to`
      */
    ["com.cloud-notes.cmdk.cloud-notes.client-border-style.to"](): string;
    /**
      * `Change colour mode to`
      */
    ["com.cloud-notes.cmdk.cloud-notes.color-mode.to"](): string;
    /**
      * `Contact us`
      */
    ["com.cloud-notes.cmdk.cloud-notes.contact-us"](): string;
    /**
      * `Create "{{keyWord}}" doc and insert`
      */
    ["com.cloud-notes.cmdk.cloud-notes.create-new-doc-and-insert"](options: {
        readonly keyWord: string;
    }): string;
    /**
      * `New "{{keyWord}}" edgeless`
      */
    ["com.cloud-notes.cmdk.cloud-notes.create-new-edgeless-as"](options: {
        readonly keyWord: string;
    }): string;
    /**
      * `New "{{keyWord}}" page`
      */
    ["com.cloud-notes.cmdk.cloud-notes.create-new-page-as"](options: {
        readonly keyWord: string;
    }): string;
    /**
      * `Change display language to`
      */
    ["com.cloud-notes.cmdk.cloud-notes.display-language.to"](): string;
    /**
      * `Add to favourites`
      */
    ["com.cloud-notes.cmdk.cloud-notes.editor.add-to-favourites"](): string;
    /**
      * `Start presentation`
      */
    ["com.cloud-notes.cmdk.cloud-notes.editor.edgeless.presentation-start"](): string;
    /**
      * `Remove from favourites`
      */
    ["com.cloud-notes.cmdk.cloud-notes.editor.remove-from-favourites"](): string;
    /**
      * `Restore from trash`
      */
    ["com.cloud-notes.cmdk.cloud-notes.editor.restore-from-trash"](): string;
    /**
      * `Reveal doc history modal`
      */
    ["com.cloud-notes.cmdk.cloud-notes.editor.reveal-page-history-modal"](): string;
    /**
      * `This doc has been moved to the trash, you can either restore or permanently delete it.`
      */
    ["com.cloud-notes.cmdk.cloud-notes.editor.trash-footer-hint"](): string;
    /**
      * `Change font style to`
      */
    ["com.cloud-notes.cmdk.cloud-notes.font-style.to"](): string;
    /**
      * `Change full width layout to`
      */
    ["com.cloud-notes.cmdk.cloud-notes.full-width-layout.to"](): string;
    /**
      * `Change default width for new pages in to standard`
      */
    ["com.cloud-notes.cmdk.cloud-notes.default-page-width-layout.standard"](): string;
    /**
      * `Change default width for new pages in to full width`
      */
    ["com.cloud-notes.cmdk.cloud-notes.default-page-width-layout.full-width"](): string;
    /**
      * `Change current page width to standard`
      */
    ["com.cloud-notes.cmdk.cloud-notes.current-page-width-layout.standard"](): string;
    /**
      * `Change current page width to full width`
      */
    ["com.cloud-notes.cmdk.cloud-notes.current-page-width-layout.full-width"](): string;
    /**
      * `Getting started`
      */
    ["com.cloud-notes.cmdk.cloud-notes.getting-started"](): string;
    /**
      * `Import workspace`
      */
    ["com.cloud-notes.cmdk.cloud-notes.import-workspace"](): string;
    /**
      * `Insert this link to the current doc`
      */
    ["com.cloud-notes.cmdk.cloud-notes.insert-link"](): string;
    /**
      * `Collapse left sidebar`
      */
    ["com.cloud-notes.cmdk.cloud-notes.left-sidebar.collapse"](): string;
    /**
      * `Expand left sidebar`
      */
    ["com.cloud-notes.cmdk.cloud-notes.left-sidebar.expand"](): string;
    /**
      * `Go to all docs`
      */
    ["com.cloud-notes.cmdk.cloud-notes.navigation.goto-all-pages"](): string;
    /**
      * `Go to edgeless list`
      */
    ["com.cloud-notes.cmdk.cloud-notes.navigation.goto-edgeless-list"](): string;
    /**
      * `Go to page list`
      */
    ["com.cloud-notes.cmdk.cloud-notes.navigation.goto-page-list"](): string;
    /**
      * `Go to trash`
      */
    ["com.cloud-notes.cmdk.cloud-notes.navigation.goto-trash"](): string;
    /**
      * `Go to workspace`
      */
    ["com.cloud-notes.cmdk.cloud-notes.navigation.goto-workspace"](): string;
    /**
      * `Go to account settings`
      */
    ["com.cloud-notes.cmdk.cloud-notes.navigation.open-account-settings"](): string;
    /**
      * `Go to Settings`
      */
    ["com.cloud-notes.cmdk.cloud-notes.navigation.open-settings"](): string;
    /**
      * `New edgeless`
      */
    ["com.cloud-notes.cmdk.cloud-notes.new-edgeless-page"](): string;
    /**
      * `New page`
      */
    ["com.cloud-notes.cmdk.cloud-notes.new-page"](): string;
    /**
      * `New workspace`
      */
    ["com.cloud-notes.cmdk.cloud-notes.new-workspace"](): string;
    /**
      * `Change noise background on the sidebar to`
      */
    ["com.cloud-notes.cmdk.cloud-notes.noise-background-on-the-sidebar.to"](): string;
    /**
      * `Restart to upgrade`
      */
    ["com.cloud-notes.cmdk.cloud-notes.restart-to-upgrade"](): string;
    /**
      * `OFF`
      */
    ["com.cloud-notes.cmdk.cloud-notes.switch-state.off"](): string;
    /**
      * `ON`
      */
    ["com.cloud-notes.cmdk.cloud-notes.switch-state.on"](): string;
    /**
      * `Change translucent UI on the sidebar to`
      */
    ["com.cloud-notes.cmdk.cloud-notes.translucent-ui-on-the-sidebar.to"](): string;
    /**
      * `What's new`
      */
    ["com.cloud-notes.cmdk.cloud-notes.whats-new"](): string;
    /**
      * `Search docs or paste link...`
      */
    ["com.cloud-notes.cmdk.docs.placeholder"](): string;
    /**
      * `Insert links`
      */
    ["com.cloud-notes.cmdk.insert-links"](): string;
    /**
      * `No results found`
      */
    ["com.cloud-notes.cmdk.no-results"](): string;
    /**
      * `No results found for`
      */
    ["com.cloud-notes.cmdk.no-results-for"](): string;
    /**
      * `Type a command or search anything...`
      */
    ["com.cloud-notes.cmdk.placeholder"](): string;
    /**
      * `Switch to $t(com.cloud-notes.edgelessMode)`
      */
    ["com.cloud-notes.cmdk.switch-to-edgeless"](): string;
    /**
      * `Switch to $t(com.cloud-notes.pageMode)`
      */
    ["com.cloud-notes.cmdk.switch-to-page"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.collection-bar.action.tooltip.delete"](): string;
    /**
      * `Edit`
      */
    ["com.cloud-notes.collection-bar.action.tooltip.edit"](): string;
    /**
      * `Pin to sidebar`
      */
    ["com.cloud-notes.collection-bar.action.tooltip.pin"](): string;
    /**
      * `Unpin`
      */
    ["com.cloud-notes.collection-bar.action.tooltip.unpin"](): string;
    /**
      * `Do you want to add a document to the current collection? If it is filtered based on rules, this will add a set of included rules.`
      */
    ["com.cloud-notes.collection.add-doc.confirm.description"](): string;
    /**
      * `Add new doc to this collection`
      */
    ["com.cloud-notes.collection.add-doc.confirm.title"](): string;
    /**
      * `Doc already exists`
      */
    ["com.cloud-notes.collection.addPage.alreadyExists"](): string;
    /**
      * `Added successfully`
      */
    ["com.cloud-notes.collection.addPage.success"](): string;
    /**
      * `Add docs`
      */
    ["com.cloud-notes.collection.addPages"](): string;
    /**
      * `Add rules`
      */
    ["com.cloud-notes.collection.addRules"](): string;
    /**
      * `All collections`
      */
    ["com.cloud-notes.collection.allCollections"](): string;
    /**
      * `Empty collection`
      */
    ["com.cloud-notes.collection.emptyCollection"](): string;
    /**
      * `Collection is a smart folder where you can manually add docs or automatically add docs through rules.`
      */
    ["com.cloud-notes.collection.emptyCollectionDescription"](): string;
    /**
      * `HELP INFO`
      */
    ["com.cloud-notes.collection.helpInfo"](): string;
    /**
      * `Edit collection`
      */
    ["com.cloud-notes.collection.menu.edit"](): string;
    /**
      * `Rename`
      */
    ["com.cloud-notes.collection.menu.rename"](): string;
    /**
      * `Removed successfully`
      */
    ["com.cloud-notes.collection.removePage.success"](): string;
    /**
      * `No collections`
      */
    ["com.cloud-notes.collections.empty.message"](): string;
    /**
      * `New collection`
      */
    ["com.cloud-notes.collections.empty.new-collection-button"](): string;
    /**
      * `Collections`
      */
    ["com.cloud-notes.collections.header"](): string;
    /**
      * `Couldn't copy image`
      */
    ["com.cloud-notes.copy.asImage.notAvailable.title"](): string;
    /**
      * `The 'Copy as image' feature is only available on our desktop app. Please download and install the client to access this feature.`
      */
    ["com.cloud-notes.copy.asImage.notAvailable.message"](): string;
    /**
      * `Download Client`
      */
    ["com.cloud-notes.copy.asImage.notAvailable.action"](): string;
    /**
      * `Image copied`
      */
    ["com.cloud-notes.copy.asImage.success"](): string;
    /**
      * `Image copy failed`
      */
    ["com.cloud-notes.copy.asImage.failed"](): string;
    /**
      * `Copy as Markdown`
      */
    ["com.cloud-notes.export.copy-markdown"](): string;
    /**
      * `Copied as Markdown`
      */
    ["com.cloud-notes.export.copied-as-markdown"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.confirmModal.button.cancel"](): string;
    /**
      * `Ok`
      */
    ["com.cloud-notes.confirmModal.button.ok"](): string;
    /**
      * `Current year`
      */
    ["com.cloud-notes.currentYear"](): string;
    /**
      * `Deleting {{count}} tags cannot be undone, please proceed with caution.`
      */
    ["com.cloud-notes.delete-tags.confirm.multi-tag-description"](options: {
        readonly count: string;
    }): string;
    /**
      * `Delete tag?`
      */
    ["com.cloud-notes.delete-tags.confirm.title"](): string;
    /**
      * `{{count}} tag deleted`
    
      * - com.cloud-notes.delete-tags.count_one: `{{count}} tag deleted`
    
      * - com.cloud-notes.delete-tags.count_other: `{{count}} tags deleted`
      */
    ["com.cloud-notes.delete-tags.count"](options: {
        readonly count: string | number | bigint;
    }): string;
    /**
      * `{{count}} tag deleted`
      */
    ["com.cloud-notes.delete-tags.count_one"](options: {
        readonly count: string | number | bigint;
    }): string;
    /**
      * `{{count}} tags deleted`
      */
    ["com.cloud-notes.delete-tags.count_other"](options: {
        readonly count: string | number | bigint;
    }): string;
    /**
      * `Delete workspace from this device and optionally delete all data.`
      */
    ["com.cloud-notes.deleteLeaveWorkspace.description"](): string;
    /**
      * `Leave workspace`
      */
    ["com.cloud-notes.deleteLeaveWorkspace.leave"](): string;
    /**
      * `After you leave, you will not be able to access content within this workspace.`
      */
    ["com.cloud-notes.deleteLeaveWorkspace.leaveDescription"](): string;
    /**
      * `Docs`
      */
    ["com.cloud-notes.docs.header"](): string;
    /**
      * `Draw with a blank whiteboard`
      */
    ["com.cloud-notes.draw_with_a_blank_whiteboard"](): string;
    /**
      * `Earlier`
      */
    ["com.cloud-notes.earlier"](): string;
    /**
      * `Edgeless mode`
      */
    ["com.cloud-notes.edgelessMode"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.editCollection.button.cancel"](): string;
    /**
      * `Create`
      */
    ["com.cloud-notes.editCollection.button.create"](): string;
    /**
      * `Create collection`
      */
    ["com.cloud-notes.editCollection.createCollection"](): string;
    /**
      * `Filters`
      */
    ["com.cloud-notes.editCollection.filters"](): string;
    /**
      * `Docs`
      */
    ["com.cloud-notes.editCollection.pages"](): string;
    /**
      * `Clear selected`
      */
    ["com.cloud-notes.editCollection.pages.clear"](): string;
    /**
      * `Rename collection`
      */
    ["com.cloud-notes.editCollection.renameCollection"](): string;
    /**
      * `Rules`
      */
    ["com.cloud-notes.editCollection.rules"](): string;
    /**
      * `No results`
      */
    ["com.cloud-notes.editCollection.rules.empty.noResults"](): string;
    /**
      * `No docs meet the filtering rules`
      */
    ["com.cloud-notes.editCollection.rules.empty.noResults.tips"](): string;
    /**
      * `No rules`
      */
    ["com.cloud-notes.editCollection.rules.empty.noRules"](): string;
    /**
      * `Add selected doc`
      */
    ["com.cloud-notes.editCollection.rules.include.add"](): string;
    /**
      * `is`
      */
    ["com.cloud-notes.editCollection.rules.include.is"](): string;
    /**
      * `is-not`
      */
    ["com.cloud-notes.editCollection.rules.include.is-not"](): string;
    /**
      * `Doc`
      */
    ["com.cloud-notes.editCollection.rules.include.page"](): string;
    /**
      * `“Selected docs” refers to manually adding docs rather than automatically adding them through rule matching. You can manually add docs through the “Add selected docs” option or by dragging and dropping.`
      */
    ["com.cloud-notes.editCollection.rules.include.tips"](): string;
    /**
      * `What is "Selected docs"？`
      */
    ["com.cloud-notes.editCollection.rules.include.tipsTitle"](): string;
    /**
      * `Selected docs`
      */
    ["com.cloud-notes.editCollection.rules.include.title"](): string;
    /**
      * `Preview`
      */
    ["com.cloud-notes.editCollection.rules.preview"](): string;
    /**
      * `Reset`
      */
    ["com.cloud-notes.editCollection.rules.reset"](): string;
    /**
      * `automatically`
      */
    ["com.cloud-notes.editCollection.rules.tips.highlight"](): string;
    /**
      * `Save`
      */
    ["com.cloud-notes.editCollection.save"](): string;
    /**
      * `Save as new collection`
      */
    ["com.cloud-notes.editCollection.saveCollection"](): string;
    /**
      * `Search doc...`
      */
    ["com.cloud-notes.editCollection.search.placeholder"](): string;
    /**
      * `Untitled collection`
      */
    ["com.cloud-notes.editCollection.untitledCollection"](): string;
    /**
      * `Update collection`
      */
    ["com.cloud-notes.editCollection.updateCollection"](): string;
    /**
      * `Collection is a smart folder where you can manually add docs or automatically add docs through rules.`
      */
    ["com.cloud-notes.editCollectionName.createTips"](): string;
    /**
      * `Name`
      */
    ["com.cloud-notes.editCollectionName.name"](): string;
    /**
      * `Collection name`
      */
    ["com.cloud-notes.editCollectionName.name.placeholder"](): string;
    /**
      * `Default to Edgeless mode`
      */
    ["com.cloud-notes.editorDefaultMode.edgeless"](): string;
    /**
      * `Default to Page mode`
      */
    ["com.cloud-notes.editorDefaultMode.page"](): string;
    /**
      * `Add docs`
      */
    ["com.cloud-notes.empty.collection-detail.action.add-doc"](): string;
    /**
      * `Add rules`
      */
    ["com.cloud-notes.empty.collection-detail.action.add-rule"](): string;
    /**
      * `Collection is a smart folder where you can manually add docs or automatically add docs through rules.`
      */
    ["com.cloud-notes.empty.collection-detail.description"](): string;
    /**
      * `Empty collection`
      */
    ["com.cloud-notes.empty.collection-detail.title"](): string;
    /**
      * `Add collection`
      */
    ["com.cloud-notes.empty.collections.action.new-collection"](): string;
    /**
      * `Create your first collection here.`
      */
    ["com.cloud-notes.empty.collections.description"](): string;
    /**
      * `Collection management`
      */
    ["com.cloud-notes.empty.collections.title"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.empty.docs.action.new-doc"](): string;
    /**
      * `Create your first doc here.`
      */
    ["com.cloud-notes.empty.docs.all-description"](): string;
    /**
      * `Docs management`
      */
    ["com.cloud-notes.empty.docs.title"](): string;
    /**
      * `Deleted docs will appear here.`
      */
    ["com.cloud-notes.empty.docs.trash-description"](): string;
    /**
      * `Create a new tag for your documents.`
      */
    ["com.cloud-notes.empty.tags.description"](): string;
    /**
      * `Tag management`
      */
    ["com.cloud-notes.empty.tags.title"](): string;
    /**
      * `There's no doc here yet`
      */
    ["com.cloud-notes.emptyDesc"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.enableCloudNotesCloudModal.button.cancel"](): string;
    /**
      * `Enable Cloud Notes Sync for {{workspaceName}}`
      */
    ["com.cloud-notes.enableCloudNotesCloudModal.custom-server.title"](options: {
        readonly workspaceName: string;
    }): string;
    /**
      * `Choose where this workspace will sync.`
      */
    ["com.cloud-notes.enableCloudNotesCloudModal.custom-server.description"](): string;
    /**
      * `Enable Cloud Notes Sync`
      */
    ["com.cloud-notes.enableCloudNotesCloudModal.custom-server.enable"](): string;
    /**
      * `Hide error`
      */
    ["com.cloud-notes.error.hide-error"](): string;
    /**
      * `Doc content is missing`
      */
    ["com.cloud-notes.error.no-page-root.title"](): string;
    /**
      * `It takes longer to load the doc content.`
      */
    ["com.cloud-notes.error.loading-timeout-error"](): string;
    /**
      * `Refetch`
      */
    ["com.cloud-notes.error.refetch"](): string;
    /**
      * `Reload Cloud Notes`
      */
    ["com.cloud-notes.error.reload"](): string;
    /**
      * `Refresh`
      */
    ["com.cloud-notes.error.retry"](): string;
    /**
      * `Something is wrong...`
      */
    ["com.cloud-notes.error.unexpected-error.title"](): string;
    /**
      * `Please request a new reset password link.`
      */
    ["com.cloud-notes.expired.page.subtitle"](): string;
    /**
      * `Please request a new link.`
      */
    ["com.cloud-notes.expired.page.new-subtitle"](): string;
    /**
      * `This link has expired...`
      */
    ["com.cloud-notes.expired.page.title"](): string;
    /**
      * `Please try it again later.`
      */
    ["com.cloud-notes.export.error.message"](): string;
    /**
      * `Export failed due to an unexpected error`
      */
    ["com.cloud-notes.export.error.title"](): string;
    /**
      * `Print`
      */
    ["com.cloud-notes.export.print"](): string;
    /**
      * `Please open the download folder to check.`
      */
    ["com.cloud-notes.export.success.message"](): string;
    /**
      * `Exported successfully`
      */
    ["com.cloud-notes.export.success.title"](): string;
    /**
      * `Add to favourites`
      */
    ["com.cloud-notes.favoritePageOperation.add"](): string;
    /**
      * `Remove from favourites`
      */
    ["com.cloud-notes.favoritePageOperation.remove"](): string;
    /**
      * `Filter`
      */
    ["com.cloud-notes.filter"](): string;
    /**
      * `Add Filter Rule`
      */
    ["com.cloud-notes.filter.add-filter"](): string;
    /**
      * `after`
      */
    ["com.cloud-notes.filter.after"](): string;
    /**
      * `before`
      */
    ["com.cloud-notes.filter.before"](): string;
    /**
      * `contains all`
      */
    ["com.cloud-notes.filter.contains all"](): string;
    /**
      * `contains one of`
      */
    ["com.cloud-notes.filter.contains one of"](): string;
    /**
      * `does not contains all`
      */
    ["com.cloud-notes.filter.does not contains all"](): string;
    /**
      * `does not contains one of`
      */
    ["com.cloud-notes.filter.does not contains one of"](): string;
    /**
      * `Empty`
      */
    ["com.cloud-notes.filter.empty-tag"](): string;
    /**
      * `Empty`
      */
    ["com.cloud-notes.filter.empty"](): string;
    /**
      * `false`
      */
    ["com.cloud-notes.filter.false"](): string;
    /**
      * `is`
      */
    ["com.cloud-notes.filter.is"](): string;
    /**
      * `is empty`
      */
    ["com.cloud-notes.filter.is empty"](): string;
    /**
      * `is not empty`
      */
    ["com.cloud-notes.filter.is not empty"](): string;
    /**
      * `Favourited`
      */
    ["com.cloud-notes.filter.is-favourited"](): string;
    /**
      * `Shared`
      */
    ["com.cloud-notes.filter.is-public"](): string;
    /**
      * `between`
      */
    ["com.cloud-notes.filter.between"](): string;
    /**
      * `last 3 days`
      */
    ["com.cloud-notes.filter.last 3 days"](): string;
    /**
      * `last 7 days`
      */
    ["com.cloud-notes.filter.last 7 days"](): string;
    /**
      * `last 15 days`
      */
    ["com.cloud-notes.filter.last 15 days"](): string;
    /**
      * `last 30 days`
      */
    ["com.cloud-notes.filter.last 30 days"](): string;
    /**
      * `this week`
      */
    ["com.cloud-notes.filter.this week"](): string;
    /**
      * `this month`
      */
    ["com.cloud-notes.filter.this month"](): string;
    /**
      * `this quarter`
      */
    ["com.cloud-notes.filter.this quarter"](): string;
    /**
      * `this year`
      */
    ["com.cloud-notes.filter.this year"](): string;
    /**
      * `last`
      */
    ["com.cloud-notes.filter.last"](): string;
    /**
      * `Save view`
      */
    ["com.cloud-notes.filter.save-view"](): string;
    /**
      * `true`
      */
    ["com.cloud-notes.filter.true"](): string;
    /**
      * `Add filter`
      */
    ["com.cloud-notes.filterList.button.add"](): string;
    /**
      * `Display`
      */
    ["com.cloud-notes.explorer.display-menu.button"](): string;
    /**
      * `Grouping`
      */
    ["com.cloud-notes.explorer.display-menu.grouping"](): string;
    /**
      * `Remove group`
      */
    ["com.cloud-notes.explorer.display-menu.grouping.remove"](): string;
    /**
      * `Ordering`
      */
    ["com.cloud-notes.explorer.display-menu.ordering"](): string;
    /**
      * `View in Page mode`
      */
    ["com.cloud-notes.header.mode-switch.page"](): string;
    /**
      * `View in Edgeless Canvas`
      */
    ["com.cloud-notes.header.mode-switch.edgeless"](): string;
    /**
      * `Add tag`
      */
    ["com.cloud-notes.header.option.add-tag"](): string;
    /**
      * `Duplicate`
      */
    ["com.cloud-notes.header.option.duplicate"](): string;
    /**
      * `Open in desktop app`
      */
    ["com.cloud-notes.header.option.open-in-desktop"](): string;
    /**
      * `View all frames`
      */
    ["com.cloud-notes.header.option.view-frame"](): string;
    /**
      * `View table of contents`
      */
    ["com.cloud-notes.header.option.view-toc"](): string;
    /**
      * `Table of contents`
      */
    ["com.cloud-notes.header.menu.toc"](): string;
    /**
      * `Contact us`
      */
    ["com.cloud-notes.helpIsland.contactUs"](): string;
    /**
      * `Getting started`
      */
    ["com.cloud-notes.helpIsland.gettingStarted"](): string;
    /**
      * `Help and feedback`
      */
    ["com.cloud-notes.helpIsland.helpAndFeedback"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.history-vision.tips-modal.cancel"](): string;
    /**
      * `Enable Cloud Notes Sync`
      */
    ["com.cloud-notes.history-vision.tips-modal.confirm"](): string;
    /**
      * `Version history can't work with local workspace. Enable Cloud Notes Sync for this workspace to use version history.`
      */
    ["com.cloud-notes.history-vision.tips-modal.description"](): string;
    /**
      * `Version history needs Cloud Notes Sync`
      */
    ["com.cloud-notes.history-vision.tips-modal.title"](): string;
    /**
      * `Back to doc`
      */
    ["com.cloud-notes.history.back-to-page"](): string;
    /**
      * `You are about to restore the current version of the doc to the latest version available. This action will overwrite any changes made prior to the latest version.`
      */
    ["com.cloud-notes.history.confirm-restore-modal.hint"](): string;
    /**
      * `Load more`
      */
    ["com.cloud-notes.history.confirm-restore-modal.load-more"](): string;
    /**
      * `LIMITED DOC HISTORY`
      */
    ["com.cloud-notes.history.confirm-restore-modal.plan-prompt.limited-title"](): string;
    /**
      * `HELP INFO`
      */
    ["com.cloud-notes.history.confirm-restore-modal.plan-prompt.title"](): string;
    /**
      * `Upgrade`
      */
    ["com.cloud-notes.history.confirm-restore-modal.pro-plan-prompt.upgrade"](): string;
    /**
      * `Restore`
      */
    ["com.cloud-notes.history.confirm-restore-modal.restore"](): string;
    /**
      * `This document is such a spring chicken, it hasn't sprouted a single historical sprig yet!`
      */
    ["com.cloud-notes.history.empty-prompt.description"](): string;
    /**
      * `Empty`
      */
    ["com.cloud-notes.history.empty-prompt.title"](): string;
    /**
      * `Restore current version`
      */
    ["com.cloud-notes.history.restore-current-version"](): string;
    /**
      * `Version history`
      */
    ["com.cloud-notes.history.version-history"](): string;
    /**
      * `View history version`
      */
    ["com.cloud-notes.history.view-history-version"](): string;
    /**
      * `Create into a New Workspace`
      */
    ["com.cloud-notes.import-template.dialog.createDocToNewWorkspace"](): string;
    /**
      * `Create doc to "{{workspace}}"`
      */
    ["com.cloud-notes.import-template.dialog.createDocToWorkspace"](options: {
        readonly workspace: string;
    }): string;
    /**
      * `Create doc with "{{templateName}}" template`
      */
    ["com.cloud-notes.import-template.dialog.createDocWithTemplate"](options: {
        readonly templateName: string;
    }): string;
    /**
      * `Failed to import template, please try again.`
      */
    ["com.cloud-notes.import-template.dialog.errorImport"](): string;
    /**
      * `Failed to load template, please try again.`
      */
    ["com.cloud-notes.import-template.dialog.errorLoad"](): string;
    /**
      * `Create into a New Workspace`
      */
    ["com.cloud-notes.import-clipper.dialog.createDocToNewWorkspace"](): string;
    /**
      * `Create doc to "{{workspace}}"`
      */
    ["com.cloud-notes.import-clipper.dialog.createDocToWorkspace"](options: {
        readonly workspace: string;
    }): string;
    /**
      * `Create doc from Web Clipper`
      */
    ["com.cloud-notes.import-clipper.dialog.createDocFromClipper"](): string;
    /**
      * `Failed to import content, please try again.`
      */
    ["com.cloud-notes.import-clipper.dialog.errorImport"](): string;
    /**
      * `Failed to load content, please try again.`
      */
    ["com.cloud-notes.import-clipper.dialog.errorLoad"](): string;
    /**
      * `Support Markdown/Notion`
      */
    ["com.cloud-notes.import_file"](): string;
    /**
      * `Cloud Notes workspace data`
      */
    ["com.cloud-notes.import.cloud-notes-workspace-data"](): string;
    /**
      * `Bear (.bear2bk) (Experimental)`
      */
    ["com.cloud-notes.import.bear"](): string;
    /**
      * `Import your Bear note backup. Tags will be converted to Cloud Notes tags and folders.`
      */
    ["com.cloud-notes.import.bear.tooltip"](): string;
    /**
      * `Docx`
      */
    ["com.cloud-notes.import.docx"](): string;
    /**
      * `Import your .docx file.`
      */
    ["com.cloud-notes.import.docx.tooltip"](): string;
    /**
      * `HTML`
      */
    ["com.cloud-notes.import.html-files"](): string;
    /**
      * `This is an experimental feature that is not perfect and may cause your data to be missing after import.`
      */
    ["com.cloud-notes.import.html-files.tooltip"](): string;
    /**
      * `Markdown files (.md)`
      */
    ["com.cloud-notes.import.markdown-files"](): string;
    /**
      * `Markdown with media files (.zip)`
      */
    ["com.cloud-notes.import.markdown-with-media-files"](): string;
    /**
      * `Please upload a markdown zip file with attachments, experimental function, there may be data loss.`
      */
    ["com.cloud-notes.import.markdown-with-media-files.tooltip"](): string;
    /**
      * `If you'd like to request support for additional file types, feel free to let us know on`
      */
    ["com.cloud-notes.import.modal.tip"](): string;
    /**
      * `Notion (Experimental)`
      */
    ["com.cloud-notes.import.notion"](): string;
    /**
      * `Import your Notion data. Supported import formats: HTML with subpages.`
      */
    ["com.cloud-notes.import.notion.tooltip"](): string;
    /**
      * `OneNote (Experimental)`
      */
    ["com.cloud-notes.import.onenote"](): string;
    /**
      * `Import a OneNote .one, .onetoc2, or .onepkg file. Available in the desktop app.`
      */
    ["com.cloud-notes.import.onenote.tooltip"](): string;
    /**
      * `This format importer is available in the Cloud Notes desktop app.`
      */
    ["com.cloud-notes.import.onenote.desktop-only"](): string;
    /**
      * `Obsidian Vault (Experimental)`
      */
    ["com.cloud-notes.import.obsidian"](): string;
    /**
      * `Import an Obsidian vault. Select a folder to import all notes, images, and assets with wikilinks resolved.`
      */
    ["com.cloud-notes.import.obsidian.tooltip"](): string;
    /**
      * `Snapshot`
      */
    ["com.cloud-notes.import.snapshot"](): string;
    /**
      * `Import your Cloud Notes workspace and page snapshot file.`
      */
    ["com.cloud-notes.import.snapshot.tooltip"](): string;
    /**
      * `.cloud-notes file`
      */
    ["com.cloud-notes.import.dotcloudnotesfile"](): string;
    /**
      * `Import your Cloud Notes db file (.cloud-notes)`
      */
    ["com.cloud-notes.import.dotcloudnotesfile.tooltip"](): string;
    /**
      * `Import failed, please try again.`
      */
    ["com.cloud-notes.import.status.failed.message"](): string;
    /**
      * `No file selected`
      */
    ["com.cloud-notes.import.status.failed.message.no-file-selected"](): string;
    /**
      * `Import failure`
      */
    ["com.cloud-notes.import.status.failed.title"](): string;
    /**
      * `Importing your workspace data, please wait patiently.`
      */
    ["com.cloud-notes.import.status.importing.message"](): string;
    /**
      * `Importing...`
      */
    ["com.cloud-notes.import.status.importing.title"](): string;
    /**
      * `Your document has been imported successfully, thank you for choosing Cloud Notes. Any questions please feel free to feedback to us`
      */
    ["com.cloud-notes.import.status.success.message"](): string;
    /**
      * `Import completed`
      */
    ["com.cloud-notes.import.status.success.title"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.inviteModal.button.cancel"](): string;
    /**
      * `Maybe later`
      */
    ["com.cloud-notes.issue-feedback.cancel"](): string;
    /**
      * `Create issue on GitHub`
      */
    ["com.cloud-notes.issue-feedback.confirm"](): string;
    /**
      * `Got feedback? We're all ears! Create an issue on GitHub to let us know your thoughts and suggestions`
      */
    ["com.cloud-notes.issue-feedback.description"](): string;
    /**
      * `Share your feedback on GitHub`
      */
    ["com.cloud-notes.issue-feedback.title"](): string;
    /**
      * `Journals`
      */
    ["com.cloud-notes.journal.app-sidebar-title"](): string;
    /**
      * `{{count}} more articles`
      */
    ["com.cloud-notes.journal.conflict-show-more"](options: {
        readonly count: string;
    }): string;
    /**
      * `Created`
      */
    ["com.cloud-notes.journal.created-today"](): string;
    /**
      * `You haven't created anything yet`
      */
    ["com.cloud-notes.journal.daily-count-created-empty-tips"](): string;
    /**
      * `You haven't updated anything yet`
      */
    ["com.cloud-notes.journal.daily-count-updated-empty-tips"](): string;
    /**
      * `Updated`
      */
    ["com.cloud-notes.journal.updated-today"](): string;
    /**
      * `No journal for this day`
      */
    ["com.cloud-notes.journal.placeholder.title"](): string;
    /**
      * `Create one to start writing.`
      */
    ["com.cloud-notes.journal.placeholder.description"](): string;
    /**
      * `Create journal`
      */
    ["com.cloud-notes.journal.placeholder.create"](): string;
    /**
      * `Just now`
      */
    ["com.cloud-notes.just-now"](): string;
    /**
      * `Align center`
      */
    ["com.cloud-notes.keyboardShortcuts.alignCenter"](): string;
    /**
      * `Align left`
      */
    ["com.cloud-notes.keyboardShortcuts.alignLeft"](): string;
    /**
      * `Align right`
      */
    ["com.cloud-notes.keyboardShortcuts.alignRight"](): string;
    /**
      * `Append to daily note`
      */
    ["com.cloud-notes.keyboardShortcuts.appendDailyNote"](): string;
    /**
      * `Body text`
      */
    ["com.cloud-notes.keyboardShortcuts.bodyText"](): string;
    /**
      * `Bold`
      */
    ["com.cloud-notes.keyboardShortcuts.bold"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.keyboardShortcuts.cancel"](): string;
    /**
      * `Code block`
      */
    ["com.cloud-notes.keyboardShortcuts.codeBlock"](): string;
    /**
      * `Copy private link`
      */
    ["com.cloud-notes.keyboardShortcuts.copy-private-link"](): string;
    /**
      * `Connector`
      */
    ["com.cloud-notes.keyboardShortcuts.connector"](): string;
    /**
      * `Divider`
      */
    ["com.cloud-notes.keyboardShortcuts.divider"](): string;
    /**
      * `Expand/collapse sidebar`
      */
    ["com.cloud-notes.keyboardShortcuts.expandOrCollapseSidebar"](): string;
    /**
      * `Go back`
      */
    ["com.cloud-notes.keyboardShortcuts.goBack"](): string;
    /**
      * `Go forward`
      */
    ["com.cloud-notes.keyboardShortcuts.goForward"](): string;
    /**
      * `Group`
      */
    ["com.cloud-notes.keyboardShortcuts.group"](): string;
    /**
      * `Group as database`
      */
    ["com.cloud-notes.keyboardShortcuts.groupDatabase"](): string;
    /**
      * `Hand`
      */
    ["com.cloud-notes.keyboardShortcuts.hand"](): string;
    /**
      * `Heading {{number}}`
      */
    ["com.cloud-notes.keyboardShortcuts.heading"](options: {
        readonly number: string;
    }): string;
    /**
      * `Image`
      */
    ["com.cloud-notes.keyboardShortcuts.image"](): string;
    /**
      * `Increase indent`
      */
    ["com.cloud-notes.keyboardShortcuts.increaseIndent"](): string;
    /**
      * `Inline code`
      */
    ["com.cloud-notes.keyboardShortcuts.inlineCode"](): string;
    /**
      * `Italic`
      */
    ["com.cloud-notes.keyboardShortcuts.italic"](): string;
    /**
      * `Hyperlink (with selected text)`
      */
    ["com.cloud-notes.keyboardShortcuts.link"](): string;
    /**
      * `Move down`
      */
    ["com.cloud-notes.keyboardShortcuts.moveDown"](): string;
    /**
      * `Move up`
      */
    ["com.cloud-notes.keyboardShortcuts.moveUp"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.keyboardShortcuts.newPage"](): string;
    /**
      * `Note`
      */
    ["com.cloud-notes.keyboardShortcuts.note"](): string;
    /**
      * `Pen`
      */
    ["com.cloud-notes.keyboardShortcuts.pen"](): string;
    /**
      * `Quick search`
      */
    ["com.cloud-notes.keyboardShortcuts.quickSearch"](): string;
    /**
      * `Redo`
      */
    ["com.cloud-notes.keyboardShortcuts.redo"](): string;
    /**
      * `Reduce indent`
      */
    ["com.cloud-notes.keyboardShortcuts.reduceIndent"](): string;
    /**
      * `Select`
      */
    ["com.cloud-notes.keyboardShortcuts.select"](): string;
    /**
      * `Select all`
      */
    ["com.cloud-notes.keyboardShortcuts.selectAll"](): string;
    /**
      * `Shape`
      */
    ["com.cloud-notes.keyboardShortcuts.shape"](): string;
    /**
      * `Strikethrough`
      */
    ["com.cloud-notes.keyboardShortcuts.strikethrough"](): string;
    /**
      * `Check keyboard shortcuts quickly`
      */
    ["com.cloud-notes.keyboardShortcuts.subtitle"](): string;
    /**
      * `Switch view`
      */
    ["com.cloud-notes.keyboardShortcuts.switch"](): string;
    /**
      * `Text`
      */
    ["com.cloud-notes.keyboardShortcuts.text"](): string;
    /**
      * `Keyboard shortcuts`
      */
    ["com.cloud-notes.keyboardShortcuts.title"](): string;
    /**
      * `Ungroup`
      */
    ["com.cloud-notes.keyboardShortcuts.unGroup"](): string;
    /**
      * `Underline`
      */
    ["com.cloud-notes.keyboardShortcuts.underline"](): string;
    /**
      * `Undo`
      */
    ["com.cloud-notes.keyboardShortcuts.undo"](): string;
    /**
      * `Zoom in`
      */
    ["com.cloud-notes.keyboardShortcuts.zoomIn"](): string;
    /**
      * `Zoom out`
      */
    ["com.cloud-notes.keyboardShortcuts.zoomOut"](): string;
    /**
      * `Zoom to 100%`
      */
    ["com.cloud-notes.keyboardShortcuts.zoomTo100"](): string;
    /**
      * `Zoom to fit`
      */
    ["com.cloud-notes.keyboardShortcuts.zoomToFit"](): string;
    /**
      * `Zoom to selection`
      */
    ["com.cloud-notes.keyboardShortcuts.zoomToSelection"](): string;
    /**
      * `Last 30 days`
      */
    ["com.cloud-notes.last30Days"](): string;
    /**
      * `Last 7 days`
      */
    ["com.cloud-notes.last7Days"](): string;
    /**
      * `Last month`
      */
    ["com.cloud-notes.lastMonth"](): string;
    /**
      * `Last week`
      */
    ["com.cloud-notes.lastWeek"](): string;
    /**
      * `Last year`
      */
    ["com.cloud-notes.lastYear"](): string;
    /**
      * `Loading`
      */
    ["com.cloud-notes.loading"](): string;
    /**
      * `Loading document content, please wait a moment.`
      */
    ["com.cloud-notes.loading.description"](): string;
    /**
      * `Rename`
      */
    ["com.cloud-notes.menu.rename"](): string;
    /**
      * `No results found`
      */
    ["com.cloud-notes.mobile.search.empty"](): string;
    /**
      * `App version`
      */
    ["com.cloud-notes.mobile.setting.about.appVersion"](): string;
    /**
      * `Editor version`
      */
    ["com.cloud-notes.mobile.setting.about.editorVersion"](): string;
    /**
      * `About`
      */
    ["com.cloud-notes.mobile.setting.about.title"](): string;
    /**
      * `Log In / Sign Up`
      */
    ["com.cloud-notes.mobile.setting.account.sign-in"](): string;
    /**
      * `Account & Data`
      */
    ["com.cloud-notes.mobile.setting.account.title"](): string;
    /**
      * `Font style`
      */
    ["com.cloud-notes.mobile.setting.appearance.font"](): string;
    /**
      * `Display language`
      */
    ["com.cloud-notes.mobile.setting.appearance.language"](): string;
    /**
      * `Color mode`
      */
    ["com.cloud-notes.mobile.setting.appearance.theme"](): string;
    /**
      * `Appearance`
      */
    ["com.cloud-notes.mobile.setting.appearance.title"](): string;
    /**
      * `Experimental features`
      */
    ["com.cloud-notes.mobile.setting.experimental.features"](): string;
    /**
      * `Experimental`
      */
    ["com.cloud-notes.mobile.setting.experimental.title"](): string;
    /**
      * `Settings`
      */
    ["com.cloud-notes.mobile.setting.header-title"](): string;
    /**
      * `Star us on GitHub`
      */
    ["com.cloud-notes.mobile.setting.others.github"](): string;
    /**
      * `Discord group`
      */
    ["com.cloud-notes.mobile.setting.others.discord"](): string;
    /**
      * `Privacy`
      */
    ["com.cloud-notes.mobile.setting.others.privacy"](): string;
    /**
      * `Terms of use`
      */
    ["com.cloud-notes.mobile.setting.others.terms"](): string;
    /**
      * `Privacy & others`
      */
    ["com.cloud-notes.mobile.setting.others.title"](): string;
    /**
      * `Official website`
      */
    ["com.cloud-notes.mobile.setting.others.website"](): string;
    /**
      * `Delete Account`
      */
    ["com.cloud-notes.mobile.setting.others.delete-account"](): string;
    /**
      * `Danger Zone`
      */
    ["com.cloud-notes.mobile.setting.danger-zone.title"](): string;
    /**
      * `Plans`
      */
    ["com.cloud-notes.mobile.setting.plans.title"](): string;
    /**
      * `Collaborate in Cloud or Self-Hosted.`
      */
    ["com.cloud-notes.mobile.setting.promo.description"](): string;
    /**
      * `Cloud Notes for teams`
      */
    ["com.cloud-notes.mobile.setting.promo.title"](): string;
    /**
      * `More cloud storage and advanced features.`
      */
    ["com.cloud-notes.mobile.setting.subscription.description"](): string;
    /**
      * `Cloud Notes Pro`
      */
    ["com.cloud-notes.mobile.setting.subscription.title"](): string;
    /**
      * `Invite a friend`
      */
    ["com.cloud-notes.mobile.setting.support.invite"](): string;
    /**
      * `Check out Cloud Notes for notes, whiteboards, docs, and AI.`
      */
    ["com.cloud-notes.mobile.setting.support.invite-message"](): string;
    /**
      * `Rate Cloud Notes`
      */
    ["com.cloud-notes.mobile.setting.support.rate"](): string;
    /**
      * `Support us`
      */
    ["com.cloud-notes.mobile.setting.support.title"](): string;
    /**
      * `Want to keep data local?`
      */
    ["com.cloud-notes.mobile.sign-in.skip.hint"](): string;
    /**
      * `Start Cloud Notes without an account`
      */
    ["com.cloud-notes.mobile.sign-in.skip.link"](): string;
    /**
      * `Older than a month`
      */
    ["com.cloud-notes.moreThan30Days"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.moveToTrash.confirmModal.cancel"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.moveToTrash.confirmModal.confirm"](): string;
    /**
      * `{{title}} will be moved to trash`
      */
    ["com.cloud-notes.moveToTrash.confirmModal.description"](options: {
        readonly title: string;
    }): string;
    /**
      * `{{ number }} docs will be moved to Trash`
      */
    ["com.cloud-notes.moveToTrash.confirmModal.description.multiple"](options: {
        readonly number: string;
    }): string;
    /**
      * `Delete doc?`
      */
    ["com.cloud-notes.moveToTrash.confirmModal.title"](): string;
    /**
      * `Delete {{ number }} docs?`
      */
    ["com.cloud-notes.moveToTrash.confirmModal.title.multiple"](options: {
        readonly number: string;
    }): string;
    /**
      * `Move to trash`
      */
    ["com.cloud-notes.moveToTrash.title"](): string;
    /**
      * `New tab`
      */
    ["com.cloud-notes.multi-tab.new-tab"](): string;
    /**
      * `Cloud Notes Sync keeps your workspace backed up, available across devices, and ready for collaboration and publishing.`
      */
    ["com.cloud-notes.nameWorkspace.cloud-notes-cloud.description"](): string;
    /**
      * `Sync across devices with Cloud Notes Sync`
      */
    ["com.cloud-notes.nameWorkspace.cloud-notes-cloud.title"](): string;
    /**
      * `In the web app, workspaces are saved in this browser. If disk space runs low, the browser may remove them automatically. For fully local storage, use the desktop app.`
      */
    ["com.cloud-notes.nameWorkspace.cloud-notes-cloud.web-tips"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.nameWorkspace.button.cancel"](): string;
    /**
      * `Create`
      */
    ["com.cloud-notes.nameWorkspace.button.create"](): string;
    /**
      * `A workspace is your virtual space to capture, create and plan as just one person or together as a team.`
      */
    ["com.cloud-notes.nameWorkspace.description"](): string;
    /**
      * `Set a workspace name`
      */
    ["com.cloud-notes.nameWorkspace.placeholder"](): string;
    /**
      * `Workspace name`
      */
    ["com.cloud-notes.nameWorkspace.subtitle.workspace-name"](): string;
    /**
      * `Workspace type`
      */
    ["com.cloud-notes.nameWorkspace.subtitle.workspace-type"](): string;
    /**
      * `Name your workspace`
      */
    ["com.cloud-notes.nameWorkspace.title"](): string;
    /**
      * `New page`
      */
    ["com.cloud-notes.new.page-mode"](): string;
    /**
      * `New edgeless`
      */
    ["com.cloud-notes.new_edgeless"](): string;
    /**
      * `Import`
      */
    ["com.cloud-notes.new_import"](): string;
    /**
      * `Next week`
      */
    ["com.cloud-notes.nextWeek"](): string;
    /**
      * `Back home`
      */
    ["com.cloud-notes.notFoundPage.backButton"](): string;
    /**
      * `Page not found`
      */
    ["com.cloud-notes.notFoundPage.title"](): string;
    /**
      * `Cloud Notes Community`
      */
    ["com.cloud-notes.other-page.nav.cloud-notes-community"](): string;
    /**
      * `Blog`
      */
    ["com.cloud-notes.other-page.nav.blog"](): string;
    /**
      * `Contact us`
      */
    ["com.cloud-notes.other-page.nav.contact-us"](): string;
    /**
      * `Download app`
      */
    ["com.cloud-notes.other-page.nav.download-app"](): string;
    /**
      * `Official website`
      */
    ["com.cloud-notes.other-page.nav.official-website"](): string;
    /**
      * `Open Cloud Notes`
      */
    ["com.cloud-notes.other-page.nav.open-cloud-notes"](): string;
    /**
      * `Add linked doc`
      */
    ["com.cloud-notes.page-operation.add-linked-page"](): string;
    /**
      * `{{ count }} more properties`
      */
    ["com.cloud-notes.page-properties.more-property.more"](options: {
        readonly count: string;
    }): string;
    /**
      * `{{ count }} more property`
      */
    ["com.cloud-notes.page-properties.more-property.one"](options: {
        readonly count: string;
    }): string;
    /**
      * `hide {{ count }} property`
      */
    ["com.cloud-notes.page-properties.hide-property.one"](options: {
        readonly count: string;
    }): string;
    /**
      * `hide {{ count }} properties`
      */
    ["com.cloud-notes.page-properties.hide-property.more"](options: {
        readonly count: string;
    }): string;
    /**
      * `Add property`
      */
    ["com.cloud-notes.page-properties.add-property"](): string;
    /**
      * `Create property`
      */
    ["com.cloud-notes.page-properties.add-property.menu.create"](): string;
    /**
      * `Properties`
      */
    ["com.cloud-notes.page-properties.add-property.menu.header"](): string;
    /**
      * `Config properties`
      */
    ["com.cloud-notes.page-properties.config-properties"](): string;
    /**
      * `Backlinks`
      */
    ["com.cloud-notes.page-properties.backlinks"](): string;
    /**
      * `Type`
      */
    ["com.cloud-notes.page-properties.create-property.menu.header"](): string;
    /**
      * `Added`
      */
    ["com.cloud-notes.page-properties.create-property.added"](): string;
    /**
      * `Icons`
      */
    ["com.cloud-notes.page-properties.icons"](): string;
    /**
      * `Local user`
      */
    ["com.cloud-notes.page-properties.local-user"](): string;
    /**
      * `Outgoing links`
      */
    ["com.cloud-notes.page-properties.outgoing-links"](): string;
    /**
      * `Info`
      */
    ["com.cloud-notes.page-properties.page-info"](): string;
    /**
      * `View Info`
      */
    ["com.cloud-notes.page-properties.page-info.view"](): string;
    /**
      * `No Record`
      */
    ["com.cloud-notes.page-properties.property-user-avatar-no-record"](): string;
    /**
      * `Local User`
      */
    ["com.cloud-notes.page-properties.property-user-local"](): string;
    /**
      * `Empty`
      */
    ["com.cloud-notes.page-properties.property-value-placeholder"](): string;
    /**
      * `Always hide`
      */
    ["com.cloud-notes.page-properties.property.always-hide"](): string;
    /**
      * `Always show`
      */
    ["com.cloud-notes.page-properties.property.always-show"](): string;
    /**
      * `Checkbox`
      */
    ["com.cloud-notes.page-properties.property.checkbox"](): string;
    /**
      * `Created by`
      */
    ["com.cloud-notes.page-properties.property.createdBy"](): string;
    /**
      * `Date`
      */
    ["com.cloud-notes.page-properties.property.date"](): string;
    /**
      * `Hide in view`
      */
    ["com.cloud-notes.page-properties.property.hide-in-view"](): string;
    /**
      * `Hide in view when empty`
      */
    ["com.cloud-notes.page-properties.property.hide-in-view-when-empty"](): string;
    /**
      * `Hide when empty`
      */
    ["com.cloud-notes.page-properties.property.hide-when-empty"](): string;
    /**
      * `Number`
      */
    ["com.cloud-notes.page-properties.property.number"](): string;
    /**
      * `Progress`
      */
    ["com.cloud-notes.page-properties.property.progress"](): string;
    /**
      * `Remove property`
      */
    ["com.cloud-notes.page-properties.property.remove-property"](): string;
    /**
      * `Required`
      */
    ["com.cloud-notes.page-properties.property.required"](): string;
    /**
      * `Show in view`
      */
    ["com.cloud-notes.page-properties.property.show-in-view"](): string;
    /**
      * `Tags`
      */
    ["com.cloud-notes.page-properties.property.tags"](): string;
    /**
      * `Doc mode`
      */
    ["com.cloud-notes.page-properties.property.docPrimaryMode"](): string;
    /**
      * `Text`
      */
    ["com.cloud-notes.page-properties.property.text"](): string;
    /**
      * `Journal`
      */
    ["com.cloud-notes.page-properties.property.journal"](): string;
    /**
      * `Duplicated`
      */
    ["com.cloud-notes.page-properties.property.journal-duplicated"](): string;
    /**
      * `Remove journal mark`
      */
    ["com.cloud-notes.page-properties.property.journal-remove"](): string;
    /**
      * `Last edited by`
      */
    ["com.cloud-notes.page-properties.property.updatedBy"](): string;
    /**
      * `Created`
      */
    ["com.cloud-notes.page-properties.property.createdAt"](): string;
    /**
      * `Updated`
      */
    ["com.cloud-notes.page-properties.property.updatedAt"](): string;
    /**
      * `Edgeless theme`
      */
    ["com.cloud-notes.page-properties.property.edgelessTheme"](): string;
    /**
      * `Page width`
      */
    ["com.cloud-notes.page-properties.property.pageWidth"](): string;
    /**
      * `Template`
      */
    ["com.cloud-notes.page-properties.property.template"](): string;
    /**
      * `Add relevant identifiers or categories to the doc. Useful for organizing content, improving searchability, and grouping related docs together.`
      */
    ["com.cloud-notes.page-properties.property.tags.tooltips"](): string;
    /**
      * `Indicates that this doc is a journal entry or daily note. Facilitates easy capture of ideas, quick logging of thoughts, and ongoing personal reflection.`
      */
    ["com.cloud-notes.page-properties.property.journal.tooltips"](): string;
    /**
      * `Use a checkbox to indicate whether a condition is true or false. Useful for confirming options, toggling features, or tracking task states.`
      */
    ["com.cloud-notes.page-properties.property.checkbox.tooltips"](): string;
    /**
      * `Use a date field to select or display a specific date. Useful for scheduling, setting deadlines, or recording important events.`
      */
    ["com.cloud-notes.page-properties.property.date.tooltips"](): string;
    /**
      * `Upload images to display or manage them. Useful for showcasing visual content, adding illustrations, or organizing a gallery.`
      */
    ["com.cloud-notes.page-properties.property.image.tooltips"](): string;
    /**
      * `Select one or more options. Useful for categorizing items, filtering data, or managing tags.`
      */
    ["com.cloud-notes.page-properties.property.multiSelect.tooltips"](): string;
    /**
      * `Enter a numeric value. Useful for quantities, measurements, or ranking items.`
      */
    ["com.cloud-notes.page-properties.property.number.tooltips"](): string;
    /**
      * `Set a progress value between 0 and 100. Useful for tracking completion status, visualizing progress, or managing goals.`
      */
    ["com.cloud-notes.page-properties.property.progress.tooltips"](): string;
    /**
      * `Choose one option. Useful for selecting a single preference, categorizing items, or making decisions.`
      */
    ["com.cloud-notes.page-properties.property.select.tooltips"](): string;
    /**
      * `Enter a link to websites or Cloud Notes docs. Useful for connecting to external resources and referencing internal docs.`
      */
    ["com.cloud-notes.page-properties.property.link.tooltips"](): string;
    /**
      * `Enter text. Useful for descriptions, comments, notes, or any other free-form text input.`
      */
    ["com.cloud-notes.page-properties.property.text.tooltips"](): string;
    /**
      * `Displays the author of the current doc. Useful for tracking doc ownership, accountability, and collaboration.`
      */
    ["com.cloud-notes.page-properties.property.createdBy.tooltips"](): string;
    /**
      * `Displays the last editor of the current doc. Useful for tracking recent changes.`
      */
    ["com.cloud-notes.page-properties.property.updatedBy.tooltips"](): string;
    /**
      * `Record the last modification timestamp. Useful for tracking changes, identifying recent updates, or monitoring content freshness.`
      */
    ["com.cloud-notes.page-properties.property.updatedAt.tooltips"](): string;
    /**
      * `Track when a doc was first created. Useful for maintaining record history, sorting by creation date, or auditing content chronologically.`
      */
    ["com.cloud-notes.page-properties.property.createdAt.tooltips"](): string;
    /**
      * `Select the doc mode from Page Mode, Edgeless Mode, or Auto. Useful for choosing the best display for your content.`
      */
    ["com.cloud-notes.page-properties.property.docPrimaryMode.tooltips"](): string;
    /**
      * `Select the doc theme from Light, Dark, or System. Useful for precise control over content viewing style.`
      */
    ["com.cloud-notes.page-properties.property.edgelessTheme.tooltips"](): string;
    /**
      * `Control the width of this page to fit content display needs.`
      */
    ["com.cloud-notes.page-properties.property.pageWidth.tooltips"](): string;
    /**
      * `Mark this doc as a template, which can be used to create new docs.`
      */
    ["com.cloud-notes.page-properties.property.template.tooltips"](): string;
    /**
      * `Created by {{userName}}`
      */
    ["com.cloud-notes.page-properties.property.createdBy.tip"](options: {
        readonly userName: string;
    }): string;
    /**
      * `Last edited by {{userName}}`
      */
    ["com.cloud-notes.page-properties.property.updatedBy.tip"](options: {
        readonly userName: string;
    }): string;
    /**
      * `Properties`
      */
    ["com.cloud-notes.propertySidebar.property-list.section"](): string;
    /**
      * `Add more properties`
      */
    ["com.cloud-notes.propertySidebar.add-more.section"](): string;
    /**
      * `customize properties`
      */
    ["com.cloud-notes.page-properties.settings.title"](): string;
    /**
      * `Open tag page`
      */
    ["com.cloud-notes.page-properties.tags.open-tags-page"](): string;
    /**
      * `Select tag or create one`
      */
    ["com.cloud-notes.page-properties.tags.selector-header-title"](): string;
    /**
      * `Display`
      */
    ["com.cloud-notes.page.display"](): string;
    /**
      * `Display properties`
      */
    ["com.cloud-notes.page.display.display-properties"](): string;
    /**
      * `Body notes`
      */
    ["com.cloud-notes.page.display.display-properties.body-notes"](): string;
    /**
      * `Grouping`
      */
    ["com.cloud-notes.page.display.grouping"](): string;
    /**
      * `Favourites`
      */
    ["com.cloud-notes.page.display.grouping.group-by-favourites"](): string;
    /**
      * `Tag`
      */
    ["com.cloud-notes.page.display.grouping.group-by-tag"](): string;
    /**
      * `Untagged`
      */
    ["com.cloud-notes.page.display.grouping.group-by-tag.untagged"](): string;
    /**
      * `No grouping`
      */
    ["com.cloud-notes.page.display.grouping.no-grouping"](): string;
    /**
      * `List option`
      */
    ["com.cloud-notes.page.display.list-option"](): string;
    /**
      * `Clear selection`
      */
    ["com.cloud-notes.page.group-header.clear"](): string;
    /**
      * `Favourited`
      */
    ["com.cloud-notes.page.group-header.favourited"](): string;
    /**
      * `Not favourited`
      */
    ["com.cloud-notes.page.group-header.not-favourited"](): string;
    /**
      * `Select all`
      */
    ["com.cloud-notes.page.group-header.select-all"](): string;
    /**
      * `Created by {{name}}`
      */
    ["com.cloud-notes.page.toolbar.created_by"](options: {
        readonly name: string;
    }): string;
    /**
      * `Doc mode`
      */
    ["com.cloud-notes.pageMode"](): string;
    /**
      * `all`
      */
    ["com.cloud-notes.pageMode.all"](): string;
    /**
      * `Edgeless`
      */
    ["com.cloud-notes.pageMode.edgeless"](): string;
    /**
      * `Page`
      */
    ["com.cloud-notes.pageMode.page"](): string;
    /**
      * `Congratulations on your successful purchase of Cloud Notes AI! You're now empowered to refine your content, generate images, and craft comprehensive mindmaps directly within Cloud Notes AI, dramatically enhancing your productivity.`
      */
    ["com.cloud-notes.payment.ai-upgrade-success-page.text"](): string;
    /**
      * `Purchase successful!`
      */
    ["com.cloud-notes.payment.ai-upgrade-success-page.title"](): string;
    /**
      * `Cancel subscription`
      */
    ["com.cloud-notes.payment.ai.action.cancel.button-label"](): string;
    /**
      * `Keep Cloud Notes AI`
      */
    ["com.cloud-notes.payment.ai.action.cancel.confirm.cancel-text"](): string;
    /**
      * `Cancel subscription`
      */
    ["com.cloud-notes.payment.ai.action.cancel.confirm.confirm-text"](): string;
    /**
      * `If you end your subscription now, you can still use Cloud Notes AI until the end of this billing period.`
      */
    ["com.cloud-notes.payment.ai.action.cancel.confirm.description"](): string;
    /**
      * `Cancel subscription`
      */
    ["com.cloud-notes.payment.ai.action.cancel.confirm.title"](): string;
    /**
      * `Login`
      */
    ["com.cloud-notes.payment.ai.action.login.button-label"](): string;
    /**
      * `Resume`
      */
    ["com.cloud-notes.payment.ai.action.resume.button-label"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.payment.ai.action.resume.confirm.cancel-text"](): string;
    /**
      * `Confirm`
      */
    ["com.cloud-notes.payment.ai.action.resume.confirm.confirm-text"](): string;
    /**
      * `Are you sure you want to resume the subscription for Cloud Notes AI? This means your payment method will be charged automatically at the end of each billing cycle, starting from the next billing cycle.`
      */
    ["com.cloud-notes.payment.ai.action.resume.confirm.description"](): string;
    /**
      * `You will be charged in the next billing cycle.`
      */
    ["com.cloud-notes.payment.ai.action.resume.confirm.notify.msg"](): string;
    /**
      * `Subscription updated`
      */
    ["com.cloud-notes.payment.ai.action.resume.confirm.notify.title"](): string;
    /**
      * `Resume auto-renewal?`
      */
    ["com.cloud-notes.payment.ai.action.resume.confirm.title"](): string;
    /**
      * `Write with you`
      */
    ["com.cloud-notes.payment.ai.benefit.g1"](): string;
    /**
      * `Create quality content from sentences to articles on topics you need`
      */
    ["com.cloud-notes.payment.ai.benefit.g1-1"](): string;
    /**
      * `Rewrite like the professionals`
      */
    ["com.cloud-notes.payment.ai.benefit.g1-2"](): string;
    /**
      * `Change the tones / fix spelling & grammar`
      */
    ["com.cloud-notes.payment.ai.benefit.g1-3"](): string;
    /**
      * `Draw with you`
      */
    ["com.cloud-notes.payment.ai.benefit.g2"](): string;
    /**
      * `Visualize your mind, magically`
      */
    ["com.cloud-notes.payment.ai.benefit.g2-1"](): string;
    /**
      * `Turn your outline into beautiful, engaging presentations`
      */
    ["com.cloud-notes.payment.ai.benefit.g2-2"](): string;
    /**
      * `Summarize your content into structured mind-map`
      */
    ["com.cloud-notes.payment.ai.benefit.g2-3"](): string;
    /**
      * `Plan with you`
      */
    ["com.cloud-notes.payment.ai.benefit.g3"](): string;
    /**
      * `Memorize and tidy up your knowledge`
      */
    ["com.cloud-notes.payment.ai.benefit.g3-1"](): string;
    /**
      * `Auto-sorting and auto-tagging`
      */
    ["com.cloud-notes.payment.ai.benefit.g3-2"](): string;
    /**
      * `Open source & Privacy ensured`
      */
    ["com.cloud-notes.payment.ai.benefit.g3-3"](): string;
    /**
      * `You have purchased Cloud Notes AI. The expiration date is {{end}}.`
      */
    ["com.cloud-notes.payment.ai.billing-tip.end-at"](options: {
        readonly end: string;
    }): string;
    /**
      * `You have purchased Cloud Notes AI. The next payment date is {{due}}.`
      */
    ["com.cloud-notes.payment.ai.billing-tip.next-bill-at"](options: {
        readonly due: string;
    }): string;
    /**
      * `Your recent payment failed, the next payment date is {{due}}.`
      */
    ["com.cloud-notes.payment.billing-tip.past-due"](options: {
        readonly due: string;
    }): string;
    /**
      * `You are currently on the Free plan.`
      */
    ["com.cloud-notes.payment.ai.pricing-plan.caption-free"](): string;
    /**
      * `You have purchased Cloud Notes AI`
      */
    ["com.cloud-notes.payment.ai.pricing-plan.caption-purchased"](): string;
    /**
      * `Learn about Cloud Notes AI`
      */
    ["com.cloud-notes.payment.ai.pricing-plan.learn"](): string;
    /**
      * `Cloud Notes AI`
      */
    ["com.cloud-notes.payment.ai.pricing-plan.title"](): string;
    /**
      * `Turn all your ideas into reality`
      */
    ["com.cloud-notes.payment.ai.pricing-plan.title-caption-1"](): string;
    /**
      * `A true multimodal AI copilot.`
      */
    ["com.cloud-notes.payment.ai.pricing-plan.title-caption-2"](): string;
    /**
      * `Billed annually`
      */
    ["com.cloud-notes.payment.ai.subscribe.billed-annually"](): string;
    /**
      * `You have purchased Cloud Notes AI.`
      */
    ["com.cloud-notes.payment.ai.usage-description-purchased"](): string;
    /**
      * `Cloud Notes AI usage`
      */
    ["com.cloud-notes.payment.ai.usage-title"](): string;
    /**
      * `Change plan`
      */
    ["com.cloud-notes.payment.ai.usage.change-button-label"](): string;
    /**
      * `Purchase`
      */
    ["com.cloud-notes.payment.ai.usage.purchase-button-label"](): string;
    /**
      * `Times used`
      */
    ["com.cloud-notes.payment.ai.usage.used-caption"](): string;
    /**
      * `{{used}}/{{limit}} times`
      */
    ["com.cloud-notes.payment.ai.usage.used-detail"](options: Readonly<{
        used: string;
        limit: string;
    }>): string;
    /**
      * `Active`
      */
    ["com.cloud-notes.payment.subscription-status.active"](): string;
    /**
      * `Past-due bill`
      */
    ["com.cloud-notes.payment.subscription-status.past-due"](): string;
    /**
      * `Trialing`
      */
    ["com.cloud-notes.payment.subscription-status.trialing"](): string;
    /**
      * `Unlimited local workspaces`
      */
    ["com.cloud-notes.payment.benefit-1"](): string;
    /**
      * `Unlimited login devices`
      */
    ["com.cloud-notes.payment.benefit-2"](): string;
    /**
      * `Unlimited blocks`
      */
    ["com.cloud-notes.payment.benefit-3"](): string;
    /**
      * `{{capacity}} of cloud storage`
      */
    ["com.cloud-notes.payment.benefit-4"](options: {
        readonly capacity: string;
    }): string;
    /**
      * `{{capacity}} of maximum file size`
      */
    ["com.cloud-notes.payment.benefit-5"](options: {
        readonly capacity: string;
    }): string;
    /**
      * `Number of members per workspace ≤ {{capacity}}`
      */
    ["com.cloud-notes.payment.benefit-6"](options: {
        readonly capacity: string;
    }): string;
    /**
      * `{{capacity}}-days version history`
      */
    ["com.cloud-notes.payment.benefit-7"](options: {
        readonly capacity: string;
    }): string;
    /**
      * `Cloud Notes AI`
      */
    ["com.cloud-notes.payment.billing-setting.ai-plan"](): string;
    /**
      * `Purchase`
      */
    ["com.cloud-notes.payment.billing-setting.ai.purchase"](): string;
    /**
      * `Start free trial`
      */
    ["com.cloud-notes.payment.billing-setting.ai.start-free-trial"](): string;
    /**
      * `One-time payment`
      */
    ["com.cloud-notes.payment.billing-setting.believer.price-caption"](): string;
    /**
      * `Cloud Notes`
      */
    ["com.cloud-notes.payment.billing-setting.believer.title"](): string;
    /**
      * `Cancel subscription`
      */
    ["com.cloud-notes.payment.billing-setting.cancel-subscription"](): string;
    /**
      * `Once you canceled subscription you will no longer enjoy the plan benefits.`
      */
    ["com.cloud-notes.payment.billing-setting.cancel-subscription.description"](): string;
    /**
      * `Change plan`
      */
    ["com.cloud-notes.payment.billing-setting.change-plan"](): string;
    /**
      * `Cloud Notes`
      */
    ["com.cloud-notes.payment.billing-setting.current-plan"](): string;
    /**
      * `Expiration date`
      */
    ["com.cloud-notes.payment.billing-setting.expiration-date"](): string;
    /**
      * `Your subscription is valid until {{expirationDate}}`
      */
    ["com.cloud-notes.payment.billing-setting.expiration-date.description"](options: {
        readonly expirationDate: string;
    }): string;
    /**
      * `Billing history`
      */
    ["com.cloud-notes.payment.billing-setting.history"](): string;
    /**
      * `Information`
      */
    ["com.cloud-notes.payment.billing-setting.information"](): string;
    /**
      * `month`
      */
    ["com.cloud-notes.payment.billing-setting.month"](): string;
    /**
      * `There are no invoices to display.`
      */
    ["com.cloud-notes.payment.billing-setting.no-invoice"](): string;
    /**
      * `Paid`
      */
    ["com.cloud-notes.payment.billing-setting.paid"](): string;
    /**
      * `Manage payment details`
      */
    ["com.cloud-notes.payment.billing-setting.payment-method"](): string;
    /**
      * `View future and past invoices, update billing information, and change payment methods. Provided by Stripe.`
      */
    ["com.cloud-notes.payment.billing-setting.payment-method.description"](): string;
    /**
      * `Go`
      */
    ["com.cloud-notes.payment.billing-setting.payment-method.go"](): string;
    /**
      * `Renew date`
      */
    ["com.cloud-notes.payment.billing-setting.renew-date"](): string;
    /**
      * `Next billing date: {{renewDate}}`
      */
    ["com.cloud-notes.payment.billing-setting.renew-date.description"](options: {
        readonly renewDate: string;
    }): string;
    /**
      * `Due date`
      */
    ["com.cloud-notes.payment.billing-setting.due-date"](): string;
    /**
      * `Your subscription will end on {{dueDate}}`
      */
    ["com.cloud-notes.payment.billing-setting.due-date.description"](options: {
        readonly dueDate: string;
    }): string;
    /**
      * `Resume`
      */
    ["com.cloud-notes.payment.billing-setting.resume-subscription"](): string;
    /**
      * `Manage your billing information and invoices`
      */
    ["com.cloud-notes.payment.billing-setting.subtitle"](): string;
    /**
      * `Billing`
      */
    ["com.cloud-notes.payment.billing-setting.title"](): string;
    /**
      * `Update`
      */
    ["com.cloud-notes.payment.billing-setting.update"](): string;
    /**
      * `Upgrade`
      */
    ["com.cloud-notes.payment.billing-setting.upgrade"](): string;
    /**
      * `View invoice`
      */
    ["com.cloud-notes.payment.billing-setting.view-invoice"](): string;
    /**
      * `year`
      */
    ["com.cloud-notes.payment.billing-setting.year"](): string;
    /**
      * `Please tell us more about your use case, to make Cloud Notes better.`
      */
    ["com.cloud-notes.payment.billing-type-form.description"](): string;
    /**
      * `Go`
      */
    ["com.cloud-notes.payment.billing-type-form.go"](): string;
    /**
      * `Tell us your use case`
      */
    ["com.cloud-notes.payment.billing-type-form.title"](): string;
    /**
      * `You have reached the limit`
      */
    ["com.cloud-notes.payment.blob-limit.title"](): string;
    /**
      * `Book a demo`
      */
    ["com.cloud-notes.payment.book-a-demo"](): string;
    /**
      * `Buy Pro`
      */
    ["com.cloud-notes.payment.buy-pro"](): string;
    /**
      * `Change to {{to}} Billing`
      */
    ["com.cloud-notes.payment.change-to"](options: {
        readonly to: string;
    }): string;
    /**
      * `Include in FOSS`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g1"](): string;
    /**
      * `Unlimited local workspaces`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g1-1"](): string;
    /**
      * `Unlimited use and customization`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g1-2"](): string;
    /**
      * `Unlimited doc and edgeless editing`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g1-3"](): string;
    /**
      * `Include in Basic`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g2"](): string;
    /**
      * `10 GB of cloud storage.`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g2-1"](): string;
    /**
      * `10 MB of maximum file size.`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g2-2"](): string;
    /**
      * `Up to 3 members per workspace.`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g2-3"](): string;
    /**
      * `7-days cloud time machine file version history.`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g2-4"](): string;
    /**
      * `Up to 3 login devices.`
      */
    ["com.cloud-notes.payment.cloud.free.benefit.g2-5"](): string;
    /**
      * `Local Editor under MIT license.`
      */
    ["com.cloud-notes.payment.cloud.free.description"](): string;
    /**
      * `Local FOSS + Cloud Basic`
      */
    ["com.cloud-notes.payment.cloud.free.name"](): string;
    /**
      * `Free forever`
      */
    ["com.cloud-notes.payment.cloud.free.title"](): string;
    /**
      * `Included in Pro plan`
      */
    ["com.cloud-notes.payment.cloud.onetime.included"](): string;
    /**
      * `Included in Believer plan`
      */
    ["com.cloud-notes.payment.cloud.lifetime.included"](): string;
    /**
      * `We host, no technical setup required.`
      */
    ["com.cloud-notes.payment.cloud.pricing-plan.select.caption"](): string;
    /**
      * `Hosted by notes.diegonmarcos.com`
      */
    ["com.cloud-notes.payment.cloud.pricing-plan.select.title"](): string;
    /**
      * `Billed annually`
      */
    ["com.cloud-notes.payment.cloud.pricing-plan.toggle-billed-yearly"](): string;
    /**
      * `Saving {{discount}}%`
      */
    ["com.cloud-notes.payment.cloud.pricing-plan.toggle-discount"](options: {
        readonly discount: string;
    }): string;
    /**
      * `Annually`
      */
    ["com.cloud-notes.payment.cloud.pricing-plan.toggle-yearly"](): string;
    /**
      * `Include in Pro`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1"](): string;
    /**
      * `Everything in Cloud Notes FOSS & Basic.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-1"](): string;
    /**
      * `100 GB of cloud storage.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-2"](): string;
    /**
      * `100 MB of maximum file size.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-3"](): string;
    /**
      * `Up to 10 members per workspace.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-4"](): string;
    /**
      * `30-days cloud time machine file version history.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-5"](): string;
    /**
      * `Add comments on Doc and Edgeless.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-6"](): string;
    /**
      * `Community support.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-7"](): string;
    /**
      * `Real-time syncing & collaboration for more people.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-8"](): string;
    /**
      * `Granular edit access to docs.`
      */
    ["com.cloud-notes.payment.cloud.pro.benefit.g1-9"](): string;
    /**
      * `For family and small teams.`
      */
    ["com.cloud-notes.payment.cloud.pro.description"](): string;
    /**
      * `Pro`
      */
    ["com.cloud-notes.payment.cloud.pro.name"](): string;
    /**
      * `annually`
      */
    ["com.cloud-notes.payment.cloud.pro.title.billed-yearly"](): string;
    /**
      * `{{price}} per month`
      */
    ["com.cloud-notes.payment.cloud.pro.title.price-monthly"](options: {
        readonly price: string;
    }): string;
    /**
      * `Include in Team Workspace`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.benefit.g1"](): string;
    /**
      * `Everything in Cloud Notes Pro.`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.benefit.g1-1"](): string;
    /**
      * `100 GB initial storage + 20 GB per seat.`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.benefit.g1-2"](): string;
    /**
      * `500 MB of maximum file size.`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.benefit.g1-3"](): string;
    /**
      * `Unlimited team members (10+ seats).`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.benefit.g1-4"](): string;
    /**
      * `Multiple admin roles.`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.benefit.g1-5"](): string;
    /**
      * `Priority customer support.`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.benefit.g1-6"](): string;
    /**
      * `Best for scalable teams.`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.description"](): string;
    /**
      * `Team`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.name"](): string;
    /**
      * `annually`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.title.billed-yearly"](): string;
    /**
      * `{{price}} per seat/month`
      */
    ["com.cloud-notes.payment.cloud.team-workspace.title.price-monthly"](options: {
        readonly price: string;
    }): string;
    /**
      * `Contact sales`
      */
    ["com.cloud-notes.payment.contact-sales"](): string;
    /**
      * `Current plan`
      */
    ["com.cloud-notes.payment.current-plan"](): string;
    /**
      * `Start 14-day free trial`
      */
    ["com.cloud-notes.payment.start-free-trial"](): string;
    /**
      * `{{amount}}% off`
      */
    ["com.cloud-notes.payment.discount-amount"](options: {
        readonly amount: string;
    }): string;
    /**
      * `Downgrade`
      */
    ["com.cloud-notes.payment.downgrade"](): string;
    /**
      * `We'd like to hear more about where we fall short, so that we can make Cloud Notes better.`
      */
    ["com.cloud-notes.payment.downgraded-notify.content"](): string;
    /**
      * `Later`
      */
    ["com.cloud-notes.payment.downgraded-notify.later"](): string;
    /**
      * `Sure, Open in browser`
      */
    ["com.cloud-notes.payment.downgraded-notify.ok-client"](): string;
    /**
      * `Sure, Open in new tab`
      */
    ["com.cloud-notes.payment.downgraded-notify.ok-web"](): string;
    /**
      * `Sorry to see you go`
      */
    ["com.cloud-notes.payment.downgraded-notify.title"](): string;
    /**
      * `You have successfully downgraded. After the current billing period ends, your account will automatically switch to the Free plan.`
      */
    ["com.cloud-notes.payment.downgraded-tooltip"](): string;
    /**
      * `Best team workspace for collaboration and knowledge distilling.`
      */
    ["com.cloud-notes.payment.dynamic-benefit-1"](): string;
    /**
      * `Focusing on what really matters with team project management and automation.`
      */
    ["com.cloud-notes.payment.dynamic-benefit-2"](): string;
    /**
      * `Pay for seats, fits all team size.`
      */
    ["com.cloud-notes.payment.dynamic-benefit-3"](): string;
    /**
      * `Solutions & best practices for dedicated needs.`
      */
    ["com.cloud-notes.payment.dynamic-benefit-4"](): string;
    /**
      * `Embedable & interrogations with IT support.`
      */
    ["com.cloud-notes.payment.dynamic-benefit-5"](): string;
    /**
      * `Everything in Cloud Notes Pro`
      */
    ["com.cloud-notes.payment.lifetime.benefit-1"](): string;
    /**
      * `Life-time personal usage`
      */
    ["com.cloud-notes.payment.lifetime.benefit-2"](): string;
    /**
      * `{{capacity}} Cloud Storage`
      */
    ["com.cloud-notes.payment.lifetime.benefit-3"](options: {
        readonly capacity: string;
    }): string;
    /**
      * `Dedicated Discord support with Cloud Notes makers`
      */
    ["com.cloud-notes.payment.lifetime.benefit-4"](): string;
    /**
      * `Become a Life-time supporter?`
      */
    ["com.cloud-notes.payment.lifetime.caption-1"](): string;
    /**
      * `Purchase`
      */
    ["com.cloud-notes.payment.lifetime.purchase"](): string;
    /**
      * `Purchased`
      */
    ["com.cloud-notes.payment.lifetime.purchased"](): string;
    /**
      * `Believer Plan`
      */
    ["com.cloud-notes.payment.lifetime.title"](): string;
    /**
      * `Upgrade`
      */
    ["com.cloud-notes.payment.member-limit.free.confirm"](): string;
    /**
      * `Workspaces created by {{planName}} users are limited to {{quota}} members. To add more collaborators, you can:`
      */
    ["com.cloud-notes.payment.member-limit.description"](options: Readonly<{
        planName: string;
        quota: string;
    }>): string;
    /**
      * `Upgrade to Cloud Notes Pro for expanded member capacity`
      */
    ["com.cloud-notes.payment.member-limit.description.tips-for-free-plan"](): string;
    /**
      * `Convert to a Team Workspace for unlimited collaboration`
      */
    ["com.cloud-notes.payment.member-limit.description.tips-1"](): string;
    /**
      * `Or create a new workspace`
      */
    ["com.cloud-notes.payment.member-limit.description.tips-2"](): string;
    /**
      * `Got it`
      */
    ["com.cloud-notes.payment.member-limit.pro.confirm"](): string;
    /**
      * `You have reached the limit`
      */
    ["com.cloud-notes.payment.member-limit.title"](): string;
    /**
      * `Manage members here. {{planName}} users can invite up to {{memberLimit}}`
      */
    ["com.cloud-notes.payment.member.description"](options: Readonly<{
        planName: string;
        memberLimit: string;
    }>): string;
    /**
      * `Choose your plan`
      */
    ["com.cloud-notes.payment.member.description.choose-plan"](): string;
    /**
      * `go upgrade`
      */
    ["com.cloud-notes.payment.member.description.go-upgrade"](): string;
    /**
      * `Looking to collaborate with more people?`
      */
    ["com.cloud-notes.payment.member.description2"](): string;
    /**
      * `Work together with unlimited team members.`
      */
    ["com.cloud-notes.payment.member.team.description"](): string;
    /**
      * `Invite team members`
      */
    ["com.cloud-notes.payment.member.team.invite.title"](): string;
    /**
      * `Invite new members to join your workspace via email or share an invite link`
      */
    ["com.cloud-notes.payment.member.team.invite.description"](): string;
    /**
      * `Email Invite`
      */
    ["com.cloud-notes.payment.member.team.invite.email-invite"](): string;
    /**
      * `Invite Link`
      */
    ["com.cloud-notes.payment.member.team.invite.invite-link"](): string;
    /**
      * `Email addresses`
      */
    ["com.cloud-notes.payment.member.team.invite.email-addresses"](): string;
    /**
      * `Enter email addresses (separated by commas)`
      */
    ["com.cloud-notes.payment.member.team.invite.email-placeholder"](): string;
    /**
      * `Import CSV`
      */
    ["com.cloud-notes.payment.member.team.invite.import-csv"](): string;
    /**
      * `Send Invites`
      */
    ["com.cloud-notes.payment.member.team.invite.send-invites"](): string;
    /**
      * `Link expiration`
      */
    ["com.cloud-notes.payment.member.team.invite.link-expiration"](): string;
    /**
      * `{{number}} days`
      */
    ["com.cloud-notes.payment.member.team.invite.expiration-date"](options: {
        readonly number: string;
    }): string;
    /**
      * `To expire at: {{expireTime}}`
      */
    ["com.cloud-notes.payment.member.team.invite.expire-at"](options: {
        readonly expireTime: string;
    }): string;
    /**
      * `Invitation link`
      */
    ["com.cloud-notes.payment.member.team.invite.invitation-link"](): string;
    /**
      * `Generate a link to invite members to your workspace`
      */
    ["com.cloud-notes.payment.member.team.invite.invitation-link.description"](): string;
    /**
      * `Generate`
      */
    ["com.cloud-notes.payment.member.team.invite.generate"](): string;
    /**
      * `Copy`
      */
    ["com.cloud-notes.payment.member.team.invite.copy"](): string;
    /**
      * `Done`
      */
    ["com.cloud-notes.payment.member.team.invite.done"](): string;
    /**
      * `Invitations sent: {{count}}`
      */
    ["com.cloud-notes.payment.member.team.invite.notify.title"](options: {
        readonly count: string;
    }): string;
    /**
      * `These email addresses have already been invited:`
      */
    ["com.cloud-notes.payment.member.team.invite.notify.fail-message"](): string;
    /**
      * `Revoke invitation`
      */
    ["com.cloud-notes.payment.member.team.revoke"](): string;
    /**
      * `Approve`
      */
    ["com.cloud-notes.payment.member.team.approve"](): string;
    /**
      * `Decline`
      */
    ["com.cloud-notes.payment.member.team.decline"](): string;
    /**
      * `Remove member`
      */
    ["com.cloud-notes.payment.member.team.remove"](): string;
    /**
      * `Retry payment`
      */
    ["com.cloud-notes.payment.member.team.retry-payment"](): string;
    /**
      * `Change role to admin`
      */
    ["com.cloud-notes.payment.member.team.change.admin"](): string;
    /**
      * `Change role to collaborator`
      */
    ["com.cloud-notes.payment.member.team.change.collaborator"](): string;
    /**
      * `Assign as owner`
      */
    ["com.cloud-notes.payment.member.team.assign"](): string;
    /**
      * `Insufficient Team Seats`
      */
    ["com.cloud-notes.payment.member.team.retry-payment.title"](): string;
    /**
      * `The payment for adding new team members has failed. To add more seats, please update your payment method and process unpaid invoices.`
      */
    ["com.cloud-notes.payment.member.team.retry-payment.owner.description"](): string;
    /**
      * `The payment for adding new team members has failed. Please contact your workspace owner to update the payment method and process unpaid invoices.`
      */
    ["com.cloud-notes.payment.member.team.retry-payment.admin.description"](): string;
    /**
      * `Update Payment`
      */
    ["com.cloud-notes.payment.member.team.retry-payment.update-payment"](): string;
    /**
      * `Subscription has been disabled for your team workspace. To add more seats, you'll need to resume subscription first.`
      */
    ["com.cloud-notes.payment.member.team.disabled-subscription.owner.description"](): string;
    /**
      * `Your team workspace has subscription disabled, which prevents adding more seats. Please contact your workspace owner to enable subscription.`
      */
    ["com.cloud-notes.payment.member.team.disabled-subscription.admin.description"](): string;
    /**
      * `Resume Subscription`
      */
    ["com.cloud-notes.payment.member.team.disabled-subscription.resume-subscription"](): string;
    /**
      * `Invitation Revoked`
      */
    ["com.cloud-notes.payment.member.team.revoke.notify.title"](): string;
    /**
      * `You have canceled the invitation for {{name}}`
      */
    ["com.cloud-notes.payment.member.team.revoke.notify.message"](options: {
        readonly name: string;
    }): string;
    /**
      * `Request approved`
      */
    ["com.cloud-notes.payment.member.team.approve.notify.title"](): string;
    /**
      * `You have approved the {{name}}’s request to join this workspace`
      */
    ["com.cloud-notes.payment.member.team.approve.notify.message"](options: {
        readonly name: string;
    }): string;
    /**
      * `Request declined`
      */
    ["com.cloud-notes.payment.member.team.decline.notify.title"](): string;
    /**
      * `You have declined the {{name}}’s request to join this workspace`
      */
    ["com.cloud-notes.payment.member.team.decline.notify.message"](options: {
        readonly name: string;
    }): string;
    /**
      * `Member removed`
      */
    ["com.cloud-notes.payment.member.team.remove.notify.title"](): string;
    /**
      * `You have removed {{name}} from this workspace`
      */
    ["com.cloud-notes.payment.member.team.remove.notify.message"](options: {
        readonly name: string;
    }): string;
    /**
      * `Role Updated`
      */
    ["com.cloud-notes.payment.member.team.change.notify.title"](): string;
    /**
      * `You have successfully promoted {{name}} to Admin.`
      */
    ["com.cloud-notes.payment.member.team.change.admin.notify.message"](options: {
        readonly name: string;
    }): string;
    /**
      * `You have successfully changed {{name}} s role to collaborator.`
      */
    ["com.cloud-notes.payment.member.team.change.collaborator.notify.message"](options: {
        readonly name: string;
    }): string;
    /**
      * `Owner assigned`
      */
    ["com.cloud-notes.payment.member.team.assign.notify.title"](): string;
    /**
      * `You have successfully assigned {{name}} as the owner of this workspace.`
      */
    ["com.cloud-notes.payment.member.team.assign.notify.message"](options: {
        readonly name: string;
    }): string;
    /**
      * `Confirm new workspace owner`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.title"](): string;
    /**
      * `You are about to transfer workspace ownership to {{name}}. Please review the following changes carefully:`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.description"](options: {
        readonly name: string;
    }): string;
    /**
      * `This action cannot be undone`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.description-1"](): string;
    /**
      * `Your role will be changed to Admin`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.description-2"](): string;
    /**
      * `You will lose ownership rights to the entire workspace`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.description-3"](): string;
    /**
      * `To confirm this transfer, please type the workspace name`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.description-4"](): string;
    /**
      * `Type workspace name to confirm`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.placeholder"](): string;
    /**
      * `Transfer Ownership`
      */
    ["com.cloud-notes.payment.member.team.assign.confirm.button"](): string;
    /**
      * `Remove member from workspace?`
      */
    ["com.cloud-notes.payment.member.team.remove.confirm.title"](): string;
    /**
      * `This action will revoke their access to all workspace resources immediately.`
      */
    ["com.cloud-notes.payment.member.team.remove.confirm.description"](): string;
    /**
      * `Remove Member`
      */
    ["com.cloud-notes.payment.member.team.remove.confirm.confirm-button"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.payment.member.team.remove.confirm.cancel"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.payment.modal.change.cancel"](): string;
    /**
      * `Change`
      */
    ["com.cloud-notes.payment.modal.change.confirm"](): string;
    /**
      * `Change your subscription`
      */
    ["com.cloud-notes.payment.modal.change.title"](): string;
    /**
      * `Cancel subscription`
      */
    ["com.cloud-notes.payment.modal.downgrade.cancel"](): string;
    /**
      * `You can still use Cloud Notes Pro until the end of this billing period :)`
      */
    ["com.cloud-notes.payment.modal.downgrade.caption"](): string;
    /**
      * `Keep Cloud Notes Pro`
      */
    ["com.cloud-notes.payment.modal.downgrade.confirm"](): string;
    /**
      * `Keep Team plan`
      */
    ["com.cloud-notes.payment.modal.downgrade.team-confirm"](): string;
    /**
      * `We're sorry to see you go, but we're always working to improve, and your feedback is welcome. We hope to see you return in the future.`
      */
    ["com.cloud-notes.payment.modal.downgrade.content"](): string;
    /**
      * `Are you sure?`
      */
    ["com.cloud-notes.payment.modal.downgrade.title"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.payment.modal.resume.cancel"](): string;
    /**
      * `Confirm`
      */
    ["com.cloud-notes.payment.modal.resume.confirm"](): string;
    /**
      * `Are you sure you want to resume the subscription for your pro account? This means your payment method will be charged automatically at the end of each billing cycle, starting from the next billing cycle.`
      */
    ["com.cloud-notes.payment.modal.resume.content"](): string;
    /**
      * `Resume auto-renewal?`
      */
    ["com.cloud-notes.payment.modal.resume.title"](): string;
    /**
      * `Refresh`
      */
    ["com.cloud-notes.payment.plans-error-retry"](): string;
    /**
      * `Unable to load pricing plans, please check your network. `
      */
    ["com.cloud-notes.payment.plans-error-tip"](): string;
    /**
      * `monthly`
      */
    ["com.cloud-notes.payment.recurring-monthly"](): string;
    /**
      * `annually`
      */
    ["com.cloud-notes.payment.recurring-yearly"](): string;
    /**
      * `Resume`
      */
    ["com.cloud-notes.payment.resume"](): string;
    /**
      * `Subscription Resumed`
      */
    ["com.cloud-notes.payment.resume.success.title"](): string;
    /**
      * `Your team workspace subscription has been enabled successfully. Changes will take effect immediately.`
      */
    ["com.cloud-notes.payment.resume.success.team.message"](): string;
    /**
      * `Resume auto-renewal`
      */
    ["com.cloud-notes.payment.resume-renewal"](): string;
    /**
      * `See all plans`
      */
    ["com.cloud-notes.payment.see-all-plans"](): string;
    /**
      * `Sign up free`
      */
    ["com.cloud-notes.payment.sign-up-free"](): string;
    /**
      * `Cloud storage is insufficient. Please contact the owner of that workspace.`
      */
    ["com.cloud-notes.payment.storage-limit.description.member"](): string;
    /**
      * `Cloud storage is insufficient. You can upgrade your account to unlock more cloud storage.`
      */
    ["com.cloud-notes.payment.storage-limit.description.owner"](): string;
    /**
      * `Unable to sync due to insufficient storage space. You can remove excess content, upgrade your account, or increase your workspace storage to resolve this issue.`
      */
    ["com.cloud-notes.payment.storage-limit.new-description.owner"](): string;
    /**
      * `Sync failed due to storage space limit`
      */
    ["com.cloud-notes.payment.storage-limit.new-title"](): string;
    /**
      * `View`
      */
    ["com.cloud-notes.payment.storage-limit.view"](): string;
    /**
      * `You are currently on the {{plan}} plan. After the current billing period ends, your account will automatically switch to the Free plan.`
      */
    ["com.cloud-notes.payment.subtitle-canceled"](options: {
        readonly plan: string;
    }): string;
    /**
      * `This is the pricing plans of Cloud Notes. You can sign up or sign in to your account first.`
      */
    ["com.cloud-notes.payment.subtitle-not-signed-in"](): string;
    /**
      * `See all plans`
      */
    ["com.cloud-notes.payment.tag-tooltips"](): string;
    /**
      * `Tell us your use case`
      */
    ["com.cloud-notes.payment.tell-us-use-case"](): string;
    /**
      * `Pricing plans`
      */
    ["com.cloud-notes.payment.title"](): string;
    /**
      * `You have changed your plan to {{plan}} billing.`
      */
    ["com.cloud-notes.payment.updated-notify-msg"](options: {
        readonly plan: string;
    }): string;
    /**
      * `Subscription updated`
      */
    ["com.cloud-notes.payment.updated-notify-title"](): string;
    /**
      * `Upgrade`
      */
    ["com.cloud-notes.payment.upgrade"](): string;
    /**
      * `Redeem code`
      */
    ["com.cloud-notes.payment.redeem-code"](): string;
    /**
      * `We'd like to hear more about your use case, so that we can make Cloud Notes better.`
      */
    ["com.cloud-notes.payment.upgrade-success-notify.content"](): string;
    /**
      * `Later`
      */
    ["com.cloud-notes.payment.upgrade-success-notify.later"](): string;
    /**
      * `Sure, open in browser`
      */
    ["com.cloud-notes.payment.upgrade-success-notify.ok-client"](): string;
    /**
      * `Sure, open in new tab`
      */
    ["com.cloud-notes.payment.upgrade-success-notify.ok-web"](): string;
    /**
      * `Thanks for subscribing!`
      */
    ["com.cloud-notes.payment.upgrade-success-notify.title"](): string;
    /**
      * `Congratulations! Your Cloud Notes account has been successfully upgraded to a Pro account.`
      */
    ["com.cloud-notes.payment.upgrade-success-page.text"](): string;
    /**
      * `Upgrade successful!`
      */
    ["com.cloud-notes.payment.upgrade-success-page.title"](): string;
    /**
      * `Congratulations! Your workspace has been successfully upgraded to a Team Workspace. Now you can invite unlimited members to collaborate in this workspace.`
      */
    ["com.cloud-notes.payment.upgrade-success-page.team.text-1"](): string;
    /**
      * `Thank you for your purchase!`
      */
    ["com.cloud-notes.payment.license-success.title"](): string;
    /**
      * `Thank you for purchasing the Cloud Notes self-hosted license.`
      */
    ["com.cloud-notes.payment.license-success.text-1"](): string;
    /**
      * `You can use this key to upgrade in Settings > Workspace > License > Use purchased key`
      */
    ["com.cloud-notes.payment.license-success.hint"](): string;
    /**
      * `Open Cloud Notes`
      */
    ["com.cloud-notes.payment.license-success.open-cloud-notes"](): string;
    /**
      * `Copied key to clipboard`
      */
    ["com.cloud-notes.payment.license-success.copy"](): string;
    /**
      * `View analytics`
      */
    ["com.cloud-notes.doc.analytics.title"](): string;
    /**
      * `({{count}} total)`
      */
    ["com.cloud-notes.doc.analytics.summary.total"](options: {
        readonly count: string;
    }): string;
    /**
      * `Last {{days}} days`
      */
    ["com.cloud-notes.doc.analytics.window.last-days"](options: {
        readonly days: string;
    }): string;
    /**
      * `Total`
      */
    ["com.cloud-notes.doc.analytics.metric.total"](): string;
    /**
      * `Unique`
      */
    ["com.cloud-notes.doc.analytics.metric.unique"](): string;
    /**
      * `Guest`
      */
    ["com.cloud-notes.doc.analytics.metric.guest"](): string;
    /**
      * `Total views`
      */
    ["com.cloud-notes.doc.analytics.chart.total-views"](): string;
    /**
      * `Unique views`
      */
    ["com.cloud-notes.doc.analytics.chart.unique-views"](): string;
    /**
      * `Unable to load analytics.`
      */
    ["com.cloud-notes.doc.analytics.error.load-analytics"](): string;
    /**
      * `Unable to load viewers.`
      */
    ["com.cloud-notes.doc.analytics.error.load-viewers"](): string;
    /**
      * `No page views in this window.`
      */
    ["com.cloud-notes.doc.analytics.empty.no-page-views"](): string;
    /**
      * `No viewers in this window.`
      */
    ["com.cloud-notes.doc.analytics.empty.no-viewers"](): string;
    /**
      * `Viewers`
      */
    ["com.cloud-notes.doc.analytics.viewers.title"](): string;
    /**
      * `Show all viewers`
      */
    ["com.cloud-notes.doc.analytics.viewers.show-all"](): string;
    /**
      * `Open pricing plans`
      */
    ["com.cloud-notes.doc.analytics.paywall.open-pricing"](): string;
    /**
      * `Doc analytics over 7 days require a Cloud Notes Team subscription.`
      */
    ["com.cloud-notes.doc.analytics.paywall.toast"](): string;
    /**
      * `Close`
      */
    ["com.cloud-notes.peek-view-controls.close"](): string;
    /**
      * `Open this doc`
      */
    ["com.cloud-notes.peek-view-controls.open-doc"](): string;
    /**
      * `Open in edgeless`
      */
    ["com.cloud-notes.peek-view-controls.open-doc-in-edgeless"](): string;
    /**
      * `Open in new tab`
      */
    ["com.cloud-notes.peek-view-controls.open-doc-in-new-tab"](): string;
    /**
      * `Open in split view`
      */
    ["com.cloud-notes.peek-view-controls.open-doc-in-split-view"](): string;
    /**
      * `Open doc info`
      */
    ["com.cloud-notes.peek-view-controls.open-info"](): string;
    /**
      * `Open this attachment`
      */
    ["com.cloud-notes.peek-view-controls.open-attachment"](): string;
    /**
      * `Open in new tab`
      */
    ["com.cloud-notes.peek-view-controls.open-attachment-in-new-tab"](): string;
    /**
      * `Open in split view`
      */
    ["com.cloud-notes.peek-view-controls.open-attachment-in-split-view"](): string;
    /**
      * `Open in center peek`
      */
    ["com.cloud-notes.peek-view-controls.open-doc-in-center-peek"](): string;
    /**
      * `Copy link`
      */
    ["com.cloud-notes.peek-view-controls.copy-link"](): string;
    /**
      * `Click or drag`
      */
    ["com.cloud-notes.split-view-drag-handle.tooltip"](): string;
    /**
      * `Split view does not support folders.`
      */
    ["com.cloud-notes.split-view-folder-warning.description"](): string;
    /**
      * `Do not show this again`
      */
    ["do-not-show-this-again"](): string;
    /**
      * `New`
      */
    ["com.cloud-notes.quicksearch.group.creation"](): string;
    /**
      * `Search locally`
      */
    ["com.cloud-notes.quicksearch.search-locally"](): string;
    /**
      * `Search for "{{query}}"`
      */
    ["com.cloud-notes.quicksearch.group.searchfor"](options: {
        readonly query: string;
    }): string;
    /**
      * `Search for "{{query}}" (locally)`
      */
    ["com.cloud-notes.quicksearch.group.searchfor-locally"](options: {
        readonly query: string;
    }): string;
    /**
      * `Reset sync`
      */
    ["com.cloud-notes.resetSyncStatus.button"](): string;
    /**
      * `This operation may fix some synchronization issues.`
      */
    ["com.cloud-notes.resetSyncStatus.description"](): string;
    /**
      * `Collections`
      */
    ["com.cloud-notes.rootAppSidebar.collections"](): string;
    /**
      * `Notifications`
      */
    ["com.cloud-notes.rootAppSidebar.notifications"](): string;
    /**
      * `Only doc can be placed on here`
      */
    ["com.cloud-notes.rootAppSidebar.doc.link-doc-only"](): string;
    /**
      * `No linked docs`
      */
    ["com.cloud-notes.rootAppSidebar.docs.no-subdoc"](): string;
    /**
      * `Loading linked docs...`
      */
    ["com.cloud-notes.rootAppSidebar.docs.references-loading"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.collection-add-tooltip"](): string;
    /**
      * `New collection`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.collection-section-add-tooltip"](): string;
    /**
      * `New linked doc`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.doc-add-tooltip"](): string;
    /**
      * `Copy`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.drop-effect.copy"](): string;
    /**
      * `Link`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.drop-effect.link"](): string;
    /**
      * `Move`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.drop-effect.move"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.fav-section-add-tooltip"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.organize-add-tooltip"](): string;
    /**
      * `New folder`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.organize-section-add-tooltip"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.tag-add-tooltip"](): string;
    /**
      * `New tag`
      */
    ["com.cloud-notes.rootAppSidebar.explorer.tag-section-add-tooltip"](): string;
    /**
      * `Favorites`
      */
    ["com.cloud-notes.rootAppSidebar.favorites"](): string;
    /**
      * `No favorites`
      */
    ["com.cloud-notes.rootAppSidebar.favorites.empty"](): string;
    /**
      * `Migration data`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data"](): string;
    /**
      * `Empty the old favorites`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.clean-all"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.clean-all.cancel"](): string;
    /**
      * `OK`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.clean-all.confirm"](): string;
    /**
      * `The old "Favorites" will be replaced`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.help"](): string;
    /**
      * `Empty the old favorites`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.help.clean-all"](): string;
    /**
      * `OK`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.help.confirm"](): string;
    /**
      * `Organize`
      */
    ["com.cloud-notes.rootAppSidebar.organize"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.rootAppSidebar.organize.delete"](): string;
    /**
      * `Remove from folder`
      */
    ["com.cloud-notes.rootAppSidebar.organize.delete-from-folder"](): string;
    /**
      * `Delete the folder will not delete any docs, tags, or collections.`
      */
    ["com.cloud-notes.rootAppSidebar.organize.delete.notify-message"](): string;
    /**
      * `Delete {{name}}`
      */
    ["com.cloud-notes.rootAppSidebar.organize.delete.notify-title"](options: {
        readonly name: string;
    }): string;
    /**
      * `No folders`
      */
    ["com.cloud-notes.rootAppSidebar.organize.empty"](): string;
    /**
      * `Empty folder`
      */
    ["com.cloud-notes.rootAppSidebar.organize.empty-folder"](): string;
    /**
      * `Add pages`
      */
    ["com.cloud-notes.rootAppSidebar.organize.empty-folder.add-pages"](): string;
    /**
      * `New folder`
      */
    ["com.cloud-notes.rootAppSidebar.organize.empty.new-folders-button"](): string;
    /**
      * `Add to favorites`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder-add-favorite"](): string;
    /**
      * `Remove from favorites`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder-rm-favorite"](): string;
    /**
      * `Add Collections`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder.add-collections"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder.new-doc"](): string;
    /**
      * `Add docs`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder.add-docs"](): string;
    /**
      * `Add others`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder.add-others"](): string;
    /**
      * `Add tags`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder.add-tags"](): string;
    /**
      * `Create a subfolder`
      */
    ["com.cloud-notes.rootAppSidebar.organize.folder.create-subfolder"](): string;
    /**
      * `New folder`
      */
    ["com.cloud-notes.rootAppSidebar.organize.new-folders"](): string;
    /**
      * `Only folder can be placed on here`
      */
    ["com.cloud-notes.rootAppSidebar.organize.root-folder-only"](): string;
    /**
      * `Add More`
      */
    ["com.cloud-notes.rootAppSidebar.organize.add-more"](): string;
    /**
      * `Add Folder`
      */
    ["com.cloud-notes.rootAppSidebar.organize.add-folder"](): string;
    /**
      * `New Collection`
      */
    ["com.cloud-notes.rootAppSidebar.collection.new"](): string;
    /**
      * `Others`
      */
    ["com.cloud-notes.rootAppSidebar.others"](): string;
    /**
      * `Only doc can be placed on here`
      */
    ["com.cloud-notes.rootAppSidebar.tag.doc-only"](): string;
    /**
      * `Tags`
      */
    ["com.cloud-notes.rootAppSidebar.tags"](): string;
    /**
      * `No tags`
      */
    ["com.cloud-notes.rootAppSidebar.tags.empty"](): string;
    /**
      * `New tag`
      */
    ["com.cloud-notes.rootAppSidebar.tags.empty.new-tag-button"](): string;
    /**
      * `New tag`
      */
    ["com.cloud-notes.rootAppSidebar.tags.new-tag"](): string;
    /**
      * `No docs`
      */
    ["com.cloud-notes.rootAppSidebar.tags.no-doc"](): string;
    /**
      * `Drag to resize`
      */
    ["com.cloud-notes.rootAppSidebar.resize-handle.tooltip.drag"](): string;
    /**
      * `Click to collapse`
      */
    ["com.cloud-notes.rootAppSidebar.resize-handle.tooltip.click"](): string;
    /**
      * `Type here ...`
      */
    ["com.cloud-notes.search-tags.placeholder"](): string;
    /**
      * `Empty`
      */
    ["com.cloud-notes.selectPage.empty"](): string;
    /**
      * `Selected`
      */
    ["com.cloud-notes.selectPage.selected"](): string;
    /**
      * `Add include doc`
      */
    ["com.cloud-notes.selectPage.title"](): string;
    /**
      * `Search collections...`
      */
    ["com.cloud-notes.selector-collection.search.placeholder"](): string;
    /**
      * `Search tags...`
      */
    ["com.cloud-notes.selector-tag.search.placeholder"](): string;
    /**
      * `Notifications`
      */
    ["com.cloud-notes.setting.notifications"](): string;
    /**
      * `Notifications`
      */
    ["com.cloud-notes.setting.notifications.header.title"](): string;
    /**
      * `Choose the types of updates you want to receive and where to get them.`
      */
    ["com.cloud-notes.setting.notifications.header.description"](): string;
    /**
      * `Email notifications`
      */
    ["com.cloud-notes.setting.notifications.email.title"](): string;
    /**
      * `Mention`
      */
    ["com.cloud-notes.setting.notifications.email.mention.title"](): string;
    /**
      * `You will be notified through email when other members of the workspace @ you.`
      */
    ["com.cloud-notes.setting.notifications.email.mention.subtitle"](): string;
    /**
      * `Invites`
      */
    ["com.cloud-notes.setting.notifications.email.invites.title"](): string;
    /**
      * `Invitation related messages will be sent through emails.`
      */
    ["com.cloud-notes.setting.notifications.email.invites.subtitle"](): string;
    /**
      * `Comments`
      */
    ["com.cloud-notes.setting.notifications.email.comments.title"](): string;
    /**
      * `You will be notified through email when other members of the workspace comment on your docs.`
      */
    ["com.cloud-notes.setting.notifications.email.comments.subtitle"](): string;
    /**
      * `Account settings`
      */
    ["com.cloud-notes.setting.account"](): string;
    /**
      * `Delete your account from {{server}}`
      */
    ["com.cloud-notes.setting.account.delete-from-server"](options: {
        readonly server: string;
    }): string;
    /**
      * `Once deleted, your account will no longer be accessible, and all data in your personal space on the server will be permanently deleted.`
      */
    ["com.cloud-notes.setting.account.delete.message"](): string;
    /**
      * `Cannot delete account`
      */
    ["com.cloud-notes.setting.account.delete.team-warning-title"](): string;
    /**
      * `You’re the owner of a team workspace. To delete your account, please delete the workspace or transfer ownership first.`
      */
    ["com.cloud-notes.setting.account.delete.team-warning-description"](): string;
    /**
      * `Delete your account?`
      */
    ["com.cloud-notes.setting.account.delete.confirm-title"](): string;
    /**
      * `Confirm your email`
      */
    ["com.cloud-notes.setting.account.delete.email-confirm-title"](): string;
    /**
      * `Please type your email to confirm`
      */
    ["com.cloud-notes.setting.account.delete.input-placeholder"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.setting.account.delete.confirm-button"](): string;
    /**
      * `Account deleted`
      */
    ["com.cloud-notes.setting.account.delete.success-title"](): string;
    /**
      * `Your account and server-side data have been deleted.`
      */
    ["com.cloud-notes.setting.account.delete.success-description-1"](): string;
    /**
      * `Local data can be deleted by uninstalling app and clearing browser data.`
      */
    ["com.cloud-notes.setting.account.delete.success-description-2"](): string;
    /**
      * `Your personal information`
      */
    ["com.cloud-notes.setting.account.message"](): string;
    /**
      * `Sync with Cloud Notes`
      */
    ["com.cloud-notes.setting.sign.message"](): string;
    /**
      * `Securely sign out of your account.`
      */
    ["com.cloud-notes.setting.sign.out.message"](): string;
    /**
      * `General`
      */
    ["com.cloud-notes.settingSidebar.settings.general"](): string;
    /**
      * `Workspace`
      */
    ["com.cloud-notes.settingSidebar.settings.workspace"](): string;
    /**
      * `Settings`
      */
    ["com.cloud-notes.settingSidebar.title"](): string;
    /**
      * `Appearance`
      */
    ["com.cloud-notes.settings.appearance"](): string;
    /**
      * `Customise the appearance of the client.`
      */
    ["com.cloud-notes.settings.appearance.border-style-description"](): string;
    /**
      * `Customise your date style.`
      */
    ["com.cloud-notes.settings.appearance.date-format-description"](): string;
    /**
      * `Maximum display of content within a doc.`
      */
    ["com.cloud-notes.settings.appearance.full-width-description"](): string;
    /**
      * `Select the language for the interface.`
      */
    ["com.cloud-notes.settings.appearance.language-description"](): string;
    /**
      * `By default, the week starts on Sunday.`
      */
    ["com.cloud-notes.settings.appearance.start-week-description"](): string;
    /**
      * `Customise appearance of Windows Client.`
      */
    ["com.cloud-notes.settings.appearance.window-frame-description"](): string;
    /**
      * `Links`
      */
    ["com.cloud-notes.setting.appearance.links"](): string;
    /**
      * `Open Cloud Notes links`
      */
    ["com.cloud-notes.setting.appearance.open-in-app"](): string;
    /**
      * `You can choose to open the link in the desktop app or directly in the browser.`
      */
    ["com.cloud-notes.setting.appearance.open-in-app.hint"](): string;
    /**
      * `Ask me each time`
      */
    ["com.cloud-notes.setting.appearance.open-in-app.always-ask"](): string;
    /**
      * `Open links in desktop app`
      */
    ["com.cloud-notes.setting.appearance.open-in-app.open-in-desktop-app"](): string;
    /**
      * `Open links in browser`
      */
    ["com.cloud-notes.setting.appearance.open-in-app.open-in-web"](): string;
    /**
      * `Open Cloud Notes links`
      */
    ["com.cloud-notes.setting.appearance.open-in-app.title"](): string;
    /**
      * `Open this doc in Cloud Notes app`
      */
    ["com.cloud-notes.open-in-app.card.title"](): string;
    /**
      * `Open in app`
      */
    ["com.cloud-notes.open-in-app.card.button.open"](): string;
    /**
      * `Dismiss`
      */
    ["com.cloud-notes.open-in-app.card.button.dismiss"](): string;
    /**
      * `Remember choice`
      */
    ["com.cloud-notes.open-in-app.card.remember"](): string;
    /**
      * `Download desktop app`
      */
    ["com.cloud-notes.open-in-app.card.download"](): string;
    /**
      * `If enabled, it will automatically check for new versions at regular intervals.`
      */
    ["com.cloud-notes.settings.auto-check-description"](): string;
    /**
      * `If enabled, new versions will be automatically downloaded to the current device.`
      */
    ["com.cloud-notes.settings.auto-download-description"](): string;
    /**
      * `Editor`
      */
    ["com.cloud-notes.settings.editorSettings"](): string;
    /**
      * `Edgeless`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless"](): string;
    /**
      * `Connector`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter"](): string;
    /**
      * `Border style`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.border-style"](): string;
    /**
      * `Border thickness`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.border-thickness"](): string;
    /**
      * `Color`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.color"](): string;
    /**
      * `Connector shape`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.connector-shape"](): string;
    /**
      * `Curve`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.connector-shape.curve"](): string;
    /**
      * `Elbowed`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.connector-shape.elbowed"](): string;
    /**
      * `Straight`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.connector-shape.straight"](): string;
    /**
      * `End endpoint`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.end-endpoint"](): string;
    /**
      * `Start endpoint`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.connecter.start-endpoint"](): string;
    /**
      * `Custom`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.custom"](): string;
    /**
      * `Mind Map`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.mind-map"](): string;
    /**
      * `Layout`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.mind-map.layout"](): string;
    /**
      * `Left`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.mind-map.layout.left"](): string;
    /**
      * `Radial`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.mind-map.layout.radial"](): string;
    /**
      * `Right`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.mind-map.layout.right"](): string;
    /**
      * `Note`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note"](): string;
    /**
      * `Background`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.background"](): string;
    /**
      * `Border style`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.border"](): string;
    /**
      * `Border thickness`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.border-thickness"](): string;
    /**
      * `Dash`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.border.dash"](): string;
    /**
      * `None`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.border.none"](): string;
    /**
      * `Solid`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.border.solid"](): string;
    /**
      * `Corners`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.corners"](): string;
    /**
      * `Shadow style`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.note.shadow"](): string;
    /**
      * `Pen`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.pen"](): string;
    /**
      * `Color`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.pen.color"](): string;
    /**
      * `Thickness`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.pen.thickness"](): string;
    /**
      * `Shape`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape"](): string;
    /**
      * `Border color`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.border-color"](): string;
    /**
      * `Border style`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.border-style"](): string;
    /**
      * `Border thickness`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.border-thickness"](): string;
    /**
      * `Diamond`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.diamond"](): string;
    /**
      * `Ellipse`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.ellipse"](): string;
    /**
      * `Fill color`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.fill-color"](): string;
    /**
      * `Flow`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.flow"](): string;
    /**
      * `Font`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.font"](): string;
    /**
      * `Font size`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.font-size"](): string;
    /**
      * `Font style`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.font-style"](): string;
    /**
      * `List`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.list"](): string;
    /**
      * `Rounded Rectangle`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.rounded-rectangle"](): string;
    /**
      * `Square`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.square"](): string;
    /**
      * `Text alignment`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.text-alignment"](): string;
    /**
      * `Text color`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.text-color"](): string;
    /**
      * `Triangle`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.shape.triangle"](): string;
    /**
      * `Frame`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.frame"](): string;
    /**
      * `Background`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.frame.background"](): string;
    /**
      * `Style`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.style"](): string;
    /**
      * `General`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.style.general"](): string;
    /**
      * `Scribbled`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.style.scribbled"](): string;
    /**
      * `Text`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text"](): string;
    /**
      * `Alignment`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.alignment"](): string;
    /**
      * `Center`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.alignment.center"](): string;
    /**
      * `Left`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.alignment.left"](): string;
    /**
      * `Right`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.alignment.right"](): string;
    /**
      * `Text color`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.color"](): string;
    /**
      * `Font`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.font"](): string;
    /**
      * `Font family`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.font-family"](): string;
    /**
      * `Font size`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.font-size"](): string;
    /**
      * `Font style`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.font-style"](): string;
    /**
      * `Font weight`
      */
    ["com.cloud-notes.settings.editorSettings.edgeless.text.font-weight"](): string;
    /**
      * `General`
      */
    ["com.cloud-notes.settings.editorSettings.general"](): string;
    /**
      * `Enable the powerful AI assistant, Cloud Notes AI.`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.description"](): string;
    /**
      * `Disable AI and Reload`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.disable.confirm"](): string;
    /**
      * `Are you sure you want to disable AI? We value your productivity and our AI can enhance it. Please think again!`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.disable.description"](): string;
    /**
      * `Disable AI?`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.disable.title"](): string;
    /**
      * `Enable AI and Reload`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.enable.confirm"](): string;
    /**
      * `Do you want to enable AI? Our AI assistant is ready to enhance your productivity and provide smart assistance. Let's get started! We need reload page to make this change.`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.enable.description"](): string;
    /**
      * `Enable AI?`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.enable.title"](): string;
    /**
      * `Cloud Notes AI`
      */
    ["com.cloud-notes.settings.editorSettings.general.ai.title"](): string;
    /**
      * `Set a default programming language.`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-code-block.language.description"](): string;
    /**
      * `Code blocks default language`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-code-block.language.title"](): string;
    /**
      * `Show line numbers in all code blocks by default.`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-code-block.line-numbers.description"](): string;
    /**
      * `Show line numbers in code blocks`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-code-block.line-numbers.title"](): string;
    /**
      * `Encapsulate code snippets for better readability.`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-code-block.wrap.description"](): string;
    /**
      * `Wrap code in code blocks`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-code-block.wrap.title"](): string;
    /**
      * `Default mode for new doc.`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-new-doc.description"](): string;
    /**
      * `New doc default mode`
      */
    ["com.cloud-notes.settings.editorSettings.general.default-new-doc.title"](): string;
    /**
      * `Auto-title new docs with current date`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.title"](): string;
    /**
      * `Automatically title blank new docs with today's date.`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.description"](): string;
    /**
      * `New doc date format`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.format.title"](): string;
    /**
      * `Choose the date format used for automatic new doc titles.`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.format.description"](): string;
    /**
      * `DD-MM-YYYY`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.format.dd-mm-yyyy"](): string;
    /**
      * `MM-DD-YYYY`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.format.mm-dd-yyyy"](): string;
    /**
      * `YYYY-MM-DD`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.format.yyyy-mm-dd"](): string;
    /**
      * `Journal style (localized)`
      */
    ["com.cloud-notes.settings.editorSettings.general.auto-date-title.format.journal"](): string;
    /**
      * `Display add icon option`
      */
    ["com.cloud-notes.settings.editorSettings.general.add-icon-option.title"](): string;
    /**
      * `Show or hide the add icon option for docs without an icon.`
      */
    ["com.cloud-notes.settings.editorSettings.general.add-icon-option.description"](): string;
    /**
      * `Customize your text experience.`
      */
    ["com.cloud-notes.settings.editorSettings.general.font-family.custom.description"](): string;
    /**
      * `Custom font family`
      */
    ["com.cloud-notes.settings.editorSettings.general.font-family.custom.title"](): string;
    /**
      * `Choose your editor's font family.`
      */
    ["com.cloud-notes.settings.editorSettings.general.font-family.description"](): string;
    /**
      * `Font family`
      */
    ["com.cloud-notes.settings.editorSettings.general.font-family.title"](): string;
    /**
      * `Adjust the base font size for better readability.`
      */
    ["com.cloud-notes.settings.editorSettings.general.font-size.description"](): string;
    /**
      * `Font size`
      */
    ["com.cloud-notes.settings.editorSettings.general.font-size.title"](): string;
    /**
      * `Automatically detect and correct spelling errors.`
      */
    ["com.cloud-notes.settings.editorSettings.general.spell-check.description"](): string;
    /**
      * `Spell check`
      */
    ["com.cloud-notes.settings.editorSettings.general.spell-check.title"](): string;
    /**
      * `Page`
      */
    ["com.cloud-notes.settings.editorSettings.page"](): string;
    /**
      * `Middle click paste`
      */
    ["com.cloud-notes.settings.editorSettings.general.middle-click-paste.title"](): string;
    /**
      * `Enable default middle click paste behavior on Linux.`
      */
    ["com.cloud-notes.settings.editorSettings.general.middle-click-paste.description"](): string;
    /**
      * `Display bi-directional links on the doc.`
      */
    ["com.cloud-notes.settings.editorSettings.page.display-bi-link.description"](): string;
    /**
      * `Display bi-directional links`
      */
    ["com.cloud-notes.settings.editorSettings.page.display-bi-link.title"](): string;
    /**
      * `Display document information on the doc.`
      */
    ["com.cloud-notes.settings.editorSettings.page.display-doc-info.description"](): string;
    /**
      * `Display doc info`
      */
    ["com.cloud-notes.settings.editorSettings.page.display-doc-info.title"](): string;
    /**
      * `Maximise display of content within a page.`
      */
    ["com.cloud-notes.settings.editorSettings.page.full-width.description"](): string;
    /**
      * `Full width layout`
      */
    ["com.cloud-notes.settings.editorSettings.page.full-width.title"](): string;
    /**
      * `Default page width`
      */
    ["com.cloud-notes.settings.editorSettings.page.default-page-width.title"](): string;
    /**
      * `Set default width for new pages, individual pages can override.`
      */
    ["com.cloud-notes.settings.editorSettings.page.default-page-width.description"](): string;
    /**
      * `Standard`
      */
    ["com.cloud-notes.settings.editorSettings.page.default-page-width.standard"](): string;
    /**
      * `Full width`
      */
    ["com.cloud-notes.settings.editorSettings.page.default-page-width.full-width"](): string;
    /**
      * `Set edgeless default color scheme.`
      */
    ["com.cloud-notes.settings.editorSettings.page.edgeless-default-theme.description"](): string;
    /**
      * `Edgeless default theme`
      */
    ["com.cloud-notes.settings.editorSettings.page.edgeless-default-theme.title"](): string;
    /**
      * `Specified by current color mode`
      */
    ["com.cloud-notes.settings.editorSettings.page.edgeless-default-theme.specified"](): string;
    /**
      * `Scroll wheel zoom`
      */
    ["com.cloud-notes.settings.editorSettings.page.edgeless-scroll-wheel-zoom.title"](): string;
    /**
      * `Use the scroll wheel to zoom in and out.`
      */
    ["com.cloud-notes.settings.editorSettings.page.edgeless-scroll-wheel-zoom.description"](): string;
    /**
      * `Preferences`
      */
    ["com.cloud-notes.settings.editorSettings.preferences"](): string;
    /**
      * `You can export the entire preferences data for backup, and the exported data can be re-imported.`
      */
    ["com.cloud-notes.settings.editorSettings.preferences.export.description"](): string;
    /**
      * `Export Settings`
      */
    ["com.cloud-notes.settings.editorSettings.preferences.export.title"](): string;
    /**
      * `You can import previously exported preferences data for restoration.`
      */
    ["com.cloud-notes.settings.editorSettings.preferences.import.description"](): string;
    /**
      * `Import Settings`
      */
    ["com.cloud-notes.settings.editorSettings.preferences.import.title"](): string;
    /**
      * `Configure your own editor`
      */
    ["com.cloud-notes.settings.editorSettings.subtitle"](): string;
    /**
      * `Editor settings`
      */
    ["com.cloud-notes.settings.editorSettings.title"](): string;
    /**
      * `Ask me every time`
      */
    ["com.cloud-notes.settings.editorSettings.ask-me-every-time"](): string;
    /**
      * `Email`
      */
    ["com.cloud-notes.settings.email"](): string;
    /**
      * `Change email`
      */
    ["com.cloud-notes.settings.email.action"](): string;
    /**
      * `Change email`
      */
    ["com.cloud-notes.settings.email.action.change"](): string;
    /**
      * `Verify email`
      */
    ["com.cloud-notes.settings.email.action.verify"](): string;
    /**
      * `Enable Cloud Notes Sync to collaborate with others`
      */
    ["com.cloud-notes.settings.member-tooltip"](): string;
    /**
      * `Loading member list...`
      */
    ["com.cloud-notes.settings.member.loading"](): string;
    /**
      * `Noise background on the sidebar`
      */
    ["com.cloud-notes.settings.noise-style"](): string;
    /**
      * `Use background noise effect on the sidebar.`
      */
    ["com.cloud-notes.settings.noise-style-description"](): string;
    /**
      * `Password`
      */
    ["com.cloud-notes.settings.password"](): string;
    /**
      * `Change password`
      */
    ["com.cloud-notes.settings.password.action.change"](): string;
    /**
      * `Set password`
      */
    ["com.cloud-notes.settings.password.action.set"](): string;
    /**
      * `Set a password to sign in to your account`
      */
    ["com.cloud-notes.settings.password.message"](): string;
    /**
      * `My profile`
      */
    ["com.cloud-notes.settings.profile"](): string;
    /**
      * `Your account profile will be displayed to everyone.`
      */
    ["com.cloud-notes.settings.profile.message"](): string;
    /**
      * `Display name`
      */
    ["com.cloud-notes.settings.profile.name"](): string;
    /**
      * `Input account name`
      */
    ["com.cloud-notes.settings.profile.placeholder"](): string;
    /**
      * `Remove workspace`
      */
    ["com.cloud-notes.settings.remove-workspace"](): string;
    /**
      * `Remove workspace from this device and optionally delete all data.`
      */
    ["com.cloud-notes.settings.remove-workspace-description"](): string;
    /**
      * `Sign in / Sign up`
      */
    ["com.cloud-notes.settings.sign"](): string;
    /**
      * `Need more customization options? Tell us in the community.`
      */
    ["com.cloud-notes.settings.suggestion"](): string;
    /**
      * `Translucent UI on the sidebar`
      */
    ["com.cloud-notes.settings.translucent-style"](): string;
    /**
      * `Use transparency effect on the sidebar.`
      */
    ["com.cloud-notes.settings.translucent-style-description"](): string;
    /**
      * `Meetings`
      */
    ["com.cloud-notes.settings.meetings"](): string;
    /**
      * `Beyond Recording
    Your AI Meeting Assistant is Here`
      */
    ["com.cloud-notes.settings.meetings.setting.welcome"](): string;
    /**
      * `Native Audio Capture, No Bots Required - Direct from Your Mac to Meeting Intelligence.`
      */
    ["com.cloud-notes.settings.meetings.setting.prompt"](): string;
    /**
      * `Learn more`
      */
    ["com.cloud-notes.settings.meetings.setting.welcome.learn-more"](): string;
    /**
      * `Enable meeting notes`
      */
    ["com.cloud-notes.settings.meetings.enable.title"](): string;
    /**
      * `Meeting recording`
      */
    ["com.cloud-notes.settings.meetings.record.header"](): string;
    /**
      * `When meeting starts`
      */
    ["com.cloud-notes.settings.meetings.record.recording-mode"](): string;
    /**
      * `Choose the behavior when the meeting starts.`
      */
    ["com.cloud-notes.settings.meetings.record.recording-mode.description"](): string;
    /**
      * `Open saved recordings`
      */
    ["com.cloud-notes.settings.meetings.record.open-saved-file"](): string;
    /**
      * `Open the locally stored recording files.`
      */
    ["com.cloud-notes.settings.meetings.record.open-saved-file.description"](): string;
    /**
      * `Transcription with AI`
      */
    ["com.cloud-notes.settings.meetings.transcription.header"](): string;
    /**
      * `AI auto summary`
      */
    ["com.cloud-notes.settings.meetings.transcription.auto-summary"](): string;
    /**
      * `Automatically generate a summary of the meeting notes.`
      */
    ["com.cloud-notes.settings.meetings.transcription.auto-summary.description"](): string;
    /**
      * `AI auto todo list`
      */
    ["com.cloud-notes.settings.meetings.transcription.auto-todo"](): string;
    /**
      * `Automatically generate a todo list of the meeting notes.`
      */
    ["com.cloud-notes.settings.meetings.transcription.auto-todo.description"](): string;
    /**
      * `Privacy & Security`
      */
    ["com.cloud-notes.settings.meetings.privacy.header"](): string;
    /**
      * `Screen & System audio recording`
      */
    ["com.cloud-notes.settings.meetings.privacy.screen-system-audio-recording"](): string;
    /**
      * `The Meeting feature requires permission to be used.`
      */
    ["com.cloud-notes.settings.meetings.privacy.screen-system-audio-recording.description"](): string;
    /**
      * `Click to allow`
      */
    ["com.cloud-notes.settings.meetings.privacy.screen-system-audio-recording.permission-setting"](): string;
    /**
      * `Microphone`
      */
    ["com.cloud-notes.settings.meetings.privacy.microphone"](): string;
    /**
      * `The Meeting feature requires permission to be used.`
      */
    ["com.cloud-notes.settings.meetings.privacy.microphone.description"](): string;
    /**
      * `Click to allow`
      */
    ["com.cloud-notes.settings.meetings.privacy.microphone.permission-setting"](): string;
    /**
      * `Permission issues`
      */
    ["com.cloud-notes.settings.meetings.privacy.issues"](): string;
    /**
      * `Permissions are granted but the status isn't updated? Restart the app to refresh permissions.`
      */
    ["com.cloud-notes.settings.meetings.privacy.issues.description"](): string;
    /**
      * `Restart App`
      */
    ["com.cloud-notes.settings.meetings.privacy.issues.restart"](): string;
    /**
      * `Do nothing`
      */
    ["com.cloud-notes.settings.meetings.record.recording-mode.none"](): string;
    /**
      * `Auto start recording`
      */
    ["com.cloud-notes.settings.meetings.record.recording-mode.auto-start"](): string;
    /**
      * `Show a recording prompt`
      */
    ["com.cloud-notes.settings.meetings.record.recording-mode.prompt"](): string;
    /**
      * `Screen & System Audio Recording`
      */
    ["com.cloud-notes.settings.meetings.record.permission-modal.title"](): string;
    /**
      * `Cloud Notes will generate meeting notes by recording your meetings. Authorization to "Screen & System Audio Recording" is necessary.`
      */
    ["com.cloud-notes.settings.meetings.record.permission-modal.description"](): string;
    /**
      * `Save meeting's recording block to`
      */
    ["com.cloud-notes.settings.meetings.record.save-mode"](): string;
    /**
      * `Open System Settings`
      */
    ["com.cloud-notes.settings.meetings.record.permission-modal.open-setting"](): string;
    /**
      * `Workspace`
      */
    ["com.cloud-notes.settings.workspace"](): string;
    /**
      * `You can view current workspace's information here.`
      */
    ["com.cloud-notes.settings.workspace.description"](): string;
    /**
      * `AI BYOK (Beta)`
      */
    ["com.cloud-notes.settings.workspace.byok.title"](): string;
    /**
      * `Loading provider keys.`
      */
    ["com.cloud-notes.settings.workspace.byok.loading"](): string;
    /**
      * `Use your own provider keys for this workspace.`
      */
    ["com.cloud-notes.settings.workspace.byok.subtitle"](): string;
    /**
      * `Use workspace provider keys before Cloud Notes AI plan routes.`
      */
    ["com.cloud-notes.settings.workspace.byok.header"](): string;
    /**
      * `BYOK requires Pro, Team, or Believer`
      */
    ["com.cloud-notes.settings.workspace.byok.locked.title"](): string;
    /**
      * `Upgrade this workspace to add provider keys and route Cloud Notes AI through your own OpenAI, Anthropic, Gemini, or FAL account.`
      */
    ["com.cloud-notes.settings.workspace.byok.locked.description"](): string;
    /**
      * `Provider keys`
      */
    ["com.cloud-notes.settings.workspace.byok.keys.title"](): string;
    /**
      * `List order controls fallback within each storage group.`
      */
    ["com.cloud-notes.settings.workspace.byok.keys.description"](): string;
    /**
      * `No provider keys`
      */
    ["com.cloud-notes.settings.workspace.byok.empty.title"](): string;
    /**
      * `Add a key to create the first route for this workspace. Provider rows are not shown until a key exists.`
      */
    ["com.cloud-notes.settings.workspace.byok.empty.description"](): string;
    /**
      * `Local`
      */
    ["com.cloud-notes.settings.workspace.byok.storage.local"](): string;
    /**
      * `Server`
      */
    ["com.cloud-notes.settings.workspace.byok.storage.server"](): string;
    /**
      * `Available to workspace members.`
      */
    ["com.cloud-notes.settings.workspace.byok.storage.server.description"](): string;
    /**
      * `Kept in this desktop device’s secure storage.`
      */
    ["com.cloud-notes.settings.workspace.byok.storage.local.description"](): string;
    /**
      * `Available in the Cloud Notes desktop app.`
      */
    ["com.cloud-notes.settings.workspace.byok.storage.local.desktop-only"](): string;
    /**
      * `Secure local storage is not available on this device.`
      */
    ["com.cloud-notes.settings.workspace.byok.storage.local.unavailable"](): string;
    /**
      * `Testing sends this key to the workspace server for this request only; it is not stored there.`
      */
    ["com.cloud-notes.settings.workspace.byok.storage.local.test-disclosure"](): string;
    /**
      * `Disabled after failure`
      */
    ["com.cloud-notes.settings.workspace.byok.status.disabled-after-failure"](): string;
    /**
      * `Key verified`
      */
    ["com.cloud-notes.settings.workspace.byok.status.key-verified"](): string;
    /**
      * `Text`
      */
    ["com.cloud-notes.settings.workspace.byok.capability.text"](): string;
    /**
      * `Image input`
      */
    ["com.cloud-notes.settings.workspace.byok.capability.image-input"](): string;
    /**
      * `Actions`
      */
    ["com.cloud-notes.settings.workspace.byok.capability.actions"](): string;
    /**
      * `Image generate`
      */
    ["com.cloud-notes.settings.workspace.byok.capability.image-generate"](): string;
    /**
      * `Transcript`
      */
    ["com.cloud-notes.settings.workspace.byok.capability.transcript"](): string;
    /**
      * `Indexing`
      */
    ["com.cloud-notes.settings.workspace.byok.capability.indexing"](): string;
    /**
      * `failed {{date}}`
      */
    ["com.cloud-notes.settings.workspace.byok.row.activity.failed"](options: {
        readonly date: string;
    }): string;
    /**
      * `not used yet`
      */
    ["com.cloud-notes.settings.workspace.byok.row.activity.unused"](): string;
    /**
      * `Feature coverage`
      */
    ["com.cloud-notes.settings.workspace.byok.coverage.title"](): string;
    /**
      * `Writing and chat`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.chat.title"](): string;
    /**
      * `Covered when an OpenAI, Anthropic, or Gemini BYOK key exists.`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.chat.fallback"](): string;
    /**
      * `Actions and structured output`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.action.title"](): string;
    /**
      * `Covered when an OpenAI or Gemini BYOK key exists.`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.action.fallback"](): string;
    /**
      * `Image generation`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.image.title"](): string;
    /**
      * `Covered when an OpenAI, Gemini, or FAL BYOK key exists.`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.image.fallback"](): string;
    /**
      * `Transcript`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.transcript.title"](): string;
    /**
      * `Covered when a server Gemini BYOK key exists.`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.transcript.fallback"](): string;
    /**
      * `Workspace indexing`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.workspace-indexing.title"](): string;
    /**
      * `Covered when a server Gemini BYOK key exists.`
      */
    ["com.cloud-notes.settings.workspace.byok.feature.workspace-indexing.fallback"](): string;
    /**
      * `Transcript and workspace indexing require a server Gemini BYOK key or Cloud Notes AI plan fallback.`
      */
    ["com.cloud-notes.settings.workspace.byok.warning.transcript"](): string;
    /**
      * `Workspace indexing requires a server Gemini BYOK key or Cloud Notes AI plan fallback.`
      */
    ["com.cloud-notes.settings.workspace.byok.warning.workspace-indexing"](): string;
    /**
      * `BYOK usage`
      */
    ["com.cloud-notes.settings.workspace.byok.usage.title"](): string;
    /**
      * `Last 30 days`
      */
    ["com.cloud-notes.settings.workspace.byok.usage.period"](): string;
    /**
      * `{{count}} tokens`
      */
    ["com.cloud-notes.settings.workspace.byok.usage.tokens"](options: {
        readonly count: string;
    }): string;
    /**
      * `Connect AI provider`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.connect-title"](): string;
    /**
      * `Manage provider`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.manage-title"](): string;
    /**
      * `Choose where the key is stored, then select the models Cloud Notes may use.`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.connect-description"](): string;
    /**
      * `Add models`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.add-model-title"](): string;
    /**
      * `Add custom model`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.add-custom-model-title"](): string;
    /**
      * `Edit model`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.edit-model-title"](): string;
    /**
      * `Choose one or more models this key may use.`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.catalog-model-description"](): string;
    /**
      * `Enter the endpoint model ID and choose its uses.`
      */
    ["com.cloud-notes.settings.workspace.byok.modal.custom-model-description"](): string;
    /**
      * `Connection`
      */
    ["com.cloud-notes.settings.workspace.byok.section.connection"](): string;
    /**
      * `Models`
      */
    ["com.cloud-notes.settings.workspace.byok.section.models"](): string;
    /**
      * `Advanced details`
      */
    ["com.cloud-notes.settings.workspace.byok.section.advanced"](): string;
    /**
      * `Models this key may use.`
      */
    ["com.cloud-notes.settings.workspace.byok.models.description.selected"](): string;
    /**
      * `Among compatible models, enabled models are tried from top to bottom.`
      */
    ["com.cloud-notes.settings.workspace.byok.models.description.order"](): string;
    /**
      * `No models added yet.`
      */
    ["com.cloud-notes.settings.workspace.byok.models.empty"](): string;
    /**
      * `All available models have been added.`
      */
    ["com.cloud-notes.settings.workspace.byok.models.all-added"](): string;
    /**
      * `No matching models.`
      */
    ["com.cloud-notes.settings.workspace.byok.models.no-search-results"](): string;
    /**
      * `Provider`
      */
    ["com.cloud-notes.settings.workspace.byok.field.provider"](): string;
    /**
      * `Key name`
      */
    ["com.cloud-notes.settings.workspace.byok.field.key-name"](): string;
    /**
      * `Description`
      */
    ["com.cloud-notes.settings.workspace.byok.field.description"](): string;
    /**
      * `Provider enabled`
      */
    ["com.cloud-notes.settings.workspace.byok.field.provider-enabled"](): string;
    /**
      * `API key`
      */
    ["com.cloud-notes.settings.workspace.byok.field.api-key"](): string;
    /**
      * `Endpoint`
      */
    ["com.cloud-notes.settings.workspace.byok.field.endpoint"](): string;
    /**
      * `API dialect`
      */
    ["com.cloud-notes.settings.workspace.byok.field.dialect"](): string;
    /**
      * `Model ID`
      */
    ["com.cloud-notes.settings.workspace.byok.field.model-id"](): string;
    /**
      * `Custom endpoints are disabled by the server administrator. In Self-hosted Admin, enable copilot.byok.allowCustomEndpoint.`
      */
    ["com.cloud-notes.settings.workspace.byok.endpoint.custom-disabled"](): string;
    /**
      * `Private network endpoints additionally require the server administrator to enable copilot.byok.allowPrivateEndpoint.`
      */
    ["com.cloud-notes.settings.workspace.byok.endpoint.private-disabled"](): string;
    /**
      * `Leave blank to keep the current key`
      */
    ["com.cloud-notes.settings.workspace.byok.placeholder.keep-current-key"](): string;
    /**
      * `Model ID`
      */
    ["com.cloud-notes.settings.workspace.byok.placeholder.model-id"](): string;
    /**
      * `Search models…`
      */
    ["com.cloud-notes.settings.workspace.byok.placeholder.search-models"](): string;
    /**
      * `Select an API dialect`
      */
    ["com.cloud-notes.settings.workspace.byok.placeholder.dialect"](): string;
    /**
      * `Use a custom API-compatible endpoint`
      */
    ["com.cloud-notes.settings.workspace.byok.endpoint.use-custom"](): string;
    /**
      * `Responses API`
      */
    ["com.cloud-notes.settings.workspace.byok.dialect.responses"](): string;
    /**
      * `Chat Completions API`
      */
    ["com.cloud-notes.settings.workspace.byok.dialect.chat-completions"](): string;
    /**
      * `Include an image generation request when testing (provider charges may apply)`
      */
    ["com.cloud-notes.settings.workspace.byok.probe.include-image"](): string;
    /**
      * `Connection verified`
      */
    ["com.cloud-notes.settings.workspace.byok.probe.verified"](): string;
    /**
      * `Connection failed`
      */
    ["com.cloud-notes.settings.workspace.byok.probe.failed"](): string;
    /**
      * `Recommended`
      */
    ["com.cloud-notes.settings.workspace.byok.model.recommended"](): string;
    /**
      * `This model has already been added.`
      */
    ["com.cloud-notes.settings.workspace.byok.model.duplicate-id"](): string;
    /**
      * `Not tested`
      */
    ["com.cloud-notes.settings.workspace.byok.model.status.not-tested"](): string;
    /**
      * `Verified`
      */
    ["com.cloud-notes.settings.workspace.byok.model.status.verified"](): string;
    /**
      * `Failed`
      */
    ["com.cloud-notes.settings.workspace.byok.model.status.failed"](): string;
    /**
      * `Partially verified · {{verified}} of {{total}}`
      */
    ["com.cloud-notes.settings.workspace.byok.model.status.partially-verified"](options: Readonly<{
        verified: string;
        total: string;
    }>): string;
    /**
      * `Disabled`
      */
    ["com.cloud-notes.settings.workspace.byok.model.status.disabled"](): string;
    /**
      * `Use this model for`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use-this-for"](): string;
    /**
      * `Chat & writing`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.chat"](): string;
    /**
      * `Actions`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.actions"](): string;
    /**
      * `Structured output`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.structured"](): string;
    /**
      * `Image understanding`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.vision"](): string;
    /**
      * `Image generation`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.image"](): string;
    /**
      * `Transcription`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.transcript"](): string;
    /**
      * `Workspace indexing`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.embedding"](): string;
    /**
      * `Search reranking`
      */
    ["com.cloud-notes.settings.workspace.byok.model.use.rerank"](): string;
    /**
      * `Add key`
      */
    ["com.cloud-notes.settings.workspace.byok.action.add-key"](): string;
    /**
      * `Test connection`
      */
    ["com.cloud-notes.settings.workspace.byok.action.test-connection"](): string;
    /**
      * `Test`
      */
    ["com.cloud-notes.settings.workspace.byok.action.test"](): string;
    /**
      * `Testing…`
      */
    ["com.cloud-notes.settings.workspace.byok.action.testing"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.settings.workspace.byok.action.cancel"](): string;
    /**
      * `Connect`
      */
    ["com.cloud-notes.settings.workspace.byok.action.connect"](): string;
    /**
      * `Connecting…`
      */
    ["com.cloud-notes.settings.workspace.byok.action.connecting"](): string;
    /**
      * `Save changes`
      */
    ["com.cloud-notes.settings.workspace.byok.action.save-changes"](): string;
    /**
      * `Add model`
      */
    ["com.cloud-notes.settings.workspace.byok.action.add-model"](): string;
    /**
      * `Add {{count}} models`
      */
    ["com.cloud-notes.settings.workspace.byok.action.add-selected-models"](options: {
        readonly count: string;
    }): string;
    /**
      * `Save model`
      */
    ["com.cloud-notes.settings.workspace.byok.action.save-model"](): string;
    /**
      * `Enable {{model}}`
      */
    ["com.cloud-notes.settings.workspace.byok.action.enable-model"](options: {
        readonly model: string;
    }): string;
    /**
      * `Disable {{model}}`
      */
    ["com.cloud-notes.settings.workspace.byok.action.disable-model"](options: {
        readonly model: string;
    }): string;
    /**
      * `Options for {{model}}`
      */
    ["com.cloud-notes.settings.workspace.byok.action.model-options"](options: {
        readonly model: string;
    }): string;
    /**
      * `Move up`
      */
    ["com.cloud-notes.settings.workspace.byok.action.move-up"](): string;
    /**
      * `Move down`
      */
    ["com.cloud-notes.settings.workspace.byok.action.move-down"](): string;
    /**
      * `Remove`
      */
    ["com.cloud-notes.settings.workspace.byok.action.remove"](): string;
    /**
      * `Clear all BYOK keys`
      */
    ["com.cloud-notes.settings.workspace.byok.action.clear-all"](): string;
    /**
      * `Reorder`
      */
    ["com.cloud-notes.settings.workspace.byok.action.reorder"](): string;
    /**
      * `Edit`
      */
    ["com.cloud-notes.settings.workspace.byok.action.edit"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.settings.workspace.byok.action.delete"](): string;
    /**
      * `Key test failed`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.test-failed.title"](): string;
    /**
      * `Local key not saved`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.local-save-failed.title"](): string;
    /**
      * `Secure device storage is unavailable.`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.local-save-failed.message"](): string;
    /**
      * `BYOK settings not loaded`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.load-failed.title"](): string;
    /**
      * `BYOK key not saved`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.save-failed.title"](): string;
    /**
      * `BYOK key not deleted`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.delete-failed.title"](): string;
    /**
      * `BYOK keys not reordered`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.reorder-failed.title"](): string;
    /**
      * `BYOK keys not cleared`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.clear-failed.title"](): string;
    /**
      * `BYOK settings changed`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.reload-required.title"](): string;
    /**
      * `Reload the settings and try again.`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.reload-required.message"](): string;
    /**
      * `Please try again.`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.operation-failed.message"](): string;
    /**
      * `Cannot reorder across storage`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.cross-storage-reorder.title"](): string;
    /**
      * `Local keys and server keys keep separate fallback order.`
      */
    ["com.cloud-notes.settings.workspace.byok.notify.cross-storage-reorder.message"](): string;
    /**
      * `Experimental features`
      */
    ["com.cloud-notes.settings.workspace.experimental-features"](): string;
    /**
      * `Get started`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.get-started"](): string;
    /**
      * `Experimental features`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.header.plugins"](): string;
    /**
      * `Some features available for early access`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.header.subtitle"](): string;
    /**
      * `I am aware of the risks, and I am willing to continue to use it.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.prompt-disclaimer"](): string;
    /**
      * `Do you want to use the plugin system that is in an experimental stage?`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.prompt-header"](): string;
    /**
      * `You are about to enable an experimental feature. This feature is still in development and may contain errors or behave unpredictably. Please proceed with caution and at your own risk.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.prompt-warning"](): string;
    /**
      * `WARNING MESSAGE`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.prompt-warning-title"](): string;
    /**
      * `Enable AI`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai.name"](): string;
    /**
      * `Enable or disable ALL AI features.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai.description"](): string;
    /**
      * `Enable AI Network Search`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-network-search.name"](): string;
    /**
      * `Enable or disable AI Network Search feature.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-network-search.description"](): string;
    /**
      * `Enable AI Model Switch`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-model-switch.name"](): string;
    /**
      * `Enable or disable AI model switch feature.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-model-switch.description"](): string;
    /**
      * `Enable AI Playground`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-playground.name"](): string;
    /**
      * `Enable or disable AI playground feature.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-playground.description"](): string;
    /**
      * `Database Full Width`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-database-full-width.name"](): string;
    /**
      * `The database will be displayed in full-width mode.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-database-full-width.description"](): string;
    /**
      * `Database Attachment Note`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-database-attachment-note.name"](): string;
    /**
      * `Allows adding notes to database attachments.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-database-attachment-note.description"](): string;
    /**
      * `Todo Block Query`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-block-query.name"](): string;
    /**
      * `Enables querying of todo blocks.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-block-query.description"](): string;
    /**
      * `Synced Doc Block`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-synced-doc-block.name"](): string;
    /**
      * `Enables syncing of doc blocks.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-synced-doc-block.description"](): string;
    /**
      * `Edgeless Text`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-edgeless-text.name"](): string;
    /**
      * `Enables edgeless text blocks.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-edgeless-text.description"](): string;
    /**
      * `Color Picker`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-color-picker.name"](): string;
    /**
      * `Enables color picker blocks.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-color-picker.description"](): string;
    /**
      * `AI Chat Block`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-chat-block.name"](): string;
    /**
      * `Enables AI chat blocks.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-chat-block.description"](): string;
    /**
      * `AI Onboarding`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-onboarding.name"](): string;
    /**
      * `Enables AI onboarding.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-onboarding.description"](): string;
    /**
      * `Mind Map Import`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mind-map-import.name"](): string;
    /**
      * `Enables mind map import.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mind-map-import.description"](): string;
    /**
      * `Block Meta`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-block-meta.name"](): string;
    /**
      * `Once enabled, all blocks will have created time, updated time, created by and updated by.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-block-meta.description"](): string;
    /**
      * `Callout`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-callout.name"](): string;
    /**
      * `Let your words stand out. This also include the callout in the transcription block.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-callout.description"](): string;
    /**
      * `Embed Iframe Block`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-embed-iframe-block.name"](): string;
    /**
      * `Enables Embed Iframe Block.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-embed-iframe-block.description"](): string;
    /**
      * `Emoji Folder Icon`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-emoji-folder-icon.name"](): string;
    /**
      * `Once enabled, you can use an emoji as the folder icon. When the first character of the folder name is an emoji, it will be extracted and used as its icon.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-emoji-folder-icon.description"](): string;
    /**
      * `Emoji Doc Icon`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-emoji-doc-icon.name"](): string;
    /**
      * `Once enabled, you can use an emoji as the doc icon. When the first character of the doc name is an emoji, it will be extracted and used as its icon.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-emoji-doc-icon.description"](): string;
    /**
      * `Editor Settings`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-editor-settings.name"](): string;
    /**
      * `Enables editor settings.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-editor-settings.description"](): string;
    /**
      * `Theme Editor`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-theme-editor.name"](): string;
    /**
      * `Enables theme editor.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-theme-editor.description"](): string;
    /**
      * `Allow create local workspace`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-local-workspace.name"](): string;
    /**
      * `Allow create local workspace`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-local-workspace.description"](): string;
    /**
      * `Advanced block visibility control`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-advanced-block-visibility.name"](): string;
    /**
      * `To provide detailed control over which edgeless blocks are visible in page mode.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-advanced-block-visibility.description"](): string;
    /**
      * `Mobile Keyboard Toolbar`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mobile-keyboard-toolbar.name"](): string;
    /**
      * `Enables the mobile keyboard toolbar.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mobile-keyboard-toolbar.description"](): string;
    /**
      * `Mobile Linked Doc Widget`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mobile-linked-doc-menu.name"](): string;
    /**
      * `Enables the mobile linked doc menu.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mobile-linked-doc-menu.description"](): string;
    /**
      * `Enable Snapshot Import Export`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-snapshot-import-export.name"](): string;
    /**
      * `Once enabled, users can import and export blocksuite snapshots.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-snapshot-import-export.description"](): string;
    /**
      * `Enable Edgeless Editing`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mobile-edgeless-editing.name"](): string;
    /**
      * `Once enabled, users can edit edgeless canvas.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-mobile-edgeless-editing.description"](): string;
    /**
      * `PDF embed preview`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-pdf-embed-preview.name"](): string;
    /**
      * `Once enabled, you can preview PDF in embed view.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-pdf-embed-preview.description"](): string;
    /**
      * `Audio block`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-audio-block.name"](): string;
    /**
      * `Audio block allows you to play audio files globally and add notes to them.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-audio-block.description"](): string;
    /**
      * `Meetings`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-meetings.name"](): string;
    /**
      * `Meetings allows you to record and transcribe meetings. Don't forget to enable it in Cloud Notes settings.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-meetings.description"](): string;
    /**
      * `Editor RTL`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-editor-rtl.name"](): string;
    /**
      * `Once enabled, the editor will be displayed in RTL mode.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-editor-rtl.description"](): string;
    /**
      * `Edgeless scribbled style`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-edgeless-scribbled-style.name"](): string;
    /**
      * `Once enabled, you can use scribbled style in edgeless mode.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-edgeless-scribbled-style.description"](): string;
    /**
      * `Database block table view virtual scroll`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-table-virtual-scroll.name"](): string;
    /**
      * `Once enabled, switch table view to virtual scroll mode in Database Block.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-table-virtual-scroll.description"](): string;
    /**
      * `Code block HTML preview`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-code-block-html-preview.name"](): string;
    /**
      * `Once enabled, you can preview HTML in code block.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-code-block-html-preview.description"](): string;
    /**
      * `Adapter Panel`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-adapter-panel.name"](): string;
    /**
      * `Once enabled, you can preview adapter export content in the right side bar.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-adapter-panel.description"](): string;
    /**
      * `Send detailed object information to AI`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-send-detailed-object.name"](): string;
    /**
      * `When toggled off, every time you choose "Continue with AI", AI only got a screenshot.`
      */
    ["com.cloud-notes.settings.workspace.experimental-features.enable-ai-send-detailed-object.description"](): string;
    /**
      * `Only an owner can edit the workspace avatar and name. Changes will be shown for everyone.`
      */
    ["com.cloud-notes.settings.workspace.not-owner"](): string;
    /**
      * `Preference`
      */
    ["com.cloud-notes.settings.workspace.preferences"](): string;
    /**
      * `Team's Billing`
      */
    ["com.cloud-notes.settings.workspace.billing"](): string;
    /**
      * `Team Workspace`
      */
    ["com.cloud-notes.settings.workspace.billing.team-workspace"](): string;
    /**
      * `Your workspace is in a free trail period.`
      */
    ["com.cloud-notes.settings.workspace.billing.team-workspace.description.free-trail"](): string;
    /**
      * `Your workspace is billed annually.`
      */
    ["com.cloud-notes.settings.workspace.billing.team-workspace.description.billed.annually"](): string;
    /**
      * `Your workspace is billed monthly.`
      */
    ["com.cloud-notes.settings.workspace.billing.team-workspace.description.billed.monthly"](): string;
    /**
      * `Your subscription will end on {{date}}`
      */
    ["com.cloud-notes.settings.workspace.billing.team-workspace.not-renewed"](options: {
        readonly date: string;
    }): string;
    /**
      * `Next billing date: {{date}}`
      */
    ["com.cloud-notes.settings.workspace.billing.team-workspace.next-billing-date"](options: {
        readonly date: string;
    }): string;
    /**
      * `Cancel Plan`
      */
    ["com.cloud-notes.settings.workspace.billing.team-workspace.cancel-plan"](): string;
    /**
      * `License`
      */
    ["com.cloud-notes.settings.workspace.license"](): string;
    /**
      * `Manage license information and invoices for the self host team workspace.`
      */
    ["com.cloud-notes.settings.workspace.license.description"](): string;
    /**
      * `Get teams plan for your self hosted workspace.`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.title"](): string;
    /**
      * `Need more seats? Best for scalable teams.`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.subtitle"](): string;
    /**
      * `Everything in Self Hosted FOSS`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.g1"](): string;
    /**
      * `{{initialQuota}} initial storage + {{quotaPerSeat}} per seat`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.g2"](options: Readonly<{
        initialQuota: string;
        quotaPerSeat: string;
    }>): string;
    /**
      * `{{quota}} of maximum file size`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.g3"](options: {
        readonly quota: string;
    }): string;
    /**
      * `Unlimited team members (10+ seats)`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.g4"](): string;
    /**
      * `Multiple admin roles`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.g5"](): string;
    /**
      * `Priority customer support`
      */
    ["com.cloud-notes.settings.workspace.license.benefit.team.g6"](): string;
    /**
      * `Learn more`
      */
    ["com.cloud-notes.settings.workspace.license.learn-more"](): string;
    /**
      * `Selfhosted workspace`
      */
    ["com.cloud-notes.settings.workspace.license.self-host"](): string;
    /**
      * `Self-host Team Workspace`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team"](): string;
    /**
      * `This license will expire on {{expirationDate}}, with {{leftDays}} days remaining.`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.team.description"](options: Readonly<{
        expirationDate: string;
        leftDays: string;
    }>): string;
    /**
      * `Basic version: {{memberCount}} seats. For more, purchase or use activation key.`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.free.description"](options: {
        readonly memberCount: string;
    }): string;
    /**
      * `Seats`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.seats"](): string;
    /**
      * `Use purchased key`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.use-purchased-key"](): string;
    /**
      * `Upload license file`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file"](): string;
    /**
      * `Upload license file locally and verify the license information.`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.description"](): string;
    /**
      * `To purchase a license:`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.tips.title"](): string;
    /**
      * `Workspace id`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.tips.workspace-id"](): string;
    /**
      * `Click to upload`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.click-to-upload"](): string;
    /**
      * `Activation failed`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.failed"](): string;
    /**
      * `Activation Success`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.success.title"](): string;
    /**
      * `License has been successfully applied`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.success.description"](): string;
    /**
      * `If you encounter any issues, contact support@toeverything.info.`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.help"](): string;
    /**
      * `Deactivate`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.deactivate-license"](): string;
    /**
      * `Replace your license file`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.replace-license.title"](): string;
    /**
      * `Replace the existing license file with a new, updated version.`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.replace-license.description"](): string;
    /**
      * `Upload license file`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.replace-license.upload"](): string;
    /**
      * `Buy more seat`
      */
    ["com.cloud-notes.settings.workspace.license.buy-more-seat"](): string;
    /**
      * `Activate License`
      */
    ["com.cloud-notes.settings.workspace.license.activate-modal.title"](): string;
    /**
      * `Enter license key to activate this self host workspace.`
      */
    ["com.cloud-notes.settings.workspace.license.activate-modal.description"](): string;
    /**
      * `License activated successfully.`
      */
    ["com.cloud-notes.settings.workspace.license.activate-success"](): string;
    /**
      * `Confirm deactivation?`
      */
    ["com.cloud-notes.settings.workspace.license.deactivate-modal.title"](): string;
    /**
      * `After deactivation, you will need to upload a new license to continue using team feature`
      */
    ["com.cloud-notes.settings.workspace.license.deactivate-modal.description-license"](): string;
    /**
      * `Manage Payment`
      */
    ["com.cloud-notes.settings.workspace.license.deactivate-modal.manage-payment"](): string;
    /**
      * `License deactivated successfully.`
      */
    ["com.cloud-notes.settings.workspace.license.deactivate-success"](): string;
    /**
      * `Local`
      */
    ["com.cloud-notes.settings.workspace.state.local"](): string;
    /**
      * `Sync with Cloud Notes`
      */
    ["com.cloud-notes.settings.workspace.state.sync-cloud-notes-cloud"](): string;
    /**
      * `Self-Hosted Server`
      */
    ["com.cloud-notes.settings.workspace.state.self-hosted"](): string;
    /**
      * `Joined Workspace`
      */
    ["com.cloud-notes.settings.workspace.state.joined"](): string;
    /**
      * `Available Offline`
      */
    ["com.cloud-notes.settings.workspace.state.available-offline"](): string;
    /**
      * `Published to Web`
      */
    ["com.cloud-notes.settings.workspace.state.published"](): string;
    /**
      * `Team Workspace`
      */
    ["com.cloud-notes.settings.workspace.state.team"](): string;
    /**
      * `Properties`
      */
    ["com.cloud-notes.settings.workspace.properties"](): string;
    /**
      * `Add property`
      */
    ["com.cloud-notes.settings.workspace.properties.add_property"](): string;
    /**
      * `All`
      */
    ["com.cloud-notes.settings.workspace.properties.all"](): string;
    /**
      * `Delete property`
      */
    ["com.cloud-notes.settings.workspace.properties.delete-property"](): string;
    /**
      * `Edit property`
      */
    ["com.cloud-notes.settings.workspace.properties.edit-property"](): string;
    /**
      * `General properties`
      */
    ["com.cloud-notes.settings.workspace.properties.general-properties"](): string;
    /**
      * `Properties`
      */
    ["com.cloud-notes.settings.workspace.properties.header.title"](): string;
    /**
      * `In use`
      */
    ["com.cloud-notes.settings.workspace.properties.in-use"](): string;
    /**
      * `Readonly properties`
      */
    ["com.cloud-notes.settings.workspace.properties.readonly-properties"](): string;
    /**
      * `Required properties`
      */
    ["com.cloud-notes.settings.workspace.properties.required-properties"](): string;
    /**
      * `Set as required property`
      */
    ["com.cloud-notes.settings.workspace.properties.set-as-required"](): string;
    /**
      * `Unused`
      */
    ["com.cloud-notes.settings.workspace.properties.unused"](): string;
    /**
      * `You can view current workspace's storage and files here.`
      */
    ["com.cloud-notes.settings.workspace.storage.subtitle"](): string;
    /**
      * `Enable Cloud Notes Sync to publish this workspace`
      */
    ["com.cloud-notes.settings.workspace.publish-tooltip"](): string;
    /**
      * `Sharing`
      */
    ["com.cloud-notes.settings.workspace.sharing.title"](): string;
    /**
      * `Allow URL unfurling by Slack & other social apps, even if a doc is only accessible by workspace members.`
      */
    ["com.cloud-notes.settings.workspace.sharing.url-preview.description"](): string;
    /**
      * `Always enable url preview`
      */
    ["com.cloud-notes.settings.workspace.sharing.url-preview.title"](): string;
    /**
      * `Control whether pages in this workspace can be shared publicly. Turn off to block new shares and external access for existing shares.`
      */
    ["com.cloud-notes.settings.workspace.sharing.workspace-sharing.description"](): string;
    /**
      * `Allow workspace page sharing`
      */
    ["com.cloud-notes.settings.workspace.sharing.workspace-sharing.title"](): string;
    /**
      * `Cloud Notes AI`
      */
    ["com.cloud-notes.settings.workspace.cloud-notes-ai.title"](): string;
    /**
      * `Allow Cloud Notes AI Assistant`
      */
    ["com.cloud-notes.settings.workspace.cloud-notes-ai.label"](): string;
    /**
      * `Allow workspace members to use Cloud Notes AI features. This setting doesn't affect billing. Workspace members use Cloud Notes AI through their personal accounts.`
      */
    ["com.cloud-notes.settings.workspace.cloud-notes-ai.description"](): string;
    /**
      * `Archived workspaces`
      */
    ["com.cloud-notes.settings.workspace.backup"](): string;
    /**
      * `Manage archived local workspace files`
      */
    ["com.cloud-notes.settings.workspace.backup.subtitle"](): string;
    /**
      * `No archived workspace files found`
      */
    ["com.cloud-notes.settings.workspace.backup.empty"](): string;
    /**
      * `Delete archived workspace`
      */
    ["com.cloud-notes.settings.workspace.backup.delete"](): string;
    /**
      * `Are you sure you want to delete this workspace. This action cannot be undone. Make sure you no longer need them before proceeding.`
      */
    ["com.cloud-notes.settings.workspace.backup.delete.warning"](): string;
    /**
      * `Workspace backup deleted successfully`
      */
    ["com.cloud-notes.settings.workspace.backup.delete.success"](): string;
    /**
      * `Workspace enabled successfully`
      */
    ["com.cloud-notes.settings.workspace.backup.import.success"](): string;
    /**
      * `Enable local workspace`
      */
    ["com.cloud-notes.settings.workspace.backup.import"](): string;
    /**
      * `Open`
      */
    ["com.cloud-notes.settings.workspace.backup.import.success.action"](): string;
    /**
      * `Deleted on {{date}} at {{time}}`
      */
    ["com.cloud-notes.settings.workspace.backup.delete-at"](options: Readonly<{
        date: string;
        time: string;
    }>): string;
    /**
      * `Indexer & Embedding`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.title"](): string;
    /**
      * `Manage Cloud Notes indexing and Cloud Notes AI Embedding for local content processing`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.description"](): string;
    /**
      * `Embedding`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.title"](): string;
    /**
      * `Embedding allows AI to retrieve your content. If the indexer uses local settings, it may affect some of the results of the Embedding.`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.description"](): string;
    /**
      * `Only the workspace owner can enable Workspace Embedding.`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.disabled-tooltip"](): string;
    /**
      * `Select doc`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.select-doc"](): string;
    /**
      * `Upload file`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.upload-file"](): string;
    /**
      * `Workspace Embedding`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.switch.title"](): string;
    /**
      * `AI can call files embedded in the workspace.`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.switch.description"](): string;
    /**
      * `Failed to update workspace doc embedding enabled`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.switch.error"](): string;
    /**
      * `Failed to remove attachment from embedding`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.remove-attachment.error"](): string;
    /**
      * `Failed to update ignored docs`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.update-ignored-docs.error"](): string;
    /**
      * `Embedding progress`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.progress.title"](): string;
    /**
      * `Syncing`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.progress.syncing"](): string;
    /**
      * `Synced`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.progress.synced"](): string;
    /**
      * `Loading sync status...`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.progress.loading-sync-status"](): string;
    /**
      * `Ignore Docs`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.ignore-docs.title"](): string;
    /**
      * `The Ignored docs will not be embedded into the current workspace.`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.ignore-docs.description"](): string;
    /**
      * `Additional attachments`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.additional-attachments.title"](): string;
    /**
      * `The uploaded file will be embedded in the current workspace.`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.additional-attachments.description"](): string;
    /**
      * `Remove the attachment from embedding?`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.additional-attachments.remove-attachment.title"](): string;
    /**
      * `Attachment will be removed. AI will not continue to extract content from this attachment.`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.additional-attachments.remove-attachment.description"](): string;
    /**
      * `Delete File`
      */
    ["com.cloud-notes.settings.workspace.indexer-embedding.embedding.additional-attachments.remove-attachment.tooltip"](): string;
    /**
      * `Sharing docs requires Cloud Notes Sync.`
      */
    ["com.cloud-notes.share-menu.EnableCloudDescription"](): string;
    /**
      * `Share mode`
      */
    ["com.cloud-notes.share-menu.ShareMode"](): string;
    /**
      * `Share doc`
      */
    ["com.cloud-notes.share-menu.SharePage"](): string;
    /**
      * `General access`
      */
    ["com.cloud-notes.share-menu.generalAccess"](): string;
    /**
      * `Share via export`
      */
    ["com.cloud-notes.share-menu.ShareViaExport"](): string;
    /**
      * `Download a static copy of your doc to share with others`
      */
    ["com.cloud-notes.share-menu.ShareViaExportDescription"](): string;
    /**
      * `Print a paper copy`
      */
    ["com.cloud-notes.share-menu.ShareViaPrintDescription"](): string;
    /**
      * `Share with link`
      */
    ["com.cloud-notes.share-menu.ShareWithLink"](): string;
    /**
      * `Create a link you can easily share with anyone. The visitors will open your doc in the form od a document`
      */
    ["com.cloud-notes.share-menu.ShareWithLinkDescription"](): string;
    /**
      * `Shared doc`
      */
    ["com.cloud-notes.share-menu.SharedPage"](): string;
    /**
      * `Copy Link`
      */
    ["com.cloud-notes.share-menu.copy"](): string;
    /**
      * `Copy private link`
      */
    ["com.cloud-notes.share-menu.copy-private-link"](): string;
    /**
      * `Copy Link to Selected Block`
      */
    ["com.cloud-notes.share-menu.copy.block"](): string;
    /**
      * `Copy Link to Edgeless Mode`
      */
    ["com.cloud-notes.share-menu.copy.edgeless"](): string;
    /**
      * `Copy Link to Selected Frame`
      */
    ["com.cloud-notes.share-menu.copy.frame"](): string;
    /**
      * `Copy Link to Page Mode`
      */
    ["com.cloud-notes.share-menu.copy.page"](): string;
    /**
      * `You can share this document with link.`
      */
    ["com.cloud-notes.share-menu.create-public-link.notification.success.message"](): string;
    /**
      * `Public link created`
      */
    ["com.cloud-notes.share-menu.create-public-link.notification.success.title"](): string;
    /**
      * `Please try again later.`
      */
    ["com.cloud-notes.share-menu.disable-publish-link.notification.fail.message"](): string;
    /**
      * `Failed to disable public link`
      */
    ["com.cloud-notes.share-menu.disable-publish-link.notification.fail.title"](): string;
    /**
      * `This doc is no longer shared publicly.`
      */
    ["com.cloud-notes.share-menu.disable-publish-link.notification.success.message"](): string;
    /**
      * `Public link disabled`
      */
    ["com.cloud-notes.share-menu.disable-publish-link.notification.success.title"](): string;
    /**
      * `Manage workspace members`
      */
    ["com.cloud-notes.share-menu.navigate.workspace"](): string;
    /**
      * `Anyone with the link`
      */
    ["com.cloud-notes.share-menu.option.link.label"](): string;
    /**
      * `No access`
      */
    ["com.cloud-notes.share-menu.option.link.no-access"](): string;
    /**
      * `Only workspace members can access this link`
      */
    ["com.cloud-notes.share-menu.option.link.no-access.description"](): string;
    /**
      * `Read only`
      */
    ["com.cloud-notes.share-menu.option.link.readonly"](): string;
    /**
      * `Anyone can access this link`
      */
    ["com.cloud-notes.share-menu.option.link.readonly.description"](): string;
    /**
      * `Sharing for this workspace is turned off. Please contact an admin to enable it.`
      */
    ["com.cloud-notes.share-menu.workspace-sharing.disabled.tooltip"](): string;
    /**
      * `Can manage`
      */
    ["com.cloud-notes.share-menu.option.permission.can-manage"](): string;
    /**
      * `Can edit`
      */
    ["com.cloud-notes.share-menu.option.permission.can-edit"](): string;
    /**
      * `Can read`
      */
    ["com.cloud-notes.share-menu.option.permission.can-read"](): string;
    /**
      * `No access`
      */
    ["com.cloud-notes.share-menu.option.permission.no-access"](): string;
    /**
      * `Members in workspace`
      */
    ["com.cloud-notes.share-menu.option.permission.label"](): string;
    /**
      * `Workspace admins and owner automatically have Can manage permissions.`
      */
    ["com.cloud-notes.share-menu.option.permission.tips"](): string;
    /**
      * `Publish to web`
      */
    ["com.cloud-notes.share-menu.publish-to-web"](): string;
    /**
      * `Share privately`
      */
    ["com.cloud-notes.share-menu.share-privately"](): string;
    /**
      * `Share`
      */
    ["com.cloud-notes.share-menu.shareButton"](): string;
    /**
      * `Shared`
      */
    ["com.cloud-notes.share-menu.sharedButton"](): string;
    /**
      * `{{member1}} and {{member2}} are in this doc`
      */
    ["com.cloud-notes.share-menu.member-management.member-count-2"](options: Readonly<{
        member1: string;
        member2: string;
    }>): string;
    /**
      * `{{member1}}, {{member2}} and {{member3}} are in this doc`
      */
    ["com.cloud-notes.share-menu.member-management.member-count-3"](options: Readonly<{
        member1: string;
        member2: string;
        member3: string;
    }>): string;
    /**
      * `{{member1}}, {{member2}} and {{memberCount}} others`
      */
    ["com.cloud-notes.share-menu.member-management.member-count-more"](options: Readonly<{
        member1: string;
        member2: string;
        memberCount: string;
    }>): string;
    /**
      * `Remove`
      */
    ["com.cloud-notes.share-menu.member-management.remove"](): string;
    /**
      * `Set as owner`
      */
    ["com.cloud-notes.share-menu.member-management.set-as-owner"](): string;
    /**
      * `Make this person the owner?`
      */
    ["com.cloud-notes.share-menu.member-management.set-as-owner.confirm.title"](): string;
    /**
      * `The new owner will be effective immediately, and you might lose access to this doc if other users remove you, please confirm.`
      */
    ["com.cloud-notes.share-menu.member-management.set-as-owner.confirm.description"](): string;
    /**
      * `Permission updated`
      */
    ["com.cloud-notes.share-menu.member-management.update-success"](): string;
    /**
      * `Failed to update permission`
      */
    ["com.cloud-notes.share-menu.member-management.update-fail"](): string;
    /**
      * `{{memberCount}} collaborators in the doc`
      */
    ["com.cloud-notes.share-menu.member-management.header"](options: {
        readonly memberCount: string;
    }): string;
    /**
      * `Add collaborators`
      */
    ["com.cloud-notes.share-menu.member-management.add-collaborators"](): string;
    /**
      * `Send invite`
      */
    ["com.cloud-notes.share-menu.invite-editor.header"](): string;
    /**
      * `Manage members`
      */
    ["com.cloud-notes.share-menu.invite-editor.manage-members"](): string;
    /**
      * `Invite`
      */
    ["com.cloud-notes.share-menu.invite-editor.invite"](): string;
    /**
      * `No results found`
      */
    ["com.cloud-notes.share-menu.invite-editor.no-found"](): string;
    /**
      * `Invite other members`
      */
    ["com.cloud-notes.share-menu.invite-editor.placeholder"](): string;
    /**
      * `Notify via Email`
      */
    ["com.cloud-notes.share-menu.invite-editor.sent-email"](): string;
    /**
      * `Permission not available in Free plan`
      */
    ["com.cloud-notes.share-menu.paywall.owner.title"](): string;
    /**
      * `Upgrade to Pro or higher to unlock permission settings for this doc.`
      */
    ["com.cloud-notes.share-menu.paywall.owner.description"](): string;
    /**
      * `Upgrade`
      */
    ["com.cloud-notes.share-menu.paywall.owner.confirm"](): string;
    /**
      * `Permission requires a workspace upgrade`
      */
    ["com.cloud-notes.share-menu.paywall.member.title"](): string;
    /**
      * `Ask your workspace owner to upgrade to Pro or higher to enable permissions.`
      */
    ["com.cloud-notes.share-menu.paywall.member.description"](): string;
    /**
      * `Got it`
      */
    ["com.cloud-notes.share-menu.paywall.member.confirm"](): string;
    /**
      * `Built with`
      */
    ["com.cloud-notes.share-page.footer.built-with"](): string;
    /**
      * `Create with`
      */
    ["com.cloud-notes.share-page.footer.create-with"](): string;
    /**
      * `Empower your sharing with Cloud Notes: One-click doc sharing`
      */
    ["com.cloud-notes.share-page.footer.description"](): string;
    /**
      * `Get started for free`
      */
    ["com.cloud-notes.share-page.footer.get-started"](): string;
    /**
      * `Use This Template`
      */
    ["com.cloud-notes.share-page.header.import-template"](): string;
    /**
      * `Login or Sign Up`
      */
    ["com.cloud-notes.share-page.header.login"](): string;
    /**
      * `Present`
      */
    ["com.cloud-notes.share-page.header.present"](): string;
    /**
      * `Edgeless`
      */
    ["com.cloud-notes.shortcutsTitle.edgeless"](): string;
    /**
      * `General`
      */
    ["com.cloud-notes.shortcutsTitle.general"](): string;
    /**
      * `Markdown syntax`
      */
    ["com.cloud-notes.shortcutsTitle.markdownSyntax"](): string;
    /**
      * `Page`
      */
    ["com.cloud-notes.shortcutsTitle.page"](): string;
    /**
      * `Collapse sidebar`
      */
    ["com.cloud-notes.sidebarSwitch.collapse"](): string;
    /**
      * `Expand sidebar`
      */
    ["com.cloud-notes.sidebarSwitch.expand"](): string;
    /**
      * `Snapshot Imp. & Exp.`
      */
    ["com.cloud-notes.snapshot.import-export.enable"](): string;
    /**
      * `Once enabled you can find the Snapshot Export Import option in the document's More menu.`
      */
    ["com.cloud-notes.snapshot.import-export.enable.desc"](): string;
    /**
      * `Maybe later`
      */
    ["com.cloud-notes.star-cloud-notes.cancel"](): string;
    /**
      * `Star on GitHub`
      */
    ["com.cloud-notes.star-cloud-notes.confirm"](): string;
    /**
      * `Are you finding our app useful and enjoyable? We'd love your support to keep improving! A great way to help us out is by giving us a star on GitHub. This simple action can make a big difference and helps us continue to deliver the best experience for you.`
      */
    ["com.cloud-notes.star-cloud-notes.description"](): string;
    /**
      * `Star us on GitHub`
      */
    ["com.cloud-notes.star-cloud-notes.title"](): string;
    /**
      * `Change plan`
      */
    ["com.cloud-notes.storage.change-plan"](): string;
    /**
      * `You have reached the maximum capacity limit for your current account`
      */
    ["com.cloud-notes.storage.maximum-tips"](): string;
    /**
      * `Pro users will have unlimited storage capacity during the alpha test period of the team version`
      */
    ["com.cloud-notes.storage.maximum-tips.pro"](): string;
    /**
      * `Plan`
      */
    ["com.cloud-notes.storage.plan"](): string;
    /**
      * `Cloud Notes Sync storage`
      */
    ["com.cloud-notes.storage.title"](): string;
    /**
      * `Upgrade`
      */
    ["com.cloud-notes.storage.upgrade"](): string;
    /**
      * `Space used`
      */
    ["com.cloud-notes.storage.used.hint"](): string;
    /**
      * `Syncing`
      */
    ["com.cloud-notes.syncing"](): string;
    /**
      * `{{count}} doc`
    
      * - com.cloud-notes.tags.count_one: `{{count}} doc`
    
      * - com.cloud-notes.tags.count_other: `{{count}} docs`
    
      * - com.cloud-notes.tags.count_zero: `{{count}} doc`
      */
    ["com.cloud-notes.tags.count"](options: {
        readonly count: string | number | bigint;
    }): string;
    /**
      * `{{count}} doc`
      */
    ["com.cloud-notes.tags.count_one"](options: {
        readonly count: string | number | bigint;
    }): string;
    /**
      * `{{count}} docs`
      */
    ["com.cloud-notes.tags.count_other"](options: {
        readonly count: string | number | bigint;
    }): string;
    /**
      * `{{count}} doc`
      */
    ["com.cloud-notes.tags.count_zero"](options: {
        readonly count: string | number | bigint;
    }): string;
    /**
      * `Type tag name here...`
      */
    ["com.cloud-notes.tags.create-tag.placeholder"](): string;
    /**
      * `Tag already exists`
      */
    ["com.cloud-notes.tags.create-tag.toast.exist"](): string;
    /**
      * `Tag created`
      */
    ["com.cloud-notes.tags.create-tag.toast.success"](): string;
    /**
      * `Tag deleted`
      */
    ["com.cloud-notes.tags.delete-tags.toast"](): string;
    /**
      * `Tag updated`
      */
    ["com.cloud-notes.tags.edit-tag.toast.success"](): string;
    /**
      * `New tag`
      */
    ["com.cloud-notes.tags.empty.new-tag-button"](): string;
    /**
      * `Enable telemetry`
      */
    ["com.cloud-notes.telemetry.enable"](): string;
    /**
      * `Telemetry is a feature that allows us to collect data on how you use the app. This data helps us improve the app and provide better features.`
      */
    ["com.cloud-notes.telemetry.enable.desc"](): string;
    /**
      * `Dark`
      */
    ["com.cloud-notes.themeSettings.dark"](): string;
    /**
      * `Light`
      */
    ["com.cloud-notes.themeSettings.light"](): string;
    /**
      * `System`
      */
    ["com.cloud-notes.themeSettings.system"](): string;
    /**
      * `Auto`
      */
    ["com.cloud-notes.themeSettings.auto"](): string;
    /**
      * `now`
      */
    ["com.cloud-notes.time.now"](): string;
    /**
      * `this month`
      */
    ["com.cloud-notes.time.this-mouth"](): string;
    /**
      * `this week`
      */
    ["com.cloud-notes.time.this-week"](): string;
    /**
      * `this year`
      */
    ["com.cloud-notes.time.this-year"](): string;
    /**
      * `today`
      */
    ["com.cloud-notes.time.today"](): string;
    /**
      * `Successfully added linked doc`
      */
    ["com.cloud-notes.toastMessage.addLinkedPage"](): string;
    /**
      * `Added to favorites`
      */
    ["com.cloud-notes.toastMessage.addedFavorites"](): string;
    /**
      * `Edgeless mode`
      */
    ["com.cloud-notes.toastMessage.edgelessMode"](): string;
    /**
      * `Moved to trash`
      */
    ["com.cloud-notes.toastMessage.movedTrash"](): string;
    /**
      * `Page Mode`
      */
    ["com.cloud-notes.toastMessage.pageMode"](): string;
    /**
      * `Default mode has changed`
      */
    ["com.cloud-notes.toastMessage.defaultMode.page.title"](): string;
    /**
      * `The default mode for this document has been changed to Page mode`
      */
    ["com.cloud-notes.toastMessage.defaultMode.page.message"](): string;
    /**
      * `Default mode has changed`
      */
    ["com.cloud-notes.toastMessage.defaultMode.edgeless.title"](): string;
    /**
      * `The default mode for this document has been changed to Edgeless mode`
      */
    ["com.cloud-notes.toastMessage.defaultMode.edgeless.message"](): string;
    /**
      * `Permanently deleted`
      */
    ["com.cloud-notes.toastMessage.permanentlyDeleted"](): string;
    /**
      * `Removed from favourites`
      */
    ["com.cloud-notes.toastMessage.removedFavorites"](): string;
    /**
      * `Successfully renamed`
      */
    ["com.cloud-notes.toastMessage.rename"](): string;
    /**
      * `{{title}} restored`
      */
    ["com.cloud-notes.toastMessage.restored"](options: {
        readonly title: string;
    }): string;
    /**
      * `Successfully deleted`
      */
    ["com.cloud-notes.toastMessage.successfullyDeleted"](): string;
    /**
      * `Today`
      */
    ["com.cloud-notes.today"](): string;
    /**
      * `Tomorrow`
      */
    ["com.cloud-notes.tomorrow"](): string;
    /**
      * `Last {{weekday}}`
      */
    ["com.cloud-notes.last-week"](options: {
        readonly weekday: string;
    }): string;
    /**
      * `Next {{weekday}}`
      */
    ["com.cloud-notes.next-week"](options: {
        readonly weekday: string;
    }): string;
    /**
      * `Limited to view-only on mobile.`
      */
    ["com.cloud-notes.top-tip.mobile"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.trashOperation.delete"](): string;
    /**
      * `Once deleted, you can't undo this action. Do you confirm?`
      */
    ["com.cloud-notes.trashOperation.delete.description"](): string;
    /**
      * `Permanently delete`
      */
    ["com.cloud-notes.trashOperation.delete.title"](): string;
    /**
      * `Once deleted, you can't undo this action. Do you confirm?`
      */
    ["com.cloud-notes.trashOperation.deleteDescription"](): string;
    /**
      * `Delete permanently`
      */
    ["com.cloud-notes.trashOperation.deletePermanently"](): string;
    /**
      * `Restore it`
      */
    ["com.cloud-notes.trashOperation.restoreIt"](): string;
    /**
      * `Refresh current page`
      */
    ["com.cloud-notes.upgrade.button-text.done"](): string;
    /**
      * `Data upgrade error`
      */
    ["com.cloud-notes.upgrade.button-text.error"](): string;
    /**
      * `Upgrade workspace data`
      */
    ["com.cloud-notes.upgrade.button-text.pending"](): string;
    /**
      * `Upgrading`
      */
    ["com.cloud-notes.upgrade.button-text.upgrading"](): string;
    /**
      * `After upgrading the workspace data, please refresh the page to see the changes.`
      */
    ["com.cloud-notes.upgrade.tips.done"](): string;
    /**
      * `We encountered some errors while upgrading the workspace data.`
      */
    ["com.cloud-notes.upgrade.tips.error"](): string;
    /**
      * `To ensure compatibility with the updated Cloud Notes client, please upgrade your data by clicking the "Upgrade workspace data" button below.`
      */
    ["com.cloud-notes.upgrade.tips.normal"](): string;
    /**
      * `AI usage`
      */
    ["com.cloud-notes.user-info.usage.ai"](): string;
    /**
      * `Sync storage`
      */
    ["com.cloud-notes.user-info.usage.cloud"](): string;
    /**
      * `Close`
      */
    ["com.cloud-notes.workbench.split-view-menu.close"](): string;
    /**
      * `Full screen`
      */
    ["com.cloud-notes.workbench.split-view-menu.full-screen"](): string;
    /**
      * `Solo view`
      */
    ["com.cloud-notes.workbench.split-view-menu.keep-this-one"](): string;
    /**
      * `Move left`
      */
    ["com.cloud-notes.workbench.split-view-menu.move-left"](): string;
    /**
      * `Move right`
      */
    ["com.cloud-notes.workbench.split-view-menu.move-right"](): string;
    /**
      * `Open in split view`
      */
    ["com.cloud-notes.workbench.split-view.page-menu-open"](): string;
    /**
      * `Open in new tab`
      */
    ["com.cloud-notes.workbench.tab.page-menu-open"](): string;
    /**
      * `You cannot delete the last workspace`
      */
    ["com.cloud-notes.workspace.cannot-delete"](): string;
    /**
      * `Synced workspaces`
      */
    ["com.cloud-notes.workspace.cloud"](): string;
    /**
      * `Sign out`
      */
    ["com.cloud-notes.workspace.cloud.account.logout"](): string;
    /**
      * `Account settings`
      */
    ["com.cloud-notes.workspace.cloud.account.settings"](): string;
    /**
      * `Admin panel`
      */
    ["com.cloud-notes.workspace.cloud.account.admin"](): string;
    /**
      * `Team owner`
      */
    ["com.cloud-notes.workspace.cloud.account.team.owner"](): string;
    /**
      * `Team member`
      */
    ["com.cloud-notes.workspace.cloud.account.team.member"](): string;
    /**
      * `Multiple teams`
      */
    ["com.cloud-notes.workspace.cloud.account.team.multi"](): string;
    /**
      * `Click to open workspace`
      */
    ["com.cloud-notes.workspace.cloud.account.team.tips-1"](): string;
    /**
      * `Click to open workspace list`
      */
    ["com.cloud-notes.workspace.cloud.account.team.tips-2"](): string;
    /**
      * `Sign in / Sign up to Cloud Notes`
      */
    ["com.cloud-notes.workspace.cloud.auth"](): string;
    /**
      * `Sync with Cloud Notes`
      */
    ["com.cloud-notes.workspace.cloud.description"](): string;
    /**
      * `Join workspace`
      */
    ["com.cloud-notes.workspace.cloud.join"](): string;
    /**
      * `Cloud Notes Sync`
      */
    ["com.cloud-notes.workspace.cloud.sync"](): string;
    /**
      * `Failed to turn on Cloud Notes Sync. Please try again.`
      */
    ["com.cloud-notes.workspace.enable-cloud.failed"](): string;
    /**
      * `Local workspaces`
      */
    ["com.cloud-notes.workspace.local"](): string;
    /**
      * `Import workspace`
      */
    ["com.cloud-notes.workspace.local.import"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.workspaceDelete.button.cancel"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.workspaceDelete.button.delete"](): string;
    /**
      * `Please type workspace name to confirm`
      */
    ["com.cloud-notes.workspaceDelete.placeholder"](): string;
    /**
      * `Delete workspace`
      */
    ["com.cloud-notes.workspaceDelete.title"](): string;
    /**
      * `Create workspace`
      */
    ["com.cloud-notes.workspaceList.addWorkspace.create"](): string;
    /**
      * `Create synced workspace`
      */
    ["com.cloud-notes.workspaceList.addWorkspace.create-cloud"](): string;
    /**
      * `Cloud sync`
      */
    ["com.cloud-notes.workspaceList.workspaceListType.cloud"](): string;
    /**
      * `Local storage`
      */
    ["com.cloud-notes.workspaceList.workspaceListType.local"](): string;
    /**
      * `Add Server`
      */
    ["com.cloud-notes.workspaceList.addServer"](): string;
    /**
      * `All docs`
      */
    ["com.cloud-notes.workspaceSubPath.all"](): string;
    /**
      * `Intelligence`
      */
    ["com.cloud-notes.workspaceSubPath.chat"](): string;
    /**
      * `Trash`
      */
    ["com.cloud-notes.workspaceSubPath.trash"](): string;
    /**
      * `Deleted docs will appear here.`
      */
    ["com.cloud-notes.workspaceSubPath.trash.empty-description"](): string;
    /**
      * `Write with a blank page`
      */
    ["com.cloud-notes.write_with_a_blank_page"](): string;
    /**
      * `Yesterday`
      */
    ["com.cloud-notes.yesterday"](): string;
    /**
      * `Inactive`
      */
    ["com.cloud-notes.inactive"](): string;
    /**
      * `Inactive member`
      */
    ["com.cloud-notes.inactive-member"](): string;
    /**
      * `Inactive workspace`
      */
    ["com.cloud-notes.inactive-workspace"](): string;
    /**
      * `Display Properties`
      */
    ["com.cloud-notes.all-docs.display.properties"](): string;
    /**
      * `List view options`
      */
    ["com.cloud-notes.all-docs.display.list-view"](): string;
    /**
      * `Icon`
      */
    ["com.cloud-notes.all-docs.display.list-view.icon"](): string;
    /**
      * `Body`
      */
    ["com.cloud-notes.all-docs.display.list-view.body"](): string;
    /**
      * `Quick actions`
      */
    ["com.cloud-notes.all-docs.quick-actions"](): string;
    /**
      * `Favorite`
      */
    ["com.cloud-notes.all-docs.quick-action.favorite"](): string;
    /**
      * `Move to trash`
      */
    ["com.cloud-notes.all-docs.quick-action.trash"](): string;
    /**
      * `Open in split view`
      */
    ["com.cloud-notes.all-docs.quick-action.split"](): string;
    /**
      * `Open in new tab`
      */
    ["com.cloud-notes.all-docs.quick-action.tab"](): string;
    /**
      * `Select checkbox`
      */
    ["com.cloud-notes.all-docs.quick-action.select"](): string;
    /**
      * `Delete permanently`
      */
    ["com.cloud-notes.all-docs.quick-action.delete-permanently"](): string;
    /**
      * `Restore`
      */
    ["com.cloud-notes.all-docs.quick-action.restore"](): string;
    /**
      * `All`
      */
    ["com.cloud-notes.all-docs.pinned-collection.all"](): string;
    /**
      * `Edit collection rules`
      */
    ["com.cloud-notes.all-docs.pinned-collection.edit"](): string;
    /**
      * `Template`
      */
    ["com.cloud-notes.all-docs.group.is-template"](): string;
    /**
      * `Not Template`
      */
    ["com.cloud-notes.all-docs.group.is-not-template"](): string;
    /**
      * `Journal`
      */
    ["com.cloud-notes.all-docs.group.is-journal"](): string;
    /**
      * `Not Journal`
      */
    ["com.cloud-notes.all-docs.group.is-not-journal"](): string;
    /**
      * `Checked`
      */
    ["com.cloud-notes.all-docs.group.is-checked"](): string;
    /**
      * `Unchecked`
      */
    ["com.cloud-notes.all-docs.group.is-not-checked"](): string;
    /**
      * `Never updated`
      */
    ["com.cloud-notes.all-docs.group.updated-at.never-updated"](): string;
    /**
      * `core`
      */
    core(): string;
    /**
      * `Dark`
      */
    dark(): string;
    /**
      * `invited you to join`
      */
    ["invited you to join"](): string;
    /**
      * `Light`
      */
    light(): string;
    /**
      * `Others`
      */
    others(): string;
    /**
      * `System`
      */
    system(): string;
    /**
      * `unnamed`
      */
    unnamed(): string;
    /**
      * `Please upgrade to the latest version of Chrome for the best experience.`
      */
    upgradeBrowser(): string;
    /**
      * `Workspace properties`
      */
    ["com.cloud-notes.workspace.properties"](): string;
    /**
      * `Rename to "{{name}}"`
      */
    ["com.cloud-notes.m.rename-to"](options: {
        readonly name: string;
    }): string;
    /**
      * `Rename`
      */
    ["com.cloud-notes.m.explorer.folder.rename"](): string;
    /**
      * `Create Folder`
      */
    ["com.cloud-notes.m.explorer.folder.new-dialog-title"](): string;
    /**
      * `Organize`
      */
    ["com.cloud-notes.m.explorer.folder.root"](): string;
    /**
      * `Create a folder in the {{parent}}.`
      */
    ["com.cloud-notes.m.explorer.folder.new-tip-empty"](options: {
        readonly parent: string;
    }): string;
    /**
      * `Create "{{value}}" in the {{parent}}.`
      */
    ["com.cloud-notes.m.explorer.folder.new-tip-not-empty"](options: Readonly<{
        value: string;
        parent: string;
    }>): string;
    /**
      * `Done`
      */
    ["com.cloud-notes.m.explorer.folder.rename-confirm"](): string;
    /**
      * `Rename`
      */
    ["com.cloud-notes.m.explorer.tag.rename"](): string;
    /**
      * `Rename Tag`
      */
    ["com.cloud-notes.m.explorer.tag.rename-menu-title"](): string;
    /**
      * `Create Tag`
      */
    ["com.cloud-notes.m.explorer.tag.new-dialog-title"](): string;
    /**
      * `Done`
      */
    ["com.cloud-notes.m.explorer.tag.rename-confirm"](): string;
    /**
      * `Create a tag in this workspace.`
      */
    ["com.cloud-notes.m.explorer.tag.new-tip-empty"](): string;
    /**
      * `Create "{{value}}" tag in this workspace.`
      */
    ["com.cloud-notes.m.explorer.tag.new-tip-not-empty"](options: {
        readonly value: string;
    }): string;
    /**
      * `Manage Doc(s)`
      */
    ["com.cloud-notes.m.explorer.tag.manage-docs"](): string;
    /**
      * `Rename`
      */
    ["com.cloud-notes.m.explorer.collection.rename"](): string;
    /**
      * `Rename Collection`
      */
    ["com.cloud-notes.m.explorer.collection.rename-menu-title"](): string;
    /**
      * `Create Collection`
      */
    ["com.cloud-notes.m.explorer.collection.new-dialog-title"](): string;
    /**
      * `No documents yet`
      */
    ["com.cloud-notes.m.explorer.empty.docs.title"](): string;
    /**
      * `Create your first document to start capturing ideas and organizing knowledge.`
      */
    ["com.cloud-notes.m.explorer.empty.docs.description"](): string;
    /**
      * `New Document`
      */
    ["com.cloud-notes.m.explorer.empty.docs.action"](): string;
    /**
      * `No collections yet`
      */
    ["com.cloud-notes.m.explorer.empty.collections.title"](): string;
    /**
      * `Create a collection to organize related content in one place.`
      */
    ["com.cloud-notes.m.explorer.empty.collections.description"](): string;
    /**
      * `New Collection`
      */
    ["com.cloud-notes.m.explorer.empty.collections.action"](): string;
    /**
      * `No tags yet`
      */
    ["com.cloud-notes.m.explorer.empty.tags.title"](): string;
    /**
      * `Add tags to your documents for easier organization and discovery.`
      */
    ["com.cloud-notes.m.explorer.empty.tags.description"](): string;
    /**
      * `New Tag`
      */
    ["com.cloud-notes.m.explorer.empty.tags.action"](): string;
    /**
      * `Rename`
      */
    ["com.cloud-notes.m.explorer.doc.rename"](): string;
    /**
      * `Doc`
      */
    ["com.cloud-notes.m.selector.type-doc"](): string;
    /**
      * `Tag`
      */
    ["com.cloud-notes.m.selector.type-tag"](): string;
    /**
      * `Collection`
      */
    ["com.cloud-notes.m.selector.type-collection"](): string;
    /**
      * `Folder`
      */
    ["com.cloud-notes.m.selector.where-folder"](): string;
    /**
      * `Tag`
      */
    ["com.cloud-notes.m.selector.where-tag"](): string;
    /**
      * `Collection`
      */
    ["com.cloud-notes.m.selector.where-collection"](): string;
    /**
      * `Apply`
      */
    ["com.cloud-notes.m.selector.confirm-default"](): string;
    /**
      * `Manage {{type}}(s)`
      */
    ["com.cloud-notes.m.selector.title"](options: {
        readonly type: string;
    }): string;
    /**
      * `{{total}} item(s)`
      */
    ["com.cloud-notes.m.selector.info-total"](options: {
        readonly total: string;
    }): string;
    /**
      * `Add {{count}} {{type}}(s)`
      */
    ["com.cloud-notes.m.selector.info-added"](options: Readonly<{
        count: string;
        type: string;
    }>): string;
    /**
      * `Remove {{count}} {{type}}(s)`
      */
    ["com.cloud-notes.m.selector.info-removed"](options: Readonly<{
        count: string;
        type: string;
    }>): string;
    /**
      * `Remove items`
      */
    ["com.cloud-notes.m.selector.remove-warning.title"](): string;
    /**
      * `You unchecked {{type}} that already exist in the current {{where}}, which means you will remove them from this {{where}}. The item will not be deleted.`
      */
    ["com.cloud-notes.m.selector.remove-warning.message"](options: Readonly<{
        type: string;
        where: string;
    }>): string;
    /**
      * `Do not ask again`
      */
    ["com.cloud-notes.m.selector.remove-warning.confirm"](): string;
    /**
      * `Cancel`
      */
    ["com.cloud-notes.m.selector.remove-warning.cancel"](): string;
    /**
      * `tag`
      */
    ["com.cloud-notes.m.selector.remove-warning.where-tag"](): string;
    /**
      * `folder`
      */
    ["com.cloud-notes.m.selector.remove-warning.where-folder"](): string;
    /**
      * `Today's activity`
      */
    ["com.cloud-notes.m.selector.journal-menu.today-activity"](): string;
    /**
      * `Duplicate Entries in Today's Journal`
      */
    ["com.cloud-notes.m.selector.journal-menu.conflicts"](): string;
    /**
      * `Unable to preview this file`
      */
    ["com.cloud-notes.attachment.preview.error.title"](): string;
    /**
      * `file type not supported.`
      */
    ["com.cloud-notes.attachment.preview.error.subtitle"](): string;
    /**
      * `Failed to render page.`
      */
    ["com.cloud-notes.pdf.page.render.error"](): string;
    /**
      * `Duplicate Entries in Today's Journal`
      */
    ["com.cloud-notes.editor.journal-conflict.title"](): string;
    /**
      * `Search for "{{query}}"`
      */
    ["com.cloud-notes.editor.at-menu.link-to-doc"](options: {
        readonly query: string;
    }): string;
    /**
      * `Recent`
      */
    ["com.cloud-notes.editor.at-menu.recent-docs"](): string;
    /**
      * `Tags`
      */
    ["com.cloud-notes.editor.at-menu.tags"](): string;
    /**
      * `Collections`
      */
    ["com.cloud-notes.editor.at-menu.collections"](): string;
    /**
      * `Loading...`
      */
    ["com.cloud-notes.editor.at-menu.loading"](): string;
    /**
      * `New`
      */
    ["com.cloud-notes.editor.at-menu.new-doc"](): string;
    /**
      * `New "{{name}}" page`
      */
    ["com.cloud-notes.editor.at-menu.create-page"](options: {
        readonly name: string;
    }): string;
    /**
      * `New "{{name}}" edgeless`
      */
    ["com.cloud-notes.editor.at-menu.create-edgeless"](options: {
        readonly name: string;
    }): string;
    /**
      * `Import`
      */
    ["com.cloud-notes.editor.at-menu.import"](): string;
    /**
      * `{{count}} more docs`
      */
    ["com.cloud-notes.editor.at-menu.more-docs-hint"](options: {
        readonly count: string;
    }): string;
    /**
      * `{{count}} more members`
      */
    ["com.cloud-notes.editor.at-menu.more-members-hint"](options: {
        readonly count: string;
    }): string;
    /**
      * `Journal`
      */
    ["com.cloud-notes.editor.at-menu.journal"](): string;
    /**
      * `Select a specific date`
      */
    ["com.cloud-notes.editor.at-menu.date-picker"](): string;
    /**
      * `Mention Members`
      */
    ["com.cloud-notes.editor.at-menu.mention-members"](): string;
    /**
      * `Member not notified`
      */
    ["com.cloud-notes.editor.at-menu.member-not-notified"](): string;
    /**
      * `This member does not have access to this doc, they are not notified.`
      */
    ["com.cloud-notes.editor.at-menu.member-not-notified-message"](): string;
    /**
      * `Invited and notified`
      */
    ["com.cloud-notes.editor.at-menu.invited-and-notified"](): string;
    /**
      * `Access needed`
      */
    ["com.cloud-notes.editor.at-menu.access-needed"](): string;
    /**
      * `{{username}} does not have access to this doc, do you want to invite and notify them?`
      */
    ["com.cloud-notes.editor.at-menu.access-needed-message"](options: {
        readonly username: string;
    }): string;
    /**
      * `Show`
      */
    ["com.cloud-notes.editor.bi-directional-link-panel.show"](): string;
    /**
      * `Hide`
      */
    ["com.cloud-notes.editor.bi-directional-link-panel.hide"](): string;
    /**
      * `Fold page block`
      */
    ["com.cloud-notes.editor.edgeless-note-header.fold-page-block"](): string;
    /**
      * `Open in Page`
      */
    ["com.cloud-notes.editor.edgeless-note-header.open-in-page"](): string;
    /**
      * `Fold`
      */
    ["com.cloud-notes.editor.edgeless-embed-synced-doc-header.fold"](): string;
    /**
      * `Unfold`
      */
    ["com.cloud-notes.editor.edgeless-embed-synced-doc-header.unfold"](): string;
    /**
      * `Open`
      */
    ["com.cloud-notes.editor.edgeless-embed-synced-doc-header.open"](): string;
    /**
      * `Empower Your Team with Seamless Collaboration`
      */
    ["com.cloud-notes.upgrade-to-team-page.title"](): string;
    /**
      * `Select an existing workspace or create a new one`
      */
    ["com.cloud-notes.upgrade-to-team-page.workspace-selector.placeholder"](): string;
    /**
      * `Create Workspace`
      */
    ["com.cloud-notes.upgrade-to-team-page.workspace-selector.create-workspace"](): string;
    /**
      * `Upgrade to Team Workspace`
      */
    ["com.cloud-notes.upgrade-to-team-page.upgrade-button"](): string;
    /**
      * `Team Workspace gives you everything you need for seamless team collaboration:`
      */
    ["com.cloud-notes.upgrade-to-team-page.benefit.title"](): string;
    /**
      * `Invite unlimited members to your workspace`
      */
    ["com.cloud-notes.upgrade-to-team-page.benefit.g1"](): string;
    /**
      * `Set custom roles and permissions for better control`
      */
    ["com.cloud-notes.upgrade-to-team-page.benefit.g2"](): string;
    /**
      * `Access advanced team management features`
      */
    ["com.cloud-notes.upgrade-to-team-page.benefit.g3"](): string;
    /**
      * `Get priority customer support`
      */
    ["com.cloud-notes.upgrade-to-team-page.benefit.g4"](): string;
    /**
      * `Perfect for growing teams and organizations that need professional collaboration tools.`
      */
    ["com.cloud-notes.upgrade-to-team-page.benefit.description"](): string;
    /**
      * `Upgrade to Team Workspace`
      */
    ["com.cloud-notes.upgrade-to-team-page.upgrade-confirm.title"](): string;
    /**
      * `Name Your Workspace`
      */
    ["com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.title"](): string;
    /**
      * `A workspace is your virtual space to capture, create and plan as just one person or together as a team.`
      */
    ["com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.description"](): string;
    /**
      * `Set a workspace name`
      */
    ["com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.placeholder"](): string;
    /**
      * `Continue to Pricing`
      */
    ["com.cloud-notes.upgrade-to-team-page.create-and-upgrade-confirm.confirm"](): string;
    /**
      * `No workspace available`
      */
    ["com.cloud-notes.upgrade-to-team-page.no-workspace-available"](): string;
    /**
      * `Workspace storage`
      */
    ["com.cloud-notes.workspace.storage"](): string;
    /**
      * `Journal`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.journal"](): string;
    /**
      * `Select a specific date`
      */
    ["com.cloud-notes.cmdk.cloud-notes.category.cloud-notes.date-picker"](): string;
    /**
      * `Workspace sync paused`
      */
    ["com.cloud-notes.payment.sync-paused.title"](): string;
    /**
      * `Your workspace has exceeded both storage and member limits, causing synchronization to pause. To resume syncing, please either:`
      */
    ["com.cloud-notes.payment.sync-paused.owner.both.description"](): string;
    /**
      * `Reduce storage usage and remove some team members`
      */
    ["com.cloud-notes.payment.sync-paused.owner.both.tips-1"](): string;
    /**
      * `Upgrade your plan for increased capacity`
      */
    ["com.cloud-notes.payment.sync-paused.owner.both.tips-2"](): string;
    /**
      * `Your workspace has exceeded its storage limit and synchronization has been paused. To resume syncing, please either:`
      */
    ["com.cloud-notes.payment.sync-paused.owner.storage.description"](): string;
    /**
      * `Remove unnecessary files or content to reduce storage usage`
      */
    ["com.cloud-notes.payment.sync-paused.owner.storage.tips-1"](): string;
    /**
      * `Upgrade your plan for increased storage capacity`
      */
    ["com.cloud-notes.payment.sync-paused.owner.storage.tips-2"](): string;
    /**
      * `Your workspace has reached its maximum member capacity and synchronization has been paused. To resume syncing, you can either`
      */
    ["com.cloud-notes.payment.sync-paused.owner.member.description"](): string;
    /**
      * `Remove some team members from the workspace`
      */
    ["com.cloud-notes.payment.sync-paused.owner.member.tips-1"](): string;
    /**
      * `Upgrade your plan to accommodate more members`
      */
    ["com.cloud-notes.payment.sync-paused.owner.member.tips-2"](): string;
    /**
      * `This workspace has exceeded both storage and member limits, causing synchronization to pause. Please contact your workspace owner to address these limits and resume syncing.`
      */
    ["com.cloud-notes.payment.sync-paused.member.both.description"](): string;
    /**
      * `This workspace has exceeded its storage limit and synchronization has been paused. Please contact your workspace owner to either reduce storage usage or upgrade the plan to resume syncing.`
      */
    ["com.cloud-notes.payment.sync-paused.member.storage.description"](): string;
    /**
      * `This workspace has reached its maximum member capacity and synchronization has been paused. Please contact your workspace owner to either adjust team membership or upgrade the plan to resume syncing.`
      */
    ["com.cloud-notes.payment.sync-paused.member.member.description"](): string;
    /**
      * `Got It`
      */
    ["com.cloud-notes.payment.sync-paused.member.member.confirm"](): string;
    /**
      * `Delete Server`
      */
    ["com.cloud-notes.server.delete"](): string;
    /**
      * `Start`
      */
    ["com.cloud-notes.page-starter-bar.start"](): string;
    /**
      * `Template`
      */
    ["com.cloud-notes.page-starter-bar.template"](): string;
    /**
      * `With AI`
      */
    ["com.cloud-notes.page-starter-bar.ai"](): string;
    /**
      * `Edgeless`
      */
    ["com.cloud-notes.page-starter-bar.edgeless"](): string;
    /**
      * `Unsupported message`
      */
    ["com.cloud-notes.notification.unsupported"](): string;
    /**
      * `What are your thoughts?`
      */
    ["com.cloud-notes.notification.comment-prompt"](): string;
    /**
      * `No new notifications`
      */
    ["com.cloud-notes.notification.empty"](): string;
    /**
      * `Loading more...`
      */
    ["com.cloud-notes.notification.loading-more"](): string;
    /**
      * `You'll be notified here for @mentions and workspace invites.`
      */
    ["com.cloud-notes.notification.empty.description"](): string;
    /**
      * `Open workspace`
      */
    ["com.cloud-notes.notification.invitation-review-approved.open-workspace"](): string;
    /**
      * `Accept & Join`
      */
    ["com.cloud-notes.notification.invitation.accept"](): string;
    /**
      * `Delete all notifications`
      */
    ["com.cloud-notes.notification.delete-all"](): string;
    /**
      * `Tips`
      */
    tips(): string;
    /**
      * `Template`
      */
    Template(): string;
    /**
      * `Delete Template`
      */
    ["com.cloud-notes.template-list.delete"](): string;
    /**
      * `No template`
      */
    ["com.cloud-notes.template-list.empty"](): string;
    /**
      * `Create new template`
      */
    ["com.cloud-notes.template-list.create-new"](): string;
    /**
      * `Set a Template for the Journal`
      */
    ["com.cloud-notes.template-journal-onboarding.title"](): string;
    /**
      * `Select`
      */
    ["com.cloud-notes.template-journal-onboarding.select"](): string;
    /**
      * `My Templates`
      */
    ["com.cloud-notes.settings.workspace.template.title"](): string;
    /**
      * `Template for journal`
      */
    ["com.cloud-notes.settings.workspace.template.journal"](): string;
    /**
      * `Select a template for your journal`
      */
    ["com.cloud-notes.settings.workspace.template.journal-desc"](): string;
    /**
      * `Keep empty`
      */
    ["com.cloud-notes.settings.workspace.template.keep-empty"](): string;
    /**
      * `New doc with template`
      */
    ["com.cloud-notes.settings.workspace.template.page"](): string;
    /**
      * `New docs will use the specified template, ignoring default settings.`
      */
    ["com.cloud-notes.settings.workspace.template.page-desc"](): string;
    /**
      * `Template for new doc`
      */
    ["com.cloud-notes.settings.workspace.template.page-select"](): string;
    /**
      * `Remove template`
      */
    ["com.cloud-notes.settings.workspace.template.remove"](): string;
    /**
      * `You don't have permission to do this`
      */
    ["com.cloud-notes.no-permission"](): string;
    /**
      * `Unused blobs`
      */
    ["com.cloud-notes.settings.workspace.storage.unused-blobs"](): string;
    /**
      * `No unused blobs`
      */
    ["com.cloud-notes.settings.workspace.storage.unused-blobs.empty"](): string;
    /**
      * `Selected`
      */
    ["com.cloud-notes.settings.workspace.storage.unused-blobs.selected"](): string;
    /**
      * `Delete blob files`
      */
    ["com.cloud-notes.settings.workspace.storage.unused-blobs.delete.title"](): string;
    /**
      * `Are you sure you want to delete these blob files? This action cannot be undone. Make sure you no longer need them before proceeding.`
      */
    ["com.cloud-notes.settings.workspace.storage.unused-blobs.delete.warning"](): string;
    /**
      * `Join Failed`
      */
    ["notes.diegonmarcos.com-to-join-workspace.title"](): string;
    /**
      * `This invitation is for another account`
      */
    ["com.cloud-notes.invitation.account-mismatch.title"](): string;
    /**
      * `You're signed in with an account that wasn't invited. Sign in with the account that received this invitation to continue.`
      */
    ["com.cloud-notes.invitation.account-mismatch.description"](): string;
    /**
      * `Sign in with another account`
      */
    ["com.cloud-notes.invitation.account-mismatch.switch-account"](): string;
    /**
      * `Back to Cloud Notes`
      */
    ["com.cloud-notes.invitation.account-mismatch.back-to-cloud-notes"](): string;
    /**
      * `Please contact your workspace owner to add more seats.`
      */
    ["notes.diegonmarcos.com-to-join-workspace.description-2"](): string;
    /**
      * `Request to join`
      */
    ["com.cloud-notes.request-to-join-workspace.button"](): string;
    /**
      * `Request Sent successfully`
      */
    ["com.cloud-notes.sent-request-to-join-workspace.title"](): string;
    /**
      * `Request failed to send`
      */
    ["com.cloud-notes.failed-to-send-request.title"](): string;
    /**
      * `Readwise`
      */
    ["com.cloud-notes.integration.name.readwise"](): string;
    /**
      * `Integrations`
      */
    ["com.cloud-notes.integration.integrations"](): string;
    /**
      * `Web Clipper`
      */
    ["com.cloud-notes.integration.web-clipper.name"](): string;
    /**
      * `Import web pages to Cloud Notes`
      */
    ["com.cloud-notes.integration.web-clipper.desc"](): string;
    /**
      * `Elevate your Cloud Notes experience with diverse add-ons and seamless integrations.`
      */
    ["com.cloud-notes.integration.setting.description"](): string;
    /**
      * `Learn how to develop a integration for Cloud Notes`
      */
    ["com.cloud-notes.integration.setting.learn"](): string;
    /**
      * `Readwise`
      */
    ["com.cloud-notes.integration.readwise.name"](): string;
    /**
      * `Manually import your content to Cloud Notes from Readwise`
      */
    ["com.cloud-notes.integration.readwise.desc"](): string;
    /**
      * `Connect`
      */
    ["com.cloud-notes.integration.readwise.connect"](): string;
    /**
      * `Connect to Readwise`
      */
    ["com.cloud-notes.integration.readwise.connect.title"](): string;
    /**
      * `Paste your access token here`
      */
    ["com.cloud-notes.integration.readwise.connect.placeholder"](): string;
    /**
      * `Please enter a valid access token.`
      */
    ["com.cloud-notes.integration.readwise.connect.input-error"](): string;
    /**
      * `Access Token failed validation`
      */
    ["com.cloud-notes.integration.readwise.connect.error-notify-title"](): string;
    /**
      * `The token could not access Readwise. Please verify access and try again.`
      */
    ["com.cloud-notes.integration.readwise.connect.error-notify-desc"](): string;
    /**
      * `Import`
      */
    ["com.cloud-notes.integration.readwise.import"](): string;
    /**
      * `Disconnect`
      */
    ["com.cloud-notes.integration.readwise.disconnect"](): string;
    /**
      * `Disconnect Readwise?`
      */
    ["com.cloud-notes.integration.readwise.disconnect.title"](): string;
    /**
      * `Once disconnected, content will no longer be imported. Do you want to keep your existing highlights in Cloud Notes?`
      */
    ["com.cloud-notes.integration.readwise.disconnect.desc"](): string;
    /**
      * `Keep`
      */
    ["com.cloud-notes.integration.readwise.disconnect.keep"](): string;
    /**
      * `Delete`
      */
    ["com.cloud-notes.integration.readwise.disconnect.delete"](): string;
    /**
      * `Highlights to be imported this time`
      */
    ["com.cloud-notes.integration.readwise.import.title"](): string;
    /**
      * `Importing everything from the start`
      */
    ["com.cloud-notes.integration.readwise.import.desc-from-start"](): string;
    /**
      * `Content`
      */
    ["com.cloud-notes.integration.readwise.import.cell-h-content"](): string;
    /**
      * `Todo`
      */
    ["com.cloud-notes.integration.readwise.import.cell-h-todo"](): string;
    /**
      * `Last update on Readwise`
      */
    ["com.cloud-notes.integration.readwise.import.cell-h-time"](): string;
    /**
      * `New`
      */
    ["com.cloud-notes.integration.readwise.import.todo-new"](): string;
    /**
      * `Skip`
      */
    ["com.cloud-notes.integration.readwise.import.todo-skip"](): string;
    /**
      * `Updated`
      */
    ["com.cloud-notes.integration.readwise.import.todo-update"](): string;
    /**
      * `No highlights needs to be imported`
      */
    ["com.cloud-notes.integration.readwise.import.empty"](): string;
    /**
      * `Importing...`
      */
    ["com.cloud-notes.integration.readwise.import.importing"](): string;
    /**
      * `Please keep this app active until it's finished`
      */
    ["com.cloud-notes.integration.readwise.import.importing-desc"](): string;
    /**
      * `Stop Importing`
      */
    ["com.cloud-notes.integration.readwise.import.importing-stop"](): string;
    /**
      * `Importing aborted`
      */
    ["com.cloud-notes.integration.readwise.import.abort-notify-title"](): string;
    /**
      * `Import aborted, with {{finished}} highlights processed`
      */
    ["com.cloud-notes.integration.readwise.import.abort-notify-desc"](options: {
        readonly finished: string;
    }): string;
    /**
      * `Configuration`
      */
    ["com.cloud-notes.integration.readwise.setting.caption"](): string;
    /**
      * `New Readwise highlights will be imported to Cloud Notes `
      */
    ["com.cloud-notes.integration.readwise.setting.sync-new-name"](): string;
    /**
      * `New highlights in Readwise will be synced to Cloud Notes `
      */
    ["com.cloud-notes.integration.readwise.setting.sync-new-desc"](): string;
    /**
      * `Updates to Readwise highlights will be imported`
      */
    ["com.cloud-notes.integration.readwise.setting.update-name"](): string;
    /**
      * `Enable this, so that we will process updates of existing highlights from Readwise `
      */
    ["com.cloud-notes.integration.readwise.setting.update-desc"](): string;
    /**
      * `How do we handle updates`
      */
    ["com.cloud-notes.integration.readwise.setting.update-strategy"](): string;
    /**
      * `Append new version to the end`
      */
    ["com.cloud-notes.integration.readwise.setting.update-append-name"](): string;
    /**
      * `Cited or modified highlights will have future versions added to the end of them`
      */
    ["com.cloud-notes.integration.readwise.setting.update-append-desc"](): string;
    /**
      * `Overwrite with new version`
      */
    ["com.cloud-notes.integration.readwise.setting.update-override-name"](): string;
    /**
      * `Cited or modified highlights will be overwritten if there are future updates`
      */
    ["com.cloud-notes.integration.readwise.setting.update-override-desc"](): string;
    /**
      * `Start Importing`
      */
    ["com.cloud-notes.integration.readwise.setting.start-import-name"](): string;
    /**
      * `Using the settings above`
      */
    ["com.cloud-notes.integration.readwise.setting.start-import-desc"](): string;
    /**
      * `Import`
      */
    ["com.cloud-notes.integration.readwise.setting.start-import-button"](): string;
    /**
      * `Apply tags to highlight imports`
      */
    ["com.cloud-notes.integration.readwise.setting.tags-label"](): string;
    /**
      * `Click to add tags`
      */
    ["com.cloud-notes.integration.readwise.setting.tags-placeholder"](): string;
    /**
      * `Author`
      */
    ["com.cloud-notes.integration.readwise-prop.author"](): string;
    /**
      * `Source`
      */
    ["com.cloud-notes.integration.readwise-prop.source"](): string;
    /**
      * `Created`
      */
    ["com.cloud-notes.integration.readwise-prop.created"](): string;
    /**
      * `Updated`
      */
    ["com.cloud-notes.integration.readwise-prop.updated"](): string;
    /**
      * `Integration properties`
      */
    ["com.cloud-notes.integration.properties"](): string;
    /**
      * `Calendar`
      */
    ["com.cloud-notes.integration.calendar.name"](): string;
    /**
      * `New events will be scheduled in Cloud Notes’s journal`
      */
    ["com.cloud-notes.integration.calendar.desc"](): string;
    /**
      * `Subscribe`
      */
    ["com.cloud-notes.integration.calendar.new-subscription"](): string;
    /**
      * `Unsubscribe`
      */
    ["com.cloud-notes.integration.calendar.unsubscribe"](): string;
    /**
      * `Add a calendar by URL`
      */
    ["com.cloud-notes.integration.calendar.new-title"](): string;
    /**
      * `Calendar URL`
      */
    ["com.cloud-notes.integration.calendar.new-url-label"](): string;
    /**
      * `An error occurred while saving the calendar settings`
      */
    ["com.cloud-notes.integration.calendar.save-error"](): string;
    /**
      * `All day`
      */
    ["com.cloud-notes.integration.calendar.all-day"](): string;
    /**
      * `Failed to load calendar accounts`
      */
    ["com.cloud-notes.integration.calendar.account.load-error"](): string;
    /**
      * `Failed to load calendar providers`
      */
    ["com.cloud-notes.integration.calendar.provider.load-error"](): string;
    /**
      * `Failed to start calendar authorization`
      */
    ["com.cloud-notes.integration.calendar.auth.start-error"](): string;
    /**
      * `Failed to unlink calendar account`
      */
    ["com.cloud-notes.integration.calendar.account.unlink-error"](): string;
    /**
      * `Unlink`
      */
    ["com.cloud-notes.integration.calendar.account.unlink"](): string;
    /**
      * `Link`
      */
    ["com.cloud-notes.integration.calendar.account.link"](): string;
    /**
      * `No calendar accounts linked yet.`
      */
    ["com.cloud-notes.integration.calendar.account.linked-empty"](): string;
    /**
      * `Authorization failed: {{error}}`
      */
    ["com.cloud-notes.integration.calendar.account.status.failed"](options: {
        readonly error: string;
    }): string;
    /**
      * `Authorization failed. Please reconnect your account.`
      */
    ["com.cloud-notes.integration.calendar.account.status.failed-reconnect"](): string;
    /**
      * `{{count}} calendar`
      */
    ["com.cloud-notes.integration.calendar.account.count"](options: {
        readonly count: string;
    }): string;
    /**
      * `Link CalDAV account`
      */
    ["com.cloud-notes.integration.calendar.caldav.link.title"](): string;
    /**
      * `Failed to link CalDAV account`
      */
    ["com.cloud-notes.integration.calendar.caldav.link.failed"](): string;
    /**
      * `Provider`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.provider"](): string;
    /**
      * `Select provider`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.provider.placeholder"](): string;
    /**
      * `Please select a provider.`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.provider.error"](): string;
    /**
      * `Username`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.username"](): string;
    /**
      * `email@example.com`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.username.placeholder"](): string;
    /**
      * `Username is required.`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.username.error"](): string;
    /**
      * `Password`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.password"](): string;
    /**
      * `Password or app-specific password`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.password.placeholder"](): string;
    /**
      * `Password is required.`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.password.error"](): string;
    /**
      * `Display name (optional)`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.displayName"](): string;
    /**
      * `My CalDAV`
      */
    ["com.cloud-notes.integration.calendar.caldav.field.displayName.placeholder"](): string;
    /**
      * `App-specific password required.`
      */
    ["com.cloud-notes.integration.calendar.caldav.hint.app-password"](): string;
    /**
      * `Learn more`
      */
    ["com.cloud-notes.integration.calendar.caldav.hint.learn-more"](): string;
    /**
      * `Provider setup guide`
      */
    ["com.cloud-notes.integration.calendar.caldav.hint.guide"](): string;
    /**
      * `New doc`
      */
    ["com.cloud-notes.integration.calendar.new-doc"](): string;
    /**
      * `Show calendar events`
      */
    ["com.cloud-notes.integration.calendar.show-events"](): string;
    /**
      * `Enabling this setting allows you to connect your calendar events to your Journal in Cloud Notes`
      */
    ["com.cloud-notes.integration.calendar.show-events-desc"](): string;
    /**
      * `Show all day event`
      */
    ["com.cloud-notes.integration.calendar.show-all-day-events"](): string;
    /**
      * `Are you sure you want to unsubscribe "{{name}}"? Unsubscribing this account will remove its data from Journal.`
      */
    ["com.cloud-notes.integration.calendar.unsubscribe-content"](options: {
        readonly name: string;
    }): string;
    /**
      * `No journal page found for {{date}}. Please create a journal page first.`
      */
    ["com.cloud-notes.integration.calendar.no-journal"](options: {
        readonly date: string;
    }): string;
    /**
      * `No subscribed calendars yet.`
      */
    ["com.cloud-notes.integration.calendar.no-calendar"](): string;
    /**
      * `MCP Server`
      */
    ["com.cloud-notes.integration.mcp-server.name"](): string;
    /**
      * `Enable other MCP Client to search and read the doc of Cloud Notes.`
      */
    ["com.cloud-notes.integration.mcp-server.desc"](): string;
    /**
      * `The MCP token is shown only once. Delete and recreate it to copy the JSON configuration.`
      */
    ["com.cloud-notes.integration.mcp-server.copy-json.disabled-hint"](): string;
    /**
      * `Credentials`
      */
    ["com.cloud-notes.integration.mcp-server.credentials.title"](): string;
    /**
      * `Use a separate credential for each MCP client so it can be revoked independently.`
      */
    ["com.cloud-notes.integration.mcp-server.credentials.description"](): string;
    /**
      * `Create credential`
      */
    ["com.cloud-notes.integration.mcp-server.action.create"](): string;
    /**
      * `Rotate`
      */
    ["com.cloud-notes.integration.mcp-server.action.rotate"](): string;
    /**
      * `Revoke credential`
      */
    ["com.cloud-notes.integration.mcp-server.action.revoke"](): string;
    /**
      * `Copy token`
      */
    ["com.cloud-notes.integration.mcp-server.action.copy-token"](): string;
    /**
      * `Copy JSON`
      */
    ["com.cloud-notes.integration.mcp-server.action.copy-json"](): string;
    /**
      * `Done`
      */
    ["com.cloud-notes.integration.mcp-server.action.done"](): string;
    /**
      * `Failed to load MCP credentials.`
      */
    ["com.cloud-notes.integration.mcp-server.load-error"](): string;
    /**
      * `No MCP credentials`
      */
    ["com.cloud-notes.integration.mcp-server.empty.title"](): string;
    /**
      * `Create a workspace-bound credential to connect an MCP client.`
      */
    ["com.cloud-notes.integration.mcp-server.empty.description"](): string;
    /**
      * `Read only`
      */
    ["com.cloud-notes.integration.mcp-server.access.read-only"](): string;
    /**
      * `Can read and search documents in this workspace using your current permissions.`
      */
    ["com.cloud-notes.integration.mcp-server.access.read-only-desc"](): string;
    /**
      * `Read and write`
      */
    ["com.cloud-notes.integration.mcp-server.access.read-write"](): string;
    /**
      * `Expires {{date}}`
      */
    ["com.cloud-notes.integration.mcp-server.meta.expires"](options: {
        readonly date: string;
    }): string;
    /**
      * `Created {{date}}`
      */
    ["com.cloud-notes.integration.mcp-server.meta.created"](options: {
        readonly date: string;
    }): string;
    /**
      * `Last used {{date}}`
      */
    ["com.cloud-notes.integration.mcp-server.meta.last-used"](options: {
        readonly date: string;
    }): string;
    /**
      * `Never used`
      */
    ["com.cloud-notes.integration.mcp-server.meta.never-used"](): string;
    /**
      * `Old token valid until {{date}}`
      */
    ["com.cloud-notes.integration.mcp-server.meta.grace-until"](options: {
        readonly date: string;
    }): string;
    /**
      * `Active`
      */
    ["com.cloud-notes.integration.mcp-server.status.active"](): string;
    /**
      * `Rotating`
      */
    ["com.cloud-notes.integration.mcp-server.status.rotating"](): string;
    /**
      * `Expiring`
      */
    ["com.cloud-notes.integration.mcp-server.status.expiring"](): string;
    /**
      * `Expired`
      */
    ["com.cloud-notes.integration.mcp-server.status.expired"](): string;
    /**
      * `Revoked`
      */
    ["com.cloud-notes.integration.mcp-server.status.revoked"](): string;
    /**
      * `Create MCP credential`
      */
    ["com.cloud-notes.integration.mcp-server.create.title"](): string;
    /**
      * `This credential will only work with this workspace's MCP endpoint.`
      */
    ["com.cloud-notes.integration.mcp-server.create.description"](): string;
    /**
      * `Label`
      */
    ["com.cloud-notes.integration.mcp-server.field.label"](): string;
    /**
      * `Access`
      */
    ["com.cloud-notes.integration.mcp-server.field.access"](): string;
    /**
      * `Expires in`
      */
    ["com.cloud-notes.integration.mcp-server.field.expiry"](): string;
    /**
      * `{{days}} days`
      */
    ["com.cloud-notes.integration.mcp-server.expiry.days"](options: {
        readonly days: string;
    }): string;
    /**
      * `MCP credential created`
      */
    ["com.cloud-notes.integration.mcp-server.reveal.title"](): string;
    /**
      * `Copy this credential now. You won’t be able to see it again.`
      */
    ["com.cloud-notes.integration.mcp-server.reveal.warning"](): string;
    /**
      * `The old token remains valid until {{date}}.`
      */
    ["com.cloud-notes.integration.mcp-server.reveal.old-valid-until"](options: {
        readonly date: string;
    }): string;
    /**
      * `Credential`
      */
    ["com.cloud-notes.integration.mcp-server.reveal.token"](): string;
    /**
      * `MCP configuration`
      */
    ["com.cloud-notes.integration.mcp-server.reveal.config"](): string;
    /**
      * `Rotate this credential?`
      */
    ["com.cloud-notes.integration.mcp-server.rotate.title"](): string;
    /**
      * `A new token will be created immediately. The old token remains valid for up to 24 hours so you can update the MCP client.`
      */
    ["com.cloud-notes.integration.mcp-server.rotate.description"](): string;
    /**
      * `Revoke “{{name}}”?`
      */
    ["com.cloud-notes.integration.mcp-server.revoke.title"](options: {
        readonly name: string;
    }): string;
    /**
      * `All generations of this credential will stop working immediately. This cannot be undone.`
      */
    ["com.cloud-notes.integration.mcp-server.revoke.description"](): string;
    /**
      * `Supported capabilities`
      */
    ["com.cloud-notes.integration.mcp-server.capabilities.title"](): string;
    /**
      * `Read documents`
      */
    ["com.cloud-notes.integration.mcp-server.capabilities.read"](): string;
    /**
      * `Keyword search`
      */
    ["com.cloud-notes.integration.mcp-server.capabilities.keyword-search"](): string;
    /**
      * `Semantic search`
      */
    ["com.cloud-notes.integration.mcp-server.capabilities.semantic-search"](): string;
    /**
      * `Create and update documents`
      */
    ["com.cloud-notes.integration.mcp-server.capabilities.write"](): string;
    /**
      * `Notes`
      */
    ["com.cloud-notes.audio.notes"](): string;
    /**
      * `Transcribing`
      */
    ["com.cloud-notes.audio.transcribing"](): string;
    /**
      * `Unable to retrieve AI results for others`
      */
    ["com.cloud-notes.audio.transcribe.non-owner.confirm.title"](): string;
    /**
      * `Audio activity`
      */
    ["com.cloud-notes.recording.new"](): string;
    /**
      * `Importing...`
      */
    ["com.cloud-notes.recording.importing.prompt"](): string;
    /**
      * `Finished`
      */
    ["com.cloud-notes.recording.success.prompt"](): string;
    /**
      * `Open app`
      */
    ["com.cloud-notes.recording.success.button"](): string;
    /**
      * `Failed to save`
      */
    ["com.cloud-notes.recording.failed.prompt"](): string;
    /**
      * `Open file`
      */
    ["com.cloud-notes.recording.failed.button"](): string;
    /**
      * `{{appName}}'s audio`
      */
    ["com.cloud-notes.recording.recording"](options: {
        readonly appName: string;
    }): string;
    /**
      * `Audio recording`
      */
    ["com.cloud-notes.recording.recording.unnamed"](): string;
    /**
      * `Start`
      */
    ["com.cloud-notes.recording.start"](): string;
    /**
      * `Dismiss`
      */
    ["com.cloud-notes.recording.dismiss"](): string;
    /**
      * `Stop`
      */
    ["com.cloud-notes.recording.stop"](): string;
    /**
      * `Migrate Data to Enhance User Experience`
      */
    ["com.cloud-notes.migration-all-docs-notification.header"](): string;
    /**
      * `We are updating the local data to facilitate the recording and filtering of created by and Last edited by information. Please click the “Migrate Data” button and ensure a stable network connection during the process.`
      */
    ["com.cloud-notes.migration-all-docs-notification.desc"](): string;
    /**
      * `Migration failed: {{errorMessage}}`
      */
    ["com.cloud-notes.migration-all-docs-notification.error"](options: {
        readonly errorMessage: string;
    }): string;
    /**
      * `Migrate data`
      */
    ["com.cloud-notes.migration-all-docs-notification.button"](): string;
    /**
      * `Comments`
      */
    ["com.cloud-notes.comment.comments"](): string;
    /**
      * `No comments yet, select content to add comment to`
      */
    ["com.cloud-notes.comment.no-comments"](): string;
    /**
      * `Delete the thread?`
      */
    ["com.cloud-notes.comment.delete.confirm.title"](): string;
    /**
      * `All comments will also be deleted, and this action cannot be undone.`
      */
    ["com.cloud-notes.comment.delete.confirm.description"](): string;
    /**
      * `Delete this reply?`
      */
    ["com.cloud-notes.comment.reply.delete.confirm.title"](): string;
    /**
      * `Delete this reply? This action cannot be undone.`
      */
    ["com.cloud-notes.comment.reply.delete.confirm.description"](): string;
    /**
      * `Show {{count}} more replies`
      */
    ["com.cloud-notes.comment.reply.show-more"](options: {
        readonly count: string;
    }): string;
    /**
      * `Show resolved comments`
      */
    ["com.cloud-notes.comment.filter.show-resolved"](): string;
    /**
      * `Only my replies and mentions`
      */
    ["com.cloud-notes.comment.filter.only-my-replies"](): string;
    /**
      * `Only current mode`
      */
    ["com.cloud-notes.comment.filter.only-current-mode"](): string;
    /**
      * `Unlock more features`
      */
    ["com.cloud-notes.payment.subscription.title"](): string;
    /**
      * `The universal editor that lets you work, play, present or create just about anything.`
      */
    ["com.cloud-notes.payment.subscription.description"](): string;
    /**
      * `Upgrade`
      */
    ["com.cloud-notes.payment.subscription.button"](): string;
    /**
      * `Reply`
      */
    ["com.cloud-notes.comment.reply"](): string;
    /**
      * `Copy link`
      */
    ["com.cloud-notes.comment.copy-link"](): string;
    /**
      * `Copy`
      */
    ["com.cloud-notes.context-menu.copy"](): string;
    /**
      * `Paste`
      */
    ["com.cloud-notes.context-menu.paste"](): string;
    /**
      * `Cut`
      */
    ["com.cloud-notes.context-menu.cut"](): string;
    /**
      * `Add icon`
      */
    ["com.cloud-notes.docIconPicker.placeholder"](): string;
    /**
      * `Devices`
      */
    ["com.cloud-notes.settings.devices.title"](): string;
    /**
      * `Devices with an active sign-in session`
      */
    ["com.cloud-notes.settings.devices.description"](): string;
    /**
      * `current`
      */
    ["com.cloud-notes.settings.devices.current"](): string;
    /**
      * `Last used {{time}}`
      */
    ["com.cloud-notes.settings.devices.last-used"](options: {
        readonly time: string;
    }): string;
    /**
      * `Loading…`
      */
    ["com.cloud-notes.settings.devices.loading"](): string;
    /**
      * `Sign out`
      */
    ["com.cloud-notes.settings.devices.sign-out"](): string;
    /**
      * `Sign out all devices`
      */
    ["com.cloud-notes.settings.devices.sign-out-all"](): string;
    /**
      * `Sign out {{device}}?`
      */
    ["com.cloud-notes.settings.devices.confirm"](options: {
        readonly device: string;
    }): string;
    /**
      * `Sign out every device?`
      */
    ["com.cloud-notes.settings.devices.confirm-all"](): string;
    /**
      * `Failed to load devices`
      */
    ["com.cloud-notes.settings.devices.load-failed"](): string;
    /**
      * `Failed to sign out device`
      */
    ["com.cloud-notes.settings.devices.sign-out-failed"](): string;
    /**
      * `Failed to sign out devices`
      */
    ["com.cloud-notes.settings.devices.sign-out-all-failed"](): string;
    /**
      * `Real-time connection failed`
      */
    ["com.cloud-notes.realtime.connection-error.title"](): string;
    /**
      * `The real-time connection could not authenticate your session. Sign in again if the problem continues.`
      */
    ["com.cloud-notes.realtime.connection-error.authentication"](): string;
    /**
      * `The server cannot be reached. Check your network connection and server proxy.`
      */
    ["com.cloud-notes.realtime.connection-error.network"](): string;
    /**
      * `The server rejected the real-time request. Try again later.`
      */
    ["com.cloud-notes.realtime.connection-error.server"](): string;
    /**
      * `The server did not respond in time. Cloud Notes will keep trying to reconnect.`
      */
    ["com.cloud-notes.realtime.connection-error.timeout"](): string;
    /**
      * `An internal error occurred.`
      */
    ["error.INTERNAL_SERVER_ERROR"](): string;
    /**
      * `Network error.`
      */
    ["error.NETWORK_ERROR"](): string;
    /**
      * `Too many requests.`
      */
    ["error.TOO_MANY_REQUEST"](): string;
    /**
      * `Resource not found.`
      */
    ["error.NOT_FOUND"](): string;
    /**
      * `Bad request.`
      */
    ["error.BAD_REQUEST"](): string;
    /**
      * `GraphQL bad request, code: {{code}}, {{message}}`
      */
    ["error.GRAPHQL_BAD_REQUEST"](options: Readonly<{
        code: string;
        message: string;
    }>): string;
    /**
      * `HTTP request error, message: {{message}}`
      */
    ["error.HTTP_REQUEST_ERROR"](options: {
        readonly message: string;
    }): string;
    /**
      * `URL blocked by SSRF protection: {{reason}}`
      */
    ["error.SSRF_BLOCKED_ERROR"](options: {
        readonly reason: string;
    }): string;
    /**
      * `Response too large ({{receivedBytes}} bytes), limit is {{limitBytes}} bytes`
      */
    ["error.RESPONSE_TOO_LARGE_ERROR"](options: Readonly<{
        receivedBytes: string;
        limitBytes: string;
    }>): string;
    /**
      * `Email service is not configured.`
      */
    ["error.EMAIL_SERVICE_NOT_CONFIGURED"](): string;
    /**
      * `Image format not supported: {{format}}`
      */
    ["error.IMAGE_FORMAT_NOT_SUPPORTED"](options: {
        readonly format: string;
    }): string;
    /**
      * `Query is too long, max length is {{max}}.`
      */
    ["error.QUERY_TOO_LONG"](options: {
        readonly max: string;
    }): string;
    /**
      * `Validation error, errors: {{errors}}`
      */
    ["error.VALIDATION_ERROR"](options: {
        readonly errors: string;
    }): string;
    /**
      * `User not found.`
      */
    ["error.USER_NOT_FOUND"](): string;
    /**
      * `User avatar not found.`
      */
    ["error.USER_AVATAR_NOT_FOUND"](): string;
    /**
      * `This email has already been registered.`
      */
    ["error.EMAIL_ALREADY_USED"](): string;
    /**
      * `You are trying to update your account email to the same as the old one.`
      */
    ["error.SAME_EMAIL_PROVIDED"](): string;
    /**
      * `Wrong user email or password: {{email}}`
      */
    ["error.WRONG_SIGN_IN_CREDENTIALS"](options: {
        readonly email: string;
    }): string;
    /**
      * `Unknown authentication provider {{name}}.`
      */
    ["error.UNKNOWN_OAUTH_PROVIDER"](options: {
        readonly name: string;
    }): string;
    /**
      * `OAuth state expired, please try again.`
      */
    ["error.OAUTH_STATE_EXPIRED"](): string;
    /**
      * `Invalid callback state parameter.`
      */
    ["error.INVALID_OAUTH_CALLBACK_STATE"](): string;
    /**
      * `Invalid callback code parameter, provider response status: {{status}} and body: {{body}}.`
      */
    ["error.INVALID_OAUTH_CALLBACK_CODE"](options: Readonly<{
        status: string;
        body: string;
    }>): string;
    /**
      * `Invalid auth state. You might start the auth progress from another device.`
      */
    ["error.INVALID_AUTH_STATE"](): string;
    /**
      * `Missing query parameter `{{name}}`.`
      */
    ["error.MISSING_OAUTH_QUERY_PARAMETER"](options: {
        readonly name: string;
    }): string;
    /**
      * `The third-party account has already been connected to another user.`
      */
    ["error.OAUTH_ACCOUNT_ALREADY_CONNECTED"](): string;
    /**
      * `Invalid OAuth response: {{reason}}.`
      */
    ["error.INVALID_OAUTH_RESPONSE"](options: {
        readonly reason: string;
    }): string;
    /**
      * `An invalid email provided: {{email}}`
      */
    ["error.INVALID_EMAIL"](options: {
        readonly email: string;
    }): string;
    /**
      * `Password must be between {{min}} and {{max}} characters`
      */
    ["error.INVALID_PASSWORD_LENGTH"](options: Readonly<{
        min: string;
        max: string;
    }>): string;
    /**
      * `Password is required.`
      */
    ["error.PASSWORD_REQUIRED"](): string;
    /**
      * `You are trying to sign in by a different method than you signed up with.`
      */
    ["error.WRONG_SIGN_IN_METHOD"](): string;
    /**
      * `You are not allowed to sign up.`
      */
    ["error.SIGN_UP_FORBIDDEN"](): string;
    /**
      * `The email token provided is not found.`
      */
    ["error.EMAIL_TOKEN_NOT_FOUND"](): string;
    /**
      * `An invalid email token provided.`
      */
    ["error.INVALID_EMAIL_TOKEN"](): string;
    /**
      * `The link has expired.`
      */
    ["error.LINK_EXPIRED"](): string;
    /**
      * `You must sign in first to access this resource.`
      */
    ["error.AUTHENTICATION_REQUIRED"](): string;
    /**
      * `The access token has expired.`
      */
    ["error.ACCESS_TOKEN_EXPIRED"](): string;
    /**
      * `The access token is invalid.`
      */
    ["error.ACCESS_TOKEN_INVALID"](): string;
    /**
      * `The auth session has expired.`
      */
    ["error.AUTH_SESSION_EXPIRED"](): string;
    /**
      * `The auth session has been revoked.`
      */
    ["error.AUTH_SESSION_REVOKED"](): string;
    /**
      * `The refresh token is invalid.`
      */
    ["error.REFRESH_TOKEN_INVALID"](): string;
    /**
      * `The refresh token has already been used.`
      */
    ["error.REFRESH_TOKEN_REUSED"](): string;
    /**
      * `Auth session service is temporarily unavailable.`
      */
    ["error.AUTH_SESSION_TEMPORARILY_UNAVAILABLE"](): string;
    /**
      * `You are not allowed to perform this action.`
      */
    ["error.ACTION_FORBIDDEN"](): string;
    /**
      * `You do not have permission to access this resource.`
      */
    ["error.ACCESS_DENIED"](): string;
    /**
      * `You must verify your email before accessing this resource.`
      */
    ["error.EMAIL_VERIFICATION_REQUIRED"](): string;
    /**
      * `Space {{spaceId}} permission not found.`
      */
    ["error.WORKSPACE_PERMISSION_NOT_FOUND"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `Space {{spaceId}} not found.`
      */
    ["error.SPACE_NOT_FOUND"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `Member not found in Space {{spaceId}}.`
      */
    ["error.MEMBER_NOT_FOUND_IN_SPACE"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `You should join in Space {{spaceId}} before broadcasting messages.`
      */
    ["error.NOT_IN_SPACE"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `You have already joined in Space {{spaceId}}.`
      */
    ["error.ALREADY_IN_SPACE"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `You do not have permission to access Space {{spaceId}}.`
      */
    ["error.SPACE_ACCESS_DENIED"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `Permissions for Space {{spaceId}} changed during synchronization.`
      */
    ["error.SYNC_PERMISSION_GENERATION_CHANGED"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `Owner of Space {{spaceId}} not found.`
      */
    ["error.SPACE_OWNER_NOT_FOUND"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `Space should have only one owner.`
      */
    ["error.SPACE_SHOULD_HAVE_ONLY_ONE_OWNER"](): string;
    /**
      * `Owner can not leave the workspace.`
      */
    ["error.OWNER_CAN_NOT_LEAVE_WORKSPACE"](): string;
    /**
      * `You can not revoke your own permission.`
      */
    ["error.CAN_NOT_REVOKE_YOURSELF"](): string;
    /**
      * `Doc {{docId}} under Space {{spaceId}} not found.`
      */
    ["error.DOC_NOT_FOUND"](options: Readonly<{
        docId: string;
        spaceId: string;
    }>): string;
    /**
      * `You do not have permission to perform {{action}} action on doc {{docId}}.`
      */
    ["error.DOC_ACTION_DENIED"](options: Readonly<{
        action: string;
        docId: string;
    }>): string;
    /**
      * `Doc {{docId}} under Space {{spaceId}} is blocked from updating.`
      */
    ["error.DOC_UPDATE_BLOCKED"](options: Readonly<{
        docId: string;
        spaceId: string;
    }>): string;
    /**
      * `Your client with version {{version}} is rejected by remote sync server. Please upgrade to {{serverVersion}}.`
      */
    ["error.VERSION_REJECTED"](options: Readonly<{
        version: string;
        serverVersion: string;
    }>): string;
    /**
      * `Invalid doc history timestamp provided.`
      */
    ["error.INVALID_HISTORY_TIMESTAMP"](): string;
    /**
      * `History of {{docId}} at {{timestamp}} under Space {{spaceId}}.`
      */
    ["error.DOC_HISTORY_NOT_FOUND"](options: Readonly<{
        docId: string;
        timestamp: string;
        spaceId: string;
    }>): string;
    /**
      * `Blob {{blobId}} not found in Space {{spaceId}}.`
      */
    ["error.BLOB_NOT_FOUND"](options: Readonly<{
        blobId: string;
        spaceId: string;
    }>): string;
    /**
      * `Blob is invalid.`
      */
    ["error.BLOB_INVALID"](): string;
    /**
      * `Expected to publish a doc, not a Space.`
      */
    ["error.EXPECT_TO_PUBLISH_DOC"](): string;
    /**
      * `Expected to revoke a public doc, not a Space.`
      */
    ["error.EXPECT_TO_REVOKE_PUBLIC_DOC"](): string;
    /**
      * `Expect grant roles on doc {{docId}} under Space {{spaceId}}, not a Space.`
      */
    ["error.EXPECT_TO_GRANT_DOC_USER_ROLES"](options: Readonly<{
        docId: string;
        spaceId: string;
    }>): string;
    /**
      * `Expect revoke roles on doc {{docId}} under Space {{spaceId}}, not a Space.`
      */
    ["error.EXPECT_TO_REVOKE_DOC_USER_ROLES"](options: Readonly<{
        docId: string;
        spaceId: string;
    }>): string;
    /**
      * `Expect update roles on doc {{docId}} under Space {{spaceId}}, not a Space.`
      */
    ["error.EXPECT_TO_UPDATE_DOC_USER_ROLE"](options: Readonly<{
        docId: string;
        spaceId: string;
    }>): string;
    /**
      * `Doc is not public.`
      */
    ["error.DOC_IS_NOT_PUBLIC"](): string;
    /**
      * `Failed to store doc updates.`
      */
    ["error.FAILED_TO_SAVE_UPDATES"](): string;
    /**
      * `Failed to store doc snapshot.`
      */
    ["error.FAILED_TO_UPSERT_SNAPSHOT"](): string;
    /**
      * `A Team workspace is required to perform this action.`
      */
    ["error.ACTION_FORBIDDEN_ON_NON_TEAM_WORKSPACE"](): string;
    /**
      * `Doc default role can not be owner.`
      */
    ["error.DOC_DEFAULT_ROLE_CAN_NOT_BE_OWNER"](): string;
    /**
      * `Can not batch grant doc owner permissions.`
      */
    ["error.CAN_NOT_BATCH_GRANT_DOC_OWNER_PERMISSIONS"](): string;
    /**
      * `Can not set a non-active member as owner.`
      */
    ["error.NEW_OWNER_IS_NOT_ACTIVE_MEMBER"](): string;
    /**
      * `Invalid invitation provided.`
      */
    ["error.INVALID_INVITATION"](): string;
    /**
      * `This invitation belongs to another account.`
      */
    ["error.INVITATION_ACCOUNT_MISMATCH"](): string;
    /**
      * `No more seat available in the Space {{spaceId}}.`
      */
    ["error.NO_MORE_SEAT"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `Unsupported subscription plan: {{plan}}.`
      */
    ["error.UNSUPPORTED_SUBSCRIPTION_PLAN"](options: {
        readonly plan: string;
    }): string;
    /**
      * `Failed to create checkout session.`
      */
    ["error.FAILED_TO_CHECKOUT"](): string;
    /**
      * `Invalid checkout parameters provided.`
      */
    ["error.INVALID_CHECKOUT_PARAMETERS"](): string;
    /**
      * `You have already subscribed to the {{plan}} plan.`
      */
    ["error.SUBSCRIPTION_ALREADY_EXISTS"](options: {
        readonly plan: string;
    }): string;
    /**
      * `Invalid subscription parameters provided.`
      */
    ["error.INVALID_SUBSCRIPTION_PARAMETERS"](): string;
    /**
      * `You didn't subscribe to the {{plan}} plan.`
      */
    ["error.SUBSCRIPTION_NOT_EXISTS"](options: {
        readonly plan: string;
    }): string;
    /**
      * `Your subscription has already been canceled.`
      */
    ["error.SUBSCRIPTION_HAS_BEEN_CANCELED"](): string;
    /**
      * `Your subscription has not been canceled.`
      */
    ["error.SUBSCRIPTION_HAS_NOT_BEEN_CANCELED"](): string;
    /**
      * `Your subscription has expired.`
      */
    ["error.SUBSCRIPTION_EXPIRED"](): string;
    /**
      * `Your subscription has already been in {{recurring}} recurring state.`
      */
    ["error.SAME_SUBSCRIPTION_RECURRING"](options: {
        readonly recurring: string;
    }): string;
    /**
      * `Failed to create customer portal session.`
      */
    ["error.CUSTOMER_PORTAL_CREATE_FAILED"](): string;
    /**
      * `You are trying to access a unknown subscription plan.`
      */
    ["error.SUBSCRIPTION_PLAN_NOT_FOUND"](): string;
    /**
      * `You cannot update an onetime payment subscription.`
      */
    ["error.CANT_UPDATE_ONETIME_PAYMENT_SUBSCRIPTION"](): string;
    /**
      * `A workspace is required to checkout for team subscription.`
      */
    ["error.WORKSPACE_ID_REQUIRED_FOR_TEAM_SUBSCRIPTION"](): string;
    /**
      * `Workspace id is required to update team subscription.`
      */
    ["error.WORKSPACE_ID_REQUIRED_TO_UPDATE_TEAM_SUBSCRIPTION"](): string;
    /**
      * `This subscription is managed by App Store or Google Play. Please manage it in the corresponding store.`
      */
    ["error.MANAGED_BY_APP_STORE_OR_PLAY"](): string;
    /**
      * `Calendar provider request error, status: {{status}}, message: {{message}}`
      */
    ["error.CALENDAR_PROVIDER_REQUEST_ERROR"](options: Readonly<{
        status: string;
        message: string;
    }>): string;
    /**
      * `Copilot session not found.`
      */
    ["error.COPILOT_SESSION_NOT_FOUND"](): string;
    /**
      * `Copilot session input is invalid.`
      */
    ["error.COPILOT_SESSION_INVALID_INPUT"](): string;
    /**
      * `Copilot session has been deleted.`
      */
    ["error.COPILOT_SESSION_DELETED"](): string;
    /**
      * `No copilot provider available: {{modelId}}`
      */
    ["error.NO_COPILOT_PROVIDER_AVAILABLE"](options: {
        readonly modelId: string;
    }): string;
    /**
      * `Failed to generate text.`
      */
    ["error.COPILOT_FAILED_TO_GENERATE_TEXT"](): string;
    /**
      * `Failed to generate embedding with {{provider}}: {{message}}`
      */
    ["error.COPILOT_FAILED_TO_GENERATE_EMBEDDING"](options: Readonly<{
        provider: string;
        message: string;
    }>): string;
    /**
      * `Failed to create chat message.`
      */
    ["error.COPILOT_FAILED_TO_CREATE_MESSAGE"](): string;
    /**
      * `Unsplash is not configured.`
      */
    ["error.UNSPLASH_IS_NOT_CONFIGURED"](): string;
    /**
      * `Action has been taken, no more messages allowed.`
      */
    ["error.COPILOT_ACTION_TAKEN"](): string;
    /**
      * `Doc {{docId}} not found.`
      */
    ["error.COPILOT_DOC_NOT_FOUND"](options: {
        readonly docId: string;
    }): string;
    /**
      * `Some docs not found.`
      */
    ["error.COPILOT_DOCS_NOT_FOUND"](): string;
    /**
      * `Copilot message {{messageId}} not found.`
      */
    ["error.COPILOT_MESSAGE_NOT_FOUND"](options: {
        readonly messageId: string;
    }): string;
    /**
      * `Copilot prompt {{name}} not found.`
      */
    ["error.COPILOT_PROMPT_NOT_FOUND"](options: {
        readonly name: string;
    }): string;
    /**
      * `Copilot prompt is invalid.`
      */
    ["error.COPILOT_PROMPT_INVALID"](): string;
    /**
      * `Copilot provider {{provider}} does not support output type {{kind}}`
      */
    ["error.COPILOT_PROVIDER_NOT_SUPPORTED"](options: Readonly<{
        provider: string;
        kind: string;
    }>): string;
    /**
      * `Provider {{provider}} failed with {{kind}} error: {{message}}`
      */
    ["error.COPILOT_PROVIDER_SIDE_ERROR"](options: Readonly<{
        provider: string;
        kind: string;
        message: string;
    }>): string;
    /**
      * `Embedding feature is disabled, please contact the administrator to enable it in the workspace settings.`
      */
    ["error.COPILOT_EMBEDDING_DISABLED"](): string;
    /**
      * `Embedding feature not available, you may need to install pgvector extension to your database`
      */
    ["error.COPILOT_EMBEDDING_UNAVAILABLE"](): string;
    /**
      * `Selected sources are still processing. Try again shortly.`
      */
    ["error.COPILOT_SELECTED_SOURCES_PROCESSING"](): string;
    /**
      * `Selected sources could not be processed. Remove the failed source or try again.`
      */
    ["error.COPILOT_SELECTED_SOURCES_FAILED"](): string;
    /**
      * `Selected sources are not available for AI retrieval.`
      */
    ["error.COPILOT_SELECTED_SOURCES_UNAVAILABLE"](): string;
    /**
      * `Too many or too much content was selected. Select fewer sources and try again.`
      */
    ["error.COPILOT_SELECTED_SOURCES_LIMIT_EXCEEDED"](): string;
    /**
      * `Failed to add workspace artifact: {{message}}`
      */
    ["error.COPILOT_FAILED_TO_ADD_WORKSPACE_ARTIFACT"](options: {
        readonly message: string;
    }): string;
    /**
      * `Transcription job already exists`
      */
    ["error.COPILOT_TRANSCRIPTION_JOB_EXISTS"](): string;
    /**
      * `Transcription job not found.`
      */
    ["error.COPILOT_TRANSCRIPTION_JOB_NOT_FOUND"](): string;
    /**
      * `Audio not provided.`
      */
    ["error.COPILOT_TRANSCRIPTION_AUDIO_NOT_PROVIDED"](): string;
    /**
      * `You have exceeded your blob size quota.`
      */
    ["error.BLOB_QUOTA_EXCEEDED"](): string;
    /**
      * `You have exceeded your storage quota.`
      */
    ["error.STORAGE_QUOTA_EXCEEDED"](): string;
    /**
      * `You have exceeded your workspace member quota.`
      */
    ["error.MEMBER_QUOTA_EXCEEDED"](): string;
    /**
      * `You have reached the limit of actions in this workspace, please upgrade your plan.`
      */
    ["error.COPILOT_QUOTA_EXCEEDED"](): string;
    /**
      * `Runtime config {{key}} not found.`
      */
    ["error.RUNTIME_CONFIG_NOT_FOUND"](options: {
        readonly key: string;
    }): string;
    /**
      * `Invalid runtime config type  for '{{key}}', want '{{want}}', but get {{get}}.`
      */
    ["error.INVALID_RUNTIME_CONFIG_TYPE"](options: Readonly<{
        key: string;
        want: string;
        get: string;
    }>): string;
    /**
      * `Mailer service is not configured.`
      */
    ["error.MAILER_SERVICE_IS_NOT_CONFIGURED"](): string;
    /**
      * `Cannot delete all admin accounts.`
      */
    ["error.CANNOT_DELETE_ALL_ADMIN_ACCOUNT"](): string;
    /**
      * `Cannot delete own account.`
      */
    ["error.CANNOT_DELETE_OWN_ACCOUNT"](): string;
    /**
      * `Cannot delete account. You are the owner of one or more team workspaces. Please transfer ownership or delete them first.`
      */
    ["error.CANNOT_DELETE_ACCOUNT_WITH_OWNED_TEAM_WORKSPACE"](): string;
    /**
      * `Captcha verification failed.`
      */
    ["error.CAPTCHA_VERIFICATION_FAILED"](): string;
    /**
      * `Invalid session id to generate license key.`
      */
    ["error.INVALID_LICENSE_SESSION_ID"](): string;
    /**
      * `License key has been revealed. Please check your mail box of the one provided during checkout.`
      */
    ["error.LICENSE_REVEALED"](): string;
    /**
      * `Workspace already has a license applied.`
      */
    ["error.WORKSPACE_LICENSE_ALREADY_EXISTS"](): string;
    /**
      * `License not found.`
      */
    ["error.LICENSE_NOT_FOUND"](): string;
    /**
      * `Invalid license to activate. {{reason}}`
      */
    ["error.INVALID_LICENSE_TO_ACTIVATE"](options: {
        readonly reason: string;
    }): string;
    /**
      * `Invalid license update params. {{reason}}`
      */
    ["error.INVALID_LICENSE_UPDATE_PARAMS"](options: {
        readonly reason: string;
    }): string;
    /**
      * `License has expired.`
      */
    ["error.LICENSE_EXPIRED"](): string;
    /**
      * `Unsupported client with version [{{clientVersion}}], required version is [{{requiredVersion}}].`
      */
    ["error.UNSUPPORTED_CLIENT_VERSION"](options: Readonly<{
        clientVersion: string;
        requiredVersion: string;
    }>): string;
    /**
      * `This Cloud Notes server is too old for this client. Please upgrade the server to {{requiredVersion}}.`
      */
    ["error.UNSUPPORTED_SERVER_VERSION"](options: {
        readonly requiredVersion: string;
    }): string;
    /**
      * `Notification not found.`
      */
    ["error.NOTIFICATION_NOT_FOUND"](): string;
    /**
      * `Mentioned user can not access doc {{docId}}.`
      */
    ["error.MENTION_USER_DOC_ACCESS_DENIED"](options: {
        readonly docId: string;
    }): string;
    /**
      * `You can not mention yourself.`
      */
    ["error.MENTION_USER_ONESELF_DENIED"](): string;
    /**
      * `Invalid app config for module `{{module}}` with key `{{key}}`. {{hint}}.`
      */
    ["error.INVALID_APP_CONFIG"](options: Readonly<{
        module: string;
        key: string;
        hint: string;
    }>): string;
    /**
      * `Invalid app config input: {{message}}`
      */
    ["error.INVALID_APP_CONFIG_INPUT"](options: {
        readonly message: string;
    }): string;
    /**
      * `Search index for Space {{spaceId}} is not ready yet.`
      */
    ["error.SEARCH_INDEX_NOT_READY"](options: {
        readonly spaceId: string;
    }): string;
    /**
      * `Search permissions are still syncing. Please try again shortly.`
      */
    ["error.SEARCH_PERMISSION_SYNCING"](): string;
    /**
      * `Search provider is temporarily unavailable.`
      */
    ["error.SEARCH_PROVIDER_UNAVAILABLE"](): string;
    /**
      * `Search index is temporarily unavailable.`
      */
    ["error.SEARCH_INDEX_FAILED"](): string;
    /**
      * `Search provider not found.`
      */
    ["error.SEARCH_PROVIDER_NOT_FOUND"](): string;
    /**
      * `Invalid request argument to search provider: {{reason}}`
      */
    ["error.INVALID_SEARCH_PROVIDER_REQUEST"](options: {
        readonly reason: string;
    }): string;
    /**
      * `Invalid indexer input: {{reason}}`
      */
    ["error.INVALID_INDEXER_INPUT"](options: {
        readonly reason: string;
    }): string;
    /**
      * `Comment not found.`
      */
    ["error.COMMENT_NOT_FOUND"](): string;
    /**
      * `Reply not found.`
      */
    ["error.REPLY_NOT_FOUND"](): string;
    /**
      * `Comment attachment not found.`
      */
    ["error.COMMENT_ATTACHMENT_NOT_FOUND"](): string;
    /**
      * `You have exceeded the comment attachment size quota.`
      */
    ["error.COMMENT_ATTACHMENT_QUOTA_EXCEEDED"](): string;
} { const { t } = useTranslation(); return useMemo(() => createProxy((key) => t.bind(null, key)), [t]); }
function createComponent(i18nKey: string) {
    return (props) => createElement(Trans, { i18nKey, shouldUnescape: true, ...props });
}
export const TypedTrans: {
    /**
      * `Go to <a>{{link}}</a> for learn more details about Cloud Notes AI.`
      */
    ["com.cloud-notes.ai-onboarding.general.5.description"]: ComponentType<TypedTransProps<{
        readonly link: string;
    }, {
        a: JSX.Element;
    }>>;
    /**
      * `By continuing, you are agreeing to our <a>AI Terms</a>.`
      */
    ["com.cloud-notes.ai-onboarding.general.privacy"]: ComponentType<TypedTransProps<Readonly<{}>, {
        a: JSX.Element;
    }>>;
    /**
      * `Opening <1>Cloud Notes</1> app now`
      */
    ["com.cloud-notes.auth.open.cloud-notes.prompt"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `This doc is now opened in <1>Cloud Notes</1> app`
      */
    ["com.cloud-notes.auth.open.cloud-notes.open-doc-prompt"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `To continue signing in, please enter the code that was sent to <a>{{email}}</a>.`
      */
    ["com.cloud-notes.auth.sign.auth.code.hint"]: ComponentType<TypedTransProps<{
        readonly email: string;
    }, {
        a: JSX.Element;
    }>>;
    /**
      * `Or <1>sign in with password</1> instead.`
      */
    ["com.cloud-notes.auth.sign.auth.code.message.password"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `This is a Cloud Notes server hosted by you or your team. After signing in, workspace data is saved to the Cloud Notes server you enter, not to Cloud Notes. <1>Learn more about self-hosting.</1>`
      */
    ["com.cloud-notes.auth.sign.add-selfhosted.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `By clicking “Continue with Google/Email” above, you acknowledge that you agree to Cloud Notes's <1>Terms of Conditions</1> and <3>Privacy Policy</3>.`
      */
    ["com.cloud-notes.auth.sign.message"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
        ["3"]: JSX.Element;
    }>>;
    /**
      * `This demo is limited. <1>Download the Cloud Notes Client</1> for the latest features and Performance.`
      */
    ["com.cloud-notes.banner.content"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> selected`
    
      * - com.cloud-notes.collection.toolbar.selected_one: `<0>{{count}}</0> collection selected`
    
      * - com.cloud-notes.collection.toolbar.selected_other: `<0>{{count}}</0> collection(s) selected`
      */
    ["com.cloud-notes.collection.toolbar.selected"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> collection selected`
      */
    ["com.cloud-notes.collection.toolbar.selected_one"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> collection(s) selected`
      */
    ["com.cloud-notes.collection.toolbar.selected_other"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> collection(s) selected`
      */
    ["com.cloud-notes.collection.toolbar.selected_others"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `Deleting <1>{{tag}}</1> cannot be undone, please proceed with caution.`
      */
    ["com.cloud-notes.delete-tags.confirm.description"]: ComponentType<TypedTransProps<{
        readonly tag: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Selected <1>{{selectedCount}}</1>, filtered <3>{{filteredCount}}</3>`
      */
    ["com.cloud-notes.editCollection.rules.countTips"]: ComponentType<TypedTransProps<Readonly<{
        selectedCount: string;
        filteredCount: string;
    }>, {
        ["1"]: JSX.Element;
        ["3"]: JSX.Element;
    }>>;
    /**
      * `Showing <1>{{count}}</1> docs.`
      */
    ["com.cloud-notes.editCollection.rules.countTips.more"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Showing <1>{{count}}</1> doc.`
      */
    ["com.cloud-notes.editCollection.rules.countTips.one"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Showing <1>{{count}}</1> docs.`
      */
    ["com.cloud-notes.editCollection.rules.countTips.zero"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Please <1>add rules</1> to save this collection or switch to <3>Docs</3>, use manual selection mode`
      */
    ["com.cloud-notes.editCollection.rules.empty.noRules.tips"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
        ["3"]: JSX.Element;
    }>>;
    /**
      * `Docs that meet the rules will be added to the current collection <2>{{highlight}}</2>`
      */
    ["com.cloud-notes.editCollection.rules.tips"]: ComponentType<TypedTransProps<{
        readonly highlight: string;
    }, {
        ["2"]: JSX.Element;
    }>>;
    /**
      * `If you are still experiencing this issue, please <1>contact us through the community</1>.`
      */
    ["com.cloud-notes.error.contact-us"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `With the workspace creator's free account, every member can access up to <1>7 days<1> of version history.`
      */
    ["com.cloud-notes.history.confirm-restore-modal.free-plan-prompt.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `With the workspace creator's Pro account, every member enjoys the privilege of accessing up to <1>30 days<1> of version history.`
      */
    ["com.cloud-notes.history.confirm-restore-modal.pro-plan-prompt.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> selected`
    
      * - com.cloud-notes.page.toolbar.selected_one: `<0>{{count}}</0> doc selected`
    
      * - com.cloud-notes.page.toolbar.selected_other: `<0>{{count}}</0> doc(s) selected`
      */
    ["com.cloud-notes.page.toolbar.selected"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> doc selected`
      */
    ["com.cloud-notes.page.toolbar.selected_one"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> doc(s) selected`
      */
    ["com.cloud-notes.page.toolbar.selected_other"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> doc(s) selected`
      */
    ["com.cloud-notes.page.toolbar.selected_others"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `You are currently on the <a>free plan</a>.`
      */
    ["com.cloud-notes.payment.billing-setting.ai.free-desc"]: ComponentType<TypedTransProps<Readonly<{}>, {
        a: JSX.Element;
    }>>;
    /**
      * `You have purchased <a>Believer plan</a>. Enjoy with your benefits!`
      */
    ["com.cloud-notes.payment.billing-setting.believer.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        a: JSX.Element;
    }>>;
    /**
      * `You are currently on the <1>{{planName}} plan</1>.`
      */
    ["com.cloud-notes.payment.billing-setting.current-plan.description"]: ComponentType<TypedTransProps<{
        readonly planName: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `You are currently on the believer <1>{{planName}} plan</1>.`
      */
    ["com.cloud-notes.payment.billing-setting.current-plan.description.lifetime"]: ComponentType<TypedTransProps<{
        readonly planName: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `You are currently on the monthly <1>{{planName}} plan</1>.`
      */
    ["com.cloud-notes.payment.billing-setting.current-plan.description.monthly"]: ComponentType<TypedTransProps<{
        readonly planName: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `You are currently on the annually <1>{{planName}} plan</1>.`
      */
    ["com.cloud-notes.payment.billing-setting.current-plan.description.yearly"]: ComponentType<TypedTransProps<{
        readonly planName: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `One-time Purchase. Personal use rights for up to 150 years. <a>Fair Usage Policies</a> may apply.`
      */
    ["com.cloud-notes.payment.lifetime.caption-2"]: ComponentType<TypedTransProps<Readonly<{}>, {
        a: JSX.Element;
    }>>;
    /**
      * `You are currently on the {{currentPlan}} plan. If you have any questions, please contact our <3>customer support</3>.`
      */
    ["com.cloud-notes.payment.subtitle-active"]: ComponentType<TypedTransProps<{
        readonly currentPlan: string;
    }, {
        ["3"]: JSX.Element;
    }>>;
    /**
      * `If you have any questions, please contact our <1> customer support</1>.`
      */
    ["com.cloud-notes.payment.upgrade-success-page.support"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `If you have any questions, please contact our <1>customer support</1>.`
      */
    ["com.cloud-notes.payment.upgrade-success-page.team.text-2"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `If you have any questions, please contact our <1>customer support</1>.`
      */
    ["com.cloud-notes.payment.license-success.text-2"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `This action deletes the old Favorites section. <b>Your documents are safe</b>, ensure you've moved your frequently accessed documents to the new personal Favorites section.`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.clean-all.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        b: JSX.Element;
    }>>;
    /**
      * `<b>Your documents are safe</b>, but you'll need to re-pin your most-used ones. "Favorites" are now personal. Move items from the old shared section to your new personal section or remove the old one by clicking "Empty the old favorites" now.`
      */
    ["com.cloud-notes.rootAppSidebar.migration-data.help.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        b: JSX.Element;
    }>>;
    /**
      * `No doc titles contain <1>{{search}}</1>`
      */
    ["com.cloud-notes.selectPage.empty.tips"]: ComponentType<TypedTransProps<{
        readonly search: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Are you sure you want to delete your account from <1>{{server}}</1>?`
      */
    ["com.cloud-notes.setting.account.delete.confirm-delete-description-1"]: ComponentType<TypedTransProps<{
        readonly server: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Your account will be inaccessible, and your personal space on the server will be permanently deleted. You can remove local data by uninstalling the app or clearing your browser storage. <1>This action is irreversible.</1>`
      */
    ["com.cloud-notes.setting.account.delete.confirm-delete-description-2"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Type <1>{{email}}</1> to confirm account deletion.`
      */
    ["com.cloud-notes.setting.account.delete.email-confirm-description"]: ComponentType<TypedTransProps<{
        readonly email: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Don't have the app? <1>Click to download</1>.`
      */
    ["com.cloud-notes.open-in-app.card.subtitle"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Settings changed; please restart the app. <1>Restart</1>`
      */
    ["com.cloud-notes.settings.editorSettings.general.spell-check.restart-hint"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Love our app? <1>Star us on GitHub</1> and <2>create issues</2> for your valuable feedback!`
      */
    ["com.cloud-notes.settings.suggestion-2"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `Meeting Features Available <strong>Free</strong> in Beta Phase`
      */
    ["com.cloud-notes.settings.meetings.setting.prompt.2"]: ComponentType<TypedTransProps<Readonly<{}>, {
        strong: JSX.Element;
    }>>;
    /**
      * `<strong>Where AI meets your meetings - cloud-notes your collaboration.</strong>
    <ul><li>Extract Action Items & Key Insights Instantly</li><li>Smart Auto-Capture Starts With Your Meeting</li><li>Seamless Integration Across All Meeting Platforms</li><li>One Unified Space for All Your Meeting's Context</li><li>Your AI Assistant with Every Meeting Context Preserved</li></ul>`
      */
    ["com.cloud-notes.settings.meetings.setting.welcome.hints"]: ComponentType<TypedTransProps<Readonly<{}>, {
        strong: JSX.Element;
        ul: JSX.Element;
        li: JSX.Element;
    }>>;
    /**
      * `Utilize the meeting notes and AI summarization features provided by Cloud Notes. <1>Discuss more in the community</1>.`
      */
    ["com.cloud-notes.settings.meetings.enable.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Activate using the local key from <1>Toeverything.Inc</1>`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.team.license"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Copy your workspace id and <1>reach out to us</1>.`
      */
    ["com.cloud-notes.settings.workspace.license.self-host-team.upload-license-file.tips.content"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `If you encounter any issues, contact support@toeverything.info. No license yet? <1>Click to purchase</1>.`
      */
    ["com.cloud-notes.settings.workspace.license.activate-modal.tips"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `This will make the workspace read-only. Your key remains usable elsewhere. Deactivation doesn't cancel your Team plan. To cancel, go to <1>Manage Payment</1>.`
      */
    ["com.cloud-notes.settings.workspace.license.deactivate-modal.description"]: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `The "<1>{{ name }}</1>" property will be removed. This action cannot be undone.`
      */
    ["com.cloud-notes.settings.workspace.properties.delete-property-desc"]: ComponentType<TypedTransProps<{
        readonly name: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> doc`
      */
    ["com.cloud-notes.settings.workspace.properties.doc"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> docs`
      */
    ["com.cloud-notes.settings.workspace.properties.doc_others"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `Manage workspace <1>{{name}}</1> properties`
      */
    ["com.cloud-notes.settings.workspace.properties.header.subtitle"]: ComponentType<TypedTransProps<{
        readonly name: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> selected`
    
      * - com.cloud-notes.tag.toolbar.selected_one: `<0>{{count}}</0> tag selected`
    
      * - com.cloud-notes.tag.toolbar.selected_other: `<0>{{count}}</0> tag(s) selected`
      */
    ["com.cloud-notes.tag.toolbar.selected"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> tag selected`
      */
    ["com.cloud-notes.tag.toolbar.selected_one"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> tag(s) selected`
      */
    ["com.cloud-notes.tag.toolbar.selected_other"]: ComponentType<TypedTransProps<{
        readonly count: string | number | bigint;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `<0>{{count}}</0> tag(s) selected`
      */
    ["com.cloud-notes.tag.toolbar.selected_others"]: ComponentType<TypedTransProps<{
        readonly count: string;
    }, {
        ["0"]: JSX.Element;
    }>>;
    /**
      * `Deleting <1>{{workspace}}</1> cannot be undone, please proceed with caution. All contents will be lost.`
      */
    ["com.cloud-notes.workspaceDelete.description"]: ComponentType<TypedTransProps<{
        readonly workspace: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Deleting <1>{{workspace}}</1> will delete both local data and synced server data. This cannot be undone, so please proceed with caution.`
      */
    ["com.cloud-notes.workspaceDelete.description2"]: ComponentType<TypedTransProps<{
        readonly workspace: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * ` We recommend the <1>Chrome</1> browser for optimal experience.`
      */
    recommendBrowser: ComponentType<TypedTransProps<Readonly<{}>, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `Are you sure you want to upgrade <1>{{workspaceName}}</1> to a Team Workspace? This will allow unlimited members to collaborate in this workspace.`
      */
    ["com.cloud-notes.upgrade-to-team-page.upgrade-confirm.description"]: ComponentType<TypedTransProps<{
        readonly workspaceName: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> mentioned you in <2>{{docTitle}}</2>`
      */
    ["com.cloud-notes.notification.mention"]: ComponentType<TypedTransProps<Readonly<{
        username: string;
        docTitle: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> commented in <2>{{docTitle}}</2>`
      */
    ["com.cloud-notes.notification.comment"]: ComponentType<TypedTransProps<Readonly<{
        username: string;
        docTitle: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> mentioned you in a comment in <2>{{docTitle}}</2>`
      */
    ["com.cloud-notes.notification.comment-mention"]: ComponentType<TypedTransProps<Readonly<{
        username: string;
        docTitle: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> has accepted your invitation`
      */
    ["com.cloud-notes.notification.invitation-accepted"]: ComponentType<TypedTransProps<{
        readonly username: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> has requested to join <2>{{workspaceName}}</2>`
      */
    ["com.cloud-notes.notification.invitation-review-request"]: ComponentType<TypedTransProps<Readonly<{
        username: string;
        workspaceName: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> has declined your request to join <2>{{workspaceName}}</2>`
      */
    ["com.cloud-notes.notification.invitation-review-declined"]: ComponentType<TypedTransProps<Readonly<{
        username: string;
        workspaceName: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> has approved your request to join <2>{{workspaceName}}</2>`
      */
    ["com.cloud-notes.notification.invitation-review-approved"]: ComponentType<TypedTransProps<Readonly<{
        username: string;
        workspaceName: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `There is an issue regarding your invitation to <1>{{workspaceName}}</1> `
      */
    ["com.cloud-notes.notification.invitation-blocked"]: ComponentType<TypedTransProps<{
        readonly workspaceName: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
    /**
      * `<1>{{username}}</1> invited you to join <2>{{workspaceName}}</2>`
      */
    ["com.cloud-notes.notification.invitation"]: ComponentType<TypedTransProps<Readonly<{
        username: string;
        workspaceName: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `Unable to join <1/> <2>{{workspaceName}}</2> due to insufficient seats available.`
      */
    ["notes.diegonmarcos.com-to-join-workspace.description-1"]: ComponentType<TypedTransProps<{
        readonly workspaceName: string;
    }, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
    }>>;
    /**
      * `You requested to join <1/> <2>{{workspaceName}}</2> with <3>{{userEmail}}</3>, the workspace owner and team admins will review your request.`
      */
    ["com.cloud-notes.sent-request-to-join-workspace.description"]: ComponentType<TypedTransProps<Readonly<{
        workspaceName: string;
        userEmail: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
        ["3"]: JSX.Element;
    }>>;
    /**
      * `Unable to process your request to join <1/> <2>{{workspaceName}}</2> with <3>{{userEmail}}</3>, the workspace has reached its member limit. Please contact the workspace owner for available seats.`
      */
    ["com.cloud-notes.failed-to-send-request.description"]: ComponentType<TypedTransProps<Readonly<{
        workspaceName: string;
        userEmail: string;
    }>, {
        ["1"]: JSX.Element;
        ["2"]: JSX.Element;
        ["3"]: JSX.Element;
    }>>;
    /**
      * `Import your Readwise highlights to Cloud Notes. Please visit Readwise, <br />click <a>"Get Access Token"</a>, and paste the token below.`
      */
    ["com.cloud-notes.integration.readwise.connect.desc"]: ComponentType<TypedTransProps<Readonly<{}>, {
        br: JSX.Element;
        a: JSX.Element;
    }>>;
    /**
      * `Updates to be imported since last successful import on {{lastImportedAt}} <a>Import everything instead</a>`
      */
    ["com.cloud-notes.integration.readwise.import.desc-from-last"]: ComponentType<TypedTransProps<{
        readonly lastImportedAt: string;
    }, {
        a: JSX.Element;
    }>>;
    /**
      * `Please contact <1>{{user}}</1> to upgrade AI rights or resend the attachment.`
      */
    ["com.cloud-notes.audio.transcribe.non-owner.confirm.message"]: ComponentType<TypedTransProps<{
        readonly user: string;
    }, {
        ["1"]: JSX.Element;
    }>>;
} = /*#__PURE__*/ createProxy(createComponent);

package app.cloudnotes.pro.utils.logger

import timber.log.Timber

class CloudNotesDebugTree : Timber.DebugTree() {

    override fun createStackElementTag(element: StackTraceElement): String {
        return "Cloud Notes:${super.createStackElementTag(element)}:${element.lineNumber}"
    }
}
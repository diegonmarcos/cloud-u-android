package app.sterna.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.ui.text.MailTextToolsPrefs
import app.sterna.ui.theme.MailMetrics

/**
 * Configs ▸ Text ▸ Answer Prediction - the prompt that writes a suggested reply.
 *
 * ONE prompt, two readers: the composer's Answer Prediction button and the reply the AI Resume box
 * suggests. Editing it here changes both. An empty field means the default, and "Reset to default"
 * puts the default text back.
 */
@Composable
internal fun MailAnswerPredictionScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    DetailScaffold(stringResource(R.string.settings_text_answer_title), onBack) { padding ->
        var prompt by remember { mutableStateOf(MailTextToolsPrefs.answerPrompt(context)) }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.settings_text_answer_note),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(MailMetrics.s16),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = {
                    prompt = it
                    MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_ANSWER_PROMPT, it)
                },
                label = { Text(stringResource(R.string.settings_text_answer_prompt)) },
                minLines = 6,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16),
            )
            TextButton(
                onClick = {
                    MailTextToolsPrefs.resetAnswerPrompt(context)
                    prompt = MailTextToolsPrefs.answerPrompt(context)
                },
                modifier = Modifier.padding(horizontal = MailMetrics.s8),
            ) {
                Text(stringResource(R.string.settings_text_answer_reset))
            }
        }
    }
}

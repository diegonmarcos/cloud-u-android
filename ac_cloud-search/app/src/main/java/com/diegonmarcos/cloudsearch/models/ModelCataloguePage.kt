package com.diegonmarcos.cloudsearch.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.ui.IconBtn
import com.diegonmarcos.cloudsearch.ui.LocalGlass
import com.diegonmarcos.cloudsearch.ui.Metrics
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Type
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository.Origin
import com.diegonmarcos.superapp.modelcataloguepage.CatalogueTheme
import com.diegonmarcos.superapp.modelcataloguepage.CatalogueWords
import com.diegonmarcos.superapp.modelcataloguepage.ModelTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.diegonmarcos.superapp.modelcataloguepage.ModelCataloguePage as SharedCataloguePage

/**
 * Chat › Search › the model chip: the whole model catalogue as a page — libs:model-catalogue's page
 * (shared with Cloud Code, which shows only A0 Code), drawn here in this app's glass theme and words
 * with every section. A tap on a chat model picks it for the chat (persisted, as the dropdown did)
 * and returns; embeddings, rerank, audio and image rows are browsable and greyed.
 */
@Composable
fun ModelCataloguePage(state: SearchState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val chat = state.chat
    BackHandler(onBack = onClose)
    var source by remember { mutableStateOf<ModelCatalogueSource?>(null) }
    LaunchedEffect(Unit) { source = withContext(Dispatchers.IO) { ModelCatalogueSource.of(state.services) } }
    val theme = CatalogueTheme(
        text = g.text, text2 = g.text2, accent = g.accent, field = g.field, card = g.card, border = g.tileBorder, negative = g.negative,
        title = Type.style(Type.menuTitle), groupTitle = Type.style(Type.cardTitle), sectionTitle = Type.style(Type.menuItem),
        body = Type.style(Type.small), label = Type.style(Type.label),
        gutter = Metrics.gutter, small = Metrics.small, gap = Metrics.gap, tiny = Metrics.tiny, cardRadius = Metrics.cardRadius,
        cardPad = Metrics.cardPad, tileRadius = Metrics.tileRadius, hairline = Metrics.hairline,
    )
    val origins = mapOf(
        Origin.LIVE to stringResource(R.string.models_origin_live), Origin.CACHED to stringResource(R.string.models_origin_cached),
        Origin.STALE to stringResource(R.string.models_origin_stale), Origin.SNAPSHOT to stringResource(R.string.models_origin_snapshot),
    )
    val ctx = LocalContext.current
    val words = CatalogueWords(
        title = stringResource(R.string.models_title),
        current = { name -> ctx.getString(R.string.models_current, name) },
        pricesAsOf = { date, origin -> ctx.getString(R.string.models_prices_as_of, date, origin) },
        origin = { origins.getValue(it) },
        refreshing = stringResource(R.string.models_refreshing),
        unreadable = stringResource(R.string.models_catalogue_unreadable),
        footer = stringResource(R.string.models_footer),
        notChat = stringResource(R.string.models_not_chat),
        noAnthropic = stringResource(R.string.models_no_anthropic),
        none = stringResource(R.string.models_none),
        notListed = stringResource(R.string.models_not_listed),
        columns = listOf(
            stringResource(R.string.models_col_provider), stringResource(R.string.models_col_model), stringResource(R.string.models_col_id),
            stringResource(R.string.models_col_params), stringResource(R.string.models_col_license),
            stringResource(R.string.models_col_input), stringResource(R.string.models_col_output),
        ),
    )
    val s = source
    SharedCataloguePage(
        catalogue = s?.catalogue, repo = s?.prices, ready = s != null,
        current = chat.model, currentName = chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model,
        theme = theme, words = words, onPick = { id -> chat.pick(id); onClose() }, modifier = modifier,
    ) {
        Text(stringResource(R.string.web_search), color = g.text2, style = Type.style(Type.small))
        Switch(chat.web, { chat.toggleWeb() }, Modifier.padding(horizontal = Metrics.small).testTag(ModelTags.WEB),
            colors = SwitchDefaults.colors(checkedTrackColor = g.accent, uncheckedTrackColor = g.field))
        IconBtn(R.drawable.ph_x, stringResource(R.string.close), ModelTags.CLOSE, onClose)
    }
}

package com.diegonmarcos.superapp.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R

/**
 * A tab whose agent DOES NOT EXIST YET, saying so and offering nothing.
 *
 * WHAT AN HONEST PLACEHOLDER LOOKS LIKE IN THIS APP, and why it looks like that. It states in one
 * line that the agent is not built, names what will be configured here when it is, and presents NO
 * CONTROL OF ANY KIND — no field, no switch, no button, not even a disabled one.
 *
 * That last part is the whole decision. A search box that does not search, or a greyed-out toggle,
 * both read as a feature that is BROKEN; a sentence saying the agent is not built reads as a
 * feature that is ABSENT. The owner will meet these three tabs before anything else on this page,
 * and the difference decides whether they spend the evening debugging a phone or simply close the
 * tab. Absent is cheap to read and broken is expensive, so nothing here is clickable.
 *
 * WebSearch, LocalSearch and Library all come here. They are three tabs rather than three
 * fragments because nothing distinguishes them except the sentence, and the sentence is a string
 * resource — a fragment per placeholder would be three files to keep in step for one paragraph of
 * difference, and the second one would drift.
 *
 * DELETING THIS IS THE POINT. When an agent is built its tab stops naming this fragment in
 * SectionPages and names its own; nothing here needs unwinding first.
 */
class AiNotBuiltFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = inflater.context
        val (scroll, column) = AiViews.page(context)

        val title = arguments?.getInt(ARG_TITLE_RES) ?: R.string.ai_not_built_generic_title
        val detail = arguments?.getInt(ARG_DETAIL_RES) ?: R.string.ai_not_built_generic_detail

        column.addView(AiViews.heading(context, getString(title)))
        // Stated as an ordinary fact, not as an error: nothing has gone wrong, the thing simply
        // does not exist yet. The warning colour is deliberately NOT used for that reason — it
        // would make an absence look like a fault.
        column.addView(AiViews.body(context, getString(R.string.ai_not_built_headline)))
        column.addView(AiViews.caption(context, getString(detail)))

        return scroll
    }

    companion object {
        private const val ARG_TITLE_RES = "titleRes"
        private const val ARG_DETAIL_RES = "detailRes"

        /**
         * [titleRes] names the agent; [detailRes] says what this tab will hold once it exists.
         *
         * Both are string RESOURCES rather than strings so that a caller cannot pass a literal
         * through and quietly ship an untranslatable line — the owner reads this interface in
         * Spanish, and a placeholder is exactly the kind of low-stakes text that gets hardcoded.
         */
        fun newInstance(titleRes: Int, detailRes: Int) = AiNotBuiltFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_TITLE_RES, titleRes)
                putInt(ARG_DETAIL_RES, detailRes)
            }
        }
    }
}

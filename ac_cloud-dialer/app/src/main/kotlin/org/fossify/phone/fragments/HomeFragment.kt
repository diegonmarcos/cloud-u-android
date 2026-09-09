package org.fossify.phone.fragments

import android.content.Context
import android.util.AttributeSet
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.databinding.FragmentHomeBinding
import org.fossify.phone.extensions.startCallWithProviderChooser
import org.fossify.phone.helpers.CallStatistics
import org.fossify.phone.helpers.CallStatsHelper
import org.fossify.phone.helpers.HomeStatisticsRenderer
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.RecentCall

/**
 * Cloud Dialer: the Home page — the owner's calling life at a glance, and the
 * page the app opens on.
 *
 * The recent-calls preview at the top is the call history's own
 * RecentCallsAdapter fed by RecentsHelper, so a row looks and behaves exactly
 * as it does on the Call history tab and there is one place that knows how to
 * draw a call. Everything below it comes from CallStatsHelper, which asks the
 * same call log provider a different question. See CallStatistics for what the
 * numbers mean and which ones the provider cannot answer.
 */
class HomeFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.HomeInnerBinding>(context, attributeSet), RefreshItemsListener {

    companion object {
        /** A glance, not a list — the whole call history is one tap away. */
        private const val RECENT_CALLS_PREVIEW_COUNT = 5
    }

    private lateinit var binding: FragmentHomeBinding
    private var recentCallsAdapter: RecentCallsAdapter? = null
    private var renderer: HomeStatisticsRenderer? = null
    private var lastStatistics: CallStatistics? = null
    private val recentsHelper = RecentsHelper(context)
    private val callStatsHelper = CallStatsHelper(context)

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentHomeBinding.bind(this)
        innerBinding = HomeInnerBinding(binding)
    }

    override fun setupFragment() {
        val currentActivity = activity ?: return
        renderer = HomeStatisticsRenderer(currentActivity, binding) { contact ->
            currentActivity.startCallWithProviderChooser(contact.phoneNumber, contact.displayName)
        }

        binding.homeSeeAllCalls.setOnClickListener { openCallHistoryTab() }
        binding.homeMissedCard.setOnClickListener { openCallHistoryTab() }
        binding.homePlaceholderAction.apply {
            underlineText()
            setOnClickListener { requestCallLogPermission() }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        activity?.updateTextColors(binding.homeHolder)
        binding.homePlaceholder.setTextColor(textColor)
        binding.homePlaceholderAction.setTextColor(properPrimaryColor)
        binding.homeSeeAllCalls.setTextColor(properPrimaryColor)

        // The headline numbers carry the same colour legend the call history
        // uses, so a red number on Home means exactly what a red row means there.
        binding.homeMissedCount.setTextColor(context.getColor(R.color.color_missed_call))
        binding.homeMissedMixCount.setTextColor(context.getColor(R.color.color_missed_call))
        binding.homeIncomingCount.setTextColor(context.getColor(R.color.color_incoming_call))
        binding.homeOutgoingCount.setTextColor(context.getColor(R.color.color_outgoing_call))
        binding.homeTalkTimeThisMonth.setTextColor(properPrimaryColor)
        binding.homeAverageCall.setTextColor(properPrimaryColor)

        recentCallsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
        }

        // Repaint rather than reload: the charts are tinted with the accent.
        lastStatistics?.let { renderer?.render(it, properPrimaryColor) }
    }

    override fun onSearchClosed() = Unit

    /** Home is a summary. Searching belongs to the lists that hold the rows. */
    override fun onSearchQueryChanged(text: String) = Unit

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        loadRecentCalls()
        callStatsHelper.getCallStatistics { statistics ->
            activity?.runOnUiThread {
                showStatistics(statistics)
                callback?.invoke()
            }
        }
    }

    private fun loadRecentCalls() {
        recentsHelper.getRecentCalls(queryLimit = RECENT_CALLS_PREVIEW_COUNT) { calls ->
            val preview = calls.take(RECENT_CALLS_PREVIEW_COUNT)
            activity?.runOnUiThread {
                val currentActivity = activity ?: return@runOnUiThread
                if (recentCallsAdapter == null) {
                    recentCallsAdapter = RecentCallsAdapter(
                        activity = currentActivity,
                        recyclerView = binding.homeRecentCallsList,
                        refreshItemsListener = this,
                        showOverflowMenu = false,
                        itemClick = {
                            val call = it as RecentCall
                            currentActivity.startCallWithProviderChooser(call.phoneNumber, call.name)
                        },
                    )
                    binding.homeRecentCallsList.adapter = recentCallsAdapter
                }

                recentCallsAdapter?.updateItems(preview)
                binding.homeRecentCallsList.beVisibleIf(preview.isNotEmpty())
            }
        }
    }

    /**
     * A fresh install and a denied permission are the two states nobody did
     * anything wrong to reach, so neither shows an error. A denied permission
     * says what the page is built from and offers to ask again; an empty log
     * says the statistics will appear once there is something to count.
     */
    private fun showStatistics(statistics: CallStatistics?) {
        binding.homeProgressIndicator.hide()
        lastStatistics = statistics

        val hasStatistics = statistics != null && statistics.hasAnyCalls
        binding.homeScrollView.beVisibleIf(hasStatistics)
        binding.homePlaceholderHolder.beGoneIf(hasStatistics)

        if (statistics != null && statistics.hasAnyCalls) {
            renderer?.render(statistics, context.getProperPrimaryColor())
            return
        }

        val hasCallLogAccess = statistics != null
        binding.homePlaceholder.text = context.getString(
            if (hasCallLogAccess) R.string.home_no_statistics else R.string.home_call_log_access
        )
        binding.homePlaceholderAction.beGoneIf(hasCallLogAccess)
    }

    private fun openCallHistoryTab() {
        (activity as? MainActivity)?.openCallHistoryTab()
    }

    private fun requestCallLogPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) { granted ->
            if (granted) {
                binding.homeProgressIndicator.show()
                refreshItems()
            }
        }
    }
}

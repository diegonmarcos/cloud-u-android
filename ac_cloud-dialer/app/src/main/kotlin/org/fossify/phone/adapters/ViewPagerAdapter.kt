package org.fossify.phone.adapters

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.fragments.MyViewPagerFragment
import org.fossify.phone.helpers.visibleDialerTabs

class ViewPagerAdapter(val activity: SimpleActivity) : PagerAdapter() {

    private val tabs get() = visibleDialerTabs(activity.config.showTabs)

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = tabs[position].layoutResourceId
        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        (view as MyViewPagerFragment<*>).apply {
            setupFragment(activity)
        }

        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    override fun getCount() = tabs.size

    override fun isViewFromObject(view: View, item: Any) = view == item
}

package com.mytvb.feature.player.view

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.feature.player.btr.BtrSettingsStore
import com.mytvb.feature.player.btr.BtrCdnMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BtrPlayerMenuTest {
    private lateinit var activity: Activity
    private lateinit var panel: MyPlayerSettingView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PlayerSettingListAdapter

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.DarkTheme)
        startKoin { modules(module { single { AppSettingsDataStore(activity.applicationContext) } }) }
        BtrSettingsStore.saveEnabled(true)
        BtrSettingsStore.saveMode(BtrCdnMode.MAINLAND)
        BtrSettingsStore.saveDebugNotices(false)
        panel = MyPlayerSettingView(activity)
        activity.setContentView(panel)
        recycler = panel.findViewById(R.id.recyclerView)
        adapter = recycler.adapter as PlayerSettingListAdapter
        panel.showHide(true)
        settle()
    }

    @After fun cleanup() { activity.finish(); stopKoin() }

    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            panel.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 1920, 1080)
            Thread.sleep(20) // RecyclerView ListAdapter calculates its diff on a worker.
        }
    }

    private fun click(id: Int) {
        val position = adapter.currentList.indexOfFirst { it is PlayerSettingRow.Item && it.id == id }
        assertTrue("Menu item $id should exist", position >= 0)
        recycler.scrollToPosition(position)
        settle()
        val view = recycler.findViewHolderForAdapterPosition(position)!!.itemView
        assertTrue(view.performClick())
        settle()
    }

    @Test fun toggleChoiceAndDebugRefreshAndPersistAcrossPanelReopen() {
        click(MyPlayerSettingView.ITEM_BTR)
        click(MyPlayerSettingView.ITEM_BTR_ENABLE)
        assertFalse(BtrSettingsStore.load().enabled)
        val toggle = adapter.currentList.filterIsInstance<PlayerSettingRow.Item>().first { it.id == MyPlayerSettingView.ITEM_BTR_ENABLE }
        assertEquals(activity.getString(R.string.off), toggle.value)
        click(MyPlayerSettingView.ITEM_BTR_MODE)
        click(1) // overseas
        assertEquals(BtrCdnMode.OVERSEAS, BtrSettingsStore.load().mode)
        click(MyPlayerSettingView.ITEM_BTR_THREADS)
        click(3) // 32
        assertEquals(32, BtrSettingsStore.load().concurrency)
        assertFalse(BtrSettingsStore.load().autoConcurrency)
        click(MyPlayerSettingView.ITEM_BTR_DEBUG)
        assertTrue(BtrSettingsStore.load().debugNotices)
        panel.showHide(false)
        settle()
        panel.showHide(true)
        settle()
        click(MyPlayerSettingView.ITEM_BTR)
        val debug = adapter.currentList.filterIsInstance<PlayerSettingRow.Item>().first { it.id == MyPlayerSettingView.ITEM_BTR_DEBUG }
        assertEquals(activity.getString(R.string.on), debug.value)
    }
}

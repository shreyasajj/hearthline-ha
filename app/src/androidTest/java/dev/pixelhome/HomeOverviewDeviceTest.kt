package dev.pixelhome

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual MainActivity layout; no unlock, permission grant, device command or credential capture. */
@RunWith(AndroidJUnit4::class)
class HomeOverviewDeviceTest {
    private fun flatten(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if(root==null) emptyList() else listOf(root)+(0 until root.childCount).flatMap { flatten(root.getChild(it)) }
    @Test fun overviewControlsFitWithoutScrolling() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val prefs=context.getSharedPreferences("hub_display",0)
        val hadSuppression=prefs.contains("dock_suppressed");val suppression=prefs.getBoolean("dock_suppressed",false)
        val hadLayout=prefs.contains("dock_dashboard");val layout=prefs.getBoolean("dock_dashboard",true)
        prefs.edit().putBoolean("dock_suppressed",false).putBoolean("dock_dashboard",true).commit()
        DockTestRuntime.start(SystemClock.elapsedRealtime())
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                var nodes=emptyList<AccessibilityNodeInfo>()
                for(attempt in 0..60) {
                    nodes=flatten(i.uiAutomation.rootInActiveWindow)
                    if(nodes.any {it.text?.toString()=="Favorites"}) break
                    Thread.sleep(100)
                }
                assertTrue("Overview missing",nodes.any {it.text?.toString()=="Favorites"})
                assertFalse("Home overview unexpectedly scrolls",nodes.any {it.isScrollable})
                for(description in listOf("Previous track","Play","Next track","Volume")) {
                    val node=nodes.firstOrNull {it.contentDescription?.toString()==description}
                    assertNotNull("Missing visible music control: $description",node)
                    val rect=Rect();node!!.getBoundsInScreen(rect)
                    assertTrue("Clipped control: $description",rect.width()>0 && rect.height() >= (if(description=="Volume") 48 else 56)*context.resources.displayMetrics.density-2)
                }
                val volume=nodes.first {it.contentDescription?.toString()=="Volume"}
                val rootBounds=Rect();i.uiAutomation.rootInActiveWindow.getBoundsInScreen(rootBounds)
                val volumeBounds=Rect();volume.getBoundsInScreen(volumeBounds)
                assertTrue("Volume extends below viewport",rootBounds.contains(volumeBounds))
                val guard=context.getSystemService(KeyguardManager::class.java)
                if(guard.isKeyguardLocked || guard.isDeviceLocked) {
                    assertTrue("A switch was enabled on the lock screen",nodes.filter {it.isCheckable}.none {it.isEnabled})
                    assertTrue("A slider was enabled on the lock screen",nodes.filter {it.rangeInfo != null}.none {it.isEnabled})
                }
                val capture=InstrumentationRegistry.getArguments().getString("capture_name","overview")!!.replace(Regex("[^a-z0-9_-]"),"")
                val bitmap=i.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null),"$capture.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
                // Additional items remain available in a separate details sheet without scrolling the overview.
                val all=nodes.firstOrNull {it.text?.toString()?.matches(Regex("All [0-9]+"))==true && it.isClickable}
                if(all!=null) {
                    all.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(500)
                    val sheet=flatten(i.uiAutomation.rootInActiveWindow)
                    assertTrue("Additional favorites cannot be reached",sheet.any {it.text?.toString()=="All favorites"})
                }
            }
        } finally {
            DockTestRuntime.end()
            val edit=prefs.edit()
            if(hadSuppression) edit.putBoolean("dock_suppressed",suppression) else edit.remove("dock_suppressed")
            if(hadLayout) edit.putBoolean("dock_dashboard",layout) else edit.remove("dock_dashboard")
            edit.commit()
        }
    }
}

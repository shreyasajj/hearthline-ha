package dev.pixelhome

import android.content.Context
import android.os.PowerManager
import org.json.JSONObject
import java.time.OffsetDateTime

/** Latest observation only; the foreground connection loop owns sequence and delivery. */
fun tabletTelemetry(context: Context, host: HubHost, page: String, tabletId: String, session: String, sequence: Long): JSONObject {
    val dock=host.dock
    return JSONObject().put("schema_version",1).put("tablet_id",tabletId).put("session_id",session)
        .put("sequence",sequence).put("observed_at",OffsetDateTime.now().toString()).put("app_version","0.7.0")
        .put("battery_percent",dock.batteryPercent ?: JSONObject.NULL).put("battery_status",dock.batteryStatus)
        .put("charging",dock.charging ?: JSONObject.NULL).put("power_source",dock.powerSource.lowercase())
        .put("dock_state",dock.observation.connection.name.lowercase())
        .put("dock_evidence",dock.observation.evidence.takeIf { it != "no_dock_signal" } ?: "unconfirmed")
        .put("detection_mode",dock.mode.name.lowercase()).put("foreground",true)
        .put("interactive",context.getSystemService(PowerManager::class.java).isInteractive)
        .put("locked",host.isLockedNow()).put("current_screen",page.takeIf { it in setOf("home","music","settings","clock") } ?: "unknown")
        .put("protected_dock_view_visible",host.isLockedNow() && host.lockScreenEligible && (context as? android.app.Activity)?.let { it.hasWindowFocus() && it.window.decorView.isShown } == true && context.getSystemService(PowerManager::class.java).isInteractive)
        .put("dock_display_enabled",host.showWhenLocked).put("dock_test_active",host.dockTestActive)
}

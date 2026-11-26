// PortedFrom: MC1Widgets/MC1RadioControl.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.platform.widgets

import android.content.Context
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings
import com.meshcoreone.android.core.l10n.generated.WidgetLocalizableStrings

class MeshStatusWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fallback = WidgetConnectionView(
            WidgetRadioStatus.DISCONNECTED,
            WidgetStatusStore(context).read().hasSavedDevice,
        )
        provideContent {
            val live by WidgetConnectionBridge.widgetStatus.collectAsState()
            val hostCategory = LocalAppWidgetOptions.current.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY)
            StatusContent(live ?: fallback, hostCategory)
        }
    }
}

class MeshStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MeshStatusWidget()
}

class ConnectRadioAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetConnectionBridge.request(context, ConnectionRequestOrigin.WIDGET)
        MeshStatusWidget().update(context, glanceId)
    }
}

@Composable
@SuppressLint("RestrictedApi")
private fun StatusContent(view: WidgetConnectionView, hostCategory: Int) {
    val context = LocalContext.current
    val status = statusText(context, view)
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val size = LocalSize.current
    val metrics = widgetMetricLines(context, view, hostCategory, size.width.value, size.height.value)
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(R.color.widget_background))
            .padding(16.dp)
            .let { modifier -> if (launch == null) modifier else modifier.clickable(actionStartActivity(launch)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (size.width >= 180.dp) {
                Image(
                    provider = ImageProvider(remember(view.status) { StatusBitmap.render(view.status) }),
                    contentDescription = null,
                    modifier = GlanceModifier.size(40.dp),
                )
                Spacer(GlanceModifier.size(12.dp))
            }
            Column {
                Text(
                    text = context.getString(com.meshcoreone.android.platform.widgets.R.string.widget_app_name),
                    style = TextStyle(color = ColorProvider(R.color.widget_primary_text), fontWeight = FontWeight.Bold, fontSize = 14.sp),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(text = status, style = TextStyle(color = ColorProvider(R.color.widget_secondary_text), fontSize = 14.sp))
            }
        }
        if (metrics.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            metrics.forEach { line ->
                Text(text = line, style = TextStyle(color = ColorProvider(R.color.widget_secondary_text), fontSize = 14.sp))
            }
        }
        if (view.status == WidgetRadioStatus.DISCONNECTED && view.hasSavedDevice) {
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = context.getString(AppOnboardingStrings.wifiConnectionConnect),
                modifier = GlanceModifier.clickable(
                    actionRunCallback<ConnectRadioAction>(
                        actionParametersOf(ActionParameters.Key<String>("origin") to "widget"),
                    ),
                ).padding(vertical = 16.dp),
                style = TextStyle(color = ColorProvider(R.color.widget_action_text), fontWeight = FontWeight.Medium),
            )
        }
    }
}

internal fun widgetMetricLines(
    context: Context,
    view: WidgetConnectionView,
    hostCategory: Int,
    widthDp: Float,
    heightDp: Float,
): List<String> {
    val metrics = view.metrics ?: return emptyList()
    // Keep host surfaces other than explicitly home-screen status-only.
    if (view.status != WidgetRadioStatus.READY || view.permissionDenied || view.reconnecting ||
        hostCategory != android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
    ) return emptyList()
    val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    if (widthDp < 220f * scale || heightDp < 140f * scale) return emptyList()
    val resources = context.resources
    val lines = buildList {
        metrics.batteryPercent?.let { add(WidgetLocalizableStrings.batteryLldPercent(resources, it)) }
        add(WidgetLocalizableStrings.lldPacketsPerMinute(resources, metrics.packetsPerMinute))
        metrics.unread?.let { add(WidgetLocalizableStrings.lldUnreadMessages(resources, it)) }
    }
    val density = resources.displayMetrics.density
    val contentWidth = ((widthDp - 32f) * density).toInt().coerceAtLeast(1)
    val headerWidth = (contentWidth - if (widthDp >= 180f) 52f * density else 0f).toInt().coerceAtLeast(1)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    fun height(text: String, width: Int, bold: Boolean = false): Int {
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build().height
    }
    val headerHeight = maxOf(
        if (widthDp >= 180f) 40f * density else 0f,
        height(context.getString(R.string.widget_app_name), headerWidth, bold = true) +
            4f * density + height(statusText(context, view), headerWidth),
    )
    val requiredHeight = 32f * density + headerHeight + 8f * density +
        lines.sumOf { height(it, contentWidth) }
    return if (requiredHeight <= heightDp * density) lines else emptyList()
}

private fun statusText(context: Context, view: WidgetConnectionView): String = when {
    view.permissionDenied -> context.getString(WidgetLocalizableStrings.openMeshCoreOne)
    view.reconnecting -> context.getString(AppLocalizableStrings.commonStatusConnecting)
    view.status == WidgetRadioStatus.DISCONNECTED -> context.getString(WidgetLocalizableStrings.disconnected)
    view.status == WidgetRadioStatus.CONNECTING -> context.getString(AppLocalizableStrings.commonStatusConnecting)
    view.status == WidgetRadioStatus.CONNECTED -> context.getString(AppLocalizableStrings.commonStatusConnecting)
    view.status == WidgetRadioStatus.SYNCING -> context.getString(AppLocalizableStrings.commonStatusConnecting)
    else -> context.getString(AppLocalizableStrings.commonStatusReady)
}

internal object StatusBitmap {
    const val EDGE_PX = 64

    fun render(status: WidgetRadioStatus): Bitmap {
        val bitmap = Bitmap.createBitmap(EDGE_PX, EDGE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (status) {
                WidgetRadioStatus.READY -> Color.rgb(34, 197, 94)
                WidgetRadioStatus.CONNECTING, WidgetRadioStatus.CONNECTED, WidgetRadioStatus.SYNCING ->
                    Color.rgb(250, 204, 21)
                WidgetRadioStatus.DISCONNECTED -> Color.rgb(148, 163, 184)
            }
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(32f, 23f, 4f, paint)
        canvas.drawLine(32f, 27f, 32f, 52f, paint)
        canvas.drawLine(23f, 52f, 41f, 52f, paint)
        canvas.drawArc(19f, 10f, 45f, 36f, -55f, 110f, false, paint)
        canvas.drawArc(8f, -1f, 56f, 47f, -48f, 96f, false, paint)
        return bitmap
    }
}

// PortedFrom: MC1Widgets/MC1RadioControl.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.platform.widgets

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings
import com.meshcoreone.android.core.l10n.generated.WidgetLocalizableStrings

class MeshStatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val view = WidgetStatusStore(context).read()
        provideContent { StatusContent(view) }
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
private fun StatusContent(view: WidgetConnectionView) {
    val context = LocalContext.current
    val status = statusText(context, view)
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(R.color.widget_background))
            .padding(16.dp)
            .let { modifier -> if (launch == null) modifier else modifier.clickable(actionStartActivity(launch)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(StatusBitmap.render(view.status)),
                contentDescription = status,
                modifier = GlanceModifier.size(40.dp),
            )
            Spacer(GlanceModifier.size(12.dp))
            Column {
                Text(
                    text = context.getString(com.meshcoreone.android.platform.widgets.R.string.widget_app_name),
                    style = TextStyle(color = ColorProvider(R.color.widget_primary_text), fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(text = status, style = TextStyle(color = ColorProvider(R.color.widget_secondary_text)))
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

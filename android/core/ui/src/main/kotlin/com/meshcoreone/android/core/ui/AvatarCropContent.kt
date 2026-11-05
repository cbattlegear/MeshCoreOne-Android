// PortedFrom: MC1/Views/Components/AvatarCropView.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: adaptive dialog, EXIF-normalized caller image and accessible crop controls.
package com.meshcoreone.android.core.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meshcoreone.android.core.designsystem.NativeThemeMetrics
import kotlin.math.roundToInt
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C

fun cropAvatarImage(image: ImageBitmap, geometry: AvatarCropGeometry): ImageBitmap {
    val source = image.asAndroidBitmap()
    val output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888, true,
        source.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    val canvas = android.graphics.Canvas(output)
    val rect = geometry.imageDrawRect()
    canvas.drawBitmap(source, null, RectF(rect.x.toFloat(), rect.y.toFloat(),
        (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return output.asImageBitmap()
}

@Composable
fun AvatarCropView(
    image: ImageBitmap,
    geometry: AvatarCropGeometry,
    onGeometryChange: (AvatarCropGeometry) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (AvatarCropGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestGeometry by rememberUpdatedState(geometry)
    val latestChange by rememberUpdatedState(onGeometryChange)
    val preview = uiString(UiText.Resource(C.contactsDetailAvatarCropPreview))
    val moveUp = uiString(UiText.Resource(C.contactsDetailAvatarCropMoveUp))
    val moveDown = uiString(UiText.Resource(C.contactsDetailAvatarCropMoveDown))
    val moveLeft = uiString(UiText.Resource(C.contactsDetailAvatarCropMoveLeft))
    val moveRight = uiString(UiText.Resource(C.contactsDetailAvatarCropMoveRight))
    // Read before Dialog installs its own constrained content window.
    val hostWindowWidth = LocalWindowInfo.current.containerSize.width
    val regularHostWidth = with(LocalDensity.current) { hostWindowWidth.toDp() >= 600.dp }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.fillMaxWidth(0.95f), shape = MaterialTheme.shapes.large) {
            SharedUiScaffold {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val scrollControls = maxHeight / LocalDensity.current.fontScale < 480.dp
                    val scrollState = rememberScrollState()
                    val contentModifier = if (scrollControls) {
                        Modifier.fillMaxSize().testTag("avatar-crop-scroll").verticalScroll(scrollState)
                    } else {
                        Modifier.fillMaxSize()
                    }
                    val previewHeight = maxHeight.coerceIn(192.dp,
                        (if (regularHostWidth) AvatarCropGeometry.REGULAR_MAX_CROP_SIZE
                        else AvatarCropGeometry.COMPACT_MAX_CROP_SIZE).dp +
                            (AvatarCropGeometry.CROP_MARGIN * 2).dp)
                    Column(contentModifier) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onCancel, modifier = Modifier.sharedTouchTarget()) {
                                Text(uiString(UiText.Resource(C.contactsCommonCancel)))
                            }
                            TextButton(onClick = { onConfirm(latestGeometry) }, modifier = Modifier.sharedTouchTarget()) {
                                Text(uiString(UiText.Resource(C.contactsDetailAvatarCropChoose)))
                            }
                        }
                        Text(uiString(UiText.Resource(C.contactsDetailAvatarCropTitle)),
                            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                        val previewModifier = if (scrollControls) Modifier.height(previewHeight) else Modifier.weight(1f)
                        BoxWithConstraints(previewModifier.fillMaxWidth().background(Color.Black)) {
                            val size = AvatarCropGeometry.cropSize(CropSize(maxWidth.value.toDouble(), maxHeight.value.toDouble()),
                                regularHostWidth)
                            val imageSize = CropSize(image.width.toDouble(), image.height.toDouble())
                            val resolved = geometry.copy(imageSize = imageSize).resized(size)
                            LaunchedEffect(size, imageSize, geometry) {
                                if (resolved != geometry) latestChange(resolved)
                            }
                            val pan = size * AvatarCropGeometry.PAN_STEP_FRACTION
                            Canvas(Modifier.fillMaxSize().testTag("avatar-crop-canvas").pointerInput(image, scrollControls) {
                                val accumulator = CropGestureAccumulator(latestGeometry)
                                val transform: (Offset, Float) -> Unit = { translation, zoom ->
                                    latestChange(accumulator.transform(latestGeometry, zoom.toDouble(), CropOffset(
                                        translation.x.toDouble() / density, translation.y.toDouble() / density,
                                    )))
                                }
                                if (scrollControls) {
                                    // Leave one-finger drags to the surrounding scroll container.
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent()
                                            if (event.changes.any { it.isConsumed }) break
                                            if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                                                transform(event.calculatePan(), event.calculateZoom())
                                                event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                } else {
                                    detectTransformGestures { _, translation, zoom, _ -> transform(translation, zoom) }
                                }
                            }.semantics {
                                contentDescription = preview
                                progressBarRangeInfo = ProgressBarRangeInfo(geometry.scale.toFloat(), 1f..4f)
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUp) { latestChange(latestGeometry.panBy(CropOffset(0.0, -pan))); true },
                                    CustomAccessibilityAction(moveDown) { latestChange(latestGeometry.panBy(CropOffset(0.0, pan))); true },
                                    CustomAccessibilityAction(moveLeft) { latestChange(latestGeometry.panBy(CropOffset(-pan, 0.0))); true },
                                    CustomAccessibilityAction(moveRight) { latestChange(latestGeometry.panBy(CropOffset(pan, 0.0))); true },
                                )
                            }) {
                                val cropPixels = size.toFloat() * density
                                val draw = resolved.imageDrawRect(cropPixels.toDouble())
                                val left = (this.size.width - cropPixels) / 2
                                val top = (this.size.height - cropPixels) / 2
                                drawIntoCanvas { target ->
                                    target.nativeCanvas.drawBitmap(image.asAndroidBitmap(), null,
                                        RectF(left + draw.x.toFloat(), top + draw.y.toFloat(),
                                            left + (draw.x + draw.width).toFloat(), top + (draw.y + draw.height).toFloat()),
                                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                                }
                                val canvasSize = this.size
                                val mask = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    addRect(Rect(Offset.Zero, canvasSize))
                                    addOval(Rect(left, top, left + cropPixels, top + cropPixels))
                                }
                                drawPath(mask, Color.Black.copy(alpha = 0.5f))
                                drawCircle(Color.White, cropPixels / 2, Offset(this.size.width / 2, this.size.height / 2),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .height(NativeThemeMetrics.MINIMUM_TOUCH_TARGET.dp)
                            .testTag("avatar-crop-zoom").clearAndSetSemantics {
                                contentDescription = preview
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    geometry.scale.toFloat(), 1f..4f, 11,
                                )
                                setProgress { value ->
                                    val snapped = AvatarCropGeometry.MIN_SCALE +
                                        ((value.toDouble().coerceIn(AvatarCropGeometry.MIN_SCALE,
                                            AvatarCropGeometry.MAX_SCALE) - AvatarCropGeometry.MIN_SCALE) /
                                            AvatarCropGeometry.ZOOM_STEP).roundToInt() * AvatarCropGeometry.ZOOM_STEP
                                    latestChange(latestGeometry.zoomBy(snapped - latestGeometry.scale))
                                    true
                                }
                            }) {
                            Slider(geometry.scale.toFloat(), onValueChange = {
                                onGeometryChange(geometry.zoomBy(it - geometry.scale))
                            }, valueRange = 1f..4f, steps = 11,
                                modifier = Modifier.fillMaxSize())
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { onGeometryChange(geometry.panBy(CropOffset(0.0, -geometry.cropSize / 8))) },
                                modifier = Modifier.sharedTouchTarget()) { Text(moveUp) }
                            TextButton(onClick = { onGeometryChange(geometry.panBy(CropOffset(0.0, geometry.cropSize / 8))) },
                                modifier = Modifier.sharedTouchTarget()) { Text(moveDown) }
                            TextButton(onClick = { onGeometryChange(geometry.panBy(CropOffset(-geometry.cropSize / 8, 0.0))) },
                                modifier = Modifier.sharedTouchTarget()) { Text(moveLeft) }
                            TextButton(onClick = { onGeometryChange(geometry.panBy(CropOffset(geometry.cropSize / 8, 0.0))) },
                                modifier = Modifier.sharedTouchTarget()) { Text(moveRight) }
                        }
                    }
                }
            }
        }
    }
}

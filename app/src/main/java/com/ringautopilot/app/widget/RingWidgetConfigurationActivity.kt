package com.ringautopilot.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ringautopilot.app.ui.theme.RingAutopilotTheme
import kotlin.math.roundToInt

class RingWidgetConfigurationActivity : ComponentActivity() {
    private var opacity by mutableIntStateOf(90)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        opacity = WidgetAppearance.opacity(this, id)
        setContent {
            RingAutopilotTheme {
                Column(Modifier.statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Widget appearance", style = MaterialTheme.typography.headlineSmall)
                    Text("Background opacity: $opacity%")
                    Slider(value = opacity.toFloat(), onValueChange = { opacity = (it / 10).roundToInt() * 10 },
                        valueRange = 10f..100f, steps = 8)
                    Button(onClick = {
                        WidgetAppearance.setOpacity(this@RingWidgetConfigurationActivity, id, opacity)
                        AppWidgetManager.getInstance(this@RingWidgetConfigurationActivity)
                            .updateAppWidget(id, RingWidgetProvider.views(this@RingWidgetConfigurationActivity, id))
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save widget") }
                }
            }
        }
    }
}

package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings

/**
 * Device Overview screen: composes the three existing feature screens
 * ([DeviceInfoScreen], [ScreenshotScreen], [RemoteScreen]) into a single scrollable
 * page. Errors remain inline (delegated to each sub-screen). Refresh/Capture/Send
 * actions live inside their respective sub-screens.
 */
@Composable
fun DeviceOverviewScreen(
    deviceInfoVm: DeviceInfoViewModel,
    screenshotVm: ScreenshotViewModel,
    remoteVm: RemoteViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Strings.t("device_overview"), style = MaterialTheme.typography.h5)
            // --- Props + export (DeviceInfoScreen hosts its own Refresh/Export) ---
            DeviceInfoScreen(vm = deviceInfoVm, modifier = Modifier.fillMaxWidth())
            // --- Screenshot (Capture/Save + preview) ---
            ScreenshotScreen(vm = screenshotVm, modifier = Modifier.fillMaxWidth())
            // --- Remote (D-pad + Back/Home/Menu + custom buttons) ---
            RemoteScreen(vm = remoteVm, selectedSerial = selectedSerial, modifier = Modifier.fillMaxWidth())
        }
    }
}

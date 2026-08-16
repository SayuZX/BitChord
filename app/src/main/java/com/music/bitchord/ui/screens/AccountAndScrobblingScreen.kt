package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountAndScrobblingScreen(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()

    var showListenBrainzTokenDialog by remember { mutableStateOf(false) }
    var showLastfmLoginDialog by remember { mutableStateOf(false) }
    val scrobbleScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = "Account & scrobbling",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        AccountCard(signedIn = signedIn, account = account, onSignIn = onSignIn)

        if (signedIn) {
            SettingsGroup {
                DestructiveRow(label = "Sign out", onClick = onSignOut)
            }
        }

        SettingsGroup(
            header = "Scrobbling",
            footer = "Scrobble your listens to Last.fm and ListenBrainz.",
        ) {
            SettingsRow(
                icon = Icons.Rounded.Cloud,
                title = "ListenBrainz",
                subtitle = if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) "Connected" else "Enter a token to enable",
                trailing = {
                    Switch(
                        checked = listenBrainzEnabled,
                        onCheckedChange = AppSettings::setListenBrainzEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { showListenBrainzTokenDialog = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = "Last.fm",
                subtitle = if (lastfmSessionKey.isNotBlank()) "Signed in as $lastfmUsername" else "Tap to sign in",
                trailing = {
                    Switch(
                        checked = lastfmEnabled,
                        onCheckedChange = AppSettings::setLastfmEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = {
                    if (lastfmSessionKey.isNotBlank()) {
                        AppSettings.setLastfmSessionKey("")
                        AppSettings.setLastfmUsername("")
                        AppSettings.setLastfmEnabled(false)
                        AppSettings.setLastfmScrobbleEnabled(false)
                        AppSettings.setLastfmNowPlaying(false)
                    } else {
                        showLastfmLoginDialog = true
                    }
                },
            )
            if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Scrobble tracks",
                    subtitle = "Log plays to your Last.fm timeline",
                    trailing = {
                        Switch(
                            checked = lastfmScrobbleEnabled,
                            onCheckedChange = AppSettings::setLastfmScrobbleEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLastfmScrobbleEnabled(!lastfmScrobbleEnabled) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Now playing",
                    subtitle = "Update Last.fm with what you're listening to",
                    trailing = {
                        Switch(
                            checked = lastfmNowPlayingEnabled,
                            onCheckedChange = AppSettings::setLastfmNowPlaying,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLastfmNowPlaying(!lastfmNowPlayingEnabled) },
                )
            }
        }

        if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
            SettingsGroup(header = "Scrobble timing") {
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = "Min song duration",
                    subtitle = "Songs shorter than this won't scrobble",
                    value = "${scrobbleMinDuration}s",
                    sliderValue = scrobbleMinDuration.toFloat(),
                    onSliderValue = { AppSettings.setScrobbleMinDuration(it.roundToInt()) },
                    valueRange = 15f..120f,
                    steps = 20,
                )
                RowDivider()
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = "Scrobble delay",
                    subtitle = "How far into a song before scrobbling",
                    value = "${(scrobbleDelayPercent * 100).roundToInt()}%",
                    sliderValue = scrobbleDelayPercent,
                    onSliderValue = { AppSettings.setScrobbleDelayPercent(it) },
                    valueRange = 0.1f..1.0f,
                    steps = 8,
                )
                RowDivider()
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = "Max delay",
                    subtitle = "Cap on scrobble delay in seconds",
                    value = "${scrobbleDelaySeconds}s",
                    sliderValue = scrobbleDelaySeconds.toFloat(),
                    onSliderValue = { AppSettings.setScrobbleDelaySeconds(it.roundToInt()) },
                    valueRange = 30f..300f,
                    steps = 26,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showListenBrainzTokenDialog) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        AlertDialog(
            onDismissRequest = { showListenBrainzTokenDialog = false },
            title = { Text("ListenBrainz Token") },
            text = {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("API Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzTokenDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showListenBrainzTokenDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showLastfmLoginDialog) {
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var lastfmError by remember { mutableStateOf<String?>(null) }
        var lastfmLoading by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!lastfmLoading) showLastfmLoginDialog = false },
            title = { Text("Last.fm Login") },
            text = {
                Column {
                    if (lastfmError != null) {
                        Text(
                            text = lastfmError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lastfmLoading = true
                        lastfmError = null
                        scrobbleScope.launch {
                            try {
                                LastFM.initialize(
                                    apiKey = LastFM.FALLBACK_COMPAT_API_KEY,
                                    secret = LastFM.FALLBACK_COMPAT_SECRET,
                                )
                                LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                    .onSuccess { auth ->
                                        AppSettings.setLastfmSessionKey(auth.session.key)
                                        AppSettings.setLastfmUsername(auth.session.name)
                                        AppSettings.setLastfmEnabled(true)
                                        showLastfmLoginDialog = false
                                    }
                                    .onFailure { e ->
                                        lastfmError = e.message ?: "Login failed"
                                    }
                            } catch (e: Exception) {
                                lastfmError = e.message ?: "Login failed"
                            } finally {
                                lastfmLoading = false
                            }
                        }
                    },
                    enabled = !lastfmLoading && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
                ) {
                    Text(if (lastfmLoading) "Signing in..." else "Sign in")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLastfmLoginDialog = false }, enabled = !lastfmLoading) {
                    Text("Cancel")
                }
            },
        )
    }
}

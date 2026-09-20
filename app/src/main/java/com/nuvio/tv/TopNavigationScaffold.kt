package com.nuvio.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.PlayerWindowBackdrop
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters

/** The main menu occupies its own row, so it never covers a poster or the hero. */
@Composable
internal fun TopNavigationScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val showMenu = currentRoute in rootRoutes
    val menuRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val contentRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var menuHasFocus by remember { mutableStateOf(false) }
    var pendingContentRoute by remember { mutableStateOf<String?>(null) }

    fun focusMenu(): Boolean {
        keyboard?.hide()
        val requester = menuRequesters[selectedDrawerRoute] ?: menuRequesters.values.firstOrNull()
        return requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)
    }

    BackHandler(enabled = showMenu) {
        if (!longPressBackHeld.value) {
            if (menuHasFocus) onExitApp() else focusMenu()
        }
    }

    LaunchedEffect(currentRoute, pendingContentRoute) {
        if (pendingContentRoute == null || currentRoute != pendingContentRoute) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { contentRequester.requestFocus() }
        pendingContentRoute = null
    }
    LaunchedEffect(showMenu) {
        if (!showMenu) {
            menuHasFocus = false
            pendingContentRoute = null
        }
    }

    Column(
        Modifier.fillMaxSize().background(
            if (PlayerWindowBackdrop.isTransparentRequested) Color.Transparent else NuvioTheme.colors.Background
        )
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                if (showMenu && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.isLongPress) {
                    if (!longPressBackHeld.value) {
                        longPressBackHeld.value = true
                        focusMenu()
                    }
                    true
                } else if (longPressBackHeld.value) {
                    if (event.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                    true
                } else false
            }
    ) {
        if (showMenu) {
            Row(
                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 24.dp)
                    .onFocusChanged { menuHasFocus = it.hasFocus }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            pendingContentRoute = currentRoute
                            true
                        } else false
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                drawerItems.forEach { item ->
                    val selected = item.route == selectedDrawerRoute
                    Button(
                        onClick = {
                            keyboard?.hide()
                            onNavigate(item.route)
                            navigateToDrawerRoute(navController, currentRoute, item.route)
                            pendingContentRoute = item.route
                        },
                        modifier = Modifier.focusRequester(menuRequesters.getValue(item.route)),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(24.dp)),
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        colors = ButtonDefaults.colors(
                            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.18f)
                                else NuvioTheme.colors.Background,
                            contentColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.TextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        DrawerItemIcon(item.iconRes, item.icon, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(item.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (showProfileSelector) {
                    val switchDescription = stringResource(R.string.enhanced_profile_switch, activeProfileName)
                    Button(
                        onClick = onSwitchProfile,
                        modifier = Modifier.semantics { contentDescription = switchDescription },
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Background,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        ProfileAvatarCircle(
                            name = activeProfileName, colorHex = activeProfileColorHex,
                            avatarImageUrl = activeProfileAvatarImageUrl, size = 32.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(activeProfileName, Modifier.widthIn(max = 88.dp),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).onKeyEvent { event ->
                if (showMenu && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    focusManager.moveFocus(FocusDirection.Up) || focusMenu()
                } else false
            }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides (showMenu && menuHasFocus),
                LocalContentFocusRequester provides contentRequester
            ) {
                NuvioNavHost(navController, startDestination, hideBuiltInHeaders = true)
            }
        }
    }
}

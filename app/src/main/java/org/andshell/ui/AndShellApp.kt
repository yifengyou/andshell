/*
 * AndShell: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package org.andshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import org.andshell.data.entity.Host
import org.andshell.service.TerminalManager
import org.andshell.ui.navigation.AndShellNavHost
import org.andshell.ui.navigation.NavDestinations
import org.andshell.ui.theme.AndShellTheme
import org.andshell.util.IconStyle
import org.andshell.util.ThemeMode

val LocalTerminalManager = compositionLocalOf<TerminalManager?> {
    null
}

@Composable
fun AndShellApp(
    appUiState: AppUiState,
    navController: NavHostController,
    makingShortcut: Boolean,
    authRequired: Boolean,
    isAuthenticated: Boolean,
    themeMode: ThemeMode,
    onAuthenticationSuccess: () -> Unit,
    onRetryMigration: () -> Unit,
    onSelectShortcut: (Host, String?, IconStyle) -> Unit,
    onNavigateToConsole: (Host) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndShellTheme(themeMode = themeMode) {
        when (appUiState) {
            is AppUiState.Loading -> {
                LoadingScreen(modifier = modifier)
            }

            is AppUiState.MigrationInProgress -> {
                MigrationScreen(
                    uiState = MigrationUiState.InProgress(appUiState.state),
                    onRetry = onRetryMigration,
                    modifier = modifier,
                )
            }

            is AppUiState.MigrationFailed -> {
                MigrationScreen(
                    uiState = MigrationUiState.Failed(
                        appUiState.error,
                        appUiState.debugLog,
                    ),
                    onRetry = onRetryMigration,
                    modifier = modifier,
                )
            }

            is AppUiState.Ready -> {
                if (authRequired && !isAuthenticated && !makingShortcut) {
                    AuthenticationScreen(
                        onAuthenticationSuccess = onAuthenticationSuccess,
                        modifier = modifier,
                    )
                } else {
                    CompositionLocalProvider(LocalTerminalManager provides appUiState.terminalManager) {
                        AndShellNavHost(
                            navController = navController,
                            startDestination = NavDestinations.HOST_LIST,
                            makingShortcut = makingShortcut,
                            onSelectShortcut = onSelectShortcut,
                            onNavigateToConsole = onNavigateToConsole,
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }
}

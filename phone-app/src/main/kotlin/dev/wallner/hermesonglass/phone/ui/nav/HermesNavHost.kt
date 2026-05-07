package dev.wallner.hermesonglass.phone.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.wallner.hermesonglass.phone.HermesApp
import dev.wallner.hermesonglass.phone.ui.chat.ChatScreen
import dev.wallner.hermesonglass.phone.ui.chat.ChatViewModel
import dev.wallner.hermesonglass.phone.ui.settings.SettingsScreen
import dev.wallner.hermesonglass.phone.ui.settings.SettingsViewModel

private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun HermesNavHost(app: HermesApp) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_CHAT) {
        composable(ROUTE_CHAT) {
            val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
            ChatScreen(
                viewModel = vm,
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

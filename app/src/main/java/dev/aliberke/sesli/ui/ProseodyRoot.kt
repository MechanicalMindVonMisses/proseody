package dev.aliberke.sesli.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.aliberke.sesli.MainViewModel
import dev.aliberke.sesli.UiEvent
import dev.aliberke.sesli.tts.SpeechEngine
import kotlin.math.roundToLong

@Composable
fun ProseodyRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Open -> nav.navigate("reader/${event.docId}") {
                    popUpTo("library")
                    launchSingleTop = true
                }
                is UiEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(vm, snackbar, onOpen = { id -> nav.navigate("reader/$id") { launchSingleTop = true } })
        }
        composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ReaderScreen(vm, entry.arguments?.getLong("id") ?: 0L, snackbar, onBack = { nav.popBackStack() })
        }
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun listeningSeconds(chars: Int, rate: Float = 1f): Long = (chars / (SpeechEngine.CHARS_PER_SECOND * rate)).roundToLong()

package example.instagram.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import example.instagram.core.LoadPhase
import example.instagram.feed.PostFeedScreen
import example.instagram.post_detail.PostDetailScreen
import milu.viewmodel.ViewModelBindingProvider
import milu.viewmodel.rememberRetainedViewModelBinding
import milu.viewmodel.watchViewModel

@Composable
fun InstagramArchitectureApp(currentUserId: String = "user-milu") {
    ViewModelBindingProvider(binding = rememberRetainedViewModelBinding()) {
        // The parameterized spec application is memoized across recompositions.
        val startupFactory = remember(currentUserId) {
            initViewModelSpec(currentUserId)
        }
        val startup = watchViewModel(startupFactory)
        var selectedPostId by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(startup) {
            startup.initialize()
        }

        when (startup.state.phase) {
            LoadPhase.Ready -> {
                val postId = selectedPostId
                if (postId == null) {
                    PostFeedScreen(
                        userId = currentUserId,
                        onPostSelected = { selectedPostId = it },
                    )
                } else {
                    PostDetailScreen(
                        postId = postId,
                        currentUserId = currentUserId,
                        onBack = { selectedPostId = null },
                    )
                }
            }
            LoadPhase.Failure -> CenteredMessage(
                startup.state.errorMessage ?: "Startup failed.",
            )
            LoadPhase.Idle,
            LoadPhase.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message)
    }
}

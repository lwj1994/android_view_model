package example.instagram.post_detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import example.instagram.core.LoadPhase
import kotlinx.coroutines.launch
import milu.viewmodel.ViewModelBindingProvider
import milu.viewmodel.rememberViewModelBinding
import milu.viewmodel.watchViewModel

@Composable
fun PostDetailScreen(
    postId: String,
    currentUserId: String,
    onBack: () -> Unit,
) {
    ViewModelBindingProvider(binding = rememberViewModelBinding()) {
        val detailFactory = remember(postId, currentUserId) {
            postDetailViewModelSpec(postId, currentUserId)
        }
        val detail = watchViewModel(detailFactory)
        val scope = rememberCoroutineScope()
        var message by remember { mutableStateOf("") }

        LaunchedEffect(detail) {
            detail.load()
        }

        when (detail.state.phase) {
            LoadPhase.Ready -> LazyColumn(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Button(onClick = onBack) { Text("Back") }
                    detail.state.post?.let { post ->
                        Text("@${post.author.username}")
                        Text(post.caption)
                    }
                }
                items(detail.comments.state.comments, key = { it.id }) { comment ->
                    Column {
                        Text("@${comment.author.username}")
                        Text(comment.message)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = message,
                            onValueChange = { message = it },
                            label = { Text("Add a comment") },
                        )
                        Button(
                            enabled = !detail.comments.state.isSubmitting,
                            onClick = {
                                val submittedMessage = message
                                message = ""
                                scope.launch { detail.addComment(submittedMessage) }
                            },
                        ) {
                            Text("Send")
                        }
                    }
                }
            }
            LoadPhase.Failure -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(detail.state.errorMessage ?: "Post failed to load.")
            }
            LoadPhase.Idle,
            LoadPhase.Loading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

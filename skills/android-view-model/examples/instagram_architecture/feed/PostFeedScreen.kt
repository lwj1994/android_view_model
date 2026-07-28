package example.instagram.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import example.instagram.models.Post
import example.instagram.user.userViewModelSpec
import milu.viewmodel.ViewModelBindingProvider
import milu.viewmodel.rememberViewModelBinding
import milu.viewmodel.watchViewModel

@Composable
fun PostFeedScreen(
    userId: String,
    onPostSelected: (String) -> Unit,
) {
    ViewModelBindingProvider(binding = rememberViewModelBinding()) {
        val feedFactory = remember(userId) { postFeedViewModelSpec(userId) }
        val userFactory = remember(userId) { userViewModelSpec(userId) }
        val feed = watchViewModel(feedFactory)
        val currentUser = watchViewModel(userFactory)

        LaunchedEffect(feed, currentUser) {
            // These keyed instances are shared with the startup coordinator.
            currentUser.load()
            feed.load()
        }

        LazyColumn(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(currentUser.state.user?.let { "@${it.username}" } ?: "Instagram VM")
            }
            items(feed.state.posts, key = Post::id) { post ->
                PostCard(post = post, onClick = { onPostSelected(post.id) })
            }
        }
    }
}

@Composable
private fun PostCard(
    post: Post,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("@${post.author.username}")
            Text("♥ ${post.likeCount}")
            Text(post.caption)
        }
    }
}

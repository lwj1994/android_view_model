package example.instagram.feed

import example.instagram.core.LoadPhase
import example.instagram.models.Post
import example.instagram.post.PostRepository
import example.instagram.post.postRepositorySpec
import milu.viewmodel.StateViewModel
import milu.viewmodel.viewModelSpecWithArg

val postFeedViewModelSpec = viewModelSpecWithArg<PostFeedViewModel, String>(
    builder = ::PostFeedViewModel,
    key = { userId -> "instagram.post-feed.$userId" },
)

data class PostFeedState(
    val phase: LoadPhase = LoadPhase.Idle,
    val posts: List<Post> = emptyList(),
    val errorMessage: String? = null,
)

/** Owns the personalized feed for one user. */
class PostFeedViewModel(
    val userId: String,
) : StateViewModel<PostFeedState>(
    initialState = PostFeedState(),
    equals = { previous, current -> previous == current },
) {
    val repository: PostRepository
        get() = viewModelBinding.read(postRepositorySpec)

    suspend fun load(force: Boolean = false) {
        if (state.phase == LoadPhase.Loading) return
        if (!force && state.phase == LoadPhase.Ready) return

        setState(PostFeedState(phase = LoadPhase.Loading, posts = state.posts))
        runCatching { repository.feed(userId) }
            .onSuccess { posts ->
                setState(PostFeedState(phase = LoadPhase.Ready, posts = posts))
            }
            .onFailure { error ->
                setState(
                    PostFeedState(
                        phase = LoadPhase.Failure,
                        posts = state.posts,
                        errorMessage = error.message,
                    ),
                )
                throw error
            }
    }
}

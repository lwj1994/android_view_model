package example.instagram.post_detail

import example.instagram.comment.CommentViewModel
import example.instagram.comment.commentViewModelSpec
import example.instagram.core.LoadPhase
import example.instagram.models.Post
import example.instagram.post.PostRepository
import example.instagram.post.postRepositorySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import milu.viewmodel.StateViewModel
import milu.viewmodel.viewModelSpecWithArg2

val postDetailViewModelSpec = viewModelSpecWithArg2<PostDetailViewModel, String, String>(
    builder = ::PostDetailViewModel,
    key = { postId, currentUserId -> "instagram.post-detail.$currentUserId.$postId" },
)

data class PostDetailState(
    val phase: LoadPhase = LoadPhase.Idle,
    val post: Post? = null,
    val errorMessage: String? = null,
)

/** Owns one post-detail dependency subtree. */
class PostDetailViewModel(
    val postId: String,
    val currentUserId: String,
) : StateViewModel<PostDetailState>(
    initialState = PostDetailState(),
    equals = { previous, current -> previous == current },
) {
    val repository: PostRepository
        get() = viewModelBinding.read(postRepositorySpec)

    // Watch makes comment changes notify this module and then its root screen.
    val comments: CommentViewModel
        get() = viewModelBinding.watch(commentViewModelSpec(postId, currentUserId))

    suspend fun load() {
        if (state.phase == LoadPhase.Loading || state.phase == LoadPhase.Ready) return
        setState(PostDetailState(phase = LoadPhase.Loading))

        runCatching {
            coroutineScope {
                val post = async { repository.post(postId) }
                val commentsLoad = async { comments.load() }
                commentsLoad.await()
                post.await()
            }
        }.onSuccess { post ->
            setState(PostDetailState(phase = LoadPhase.Ready, post = post))
        }.onFailure { error ->
            setState(
                PostDetailState(
                    phase = LoadPhase.Failure,
                    errorMessage = error.message,
                ),
            )
        }
    }

    suspend fun addComment(message: String) {
        comments.add(message)
    }
}

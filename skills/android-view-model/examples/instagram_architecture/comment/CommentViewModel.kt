package example.instagram.comment

import example.instagram.core.InstagramDemoException
import example.instagram.core.LoadPhase
import example.instagram.models.Comment
import example.instagram.user.UserViewModel
import example.instagram.user.userViewModelSpec
import milu.viewmodel.StateViewModel
import milu.viewmodel.viewModelSpecWithArg2

val commentViewModelSpec = viewModelSpecWithArg2<CommentViewModel, String, String>(
    builder = ::CommentViewModel,
    key = { postId, currentUserId -> "instagram.comments.$currentUserId.$postId" },
)

data class CommentState(
    val phase: LoadPhase = LoadPhase.Idle,
    val comments: List<Comment> = emptyList(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

/** Owns comments for one post and writes as the current user. */
class CommentViewModel(
    val postId: String,
    val currentUserId: String,
) : StateViewModel<CommentState>(
    initialState = CommentState(),
    equals = { previous, current -> previous == current },
) {
    val repository: CommentRepository
        get() = viewModelBinding.read(commentRepositorySpec)

    // This resolves the same keyed UserViewModel used by startup.
    val currentUser: UserViewModel
        get() = viewModelBinding.read(userViewModelSpec(currentUserId))

    suspend fun load() {
        if (state.phase == LoadPhase.Loading || state.phase == LoadPhase.Ready) return

        setState(CommentState(phase = LoadPhase.Loading, comments = state.comments))
        runCatching { repository.comments(postId) }
            .onSuccess { comments ->
                setState(CommentState(phase = LoadPhase.Ready, comments = comments))
            }
            .onFailure { error ->
                setState(
                    CommentState(
                        phase = LoadPhase.Failure,
                        comments = state.comments,
                        errorMessage = error.message,
                    ),
                )
                throw error
            }
    }

    suspend fun add(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.isEmpty() || state.isSubmitting) return
        val author = currentUser.state.user
            ?: throw InstagramDemoException(
                "InitViewModel must load the current user before comments are submitted.",
            )

        setState(state.copy(isSubmitting = true, errorMessage = null))
        runCatching { repository.addComment(postId, author, message) }
            .onSuccess { comment ->
                setState(
                    state.copy(
                        phase = LoadPhase.Ready,
                        comments = state.comments + comment,
                        isSubmitting = false,
                    ),
                )
            }
            .onFailure { error ->
                setState(
                    state.copy(
                        isSubmitting = false,
                        errorMessage = error.message,
                    ),
                )
                throw error
            }
    }
}

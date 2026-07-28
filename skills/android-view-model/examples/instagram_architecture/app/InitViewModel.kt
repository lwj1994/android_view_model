package example.instagram.app

import example.instagram.core.LoadPhase
import example.instagram.feed.PostFeedViewModel
import example.instagram.feed.postFeedViewModelSpec
import example.instagram.user.UserViewModel
import example.instagram.user.userViewModelSpec
import milu.viewmodel.StateViewModel
import milu.viewmodel.viewModelSpecWithArg

val initViewModelSpec = viewModelSpecWithArg<InitViewModel, String>(
    builder = ::InitViewModel,
    key = { currentUserId -> "instagram.init.$currentUserId" },
)

data class InitState(
    val phase: LoadPhase = LoadPhase.Idle,
    val errorMessage: String? = null,
)

/** Coordinates startup order without owning user or feed behavior. */
class InitViewModel(
    val currentUserId: String,
) : StateViewModel<InitState>(
    initialState = InitState(),
    equals = { previous, current -> previous == current },
) {
    val user: UserViewModel
        get() = viewModelBinding.read(userViewModelSpec(currentUserId))

    val feed: PostFeedViewModel
        get() = viewModelBinding.read(postFeedViewModelSpec(currentUserId))

    suspend fun initialize() {
        if (state.phase == LoadPhase.Loading || state.phase == LoadPhase.Ready) return
        setState(InitState(phase = LoadPhase.Loading))

        runCatching {
            // Feed loading requires the restored current-user module first.
            user.load()
            feed.load()
        }.onSuccess {
            setState(InitState(phase = LoadPhase.Ready))
        }.onFailure { error ->
            setState(
                InitState(
                    phase = LoadPhase.Failure,
                    errorMessage = error.message,
                ),
            )
        }
    }
}

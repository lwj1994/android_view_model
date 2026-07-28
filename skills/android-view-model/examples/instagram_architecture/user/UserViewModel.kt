package example.instagram.user

import example.instagram.core.LoadPhase
import example.instagram.models.User
import milu.viewmodel.StateViewModel
import milu.viewmodel.viewModelSpecWithArg

val userViewModelSpec = viewModelSpecWithArg<UserViewModel, String>(
    builder = ::UserViewModel,
    key = { userId -> "instagram.user.$userId" },
)

data class UserState(
    val phase: LoadPhase = LoadPhase.Idle,
    val user: User? = null,
    val errorMessage: String? = null,
)

/** Loads and exposes one user identified by [userId]. */
class UserViewModel(
    val userId: String,
) : StateViewModel<UserState>(
    initialState = UserState(),
    equals = { previous, current -> previous == current },
) {
    // Repository notifications do not need to refresh this feature module.
    val repository: UserRepository
        get() = viewModelBinding.read(userRepositorySpec)

    suspend fun load(force: Boolean = false) {
        if (state.phase == LoadPhase.Loading) return
        if (!force && state.phase == LoadPhase.Ready) return

        setState(UserState(phase = LoadPhase.Loading, user = state.user))
        runCatching { repository.user(userId) }
            .onSuccess { user ->
                setState(UserState(phase = LoadPhase.Ready, user = user))
            }
            .onFailure { error ->
                setState(
                    UserState(
                        phase = LoadPhase.Failure,
                        user = state.user,
                        errorMessage = error.message,
                    ),
                )
                throw error
            }
    }
}

package example.instagram.user

import milu.viewmodel.readViewModel
import example.instagram.core.InstagramApi
import example.instagram.core.instagramApiSpec
import example.instagram.models.User
import milu.viewmodel.ViewModel
import milu.viewmodel.viewModelSpec

val userRepositorySpec = viewModelSpec(
    key = "instagram.user-repository",
) {
    UserRepository()
}

class UserRepository : ViewModel() {
    val api: InstagramApi by readViewModel(instagramApiSpec) { viewModelBinding }

    suspend fun user(id: String): User = api.fetchUser(id)
}

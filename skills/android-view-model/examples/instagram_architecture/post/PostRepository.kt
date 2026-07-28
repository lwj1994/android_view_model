package example.instagram.post

import example.instagram.core.InstagramApi
import example.instagram.core.instagramApiSpec
import example.instagram.models.Post
import milu.viewmodel.ViewModel
import milu.viewmodel.viewModelSpec

val postRepositorySpec = viewModelSpec(
    key = "instagram.post-repository",
) {
    PostRepository()
}

class PostRepository : ViewModel() {
    val api: InstagramApi
        get() = viewModelBinding.read(instagramApiSpec)

    suspend fun feed(userId: String): List<Post> = api.fetchFeed(userId)

    suspend fun post(postId: String): Post = api.fetchPost(postId)
}

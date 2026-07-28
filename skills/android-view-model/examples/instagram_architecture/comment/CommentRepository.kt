package example.instagram.comment

import example.instagram.core.InstagramApi
import example.instagram.core.instagramApiSpec
import example.instagram.models.Comment
import example.instagram.models.User
import milu.viewmodel.ViewModel
import milu.viewmodel.viewModelSpec

val commentRepositorySpec = viewModelSpec(
    key = "instagram.comment-repository",
) {
    CommentRepository()
}

class CommentRepository : ViewModel() {
    val api: InstagramApi
        get() = viewModelBinding.read(instagramApiSpec)

    suspend fun comments(postId: String): List<Comment> = api.fetchComments(postId)

    suspend fun addComment(
        postId: String,
        author: User,
        message: String,
    ): Comment = api.createComment(postId, author, message)
}

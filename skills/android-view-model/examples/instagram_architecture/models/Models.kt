package example.instagram.models

data class User(
    val id: String,
    val username: String,
    val displayName: String,
)

data class Post(
    val id: String,
    val author: User,
    val caption: String,
    val likeCount: Int,
)

data class Comment(
    val id: String,
    val postId: String,
    val author: User,
    val message: String,
)

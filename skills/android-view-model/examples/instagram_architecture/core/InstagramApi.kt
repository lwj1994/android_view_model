package example.instagram.core

import example.instagram.models.Comment
import example.instagram.models.Post
import example.instagram.models.User
import kotlinx.coroutines.delay
import milu.viewmodel.ViewModel
import milu.viewmodel.viewModelSpec

val instagramApiSpec = viewModelSpec(
    key = "instagram.api",
) {
    InstagramApi()
}

/** Simulates a remote API with in-memory data so the architecture stays visible. */
class InstagramApi : ViewModel() {
    private val currentUser = User(
        id = "user-milu",
        username = "milu",
        displayName = "Milu",
    )

    private val ada = User(
        id = "user-ada",
        username = "ada",
        displayName = "Ada Lovelace",
    )

    private val linus = User(
        id = "user-linus",
        username = "linus",
        displayName = "Linus Torvalds",
    )

    private val users = listOf(currentUser, ada, linus)

    private val posts = listOf(
        Post(
            id = "post-1",
            author = ada,
            caption = "Break complex systems into modules with clear boundaries.",
            likeCount = 1_842,
        ),
        Post(
            id = "post-2",
            author = linus,
            caption = "Good architecture makes lifecycle relationships visible.",
            likeCount = 936,
        ),
    )

    private val comments = mutableMapOf(
        "post-1" to mutableListOf(
            Comment(
                id = "comment-1",
                postId = "post-1",
                author = currentUser,
                message = "The dependency graph is easy to follow.",
            ),
        ),
        "post-2" to mutableListOf(),
    )

    private var nextCommentId = 2

    suspend fun fetchUser(userId: String): User {
        simulateLatency()
        return users.firstOrNull { it.id == userId }
            ?: throw InstagramDemoException("No demo user exists for $userId.")
    }

    suspend fun fetchFeed(userId: String): List<Post> {
        fetchUser(userId)
        return posts.toList()
    }

    suspend fun fetchPost(postId: String): Post {
        simulateLatency()
        return posts.firstOrNull { it.id == postId }
            ?: throw InstagramDemoException("No demo post exists for $postId.")
    }

    suspend fun fetchComments(postId: String): List<Comment> {
        simulateLatency()
        return comments[postId]?.toList().orEmpty()
    }

    suspend fun createComment(
        postId: String,
        author: User,
        message: String,
    ): Comment {
        simulateLatency()
        val comment = Comment(
            id = "comment-${nextCommentId++}",
            postId = postId,
            author = author,
            message = message,
        )
        comments.getOrPut(postId) { mutableListOf() }.add(comment)
        return comment
    }

    private suspend fun simulateLatency() {
        delay(150)
    }
}

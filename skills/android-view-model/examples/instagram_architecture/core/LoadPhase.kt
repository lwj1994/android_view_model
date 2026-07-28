package example.instagram.core

enum class LoadPhase {
    Idle,
    Loading,
    Ready,
    Failure,
}

class InstagramDemoException(message: String) : IllegalStateException(message)

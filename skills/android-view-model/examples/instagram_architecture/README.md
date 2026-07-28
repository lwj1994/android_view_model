# Instagram Multi-Module Architecture Example

This is an illustrative Compose application organized by functional module.
It mirrors the Flutter `view_model` skill's Instagram example while using
AndroidViewModel APIs and Android host conventions.

The directory is documentation source for the bundled skill. It is
intentionally outside the Gradle source sets and is not required to compile as
a standalone application. Copy the files into an Android app module and adjust
package names, navigation, and UI dependencies if you want to run it.

## Directory Structure

```text
instagram_architecture/
├── README.md
├── app/
│   ├── InstagramApplication.kt    # Process initialization
│   ├── InstagramApp.kt            # App root and retained binding
│   └── InitViewModel.kt           # Startup flow coordinator
├── core/
│   ├── InstagramApi.kt            # Shared remote-capability ViewModel
│   └── LoadPhase.kt
├── models/
│   └── Models.kt
├── user/
│   ├── UserRepository.kt
│   └── UserViewModel.kt
├── post/
│   └── PostRepository.kt          # Shared by feed and post detail
├── feed/
│   ├── PostFeedScreen.kt
│   └── PostFeedViewModel.kt
├── comment/
│   ├── CommentRepository.kt
│   └── CommentViewModel.kt
└── post_detail/
    ├── PostDetailScreen.kt
    └── PostDetailViewModel.kt
```

## Dependency Graph

```text
InstagramArchitectureApp retained binding
└── InitViewModel(currentUserId)
    ├── UserViewModel(currentUserId)
    │   └── UserRepository ───────────────┐
    └── PostFeedViewModel(currentUserId)  │
        └── PostRepository ───────────────┤
                                          └── InstagramApi

PostDetailScreen composition binding
└── PostDetailViewModel(postId, currentUserId)
    ├── PostRepository (shared with Feed)
    └── CommentViewModel(postId, currentUserId)
        ├── UserViewModel (shared with startup flow)
        └── CommentRepository ── InstagramApi (same instance)
```

## Key Design Decisions

- The API, repositories, feature state, and startup coordinator are managed
  ViewModels. Data entities remain immutable Kotlin data classes.
- Every spec is stable and declared beside the module it constructs. Normal
  dependency resolution always keeps the spec and calls `read(spec)` or
  `watch(spec)`; the example never uses cached lookup.
- Identity-bearing modules receive context explicitly. Parameterized specs
  derive keys from `userId` and `postId`, so separate users and posts cannot
  collide.
- Repositories stay context-free. IDs are method arguments rather than mutable
  repository fields.
- API and repository modules use explicit keys because independent Compose
  root bindings intentionally share them. They do not use `aliveForever`;
  ownership from active roots and dependency edges is sufficient.
- `PostDetailViewModel` watches `CommentViewModel` because comment state must
  propagate through the parent and recompose the detail screen. Command-only
  dependencies use `read`.
- Every nested ViewModel is exposed through a resolver property. No dependency
  is stored with `by lazy` or in another long-lived field.
- Applied parameterized specs are memoized with `remember(...)` before they are
  passed to `watchViewModel`. This keeps the factory stable across
  recompositions while the spec-derived key defines instance identity.
- The app root uses `rememberRetainedViewModelBinding`; feature screens use
  `rememberViewModelBinding` so their root ownership ends when the composition
  leaves. Shared keyed modules remain alive while another owner still exists.
- ViewModel state changes stay on the main thread. Suspended repository calls
  return to the main dispatcher before `setState` runs.

## Suggested Reading Order

1. Start with `app/InitViewModel.kt` to see startup coordination.
2. Follow its resolver properties into the user and feed modules.
3. Open `post_detail/PostDetailViewModel.kt` to see a watched child module.
4. Compare the retained app binding with the feature-local bindings.

# AGENTS.md

给 AI / 自动化工具使用的 AndroidViewModel 工程约束。

## 一句话说明

AndroidViewModel 是 Flutter `view_model` 核心模型的 Android 实现：业务能力可
建模为 ViewModel，通过稳定 spec 与 `watch/read` 组合；binding graph 使用
source-aware owner 路径管理生命周期，并在最后一条 owner 路径离开时自动销毁。

## 目录

```text
android-view-model/src/main/kotlin/milu/viewmodel/  核心 runtime 与 Android host 集成
android-view-model/src/test/kotlin/milu/viewmodel/  单元与生命周期契约测试
example/                                            Host 使用示例
skills/android-view-model/                          AI Skill
```

## 核心不变式

1. 稳定 spec 的 `watch/read` 是主入口；两者都会创建/获取、bind，并观察 handle
   disposal，只有 `watch` 监听 VM 自身通知。即使 spec 带 key/tag，也继续
   传 spec；cached API 只查询其他路径已创建的实例，是高级 escape hatch，不能与
   主入口并列推荐。README、Skill、示例与公开 API 注释都必须保持这个优先级。
2. identity 是解析 ViewModel 类型 + effective key。无 key 时，同一 binding 内
   同类型复用、不同 binding 隔离；显式 key 仅用于跨 binding 共享或同类型多实例。
3. 默认使用受 binding 管理的非 singleton 模块，不要为普通 service 自动添加
   key 或 `aliveForever`。所有 `aliveForever` spec 都必须显式 key，root 与 nested
   解析统一在 builder 执行前校验，底层 Store 也必须兜底；`recycle/debugReset`
   仍可强制销毁。
4. 每个 parent generation 延迟拥有稳定 dependency binding。它保活已解析 child、
   实时传播 root owners；direct 与多个 parent 路径按 source 独立释放。
5. 嵌套 ViewModel 与 host 中可能经历 recycle 的 ViewModel 必须通过
   resolver property 获取，不得使用 `by lazy`/stored reference 长期缓存。
6. ViewModel 内 `read` 不冒泡 child 自身通知；`watch` 先调用
   `parent.onDependencyNotify(child)` 再通知 parent。同步 graph 按 binding 去重。
7. 不提供原位替换实例的 `recreate` API。需要独立新实例时使用显式新 key；若明确
   接受影响所有 owners，则先全局 `recycle`，再由 resolver getter 通过
   `watch/read(spec)` 走正常 cache-miss 路径创建新 handle 与 dependency tree，
   不迁移旧对象关系。
8. 所有公开 ViewModel API 都只能在主线程调用；业务 ViewModel 不继承 AndroidX
   `ViewModel`，AndroidX 只用于 host retention。

## 测试规则

- 测试必须单线程、单 JVM fork、按 runner 顺序执行。禁止 parallel fork、测试
  分片或 concurrent runner；registry、config、lifecycle、reset 与 spec proxy
  都是进程级状态。
- `android-view-model/build.gradle.kts` 中的 `maxParallelForks = 1` 不得移除。
- ViewModel 构造调用必须放在 `viewModelSpec` builder 内；测试体和 `setUp()`
  不得直接实例化受管 ViewModel。
- 不要把 ViewModel 存在测试字段；使用由测试 binding 解析的 getter。
- 每个 binding 必须 dispose，并在用例间 reset `InstanceManager` 与 `ViewModel`。

## 验证命令

```bash
./gradlew :android-view-model:testDebugUnitTest \
  :android-view-model:assembleDebug \
  :android-view-model:lintDebug \
  --no-parallel --max-workers=1
```

不要给验证命令添加 `--parallel`。

## 发布流程

AndroidViewModel 当前以 JitPack tag 为推荐分发方式：

1. 更新 `android-view-model/build.gradle.kts` 的版本与 README 安装示例。
2. 更新 `CHANGELOG.md`，并执行上述串行测试、构建与 Lint。
3. 提交并推送 `main`。
4. 创建 annotated tag `vX.Y.Z`，推送 tag，再创建同名 GitHub Release。

不要移动已经推送的 tag；需要修正时发布新版本。除非用户明确要求并且 Maven
Central 凭据可用，否则不要额外执行 Maven Central 发布任务。

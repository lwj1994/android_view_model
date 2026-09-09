# AndroidViewModel 示例

示例统一通过 `by watchViewModel/readViewModel` 声明 VM 属性。

- [MainActivity.kt](src/main/kotlin/milu/viewmodel/example/MainActivity.kt)：Activity、Compose、Fragment view、Activity 共享 binding 和自定义 View。
- [CounterModels.kt](src/main/kotlin/milu/viewmodel/example/CounterModels.kt)：嵌套 ViewModel 与普通类的 binding scope。
- [ProcessCounterExample.kt](src/main/kotlin/milu/viewmodel/example/ProcessCounterExample.kt)：跨进程状态同步和 Compose 消费。

Compose 中使用：

```kotlin
val counter by watchViewModel(counterSpec)
ComposeButton(onClick = { counter.reset() }) {
    Text("Reset")
}
```

Host 和嵌套 ViewModel 显式传入 binding lambda：

```kotlin
val counter by watchViewModel(counterSpec) { viewModelBinding }
val analytics by readViewModel(analyticsSpec) { viewModelBinding }
```

Fragment view 使用 `{ viewLifecycleViewModelBinding }`，普通类使用
`{ scope.viewModelBinding }`。委托负责每次访问时解析当前实例；recycle 后继续访问同一属性即可。

不要另存 VM 实例或使用 `by lazy`、`remember { vm }`。事件回调使用
`{ vm.action() }`，避免 `vm::action` 提前捕获旧实例。跨组件传稳定 spec，
Composable 边界传不可变渲染值和事件回调。

普通类在结束时关闭 scope；其他 host 由对应 binding 生命周期负责释放。

package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KProperty

/**
 * 只保存解析逻辑的只读属性委托。每次访问都通过当前 ownership 边界解析，绝不缓存 VM。
 * 必须在主线程、所属 owner 存活期间使用；不要跨 owner 传递委托或解析出的实例。
 */
@MainThread
public class ViewModelDelegate<VM : ViewModel> internal constructor(
    private val resolve: () -> VM,
) {
    public operator fun getValue(thisRef: Any?, property: KProperty<*>): VM {
        assertMainThread()
        return resolve()
    }
}

/** 延迟获取 binding，适用于 Fragment view lifecycle 等会更换 binding 的 host。 */
@MainThread
public fun <VM : ViewModel> watchViewModel(
    factory: ViewModelFactory<VM>,
    binding: () -> ViewModelBinding,
): ViewModelDelegate<VM> {
    assertMainThread()
    return ViewModelDelegate { binding().watch(factory) }
}

/** 与 watch 相同的 ownership/disposal 语义，但不监听 VM 自身通知。 */
@MainThread
public fun <VM : ViewModel> readViewModel(
    factory: ViewModelFactory<VM>,
    binding: () -> ViewModelBinding,
): ViewModelDelegate<VM> {
    assertMainThread()
    return ViewModelDelegate { binding().read(factory) }
}


package milu.viewmodel.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class ViewModelDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = ViewModelDetector()
    override fun getIssues(): List<Issue> = ViewModelDetector.issues

    private val api = kotlin("""
        package milu.viewmodel
        import kotlin.reflect.KProperty
        open class ViewModel { val viewModelBinding = ViewModelBinding() }
        interface ViewModelFactory<T : ViewModel> { fun build(): T }
        class ViewModelSpec<T : ViewModel>(val builder: () -> T, val key: Any? = null,
            val aliveForever: Boolean = false) : ViewModelFactory<T> {
            override fun build(): T = builder()
        }
        class ViewModelSpecWithArg<T : ViewModel, A>(val builder: (A) -> T,
            val key: ((A) -> Any?)? = null, val aliveForever: ((A) -> Boolean)? = null)
        fun <T : ViewModel> viewModelSpec(key: Any? = null, aliveForever: Boolean = false,
            builder: () -> T) = ViewModelSpec(builder, key, aliveForever)
        fun <T : ViewModel, A> viewModelSpecWithArg(builder: (A) -> T,
            key: ((A) -> Any?)? = null, aliveForever: ((A) -> Boolean)? = null) =
            ViewModelSpecWithArg(builder, key, aliveForever)
        class ViewModelBinding {
            fun <T : ViewModel> read(spec: ViewModelFactory<T>): T = spec.build()
            fun <T : ViewModel> watch(spec: ViewModelFactory<T>): T = spec.build()
        }
        class ViewModelDelegate<T : ViewModel>(val resolve: () -> T) {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): T = resolve()
        }
        fun <T : ViewModel> readViewModel(spec: ViewModelFactory<T>,
            binding: () -> ViewModelBinding): ViewModelDelegate<T> = ViewModelDelegate { binding().read(spec) }
        fun <T : ViewModel> ViewModelBinding.readViewModel(spec: ViewModelFactory<T>): ViewModelDelegate<T> =
            ViewModelDelegate { read(spec) }
    """).indented()

    fun testUsageWarnings() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            class Child : ViewModel()
            val retained = viewModelSpec(aliveForever = true) { Child() }
            val args = viewModelSpecWithArg<Child, String>(builder = { Child() })
            class Host {
                val binding = ViewModelBinding()
                val cached = binding.read(retained)
                val lazyChild by lazy { binding.read(retained) }
                fun direct() = Child()
            }
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run()
            .expectWarningCount(4)
            .expectContains("[ViewModelAliveForeverKey]")
            .expectContains("[ViewModelCachedInstance]")
            .expectContains("[ViewModelUnmanagedConstruction]")
    }

    fun testUnkeyedParameterizedSpecsAreAllowed() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            class Child : ViewModel()
            val omitted = viewModelSpecWithArg<Child, Int>(builder = { Child() })
            val nullKey = ViewModelSpecWithArg<Child, Int>(builder = { Child() }, key = null)
            val nullCallback = viewModelSpecWithArg<Child, Int>(builder = { Child() }, key = { null })
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
    }

    fun testValidBuildersGettersDelegatesAndLocals() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            class Child : ViewModel()
            val spec = viewModelSpec(key = "child", aliveForever = true) { Child() }
            val args = viewModelSpecWithArg<Child, String>(builder = { Child() }, key = { it })
            class Factory : ViewModelFactory<Child> { override fun build() = Child() }
            class Host {
                val binding = ViewModelBinding()
                val child by readViewModel(spec) { binding }
                val resolver = readViewModel(spec) { binding }
                val computed get() = binding.read(spec)
                val callback = { binding.read(spec) }
                val text by lazy { binding.read(spec); "ready" }
                val localLazyCallback = {
                    val local by lazy { binding.read(spec) }
                    local
                }
                fun use(): Child { val local = binding.watch(spec); return local }
            }
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
    }

    fun testCyclesAcrossFilesAndDelegates() {
        lint().files(api,
            kotlin("""
                package test
                import milu.viewmodel.*
                val aSpec = viewModelSpec { A() }
                class A : ViewModel() {
                    val b by readViewModel(bSpec) { viewModelBinding }
                }
            """).indented().to("src/test/A.kt"),
            kotlin("""
                package test
                import milu.viewmodel.*
                val bSpec = viewModelSpec { B() }
                class B : ViewModel() {
                    val a get() = viewModelBinding.read(aSpec)
                }
            """).indented().to("src/test/B.kt"),
        ).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectWarningCount(2)
            .expectContains("test.A -> test.B -> test.A")
            .expectContains("test.B -> test.A -> test.B")
    }

    fun testNullKeysAndBuilderExemptionIsNarrow() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            class Child : ViewModel()
            val spec = viewModelSpecWithArg<Child, String>(
                builder = { Child() }, key = { null }, aliveForever = { true })
            val wrong = viewModelSpec(key = Child()) { Child() }
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run()
            .expectWarningCount(2)
    }

    fun testFieldAssignmentAndUnrelatedNames() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            class Child : ViewModel()
            val spec = viewModelSpec { Child() }
            class Host {
                val binding = ViewModelBinding()
                lateinit var child: Child
                fun initialize() { child = binding.read(spec) }
            }
            class Other { fun read(value: Int) = value }
            val ordinary = Other().read(1)
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectWarningCount(1)
    }

    fun testEachRuleCanBeDisabled() {
        for (issue in ViewModelDetector.issues) {
            lint().files(api, kotlin("""
                package test
                import milu.viewmodel.*
                class Child : ViewModel()
                val spec = viewModelSpec { Child() }
                val retained = viewModelSpec(aliveForever = true) { Child() }
                val args = viewModelSpecWithArg<Child, String>(builder = { Child() })
                val direct = Child()
                class Host { val child = ViewModelBinding().read(spec) }
            """).indented()).issues(issue).overrideConfig(xml("lint.xml", "<lint><issue id=\"${issue.id}\" severity=\"ignore\" /></lint>"))
                .allowMissingSdk().testModes(TestMode.DEFAULT)
                .run().expectClean()
        }
    }

    fun testApplicationSubpackageIsNotFramework() {
        lint().files(api, kotlin("""
            package milu.viewmodel.example
            import milu.viewmodel.ViewModel
            class Child : ViewModel()
            fun viewModelSpec(aliveForever: Boolean = false) = aliveForever
            fun viewModelSpecWithArg(value: Int) = value
            class Source { fun read(): Child = error("unrelated API") }
            val retained = viewModelSpec(aliveForever = true)
            val args = viewModelSpecWithArg(1)
            val child = Source().read()
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
    }

    fun testSeparateBindingsDoNotFormParentEdges() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            val spec = viewModelSpec { Child() }
            class Child : ViewModel() {
                val otherBinding = ViewModelBinding()
                val other by readViewModel(spec) { otherBinding }
                val computed get() = otherBinding.read(spec)
            }
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
    }

    fun testFixedBindingDelegateAndAliasedFactory() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            import milu.viewmodel.viewModelSpec as makeSpec
            val spec = makeSpec { Child() }
            class Child : ViewModel() {
                val self by viewModelBinding.readViewModel(spec)
            }
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run()
            .expectWarningCount(1).expectContains("[ViewModelDependencyCycle]")
    }

    fun testLocalSuppressions() {
        lint().files(api, kotlin("""
            package test
            import milu.viewmodel.*
            @Suppress("ViewModelUnmanagedConstruction", "ViewModelCachedInstance",
                "ViewModelAliveForeverKey", "ViewModelDependencyCycle")
            class Child : ViewModel() {
                val spec = viewModelSpec(aliveForever = true) { Child() }
                val direct = Child()
                val self = viewModelBinding.read(spec)
                val args = viewModelSpecWithArg<Child, Int>(builder = { Child() })
            }
        """).indented()).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
    }
}

package milu.viewmodel

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.reflect.typeOf

class SpecArityContractTest {
    private val bindings = mutableListOf<ViewModelBinding>()
    private fun resolve(spec: ViewModelSpec<ArityModel>): String {
        val binding = ViewModelBinding().also(bindings::add)
        val vm by binding.readViewModel(spec)
        return vm.arguments
    }
    @Before fun setUp() = ViewModel.reset()
    @After fun tearDown() {
        bindings.asReversed().forEach { it.dispose() }
        ViewModel.reset()
    }

    @Test fun zeroArgumentConstructorsAndFactoryDefaultsPreserveIdentity() {
        val raw = ViewModelSpec(ArityModel::class) { ArityModel("raw") }
        val typed = ViewModelSpec<ArityModel>(typeOf<ArityModel>()) { ArityModel("typed") }
        assertEquals(ArityModel::class, raw.modelClass)
        assertNull(raw.modelType)
        assertEquals(typeOf<ArityModel>(), typed.modelType)
        assertEquals("raw", resolve(raw))
        assertEquals("typed", resolve(typed))
        val binding = ViewModelBinding().also(bindings::add)
        val factory = object : ViewModelFactory<ArityModel> {
            override val modelClass = ArityModel::class
            override fun build() = raw.build()
        }
        val vm by binding.readViewModel(factory)
        assertEquals("raw", vm.arguments)
        assertNull(factory.key())
        assertNull(factory.tag())
        assertFalse(factory.aliveForever())
        val legacyTyped = object : TypedViewModelFactory<ArityModel>, ViewModelFactory<ArityModel> by factory {
            override val modelType = null
        }
        assertSame(vm, binding.read(legacyTyped))
    }

    @Test fun arity1PreservesArgumentOrderMetadataAndEveryOverrideScope() = runTest {
        val base = viewModelSpecWithArg<ArityModel, String>(
            builder = { a -> ArityModel("base:" + a) },
            key = { a -> "key:" + a },
            tag = { a -> "tag:" + a },
            aliveForever = { a -> a.isNotEmpty() },
        )
        val fake = viewModelSpecWithArg<ArityModel, String>(
            builder = { a -> ArityModel("fake:" + a) },
        )
        val raw = ViewModelSpecWithArg<ArityModel, String>(
            modelClass = ArityModel::class,
            builder = { a -> ArityModel("raw:" + a) },
        )
        val typed = ViewModelSpecWithArg<ArityModel, String>(
            modelType = typeOf<ArityModel>(),
            builder = { a -> ArityModel("typed:" + a) },
        )
        val fullRaw = ViewModelSpecWithArg<ArityModel, String>(
            modelClass = ArityModel::class,
            builder = { a -> ArityModel("full:" + a) },
            key = { a -> "full-key:" + a },
            tag = { a -> "full-tag:" + a },
            aliveForever = { a -> false },
        )
        assertEquals("raw:1", resolve(raw("1")))
        assertEquals("typed:1", resolve(typed("1")))
        assertEquals("full:1", resolve(fullRaw("1")))
        assertEquals("full-key:1", fullRaw("1").key())
        assertEquals("full-tag:1", fullRaw("1").tag())
        assertFalse(fullRaw("1").aliveForever())
        assertEquals("base:1", resolve(base("1")))
        assertEquals("key:1", base("1").key())
        assertEquals("tag:1", base("1").tag())
        assertTrue(base("1").aliveForever())
        base.setProxy(fake)
        assertEquals("fake:1", resolve(base("1")))
        assertNull(base("1").key())
        assertNull(base("1").tag())
        assertFalse(base("1").aliveForever())
        base.clearProxy()
        assertEquals("base:1", resolve(base("1")))
        val restore = base.overrideWith(raw)
        assertEquals("raw:1", resolve(base("1")))
        restore()
        restore()
        base.runWithOverride(fake) {
            yield()
            assertEquals("fake:1", resolve(base("1")))
            val restoreLocal = base.overrideWith(typed)
            val restoreInner = base.overrideWith(raw)
            restoreLocal()
            assertEquals("raw:1", resolve(base("1")))
            restoreInner()
            restoreInner()
            assertEquals("fake:1", resolve(base("1")))
        }
        assertEquals("base:1", resolve(base("1")))
    }

    @Test fun arity2PreservesArgumentOrderMetadataAndEveryOverrideScope() = runTest {
        val base = viewModelSpecWithArg2<ArityModel, String, String>(
            builder = { a, b -> ArityModel("base:" + a + ":" + b) },
            key = { a, b -> "key:" + a + ":" + b },
            tag = { a, b -> "tag:" + a + ":" + b },
            aliveForever = { a, b -> a.isNotEmpty() },
        )
        val fake = viewModelSpecWithArg2<ArityModel, String, String>(
            builder = { a, b -> ArityModel("fake:" + a + ":" + b) },
        )
        val raw = ViewModelSpecWithArg2<ArityModel, String, String>(
            modelClass = ArityModel::class,
            builder = { a, b -> ArityModel("raw:" + a + ":" + b) },
        )
        val typed = ViewModelSpecWithArg2<ArityModel, String, String>(
            modelType = typeOf<ArityModel>(),
            builder = { a, b -> ArityModel("typed:" + a + ":" + b) },
        )
        val fullRaw = ViewModelSpecWithArg2<ArityModel, String, String>(
            modelClass = ArityModel::class,
            builder = { a, b -> ArityModel("full:" + a + ":" + b) },
            key = { a, b -> "full-key:" + a + ":" + b },
            tag = { a, b -> "full-tag:" + a + ":" + b },
            aliveForever = { a, b -> false },
        )
        assertEquals("raw:1:2", resolve(raw("1", "2")))
        assertEquals("typed:1:2", resolve(typed("1", "2")))
        assertEquals("full:1:2", resolve(fullRaw("1", "2")))
        assertEquals("full-key:1:2", fullRaw("1", "2").key())
        assertEquals("full-tag:1:2", fullRaw("1", "2").tag())
        assertFalse(fullRaw("1", "2").aliveForever())
        assertEquals("base:1:2", resolve(base("1", "2")))
        assertEquals("key:1:2", base("1", "2").key())
        assertEquals("tag:1:2", base("1", "2").tag())
        assertTrue(base("1", "2").aliveForever())
        base.setProxy(fake)
        assertEquals("fake:1:2", resolve(base("1", "2")))
        assertNull(base("1", "2").key())
        assertNull(base("1", "2").tag())
        assertFalse(base("1", "2").aliveForever())
        base.clearProxy()
        assertEquals("base:1:2", resolve(base("1", "2")))
        val restore = base.overrideWith(raw)
        assertEquals("raw:1:2", resolve(base("1", "2")))
        restore()
        restore()
        base.runWithOverride(fake) {
            yield()
            assertEquals("fake:1:2", resolve(base("1", "2")))
            val restoreLocal = base.overrideWith(typed)
            val restoreInner = base.overrideWith(raw)
            restoreLocal()
            assertEquals("raw:1:2", resolve(base("1", "2")))
            restoreInner()
            restoreInner()
            assertEquals("fake:1:2", resolve(base("1", "2")))
        }
        assertEquals("base:1:2", resolve(base("1", "2")))
    }

    @Test fun arity3PreservesArgumentOrderMetadataAndEveryOverrideScope() = runTest {
        val base = viewModelSpecWithArg3<ArityModel, String, String, String>(
            builder = { a, b, c -> ArityModel("base:" + a + ":" + b + ":" + c) },
            key = { a, b, c -> "key:" + a + ":" + b + ":" + c },
            tag = { a, b, c -> "tag:" + a + ":" + b + ":" + c },
            aliveForever = { a, b, c -> a.isNotEmpty() },
        )
        val fake = viewModelSpecWithArg3<ArityModel, String, String, String>(
            builder = { a, b, c -> ArityModel("fake:" + a + ":" + b + ":" + c) },
        )
        val raw = ViewModelSpecWithArg3<ArityModel, String, String, String>(
            modelClass = ArityModel::class,
            builder = { a, b, c -> ArityModel("raw:" + a + ":" + b + ":" + c) },
        )
        val typed = ViewModelSpecWithArg3<ArityModel, String, String, String>(
            modelType = typeOf<ArityModel>(),
            builder = { a, b, c -> ArityModel("typed:" + a + ":" + b + ":" + c) },
        )
        val fullRaw = ViewModelSpecWithArg3<ArityModel, String, String, String>(
            modelClass = ArityModel::class,
            builder = { a, b, c -> ArityModel("full:" + a + ":" + b + ":" + c) },
            key = { a, b, c -> "full-key:" + a + ":" + b + ":" + c },
            tag = { a, b, c -> "full-tag:" + a + ":" + b + ":" + c },
            aliveForever = { a, b, c -> false },
        )
        assertEquals("raw:1:2:3", resolve(raw("1", "2", "3")))
        assertEquals("typed:1:2:3", resolve(typed("1", "2", "3")))
        assertEquals("full:1:2:3", resolve(fullRaw("1", "2", "3")))
        assertEquals("full-key:1:2:3", fullRaw("1", "2", "3").key())
        assertEquals("full-tag:1:2:3", fullRaw("1", "2", "3").tag())
        assertFalse(fullRaw("1", "2", "3").aliveForever())
        assertEquals("base:1:2:3", resolve(base("1", "2", "3")))
        assertEquals("key:1:2:3", base("1", "2", "3").key())
        assertEquals("tag:1:2:3", base("1", "2", "3").tag())
        assertTrue(base("1", "2", "3").aliveForever())
        base.setProxy(fake)
        assertEquals("fake:1:2:3", resolve(base("1", "2", "3")))
        assertNull(base("1", "2", "3").key())
        assertNull(base("1", "2", "3").tag())
        assertFalse(base("1", "2", "3").aliveForever())
        base.clearProxy()
        assertEquals("base:1:2:3", resolve(base("1", "2", "3")))
        val restore = base.overrideWith(raw)
        assertEquals("raw:1:2:3", resolve(base("1", "2", "3")))
        restore()
        restore()
        base.runWithOverride(fake) {
            yield()
            assertEquals("fake:1:2:3", resolve(base("1", "2", "3")))
            val restoreLocal = base.overrideWith(typed)
            val restoreInner = base.overrideWith(raw)
            restoreLocal()
            assertEquals("raw:1:2:3", resolve(base("1", "2", "3")))
            restoreInner()
            restoreInner()
            assertEquals("fake:1:2:3", resolve(base("1", "2", "3")))
        }
        assertEquals("base:1:2:3", resolve(base("1", "2", "3")))
    }

    @Test fun arity4PreservesArgumentOrderMetadataAndEveryOverrideScope() = runTest {
        val base = viewModelSpecWithArg4<ArityModel, String, String, String, String>(
            builder = { a, b, c, d -> ArityModel("base:" + a + ":" + b + ":" + c + ":" + d) },
            key = { a, b, c, d -> "key:" + a + ":" + b + ":" + c + ":" + d },
            tag = { a, b, c, d -> "tag:" + a + ":" + b + ":" + c + ":" + d },
            aliveForever = { a, b, c, d -> a.isNotEmpty() },
        )
        val fake = viewModelSpecWithArg4<ArityModel, String, String, String, String>(
            builder = { a, b, c, d -> ArityModel("fake:" + a + ":" + b + ":" + c + ":" + d) },
        )
        val raw = ViewModelSpecWithArg4<ArityModel, String, String, String, String>(
            modelClass = ArityModel::class,
            builder = { a, b, c, d -> ArityModel("raw:" + a + ":" + b + ":" + c + ":" + d) },
        )
        val typed = ViewModelSpecWithArg4<ArityModel, String, String, String, String>(
            modelType = typeOf<ArityModel>(),
            builder = { a, b, c, d -> ArityModel("typed:" + a + ":" + b + ":" + c + ":" + d) },
        )
        val fullRaw = ViewModelSpecWithArg4<ArityModel, String, String, String, String>(
            modelClass = ArityModel::class,
            builder = { a, b, c, d -> ArityModel("full:" + a + ":" + b + ":" + c + ":" + d) },
            key = { a, b, c, d -> "full-key:" + a + ":" + b + ":" + c + ":" + d },
            tag = { a, b, c, d -> "full-tag:" + a + ":" + b + ":" + c + ":" + d },
            aliveForever = { a, b, c, d -> false },
        )
        assertEquals("raw:1:2:3:4", resolve(raw("1", "2", "3", "4")))
        assertEquals("typed:1:2:3:4", resolve(typed("1", "2", "3", "4")))
        assertEquals("full:1:2:3:4", resolve(fullRaw("1", "2", "3", "4")))
        assertEquals("full-key:1:2:3:4", fullRaw("1", "2", "3", "4").key())
        assertEquals("full-tag:1:2:3:4", fullRaw("1", "2", "3", "4").tag())
        assertFalse(fullRaw("1", "2", "3", "4").aliveForever())
        assertEquals("base:1:2:3:4", resolve(base("1", "2", "3", "4")))
        assertEquals("key:1:2:3:4", base("1", "2", "3", "4").key())
        assertEquals("tag:1:2:3:4", base("1", "2", "3", "4").tag())
        assertTrue(base("1", "2", "3", "4").aliveForever())
        base.setProxy(fake)
        assertEquals("fake:1:2:3:4", resolve(base("1", "2", "3", "4")))
        assertNull(base("1", "2", "3", "4").key())
        assertNull(base("1", "2", "3", "4").tag())
        assertFalse(base("1", "2", "3", "4").aliveForever())
        base.clearProxy()
        assertEquals("base:1:2:3:4", resolve(base("1", "2", "3", "4")))
        val restore = base.overrideWith(raw)
        assertEquals("raw:1:2:3:4", resolve(base("1", "2", "3", "4")))
        restore()
        restore()
        base.runWithOverride(fake) {
            yield()
            assertEquals("fake:1:2:3:4", resolve(base("1", "2", "3", "4")))
            val restoreLocal = base.overrideWith(typed)
            val restoreInner = base.overrideWith(raw)
            restoreLocal()
            assertEquals("raw:1:2:3:4", resolve(base("1", "2", "3", "4")))
            restoreInner()
            restoreInner()
            assertEquals("fake:1:2:3:4", resolve(base("1", "2", "3", "4")))
        }
        assertEquals("base:1:2:3:4", resolve(base("1", "2", "3", "4")))
    }
}

private class ArityModel(val arguments: String) : ViewModel()

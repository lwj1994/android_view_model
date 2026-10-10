package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.reflect.typeOf

class GenericTypeIdentityTest {
    private val bindings = mutableListOf<ViewModelBinding>()

    @Before
    fun setUp() = ViewModel.reset()

    @After
    fun tearDown() {
        bindings.asReversed().forEach(ViewModelBinding::dispose)
        bindings.clear()
        ViewModel.reset()
    }

    @Test
    fun sameBinding_preservesDifferentGenericStateTypesAndWatchSubscriptions() {
        var updates = 0
        var numberBuilds = 0
        var textBuilds = 0
        val binding = binding { updates += 1 }
        val numberSpec = viewModelSpec {
            numberBuilds += 1
            GenericIdentityState(1)
        }
        val textSpec = viewModelSpec {
            textBuilds += 1
            GenericIdentityState("first")
        }
        val number by binding.watchViewModel(numberSpec)
        val text by binding.watchViewModel(textSpec)

        assertEquals(1, number.state)
        assertEquals("first", text.state)
        assertNotSame(number, text)
        repeat(3) {
            assertSame(number, binding.read(numberSpec))
            assertSame(text, binding.watch(textSpec))
        }
        assertEquals(1, numberBuilds)
        assertEquals(1, textBuilds)

        number.setState(2)
        text.setState("second")
        assertEquals(2, updates)
        assertEquals(2, number.state)
        assertEquals("second", text.state)
    }

    @Test
    fun sameExplicitKey_sharesOnlyMatchingGenericTypesAcrossBindings() {
        val first = binding()
        val second = binding()
        val numberSpec = viewModelSpec(key = "shared") { GenericIdentityState(1) }
        val textSpec = viewModelSpec(key = "shared") { GenericIdentityState("text") }
        val firstNumber by first.readViewModel(numberSpec)
        val firstText by first.readViewModel(textSpec)
        val secondNumber by second.readViewModel(numberSpec)
        val secondText by second.readViewModel(textSpec)
        val number = firstNumber
        val text = firstText

        assertSame(number, secondNumber)
        assertSame(text, secondText)
        assertNotSame(number, text)
        first.dispose()
        assertFalse(number.isDisposed)
        assertFalse(text.isDisposed)
        assertEquals(1, secondNumber.state)
        assertEquals("text", secondText.state)
        second.dispose()
        assertTrue(number.isDisposed)
        assertTrue(text.isDisposed)
    }

    @Test
    fun cachedQueriesAndTagBatches_onlyReturnTheRequestedGenericType() {
        val owner = binding()
        val reader = binding()
        val tag = Any()
        val oldNumberSpec = viewModelSpec(key = "older", tag = tag) { GenericIdentityState(1) }
        val newNumberSpec = viewModelSpec(key = "newer", tag = tag) { GenericIdentityState(2) }
        val oldTextSpec = viewModelSpec(key = "older", tag = tag) { GenericIdentityState("old") }
        val newTextSpec = viewModelSpec(key = "newer", tag = tag) { GenericIdentityState("new") }
        val oldNumber by owner.readViewModel(oldNumberSpec)
        val newNumber by owner.readViewModel(newNumberSpec)
        val oldText by owner.readViewModel(oldTextSpec)
        val newText by owner.readViewModel(newTextSpec)
        assertEquals(1, oldNumber.state)
        assertEquals("old", oldText.state)
        assertEquals(2, newNumber.state)
        assertEquals("new", newText.state)

        assertSame(newNumber, ViewModel.readCached<GenericIdentityState<Int>>())
        assertSame(newText, ViewModel.maybeReadCached<GenericIdentityState<String>>())
        assertSame(oldNumber, ViewModel.readCached<GenericIdentityState<Int>>(key = "older"))
        assertSame(oldText, reader.readCached<GenericIdentityState<String>>(key = "older"))
        assertSame(newNumber, reader.watchCached<GenericIdentityState<Int>>(tag = tag))
        assertSame(newText, reader.maybeWatchCached<GenericIdentityState<String>>(tag = tag))
        assertSame(newNumber, reader.maybeReadCached<GenericIdentityState<Int>>(key = "missing", tag = tag))
        assertSame(newText, ViewModel.readCached<GenericIdentityState<String>>(key = "missing", tag = tag))
        assertEquals(listOf(newNumber, oldNumber), reader.readCachesByTag<GenericIdentityState<Int>>(tag))
        assertEquals(listOf(newText, oldText), reader.watchCachesByTag<GenericIdentityState<String>>(tag))

        assertNull(ViewModel.maybeReadCached<GenericIdentityState<Boolean>>())
        assertNull(reader.maybeReadCached<GenericIdentityState<Boolean>>(tag = tag))
        assertNull(reader.maybeWatchCached<GenericIdentityState<Boolean>>(key = "older"))
        assertTrue(reader.readCachesByTag<GenericIdentityState<Boolean>>(tag).isEmpty())
        assertTrue(reader.watchCachesByTag<GenericIdentityState<Boolean>>(tag).isEmpty())
        assertEquals(
            if (BuildConfig.DEBUG) 2 else 0,
            InstanceManager.debugAmbiguousTagLookupWarningCount,
        )
    }

    @Test
    fun nullableAndNestedArguments_arePartOfDeclaredIdentity() {
        val binding = binding()
        val textSpec = viewModelSpec { GenericIdentityState("same") }
        val nullableTextSpec = viewModelSpec { GenericIdentityState<String?>("same") }
        val numbersSpec = viewModelSpec { GenericIdentityState(listOf(1)) }
        val textsSpec = viewModelSpec { GenericIdentityState(listOf("one")) }
        val text by binding.readViewModel(textSpec)
        val nullableText by binding.readViewModel(nullableTextSpec)
        val numbers by binding.readViewModel(numbersSpec)
        val texts by binding.readViewModel(textsSpec)

        assertNotSame(text, nullableText)
        nullableText.setState(null)
        assertEquals("same", text.state)
        assertNull(nullableText.state)
        assertEquals(1, numbers.state.single())
        assertEquals("one", texts.state.single())
        assertNotSame(numbers, texts)
        assertSame(nullableText, ViewModel.readCached<GenericIdentityState<String?>>())
        assertSame(numbers, ViewModel.readCached<GenericIdentityState<List<Int>>>())
        assertSame(texts, ViewModel.readCached<GenericIdentityState<List<String>>>())
    }

    @Test
    fun argumentSpecs_preserveGenericIdentityForEverySupportedArity() {
        val binding = binding()
        val numberSpecs = listOf(
            viewModelSpecWithArg<GenericIdentityState<Int>, Int>(
                builder = { a -> GenericIdentityState(a) }, key = { "arg-1" },
            )(1),
            viewModelSpecWithArg2<GenericIdentityState<Int>, Int, Int>(
                builder = { a, b -> GenericIdentityState(a + b) }, key = { _, _ -> "arg-2" },
            )(1, 1),
            viewModelSpecWithArg3<GenericIdentityState<Int>, Int, Int, Int>(
                builder = { a, b, c -> GenericIdentityState(a + b + c) }, key = { _, _, _ -> "arg-3" },
            )(1, 1, 1),
            viewModelSpecWithArg4<GenericIdentityState<Int>, Int, Int, Int, Int>(
                builder = { a, b, c, d -> GenericIdentityState(a + b + c + d) },
                key = { _, _, _, _ -> "arg-4" },
            )(1, 1, 1, 1),
        )
        val textSpecs = listOf(
            viewModelSpecWithArg<GenericIdentityState<String>, String>(
                builder = { a -> GenericIdentityState(a) }, key = { "arg-1" },
            )("x"),
            viewModelSpecWithArg2<GenericIdentityState<String>, String, String>(
                builder = { a, b -> GenericIdentityState(a + b) }, key = { _, _ -> "arg-2" },
            )("x", "x"),
            viewModelSpecWithArg3<GenericIdentityState<String>, String, String, String>(
                builder = { a, b, c -> GenericIdentityState(a + b + c) }, key = { _, _, _ -> "arg-3" },
            )("x", "x", "x"),
            viewModelSpecWithArg4<GenericIdentityState<String>, String, String, String, String>(
                builder = { a, b, c, d -> GenericIdentityState(a + b + c + d) },
                key = { _, _, _, _ -> "arg-4" },
            )("x", "x", "x", "x"),
        )

        numberSpecs.zip(textSpecs).forEachIndexed { index, (numberSpec, textSpec) ->
            val number by binding.readViewModel(numberSpec)
            val text by binding.readViewModel(textSpec)
            assertEquals(index + 1, number.state)
            assertEquals("x".repeat(index + 1), text.state)
            assertNotSame(number, text)
        }
    }

    @Test
    fun nestedDifferentGenericArguments_doNotTriggerAFalseConstructionCycle() {
        val binding = binding()
        val childSpec = viewModelSpec { GenericIdentityNode(listOf("child")) }
        val parentSpec = viewModelSpec {
            GenericIdentityNode(listOf(1)) { dependencies ->
                val child by dependencies.readViewModel(childSpec)
                child.value.single()
            }
        }
        val parent by binding.readViewModel(parentSpec)

        assertEquals(listOf(1), parent.value)
        assertEquals("child", parent.initializedValue)
        assertEquals(listOf("child"), ViewModel.readCached<GenericIdentityNode<List<String>>>().value)
    }

    @Test
    fun recursiveConstructionWithTheSameCompleteType_isStillRejected() {
        val binding = binding()
        lateinit var recursiveSpec: ViewModelSpec<GenericIdentityNode<List<Int>>>
        recursiveSpec = viewModelSpec {
            GenericIdentityNode(listOf(1)) { dependencies ->
                val recursive by dependencies.readViewModel(recursiveSpec)
                recursive.value.single()
            }
        }
        val error = assertThrows(ViewModelError::class.java) { binding.read(recursiveSpec) }
        assertTrue(error.message.orEmpty().contains("Circular ViewModel construction"))
        assertNull(ViewModel.maybeReadCached<GenericIdentityNode<List<Int>>>())
    }

    @Test
    fun legacyNonGenericKClassConstructorsAndFactories_shareWithReifiedSpecs() {
        val first = binding()
        val second = binding()
        val spec = viewModelSpec(key = "legacy") { LegacyIdentityModel("original") }
        val legacy = ViewModelSpec(LegacyIdentityModel::class, "legacy", null, false) {
            LegacyIdentityModel("legacy builder must not run")
        }
        val typed = ViewModelSpec(typeOf<LegacyIdentityModel>(), key = "legacy") {
            LegacyIdentityModel("typed builder must not run")
        }
        val custom = object : ViewModelFactory<LegacyIdentityModel> {
            override val modelClass = LegacyIdentityModel::class
            override fun key(): Any = "legacy"
            override fun build(): LegacyIdentityModel = spec.build()
        }
        val argumentSpecs = listOf(
            ViewModelSpecWithArg(
                LegacyIdentityModel::class,
                builder = { a: String -> LegacyIdentityModel(a) }, key = { "legacy" },
            )("a"),
            ViewModelSpecWithArg2(
                LegacyIdentityModel::class,
                builder = { a: String, b: String -> LegacyIdentityModel(a + b) }, key = { _, _ -> "legacy" },
            )("a", "b"),
            ViewModelSpecWithArg3(
                LegacyIdentityModel::class,
                builder = { a: String, b: String, c: String -> LegacyIdentityModel(a + b + c) },
                key = { _, _, _ -> "legacy" },
            )("a", "b", "c"),
            ViewModelSpecWithArg4(
                LegacyIdentityModel::class,
                builder = { a: String, b: String, c: String, d: String -> LegacyIdentityModel(a + b + c + d) },
                key = { _, _, _, _ -> "legacy" },
            )("a", "b", "c", "d"),
        )
        val model by first.readViewModel(spec)
        val viaLegacy by second.readViewModel(legacy)
        val viaTyped by second.readViewModel(typed)
        val viaCustom by second.readViewModel(custom)

        assertEquals("original", model.label)
        assertSame(model, viaLegacy)
        assertSame(model, viaTyped)
        assertSame(model, viaCustom)
        argumentSpecs.forEach { argumentSpec ->
            val viaArguments by second.readViewModel(argumentSpec)
            assertSame(model, viaArguments)
        }
        assertSame(model, ViewModel.readCached<LegacyIdentityModel>(key = "legacy"))
    }

    @Test
    fun rawGenericKClass_isRejectedBeforeBuildAndTypedCustomFactoriesRemainUsable() {
        val binding = binding()
        var rawBuilds = 0
        val error = assertThrows(ViewModelError::class.java) {
            ViewModelSpec(GenericIdentityState::class) {
                rawBuilds += 1
                GenericIdentityState(1)
            }
        }
        assertTrue(error.message.orEmpty().contains("type arguments"))
        val spec = viewModelSpec { GenericIdentityState(2) }
        val raw = object : ViewModelFactory<GenericIdentityState<Int>> {
            override val modelClass = spec.modelClass
            override fun build(): GenericIdentityState<Int> {
                rawBuilds += 1
                return spec.build()
            }
        }
        assertThrows(ViewModelError::class.java) { binding.read(raw) }
        assertEquals(0, rawBuilds)

        val typed = object : TypedViewModelFactory<GenericIdentityState<Int>> {
            override val modelClass = spec.modelClass
            override val modelType = typeOf<GenericIdentityState<Int>>()
            override fun build(): GenericIdentityState<Int> = spec.build()
        }
        val model by binding.readViewModel(typed)
        assertEquals(2, model.state)
        assertSame(model, binding.read(spec))
        assertSame(model, ViewModel.readCached<GenericIdentityState<Int>>())
    }

    @Test
    fun rawInnerClasses_requireKTypeWhileReifiedInnerTypesRemainIndependent() {
        val binding = binding()
        var rawBuilds = 0
        val numberSpec = viewModelSpec { GenericIdentityOuter(1).Middle().Model() }
        val textSpec = viewModelSpec { GenericIdentityOuter("text").Middle().Model() }
        val plainSpec = viewModelSpec { PlainIdentityOuter().Model() }
        val staticSpec = ViewModelSpec(PlainIdentityOuter.StaticModel::class) {
            PlainIdentityOuter.StaticModel()
        }
        val number by binding.readViewModel(numberSpec)
        val text by binding.readViewModel(textSpec)
        val plain by binding.readViewModel(plainSpec)
        val staticModel by binding.readViewModel(staticSpec)

        val genericError = assertThrows(ViewModelError::class.java) {
            ViewModelSpec(numberSpec.modelClass) {
                rawBuilds += 1
                numberSpec.build()
            }
        }
        val plainError = assertThrows(ViewModelError::class.java) {
            ViewModelSpec(plainSpec.modelClass) {
                rawBuilds += 1
                plainSpec.build()
            }
        }
        assertTrue(genericError.message.orEmpty().contains("non-static inner"))
        assertTrue(plainError.message.orEmpty().contains("non-static inner"))
        assertEquals(0, rawBuilds)
        assertEquals(1, number.state)
        assertEquals("text", text.state)
        assertNotSame(number, text)
        assertEquals("plain", plain.state)
        assertEquals("static", staticModel.state)
        assertSame(number, ViewModel.readCached<GenericIdentityOuter<Int>.Middle.Model>())
        assertSame(text, ViewModel.readCached<GenericIdentityOuter<String>.Middle.Model>())
    }

    @Test
    fun recyclingOneGenericType_preservesOthersAndDelegatesResolveNewGenerations() {
        val binding = binding()
        val numberSpec = viewModelSpec(key = "same") { GenericIdentityState(1) }
        val textSpec = viewModelSpec(key = "same") { GenericIdentityState("text") }
        val number by binding.readViewModel(numberSpec)
        val text by binding.readViewModel(textSpec)
        val firstNumber = number
        val firstText = text

        binding.recycle(firstNumber)
        assertTrue(firstNumber.isDisposed)
        assertFalse(firstText.isDisposed)
        assertNotSame(firstNumber, number)
        assertSame(firstText, text)
        assertSame(firstText, ViewModel.readCached<GenericIdentityState<String>>(key = "same"))

        val nextNumber = number
        ViewModel.reset()
        assertTrue(nextNumber.isDisposed)
        assertTrue(firstText.isDisposed)
        assertNotSame(nextNumber, number)
        assertNotSame(firstText, text)
        assertEquals(1, number.state)
        assertEquals("text", text.state)
    }

    private fun binding(onUpdate: (() -> Unit)? = null): ViewModelBinding =
        (if (onUpdate == null) ViewModelBinding() else ViewModelBinding(onUpdate)).also(bindings::add)
}

private class GenericIdentityState<T>(initialState: T) : StateViewModel<T>(initialState)

private class GenericIdentityNode<T>(
    val value: T,
    initialize: ((ViewModelBinding) -> Any?)? = null,
) : ViewModel() {
    val initializedValue: Any? = initialize?.invoke(viewModelBinding)
}

private class LegacyIdentityModel(val label: String) : ViewModel()

private class GenericIdentityOuter<T>(private val value: T) {
    inner class Middle {
        inner class Model : StateViewModel<T>(value)
    }
}

private class PlainIdentityOuter {
    inner class Model : StateViewModel<String>("plain")

    class StaticModel : StateViewModel<String>("static")
}

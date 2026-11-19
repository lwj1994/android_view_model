package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A shared ViewModel read by every cell of a long list, whose dependencies mirror
 * every cell as an owner. Owner propagation must stay linear per owner change,
 * otherwise building or unmounting such a list hangs.
 */
class ManyOwnersTest {
    private val disposed = mutableListOf<String>()
    private val cells = mutableListOf<ChainCell>()

    @Before
    fun setUp() = ViewModel.reset()

    @After
    fun tearDown() {
        cells.forEach { it.dispose() }
        ViewModel.reset()
    }

    private fun chain(depth: Int): ViewModelSpec<ChainNode> {
        var next: ViewModelSpec<ChainNode>? = null
        for (level in depth - 1 downTo 0) {
            val below = next
            next = viewModelSpec(key = "level$level") { ChainNode("level$level", below, disposed) }
        }
        return next!!
    }

    private fun cells(spec: ViewModelSpec<ChainNode>, count: Int) =
        List(count) { ChainCell(spec) }.also(cells::addAll)

    private fun handle(key: String) = InstanceManager.getHandle(
        InstanceType(ChainNode::class), InstanceFactory(arg = InstanceArg(key = key)),
    )

    @Test
    fun thousandsOfOwnersPropagateToDependenciesInLinearTime() {
        val owners = 1500
        val depth = 4
        val cells = cells(chain(depth), owners)

        val bindStart = System.nanoTime()
        cells.forEach { it.vm }
        val bound = System.nanoTime() - bindStart

        for (level in 0 until depth) {
            // Deeper levels are also owned by the parent's dependency binding.
            val handle = handle("level$level")
            assertEquals("level$level", owners + if (level == 0) 0 else 1, handle.bindingIds.size)
            assertEquals(owners, (handle.value as ChainNode).refHandler.externalOwners.size)
        }

        // Unmount in the order a list unmounts its cells.
        val releaseStart = System.nanoTime()
        cells.forEach { it.dispose() }
        val released = System.nanoTime() - releaseStart

        assertEquals(listOf("level0", "level1", "level2", "level3"), disposed)
        // Quadratic owner sync took tens of seconds here; linear sync takes milliseconds.
        val totalMillis = (bound + released) / 1_000_000
        assertTrue("bind ${bound / 1_000_000}ms, release ${released / 1_000_000}ms", totalMillis < 5_000)
    }

    @Test
    fun releasingOwnersFromTheMiddleKeepsTheRemainingPropagation() {
        val owners = 200
        val cells = cells(chain(3), owners)
        cells.forEach { it.vm }

        for (i in 0 until owners step 2) cells[i].dispose()
        val kept = (1 until owners step 2).map { cells[it].id }
        val cellIds = cells.map { it.id }.toSet()
        for (level in 0 until 3) {
            val handle = handle("level$level")
            assertEquals("level$level", kept, handle.bindingIds.filter(cellIds::contains))
            assertEquals(kept, (handle.value as ChainNode).refHandler.externalOwners.map { it.id })
        }
        assertTrue(disposed.isEmpty())

        for (i in 1 until owners step 2) cells[i].dispose()
        assertEquals(listOf("level0", "level1", "level2"), disposed)
    }
}

private class ChainCell(spec: ViewModelSpec<ChainNode>) : ViewModelBinding() {
    val vm by readViewModel(spec)
}

private class ChainNode(
    name: String,
    next: ViewModelSpec<ChainNode>?,
    disposed: MutableList<String>,
) : ViewModel() {
    init {
        addDispose { disposed += name }
        if (next != null) {
            val child by viewModelBinding.readViewModel(next)
            child
        }
    }
}

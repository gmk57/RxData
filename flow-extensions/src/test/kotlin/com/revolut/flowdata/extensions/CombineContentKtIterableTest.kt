package com.revolut.flowdata.extensions

import com.revolut.data.model.Data
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class CombineContentKtIterableTest {

    private lateinit var flows: List<MutableSharedFlow<Data<String>>>
    private lateinit var testCombined: Flow<String>


    @BeforeEach
    fun setup() {
        flows = List(5) { MutableSharedFlow() }

        testCombined = combineContent(flows)
            .mapData { dataList -> dataList.joinToString(separator = "") }
            .extractContent()
    }

    @Test
    fun `Combined value when all flows have data`() = runFlowTest(testCombined) {
        flows.forEachIndexed { index, flow -> flow.emit(Data("$index")) }

        Assertions.assertEquals("01234", expectMostRecentItem())
        expectNoEvents()
    }

    @Test
    fun `No combined values while all flows are loading`() = runFlowTest(testCombined) {
        flows.forEach { flow -> flow.emit(Data(loading = true)) }

        expectNoEvents()
    }

    @Test
    fun `No combined values when one flow has value`() = runFlowTest(testCombined) {
        flows.first().emit(Data("0"))
        flows.drop(1).forEach { flow -> flow.emit(Data()) }

        expectNoEvents()
    }

    @Test
    fun `Latest value is combined when one of the flows emits`() = runFlowTest(testCombined) {
        with(flows.last()) {
            emit(Data("0"))
            emit(Data("9"))
        }
        expectNoEvents()

        flows.dropLast(1).forEachIndexed { index, flow -> flow.emit(Data("$index")) }

        Assertions.assertEquals("01239", awaitItem())
        expectNoEvents()
    }

    @Test
    fun `Combined value should be the same for pair and list parameters`() = runTest {
        val combinedListFlow = combineContent(
            listOf(
                flowOf(Data("a")),
                flowOf(Data("b")),
            )
        ).mapData { dataList -> dataList.joinToString(separator = "") }.extractContent()

        val combinedPairFlow = combineContent(
            flowOf(Data("a")),
            flowOf(Data("b")),
        ).mapData { (a, b) -> a + b }.extractContent()

        val combinedListData = combinedListFlow.first()
        val combinedPairData = combinedPairFlow.first()

        Assertions.assertEquals(combinedListData, combinedPairData)
    }

    @Test
    fun `Combined value should be the same for triple and list parameters`() = runTest {
        val combinedListFlow = combineContent(
            listOf(
                flowOf(Data("a")),
                flowOf(Data("b")),
                flowOf(Data("c")),
            )
        ).mapData { dataList -> dataList.joinToString(separator = "") }.extractContent()

        val combinedTripleFlow = combineContent(
            flowOf(Data("a")),
            flowOf(Data("b")),
            flowOf(Data("c")),
        ).mapData { (a, b, c) -> a + b + c }.extractContent()

        val combinedListData = combinedListFlow.first()
        val combinedTripleData = combinedTripleFlow.first()

        Assertions.assertEquals(combinedListData, combinedTripleData)
    }

    @Test
    fun `Combined value should be the same for vararg and list parameters`() = runFlowTest(testCombined) {
        flows.forEachIndexed { index, flow -> flow.emit(Data("$index")) }
        val combinedFromList = expectMostRecentItem()

        val varargCombinedFlow = combineContent(
            flowOf(Data("0")),
            flowOf(Data("1")),
            flowOf(Data("2")),
            flowOf(Data("3")),
            flowOf(Data("4")),
        )
            .mapData { dataList -> dataList.joinToString(separator = "") }
            .extractContent()

        val combinedFromVararg = varargCombinedFlow.first()

        Assertions.assertEquals(combinedFromList, combinedFromVararg)
    }
}
package com.revolut.rxdata.dod

import com.revolut.data.model.Data
import com.revolut.rxdata.core.extensions.extractContent
import com.revolut.rxdata.core.extensions.takeUntilLoaded
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.only
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


/*
 * Copyright (C) 2019 Revolut
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

class DataObservableDelegateTest : BaseDataObservableDelegateTest() {

    private val domain: Domain = "domain_model"
    private val domain2: Domain = "domain_model_2"

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)
        testObserver.assertValueCount(2)

        testObserver.assertValueAt(1, Data(domain, error = null, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(params))
        verify(toMemory, only()).invoke(eq(params), eq(domain))
        verify(fromStorage, only()).invoke(eq(params))
        verify(toStorage, only()).invoke(eq(params), eq(domain))
    }

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(3)

        testObserver.assertValueAt(1, Data(cachedDomain, error = null, loading = true))
        testObserver.assertValueAt(2, Data(domain, error = null, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(params))
        verify(toMemory).invoke(eq(params), eq(cachedDomain))
        verify(toMemory).invoke(eq(params), eq(domain))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(params))
        verify(toStorage, only()).invoke(eq(params), eq(domain))
    }

    @Test
    fun `fromNetwork should be invoked on IO`() {
        memCache[params] = cachedDomain

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable {
            domain
        })

        dataObservableDelegate.observe(params = params, forceReload = true).test()

        verifyNoMoreInteractions(fromNetwork)

        ioScheduler.triggerActions()

        verify(fromNetwork).invoke(any())
    }

    @Test
    fun `WHEN unsubscribed from network reload THEN data is still saved to storage and memory`() {
        val delayedNetwork = Single.fromCallable { domain }
            .delay(1, TimeUnit.SECONDS, computationScheduler)

        whenever(fromNetwork.invoke(eq(params))).thenReturn(delayedNetwork)
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false)
                .extractContent()
                .firstOrError() // gets storage and unsubscribes before network emits
                .test()

        ioScheduler.triggerActions()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, cachedDomain)
        testObserver.assertComplete()

        //storage domain moved to memory
        verify(toMemory).invoke(eq(params), eq(cachedDomain))
        //network domain not moved to memory yet
        verify(toMemory, never()).invoke(eq(params), eq(domain))

        computationScheduler.advanceTimeBy(
            1,
            TimeUnit.SECONDS
        ) //network finishes after observer unsubscribed

        //network domain moved to memory
        verify(toMemory, atLeastOnce()).invoke(eq(params), eq(domain))
        //network domain moved to storage
        verify(toStorage, atLeastOnce()).invoke(eq(params), eq(domain))
    }


    @Test
    fun `WHEN unsubscribed from dod AND dod is globally cleared THEN data is not saved`() {
        DodGlobal.networkTimeoutSeconds = 60L

        val delayedNetwork = Single.fromCallable { domain }
            .delay(10, TimeUnit.SECONDS, computationScheduler)

        whenever(fromNetwork.invoke(eq(params))).thenReturn(delayedNetwork)
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false)
                .extractContent()
                .firstOrError() // gets storage and un-subscribes before network emits
                .test()
        ioScheduler.triggerActions()

        //network is not finished yet and we dispose DOD globally
        computationScheduler.advanceTimeBy(5, TimeUnit.SECONDS)
        DodGlobal.clearPendingNetwork()
        computationScheduler.advanceTimeBy(5, TimeUnit.SECONDS)

        //network result is not stored
        verify(toMemory, never()).invoke(eq(params), eq(domain))
        verify(toStorage, never()).invoke(eq(params), eq(domain))
    }

    @Test
    fun `WHEN error is thrown in toStorage THEN it's emitted as Data error`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(toStorage.invoke(any(), any())).thenThrow(IllegalStateException())

        val testObserver = dataObservableDelegate.observe(params = params).test()

        ioScheduler.triggerActions()

        testObserver.assertValueAt(0, Data(content = null, error = null, loading = true))
        testObserver.assertValueAt(1, Data(content = domain, error = IllegalStateException(), loading = false))
    }

    @Test
    fun `WHEN error is thrown in toMemory THEN it's emitted as Data error`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(toMemory.invoke(any(), any())).thenThrow(IllegalStateException())

        val testObserver = dataObservableDelegate.observe(params = params).test()

        ioScheduler.triggerActions()

        testObserver.assertValueAt(0, Data(content = null, error = null, loading = true))
        testObserver.assertValueAt(1, Data(content = null, error = IllegalStateException(), loading = false))
    }

    @Test
    fun `WHEN unsubscribed from network and data NOT arrives in 60 seconds THEN data not saved`() {
        DodGlobal.networkTimeoutSeconds = 60L

        val delayedNetwork = Single.fromCallable { domain }
            .delay(2, TimeUnit.MINUTES, ioScheduler)

        whenever(fromNetwork.invoke(eq(params))).thenReturn(delayedNetwork)
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false)
                .extractContent()
                .firstOrError() // gets storage and un-subscribes before network emits
                .test()

        ioScheduler.triggerActions()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, cachedDomain)
        testObserver.assertComplete()

        //storage domain moved to memory
        verify(toMemory).invoke(eq(params), eq(cachedDomain))
        //network domain not moved to memory yet
        verify(toMemory, never()).invoke(eq(params), eq(domain))

        //network reload terminates after 60 seconds
        ioScheduler.advanceTimeBy(60, TimeUnit.SECONDS)

        //network domain not moved to memory
        verify(toMemory, never()).invoke(eq(params), eq(domain))
        //network domain not moved to storage
        verify(toStorage, never()).invoke(eq(params), eq(domain))


        //re-subscribption will re-trigger network after 60 seconds timeout
        val network2 = Single.fromCallable { domain2 }

        whenever(fromNetwork.invoke(eq(params))).thenReturn(network2)
        dataObservableDelegate.observe(params = params, forceReload = false)
            .test()

        ioScheduler.triggerActions()

        //network domain2 moved to memory
        verify(toMemory, atLeastOnce()).invoke(eq(params), eq(domain2))
        //network domain2 moved to storage
        verify(toStorage, atLeastOnce()).invoke(eq(params), eq(domain2))
    }

    @Test
    fun `WHEN unsubscribed from network stream AND error occurs THEN next observe will reload`() {
        val delayedNetwork = Single.fromCallable<Domain> { throw  backendException }
            .delay(1, TimeUnit.SECONDS, ioScheduler)

        whenever(fromNetwork.invoke(eq(params))).thenReturn(delayedNetwork)
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false)
                .extractContent()
                .firstOrError() // gets storage and unsubscribes before network emits
                .test()

        ioScheduler.triggerActions()

        verify(toMemory).invoke(eq(params), eq(cachedDomain)) //storage domain moved to memory

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, cachedDomain)
        testObserver.assertComplete()

        ioScheduler.advanceTimeBy(
            1,
            TimeUnit.SECONDS
        ) //network finishes with error after observer unsubscribed

        verifyNoMoreInteractions(toMemory)
        verifyNoMoreInteractions(toStorage)

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        //observing with forceReload = false expecting to trigger network call again
        dataObservableDelegate.observe(params = params, forceReload = false)
            .extractContent()
            .firstOrError() // gets storage and unsubscribes before network emits
            .test()

        ioScheduler.triggerActions()

        //network domain moved to memory
        verify(toMemory, atLeastOnce()).invoke(eq(params), eq(domain))
        //network domain moved to storage
        verify(toStorage, atLeastOnce()).invoke(eq(params), eq(domain))

    }

    @Test
    fun `WHEN previous fromNetwork failed THEN observe forceReload=false will reload`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        storage[params] = cachedDomain

        dataObservableDelegate.observe(params = params, forceReload = false)
            .extractContent()
            .test().apply { ioScheduler.triggerActions() }
            .assertError(backendException)

        assertEquals(cachedDomain, memCache[params])
        verify(fromNetwork, times(1)).invoke(eq(params))

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        dataObservableDelegate.observe(params = params, forceReload = false)
            .extractContent()
            .test().apply { ioScheduler.triggerActions() }
            .assertValues(cachedDomain, domain)

        verify(fromNetwork, times(2)).invoke(eq(params))
    }

    @Test
    fun `WHEN previous fromNetwork failed with NoSuchElementException THEN observe forceReload=false will not reload`() {
        class CustomException : NoSuchElementException()

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw CustomException() })
        storage[params] = cachedDomain

        dataObservableDelegate.observe(params = params, forceReload = false)
            .extractContent(consumeErrors = { error, _ -> error.takeUnless { it is CustomException } })
            .test().apply { ioScheduler.triggerActions() }
            .assertValues(cachedDomain)

        assertEquals(cachedDomain, memCache[params])
        verify(fromNetwork, times(1)).invoke(eq(params))

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        dataObservableDelegate.observe(params = params, forceReload = false)
            .extractContent()
            .test().apply { ioScheduler.triggerActions() }
            .assertValues(cachedDomain)

        verifyNoMoreInteractions(fromNetwork)
    }

    @Test
    fun `FORCE observing data when memory cache IS NOT EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        memCache[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = cachedDomain, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(1, Data(content = domain, error = null, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(params))
        verify(toMemory, only()).invoke(eq(params), eq(domain))
        verifyNoMoreInteractions(fromStorage)
        verify(toStorage, only()).invoke(eq(params), eq(domain))
    }

    @Test
    fun `WHEN fromNetwork is retried AND notifyFromMemory called THEN subscriber receives the value`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.never())
        memCache[params] = cachedDomain


        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = cachedDomain, error = null, loading = true))

        val err = IllegalStateException("From Network is Retried Outside")
        dataObservableDelegate.notifyFromMemory(error = err, loading = true) { it == params }

        testObserver.assertValueAt(1, Data(content = cachedDomain, error = err, loading = true))

    }

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(1, Data(null, error = backendException, loading = false))


        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, times(2)).invoke(eq(params))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(params))
        verifyNoMoreInteractions(toStorage)
    }

    @Test
    fun `FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()
        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(3)

        testObserver.assertValueAt(1, Data(cachedDomain, error = null, loading = true))
        testObserver.assertValueAt(2, Data(cachedDomain, error = backendException, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, times(2)).invoke(eq(params))
        verify(toMemory, only()).invoke(eq(params), eq(cachedDomain))
        verify(fromStorage, only()).invoke(eq(params))
        verifyNoMoreInteractions(toStorage)
    }

    @Test
    fun `FORCE observing data when memory cache IS NOT EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        memCache[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = cachedDomain, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(
            1,
            Data(content = cachedDomain, error = backendException, loading = false)
        )

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, times(2)).invoke(eq(params))
        verifyNoMoreInteractions(toMemory)
        verifyNoMoreInteractions(fromStorage)
        verifyNoMoreInteractions(toStorage)
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()
        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(1, Data(domain, error = null, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(params))
        verify(toMemory, only()).invoke(eq(params), eq(domain))
        verify(fromStorage, only()).invoke(eq(params))
        verify(toStorage, only()).invoke(eq(params), eq(domain))
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()
        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(3)
        testObserver.assertValueAt(1, Data(cachedDomain, error = null, loading = true))
        testObserver.assertValueAt(2, Data(domain, error = null, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, only()).invoke(eq(params))
        verify(toMemory).invoke(eq(params), eq(cachedDomain))
        verify(toMemory).invoke(eq(params), eq(domain))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(params))
        verify(toStorage).invoke(eq(params), eq(domain))
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS NOT EMPTY`() {
        memCache[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = cachedDomain, error = null, loading = false))

        verifyNoMoreInteractions(fromNetwork)
        verify(fromMemory, only()).invoke(eq(params))
        verifyNoMoreInteractions(toMemory)
        verifyNoMoreInteractions(fromStorage)
        verifyNoMoreInteractions(toStorage)
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(1, Data(null, error = backendException, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, times(2)).invoke(eq(params))
        verifyNoMoreInteractions(toMemory)
        verify(fromStorage, only()).invoke(eq(params))
        verifyNoMoreInteractions(toStorage)
    }

    @Test
    fun `NOT FORCE observing data when memory cache IS EMPTY and storage IS NOT EMPTY and server returns ERROR`() {
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { throw backendException })
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(null, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver.assertValueCount(3)
        testObserver.assertValueAt(1, Data(cachedDomain, error = null, loading = true))

        testObserver.assertValueAt(2, Data(cachedDomain, error = backendException, loading = false))

        verify(fromNetwork, only()).invoke(eq(params))
        verify(fromMemory, times(2)).invoke(eq(params))
        verify(toMemory, only()).invoke(eq(params), eq(cachedDomain))
        verify(fromStorage, only()).invoke(eq(params))
        verifyNoMoreInteractions(toStorage)
    }

    @Test
    fun `observing data for MORE THAN ONE observer`() {
        var firstTime = true

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable {
            if (firstTime) {
                firstTime = false
                domain
            } else {
                throw backendException
            }
        })
        memCache[params] = cachedDomain

        val testObserver1 =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        testObserver1.assertValueCount(1)
        testObserver1.assertValueAt(0, Data(content = cachedDomain, error = null, loading = false))

        // refresh with result
        val testObserver2 =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver1.assertValueCount(2)
        testObserver1.assertValueAt(0, Data(content = cachedDomain, error = null, loading = false))

        testObserver2.assertValueCount(1)
        testObserver2.assertValueAt(0, Data(content = cachedDomain, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver1.assertValueCount(3)
        testObserver1.assertValueAt(2, Data(content = domain, error = null, loading = false))

        testObserver2.assertValueCount(2)
        testObserver2.assertValueAt(1, Data(content = domain, error = null, loading = false))

        verify(toMemory).invoke(eq(params), eq(domain))

        //refresh with error
        val testObserver3 =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver1.assertValueCount(4)
        testObserver1.assertValueAt(3, Data(content = domain, error = null, loading = true))

        testObserver2.assertValueCount(3)
        testObserver2.assertValueAt(2, Data(content = domain, error = null, loading = true))

        testObserver3.assertValueCount(1)
        testObserver3.assertValueAt(0, Data(content = domain, error = null, loading = true))

        ioScheduler.advanceTimeBy(10, TimeUnit.MILLISECONDS)

        testObserver1.assertValueCount(5)
        testObserver1.assertValueAt(
            4,
            Data(content = domain, error = backendException, loading = false)
        )

        testObserver2.assertValueCount(4)
        testObserver2.assertValueAt(
            3,
            Data(content = domain, error = backendException, loading = false)
        )

        testObserver3.assertValueCount(2)
        testObserver3.assertValueAt(
            1,
            Data(content = domain, error = backendException, loading = false)
        )
    }

    @Test
    fun `re-subscribing to constructed stream re-fetches memory cache`() {
        val observable = dataObservableDelegate.observe(params, forceReload = true)

        observable.test().awaitCount(1).dispose()
        verify(fromMemory, times(1)).invoke(eq(params))

        observable.test().awaitCount(1).dispose()

        verify(fromMemory, times(2)).invoke(eq(params))
    }

    @Test
    fun `reload completable notifies subscribers and updates storage and memory`() {
        // given
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        ioScheduler.triggerActions()

        testObserver.assertValues(
            Data(null, null, true),
            Data(domain, null, false)
        )

        val updatedDomain: Domain = "updated_domain"
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { updatedDomain })

        // when
        val reload = dataObservableDelegate.reload(params = params).test()

        // then
        reload.assertComplete() // complete non-blocking before network executes
        ioScheduler.triggerActions() // network executes

        testObserver.assertValueCount(4)
        testObserver.assertValueAt(2, Data(domain, null, true))
        testObserver.assertValueAt(3, Data(updatedDomain, null, false))

        verify(toMemory).invoke(params, updatedDomain)
        verify(toStorage).invoke(params, updatedDomain)
    }

    @Test
    fun `reload await completes only after network request is completed`() {
        // given
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        // when
        val reload = dataObservableDelegate.reload(params = params, await = true).test()

        // then
        reload.assertNotComplete()  // not complete before network executes
        ioScheduler.triggerActions()
        reload.assertComplete() // not complete after network executed
    }

    @Test
    fun `reload await emits error if network fails`() {
        // given
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        val error = IllegalStateException()
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.error(error))

        // when
        val reload = dataObservableDelegate.reload(params = params, await = true).test()

        // then
        reload.assertNotTerminated()
        ioScheduler.triggerActions() // network executes
        reload.assertError(error)
    }

    @Test
    fun `WHEN storage returns error THEN network data is emitted later`() {
        val error = IllegalStateException()
        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })
        whenever(fromStorage.invoke(any())).thenThrow(error)

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = false).test()

        ioScheduler.triggerActions()

        testObserver.assertValueCount(3)
        testObserver.assertValueAt(0, Data(content = null, error = null, loading = true))
        testObserver.assertValueAt(1, Data(content = null, error = error, loading = true))
        testObserver.assertValueAt(2, Data(content = domain, error = null, loading = false))
    }

    @Test
    fun `WHEN network request is over THEN disposable is removed from container`() {
        val disposableContainer = CompositeDisposable()
        val dataObservableDelegate = DataObservableDelegate(
            fromNetwork = fromNetworkScoped,
            fromMemory = fromMemory,
            toMemory = toMemory,
            fromStorage = fromStorage,
            toStorage = toStorage,
            networkSubscriptionsContainer = disposableContainer
        )

        whenever(fromNetwork.invoke(eq(params))).thenReturn(Single.fromCallable { domain })

        dataObservableDelegate.observe(params = params, forceReload = true)
            .takeUntilLoaded()
            .test()
            .apply { ioScheduler.triggerActions() }
            .assertComplete()

        assertEquals(0, disposableContainer.size())
    }

    @Test
    fun `WHEN fromNetwork failing with delay AND notifyFromMemory called in between THEN subscriber receives latest value`() {
        val networkSubject = BehaviorSubject.create<Domain>()
        whenever(fromNetwork.invoke(eq(params))).thenReturn(networkSubject.firstOrError())
        memCache[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = cachedDomain, error = null, loading = true))

        memCache[params] = domain2
        dataObservableDelegate.notifyFromMemory { it == params }

        testObserver.assertValueAt(1, Data(content = domain2))

        networkSubject.onError(RuntimeException())
        ioScheduler.triggerActions()

        testObserver.assertValueAt(2, Data(content = domain2, error = RuntimeException()))
    }

    @Test
    fun `WHEN fromNetwork failing with delay AND updateAll called in between THEN subscriber receives latest value`() {
        val networkSubject = BehaviorSubject.create<Domain>()
        whenever(fromNetwork.invoke(eq(params))).thenReturn(networkSubject.firstOrError())
        memCache[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = cachedDomain, error = null, loading = true))

        dataObservableDelegate.updateAll(params, domain2)

        testObserver.assertValueAt(1, Data(content = domain2))

        networkSubject.onError(RuntimeException())
        ioScheduler.triggerActions()

        testObserver.assertValueAt(2, Data(content = domain2, error = RuntimeException()))
    }

    @Test
    fun `WHEN fromNetwork failing with delay AND updateMemory called in between THEN subscriber receives latest value`() {
        val networkSubject = BehaviorSubject.create<Domain>()
        whenever(fromNetwork.invoke(eq(params))).thenReturn(networkSubject.firstOrError())
        memCache[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = cachedDomain, error = null, loading = true))

        dataObservableDelegate.updateMemory(params, domain2)

        testObserver.assertValueAt(1, Data(content = domain2))

        networkSubject.onError(RuntimeException())
        ioScheduler.triggerActions()

        testObserver.assertValueAt(2, Data(content = domain2, error = RuntimeException()))
    }

    @Test
    fun `WHEN fromMemory returns null, fromStorage has value AND fromNetwork fails THEN subscriber receives storage value`() {
        val networkSubject = BehaviorSubject.create<Domain>()
        whenever(fromNetwork.invoke(eq(params))).thenReturn(networkSubject.firstOrError())
        whenever(fromMemory.invoke(any())).thenReturn(null)
        storage[params] = cachedDomain

        val testObserver =
            dataObservableDelegate.observe(params = params, forceReload = true).test()

        testObserver.assertValueCount(1)
        testObserver.assertValueAt(0, Data(content = null, error = null, loading = true))
        ioScheduler.triggerActions()

        testObserver.assertValueCount(2)
        testObserver.assertValueAt(1, Data(content = cachedDomain, error = null, loading = true))

        networkSubject.onError(RuntimeException())
        ioScheduler.triggerActions()

        testObserver.assertValueCount(3)
        testObserver.assertValueAt(2, Data(content = cachedDomain, error = RuntimeException()))
    }
}

package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test

class ApiConnectivityTest {
    @Test
    fun expected_timeoutsAndTransport() {
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(SocketTimeoutException("t"))).isTrue()
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(UnknownHostException("h"))).isTrue()
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(ConnectException("c"))).isTrue()
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(IOException("Unable to read data"))).isTrue()
        // withTimeoutOrNull wraps as SocketTimeout / InterruptedIO in Retrofit paths; TimeoutCancellationException ctor is internal.
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(java.io.InterruptedIOException("timeout"))).isTrue()
    }

    @Test
    fun notExpected_bugsAndPathGone() {
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(null)).isFalse()
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(IllegalStateException("bug"))).isFalse()
        assertThat(ApiConnectivity.isExpectedConnectivityFailure(FileNotFoundException("missing"))).isFalse()
    }
}

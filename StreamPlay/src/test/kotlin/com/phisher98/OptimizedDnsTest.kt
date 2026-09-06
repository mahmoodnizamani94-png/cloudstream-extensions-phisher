package com.phisher98

import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class OptimizedDnsTest {

    private val ipv4A = InetAddress.getByName("192.0.2.1")
    private val ipv4B = InetAddress.getByName("198.51.100.1")
    private val ipv6A = InetAddress.getByName("2001:db8::1")
    private val ipv6B = InetAddress.getByName("2001:db8::2")
    private val loopbackV4 = InetAddress.getByName("127.0.0.1")
    private val anyLocalV4 = InetAddress.getByName("0.0.0.0")

    @Before
    fun setUp() {
        OptimizedDns.clearCacheForTesting()
    }

    @After
    fun tearDown() {
        OptimizedDns.clearCacheForTesting()
    }

    @Test
    fun testNumericIpLiteralBypassesResolverAndCache() {
        val delegateCount = AtomicInteger(0)
        val mockDelegate = Dns {
            delegateCount.incrementAndGet()
            listOf(ipv4A)
        }

        val dns = OptimizedDns(delegate = mockDelegate)

        // IPv4 numeric literals
        val resV4 = dns.lookup("1.1.1.1")
        assertEquals(1, resV4.size)
        assertEquals("1.1.1.1", resV4[0].hostAddress)

        val resLocal = dns.lookup("127.0.0.1")
        assertEquals(1, resLocal.size)
        assertEquals("127.0.0.1", resLocal[0].hostAddress)

        // IPv6 numeric literal
        val resV6 = dns.lookup("2606:4700:4700::1111")
        assertEquals(1, resV6.size)

        // Zero delegate calls and zero cache entries because numeric IPs fast-path
        assertEquals(0, delegateCount.get())
        assertEquals(0, dns.cacheSize)
    }

    @Test
    fun testCacheHitAvoidsDelegateLookup() {
        val delegateCount = AtomicInteger(0)
        val mockDelegate = Dns { hostname ->
            delegateCount.incrementAndGet()
            listOf(ipv4A, ipv4B)
        }

        val dns = OptimizedDns(delegate = mockDelegate)

        // First lookup: misses cache, calls delegate
        val first = dns.lookup("api.themoviedb.org")
        assertEquals(1, delegateCount.get())
        assertEquals(listOf(ipv4A, ipv4B), first)
        assertEquals(1, dns.cacheSize)

        // Second lookup: hits cache, does NOT call delegate
        val second = dns.lookup("api.themoviedb.org")
        assertEquals(1, delegateCount.get())
        assertEquals(listOf(ipv4A, ipv4B), second)

        // Case insensitivity check
        val third = dns.lookup("API.TheMovieDB.org")
        assertEquals(1, delegateCount.get())
        assertEquals(listOf(ipv4A, ipv4B), third)
    }

    @Test
    fun testCacheTtlExpiration() {
        val delegateCount = AtomicInteger(0)
        val mockDelegate = Dns {
            delegateCount.incrementAndGet()
            listOf(ipv4A)
        }

        // 60ms TTL
        val dns = OptimizedDns(delegate = mockDelegate, cacheTtlMillis = 60L)

        // 1st lookup
        val first = dns.lookup("expiring.domain.com")
        assertEquals(1, delegateCount.get())
        assertEquals(listOf(ipv4A), first)

        // Immediate 2nd lookup within TTL
        val second = dns.lookup("expiring.domain.com")
        assertEquals(1, delegateCount.get())
        assertEquals(listOf(ipv4A), second)

        // Sleep to exceed 60ms TTL
        Thread.sleep(80L)

        // 3rd lookup after expiry: triggers fresh delegate call
        val third = dns.lookup("expiring.domain.com")
        assertEquals(2, delegateCount.get())
        assertEquals(listOf(ipv4A), third)
    }

    @Test
    fun testHappyEyeballsIpv4Preference() {
        // Delegate returns IPv6 first, then IPv4 interleaved: [ipv6A, ipv4A, ipv6B, ipv4B]
        val mockDelegate = Dns {
            listOf(ipv6A, ipv4A, ipv6B, ipv4B)
        }

        val dns = OptimizedDns(delegate = mockDelegate)
        val resolved = dns.lookup("dualstack.example.org")

        // Must sort IPv4 strictly before IPv6: [ipv4A, ipv4B, ipv6A, ipv6B]
        assertEquals(4, resolved.size)
        assertTrue("First address must be IPv4", resolved[0] is Inet4Address)
        assertTrue("Second address must be IPv4", resolved[1] is Inet4Address)
        assertTrue("Third address must be IPv6", resolved[2] is Inet6Address)
        assertTrue("Fourth address must be IPv6", resolved[3] is Inet6Address)
        assertEquals(listOf(ipv4A, ipv4B, ipv6A, ipv6B), resolved)
    }

    @Test
    fun testPreferIpv4DirectMethod() {
        val dns = OptimizedDns()

        // Empty list
        assertEquals(emptyList<InetAddress>(), dns.preferIpv4(emptyList()))

        // Single address
        assertEquals(listOf(ipv4A), dns.preferIpv4(listOf(ipv4A)))
        assertEquals(listOf(ipv6A), dns.preferIpv4(listOf(ipv6A)))

        // Only IPv4
        assertEquals(listOf(ipv4A, ipv4B), dns.preferIpv4(listOf(ipv4A, ipv4B)))

        // Only IPv6
        assertEquals(listOf(ipv6A, ipv6B), dns.preferIpv4(listOf(ipv6A, ipv6B)))

        // Mixed: IPv6 first -> becomes IPv4 first
        val mixed = listOf(ipv6A, ipv4A, ipv6B, ipv4B)
        val sorted = dns.preferIpv4(mixed)
        assertEquals(listOf(ipv4A, ipv4B, ipv6A, ipv6B), sorted)
    }

    @Test
    fun testBogusAndLoopbackIpFilteringForRemoteHosts() {
        // Delegate returns poisoned addresses (0.0.0.0 and 127.0.0.1) alongside legitimate IP
        val mockDelegate = Dns {
            listOf(anyLocalV4, loopbackV4, ipv4A)
        }

        val dns = OptimizedDns(delegate = mockDelegate)

        // For a remote host, 0.0.0.0 and 127.0.0.1 MUST be stripped
        val resolved = dns.lookup("pirate-proxy.cdn.com")
        assertEquals(1, resolved.size)
        assertEquals(ipv4A, resolved[0])

        // For localhost, loopback addresses MUST be preserved
        val localResolved = dns.filterValidAddresses("localhost", listOf(loopbackV4))
        assertEquals(listOf(loopbackV4), localResolved)
    }

    @Test
    fun testSystemDnsFailureTriggersDoHFallback() {
        // System delegate fails with UnknownHostException
        val mockDelegate = Dns {
            throw UnknownHostException("ISP DNS Poisoned or NXDOMAIN")
        }

        val dohCallCount = AtomicInteger(0)
        val mockDoh: (String) -> List<InetAddress> = { host ->
            dohCallCount.incrementAndGet()
            listOf(ipv4B)
        }

        val dns = OptimizedDns(delegate = mockDelegate, dohResolver = mockDoh)

        val resolved = dns.lookup("blocked-upstream.stream.io")
        assertEquals(1, dohCallCount.get())
        assertEquals(listOf(ipv4B), resolved)

        // Result from DoH should also be cached
        assertNotNull(dns.getCached("blocked-upstream.stream.io"))
    }

    @Test
    fun testPreferDohBypassesDelegate() {
        val delegateCount = AtomicInteger(0)
        val mockDelegate = Dns {
            delegateCount.incrementAndGet()
            listOf(ipv4A)
        }

        val dohCallCount = AtomicInteger(0)
        val mockDoh: (String) -> List<InetAddress> = {
            dohCallCount.incrementAndGet()
            listOf(ipv4B)
        }

        val dns = OptimizedDns(
            delegate = mockDelegate,
            dohResolver = mockDoh,
            preferDoh = true
        )

        val resolved = dns.lookup("doh-first.org")
        assertEquals(1, dohCallCount.get())
        assertEquals(0, delegateCount.get())
        assertEquals(listOf(ipv4B), resolved)
    }

    @Test
    fun testBothSystemAndDoHFailureThrowsUnknownHostException() {
        val mockDelegate = Dns {
            throw UnknownHostException("System DNS down")
        }

        val mockDoh: (String) -> List<InetAddress> = {
            throw UnknownHostException("DoH resolver down")
        }

        val dns = OptimizedDns(delegate = mockDelegate, dohResolver = mockDoh)

        try {
            dns.lookup("unresolvable.invalid")
            fail("Expected UnknownHostException when all resolvers fail")
        } catch (e: UnknownHostException) {
            assertTrue(e.message?.contains("Unable to resolve host") == true)
        }
    }

    @Test
    fun testEmptyOrBlankHostnameThrowsUnknownHostException() {
        val dns = OptimizedDns()

        try {
            dns.lookup("")
            fail("Expected UnknownHostException for empty hostname")
        } catch (e: UnknownHostException) {
            assertTrue(e.message?.contains("cannot be empty") == true)
        }

        try {
            dns.lookup("   ")
            fail("Expected UnknownHostException for blank hostname")
        } catch (e: UnknownHostException) {
            assertTrue(e.message?.contains("cannot be empty") == true)
        }
    }

    @Test
    fun testNumericIpDetectionHelper() {
        val dns = OptimizedDns()

        assertTrue(dns.isNumericIp("127.0.0.1"))
        assertTrue(dns.isNumericIp("192.168.1.1"))
        assertTrue(dns.isNumericIp("10.0.0.1"))
        assertTrue(dns.isNumericIp("2606:4700:4700::1111"))
        assertTrue(dns.isNumericIp("::1"))

        assertFalse(dns.isNumericIp("example.com"))
        assertFalse(dns.isNumericIp("api.themoviedb.org"))
        assertFalse(dns.isNumericIp("127.0.0.1.nip.io"))
        assertFalse(dns.isNumericIp("subdomain.1.2.3.4"))
    }

    @Test
    fun testTopPredictiveDomainsConfiguration() {
        val domains = OptimizedDns.TOP_PREDICTIVE_DOMAINS
        assertTrue(domains.contains("api.themoviedb.org"))
        assertTrue(domains.contains("image.tmdb.org"))
        assertTrue(domains.contains("v3-cinemeta.strem.io"))
        assertTrue(domains.contains("api.ani.zip"))
        assertTrue(domains.contains("graphql.anilist.co"))
        assertTrue(domains.contains("torrentio.strem.fun"))

        assertTrue(OptimizedDns.CLOUDFLARE_BOOTSTRAP_IPS.contains("1.1.1.1"))
        assertTrue(OptimizedDns.GOOGLE_BOOTSTRAP_IPS.contains("8.8.8.8"))
    }
}

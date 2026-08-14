package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Maps link parsing.
 *
 * The lat/lng ordering is the trap: Google writes (lat, lng) while GeoJSON
 * and MapLibre use [lng, lat]. Swapping them puts a Saudi campus in the
 * Indian Ocean, and the mistake is invisible until someone follows the route.
 */
class GoogleMapsLinkTest {

    private fun found(raw: String): LatLng {
        val result = parseGoogleMapsLink(raw)
        assertTrue("expected coordinates, got $result", result is MapsLinkResult.Found)
        return (result as MapsLinkResult.Found).position
    }

    @Test
    fun `parses the exact dropped pin`() {
        val url = "https://www.google.com/maps/place/Taibah+University/" +
            "@24.4672,39.6111,17z/data=!3m1!4b1!4m5!3m4!1s0x0:0x0!8m2!3d24.4839!4d39.5892"
        val p = found(url)

        // The pin (!3d!4d), not the camera (@) - they differ here on purpose.
        assertEquals(24.4839, p.latitude, 1e-6)
        assertEquals(39.5892, p.longitude, 1e-6)
    }

    @Test
    fun `prefers the pin over the camera position`() {
        val url = "https://maps.google.com/@10.0,20.0,17z/data=!3d24.4839!4d39.5892"
        val p = found(url)

        assertEquals("should use the pin", 24.4839, p.latitude, 1e-6)
    }

    @Test
    fun `parses the camera position when there is no pin`() {
        val p = found("https://www.google.com/maps/@24.4672,39.6111,17z")
        assertEquals(24.4672, p.latitude, 1e-6)
        assertEquals(39.6111, p.longitude, 1e-6)
    }

    @Test
    fun `parses query parameters`() {
        assertEquals(24.4672, found("https://maps.google.com/?q=24.4672,39.6111").latitude, 1e-6)
        assertEquals(39.6111, found("https://maps.google.com/?ll=24.4672,39.6111").longitude, 1e-6)
        assertEquals(
            24.4672,
            found("https://www.google.com/maps/dir/?api=1&destination=24.4672,39.6111").latitude,
            1e-6
        )
    }

    @Test
    fun `parses the loc prefix`() {
        assertEquals(24.4672, found("https://maps.google.com/?q=loc:24.4672,39.6111").latitude, 1e-6)
    }

    @Test
    fun `parses a bare pasted pair`() {
        // What people actually copy out of the Google Maps app.
        val p = found("24.4672, 39.6111")
        assertEquals(24.4672, p.latitude, 1e-6)
        assertEquals(39.6111, p.longitude, 1e-6)
    }

    @Test
    fun `keeps latitude and longitude the right way round`() {
        // Latitude is bounded to 90; 39.6 as a latitude would be a silent
        // 1600km error rather than an obvious failure.
        val p = found("https://www.google.com/maps/@24.4672,39.6111,17z")
        assertTrue("latitude should be the smaller Saudi value", p.latitude < 30)
        assertTrue("longitude should be the larger", p.longitude > 30)
    }

    @Test
    fun `short links report that they need the network`() {
        assertTrue(isShortLink("https://maps.app.goo.gl/abc123"))
        assertEquals(
            MapsLinkResult.NeedsNetwork,
            parseGoogleMapsLink("https://maps.app.goo.gl/abc123")
        )
    }

    @Test
    fun `a short link carrying coordinates is still parsed`() {
        // Expanding it over the network would be wasteful when the answer is
        // already in the string.
        val p = found("https://maps.app.goo.gl/x?q=24.4672,39.6111")
        assertEquals(24.4672, p.latitude, 1e-6)
    }

    @Test
    fun `rejects out-of-range coordinates`() {
        val result = parseGoogleMapsLink("https://maps.google.com/?q=999.0,39.6")
        assertTrue("expected rejection, got $result", result is MapsLinkResult.NotFound)
    }

    @Test
    fun `rejects rubbish input`() {
        assertTrue(parseGoogleMapsLink("") is MapsLinkResult.NotFound)
        assertTrue(parseGoogleMapsLink("hello world") is MapsLinkResult.NotFound)
        assertTrue(parseGoogleMapsLink("https://example.com") is MapsLinkResult.NotFound)
    }

    @Test
    fun `handles negative coordinates`() {
        val p = found("https://www.google.com/maps/@-33.8688,151.2093,17z")
        assertEquals(-33.8688, p.latitude, 1e-6)
        assertEquals(151.2093, p.longitude, 1e-6)
    }

    @Test
    fun `builds a shareable link that round-trips`() {
        val original = LatLng(24.4672, 39.6111)
        val parsed = found(googleMapsLinkFor(original))

        assertEquals(original.latitude, parsed.latitude, 1e-6)
        assertEquals(original.longitude, parsed.longitude, 1e-6)
    }
}

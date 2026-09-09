package app.sterna.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCleanerTest {

    @Test fun stripsUtmParams() {
        assertEquals(
            "https://example.com/page?id=42",
            LinkCleaner.strip("https://example.com/page?utm_source=news&id=42&utm_medium=email"),
        )
    }

    @Test fun stripsAllLeavingNoQuery() {
        assertEquals(
            "https://example.com/page",
            LinkCleaner.strip("https://example.com/page?utm_source=news&fbclid=abc&gclid=xyz"),
        )
    }

    @Test fun keepsFunctionalParamsUntouched() {
        val url = "https://example.com/search?q=hello+world&page=2"
        assertEquals(url, LinkCleaner.strip(url))
    }

    @Test fun returnsOriginalWhenNoQuery() {
        val url = "https://example.com/path/to/page"
        assertEquals(url, LinkCleaner.strip(url))
    }

    @Test fun preservesFragmentAfterStripping() {
        assertEquals(
            "https://example.com/doc?id=1#section",
            LinkCleaner.strip("https://example.com/doc?utm_campaign=x&id=1#section"),
        )
    }

    @Test fun preservesFragmentWhenAllStripped() {
        assertEquals(
            "https://example.com/doc#section",
            LinkCleaner.strip("https://example.com/doc?fbclid=abc#section"),
        )
    }

    @Test fun isCaseInsensitiveOnParamName() {
        assertEquals(
            "https://example.com/p?keep=1",
            LinkCleaner.strip("https://example.com/p?UTM_Source=x&FBCLID=y&keep=1"),
        )
    }

    @Test fun stripsVariousVendorParams() {
        assertEquals(
            "https://shop.example.com/item",
            LinkCleaner.strip("https://shop.example.com/item?mc_eid=1&mkt_tok=2&igshid=3&yclid=4&msclkid=5"),
        )
    }

    @Test fun doesNotStripGenericLookalikes() {
        // "ref", "source", "id" are functional in many apps — never touched.
        val url = "https://example.com/p?ref=friend&source=home&id=9"
        assertEquals(url, LinkCleaner.strip(url))
    }

    @Test fun handlesParamWithoutValue() {
        assertEquals(
            "https://example.com/p?flag",
            LinkCleaner.strip("https://example.com/p?utm_source=x&flag"),
        )
    }

    @Test fun leavesNonHttpSchemeWithoutQueryAlone() {
        val url = "mailto:someone@example.com"
        assertEquals(url, LinkCleaner.strip(url))
    }

    @Test fun stripsPercentEncodedTrackingParamName() {
        // "utm%5Fsource" decodes to "utm_source" — must not slip past the matcher.
        assertEquals(
            "https://example.com/p?id=1",
            LinkCleaner.strip("https://example.com/p?utm%5Fsource=news&id=1"),
        )
    }
}

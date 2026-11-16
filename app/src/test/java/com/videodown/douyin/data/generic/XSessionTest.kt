package com.videodown.douyin.data.generic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XSessionTest {
    @Test fun `missing or blank header is not signed in`() {
        assertFalse(XSession.signedIn(null))
        assertFalse(XSession.signedIn(""))
        assertFalse(XSession.signedIn("   "))
    }

    @Test fun `one of the two cookies is not enough`() {
        assertFalse(XSession.signedIn("auth_token=abc"))
        assertFalse(XSession.signedIn("ct0=csrf"))
    }

    @Test fun `both cookies with values are a saved session`() {
        assertTrue(XSession.signedIn("auth_token=abc; ct0=csrf"))
        assertTrue(XSession.signedIn("guest_id=v1%3A1; auth_token=abc; ct0=csrf; twid=u%3D42"))
        assertTrue(XSession.signedIn("  auth_token = abc ;  ct0 = csrf  "))
    }

    @Test fun `blank cookie values do not count as a session`() {
        assertFalse(XSession.signedIn("auth_token=; ct0=csrf"))
        assertFalse(XSession.signedIn("auth_token=abc; ct0="))
        assertFalse(XSession.signedIn("auth_token; ct0=csrf"))
    }

    @Test fun `cookie names are matched exactly`() {
        assertFalse(XSession.signedIn("Auth_Token=abc; ct0=csrf"))
        assertFalse(XSession.signedIn("auth_token_extra=abc; ct0=csrf"))
    }
}

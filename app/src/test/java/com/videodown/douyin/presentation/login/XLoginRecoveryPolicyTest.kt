package com.videodown.douyin.presentation.login

import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XLoginRecoveryPolicyTest {
    @Test fun explicitLoginFailureUsesSignInWhenNoSessionIsDetected() {
        assertEquals(
            XLoginRecoveryAction.SIGN_IN_AND_RETRY,
            xLoginRecoveryAction(VideoPlatform.X, DownloadFailureKind.LOGIN_REQUIRED, false),
        )
    }

    @Test fun explicitLoginFailurePrioritisesReauthenticationForStoredSession() {
        assertEquals(
            XLoginRecoveryAction.REAUTHENTICATE_AND_RETRY,
            xLoginRecoveryAction(VideoPlatform.X, DownloadFailureKind.LOGIN_REQUIRED, true),
        )
    }

    @Test fun accessDeniedDoesNotClaimThatTheSessionExpired() {
        assertEquals(
            XLoginRecoveryAction.CHECK_SIGN_IN_AND_ACCESS,
            xLoginRecoveryAction(VideoPlatform.X, DownloadFailureKind.ACCESS_DENIED, true),
        )
    }

    @Test fun unrelatedFailuresAndPlatformsDoNotOfferXSignIn() {
        assertNull(xLoginRecoveryAction(VideoPlatform.X, DownloadFailureKind.NETWORK, true))
        assertNull(xLoginRecoveryAction(VideoPlatform.DOUYIN, DownloadFailureKind.LOGIN_REQUIRED, false))
        assertNull(xLoginRecoveryAction(null, DownloadFailureKind.LOGIN_REQUIRED, false))
    }
}

package com.sagarsystemslab.nownetwork.experience

import org.junit.Assert.assertEquals
import org.junit.Test

class ProofImageSamplingTest {
    @Test fun smallPhotosUseAValidNonzeroSample() {
        assertEquals(1, proofImageSampleSize(320, 240, 384))
    }
    @Test fun cameraPhotosAreBoundedForPreviewAndThumbnail() {
        assertEquals(4, proofImageSampleSize(4000, 3000, 1200))
        assertEquals(16, proofImageSampleSize(4000, 3000, 384))
        assertEquals(16, proofImageSampleSize(3000, 4000, 384))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidImageBoundsAreRejected() {
        proofImageSampleSize(0, 3000, 384)
    }
}

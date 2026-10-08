package com.sagarsystemslab.nownetwork.experience

/** BitmapFactory defaults its sample field to zero; decoding calculations must start at one. */
internal fun proofImageSampleSize(width: Int, height: Int, maximum: Int): Int {
    require(width in 1..16000 && height in 1..16000 && maximum > 0)
    var sample = 1
    while (width / sample > maximum || height / sample > maximum) sample *= 2
    return sample
}

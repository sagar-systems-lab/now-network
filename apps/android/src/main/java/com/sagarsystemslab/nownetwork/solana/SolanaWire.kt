package com.sagarsystemslab.nownetwork.solana

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

internal class SolanaPublicKey private constructor(
    val bytes: ByteArray,
    val address: String,
) {
    companion object {
        fun parse(value: String): SolanaPublicKey {
            val decoded = decodeBase58(value)
            require(decoded.size == PUBLIC_KEY_SIZE) {
                "Solana public key must decode to 32 bytes"
            }
            return SolanaPublicKey(decoded, value)
        }

        fun fromBytes(bytes: ByteArray): SolanaPublicKey {
            require(bytes.size == PUBLIC_KEY_SIZE) {
                "Solana public key must contain 32 bytes"
            }
            val copy = bytes.copyOf()
            return SolanaPublicKey(copy, encodeBase58(copy))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SolanaPublicKey && address == other.address

    override fun hashCode(): Int = address.hashCode()

    override fun toString(): String = address
}

internal data class SolanaAccountMeta(
    val key: SolanaPublicKey,
    val isSigner: Boolean,
    val isWritable: Boolean,
)

internal data class SolanaInstruction(
    val programId: SolanaPublicKey,
    val accounts: List<SolanaAccountMeta>,
    val data: ByteArray,
)

internal object SolanaPrograms {
    val system = SolanaPublicKey.parse("11111111111111111111111111111111")
    val token = SolanaPublicKey.parse("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val associatedToken =
        SolanaPublicKey.parse("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val rent = SolanaPublicKey.parse("SysvarRent111111111111111111111111111111111")
}

internal fun findProgramAddress(
    seeds: List<ByteArray>,
    programId: SolanaPublicKey,
): SolanaPublicKey {
    require(seeds.size <= MAX_PDA_SEEDS) { "too many PDA seeds" }
    seeds.forEach { seed ->
        require(seed.size <= MAX_PDA_SEED_LENGTH) { "PDA seed exceeds 32 bytes" }
    }

    for (bump in 255 downTo 0) {
        val digest = sha256(
            buildList {
                addAll(seeds)
                add(byteArrayOf(bump.toByte()))
                add(programId.bytes)
                add(PDA_MARKER)
            },
        )
        if (!isEd25519Point(digest)) {
            return SolanaPublicKey.fromBytes(digest)
        }
    }

    error("no viable PDA bump")
}

internal fun serializeLegacyTransaction(
    feePayer: SolanaPublicKey,
    recentBlockhash: String,
    instructions: List<SolanaInstruction>,
): ByteArray {
    require(instructions.isNotEmpty()) { "transaction requires at least one instruction" }
    val blockhash = decodeBase58(recentBlockhash)
    require(blockhash.size == PUBLIC_KEY_SIZE) { "recent blockhash must decode to 32 bytes" }

    data class MutablePrivileges(
        val key: SolanaPublicKey,
        var signer: Boolean,
        var writable: Boolean,
        val order: Int,
    )

    val privileges = linkedMapOf<String, MutablePrivileges>()
    var order = 0

    fun merge(key: SolanaPublicKey, signer: Boolean, writable: Boolean) {
        val existing = privileges[key.address]
        if (existing == null) {
            privileges[key.address] = MutablePrivileges(
                key = key,
                signer = signer,
                writable = writable,
                order = order++,
            )
        } else {
            existing.signer = existing.signer || signer
            existing.writable = existing.writable || writable
        }
    }

    merge(feePayer, signer = true, writable = true)
    instructions.forEach { instruction ->
        instruction.accounts.forEach { meta ->
            merge(meta.key, meta.isSigner, meta.isWritable)
        }
        merge(instruction.programId, signer = false, writable = false)
    }

    val payer = privileges.getValue(feePayer.address)
    payer.signer = true
    payer.writable = true

    val all = privileges.values.toList()
    val ordered = buildList {
        add(payer)
        addAll(
            all.filter { it.key != feePayer && it.signer && it.writable }
                .sortedBy { it.order },
        )
        addAll(
            all.filter { it.key != feePayer && it.signer && !it.writable }
                .sortedBy { it.order },
        )
        addAll(
            all.filter { !it.signer && it.writable }
                .sortedBy { it.order },
        )
        addAll(
            all.filter { !it.signer && !it.writable }
                .sortedBy { it.order },
        )
    }

    require(ordered.size <= 256) { "legacy transaction exceeds account index range" }

    val requiredSignatures = ordered.count { it.signer }
    val readonlySigned = ordered.count { it.signer && !it.writable }
    val readonlyUnsigned = ordered.count { !it.signer && !it.writable }
    require(requiredSignatures in 1..255) { "invalid signer count" }

    val accountIndexes = ordered.mapIndexed { index, item ->
        item.key.address to index
    }.toMap()

    val message = ByteArrayOutputStream().apply {
        write(requiredSignatures)
        write(readonlySigned)
        write(readonlyUnsigned)
        write(shortVec(ordered.size))
        ordered.forEach { write(it.key.bytes) }
        write(blockhash)
        write(shortVec(instructions.size))

        instructions.forEach { instruction ->
            val programIndex = accountIndexes[instruction.programId.address]
                ?: error("program account missing from compiled keys")
            write(programIndex)
            write(shortVec(instruction.accounts.size))
            instruction.accounts.forEach { meta ->
                val accountIndex = accountIndexes[meta.key.address]
                    ?: error("instruction account missing from compiled keys")
                write(accountIndex)
            }
            write(shortVec(instruction.data.size))
            write(instruction.data)
        }
    }.toByteArray()

    return ByteArrayOutputStream().apply {
        write(shortVec(requiredSignatures))
        repeat(requiredSignatures) {
            write(ByteArray(SIGNATURE_SIZE))
        }
        write(message)
    }.toByteArray()
}

internal fun decodeBase58(value: String): ByteArray {
    require(value.isNotEmpty()) { "base58 value is empty" }

    var number = BigInteger.ZERO
    val base = BigInteger.valueOf(58L)
    value.forEach { char ->
        val digit = BASE58_INDEX[char]
            ?: throw IllegalArgumentException("invalid base58 character")
        number = number.multiply(base).add(BigInteger.valueOf(digit.toLong()))
    }

    val leadingZeroes = value.takeWhile { it == '1' }.length
    val magnitude = if (number == BigInteger.ZERO) {
        byteArrayOf()
    } else {
        number.toByteArray().let { encoded ->
            if (encoded.size > 1 && encoded[0] == 0.toByte()) {
                encoded.copyOfRange(1, encoded.size)
            } else {
                encoded
            }
        }
    }

    return ByteArray(leadingZeroes + magnitude.size).also { output ->
        magnitude.copyInto(output, destinationOffset = leadingZeroes)
    }
}

internal fun encodeBase58(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""

    val leadingZeroes = bytes.takeWhile { it == 0.toByte() }.size
    var number = BigInteger(1, bytes)
    val base = BigInteger.valueOf(58L)
    val encoded = StringBuilder()

    while (number > BigInteger.ZERO) {
        val result = number.divideAndRemainder(base)
        encoded.append(BASE58_ALPHABET[result[1].toInt()])
        number = result[0]
    }

    repeat(leadingZeroes) {
        encoded.append('1')
    }

    return encoded.reverse().toString()
}

private fun shortVec(value: Int): ByteArray {
    require(value >= 0)
    var remaining = value
    val output = ByteArrayOutputStream()
    do {
        var current = remaining and 0x7f
        remaining = remaining ushr 7
        if (remaining != 0) current = current or 0x80
        output.write(current)
    } while (remaining != 0)
    return output.toByteArray()
}

private fun sha256(parts: List<ByteArray>): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach(digest::update)
    return digest.digest()
}

private fun isEd25519Point(bytes: ByteArray): Boolean {
    if (bytes.size != PUBLIC_KEY_SIZE) return false

    val encodedY = bytes.copyOf()
    val sign = (encodedY[31].toInt() ushr 7) and 1
    encodedY[31] = (encodedY[31].toInt() and 0x7f).toByte()

    val y = littleEndianToBigInteger(encodedY)
    if (y >= FIELD_PRIME) return false

    val ySquared = mod(y.multiply(y))
    val numerator = mod(ySquared.subtract(BigInteger.ONE))
    val denominator = mod(CURVE_D.multiply(ySquared).add(BigInteger.ONE))
    val xSquared = mod(
        numerator.multiply(
            denominator.modPow(FIELD_PRIME.subtract(BigInteger.TWO), FIELD_PRIME),
        ),
    )

    var x = xSquared.modPow(
        FIELD_PRIME.add(BigInteger.valueOf(3L)).divide(BigInteger.valueOf(8L)),
        FIELD_PRIME,
    )

    if (mod(x.multiply(x).subtract(xSquared)) != BigInteger.ZERO) {
        x = mod(x.multiply(SQRT_MINUS_ONE))
    }
    if (mod(x.multiply(x).subtract(xSquared)) != BigInteger.ZERO) return false
    if (x == BigInteger.ZERO && sign == 1) return false

    return true
}

private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger =
    BigInteger(1, bytes.reversedArray())

private fun mod(value: BigInteger): BigInteger {
    val remainder = value.remainder(FIELD_PRIME)
    return if (remainder.signum() >= 0) remainder else remainder.add(FIELD_PRIME)
}

private const val PUBLIC_KEY_SIZE = 32
private const val SIGNATURE_SIZE = 64
private const val MAX_PDA_SEEDS = 16
private const val MAX_PDA_SEED_LENGTH = 32
private const val BASE58_ALPHABET =
    "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private val BASE58_INDEX = BASE58_ALPHABET
    .withIndex()
    .associate { (index, char) -> char to index }
private val PDA_MARKER = "ProgramDerivedAddress".encodeToByteArray()
private val FIELD_PRIME = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19L))
private val CURVE_D = mod(
    BigInteger.valueOf(-121665L).multiply(
        BigInteger.valueOf(121666L).modPow(
            FIELD_PRIME.subtract(BigInteger.TWO),
            FIELD_PRIME,
        ),
    ),
)
private val SQRT_MINUS_ONE =
    BigInteger.valueOf(2L).modPow(
        FIELD_PRIME.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4L)),
        FIELD_PRIME,
    )

package org.bitcoinj.core;

import org.bouncycastle.crypto.digests.Blake2bDigest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Helpers for the BLAKE2b-256 "header v2" block proof-of-work introduced by the
 * Bitcoin Knots hardfork (Bitcoin Knots PR #359, "Hardfork: New BLAKE2b
 * proof-of-work algorithm").
 *
 * <p>Only the block-header proof-of-work hashing lives here (and, later, in the
 * header parse/serialize paths that feed it). Every other hash in this fork is
 * unchanged. Byte conventions mirror the upstream reference implementations
 * ({@code src/primitives/block.cpp} and the functional test framework
 * {@code test/functional/test_framework/messages.py}), which are validated
 * against {@code src/test/data/block_header_v2.json}.
 *
 * <p>The input is the full serialized 164-byte header-v2 block header. The
 * returned {@code result} bytes are such that their hex form is the block hash
 * as displayed by block explorers and reported by Bitcoin Knots RPC.
 */
public final class Blake2b {

    private Blake2b() {
    }

    /** BLAKE2b-256 of {@code data} (raw 32-byte digest). */
    public static byte[] blake2b256(byte[] data) {
        Blake2bDigest digest = new Blake2bDigest(256);
        digest.update(data, 0, data.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    /** BIP-340 style tagged SHA-256: SHA256(SHA256(tag) || SHA256(tag) || data). */
    public static byte[] taggedSha256(String tag, byte[] data) {
        byte[] tagHash = sha256(tag.getBytes(StandardCharsets.US_ASCII));
        MessageDigest md = newSha256();
        md.update(tagHash);
        md.update(tagHash);
        md.update(data);
        return md.digest();
    }

    /** Single SHA-256 of {@code data}. */
    public static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every intermediate of the header-v2 proof-of-work hash, exposed so unit
     * tests can assert each value against the published test vectors.
     */
    public static final class HeaderV2Hash {
        public final int asicProfile;
        public final byte[] xorKeyHash;
        public final byte[] prevBlockHidden;
        public final byte[] h1;
        public final byte[] h2;
        public final byte[] hash1;
        public final byte[] asicInput;
        public final byte[] hash2;
        public final byte[] mask;
        public final byte[] result;

        HeaderV2Hash(int asicProfile, byte[] xorKeyHash, byte[] prevBlockHidden, byte[] h1, byte[] h2,
                     byte[] hash1, byte[] asicInput, byte[] hash2, byte[] mask, byte[] result) {
            this.asicProfile = asicProfile;
            this.xorKeyHash = xorKeyHash;
            this.prevBlockHidden = prevBlockHidden;
            this.h1 = h1;
            this.h2 = h2;
            this.hash1 = hash1;
            this.asicInput = asicInput;
            this.hash2 = hash2;
            this.mask = mask;
            this.result = result;
        }
    }

    /**
     * Computes the header-v2 proof-of-work hash of a 164-byte serialized header
     * and returns every intermediate component.
     */
    public static HeaderV2Hash headerV2HashComponents(byte[] header) {
        if (header.length != 164) {
            throw new IllegalArgumentException("header v2 must be 164 bytes, got " + header.length);
        }

        byte[] xorKey = Arrays.copyOfRange(header, 112, 128);
        byte[] xorKeyHash = taggedSha256("Bitcoin block hash PoW XOR key", xorKey);

        // The prev-block hash is reversed to display order for hashing.
        byte[] prevblockOrdered = reverse(Arrays.copyOfRange(header, 4, 36));
        byte[] prevblockHidden = taggedSha256("Bitcoin prevblock header, hashed", prevblockOrdered);
        for (int i = 0; i < 6; i++) {
            prevblockHidden[i] = 0;
        }

        byte[] txcount32 = {header[108], header[109], 0, 0};
        byte[] h1 = taggedSha256("Bitcoin block header 1",
                concat(
                        Arrays.copyOfRange(header, 0, 4),      // complete version (v2 flag bit set)
                        prevblockOrdered,                       // prev block hash, display order
                        Arrays.copyOfRange(header, 128, 132),   // height, int32 LE
                        Arrays.copyOfRange(header, 36, 68),     // merkle root, wire (internal) order
                        Arrays.copyOfRange(header, 68, 72),     // time on wire
                        new byte[] {0},                         // reserved for extended 40-bit time
                        Arrays.copyOfRange(header, 72, 76),     // nBits
                        txcount32,                              // tx count as uint32 LE
                        new byte[] {header[110]},               // flags
                        new byte[] {header[111]},               // xor-key mask clear bits
                        xorKeyHash));

        byte[] h2 = taggedSha256("Merge-mining hook",
                concat(h1, new byte[32], Arrays.copyOfRange(header, 132, 164))); // mm_rhs, wire order

        // First BLAKE2b stage: 4 zero bytes + h2 + extranonce ("Sv1 coinb1/extranonce").
        byte[] hash1 = blake2b256(concat(new byte[4], h2, Arrays.copyOfRange(header, 88, 104)));

        int flags = header[110] & 0xff;
        int profile = flags & 3;
        // nonce, nonce2, time_offset, nonce3, hash1
        byte[] tail = concat(
                Arrays.copyOfRange(header, 76, 80),
                Arrays.copyOfRange(header, 80, 84),
                Arrays.copyOfRange(header, 104, 108),
                Arrays.copyOfRange(header, 84, 88),
                hash1);
        byte[] asicInput;
        switch (profile) {
            case 0:
                asicInput = concat(prevblockHidden, tail);
                break;
            case 1:
                asicInput = concat(
                        Arrays.copyOfRange(header, 76, 80),   // nonce
                        Arrays.copyOfRange(header, 80, 84),   // nonce2
                        Arrays.copyOfRange(header, 84, 88),   // nonce3
                        Arrays.copyOfRange(header, 104, 108), // time_offset
                        hash1, h2);
                break;
            case 2:
                asicInput = concat(new byte[48], h2, tail);
                break;
            default: // 3
                asicInput = concat(new byte[80], h2, tail);
        }

        byte[] hash2 = blake2b256(asicInput);

        byte[] mask = new byte[32];
        if (!isZero(xorKey)) {
            mask = taggedSha256("Bitcoin block hash PoW XOR mask", xorKey);
            int clearBits = header[111] & 0xff;
            int clearBytes = clearBits / 8;
            int remaining = clearBits % 8;
            for (int i = 0; i < clearBytes && i < 32; i++) {
                mask[i] = 0;
            }
            if (clearBytes < 32) {
                mask[clearBytes] &= (0xff >>> remaining);
            }
        }

        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) {
            result[i] = (byte) (hash2[i] ^ mask[i]);
        }
        return new HeaderV2Hash(profile, xorKeyHash, prevblockHidden, h1, h2, hash1, asicInput, hash2, mask, result);
    }

    /** Convenience: the 32-byte header-v2 proof-of-work hash (display order). */
    public static byte[] headerV2Hash(byte[] header) {
        return headerV2HashComponents(header).result;
    }

    private static boolean isZero(byte[] a) {
        for (byte b : a) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] reverse(byte[] a) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[a.length - 1 - i];
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}

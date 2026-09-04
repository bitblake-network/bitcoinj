package org.bitcoinj.params;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.MemoryBlockStore;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Validates the testnet4 difficulty rules: the pure ports of Core's
 * {@code ApplyBlake2bTargetShift} / {@code CalculateNextWorkRequired} (BIP94)
 * and the end-to-end {@code checkDifficultyTransitions} path on a synthetic
 * in-memory chain, including the one-off BLAKE2b target shift at the hardfork
 * boundary.
 */
public class TestNet4DifficultyTest {

    /** Minimum difficulty target (0x1d00ffff) encoded. */
    private static final long EASY = 0x1d00ffffL;
    /** A harder-than-minimum target, still valid. */
    private static final long HARD = 0x1c00ffffL;

    private static final long BASE_TIME = 1700000000L;

    /** TestNet4 with the hardfork moved to height 6 so the shift can be exercised cheaply. */
    private static final class LowForkParams extends TestNet4Params {
        LowForkParams() {
            blake2bHeight = 6;
        }
    }

    private static Block header(NetworkParameters params, Sha256Hash prevHash, long time, long nBits) {
        return new Block(params, 1L, prevHash, Sha256Hash.ZERO_HASH, time, nBits, 0L, Collections.emptyList());
    }

    @Test
    public void applyBlake2bTargetShift() {
        BigInteger limit = Utils.decodeCompactBits(EASY);
        long shifted = AbstractBitcoinNetParams.applyBlake2bTargetShift(0x1702905cL, limit, 20);
        BigInteger expected = Utils.decodeCompactBits(0x1702905cL).shiftLeft(20);
        assertEquals("shifted target must equal compact(target << 20)", Utils.encodeCompactBits(expected), shifted);
        // A value at the proof-of-work limit cannot be shifted further.
        assertEquals("limit stays at the limit", EASY,
                AbstractBitcoinNetParams.applyBlake2bTargetShift(EASY, limit, 20));
    }

    @Test
    public void calculateNextWorkRequiredBip94() {
        BigInteger limit = Utils.decodeCompactBits(EASY);
        // Two-week span: target stays at the base. BIP94 rebases on the first-of-period bits,
        // plain retarget on the last-of-period bits.
        assertEquals(EASY, AbstractBitcoinNetParams.calculateNextWorkRequired(HARD, EASY, 1209600L, limit, true));
        assertEquals(HARD, AbstractBitcoinNetParams.calculateNextWorkRequired(HARD, EASY, 1209600L, limit, false));
        // Half span halves the target (harder) when rebased on the easy first-of-period bits.
        long next = AbstractBitcoinNetParams.calculateNextWorkRequired(HARD, EASY, 604800L, limit, true);
        BigInteger expected = Utils.decodeCompactBits(EASY).multiply(BigInteger.valueOf(604800L))
                .divide(BigInteger.valueOf(1209600L));
        assertEquals(Utils.encodeCompactBits(expected), next);
    }

    @Test
    public void acceptsUnchangedDifficultyOffBoundary() throws Exception {
        TestNet4Params params = TestNet4Params.get();
        MemoryBlockStore store = new MemoryBlockStore(params);
        StoredBlock storedPrev = buildChain(params, 1, HARD, store);
        // Same difficulty, within the 20-minute window -> accepted.
        Block next = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 3 * 600L, HARD);
        params.checkDifficultyTransitions(storedPrev, next, store);
        // Different difficulty off-boundary -> rejected.
        Block bad = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 3 * 600L, EASY);
        expectRejection(params, storedPrev, bad, store);
    }

    @Test
    public void minDifficultyRules() throws Exception {
        TestNet4Params params = TestNet4Params.get();
        MemoryBlockStore store = new MemoryBlockStore(params);
        StoredBlock storedPrev = buildChain(params, 2, EASY, store);
        // Within 20 minutes, an easy block equals the walked-back expectation -> accepted.
        Block easy = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 3 * 600L, EASY);
        params.checkDifficultyTransitions(storedPrev, easy, store);
        // After 20+ minutes an easy block is allowed by the min-difficulty rule.
        Block late = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 3 * 600L + 1500L, EASY);
        params.checkDifficultyTransitions(storedPrev, late, store);
        // But a hard block within the window contradicts the easy walked-back target.
        Block hard = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 3 * 600L, HARD);
        expectRejection(params, storedPrev, hard, store);
    }

    @Test
    public void oneOffBlake2bShiftAtForkBoundary() throws Exception {
        LowForkParams params = new LowForkParams();
        MemoryBlockStore store = new MemoryBlockStore(params);
        StoredBlock storedPrev = buildChain(params, 5, HARD, store);
        long shifted = AbstractBitcoinNetParams.applyBlake2bTargetShift(HARD,
                Utils.decodeCompactBits(EASY), params.getBlake2bTargetShift());
        // The block at the fork height carries the shifted target -> accepted.
        Block ok = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 6 * 600L, shifted);
        params.checkDifficultyTransitions(storedPrev, ok, store);
        // The unshifted target at the fork height -> rejected.
        Block bad = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 6 * 600L, HARD);
        expectRejection(params, storedPrev, bad, store);
    }

    // --- helpers ---

    /** Builds a header chain (heights 1..height) with the given difficulty into {@code store} and returns the tip. */
    private static StoredBlock buildChain(NetworkParameters params, int height, long nBits, MemoryBlockStore store)
            throws Exception {
        Block genesis = params.getGenesisBlock();
        store.put(new StoredBlock(genesis, genesis.getWork(), 0));
        Block prev = genesis;
        StoredBlock tip = null;
        for (int h = 1; h <= height; h++) {
            Block b = header(params, prev.getHash(), BASE_TIME + h * 600L, nBits);
            tip = new StoredBlock(b, b.getWork(), h);
            store.put(tip);
            prev = b;
        }
        return tip;
    }

    private static void expectRejection(TestNet4Params params, StoredBlock storedPrev, Block next,
            MemoryBlockStore store) throws Exception {
        try {
            params.checkDifficultyTransitions(storedPrev, next, store);
            fail("expected VerificationException");
        } catch (VerificationException expected) {
            // expected
        }
    }
}

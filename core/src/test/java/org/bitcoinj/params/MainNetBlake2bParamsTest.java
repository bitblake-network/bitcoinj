package org.bitcoinj.params;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.MemoryBlockStore;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MainNetBlake2bParamsTest {

    private static final long BASE_TIME = 1788000000L;

    /** Mainnet with the hardfork moved to height 6 so the shift can be exercised cheaply. */
    private static final class LowForkParams extends MainNetBlake2bParams {
        LowForkParams() {
            blake2bHeight = 6;
        }
    }

    private static Block header(NetworkParameters params, Sha256Hash prevHash, long time, long nBits) {
        return new Block(params, 1L, prevHash, Sha256Hash.ZERO_HASH, time, nBits, 0L, Collections.emptyList());
    }

    @Test
    public void networkFacts() {
        MainNetBlake2bParams params = MainNetBlake2bParams.get();
        assertEquals(NetworkParameters.ID_MAINNET_BLAKE2B, params.getId());
        assertEquals("main-blake2b", params.getPaymentProtocolId());
        // Same pre-fork facts as the standard mainnet (shared history).
        assertEquals(MainNetParams.get().getGenesisBlock().getHashAsString(),
                params.getGenesisBlock().getHashAsString());
        assertEquals(MainNetParams.get().getPort(), params.getPort());
        assertEquals(MainNetParams.get().getPacketMagic(), params.getPacketMagic());
        assertEquals(MainNetParams.get().getAddressHeader(), params.getAddressHeader());
        assertEquals(MainNetParams.get().getSegwitAddressHrp(), params.getSegwitAddressHrp());
        assertSame(params, NetworkParameters.fromID(NetworkParameters.ID_MAINNET_BLAKE2B));
        assertSame(params, NetworkParameters.fromPmtProtocolID("main-blake2b"));
        // Distinct from the pre-fork standard mainnet.
        assertNotEquals(MainNetParams.get().getId(), params.getId());
    }

    @Test
    public void blake2bActivation() {
        MainNetBlake2bParams params = MainNetBlake2bParams.get();
        assertEquals(961640, params.getBlake2bHeight());
        // Knots mainnet uses a 22-bit shift (chainparams.cpp), not the 20-bit testnet4 default.
        assertEquals(22, params.getBlake2bTargetShift());
        assertFalse(params.isBlake2bActiveAt(0));
        assertFalse(params.isBlake2bActiveAt(961639));
        assertTrue(params.isBlake2bActiveAt(961640));
        assertTrue(params.isBlake2bActiveAt(967955));
        // Mainnet strict difficulty policy.
        assertFalse(params.isPowAllowMinDifficultyBlocks());
        assertFalse(params.isEnforceBip94());
        // The standard mainnet params remain untouched (pre-fork view).
        assertEquals(Integer.MAX_VALUE, MainNetParams.get().getBlake2bHeight());
    }

    @Test
    public void mainnetActivationShiftVector() {
        // Live mainnet BLAKE2b activation (height 961640, cross-checked 2026-09-05):
        // block 961639 nBits 0x1702353d -> block 961640 nBits 0x1a008d4f, shift 22.
        MainNetBlake2bParams params = MainNetBlake2bParams.get();
        long shifted = AbstractBitcoinNetParams.applyBlake2bTargetShift(0x1702353dL,
                params.getMaxTarget(), params.getBlake2bTargetShift());
        assertEquals("activation nBits must equal shift22(prev nBits)", 0x1a008d4fL, shifted);
    }

    @Test
    public void oneOffBlake2bShiftAtActivation() throws Exception {
        LowForkParams params = new LowForkParams();
        MemoryBlockStore store = new MemoryBlockStore(params);
        StoredBlock storedPrev = buildChain(params, 5, 0x1702905cL, store);
        long shifted = AbstractBitcoinNetParams.applyBlake2bTargetShift(0x1702905cL,
                params.getMaxTarget(), params.getBlake2bTargetShift());
        // The block at the fork height carries the shifted target -> accepted.
        Block ok = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 6 * 600L, shifted);
        params.checkDifficultyTransitions(storedPrev, ok, store);
        // The unshifted (inherited) target at the fork height -> rejected.
        Block bad = header(params, storedPrev.getHeader().getHash(), BASE_TIME + 6 * 600L, 0x1702905cL);
        try {
            params.checkDifficultyTransitions(storedPrev, bad, store);
            fail("expected VerificationException");
        } catch (VerificationException expected) {
            // expected
        }
    }

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
}

package org.bitcoinj.params;

import org.bitcoinj.core.NetworkParameters;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MainNetBlake2bParamsTest {

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
        assertEquals(20, params.getBlake2bTargetShift());
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
}

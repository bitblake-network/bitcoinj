package org.bitcoinj.params;

import org.bitcoinj.core.BitcoinSerializer;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestNet4ParamsTest {

    @Test
    public void genesis() {
        TestNet4Params params = TestNet4Params.get();
        assertEquals("00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043",
                params.getGenesisBlock().getHashAsString());
    }

    @Test
    public void networkFacts() {
        TestNet4Params params = TestNet4Params.get();
        assertEquals(NetworkParameters.ID_TESTNET4, params.getId());
        assertEquals("testnet4", params.getPaymentProtocolId());
        assertEquals(48333, params.getPort());
        assertEquals(0x1c163f28L, params.getPacketMagic());
        assertEquals(111, params.getAddressHeader());
        assertEquals(196, params.getP2SHHeader());
        assertEquals("tb", params.getSegwitAddressHrp());
        assertSame(params, NetworkParameters.fromID(NetworkParameters.ID_TESTNET4));
        assertSame(params, NetworkParameters.fromPmtProtocolID("testnet4"));
        assertNotEquals(NetworkParameters.ID_TESTNET, params.getId());
    }

    @Test
    public void blake2bActivation() {
        TestNet4Params params = TestNet4Params.get();
        assertEquals(150308, params.getBlake2bHeight());
        assertEquals(20, params.getBlake2bTargetShift());
        assertFalse(params.isBlake2bActiveAt(0));
        assertFalse(params.isBlake2bActiveAt(150307));
        assertTrue(params.isBlake2bActiveAt(150308));
        assertTrue(params.isBlake2bActiveAt(150573));
        assertTrue(params.isPowAllowMinDifficultyBlocks());
        assertTrue(params.isEnforceBip94());

        // Mainnet stays untouched by the hardfork knobs.
        assertFalse(MainNetParams.get().isBlake2bActiveAt(0));
        assertEquals(Integer.MAX_VALUE, MainNetParams.get().getBlake2bHeight());
        assertFalse(MainNetParams.get().isEnforceBip94());
        assertFalse(MainNetParams.get().isPowAllowMinDifficultyBlocks());
    }

    @Test
    public void parsesPreForkV1Header() throws Exception {
        // Real testnet4 v1 (SHA-256d) header at height 149537, from the live chain.
        TestNet4Params params = TestNet4Params.get();
        BitcoinSerializer serializer = params.getSerializer(true);
        InputStream in = getClass().getResourceAsStream("/org/bitcoinj/core/h149537.txt");
        assertNotNull("live header resource missing", in);
        byte[] serialized = Utils.HEX.decode(
                new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim());
        Block header = serializer.makeBlock(serialized, 0, Message.UNKNOWN_LENGTH);
        assertFalse(header.isHeaderV2());
        assertEquals(80, header.getHeaderSize());
        assertEquals("00000000008718548961fc1af48c515037b1b5f2efe6e778c60ed0b37a593744",
                header.getHashAsString());
    }
}

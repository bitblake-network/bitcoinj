package org.bitcoinj.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bitcoinj.params.MainNetParams;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Validates the header-v2 BLAKE2b proof-of-work hash against the official
 * Bitcoin Knots test vectors (src/test/data/block_header_v2.json), covering all
 * four ASIC profiles plus the time-offset-disabled case. Every intermediate
 * (xor_key_hash, h1, h2, blake2b_1, blake2b_2, mask, asic_input) is asserted,
 * not just the final block hash.
 */
public class Blake2bHeaderHashTest {

    @Test
    public void headerV2Vectors() throws Exception {
        InputStream in = getClass().getResourceAsStream("block_header_v2.json");
        assertNotNull("test vector resource missing", in);
        JsonNode root = new ObjectMapper().readTree(in);
        JsonNode headers = root.get("headers");
        assertNotNull(headers);
        assertEquals("expected 5 vectors", 5, headers.size());
        for (JsonNode vector : headers) {
            String name = vector.get("name").asText();
            byte[] serialized = Utils.HEX.decode(vector.get("serialized").asText());
            assertEquals(name + ": header length", 164, serialized.length);

            Blake2b.HeaderV2Hash h = Blake2b.headerV2HashComponents(serialized);

            assertEquals(name + ": asic_profile", vector.get("asic_profile").asInt(), h.asicProfile);
            assertEquals(name + ": xor_key_hash", vector.get("xor_key_hash").asText(), Utils.HEX.encode(h.xorKeyHash));
            assertEquals(name + ": h1", vector.get("h1").asText(), Utils.HEX.encode(h.h1));
            assertEquals(name + ": h2", vector.get("h2").asText(), Utils.HEX.encode(h.h2));
            assertEquals(name + ": blake2b_1", vector.get("blake2b_1").asText(), Utils.HEX.encode(h.hash1));
            assertEquals(name + ": blake2b_2", vector.get("blake2b_2").asText(), Utils.HEX.encode(h.hash2));
            assertEquals(name + ": mask", vector.get("mask").asText(), Utils.HEX.encode(h.mask));
            assertEquals(name + ": asic_input", vector.get("asic_input").asText(), Utils.HEX.encode(h.asicInput));
            assertEquals(name + ": block_hash", vector.get("block_hash").asText(), Utils.HEX.encode(h.result));
            // headerV2Hash convenience must return exactly the result bytes.
            assertEquals(name + ": convenience hash", Utils.HEX.encode(h.result),
                    Utils.HEX.encode(Blake2b.headerV2Hash(serialized)));
        }
    }

    /**
     * Cross-checks against real live testnet4 headers fetched from the
     * explorer (mempool.guide/testnet4, Esplora-compatible, header-v2 aware).
     * Block 150308 is the first BLAKE2b/header-v2 block of the live chain;
     * blocks 150307 and 149537 are still SHA-256d/v1.
     */
    @Test
    public void liveTestnet4Headers() throws Exception {
        checkLive("h150308.txt", "000000000000b9d1b7e1bb0e77215ee92c6ef7ec8f4473e23908380649e779b6",
                164, 150308);
        checkLive("h150307.txt", "000000000017ec2251d81c8d2ca401c713e98e85196c7f660a4088a7ca57b1cc",
                80, -1);
        checkLive("h149537.txt", "00000000008718548961fc1af48c515037b1b5f2efe6e778c60ed0b37a593744",
                80, -1);
    }

    private void checkLive(String resource, String expectedId, int expectedHeaderSize, int expectedHeight) throws Exception {
        NetworkParameters params = MainNetParams.get();
        BitcoinSerializer serializer = params.getSerializer(true);
        InputStream in = getClass().getResourceAsStream(resource);
        assertNotNull("live header resource missing: " + resource, in);
        byte[] serialized = Utils.HEX.decode(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim());
        assertEquals(resource + ": header length", expectedHeaderSize, serialized.length);
        Block header = serializer.makeBlock(serialized, 0, Message.UNKNOWN_LENGTH);
        assertEquals(resource + ": v2 flag", expectedHeaderSize == 164, header.isHeaderV2());
        assertEquals(resource + ": header size", expectedHeaderSize, header.getHeaderSize());
        assertEquals(resource + ": block id", expectedId, header.getHashAsString());
        if (expectedHeight >= 0) {
            assertEquals(resource + ": header height", expectedHeight, header.getHeaderHeight());
        }
        assertEquals(resource + ": round trip", Utils.HEX.encode(serialized), Utils.HEX.encode(header.bitcoinSerialize()));
    }

    /**
     * A P2P headers message can mix pre-fork v1 (80-byte) and post-fork v2
     * (164-byte) headers in one payload, as happens while an SPV client crosses
     * the BLAKE2b activation. This exercises the variable-size cursor advance
     * end to end with real live headers.
     */
    @Test
    public void headersMessageMixedV1AndV2() throws Exception {
        NetworkParameters params = MainNetParams.get();
        Block v1 = loadHeader(params, "h150307.txt");
        Block v2 = loadHeader(params, "h150308.txt");

        HeadersMessage sent = new HeadersMessage(params, v1.cloneAsHeader(), v2.cloneAsHeader());
        HeadersMessage parsed = new HeadersMessage(params, sent.bitcoinSerialize());
        List<Block> headers = parsed.getBlockHeaders();
        assertEquals("two headers", 2, headers.size());

        assertEquals(v1.getHashAsString(), headers.get(0).getHashAsString());
        assertFalse(headers.get(0).isHeaderV2());
        assertEquals(80, headers.get(0).getHeaderSize());

        assertEquals(v2.getHashAsString(), headers.get(1).getHashAsString());
        assertTrue(headers.get(1).isHeaderV2());
        assertEquals(164, headers.get(1).getHeaderSize());
        assertEquals(150308, headers.get(1).getHeaderHeight());
    }

    private Block loadHeader(NetworkParameters params, String resource) throws Exception {
        BitcoinSerializer serializer = params.getSerializer(true);
        InputStream in = getClass().getResourceAsStream(resource);
        assertNotNull("live header resource missing: " + resource, in);
        byte[] serialized = Utils.HEX.decode(
                new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim());
        return serializer.makeBlock(serialized, 0, Message.UNKNOWN_LENGTH);
    }

    /**
     * Parses each official vector through the real {@link Block} header path
     * (same code path used for P2P headers messages) and checks that the parsed
     * header hashes to the published block hash and re-serializes byte-for-byte.
     */
    @Test
    public void headerV2BlockParseSerializeRoundTrip() throws Exception {
        NetworkParameters params = MainNetParams.get();
        BitcoinSerializer serializer = params.getSerializer(true);
        InputStream in = getClass().getResourceAsStream("block_header_v2.json");
        assertNotNull("test vector resource missing", in);
        JsonNode headers = new ObjectMapper().readTree(in).get("headers");
        assertNotNull(headers);
        for (JsonNode vector : headers) {
            String name = vector.get("name").asText();
            byte[] serialized = Utils.HEX.decode(vector.get("serialized").asText());

            Block header = serializer.makeBlock(serialized, 0, Message.UNKNOWN_LENGTH);
            assertTrue(name + ": header v2 flag", header.isHeaderV2());
            assertEquals(name + ": header size", 164, header.getHeaderSize());
            assertEquals(name + ": header height", vector.get("fields").get("m_height").asInt(),
                    header.getHeaderHeight());
            assertEquals(name + ": block hash via Block", vector.get("block_hash").asText(),
                    header.getHashAsString());
            assertEquals(name + ": serialized round trip", vector.get("serialized").asText(),
                    Utils.HEX.encode(header.bitcoinSerialize()));
        }
    }
}

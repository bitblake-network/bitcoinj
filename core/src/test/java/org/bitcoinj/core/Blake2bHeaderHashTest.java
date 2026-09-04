package org.bitcoinj.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bitcoinj.params.MainNetParams;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
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

package org.bitcoinj.core;

import org.bitcoinj.params.MainNetBlake2bParams;
import org.bitcoinj.params.TestNet4Params;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import static org.junit.Assert.fail;

/**
 * Full-chain header validator for the BLAKE2b networks, driven by a header dump
 * produced from a node:
 *
 * <pre>
 *   bitcoin-cli getblockhash &lt;h&gt; ; bitcoin-cli getblockheader &lt;hash&gt; false
 *   # one line per header, forward order (height ascending):
 *   #   "&lt;height&gt; &lt;raw header hex&gt;"
 * </pre>
 *
 * Enable with environment variables (the test skips when unset):
 *
 * <pre>
 *   BITCOINJ_HEADER_DUMP=/path/headers.txt
 *   BITCOINJ_NETWORK=testnet4|mainnet-blake2b
 * </pre>
 *
 * For every header it checks, with the real fork params:
 * <ul>
 *   <li>parse (80-byte v1 / 164-byte v2 per the version flag);</li>
 *   <li>prev-hash chaining and height continuity;</li>
 *   <li>header format vs the activation height (v1 below, v2 at/after);</li>
 *   <li>the committed v2 height field;</li>
 *   <li>proof of work (SHA-256d pre-fork / BLAKE2b post-fork hash vs target);</li>
 *   <li>difficulty transitions (testnet4 min-difficulty + BIP94, mainnet strict,
 *       one-off BLAKE2b target shift) via {@code NetworkParameters.checkDifficultyTransitions}.
 *       Where the dump does not contain enough ancestry the difficulty check is
 *       reported as skipped instead of failed.</li>
 * </ul>
 */
public class HeaderChainValidatorTest {

    @Test
    public void validateDump() throws Exception {
        String path = System.getenv("BITCOINJ_HEADER_DUMP");
        Assume.assumeTrue("BITCOINJ_HEADER_DUMP not set; skipping full-chain header validation",
                path != null && new File(path).isFile());

        String network = System.getenv("BITCOINJ_NETWORK");
        NetworkParameters params;
        if ("mainnet-blake2b".equalsIgnoreCase(network)) {
            params = MainNetBlake2bParams.get();
        } else if (network == null || "testnet4".equalsIgnoreCase(network)) {
            params = TestNet4Params.get();
        } else {
            throw new IllegalArgumentException("unknown BITCOINJ_NETWORK: " + network);
        }

        BitcoinSerializer serializer = params.getSerializer(false);
        MemoryBlockStore store = new MemoryBlockStore(params);
        // Genesis anchor, so difficulty walk-backs that reach the chain root can resolve.
        store.put(new StoredBlock(params.getGenesisBlock(), params.getGenesisBlock().getWork(), 0));

        int validated = 0;
        int difficultyChecked = 0;
        int difficultySkipped = 0;
        int expectedHeight = -1;
        int firstHeight = -1;
        Sha256Hash prevHash = null;
        StoredBlock storedPrev = null;

        StringBuilder failures = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+", 2);
                if (parts.length != 2) {
                    failures.append("bad line: ").append(line).append('\n');
                    break;
                }
                int height;
                try {
                    height = Integer.parseInt(parts[0]);
                } catch (NumberFormatException e) {
                    failures.append("bad height in line: ").append(line).append('\n');
                    break;
                }
                if (firstHeight < 0) {
                    firstHeight = height;
                }
                byte[] raw = Utils.HEX.decode(parts[1].trim());
                Block header;
                try {
                    header = serializer.makeBlock(raw, 0, Message.UNKNOWN_LENGTH);
                } catch (ProtocolException e) {
                    failures.append("parse failed at height ").append(height).append(": ").append(e.getMessage()).append('\n');
                    break;
                }
                if (header.hasTransactions()) {
                    failures.append("line at height ").append(height).append(" is not a header-only block\n");
                    break;
                }
                if (expectedHeight >= 0 && height != expectedHeight) {
                    failures.append("height continuity: expected ").append(expectedHeight)
                            .append(" got ").append(height).append('\n');
                    break;
                }
                if (prevHash != null && !header.getPrevBlockHash().equals(prevHash)) {
                    failures.append("prev-hash link broken at height ").append(height)
                            .append(": expected ").append(prevHash).append(" got ")
                            .append(header.getPrevBlockHash()).append('\n');
                    break;
                }
                boolean expectV2 = params.isBlake2bActiveAt(height);
                if (expectV2 != header.isHeaderV2()) {
                    failures.append("header format at height ").append(height)
                            .append(": expected v").append(expectV2 ? 2 : 1)
                            .append(" got v").append(header.isHeaderV2() ? 2 : 1).append('\n');
                    break;
                }
                if (header.isHeaderV2() && header.getHeaderHeight() != height) {
                    failures.append("v2 committed height at ").append(height)
                            .append(" is ").append(header.getHeaderHeight()).append('\n');
                    break;
                }
                try {
                    if (!header.checkProofOfWork(false)) {
                        failures.append("proof of work failed at height ").append(height).append('\n');
                        break;
                    }
                } catch (VerificationException e) {
                    failures.append("pow/verification at height ").append(height).append(": ")
                            .append(e.getMessage()).append('\n');
                    break;
                }
                if (storedPrev != null) {
                    // A 2016-boundary check walks back to the first block of the period that
                    // just ended (storedPrev.getHeight() - INTERVAL + 1). If the dump does not
                    // reach that far, skip rather than fail: the ancestry is simply missing.
                    boolean atTransition = (storedPrev.getHeight() + 1) % NetworkParameters.INTERVAL == 0;
                    boolean ancestryComplete = !atTransition
                            || (long) storedPrev.getHeight() - NetworkParameters.INTERVAL + 1 >= firstHeight;
                    if (!ancestryComplete) {
                        difficultySkipped++;
                    } else {
                        try {
                            params.checkDifficultyTransitions(storedPrev, header, store);
                            difficultyChecked++;
                        } catch (BlockStoreException e) {
                            // Not enough ancestry for e.g. a min-difficulty walk-back.
                            difficultySkipped++;
                        } catch (VerificationException e) {
                            failures.append("difficulty transition at height ").append(height).append(": ")
                                    .append(e.getMessage()).append('\n');
                            break;
                        }
                    }
                }
                try {
                    StoredBlock sb = new StoredBlock(header, header.getWork(), height);
                    store.put(sb);
                    storedPrev = sb;
                } catch (VerificationException e) {
                    failures.append("work at height ").append(height).append(": ").append(e.getMessage()).append('\n');
                    break;
                }
                prevHash = header.getHash();
                expectedHeight = height + 1;
                validated++;
            }
        }

        System.out.println("=== HeaderChainValidator [" + network + "] " + path + " ===");
        System.out.println("validated headers: " + validated
                + " | difficulty checked: " + difficultyChecked
                + " | difficulty skipped (ancestry gap): " + difficultySkipped);
        if (validated > 0 && storedPrev != null) {
            System.out.println("tip height: " + (storedPrev.getHeight()) + " tip hash: " + storedPrev.getHeader().getHashAsString());
        }
        if (failures.length() > 0) {
            System.out.println("FAILED at validated=" + validated + ":");
            System.out.print(failures);
            fail("header chain validation failed at height " + validated);
        }
        System.out.println("RESULT: PASS");
    }
}

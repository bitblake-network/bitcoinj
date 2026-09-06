/*
 * Copyright 2013 Google Inc.
 * Copyright 2018 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.params.UnitTestParams;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Stopwatch;

public class SPVBlockStoreTest {
    private static NetworkParameters UNITTEST;
    private static NetworkParameters TESTNET;
    private File blockStoreFile;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Utils.resetMocking();
        UNITTEST = UnitTestParams.get();
        TESTNET = TestNet3Params.get();
    }

    @Before
    public void setup() throws Exception {
        blockStoreFile = File.createTempFile("spvblockstore", null);
        blockStoreFile.delete();
        blockStoreFile.deleteOnExit();
    }

    @Test
    public void basics() throws Exception {
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile);

        Address to = LegacyAddress.fromKey(UNITTEST, new ECKey());
        // Check the first block in a new store is the genesis block.
        StoredBlock genesis = store.getChainHead();
        assertEquals(UNITTEST.getGenesisBlock(), genesis.getHeader());
        assertEquals(0, genesis.getHeight());

        // Build a new block.
        StoredBlock b1 = genesis.build(genesis.getHeader().createNextBlock(to).cloneAsHeader());
        store.put(b1);
        store.setChainHead(b1);
        store.close();

        // Check we can get it back out again if we rebuild the store object.
        store = new SPVBlockStore(UNITTEST, blockStoreFile);
        StoredBlock b2 = store.get(b1.getHeader().getHash());
        assertEquals(b1, b2);
        // Check the chain head was stored correctly also.
        StoredBlock chainHead = store.getChainHead();
        assertEquals(b1, chainHead);
        store.close();
    }

    @Test(expected = BlockStoreException.class)
    public void twoStores_onSameFile() throws Exception {
        new SPVBlockStore(UNITTEST, blockStoreFile);
        new SPVBlockStore(UNITTEST, blockStoreFile);
    }

    @Test
    public void twoStores_butSequentially() throws Exception {
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile);
        store.close();
        store = new SPVBlockStore(UNITTEST, blockStoreFile);
    }

    @Test(expected = BlockStoreException.class)
    public void twoStores_sequentially_butMismatchingCapacity() throws Exception {
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile, 10, false);
        store.close();
        store = new SPVBlockStore(UNITTEST, blockStoreFile, 20, false);
    }

    @Test
    public void twoStores_sequentially_grow() throws Exception {
        Address to = LegacyAddress.fromKey(UNITTEST, new ECKey());
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile, 10, true);
        final StoredBlock block0 = store.getChainHead();
        final StoredBlock block1 = block0.build(block0.getHeader().createNextBlock(to).cloneAsHeader());
        store.put(block1);
        final StoredBlock block2 = block1.build(block1.getHeader().createNextBlock(to).cloneAsHeader());
        store.put(block2);
        store.setChainHead(block2);
        store.close();

        store = new SPVBlockStore(UNITTEST, blockStoreFile, 20, true);
        final StoredBlock read2 = store.getChainHead();
        assertEquals(block2, read2);
        final StoredBlock read1 = read2.getPrev(store);
        assertEquals(block1, read1);
        final StoredBlock read0 = read1.getPrev(store);
        assertEquals(block0, read0);
        store.close();
        assertEquals(SPVBlockStore.getFileSize(20), blockStoreFile.length());
    }

    @Test(expected = BlockStoreException.class)
    public void twoStores_sequentially_shrink() throws Exception {
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile, 20, true);
        store.close();
        store = new SPVBlockStore(UNITTEST, blockStoreFile, 10, true);
    }

    @Test
    public void performanceTest() throws BlockStoreException {
        // On slow machines, this test could fail. Then either add @Ignore or adapt the threshold and please report to
        // us.
        final int ITERATIONS = 100000;
        final long THRESHOLD_MS = 5000;
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile);
        Stopwatch watch = Stopwatch.createStarted();
        for (int i = 0; i < ITERATIONS; i++) {
            // Using i as the nonce so that the block hashes are different.
            Block block = new Block(UNITTEST, 0, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH, 0, 0, i,
                    Collections.<Transaction> emptyList());
            StoredBlock b = new StoredBlock(block, BigInteger.ZERO, i);
            store.put(b);
            store.setChainHead(b);
        }
        assertTrue("took " + watch + " for " + ITERATIONS + " iterations",
                watch.elapsed(TimeUnit.MILLISECONDS) < THRESHOLD_MS);
        store.close();
    }

    @Test
    public void clear() throws Exception {
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile);

        // Build a new block.
        Address to = LegacyAddress.fromKey(UNITTEST, new ECKey());
        StoredBlock genesis = store.getChainHead();
        StoredBlock b1 = genesis.build(genesis.getHeader().createNextBlock(to).cloneAsHeader());
        store.put(b1);
        store.setChainHead(b1);
        assertEquals(b1.getHeader().getHash(), store.getChainHead().getHeader().getHash());
        store.clear();
        assertNull(store.get(b1.getHeader().getHash()));
        assertEquals(UNITTEST.getGenesisBlock().getHash(), store.getChainHead().getHeader().getHash());
        store.close();
    }

    @Test
    public void oneStoreDelete() throws Exception {
        SPVBlockStore store = new SPVBlockStore(UNITTEST, blockStoreFile);
        store.close();
        boolean deleted = blockStoreFile.delete();
        if (!Utils.isWindows()) {
            // TODO: Deletion is failing on Windows
            assertTrue(deleted);
        }
    }

    @Test
    public void legacyV1FileRebuilt() throws Exception {
        // Create a legacy V1-format file. Opening it must rebuild the store from genesis for
        // 164-byte header-v2 support (V3 format); the rebuilt store starts at the genesis block.
        RandomAccessFile raf = new RandomAccessFile(blockStoreFile, "rw");
        FileChannel channel = raf.getChannel();
        ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0,
                SPVBlockStore.FILE_PROLOGUE_BYTES + SPVBlockStore.RECORD_SIZE_V1 * 3);
        buffer.put(SPVBlockStore.HEADER_MAGIC_V1); // header magic
        Block genesisBlock = TESTNET.getGenesisBlock();
        StoredBlock storedGenesisBlock = new StoredBlock(genesisBlock.cloneAsHeader(), genesisBlock.getWork(), 0);
        Sha256Hash genesisHash = storedGenesisBlock.getHeader().getHash();
        ((Buffer) buffer).position(SPVBlockStore.FILE_PROLOGUE_BYTES);
        buffer.put(genesisHash.getBytes());
        storedGenesisBlock.serializeCompact(buffer);
        buffer.putInt(4, buffer.position()); // ring cursor
        ((Buffer) buffer).position(8);
        buffer.put(genesisHash.getBytes()); // chain head
        raf.close();

        // Opening rebuilds the store in the V3 format (header slot sized for header-v2).
        SPVBlockStore store = new SPVBlockStore(TESTNET, blockStoreFile);

        // The rebuilt store starts at the same genesis block.
        assertEquals(genesisHash, store.getChainHead().getHeader().getHash());
        // Ring cursor after storing just the genesis block.
        assertEquals(SPVBlockStore.FILE_PROLOGUE_BYTES + SPVBlockStore.RECORD_SIZE_V2 * 1,
                store.getRingCursor());
        store.close();
    }

    @Test
    public void headerV2RoundTrip() throws Exception {
        // A 164-byte header-v2 block must survive an SPVBlockStore write/read cycle: the record
        // header slot is sized for header-v2. Use a real live testnet4 header-v2 block fixture.
        String hex;
        try (java.io.InputStream in = SPVBlockStoreTest.class.getResourceAsStream(
                "/org/bitcoinj/core/h150308.txt")) {
            hex = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.US_ASCII))
                    .readLine().trim();
        }
        byte[] raw = Utils.HEX.decode(hex);
        Block v2 = TESTNET.getSerializer(false).makeBlock(raw, 0, Message.UNKNOWN_LENGTH);
        assertTrue("fixture must be a header-v2 block", v2.isHeaderV2());
        assertEquals(Block.HEADER_V2_SIZE, v2.getMessageSize());

        SPVBlockStore store = new SPVBlockStore(TESTNET, blockStoreFile);
        StoredBlock genesis = store.getChainHead();
        StoredBlock stored = new StoredBlock(v2, genesis.getChainWork().add(v2.getWork()), 1);
        store.put(stored);
        store.setChainHead(stored);
        store.close();

        store = new SPVBlockStore(TESTNET, blockStoreFile);
        StoredBlock read = store.get(v2.getHash());
        assertEquals(stored, read);
        assertTrue(read.getHeader().isHeaderV2());
        assertEquals(Block.HEADER_V2_SIZE, read.getHeader().getMessageSize());
        assertEquals(v2.getHash(), read.getHeader().getHash());
        assertEquals(stored, store.getChainHead());
        store.close();
    }
}

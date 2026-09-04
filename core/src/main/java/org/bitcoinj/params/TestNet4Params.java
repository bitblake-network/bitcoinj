/*
 * Copyright 2026 The BitBlakeSwap project
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

package org.bitcoinj.params;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;

import java.util.Collections;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for Bitcoin testnet4, the network that carries the Bitcoin Knots
 * BLAKE2b hardfork ("header v2", Bitcoin Knots PR #359). BitBlakeSwap operates
 * as a client of this network.
 *
 * <p>The BLAKE2b hardfork activates at height {@link #BLAKE2B_ACTIVATION_HEIGHT}
 * (verified 2026-09-04 against the live chain: block 150307 is the last
 * SHA-256d/v1 block, block 150308 the first BLAKE2b/header-v2 block). Before
 * that height testnet4 follows the historical SHA-256d rules, including the
 * testnet minimum-difficulty rule and the BIP94 timewarp fix.
 */
public class TestNet4Params extends AbstractBitcoinNetParams {

    /** First BLAKE2b/header-v2 block on live testnet4. */
    public static final int BLAKE2B_ACTIVATION_HEIGHT = 150308;

    /** One-off difficulty target shift applied to the first post-hardfork block (2^20). */
    public static final int BLAKE2B_TARGET_SHIFT = 20;

    public static final int TESTNET4_MAJORITY_WINDOW = 100;
    public static final int TESTNET4_MAJORITY_REJECT_BLOCK_OUTDATED = 75;
    public static final int TESTNET4_MAJORITY_ENFORCE_BLOCK_UPGRADE = 51;

    /**
     * The real testnet4 genesis header (80 bytes, SHA-256d era). Its merkle root is the real
     * one committed by the chain (7aa0a7ae...); the coinbase itself is irrelevant for SPV use and
     * its unusual all-zero output script is deliberately not parsed. The header-only block hashes
     * to the same genesis id as the full block on the wire.
     */
    private static final String GENESIS_MERKLE_ROOT_HEX =
            "7aa0a7ae1e223414cb807e40cd57e667b718e42aaf9306db9102fe28912b7b4e";
    private static final long GENESIS_TIME = 1714777860L; // 03/May/2024
    private static final long GENESIS_NONCE = 393743547L;

    public TestNet4Params() {
        super();
        id = ID_TESTNET4;
        packetMagic = 0x1c163f28;
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        maxTarget = Utils.decodeCompactBits(0x1d00ffffL);
        port = 48333;
        addressHeader = 111;
        p2shHeader = 196;
        dumpedPrivateKeyHeader = 239;
        segwitAddressHrp = "tb";
        bip32HeaderP2PKHpub = 0x043587cf; // "tpub"
        bip32HeaderP2PKHpriv = 0x04358394; // "tprv"
        bip32HeaderP2WPKHpub = 0x045f1cf6; // "vpub"
        bip32HeaderP2WPKHpriv = 0x045f18bc; // "vprv"
        spendableCoinbaseDepth = 100;
        subsidyDecreaseBlockCount = 210000;

        // BLAKE2b hardfork and difficulty-policy knobs.
        blake2bHeight = BLAKE2B_ACTIVATION_HEIGHT;
        blake2bTargetShift = BLAKE2B_TARGET_SHIFT;
        powAllowMinDifficultyBlocks = true;
        enforceBip94 = true;

        majorityEnforceBlockUpgrade = TESTNET4_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = TESTNET4_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = TESTNET4_MAJORITY_WINDOW;

        genesisBlock = buildGenesis();
        String genesisHash = genesisBlock.getHashAsString();
        checkState(genesisHash.equals("00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043"),
                "testnet4 genesis hash mismatch: " + genesisHash);

        dnsSeeds = new String[] {
                "seed.testnet4.bitcoin.sprovoost.nl", // Sjors Provoost
                "seed.testnet4.wiz.biz",              // Jason Maurice
        };
    }

    private Block buildGenesis() {
        return new Block(this, Block.BLOCK_VERSION_GENESIS, Sha256Hash.ZERO_HASH,
                Sha256Hash.wrap(GENESIS_MERKLE_ROOT_HEX), GENESIS_TIME, 0x1d00ffffL, GENESIS_NONCE,
                Collections.emptyList());
    }

    private static TestNet4Params instance;

    public static synchronized TestNet4Params get() {
        if (instance == null) {
            instance = new TestNet4Params();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_TESTNET4;
    }
}

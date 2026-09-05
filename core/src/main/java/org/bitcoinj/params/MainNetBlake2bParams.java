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
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

/**
 * Parameters for the BLAKE2b hardfork of the Bitcoin mainnet (Bitcoin Knots
 * PR #359). The BLAKE2b mainnet shares the complete pre-fork history with the
 * standard SHA-256d mainnet and hard-forks at {@link #BLAKE2B_ACTIVATION_HEIGHT}
 * (live since ~August 2026; cross-checked 2026-09-05 against a mining gateway
 * producing header-v2 blocks at height ~967,955).
 *
 * <p>Unlike testnet4, mainnet has no minimum-difficulty rule and no BIP94
 * timewarp fix; only the one-off target shift at the activation block applies.
 */
public class MainNetBlake2bParams extends MainNetParams {

    /** First BLAKE2b/header-v2 block on the BLAKE2b mainnet. */
    public static final int BLAKE2B_ACTIVATION_HEIGHT = 961640;

    /**
     * One-off difficulty target shift applied to the first post-hardfork block.
     * Knots mainnet sets {@code consensus.Blake2bTargetShift = 22} in
     * {@code src/kernel/chainparams.cpp} (the testnet4 default is 20); the live
     * activation block 961640 carries nBits {@code 0x1a008d4f} = shift22 of the
     * previous block's {@code 0x1702353d} (cross-checked 2026-09-05).
     */
    public static final int BLAKE2B_TARGET_SHIFT = 22;

    public MainNetBlake2bParams() {
        super();
        id = ID_MAINNET_BLAKE2B;
        blake2bHeight = BLAKE2B_ACTIVATION_HEIGHT;
        blake2bTargetShift = BLAKE2B_TARGET_SHIFT;
        // Mainnet keeps its strict difficulty policy: no min-difficulty rule, no BIP94.
        powAllowMinDifficultyBlocks = false;
        enforceBip94 = false;
    }

    private static MainNetBlake2bParams instance;

    public static synchronized MainNetBlake2bParams get() {
        if (instance == null) {
            instance = new MainNetBlake2bParams();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return PAYMENT_PROTOCOL_ID_MAINNET_BLAKE2B;
    }

    /**
     * Mainnet difficulty-transition validation with the one-off BLAKE2b target
     * shift at the activation block, mirroring Bitcoin Core's
     * {@code GetNextWorkRequired} (src/pow.cpp, Knots PR #359): the shift is
     * applied on top of the normally expected difficulty of the first
     * post-hardfork block. On the live mainnet the activation height is not on
     * a 2016-boundary (961640 % 2016 == 8), so the expected base is the ongoing
     * period's target, i.e. the previous block's nBits; the inherited
     * {@code MainNetParams} check would otherwise reject the change as an
     * "unexpected change in difficulty". All other headers (pre-fork and
     * post-fork) keep the standard strict mainnet rules.
     */
    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final Block nextBlock,
            final BlockStore blockStore) throws VerificationException, BlockStoreException {
        if (storedPrev.getHeight() + 1 == getBlake2bHeight()) {
            long expected = applyBlake2bTargetShift(storedPrev.getHeader().getDifficultyTarget(),
                    getMaxTarget(), getBlake2bTargetShift());
            if (nextBlock.getDifficultyTarget() != expected) {
                throw new VerificationException("BLAKE2b activation difficulty shift mismatch at height "
                        + (storedPrev.getHeight() + 1) + ": "
                        + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " + Long.toHexString(expected));
            }
            return;
        }
        super.checkDifficultyTransitions(storedPrev, nextBlock, blockStore);
    }
}

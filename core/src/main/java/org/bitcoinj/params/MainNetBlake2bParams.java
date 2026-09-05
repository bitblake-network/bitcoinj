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

    /** One-off difficulty target shift applied to the first post-hardfork block (2^20). */
    public static final int BLAKE2B_TARGET_SHIFT = 20;

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
}

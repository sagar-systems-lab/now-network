import { PublicKey } from "npm:@solana/web3.js@1.98.4";

export const TOKEN_PROGRAM_ID =
  "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
export const ASSOCIATED_TOKEN_PROGRAM_ID =
  "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";
export const SYSTEM_PROGRAM_ID = "11111111111111111111111111111111";

const textEncoder = new TextEncoder();

function pda(programId: PublicKey, seeds: readonly Uint8Array[]): PublicKey {
  return PublicKey.findProgramAddressSync(
    seeds.map((seed) => Uint8Array.from(seed)),
    programId,
  )[0];
}

export type RefreshChainAddresses = {
  configAddress: string;
  refreshAddress: string;
  contributionAddress: string;
  vaultTokenAccount: string;
};

export function deriveRefreshChainAddresses(input: {
  programId: string;
  rewardMint: string;
  creatorWallet: string;
  chainRefreshId: Uint8Array;
}): RefreshChainAddresses {
  if (input.chainRefreshId.length !== 32) {
    throw new RangeError("chainRefreshId must be 32 bytes");
  }

  const program = new PublicKey(input.programId);
  const rewardMint = new PublicKey(input.rewardMint);
  const creator = new PublicKey(input.creatorWallet);
  const tokenProgram = new PublicKey(TOKEN_PROGRAM_ID);
  const associatedTokenProgram = new PublicKey(ASSOCIATED_TOKEN_PROGRAM_ID);

  const config = pda(program, [textEncoder.encode("config")]);
  const refresh = pda(program, [
    textEncoder.encode("refresh"),
    input.chainRefreshId,
  ]);
  const contribution = pda(program, [
    textEncoder.encode("contribution"),
    refresh.toBytes(),
    creator.toBytes(),
  ]);
  const vault = pda(associatedTokenProgram, [
    refresh.toBytes(),
    tokenProgram.toBytes(),
    rewardMint.toBytes(),
  ]);

  return {
    configAddress: config.toBase58(),
    refreshAddress: refresh.toBase58(),
    contributionAddress: contribution.toBase58(),
    vaultTokenAccount: vault.toBase58(),
  };
}

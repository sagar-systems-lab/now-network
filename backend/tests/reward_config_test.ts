import { configuredRewardMint, DEVNET_REWARD_MINT } from "../src/reward-config.ts";

Deno.test("devnet demo mint matches the Android connected build without overriding explicit config", async () => {
  const gradle = await Deno.readTextFile(
    new URL("../../apps/android/build.gradle.kts", import.meta.url),
  );
  if (
    !gradle.includes(`"${DEVNET_REWARD_MINT}"`) ||
    configuredRewardMint("devnet", undefined) !== DEVNET_REWARD_MINT ||
    configuredRewardMint("devnet", "  other-mint  ") !== "other-mint"
  ) {
    throw new Error("Devnet mint configuration is inconsistent");
  }
  for (const cluster of ["mainnet-beta", "testnet"]) {
    try {
      configuredRewardMint(cluster, undefined);
      throw new Error("Missing non-devnet reward mint was accepted");
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes("NOW_REWARD_MINT")) {
        throw error;
      }
    }
  }
});

export const DEVNET_REWARD_MINT = "2FxQUpesqczzdBzsumfUSnpkj6Q9QYo9dJCpGNq9mi6F";

export function configuredRewardMint(cluster: string, configured: string | undefined): string {
  const mint = configured?.trim();
  if (mint) return mint;
  if (cluster === "devnet") return DEVNET_REWARD_MINT;
  throw new Error("missing required environment variable NOW_REWARD_MINT");
}

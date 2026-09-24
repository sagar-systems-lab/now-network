export class ApiFault extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly safeToRetry = false,
    readonly retryAfterMs: number | null = null,
  ) {
    super(message);
    this.name = "ApiFault";
  }
}

export function asApiFault(error: unknown): ApiFault {
  if (error instanceof ApiFault) return error;
  return new ApiFault(500, "INTERNAL_ERROR", "The request could not be completed.", true);
}

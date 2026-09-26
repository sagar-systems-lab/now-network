export type RealtimePublishSummary = {
  published: number;
};

export interface RealtimeOutboxRepository {
  publishBatch(input: {
    observedAt: Date;
    limit: number;
  }): Promise<RealtimePublishSummary>;
}

export class RealtimePublisher {
  constructor(
    private readonly repository: RealtimeOutboxRepository,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async runOnce(limit = 16): Promise<RealtimePublishSummary> {
    const bounded = Math.max(1, Math.min(64, Math.trunc(limit)));
    return await this.repository.publishBatch({
      observedAt: this.now(),
      limit: bounded,
    });
  }
}
